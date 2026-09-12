package ai.platon.pulsar.protocol.browser.emulator.impl

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Tests for the URL identity checks behind the snapshot origin guard.
 *
 * Regression background: Amazon answers a product URL with a 302 that only
 * swaps variant/tracking parameters (`?psc=1` -> `?th=1`, `/dp/B0X` ->
 * `/dp/B0X?th=1`).  The guard used to reject that capture as "another
 * document", failing the fetch with a `Tab origin mismatch` WebDriverException
 * even though the committed document was exactly the requested page.
 */
@Tag("Unit")
@Tag("Fast")
class UrlDocumentMatcherTest {

    @Test
    @DisplayName("same URL matches both predicates")
    fun sameUrlMatches() {
        val url = "https://www.amazon.com/dp/B0FQFB8FMG"
        assertTrue(UrlDocumentMatcher.referToSameDocument(url, url))
        assertTrue(UrlDocumentMatcher.referToSamePageIgnoringQuery(url, url))
    }

    @Test
    @DisplayName("query-only variant redirect is the same page, not the same document")
    fun queryOnlyRedirectIsSamePage() {
        val fetched = "https://www.amazon.com/TOZO-Headphones/dp/B0DGKWQMSM/ref=zg_bs?psc=1"
        val committed = "https://www.amazon.com/TOZO-Headphones/dp/B0DGKWQMSM/ref=zg_bs?th=1"

        assertFalse(
            UrlDocumentMatcher.referToSameDocument(fetched, committed),
            "the query string differs, so this is not the identical request"
        )
        assertTrue(
            UrlDocumentMatcher.referToSamePageIgnoringQuery(fetched, committed),
            "same scheme/host/path — the committed document is the requested page"
        )
    }

    @Test
    @DisplayName("bare /dp/<ASIN> redirect to ?th=1 is the same page")
    fun bareDpRedirectIsSamePage() {
        assertTrue(
            UrlDocumentMatcher.referToSamePageIgnoringQuery(
                "https://www.amazon.com/dp/B08PP5MSVB",
                "https://www.amazon.com/dp/B08PP5MSVB?th=1"
            )
        )
    }

    @Test
    @DisplayName("a different path is rejected even when the host matches")
    fun differentPathIsRejected() {
        val a = "https://example.com/product/42"
        val b = "https://example.com/product/43"

        assertFalse(UrlDocumentMatcher.referToSameDocument(a, b))
        assertFalse(
            UrlDocumentMatcher.referToSamePageIgnoringQuery(a, b),
            "a different path can be a different document — the guard must still refuse it"
        )
    }

    @Test
    @DisplayName("a different host is rejected")
    fun differentHostIsRejected() {
        assertFalse(
            UrlDocumentMatcher.referToSamePageIgnoringQuery(
                "https://example.com/p/1",
                "https://evil.example.net/p/1"
            )
        )
    }

    @Test
    @DisplayName("a different scheme is rejected")
    fun differentSchemeIsRejected() {
        assertFalse(
            UrlDocumentMatcher.referToSamePageIgnoringQuery(
                "https://example.com/p/1",
                "http://example.com/p/1"
            )
        )
    }

    @Test
    @DisplayName("a different port is rejected")
    fun differentPortIsRejected() {
        assertFalse(
            UrlDocumentMatcher.referToSamePageIgnoringQuery(
                "http://example.com:8080/p/1",
                "http://example.com:9090/p/1"
            )
        )
    }

    @Test
    @DisplayName("fragments never change the document identity")
    fun fragmentsAreIgnored() {
        assertTrue(
            UrlDocumentMatcher.referToSameDocument(
                "https://example.com/p/1#section",
                "https://example.com/p/1"
            )
        )
    }

    @Test
    @DisplayName("a trailing slash is not a different document")
    fun trailingSlashIsIgnored() {
        assertTrue(
            UrlDocumentMatcher.referToSameDocument(
                "https://example.com/p/1/",
                "https://example.com/p/1"
            )
        )
    }

    @Test
    @DisplayName("host comparison is case-insensitive")
    fun hostComparisonIsCaseInsensitive() {
        assertTrue(
            UrlDocumentMatcher.referToSameDocument(
                "https://WWW.Example.COM/p/1",
                "https://www.example.com/p/1"
            )
        )
    }

    @Test
    @DisplayName("unparseable URLs never match")
    fun unparseableUrlsDoNotMatch() {
        assertFalse(UrlDocumentMatcher.referToSameDocument("not a url", "https://example.com"))
        assertFalse(UrlDocumentMatcher.referToSamePageIgnoringQuery("", "https://example.com"))
        assertFalse(UrlDocumentMatcher.referToSamePageIgnoringQuery("about:blank", "https://example.com"))
    }
}
