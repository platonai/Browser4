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
import ai.platon.pulsar.common.config.ImmutableConfig
import ai.platon.pulsar.common.logging.ThrottlingLogger
import ai.platon.pulsar.protocol.browser.driver.WebDriverPoolManager
import ai.platon.pulsar.protocol.browser.driver.WebDriverPoolManager.Companion.DRIVER_FAST_CLOSE_TIME_OUT
import ai.platon.pulsar.protocol.browser.driver.WebDriverPoolManager.Companion.DRIVER_SAFE_CLOSE_TIME_OUT
import ai.platon.pulsar.protocol.browser.emulator.WebDriverPoolException
import ai.platon.pulsar.protocol.browser.emulator.WebDriverPoolExhaustedException
import ai.platon.pulsar.skeleton.common.AppSystemInfo
import ai.platon.pulsar.skeleton.common.metrics.MetricsSystem
import ai.platon.pulsar.skeleton.workflow.fetch.FetchResult
import ai.platon.pulsar.skeleton.workflow.fetch.FetchTask
import ai.platon.pulsar.skeleton.workflow.fetch.driver.*
import ai.platon.pulsar.skeleton.workflow.fetch.privacy.BrowserId
import com.codahale.metrics.Gauge
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * The web driver context.
 * Web page fetch tasks run in web driver contexts.
 * */
