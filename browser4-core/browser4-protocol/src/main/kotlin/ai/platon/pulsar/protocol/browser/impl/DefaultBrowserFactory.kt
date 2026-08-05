package ai.platon.pulsar.protocol.browser.impl

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

    constructor(conf: ImmutableConfig) : this(conf, BrowserSettings(conf))

    @Synchronized
    override fun launch(
        browserId: BrowserId, launcherOptions: LauncherOptions, launchOptions: ChromeOptions
    ): Browser = getLauncher(browserId.browserType).launch(browserId, launcherOptions, launchOptions)

    @Synchronized
    override fun connect(browserType: BrowserType, port: Int, settings: BrowserSettings): Browser =
        getLauncher(browserType).connect(port, settings)

    private fun getLauncher(browserType: BrowserType): BrowserLauncher {
        return launchers[browserType] ?: throw IllegalArgumentException("Unknown browser type: $browserType")
    }
}
