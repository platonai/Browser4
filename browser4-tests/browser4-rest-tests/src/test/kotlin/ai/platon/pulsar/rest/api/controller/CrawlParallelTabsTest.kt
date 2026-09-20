package ai.platon.pulsar.rest.api.controller

import ai.platon.pulsar.rest.api.service.crawl.CrawlRequest
import ai.platon.pulsar.rest.api.service.crawl.CrawlResponse
import ai.platon.pulsar.rest.api.service.crawl.CrawlService
import ai.platon.pulsar.test.TestUrls
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.client.expectBody
import java.time.Duration
import java.time.Instant

/**
 * Acceptance tests for multi-tab parallel collection: a crawl with several
 * independent units must actually overlap them on separate browser tabs, and
 * `--parallel 1` must still be strictly sequential.
 *
 * The evidence is measured on the **target server**, not inferred from the
 * crawl's own bookkeeping: [ConcurrencyProbeController] holds each request open
 * for a fixed time and records the high-water mark of requests it served at
 * once.  A crawl that claims parallelism but drives one tab cannot produce an
 * overlap on the server no matter how the crawl counts its own work, so the
 * server-side high-water mark is what makes "multi-tab" falsifiable.
 *
 * Both directions are asserted, which is what makes the measurement meaningful:
 * the parallel run has to overlap, and the `parallelTabs = 1` control run has to
 * *not* overlap.  Without the control, a passing parallel assertion could just be
 * the probe counting unrelated requests.
 *
 * Tagged [IntegrationTest]: needs a real browser, the driver pool and the mock
 * site, so it runs in main CI + nightly (not PR CI).
 */
@Tag("IntegrationTest")
class CrawlParallelTabsTest : RestAPITestBase() {

    private val probeBase: String by lazy { "${TestUrls.MOCK_CRAWL_BASE.substringBefore("/generated")}/__probe" }

    /** The static /generated/crawl/ fixture the link-discovery round test crawls. */
    private val crawlBase: String by lazy { TestUrls.MOCK_CRAWL_BASE }

    /** Long enough that a sequential crawl cannot fake an overlap. */
    private val pageDelayMs = 1_500L

    @Test
    @DisplayName("a 4-seed crawl with parallelTabs=4 collects the seeds on several tabs at once")
    fun testParallelSeedCrawlOverlapsOnTheServer() {
        val ids = listOf("alpha", "bravo", "charlie", "delta")
        resetProbe()

        val response = crawlSeeds(ids, parallelTabs = 4)

        assertEquals("OK", response.status, "crawl should complete OK: error=${response.error}")
        assertEquals(4, response.pages?.size ?: 0, "one page per seed expected, got ${response.pages}")
        // Every row is the page at its own URL: parallel collection must not
        // cross metadata between the tabs that ran at the same time.
        for (page in response.pages!!) {
            assertTrue(
                page.title == "Probe ${idOf(page.url)}",
                "title for ${page.url} is '${page.title}', which is not the page at that URL"
            )
        }

        // The budget asked for, and the overlap actually achieved.
        assertEquals(4, response.parallelTabs, "the crawl must report the budget it ran under")
        assertTrue(
            response.maxConcurrentFetches >= 2,
            "a 4-seed crawl with a budget of 4 reported a peak of only " +
                "${response.maxConcurrentFetches} fetch(es) in flight"
        )

        // The server's own high-water mark: four slow requests really were open
        // at the same time, so the pages were collected on several tabs.
        val stats = probeStats()
        val maxConcurrent = stats["maxConcurrent"] ?: 0
        assertTrue(
            maxConcurrent >= 2,
            "the target server never served more than $maxConcurrent probe request(s) at once, " +
                "so the crawl did not collect in parallel — stats=$stats"
        )
        assertTrue(
            (stats["totalRequests"] ?: 0) >= 4,
            "every seed must have reached the server, stats=$stats"
        )
    }

    @Test
    @DisplayName("the same 4-seed crawl with parallelTabs=1 is strictly sequential")
    fun testSequentialControlRunDoesNotOverlap()  {
        val ids = listOf("echo", "foxtrot", "golf", "hotel")
        resetProbe()

        val response = crawlSeeds(ids, parallelTabs = 1)

        assertEquals("OK", response.status, "crawl should complete OK: error=${response.error}")
        assertEquals(4, response.pages?.size ?: 0, "one page per seed expected")
        assertEquals(1, response.parallelTabs)
        assertEquals(
            1, response.maxConcurrentFetches,
            "a budget of 1 must never overlap two units"
        )

        // The control: with a budget of 1 the target server must never see two
        // probe requests at once.  This is what proves the parallel test above
        // measures the crawl and not background noise.
        val stats = probeStats()
        assertEquals(
            1, stats["maxConcurrent"],
            "a sequential crawl must not overlap on the server, stats=$stats"
        )
    }

