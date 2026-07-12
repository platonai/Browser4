package ai.platon.pulsar.browser

import ai.platon.browser4.chrome.util.ChromeOptions
import ai.platon.browser4.chrome.util.LauncherOptions
import ai.platon.pulsar.browser.common.BrowserSettings

interface BrowserLauncher {
    fun connect(port: Int, settings: BrowserSettings = BrowserSettings()): Browser
    fun launch(
        browserId: BrowserId,
        launcherOptions: LauncherOptions,
        launchOptions: ChromeOptions
    ): Browser
}
