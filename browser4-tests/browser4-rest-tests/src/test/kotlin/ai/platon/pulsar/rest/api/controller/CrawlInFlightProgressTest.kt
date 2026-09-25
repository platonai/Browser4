package ai.platon.pulsar.rest.api.controller

import ai.platon.pulsar.rest.api.service.crawl.CrawlResponse
import ai.platon.pulsar.rest.api.service.crawl.CrawlStatus
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.client.expectBody
import java.time.Instant

/**
 * What a *poller* sees while a multi-seed crawl runs.
 *
 * Every round publishes into the same in-memory record: `recordSeedProgress`
 * when a seed settles, and `publishPages` from each page a running round
 * collects.  The derived progress view therefore has to be
 *
 *  * **aggregated** — the pages of every round that has published so far, not
 *    just of the round that published last; and
 *  * **monotone** — never smaller than the previous sample, and never larger
 *    than the record the crawl terminates with.
 *
 * The crawl is built so both are observable: several seeds, each one a hub whose
 * out-links hold the server open ([ConcurrencyProbeController]), so the rounds
 * last seconds and publish many times.  Only the *discovered* pages become rows
 * — a depth-1 round records its out-links, not the portal it discovered them on —
 * so a three-seed run collects `3 x linksPerHub` pages.  The sequential run alone
 * is enough to expose a per-round view (seed A settles at 3, then seed B's first
 * publish reports 1); the two-seed run adds the case where two rounds publish at
 * the same time.
 *
 * Tagged [IntegrationTest]: needs a real browser, the driver pool and the mock
 * site.
 */
@Tag("IntegrationTest")
class CrawlInFlightProgressTest : CrawlTestBase() {

    /** Links per hub, and how long each of them holds the server open. */
    private val linksPerHub = 3
    private val linkDelayMs = 600L

    /** One poll of the task record. */
    private data class Sample(
        val terminal: Boolean,
        val status: String,
        val pagesFound: Int,
        val pagesExpected: Int,
        val failed: Int,
        val linksDiscovered: Int,
    ) {
        override fun toString(): String =
            "pages=$pagesFound/$pagesExpected links=$linksDiscovered failed=$failed status=$status"
    }

    @Test
    @DisplayName("a settled seed's pages stay visible while the next seed starts publishing")
    fun testSequentialSeedsKeepTheCollectedTotal() {
        // The pathological sequence this pins: seed A settles, `recordSeedProgress`
        // publishes the aggregate (its links), then seed B's round publishes its own
        // first page — and the count a poller sees falls back to 1.
        val seeds = hubSeeds(listOf("alpha", "bravo", "charlie"))

        val (trace, terminal) = crawlAndSample(seeds, parallelTabs = 1)

        assertMonotone(trace, terminal, expectedPages = seeds.size * linksPerHub)
    }

    @Test
    @DisplayName("two overlapping seed rounds keep the collected total")
    fun testOverlappingRoundsKeepTheCollectedTotal() {
        // Same promise with two rounds publishing at once: the aggregation must
        // count both, and neither publisher may write a record that drops what the
        // other one reported.
        val seeds = hubSeeds(listOf("delta", "echo"))

        val (trace, terminal) = crawlAndSample(seeds, parallelTabs = 2)

        assertMonotone(trace, terminal, expectedPages = seeds.size * linksPerHub)
    }

    // -----------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------

    private fun hubSeeds(ids: List<String>): List<String> =
        ids.map { "$probeBase/hub/$it?links=$linksPerHub&delayMs=$linkDelayMs" }

