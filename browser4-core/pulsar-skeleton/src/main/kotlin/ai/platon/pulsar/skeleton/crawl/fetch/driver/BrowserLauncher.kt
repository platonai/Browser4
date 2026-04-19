package ai.platon.pulsar.skeleton.crawl.fetch.driver

import ai.platon.browser4.driver.chrome.common.ChromeOptions
import ai.platon.browser4.driver.chrome.common.LauncherOptions
import ai.platon.browser4.driver.common.BrowserSettings
import ai.platon.pulsar.skeleton.crawl.fetch.privacy.BrowserId

interface BrowserLauncher {
    fun connect(port: Int, settings: BrowserSettings = BrowserSettings()): Browser
    fun launch(browserId: BrowserId, launcherOptions: LauncherOptions, launchOptions: ChromeOptions): Browser
}
