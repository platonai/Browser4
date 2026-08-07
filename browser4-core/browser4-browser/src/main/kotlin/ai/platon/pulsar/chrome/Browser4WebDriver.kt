package ai.platon.pulsar.chrome

import ai.platon.pulsar.api.BrowserProtocol
import ai.platon.pulsar.api.model.BrowserTab
import ai.platon.pulsar.api.model.WebDriverException

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
}
