package ai.platon.pulsar.chrome

import ai.platon.pulsar.api.ChromeOptions
import ai.platon.pulsar.api.model.BrowserSettings
import ai.platon.pulsar.api.model.ReducedUserAgent
import ai.platon.pulsar.common.browser.Browsers
import ai.platon.pulsar.common.getLogger
import java.nio.file.Path

/**
 * Resolves and applies the launch-time `--user-agent` switch that keeps a headless
 * Chrome from advertising the `HeadlessChrome` product token.
 *
 * ## Why this exists
 *
 * `pulsar-browser` already ships the fix — [BrowserSettings.resolveUserAgent] builds a
 * reduced user agent through [ReducedUserAgent] and [BrowserSettings.createChromeOptions]
 * puts it on the command line. The default path is dead, though:
 * `ReducedUserAgent.buildOrNull()` declares `binary: Path? = null` and forwards that `null`
 * to `ReducedUserAgent.chromeMajorVersion(binary)`, so the callee's own default
 * (`Browsers.searchChromeBinaryOrNull()`) is never evaluated — an explicit `null` argument
 * short-circuits the default-argument mechanism, the function returns `null`, and
 * `resolveUserAgent()` returns `null` for every session that does not spell out
 * `browser.launch.user.agent` in a config file. No `--user-agent` switch is added, and a
 * headless browser keeps sending `... HeadlessChrome/<major>.0.0.0 ... Safari/537.36`.
 *
 * That token is a one-line bot verdict (SannySoft `HEADCHR_UA`, Incolumitas
 * `intoli.userAgent`, BrowserScan's aggregate "Robot" badge, "You are a bot!" on
 * deviceandbrowserinfo) and it contradicts the `Sec-CH-UA*` client hints the very same
 * browser sends. Measured on Chrome 153 / Windows: a headed session reports
 * `Chrome/153.0.0.0`, the headless session reports `HeadlessChrome/153.0.0.0` with no
 * `--user-agent` on the process command line.
 *
 * This object re-derives the user agent with the binary resolved *explicitly*, so the
 * lookup that `pulsar-browser` lost actually runs, and applies it at the single point
 * every browser launch funnels through (see `DefaultBrowserFactory`). A launch-time
 * `--user-agent` is the only mechanism that reaches every JavaScript scope of a session
 * (page, iframes, dedicated/shared/service workers) while leaving the client hints intact —
 * a page-world patch would not.
 *
 * The value is only ever applied when the program has not already decided one, so an
 * explicit `browser.launch.user.agent` (or a caller-supplied option) always wins.
 *
 * @see ReducedUserAgent
 */
object Browser4UserAgent {
    private val logger = getLogger(Browser4UserAgent::class)

    /**
     * The reduced desktop user agent matching the installed Chrome, or `null` when the
     * browser major version cannot be determined.
     *
     * The reduced form (`Chrome/<major>.0.0.0`) is exactly what a current desktop Chrome
     * reports for `navigator.userAgent` thanks to user-agent reduction, so this value is
     * indistinguishable from a genuine browser in either display mode.
     *
     * @param binary the Chrome binary; defaults to the one [Browsers] resolves
     */
    fun reducedUserAgentOrNull(binary: Path? = Browsers.searchChromeBinaryOrNull()): String? {
        val major = ReducedUserAgent.chromeMajorVersion(binary) ?: return null
        return ReducedUserAgent.build(major)
    }

    /**
     * The user agent [settings] asks for, or `null` when no override should be applied.
     *
     * A configured `browser.launch.user.agent` wins (and is normalised, so a configured
     * value that still carries a `Headless` token is repaired rather than trusted).
     * Otherwise `browser.launch.user.agent.stealth` decides: when it is disabled the session
     * is left exactly as Chrome launched it.
     */
    fun resolveOrNull(
        settings: BrowserSettings,
        binary: Path? = Browsers.searchChromeBinaryOrNull()
    ): String? {
        val launchConfig = settings.launchConfig
        val configured = launchConfig.userAgent.trim().takeIf { it.isNotEmpty() }
        if (configured != null) {
            return ReducedUserAgent.reduce(configured)
        }
        if (!launchConfig.userAgentStealth) {
            return null
        }

        return reducedUserAgentOrNull(binary)
    }

    /**
     * Apply the stealth user agent to [launchOptions] when it is still unset.
     *
     * Called from the single funnel every launch goes through, so the invariant "no
     * `HeadlessChrome` token on the wire" holds for session launches, pooled launches and
     * the legacy display-mode launch path alike.
     *
     * @return `true` when [ChromeOptions.userAgent] was set by this call
     */
    fun applyTo(
        launchOptions: ChromeOptions,
        settings: BrowserSettings,
        binary: Path? = Browsers.searchChromeBinaryOrNull()
    ): Boolean {
        if (!launchOptions.userAgent.isNullOrBlank()) {
            // The program (or a caller) already decided the user agent — never override it.
            return false
        }

        val userAgent = resolveOrNull(settings, binary)
        if (userAgent == null) {
            // Loud, but not fatal: the browser still works, it just advertises the
            // headless token, which is what makes it detectable. Say so once per launch.
            logger.warn(
                "Could not resolve a reduced user agent for the installed Chrome (binary: {}). " +
                    "The browser will keep its default user agent, which advertises " +
                    "'HeadlessChrome' in headless mode. Configure browser.launch.user.agent to override it.",
                binary ?: "<not found>"
            )
            return false
        }

        launchOptions.userAgent = userAgent
        return true
    }
}
