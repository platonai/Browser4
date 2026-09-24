package ai.platon.pulsar.chrome

import ai.platon.pulsar.api.BrowserProtocol
import ai.platon.pulsar.api.model.BrowserSettings
import ai.platon.pulsar.api.model.BrowserTab
import ai.platon.pulsar.chrome.protocol.DialogEvent
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
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
    // selectOption target probe
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("selectOptionTargetError accepts an existing target")
    fun selectOptionTargetErrorAcceptsExistingTarget() {
        assertNull(Browser4WebDriver.selectOptionTargetError("#size", true))
    }

    @Test
    @DisplayName("selectOptionTargetError reports an unresolved locator for null")
    fun selectOptionTargetErrorReportsUnresolvedLocator() {
        assertEquals(
            "Option target could not be resolved (not found or locator failure): #missing",
            Browser4WebDriver.selectOptionTargetError("#missing", null)
        )
    }

    @Test
    @DisplayName("selectOptionTargetError reports a missing target for false")
    fun selectOptionTargetErrorReportsMissingTarget() {
        assertEquals(
            "Option target not found: #missing",
            Browser4WebDriver.selectOptionTargetError("#missing", false)
        )
    }

    // -------------------------------------------------------------------------
    // input target probe (fill / type)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("inputTargetError accepts a resolvable, enabled target")
    fun inputTargetErrorAcceptsAResolvableTarget() {
        val probe = mapOf("found" to true, "kind" to "input", "disabled" to false, "readOnly" to false, "text" to "")

        assertNull(Browser4WebDriver.inputTargetError("fill", "#q", probe))
    }

    @Test
    @DisplayName("inputTargetError reports a null probe as an unmatched selector")
    fun inputTargetErrorReportsANullProbe() {
        val error = Browser4WebDriver.inputTargetError("fill", "#definitely-not-here-xyz", null)

        assertNotNull(error)
        assertTrue(error!!.startsWith("fill: no element found for selector [#definitely-not-here-xyz]"), error)
        assertTrue(error.contains("snapshot"), "the message must point at the stale-ref remedy: $error")
    }

    @Test
    @DisplayName("inputTargetError reports a probe without found=true as unmatched")
    fun inputTargetErrorReportsAnUnfoundProbe() {
        val error = Browser4WebDriver.inputTargetError("type", "#missing", mapOf("found" to false))

        assertNotNull(error)
        assertTrue(error!!.startsWith("type: no element found for selector [#missing]"), error)
    }

    @Test
    @DisplayName("inputTargetError refuses a disabled target")
    fun inputTargetErrorRefusesADisabledTarget() {
        val probe = mapOf("found" to true, "kind" to "input", "disabled" to true, "readOnly" to false)

        val error = Browser4WebDriver.inputTargetError("fill", "#q", probe)

        assertEquals("fill: target [#q] is disabled — user input is blocked.", error)
    }

    @Test
    @DisplayName("inputTargetError refuses a read-only target")
    fun inputTargetErrorRefusesAReadOnlyTarget() {
        val probe = mapOf("found" to true, "kind" to "input", "disabled" to false, "readOnly" to true)

        val error = Browser4WebDriver.inputTargetError("fill", "#q", probe)

        assertEquals("fill: target [#q] is read-only — user input is blocked.", error)
    }

    @Test
    @DisplayName("the shared input target probe binds the element and reports its state")
    fun inputTargetProbeJsReportsTheElementState() {
        val js = Browser4WebDriver.inputTargetProbeJs()

        assertTrue(js.contains("var el = this;"), "expected `this`-bound element: $js")
        assertFalse(js.contains("document.querySelector"), "must not use document.querySelector")
        assertTrue(js.contains("found: false"), "expected an unresolved-locator marker: $js")
        assertTrue(js.contains("disabled"), "expected the disabled flag: $js")
        assertTrue(js.contains("readOnly"), "expected the readOnly flag: $js")
    }

    // -------------------------------------------------------------------------
    // submitFormFallbackJs
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("submitFormFallbackJs embeds the escaped selector and submits the nearest form")
    fun submitFormFallbackJsSubmitsNearestForm() {
        val js = Browser4WebDriver.submitFormFallbackJs("input#q")
        assertTrue(js.contains("document.querySelector('input#q')"), "expected selector: $js")
        assertTrue(js.contains("'keydown'"), "expected keydown dispatch: $js")
        assertTrue(js.contains("'keypress'"), "expected keypress dispatch: $js")
        assertTrue(js.contains("'keyup'"), "expected keyup dispatch: $js")
        assertTrue(js.contains("form.requestSubmit()"), "expected requestSubmit: $js")
        assertTrue(js.contains("form.submit()"), "expected submit fallback: $js")
    }

    @Test
    @DisplayName("submitFormFallbackJs escapes quotes in the selector")
    fun submitFormFallbackJsEscapesSelector() {
        val js = Browser4WebDriver.submitFormFallbackJs("input[name='q']")
        assertTrue(js.contains("document.querySelector('input[name=\\'q\\']')"), "expected escaped selector: $js")
    }

    // -------------------------------------------------------------------------
    // The page-side console builders — FALLBACK ONLY.
    // The console path is the CDP capture (Browser4WebDriverConsoleCaptureTest and the
    // test_e2e_console_capture_* scenarios); these builders are evaluated only when the transport
    // cannot enable the Console domain, or when browser.console.capture=false.
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("the page-side fallback embeds the level filter and its own buffer")
    fun consoleMessagesJsEmbedsLevelAndBuffer() {
        val js = Browser4WebDriver.consoleMessagesJs("error")
        assertTrue(js.contains("minPriority['error']"), "expected level embedding: $js")
        assertTrue(js.contains("window.__b4_console"), "expected buffer: $js")
    }

    @Test
    @DisplayName("the page-side fallback clears its own buffer")
    fun consoleClearJsClearsBuffer() {
        assertTrue(Browser4WebDriver.consoleClearJs().contains("window.__b4_console = []"))
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
    @DisplayName("normalizeStorageStateCookie keeps explicit non-root paths (domain- and url-scoped)")
    fun normalizeStorageStateCookieKeepsNonRootPaths() {
        val domainScoped = Browser4WebDriver.normalizeStorageStateCookie(
            mapOf("name" to "session_id", "value" to "abc123", "domain" to "localhost", "path" to "/app")
        )
        assertEquals("/app", domainScoped["path"], "domain-scoped cookie must keep its explicit path")
        assertEquals("localhost", domainScoped["domain"])

        val urlScoped = Browser4WebDriver.normalizeStorageStateCookie(
            mapOf("name" to "session_id", "value" to "abc123", "url" to "http://localhost:18080/x", "path" to "/app")
        )
        assertEquals("/app", urlScoped["path"], "url-scoped cookie must keep its explicit path")
        assertEquals("http://localhost:18080/x", urlScoped["url"])
    }

    @Test
    @DisplayName("normalizeStorageStateCookie rejects paths that do not start with /")
    fun normalizeStorageStateCookieRejectsRelativePath() {
        val error = runCatching {
            Browser4WebDriver.normalizeStorageStateCookie(
                mapOf("name" to "bad_path", "value" to "v", "domain" to "localhost", "path" to "app")
            )
        }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException, "expected an IllegalArgumentException, got $error")
        assertTrue(
            error?.message?.contains("bad_path") == true,
            "expected the error to name the cookie, got ${error?.message}"
        )
        assertTrue(
            error?.message?.contains("start with '/'") == true,
            "expected the path rule in the message, got ${error?.message}"
        )
    }

    @Test
    @DisplayName("normalizeStorageStateCookie rejects cookie names the browser will not store")
    fun normalizeStorageStateCookieRejectsUnstorableNames() {
        for (badName in listOf("a b", "a;b", "a=b", "a\tb")) {
            val error = runCatching {
                Browser4WebDriver.normalizeStorageStateCookie(
                    mapOf("name" to badName, "value" to "v", "url" to "http://example.com")
                )
            }.exceptionOrNull()
            assertTrue(error is IllegalArgumentException, "expected rejection for name '$badName', got $error")
            assertTrue(
                error?.message?.contains(badName) == true,
                "expected the error to name the cookie, got ${error?.message}"
            )
        }
    }

    @Test
    @DisplayName("normalizeStorageStateCookie accepts names Chrome accepts (unicode, punctuation)")
    fun normalizeStorageStateCookieAcceptsBrowserNames() {
        // Chrome's CDP layer accepts these; the in-repo pre-validation must not
        // over-reject names a round-tripped state can legitimately contain.
        for (goodName in listOf("utf_\u540d", "quote\"name", "brace{name}", "dollar\$name", "dot.name", "a-b_c")) {
            val normalized = Browser4WebDriver.normalizeStorageStateCookie(
                mapOf("name" to goodName, "value" to "v", "domain" to "localhost")
            )
            assertEquals(goodName, normalized["name"], "name '$goodName' must be preserved")
        }
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
    @DisplayName("parseDragCenter reads resolved coordinates, css path and frame flag")
    fun parseDragCenterReadsCoordinates() {
        assertEquals(
            Browser4WebDriver.DragCenter(12.5, 48.0, "div#board > span.item", false, 1280, 900),
            Browser4WebDriver.parseDragCenter(
                """{"x":12.5,"y":48,"cssPath":"div#board > span.item","inFrame":false,"vw":1280,"vh":900}"""
            )
        )
    }

    @Test
    @DisplayName("parseDragCenter flags frame-resident elements")
    fun parseDragCenterFlagsFrameResidents() {
        val center = Browser4WebDriver.parseDragCenter(
            """{"x":1,"y":2,"cssPath":"iframe#f > div","inFrame":true}"""
        )
        assertTrue(center?.inFrame == true, "expected inFrame=true, got $center")
        // Absent inFrame defaults to false; absent viewport size defaults to 0.
        val center2 = Browser4WebDriver.parseDragCenter("""{"x":1,"y":2,"cssPath":"div"}""")
        assertTrue(center2?.inFrame == false, "expected inFrame=false, got $center2")
        assertTrue(center2?.viewportWidth == 0 && center2?.viewportHeight == 0, "expected zero viewport, got $center2")
    }

    @Test
    @DisplayName("parseDragCenter rejects malformed or incomplete results")
    fun parseDragCenterRejectsMalformedResults() {
        assertNull(Browser4WebDriver.parseDragCenter("not-json"))
        assertNull(Browser4WebDriver.parseDragCenter("""{"x":12.5}"""))
        assertNull(Browser4WebDriver.parseDragCenter("""{"x":12.5,"y":48,"cssPath":""}"""))
        assertNull(Browser4WebDriver.parseDragCenter(null))
    }

    @Test
    @DisplayName("parseDragCenter reads the element box and defaults it to zero")
    fun parseDragCenterReadsElementBox() {
        val boxed = Browser4WebDriver.parseDragCenter(
            """{"x":10,"y":20,"cssPath":"button#go","inFrame":false,"vw":1280,"vh":900,"w":96.5,"h":32}"""
        )
        assertEquals(96.5, boxed?.width)
        assertEquals(32.0, boxed?.height)

        val unboxed = Browser4WebDriver.parseDragCenter("""{"x":10,"y":20,"cssPath":"button#go"}""")
        assertEquals(0.0, unboxed?.width)
        assertEquals(0.0, unboxed?.height)
    }

    @Test
    @DisplayName("dragCenterJs reports the element box for jitter clamping")
    fun dragCenterJsReportsElementBox() {
        val js = Browser4WebDriver.dragCenterJs()
        assertTrue(js.contains("w: r.width"), "expected the element width: $js")
        assertTrue(js.contains("h: r.height"), "expected the element height: $js")
    }

    // -------------------------------------------------------------------------
    // Pointer jitter (click-family pointer moves)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("jitteredPointerPosition stays inside a large element")
    fun jitteredPointerPositionStaysInsideLargeElement() {
        // 200x40 element: the full ±2 px jitter is allowed (half of 40 px minus the 1 px inset).
        val (x, y) = Browser4WebDriver.jitteredPointerPosition(100.0, 50.0, 200.0, 40.0) { range ->
            assertEquals(Browser4WebDriver.POINTER_JITTER_PX, range)
            -range
        }
        assertEquals(98.0, x)
        assertEquals(48.0, y)
    }

    @Test
    @DisplayName("jitteredPointerPosition shrinks the offset for a small element")
    fun jitteredPointerPositionShrinksOffsetForSmallElement() {
        // 4x4 element: half of the box minus the 1 px inset leaves 1 px instead of 2 px.
        val (x, y) = Browser4WebDriver.jitteredPointerPosition(10.0, 10.0, 4.0, 4.0) { range ->
            assertEquals(1.0, range)
            range
        }
        assertEquals(11.0, x)
        assertEquals(11.0, y)
    }

    @Test
    @DisplayName("jitteredPointerPosition keeps the exact center when the box is unknown")
    fun jitteredPointerPositionKeepsCenterWithoutBox() {
        val (x, y) = Browser4WebDriver.jitteredPointerPosition(7.0, 9.0, 0.0, 0.0) { range ->
            fail("no offset may be drawn without an element box, got range=$range")
        }
        assertEquals(7.0, x)
        assertEquals(9.0, y)
    }

    @Test
    @DisplayName("jitteredPointerPosition never leaves the element box")
    fun jitteredPointerPositionNeverLeavesTheBox() {
        val boxes = listOf(
            200.0 to 40.0,
            13.0 to 13.0,
            6.0 to 6.0,
            5.0 to 40.0,
            1000.0 to 8.0,
        )

        boxes.forEach { (width, height) ->
            repeat(200) {
                val (x, y) = Browser4WebDriver.jitteredPointerPosition(500.0, 500.0, width, height)
                assertTrue(
                    x >= 500.0 - width / 2 && x <= 500.0 + width / 2,
                    "x=$x escaped a ${width}x$height box",
                )
                assertTrue(
                    y >= 500.0 - height / 2 && y <= 500.0 + height / 2,
                    "y=$y escaped a ${width}x$height box",
                )
            }
        }
    }

    @Test
    @DisplayName("jitteredPointerPosition varies between calls")
    fun jitteredPointerPositionVariesBetweenCalls() {
        val positions = (1..20).map { Browser4WebDriver.jitteredPointerPosition(100.0, 100.0, 200.0, 40.0) }
        assertTrue(positions.any { it != positions.first() }, "the pointer position must not be constant")
        positions.forEach {
            assertTrue(
                kotlin.math.abs(it.first - 100.0) <= Browser4WebDriver.POINTER_JITTER_PX &&
                    kotlin.math.abs(it.second - 100.0) <= Browser4WebDriver.POINTER_JITTER_PX,
                "offset out of range: $it",
            )
        }
    }

    @Test
    @DisplayName("dragCenterJs resolves center, css path, frame residency and viewport")
    fun dragCenterJsContainsResolutionLogic() {
        val js = Browser4WebDriver.dragCenterJs()
        assertTrue(js.contains("getBoundingClientRect"), "expected rect resolution: $js")
        assertTrue(js.contains("CSS.escape"), "expected id escaping: $js")
        assertTrue(js.contains("nth-of-type"), "expected sibling disambiguation: $js")
        assertTrue(js.contains("inFrame: this.ownerDocument !== document"), "expected frame detection: $js")
        assertTrue(js.contains("cssPath: path.join(' > ')"), "expected css path output: $js")
        assertTrue(js.contains("vw: window.innerWidth"), "expected viewport width output: $js")
        assertTrue(js.contains("vh: window.innerHeight"), "expected viewport height output: $js")
        assertTrue(js.contains("document.elementFromPoint"), "expected the hit test: $js")
    }

    @Test
    @DisplayName("hitTestJs repeats the hit test at the pressed point")
    fun hitTestJsRepeatsTheHitTestAtThePressedPoint() {
        val js = Browser4WebDriver.hitTestJs(center(270.0, 321.0))
        assertTrue(js.contains("querySelector('button#go')"), "expected the element lookup: $js")
        assertTrue(js.contains("document.elementFromPoint(270.0, 321.0)"), "expected the pressed point: $js")
        assertTrue(js.contains("el.contains(at)"), "expected the containment fallback: $js")

        val quoted = Browser4WebDriver.hitTestJs(
            Browser4WebDriver.DragCenter(1.0, 2.0, "div[data-x='a']", false, 0, 0)
        )
        assertTrue(
            quoted.contains("""querySelector('div[data-x=\'a\']')"""),
            "expected the CSS path to be escaped for the JS literal: $quoted"
        )
    }

    // -------------------------------------------------------------------------
    // Trusted clicks (CDP input instead of synthetic DOM events)
    // -------------------------------------------------------------------------

    private fun center(x: Double, y: Double, inFrame: Boolean = false, hit: Boolean = true) =
        Browser4WebDriver.DragCenter(x, y, "button#go", inFrame, 1280, 900, hit = hit)

    @Test
    @DisplayName("canClickWithTrustedInput accepts an unobstructed main-frame element")
    fun canClickWithTrustedInputAcceptsUnobstructedElement() {
        assertTrue(Browser4WebDriver.canClickWithTrustedInput(center(10.0, 20.0)))
    }

    @Test
    @DisplayName("canClickWithTrustedInput rejects unresolvable, frame-resident and occluded targets")
    fun canClickWithTrustedInputRejectsUnusableTargets() {
        assertFalse(Browser4WebDriver.canClickWithTrustedInput(null), "an unresolved element cannot be clicked")
        assertFalse(
            Browser4WebDriver.canClickWithTrustedInput(center(10.0, 20.0, inFrame = true)),
            "frame coordinates are not main-frame viewport coordinates",
        )
        assertFalse(
            Browser4WebDriver.canClickWithTrustedInput(center(10.0, 20.0, hit = false)),
            "a trusted click on an occluded point would hit whatever is on top",
        )
    }

    @Test
    @DisplayName("parseDragCenter reports whether the element is hit at its center")
    fun parseDragCenterReadsHitTest() {
        val hit = Browser4WebDriver.parseDragCenter("""{"x":1,"y":2,"cssPath":"button#go","hit":true}""")
        assertTrue(hit?.hit == true, "expected hit=true, got $hit")

        val missed = Browser4WebDriver.parseDragCenter("""{"x":1,"y":2,"cssPath":"button#go","hit":false}""")
        assertTrue(missed?.hit == false, "expected hit=false, got $missed")

        val unreported = Browser4WebDriver.parseDragCenter("""{"x":1,"y":2,"cssPath":"button#go"}""")
        assertTrue(unreported?.hit == false, "an old payload without a hit test must not be clickable: $unreported")
    }

    @Test
    @DisplayName("the trusted-click probe is installed hidden and removed again")
    fun trustedClickProbeIsHiddenAndRemoved() {
        val install = Browser4WebDriver.trustedClickProbeInstallJs()
        assertTrue(install.contains("Object.defineProperty"), "expected defineProperty: $install")
        assertTrue(install.contains("enumerable: false"), "the probe must be hidden from window enumeration: $install")
        assertTrue(install.contains("addEventListener"), "expected capture-phase listeners: $install")
        assertTrue(install.contains("event.isTrusted"), "expected the trust flag: $install")

        val read = Browser4WebDriver.trustedClickProbeReadJs()
        assertTrue(read.contains("removeEventListener"), "the probe must remove its listeners: $read")
        assertTrue(read.contains("'trusted'"), "expected the trusted outcome: $read")
        assertTrue(read.contains("'none'"), "expected the not-delivered outcome: $read")
        assertTrue(read.contains("'missing'"), "expected the tampered outcome: $read")
    }

    @Test
    @DisplayName("buildDragSequenceScript is a CDP-compatible function declaration")
    fun buildDragSequenceScriptIsFunctionDeclaration() {
        val script = Browser4WebDriver.buildDragSequenceScript(
            targetCssPath = "div#target",
            sourceX = 1.0,
            sourceY = 1.0,
            targetX = 1.0,
            targetY = 1.0,
            delays = listOf(1L, 1L, 1L, 1L),
        )
        // Runtime.callFunctionOn rejects expressions (IIFEs) with
        // "Given expression does not evaluate to a function".
        assertTrue(script.trimStart().startsWith("async function() {"), "expected function declaration: $script")
        assertFalse(script.contains("(async () =>"), "must not use an IIFE: $script")
    }

    @Test
    @DisplayName("buildDragSequenceScript fires the full lifecycle in order")
    fun buildDragSequenceScriptFiresFullLifecycleInOrder() {
        val script = Browser4WebDriver.buildDragSequenceScript(
            targetCssPath = "div#target",
            sourceX = 10.0,
            sourceY = 20.0,
            targetX = 30.0,
            targetY = 40.0,
            delays = listOf(150L, 200L, 250L, 180L),
        )
        val dragstart = script.indexOf("'dragstart'")
        val dragenter = script.indexOf("'dragenter'")
        val dragover = script.indexOf("'dragover'")
        val drop = script.indexOf("'drop'")
        val dragend = script.indexOf("'dragend'")
        assertTrue(dragstart in 0 until dragenter, "dragstart must precede dragenter")
        assertTrue(dragenter in 0 until dragover, "dragenter must precede dragover")
        assertTrue(dragover in 0 until drop, "dragover must precede drop")
        assertTrue(drop in 0 until dragend, "drop must precede dragend")
    }

    @Test
    @DisplayName("buildDragSequenceScript embeds randomized delays and the resolved target point")
    fun buildDragSequenceScriptEmbedsRandomizedDelays() {
        val delays = listOf(111L, 222L, 333L, 444L)
        val script = Browser4WebDriver.buildDragSequenceScript(
            targetCssPath = "div#target",
            sourceX = 10.5,
            sourceY = 20.25,
            targetX = 30.75,
            targetY = 40.0,
            delays = delays,
        )
        delays.forEach { delay ->
            assertTrue(script.contains("sleep($delay)"), "expected embedded delay $delay: $script")
        }
        // The resolved (jittered) target point is the default drop point for
        // the center mode and feeds the elementFromPoint occlusion pre-check.
        assertTrue(script.contains("var dropX = 30.75"), "expected embedded target x: $script")
        assertTrue(script.contains("var dropY = 40.0"), "expected embedded target y: $script")
        assertTrue(script.contains("elementFromPoint(dropX, dropY)"), "expected pre-check on the drop point: $script")
        // Center drops carry the rect branch too, but its guard is statically
        // false, so the drop point is never re-derived from the rect.
        assertTrue(
            script.contains("if (\"center\" === 'top' || \"center\" === 'bottom')"),
            "center drops must not re-derive the point from the rect: $script"
        )
    }

    @Test
    @DisplayName("buildDragSequenceScript pins top/bottom drops to the live rect edge region")
    fun buildDragSequenceScriptPinsEdgeDrops() {
        val base = listOf("top", "bottom")
        base.forEach { position ->
            val script = Browser4WebDriver.buildDragSequenceScript(
                targetCssPath = "div#target",
                sourceX = 1.0,
                sourceY = 1.0,
                targetX = 30.0,
                targetY = 40.0,
                delays = List(6) { it.toLong() + 1 },
                dropPosition = position,
            )
            assertTrue(
                script.contains("if (\"$position\" === 'top' || \"$position\" === 'bottom')"),
                "expected the edge-region branch to be selected for '$position': $script"
            )
            assertTrue(
                script.contains("dropY = \"$position\" === 'bottom' ? targetRect.bottom - 2 : targetRect.top + 2"),
                "expected an edge-region drop point for '$position': $script"
            )
            assertTrue(script.contains("if (targetRect.height > 4)"), "expected a degenerate-size guard: $script")
        }
    }

    @Test
    @DisplayName("buildDragSequenceScript sweeps dragover events only for positioned drops")
    fun buildDragSequenceScriptSweepsOnlyWhenPositioned() {
        val center = Browser4WebDriver.buildDragSequenceScript(
            targetCssPath = "div#target",
            sourceX = 1.0,
            sourceY = 1.0,
            targetX = 1.0,
            targetY = 1.0,
            delays = listOf(1L, 2L, 3L, 4L),
            dropPosition = "center",
        )
        val bottom = Browser4WebDriver.buildDragSequenceScript(
            targetCssPath = "div#target",
            sourceX = 1.0,
            sourceY = 1.0,
            targetX = 1.0,
            targetY = 1.0,
            delays = listOf(1L, 2L, 3L, 4L, 5L, 6L),
            dropPosition = "bottom",
        )
        fun dragoverCount(script: String): Int =
            Regex("'dragover'").findAll(script).count()
        assertEquals(1, dragoverCount(center), "center drops keep the legacy single dragover: $center")
        // The sweep step table is emitted once and loops at runtime, so the
        // positioned script textually carries 2 dragover dispatches which run
        // as 2 sweep events + the final dragover = 3 runtime events.
        assertEquals(2, dragoverCount(bottom), "positioned drops sweep two dragover events before the final one: $bottom")
        assertTrue(bottom.contains("sweep"), "expected the sweep step table: $bottom")
        assertTrue(bottom.contains("delay: 3") && bottom.contains("delay: 4"), "expected per-sweep-step delays: $bottom")
        assertTrue(bottom.contains("sleep(5)") && bottom.contains("sleep(6)"), "expected final dragover/drop delays: $bottom")
    }

    @Test
    @DisplayName("buildDragSequenceScript requires 6 delays for positioned drops")
    fun buildDragSequenceScriptRequiresSixDelaysWhenPositioned() {
        assertThrows(IllegalArgumentException::class.java) {
            Browser4WebDriver.buildDragSequenceScript(
                targetCssPath = "div#target",
                sourceX = 1.0,
                sourceY = 1.0,
                targetX = 1.0,
                targetY = 1.0,
                delays = listOf(1L, 2L, 3L, 4L),
                dropPosition = "top",
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            Browser4WebDriver.buildDragSequenceScript(
                targetCssPath = "div#target",
                sourceX = 1.0,
                sourceY = 1.0,
                targetX = 1.0,
                targetY = 1.0,
                delays = List(6) { 1L },
                dropPosition = "center",
            )
        }
    }

    @Test
    @DisplayName("parseDragPositionReport renders the resulting DOM placement")
    fun parseDragPositionReportRendersPlacement() {
        assertEquals(
            "Dropped li#priorityHigh as child 4 of 4 in ul#priorityList",
            Browser4WebDriver.parseDragPositionReport(
                """{"ok":true,"tag":"li","id":"priorityHigh","parentTag":"ul","parentId":"priorityList","index":3,"total":4}"""
            )
        )
        assertEquals(
            "Dropped li as child 1 of 2 in ul",
            Browser4WebDriver.parseDragPositionReport(
                """{"ok":true,"tag":"li","id":"","parentTag":"ul","parentId":"","index":0,"total":2}"""
            )
        )
        assertNull(Browser4WebDriver.parseDragPositionReport("""{"ok":false}"""))
        assertNull(Browser4WebDriver.parseDragPositionReport("""{"ok":true,"tag":"li","id":"","parentTag":"ul","parentId":"","index":4,"total":4}"""))
        assertNull(Browser4WebDriver.parseDragPositionReport(null))
        assertNull(Browser4WebDriver.parseDragPositionReport("not-json"))
    }

    @Test
    @DisplayName("dragPositionReportJs resolves tag, id and sibling placement")
    fun dragPositionReportJsContainsPlacementLogic() {
        val js = Browser4WebDriver.dragPositionReportJs()
        assertTrue(js.contains("this.tagName.toLowerCase()"), "expected tag resolution: $js")
        assertTrue(js.contains("kids.indexOf(this)"), "expected child index resolution: $js")
    }

    @Test
    @DisplayName("DragDropPosition.from normalizes case and rejects unknown values")
    fun dragPositionFromValidatesValues() {
        assertEquals("center", Browser4WebDriver.DragDropPosition.from("center").key)
        assertEquals("top", Browser4WebDriver.DragDropPosition.from("TOP").key)
        assertEquals("bottom", Browser4WebDriver.DragDropPosition.from(" bottom ").key)
        val thrown = assertThrows(IllegalArgumentException::class.java) {
            Browser4WebDriver.DragDropPosition.from("middle")
        }
        assertTrue(thrown.message.orEmpty().contains("middle"), "expected the offending value in the message")
    }

    @Test
    @DisplayName("buildDragSequenceScript guards against occluded or moved targets")
    fun buildDragSequenceScriptGuardsOcclusion() {
        val script = Browser4WebDriver.buildDragSequenceScript(
            targetCssPath = "div#target",
            sourceX = 1.0,
            sourceY = 1.0,
            targetX = 1.0,
            targetY = 1.0,
            delays = listOf(1L, 1L, 1L, 1L),
        )
        assertTrue(script.contains("b.contains(a)"), "expected containment check: $script")
        assertFalse(script.contains("a.contains(b)"), "ancestor hits must not count as related: $script")
        assertTrue(script.contains("occluded or moved"), "expected occlusion error message: $script")
        assertTrue(
            script.contains("'Target element was not found at drag time'"),
            "expected not-found-at-drag-time guard: $script"
        )
    }

    @Test
    @DisplayName("buildDragSequenceScript JSON-escapes the target css path")
    fun buildDragSequenceScriptJsonEscapesCssPath() {
        val script = Browser4WebDriver.buildDragSequenceScript(
            targetCssPath = "div#it's",
            sourceX = 1.0,
            sourceY = 1.0,
            targetX = 1.0,
            targetY = 1.0,
            delays = listOf(1L, 1L, 1L, 1L),
        )
        // The css path must be embedded as a JSON string literal so quotes are safe.
        assertTrue(script.contains("""document.querySelector("div#it's")"""), "expected JSON-quoted path: $script")
    }

    @Test
    @DisplayName("dragScriptErrorMessage reports success, script and page failures")
    fun dragScriptErrorMessageReportsFailures() {
        assertNull(Browser4WebDriver.dragScriptErrorMessage("""{"ok":true}"""))
        assertEquals(
            "Target element is occluded or moved",
            Browser4WebDriver.dragScriptErrorMessage("""{"ok":false,"error":"Target element is occluded or moved"}""")
        )
        assertEquals("Unknown drag failure", Browser4WebDriver.dragScriptErrorMessage("""{"ok":false}"""))
        assertEquals("Failed to execute drag script", Browser4WebDriver.dragScriptErrorMessage(42))
        assertEquals("Failed to execute drag script", Browser4WebDriver.dragScriptErrorMessage(null))
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

    // -------------------------------------------------------------------------
    // Capture annotations (vi and the normalizedURI link)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("viDataStatusJs short-circuits a document that already has vi data")
    fun viDataStatusJsShortCircuitsOnComputedData() {
        val js = Browser4WebDriver.viDataStatusJs()

        assertTrue(
            js.indexOf("u._viDataComputed === true") < js.indexOf("u.compute()"),
            "an already annotated document must not be computed again: $js"
        )
        assertTrue(
            js.contains("status = '${Browser4WebDriver.VI_DATA_COMPUTED}'"),
            "the short circuit must report the computed status: $js"
        )
    }

    @Test
    @DisplayName("viDataStatusJs reports a tab without the Browser4 runtime")
    fun viDataStatusJsReportsMissingRuntime() {
        val js = Browser4WebDriver.viDataStatusJs()

        assertTrue(
            js.contains("window.__pulsar_utils__"),
            "the runtime must be read defensively so a missing one does not throw: $js"
        )
        assertTrue(
            js.contains("typeof u.getAnnotatedHTML !== 'function'"),
            "a runtime without the annotated serializer must not be computed for: $js"
        )
        assertTrue(
            js.contains("'${Browser4WebDriver.VI_DATA_UNAVAILABLE}'"),
            "the missing runtime must report the unavailable status: $js"
        )
    }

    @Test
    @DisplayName("viDataStatusJs leaves a document without a body alone")
    fun viDataStatusJsSkipsDocumentsWithoutBody() {
        val js = Browser4WebDriver.viDataStatusJs()

        assertTrue(
            js.contains("!document.body || !document.body.firstChild"),
            "compute() no-ops without a body, so it must not be called: $js"
        )
        assertTrue(
            js.contains("'${Browser4WebDriver.VI_DATA_NOT_READY}'"),
            "a bodyless document must report the not-ready status: $js"
        )
    }

    @Test
    @DisplayName("viDataStatusJs reports a runtime that produced no vi data")
    fun viDataStatusJsReportsFailure() {
        val js = Browser4WebDriver.viDataStatusJs()

        assertTrue(
            js.contains("try { u.compute(); } catch (e)"),
            "a compute failure must not escape into the serialization path: $js"
        )
        assertTrue(
            js.contains(
                "u._viDataComputed === true ? '${Browser4WebDriver.VI_DATA_COMPUTED}' " +
                    ": '${Browser4WebDriver.VI_DATA_FAILED}'"
            ),
            "the flag must be verified after computing, not assumed: $js"
        )
    }

    @Test
    @DisplayName("viDataStatusJs reports the stored page URL and the live document URL")
    fun viDataStatusJsReportsLinkAndDocumentUrl() {
        val js = Browser4WebDriver.viDataStatusJs()

        assertTrue(js.contains("u._captureMetaLinks"), "the stored capture links must be read: $js")
        assertTrue(
            js.contains("'${Browser4WebDriver.CAPTURE_META_LINK_REL}'"),
            "the normalizedURI link must be the one reported: $js"
        )
        assertTrue(js.contains("document.URL"), "the live document URL must be reported: $js")
        // The three fields travel inside one JS string joined by the separator,
        // spelled as an escape so the generated source stays plain ASCII.
        val separatorEscape = Browser4WebDriver.fieldSeparatorJsEscape
        assertTrue(
            js.contains("'$separatorEscape'"),
            "the fields must be joined by the '$separatorEscape' escape: $js"
        )
        assertTrue(
            !js.contains(Browser4WebDriver.VI_DATA_FIELD_SEPARATOR),
            "the raw control character must not be emitted into the JS source: $js"
        )
    }

    @Test
    @DisplayName("the field separator escape matches the parsed separator")
    fun fieldSeparatorEscapeMatchesParsedSeparator() {
        assertEquals("\u0001", Browser4WebDriver.VI_DATA_FIELD_SEPARATOR)
        assertEquals("\\u0001", Browser4WebDriver.fieldSeparatorJsEscape)
    }

    @Test
    @DisplayName("parseViDataProbe splits the status, the stored link and the document URL")
    fun parseViDataProbeSplitsFields() {
        val probe = Browser4WebDriver.parseViDataProbe(
            "computed\u0001https://example.com/\u0001https://example.com/?th=1"
        )

        assertEquals("computed", probe?.status)
        assertEquals("https://example.com/", probe?.storedUri)
        assertEquals("https://example.com/?th=1", probe?.documentUrl)
    }

    @Test
    @DisplayName("parseViDataProbe keeps a blank link for an unannotated document")
    fun parseViDataProbeKeepsBlankLink() {
        val probe = Browser4WebDriver.parseViDataProbe("unavailable\u0001\u0001about:blank")

        assertEquals("unavailable", probe?.status)
        assertEquals("", probe?.storedUri)
        assertEquals("about:blank", probe?.documentUrl)
    }

    @Test
    @DisplayName("parseViDataProbe rejects unexpected evaluation results")
    fun parseViDataProbeRejectsUnexpectedResults() {
        assertNull(Browser4WebDriver.parseViDataProbe(null), "null is not a probe")
        assertNull(Browser4WebDriver.parseViDataProbe(42), "a number is not a probe")
        assertNull(Browser4WebDriver.parseViDataProbe("computed"), "a missing separator is not a probe")
        assertNull(
            Browser4WebDriver.parseViDataProbe("computed\u0001a\u0001b\u0001c"),
            "extra fields mean the result was not produced by the probe"
        )
    }

    @Test
    @DisplayName("storeCaptureMetaLinkJs writes the normalized URI without dropping other links")
    fun storeCaptureMetaLinkJsMergesTheLink() {
        val js = Browser4WebDriver.storeCaptureMetaLinkJs("https://example.com/a'b")

        assertTrue(
            js.contains("u._captureMetaLinks = u._captureMetaLinks || {}"),
            "existing capture links must be preserved: $js"
        )
        assertTrue(
            js.contains("""u._captureMetaLinks['normalizedURI'] = 'https://example.com/a\'b'"""),
            "the URL must be stored escaped under the normalizedURI rel: $js"
        )
    }

    @Test
    @DisplayName("a vi failure is reported once per document URL")
    fun viFailureIsReportedOncePerDocument() {
        assertTrue(
            Browser4WebDriver.shouldReportViFailure(null, "https://example.com/"),
            "the first failure on a document must be reported"
        )
        assertFalse(
            Browser4WebDriver.shouldReportViFailure("https://example.com/", "https://example.com/"),
            "a repeated read of the same failing document must stay quiet"
        )
        assertTrue(
            Browser4WebDriver.shouldReportViFailure("https://example.com/", "https://example.com/other"),
            "a failure on another document must be reported"
        )
    }

    @Test
    @DisplayName("vi statuses are distinct non-blank tokens")
    fun viStatusesAreDistinct() {
        val statuses = setOf(
            Browser4WebDriver.VI_DATA_COMPUTED,
            Browser4WebDriver.VI_DATA_NOT_READY,
            Browser4WebDriver.VI_DATA_UNAVAILABLE,
            Browser4WebDriver.VI_DATA_FAILED,
        )

        assertEquals(4, statuses.size, "the statuses must be distinguishable")
        assertTrue(statuses.none { it.isBlank() }, "statuses must not be blank: $statuses")
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
