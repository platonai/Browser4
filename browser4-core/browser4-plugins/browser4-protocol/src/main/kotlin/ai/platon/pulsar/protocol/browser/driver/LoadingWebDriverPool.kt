package ai.platon.pulsar.protocol.browser.driver

import ai.platon.pulsar.common.AppContext
import ai.platon.pulsar.common.brief
import ai.platon.pulsar.common.config.AppConstants.DEFAULT_BROWSER_MAX_OPEN_TABS
import ai.platon.pulsar.common.config.CapabilityTypes.BROWSER_DRIVER_POOL_IDLE_TIMEOUT
import ai.platon.pulsar.common.config.CapabilityTypes.BROWSER_MAX_OPEN_TABS
import ai.platon.pulsar.common.config.ImmutableConfig
import ai.platon.pulsar.common.config.MutableConfig
import ai.platon.pulsar.common.config.VolatileConfig
import ai.platon.pulsar.common.readable
import ai.platon.pulsar.common.stringify
import ai.platon.pulsar.persist.WebPage
import ai.platon.pulsar.protocol.browser.emulator.WebDriverPoolExhaustedException
import ai.platon.pulsar.skeleton.browser.Browser
import ai.platon.pulsar.skeleton.browser.BrowserManager
import ai.platon.pulsar.skeleton.browser.driver.*
import ai.platon.pulsar.skeleton.common.AppSystemInfo
import ai.platon.pulsar.skeleton.common.metrics.MetricsSystem
import ai.platon.pulsar.skeleton.event.BrowseEventHandlers
import ai.platon.pulsar.skeleton.event.PulsarEventBus
import ai.platon.pulsar.skeleton.workflow.fetch.privacy.BrowserId
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Created by Vincent on 18-1-1.
 * Copyright @ 2013-2023 Platon AI. All rights reserved
 */
