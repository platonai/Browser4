package ai.platon.pulsar.skeleton.crawl.impl

import ai.platon.pulsar.common.*
import ai.platon.pulsar.common.collect.ConcurrentLoadingIterable
import ai.platon.pulsar.common.collect.DelayUrl
import ai.platon.pulsar.common.config.AppConstants.DEFAULT_BROWSER_MAX_OPEN_TABS
import ai.platon.pulsar.common.config.AppConstants.FETCH_TASK_TIMEOUT_DEFAULT
import ai.platon.pulsar.common.config.CapabilityTypes.*
import ai.platon.pulsar.common.emoji.PopularEmoji
import ai.platon.pulsar.common.measure.ByteUnit
import ai.platon.pulsar.common.proxy.*
import ai.platon.pulsar.common.urls.CallableDegenerateUrl
import ai.platon.pulsar.common.urls.DegenerateUrl
import ai.platon.pulsar.common.urls.URLUtils
import ai.platon.pulsar.common.urls.UrlAware
import ai.platon.pulsar.persist.AbstractWebPage
import ai.platon.pulsar.persist.WebDBException
import ai.platon.pulsar.persist.WebPage
import ai.platon.pulsar.skeleton.common.AppSystemInfo
import ai.platon.pulsar.skeleton.common.message.PageLoadStatusFormatter
import ai.platon.pulsar.skeleton.common.metrics.MetricsSystem
import ai.platon.pulsar.skeleton.common.options.LoadOptions
import ai.platon.pulsar.skeleton.context.PulsarContexts
import ai.platon.pulsar.skeleton.context.support.AbstractPulsarContext
import ai.platon.pulsar.skeleton.crawl.common.url.ListenableUrl
import ai.platon.pulsar.skeleton.crawl.fetch.privacy.AbstractPrivacyContext
import ai.platon.pulsar.skeleton.session.PulsarSession
import com.codahale.metrics.Gauge
import kotlinx.coroutines.*
import org.apache.commons.lang3.RandomStringUtils
import org.apache.commons.lang3.SystemUtils
import org.springframework.beans.FatalBeanException
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentSkipListSet
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.random.Random
import kotlin.reflect.full.memberProperties
import kotlin.reflect.jvm.isAccessible

private class StreamingCrawlerMetrics {
    private val registry = MetricsSystem.defaultMetricRegistry

    val retries = registry.multiMetric(this, "retries")
    val cancels = registry.multiMetric(this, "cancels")
    val gone = registry.multiMetric(this, "gone")
    val tasks = registry.multiMetric(this, "tasks")
    val successes = registry.multiMetric(this, "successes")
    val finishes = registry.multiMetric(this, "finishes")

    val fetchSuccesses = registry.multiMetric(this, "fetchSuccesses")

    val drops = registry.meter(this, "drops")
    val timeouts = registry.meter(this, "timeouts")
}

private enum class CriticalWarning(val message: String) {
    HIGH_CPU_LOAD("HIGH CPU LOAD"),
    OUT_OF_MEMORY("OUT OF MEMORY"),
    OUT_OF_DISK_STORAGE("OUT OF DISK STORAGE"),
    WEB_DB_LOST("WEB DB LOST"),
    NO_PROXY("NO PROXY AVAILABLE"),
    FAST_CONTEXT_LEAK("CONTEXT LEAK TOO FAST"),
    FAST_CANCELS("CANCELS TOO FAST"),
    WRONG_DISTRICT("WRONG DISTRICT! ALL RESIDENT TASKS ARE PAUSED"),
    WRONG_PROFILE("WRONG PROFILE! ALL RESIDENT TASKS ARE PAUSED"),
}

private class GlobalCrawlState {
    val globalRunningInstances = AtomicInteger()
    val globalRunningTasks = AtomicInteger()
    val globalKilledTasks = AtomicInteger()
    val globalTasks = AtomicInteger()
    var globalWebDBFailures = AtomicInteger()

    val globalMetrics = StreamingCrawlerMetrics()

    val globalLoadingUrls = ConcurrentSkipListSet<String>()

    var contextLeakWaitingTime = Duration.ZERO
    var proxyVendorWaitingTime = Duration.ZERO
    var criticalWarning: CriticalWarning? = null
    var lastUrl = ""
    var lastHtmlIntegrity = ""
    var lastFetchError = ""
    val lastCancelReason = Frequency<String>()
    val illegalApplicationState = AtomicBoolean()

    var wrongProfile = MetricsSystem.reg.multiMetric(this, "WRONG_PROFILE_COUNT")

