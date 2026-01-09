package ai.platon.pulsar.protocol.browser.driver.playwright

import ai.platon.pulsar.browser.driver.chrome.common.ChromeOptions
import ai.platon.pulsar.browser.driver.chrome.common.LauncherOptions
import ai.platon.pulsar.common.Runtimes
import ai.platon.pulsar.common.browser.BrowserType
import ai.platon.pulsar.skeleton.crawl.fetch.driver.IllegalWebDriverStateException
import ai.platon.pulsar.common.printlnPro
import ai.platon.pulsar.skeleton.crawl.fetch.privacy.BrowserId
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.assertThrows
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@Disabled("Run PlaywrightDriverTest manually")
@Tag("MustManuallyRun")
@Tag("OptionalTest")
class PlaywrightDriverTest {

    val browserId = BrowserId.createRandomTemp(BrowserType.PLAYWRIGHT_CHROME)
    val launcherOptions = LauncherOptions()
    val chromeOptions = ChromeOptions()
    lateinit var browser: PlaywrightBrowser
    lateinit var driver: PlaywrightDriver
    val url = "https://www.baidu.com"
    val url2 = "https://www.example.com"

    @BeforeEach
    fun checkIfGUIAvailable() {
        Assumptions.assumeTrue(Runtimes.isGUIAvailable(), "Test playwright only in a GUI environment")
    }

    @BeforeEach
    fun setup() {
        Assumptions.assumeTrue(Runtimes.isGUIAvailable(), "Test playwright only in a GUI environment")

        assertEquals("PLAYWRIGHT_CHROME", browserId.browserType.name)

        launcherOptions.settings.confuser.reset()
        browser = PlaywrightBrowserLauncher().launch(browserId, launcherOptions, chromeOptions)
        driver = browser.newDriver()
    }

    @AfterEach
    fun tearDown() {
        if (::driver.isInitialized) {
            driver.close()
        }

        if (::browser.isInitialized) {
            browser.close()
        }
    }

    @Test
    fun test_newDriver() {
        runBlocking {
            driver.navigateTo(url)
            val text = driver.selectFirstTextOrNull("body")
            assertNotNull(text)
            printlnPro(">>>\n" + text.take(100) + "\n<<<")
        }
    }

    @Test
    fun test_navigateTo() {
        runBlocking {
            driver.navigateTo(url)
            driver.waitForNavigation()
            driver.navigateTo(url2)
            driver.waitForNavigation()
            val text = driver.selectFirstTextOrNull("body")
            assertNotNull(text)
            printlnPro(">>>\n" + text.take(100) + "\n<<<")
        }
    }

    @Test
    fun test_navigateTo_ChromeBlank() {
        runBlocking {
            driver.navigateTo("about:blank")
            driver.waitForNavigation()
            val text = driver.selectFirstTextOrNull("body")
            assertNotNull(text)
            assertTrue { text.isBlank() }
        }
    }

    @Test
    fun test_navigateTo_InvalidURL() {
        assertThrows<IllegalWebDriverStateException> {
            runBlocking {
                // Protocol error (Page.navigate): Cannot navigate to invalid URL
                driver.navigateTo("    ")
            }
        }

        assertThrows<IllegalWebDriverStateException> {
            runBlocking {
                // net::ERR_FILE_NOT_FOUND
                driver.navigateTo("a://b")
            }
        }
    }

    @Test
    fun test_addInitScript() {
        runBlocking {
            browser.settings.confuser.clear()
            browser.settings.scriptLoader.reload()

            driver.addInitScript("window.__test_utils__ = { add: (a, b) => a + b }")

            driver.navigateTo(url2)
            driver.waitForNavigation()

            var result = driver.evaluate("1+1")
            assertEquals(2, result)

            result = driver.evaluate("typeof(window)")
            printlnPro("typeof(window) -> $result")
            assertEquals("object", result)


            result = driver.evaluate("typeof(__test_utils__)")
            printlnPro("typeof(__test_utils__) -> $result")
            assertEquals("object", result)


            result = driver.evaluate("typeof(__pulsar_)")
            printlnPro("typeof(__pulsar_) -> $result")
            assertEquals("function", result)

            result = driver.evaluate("__pulsar_utils__.add(1, 2)")
            printlnPro(result)
            assertEquals(3, result)
        }
    }

    @Test
    fun test_evaluate() {
        val selector = "a[href*=product]"
        // TODO: do not support abs prefix
        val attrName = "href"
        val expression = "__pulsar_utils__.selectAttributeAll('$selector', '$attrName')"
        runBlocking {
            driver.navigateTo("https://www.hua.com/")
            driver.waitForSelector("body")
            val result = driver.evaluate(expression)
            printlnPro(result)
        }
    }

    @Test
    fun test_navigateTo_Parallel() {
        runBlocking {
            driver.navigateTo("https://www.hua.com/")
            driver.waitForSelector("body")

            val links = driver.selectHyperlinks("a[href*=product]", 0, 10)
            assertTrue { links.isNotEmpty() }
            assertTrue { links.size <= 10 }
            links.map { link ->
                browser.newDriver().use {
                    // ReferenceError: __pulsar_utils__ is not defined
                    navigateTo(link.url, it)
                }
            }
        }
    }

    private fun navigateTo(url: String, driver: PlaywrightDriver) {
        runBlocking {
            printlnPro("Navigating - $url")
            driver.navigateTo(url)
            driver.waitForSelector("body")
            val text = driver.selectFirstTextOrNull("body")?.trim()
                ?.replace("\\s+".toRegex(), " ")
            assertNotNull(text)
            val start = text.length / 2
            printlnPro(">>>\n" + text.substring(start, start + 100) + "\n<<<")
        }
    }
}
