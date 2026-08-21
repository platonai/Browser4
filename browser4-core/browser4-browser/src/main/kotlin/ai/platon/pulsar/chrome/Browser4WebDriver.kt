package ai.platon.pulsar.chrome

import ai.platon.pulsar.api.BrowserProtocol
import ai.platon.pulsar.api.model.BrowserTab
import ai.platon.pulsar.api.model.WebDriverException
import ai.platon.pulsar.chrome.network.RobustRPC
import ai.platon.pulsar.chrome.protocol.Keyboard
import ai.platon.pulsar.common.serialize.json.pulsarObjectMapper
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
    // cross into the main world for drag events).  This override performs a
    // real CDP drag: Input.setInterceptDrags + mouse sequence, then
    // Input.dispatchDragEvent for dragEnter/dragOver/drop — the same flow as
    // EmulationHandler.Mouse.dragAndDrop, resolved through selectors.
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
    override suspend fun drag(sourceSelector: String, targetSelector: String) {
        rpc.invokeOnPage("drag") {
            val encodedSource = "'${escapeJsSelector(sourceSelector)}'"
            val encodedTarget = "'${escapeJsSelector(targetSelector)}'"
            val script = """
                (async () => {
                    const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
                    const source = document.querySelector($encodedSource);
                    const target = document.querySelector($encodedTarget);
                    if (!source || !target) {
                        return JSON.stringify({
                            ok: false,
                            error: !source && !target
                                ? 'Source and target elements were not found'
                                : !source
                                    ? 'Source element was not found'
                                    : 'Target element was not found'
                        });
                    }
                    if (typeof DataTransfer === 'undefined' || typeof DragEvent === 'undefined') {
                        return JSON.stringify({
                            ok: false,
                            error: 'HTML5 drag-and-drop APIs are not available in the current page context'
                        });
                    }

                    const sourceRect = source.getBoundingClientRect();
                    const targetRect = target.getBoundingClientRect();
                    const sourceX = Math.round(sourceRect.left + sourceRect.width / 2);
                    const sourceY = Math.round(sourceRect.top + sourceRect.height / 2);
                    const targetX = Math.round(targetRect.left + targetRect.width / 2);
                    const targetY = Math.round(targetRect.top + targetRect.height / 2);
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

                    fire(source, 'dragstart', sourceX, sourceY);
                    await sleep(80);
                    fire(target, 'dragenter', targetX, targetY);
                    await sleep(80);
                    fire(target, 'dragover', targetX, targetY);
                    await sleep(120);
                    fire(target, 'drop', targetX, targetY);
                    await sleep(80);
                    fire(source, 'dragend', targetX, targetY);

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

    // ---------------------------------------------------------------------------
    // Dialog state fix — upstream dialogAccept/dialogDismiss call CDP
    // Page.handleJavaScriptDialog directly but never drain DialogHandler's
    // pending queue, and DialogHandler.onDialogClosed only logs.  The
    // tool-layer "blocked by a native dialog" guard checks that queue, so a
    // handled dialog keeps failing screenshots/health-checks until the session
    // is closed.  These overrides drain the queue after every CDP dialog call.
    // ---------------------------------------------------------------------------

    /**
     * Accept the current JavaScript dialog, then drain the pending-dialog queue
     * so the "blocked by dialog" guard does not keep failing on an already
     * handled dialog.
     */
    @Throws(WebDriverException::class)
    override suspend fun dialogAccept(promptText: String?) {
        try {
            super.dialogAccept(promptText)
        } finally {
            drainPendingDialogs()
        }
    }

    /**
     * Dismiss (Cancel) the current JavaScript dialog, then drain the
     * pending-dialog queue (see [dialogAccept]).
     */
    @Throws(WebDriverException::class)
    override suspend fun dialogDismiss() {
        try {
            super.dialogDismiss()
        } finally {
            drainPendingDialogs()
        }
    }

    /**
     * Drop every entry from [DialogHandler]'s pending queue.  The queue is
     * only appended by `Page.javascriptDialogOpening` and never emptied by
     * the upstream dialog path, so it must be drained explicitly after a CDP
     * `Page.handleJavaScriptDialog` call.
     */
    private fun drainPendingDialogs() {
        while (dialogHandler.hasPendingDialog()) {
            dialogHandler.getPendingDialog()
        }
    }
}