    val readableCriticalWarning: String
        get() = criticalWarning?.message?.let { "!!! WARNING !!! $it !!! ${Instant.now()}" } ?: ""
}

open class StreamingTaskRunner(
    /**
     * The url sequence
     * */
    var urls: Sequence<UrlAware>,
    /**
     * The default pulsar session to use
     * */
    session: PulsarSession = PulsarContexts.getOrCreateSession(),
    /**
     * Auto close or not
     * */
    autoClose: Boolean = true,
) : AbstractTaskRunner(session, autoClose) {
    companion object {
        private var globalState = GlobalCrawlState()

        init {
            mapOf(
                "illegalApplicationState" to Gauge { globalState.illegalApplicationState.get() },

                "globalState.globalRunningInstances" to Gauge { globalState.globalRunningInstances.get() },
                "globalState.globalRunningTasks" to Gauge { globalState.globalRunningTasks.get() },
                "globalState.globalKilledTasks" to Gauge { globalState.globalKilledTasks.get() },
                "globalState.globalWebDBFailures" to Gauge { globalState.globalWebDBFailures.get() },

                "contextLeakWaitingTime" to Gauge { globalState.contextLeakWaitingTime },
                "proxyVendorWaitingTime" to Gauge { globalState.proxyVendorWaitingTime },
                "000WARNING" to Gauge { globalState.readableCriticalWarning },
                "lastCancelReason" to Gauge { globalState.lastCancelReason.toString() },

                "lastUrl" to Gauge { globalState.lastUrl },
                "lastHtmlIntegrity" to Gauge { globalState.lastHtmlIntegrity },
                "lastFetchError" to Gauge { globalState.lastFetchError },
            ).let { MetricsSystem.reg.registerAll(this, it) }
        }

        /**
         * Reset the global state to the initial state. The global state is shared by all [StreamingTaskRunner]s.
         * */
        fun resetGlobalState() {
            globalState = GlobalCrawlState()
        }

        /**
         * Clears all the illegal states shared by all [StreamingTaskRunner]s.
         * Only when there is no illegal states set, the newly created crawler can work properly.
         * Other object scope data will keep unchanged, so we can still know what happened.
         * */
        fun clearIllegalState() {
            globalState.illegalApplicationState.set(false)
        }
    }

    private val logger = getLogger(StreamingTaskRunner::class)
    private val tracer get() = logger.takeIf { it.isTraceEnabled }
    private val taskLogger = getLogger(StreamingTaskRunner::class, ".Task")
    private val sessionConfig = session.sessionConfig
    private val context = session.context as AbstractPulsarContext
    private val globalCache get() = session.globalCache
    private val globalCacheOrNull get() = if (isActive) session.globalCache else null
    private val isProxyEnabled get() = ProxyPoolManager.isProxyEnabled(sessionConfig)
    private val proxyPool: ProxyPool? get() = if (isProxyEnabled) context.getBeanOrNull(ProxyPool::class) else null
    private var proxyOutOfService = 0

    /**
     * Override the main loop concurrency.
     *
     * When we execute non-browser tasks in the main loop, we might need higher
     * concurrency level than fetch concurrency.
     *
     * 0 means follow the fetch concurrency.
     * */
    private val concurrencyOverride get() = sessionConfig.getInt(MAIN_LOOP_CONCURRENCY_OVERRIDE, 0)

    private val flowState = AtomicReference(FlowState.CONTINUE)

    private var lastActiveTime = Instant.now()

    private val lock = ReentrantLock()
    private val notBusy = lock.newCondition()

    private val gauges = mapOf(
        "idleTime" to Gauge { idleTime.readable() },
        "numBrowserContexts" to Gauge { numBrowserContexts },
        "numMaxActiveTabs" to Gauge { numMaxOpenTabs },
        "fetchConcurrency" to Gauge { fetchConcurrency },
        "concurrency" to Gauge { concurrency },
    )

    private var forceQuit = false

    /**
     * The maximum number of privacy contexts allowed.
     * */
    val numBrowserContexts: Int get() {
        // return sessionConfig.getInt(PRIVACY_CONTEXT_NUMBER, 2)

        // PRIVACY_CONTEXT_NUMBER is deprecated, use BROWSER_CONTEXT_NUMBER instead
//        val fallbackValue = sessionConfig.getInt(PRIVACY_CONTEXT_NUMBER, 2)
//        val browserContextNumber = sessionConfig.getInt(BROWSER_CONTEXT_NUMBER, fallbackValue)
        // return browserContextNumber

        return sessionConfig.getWithFallback(BROWSER_CONTEXT_NUMBER, PRIVACY_CONTEXT_NUMBER)?.toIntOrNull() ?: 2
    }

    @Deprecated("Use numBrowserContexts instead", replaceWith = ReplaceWith("numBrowserContexts"))
    val numPrivacyContexts get() = numBrowserContexts

    /**
     * The maximum number of open tabs allowed in each open browser.
     * */
    val numMaxOpenTabs: Int get() {
        val c = sessionConfig.get(BROWSER_MAX_OPEN_TABS)?.toIntOrNull() ?: DEFAULT_BROWSER_MAX_OPEN_TABS
        return c.coerceAtMost(50)
    }

    /**
     * The fetch concurrency equals to the number of all allowed open tabs.
     * */
    val fetchConcurrency get() = numBrowserContexts * numMaxOpenTabs

    /**
     * The main loop concurrency.
     * */
    val concurrency get() = if (concurrencyOverride > 0) concurrencyOverride else fetchConcurrency

    /**
     * The out of work timeout.
     * */
    val outOfWorkTimeout = Duration.ofMinutes(10)

    /**
     * The timeout for each fetch task.
     * */
    val fetchTaskTimeout get() = sessionConfig.getDuration(FETCH_TASK_TIMEOUT, FETCH_TASK_TIMEOUT_DEFAULT)

    /**
     * The idle time during which there is no fetch tasks.
     * */
    val idleTime get() = Duration.between(lastActiveTime, Instant.now())

    /**
     * Check if the crawler is out of work (idle for long time).
     * */
    val isOutOfWork get() = idleTime > outOfWorkTimeout

    /**
     * Check if smart retry is enabled.
     *
     * If smart retry is enabled, tasks will be retried if it's canceled or marked as retry.
     * A continuous crawl system should enable smart retry, while a simple demo can disable it.
     * */
    val isSmartRetryEnabled get() = sessionConfig.getBoolean(CRAWL_SMART_RETRY, true)

    /**
     * Check if the crawler is idle.
     * An idle crawler means:
     * 1. no loading urls
     * 2. no urls in the loading queue
     * */
    val isIdle: Boolean
        get() {
            return !urls.iterator().hasNext() && globalState.globalLoadingUrls.isEmpty()
                    && idleTime > Duration.ofSeconds(10)
        }

    /**
     * Check if the crawler is active.
     * */
    override val isActive: Boolean
        get() {
            return super.isActive
                    && AppContext.isActive
                    && session.context.isActive
                    && !forceQuit
                    && !globalState.illegalApplicationState.get()
        }

    /**
     * The job name.
     * */
    var jobName: String = "crawler-" + RandomStringUtils.secure().nextAlphanumeric(5)

    init {
        MetricsSystem.reg.registerAll(this, "$id.g", gauges)

        val cacheGauges = mapOf(
            "paused" to Gauge { isPaused },
            "pageCacheSize" to Gauge { globalCacheOrNull?.pageCache?.size ?: 0 },
            "documentCacheSize" to Gauge { globalCacheOrNull?.documentCache?.size ?: 0 }
        )
        MetricsSystem.reg.registerAll(this, "$id.g", cacheGauges)

        generateFinishCommand()
    }

    /**
     * Run the crawler.
     * */
    open fun run() {
        runBlocking {
            supervisorScope {
                run(this)
            }
        }
    }

    /**
     * Run the crawler in the given coroutine scope.
     * */
    open suspend fun run(scope: CoroutineScope) {
        startCrawlLoop(scope)
    }

    override fun report() {
        val sb = StringBuilder()

        StreamingTaskRunner::class.memberProperties.filter { it.isAccessible }.forEach {
            sb.append(it.name).append(": ").append(it.get(this)).append("\n")
        }

        logger.info(sb.toString())
    }

    /**
     * Wait until all tasks are done.
     * */
    @Throws(InterruptedException::class)
    override fun await() {
        lock.withLock { notBusy.await() }
    }

    /**
     * Quit the crawl loop.
     * */
    fun quit() {
        forceQuit = true
    }

    /**
     * Quit and close the crawl loop.
     * */
    override fun close() {
        quit()
        super.close()
    }

    protected suspend fun startCrawlLoop(scope: CoroutineScope) {
        logger.info("Starting crawler | {} | #{} | {} ...", name, id, session::class.java)

        val startTime = Instant.now()

        globalState.globalRunningInstances.incrementAndGet()
        runCrawlLoopWhileActive(scope)
        globalState.globalRunningInstances.decrementAndGet()

        logger.info(
            "All done. Total {} tasks are processed in session {} in {}",
            globalState.globalMetrics.tasks.counter.count, session,
            DateTimes.elapsedTime(startTime).readable()
        )
    }

    private suspend fun runCrawlLoopWhileActive(scope: CoroutineScope) {
        var idleSeconds = 0
        while (isActive) {
            checkEmptyUrlSequence(++idleSeconds)

            urls.forEachIndexed { j, url ->
                idleSeconds = 0
                globalState.globalTasks.incrementAndGet()

                if (!isActive) {
                    globalState.globalMetrics.drops.mark()
                    return@runCrawlLoopWhileActive
                }

                tracer?.trace(
                    "{}. {}/{} running tasks, processing {}",
                    globalState.globalTasks,
                    globalState.globalLoadingUrls.size,
                    globalState.globalRunningTasks,
                    url.configuredUrl
                )

                // The largest disk must have at least 10 GiB remaining space
                val freeSpace =
                    Runtimes.unallocatedDiskSpaces().maxOfOrNull { ByteUnit.BYTE.toGB(it) } ?: 0.0
                if (freeSpace < 10.0) {
                    val diskSpaces = Runtimes.unallocatedDiskSpaces().joinToString { ByteUnit.BYTE.toGB(it).toString() }
                    logger.error("Disk space is full! | {}", diskSpaces)
                    globalState.criticalWarning = CriticalWarning.OUT_OF_DISK_STORAGE
                    return@runCrawlLoopWhileActive
                }

                if (url.isNil) {
                    globalState.globalMetrics.drops.mark()
                    return@forEachIndexed
                }

                // disabled, might be slow
                val urlSpec = URLUtils.splitUrlArgs(url.url).first
                if (alwaysFalse() && doLaterIfProcessing(urlSpec, url, Duration.ofSeconds(10))) {
                    return@forEachIndexed
                }

                globalState.globalLoadingUrls.add(urlSpec)
                val state = runWithStatusCheck(1 + j, url, scope)

                if (state != FlowState.CONTINUE) {
                    return@runCrawlLoopWhileActive
                } else {
                    // if urls is ConcurrentLoadingIterable
                    // TODO: the line below can be removed
                    (urls.iterator() as? ConcurrentLoadingIterable.LoadingIterator)?.tryLoad()
                }
            }
        }
    }

    private suspend fun checkEmptyUrlSequence(idleSeconds: Int) {
        if (urls.iterator().hasNext()) {
            return
        }

        val reportPeriod = when {
            idleSeconds < 1000 -> 120
            idleSeconds < 10000 -> 300
            else -> 6000
        }

        if (idleSeconds % reportPeriod == 0) {
            logger.debug("The url sequence is empty. {} {}", globalState.globalLoadingUrls.size, idleTime)
        }

        delay(1_000)

        if (isIdle) {
            lock.withLock { notBusy.signalAll() }
        }
    }

    private suspend fun runWithStatusCheck(j: Int, url: UrlAware, scope: CoroutineScope): FlowState {
        lastActiveTime = Instant.now()
        var k = 0

        while (isActive && isPaused) {
            if (k++ % 20 == 0) {
                logger.info("The crawl loop is paused, use resume() to resume the crawl loop")
            }
            delay(1000)
        }
        k = 0 // reset k explicitly

        delayIfEstimatedNoLoadResource(j)

        while (isActive && AppSystemInfo.isCriticalCPULoad) {
            globalState.criticalWarning = CriticalWarning.HIGH_CPU_LOAD
            // CPU load changes very fast, it drops immediately when a web driver becomes free,
            // so we delay for short and random time.
            randomDelay(200, 300)
        }

        /**
         * If all memory is used up, we can do nothing but wait.
         * */
        k = 0
        while (isActive && AppSystemInfo.isCriticalMemory) {
            if (k++ % 20 == 0) {
                // k is the number of consecutive warnings, the sequence of k is: 1, 21, 41, 61, ...
                handleMemoryShortage(k)
            }
            globalState.criticalWarning = CriticalWarning.OUT_OF_MEMORY
            randomDelay(500, 500)
        }
        k = 0 // reset k explicitly

        /**
         * If the privacy context leaks too fast, there is a good chance that there is a bug,
         * or the quality of this batch of proxy IPs is poor.
         * */
        val contextLeaksRate = AbstractPrivacyContext.globalMetrics.contextLeaks.meter.fifteenMinuteRate
        if (isActive && contextLeaksRate >= 5 / 60f) {
            globalState.criticalWarning = CriticalWarning.FAST_CONTEXT_LEAK
            handleContextLeaks()
        }

        if (isActive && globalState.wrongProfile.hourlyCounter.count > 60) {
            handleWrongProfile()
        }

        if (isActive && proxyOutOfService > 0) {
            globalState.criticalWarning = CriticalWarning.NO_PROXY
            handleProxyOutOfService()
        }

        if (isActive && globalState.globalWebDBFailures.get() > 0) {
            globalState.criticalWarning = CriticalWarning.WEB_DB_LOST
            handleWebDBLost()
        }

        if (isActive && FileCommand.check("finish-job")) {
            logger.info("Find finish-job command, quit streaming crawler ...")
            flowState.set(FlowState.BREAK)
            return flowState.get()
        }

        if (!isActive) {
            flowState.set(FlowState.BREAK)
            return flowState.get()
        }

        delayIfEstimatedNoLoadResource(j)

        globalState.criticalWarning = null

        val context = Dispatchers.Default + CoroutineName("w")
        val urlSpec = URLUtils.splitUrlArgs(url.url).first
        // We must increase the number before the task is actually launched in a coroutine,
        // otherwise, it's easy to grow larger than fetchConcurrency.
        globalState.globalRunningTasks.incrementAndGet()
        scope.launch(context) {
            try {
                globalState.globalMetrics.tasks.mark()
                runTaskWithEventHandlers(url)
            } finally {
                lastActiveTime = Instant.now()

                globalState.globalLoadingUrls.remove(urlSpec)
                globalState.globalRunningTasks.decrementAndGet()

                globalState.globalMetrics.finishes.mark()
            }
        }

        return flowState.get()
    }

    /**
     * Delay if there is no resource to load a new task.
     * Running task has to be no more than the available web drivers.
     * */
    private suspend fun delayIfEstimatedNoLoadResource(j: Int, maxTry: Int = 1000) {
        var k = 0
        while (isActive && ++k < maxTry && globalState.globalRunningTasks.get() >= concurrency) {
            if (j % 120 == 0) {
                logger.info(
                    "$j. Long time to run $globalState.globalRunningTasks tasks | $lastActiveTime -> {}",
                    idleTime.readable()
                )
            }
            val timeMillis = 500L + Random.nextInt(500)
            delay(timeMillis)
        }
    }

    private suspend fun runTaskWithEventHandlers(url: UrlAware) {
        when {
            !isActive -> {
                return
            }

            url is DegenerateUrl -> {
                runDegenerateUrlTask(url)
            }

            else -> {
                loadWithEventHandlers(url)
            }
        }
    }

    private fun runDegenerateUrlTask(url: DegenerateUrl) {
        when (url) {
            is CallableDegenerateUrl -> {
                // The url is degenerated, which means it's not a resource on the Internet but a normal executable task.
                url.invoke()
            }

            is ListenableUrl -> {
                emit(CrawlEvents.willLoad, url)
                emit(CrawlEvents.load, url)
                emit(CrawlEvents.loaded, url, null)
            }
        }
    }

    private suspend fun loadWithEventHandlers(url: UrlAware) {
        emit(CrawlEvents.willLoad, url)

        val page = loadWithTimeout(url)

        // A continuous crawl system should enable smart retry, while a simple demo can disable it
        if (isSmartRetryEnabled) {
            handleRetry(url, page)
        }

        if (page != null) {
            collectStatAfterLoad(page)
        }

        emit(CrawlEvents.loaded, url, page)
    }

    private suspend fun loadWithTimeout(url: UrlAware): WebPage? {
        var page: WebPage? = null
        val timeout = fetchTaskTimeout.plusSeconds(30).toMillis()
        try {
            page = withTimeout(timeout) {
                loadWithMinorExceptionHandled(url)
            }
        } catch (e: TimeoutCancellationException) {
            globalState.globalMetrics.timeouts.mark()
            logger.info(
                "{}. Task timeout ({}) to load page, thrown by [withTimeout] | {}",
                globalState.globalMetrics.timeouts.count, timeout, url
            )
        } catch (e: CancellationException) {
            if (globalState.illegalApplicationState.compareAndSet(false, true)) {
                if (AppContext.isActive) {
                    logger.warn(
                        "Coroutine was cancelled, set flow state to be BREAK" +
                                " and quit the crawler's loop | JobCancellationException", e
                    )
                }
            }
            flowState.set(FlowState.BREAK)
        } catch (e: Throwable) {
            when {
                // The following exceptions can be caught as a Throwable but not the concrete exception,
                // one of the reason is the concrete exception is not public.
                e.javaClass.name == "kotlinx.coroutines.JobCancellationException" -> {
                    if (globalState.illegalApplicationState.compareAndSet(false, true)) {
                        if (AppContext.isActive) {
                            logger.warn(
                                "Coroutine was cancelled, set flow state to be BREAK" +
                                        " and quit the crawler's loop | JobCancellationException", e
                            )
                        }
                    }
                    flowState.set(FlowState.BREAK)
                }

                e.javaClass.name.contains("DriverLaunchException") -> {
                    logger.warn("[Unexpected] DriverLaunchException | {}", e.message)
                }

                else -> {
                    logger.warn("[Unexpected] Exception details: >>>>", e)
                }
            }
        }

        return page
    }

    private fun collectStatAfterLoad(page: WebPage) {
        if (page.isCanceled) {
            return
        }

        globalState.lastFetchError = page.protocolStatus.takeIf { !it.isSuccess }?.toString() ?: ""
        if (!page.protocolStatus.isSuccess) {
            return
        }

        globalState.lastUrl = page.configuredUrl
        globalState.lastHtmlIntegrity = page.htmlIntegrity.toString()


        if (page.htmlIntegrity.isWrongProfile) {
            globalState.wrongProfile.mark()
        } else {
            globalState.wrongProfile.reset()
        }

        if (page.isFetched) {
            globalState.globalMetrics.fetchSuccesses.mark()
        }

        globalState.globalMetrics.successes.mark()
    }

    /**
     * Find a proper strategy to retry the task.
     * */
    private suspend fun handleRetry(url: UrlAware, page: WebPage?) {
        when {
            !isActive -> return
            page == null -> handleRetry0(url, null)
            page.isCanceled -> handleCanceled(url, page)
            page.protocolStatus.isRetry -> handleRetry0(url, page)
        }
    }

    @Throws(Exception::class)
    private suspend fun loadWithMinorExceptionHandled(url: UrlAware): WebPage? {
        val options = session.options(url.args ?: "")
        if (options.isDead()) {
            // The url is dead, drop the task
            globalState.globalKilledTasks.incrementAndGet()
            return null
        }

        tracer?.trace(
            "{}. {}/{} global running tasks, loading with session.loadDeferred | {}",
            globalState.globalTasks,
            globalState.globalLoadingUrls.size,
            globalState.globalRunningTasks,
            url.configuredUrl
        )

        return kotlin.runCatching { session.loadDeferred(url, options) }
            .onSuccess { flowState.set(handleLoadSuccess(url, it)) }
            .onFailure { flowState.set(handleLoadException(url, it)) }
            .getOrNull()
    }

    @Throws(Exception::class)
    private fun handleLoadSuccess(url: UrlAware, page: WebPage): FlowState {
//        if (globalState.globalWebDBFailures.get() > 0 && globalState.globalWebDBFailures.decrementAndGet() < 0) {
//            globalState.globalWebDBFailures.set(0)
//        }

        return when (val state = flowState.get()) {
            FlowState.BREAK -> FlowState.BREAK
            else -> state
        }
    }

    @Throws(Exception::class)
    private fun handleLoadException(url: UrlAware, e: Throwable): FlowState {
        val state = flowState
        if (state.get() == FlowState.BREAK) {
            return state.get()
        }

        if (!isActive) {
            logger.debug("Process is closing")
            return FlowState.BREAK
        }

        when (e) {
            is InterruptedException -> {
                Thread.currentThread().interrupt()
                logger.warn("Thread was interrupted, quit ...", e)
                return FlowState.BREAK
            }

            is IllegalApplicationStateException -> {
                if (globalState.illegalApplicationState.compareAndSet(false, true)) {
                    logger.warn("\n!!!Illegal application context, quit ... | {}", e.message)
                }
                return FlowState.BREAK
            }

            is FatalBeanException -> {
                if (state.compareAndSet(FlowState.CONTINUE, FlowState.BREAK)) {
                    logger.warn("Fatal bean exception, quit...", e)
                } else {
                    logger.warn("Fatal bean exception, quit... | {}", e.message)
                }
                return state.get()
            }

            is ProxyInsufficientBalanceException -> {
                proxyOutOfService++
                logger.warn("ProxyInsufficientBalance, {}. {}", proxyOutOfService, e.message)
            }

            is ProxyVendorUntrustedException -> {
                logger.warn("Proxy is untrusted | {}", e.message)
                return FlowState.BREAK
            }

            is ProxyException -> {
                logger.warn("[Unexpected] proxy exception | {}", e.brief())
            }

            is WebDBException -> {
                globalState.globalWebDBFailures.incrementAndGet()
                // logger.warn("Web DB failure | {} | the Web DB layer should have reported the detail", continousWebDBFailureCount)
            }

            is TimeoutCancellationException -> {
                logger.warn(
                    "[Timeout] Coroutine was cancelled, thrown by [withTimeout]. {} | {}",
                    e.brief(), url
                )
            }

            is CancellationException -> {
                // Has to come after TimeoutCancellationException
                if (globalState.illegalApplicationState.compareAndSet(false, true)) {
                    logger.warn("Streaming crawler job was canceled, quit ...", e)
                }
                return FlowState.BREAK
            }

            is IllegalStateException -> {
                logger.warn("Illegal state", e)
                return state.get()
                // return FlowState.BREAK
            }

            else -> throw e
        }

        return state.get()
    }

    private fun doLaterIfProcessing(urlSpec: String, url: UrlAware, delay: Duration): Boolean {
        if (urlSpec in globalState.globalLoadingUrls || urlSpec in globalCache.fetchingCache) {
            // process later, hope the page is fetched
            logger.debug("Task is in process, do it {} later | {}", delay.readable(), url.configuredUrl)
            fetchDelayed(url, delay)
            return true
        }

        return false
    }

    private suspend fun handleCanceled(url: UrlAware, page: WebPage?) {
        globalState.globalMetrics.cancels.mark()
        val delay = page?.retryDelay?.takeIf { !it.isZero } ?: Duration.ofSeconds(10)
        // Delay fetching the page.
        fetchDelayed(url, delay)

        // Collect all cancel reasons
        if (page is AbstractWebPage) {
            // page is not updated using page datum if the page is canceled, so use pageDatum
            val reason = page.pageDatum?.protocolStatus?.reason ?: "unknown"
            globalState.lastCancelReason.add(reason.toString())
        }

        // Set a guard to prevent too many cancels.
        // If there are too many cancels, the loop should have a rest.
        //
        // rate_unit=events/second
        val oneMinuteRate = globalState.globalMetrics.cancels.meter.oneMinuteRate
        if (isActive && oneMinuteRate >= 1.0) {
            globalState.criticalWarning = CriticalWarning.FAST_CANCELS
            delay(1_000)
        }
    }

    private fun handleRetry0(url: UrlAware, page: WebPage?) {
        val nextRetryNumber = 1 + (page?.fetchRetries ?: 0)
        if (page != null && nextRetryNumber > page.maxRetries) {
            // should not go here, because the page should be marked as GONE
            globalState.globalMetrics.gone.mark()
            if (taskLogger.isInfoEnabled) {
                val message = PageLoadStatusFormatter(page, prefix = "Gone (unexpected)")
                taskLogger.info("{}, gone unexpected, no retry", message)
            }
            return
        }

        val delay = page?.retryDelay?.takeIf { !it.isZero } ?: retryDelayPolicy(nextRetryNumber, url)
//        val delayCache = globalState.globalCache.urlPool.delayCache
//        // erase -refresh options
//        url.args = url.args?.replace("-refresh", "-refresh-erased")
//        delayCache.add(DelayUrl(url, delay))
        fetchDelayed(url, delay)

        globalState.globalMetrics.retries.mark()
        if (page != null && taskLogger.isInfoEnabled) {
            val symbol = PopularEmoji.FENCER
            val prefix = "$symbol Trying ${nextRetryNumber}th ${delay.readable()} later | "
            taskLogger.info("{}, retrying", PageLoadStatusFormatter(page, prefix = prefix))
        }
    }

    private fun fetchDelayed(url: UrlAware, delay: Duration) {
        val delayCache = globalCache.urlPool.delayCache
        // erase -refresh options
//        url.args = url.args?.replace("-refresh", "-refresh-erased")
        url.args = url.args?.let { LoadOptions.eraseOptions(it, "refresh") }
        require(url.args?.contains("refresh") != true)

        delayCache.add(DelayUrl(url, delay))
    }

    private fun handleMemoryShortage(consecutiveWarningCount: Int) {
        logger.info(
            "{}. runningTasks: {}, availableMemory: {}, memoryToReserve: {}, shortage: {}",
            consecutiveWarningCount,
            globalState.globalRunningTasks, AppSystemInfo.formatAvailableMemory(),
            AppSystemInfo.formatMemoryToReserve(), AppSystemInfo.formatMemoryShortage()
        )
        session.globalCache.clearPDCaches()

        // When control returns from the method call, the Java Virtual Machine
        // has made the best effort to reclaim space from all unused objects.
        System.gc()
    }

    /**
     * Proxies should live for more than 5 minutes. If proxy is not enabled, the rate is always 0.
     *
     * 5 / 60f ~= 0.083
     * */
    private suspend fun handleContextLeaks() {
        val contextLeaks = AbstractPrivacyContext.globalMetrics.contextLeaks
        val contextLeaksRate = contextLeaks.meter.fifteenMinuteRate
        var k = 0
        val threshold = 5 / 60f // 5 / 60f ~= 0.083
        while (isActive && contextLeaksRate >= threshold && ++k < 600) {
            logger.takeIf { k % 60 == 0 }?.warn(
                "Context leaks too fast: {} leaks/seconds, available memory: {}",
                contextLeaksRate, AppSystemInfo.formatAvailableMemory()
            )
            delay(1000)

            contextLeaks.update()

            globalState.contextLeakWaitingTime += Duration.ofSeconds(1)
        }

        globalState.contextLeakWaitingTime = Duration.ZERO
    }

    private suspend fun handleProxyOutOfService() {
        while (isActive && proxyOutOfService > 0) {
            delay(1000)
            globalState.proxyVendorWaitingTime += Duration.ofSeconds(1)
            handleProxyOutOfService0()
        }

        globalState.proxyVendorWaitingTime = Duration.ZERO
    }

    private fun handleProxyOutOfService0() {
        if (++proxyOutOfService % 180 == 0) {
            logger.warn("Proxy out of service, check it again ...")
            val p = proxyPool
            if (p != null) {
                p.runCatching { take() }.onFailure {
                    if (it !is ProxyInsufficientBalanceException) {
                        proxyOutOfService = 0
                    } else {
                        warnInterruptible(this, it, "Proxy account insufficient balance")
                    }
                }.onSuccess { proxyOutOfService = 0 }
            } else {
                proxyOutOfService = 0
            }
        }
    }

    private suspend fun handleWebDBLost() {
        val startTime = Instant.now()
        var lastReportTime = Instant.EPOCH
        var canConnect = false
        while (isActive && !canConnect) {
            canConnect = context.webDb.canConnect()
            if (!canConnect) {
                val elapsedTime = DateTimes.elapsedTime(startTime)
                val elapsedSeconds = elapsedTime.seconds
                val delaySeconds = elapsedSeconds.coerceAtLeast(5).coerceAtMost(30)
                val delayInterval = Duration.ofSeconds(delaySeconds)

                val reportInterval = when {
                    elapsedSeconds < 30 -> Duration.ofSeconds(30)
                    elapsedSeconds < 600 -> Duration.ofMinutes(1)
                    else -> Duration.ofMinutes(2)
                }
                if (DateTimes.elapsedTime(lastReportTime) > reportInterval) {
                    logger.warn("Web DB lost for {}", elapsedTime.readable())
                    lastReportTime = Instant.now()
                }

                delay(delayInterval.toMillis())
            }
        }

        if (canConnect) {
            globalState.globalWebDBFailures.set(0)
        }
    }

    private suspend fun handleWrongProfile() {
        var k = 0
        while (globalState.wrongProfile.hourlyCounter.count > 60) {
            globalState.criticalWarning = CriticalWarning.WRONG_PROFILE
            logger.takeIf { k++ % 20 == 0 }?.warn("{}, wrong profile", globalState.criticalWarning?.message ?: "")
            delay(1000)
        }
    }

    private suspend fun randomDelay(baseMills: Int, randomDelta: Int) =
        delay(baseMills.toLong() + Random.nextInt(randomDelta))

    private fun generateFinishCommand() {
        if (SystemUtils.IS_OS_UNIX) {
            generateFinishCommandUnix()
        }
    }

    private fun generateFinishCommandUnix() {
        val finishScriptPath = AppPaths.SCRIPT_DIR.resolve("finish-crawler.sh")
        val cmd = "#bin\necho finish-job $jobName >> " + AppPaths.PATH_LOCAL_COMMAND
        try {
            Files.write(finishScriptPath, cmd.toByteArray(), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
            Files.setPosixFilePermissions(finishScriptPath, PosixFilePermissions.fromString("rwxrw-r--"))
        } catch (e: IOException) {
            logger.error(e.toString())
        }
    }
}
