package ai.platon.browser4.chrome

import ai.platon.browser4.api.BrowserProtocol
import ai.platon.browser4.api.model.BrowserTab

/**
 * Browser4-specific extension of [PulsarWebDriver].
 *
 * This class is the extension point for all browser4-specific features, bug fixes,
 * and new requirements that go beyond the core functionality provided by
 * `pulsar-browser`'s [ai.platon.pulsar.chrome.PulsarWebDriver].
 *
 * ## Relationship to pulsar-browser
 *
 * `pulsar-browser` was extracted from `browser4-browser` as a standalone library
 * to simplify this repository and reduce build time.  It provides the core
 * browser-automation primitives in the `ai.platon.pulsar.*` package namespace.
 *
 * This class currently extends the local [PulsarWebDriver] (in
 * `ai.platon.browser4.chrome`).  Once the type migration from
 * `ai.platon.browser4.*` to `ai.platon.pulsar.*` is complete, it will directly
 * extend [ai.platon.pulsar.chrome.PulsarWebDriver].
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
}
