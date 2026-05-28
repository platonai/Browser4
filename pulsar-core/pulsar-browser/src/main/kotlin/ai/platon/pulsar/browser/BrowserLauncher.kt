package ai.platon.pulsar.browser

import ai.platon.pulsar.browser.common.BrowserSettings

interface BrowserLauncher {
    fun connect(port: Int, settings: BrowserSettings = BrowserSettings()): Browser
    fun launch(
        browserId: BrowserId,
        launcherOptions: ai.platon.pulsar.chrome.LauncherOptions,
        launchOptions: ai.platon.pulsar.chrome.ChromeOptions
    ): Browser
}
