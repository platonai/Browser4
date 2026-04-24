/**
 * Copyright (c) Vincent Zhang, ivincent.zhang@gmail.com, Platon.AI.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.platon.pulsar.protocol.browser.emulator.context

import ai.platon.pulsar.common.*
import ai.platon.pulsar.common.browser.fingerprint.Fingerprint
import ai.platon.pulsar.common.config.CapabilityTypes.BROWSER_CONTEXT_NUMBER
import ai.platon.pulsar.common.config.CapabilityTypes.PRIVACY_CONTEXT_NUMBER
import ai.platon.pulsar.common.config.ImmutableConfig
import ai.platon.pulsar.common.emoji.PopularEmoji
import ai.platon.pulsar.common.logging.ThrottlingLogger
import ai.platon.pulsar.common.proxy.ProxyPoolManager
import ai.platon.pulsar.common.proxy.ProxyVendorException
import ai.platon.pulsar.persist.AbstractWebPage
import ai.platon.pulsar.persist.RetryScope
import ai.platon.pulsar.persist.WebPage
import ai.platon.pulsar.persist.model.GoraWebPage
import ai.platon.browser4.protocol.browser.DefaultWebDriverPoolManager
import ai.platon.pulsar.protocol.browser.driver.WebDriverPoolManager
import ai.platon.pulsar.skeleton.common.AppSystemInfo
import ai.platon.pulsar.skeleton.common.metrics.MetricsSystem
import ai.platon.pulsar.skeleton.CoreMetrics
import ai.platon.pulsar.skeleton.workflow.fetch.FetchResult
import ai.platon.pulsar.skeleton.workflow.fetch.FetchTask
import ai.platon.pulsar.skeleton.workflow.fetch.driver.WebDriver
import ai.platon.pulsar.skeleton.workflow.fetch.privacy.AbstractPrivacyContext
import ai.platon.pulsar.skeleton.workflow.fetch.privacy.BrowserProfile
import ai.platon.pulsar.skeleton.workflow.fetch.privacy.PrivacyContext
import ai.platon.pulsar.skeleton.workflow.fetch.privacy.PrivacyException
import com.google.common.collect.Iterables
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.util.concurrent.atomic.AtomicInteger

open class MultiPrivacyContextManager(
    driverPoolManager: WebDriverPoolManager,
    proxyPoolManager: ProxyPoolManager? = null,
    val coreMetrics: CoreMetrics? = null,
    conf: ImmutableConfig
) : AbstractBrowserPrivacyManager(driverPoolManager, proxyPoolManager, conf) {
    class Metrics {
        private val registry = MetricsSystem.reg

        val tasks = registry.multiMetric(this, "tasks")
        val successes = registry.multiMetric(this, "successes")
        val finishes = registry.multiMetric(this, "finishes")

        val illegalDrivers = registry.meter(this, "illegalDrivers")
    }

    companion object {
        val VAR_CONTEXT_INFO = "CONTEXT_INFO"
        val SNAPSHOT_FILE_NAME = "privacy.context.snapshot.txt"
        val SNAPSHOT_PATH = AppPaths.TMP_DIR.resolve(SNAPSHOT_FILE_NAME)
        var SNAPSHOT_DUMP_INTERVAL = Duration.ofMinutes(1)
    }

    private val logger = getLogger(MultiPrivacyContextManager::class)
    private val tracer = logger.takeIf { it.isTraceEnabled }
    private val throttlingLogger = ThrottlingLogger(logger)
    private var numTasksAtLastReportTime = 0L
    private val allowedPrivacyContextCount: Int get() {
        // PRIVACY_CONTEXT_NUMBER is deprecated, use BROWSER_CONTEXT_NUMBER instead
//        val defaultValue = conf.getInt(PRIVACY_CONTEXT_NUMBER, 2)
//        return conf.getInt(BROWSER_CONTEXT_NUMBER, defaultValue)

        return conf.getWithFallback(BROWSER_CONTEXT_NUMBER, PRIVACY_CONTEXT_NUMBER)?.toIntOrNull() ?: 2
    }

    val maxAllowedBadContexts = 10

    internal val maintainCount = AtomicInteger()
    private var lastMaintainTime = Instant.now()
    private val minMaintainInterval = Duration.ofSeconds(15)
    private val tooFrequentMaintenance get() = DateTimes.isNotExpired(lastMaintainTime, minMaintainInterval)
    private var lastDumpTime = Instant.now()
    private val snapshotDumpCount = AtomicInteger()
    var snapshotDumpInterval = SNAPSHOT_DUMP_INTERVAL
    private val messageWriter = MultiSinkMessageWriter()

    private val activeContextCount get() = permanentContexts.size + temporaryContexts.size

    private var driverAbsenceReportTime = Instant.EPOCH

    private val iterator = Iterables.cycle(temporaryContexts.values).iterator()

    val metrics = Metrics()

    constructor(
        driverPoolManager: WebDriverPoolManager
    ) : this(driverPoolManager, null, null, driverPoolManager.immutableConfig)

    constructor(
        driverPoolManager: WebDriverPoolManager,
        conf: ImmutableConfig
    ) : this(driverPoolManager, null, null, conf)

    constructor(
        conf: ImmutableConfig
    ) : this(DefaultWebDriverPoolManager(conf), conf)

    /**
     * Run a task in a privacy context.
     *
     * The privacy context is selected from the active privacy context pool,
     * and it is supposed to have at least one ready web driver to run the task.
     *
     * If the privacy context chosen is not ready to serve, especially, it has no any ready web driver,
     * the task will be canceled.
     *
     * @param task the fetch task
     * @param fetchFun the fetch function
     * @return the fetch result
     * @throws ProxyVendorException if the proxy vendor is not available
     * @throws Exception if the fetch function throws an exception
     * */
    @Throws(ProxyVendorException::class, Exception::class)
    override suspend fun run(task: FetchTask, fetchFun: suspend (FetchTask, WebDriver) -> FetchResult): FetchResult {
        metrics.tasks.mark()

        if (!isActive) {
            return FetchResult.canceled(task, "Inactive privacy context manager")
        }

        // maintain the privacy context system before run
        maintain()

        // Try to get a ready privacy context, the privacy context is supposed to be:
        // not closed, not retired, [not idle]?, has promised driver.
        // If the privacy context is inactive, close it and cancel the task.
        try {
            val context = tryGetNextReadyPrivacyContext(task.page, task.fingerprint, task)
            val result = runWithPrivacyContextChecked(context, task, fetchFun).also { metrics.finishes.mark() }

            return result
        } catch (e: ProxyVendorException) {
            logger.warn("ProxyVendorException, can not handle at this layer, rethrow | {}\n{}", task.url, e.brief())
            throw e
        } catch (e: PrivacyException) {
            logger.warn("PrivacyException, retry later | ${task.url}", e)
            return FetchResult.crawlRetry(task, e)
        }
    }

    /**
     * Create a privacy context who is not added to the context list yet.
     * */
    override fun createUnmanagedContext(profile: BrowserProfile): BrowserPrivacyContext {
        val context = BrowserPrivacyContext(proxyPoolManager, driverPoolManager, coreMetrics, conf, profile)

        when {
            profile.isPermanent -> {
                logger.info("Permanent privacy context is created | #{} | {}", context.display, context.baseDir)
            }

            profile.isTemporary -> {
                logger.info(
                    "Temporary privacy context is created | #{} | active: {}, allowed: {} | {}",
                    context.display, temporaryContexts.size, allowedPrivacyContextCount, context.baseDir
                )
            }
            profile.isGroup -> {
                logger.info(
                    "Sequential privacy context in group is created | {} | active: {}, allowed: {} | {}",
                    context.display, temporaryContexts.size, allowedPrivacyContextCount, context.baseDir
                )
            }
            else -> {
                logger.warn("Unexpected privacy context is created | {} | {}", context.display, context.baseDir)
            }
        }

        return context
    }

    /**
     * Try to get a ready privacy context.
     *
     * The status of the privacy context can change immediately, so this method may
     * return a non-ready privacy context.
     *
     * If the total number of active contexts is less than the maximum number allowed,
     * a new privacy context will be created.
     *
     * If the privacy context is inactive, close it and create a new one immediately, and returns a new one.
     *
     * This method can return a non-ready privacy context, in which case the task will be canceled.
     *
     * A ready privacy context has to meet the following requirements:
     * 1. not closed
     * 2. not leaked
     * 3. [requirement removed] not idle
     * 4. not retired
     * 5. if there is a proxy, the proxy has to be ready
     * 6. the associated driver pool promises to provide an available driver, ether one of the following:
     *    1. it has slots to create new drivers
     *    2. it has standby drivers
     *
     * @param fingerprint The fingerprint of this privacy context.
     * @return A privacy context which is promised to be ready.
     * @throws PrivacyException if no ready privacy context is available
     * */
    @Throws(PrivacyException::class)
    override fun tryGetNextReadyPrivacyContext(
        page: WebPage,
        fingerprint: Fingerprint,
        task: FetchTask
    ): PrivacyContext {
        val context = tryGetNextUnderLoadedPrivacyContext(page, fingerprint, task)

        // A ready context is supposed to serve tasks, but not guaranteed.
        if (context.isReady) {
            return context
        } else {
            // If a context is not ready, it can be fully loaded, which means all web drivers are working,
            // and it can turn to be ready later
        }

        // The context is inactive, close it
        if (!context.isActive) {
            close(context)
        }

        return context
    }

    /**
     * Gets an under-loaded privacy context, which can be either ready or not ready.
     *
     * If the total number of active contexts is less than the maximum number allowed,
     * a new privacy context will be created.
     *
     * This method can return an inactive privacy context, in which case, the task should be canceled,
     * and the privacy context should be closed.
     *
     * @param fingerprint The fingerprint of this privacy context.
     * @return A privacy context which is promised to be ready.
     * @throws PrivacyException if no ready privacy context is available
     * */
    @Throws(PrivacyException::class)
    override fun tryGetNextUnderLoadedPrivacyContext(
        page: WebPage,
        fingerprint: Fingerprint,
        task: FetchTask
    ): PrivacyContext {
        synchronized(contextLifeCycleMonitor) {
            if (!isActive) {
                throw PrivacyException("Inactive privacy context manager")
            }

            val privacyAgent = createPrivacyAgent(page, fingerprint)
            if (privacyAgent.isPermanent) {
                // logger.info("Prepare for permanent browser profile | {}", privacyAgent)
                reserveResourceForcefully()
                return getOrCreate(privacyAgent)
            }

            if (activeContextCount < allowedPrivacyContextCount) {
                getOrCreate(privacyAgent)
            }

            try {
                return tryGetNextUnderLoadedPrivacyContext()
            } catch (e: NoSuchElementException) {
                throw PrivacyException("No under-loaded privacy context available", e)
            }
        }
    }

    override fun getOrCreate(profile: BrowserProfile): PrivacyContext {
        synchronized(contextLifeCycleMonitor) {
            if (!isActive) {
                throw PrivacyException("Inactive privacy context manager")
            }

            return if (profile.isPermanent) {
                permanentContexts.computeIfAbsent(profile) { createUnmanagedContext(profile) }
            } else {
                temporaryContexts.computeIfAbsent(profile) { createUnmanagedContext(profile) }
            }
        }
    }

    override fun tryGetNextReadyPrivacyContext(fingerprint: Fingerprint): PrivacyContext {
        return tryGetNextReadyPrivacyContext(GoraWebPage.NIL, fingerprint, FetchTask.NIL)
    }

    /**
     * Maintain all the privacy contexts, check and report inconsistency, illness, idleness, etc.,
     * close bad contexts if necessary.
     *
     * If "takePrivacyContextSnapshot" is in file AppPaths.PATH_LOCAL_COMMAND, perform the action.
     *
     * If the tmp dir is the default one, run the following command to take snapshot once:
     * echo takePrivacyContextSnapshot >> /tmp/pulsar/pulsar-commands
     * */
    override fun maintain(force: Boolean) {
        if (!force && tooFrequentMaintenance) {
            return
        }
        lastMaintainTime = Instant.now()

        if (maintainCount.getAndIncrement() == 0) {
            logger.info("Maintaining service is started, minimal maintain interval: {}", minMaintainInterval)
        }

        doMaintain()

        // assign the last maintain time again
        lastMaintainTime = Instant.now()
    }

    private fun doMaintain() {
        closeDyingContexts()

        // and then check the active context list
        activeContexts.values.forEach { context ->
            context.maintain()
        }
//        driverPoolManager.maintain()

        dumpIfNecessary()
    }

    @Throws(PrivacyException::class)
    private fun createPrivacyAgent(page: WebPage, fingerprint: Fingerprint): BrowserProfile {
        // Specify the browser profile by the user code
        val specifiedProfile = page.getBeanOrNull(BrowserProfile::class.java)
        if (specifiedProfile is BrowserProfile) {
            return specifiedProfile
        }

        val generator = privacyAgentGeneratorFactory.generator
        try {
            return generator.invoke(fingerprint)
        } catch (e: IOException) {
            throw PrivacyException("Failed to create a browser profile | ${generator::class.simpleName}", e)
        }
    }

    /**
     * Get the next under loaded privacy context, which can be ether active or inactive.
     *
     * If a privacy context is full capacity, it means the underlying layer is healthy and is running full load, and
     * there is no resource to serve new tasks.
     *
     * If a privacy context is not full capacity, it means the underlying layer is inactive or has available resources
     * to serve new tasks.
     *
     * @return A privacy context which is promised to be ready to serve a new task.
     * @throws NoSuchElementException if no under-loaded privacy context is available
     * */
    @Throws(NoSuchElementException::class)
    private fun tryGetNextUnderLoadedPrivacyContext(): PrivacyContext {
        var n = temporaryContexts.size

        var pc = iterator.next()
        while (n-- > 0 && pc.isFullCapacity) {
            pc = iterator.next()
        }

        return pc
    }

    private fun reserveResourceForcefully() {
        doMaintain()

        if (AppSystemInfo.isSystemOverCriticalLoad) {
            logger.info(
                "Critical resource, closing a temporary context | availableMem: {}, memToReserve: {}, shortage: {}",
                AppSystemInfo.formatAvailableMemory(), AppSystemInfo.formatMemoryToReserve(),
                AppSystemInfo.formatMemoryShortage()
            )

            temporaryContexts.entries.firstOrNull()?.let { close(it.value) }
        }
    }

    private fun closeDyingContexts() {
        // weak consistency, which is OK
        activeContexts.filterValues { !it.isActive }.values.forEach {
            permanentContexts.remove(it.profile)
            temporaryContexts.remove(it.profile)

            logger.info(
                "Privacy context is inactive, closing it | {} | {} | {}",
                it.elapsedTime.readable(), it.display, it.readableState
            )
            close(it)
        }

        temporaryContexts.filterValues { it.isIdle }.values.forEach {
            temporaryContexts.remove(it.profile)
            logger.warn(
                "Privacy context hangs unexpectedly, closing it | {}/{} | {} | {}",
                it.idleTime.readable(), it.elapsedTime.readable(), it.display, it.readableState
            )
            close(it)
        }

        permanentContexts.filterValues { it.isIdle }.values.forEach {
            permanentContexts.remove(it.profile)
            logger.warn(
                "Permanent privacy context is idle, closing it | {}/{} | {} | {}",
                it.idleTime.readable(), it.elapsedTime.readable(), it.display, it.readableState
            )
            close(it)
        }

        activeContexts.filterValues { it.isHighFailureRate }.values.forEach {
            permanentContexts.remove(it.profile)
            temporaryContexts.remove(it.profile)
            logger.warn(
                "Privacy context has too high failure rate: {}, closing it | {} | {} | {}",
                it.failureRate, it.elapsedTime.readable(), it.display, it.readableState
            )
            close(it)
        }
    }

    /**
     * Try to run the task with the given privacy context, the privacy context is supposed to be:
     * not closed, not retired, [not idle]?, has promised driver.
     * If the privacy context is inactive, close it and cancel the task.
     * */
    @Throws(ProxyVendorException::class)
    private suspend fun runWithPrivacyContextChecked(
        privacyContext: PrivacyContext, task: FetchTask, fetchFun: suspend (FetchTask, WebDriver) -> FetchResult
    ): FetchResult {
        val error = checkPrivacyContext(privacyContext, task)
        if (error != null) {
            // Use default delay strategy.
            return FetchResult.crawlRetry(task, error)
        }

        // No driver available currently, it indicates that all drivers are working, retry later
        if (!privacyContext.hasWebDriverPromise()) {
            // The schedule will check the driver again, so it's OK to retry in a short period
            return FetchResult.crawlRetry(task, delay = Duration.ofSeconds(10), "No driver available")
        }

        return doRunAndUpdate(privacyContext, task, fetchFun)
    }

    @Throws(ProxyVendorException::class)
    private suspend fun doRunAndUpdate(
        privacyContext: PrivacyContext, task: FetchTask, fetchFun: suspend (FetchTask, WebDriver) -> FetchResult
    ): FetchResult {
        val result = doRun(privacyContext, task, fetchFun)

        updatePrivacyContext(privacyContext as AbstractPrivacyContext, result)
        // All retries are forced to do in crawl scope
        if (result.isPrivacyRetry) {
            result.status.upgradeRetry(RetryScope.CRAWL)
        }

        return result
    }

    @Throws(ProxyVendorException::class)
    private suspend fun doRun(
        privacyContext: PrivacyContext, task: FetchTask, fetchFun: suspend (FetchTask, WebDriver) -> FetchResult
    ): FetchResult {
        val result: FetchResult = try {
            require(!task.isCanceled)
            require(task.state.get() == FetchTask.State.NOT_READY)
            require(task.proxyEntry == null)

            task.markReady()
            // @Throws(Exception::class)
            privacyContext.run(task) { _, driver ->
                task.startWork()
                fetchFun(task, driver)
            }
        } finally {
            task.done()
            val page = task.page as AbstractWebPage
            page.variables[VAR_CONTEXT_INFO] = formatPrivacyContext(privacyContext as AbstractPrivacyContext)
        }

        return result
    }

    /**
     * Check the privacy context, if the privacy context is inactive, close it and cancel the task.
     * */
    private fun checkPrivacyContext(privacyContext: PrivacyContext, task: FetchTask): String? {
        val url = task.url
        val state = privacyContext.readableState
        val errorMessage = when {
            privacyContext.isIdle -> {
                logger.info("Privacy context is idle, close it and retrying task | #{} | {} | {}", task.id, state, url)
                close(privacyContext)
                "PRIVACY CX IDLE"
            }
            privacyContext.isClosed -> {
                logger.info("Privacy context is closed, retrying task | #{} | {} | {}", task.id, state, url)
                "PRIVACY CX CLOSED"
            }
            !privacyContext.isActive -> {
                logger.info("Privacy context is inactive, close it and retrying task | #{} | {} | {}", task.id, state, url)
                close(privacyContext)
                "PRIVACY CX INACTIVE"
            }
            else -> null
        }

        if (errorMessage != null) {
            metrics.illegalDrivers.mark()

            // rate_unit=events/minute
            if (metrics.illegalDrivers.oneMinuteRate > 5) {
                handleTooManyDriverAbsence(errorMessage, task)
            }
        }

        return errorMessage
    }

    private fun handleTooManyDriverAbsence(errorMessage: String, task: FetchTask) {
        val now = Instant.now()
        if (Duration.between(driverAbsenceReportTime, now).seconds > 10) {
            driverAbsenceReportTime = now

            val promisedDrivers = temporaryContexts.values.joinToString { it.promisedWebDriverCount().toString() }
            val states = temporaryContexts.values.joinToString { it.readableState }
            val idleTimes = temporaryContexts.values.joinToString { it.idleTime.readable() }
            logger.warn(
                "Too many driver absence, promised drivers: {} | {} | {} | {} | {}",
                promisedDrivers, errorMessage, states, idleTimes, task.url
            )
        }
    }

    private fun formatPrivacyContext(privacyContext: AbstractPrivacyContext): String {
        return String.format("%s(%.2f)", privacyContext.profile.display, privacyContext.meterSuccesses.meanRate)
    }

    /**
     * Handle after run
     * */
    private fun updatePrivacyContext(privacyContext: AbstractPrivacyContext, result: FetchResult) {
        if (!privacyContext.isActive) {
            reportPrivacyContextInactive(privacyContext, result)
            return
        }

        val numTasks = privacyContext.meterTasks.count
        if (numTasks > numTasksAtLastReportTime && numTasks % 30 == 0L) {
            numTasksAtLastReportTime = numTasks
            privacyContext.report()
        }

        val status = result.response.protocolStatus
        when {
            // TODO: review all retries and cancels
//            status.isRetry(RetryScope.PRIVACY) -> logPrivacyLeakWarning(privacyContext, result)
            status.isRetry -> logPrivacyLeakWarning(privacyContext, result)
            status.isSuccess -> metrics.successes.mark()
        }
    }

    private fun logPrivacyLeakWarning(privacyContext: AbstractPrivacyContext, result: FetchResult) {
        val warnings = privacyContext.privacyLeakWarnings.get()
        val status = result.status
        if (warnings > 0) {
            val symbol = PopularEmoji.WARNING

            val warningMessage = if (privacyContext.profile.isPermanent) {
                String.format("%s/%s", warnings, privacyContext.maximumWarnings)
            } else {
                String.format("%s", warnings)
            }

            logger.info(
                "$symbol Privacy leak warning {} | {}#{} | {}. {}",
                warningMessage,
                privacyContext.seq, privacyContext.display,
                result.task.page.id, status
            )
        }

        if (privacyContext.privacyLeakWarnings.get() == 6) {
            privacyContext.report()
        }
    }

    private fun reportPrivacyContextInactive(privacyContext: AbstractPrivacyContext, result: FetchResult) {
        throttlingLogger.info(
            "{}. Context {}/#{} is not active | {} | {} | {}",
            result.task.id, privacyContext.seq, privacyContext.privacyLeakWarnings,
            result.status, privacyContext.readableState, result.task.url
        )
    }

    private fun dumpIfNecessary() {
        if (DateTimes.isExpired(lastDumpTime, snapshotDumpInterval)) {
            lastDumpTime = Instant.now()
            dump()
        }
    }

    private fun dump() {
        try {
//            if (!Files.exists(SNAPSHOT_PATH)) {
//                Files.createDirectories(SNAPSHOT_PATH.parent)
//            }

            if (activeContexts.isEmpty()) {
                return
            }

            val count = snapshotDumpCount.incrementAndGet()
            val sb = StringBuilder()
            sb.append("\n\n\n$count. Privacy contexts snapshot \n")
            sb.appendLine(LocalDateTime.now())
            sb.append("------------------------------")
            sb.append("\n", buildStatusString())
            sb.append("\n")
            sb.append("\n")
            activeContexts.values.forEach { sb.append(it.buildReport()) }
            sb.append("<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<")

            // Files.writeString(SNAPSHOT_PATH, sb.toString(), StandardOpenOption.CREATE, StandardOpenOption.APPEND)
            // use message writer to write the snapshot, so if the file becomes too large, it will be rotated
            messageWriter.writeTo(sb.toString(), SNAPSHOT_PATH)
        } catch (e: IOException) {
            logger.warn("IOException", e)
        }
    }
}
