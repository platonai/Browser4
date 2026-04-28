package ai.platon.browser4.protocol.browser

import ai.platon.browser4.driver.chrome.common.ChromeOptions
import ai.platon.browser4.driver.chrome.common.LauncherOptions
import ai.platon.browser4.driver.common.BrowserSettings
import ai.platon.pulsar.common.browser.BrowserType
import ai.platon.pulsar.common.config.ImmutableConfig
import ai.platon.pulsar.protocol.browser.impl.AbstractBrowserFactory
import ai.platon.pulsar.protocol.browser.impl.PulsarBrowserLauncher
import ai.platon.pulsar.skeleton.crawl.fetch.driver.Browser
import ai.platon.pulsar.skeleton.workflow.fetch.driver.BrowserLaunchException
import ai.platon.pulsar.skeleton.workflow.fetch.privacy.BrowserId

/**
 * A factory to create browser instances.
 * */
class PulsarBrowserFactory(
    conf: ImmutableConfig = ImmutableConfig(),
    settings: BrowserSettings = BrowserSettings(conf),
) : AbstractBrowserFactory(conf, settings) {
    private val launcher = PulsarBrowserLauncher()

    val browserType = BrowserType.PULSAR_CHROME

    /**
     * Connect to a browser instance, the browser instance should be open with Chrome devtools open.
     * */
    override fun connect(browserType: BrowserType, port: Int, settings: BrowserSettings): Browser =
        launcher.connect(port, settings)

    /**
     * Launch a browser with the given browser id, the browser id is used to identify the browser instance.
     * */
    @Throws(BrowserLaunchException::class)
    override fun launch(
        browserId: BrowserId, launcherOptions: LauncherOptions, launchOptions: ChromeOptions
    ): Browser {
        require(browserId.browserType == browserType) {
            "Browser type mismatch, expected $browserType, actual ${browserId.browserType}" }

        return launcher.launch(browserId, launcherOptions, launchOptions)
    }

    /**
     * Launch the system default browser, the system default browser is your daily used browser.
     * */
    @Throws(BrowserLaunchException::class)
    override fun launchSystemDefaultBrowser(): Browser =
        launcher.launch(BrowserId.SYSTEM_DEFAULT, LauncherOptions(settings), ChromeOptions())

    /**
     * Launch the default browser, notice, the default browser is not the one you used daily.
     * */
    @Throws(BrowserLaunchException::class)
    override fun launchDefaultBrowser(): Browser =
        launcher.launch(BrowserId.DEFAULT, LauncherOptions(settings), ChromeOptions())

    /**
     * Launch the prototype browser, the prototype browser is a browser instance with default settings.
     * */
    @Throws(BrowserLaunchException::class)
    override fun launchPrototypeBrowser(): Browser =
        launcher.launch(BrowserId.PROTOTYPE, LauncherOptions(settings), ChromeOptions())

    /**
     * Launch the next sequential browser, the browser's user data dir rotates between a group of dirs.
     * */
    @Throws(BrowserLaunchException::class)
    override fun launchNextSequentialBrowser(): Browser =
        launcher.launch(BrowserId.NEXT_SEQUENTIAL, LauncherOptions(settings), ChromeOptions())

    /**
     * Launch a random temporary browser, the browser's user data dir is a random temporary dir.
     * */
    @Throws(BrowserLaunchException::class)
    override fun launchRandomTempBrowser(): Browser =
        launcher.launch(BrowserId.RANDOM_TEMP, LauncherOptions(settings), ChromeOptions())
}