class LoadingWebDriverPool constructor(
    val browserId: BrowserId,
    val browserManager: BrowserManager,
    val immutableConfig: ImmutableConfig
) : AutoCloseable {
    companion object {
        var CLOSE_ALL_TIMEOUT = Duration.ofSeconds(60)
        var POLLING_TIMEOUT = Duration.ofSeconds(60)
        private val ID_SUPPLIER = AtomicInteger()
    }

    private val logger = LoggerFactory.getLogger(LoadingWebDriverPool::class.java)

    val id = ID_SUPPLIER.incrementAndGet()

    private val registry = MetricsSystem.defaultMetricRegistry

    /**
     * The max number of drivers the pool can hold
     * */
    val capacity: Int
        get() {
            val c = immutableConfig.get(BROWSER_MAX_OPEN_TABS)?.toIntOrNull() ?: DEFAULT_BROWSER_MAX_OPEN_TABS
            return c.coerceAtMost(50)
        }

    /**
     * The browser who create all drivers for this pool.
     * */
    private var _browser: Browser? = null

    /**
     * The consistent stateful driver
     * */
    private val statefulDriverPool = ConcurrentStatefulDriverPool(browserManager, capacity)

    /**
     * Standby drivers and working drivers
     * */
    private val activeDrivers: Collection<WebDriver> get() = statefulDriverPool.standbyDrivers + statefulDriverPool.workingDrivers

    val meterClosed = registry.meter(this, "closed")
    val meterOffer = registry.meter(this, "offer")

    /**
     * Retired but not closed yet.
     * */
    private var isRetired = false
    private val closed = AtomicBoolean()
    val isClosed get() = closed.get()
    val isActive get() = !isClosed && !isRetired && AppContext.isActive
    private val launchEventsEmitted = AtomicBoolean()
    private val _numCreatedDrivers = AtomicInteger()
    private val _numWaitingTasks = AtomicInteger()

    /**
     * Number of created drivers, should be equal to numStandby + numWorking + numRetired.
     * */
    val numCreated get() = _numCreatedDrivers.get()
    val numWaiting get() = _numWaitingTasks.get()

    /**
     * Number of drivers on standby.
     * */
    val numStandby get() = statefulDriverPool.standbyDrivers.size

    /**
     * Number of all possible working drivers
     * */
    val numAvailable get() = numStandby + numDriverSlots

    /**
     * Number of drivers at work.
     * */
    val numWorking get() = statefulDriverPool.workingDrivers.size

    /**
     * Number of retired drivers.
     * */
    val numRetired get() = statefulDriverPool.retiredDrivers.size

    /**
     * Number of closed drivers.
     * */
    val numClosed get() = statefulDriverPool.closedDrivers.size

    /**
     * Number of active drivers.
     * */
    val numActive get() = numWorking + numStandby

    /**
     * Number of available slots to allocate new drivers
     * */
    val numDriverSlots get() = capacity - numActive

    /**
     * The last active time of the pool.
     * */
    var lastActiveTime: Instant = Instant.now()
        private set

    /**
     * The idle timeout of the pool.
     * */
    val idleTimeout get() = immutableConfig.getDuration(BROWSER_DRIVER_POOL_IDLE_TIMEOUT, Duration.ofMinutes(20))

    /**
     * The idle time of the pool.
     * */
    val idleTime get() = Duration.between(lastActiveTime, Instant.now())

    /**
     * Check if the pool is idle. If there is no working driver and the idle time is longer than the idle timeout,
     * the pool is idle. If the pool is idle, it should be closed.
     *
     * TODO: consider permanent browsers
     * */
    val isIdle get() = (numWorking == 0 && idleTime > idleTimeout)

    val isPermanent get() = browserId.profile.isPermanent

    class Snapshot(
        val numActive: Int,
        val numStandby: Int,
        val numWaiting: Int,
        val numWorking: Int,
        val numDriverSlots: Int,
        val numRetired: Int,
        val numClosed: Int,
        val isRetired: Boolean,
        val isIdle: Boolean,
        val idleTime: Duration
    ) {
        fun format(verbose: Boolean): String {
            val status = if (verbose) {
                String.format(
                    "active: %d, standby: %d, waiting: %d, working: %d, slots: %d, retired: %d, closed: %d",
                    numActive, numStandby, numWaiting, numWorking, numDriverSlots, numRetired, numClosed
                )
            } else {
                String.format(
                    "%d/%d/%d/%d/%d/%d/%d (active/standby/waiting/working/slots/retired/closed)",
                    numActive, numStandby, numWaiting, numWorking, numDriverSlots, numRetired, numClosed
                )
            }

            val time = idleTime.readable()
            return when {
                AppSystemInfo.isCriticalDiskSpace -> "[Critical disk space] | $status"
                AppSystemInfo.isCriticalMemory -> "[Critical memory] | $status"
                AppSystemInfo.isCriticalCPULoad -> "[Critical CPU] | $status"
                AppSystemInfo.isSystemOverCriticalLoad -> "[System over critical load] | $status"
                isIdle -> "[Idle] $time | $status"
                isRetired -> "[Retired] | $status"
                else -> status
            }
        }

        override fun toString() = format(false)
    }

    /**
     * Retrieves and removes the head of this free driver queue,
     * or returns {@code null} if there is no free drivers.
     *
     * @return the head of the free driver queue, or {@code null} if this queue is empty
     */
    @Throws(BrowserLaunchException::class, WebDriverPoolExhaustedException::class, InterruptedException::class)
    fun poll(): WebDriver = poll(VolatileConfig.UNSAFE)

    @Throws(BrowserLaunchException::class, WebDriverPoolExhaustedException::class, InterruptedException::class)
    fun poll(conf: VolatileConfig): WebDriver = poll(0, conf, POLLING_TIMEOUT.seconds, TimeUnit.SECONDS)

    @Throws(BrowserLaunchException::class, WebDriverPoolExhaustedException::class, InterruptedException::class)
    fun poll(priority: Int, conf: MutableConfig, timeout: Duration): WebDriver {
        return poll(priority, conf, timeout.seconds, TimeUnit.SECONDS)
    }

    @Throws(BrowserLaunchException::class, WebDriverPoolExhaustedException::class, InterruptedException::class)
    fun poll(priority: Int, conf: MutableConfig, timeout: Long, unit: TimeUnit): WebDriver {
        val driver = pollWebDriver(priority, conf, timeout, unit)
        if (driver == null) {
            val snapshot = takeSnapshot()
            val message = String.format("%s", snapshot.format(true))
            if (AppContext.isActive) {
                // log only when the application is active
                logger.info("Driver pool is exhausted, rethrow WebDriverPoolExhaustedException | $message")
            }
            throw WebDriverPoolExhaustedException(browserId.toString(), "Driver pool is exhausted ($snapshot)")
        }

        return driver
    }

    /**
     * Poll a [WebDriver]. If it's the browser is not launched yet, launch it and emit launch events
     * */
    @Throws(BrowserLaunchException::class, WebDriverPoolExhaustedException::class)
    suspend fun poll(priority: Int, conf: MutableConfig, event: BrowseEventHandlers?, page: WebPage): WebDriver {
        val settings = browserManager.settings
        val timeout = settings.pollingDriverTimeout

        // NOTE: concurrency note - if multiple threads come to the code snippet,
        // only one goes to pollWithEvents, others wait in poll
        val notEmitted = launchEventsEmitted.compareAndSet(false, true)
        return if (notEmitted) {
            pollWithEvents(priority, conf, event, page, timeout)
        } else {
            poll(priority, conf, timeout)
        }
    }

    fun put(driver: WebDriver) {
        lastActiveTime = Instant.now()
        offerOrDismiss(driver)
    }

    private fun offerOrDismiss(driver: WebDriver) {
        require(driver is AbstractWebDriver)
        if (driver.isWorking) {
            statefulDriverPool.offer(driver)
            meterOffer.mark()
        } else {
            val browser = driver.browser
            if (browser.isActive) {
                logger.warn(
                    "Closing driver that doesn't work unexpectedly #{}: {} | browser #{}:{}",
                    driver.id, driver.status, browser.instanceId, browser.readableState
                )
            } else {
                logger.debug(
                    "Closing driver that doesn't work #{}: {} | browser #{}:{}",
                    driver.id, driver.status, browser.instanceId, browser.readableState
                )
            }

            statefulDriverPool.close(driver)
            meterClosed.mark()
        }
    }

    /**
     * Force the page stop all navigations.
     * Mark the driver pool be retired, but not closed yet.
     * */
    fun retire() {
        isRetired = true
        statefulDriverPool.retire()
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            statefulDriverPool.clear()
        }
    }

    fun forEach(action: (WebDriver) -> Unit) = activeDrivers.forEach(action)

    fun firstOrNull(predicate: (WebDriver) -> Boolean) = activeDrivers.firstOrNull(predicate)

    fun cancel(url: String): WebDriver? {
        return activeDrivers.lastOrNull { it.navigateEntry.pageUrl == url }?.also {
            require(it is AbstractWebDriver)
            it.cancel()
        }
    }

    /**
     * Cancel all the fetch tasks, stop loading all pages, the execution will throw a CancellationException.
     *
     * */
    fun cancelAll() {
        // mark each driver be canceled, so the execution will throw a CancellationException
        statefulDriverPool.cancelAll()
    }

    override fun toString(): String = takeSnapshot().format(false)

    /**
     * Take an imprecise snapshot
     * */
    fun takeSnapshot(): Snapshot {
        return Snapshot(
            numActive,
            numStandby,
            numWaiting,
            numWorking,
            numDriverSlots,
            numRetired,
            numClosed,
            isRetired,
            isIdle,
            idleTime,
        )
    }

    @Throws(BrowserLaunchException::class, WebDriverPoolExhaustedException::class)
    private suspend fun pollWithEvents(
        priority: Int, conf: MutableConfig, event: BrowseEventHandlers?, page: WebPage, timeout: Duration
    ): WebDriver {
        // TODO: is it better to handle launch events in browser?
        dispatchEvent("onWillLaunchBrowser") {
            PulsarEventBus.emitBrowseEvent("onWillLaunchBrowser", page)
            event?.onWillLaunchBrowser?.invoke(page)
        }

        return poll(priority, conf, timeout).also { driver ->
            dispatchEvent("onBrowserLaunched") {
                event?.onBrowserLaunched?.invoke(page, driver)
                PulsarEventBus.emitBrowseEvent("onBrowserLaunched", page)
            }
        }
    }

    @Throws(BrowserLaunchException::class, InterruptedException::class)
    private fun pollWebDriver(priority: Int, conf: MutableConfig, timeout: Long, unit: TimeUnit): WebDriver? {
        _numWaitingTasks.incrementAndGet()

        val driver = try {
            resourceSafeCreateDriverIfNecessary(priority, conf)
            statefulDriverPool.poll(timeout, unit)
        } finally {
            _numWaitingTasks.decrementAndGet()
            lastActiveTime = Instant.now()
        }

        return driver
    }

    /**
     * Create a driver if necessary.
     * Web drivers are open in sequence, so memory and CPU usage will not skyrocket.
     * */
    @Throws(BrowserLaunchException::class)
    private fun resourceSafeCreateDriverIfNecessary(priority: Int, conf: MutableConfig) {
        synchronized(browserManager) {
            if (!isActive) {
                return
            }

            if (!shouldCreateWebDriver()) {
                return
            }

            val driver = computeBrowserAndDriver(priority, conf)
        }
    }

    @Throws(BrowserLaunchException::class)
    private fun computeBrowserAndDriver(priority: Int, conf: MutableConfig): WebDriver {
        return computeBrowserAndDriver0(conf)
    }

    /**
     * Check if we should create a new web driver.
     * */
    private fun shouldCreateWebDriver(): Boolean {
        // Using the count of non-quit drivers can better match the memory consumption,
        // but it's easy to wrongly count the quit drivers, a tiny bug can lead to a big mistake.
        // We leave a debug log here for diagnosis purpose.
        val resourceConsumingDriversInBrowser = _browser?.drivers?.values
            ?.filterIsInstance<AbstractWebDriver>()
            ?.count { !it.isQuit && !it.isRetired } ?: 0
        // Number of active drivers in this driver pool
        val resourceConsumingDriversInPool = statefulDriverPool.activeDriverCount
        if (resourceConsumingDriversInBrowser != resourceConsumingDriversInPool) {
            logger.debug(
                "Inconsistent online driver status, resource consuming drivers: {}/{}/{} (slots/pool/browser)",
                numDriverSlots, resourceConsumingDriversInPool, resourceConsumingDriversInBrowser
            )
        }

        val isCriticalResources = AppSystemInfo.isSystemOverCriticalLoad
        if (resourceConsumingDriversInPool >= capacity) {
            // should also: numDriverSlots > 0
            logger.debug(
                "Enough online drivers, will not create new one." +
                        " Resource consuming drivers: {}/{}/{} (slots/pool/browser)",
                numDriverSlots, resourceConsumingDriversInPool, resourceConsumingDriversInBrowser
            )
        } else if (AppSystemInfo.isCriticalMemory) {
            logger.info(
                "Critical memory: {}, resource consuming drivers: {}/{}/{} (slots/pool/browser), will not create new driver",
                AppSystemInfo.formatAvailableMemory(),
                numDriverSlots, resourceConsumingDriversInPool, resourceConsumingDriversInBrowser
            )
        }

        return isActive && !isCriticalResources && resourceConsumingDriversInPool < capacity
    }

    @Throws(WebDriverException::class)
    private fun computeBrowserAndDriver0(conf: MutableConfig): WebDriver {
        logger.debug("Launch browser and new driver | {}", browserId)

        // Use BrowserFactory's default settings
        val settings = browserManager.settings
        //  Launch a browser. If the browser with the id is already launched, return the existing one.
        // val browser = _browser ?: browserFactory.launch(browserId, settings)
        val browser = _browser ?: browserManager.launch(browserId, settings)
        check(browser.isActive)
        // open a new tab about:blank
        val driver = browser.newDriver()

        _browser = browser
        _numCreatedDrivers.incrementAndGet()
        statefulDriverPool.offer(driver)

        if (logger.isDebugEnabled) {
            logDriverOnline(driver)
        }

        return driver
    }

    private suspend fun dispatchEvent(name: String, action: suspend () -> Unit) {
        if (!isActive) {
            return
        }

        try {
            action()
        } catch (e: WebDriverCancellationException) {
            logger.info("Web driver is cancelled")
        } catch (e: WebDriverException) {
            logger.warn(e.brief("[Ignored][$name] "))
        } catch (e: Exception) {
            logger.warn(e.stringify("[Ignored][$name] "))
        } catch (e: Throwable) {
            logger.error(e.stringify("[Unexpected][$name] "))
        }
    }

    private fun logDriverOnline(driver: WebDriver) {
        require(driver is AbstractWebDriver)
        val browserSettings = driver.browser.settings
        logger.trace(
            "The {}th web driver is active, browser: {} pageLoadStrategy: {} capacity: {}",
            numActive, driver.name,
            browserSettings.pageLoadStrategy, capacity
        )
    }
}
