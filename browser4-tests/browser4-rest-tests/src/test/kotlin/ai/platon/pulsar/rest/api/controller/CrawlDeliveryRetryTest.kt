package ai.platon.pulsar.rest.api.controller

import ai.platon.pulsar.rest.api.service.crawl.CrawlResponse
import ai.platon.pulsar.rest.api.service.crawl.CrawlStatus
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.client.expectBody
import java.util.UUID

/**
 * A page that fails its **first** load is still delivered, and is never reported as lost
 * (section 18.5 item 2).
 *
 * The failure cannot be produced with a static fixture: the page has to fail once and answer
 * afterwards, which is what `ConcurrencyProbeController`'s `/flaky` endpoint does (a failing
 * status for the first `failures` requests, the real page afterwards, hits counted per id).
 * The site's own count is the only honest witness — to the crawl both requests are one URL —
 * so it is what tells "asked twice, the second one landed" from "asked once".
 *
 * Which mechanism lands the second request is worth being precise about, because the crawl has
 * two: the engine schedules its own retry when it classifies a failure as retryable (that is
 * what these fixtures produce — `1601 Retry`, `Trying 1th ... later`, see the probe), and the
 * crawl adds one more load for the failures the engine leaves terminal
 * (`CrawlSupport.resolveDeliveryAttempt`, unit-tested in `CrawlSupportTest` and pinned by
 * `CrawlLedgerTest`).  What this class pins is the *contract* both have to satisfy: a failed
 * first load must end in the delivered page and never in a reported loss — the crawl must not
 * settle a URL that is still going to arrive.
 *
 * Two paths are covered because they submit their pages independently: a depth-1 round hands its
 * out-links to the session itself (`crawlDepth1`), while a depth-2 round submits the children its
 * parse handler discovers (`crawlDepthN`).
 *
 * Tagged [IntegrationTest] and [Heavy].  The class is one of the crawl acceptance
 * classes whose honest runtime is minutes (480 s at best, 1045 s on a slow runner),
 * and `Heavy` is the taxonomy's own answer for >30 s / resource-hungry suites:
 * nightly and resource-isolated runs, not the release gate, which has to stay
 * inside its own time limit.  See docs-dev/copilot/ci-stabilization-4.13.x.md §33.
 */
@Tag("IntegrationTest")
@Tag("Heavy")
class CrawlDeliveryRetryTest : CrawlTestBase() {

    /** Per-test ids, so a run cannot be answered by the page store of an earlier one. */
    private val runTag: String by lazy { "retry-" + UUID.randomUUID().toString().take(8) }

    @Test
    @DisplayName("depth-1: an out-link that fails its first load is still delivered")
    fun testOutLinkThatFailedItsFirstLoadIsDelivered() {
        resetProbe()
        val links = 2
        val seed = "$probeBase/flaky-hub/$runTag?links=$links&failures=1"

        val response = runCrawl(seed, depth = 1, args = "-outLink \"a.probe-link\" -topLinks $links -refresh")

        assertEquals(CrawlStatus.OK, response.status, "the crawl completed: ${response.diagnostic ?: response.error}")
        assertEquals(links, response.pagesFound, "both children were delivered")
        assertTrue(
            response.failedPages.isNullOrEmpty(),
            "nothing was lost: ${response.failedPages?.map { "${it.url} (${it.reason})" }}"
        )
        assertDeliveredAfterOneFailedLoad(response, children = links, rows = links)
    }

    @Test
    @DisplayName("depth-2: a page the parse handler discovered that fails its first load is still delivered")
    fun testDiscoveredChildThatFailedItsFirstLoadIsDelivered() {
        resetProbe()
        val links = 1
        // The portal itself always answers; only the child it links to fails its first load.
        val seed = "$probeBase/flaky-hub/$runTag-hub?links=$links&failures=1"

        val response = runCrawl(seed, depth = 2, args = "-outLink \"a.probe-link\" -topLinks $links -refresh")

        assertEquals(CrawlStatus.OK, response.status, "the crawl completed: ${response.diagnostic ?: response.error}")
        assertEquals(2, response.pagesFound, "the portal and its one child")
        assertTrue(
            response.failedPages.isNullOrEmpty(),
            "nothing was lost: ${response.failedPages?.map { "${it.url} (${it.reason})" }}"
        )
        assertDeliveredAfterOneFailedLoad(response, children = links, rows = links + 1)
    }

    /**
     * The site saw each flaky child exactly twice — the failed load and the one that landed — and
     * the row the crawl reports for it can only have come from the second, because a load that
     * delivered nothing produces no row at all.
     *
     * @param children how many flaky pages the round submitted.
     * @param rows how many rows the round records in total (a depth-2 round also records the
     *   portal it started from).
     */
    private fun assertDeliveredAfterOneFailedLoad(response: CrawlResponse, children: Int, rows: Int) {
        val hits = flakyHits()
        val requested = hits.filterKeys { it.startsWith(runTag) }
        assertEquals(
            children, requested.size,
            "the probe recorded $requested — every child of this run must have been requested"
        )
        requested.forEach { (id, count) ->
            assertEquals(2, count, "child '$id' should have been requested twice (the failed load, then the retry)")
        }

        val pages = requireNotNull(response.pages) { "the crawl reported no pages" }
        assertEquals(rows, pages.size, "unexpected rows: ${pages.map { it.url }}")
        val deliveredRows = pages.filter { it.title?.startsWith("Flaky probe") == true }
        assertEquals(
            children, deliveredRows.size,
            "every child's row must carry the page the retry delivered: " +
                "${pages.map { "${it.url} -> ${it.title}" }}"
        )
    }

    /**
     * GET /__probe/stats -> the per-id flaky hit counts.
     *
     * The one nested shape in the probe's body, so it is read here rather than through
     * [probeStats] — which reads the flat numeric counters and skips everything else.
     */
    private fun flakyHits(): Map<String, Int> {
        val raw = client.get().uri("$probeBase/stats")
            .exchange()
            .expectStatus().is2xxSuccessful
            .expectBody<String>()
            .returnResult()
            .responseBody
        val body = requireNotNull(raw) { "the probe stats body was empty" }
        val stats = jacksonObjectMapper().readValue(body, Map::class.java)
        val hits = stats["flakyHits"] as? Map<*, *> ?: emptyMap<Any?, Any?>()
        return hits.entries.associate { (key, value) -> key.toString() to ((value as? Number)?.toInt() ?: 0) }
    }

    private fun runCrawl(seed: String, depth: Int, args: String): CrawlResponse =
        waitForTerminal(submitCrawl(seed, depth, args))

    private fun submitCrawl(seed: String, depth: Int, args: String): String {
        val body = jacksonObjectMapper().writeValueAsString(
            mapOf("url" to seed, "args" to args, "depth" to depth)
        )
        val rawTaskId = client.post().uri("/api/crawl")
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
            .exchange()
            .expectStatus().is2xxSuccessful
            .expectBody<String>()
            .returnResult()
            .responseBody
        val taskId = rawTaskId?.trim()?.removeSurrounding("\"")
        check(!taskId.isNullOrBlank()) { "Expected non-blank crawl task id but got: $rawTaskId" }
        return taskId
    }
}
