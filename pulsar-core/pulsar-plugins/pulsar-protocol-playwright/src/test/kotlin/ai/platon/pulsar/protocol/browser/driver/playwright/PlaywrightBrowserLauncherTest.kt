package ai.platon.pulsar.protocol.browser.driver.playwright

import ai.platon.pulsar.browser.driver.chrome.common.ChromeOptions
import ai.platon.pulsar.browser.driver.chrome.common.LauncherOptions
import ai.platon.pulsar.common.Runtimes
import ai.platon.pulsar.common.browser.BrowserType
import ai.platon.pulsar.skeleton.crawl.fetch.driver.Browser
import ai.platon.pulsar.skeleton.crawl.fetch.driver.WebDriver
import ai.platon.pulsar.skeleton.crawl.fetch.privacy.BrowserId
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("OptionalTest")
class PlaywrightBrowserLauncherTest: PlaywrightTestBase() {
    private val browserLauncher = PlaywrightBrowserLauncher()

    @Test
    fun testPlaywright() {
        val options = com.microsoft.playwright.BrowserType.LaunchOptions()
        options.headless = false
        val browser = playwright.chromium().launch(options)
        assertTrue { browser.isConnected }
        val page = browser.newPage()

        val url = "https://www.baidu.com/"
        page.navigate(url)
        page.waitForURL(url)
        assertTrue { page.querySelector("body").isVisible }
    }

    @Test
    fun testPlaywrightLaunchPersistentContext() {
        val browserId = BrowserId.createRandomTemp(BrowserType.PLAYWRIGHT_CHROME)
        val options = com.microsoft.playwright.BrowserType.LaunchPersistentContextOptions()
        options.headless = false
        val browserContext = playwright.chromium().launchPersistentContext(browserId.userDataDir, options)
        val page = browserContext.newPage()

        val url = "https://www.baidu.com/"
        page.navigate(url)
        page.waitForURL(url)
        assertTrue { page.querySelector("body").isVisible }

        browserContext.close()
    }

    @Test
    fun testLaunchRandomBrowser() {
        val browserId = BrowserId.createRandomTemp(BrowserType.PLAYWRIGHT_CHROME)
        val chromeOptions = ChromeOptions()
        chromeOptions.headless = false

        val browser = browserLauncher.launch(browserId, LauncherOptions(), chromeOptions)
        val driver = browser.newDriver()
        testBrowserAndDriver(browser, driver)
        assertFalse(browser.isPermanent)

        browser.close()
    }

    @Test
    fun testLaunchDefaultBrowser() {
        val browserId = BrowserId.createDefault(BrowserType.PLAYWRIGHT_CHROME)
        val chromeOptions = ChromeOptions()
        chromeOptions.headless = false

        val browser = browserLauncher.launch(browserId, LauncherOptions(), chromeOptions)
        val driver = browser.newDriver()
        testBrowserAndDriver(browser, driver)
        assertTrue(browser.isPermanent)

        browser.close()
    }

    private fun testBrowserAndDriver(browser: Browser, driver: WebDriver) {
        val url = "https://www.baidu.com/"

        runBlocking {
            driver.navigateTo(url)
            driver.waitForNavigation()
        }

        assertNotNull(browser)
        assertFalse(browser.isClosed)
        assertTrue(browser.isActive)
        assertTrue(browser.isConnected)
        assertFalse(browser.isIdle)
    }
}
