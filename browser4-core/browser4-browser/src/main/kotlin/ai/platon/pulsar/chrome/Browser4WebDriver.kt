package ai.platon.pulsar.chrome

import ai.platon.pulsar.api.BrowserProtocol
import ai.platon.pulsar.api.model.BrowserTab
import ai.platon.pulsar.api.model.WebDriverException
import ai.platon.pulsar.chrome.network.RobustRPC
import ai.platon.pulsar.chrome.protocol.Keyboard
import ai.platon.pulsar.chrome.protocol.util.withNodeObjectId
import ai.platon.pulsar.chrome.util.ChromeDriverException
import ai.platon.pulsar.common.getLogger
import ai.platon.pulsar.common.serialize.json.pulsarObjectMapper
import ai.platon.pulsar.common.urls.URLUtils
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kotlinx.coroutines.delay
import kotlin.random.Random

/**
 * Browser4-specific extension of [PulsarWebDriver].
 *
 * This class is the extension point for all browser4-specific features, bug fixes,
 * and new requirements that go beyond the core functionality provided by
 * [ai.platon.pulsar.chrome.PulsarWebDriver] from the `pulsar-browser` library.
 *
 * ## Relationship to pulsar-browser
 *
 * `pulsar-browser:4.11.2` was extracted from `browser4-browser` as a standalone
 * library to simplify this repository and reduce build time.  All types previously
 * in `ai.platon.browser4.*` now live in `ai.platon.pulsar.*` within that library.
 *
 * This class extends [PulsarWebDriver] directly — it is the **only** production
 * source file remaining in the `browser4-browser` module.  Everything else is
 * provided by the `pulsar-browser` dependency.
 *
 * ## Extension guide
 *
 * All further extensions should use [executeCdpCommand] to implement new
 * functionality.  This method provides low-level access to the Chrome DevTools
 * Protocol without coupling extension code to internal implementation details:
 *
 * ```kotlin
 * // Example: custom CDP command
 * val result = executeCdpCommand(
 *     "Page.captureScreenshot",
 *     mapOf("format" to "png", "fromSurface" to true)
 * )
 * ```
 *
 * [executeCdpCommand] delegates to [BrowserProtocol.executeCdpCommand] through
 * the robust RPC layer, giving callers the same retry / error-handling
 * guarantees as every other driver operation.
 *
 * @see PulsarWebDriver
 * @see executeCdpCommand
 */
