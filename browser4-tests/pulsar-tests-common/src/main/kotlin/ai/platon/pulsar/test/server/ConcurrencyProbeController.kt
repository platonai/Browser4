package ai.platon.pulsar.test.server

import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Test-only fixture that makes *collection parallelism* observable from the
 * server side.
 *
 * A crawl that claims to use several browser tabs can only be believed if the
 * target site saw several requests at the same time — a per-page timing measure
 * cannot tell "four tabs in parallel" from "four tabs one after another, each
 * fast".  So this endpoint holds each request open for a caller-chosen time and
 * records how many were in flight at once:
 *
 *  * `GET /__probe/slow?delayMs=1500&id=<tag>` — an HTML page that occupies a
 *    worker for `delayMs` before answering, folded into the high-water mark;
 *  * `GET /__probe/stats` — `maxConcurrent` / `totalRequests` / `inFlight`, plus the
 *    per-id `flakyHits`;
 *  * `GET /__probe/flaky[/{id}]?failures=N&status=500` — a page whose first N requests
 *    fail to load, for the crawl's delivery retry;
 *  * `GET /__probe/flaky-hub[/{id}]?links=N&failures=N` — a portal whose links are
 *    `/flaky` pages;
 *  * `POST /__probe/reset` — zero the counters so each test measures only its
 *    own crawl.
 *
 * Only `/slow` feeds the parallelism measurement, so a browser's own side requests
 * (`robots.txt`, favicon, preflights) can never inflate it; `/flaky` keeps its own
 * per-id hit counts, which are what a retry test reads.
 *
 * Serving the page from a Tomcat worker thread is deliberate: the thread is
 * blocked for the whole delay, so `maxConcurrent` measures requests that really
 * overlapped rather than ones the server merely accepted.
 */
@RestController
@RequestMapping("/__probe")
class ConcurrencyProbeController {

    private val inFlight = AtomicInteger()
    private val maxConcurrent = AtomicInteger()
    private val totalRequests = AtomicInteger()

    /** How many times each [flaky] page has been requested, keyed by id. */
    private val flakyHits = ConcurrentHashMap<String, AtomicInteger>()

    private companion object {
        /** A hub that linked to more than this would take minutes to drain. */
        const val MAX_HUB_LINKS = 32
    }

    /**
     * A slow, well-formed HTML page.
     *
     * The id lives in the *path* (`/__probe/slow/<id>`), not only in the query,
     * so two probe pages are two genuinely different URLs — a crawler that
     * normalizes query strings away would otherwise collapse all of them onto
     * one page and the test would measure nothing.
     *
     * @param delayMs how long to hold the request open; keeps the tab busy long
     *   enough that a sequential client cannot produce any overlap at all.
     * @param id an opaque tag echoed into the title, so a crawl result can be
     *   checked against the page it claims to have collected.
     */
    @GetMapping(value = ["/slow", "/slow/{id}"], produces = ["text/html"])
    fun slow(
        @PathVariable(name = "id", required = false) id: String?,
        @RequestParam(name = "delayMs", defaultValue = "0") delayMs: Long,
    ): String {
        val concurrent = inFlight.incrementAndGet()
        maxConcurrent.accumulateAndGet(concurrent) { a, b -> maxOf(a, b) }
        totalRequests.incrementAndGet()
        try {
            if (delayMs > 0) {
                Thread.sleep(delayMs)
            }
            return slowPage(id ?: "probe", delayMs)
        } finally {
            inFlight.decrementAndGet()
        }
    }

    /** The high-water mark of concurrently served `/slow` requests. */
    @GetMapping("/stats")
    fun stats(): Map<String, Any> = mapOf(
        "maxConcurrent" to maxConcurrent.get(),
        "totalRequests" to totalRequests.get(),
        "inFlight" to inFlight.get(),
        // How many times each flaky page was asked for: a delivery retry is only
        // observable from the site's side, since both loads are one URL to the crawl.
        "flakyHits" to flakyHits.mapValues { it.value.get() },
    )

    /**
     * A page whose first [failures] requests fail, and which answers normally afterwards.
     *
     * A load failure has to be reproducible from the server side for the crawl's handling of one
     * to be observable, and two runs through this endpoint taught it what a failing answer may not
     * be:
     *
     *  * **not an empty `200`**: the browser navigates to it and synthesizes
     *    `<html><head></head><body></body></html>` — 39 bytes, measured — which the crawl reads as
     *    a delivered document rather than as a page it never received.  The first version of this
     *    endpoint answered that way, and both retry tests failed with `expected: <2> but was: <1>`
     *    because no retry was ever due.
     *  * **and neither a `500` nor a `404` reaches the crawl as a *terminal* failure**: a
     *    browser-driven load that fails lands on the browser's error page, so the engine
     *    classifies it as retryable (`ProtocolStatus.retry`, `1601`, `rs: BrowserErrorPageException`
     *    in the log) and schedules the next load itself 37-55 s later
     *    (`StreamingTaskRunner.Task: Trying 1th ... later`).  Both statuses were tried; both came
     *    back the same way.
     *
     * So what this endpoint can prove is the *outcome*: a page that failed its first load is
     * delivered anyway (by the engine's scheduled retry) and is never reported lost.  The crawl's
     * own retry only ever sees the failures the engine leaves terminal, and is unit-tested there.
     *
     * Hits are counted per id so a test can see that the page was requested twice and that the
     * second request is what produced the row.
     *
     * @param failures how many requests fail (0 makes it an ordinary page).
     * @param status the status code those requests are answered with.
     */
    @GetMapping(value = ["/flaky", "/flaky/{id}"], produces = ["text/html"])
    fun flaky(
        @PathVariable(name = "id", required = false) id: String?,
        @RequestParam(name = "failures", defaultValue = "1") failures: Int,
        @RequestParam(name = "status", defaultValue = "500") status: Int,
    ): ResponseEntity<String> {
        val tag = id ?: "probe"
        val hits = flakyHits.computeIfAbsent(tag) { AtomicInteger() }.incrementAndGet()
        return if (hits <= failures.coerceAtLeast(0)) {
            ResponseEntity.status(status).contentType(MediaType.TEXT_HTML).body("")
        } else {
            ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(flakyPage(tag, hits))
        }
    }

