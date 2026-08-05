package ai.platon.pulsar.heavy.browser.driver

import ai.platon.pulsar.protocol.browser.DefaultWebDriverPoolManager
import ai.platon.pulsar.api.BrowserId
import ai.platon.pulsar.common.LinkExtractors
import ai.platon.pulsar.common.config.ImmutableConfig
import ai.platon.pulsar.common.printlnPro
import ai.platon.pulsar.api.WebDriver
import ai.platon.pulsar.protocol.browser.driver.LoadingWebDriverPool
import ai.platon.pulsar.skeleton.common.AppSystemInfo
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.util.concurrent.Executors

@Tag("ManualOnly")
class LoadingWebDriverPoolTest {
    private val config = ImmutableConfig()
    private lateinit var browserId: BrowserId
    private val poolManager = DefaultWebDriverPoolManager(config)
    private lateinit var pool: LoadingWebDriverPool
    private val seeds = LinkExtractors.fromResource("seeds100.txt")

    @BeforeEach
    fun setup() {
        browserId = BrowserId.createRandomTemp()
        pool = poolManager.createUnmanagedDriverPool(browserId)
    }

    @AfterEach
    fun tearDown() {
        pool.close()
        poolManager.close()
    }

    @Tag("Slow")
    @Tag("Heavy")
    @Test
    suspend fun test_pollWebDrivers() {
        var n = 20
        while (n-- > 0 && pool.numDriverSlots > 0 && !AppSystemInfo.isSystemOverCriticalLoad) {
            val driver = pool.poll()

            printlnPro("Created WebDriver #${driver.id} | ${pool.takeSnapshot()} | ${driver::class.qualifiedName}")

            driver.navigate(seeds.random())
            driver.waitForSelector("body")
            driver.stop()
        }
    }

    @Tag("Slow")
    @Tag("Heavy")
    @Test
    fun test_pollAndPutWebDrivers() {
        val slots = pool.numDriverSlots
        val executor = Executors.newFixedThreadPool(slots)
        val futures = mutableListOf<java.util.concurrent.Future<*>>()

        repeat(60) { round ->
            if (futures.size >= slots) {
                futures.removeAt(0).get()
            }

            val driver = pool.poll()

            printlnPro("${round + 1}. Round ${round + 1} polling a driver")
            printlnPro("Created WebDriver #${driver.id} | ${pool.takeSnapshot()} | ${driver::class.qualifiedName}")

            val future = executor.submit {
                val url = seeds.random()
                try {
                    navigate(url, driver)
                    printlnPro("Navigated, put driver #${driver.id} | $url")
                } finally {
                    pool.put(driver)
                }
            }

            futures += future
        }

        futures.forEach { it.get() }
        executor.shutdown()
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