    /**
     * Assert what the progress view promises: the collected total of every round
     * that has published, never a partial view, and never more than the crawl
     * terminates with.
     */
    private fun assertMonotone(trace: List<Sample>, terminal: CrawlResponse, expectedPages: Int) {
        val running = trace.filter { !it.terminal }
        // A trace of one sample per 150 ms is hundreds of lines; only the samples
        // where something changed say anything about the progress view.
        val history = trace.transitions()

        assertTrue(running.size >= 5,
            "the crawl finished too fast to observe its progress view (${running.size} running sample(s)): $history")
        assertEquals(expectedPages, terminal.pagesExpected,
            "the crawl did not collect what the test needs to be meaningful: $terminal")
        assertEquals(expectedPages, terminal.pages?.size,
            "the terminal record must account for every page: $terminal")

        // 1. Aggregation: the count of everything collected so far, never just the
        //    pages of the round that published last.
        var pagesHighWater = 0
        for (sample in running) {
            assertTrue(sample.pagesFound >= pagesHighWater,
                "pagesFound fell back from $pagesHighWater to ${sample.pagesFound} while the crawl " +
                    "was running: $history")
            pagesHighWater = sample.pagesFound
        }

        // 2. The same promise for the settled-seed accounting: once a loss or an
        //    expected total is visible, a later publish must not erase it.
        var expectedHighWater = 0
        var failedHighWater = 0
        for (sample in running) {
            assertTrue(sample.pagesExpected >= expectedHighWater,
                "pagesExpected fell back from $expectedHighWater to ${sample.pagesExpected}: $history")
            assertTrue(sample.failed >= failedHighWater,
                "failedPages fell back from $failedHighWater to ${sample.failed}: $history")
            expectedHighWater = sample.pagesExpected
            failedHighWater = sample.failed
        }

        // 3. The in-flight view never runs ahead of the terminal record, and it was
        //    observed filling up: one sample at the end would prove nothing about
        //    the publishes in between — and would hide a fallback.  The last page's
        //    publish can land in the same poll interval as the terminal write, so
        //    this checks that the view grew, not that it reached the total.
        assertTrue(pagesHighWater <= terminal.pagesFound,
            "the in-flight view reached $pagesHighWater page(s), the terminal record only " +
                "${terminal.pagesFound}: $history")
        val observedCounts = trace.map { it.pagesFound }.distinct()
        assertTrue(observedCounts.size >= 3,
            "the progress view was observed at ${observedCounts.size} distinct page count(s) " +
                "(at least 3 is needed for the run to say anything): $history")
    }

    /** The samples in which something changed, in order. */
    private fun List<Sample>.transitions(): List<Sample> {
        val out = mutableListOf<Sample>()
        var last: Sample? = null
        for (sample in this) {
            if (sample != last) out.add(sample)
            last = sample
        }
        return out
    }

    /** Run the crawl and poll its record every 150 ms until it is terminal. */
    private fun crawlAndSample(seeds: List<String>, parallelTabs: Int): Pair<List<Sample>, CrawlResponse> {
        val payload = jacksonObjectMapper().writeValueAsString(
            mapOf(
                "url" to seeds.first(),
                "urls" to seeds,
                "args" to "-outLink \"a.probe-link\" -topLinks $linksPerHub -refresh",
                "depth" to 1,
                "parallelTabs" to parallelTabs,
            )
        )
        val rawTaskId = client.post().uri("/api/crawl")
            .contentType(MediaType.APPLICATION_JSON)
            .body(payload)
            .exchange()
            .expectStatus().is2xxSuccessful
            .expectBody<String>()
            .returnResult()
            .responseBody
        val taskId = requireNotNull(rawTaskId) { "expected a crawl task id" }
            .trim().removeSurrounding("\"")

        val trace = mutableListOf<Sample>()
        // The shared bound, not a hand-chosen one: this class polls every 150 ms, but it
        // must wait at least as long as the server's own task budget can hold a crawl
        // (see [crawlTerminalWait]).
        val deadline = Instant.now().plus(crawlTerminalWait)
        while (Instant.now().isBefore(deadline)) {
            Thread.sleep(150)
            val response = readResult(taskId)
            val sample = Sample(
                terminal = response.isTerminal(),
                status = response.status,
                pagesFound = response.pagesFound,
                pagesExpected = response.pagesExpected,
                failed = response.failedPages?.size ?: 0,
                linksDiscovered = response.linksDiscovered,
            )
            trace.add(sample)
            if (sample.terminal) {
                return trace to response
            }
        }
        error("crawl $taskId never reached a terminal state; samples=$trace")
    }

    private fun readResult(taskId: String): CrawlResponse {
        val raw = client.get().uri("/api/crawl/$taskId/result")
            .exchange()
            .expectStatus().is2xxSuccessful
            .expectBody<String>()
            .returnResult()
            .responseBody
        return requireNotNull(raw) { "empty crawl result for $taskId" }
            .let {
                jacksonObjectMapper()
                    .registerModule(JavaTimeModule())
                    .readValue(it, CrawlResponse::class.java)
            }
    }

    /** Terminal detection defers to [CrawlStatus] — the one vocabulary definition. */
    private fun CrawlResponse.isTerminal(): Boolean =
        finishTime != null || CrawlStatus.isTerminal(status)
}
