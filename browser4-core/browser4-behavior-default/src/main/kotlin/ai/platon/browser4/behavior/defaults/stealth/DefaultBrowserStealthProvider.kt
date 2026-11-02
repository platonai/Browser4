package ai.platon.browser4.behavior.defaults.stealth

import ai.platon.browser4.behavior.BrowserBehaviorEdition
import ai.platon.browser4.behavior.stealth.BrowserStealthProvider
import ai.platon.browser4.behavior.stealth.StealthLaunchContext
import ai.platon.browser4.behavior.stealth.StealthLaunchReport
import ai.platon.browser4.behavior.stealth.StealthSessionContext
import ai.platon.browser4.common.B4Constants
import ai.platon.pulsar.common.browser.Browsers
import java.nio.file.Path

/**
 * The community/default browser-stealth policy.
 *
 * It is the behaviour Browser4 has always shipped, now behind the
 * [BrowserStealthProvider] seam, and it deliberately stays small and auditable:
 *
 * 1. **[Browser4UserAgent]** — a headless launch gets the reduced
 *    (`Chrome/<major>.0.0.0`) `--user-agent`, because the stock headless product token
 *    (`HeadlessChrome/...`) is a one-line bot verdict that also contradicts the
 *    `Sec-CH-UA*` client hints the same browser sends. Headed launches are left alone (a
 *    `--user-agent` there only empties the client-hint `fullVersionList`), and an explicit
 *    user agent always wins. The master `browser.stealth.enabled` switch and a
 *    fingerprint-browser channel both suppress this rewrite.
 * 2. **[Browser4Extensions]** — the environment-purity switch: with
 *    `browser.extensions.inherit=false` the launch gets `--disable-extensions`, so the user's
 *    own extensions do not travel into the session profile.
 * 3. **Baseline page-world payload gate** — [baselinePageWorldScriptsEnabled] suppresses the
 *    base library's `js/stealth.js` registration when `browser.stealth.enabled=false` or the
 *    session runs on a fingerprint-browser channel ([B4Constants.stealthAppliesTo]).
 *
 * Driver-level layers stay where they are: the base library passes
 * `--disable-blink-features=AutomationControlled`, `Browser4WebDriver` enables focus
 * emulation, and the baseline page-world payload is the classpath resource `js/stealth.js`.
 * That resource intentionally lives in `browser4-browser`, not here: the base
 * `DualWorldScriptLoader` resolves it from the classpath, and only a *direct* dependency of
 * every product module (which `browser4-browser` is, while this module is transitive) is
 * guaranteed to be resolved before the base jar shadows. [documentStartScripts] is therefore
 * empty for the default edition; a professional or custom edition adds its page-world layer
 * through that hook (its scripts run after the baseline, so they can override it).
 *
 * Registered through `META-INF/services/ai.platon.browser4.behavior.stealth.BrowserStealthProvider`,
 * so `browser4-browser` discovers it without a compile-time dependency on this module.
 */
class DefaultBrowserStealthProvider(
    /**
     * Resolves the Chrome binary the reduced user agent is derived from.
     *
     * A supplier rather than a resolved value so the lookup happens per launch (exactly as
     * the pre-module code did) and tests can point it at a fake install layout. The
     * no-argument constructor the ServiceLoader needs is generated because the parameter has
     * a default.
     */
    private val chromeBinary: () -> Path? = { Browsers.searchChromeBinaryOrNull() },
) : BrowserStealthProvider {
    override val id: String = ID
    override val edition: String = BrowserBehaviorEdition.DEFAULT
    override val priority: Int = 0

    /**
     * Apply the two launch-time policies to [StealthLaunchContext.options].
     *
     * Both sub-policies are safe to call more than once per launch: each only changes an
     * option the caller/configuration has not already decided, and each reports whether it
     * changed anything.
     *
     * A fingerprint-browser channel ([B4Constants.stealthAppliesTo]) skips the user-agent
     * rewrite entirely: that browser owns its own fingerprint and its own anti-detection
     * behaviour, so Browser4's reduced agent would only contradict it. The extension policy
     * still applies — it is environment purity, not a stealth patch. An ordinary channel
     * additionally honours `browser.stealth.enabled` inside [Browser4UserAgent].
     */
    override fun applyLaunchOptions(context: StealthLaunchContext): StealthLaunchReport {
        val options = context.options
        val settings = context.settings

        val userAgentApplied = if (B4Constants.stealthAppliesTo(context.channel)) {
            Browser4UserAgent.applyTo(options, settings, chromeBinary())
        } else {
            false
        }
        val extensionsDisabled = Browser4Extensions.applyTo(options, settings)

        return StealthLaunchReport(
            providerId = id,
            edition = edition,
            userAgent = options.userAgent?.takeIf { userAgentApplied },
            extensionsDisabled = extensionsDisabled,
        )
    }

    /**
     * The baseline payload is Browser4's own anti-detection layer, so the master switch and a
     * fingerprint-browser channel both turn it off; `documentStartScripts` is not consulted
     * for this decision (an edition's own scripts stay the edition's call).
     */
    override fun baselinePageWorldScriptsEnabled(context: StealthSessionContext): Boolean {
        val config = context.settings.config
        val enabled = config.getBoolean(B4Constants.STEALTH_ENABLED, B4Constants.STEALTH_ENABLED_DEFAULT)
        if (!enabled) {
            return false
        }
        val channel = config.get(B4Constants.BROWSER_CHANNEL)
        return B4Constants.stealthAppliesTo(channel)
    }

    companion object {
        /** Provider id used by configuration and diagnostics. */
        const val ID: String = "default"
    }
}
