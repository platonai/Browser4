package ai.platon.browser4.common

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Unit tests for [B4UrlExtractor], the core URL extraction logic backed by RFC 3987 style
 * regular expressions. These tests focus on the public extraction API and its behaviour on
 * well-formed and edge-case inputs.
 */
class B4UrlExtractorTest {

    private val extractor = B4UrlExtractor()

    @Test
    @DisplayName("extracts a full https url including path and query")
    fun extractsFullHttpsUrlWithPathAndQuery() {
        val line = "please open https://example.com/path/to/page?q=1&lang=en#frag now"
        assertEquals("https://example.com/path/to/page?q=1&lang=en#frag", extractor.extract(line))
    }

    @Test
    @DisplayName("extracts a url without protocol when it has a known TLD")
    fun extractsUrlWithoutProtocolUsingKnownTld() {
        val line = "visit example.com for details"
        assertEquals("example.com", extractor.extract(line))
    }

    @Test
    @DisplayName("extracts an http url with ip address and port")
    fun extractsHttpUrlWithIpAndPort() {
        val line = "dashboard at http://192.168.1.1:8080/admin/index.html"
        assertEquals("http://192.168.1.1:8080/admin/index.html", extractor.extract(line))
    }

    @Test
    @DisplayName("extracts a localhost url when prefixed with a protocol")
    fun extractsLocalhostUrlWithProtocol() {
        val line = "dev server running at http://localhost:3000/health"
        assertEquals("http://localhost:3000/health", extractor.extract(line))
    }

    @Test
    @DisplayName("returns null when the line contains no url")
    fun returnsNullWhenNoUrlPresent() {
        assertNull(extractor.extract("there is no link in this sentence"))
    }

    @Test
    @DisplayName("does not match a bare host without protocol and without a known TLD")
    fun doesNotMatchBareHostWithoutTld() {
        // "localhost" has no protocol and "localhost" is not a registered TLD, so it must not match.
        assertNull(extractor.extract("connect to localhost please"))
    }

    @Test
    @DisplayName("extracts the first url when multiple urls are present")
    fun extractsFirstUrlAmongMany() {
        val line = "compare http://a.com and https://b.org/x and ftp://c.net"
        assertEquals("http://a.com", extractor.extract(line))
    }

    @Test
    @DisplayName("extractAll collects every url in the line")
    fun extractAllCollectsEveryUrl() {
        val line = "see http://a.com and https://b.org/x and http://a.com again"
        val urls = extractor.extractAll(line)
        assertEquals(setOf("http://a.com", "https://b.org/x"), urls)
    }

    @Test
    @DisplayName("extractAll returns an empty set when no url is present")
    fun extractAllReturnsEmptyWhenNoUrl() {
        assertTrue(extractor.extractAll("nothing to see here").isEmpty())
    }

    @Test
    @DisplayName("extractAll honours the provided filter predicate")
    fun extractAllHonoursFilter() {
        val line = "see http://a.com and https://b.org/x"
        val urls = extractor.extractAll(line) { !it.endsWith(".org/x") }
        assertEquals(setOf("http://a.com"), urls)
    }

    @Test
    @DisplayName("extractTo populates a caller-provided collection and supports filtering")
    fun extractToPopulatesTargetCollection() {
        val line = "links: http://a.com https://b.org/x http://c.io"
        val collected = mutableSetOf<String>()
        extractor.extractTo(line, collected) { it.startsWith("http://") }
        assertEquals(setOf("http://a.com", "http://c.io"), collected)
    }

    @Test
    @DisplayName("extracts an internationalized domain name url (punycode)")
    fun extractsInternationalizedDomainName() {
        val line = "visit https://xn--fsq.xn--fiqs8s/page now"
        val result = extractor.extract(line)
        assertNotNull(result, "punycode IDN URL should be extracted")
        assertTrue(result!!.startsWith("https://xn--"))
    }

    @Test
    @DisplayName("extracts a url embedded in surrounding punctuation")
    fun extractsUrlEmbeddedInPunctuation() {
        val line = "click (https://example.com/go) to continue."
        assertEquals("https://example.com/go", extractor.extract(line))
    }

    @Test
    @DisplayName("extracts a punycode (xn--) tld url")
    fun extractsPunycodeTld() {
        val line = "visit https://example.xn--fiqs8s now"
        assertEquals("https://example.xn--fiqs8s", extractor.extract(line))
    }
}
