package ai.platon.pulsar.protocol.browser.impl

import ai.platon.browser4.behavior.BrowserBehavior
import ai.platon.browser4.behavior.stealth.StealthLaunchContext
import ai.platon.browser4.common.B4Constants
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
        // `HeadlessChrome` product token on the wire, that the environment-purity switch is
        // honoured, and — for an edition that installs its own stealth provider — that the
        // edition's launch policy applies.
        //
        // Who decides what is the replaceable part: the active BrowserStealthProvider
        // (browser4-behavior-api) owns the launch policy. Its default implementation
        // (browser4-behavior-default) applies the reduced user agent and the extension policy
        // described in its KDoc, and it skips the user-agent rewrite for a fingerprint-browser
        // channel (see [B4Constants.stealthAppliesTo]) — the channel travels in the context so
        // an edition can make the same call itself. A professional/custom edition module
        // replaces that module without touching this funnel. A throw from the provider fails
        // the launch with its own error (Chrome has not started yet): silently launching
        // without the policy would hide a broken edition behind a detectable browser.
        val stealth = BrowserBehavior.stealth(launcherOptions.settings.config)
        stealth.applyLaunchOptions(
            StealthLaunchContext(launchOptions, launcherOptions.settings, channel)
        )
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
