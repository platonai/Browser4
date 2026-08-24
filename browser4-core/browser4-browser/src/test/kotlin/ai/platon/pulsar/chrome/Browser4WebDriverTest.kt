package ai.platon.pulsar.chrome

import ai.platon.pulsar.api.BrowserProtocol
import ai.platon.pulsar.api.model.BrowserSettings
import ai.platon.pulsar.api.model.BrowserTab
import ai.platon.pulsar.chrome.protocol.DialogEvent
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.mockito.kotlin.wheneverBlocking
import java.util.Queue

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

    // -------------------------------------------------------------------------
    // Storage state helpers (loadStorageState override)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("normalizeStorageStateCookie keeps name and value and maps optional fields")
    fun normalizeStorageStateCookieKeepsFields() {
        val cookie = mapOf(
            "name" to "session_id",
            "value" to "abc123",
            "url" to "http://127.0.0.1:47815/interactive",
            "path" to "/",
            "httpOnly" to true,
            "secure" to false,
        )
        val normalized = Browser4WebDriver.normalizeStorageStateCookie(cookie)
        assertEquals("session_id", normalized["name"])
        assertEquals("abc123", normalized["value"])
        assertEquals("http://127.0.0.1:47815/interactive", normalized["url"])
        assertEquals("/", normalized["path"])
        assertEquals(true, normalized["httpOnly"])
        assertEquals(false, normalized["secure"])
        assertFalse(normalized.containsKey("expires"), "absent expires must be dropped")
    }

    @Test
    @DisplayName("normalizeStorageStateCookie trims and coerces values")
    fun normalizeStorageStateCookieTrimsAndCoerces() {
        val normalized = Browser4WebDriver.normalizeStorageStateCookie(
            mapOf(
                "name" to "  restoredCookie ",
                "value" to 42,
                "domain" to " 127.0.0.1 ",
                "expires" to "0",
                "sameSite" to " Lax ",
            )
        )
        assertEquals("restoredCookie", normalized["name"])
        assertEquals("42", normalized["value"])
        assertEquals("127.0.0.1", normalized["domain"])
        assertEquals("Lax", normalized["sameSite"])
        assertFalse(normalized.containsKey("expires"), "expires <= 0 must be dropped")
    }

    @Test
    @DisplayName("normalizeStorageStateCookie requires a url or domain")
    fun normalizeStorageStateCookieRequiresUrlOrDomain() {
        val error = runCatching {
            Browser4WebDriver.normalizeStorageStateCookie(mapOf("name" to "x", "value" to "y"))
        }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException, "expected an IllegalArgumentException, got $error")
        assertTrue(
            error?.message?.contains("url or domain") == true,
            "expected missing url/domain message, got ${error?.message}"
        )
    }

    @Test
    @DisplayName("normalizeStorageStateCookie rejects blank names")
    fun normalizeStorageStateCookieRejectsBlankName() {
        val error = runCatching {
            Browser4WebDriver.normalizeStorageStateCookie(
                mapOf("name" to "  ", "value" to "y", "url" to "http://example.com")
            )
        }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException, "expected an IllegalArgumentException, got $error")
    }

    @Test
    @DisplayName("restoreLocalStorageScript clears and rewrites the entries array")
    fun restoreLocalStorageScriptClearsAndRewrites() {
        val script = Browser4WebDriver.restoreLocalStorageScript(
            """[{"name":"k","value":"v"}]"""
        )
        assertTrue(script.contains("window.localStorage.clear()"), "expected clear call: $script")
        assertTrue(script.contains("window.localStorage.setItem(entry.name, entry.value ?? \"\")"), "expected setItem: $script")
        assertTrue(script.contains("return entries.length"), "expected entry count return: $script")
    }

    @Test
    @DisplayName("isDocumentOriginReady accepts only an exact committed origin")
    fun isDocumentOriginReadyAcceptsOnlyExactOrigin() {
        assertTrue(Browser4WebDriver.isDocumentOriginReady("http://127.0.0.1:47815", "http://127.0.0.1:47815"))
        assertFalse(Browser4WebDriver.isDocumentOriginReady(null, "http://127.0.0.1:47815"))
        assertFalse(Browser4WebDriver.isDocumentOriginReady("", "http://127.0.0.1:47815"))
        // Opaque-origin provisional documents report "null" — not ready.
        assertFalse(Browser4WebDriver.isDocumentOriginReady("null", "http://127.0.0.1:47815"))
        // A redirect to another origin is not ready for the requested origin.
        assertFalse(
            Browser4WebDriver.isDocumentOriginReady("https://www.example.com", "http://127.0.0.1:47815")
        )
    }

    @Test
    @DisplayName("parseDragCenter reads resolved viewport coordinates")
    fun parseDragCenterReadsCoordinates() {
        assertEquals(Pair(12.5, 48.0), Browser4WebDriver.parseDragCenter("""{"x":12.5,"y":48}"""))
    }

    @Test
    @DisplayName("parseDragCenter rejects malformed or incomplete results")
    fun parseDragCenterRejectsMalformedResults() {
        assertNull(Browser4WebDriver.parseDragCenter("not-json"))
        assertNull(Browser4WebDriver.parseDragCenter("""{"x":12.5}"""))
        assertNull(Browser4WebDriver.parseDragCenter(null))
    }

    @Test
    @DisplayName("dialog acknowledgement preserves later queued dialogs")
    fun dialogAcknowledgementPreservesLaterDialogs() = runBlocking {
        val driver = dialogDriver()
        val first = DialogEvent("first", "alert", "about:blank", "", false)
        val second = DialogEvent("second", "alert", "about:blank", "", false)
        pendingDialogs(driver).addAll(listOf(first, second))

        driver.dialogDismiss()

        assertSame(second, driver.dialogHandler.peekPendingDialog())
    }

    @Test
    @DisplayName("failed CDP dialog action keeps the pending dialog")
    fun failedDialogActionKeepsPendingDialog() = runBlocking {
        val protocol = mock<BrowserProtocol>()
        val driver = dialogDriver(protocol)
        val pending = DialogEvent("pending", "alert", "about:blank", "", false)
        pendingDialogs(driver).add(pending)
        val failure = IllegalStateException("CDP failed")
        wheneverBlocking { protocol.handleJavaScriptDialog(true, null) }.thenThrow(failure)

        val thrown = runCatching { driver.dialogAccept(null) }.exceptionOrNull()

        assertSame(failure, thrown)
        assertSame(pending, driver.dialogHandler.peekPendingDialog())
    }

    private fun dialogDriver(protocol: BrowserProtocol = mock()): Browser4WebDriver {
        val browser = mock<PulsarBrowser>()
        whenever(browser.settings).thenReturn(BrowserSettings())
        return Browser4WebDriver("test", BrowserTab(), protocol, browser)
    }

    @Suppress("UNCHECKED_CAST")
    private fun pendingDialogs(driver: Browser4WebDriver): Queue<DialogEvent> {
        val field = driver.dialogHandler.javaClass.getDeclaredField("pendingDialogs")
        field.isAccessible = true
        return field.get(driver.dialogHandler) as Queue<DialogEvent>
    }
}
