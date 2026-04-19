package ai.platon.pulsar.protocol.browser.impl

import ai.platon.browser4.driver.chrome.ChromeLauncher
import ai.platon.browser4.driver.chrome.common.ChromeOptions
import ai.platon.browser4.driver.chrome.common.LauncherOptions
import ai.platon.browser4.driver.chrome.util.ChromeLaunchException
import ai.platon.browser4.driver.common.BrowserSettings
import ai.platon.pulsar.protocol.browser.driver.cdt.PulsarBrowser
import ai.platon.pulsar.skeleton.crawl.fetch.driver.Browser
import ai.platon.pulsar.skeleton.crawl.fetch.driver.BrowserLaunchException
import ai.platon.pulsar.skeleton.crawl.fetch.driver.BrowserLauncher
import ai.platon.pulsar.skeleton.crawl.fetch.privacy.BrowserId

/**
 * A factory implementation to create browser instances.
 * */
open class PulsarBrowserLauncher: BrowserLauncher {

    override fun connect(port: Int, settings: BrowserSettings): Browser {
        return PulsarBrowser(port, settings = settings)
    }

    @Throws(BrowserLaunchException::class)
    override fun launch(
        browserId: BrowserId, launcherOptions: LauncherOptions, launchOptions: ChromeOptions
    ): Browser = launchPulsarBrowser0(browserId, launcherOptions, launchOptions)

    @Throws(BrowserLaunchException::class)
    private fun launchPulsarBrowser0(
        browserId: BrowserId, launcherOptions: LauncherOptions, launchOptions: ChromeOptions
    ): Browser {
        val browserSettings = launcherOptions.settings
        val browser = launchPulsarBrowser1(browserId, launcherOptions, launchOptions)

        if (!browserSettings.isGUI) {
            // Web drivers are in GUI mode, please close it manually
            // browser.registerShutdownHook()
        }

        return browser
    }

    @Synchronized
    @Throws(BrowserLaunchException::class)
    private fun launchPulsarBrowser1(
        browserId: BrowserId, launcherOptions: LauncherOptions, browserOptions: ChromeOptions
    ): PulsarBrowser {
        try {
            val launcher = ChromeLauncher(userDataDir = browserId.userDataDir, options = launcherOptions)
            val chrome = launcher.launch(browserOptions)
            return PulsarBrowser(browserId, chrome, launcherOptions.settings, launcher)
        } catch (e: ChromeLaunchException) {
            throw BrowserLaunchException("Failed to launch browser | $browserId", e)
        }
    }
}