    @Test
    @DisplayName("a budget above the server ceiling is refused instead of silently trimmed")
    fun testBudgetAboveCeilingIsRefused() {
        // Two different failures are worth distinguishing: the request itself is
        // invalid (400, here — no crawl is created), while a *valid* request is
        // clamped by CrawlService.resolveParallelTabs and reported back in
        // CrawlResponse.parallelTabs.  This pins the loud half.
        val body = """
            {"url":"$probeBase/slow/too-many","args":"-refresh","depth":0,
             "parallelTabs": ${CrawlService.MAX_PARALLEL_TABS + 1}}
        """.trimIndent()
        client.post().uri("/api/crawl")
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
            .exchange()
            .expectStatus().isBadRequest
    }

    @Test
    @DisplayName("concurrent link-discovery rounds that share out-links still settle and lose nothing")
    fun testParallelLinkDiscoveryRoundsLoseNoPages() {
        // Two seeds whose link-discovery rounds overlap: the hub links to the
        // first products, and the electronics category links to some of the same
        // ones.  Running the rounds at the same time means the *same* out-link is
        // queued by two rounds at once — the case that would hang a round whose
        // submitted page is served to somebody else's handler.  The rounds must
        // still settle, and neither may report a lost page.
        val seeds = listOf(
            "$crawlBase/index.html",
            "$crawlBase/category/electronics.html"
        )
        val response = crawlWithArgs(
            urls = seeds,
            depth = 1,
            parallelTabs = 2,
            args = "-outLink \"a.product\" -outLinkPattern \"product/\" -refresh"
        )

        assertEquals("OK", response.status, "crawl should complete OK: error=${response.error}")
        assertEquals(
            listOf("fetched", "fetched"),
            response.seedStatuses?.map { it.status },
            "both seed rounds must complete: ${response.seedStatuses}"
        )
        assertTrue(
            (response.failedPages ?: emptyList()).isEmpty(),
            "rounds that share out-links lost pages: ${response.failedPages}"
        )
        assertEquals(
            response.pagesExpected,
            response.pages?.size ?: 0,
            "pagesFound + failedPages must equal pagesExpected"
        )
        assertTrue(
            response.maxConcurrentFetches >= 2,
            "the two rounds should have overlapped, peak was ${response.maxConcurrentFetches}"
        )
        // Every page both rounds link to is present, under its own title.
        val urls = response.pages!!.map { it.url }.toSet()
        assertTrue("$crawlBase/product/1.html" in urls, "shared out-link missing: $urls")
        assertTrue("$crawlBase/product/2.html" in urls, "shared out-link missing: $urls")
    }

    // -----------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------

    /** The `id` path segment a probe URL carries, used to rebuild the title. */
    private fun idOf(url: String): String =
        url.substringAfter("/slow/", "").substringBefore('?')

    private fun resetProbe() {
        client.post().uri("$probeBase/reset")
            .exchange()
            .expectStatus().is2xxSuccessful
    }

    private fun probeStats(): Map<String, Int> {
        val raw = client.get().uri("$probeBase/stats")
            .exchange()
            .expectStatus().is2xxSuccessful
            .expectBody<String>()
            .returnResult()
            .responseBody
        val body = requireNotNull(raw) { "empty /__probe/stats body" }
        return jacksonObjectMapper().readValue(body, Map::class.java)
            .entries.associate { (k, v) -> k.toString() to (v as Number).toInt() }
    }

    private fun crawlSeeds(ids: List<String>, parallelTabs: Int): CrawlResponse {
        // Distinct *paths*, not merely distinct query strings: a crawler that
        // normalizes query params away would otherwise collapse four probe pages
        // into one and the overlap measurement would be meaningless.
        val urls = ids.map { "$probeBase/slow/$it?delayMs=$pageDelayMs" }
        return crawlWithArgs(urls, depth = 0, parallelTabs = parallelTabs, args = "-refresh")
    }

    private fun crawlWithArgs(
        urls: List<String>,
        depth: Int,
        parallelTabs: Int,
        args: String,
    ): CrawlResponse {
        val request = CrawlRequest(
            url = urls.first(),
            args = args,
            depth = depth,
            urls = urls,
            parallelTabs = parallelTabs
        )
        return waitForTerminal(submitRaw(request))
    }

