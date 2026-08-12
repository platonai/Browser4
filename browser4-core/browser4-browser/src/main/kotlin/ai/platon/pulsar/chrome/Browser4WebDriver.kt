package ai.platon.pulsar.chrome

import ai.platon.pulsar.api.BrowserProtocol
import ai.platon.pulsar.api.model.BrowserTab
import ai.platon.pulsar.api.model.WebDriverException
import ai.platon.pulsar.chrome.protocol.Keyboard
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
                browser = driver.browser as PulsarBrowser,
            )
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
     * @param selector A CSS selector or "backend:nodeId" locator for the target element.
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

        // Scroll the element into view so it is interactable.  Right-click and
        // other non-left buttons do not require the element to be focusable
        // (e.g. <div> elements without tabindex), so focus is best-effort.
        try {
            page.focusOnSelector(selector)
        } catch (e: Exception) {
            // Element is not focusable — that's fine for non-left clicks.
        }
        page.scrollIntoViewIfNeeded(selector)

        // Resolve the element's clickable point after scroll.
        val point = clickablePoint(selector)
            ?: throw WebDriverException("Element not found or not clickable: $selector")

        // Move to the element, then dispatch press + release with the
        // requested button.  mouseDown/mouseUp accept button names directly
        // ("right", "middle", etc.).
        mouseMove(point.x, point.y)
        mouseDown(button, count)
        mouseUp(button, count)
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
        keyboard.down(key)
    }

    /**
     * Dispatch a keyUp for [key] through the stateful [Keyboard.up] path,
     * matching [keyDown] so released modifiers are cleared again.
     */
    override suspend fun keyUp(key: String) {
        keyboard.up(key)
    }

    /**
     * Press [key], optionally on the element identified by [selector], using
     * the shared [keyboard] so that modifiers held via [keyDown] are applied.
     *
     * When [selector] is provided the element is focused first, and the
     * cursor is moved to the end ONLY for single printable characters —
     * navigation keys (Home, End, ArrowLeft, Delete, …) preserve the current
     * cursor position so chains like Home→Delete work as expected.
     */
    @Throws(WebDriverException::class)
    override suspend fun press(key: String, selector: String?) {
        if (selector != null) {
            page.focusOnSelector(selector)

            if (key.length == 1 && !Character.isISOControl(key[0])) {
                evaluate("""
                    (function(){
                        var el = document.querySelector('${selector.replace("'", "\\'")}');
                        if (!el) return;
                        if (typeof el.setSelectionRange === 'function') {
                            el.setSelectionRange(99999, 99999);
                        }
                    })()
                """.trimIndent())
            }
        }

        keyboard.press(key, randomDelayMillis("press"))
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
        page.focusOnSelector(selector)

        // Type code point by code point — avoids the charAt() surrogate-splitting
        // bug in the upstream Keyboard.type().
        var i = 0
        while (i < text.length) {
            val codePoint = text.codePointAt(i)
            val charCount = Character.charCount(codePoint)
            val charString = text.substring(i, i + charCount)

            if (Character.isISOControl(codePoint)) {
                press(charString)
            } else {
                browserProtocol.insertText(charString)
            }

            if (charCount > 1) {
                // Supplementary character — give the browser a little more time
                delay(randomDelayMillis("type") * 2)
            } else {
                delay(randomDelayMillis("type"))
            }
            i += charCount
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
     * HTML `maxlength` attribute so that programmatic assignments do not
     * silently exceed the element's constraint.
     *
     * The upstream [PulsarWebDriver.fill] JavaScript fallback directly assigns
     * `this.value = text` without checking [HTMLInputElement.maxLength].
     * Browsers enforce `maxlength` for user input but not for programmatic
     * `value` assignment, so a long string can silently overflow.
     */
    @Throws(WebDriverException::class)
    suspend fun fillSafe(selector: String, text: String) {
        evaluate("""
            (function(){
                var el = document.querySelector('${selector.replace("'", "\\'")}');
                if (!el) return;
                var val = '${
                    text.replace("\\", "\\\\")
                        .replace("'", "\\'")
                        .replace("\n", "\\n")
                        .replace("\r", "\\r")
                }';
                var maxLen = el.maxLength;
                if (maxLen > 0 && val.length > maxLen) { val = val.substring(0, maxLen); }
                // For number / range inputs use valueAsNumber to avoid string-coercion
                // edge cases that can leave the value empty.
                if (el.type === 'number' || el.type === 'range') {
                    var numVal = parseFloat(val);
                    if (!isNaN(numVal)) { el.valueAsNumber = numVal; }
                    else { el.value = val; }
                } else {
                    el.value = val;
                }
                if (typeof el.focus === 'function') { el.focus(); }
                el.dispatchEvent(new Event('input', { bubbles: true }));
                el.dispatchEvent(new Event('change', { bubbles: true }));
            })()
        """.trimIndent())
    }
}
