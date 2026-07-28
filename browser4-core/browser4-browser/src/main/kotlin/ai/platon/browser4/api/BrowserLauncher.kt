package ai.platon.browser4.api

import ai.platon.browser4.api.ChromeOptions
import ai.platon.browser4.api.LauncherOptions
import ai.platon.browser4.api.model.BrowserSettings

interface BrowserLauncher {
    fun connect(port: Int, settings: BrowserSettings = BrowserSettings()): Browser
    fun launch(
        browserId: BrowserId,
        launcherOptions: LauncherOptions,
        launchOptions: ChromeOptions
    ): Browser
}