    /**
     * Submit through the real HTTP endpoint and return the task id.
     *
     * The JSON is assembled field by field — not by serializing the DTO — so the
     * wire contract is what the test pins: the `parallelTabs` field name and its
     * absence when no budget was requested.  A text template would need the args
     * string escaped by hand (it carries CSS selectors and quotes), and getting
     * that wrong fails the test for the wrong reason.
     */
    private fun submitRaw(request: CrawlRequest): String {
        val payload = mutableMapOf<String, Any>(
            "url" to request.url,
            "urls" to request.urls.orEmpty(),
            "args" to request.args,
            "depth" to request.depth,
        )
        // The literal field name, spelled here rather than taken from the DTO:
        // the server, the CLI and this test must agree on the wire name, and a
        // rename has to break a test instead of drifting silently.
        request.parallelTabs?.let { payload["parallelTabs"] = it }
        val body = jacksonObjectMapper().writeValueAsString(payload)
        val rawTaskId = client.post().uri("/api/crawl")
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
            .exchange()
            .expectStatus().is2xxSuccessful
            .expectBody<String>()
            .returnResult()
            .responseBody
        return requireNotNull(rawTaskId) { "expected a crawl task id" }
            .trim()
            .removeSurrounding("\"")
            .also { check(it.isNotBlank()) { "blank crawl task id" } }
    }

    /**
     * Terminal-state wait for a single crawl.
     *
     * This was a hard-coded 4 minutes, which a loaded CI runner ate: a healthy local
     * run of this class finishes in ~64–91 s, while on CI the same code took 7.4x
     * longer (class 72.7 s green → 538.1 s red) and the sequential control run hit
     * the cap while still `PROCESSING`, failing the gate twice — see
     * `docs-dev/copilot/ci-stabilization-4.13.x.md` §19.  10 minutes keeps roughly a
     * 7x margin over the healthy time while staying bounded, so a genuinely stuck
     * crawl still fails — now with the task's own account of where it stopped.
     */
    private val terminalWait: Duration = Duration.ofMinutes(10)

    private fun waitForTerminal(taskId: String): CrawlResponse {
        val deadline = Instant.now().plus(terminalWait)
        var last: CrawlResponse? = null
        while (Instant.now().isBefore(deadline)) {
            Thread.sleep(1_000)
            val raw = client.get().uri("/api/crawl/$taskId/result")
                .exchange()
                .expectStatus().is2xxSuccessful
                .expectBody<String>()
                .returnResult()
                .responseBody
            val result = requireNotNull(raw) { "empty crawl result for $taskId" }
                .let {
                    jacksonObjectMapper()
                        .registerModule(JavaTimeModule())
                        .readValue(it, CrawlResponse::class.java)
                }
            last = result
            if (result.isTerminal()) {
                return result
            }
        }
        // The old failure said only "last: PROCESSING", which said nothing about how
        // far the crawl got.  Report the task's own accounting instead (§19.5).
        error(
            "Crawl $taskId did not reach a terminal state within ${terminalWait.toMinutes()} minutes: " +
                (last?.describe() ?: "no result was ever returned")
        )
    }

    /**
     * Terminal detection has to accept every spelling used on this line: the
     * response defaults to the `"CREATED"` token, [CrawlService] settles with
     * `ResourceStatus` display text (`"OK"`, `"Request Timeout"`,
     * `"Internal Server Error"`, `"Not Found"`), and [CrawlResponse.finishTime] is
     * the model's own terminal marker.  The previous check compared against
     * `"SC_REQUEST_TIMEOUT"` / `"SC_INTERNAL_SERVER_ERROR"` spellings that the
     * service never emits, so a crawl that had already timed out could never be
     * recognised and the wait ran out its whole cap before blaming a stall.
     */
    private fun CrawlResponse.isTerminal(): Boolean =
        finishTime != null ||
            status.equals("OK", true) ||
            status.equals("Request Timeout", true) ||
            status.equals("Internal Server Error", true) ||
            status.equals("Not Found", true)

    /** One line of the task's own accounting, for a timeout that has to be actionable. */
    private fun CrawlResponse.describe(): String = buildString {
        append("status=").append(status)
        append(", pages=").append(pagesFound).append('/').append(pagesExpected)
        append(", links=").append(linksDiscovered)
        append(", parallelTabs=").append(parallelTabs)
        append(", waiting=").append(
            Duration.between(startedTime ?: Instant.ofEpochMilli(createdAt), Instant.now()).seconds
        ).append('s')
        error?.let { append(", error=").append(it) }
        diagnostic?.let { append(", diagnostic=").append(it) }
        failedPages?.takeIf { it.isNotEmpty() }?.let { append(", failedPages=").append(it.size) }
        seedStatuses?.takeIf { it.isNotEmpty() }?.let { seeds ->
            append(", seeds=[").append(
                seeds.joinToString("; ") { "${it.url.substringAfterLast('/')}:${it.status}" }
            ).append(']')
        }
    }
}
