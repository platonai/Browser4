package ai.platon.pulsar

import ai.platon.pulsar.browser.BrowserId
import ai.platon.pulsar.core.api.Browser
import ai.platon.pulsar.core.api.BrowserManager
import ai.platon.pulsar.core.api.WebDriver
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.apache.commons.lang3.StringUtils
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.time.Duration.Companion.milliseconds

open class WebDriverService(
    val browserManager: BrowserManager,
    val requiredPageSize: Int = 100
) {
    fun runWebDriverTest(block: suspend (driver: WebDriver) -> Unit) {
        runBlocking {
            browserManager.launchRandomTempBrowser().use {
                it.newDriver().use { driver ->
                    block(driver)
                }
            }
        }
    }

    fun runWebDriverTest(browserId: BrowserId, block: suspend (driver: WebDriver) -> Unit) {
        runBlocking {
            browserManager.launch(browserId, browserManager.settings).use {
                it.newDriver().use { driver ->
                    block(driver)
                }
            }
        }
    }

    fun runWebDriverTest(url: String, block: suspend (driver: WebDriver) -> Unit) {
        runBlocking {
            browserManager.launchRandomTempBrowser().use {
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

    fun runEnhancedWebDriverTest(url: String, block: suspend (driver: WebDriver) -> Unit) {
        runBlocking {
            browserManager.launchRandomTempBrowser().use {
                it.newDriver().use { driver ->
                    openEnhanced(url, driver)

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

    fun runEnhancedWebDriverTest(url: String, browser: Browser, block: suspend (driver: WebDriver) -> Unit) {
        runBlocking {
            browser.newDriver().use { driver ->
                openEnhanced(url, driver)

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

    fun runEnhancedWebDriverTest(browser: Browser, block: suspend (driver: WebDriver) -> Unit) {
        runBlocking {
            browser.newDriver().use { block(it) }
        }
    }

    open suspend fun open(url: String, driver: WebDriver, scrollCount: Int = 3) {
        driver.navigate(url)
        driver.waitForNavigation()
        var n = scrollCount
        while (n-- > 0) {
            driver.scrollDown(1)
            delay(1000)
        }
        driver.scrollToTop()
    }

    open suspend fun openEnhanced(url: String, driver: WebDriver, scrollCount: Int = 3) {
        driver.navigate(url)
        driver.waitForSelector("body")
//        driver.waitForSelector("input[id]")

        // make sure all metadata are available
        driver.evaluate("__pulsar_utils__.waitForReady()")
        // make sure all metadata are available
        driver.evaluate("__pulsar_utils__.compute()")

//        driver.bringToFront()
        var n = scrollCount
        while (n-- > 0) {
            driver.scrollDown(1)
            delay(1000.milliseconds)
        }
        driver.scrollToTop()

        val pageSource = driver.pageSource()
        val display = StringUtils.abbreviateMiddle(pageSource, "...", 100)
        assumeTrue({ (pageSource?.length ?: 0) > requiredPageSize }, "Page source is too small | $display")
    }
}

open class FastWebDriverService(
    browserManager: BrowserManager,
    requiredPageSize: Int = 1
) : WebDriverService(browserManager, requiredPageSize) {
    override suspend fun openEnhanced(url: String, driver: WebDriver, scrollCount: Int) {
        driver.navigate(url)
        driver.delay(1000)

        // make sure all metadata are available
        driver.evaluateDetail("__pulsar_utils__.waitForReady()")
        // make sure all metadata are available
        driver.evaluateDetail("__pulsar_utils__.compute()")

        val pageSource = driver.pageSource()
        val display = StringUtils.abbreviateMiddle(pageSource, "...", 100)
        assumeTrue({ (pageSource?.length ?: 0) > requiredPageSize }, "Page source is too small | $display")
    }
}
