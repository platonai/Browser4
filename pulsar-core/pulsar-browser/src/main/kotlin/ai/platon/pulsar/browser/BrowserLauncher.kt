package ai.platon.pulsar.browser

import ai.platon.pulsar.browser.common.BrowserSettings
import ai.platon.pulsar.chrome.util.ChromeOptions
import ai.platon.pulsar.chrome.util.LauncherOptions

interface BrowserLauncher {
    fun connect(port: Int, settings: BrowserSettings = BrowserSettings()): Browser
    fun launch(
        browserId: BrowserId,
        launcherOptions: LauncherOptions,
        launchOptions: ChromeOptions
    ): Browser
}
