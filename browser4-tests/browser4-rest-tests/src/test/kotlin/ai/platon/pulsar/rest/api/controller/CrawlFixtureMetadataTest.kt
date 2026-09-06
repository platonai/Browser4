package ai.platon.pulsar.rest.api.controller

import ai.platon.pulsar.rest.api.service.CrawlResponse
import ai.platon.pulsar.test.TestUrls
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.client.expectBody
import java.time.Duration
import java.time.Instant

/**
 * Verifies that a link-discovery crawl records each page's metadata under the
 * URL that produced it — the per-fetch metadata integrity guarantee (Issue 1
 * acceptance: "each stored title matches its URL").
 *
 * A depth-2 crawl over the static /generated/crawl/ fixture must produce one
 * row per page, in a deterministic depth/URL order, with the title of the
 * page at that URL — never another page's title, even when pages are fetched
 * and recorded in sequence over the same browser session.
 *
 * Also verifies --readonly surfacing (Issue 2 acceptance): a readonly crawl
 * either served the stored content (rows marked, note carries the age) or
 * verifies every page was fetched fresh from the live site; in both branches
 * each stored title still matches its URL.
 *
 * Tagged [IntegrationTest] so it runs in main CI + nightly (not PR CI).
 */
@Tag("IntegrationTest")
class CrawlFixtureMetadataTest : RestAPITestBase() {

    private val crawlBase: String by lazy { TestUrls.MOCK_CRAWL_BASE }

    /** Static fixture ground truth: URL -> <title> of the file at that URL. */
    private fun fixtureTitles(): Map<String, String> = mapOf(
        "$crawlBase/index.html" to "Crawl Test Hub",
        "$crawlBase/product/1.html" to "Widget Alpha — \$10.00",
        "$crawlBase/product/2.html" to "Widget Beta — \$20.00",
        "$crawlBase/product/3.html" to "Widget Gamma — \$30.00",
        "$crawlBase/product/4.html" to "Widget Delta — \$40.00",
        "$crawlBase/product/5.html" to "Widget Epsilon — \$50.00",
        "$crawlBase/product/6.html" to "Widget Zeta — \$60.00",
        "$crawlBase/product/7.html" to "Widget Lambda — \$70.00",
        "$crawlBase/product/8.html" to "Widget Mu — \$80.00",
        "$crawlBase/product/9.html" to "Widget Nu — \$90.00"
    )

    /** Depth of each fixture page in a depth-2 crawl from the hub. */
    private fun fixtureDepths(): Map<String, Int> = mapOf(
        "$crawlBase/index.html" to 0,
        "$crawlBase/product/1.html" to 1,
        "$crawlBase/product/2.html" to 1,
        "$crawlBase/product/3.html" to 1,
        "$crawlBase/product/4.html" to 2,
        "$crawlBase/product/5.html" to 2,
        "$crawlBase/product/6.html" to 2,
        "$crawlBase/product/7.html" to 2,
        "$crawlBase/product/8.html" to 2,
        "$crawlBase/product/9.html" to 2
    )

    @Test
    @DisplayName("depth-2 crawl records each stored title under the URL that produced it")
    fun testDepth2CrawlRecordsTitlesPerUrl() {
        val response = runCrawl(depth = 2, args = "-refresh")

        assertTrue(response.status == "OK" || response.status == "SC_OK",
            "crawl should complete OK, got: ${response.status} error=${response.error}")
        val pages = requireNotNull(response.pages)

        // 1 hub + 3 depth-1 + 6 depth-2 = 10 rows; no page appears twice.
        assertEquals(10, pages.size, "expected 10 pages (hub + 9 products), got ${pages.size}")
        assertEquals(10, pages.map { it.url }.distinct().size, "duplicate URL rows in crawl result")

        // Every row's title is the title of the page at that URL, and the depth
        // label matches the discovery depth — never another page's content.
        val expectedTitles = fixtureTitles()
        val expectedDepths = fixtureDepths()
        for (page in pages) {
            val expectedTitle = expectedTitles[page.url]
            assertNotNull(expectedTitle, "unexpected page URL in crawl result: ${page.url}")
            assertEquals(expectedTitle, page.title,
                "title for ${page.url} does not match the page at that URL (crossed metadata?)")
            assertEquals(expectedDepths[page.url], page.depth,
                "depth for ${page.url} does not match its discovery depth")
        }

        // Deterministic ordering: depth asc, then URL asc.
        val sortedUrls = pages.map { it.url }
        assertEquals(pages.sortedWith(compareBy({ it.depth }, { it.url })).map { it.url }, sortedUrls,
            "crawl result is not sorted by (depth, url)")
    }

