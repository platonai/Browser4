package ai.platon.pulsar.skeleton.browser

import ai.platon.pulsar.driver.chrome.ChromeOptions
import ai.platon.pulsar.driver.chrome.LauncherOptions
import ai.platon.pulsar.driver.common.BrowserSettings
import ai.platon.pulsar.skeleton.workflow.fetch.privacy.BrowserId

interface BrowserLauncher {
    fun connect(port: Int, settings: BrowserSettings = BrowserSettings()): Browser
    fun launch(browserId: BrowserId, launcherOptions: LauncherOptions, launchOptions: ChromeOptions): Browser
}
