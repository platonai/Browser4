package ai.platon.pulsar.chrome

import ai.platon.pulsar.api.BrowserProtocol
import ai.platon.pulsar.api.model.BrowserTab
import ai.platon.pulsar.api.model.WebDriverException
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

        // Pre-click sequence: focus and scroll into view so the element is
        // interactable, matching what the parent click() does internally.
        page.focusOnSelector(selector)
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
        // Focus + cursor-to-end sequence matching PulsarWebDriver.type(_, selector)
        page.focusOnSelector(selector)
        evaluate("""
            (function(){
                var el = document.querySelector('${selector.replace("'", "\\'")}');
                if (!el) return;
                if (typeof el.focus === 'function') { el.focus(); }
                if (typeof el.setSelectionRange === 'function') {
                    el.setSelectionRange(99999, 99999);
                }
            })()
        """.trimIndent())

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
     * Press a [key] on the element identified by [selector], with conditional
     * cursor positioning so that navigation-key chains (e.g. Home → Delete)
     * are not broken by an eager `setSelectionRange(99999, 99999)`.
     *
     * The upstream [PulsarWebDriver.press] unconditionally calls
     * `setSelectionRange(99999, 99999)` after focusing, which resets the
     * cursor to the end.  For printable single-character keys this is correct
     * (it ensures the typed character appends rather than prepends).  For
     * navigation keys (Home, End, ArrowLeft, Delete, …) it destroys the
     * expected cursor state and makes chained operations fail.
     */
    @Throws(WebDriverException::class)
    suspend fun pressSafe(key: String, selector: String) {
        page.focusOnSelector(selector)

        // Position cursor at end ONLY for single printable characters.
        // Navigation / control keys must preserve the current cursor position
        // so that chains like Home→Delete work as expected.
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

        press(key)
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
                if (typeof el.focus === 'function') { el.focus(); }
                var val = '${
                    text.replace("\\", "\\\\")
                        .replace("'", "\\'")
                        .replace("\n", "\\n")
                        .replace("\r", "\\r")
                }';
                var maxLen = el.maxLength;
                if (maxLen > 0 && val.length > maxLen) { val = val.substring(0, maxLen); }
                el.value = val;
                el.dispatchEvent(new Event('input', { bubbles: true }));
                el.dispatchEvent(new Event('change', { bubbles: true }));
            })()
        """.trimIndent())
    }
}