open class WebDriverContext(
    val browserId: BrowserId,
    val driverPoolManager: WebDriverPoolManager,
    val conf: ImmutableConfig
): AutoCloseable {
    companion object {
        private val numGlobalRunningTasks = AtomicInteger()
        private val globalTasks = MetricsSystem.reg.meter(this, "globalTasks")
        private val globalFinishedTasks = MetricsSystem.reg.meter(this, "globalFinishedTasks")

        init {
            MetricsSystem.reg.register(this,"globalRunningTasks", Gauge { numGlobalRunningTasks.get() })
        }
    }

    private val logger = LoggerFactory.getLogger(WebDriverContext::class.java)
    private val throttlingLogger = ThrottlingLogger(logger)
    private val runningTasks = ConcurrentLinkedDeque<FetchTask>()
    private val lock = ReentrantLock()
    private val notBusy = lock.newCondition()

    private val closed = AtomicBoolean()

    private val browserManager = driverPoolManager.browserManager

    private val browser get() = browserManager.findBrowserOrNull(browserId) as? AbstractBrowser

    /**
     * The driver context is active if the following conditions meet:
     * 1. the context is not closed
     * 2. the application is active
     * 3. the browser is not in closed pool nor in retired pool
     * */
    open val isActive: Boolean get() {
        val active = !closed.get() && AppContext.isActive && driverPoolManager.hasPossibility(browserId)

        if (!active) {
            val state = listOf("closed" to closed.get(),
                "hasPossibility" to driverPoolManager.hasPossibility(browserId)
            ).map { it.first to if (it.second) "✓" else "✗" }
                .joinToString(",") { it.first + ":" + it.second }
            throttlingLogger.info("WebDriverContext is not active | $state | ${browserId.contextDir}")
        }

        return active
    }
    /**
     * Check if the driver context is retired.
     * */
    open val isRetired get() = driverPoolManager.isRetiredPool(browserId)
    /**
     * Check if the driver context is ready to serve
     * */
    open val isReady: Boolean
        get() {
            val isDriverPoolReady = driverPoolManager.isReady && driverPoolManager.hasDriverPromise(browserId)
            return isActive && isDriverPoolReady
        }

    val state: Map<String, Any>
        get() = mapOf(
            "browserId" to browserId,
            "isActive" to isActive,
            "isRetired" to isRetired,
            "isReady" to isReady,
            "runningTasks" to runningTasks.size,
            "numGlobalRunningTasks" to numGlobalRunningTasks.get(),
            "globalTasksCount" to globalTasks.count,
            "globalFinishedTasksCount" to globalFinishedTasks.count
        )

    /**
     * Run a web driver task.
     * This method should not throw any WebDriverException.
     * */
    suspend fun run(task: FetchTask, browseFun: suspend (FetchTask, WebDriver) -> FetchResult): FetchResult {
        globalTasks.mark()

        return checkAbnormalResult(task) ?: try {
            runningTasks.add(task)
            numGlobalRunningTasks.incrementAndGet()

            doRunAndHandleWebDriverException(task) {
                driverPoolManager.run(browserId, task) { browseFun(task, it) }
                    ?: FetchResult.crawlRetry(task, "Driver pool manager exception")
            }
        } finally {
            runningTasks.remove(task)
            numGlobalRunningTasks.decrementAndGet()
            globalFinishedTasks.mark()

            if (runningTasks.isEmpty()) {
                lock.withLock { notBusy.signalAll() }
            }

            if (numGlobalRunningTasks.get() == 0 && globalFinishedTasks.fiveMinuteRate > 0.1) {
                logger.debug("No running task now | ${globalFinishedTasks.count}/${globalTasks.count} (finished/all)")
            }
        }
    }

    private suspend fun doRunAndHandleWebDriverException(task: FetchTask, browseFun: suspend () -> FetchResult): FetchResult {
        return try {
            browseFun()
        } catch (e: IllegalWebDriverStateException) {
            handleIllegalWebDriverStateException(task, e)
        } catch (e: WebDriverPoolExhaustedException) {
            if (AppContext.isActive) {
                // log only when the application is active
                val message = String.format("%s. [Exhausted] Retry task %s in crawl scope | cause by: %s", task.page.id, task.id, e.message)
                logger.warn(message)
            }
            FetchResult.crawlRetry(task, "Driver pool exhausted")
        } catch (e: WebDriverPoolException) {
            if (AppContext.isActive) {
                // log only when the application is active
                logger.warn("WebDriverPoolException | {} | {}", e.browserId, e.message)
                logger.warn("{}. [WebDriverPoolException] Retry task {} in crawl scope", task.page.id, task.id)
            }
            FetchResult.crawlRetry(task, "Driver pool exception")
        } catch (e: WebDriverException) {
            logger.warn("{}. [WebDriverException] Retry task {} in crawl scope | caused by: {}", task.page.id, task.id, e.message)
            logger.warn("Failed to execute fetch task", e)
            FetchResult.crawlRetry(task, e)
        }
    }

    private fun handleIllegalWebDriverStateException(task: FetchTask, e: IllegalWebDriverStateException): FetchResult {
        val driver = e.driver
        val b = driver?.browser ?: this.browser

        val reason = when {
            !AppContext.isActive -> "Browser4 is shutting down"
            e is BrowserUnavailableException -> "BrowserUnavailableException"
            else -> "IllegalWebDriverStateException"
        }

        val result = when {
            !AppContext.isActive -> FetchResult.canceled(task, reason)
            b?.isActive == true -> {
                logger.warn("Closing illegal browser, retrying task #${task.page.id} in crawl scope | {} | {} | {}",
                    b.readableState, e.message, task.page.url)
                FetchResult.crawlRetry(task, reason)
            }
            else -> FetchResult.canceled(task, reason)
        }

        driverPoolManager.closeBrowserAccompaniedDriverPoolGracefully(browserId, DRIVER_FAST_CLOSE_TIME_OUT)
        return result
    }

    @Throws(Exception::class)
    open fun maintain() {
        // should close dead, valueless, idle driver pools, etc
    }

    /**
     * Closing call stack:
     *
     * PrivacyContextManager.close -> PrivacyContext.close -> WebDriverContext.close -> WebDriverPoolManager.close
     * -> BrowserManager.close -> Browser.close -> WebDriver.close
     * |-> LoadingWebDriverPool.close
     *
     * */
    override fun close() {
        if (closed.compareAndSet(false, true)) {
            if (!AppContext.isActive) {
                runCatching { shutdownUnderlyingLayerImmediately() }.onFailure { warnForClose(this, it) }
            } else {
                runCatching { closeContext() }.onFailure { warnForClose(this, it) }
            }
        }
    }

    private fun closeContext() {
        val asap = !AppContext.isActive || AppSystemInfo.isSystemOverCriticalLoad

        logger.debug("Closing web driver context, asap: $asap")

        // not shutdown, wait longer
        if (asap) {
            closeUnderlyingLayerGracefully()
        } else {
            // always close the context as soon as possible, just retry the unfinished tasks.
            // waitUntilAllDoneNormally(Duration.ofMinutes(1))
            // close underlying IO based modules asynchronously
            closeUnderlyingLayerGracefully()
        }

        // No need to wait for the underlying layer to be closed, just close it
        // waitUntilNoRunningTasks(Duration.ofSeconds(10))

        val isShutdown = if (AppContext.isActive) "" else " (shutdown)"
        val display = browserId.display
        if (runningTasks.isNotEmpty()) {
            logger.info("Still {} running tasks after context close$isShutdown | {} | {}",
                runningTasks.size, runningTasks.joinToString { "${it.id}(${it.state})" }, display)
        } else {
            logger.info("Web driver context is closed successfully$isShutdown | {} | {}", display, browserId.contextDir)
        }
    }

    private fun closeUnderlyingLayerGracefully() {
        // Mark all working tasks to be canceled, so they return as soon as possible
        runningTasks.forEach { it.cancel() }
        // Cancel the browser, and all online drivers, and the worker coroutines with the drivers
        driverPoolManager.cancelAll(browserId)

        driverPoolManager.closeBrowserAccompaniedDriverPoolGracefully(browserId, DRIVER_SAFE_CLOSE_TIME_OUT)
    }

    private fun shutdownUnderlyingLayerImmediately() {
        logger.info("Shutdown the underlying layer immediately")

        runningTasks.forEach { it.cancel() }
        driverPoolManager.cancelAll()
        driverPoolManager.close()
    }

    private fun waitUntilAllDoneNormally(timeout: Duration) {
        waitUntilIdle(timeout)
    }

    private fun waitUntilNoRunningTasks(timeout: Duration) {
        waitUntilIdle(timeout)
    }

    /**
     * Wait until there is no running tasks.
     * @see [ArrayBlockingQueue#take]
     * @throws InterruptedException if the current thread is interrupted
     * */
    @Throws(InterruptedException::class)
    private fun waitUntilIdle(timeout: Duration) {
        var n = timeout.seconds
        lock.lockInterruptibly()
        try {
            while (runningTasks.isNotEmpty() && !AppSystemInfo.isSystemOverCriticalLoad && n-- > 0) {
                notBusy.await(1, TimeUnit.SECONDS)
            }
        } finally {
            lock.unlock()
        }

        val isShutdown = if (AppContext.isActive) "" else " (shutdown)"
        val display = browserId.display
        val message = when {
            AppSystemInfo.isCriticalMemory ->
                String.format("Low memory (%s), close %d retired browsers immediately$isShutdown | $display",
                    AppSystemInfo.formatAvailableMemory(), runningTasks.size)
            n <= 0L -> String.format("Timeout (still %d running tasks)$isShutdown | $display", runningTasks.size)
            n > 0 -> String.format("All tasks return in %d seconds$isShutdown | $display", timeout.seconds - n)
            else -> ""
        }

        if (message.isNotBlank()) {
            logger.info(message)
        }
    }

    private fun checkAbnormalResult(task: FetchTask): FetchResult? {
        if (!isActive) {
            return FetchResult.canceled(task, "Inactive web driver context")
        }

        if (driverPoolManager.isRetiredPool(browserId)) {
            return FetchResult.canceled(task, "Retired driver pool")
        }

        return null
    }
}
