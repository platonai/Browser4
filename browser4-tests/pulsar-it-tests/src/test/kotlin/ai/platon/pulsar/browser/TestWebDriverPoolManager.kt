package ai.platon.pulsar.browser

import ai.platon.pulsar.common.config.ImmutableConfig
import ai.platon.pulsar.protocol.browser.DefaultWebDriverPoolManager
import ai.platon.pulsar.skeleton.common.AppSystemInfo
import ai.platon.pulsar.skeleton.crawl.fetch.driver.AbstractWebDriver
import ai.platon.pulsar.skeleton.crawl.fetch.driver.WebDriver
import ai.platon.pulsar.skeleton.crawl.fetch.privacy.BrowserId
import org.slf4j.LoggerFactory
import java.util.*
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.*
import org.junit.jupiter.api.DisplayName

class TestWebDriverPoolManager {
    companion object {
        val logger = LoggerFactory.getLogger(TestWebDriverPoolManager::class.java)

        lateinit var driverPoolManager: DefaultWebDriverPoolManager
        var quitMultiThreadTesting = false
    }

    @BeforeTest
    fun setup() {
        driverPoolManager = DefaultWebDriverPoolManager(ImmutableConfig())
    }

    @AfterTest
    fun tearDown() {
        driverPoolManager.close()
    }

    @Test
        @DisplayName("test createUnmanagedDriverPool")
    fun testCreateUnmanagedDriverPool() {
        val driverPool = driverPoolManager.createUnmanagedDriverPool(BrowserId.RANDOM_TEMP)
        val workingDrivers = mutableListOf<WebDriver>()
        var numDrivers = 0
        assertTrue("driverPool.capacity should not be too large, actual ${driverPool.capacity}") {
            driverPool.capacity <= 50
        }
        var i = 0
        while (i++ < driverPool.capacity && !AppSystemInfo.isSystemOverCriticalLoad) {
            val driver = driverPool.poll()
            require(driver is AbstractWebDriver)
            assertTrue { driver.isWorking }
            workingDrivers.add(driver)
            ++numDrivers
        }

        assertEquals(numDrivers, driverPool.numWorking)
        assertEquals(0, driverPool.numStandby)
        assertEquals(numDrivers, driverPool.numActive)

        workingDrivers.forEachIndexed { i, driver ->
            require(driver is AbstractWebDriver)

            if (i % 2 == 0) {
                driver.retire()
                assertTrue { driver.isRetired }
            } else {
                assertTrue { driver.isWorking }
            }

            driverPool.put(driver)
        }

        assertEquals(0, driverPool.numWorking)

        if (numDrivers % 2 == 0) {
            assertEquals(numDrivers / 2, driverPool.numStandby)
            assertEquals(numDrivers / 2, driverPool.meterClosed.count.toInt())
        }

        driverPool.close()

        assertEquals(0, driverPool.numWorking)
        assertEquals(0, driverPool.numStandby)
//        assertEquals(10, driverPool.counterClosed.count)
    }

    @Ignore("Time consuming (and also bugs)")
    @Test
    fun testWebDriverPoolMultiThreaded() {
        val driverPool = driverPoolManager.createUnmanagedDriverPool(BrowserId.RANDOM_TEMP)
        val workingDrivers = ArrayBlockingQueue<WebDriver>(30)

        val consumer = Thread {
            while (!quitMultiThreadTesting) {
                if (workingDrivers.size > 20) {
                    TimeUnit.MILLISECONDS.sleep(500)
                }

                if (workingDrivers.size < 20) {
                    driverPool.poll().let { workingDrivers.add(it) }
                }
            }
        }

        val producer = Thread {
            while (!quitMultiThreadTesting) {
                val i = Random().nextInt()
                val driver = workingDrivers.poll()
                if (driver != null) {
                    require(driver is AbstractWebDriver)

                    if (i % 3 == 0) {
                        logger.info("Offer {}", driver)
                        driverPool.put(driver)
                    } else {
                        logger.info("Retire {}", driver)
                        driver.retire()
                        driverPool.put(driver)
                    }
                }
            }
        }

        val closer = Thread {
            while (!quitMultiThreadTesting) {
                // log.info("Close all")
                driverPool.close()
                driverPool.close()
                Thread.sleep(1000)
            }
        }

        consumer.isDaemon = true
        producer.isDaemon = true
        closer.isDaemon = true

        consumer.start()
        producer.start()
        closer.start()

        var n = 360
        while (n-- > 0) {
            TimeUnit.SECONDS.sleep(1)
        }

        logger.info("All done.")
        quitMultiThreadTesting = true
        producer.join()
        consumer.join()
        closer.join()
    }
}
