package ai.platon.pulsar.browser

import ai.platon.pulsar.browser.chrome.ChromeOptions
import ai.platon.pulsar.browser.chrome.LauncherOptions
import ai.platon.pulsar.driver.common.BrowserSettings

interface BrowserLauncher {
    fun connect(port: Int, settings: BrowserSettings = BrowserSettings()): Browser
    fun launch(browserId: BrowserId, launcherOptions: ai.platon.pulsar.browser.chrome.LauncherOptions, launchOptions: ai.platon.pulsar.browser.chrome.ChromeOptions): Browser
}
