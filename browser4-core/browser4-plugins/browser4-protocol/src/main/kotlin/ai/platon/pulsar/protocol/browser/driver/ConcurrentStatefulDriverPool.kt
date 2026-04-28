package ai.platon.pulsar.protocol.browser.driver

import ai.platon.pulsar.common.getLogger
import ai.platon.pulsar.common.warnInterruptible
import ai.platon.pulsar.protocol.browser.impl.BasicBrowserManager
import ai.platon.pulsar.skeleton.workflow.fetch.driver.AbstractWebDriver
import ai.platon.pulsar.skeleton.workflow.fetch.driver.BrowserManager
import ai.platon.pulsar.skeleton.workflow.fetch.driver.WebDriver
import kotlinx.coroutines.runBlocking
import java.util.*
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit

class ConcurrentStatefulDriverPool(
    private val browserManager: BrowserManager,
    private val capacity: Int
) {
    private val logger = getLogger(this)

    /**
     * Keep standby drivers
     * */
    private val _standbyDrivers = ArrayBlockingQueue<WebDriver>(2 * capacity)
    /**
     * Keep working drivers
     * */
    private val _workingDrivers = ConcurrentLinkedQueue<WebDriver>()
    /**
     * Keep retired drivers
     * */
    private val _retiredDrivers = ConcurrentLinkedQueue<WebDriver>()
    /**
     * Keep closed drivers
     * */
    private val _closedDrivers = ConcurrentLinkedQueue<WebDriver>()

    /**
     * Keep standby drivers
     * */
    val standbyDrivers: Queue<WebDriver> get() = _standbyDrivers
    /**
     * Keep working drivers
     * */
    val workingDrivers: Queue<WebDriver> get() = _workingDrivers
    /**
     * Keep retired drivers
     * */
    val retiredDrivers: Queue<WebDriver> get() = _retiredDrivers
    /**
     * Keep closed drivers
     * */
    val closedDrivers: Queue<WebDriver> get() = _closedDrivers

    @get:Synchronized
    val activeDriverCount get() = workingDrivers.size + standbyDrivers.size

    @Throws(InterruptedException::class)
    @Synchronized
    fun poll(timeout: Long, unit: TimeUnit): WebDriver? {
        val driver = _standbyDrivers.poll(timeout, unit)
        if (driver is AbstractWebDriver) {
            driver.startWork()
            _workingDrivers.add(driver)
        }
        return driver
    }

    @Synchronized
    fun offer(driver: WebDriver) {
        require(driver is AbstractWebDriver)

        if (!driver.isRecyclable) {
            // this driver is not recyclable, it should keep open
            return
        }

        driver.free()
        _workingDrivers.remove(driver)
        _standbyDrivers.offer(driver)
    }

    @Synchronized
    fun close(driver: WebDriver) {
        require(driver is AbstractWebDriver)

        // Make sure the status of the driver is correct

        _standbyDrivers.remove(driver)
        _workingDrivers.remove(driver)
        _retiredDrivers.remove(driver)
        _closedDrivers.add(driver)

        if (!driver.isRetired && !driver.isQuit) {
            driver.retire()
        }

        runCatching { browserManager.closeDriver(driver) }.onFailure { warnInterruptible(this, it) }

        // require(driver.isQuit)
    }

    @Synchronized
    fun retire() {
        val drivers = _standbyDrivers + _workingDrivers
        _standbyDrivers.clear()
        _workingDrivers.clear()
        _retiredDrivers.addAll(drivers)

        drivers.forEach { driver ->
            require(driver is AbstractWebDriver)
            // cancel the driver so the fetch task return immediately
            driver.cancel()
            // retire the driver, so it should not be used to fetch pages anymore
            driver.retire()
            // stop the driver so no more resource it uses
            kotlin.runCatching { runBlocking { driver.stop() } }.onFailure { warnInterruptible(this, it) }
        }
    }

    @Synchronized
    fun cancelAll() {
        _workingDrivers.forEach { (it as? AbstractWebDriver)?.cancel() }
    }

    @Synchronized
    fun clear() {
        standbyDrivers.clear()
        workingDrivers.clear()
        retiredDrivers.clear()
        closedDrivers.clear()
    }
}
