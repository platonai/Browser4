package ai.platon.pulsar.protocol.browser.emulator.impl

import ai.platon.pulsar.common.urls.URLUtils
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
 *
 * The same happened to local-file fetches: the driver serves
 * `http://localfile.internal?path=<base64>` by navigating to that path's
 * `file://` URL, which the guard could not recognise as the requested document
 * either.
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

    @Test
    @DisplayName("a local-file fetch is the same document as the file:// URL of its own path")
    fun localFileFetchMatchesItsFile() {
        val path = sampleLocalFilePath()
        val fetched = URLUtils.pathToLocalURL(path)
        val committed = path.toUri().toString()

        assertTrue(
            UrlDocumentMatcher.referToSameLocalFile(committed, fetched),
            "the committed document is exactly the file the fetch encoded"
        )
        assertFalse(
            UrlDocumentMatcher.referToSameDocument(committed, fetched),
            "as URLs the two never match — the decoded path is the only evidence"
        )
    }

    @Test
    @DisplayName("a local-file fetch does not match a different file")
    fun localFileFetchDoesNotMatchAnotherFile() {
        val fetched = URLUtils.pathToLocalURL(sampleLocalFilePath())
        val otherFile = sampleLocalFilePath().resolveSibling("other.html").toUri().toString()

        assertFalse(
            UrlDocumentMatcher.referToSameLocalFile(otherFile, fetched),
            "an earlier local-file fetch's document must still be rejected"
        )
    }

    @Test
    @DisplayName("the local-file check only applies when both sides are local files")
    fun localFileCheckNeedsBothSidesLocal() {
        val committed = sampleLocalFilePath().toUri().toString()
        val fetched = URLUtils.pathToLocalURL(sampleLocalFilePath())

        assertFalse(
            UrlDocumentMatcher.referToSameLocalFile(committed, "https://example.com/test.html"),
            "a file:// document is foreign content for a web fetch"
        )
        assertFalse(
            UrlDocumentMatcher.referToSameLocalFile("https://example.com/test.html", fetched),
            "a web document is not the file a local-file fetch asked for"
        )
    }

    @Test
    @DisplayName("a malformed local-file URL never matches")
    fun malformedLocalFileUrlDoesNotMatch() {
        val committed = sampleLocalFilePath().toUri().toString()

        assertFalse(UrlDocumentMatcher.referToSameLocalFile(committed, "http://localfile.internal"))
        assertFalse(
            UrlDocumentMatcher.referToSameLocalFile(
                committed,
                "http://localfile.internal?path=not-base64!!"
            )
        )
    }

    /** An absolute path in the OS temp dir, so the test holds on every platform. */
    private fun sampleLocalFilePath(): java.nio.file.Path =
        java.nio.file.Path.of(System.getProperty("java.io.tmpdir")).resolve("pulsar").resolve("test.html")
}
