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

import ai.platon.pulsar.common.DateTimes
import ai.platon.pulsar.common.config.CapabilityTypes
import ai.platon.pulsar.common.proxy.*
import ai.platon.pulsar.skeleton.common.metrics.MetricsSystem
import ai.platon.pulsar.skeleton.crawl.fetch.FetchResult
import ai.platon.pulsar.skeleton.crawl.fetch.FetchTask
import ai.platon.pulsar.skeleton.crawl.fetch.driver.WebDriver
import com.codahale.metrics.Gauge
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

open class ProxyContext(
    var proxyEntry: ProxyEntry? = null,
    private val proxyPoolManager: ProxyPoolManager,
    private val driverContext: WebDriverContext
): AutoCloseable {

    companion object {
        val numProxyAbsence = AtomicInteger()
        var lastProxyAbsentTime = Instant.now()
        val numRunningTasks = AtomicInteger()
        var maxAllowedProxyAbsence = 200

        init {
            mapOf(
                "proxyAbsences" to Gauge { numProxyAbsence.get() },
                "runningTasks" to Gauge { numRunningTasks.get() }
            ).forEach { MetricsSystem.reg.register(this, it.key, it.value) }
        }
        
        /**
         * Create a proxy context for the given driver context.
         *
         * Proxy order:
         * 1. the proxy in the config file under context directory
         * 2. the proxy in AppPaths.ENABLED_PROXY_DIR
         * 3. the proxy loaded from the provider, the provider is configured in AppPaths.AVAILABLE_PROXY_DIR
         * */
        @Throws(ProxyException::class)
        fun create(driverContext: WebDriverContext, proxyPoolManager: ProxyPoolManager): ProxyContext {
            try {
                val id = driverContext.browserId

                if (!id.hasProxy()) {
                    val proxy = proxyPoolManager.getProxy(id.contextDir, id.fingerprint)
                    id.setProxy(proxy)
                }
                val proxy = id.fingerprint.proxyEntry!!

                numProxyAbsence.takeIf { it.get() > 0 }?.decrementAndGet()

                return ProxyContext(proxy, proxyPoolManager, driverContext)
            } catch (e: NoProxyException) {
                numProxyAbsence.incrementAndGet()
                checkProxyAbsence()
                throw e
            }
        }

        fun checkProxyAbsence() {
            if (numProxyAbsence.get() > maxAllowedProxyAbsence) {
                val now = Instant.now()
                val day1 = DateTimes.dayOfMonth(lastProxyAbsentTime)
                val day2 = DateTimes.dayOfMonth(now)
                if (day2 != day1) {
                    // clear the proxy absence counter at every start of day
                    numProxyAbsence.set(0)
                    lastProxyAbsentTime = now
                } else {
                    throw ProxyVendorUntrustedException("No proxy available, the vendor is untrusted." +
                            " Proxy is absent for $numProxyAbsence times from $lastProxyAbsentTime")
                }
            }
        }
    }

    private val logger = LoggerFactory.getLogger(ProxyContext::class.java)!!
    
    private val conf get() = proxyPoolManager.conf
    /**
     * If the number of success exceeds [maxFetchSuccess], emit a PrivacyRetry result
     * */
    private val maxFetchSuccess = conf.getInt(CapabilityTypes.PROXY_MAX_FETCH_SUCCESS, Int.MAX_VALUE / 10)
    private val minTimeToLive = Duration.ofSeconds(30)
    private val closing = AtomicBoolean()
    private val closed = AtomicBoolean()

    val isEnabled get() = proxyPoolManager.isEnabled
    val isRetired: Boolean get() {
        val p = proxyEntry
        if (p != null) {
            if (p.isExpired) {
                p.retire()
            }
            return p.isRetired
        }
        return false
    }
    val isActive get() = proxyPoolManager.isActive && !closing.get() && !closed.get()
    val isReady: Boolean get() {
        val isProxyReady = proxyEntry == null || proxyEntry?.isReady == true
        return isProxyReady && !isRetired && isActive
    }
    val state: Map<String, Any> get() {
        return mapOf(
            "proxyEntry" to (proxyEntry ?: "<no proxy>"),
            "isRetired" to isRetired,
            "isActive" to isActive,
            "isReady" to isReady,
            "numProxyAbsence" to numProxyAbsence.get(),
            "numRunningTasks" to numRunningTasks.get(),
            "maxAllowedProxyAbsence" to maxAllowedProxyAbsence
        )
    }

    init {
        maxAllowedProxyAbsence = conf.getInt(CapabilityTypes.PROXY_MAX_ALLOWED_PROXY_ABSENCE, 10)
    }

    /**
     * Run the task with the proxy context.
     * @return the fetch result
     * @throws ProxyVendorException if the proxy is not available
     * */
    @Throws(ProxyVendorException::class)
    suspend fun run(task: FetchTask, browseFun: suspend (FetchTask, WebDriver) -> FetchResult): FetchResult {
        return checkAbnormalResult(task) ?:run0(task, browseFun)
    }

    open fun maintain() {
        val p = proxyEntry
        if (p != null && p.isExpired) {
            p.retire()
        }
        // nothing to do currently
    }

    @Throws(ProxyVendorException::class)
    private suspend fun run0(
        task: FetchTask, browseFun: suspend (FetchTask, WebDriver) -> FetchResult
    ): FetchResult {
        var success = false
        return try {
            beforeTaskStart(task)
            proxyPoolManager.runWith(proxyEntry) { driverContext.run(task, browseFun) }.also {
                success = it.response.protocolStatus.isSuccess
                it.response.pageDatum.proxyEntry = proxyEntry
                numProxyAbsence.takeIf { it.get() > 0 }?.decrementAndGet()
            }
        } catch (e: ProxyException) {
            handleProxyException(task, e)
        } finally {
            afterTaskFinished(task, success)
        }
    }

    private fun checkAbnormalResult(task: FetchTask): FetchResult? {
        if (!isActive) {
            return FetchResult.canceled(task, "PROXY CX INACTIVE")
        }

        checkProxyAbsence()

        return null
    }

    @Throws(ProxyVendorException::class)
    private fun handleProxyException(task: FetchTask, e: ProxyException): FetchResult {
        return when (e) {
            is ProxyInsufficientBalanceException -> {
                throw e
            }
            is ProxyRetiredException -> {
                logger.warn("{}, context reset will be triggered | {}", e.message, task.proxyEntry?:"<no proxy>")
                FetchResult.privacyRetry(task, e)
            }
            is NoProxyException -> {
                numProxyAbsence.incrementAndGet()
                checkProxyAbsence()
                logger.warn("No proxy available temporary the {}th times, cause: {}", numProxyAbsence, e.message)
                FetchResult.crawlRetry(task, "No proxy")
            }
            else -> {
                logger.warn("Task failed with proxy {}, cause: {}", proxyEntry, e.message)
                FetchResult.privacyRetry(task, e)
            }
        }
    }

    internal fun beforeTaskStart(task: FetchTask) {
        numRunningTasks.incrementAndGet()

        // If the proxy is idle, and here comes a new task, reset the context
        // The proxy is about to be unusable, reset the context
        proxyEntry?.also {
            task.proxyEntry = it
            it.lastActiveTime = Instant.now()

            if (it.willExpireAfter(minTimeToLive)) {
                if (closing.compareAndSet(false, true)) {
                    throw ProxyRetiredException("The proxy is expired ($minTimeToLive)")
                }
            }

            val successPages = it.numSuccessPages.get()
            // Add a random number to disturb the anti-spider
            val delta = (0.25 * maxFetchSuccess).toInt()
            val limit = maxFetchSuccess + Random(System.currentTimeMillis()).nextInt(-delta, delta)
            if (successPages > limit) {
                // If a proxy served to many pages, the target site may track the finger print of the crawler
                // and also maxFetchSuccess can be used for test purpose
                logger.info("Served too many pages ($successPages/$maxFetchSuccess) | {}", it)
                if (closing.compareAndSet(false, true)) {
                    throw ProxyRetiredException("Served too many pages")
                }
            }
        }
    }
    
    internal fun afterTaskFinished(task: FetchTask, success: Boolean) {
        numRunningTasks.decrementAndGet()
        proxyEntry?.apply {
            if (success) {
                refresh()
                numSuccessPages.incrementAndGet()
                lastTarget = task.url
                servedDomains.add(task.domain)
            } else {
                numFailedPages.incrementAndGet()
            }
        }
    }

    /**
     * Block until the proxy is offline
     * */
    override fun close() {
        if (closed.compareAndSet(false, true)) {
            proxyPoolManager.activeProxyEntries.remove(driverContext.browserId.userDataDir)
        }
    }
}
