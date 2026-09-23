package ai.platon.pulsar.rest.api.controller

import ai.platon.pulsar.rest.api.service.crawl.CrawlResponse
import ai.platon.pulsar.rest.api.service.crawl.CrawlStatus
import ai.platon.pulsar.test.TestUrls
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.client.expectBody
import java.time.Duration
import java.time.Instant

/**
 * Link discovery over a storefront that offers the same page through several
 * anchors (`/generated/crawl/dup/hub.html`: five products, eight anchors — image
 * plus title, a grid/list query pair, and a fragment link).
 *
 * The contracts pinned here are the ones `crawl.md` documents:
 *
 *  * "Deduplicates and limits to `--top-links` links" — the budget is spent on
 *    *distinct* pages, so repeats on one page cannot shrink the crawl;
 *  * `--ignore-url-query` — the query is stripped from a *discovered* href, so
 *    the URL a row reports is the URL that was fetched;
 *  * the dedup identity is query- and fragment-insensitive, so a page offered
 *    under two spellings is fetched once, under the first spelling seen.
 *
 * Tagged [IntegrationTest]: it drives real browser crawls through the REST API.
 */
@Tag("IntegrationTest")
class CrawlLinkDiscoveryTest : RestAPITestBase() {

    private val crawlBase: String by lazy { TestUrls.MOCK_CRAWL_BASE }

    /** The five distinct pages the duplicate hub offers. */
    private fun productTitles(): Map<String, String> = mapOf(
        "$crawlBase/product/1.html" to "Widget Alpha — \$10.00",
        "$crawlBase/product/2.html" to "Widget Beta — \$20.00",
        "$crawlBase/product/3.html" to "Widget Gamma — \$30.00",
        "$crawlBase/product/4.html" to "Widget Delta — \$40.00",
        "$crawlBase/product/5.html" to "Widget Epsilon — \$50.00"
    )

    @Test
    @DisplayName("repeats on a page do not spend the --top-links budget")
    fun testRepeatsDoNotSpendTheBudget() {
        // Eight anchors, five destinations, a budget of three: the crawl owes
        // three *distinct* pages.  Spending the budget on the repeats (image +
        // title of the same product) fetches two pages and reports three
        // discovered links — a smaller crawl than the one that was asked for.
        val response = runCrawl(args = "-outLink \"a.pick\" -topLinks 3 -refresh")

        assertTrue(response.status == CrawlStatus.OK,
            "crawl should complete OK, got: ${response.status} error=${response.error}")
        assertNoLostPages(response)

        val pages = requireNotNull(response.pages)
        assertEquals(4, pages.size,
            "expected the hub plus 3 distinct products, got ${pages.size}: ${pages.map { it.url }}")
        assertEquals(listOf(
            "$crawlBase/dup/hub.html",
            "$crawlBase/product/1.html",
            "$crawlBase/product/2.html",
            "$crawlBase/product/3.html"
        ), pages.map { it.url }, "the budget must be spent on the first three distinct targets")

        // The repeats are not discoveries: the count the CLI shows must be the
        // number of links the crawl actually queued.
        assertEquals(3, response.linksDiscovered,
            "linksDiscovered should count queued links, not anchors")
    }

    @Test
    @DisplayName("--ignore-url-query strips the query from the discovered hrefs a crawl queues")
    fun testIgnoreUrlQueryShapesDiscoveredUrls() {
        // This run also pins the *default* spelling rules, because it asserts the exact URL of
        // every row: product/4.html is offered as ?src=grid and ?src=list and is reported once,
        // and product/5.html is offered with a fragment and reported without it.
        //
        // "The first spelling of one page wins" is pinned where it is decided
        // (CrawlSupportTest.testOnePageIsQueuedUnderItsFirstSpelling) rather than with a crawl of
        // its own: every crawl of this fixture costs minutes on CI, and the tag gate runs close
        // to its budget (docs-dev/copilot/ci-stabilization-4.13.x.md §24).
        val response = runCrawl(args = "-outLink \"a.pick\" -topLinks 20 -ignoreUrlQuery -refresh")

        assertTrue(response.status == CrawlStatus.OK,
            "crawl should complete OK, got: ${response.status} error=${response.error}")
        assertNoLostPages(response)

        val pages = requireNotNull(response.pages)
        assertEquals(6, pages.size,
            "expected the hub plus 5 distinct products, got ${pages.size}: ${pages.map { it.url }}")
        assertTrue(pages.none { it.url.contains('?') },
            "--ignoreUrlQuery must strip the query from every discovered URL, got: ${pages.map { it.url }}")

        val titles = productTitles()
        for ((url, title) in titles) {
            val page = pages.singleOrNull { it.url == url }
            assertNotNull(page, "expected a row for $url, got ${pages.map { it.url }}")
            assertEquals(title, page!!.title, "title for $url does not match the page at that URL")
        }
    }

    // -----------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------

    /**
     * Issue #592 conservation: every page a crawl submitted is either a record
     * or a reported loss, so `pagesFound + failedPages.size == pagesExpected`.
     */
    private fun assertNoLostPages(response: CrawlResponse) {
        val failed = response.failedPages ?: emptyList()
        val pages = response.pages ?: emptyList()
        assertTrue(failed.isEmpty(),
            "crawl lost ${failed.size} page(s) of ${response.pagesExpected}: " +
                failed.joinToString("; ") {
                    "${it.url} (depth=${it.depth}, status=${it.protocolStatus}, reason=${it.reason})"
                })
        assertEquals(response.pagesExpected, pages.size,
            "pagesFound + failedPages must equal pagesExpected " +
                "(${pages.size} + ${failed.size} != ${response.pagesExpected})")
    }

    private fun runCrawl(args: String): CrawlResponse = waitForTerminal(submitCrawl(args))

    private fun submitCrawl(args: String): String {
        // The args carry a quoted CSS selector, so they must be escaped for JSON:
        // a raw `-outLink "a.pick"` inside the string literal is a 400, not a crawl.
        val jsonArgs = args.replace("\\", "\\\\").replace("\"", "\\\"")
        val body = """
            {"url": "${TestUrls.MOCK_CRAWL_DUP_HUB_URL}",
             "args": "$jsonArgs",
             "depth": 2}
        """.trimIndent()
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

    private fun waitForTerminal(taskId: String): CrawlResponse {
        val deadline = Instant.now().plus(Duration.ofMinutes(4))
        var last: CrawlResponse? = null
        while (Instant.now().isBefore(deadline)) {
            Thread.sleep(2000)
            // The result body is deserialized with the Kotlin-aware mapper:
            // `expectBody<CrawlResponse>()` uses the client-side converter, which
            // binds no field of an all-default Kotlin class (`status` would stay
            // at its default forever).
            val raw = client.get().uri("/api/crawl/$taskId/result")
                .exchange()
                .expectStatus().is2xxSuccessful
                .expectBody<String>()
                .returnResult()
                .responseBody
            val result = requireNotNull(raw) { "Empty crawl result body for $taskId" }
                .let {
                    jacksonObjectMapper()
                        .registerModule(JavaTimeModule())
                        .readValue(it, CrawlResponse::class.java)
                }
            last = result
            if (CrawlStatus.isTerminal(result.status)) {
                return result
            }
        }
        error("Crawl $taskId did not reach a terminal state within 4 minutes, last status: ${last?.status}")
    }
}
