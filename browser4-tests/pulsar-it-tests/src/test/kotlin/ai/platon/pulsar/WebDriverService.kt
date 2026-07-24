package ai.platon.pulsar

import ai.platon.browser4.api.BrowserId
import ai.platon.browser4.api.manage.BrowserFactory
import ai.platon.pulsar.core.api.Browser
import ai.platon.pulsar.core.api.WebDriver
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.apache.commons.lang3.StringUtils
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds

open class WebDriverService(
    val browserFactory: BrowserFactory,
    val requiredPageSize: Int = 100
) {
    fun runWebDriverTest(block: suspend (driver: WebDriver) -> Unit) {
        runBlocking {
            browserFactory.launchRandomTempBrowser().use {
                it.newDriver().use { driver ->
                    block(driver)
                }
            }
        }
    }

    fun runWebDriverTest(browserId: BrowserId, block: suspend (driver: WebDriver) -> Unit) {
        runBlocking {
            browserFactory.launch(browserId).use {
                it.newDriver().use { driver ->
                    block(driver)
                }
            }
        }
    }

    fun runWebDriverTest(url: String, block: suspend (driver: WebDriver) -> Unit) {
        runBlocking {
            browserFactory.launchRandomTempBrowser().use {
                it.newDriver().use { driver ->
                    open(url, driver)
                    block(driver)
                }
            }
        }
    }

    fun runWebDriverTest(url: String, browser: Browser, block: suspend (driver: WebDriver) -> Unit) {
        runBlocking {
            browser.newDriver().use { driver ->
                open(url, driver)
                block(driver)
            }
        }
    }

    fun runWebDriverTestAndCompute(url: String, block: suspend (driver: WebDriver) -> Unit) {
        runBlocking {
            browserFactory.launchRandomTempBrowser().use {
                it.newDriver().use { driver ->
                    openAndCompute(url, driver)

                    val pageSource = driver.pageSource()
                    val display = StringUtils.abbreviateMiddle(pageSource, "...", 100)
                    assumeTrue(
                        { (pageSource?.length ?: 0) > requiredPageSize },
                        "Page source is too small | $display"
                    )

                    block(driver)
                }
            }
        }
    }

    fun runWebDriverTestAndCompute(url: String, browser: Browser, block: suspend (driver: WebDriver) -> Unit) {
        runBlocking {
            browser.newDriver().use { driver ->
                openAndCompute(url, driver)

                val pageSource = driver.pageSource()
                val display = StringUtils.abbreviateMiddle(pageSource, "...", 100)
                assumeTrue(
                    { (pageSource?.length ?: 0) > requiredPageSize },
                    "Page source is too small | $display"
                )

                block(driver)
            }
        }
    }

    fun runWebDriverTestAndCompute(browser: Browser, block: suspend (driver: WebDriver) -> Unit) {
        runBlocking {
            browser.newDriver().use { block(it) }
        }
    }

    /**
     * Waits for __pulsar_utils__ to be available after navigation.
     *
     * After frame navigation, the isolated world context is cleared and recreated asynchronously.
     * In fast-loading environments (e.g. CI headless mode), waitForNavigation may detect the
     * URL change before the onFrameNavigated handler has finished recreating the isolated world
     * and re-injecting the runtime. This helper retries with a short delay to bridge that gap.
     */
    suspend fun waitForPulsarUtils(driver: WebDriver, maxRetries: Int = 10, delayMs: Long = 100) {
        repeat(maxRetries) { attempt ->
            val result = driver.evaluateValue("typeof(__pulsar_utils__)")
            if (result?.toString() == "function") return
            if (attempt < maxRetries - 1) {
                delay(delayMs.milliseconds)
            }
        }
        val finalResult = driver.evaluateValue("typeof(__pulsar_utils__)")
        assertEquals("function", finalResult?.toString(), "__pulsar_utils__ is not injected properly")
    }

    open suspend fun open(url: String, driver: WebDriver, scrollCount: Int = 3) {
        driver.navigate(url)

        driver.waitForNavigation()
        driver.waitForSelector("body")
        waitForPulsarUtils(driver)

        driver.waitForNavigation()
        var n = scrollCount
        while (n-- > 0) {
            driver.scrollDown(1)
            delay(1000.milliseconds)
        }
        driver.scrollToTop()
    }

    open suspend fun openAndCompute(url: String, driver: WebDriver, scrollCount: Int = 3) {
        driver.navigate(url)
        driver.waitForNavigation()
        driver.waitForSelector("body")
        waitForPulsarUtils(driver)

        // make sure all metadata are available
        driver.evaluate("__pulsar_utils__.waitForReady()")
        // make sure all metadata are available
        driver.evaluate("__pulsar_utils__.compute()")

//        driver.bringToFront()
        var n = scrollCount
        while (n-- > 0) {
            driver.scrollDown(1)
            delay(1000)
        }
        driver.scrollToTop()

        val pageSource = driver.pageSource()
        val display = StringUtils.abbreviateMiddle(pageSource, "...", 100)
        assumeTrue({ (pageSource?.length ?: 0) > requiredPageSize }, "Page source is too small | $display")
    }
}

open class FastWebDriverService(
    browserFactory: BrowserFactory,
    requiredPageSize: Int = 1
) : WebDriverService(browserFactory, requiredPageSize) {
    override suspend fun openAndCompute(url: String, driver: WebDriver, scrollCount: Int) {
        driver.navigate(url)

        driver.waitForNavigation()
        driver.waitForSelector("body")
        waitForPulsarUtils(driver)

        // make sure all metadata are available
        driver.evaluateDetail("__pulsar_utils__.waitForReady()")
        // make sure all metadata are available
        driver.evaluateDetail("__pulsar_utils__.compute()")

        val pageSource = driver.pageSource()
        val display = StringUtils.abbreviateMiddle(pageSource, "...", 100)
        assumeTrue({ (pageSource?.length ?: 0) > requiredPageSize }, "Page source is too small | $display")
    }
}
