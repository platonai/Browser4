package ai.platon.pulsar.chrome

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Unit tests for the pure helpers in [Browser4WebDriver.Companion].
 *
 * These cover the trickiest parts of the browser4-specific driver without
 * needing a live CDP connection: JavaScript string escaping (for embedding
 * user text and selectors into generated JS), Unicode surrogate-pair-safe
 * code-point splitting, and the constraint-aware fill JS body.
 */
@DisplayName("Browser4WebDriver helpers")
class Browser4WebDriverTest {

    // -------------------------------------------------------------------------
    // escapeJsString
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("escapeJsString leaves plain text unchanged")
    fun escapeJsStringLeavesPlainTextUnchanged() {
        assertEquals("hello world", Browser4WebDriver.escapeJsString("hello world"))
    }

    @Test
    @DisplayName("escapeJsString escapes backslash and single quote")
    fun escapeJsStringEscapesBackslashAndQuote() {
        assertEquals("a\\\\b\\'c", Browser4WebDriver.escapeJsString("a\\b'c"))
    }

    @Test
    @DisplayName("escapeJsString escapes newline and carriage return")
    fun escapeJsStringEscapesNewlineAndCarriageReturn() {
        assertEquals("a\\nb\\rc", Browser4WebDriver.escapeJsString("a\nb\rc"))
    }

    @Test
    @DisplayName("escapeJsString preserves multi-byte characters")
    fun escapeJsStringPreservesMultiByteCharacters() {
        assertEquals("你好👋", Browser4WebDriver.escapeJsString("你好👋"))
    }

    @Test
    @DisplayName("escapeJsString returns empty string for empty input")
    fun escapeJsStringEmpty() {
        assertEquals("", Browser4WebDriver.escapeJsString(""))
    }

    // -------------------------------------------------------------------------
    // escapeJsSelector
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("escapeJsSelector leaves plain selectors unchanged")
    fun escapeJsSelectorLeavesPlainSelectorUnchanged() {
        assertEquals("#input", Browser4WebDriver.escapeJsSelector("#input"))
    }

    @Test
    @DisplayName("escapeJsSelector escapes backslash and single quote")
    fun escapeJsSelectorEscapesBackslashAndQuote() {
        assertEquals("a\\\\b\\'c", Browser4WebDriver.escapeJsSelector("a\\b'c"))
    }

    // -------------------------------------------------------------------------
    // codePoints
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("codePoints splits ASCII into single characters")
    fun codePointsSplitsAscii() {
        assertEquals(listOf("a", "b", "c"), Browser4WebDriver.codePoints("abc"))
    }

    @Test
    @DisplayName("codePoints keeps a surrogate pair as one element")
    fun codePointsKeepsSurrogatePairIntact() {
        val result = Browser4WebDriver.codePoints("👋")
        assertEquals(listOf("👋"), result)
        // The single element is the full surrogate pair (length 2 in UTF-16).
        assertEquals(2, result.single().length)
    }

    @Test
    @DisplayName("codePoints mixes BMP and supplementary characters")
    fun codePointsMixesBmpAndSupplementary() {
        // U+20000 (CJK supplementary) is a surrogate pair; 'a' and '中' are BMP.
        assertEquals(
            listOf("a", "👋", "中", "\uD840\uDC00"),
            Browser4WebDriver.codePoints("a👋中\uD840\uDC00")
        )
    }

    @Test
    @DisplayName("codePoints returns empty list for empty input")
    fun codePointsEmpty() {
        assertTrue(Browser4WebDriver.codePoints("").isEmpty())
    }

    // -------------------------------------------------------------------------
    // fillValueJs
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("fillValueJs binds the element via this")
    fun fillValueJsBindsElementViaThis() {
        val js = Browser4WebDriver.fillValueJs("hello")
        assertTrue(js.contains("var el = this;"), "expected `this`-bound element: $js")
        assertFalse(js.contains("document.querySelector"), "must not use document.querySelector")
    }

    @Test
    @DisplayName("fillValueJs embeds the escaped value")
    fun fillValueJsEmbedsEscapedValue() {
        val js = Browser4WebDriver.fillValueJs("it's a\\test")
        assertTrue(js.contains("var val = 'it\\'s a\\\\test';"), "expected escaped value: $js")
    }

    @Test
    @DisplayName("fillValueJs guards readonly/disabled/maxlength")
    fun fillValueJsGuardsConstraints() {
        val js = Browser4WebDriver.fillValueJs("x")
        assertTrue(js.contains("el.disabled || el.readOnly"), "expected readonly/disabled guard")
        assertTrue(js.contains("el.maxLength"), "expected maxlength guard")
    }

    @Test
    @DisplayName("fillValueJs dispatches input and change events")
    fun fillValueJsDispatchesEvents() {
        val js = Browser4WebDriver.fillValueJs("x")
        assertTrue(js.contains("new Event('input'"), "expected input event")
        assertTrue(js.contains("new Event('change'"), "expected change event")
    }
}
