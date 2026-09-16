package ai.platon.pulsar.test.server

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
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
 *  * `GET /__probe/stats` — `maxConcurrent` / `totalRequests` / `inFlight`;
 *  * `POST /__probe/reset` — zero the counters so each test measures only its
 *    own crawl.
 *
 * Only `/slow` is counted, so a browser's own side requests (`robots.txt`,
 * favicon, preflights) can never inflate the parallelism measurement.
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
    )

    /** Zero the counters, so the next crawl is measured on its own. */
    @PostMapping("/reset")
    fun reset(): Map<String, Any> {
        inFlight.set(0)
        maxConcurrent.set(0)
        totalRequests.set(0)
        return stats()
    }

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
