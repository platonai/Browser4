package ai.platon.pulsar.rest.api.service.crawl

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Tests for the reporting helpers that used to be private members of
 * [CrawlService] and now live in `CrawlSupport.kt`.
 *
 * They are all pure functions over crawl data, so their contracts — URL
 * canonicalization, the title fallback, pattern filtering and the wording of
 * the loss/readonly notes the CLI surfaces — are pinned here instead of only
 * through a browser run.
 */
class CrawlSupportTest {

    // ------------------------------------------------------------------
    // normalizeForVisit
    // ------------------------------------------------------------------

    @Test
    @DisplayName("URL canonicalization strips the fragment, the query and a trailing slash")
    fun testNormalizeForVisitCanonicalizes() {
        assertEquals(
            "https://example.com/product/1",
            normalizeForVisit("https://example.com/product/1")
        )
        assertEquals(
            "https://example.com/product/1",
            normalizeForVisit("  HTTPS://Example.com/product/1/  ")
        )
        // A query string or a fragment must not create a second identity for the
        // same page, or the crawl would submit it twice.
        assertEquals(
            normalizeForVisit("https://example.com/product/1?utm_source=x"),
            normalizeForVisit("https://example.com/product/1#details")
        )
    }

    @Test
    @DisplayName("a fragment-only href canonicalizes onto the portal URL it was resolved against")
    fun testNormalizeForVisitCollapsesFragmentOnlyHref() {
        // Resolving '#' against the portal appends it to the URL; the dedupe key
        // must be the fragment-less form or the crawl reports a hollow extra page.
        assertEquals(
            normalizeForVisit("https://example.com/hub.html"),
            normalizeForVisit("https://example.com/hub.html#")
        )
    }

    // ------------------------------------------------------------------
    // extractTitleFromHtml
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the title fallback reads <title> out of raw HTML")
    fun testExtractTitleFromHtml() {
        assertEquals(
            "Crawl Test Hub",
            extractTitleFromHtml("<html><head><title>  Crawl Test Hub  </title></head></html>")
        )
        // Attributes and casing vary in the wild.
        assertEquals(
            "Widget",
            extractTitleFromHtml("""<TITLE lang="en" data-x="1">Widget</TITLE>""")
        )
    }

    @Test
    @DisplayName("the title fallback returns null instead of inventing a title")
    fun testExtractTitleFromHtmlReturnsNullWhenAbsent() {
        assertNull(extractTitleFromHtml(null))
        assertNull(extractTitleFromHtml(""))
        assertNull(extractTitleFromHtml("<html><body>no title here</body></html>"))
        assertNull(extractTitleFromHtml("<html><head><title>   </title></head></html>"))
    }

    // ------------------------------------------------------------------
    // matchesPattern
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a blank or catch-all out-link pattern admits every URL")
    fun testMatchesPatternAdmitsEverythingWithoutAPattern() {
        assertTrue(matchesPattern("https://example.com/a", null))
        assertTrue(matchesPattern("https://example.com/a", ""))
        assertTrue(matchesPattern("https://example.com/a", "   "))
        assertTrue(matchesPattern("https://example.com/a", ".+"))
    }

    @Test
    @DisplayName("an out-link pattern filters the URLs it does not match")
    fun testMatchesPatternFilters() {
        assertTrue(matchesPattern("https://example.com/product/1", "/product/"))
        assertFalse(matchesPattern("https://example.com/category/1", "/product/"))
    }

    @Test
    @DisplayName("an invalid pattern admits everything instead of emptying the crawl")
    fun testInvalidPatternIsNotAFilter() {
        // A broken regex must not silently turn a crawl into "no links found".
        assertTrue(matchesPattern("https://example.com/a", "([unclosed"))
    }

    // ------------------------------------------------------------------
    // buildLossNote
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a crawl that lost nothing has no loss note")
    fun testNoLossesProduceNoNote() {
        assertNull(buildLossNote(pagesFound = 10, pagesExpected = 10, failedPages = emptyList()))
    }

    @Test
    @DisplayName("the loss note names the lost pages and their reason")
    fun testLossNoteNamesTheLosses() {
        val note = buildLossNote(
            pagesFound = 8,
            pagesExpected = 10,
            failedPages = listOf(
                CrawlFailedPage("https://example.com/2.html", 1, 1601, "refused"),
                CrawlFailedPage("https://example.com/7.html", 2, 0)
            )
        )

        requireNotNull(note)
        assertTrue(note.contains("2 of 10 page(s) were submitted but never delivered (8 recorded)"), note)
        assertTrue(note.contains("https://example.com/2.html (depth=1, status=1601, refused)"), note)
        // A page with no protocol status must not print a fake "status=0".
        assertTrue(note.contains("https://example.com/7.html (depth=2)"), note)
    }

    @Test
    @DisplayName("the loss note caps the URLs it spells out but never the count")
    fun testLossNoteIsCappedButExact() {
        val failed = (1..8).map { CrawlFailedPage("https://example.com/$it.html", 1, 0) }

        val note = requireNotNull(buildLossNote(pagesFound = 2, pagesExpected = 10, failedPages = failed))

        assertTrue(note.contains("8 of 10 page(s)"), note)
        assertTrue(note.contains("(+3 more)"), "the omitted pages are still accounted for: $note")
        assertFalse(note.contains("https://example.com/6.html"), "only the first five URLs are named: $note")
    }

    // ------------------------------------------------------------------
    // buildReadonlyNote
    // ------------------------------------------------------------------

    @Test
    @DisplayName("--readonly says so when every page was fetched fresh")
    fun testReadonlyNoteWhenEverythingWasFresh() {
        val note = buildReadonlyNote(listOf(CrawlPageResult("https://example.com/a")))

        assertTrue(note.startsWith("readonly: verified fresh"), note)
        assertTrue(note.contains("all 1 page(s) fetched from the live site"), note)
    }

    @Test
    @DisplayName("--readonly reports how much was served from the store, and how old it was")
    fun testReadonlyNoteWhenPagesCameFromTheStore() {
        val note = buildReadonlyNote(
            listOf(
                CrawlPageResult("https://example.com/a", servedFromStore = true, storeAgeSeconds = 3661),
                CrawlPageResult("https://example.com/b", servedFromStore = true, storeAgeSeconds = 30),
                CrawlPageResult("https://example.com/c")
            )
        )

        assertTrue(note.contains("2/3 page(s) served from the page store"), note)
        // The oldest stored content is the one that bounds how fresh the crawl is.
        assertTrue(note.contains("(stored content up to 1h 1m old)"), note)
        assertTrue(note.contains("1 fetched fresh"), note)
    }
}
