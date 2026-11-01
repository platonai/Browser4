package ai.platon.pulsar.protocol.browser.impl

import ai.platon.browser4.common.B4Constants
import ai.platon.pulsar.chrome.Browser4Extensions
import ai.platon.pulsar.chrome.Browser4UserAgent
import ai.platon.pulsar.chrome.manage.PulsarBrowserLauncher
import ai.platon.pulsar.api.ChromeOptions
import ai.platon.pulsar.api.LauncherOptions
import ai.platon.pulsar.api.Browser
import ai.platon.pulsar.api.BrowserId
import ai.platon.pulsar.api.BrowserLauncher
import ai.platon.pulsar.api.model.BrowserSettings
import ai.platon.pulsar.api.manage.AbstractBrowserFactory
import ai.platon.pulsar.common.browser.BrowserType
import ai.platon.pulsar.common.config.ImmutableConfig

class DefaultBrowserFactory(
    conf: ImmutableConfig = ImmutableConfig(loadDefaults = true),
    settings: BrowserSettings = BrowserSettings(conf)
) : AbstractBrowserFactory(conf, settings) {
    private val launchers = mapOf(
        BrowserType.PULSAR_CHROME to PulsarBrowserLauncher()
    )
    private val egoLiteLauncher = EgoLiteBrowserLauncher()

    constructor(conf: ImmutableConfig) : this(conf, BrowserSettings(conf))

    @Synchronized
    override fun launch(
        browserId: BrowserId, launcherOptions: LauncherOptions, launchOptions: ChromeOptions
    ): Browser {
        // A session-level browser channel (`browser.channel`, from `open --channel
        // ego-lite`) picks the browser binary this launch resolves; unset means the
        // default Chrome.
        val channel = launcherOptions.settings.config.get(B4Constants.BROWSER_CHANNEL)
            ?.trim()?.takeIf { it.isNotEmpty() }
        val chromeChannel = channel != null &&
            channel.lowercase() in setOf("chrome", "google-chrome", "google chrome")
        val egoLite = channel != null && !chromeChannel
        if (egoLite && !EgoLiteBrowsers.isEgoLiteChannel(channel)) {
            throw IllegalArgumentException(
                "Unknown ${B4Constants.BROWSER_CHANNEL}: $channel. " +
                    "Supported channels: chrome (the default), ${B4Constants.BROWSER_CHANNEL_EGO_LITE}"
            )
        }
        // For the ego (lite) channel the stealth user agent must be derived from the
        // ego binary's own Chromium version, probed over CDP: ego stamps its binaries
        // and its install-layout version folder with the application version (0.6.x),
        // so [Browser4UserAgent]'s install-layout lookup would resolve to major 0 and
        // produce "Chrome/0.0.0.0" — a worse bot signal than the native HeadlessChrome
        // token. Never call [Browser4UserAgent.applyTo] with the ego binary.
        val userAgentBinary = if (egoLite) {
            EgoLiteBrowsers.resolveBinaryOrNull(launcherOptions.settings.config)
        } else null

        // Every browser launch in the application funnels through this method, which makes it
        // the one place able to guarantee that no session ever puts the headless
        // `HeadlessChrome` product token on the wire. It has to be done here because the
        // library's own `BrowserSettings.resolveUserAgent()` returns null on its default path
        // (see [Browser4UserAgent] for the exact reason), so no `--user-agent` switch is added
        // by `createStandardLaunchOptions()`. A user agent already decided by the caller or by
        // `browser.launch.user.agent` is left untouched.
        if (egoLite) {
            // A missing binary is reported by EgoLiteBrowserLauncher.launch below.
            if (userAgentBinary != null) {
                EgoLiteBrowsers.applyStealthUserAgent(launchOptions, launcherOptions.settings, userAgentBinary)
            }
        } else {
            Browser4UserAgent.applyTo(launchOptions, launcherOptions.settings)
        }
        Browser4Extensions.applyTo(launchOptions, launcherOptions.settings)
        val launcher = if (egoLite) egoLiteLauncher else getLauncher(browserId.browserType)
        return launcher.launch(browserId, launcherOptions, launchOptions)
    }

    @Synchronized
    override fun connect(browserType: BrowserType, port: Int, settings: BrowserSettings): Browser =
        getLauncher(browserType).connect(port, settings)

    private fun getLauncher(browserType: BrowserType): BrowserLauncher {
        return launchers[browserType] ?: throw IllegalArgumentException("Unknown browser type: $browserType")
    }
}
