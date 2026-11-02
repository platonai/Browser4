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
        // Every browser launch in the application funnels through this method, which makes it
        // the one place able to guarantee that no session ever puts the headless
        // `HeadlessChrome` product token on the wire. It has to be done here because the
        // library's own `BrowserSettings.resolveUserAgent()` returns null on its default path
        // (see [Browser4UserAgent] for the exact reason), so no `--user-agent` switch is added
        // by `createStandardLaunchOptions()`. A user agent already decided by the caller or by
        // `browser.launch.user.agent` is left untouched.
        //
        // The ego (lite) channel is a fingerprint browser that owns its own anti-detection
        // behaviour — user agent included — so Browser4 never rewrites it: human-like input
        // stays, stealth does not. See [B4Constants.stealthAppliesTo].
        if (!egoLite) {
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
