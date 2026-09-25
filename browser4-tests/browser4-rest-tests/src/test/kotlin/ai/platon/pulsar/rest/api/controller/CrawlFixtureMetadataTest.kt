package ai.platon.pulsar.rest.api.controller

import ai.platon.pulsar.rest.api.service.crawl.CrawlResponse
import ai.platon.pulsar.rest.api.service.crawl.CrawlStatus
import ai.platon.pulsar.test.TestUrls
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.client.expectBody

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
class CrawlFixtureMetadataTest : CrawlTestBase() {

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

        assertTrue(response.status == CrawlStatus.OK,
            "crawl should complete OK, got: ${response.status} error=${response.error}")
        assertNoLostPages(response)
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
    @DisplayName("-readonly wins over -refresh: asking for both still serves the stored copy")
    fun testReadOnlyRefreshCrawlVerifiesFreshness() {
        // The two flags cannot be combined, and this is what that means end to end: `-refresh`
        // expands to `-ignoreFailure -i 0s`, which makes every local copy look expired and so stops
        // a read-only load from being the cache hit `--readonly` promises — the read-only one
        // decides (CrawlSupport.resolveRoundArgs).  Requesting both therefore behaves like
        // requesting `--readonly`, *not* like requesting a fresh fetch: the discriminating
        // assertion is that the note does not claim freshness.
        val response = runCrawl(depth = 2, args = "-readonly -refresh")

        assertTrue(response.status == CrawlStatus.OK,
            "crawl should complete OK, got: ${response.status} error=${response.error}")
        assertNoLostPages(response)
        val pages = requireNotNull(response.pages)
        assertEquals(10, pages.size, "expected 10 pages, got ${pages.size}")

        val served = pages.count { it.servedFromStore }
        val note = requireNotNull(response.readonlyNote) { "readonly crawl must produce a readonlyNote" }
        assertTrue(served > 0,
            "readonly wins over refresh, so the stored fixture should have been served, but the " +
                "note says: $note")
        assertTrue(note.contains("served from the page store"),
            "readonly note should report store serves, got: $note")
        assertTrue(!note.contains("verified fresh"),
            "a read-only round that served the store must not claim freshness, got: $note")

        // Metadata integrity holds on the served copy too.
        val expectedTitles = fixtureTitles()
        for (page in pages) {
            assertEquals(expectedTitles[page.url], page.title,
                "title for ${page.url} does not match the page at that URL")
        }
    }

    @Test
    @DisplayName("readonly crawl without refresh surfaces store serves with age, or verifies freshness")
    fun testReadonlyCrawlSurfacesServedOrFresh() {
        // No -refresh: the page store holds the fixture pages (the tests above fetched them), and
        // `-readonly` now wins over the refresh a crawl would otherwise add (resolveRoundArgs), so
        // the loads are free to serve that stored content — readonly mode must say so with the age
        // of the content.  The freshness branch remains for a store that does not hold the page
        // (a cache miss is fetched, just not written back).  Either way metadata integrity holds
        // per row.
        val response = runCrawl(depth = 2, args = "-readonly")

        assertTrue(response.status == CrawlStatus.OK,
            "crawl should complete OK, got: ${response.status} error=${response.error}")
        assertNoLostPages(response)
        val pages = requireNotNull(response.pages)
        // Without this the test passed vacuously whenever the crawl listed no
        // rows at all: the per-row title loop below simply never ran.
        assertEquals(10, pages.size, "expected 10 pages, got ${pages.size}")
        val note = requireNotNull(response.readonlyNote) { "readonly crawl must produce a readonlyNote" }

        val served = pages.filter { it.servedFromStore }
        // The tests above fetched the fixture into the page store, and a read-only crawl is what
        // may answer from it (CrawlSupport.isReadOnlyStoreServe) — so the store branch is the one
        // this run takes.  If the store ever stops holding the fixture, this assertion is where
        // that shows up, instead of the run silently verifying freshness.
        assertTrue(
            served.isNotEmpty(),
            "a read-only crawl should serve the stored fixture the earlier tests fetched, " +
                "note: ${response.readonlyNote}"
        )
        assertTrue(note.contains("served from the page store"),
            "readonly note should report store serves, got: $note")
        assertTrue(note.contains("old"), "readonly note should carry the age of stored content, got: $note")
        // Served rows carry the stored-content age; the original fetch time
        // of stored content is preserved, so the age is always computable.
        assertTrue(served.all { it.storeAgeSeconds != null },
            "served-from-store rows must carry storeAgeSeconds")

        // Every row — stored or fresh — still shows the title of the page at
        // that URL.  Stored content is served under the URL it was stored for.
        val expectedTitles = fixtureTitles()
        for (page in pages) {
            assertEquals(expectedTitles[page.url], page.title,
                "title for ${page.url} does not match the page at that URL")
        }
    }

    @Test
    @DisplayName("two crawls submitted back to back both finish cleanly, with no lost pages")
    fun testBackToBackCrawlsLoseNoPages() {
        // Issue #592: a crawl that kept working after it reported completion raced
        // with the next crawl over the shared browser session, and pages were
        // silently dropped.  Submitting the second crawl while the first is still
        // running makes that interference part of the test instead of an accident
        // of CI scheduling.
        val firstTask = submitCrawl(depth = 2, args = "-refresh")
        val secondTask = submitCrawl(depth = 2, args = "-refresh")
        check(firstTask != secondTask) { "expected two distinct crawl tasks" }

        for ((label, taskId) in listOf("first" to firstTask, "second" to secondTask)) {
            val response = waitForTerminal(taskId)
            assertTrue(response.status == CrawlStatus.OK,
                "$label crawl should complete OK, got: ${response.status} error=${response.error}")
            assertNoLostPages(response)
            val pages = requireNotNull(response.pages) { "$label crawl returned no pages" }
            assertEquals(10, pages.size,
                "$label crawl: expected 10 pages (hub + 9 products), got ${pages.size}")
            for (page in pages) {
                assertEquals(fixtureTitles()[page.url], page.title,
                    "$label crawl: title for ${page.url} does not match the page at that URL")
            }
        }
    }

    // -----------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------

    /**
     * Issue #592 conservation: every page a crawl submitted is either a record
     * or a reported loss, so `pagesFound + failedPages.size == pagesExpected`.
     *
     * Asserting it here means a truncated crawl can no longer pass as a
     * complete one — and when it fails, it names the pages it lost instead of
     * only reporting a smaller page count.
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

    private fun runCrawl(depth: Int, args: String): CrawlResponse = waitForTerminal(submitCrawl(depth, args))

    private fun submitCrawl(depth: Int, args: String): String {
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

        return taskId
    }

}
