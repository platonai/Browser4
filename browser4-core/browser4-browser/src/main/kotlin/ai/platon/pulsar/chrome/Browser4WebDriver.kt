package ai.platon.pulsar.chrome

import ai.platon.pulsar.api.BrowserProtocol
import ai.platon.pulsar.api.model.BrowserTab
import ai.platon.pulsar.api.model.WebDriverException
import ai.platon.pulsar.chrome.network.RobustRPC
import ai.platon.pulsar.chrome.protocol.Keyboard
import ai.platon.pulsar.common.getLogger
import ai.platon.pulsar.common.serialize.json.pulsarObjectMapper
import ai.platon.pulsar.common.urls.URLUtils
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kotlinx.coroutines.delay

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

        internal fun parseDragCenter(value: Any?): Pair<Double, Double>? {
            val json = value as? String ?: return null
            val node = runCatching { pulsarObjectMapper().readTree(json) }.getOrNull() ?: return null
            val x = node.get("x")?.takeIf { it.isNumber }?.asDouble() ?: return null
            val y = node.get("y")?.takeIf { it.isNumber }?.asDouble() ?: return null
            return Pair(x, y)
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
    // event sequence through BrowserProtocol.evaluate (main world, no
    // isolated-world context id), so page-registered listeners receive the
    // full drag lifecycle (dragstart → dragenter → dragover → drop → dragend).
    // The events remain synthetic (isTrusted=false): libraries that gate on
    // isTrusted (SortableJS, react-dnd) still won't respond — a browser-level
    // limitation, not fixable from the driver (see the KDoc below for what was
    // tried and ruled out).
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
     * [BrowserProtocol.evaluate] (main world, no isolated-world context id),
     * so page-registered listeners receive the full drag lifecycle
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
     * @throws WebDriverException if either element cannot be located or the script fails.
     */
    @Throws(WebDriverException::class)
    override suspend fun drag(sourceSelector: String, targetSelector: String): Unit {
        rpc.invokeOnPage("drag") {
            // Resolve both elements through the driver's locator path
            // (supports CSS selectors, XPath, backend:nodeId and eN snapshot
            // refs), computing their viewport centers in one pass.
            val sourcePoint = resolveDragCenter(sourceSelector)
                ?: throw WebDriverException("Source element was not found: $sourceSelector", driver = this@Browser4WebDriver)
            val targetPoint = resolveDragCenter(targetSelector)
                ?: throw WebDriverException("Target element was not found: $targetSelector", driver = this@Browser4WebDriver)

            val script = """
                (async () => {
                    const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
                    const source = document.elementFromPoint(${sourcePoint.first}, ${sourcePoint.second});
                    const target = document.elementFromPoint(${targetPoint.first}, ${targetPoint.second});
                    if (!source || !target) {
                        return JSON.stringify({
                            ok: false,
                            error: !source && !target
                                ? 'Source and target elements were not found at their resolved positions'
                                : !source
                                    ? 'Source element was not found at its resolved position'
                                    : 'Target element was not found at its resolved position'
                        });
                    }
                    if (typeof DataTransfer === 'undefined' || typeof DragEvent === 'undefined') {
                        return JSON.stringify({
                            ok: false,
                            error: 'HTML5 drag-and-drop APIs are not available in the current page context'
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

                    fire(source, 'dragstart', ${sourcePoint.first}, ${sourcePoint.second});
                    await sleep(80);
                    fire(target, 'dragenter', ${targetPoint.first}, ${targetPoint.second});
                    await sleep(80);
                    fire(target, 'dragover', ${targetPoint.first}, ${targetPoint.second});
                    await sleep(120);
                    fire(target, 'drop', ${targetPoint.first}, ${targetPoint.second});
                    await sleep(80);
                    fire(source, 'dragend', ${targetPoint.first}, ${targetPoint.second});

                    return JSON.stringify({ ok: true });
                })()
            """.trimIndent()

            // browserProtocol.evaluate runs in the main world (no contextId),
            // unlike JsHandler.evaluate which prefers an isolated world.
            val evaluate = browserProtocol.evaluate(script, returnByValue = true, awaitPromise = true)
            val result = evaluate.result.value as? String
                ?: """{"ok":false,"error":"Failed to execute drag script"}"""
            val parsed = runCatching { pulsarObjectMapper().readTree(result) }.getOrNull()
            if (parsed?.get("ok")?.asBoolean() != true) {
                val error = parsed?.get("error")?.asText() ?: "Unknown drag failure"
                throw WebDriverException(
                    "Failed to drag '$sourceSelector' to '$targetSelector': $error",
                    driver = this@Browser4WebDriver
                )
            }

            gap("drag")
        }
    }

    /**
     * Resolve the viewport center of [selector] via the driver's locator path.
     * Supports CSS selectors, XPath, `backend:nodeId` and `eN` snapshot refs
     * (the upstream evaluateValue locator resolution), so drag works with every
     * locator format the rest of the CLI accepts. Returns (x, y) in viewport
     * CSS pixels, or null when the element does not exist.
     */
    private suspend fun resolveDragCenter(selector: String): Pair<Double, Double>? {
        val value = evaluateValue(
            selector,
            """
            function() {
                const r = this.getBoundingClientRect();
                return JSON.stringify({ x: r.left + r.width / 2, y: r.top + r.height / 2 });
            }
            """.trimIndent()
        )
        return parseDragCenter(value)
    }

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
        dialogHandler.getPendingDialog()
    }
}