open class Browser4WebDriver(
    uniqueID: String,
    chromeTab: BrowserTab,
    browserProtocol: BrowserProtocol,
    browser: PulsarBrowser
) : PulsarWebDriver(uniqueID, chromeTab, browserProtocol, browser) {

    /**
     * Viewport center of a drag element, plus the stable CSS path used to
     * re-locate it inside the drag script (where CDP node object ids are
     * not available for the target), a frame-residency flag, and the viewport
     * size at resolution time (used to confirm the target is actually
     * visible after an asynchronous scroll commit).
     */
    internal data class DragCenter(
        val x: Double,
        val y: Double,
        val cssPath: String,
        val inFrame: Boolean,
        val viewportWidth: Int = 0,
        val viewportHeight: Int = 0,
    )

    companion object {
        private val logger = getLogger(Browser4WebDriver::class)

        private val storageStateMapper: ObjectMapper = jacksonObjectMapper()
            .setDefaultPropertyInclusion(JsonInclude.Include.NON_NULL)

        private data class StorageStatePayload(
            val cookies: List<Map<String, Any?>> = emptyList(),
            val origins: List<StorageStateOriginPayload> = emptyList(),
        )

        private data class StorageStateOriginPayload(
            val origin: String = "",
            val localStorage: List<StorageStateEntryPayload> = emptyList(),
        )

        private data class StorageStateEntryPayload(
            val name: String = "",
            val value: String = "",
        )

        private data class StorageStateLoadSummary(
            val cookies: Int,
            val origins: Int,
            val localStorageEntries: Int,
        )

        /**
         * Create a [Browser4WebDriver] from an existing [PulsarWebDriver],
         * reusing its underlying CDP connection, tab, and browser.
         *
         * Both drivers share the same [BrowserProtocol], [BrowserTab], and
         * [PulsarBrowser], so no CDP connections are torn down or duplicated.
         * Callers should unbind the original driver and bind the returned
         * instance.
         */
        fun from(driver: PulsarWebDriver): Browser4WebDriver =
            Browser4WebDriver(
                uniqueID = driver.guid,
                chromeTab = driver.chromeTab,
                browserProtocol = driver.browserProtocol,
                browser = driver.browser,
            )

        /**
         * Escape [text] for embedding as a single-quoted JavaScript string literal.
         * Escapes backslash, single quote, newline, and carriage return.
         */
        fun escapeJsString(text: String): String =
            text.replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\n", "\\n")
                .replace("\r", "\\r")

        /**
         * Escape [selector] for embedding inside a single-quoted JavaScript string
         * literal (e.g. `document.querySelector('<selector>')`).  Escapes backslash
         * and single quote.
         */
        fun escapeJsSelector(selector: String): String =
            selector.replace("\\", "\\\\").replace("'", "\\'")

        /**
         * Split [text] into complete Unicode code points, preserving surrogate pairs
         * (emoji, CJK supplementary ideographs).  The upstream `Keyboard.type()` walks
         * the string with `charAt()` (UTF-16 code units), which splits surrogate pairs
         * into invalid halves.  Iterating the returned list inserts each complete code
         * point in a single CDP `Input.insertText` call.
         */
        fun codePoints(text: String): List<String> {
            val result = mutableListOf<String>()
            var i = 0
            while (i < text.length) {
                val codePoint = text.codePointAt(i)
                val charCount = Character.charCount(codePoint)
                result.add(text.substring(i, i + charCount))
                i += charCount
            }
            return result
        }

        /**
         * The `function()` body used by [fillSafe] (and the executor's fallback) to
         * set an element's value while honoring user-input constraints.  Evaluated
         * with `this` bound to the target element (via `callFunctionOn`).
         */
        fun fillValueJs(text: String): String =
            """
            function() {
                var el = this;
                if (!el) { return; }
                if (el.disabled || el.readOnly) { return; }
                var val = '${escapeJsString(text)}';
                var maxLen = el.maxLength;
                if (maxLen > 0 && val.length > maxLen) { val = val.substring(0, maxLen); }
                if (el.isContentEditable) {
                    el.textContent = val;
                } else if (el.type === 'number' || el.type === 'range') {
                    var numVal = parseFloat(val);
                    if (!isNaN(numVal)) { el.valueAsNumber = numVal; }
                    else { el.value = val; }
                } else {
                    el.value = val;
                }
                if (typeof el.focus === 'function') { el.focus(); }
                el.dispatchEvent(new Event('input', { bubbles: true }));
                el.dispatchEvent(new Event('change', { bubbles: true }));
            }
            """.trimIndent()

        /**
         * Normalize a cookie from a storage-state JSON payload for
         * `Network.setCookies`.  Mirrors the upstream pulsar-browser
         * normalization so states saved by `tab.saveStorageState()` round-trip
         * unchanged.
         *
         * @param cookie Raw cookie entry from the storage-state payload.
         * @return A map with the canonical `Network.setCookies` field set.
         */
        fun normalizeStorageStateCookie(cookie: Map<String, Any?>): Map<String, Any?> {
            val name = cookie["name"]?.toString()?.trim().orEmpty()
            require(name.isNotEmpty()) { "Storage state cookie name must not be blank" }

            val normalized = linkedMapOf<String, Any?>(
                "name" to name,
                "value" to (cookie["value"]?.toString() ?: ""),
            )

            cookie["url"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { normalized["url"] = it }
            cookie["domain"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { normalized["domain"] = it }
            cookie["path"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { normalized["path"] = it }
            cookie["expires"]?.toString()?.toDoubleOrNull()?.takeIf { it > 0 }?.let { normalized["expires"] = it }
            cookie["httpOnly"]?.toString()?.toBooleanStrictOrNull()?.let { normalized["httpOnly"] = it }
            cookie["secure"]?.toString()?.toBooleanStrictOrNull()?.let { normalized["secure"] = it }
            cookie["sameSite"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { normalized["sameSite"] = it }

            require("url" in normalized || "domain" in normalized) {
                "Storage state cookie '$name' must include either url or domain"
            }
            return normalized
        }

        /**
         * Build the JavaScript used to restore localStorage entries for a single
         * origin.  [entriesJson] must be a JSON array of `{name, value}` objects.
         */
        fun restoreLocalStorageScript(entriesJson: String): String =
            """
            (() => {
              const entries = $entriesJson;
              window.localStorage.clear();
              for (const entry of entries) {
                window.localStorage.setItem(entry.name, entry.value ?? "");
              }
              return entries.length;
            })()
            """.trimIndent()

        /**
         * True once the evaluated `location.origin` has committed to exactly
         * [targetOrigin].  Opaque-origin documents report `"null"` (or fail to
         * evaluate at all), so neither case counts as ready.
         */
        fun isDocumentOriginReady(evaluatedOrigin: String?, targetOrigin: String): Boolean =
            !evaluatedOrigin.isNullOrBlank() && evaluatedOrigin.trim() == targetOrigin

        /**
         * Parse the JSON produced by [dragCenterJs] into a [DragCenter].
         * Returns null when the value is missing, malformed, or lacks a usable
         * CSS path (which makes the element unre-locatable at drag time).
         */
        internal fun parseDragCenter(value: Any?): DragCenter? {
            val json = value as? String ?: return null
            val node = runCatching { pulsarObjectMapper().readTree(json) }.getOrNull() ?: return null
            val x = node.get("x")?.takeIf { it.isNumber }?.asDouble() ?: return null
            val y = node.get("y")?.takeIf { it.isNumber }?.asDouble() ?: return null
            val cssPath = node.get("cssPath")?.asText()?.takeIf { it.isNotBlank() } ?: return null
            return DragCenter(
                x = x,
                y = y,
                cssPath = cssPath,
                inFrame = node.get("inFrame")?.asBoolean() ?: false,
                viewportWidth = node.get("vw")?.takeIf { it.isNumber }?.asInt() ?: 0,
                viewportHeight = node.get("vh")?.takeIf { it.isNumber }?.asInt() ?: 0,
            )
        }

        /**
         * The `function()` body evaluated with `this` bound to a drag element.
         * Returns the element's viewport center, a stable CSS path that
         * re-locates the same element from the top document at drag time, and
         * whether the element lives in a frame (drag coordinates and
         * `document.elementFromPoint` are frame-relative then, so frame
         * residents must be rejected explicitly rather than silently mis-dragged).
         */
        internal fun dragCenterJs(): String =
            """
            function() {
                if (!(this instanceof Element)) {
                    return JSON.stringify({ cssPath: '' });
                }
                const r = this.getBoundingClientRect();
                const path = [];
                let el = this;
                while (el && el.nodeType === 1) {
                    let part = el.tagName.toLowerCase();
                    if (el.id) {
                        part += '#' + CSS.escape(el.id);
                        path.unshift(part);
                        break;
                    }
                    if (el.parentElement) {
                        const sameTag = Array.prototype.filter.call(
                            el.parentElement.children,
                            (c) => c.tagName === el.tagName
                        );
                        if (sameTag.length > 1) {
                            part += ':nth-of-type(' + (sameTag.indexOf(el) + 1) + ')';
                        }
                    }
                    path.unshift(part);
                    el = el.parentElement;
                }
                return JSON.stringify({
                    x: r.left + r.width / 2,
                    y: r.top + r.height / 2,
                    cssPath: path.join(' > '),
                    inFrame: this.ownerDocument !== document,
                    vw: window.innerWidth,
                    vh: window.innerHeight
                });
            }
            """.trimIndent()

        /**
         * Build the drag sequence script executed with `this` bound to the
         * source element (via CDP `callFunctionOn`).  The target is re-located
         * by [targetCssPath] and must still be hit by the resolved viewport
         * point — otherwise the drag fails loudly instead of dispatching on an
         * unrelated element (occluded by an overlay, `pointer-events: none`,
         * or moved by an async layout shift).
         *
         * All failures are reported before any event is dispatched, so a retry
         * of the outer RPC block re-runs the sequence idempotently.
         *
         * [delays] must contain exactly 4 randomized inter-event delays (ms).
         */
        internal fun buildDragSequenceScript(
            targetCssPath: String,
            sourceX: Double,
            sourceY: Double,
            targetX: Double,
            targetY: Double,
            delays: List<Long>,
        ): String {
            require(delays.size == 4) { "drag sequence requires exactly 4 delays" }
            val targetJson = pulsarObjectMapper().writeValueAsString(targetCssPath)
            // CDP Runtime.callFunctionOn requires a *function declaration*, not
            // an expression — an IIFE is rejected with "Given expression does
            // not evaluate to a function".  `async function()` + awaitPromise
            // gives us the same async sequencing inside a valid declaration.
            return """
                async function() {
                    const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
                    const source = this;
                    if (typeof DataTransfer === 'undefined' || typeof DragEvent === 'undefined') {
                        return JSON.stringify({
                            ok: false,
                            error: 'HTML5 drag-and-drop APIs are not available in the current page context'
                        });
                    }
                    const target = document.querySelector($targetJson);
                    if (!target) {
                        return JSON.stringify({
                            ok: false,
                            error: 'Target element was not found at drag time'
                        });
                    }
                    const hit = document.elementFromPoint($targetX, $targetY);
                    // The hit must be the target itself or one of its
                    // descendants (b.contains(a): the target contains the
                    // hit).  A hit on an *ancestor* means the target is not
                    // hittable at that point (pointer-events:none, or it
                    // moved) — dispatching there would silently drop onto the
                    // wrong element.
                    const related = (a, b) => a === b || (!!a && !!b && b.contains(a));
                    if (!related(hit, target)) {
                        return JSON.stringify({
                            ok: false,
                            error: 'Target element is occluded or moved: the resolved point is covered by another element'
                        });
                    }
                    const dataTransfer = new DataTransfer();
                    const fire = (element, type, clientX, clientY) => {
                        const event = new DragEvent(type, {
                            bubbles: true,
                            cancelable: true,
                            composed: true,
                            dataTransfer,
                            clientX,
                            clientY
                        });
                        element.dispatchEvent(event);
                    };
                    fire(source, 'dragstart', $sourceX, $sourceY);
                    await sleep(${delays[0]});
                    fire(hit, 'dragenter', $targetX, $targetY);
                    await sleep(${delays[1]});
                    fire(hit, 'dragover', $targetX, $targetY);
                    await sleep(${delays[2]});
                    fire(hit, 'drop', $targetX, $targetY);
                    await sleep(${delays[3]});
                    fire(source, 'dragend', $targetX, $targetY);
                    return JSON.stringify({ ok: true });
                }
            """.trimIndent()
        }

        /**
         * Interpret the `callFunctionOn` result value of a drag script.
         * Returns null on success, or a user-facing error message.
         */
        internal fun dragScriptErrorMessage(result: Any?): String? {
            val json = result as? String ?: return "Failed to execute drag script"
            val parsed = runCatching { pulsarObjectMapper().readTree(json) }.getOrNull()
            if (parsed?.get("ok")?.asBoolean() == true) {
                return null
            }
            return parsed?.get("error")?.asText() ?: "Unknown drag failure"
        }
    }

    // ---------------------------------------------------------------------------
    // Extension surface
    //
    // Override or add methods here for browser4-specific behaviour.
    //
    // Use executeCdpCommand(method, params) inherited from PulsarWebDriver
    // (which delegates to BrowserProtocol via RobustRPC) for any low-level
    // CDP integration.
    // ---------------------------------------------------------------------------

    /**
     * A [RobustRPC] instance for this driver.
     *
     * The upstream [PulsarWebDriver.rpc] is `private`, so overrides cannot reuse
     * it.  This instance wraps the browser4-specific operations below so that
     * they keep the same retry / health-check / CDT-agent-recovery guarantees
     * as the rest of the driver (see [RobustRPC]).  Failure accounting is
     * per-instance, so it is tracked independently from the parent's counters.
     */
    private val rpc = RobustRPC(this)

    /**
     * Click on an element identified by [selector] with optional [button] and [count].
     *
     * Extends [PulsarWebDriver.click] with a [button] parameter for right-click,
     * middle-click, and other mouse buttons.  When [button] is `null` or `"left"`,
     * this delegates directly to the parent implementation for standard left-click
     * behaviour (focus → scroll-into-view → click at computed point).
     *
     * For non-left buttons the element is focused and scrolled into view before
     * dispatching [mouseDown] / [mouseUp] at the element's clickable point, matching
     * the parent's pre-click sequence without duplicating its internals.
     *
     * @param selector A CSS selector, XPath, or "backend:nodeId" locator for the target element.
     * @param count Number of consecutive clicks (1 = single, 2 = double, etc.).
     * @param button Mouse button name: `"left"`, `"right"`, `"middle"`, `"back"`, or `"forward"`.
     *        Defaults to `"left"` when `null`.
     * @throws WebDriverException if the element cannot be found or interacted with.
     */
    @Throws(WebDriverException::class)
    suspend fun click(selector: String, count: Int = 1, button: String? = null) {
        if (button == null || button == "left") {
            click(selector, count)
            return
        }

        // Match the parent click's dialog handling: drain any stale dialog before
        // the operation (a leftover dialog blocks CDP health checks), and auto-accept
        // any dialog the click opens (when autoDismissDialogs is enabled).
        dialogHandler.dismissAllPending()
        try {
            rpc.invokeOnElement(selector, "click", scrollIntoView = true) {
                // Scroll the element into view so it is interactable.  Right-click and
                // other non-left buttons do not require the element to be focusable
                // (e.g. <div> elements without tabindex), so focus is best-effort.
                try {
                    page.focusOnSelector(selector)
                } catch (e: Exception) {
                    // Element is not focusable — that's fine for non-left clicks.
                }

                // Resolve the element's clickable point after scroll.
                val point = clickablePoint(selector)
                    ?: throw WebDriverException("Element not found or not clickable: $selector")

                // Move to the element, then dispatch `count` press+release pairs with
                // the requested button.  Each pair carries an incrementing detail
                // (1..count) so a count of 2 produces a proper double-click sequence.
                mouseMove(point.x, point.y)
                repeat(count) { i ->
                    mouseDown(button, i + 1)
                    mouseUp(button, i + 1)
                }
            }
        } finally {
            dialogHandler.drainAutoDismiss()
        }
    }

    // ---------------------------------------------------------------------------
    // Keyboard fixes — the upstream pulsar-browser:4.11.2 Keyboard / PulsarWebDriver
    // lack the fixes from c9e32e070 (PR #564).  These overrides bridge the gap
    // until a new pulsar-browser release incorporates them upstream.
    // ---------------------------------------------------------------------------

    /**
     * Shared [Keyboard] instance for this driver.
     *
     * The upstream [PulsarWebDriver.keyDown] / [keyUp] dispatch stateless JS
     * [KeyboardEvent]s, and the upstream [PulsarWebDriver.press] reads the
     * modifier state from a *private* Keyboard instance that keyDown never
     * touches.  Keeping one [Keyboard] for keyDown/keyUp/press here makes
     * `Keyboard.pressedModifiers` track held modifiers so that sequences like
     * `keyDown("Control")` → `press("a")` produce DOM events with
     * `ctrlKey: true`.
     */
    private val keyboard: Keyboard by lazy { Keyboard(browserProtocol) }

    /**
     * Dispatch a keyDown for [key] through the stateful [Keyboard.down]
     * path so held modifiers (Control, Alt, Meta, Shift) are tracked and
     * applied to subsequent [press] calls.
     */
    override suspend fun keyDown(key: String) {
        rpc.invokeOnPage("keyDown") { keyboard.down(key) }
    }

    /**
     * Dispatch a keyUp for [key] through the stateful [Keyboard.up] path,
     * matching [keyDown] so released modifiers are cleared again.
     */
    override suspend fun keyUp(key: String) {
        rpc.invokeOnPage("keyUp") { keyboard.up(key) }
    }

    /**
     * Press [key], optionally on the element identified by [selector], using
     * the shared [keyboard] so that modifiers held via [keyDown] are applied.
     *
     * When [selector] is provided the element is focused first, and the
     * cursor is moved to the end ONLY for single printable characters —
     * navigation keys (Home, End, ArrowLeft, Delete, …) preserve the current
     * cursor position so chains like Home→Delete work as expected.
     *
     * Mirrors the parent's Enter-key safety net: a CDP-dispatched `Enter` does
     * not reliably trigger the browser's implicit form submission (HTML
     * §4.10.2.2), so [trySubmitFormOnEnter] explicitly submits the nearest form.
     */
    @Throws(WebDriverException::class)
    override suspend fun press(key: String, selector: String?) {
        if (selector.isNullOrBlank()) {
            rpc.invokeOnPage("press") {
                keyboard.press(key, randomDelayMillis("press"))
                if (key == "Enter") trySubmitFormOnEnter()
                gap("press")
            }
            return
        }

        rpc.invokeOnElement(selector, "press", focus = true) {
            if (key.length == 1 && !Character.isISOControl(key[0])) {
                try {
                    evaluate(
                        """
                        (function(){
                            var el = document.querySelector('${escapeJsSelector(selector)}');
                            if (!el) return;
                            if (typeof el.setSelectionRange === 'function') {
                                el.setSelectionRange(99999, 99999);
                            }
                        })()
                        """.trimIndent()
                    )
                } catch (_: Exception) {
                    // Non-text elements (buttons, divs) don't support setSelectionRange.
                    // Silently ignore — the press will still work for non-text targets.
                }
            }

            keyboard.press(key, randomDelayMillis("press"))
            if (key == "Enter") trySubmitFormOnEnter()
            gap("press")
        }
    }

    /**
     * Type text into the element identified by [selector] with correct Unicode
     * surrogate-pair handling.
     *
     * The upstream [PulsarWebDriver.type] delegates to `Keyboard.type()` which
     * walks the string with `charAt()`, splitting surrogate pairs (emoji, CJK
     * supplementary ideographs) into invalid halves that cause CDP
     * `Input.insertText` to fail.  This method walks by code point via
     * [String.codePointAt] and inserts each complete code point in a single
     * CDP call.
     *
     * For ASCII-only text this is functionally identical to the parent
     * implementation; callers that know their text is BMP-safe may prefer
     * to delegate directly.
     */
    @Throws(WebDriverException::class)
    suspend fun typeSafe(text: String, selector: String) {
        // Focus the element without repositioning the cursor.
        // The parent type(text, selector) always clicks the right edge +
        // setSelectionRange(99999,99999), which breaks chained operations
        // like ArrowLeft→type that rely on preserving cursor position.
        // insertText() respects the existing cursor position, so text
        // appends when cursor is at end and inserts when cursor was moved.
        rpc.invokeOnElement(selector, "type", focus = true) {
            // Type code point by code point — avoids the charAt() surrogate-splitting
            // bug in the upstream Keyboard.type().
            for (charString in codePoints(text)) {
                if (Character.isISOControl(charString.codePointAt(0))) {
                    press(charString)
                } else {
                    browserProtocol.insertText(charString)
                }

                if (charString.length > 1) {
                    // Supplementary character — give the browser a little more time
                    delay(randomDelayMillis("type") * 2)
                } else {
                    delay(randomDelayMillis("type"))
                }
            }
        }
    }

    /**
     * Press a [key] on the element identified by [selector] — an alias of
     * [press] kept for backward compatibility with the original
     * Browser4WebDriver extension surface.
     */
    @Throws(WebDriverException::class)
    suspend fun pressSafe(key: String, selector: String) {
        press(key, selector)
    }

    /**
     * Fill the element identified by [selector] with [text], respecting the
     * element's user-input constraints:
     *
     * - `readonly` and `disabled` elements keep their current value (user
     *   input is blocked, and a programmatic assignment would silently
     *   bypass that constraint).
     * - `maxlength` is honored so a long string cannot silently overflow
     *   (browsers enforce it for user input but not for programmatic
     *   `value` assignment).
     * - number / range inputs use `valueAsNumber` to avoid string-coercion
     *   edge cases that can leave the value empty.
     * - contenteditable elements get their text content replaced.
     *
     * The element is resolved via [PulsarWebDriver.evaluateValue], which
     * supports CSS selectors, XPath, and `backend:nodeId` / `e123` locators
     * (unlike a raw `document.querySelector`), and evaluates with `this`
     * bound to the target element.
     */
    @Throws(WebDriverException::class)
    suspend fun fillSafe(selector: String, text: String) {
        evaluateValue(selector, fillValueJs(text))
    }

    // ---------------------------------------------------------------------------
    // Storage state — the upstream pulsar-browser implementation races the
    // per-origin navigation and can evaluate `window.localStorage` against an
    // opaque-origin provisional document (`SecurityError: Access is denied for
    // this document`).  `open()` delegates to `waitForNavigation()` with the
    // default `oldUrl=""`, which short-circuits as soon as the tab has *any*
    // URL, so the restore runs before the target document commits.  This
    // override navigates to each origin and waits until the document has
    // actually committed to that origin before restoring its localStorage.
    // ---------------------------------------------------------------------------

    /**
     * Loads a previously saved browser storage state JSON, restoring cookies
     * and localStorage.
     *
     * Overrides the upstream pulsar-browser implementation to wait for each
     * origin's document to commit before touching `window.localStorage`, which
     * is only accessible on a document with a standard (non-opaque) origin.
     *
     * @param state A JSON string produced by [saveStorageState].
     * @return A JSON summary of the restored cookies, origins, and localStorage entries.
     */
    @Throws(WebDriverException::class)
    override suspend fun loadStorageState(state: String): String {
        val payload = storageStateMapper.readValue<StorageStatePayload>(state)
        val cookies = payload.cookies.map(::normalizeStorageStateCookie)
        if (cookies.isNotEmpty()) {
            browserProtocol.setCookies(cookies)
        }

        val originalUrl = currentUrl()
        var restoredOrigins = 0
        var restoredLocalStorageEntries = 0

        payload.origins.forEach { originState ->
            val origin = originState.origin.trim()
            require(origin.isNotEmpty()) { "Storage state origin must not be blank" }
            require(URLUtils.isStandard(origin)) { "Storage state origin must be a standard URL: $origin" }

            navigate(origin)
            restoreLocalStorageForOrigin(origin, originState.localStorage)
            restoredOrigins += 1
            restoredLocalStorageEntries += originState.localStorage.size
        }

        if (payload.origins.isNotEmpty() && originalUrl.isNotBlank() && currentUrl() != originalUrl) {
            open(originalUrl)
        }

        return storageStateMapper.writeValueAsString(
            StorageStateLoadSummary(
                cookies = cookies.size,
                origins = restoredOrigins,
                localStorageEntries = restoredLocalStorageEntries,
            )
        )
    }

    /**
     * Navigate to [origin] and restore its localStorage entries once the main
     * document has actually committed to that origin.  Retries transient
     * evaluation failures (execution contexts are destroyed/recreated while a
     * navigation commits) and fails loudly if the document never settles.
     */
    private suspend fun restoreLocalStorageForOrigin(
        origin: String,
        entries: List<StorageStateEntryPayload>,
    ) {
        val normalizedEntries = entries.map { entry ->
            val name = entry.name.trim()
            require(name.isNotEmpty()) { "localStorage entry name must not be blank" }
            mapOf(
                "name" to name,
                "value" to entry.value,
            )
        }
        val entriesJson = storageStateMapper.writeValueAsString(normalizedEntries)
        val script = restoreLocalStorageScript(entriesJson)
        val waitTimeout = timeout("waitForNavigation")
        val deadline = System.nanoTime() + waitTimeout.toMillis() * 1_000_000L

        while (System.nanoTime() < deadline) {
            val currentOrigin = runCatching { evaluateValue("location.origin")?.toString()?.trim() }
                .getOrNull()
            if (isDocumentOriginReady(currentOrigin, origin)) {
                // The document is committed; restore now.  A thrown
                // evaluation error here means the context was destroyed
                // between the probe and the write — keep polling.
                val restoredCount = runCatching { evaluateValue(script) }.getOrNull()
                if (restoredCount != null) {
                    val count = (restoredCount as? Number)?.toInt()
                    require(count == normalizedEntries.size) {
                        "Expected to restore ${normalizedEntries.size} localStorage entries but restored ${count ?: "none"}"
                    }
                    return
                }
            }
            delay(200)
        }

        logger.warn(
            "Timed out restoring localStorage for origin {} after {}",
            origin,
            waitTimeout
        )
        throw WebDriverException(
            "Timed out restoring localStorage for origin $origin after $waitTimeout"
        )
    }

    /**
     * Re-implementation of the parent's private `trySubmitFormOnEnter()`.
     *
     * CDP `Input.dispatchKeyEvent` sends trusted keydown/keypress events, but
     * Chromium does not reliably fire the implicit form submission default
     * action (HTML spec §4.10.2.2) for synthesized input.  This method is a
     * safety net: after a CDP `Enter` lands, it explicitly submits the nearest
     * eligible form via `requestSubmit()` (with `submit()` fallback).
     *
     * Elements excluded (Enter does *not* implicitly submit for these):
     * - `<textarea>` — Enter inserts a newline
     * - `<input type="radio|checkbox|file|button|reset|submit|image|hidden">`
     * - Any element not inside a `<form>`
     */
    private suspend fun trySubmitFormOnEnter() {
        runCatching {
            browserProtocol.evaluate(
                expression = PulsarWebDriver.TRY_SUBMIT_FORM_ON_ENTER_JS,
                returnByValue = true,
            )
        }.onFailure {
            // Best-effort safety net — a failure here must not fail the press itself.
        }
    }

    // ---------------------------------------------------------------------------
    // Drag & drop fix — the upstream pulsar-browser:4.11.x drag() dispatches
    // synthetic (untrusted) DragEvents from JS, which never reach listeners
    // registered by the page's own scripts (isolated-world dispatch does not
    // cross into the main world for drag events).  This override runs the same
    // event sequence through callFunctionOn in the main world (no isolated-world
    // context id), so page-registered listeners receive the full drag lifecycle
    // (dragstart → dragenter → dragover → drop → dragend).
    //
    // Hardening over the initial fix:
    // - The source element is bound as `this` (a real CDP node reference), so
    //   the dragstart/dragend always fire on the intended node.
    // - The target is re-located by a stable CSS path and must still be hit by
    //   its resolved viewport point (elementFromPoint).  Occluded targets,
    //   pointer-events:none targets, and targets moved by an async layout shift
    //   fail loudly instead of silently dispatching on an unrelated element.
    // - Elements inside frames are rejected explicitly: their coordinates are
    //   frame-relative and elementFromPoint runs in the top document, so a
    //   frame drag would silently target the wrong element.
    // - Press/release points are jittered and inter-event delays randomized,
    //   so the synthetic sequence does not fingerprint as a constant-pattern
    //   automation (see dragAndDrop for the same anti-detection intent).
    // - All failure paths return before any event is dispatched, keeping the
    //   outer RPC retry idempotent.
    //
    // Known limitation (unchanged): the events remain synthetic
    // (isTrusted=false); libraries that gate on isTrusted (SortableJS,
    // react-dnd) still won't respond — a browser-level limitation.
    // ---------------------------------------------------------------------------

    /**
     * Drag the element identified by [sourceSelector] onto the element identified
     * by [targetSelector].
     *
     * Upstream drag() dispatches synthetic DragEvents through `JsHandler`,
     * which evaluates in an **isolated world** — the events never reach
     * main-world page listeners, so the drag silently does nothing.
     *
     * This override runs the same event sequence through
     * [BrowserProtocol.callFunctionOn] with the source bound as the call
     * receiver (main world, no isolated-world context id), so page-registered
     * listeners receive the full drag lifecycle
     * (dragstart → dragenter → dragover → drop → dragend), verified against a
     * live listener probe.
     *
     * Notes on what was tried and ruled out:
     * - CDP `Input.dispatchDragEvent` + manual DragData: accepted by Chrome,
     *   but libraries require `dragstart`, which CDP never emits.
     * - Trusted CDP mouse sequences (press → move → release): headless Chrome
     *   never starts the native drag state machine, so no dragstart fires.
     * - Synthetic events are `isTrusted=false`; libraries that gate on
     *   isTrusted (SortableJS, react-dnd) will not respond.  That is a
     *   browser-level limitation, not fixable from the driver.
     *
     * @param sourceSelector A CSS selector, XPath, or "backend:nodeId" locator for the drag source.
     * @param targetSelector A CSS selector, XPath, or "backend:nodeId" locator for the drop target.
     * @throws WebDriverException if either element cannot be located, lives in a
     *   frame, is occluded/moved at drag time, or the script fails.
     */
    @Throws(WebDriverException::class)
    override suspend fun drag(sourceSelector: String, targetSelector: String): Unit {
        // Phase 1 — resolve (retryable pieces are covered by their own RPC
        // layers; deterministic failures like a missing element must surface
        // directly instead of being wrapped by the outer retry machinery).
        // Only the target is scrolled into view: the source is bound as a real
        // CDP node, so it needs no viewport presence.  PageHandler's
        // scrollIntoViewIfNeeded prefers a *smooth* JS scroll (animated), whose
        // geometry stays in transit for hundreds of ms — unusable for
        // point-based validation — so use an instant scroll here instead.
        runCatching {
            evaluateValue(
                targetSelector,
                "function(){ this.scrollIntoView({ block: 'center', behavior: 'instant' }); return true; }",
            )
        }

        val source = resolveDragCenter(sourceSelector)
            ?: throw WebDriverException("Source element was not found: $sourceSelector", driver = this@Browser4WebDriver)
        // The target must be *visible*: even an instant scroll commits
        // asynchronously on the renderer, so poll until the resolved center
        // lands inside the viewport (or give up and let the script report the
        // real failure).  Re-scrolling inside the poll would restart any
        // in-flight scroll animation, so the poll only waits and re-reads.
        val target = resolveDragTargetInViewport(targetSelector)
            ?: throw WebDriverException("Target element was not found: $targetSelector", driver = this@Browser4WebDriver)

        if (source.inFrame || target.inFrame) {
            throw WebDriverException(
                "Drag into/from elements inside frames is not supported: '$sourceSelector' -> '$targetSelector'",
                driver = this@Browser4WebDriver
            )
        }

        val sourceNode = rpc.invokeOnPage("drag") { page.dom.queryLocator(sourceSelector) }
            ?: throw WebDriverException("Source element was not found: $sourceSelector", driver = this@Browser4WebDriver)

        // Humanize the sequence: jitter the press/release points (±2px) and
        // randomize inter-event delays (120-300ms, the same magnitude as the
        // type() bucket).  Constant centers and fixed delays are a fingerprint
        // for synthetic drags.
        val sourcePoint = Pair(source.x + randomOffset(2.0), source.y + randomOffset(2.0))
        val targetPoint = Pair(target.x + randomOffset(2.0), target.y + randomOffset(2.0))
        val delays = List(4) { randomDragDelayMillis() }

        val script = buildDragSequenceScript(
            targetCssPath = target.cssPath,
            sourceX = sourcePoint.first,
            sourceY = sourcePoint.second,
            targetX = targetPoint.first,
            targetY = targetPoint.second,
            delays = delays,
        )

        // Phase 2 — execute the sequence.  The source element is bound as
        // `this` (a real CDP node reference) and userGesture=true keeps
        // user-activation-gated APIs available to page dragstart listeners.
        // Page-side failures (occlusion, missing target) are deterministic:
        // they must propagate immediately with their real message.  Only
        // transient CDP failures are retried, manually, inside this block.
        withNodeObjectId(browserProtocol, sourceNode) { sourceObjectId ->
            var lastCdpFailure: ChromeDriverException? = null
            repeat(3) { attempt ->
                try {
                    val result = browserProtocol.callFunctionOn(
                        script,
                        objectId = sourceObjectId,
                        returnByValue = true,
                        userGesture = true,
                        awaitPromise = true,
                    )
                    val scriptError = dragScriptErrorMessage(result?.result?.value)
                    if (scriptError == null) {
                        return@repeat
                    }
                    throw WebDriverException(
                        "Failed to drag '$sourceSelector' to '$targetSelector': $scriptError",
                        driver = this@Browser4WebDriver
                    )
                } catch (e: WebDriverException) {
                    throw e
                } catch (e: ChromeDriverException) {
                    lastCdpFailure = e
                    if (attempt < 2) {
                        delay(200)
                    }
                }
            }
            lastCdpFailure?.let { throw it }
        }

        gap("drag")
    }

    /**
     * Resolve the viewport center, stable CSS path, and frame residency of
     * [selector] via the driver's locator path.  Supports CSS selectors, XPath,
     * `backend:nodeId` and `eN` snapshot refs (the upstream evaluateValue
     * locator resolution), so drag works with every locator format the rest of
     * the CLI accepts. Returns null when the element does not exist or cannot
     * be re-located by CSS path.
     */
    private suspend fun resolveDragCenter(selector: String): DragCenter? {
        val value = evaluateValue(selector, dragCenterJs())
        return parseDragCenter(value)
    }

    /**
     * Resolve [selector] like [resolveDragCenter], but keep polling until the
     * element's center is inside the viewport (scroll commits and smooth-scroll
     * animations are asynchronous, so a single read can observe stale
     * geometry).  Never re-scrolls inside the poll — that would restart any
     * in-flight scroll animation.  Returns the last resolution when the
     * element never becomes visible; the drag script then reports the real
     * failure (e.g. occluded) instead of this helper guessing.
     */
    private suspend fun resolveDragTargetInViewport(selector: String): DragCenter? {
        var last: DragCenter? = null
        repeat(15) {
            last = resolveDragCenter(selector) ?: return null
            val visible = last.viewportWidth <= 0 || (
                last.x >= 0 &&
                    last.y >= 0 &&
                    last.x <= last.viewportWidth &&
                    last.y <= last.viewportHeight
                )
            if (visible) {
                return last
            }
            delay(150)
        }
        return last
    }

    /** Uniform random offset in [-range, range], used to jitter drag points. */
    private fun randomOffset(range: Double): Double = Random.nextDouble(-range, range)

    /** Randomized inter-event delay for the drag sequence, 120-300 ms. */
    private fun randomDragDelayMillis(): Long = Random.nextLong(120L, 301L)

    // ---------------------------------------------------------------------------
    // Dialog state fix — upstream dialogAccept/dialogDismiss call CDP
    // Page.handleJavaScriptDialog directly but never drain DialogHandler's
    // pending queue, and DialogHandler.onDialogClosed only logs.  The
    // tool-layer "blocked by a native dialog" guard checks that queue, so a
    // handled dialog keeps failing screenshots/health-checks until the session
    // is closed. These overrides acknowledge the queue head after CDP succeeds.
    // ---------------------------------------------------------------------------

    /**
     * Accept the current JavaScript dialog, then acknowledge exactly the dialog
     * CDP handled (queue head) so later pending entries stay intact.
     */
    @Throws(WebDriverException::class)
    override suspend fun dialogAccept(promptText: String?): Unit {
        super.dialogAccept(promptText)
        acknowledgeHandledDialog()
    }

    /**
     * Dismiss (Cancel) the current JavaScript dialog, then acknowledge exactly
     * the dialog CDP handled (see [dialogAccept]).
     */
    @Throws(WebDriverException::class)
    override suspend fun dialogDismiss(): Unit {
        super.dialogDismiss()
        acknowledgeHandledDialog()
    }

    /**
     * Remove only the head of [DialogHandler]'s pending queue — the dialog CDP
     * just handled.  Unlike draining the whole queue, later entries (dialogs
     * queued after this one) are preserved.  The queue is only appended by
     * `Page.javascriptDialogOpening` and never emptied by the upstream dialog
     * path, so it must be acknowledged explicitly after a CDP
     * `Page.handleJavaScriptDialog` call.
     */
    private fun acknowledgeHandledDialog() {
        val acknowledged = dialogHandler.getPendingDialog()
        if (acknowledged != null) {
            logger.debug(
                "Acknowledged dialog handled by CDP: type={} message={}",
                acknowledged.type,
                acknowledged.message,
            )
        } else {
            // The opening event may still be in flight over the WebSocket, or
            // the dialog was opened before DialogHandler subscribed.  Nothing
            // to remove — the stale-entry risk this fix guards against does
            // not apply to a queue that is already empty.
            logger.debug("No pending dialog event to acknowledge after CDP dialog handling")
        }
    }
}
