package ai.platon.pulsar.protocol.browser.driver.playwright

import ai.platon.pulsar.browser.driver.chrome.common.ChromeOptions
import ai.platon.pulsar.browser.driver.chrome.common.LauncherOptions
import ai.platon.pulsar.common.LinkExtractors
import ai.platon.pulsar.common.Runtimes
import ai.platon.pulsar.common.browser.BrowserType
import ai.platon.pulsar.common.sleepSeconds
import ai.platon.pulsar.skeleton.crawl.fetch.driver.WebDriver
import ai.platon.pulsar.skeleton.crawl.fetch.privacy.BrowserId
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import kotlin.test.Test

@Tag("OptionalTest")
class PlaywrightBrowserTest {

    private val browserId = BrowserId.createRandomTemp(BrowserType.PLAYWRIGHT_CHROME)
    private val launcherOptions = LauncherOptions()
    private val chromeOptions = ChromeOptions()
    private lateinit var browser: PlaywrightBrowser
    private val seeds = LinkExtractors.fromResource("seeds/seeds.txt")

    @BeforeEach
    fun checkIfGUIAvailable() {
        Assumptions.assumeTrue(Runtimes.isGUIAvailable(), "Test playwright only in a GUI environment")
    }

    @BeforeEach
    fun setup() {
        Assumptions.assumeTrue(Runtimes.isGUIAvailable(), "Test playwright only in a GUI environment")
        launcherOptions.settings.confuser.reset()
        browser = PlaywrightBrowserLauncher().launch(browserId, launcherOptions, chromeOptions)
    }

    @AfterEach
    fun tearDown() {
        if (::browser.isInitialized) {
            browser.close()
        }
    }

    @Test
    fun test_newDriverUnmanaged() = runBlocking {
        val drivers = mutableListOf<WebDriver>()

        seeds.shuffled().take(10).parallelStream().forEach { seed ->
            try {
                val driver = browser.newDriverUnmanaged(seed)
                drivers.add(driver)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        sleepSeconds(5)

        drivers.forEach { it.close() }
    }
}
