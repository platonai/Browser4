package ai.platon.pulsar.chrome

import ai.platon.browser4.common.B4Constants
import ai.platon.pulsar.api.ChromeOptions
import ai.platon.pulsar.api.model.BrowserSettings
import ai.platon.pulsar.common.getLogger

/**
 * Applies the environment-purity policy for browser extensions at launch time.
 *
 * A browser profile inherits the user's extensions from the prototype data dir. When a live
 * extension injects its content script, Chromium installs the `chrome.runtime` skeleton in
 * the page's main world, which bot detectors sample. Setting `browser.extensions.inherit=false`
 * suppresses every user extension by enabling [ChromeOptions.disableExtensions] on the launch
 * options — which maps to the native `--disable-extensions` Chrome flag.
 *
 * This is deliberately *not* a stealth mechanism: it removes a footprint that comes from the
 * user's own browser configuration, and changes nothing about automation signals.
 *
 * Applied from the single funnel every launch goes through (see `DefaultBrowserFactory`), so
 * the policy holds for session launches, pooled launches and the legacy display-mode path.
 */
object Browser4Extensions {
    private val logger = getLogger(Browser4Extensions::class)

    /**
     * @return `true` when this call disabled extensions for the launch
     */
    fun applyTo(launchOptions: ChromeOptions, settings: BrowserSettings): Boolean {
        val inherit = settings.config.getBoolean(
            B4Constants.EXTENSIONS_INHERIT, B4Constants.EXTENSIONS_INHERIT_DEFAULT
        )
        if (inherit) {
            return false
        }

        launchOptions.disableExtensions = true
        logger.info(
            "Environment purity is on ({} = false): user extensions are disabled for this launch",
            B4Constants.EXTENSIONS_INHERIT
        )
        return true
    }
}

