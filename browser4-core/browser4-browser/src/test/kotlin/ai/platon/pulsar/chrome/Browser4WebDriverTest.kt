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
import org.junit.jupiter.api.Assertions.assertThrows
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
    // consoleMessagesJs / consoleClearJs
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("consoleMessagesJs embeds the level filter and the buffer")
    fun consoleMessagesJsEmbedsLevelAndBuffer() {
        val js = Browser4WebDriver.consoleMessagesJs("error")
        assertTrue(js.contains("minPriority['error']"), "expected level embedding: $js")
        assertTrue(js.contains("window.__b4_console"), "expected buffer: $js")
    }

    @Test
    @DisplayName("consoleClearJs clears the buffer")
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
