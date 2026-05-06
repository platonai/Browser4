package ai.platon.pulsar.protocol.browser.driver

import ai.platon.pulsar.common.*
import ai.platon.pulsar.common.concurrent.PreemptChannelSupport
import ai.platon.pulsar.common.config.AppConstants.DEFAULT_BROWSER_MAX_OPEN_TABS
import ai.platon.pulsar.common.config.CapabilityTypes.BROWSER_MAX_OPEN_TABS
import ai.platon.pulsar.common.config.ImmutableConfig
import ai.platon.pulsar.common.config.Parameterized
import ai.platon.pulsar.protocol.browser.emulator.WebDriverPoolException
import ai.platon.pulsar.protocol.browser.emulator.WebDriverPoolExhaustedException
import ai.platon.pulsar.protocol.browser.impl.BasicBrowserManager
import ai.platon.pulsar.skeleton.common.metrics.MetricsSystem
import ai.platon.pulsar.skeleton.common.persist.ext.eventHandlers
import ai.platon.pulsar.skeleton.workflow.fetch.FetchResult
import ai.platon.pulsar.skeleton.workflow.fetch.FetchTask
import ai.platon.pulsar.skeleton.workflow.fetch.driver.*
import ai.platon.pulsar.skeleton.workflow.fetch.privacy.BrowserId
import com.codahale.metrics.Gauge
import com.google.common.annotations.Beta
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentSkipListMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * The web driver pool manager.
 *
 * The web driver pool manager provides web drivers to run web page fetch tasks.
 */
