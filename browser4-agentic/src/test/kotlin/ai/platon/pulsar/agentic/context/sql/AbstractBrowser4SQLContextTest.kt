package ai.platon.pulsar.agentic.context.sql

import ai.platon.pulsar.common.sql.SQLUtils
import ai.platon.pulsar.skeleton.common.options.LoadOptions
import ai.platon.pulsar.skeleton.common.urls.NormURL
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * The url an X-SQL statement resolves the page by.
 *
 * [AbstractBrowser4SQLContext] is the only place that undoes the X-SQL quote placeholder, and the
 * *order* is the whole point: the placeholder is `^27`, `^` is not a legal uri character, and the url
 * is parsed into a `NormURL` on the way in.
 */
@DisplayName("AbstractBrowser4SQLContext.realUrlOf")
class AbstractBrowser4SQLContextTest {

    private val options = LoadOptions.parse("")

    @Test
    @DisplayName("undoes the X-SQL quote placeholder")
    fun undoesTheQuotePlaceholder() {
        assertEquals(
            "http://example.com/o'brien",
            AbstractBrowser4SQLContext.realUrlOf(
                "http://example.com/o${SQLUtils.SINGLE_QUOTE_PLACE_HOLDER}brien"
            )
        )
    }

    @Test
    @DisplayName("leaves a url without the placeholder exactly as it is")
    fun leavesPlainUrlsAlone() {
        assertEquals("http://example.com/p", AbstractBrowser4SQLContext.realUrlOf("http://example.com/p"))
    }

    @Test
    @DisplayName("the placeholder has to be undone before the url is parsed")
    fun theUnsanitizingHasToRunBeforeTheParse() {
        // `NormURL(String)` parses with `java.net.URI`, which rejects '^' — while `java.net.URL`
        // accepts it.  A `^27`-bearing url therefore cannot become a NormURL at all, which is why the
        // un-sanitizing runs *before* `super.normalize`: it used to run after, where it could only
        // ever be a no-op (super had already refused the very urls it was meant to fix, and reported
        // them as NIL), and the NormURL it rebuilt on the way dropped `detail`.
        val sanitized = "http://example.com/o${SQLUtils.SINGLE_QUOTE_PLACE_HOLDER}brien"

        assertThrows<IllegalArgumentException> { NormURL(sanitized, options) }
        assertEquals(
            "http://example.com/o'brien",
            NormURL(AbstractBrowser4SQLContext.realUrlOf(sanitized), options).urlString
        )
    }
}
