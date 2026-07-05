package ai.platon.browser4.api

import ai.platon.pulsar.browser.ChromeOptions
import ai.platon.pulsar.browser.LauncherOptions
import ai.platon.pulsar.browser.common.BrowserSettings

interface BrowserLauncher {
    fun connect(port: Int, settings: BrowserSettings = BrowserSettings()): Browser
    fun launch(
        browserId: BrowserId,
        launcherOptions: LauncherOptions,
        launchOptions: ChromeOptions
    ): Browser
}
