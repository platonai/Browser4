package ai.platon.pulsar.skeleton.browser

import ai.platon.pulsar.common.browser.BrowserProfileMode
import ai.platon.pulsar.common.browser.BrowserType
import ai.platon.pulsar.common.config.ImmutableConfig
import ai.platon.pulsar.driver.chrome.ChromeOptions
import ai.platon.pulsar.driver.chrome.LauncherOptions
import ai.platon.pulsar.driver.common.BrowserSettings
import ai.platon.pulsar.skeleton.browser.driver.BrowserLaunchException
import ai.platon.pulsar.skeleton.workflow.fetch.privacy.BrowserId

/**
 * A factory to create browser instances.
 * */
interface BrowserFactory {

    val conf: ImmutableConfig

    val settings: BrowserSettings

    /**
     * Connect to a browser instance, the browser instance should be open with Chrome devtools open.
     * */
    fun connect(browserType: BrowserType, port: Int, settings: BrowserSettings = BrowserSettings()): Browser

    @Throws(BrowserLaunchException::class)
    fun launch(profileMode: BrowserProfileMode): Browser

    /**
     * Launch the system default browser, the system default browser is your daily used browser.
     * */
    @Throws(BrowserLaunchException::class)
    fun launch(browserId: BrowserId): Browser

    /**
     * Launch the system default browser, the system default browser is your daily used browser.
     * */
    @Throws(BrowserLaunchException::class)
    fun launch(browserId: BrowserId, settings: BrowserSettings): Browser

    /**
     * Launch the system default browser, the system default browser is your daily used browser.
     * */
    @Throws(BrowserLaunchException::class)
    fun launchSystemDefaultBrowser(): Browser

    /**
     * Launch the default browser, notice, the default browser is not the one you used daily.
     * */
    @Throws(BrowserLaunchException::class)
    fun launchDefaultBrowser(): Browser

    /**
     * Launch the prototype browser, the prototype browser is a browser instance with default settings.
     * */
    @Throws(BrowserLaunchException::class)
    fun launchPrototypeBrowser(): Browser

    /**
     * Launch the next sequential browser, the browser's user data dir rotates between a group of dirs.
     * */
    @Throws(BrowserLaunchException::class)
    fun launchNextSequentialBrowser(): Browser

    /**
     * Launch a random temporary browser, the browser's user data dir is a random temporary dir.
     * */
    @Throws(BrowserLaunchException::class)
    fun launchRandomTempBrowser(): Browser

    /**
     * Launch a browser with the given browser id, the browser id is used to identify the browser instance.
     * */
    @Throws(BrowserLaunchException::class)
    fun launch(
        browserId: BrowserId, launcherOptions: LauncherOptions, launchOptions: ChromeOptions
    ): Browser
}
