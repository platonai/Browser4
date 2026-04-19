package ai.platon.pulsar.protocol.browser.impl

import ai.platon.browser4.driver.chrome.common.ChromeOptions
import ai.platon.browser4.driver.chrome.common.LauncherOptions
import ai.platon.browser4.driver.common.BrowserSettings
import ai.platon.pulsar.common.browser.BrowserProfileMode
import ai.platon.pulsar.common.config.ImmutableConfig
import ai.platon.pulsar.skeleton.crawl.fetch.driver.Browser
import ai.platon.pulsar.skeleton.crawl.fetch.driver.BrowserFactory
import ai.platon.pulsar.skeleton.crawl.fetch.driver.BrowserLaunchException
import ai.platon.pulsar.skeleton.crawl.fetch.privacy.BrowserId

abstract class AbstractBrowserFactory(
    override val conf: ImmutableConfig,
    override val settings: BrowserSettings = BrowserSettings(conf),
) : BrowserFactory {

    @Throws(BrowserLaunchException::class)
    override fun launch(profileMode: BrowserProfileMode): Browser {
        val browserId = when (profileMode) {
            BrowserProfileMode.SYSTEM_DEFAULT -> BrowserId.SYSTEM_DEFAULT
            BrowserProfileMode.DEFAULT -> BrowserId.DEFAULT
            BrowserProfileMode.PROTOTYPE -> BrowserId.PROTOTYPE
            BrowserProfileMode.TEMPORARY -> BrowserId.RANDOM_TEMP
            BrowserProfileMode.SEQUENTIAL -> BrowserId.NEXT_SEQUENTIAL
        }

        return launch(browserId)
    }

    /**
     * Launch a browser with the given browser id, the browser id is used to identify the browser instance.
     * */
    override fun launch(browserId: BrowserId) = launch(browserId, settings)

    /**
     * Launch the system default browser, the system default browser is your daily used browser.
     * */
    @Throws(BrowserLaunchException::class)
    override fun launch(browserId: BrowserId, settings: BrowserSettings): Browser {
        val launcherOptions = LauncherOptions(settings)
        if (settings.isSupervised) {
            launcherOptions.supervisorProcess = settings.supervisorProcess
            launcherOptions.supervisorProcessArgs.addAll(settings.supervisorProcessArgs)
        }

        val capabilities = mutableMapOf<String, Any>()
        val proxyURI = browserId.fingerprint.proxyURI
        if (proxyURI != null) {
            capabilities["proxy"] = proxyURI
        }

        val launchOptions = settings.createChromeOptions(capabilities)

        return launch(browserId, launcherOptions, launchOptions)
    }

    /**
     * Launch the system default browser, the system default browser is your daily used browser.
     * */
    @Throws(BrowserLaunchException::class)
    override fun launchSystemDefaultBrowser(): Browser =
        launch(BrowserId.SYSTEM_DEFAULT, LauncherOptions(settings), ChromeOptions())

    /**
     * Launch the default browser, notice, the default browser is not the one you used daily.
     * */
    @Throws(BrowserLaunchException::class)
    override fun launchDefaultBrowser(): Browser = launch(BrowserId.DEFAULT, LauncherOptions(settings), ChromeOptions())

    /**
     * Launch the prototype browser, the prototype browser is a browser instance with default settings.
     * */
    @Throws(BrowserLaunchException::class)
    override fun launchPrototypeBrowser(): Browser = launch(BrowserId.PROTOTYPE, LauncherOptions(settings), ChromeOptions())

    /**
     * Launch the next sequential browser, the browser's user data dir rotates between a group of dirs.
     * */
    @Throws(BrowserLaunchException::class)
    override fun launchNextSequentialBrowser(): Browser =
        launch(BrowserId.NEXT_SEQUENTIAL, LauncherOptions(settings), ChromeOptions())

    /**
     * Launch a random temporary browser, the browser's user data dir is a random temporary dir.
     * */
    @Throws(BrowserLaunchException::class)
    override fun launchRandomTempBrowser(): Browser = launch(BrowserId.RANDOM_TEMP, LauncherOptions(settings), ChromeOptions())
}
