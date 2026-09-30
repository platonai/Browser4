package ai.platon.pulsar.protocol.browser.driver

import ai.platon.pulsar.api.AbstractWebDriver
import ai.platon.pulsar.api.BrowserId
import ai.platon.pulsar.api.model.BrowserLaunchException
import ai.platon.pulsar.api.model.WebDriverCancellationException
import ai.platon.pulsar.api.model.WebDriverException
import ai.platon.pulsar.common.AppContext
import ai.platon.pulsar.common.brief
import ai.platon.pulsar.common.config.AppConstants.DEFAULT_BROWSER_MAX_OPEN_TABS
import ai.platon.pulsar.common.config.CapabilityTypes.BROWSER_DRIVER_POOL_IDLE_TIMEOUT
import ai.platon.pulsar.common.config.CapabilityTypes.BROWSER_MAX_OPEN_TABS
import ai.platon.pulsar.common.config.ImmutableConfig
import ai.platon.pulsar.common.config.MutableConfig
import ai.platon.pulsar.common.config.VolatileConfig
import ai.platon.pulsar.common.logging.ThrottlingLogger
import ai.platon.pulsar.common.readable
import ai.platon.pulsar.common.stringify
import ai.platon.pulsar.api.Browser
import ai.platon.pulsar.api.BrowserManager
import ai.platon.pulsar.api.WebDriver
import ai.platon.pulsar.persist.WebPage
import ai.platon.pulsar.protocol.browser.emulator.WebDriverPoolExhaustedException
import ai.platon.pulsar.skeleton.common.AppSystemInfo
import ai.platon.pulsar.skeleton.common.metrics.MetricsSystem
import ai.platon.pulsar.skeleton.event.BrowseEventHandlers
import ai.platon.pulsar.skeleton.event.PulsarEventBus
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

        /**
         * The wait for a driver is done in slices of this duration, see [pollDriverInSlices].
         *
         * The slice has to be short enough to re-evaluate the resource guard while waiting - the guard
         * can refuse to create a driver during a transient load spike - and long enough to avoid busy
         * waiting. It also bounds how long the stateful driver pool is locked while waiting.
         * */
        var POLLING_SLICE = Duration.ofMillis(500)

        private val ID_SUPPLIER = AtomicInteger()

        /**
         * A wait at least this long for a driver is reported in the debug log, together with the
         * pool state and the drivers the pool could not hand out.
         *
         * A driver is normally handed out in microseconds, so this is a *long* wait, not a slow one:
         * the point is to make a stalled fetch explainable from the pool's own log instead of
         * inferring it from a page that "took 40 seconds".
         * */
        var WAIT_DEBUG_THRESHOLD = Duration.ofSeconds(1)

        /**
         * A wait at least this long is reported at the warn level, throttled, see [reportDriverWait].
         * */
        var WAIT_WARN_THRESHOLD = Duration.ofSeconds(10)
    }

    private val logger = LoggerFactory.getLogger(LoadingWebDriverPool::class.java)
    private val throttlingLogger = ThrottlingLogger(logger, ttl = Duration.ofMinutes(1))

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
        // Milliseconds, not seconds: `timeout.seconds` truncates, so any sub-second timeout became
        // "do not wait at all" - a caller asking for 700ms got an immediate pool-exhausted failure.
        return poll(priority, conf, timeout.toMillis(), TimeUnit.MILLISECONDS, null)
    }

    @Throws(BrowserLaunchException::class, WebDriverPoolExhaustedException::class, InterruptedException::class)
    fun poll(priority: Int, conf: MutableConfig, timeout: Long, unit: TimeUnit): WebDriver {
        return poll(priority, conf, timeout, unit, null)
    }

    /**
     * Poll a driver, recording [waiter] as the task that had to wait for it.
     *
     * [waiter] is the page the caller wants to fetch when the caller knows it (the browsing path
     * does); it is what makes a long wait attributable in the log.  The diagnostics themselves are
     * in [reportDriverWait].
     * */
    @Throws(BrowserLaunchException::class, WebDriverPoolExhaustedException::class, InterruptedException::class)
    private fun poll(priority: Int, conf: MutableConfig, timeout: Long, unit: TimeUnit, waiter: String?): WebDriver {
        val start = System.nanoTime()
        val driver = pollWebDriver(priority, conf, timeout, unit, waiter)
        val waitedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)

        if (driver == null) {
            val snapshot = takeSnapshot()
            val message = String.format("%s", snapshot.format(true))
            if (AppContext.isActive) {
                // log only when the application is active
                logger.info(
                    "Driver pool is exhausted after {}ms, rethrow WebDriverPoolExhaustedException | {} | {} | {}",
                    waitedMillis, message, driverWaitReason(), describeDriverHolders()
                )
            }
            throw WebDriverPoolExhaustedException(browserId.toString(), exhaustionMessage(snapshot))
        }

        return driver
    }

    /**
     * What the caller is told when the pool cannot serve it: the pool state, why no driver came out,
     * and who is holding the drivers that exist.  A caller that only sees "exhausted" cannot tell a
     * saturated pool from a pool whose browser never launched.
     * */
    private fun exhaustionMessage(snapshot: Snapshot): String =
        "Driver pool is exhausted ($snapshot) | ${driverWaitReason()} | ${describeDriverHolders()}"

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
            poll(priority, conf, timeout.toMillis(), TimeUnit.MILLISECONDS, page.url)
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

        return poll(priority, conf, timeout.toMillis(), TimeUnit.MILLISECONDS, page.url).also { driver ->
            dispatchEvent("onBrowserLaunched") {
                event?.onBrowserLaunched?.invoke(page, driver)
                PulsarEventBus.emitBrowseEvent("onBrowserLaunched", page)
            }
        }
    }

    @Throws(BrowserLaunchException::class, InterruptedException::class)
    private fun pollWebDriver(
        priority: Int, conf: MutableConfig, timeout: Long, unit: TimeUnit, waiter: String?
    ): WebDriver? {
        _numWaitingTasks.incrementAndGet()

        val started = System.nanoTime()
        var driver: WebDriver? = null
        try {
            driver = pollDriverInSlices(priority, conf, unit.toMillis(timeout))
        } finally {
            _numWaitingTasks.decrementAndGet()
            lastActiveTime = Instant.now()
            reportDriverWait(waiter, Duration.ofNanos(System.nanoTime() - started), driver)
        }

        return driver
    }

    /**
     * Report how long a task waited for a driver, and who was holding the drivers it could not get.
     *
     * The pool is the last place that can explain a stalled fetch: a task that spends its time here
     * looks exactly like a slow page everywhere above.  There is deliberately nothing to infer any
     * more — the three questions "who is waiting", "how long", "who is holding the drivers" are
     * answered here.
     *
     * Two levels, and the difference matters:
     *
     *  * a debug line per wait over [WAIT_DEBUG_THRESHOLD] carrying the exact numbers — the wait, the
     *    driver that came out (if any), the pool state, the reason and the working drivers with the
     *    pages they are on.  This is the line to read when investigating, usually with the debug level
     *    turned on for this package only;
     *  * a warn once a wait passes [WAIT_WARN_THRESHOLD], so a starving pool is visible without debug
     *    logging.  Its message may only vary by things from a small set (the pool, the wait bucket,
     *    the reason, the browser) because [ThrottlingLogger] throttles the *rendered* message: a
     *    message carrying the waiter URL or a duration would be a new message every time and would
     *    throttle nothing.
     *
     * Runs in a `finally` block of the caller, so it must not throw: the diagnostics are wrapped.
     * */
    private fun reportDriverWait(waiter: String?, waited: Duration, driver: WebDriver?) {
        if (waited < WAIT_DEBUG_THRESHOLD) {
            return
        }

        val reason = runCatching { driverWaitReason() }.getOrElse { "the pool state is unavailable" }
        val holders = runCatching { describeDriverHolders() }.getOrElse { "the working drivers are unavailable" }

        if (waited >= WAIT_WARN_THRESHOLD) {
            throttlingLogger.warn(
                "A task waited more than {} the driver wait warning threshold ({}) | driver pool #{}: {} | {}",
                driverWaitBucket(waited, WAIT_WARN_THRESHOLD),
                WAIT_WARN_THRESHOLD.readable(),
                id,
                reason,
                browserId
            )
        }

        if (logger.isDebugEnabled) {
            logger.debug(
                "Waited {} for a web driver{} | {} | {} | waiting task: {} | {}",
                waited.readable(),
                driver?.let { " #${it.id}" } ?: " and did not get one",
                takeSnapshot().format(true),
                reason,
                waiter ?: "unknown",
                holders
            )
        }
    }

    /**
     * Why the pool could not hand out a driver, in the pool's own terms.
     *
     * The order mirrors [shouldCreateWebDriver]: a pool out of slots is saturated, and a pool with
     * slots left is being held back by the resource guard or has not created a driver yet.  The
     * numbers are the debug line's job; this is the categorical answer a log reader needs.
     * */
    private fun driverWaitReason(): String = driverWaitReason(
        isClosed = isClosed,
        isRetired = isRetired,
        numDriverSlots = numDriverSlots,
        numActive = numActive,
        isCriticalMemory = AppSystemInfo.isCriticalMemory,
        isOverCriticalLoad = AppSystemInfo.isSystemOverCriticalLoad,
    )

    /**
     * Who is holding the drivers this pool cannot hand out, and what they are fetching.
     *
     * Bounded, and never throwing: a diagnostic line has to stay one line, and an exception from a
     * driver that is being retired would replace the error the caller is about to see.
     * */
    private fun describeDriverHolders(limit: Int = 3): String {
        val working = statefulDriverPool.workingDrivers.toList()
        val labels = working.take(limit).map { driver ->
            runCatching { driverHolderLabel(driver.id, driver.readableState, driver.navigateEntry.pageUrl) }
                .getOrElse { "#${driver.id}" }
        }
        return describeDriverHolders(labels, working.size)
    }

    /**
     * Wait for a driver for at most [timeoutMillis].
     *
     * An idle driver is always taken before a new one is created: taking one off the standby queue is
     * free, while creating one launches a tab that the pool then keeps for the rest of its life, so
     * creating first spends the pool's capacity on idle tabs.  A pool that has served a few crawls
     * ends up holding dozens of standby drivers while its callers still pay for a tab launch, and the
     * browser gets slower with every tab it accumulates (measured on CI: 44 idle drivers, capacity
     * exhausted at 50 tabs, and a fetch that takes 80-100 s against 2.6 s on an idle pool).
     *
     * A driver can be created only when the resource guard in [shouldCreateWebDriver] allows it, and the
     * guard refuses while the system is over the critical load - a CPU spike caused by another browser
     * launching is enough. Trying to create a driver only once and then blocking for the whole timeout
     * turns such a transient refusal into a hard [WebDriverPoolExhaustedException] long after the load
     * has settled, so the guard is re-evaluated in each slice of the wait.
     *
     * Waiting in slices also releases the stateful driver pool from time to time, so a driver returned
     * by a task running in the same pool can be offered to this waiting thread.
     * */
    private fun pollDriverInSlices(priority: Int, conf: MutableConfig, timeoutMillis: Long): WebDriver? {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        var driver: WebDriver? = null

        while (driver == null) {
            // A standby driver costs nothing to reuse, so it is taken before anything is created.
            driver = statefulDriverPool.poll(0, TimeUnit.MILLISECONDS)
            if (driver != null) {
                break
            }

            resourceSafeCreateDriverIfNecessary(priority, conf)

            // The pool can not serve tasks anymore, e.g. it is retired or closed, do not wait for it
            if (!isActive) {
                break
            }

            val remainingNanos = deadline - System.nanoTime()
            if (remainingNanos <= 0) {
                break
            }

            val sliceMillis = TimeUnit.NANOSECONDS.toMillis(remainingNanos)
                .coerceAtMost(POLLING_SLICE.toMillis())
                .coerceAtLeast(1)
            driver = statefulDriverPool.poll(sliceMillis, TimeUnit.MILLISECONDS)
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
        } else if (isCriticalResources) {
            // The guard can refuse only transiently, the load can settle at any moment, so the waiters
            // keep polling for a driver, see pollDriverInSlices. The message must stay exactly the same
            // for the throttling to take effect, the varying details are logged at the debug level.
            throttlingLogger.info(
                "The system is over the critical load, will not create a new driver | {}",
                browserId
            )
            logger.debug(
                "The system is over the critical load, will not create a new driver | {}" +
                        " | resource consuming drivers: {}/{}/{} (slots/pool/browser)",
                browserId, numDriverSlots, resourceConsumingDriversInPool, resourceConsumingDriversInBrowser
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

/**
 * Why a task had to wait for a driver, as the pool itself sees it.
 *
 * Kept free of the pool so the wording — the part a reader has to interpret — is pinned by a unit
 * test: "driver creation is refused" and "every driver slot is taken" call for different actions
 * (wait for the load to settle versus add capacity), and confusing them is how a genuine
 * saturation gets read as a transient hiccup.
 */
internal fun driverWaitReason(
    isClosed: Boolean,
    isRetired: Boolean,
    numDriverSlots: Int,
    numActive: Int,
    isCriticalMemory: Boolean,
    isOverCriticalLoad: Boolean,
): String = when {
    isClosed -> "the pool is closed"
    isRetired -> "the pool is retired"
    numDriverSlots <= 0 -> "every driver slot is taken"
    isCriticalMemory -> "driver creation is refused: critical memory"
    isOverCriticalLoad -> "driver creation is refused: the system is over the critical load"
    numActive == 0 -> "no driver has been created yet"
    else -> "no driver became available"
}

/**
 * How far past the warning threshold a wait is, as a label from a small set.
 *
 * A ratio rather than a duration on purpose: [ThrottlingLogger] throttles the rendered message, so
 * a message carrying the exact wait would never repeat and would throttle nothing.
 */
internal fun driverWaitBucket(waited: Duration, warnThreshold: Duration): String {
    val thresholdMillis = warnThreshold.toMillis()
    if (thresholdMillis <= 0) return "1x"
    return when {
        waited.toMillis() >= thresholdMillis * 30 -> "30x"
        waited.toMillis() >= thresholdMillis * 10 -> "10x"
        waited.toMillis() >= thresholdMillis * 3 -> "3x"
        else -> "1x"
    }
}

/** One working driver, as a diagnostic names it: the tab, its state, and the page it is on. */
internal fun driverHolderLabel(id: Int, state: String, pageUrl: String?): String =
    "#$id $state" + (pageUrl?.takeIf { it.isNotBlank() }?.let { " $it" } ?: "")

/**
 * The drivers a pool could not hand out.
 *
 * [labels] is already bounded by the caller; [total] is the real number, so the line can say how
 * many were left out instead of pretending the pool holds three drivers.
 */
internal fun describeDriverHolders(labels: List<String>, total: Int): String = when {
    total <= 0 -> "no driver is working"
    else -> "working drivers: " + labels.joinToString(", ") +
        if (total > labels.size) " (+${total - labels.size} more)" else ""
}