    /**
     * A portal page whose only out-links are [flaky] pages.
     *
     * The portal itself always answers, and always with content: what a retry test needs is
     * a *discovered* page that fails to load at least once, and a portal that failed would
     * take the "0 out-links" diagnostic path instead (and, at depth >= 2, would never get
     * to discover anything).
     *
     * @param links how many flaky pages the hub links to (1..32).
     * @param failures how many of each linked page's first requests fail, handed to [flaky]
     *   through the href.
     */
    @GetMapping(value = ["/flaky-hub", "/flaky-hub/{id}"], produces = ["text/html"])
    fun flakyHub(
        @PathVariable(name = "id", required = false) id: String?,
        @RequestParam(name = "links", defaultValue = "2") links: Int,
        @RequestParam(name = "failures", defaultValue = "1") failures: Int,
    ): String = hubPage(id ?: "probe", links) { tag, index -> "/__probe/flaky/$tag-$index?failures=$failures" }

    /**
     * A portal page whose only out-links are [slow] pages.
     *
     * The in-flight progress view of a crawl is written by every round while it
     * runs, so observing it needs a crawl that lasts long enough for two rounds
     * to publish at the same time — a hub whose links each hold the server open
     * for `delayMs` does exactly that.  Each link is a distinct path with a
     * distinct title, so a collected row can still be checked against the page
     * it claims to have come from.
     *
     * The hub itself answers immediately: the time has to be spent on the
     * discovered links, otherwise only the seed round would be observable.
     *
     * @param links how many slow pages the hub links to (1..32).
     * @param delayMs the hold time handed to every linked [slow] page.
     */
    @GetMapping(value = ["/hub", "/hub/{id}"], produces = ["text/html"])
    fun hub(
        @PathVariable(name = "id", required = false) id: String?,
        @RequestParam(name = "links", defaultValue = "4") links: Int,
        @RequestParam(name = "delayMs", defaultValue = "1000") delayMs: Long,
    ): String = hubPage(id ?: "probe", links) { tag, index -> "/__probe/slow/$tag-$index?delayMs=$delayMs" }

    /** The hub markup, with one anchor per linked page and its href left to [href]. */
    private fun hubPage(tag: String, links: Int, href: (String, Int) -> String): String {
        val anchors = (1..links.coerceIn(1, MAX_HUB_LINKS)).joinToString("\n                ") { index ->
            """<a class="probe-link" href="${href(tag, index)}">Probe $tag-$index</a>"""
        }
        return """
        <!DOCTYPE html>
        <html lang="en">
        <head>
            <meta charset="UTF-8">
            <title>Probe hub $tag</title>
        </head>
        <body>
            <h1>Probe hub $tag</h1>
            <p id="probe-hub-id">$tag</p>
            <div class="links">
                $anchors
            </div>
        </body>
        </html>
        """.trimIndent()
    }

    /** Zero the counters, so the next crawl is measured on its own. */
    @PostMapping("/reset")
    fun reset(): Map<String, Any> {
        inFlight.set(0)
        maxConcurrent.set(0)
        totalRequests.set(0)
        flakyHits.clear()
        return stats()
    }

    private fun flakyPage(id: String, hits: Int) = """
        <!DOCTYPE html>
        <html lang="en">
        <head>
            <meta charset="UTF-8">
            <title>Flaky probe $id</title>
        </head>
        <body>
            <h1>Flaky probe $id</h1>
            <p id="probe-id">$id</p>
            <p id="probe-hits">$hits</p>
        </body>
        </html>
    """.trimIndent()

    private fun slowPage(id: String, delayMs: Long) = """
        <!DOCTYPE html>
        <html lang="en">
        <head>
            <meta charset="UTF-8">
            <title>Probe $id</title>
        </head>
        <body>
            <h1>Probe $id</h1>
            <p id="probe-id">$id</p>
            <p id="probe-delay">$delayMs</p>
            <div class="content">
                <p>This fixture is held open for $delayMs ms so that a parallel
                crawl shows up as concurrent requests in /__probe/stats.</p>
            </div>
        </body>
        </html>
    """.trimIndent()
}
