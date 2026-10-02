package ai.platon.pulsar.skeleton.common.urls

import ai.platon.pulsar.common.urls.Hyperlink
import ai.platon.pulsar.common.urls.UrlAware
import ai.platon.pulsar.skeleton.common.options.LoadOptions
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import org.junit.jupiter.api.DisplayName

class CombinedScopedUrlNormalizerTest {

    @Test
        @DisplayName("test normalize with valid url and options")
    fun testNormalizeWithValidUrlAndOptions() {
        val urlAware = mock(UrlAware::class.java)
        `when`(urlAware.url).thenReturn("http://example.com")
        `when`(urlAware.args).thenReturn("arg1=val1")

        val options = LoadOptions.parse("")
        options.priority = 10

        val normalizer = CombinedUrlNormalizer()
        val result = normalizer.normalize(urlAware, options, false)

        assertNotNull(result)
        // A url with no path *is* the root path: `normalize` folds it to `/` (RFC 3986 §6.2.3), and
        // its result is the page-store key the whole pipeline looks a page up by — see the base
        // library's URLUtilsTest.
        assertEquals("http://example.com/", result.url.toString())
    }

    @Test
        @DisplayName("test normalize with invalid url")
    fun testNormalizeWithInvalidUrl() {
        val urlAware = mock(UrlAware::class.java)
        `when`(urlAware.url).thenReturn("invalid-url")

        val options = LoadOptions.parse("")


        val normalizer = CombinedUrlNormalizer()
        val result = normalizer.normalize(urlAware, options, false)

        assertTrue { result.isNil }
    }

    @Test
        @DisplayName("test priority overriding")
    fun testPriorityOverriding() {
        val urlAware = Hyperlink("http://example.com", "", args = "-priority -2000")

        val options = LoadOptions.parse("-priority -3000")
        assertEquals(-3000, options.priority)
        options.priority = 10
        assertEquals(10, options.priority)

        val normalizer = CombinedUrlNormalizer()
        val result = normalizer.normalize(urlAware, options, false)

        assertNotNull(result)
        // See testNormalizeWithValidUrlAndOptions: the empty path is the root.
        assertEquals("http://example.com/", result.url.toString())
        val detail = result.detail
        assertNotNull(detail)
        requireNotNull(detail)
        assertEquals(-2000, detail.priority)
    }

    @Test
        @DisplayName("test normalize with null urlNormalizers")
    fun testNormalizeWithNullUrlnormalizers() {
        val urlAware = mock(UrlAware::class.java)
        `when`(urlAware.url).thenReturn("http://example.com")

        val options = LoadOptions.parse("")

        val normalizer = CombinedUrlNormalizer(null)
        val result = normalizer.normalize(urlAware, options, false)

        assertNotNull(result)
        // See testNormalizeWithValidUrlAndOptions: the empty path is the root.
        assertEquals("http://example.com/", result.url.toString())
    }

    @Test
        @DisplayName("test createLoadOptions")
    fun testCreateloadoptions() {
        val urlAware = mock(UrlAware::class.java)
        val options = LoadOptions.parse("")

        val normalizer = CombinedUrlNormalizer()
        val result = normalizer.createLoadOptions(urlAware, options, false)

        assertNotNull(result)
    }

    @Test
        @DisplayName("test createLoadOptions0")
    fun testCreateloadoptions0() {
        val urlAware = mock(UrlAware::class.java)
        val options = LoadOptions.parse("")

        val normalizer = CombinedUrlNormalizer()
        val result = normalizer.createLoadOptions0(urlAware, options)

        assertNotNull(result)
    }

    @Test
        @DisplayName("test normalize with empty url")
    fun testNormalizeWithEmptyUrl() {
        val urlAware = mock(UrlAware::class.java)
        `when`(urlAware.url).thenReturn("")

        val options = LoadOptions.parse("")

        val normalizer = CombinedUrlNormalizer()
        val result = normalizer.normalize(urlAware, options, false)

        assertTrue(result.isNil)
    }

    @Test
        @DisplayName("test normalize keeps the path and only drops the fragment")
    fun testNormalizeWithSpecialCharactersInUrlNotSupported() {
        val urlAware = mock(UrlAware::class.java)
        `when`(urlAware.url).thenReturn("http://example.com/!@#$%^&*()")

        val options = LoadOptions.parse("")

        val normalizer = CombinedUrlNormalizer()
        val result = normalizer.normalize(urlAware, options, false)

        assertNotNull(result)
        // `$%^&*()` is the fragment — a bare '%' is not a legal escape — and it is what the
        // normalization discards.  This used to normalize to NIL: the uri was built with the
        // fragment still attached, so a part that was about to be thrown away could reject a url
        // every browser loads.  `!@` is the path and stays.
        assertFalse(result.isNil, "a discarded fragment must not reject the url")
        assertEquals("http://example.com/!@", result.url.toString())
    }

    @Test
        @DisplayName("test normalize with very long url")
    fun testNormalizeWithVeryLongUrl() {
        val longUrl = "http://example.com/" + "a".repeat(5000)
        val urlAware = mock(UrlAware::class.java)
        `when`(urlAware.url).thenReturn(longUrl)

        val options = LoadOptions.parse("")

        val normalizer = CombinedUrlNormalizer()
        val result = normalizer.normalize(urlAware, options, false)

        assertNotNull(result)
        assertEquals(longUrl, result.url.toString())
    }

    @Test
        @DisplayName("test normalize truncates an unencoded space, a known limitation")
    fun testNormalizeWithUrlContainingSpacesNotSupported() {
        val urlAware = mock(UrlAware::class.java)
        `when`(urlAware.url).thenReturn("http://example.com/with spaces-not-supported")

        val options = LoadOptions.parse("")

        val normalizer = CombinedUrlNormalizer()
        val result = normalizer.normalize(urlAware, options, false)

        assertNotNull(result)
        // A url and its trailing argument list are one whitespace-separated string
        // (`"$url -expires 1s"`), so the first space ends the url — a raw space inside a url reads
        // as the start of the args.  Pinned as the documented behaviour, not as a promise: an
        // unencoded space silently truncates the url today, tracked as S1 in
        // docs-dev/copilot/url-normalization-review.md (the fix belongs to the goto/load input
        // validation, not to the normalizer).
        assertEquals("http://example.com/with", result.url.toString())
    }
}
