package ai.platon.pulsar.heavy.browser.driver

import ai.platon.pulsar.common.LinkExtractors
import ai.platon.pulsar.common.Runtimes
import ai.platon.pulsar.common.browser.BrowserType
import ai.platon.pulsar.common.config.ImmutableConfig
import ai.platon.pulsar.common.printlnPro
import ai.platon.pulsar.common.sleepSeconds
import ai.platon.pulsar.protocol.browser.DefaultWebDriverPoolManager
import ai.platon.pulsar.protocol.browser.driver.LoadingWebDriverPool
import ai.platon.pulsar.skeleton.common.AppSystemInfo
import ai.platon.pulsar.skeleton.crawl.fetch.driver.WebDriver
import ai.platon.pulsar.skeleton.crawl.fetch.privacy.BrowserId
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import java.util.concurrent.Executors
import kotlin.test.Test

class LoadingWebDriverPoolTest {
    private val config = ImmutableConfig()
    private lateinit var browserId: BrowserId
    private val poolManager = DefaultWebDriverPoolManager(config)
    private lateinit var pool: LoadingWebDriverPool
    private val seeds = LinkExtractors.fromResource("seeds100.txt")

    fun checkPlaywrightAvailable(): Boolean {
        if (!config.getBoolean("pulsar.test.playwright", false)) {
            return false
        }

        val outputs = Runtimes.exec("playwright --version")
        return outputs.any { it.contains("Version") }
    }

    @BeforeEach
    fun setup() {
        browserId = if (checkPlaywrightAvailable()) {
            BrowserId.createRandomTemp(BrowserType.PLAYWRIGHT_CHROME)
        } else {
            BrowserId.createRandomTemp()
        }

        pool = poolManager.createUnmanagedDriverPool(browserId)
    }

    @AfterEach
    fun tearDown() {
        pool.close()
        poolManager.close()
    }

    @Tag("Slow")
    @Test
    fun test_pollWebDrivers() {
        runBlocking {
            while(pool.numDriverSlots > 0 && !AppSystemInfo.isSystemOverCriticalLoad) {
                val driver = pool.poll()

                printlnPro("Created WebDriver #${driver.id} | ${pool.takeSnapshot()} | ${driver::class.qualifiedName}")

                driver.navigate(seeds.random())
                driver.waitForSelector("body")
                driver.stop()
            }
        }
    }

    @Tag("Slow")
    @Test
    fun test_pollAndPutWebDrivers() {
        val drivers = mutableListOf<WebDriver>()
        val executor = Executors.newFixedThreadPool(pool.numDriverSlots)

        var i = 0
        while(i++ < 60) {
            if (pool.numDriverSlots == 0) {
                sleepSeconds(1)
                continue
            }

            printlnPro("$i. Round $i polling a driver")
            val driver = pool.poll()
            drivers += driver

            printlnPro("Created WebDriver #${driver.id} | ${pool.takeSnapshot()} | ${driver::class.qualifiedName}")

            executor.submit {
                val url = seeds.random()
                navigate(url, driver)

                printlnPro("Navigated, put driver #${driver.id} | $url")
                pool.put(driver)
            }
        }

        drivers.forEach { it.close() }
    }

    private fun navigate(url: String, driver: WebDriver) {
        printlnPro("Navigating to $url")

        runBlocking {
            try {
                driver.navigate(url)
                // driver.waitForSelector("body")
                driver.delay(5000)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}