    @Test
    @DisplayName("readonly + refresh crawl verifies freshness and never serves stored content")
    fun testReadonlyRefreshCrawlVerifiesFreshness() {
        val response = runCrawl(depth = 2, args = "-readonly -refresh")

        assertTrue(response.status == "OK" || response.status == "SC_OK",
            "crawl should complete OK, got: ${response.status} error=${response.error}")
        val pages = requireNotNull(response.pages)
        assertEquals(10, pages.size, "expected 10 pages, got ${pages.size}")

        // With -refresh nothing may be served from the store; every page was
        // fetched from the live site and the note says so (Issue 2: readonly
        // surfaces what it did — served with age, or verified fresh).
        assertTrue(pages.none { it.servedFromStore },
            "readonly -refresh crawl must not serve stored content, but ${pages.count { it.servedFromStore }} page(s) did")
        val note = requireNotNull(response.readonlyNote) { "readonly crawl must produce a readonlyNote" }
        assertTrue(note.contains("verified fresh"), "readonly note should verify freshness, got: $note")
        assertTrue(note.contains("nothing was written to the page store"), "readonly note should state nothing was written, got: $note")

        // Metadata integrity holds on the fresh fetch too.
        val expectedTitles = fixtureTitles()
        for (page in pages) {
            assertEquals(expectedTitles[page.url], page.title,
                "title for ${page.url} does not match the page at that URL")
        }
    }

    @Test
    @DisplayName("readonly crawl without refresh surfaces store serves with age, or verifies freshness")
    fun testReadonlyCrawlSurfacesServedOrFresh() {
        // No -refresh: when the page store holds the fixture pages (from
        // earlier crawls), the load may serve stored content — readonly mode
        // must say so with the age of the content; otherwise it must verify
        // freshness.  Either way metadata integrity holds per row.
        val response = runCrawl(depth = 2, args = "-readonly")

        assertTrue(response.status == "OK" || response.status == "SC_OK",
            "crawl should complete OK, got: ${response.status} error=${response.error}")
        val pages = requireNotNull(response.pages)
        val note = requireNotNull(response.readonlyNote) { "readonly crawl must produce a readonlyNote" }

        val served = pages.filter { it.servedFromStore }
        if (served.isEmpty()) {
            assertTrue(note.contains("verified fresh"),
                "readonly note should verify freshness when nothing was served, got: $note")
        } else {
            assertTrue(note.contains("served from the page store"),
                "readonly note should report store serves, got: $note")
            assertTrue(note.contains("old"), "readonly note should carry the age of stored content, got: $note")
            // Served rows carry the stored-content age; the original fetch time
            // of stored content is preserved, so the age is always computable.
            assertTrue(served.all { it.storeAgeSeconds != null },
                "served-from-store rows must carry storeAgeSeconds")
        }

        // Every row — stored or fresh — still shows the title of the page at
        // that URL.  Stored content is served under the URL it was stored for.
        val expectedTitles = fixtureTitles()
        for (page in pages) {
            assertEquals(expectedTitles[page.url], page.title,
                "title for ${page.url} does not match the page at that URL")
        }
    }

    // -----------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------

    private fun runCrawl(depth: Int, args: String): CrawlResponse {
        val body = """
            {"url": "${TestUrls.MOCK_CRAWL_HUB_URL}",
             "args": "-outLink \"a.product\" -outLinkPattern \"product/\" $args",
             "depth": $depth}
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

        return waitForTerminal(taskId)
    }

    private fun waitForTerminal(taskId: String): CrawlResponse {
        val deadline = Instant.now().plus(Duration.ofMinutes(6))
        var last: CrawlResponse? = null
        while (Instant.now().isBefore(deadline)) {
            Thread.sleep(2000)
            // Fetch the raw body and deserialize with the Kotlin-aware Jackson
            // mapper.  `expectBody<CrawlResponse>()` uses the client-side
            // converter without the Kotlin module: CrawlResponse's all-default
            // constructor lets it instantiate the class, but no field is ever
            // bound — status would stay at its "CREATED" default forever even
            // though the server reports PROCESSING/OK.
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
            if (result.status == "OK" || result.status == "SC_OK" ||
                result.status == "SC_REQUEST_TIMEOUT" || result.status == "SC_INTERNAL_SERVER_ERROR"
            ) {
                return result
            }
        }
        error("Crawl $taskId did not reach a terminal state within 6 minutes, last status: ${last?.status}")
    }
}
