package ai.platon.pulsar.skeleton.common.urls

import ai.platon.pulsar.common.urls.Hyperlink
import ai.platon.pulsar.skeleton.common.options.LoadOptions
import ai.platon.pulsar.skeleton.workflow.filter.AbstractScopedUrlNormalizer
import ai.platon.pulsar.skeleton.workflow.filter.ChainedUrlNormalizer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The option contract and the fragment handling of [CombinedUrlNormalizer].
 *
 * The two load flags it consults are read from the options the *url* ends up with, not from the
 * caller's copy: the argument list attached to a url (`"$url -noNorm"`, `UrlAware.args`, the args
 * `CrawlSupport.buildLinkArgs` puts on a discovered link) has the documented priority over the
 * [LoadOptions] it is merged into, and reading the caller's copy silently dropped them.
 *
 * The fragment cases pin the other half: the fragment is discarded by the normalization, so it
 * must not be able to reject the url — a bare `%` or a second sharp inside it used to make
 * `URLUtils.normalizeOrNull` return null and turned a page every browser opens into a NIL page.
 */
class CombinedUrlNormalizerTest {

    private val normalizer = CombinedUrlNormalizer()

    @Test
    @DisplayName("-noNorm written next to the url disables the normalization")
    fun noNormInTheUrlArgumentsIsHonored() {
        val url = Hyperlink("http://example.com/a#frag -noNorm", "")

        val result = normalizer.normalize(url, LoadOptions.parse(""), false)

        assertFalse(result.isNil)
        // The fragment survives, which is exactly what -noNorm asks for: without the fix the flag
        // never reached the normalization (it was read from the caller's options) and the url came
        // back without it.
        assertEquals("http://example.com/a#frag", result.url.toString())
    }

    @Test
    @DisplayName("-noNorm in UrlAware.args disables the normalization")
    fun noNormInUrlAwareArgsIsHonored() {
        val url = Hyperlink("http://example.com/a#frag", "", args = "-noNorm")

        val result = normalizer.normalize(url, LoadOptions.parse(""), false)

        assertEquals("http://example.com/a#frag", result.url.toString())
    }

    @Test
    @DisplayName("a url with no -noNorm still loses its fragment")
    fun fragmentIsDroppedWithoutTheFlag() {
        val url = Hyperlink("http://example.com/a#frag", "")

        val result = normalizer.normalize(url, LoadOptions.parse(""), false)

        assertEquals("http://example.com/a", result.url.toString())
    }

    @Test
    @DisplayName("-ignoreUrlQuery does not rewrite the url that is being loaded")
    fun ignoreUrlQueryDoesNotRewriteTheLoadedUrl() {
        // The flag is documented for *discovered* out-link hrefs (see the crawl's
        // selectDiscoveredLinks / buildLinkArgs and PulsarSession.parseNormalizedLink), where it
        // decides which queued spelling an identity has.  This call shapes the url the caller asked
        // to load, and stripping the query here made a crawl fetch `.../search` where the seed said
        // `.../search?q=x&page=3` while the result row still reported the seed.
        val url = Hyperlink("http://example.com/search?q=x&page=3", "")

        val result = normalizer.normalize(url, LoadOptions.parse("-ignoreUrlQuery"), false)

        // The query must survive — the flag concerns *discovered* out-link hrefs, not the url the
        // caller asked to load.  Its parameters come back in the canonical order `normalize` folds
        // them into (query parameters are ordered by name; see the base library's URLUtilsTest),
        // which is the page-store identity, not the address the browser is sent to.
        val loaded = result.url.toString()
        assertTrue(loaded.startsWith("http://example.com/search?"), "the query must survive: $loaded")
        assertTrue(
            loaded.contains("q=x") && loaded.contains("page=3"),
            "both parameters must survive: $loaded"
        )
    }

    @Test
    @DisplayName("-ignoreUrlQuery must not collapse the local-file urls onto one identity")
    fun ignoreUrlQueryKeepsTheLocalFilePathParameter() {
        // Every local file is `http://localfile.internal?path=<base64>`: the query *is* the
        // identity.  Stripping it here threw the path away as well and made every local file share
        // one page-store and page-cache key — the second file got the first one's page.
        val first = Hyperlink("http://localfile.internal?path=QzpcVXNlcnNcZmlyc3QudHh0", "")
        val second = Hyperlink("http://localfile.internal?path=QzpcVXNlcnNcc2Vjb25kLnR4dA==", "")
        val options = LoadOptions.parse("-ignoreUrlQuery")

        val a = normalizer.normalize(first, options, false)
        val b = normalizer.normalize(second, options, false)

        assertTrue(a.url.toString().contains("path="), "the path parameter is the file: ${a.url}")
        assertFalse(a.url.toString() == b.url.toString(), "two files must not share one identity")
    }

    @Test
    @DisplayName("an invalid escape inside the fragment does not normalize the url to NIL")
    fun invalidEscapeInFragmentIsNotFatal() {
        val options = LoadOptions.parse("")

        listOf(
            "http://example.com/a#100%",
            "http://example.com/a#x#y"
        ).forEach { spec ->
            val result = normalizer.normalize(Hyperlink(spec, ""), options, false)

            assertFalse(result.isNil, "$spec is loadable: the fragment is discarded")
            assertEquals("http://example.com/a", result.url.toString())
        }
    }

    @Test
    @DisplayName("an invalid escape in the kept part of the url is still NIL")
    fun invalidEscapeInPathIsStillFatal() {
        val result = normalizer.normalize(Hyperlink("http://example.com/a%", ""), LoadOptions.parse(""), false)

        assertTrue(result.isNil, "a malformed escape in the path is a real error")
    }

    @Test
    @DisplayName("an unparseable url with -noNorm is NIL instead of an exception")
    fun unparseableUrlWithNoNormIsNil() {
        // -noNorm skips the normalization but not the parse that builds the NormURL, and the
        // exception used to escape this method entirely.
        val url = Hyperlink("http://example.com/a#x#y -noNorm", "")

        val result = normalizer.normalize(url, LoadOptions.parse(""), false)

        assertNotNull(result)
        assertTrue(result.isNil)
    }

    @Test
    @DisplayName("a registered normalizer still gets the first word, and its rejection is NIL")
    fun registeredNormalizerRejectionIsNil() {
        val rejecting = object : AbstractScopedUrlNormalizer() {
            override fun isRelevant(url: String, scope: String) = true
            override fun normalize(url: String, scope: String): String? = null
        }
        val chain = ChainedUrlNormalizer().apply { add(rejecting) }

        val url = Hyperlink("http://example.com/a", "")
        val result = CombinedUrlNormalizer(chain).normalize(url, LoadOptions.parse(""), false)

        assertTrue(result.isNil)
    }
}