open class WebDriverPoolManager constructor(
    val browserManager: BrowserManager,
    val immutableConfig: ImmutableConfig,
    val suppressMetrics: Boolean = false,
) : Parameterized, PreemptChannelSupport("WebDriverPoolManager"), AutoCloseable {
    companion object {
        val DRIVER_FAST_CLOSE_TIME_OUT = Duration.ofSeconds(10)
        val DRIVER_SAFE_CLOSE_TIME_OUT = Duration.ofSeconds(60)
    }

    private val logger = LoggerFactory.getLogger(WebDriverPoolManager::class.java)
    private val closed = AtomicBoolean()
    val isActive get() = !closed.get() && AppContext.isActive

    val isReady get() = isActive

    private val driverPoolPool = ConcurrentStatefulDriverPoolPool()

    /**
     * The max number of drivers the pool can hold
     * */
    private val poolCapacity: Int
        get() {
            val c = immutableConfig.get(BROWSER_MAX_OPEN_TABS)?.toIntOrNull() ?: DEFAULT_BROWSER_MAX_OPEN_TABS
            return c.coerceAtMost(50)
        }

    val idleTimeout = Duration.ofMinutes(18)

    val workingDriverPools get() = driverPoolPool.workingDriverPools

    val retiredDriverPools get() = driverPoolPool.retiredDriverPools

    val closedDriverPools get() = driverPoolPool.closedDriverPools

    val startTime = Instant.now()
    var lastActiveTime = startTime
    val idleTime get() = Duration.between(lastActiveTime, Instant.now())
    val isIdle get() = idleTime > idleTimeout

    val numWaitingTasks get() = workingDriverPools.values.sumOf { it.numWaiting }
    val numStandbyDrivers get() = workingDriverPools.values.sumOf { it.numStandby }
    val numWorkingDrivers get() = workingDriverPools.values.sumOf { it.numWorking }
    val numAvailableDriverSlots get() = workingDriverPools.values.sumOf { it.numDriverSlots }
    val numActiveDrivers get() = workingDriverPools.values.sumOf { it.numActive }

    val numDyingDrivers get() = retiredDriverPools.values.sumOf { it.numCreated }

    val numClosedDrivers get() = closedDriverPools.size

    /**
     * Maximum allowed number of dying drives, if it's exceeded, the oldest driver pool should be closed.
     * Dying drivers are kept for diagnosis.
     * */
    val maxAllowedDyingDrivers = 0

    val numReset by lazy { MetricsSystem.reg.meter(this, "numReset") }
    val numTimeout by lazy { MetricsSystem.reg.meter(this, "numTimeout") }
    val gauges = mapOf(
        "waitingTasks" to Gauge { numWaitingTasks },
        "standbyDrivers" to Gauge { numStandbyDrivers },
        "workingDrivers" to Gauge { numWorkingDrivers },
        "availableDriverSlots" to Gauge { numAvailableDriverSlots },
        "activeDrivers" to Gauge { numActiveDrivers },
        "dyingDrivers" to Gauge { numDyingDrivers },
        "closedDrivers" to Gauge { numClosedDrivers },
        "pTasks" to Gauge { numPreemptiveTasks.get() },
        "runningPTasks" to Gauge { numRunningPreemptiveTasks.get() },
        "pendingNTasks" to Gauge { numPendingNormalTasks.get() },
        "runningNTasks" to Gauge { numRunningNormalTasks.get() },
        "idleTime" to Gauge { idleTime.readable() }
    ).takeUnless { suppressMetrics }

    private var lastMaintainTime = Instant.now()
    private val maintainCount = AtomicInteger()
    private val minMaintainInterval = Duration.ofSeconds(15)
    private val tooFrequentMaintenance get() = DateTimes.isNotExpired(lastMaintainTime, minMaintainInterval)

    /**
     * The web driver pool closer
     * */
    private val driverPoolCloser = BrowserAccompaniedDriverPoolCloser(driverPoolPool, this)

    /**
     * The deferred tasks
     * */
    private val _deferredTasks = ConcurrentSkipListMap<Int, Deferred<FetchResult?>>()

    init {
        gauges?.let { MetricsSystem.reg.registerAll(this, it) }
    }

    /**
     * Check if the browser has possibility to serve tasks.
     *
     * 1. the browser is not in any pool
     * 2. the browser is in the working pool
     *
     * @param browserId the browser id
     * */
    fun hasPossibility(browserId: BrowserId) = isActive && driverPoolPool.hasPossibility(browserId)

    /**
     * Get the number of drivers which can serve tasks in the pool.
     * */
    fun promisedDriverCount(browserId: BrowserId) = driverPoolPool.promisedDriverCount(browserId, poolCapacity)

    /**
     * Check if the pool has at least one driver to serve.
     * */
    fun hasDriverPromise(browserId: BrowserId) = promisedDriverCount(browserId) > 0

    /**
     * Check if the pool is on full capacity.
     * */
    fun isFullCapacity(browserId: BrowserId) = driverPoolPool.isFullCapacity(browserId)

    /**
     * Subscribe a web driver in the pool specified by [browserId], the other subscriber
     * should not use the driver.
     * */
    @Beta
    fun subscribeDriver(browserId: BrowserId) = driverPoolPool.subscribeDriver(browserId)

    /**
     * Subscribe a web driver, the other subscriber should not use the driver.
     * */
    @Beta
    fun subscribeDriver() = driverPoolPool.subscribeDriver()

    /**
     * Check if a pool is retired.
     * */
    fun isRetiredPool(browserId: BrowserId) = driverPoolPool.isRetiredPool(browserId)

    /**
     * reactor: tell me if you can do this job
     * pro-actor: here is a job, tell me if you finished it
     *
     * @return The result of action, or null if timeout
     * */
    @Throws(WebDriverException::class, WebDriverPoolException::class)
    suspend fun run(
        browserId: BrowserId, task: FetchTask,
        browseFun: suspend (driver: WebDriver) -> FetchResult?,
    ) = run(WebDriverTask(browserId, task.page, task.priority, browseFun))

    /**
     * Run the task.
     * */
    @Throws(
        BrowserUnavailableException::class,
        BrowserLaunchException::class,
        WebDriverPoolException::class,
        WebDriverPoolExhaustedException::class,
        WebDriverException::class,
    )
    suspend fun run(task: WebDriverTask): FetchResult? {
        lastActiveTime = Instant.now()
        try {
            return doRun(task)
        } catch (e: InterruptedException) {
            warnInterruptible(this, e)
            return null
        } finally {
            lastActiveTime = Instant.now()
        }
    }

    /**
     * Check if a pool is retired.
     * */
    fun isActivePool(browserId: BrowserId) = driverPoolPool.isActivePool(browserId)

    /**
     * Cancel the fetch task specified by [url] remotely.
     * NOTE: A cancel request should run immediately not waiting for any browser task return.
     * */
    fun cancel(url: String): WebDriver? {
        var driver: WebDriver? = null
        workingDriverPools.values.forEach { driverPool ->
            driver = driverPool.cancel(url)
        }
        return driver
    }

    /**
     * Cancel the fetch task specified by [url] remotely.
     * NOTE: A cancel request should run immediately not waiting for any browser task return.
     * */
    fun cancel(browserId: BrowserId, url: String): WebDriver? {
        val driverPool = workingDriverPools[browserId] ?: return null
        val driver = driverPool.firstOrNull { it.navigateEntry.pageUrl == url }
        if (driver is AbstractWebDriver) {
            driver.cancel()
        }
        return driver
    }

    /**
     * Cancel all the fetch tasks, stop loading all pages.
     * */
    fun cancelAll() {
        workingDriverPools.values.forEach { it.cancelAll() }
    }

    /**
     * Cancel all the fetch tasks, stop loading all pages.
     * */
    fun cancelAll(browserId: BrowserId) {
        val driverPool = workingDriverPools[browserId] ?: return
        driverPool.cancelAll()
    }

    /**
     * Create a driver pool, but the driver pool is not added to [workingDriverPools].
     * */
    fun createUnmanagedDriverPool(browserId: BrowserId): LoadingWebDriverPool {
        return LoadingWebDriverPool(browserId, browserManager, immutableConfig)
    }

    /**
     * Find the driver pool with [browserId], cancel all running tasks in it, and close all drivers in it.
     * When we close a driver pool, all threads that are trying to get a driver should wait.
     * When the driver pool is closed, all the drivers in it will be closed, and the browser associated with the driver
     * pool will be closed as well.
     * */
    fun closeBrowserAccompaniedDriverPoolGracefully(browserId: BrowserId, timeToWait: Duration) {
        numReset.mark()

        // TODO: mark the driver pool and all the drivers as retired first and them close them
        // TODO: only lock the browser/driver pool

        // Preempt the channel to ensure consistency.
        // Preemptive tasks are low-probability events, so it's OK to use locks.
        // Waits until there are no normal tasks. If there is at least one preemptive task
        // in the critical section, all normal tasks have to wait.
        preempt {
            try {
                driverPoolCloser.closeGracefully(browserId)
            } catch (e: Exception) {
                logger.warn("Failed to close the browser | {} | {}", browserId, e.message)
                logger.error("Failed to close the browser", e)
            }
        }
    }

    /**
     * Maintain all the driver pools, check and report inconsistency, illness, idleness, etc.,
     * close bad pools if necessary.
     *
     * If "takeDriverPoolSnapshot" is in file AppPaths.PATH_LOCAL_COMMAND, perform the action.
     *
     * If the tmp dir is the default one, run the following command to take snapshot once:
     * echo takeDriverPoolSnapshot >> /tmp/pulsar/pulsar-commands
     * */
    @Throws(Exception::class)
    fun maintain(force: Boolean = false) {
        if (!force && tooFrequentMaintenance) {
            return
        }
        lastMaintainTime = Instant.now()

        if (maintainCount.getAndIncrement() == 0) {
            logger.info("Maintaining service is started, minimal maintain interval: {}", minMaintainInterval)
        }

        try {
            val allPermanent = browserManager.browsers.all { it.value.isPermanent }
            if (!allPermanent) {
                doMaintain()
            }
        } catch (e: InterruptedException) {
            logger.warn("Interrupted | {}", e.message)
            Thread.currentThread().interrupt()
        } catch (t: Throwable) {
            logger.warn(t.stringify("Failed to maintain the driver pool"))
        }

        // assign the last maintain time again
        lastMaintainTime = Instant.now()
    }

    @Throws(InterruptedException::class, Exception::class)
    private fun doMaintain() {
        // To close retired driver pools, there is no need to wait for normal tasks, so no preempting is required
        driverPoolCloser.closeOldestRetiredDriverPoolSafely()
        // Close unexpected active browsers
        driverPoolCloser.closeUnexpectedActiveBrowsers()

        /**
         * Check if there is zombie browsers who are not in active browser list nor in closed browser list,
         * if there are some of such browsers, issue warnings and destroy them.
         * */
        (browserManager as BasicBrowserManager).destroyZombieBrowsersForcibly()

        val idleDriverPoolCount = workingDriverPools.values.count { it.isIdle }
        if (idleDriverPoolCount > 0) {
            logger.warn("There are {} idle driver pools, preempt and do the maintaining", idleDriverPoolCount)

            // TODO: mark the driver pool and all the drivers as retired first and them close them
            // TODO: only lock the browser/driver pool

            // Preempt the channel to ensure consistency
            // We doubt the preemptive maintainer slows down the system if it runs too frequent
            preempt {
                driverPoolCloser.closeIdleDriverPoolsSafely()
            }
        }

        /**
         * If "takeDriverPoolSnapshot" is in file AppPaths.PATH_LOCAL_COMMAND, perform the action.
         *
         * If the tmp dir is the default one, run the following command to take snapshot once:
         * echo takeDriverPoolSnapshot >> /tmp/pulsar/pulsar-commands
         * */
        if (FileCommand.check("takeDriverPoolSnapshot")) {
            logger.info(
                """
                Driver pool manager:
                >>>>>>>>>>>>>>>>>>>>>>>>>>>>>>
                ${buildStatusString()}
                <<<<<<<<<<<<<<<<<<<<<<<<<<<<<<
            """.trimIndent()
            )
        }
    }

    /**
     * Take a snapshot about the state of the driver pools.
     * */
    fun buildStatusString(browserId: BrowserId, verbose: Boolean = false): String {
        return workingDriverPools[browserId]?.takeSnapshot()?.format(verbose) ?: ""
    }

    /**
     * Take a snapshot about the state of the driver pools.
     * */
    fun buildStatusString(verbose: Boolean = false): String {
        val sb = StringBuilder()
        if (workingDriverPools.isNotEmpty()) {
            workingDriverPools.entries.joinTo(sb, "\n") { it.value.takeSnapshot().format(verbose) + " | " + it.key }
        }
        return sb.toString()
    }

    /**
     * Close the web driver pool manager. All deferred tasks will be canceled, the pool of driver pools will be cleared
     * and the browser manager will be closed.
     *
     * It happens when the program exits.
     *
     * The closing call stack:
     *
     * PrivacyContextManager.close -> PrivacyContext.close -> WebDriverContext.close -> WebDriverPoolManager.close
     * -> BrowserManager.close -> Browser.close -> WebDriver.close
     * |-> LoadingWebDriverPool.close
     * */
    override fun close() {
        if (closed.compareAndSet(false, true)) {
            _deferredTasks.values.forEach {
                // Cancels job, including all its children with a specified diagnostic error
                it.cancel("This process is closing")
            }
            _deferredTasks.clear()

            // Actually no exception to catch
            kotlin.runCatching { driverPoolPool.close() }.onFailure { warnForClose(this, it) }
            // Actually no exception to catch
            kotlin.runCatching { browserManager.close() }.onFailure { warnForClose(this, it) }

            logger.info("Web driver pool manager is closed")
        }
    }

    /**
     * Return a string to represent the snapshot of the status.
     * */
    override fun toString(): String = buildStatusString(false)

    @Throws(
        BrowserUnavailableException::class,
        BrowserLaunchException::class,
        WebDriverPoolException::class,
        WebDriverPoolExhaustedException::class,
        WebDriverException::class,
        InterruptedException::class
    )
    private suspend fun doRun(task: WebDriverTask): FetchResult? {
        maintain()

        val result = runWithDriverPool(task)
        return result
    }

    @Throws(WebDriverException::class, WebDriverPoolException::class)
    private suspend fun runWithDriver(task: WebDriverTask, driver: WebDriver): FetchResult? {
        return runCancelableWithTimeout(task, driver)
    }

    /**
     * Waits until there are no supervisor tasks running and run the task:
     *
     * 1. get or create a driver pool for the browserId
     * 2. run the task with the driver pool
     *
     * There are two types of tasks: normal tasks and supervisor tasks.
     * Normal tasks are executed concurrently; however, they cannot be executed simultaneously with supervisor tasks.
     *
     * The [PreemptChannelSupport] mechanism is designed to handle this behavior.
     */
    @Throws(
        BrowserUnavailableException::class,
        BrowserLaunchException::class,
        WebDriverPoolException::class,
        WebDriverPoolExhaustedException::class,
        WebDriverException::class,
        InterruptedException::class
    )
    private suspend fun runWithDriverPool(task: WebDriverTask): FetchResult? {
        val browserId = task.browserId
        var result: FetchResult? = null

        // Preemptive task is a low-probability event, so it's OK to use locks in this case
        whenNormalDeferred {
            if (!isActive) {
                if (AppContext.isActive) {
                    logger.warn("[Unexpected] Web driver pool manager is inactive")
                }
                return@whenNormalDeferred null
            }

            // The browser id is specified by the caller, a typical strategy is
            // taking a browser id in a list in sequence, in which case, driver pools are return
            // one by one in sequence.
            //
            val driverPool = getOrCreateDriverPool(browserId, task.priority)

            result = runWithDriverPool(task, driverPool)
        }

        return result
    }

    @Throws(WebDriverPoolException::class)
    private fun getOrCreateDriverPool(browserId: BrowserId, priority: Int): LoadingWebDriverPool {
        if (isRetiredPool(browserId)) {
            logger.warn("Web driver pool is retired, throw WebDriverPoolException | $browserId")
            throw WebDriverPoolException(browserId.toString(), "Web driver pool is retired")
        }

        // The browser id is specified by the caller, a typical strategy is
        // taking a browser id in a list in sequence, in which case, driver pools are return
        // one by one in sequence.
        //
        val driverPool = driverPoolPool.computeIfAbsent(browserId) { createUnmanagedDriverPool(browserId) }

        if (!driverPool.isActive) {
            val message = "$driverPool | $browserId"
            logger.warn("Driver pool is inactive | {}\n{}", message, driverPool.takeSnapshot())
            throw WebDriverPoolException(browserId.toString(), "Driver pool is inactive | $message")
        }

        return driverPool
    }

    @Throws(
        BrowserUnavailableException::class,
        BrowserLaunchException::class,
        WebDriverPoolExhaustedException::class,
        WebDriverException::class,
        InterruptedException::class
    )
    private suspend fun runWithDriverPool(task: WebDriverTask, driverPool: LoadingWebDriverPool): FetchResult? {
        var driver: WebDriver? = null
        try {
            driver = driverPool.poll(
                task.priority,
                task.volatileConfig, task.page.eventHandlers?.browseEventHandlers, task.page
            )

            return runWithDriver(task, driver)
        } finally {
            driver?.let { driverPool.put(it) }
        }
    }

    private suspend fun runCancelableWithTimeout(task: WebDriverTask, driver: WebDriver): FetchResult? {
        // do not take up too much time on this driver
        require(driver is AbstractWebDriver)
        val settings = driver.browser.settings
        val fetchTaskTimeout = settings.fetchTaskTimeout

        return try {
            // The code that is executing inside the [block] is cancelled on timeout.
            withTimeout(fetchTaskTimeout.toMillis()) {
                runCancelable(task, driver)
            }
        } catch (e: TimeoutCancellationException) {
            numTimeout.mark()
            val browserId = driver.browser.id
            logger.warn(
                "Coroutine canceled({}) (by [withTimeout]) | {} | {}",
                fetchTaskTimeout.readable(), buildStatusString(browserId), browserId
            )
            null
        } finally {
            _deferredTasks.remove(task.id)
        }
    }

    /**
     * Run the task and save the execution state, so it can be canceled by [cancel] and [cancelAll].
     * */
    private suspend fun runCancelable(task: WebDriverTask, driver: WebDriver): FetchResult? {
        val deferred = supervisorScope {
            // Creates a coroutine and returns its future result
            // The running coroutine is cancelled when the resulting deferred is cancelled
            async { task.driverFun(driver) }
        }

        _deferredTasks[task.id] = deferred
        // Awaits for completion of this value without blocking a thread and resumes
        // when deferred computation is complete, returning the resulting value or throwing
        // the corresponding exception if the deferred was cancelled.
        return try {
            deferred.await()
        } catch (e: CancellationException) {
            logger.info("Coroutine cancelled, return null result | {}", e.message)
            null
        } finally {
            _deferredTasks.remove(task.id)
        }
    }
}
