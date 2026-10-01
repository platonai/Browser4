package ai.platon.pulsar.agentic.context.sql

import ai.platon.pulsar.common.sql.SQLUtils
import ai.platon.pulsar.common.urls.Hyperlink
import ai.platon.pulsar.skeleton.common.options.LoadOptions
import ai.platon.pulsar.skeleton.common.urls.NormURL
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.net.URL

/**
 * The url an X-SQL statement resolves the page by.
 *
 * [AbstractBrowser4SQLContext.reKeyForSql] is the one place the SQL layer re-keys a `NormURL`, and it
 * is the only place that undoes the X-SQL quote placeholder — so it is also the only place that can
 * lose the caller's `UrlAware`.
 */
@DisplayName("AbstractBrowser4SQLContext.reKeyForSql")
class AbstractBrowser4SQLContextTest {

    private val options = LoadOptions.parse("")

    @Test
    @DisplayName("undoes the X-SQL quote placeholder")
    fun undoesTheQuotePlaceholder() {
        // `java.net.URL` accepts '^' while `java.net.URI` rejects it, which is exactly why a
        // `^27`-bearing url can only exist in a NormURL built from a URL -- and why undoing the
        // placeholder is the SQL layer's job: nothing else can see that url shape at all.
        val sanitized = URL("http://example.com/o${SQLUtils.SINGLE_QUOTE_PLACE_HOLDER}brien")
        val normURL = NormURL(sanitized, options)

        val reKeyed = AbstractBrowser4SQLContext.reKeyForSql(normURL)

        assertEquals("http://example.com/o'brien", reKeyed.urlString)
    }

    @Test
    @DisplayName("keeps the url, the href and the options of a url with no placeholder")
    fun keepsTheUrlHrefAndOptions() {
        val normURL = NormURL("http://example.com/p", options, hrefSpec = "http://example.com/p#top")

        val reKeyed = AbstractBrowser4SQLContext.reKeyForSql(normURL)

        assertEquals("http://example.com/p", reKeyed.urlString)
        assertEquals("http://example.com/p#top", reKeyed.hrefSpec)
        assertEquals(options, reKeyed.options)
    }

    @Test
    @DisplayName("keeps detail, so the caller's own url object is still reachable")
    fun keepsTheCallersDetail() {
        // `detail` is the UrlAware the caller normalized.  Dropping it left a caller that kept a
        // handle on its own url object with nothing to read back, and killed the referrer fallback
        // (`NormURL.referrer` is `options.referrer ?: detail?.referrer`).
        val page = Hyperlink("http://example.com/p", "", referrer = "http://example.com/from")
        val normURL = NormURL("http://example.com/p", options, detail = page)

        val reKeyed = AbstractBrowser4SQLContext.reKeyForSql(normURL)

        assertSame(page, reKeyed.detail, "the caller's url object must survive the re-key")
        assertEquals("http://example.com/from", reKeyed.referrer)
    }

    @Test
    @DisplayName("a NIL url stays NIL and keeps its detail")
    fun keepsNilUrlsNil() {
        val page = Hyperlink("http://example.com/p", "")
        val nil = NormURL.createNil(page)

        val reKeyed = AbstractBrowser4SQLContext.reKeyForSql(nil)

        assertTrue(reKeyed.isNil)
        assertNotNull(reKeyed.detail)
    }
}