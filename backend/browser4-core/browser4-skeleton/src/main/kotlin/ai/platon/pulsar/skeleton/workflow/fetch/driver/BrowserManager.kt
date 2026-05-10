package ai.platon.pulsar.skeleton.workflow.fetch.driver

import ai.platon.browser4.driver.common.BrowserSettings
import ai.platon.pulsar.common.browser.BrowserProfileMode
import ai.platon.pulsar.skeleton.crawl.fetch.driver.Browser
import ai.platon.pulsar.skeleton.workflow.fetch.privacy.BrowserId

interface BrowserManager : AutoCloseable {
    val browserFactory: BrowserFactory

    val settings: BrowserSettings

    val browsers: Map<BrowserId, Browser>

    @Throws(BrowserLaunchException::class)
    fun launch(profileMode: BrowserProfileMode): Browser

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
     * Launch a random temporary browser, the browser's user data dir is a random temporary dir.
     * */
    @Throws(BrowserLaunchException::class)
    fun launchRandomTempBrowser(): Browser

    fun findBrowserOrNull(browserId: BrowserId): Browser?

    fun closeBrowser(browserId: BrowserId)

    fun closeBrowser(browser: Browser)

    fun closeDriver(driver: WebDriver)
}
