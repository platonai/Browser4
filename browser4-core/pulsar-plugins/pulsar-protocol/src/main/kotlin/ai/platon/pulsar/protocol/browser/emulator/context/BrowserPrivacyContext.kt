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
import ai.platon.pulsar.common.PulsarParams.VAR_PRIVACY_CONTEXT_DISPLAY
import ai.platon.pulsar.common.config.ImmutableConfig
import ai.platon.pulsar.common.logging.ThrottlingLogger
import ai.platon.pulsar.common.proxy.*
import ai.platon.pulsar.persist.AbstractWebPage
import ai.platon.pulsar.protocol.browser.driver.WebDriverPoolManager
import ai.platon.pulsar.skeleton.common.options.LoadOptions
import ai.platon.pulsar.skeleton.common.proxy.UniversalProxyParser
import ai.platon.pulsar.skeleton.crawl.CoreMetrics
import ai.platon.pulsar.skeleton.crawl.fetch.FetchResult
import ai.platon.pulsar.skeleton.crawl.fetch.FetchTask
import ai.platon.pulsar.skeleton.crawl.fetch.driver.WebDriver
import ai.platon.pulsar.skeleton.crawl.fetch.privacy.AbstractPrivacyContext
import ai.platon.pulsar.skeleton.crawl.fetch.privacy.BrowserId
import ai.platon.pulsar.skeleton.crawl.fetch.privacy.BrowserProfile
import com.google.common.annotations.Beta

open class BrowserPrivacyContext(
    val proxyPoolManager: ProxyPoolManager? = null,
    val driverPoolManager: WebDriverPoolManager,
    val coreMetrics: CoreMetrics? = null,
    conf: ImmutableConfig,
    profile: BrowserProfile
) : AbstractPrivacyContext(profile, conf) {
    private val logger = getLogger(BrowserPrivacyContext::class)
    private val throttlingLogger = ThrottlingLogger(logger)

    private val isActive0: Boolean get() {
        val isProxyContextActive = proxyContext == null || proxyContext?.isActive == true
        val isDriverContextActive = driverContext.isActive
        return isProxyContextActive && isDriverContextActive && super.isActive
    }

    private val isRetired0: Boolean
        get() {
            val doRetired = retired || proxyContext?.isRetired == true || driverContext.isRetired
            return doRetired
        }

    val browserId = BrowserId(profile.contextDir, profile.fingerprint)
    val driverContext = WebDriverContext(browserId, driverPoolManager, conf)
    var proxyContext: ProxyContext? = null
        private set
    val proxyEntry get() = proxyContext?.proxyEntry

    override val isRetired: Boolean get() = isRetired0

    override val isActive: Boolean get() = isActive0

    override val isReady: Boolean
        get() {
            val isProxyContextReady = proxyContext == null || proxyContext?.isReady == true
            val isDriverContextReady = driverContext.isReady
            return isProxyContextReady && isDriverContextReady && super.isReady
        }

    override val isFullCapacity: Boolean get() = driverPoolManager.isFullCapacity(browserId)

    override val state: Map<String, Any?>
        get() = mapOf(
            "id" to id,
            "browser" to browserId.display,
            "proxyEntry" to proxyEntry,
            "isActive" to isActive0,
            "isFullCapacity" to isFullCapacity,
            "isRetired" to isRetired0
        ) + super.state

    @Throws(ProxyVendorException::class, IllegalStateException::class)
    override suspend fun open(url: String): FetchResult {
        val task = FetchTask.create(url, conf.toVolatileConfig())
        val f = checkNotNull(webdriverFetcher) { "WebDriverFetcher is null" }
        return doRun(task) { _, driver -> f.fetchDeferred(task, driver) }
    }

    /**
     * @param url The url to fetch
     * @param options Load options
     * */
    @Throws(ProxyVendorException::class, IllegalStateException::class)
    override suspend fun open(url: String, options: LoadOptions): FetchResult {
        val task = FetchTask.create(url, options)
        val f = checkNotNull(webdriverFetcher) { "WebDriverFetcher is null" }
        return doRun(task) { _, driver -> f.fetchDeferred(task, driver) }
    }

    @Throws(ProxyVendorException::class)
    override suspend fun doRun(task: FetchTask, fetchFun: suspend (FetchTask, WebDriver) -> FetchResult): FetchResult {
        initProxyContextIfNecessary(task)

        return checkAbnormalResult(task) ?: proxyContext?.run(task, fetchFun) ?: driverContext.run(task, fetchFun)
    }

    override fun maintain() {
        proxyContext?.maintain()
        driverContext.maintain()
    }

    override fun promisedWebDriverCount() = driverPoolManager.promisedDriverCount(browserId)

    @Beta
    override fun subscribeWebDriver() = driverPoolManager.subscribeDriver(browserId)

    override fun buildReport(): String {
        var report = String.format(
            "Privacy context has lived for %s | %s | %s" +
                    " | success: %s(%s pages/s) | small: %s(%s) | traffic: %s(%s/s) | tasks: %s total run: %s | %s",
            // Privacy context has lived for {} | {} | {}
            elapsedTime.readable(), display, readableState,
            // success: {}({} pages/s)
            meterSuccesses.count, String.format("%.2f", meterSuccesses.meanRate),
            // small: {}({})
            meterSmallPages.count, String.format("%.1f%%", 100 * smallPageRate),
            // traffic: {}({}/s)
            Strings.compactFormat(coreMetrics?.totalNetworkIFsRecvBytes ?: 0),
            Strings.compactFormat(coreMetrics?.networkIFsRecvBytesPerSecond ?: 0),
            // tasks: {} total run: {}
            meterTasks.count, meterFinishes.count,
            // proxy: {}
            proxyContext?.proxyEntry?.toString()
        )
        report += "\n"

        if (smallPageRate > 0.5) {
            report += String.format(
                "Privacy context #%s is disqualified, too many small pages: %s(%s)",
                seq, meterSmallPages.count, String.format("%.1f%%", 100 * smallPageRate)
            )
            report += "\n"
        }

        // 0 to disable
        if (meterSuccesses.meanRate < 0) {
            report += String.format(
                "Privacy context #{} is disqualified, it's expected 120 pages in 120 seconds at least",
                seq
            )
            // check the zombie context list, if the context keeps go bad, the proxy provider is bad
            report += "\n"
        }

        return report.trimEnd()
    }

    override fun report() {
        logger.info(buildReport())
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
            try {
                report()
                driverContext.close()
                proxyContext?.close()
            } catch (t: Throwable) {
                warnForClose(this, t)
            }
        }
    }

    private fun checkAbnormalResult(task: FetchTask): FetchResult? {
        return when {
            !isActive -> FetchResult.canceled(task, "PRIVACY CX INACTIVE")
            else -> null
        }
    }

    @Throws(ProxyException::class)
    @Synchronized
    private fun initProxyContextIfNecessary(task: FetchTask) {
        if (!ProxyPoolManager.isProxyEnabled(conf)) {
            driverContext.browserId.unsetProxy()
            proxyContext = null
            return
        }

        if (proxyEntry != null) {
            // logger.info("Proxy context is already created, skip creating proxy context")
            return
        }

        registerProxyParser()

        createProxyContextIfEnabled()
        val page = task.page
        require(page is AbstractWebPage)
        page.setVar(VAR_PRIVACY_CONTEXT_DISPLAY, display)
    }

    private fun registerProxyParser() {
        ProxyParserFactory.registerIfAbsent("UniversalProxyParser", UniversalProxyParser())
    }

    @Throws(ProxyException::class)
    private fun createProxyContextIfEnabled() {
        if (proxyPoolManager == null) {
            // logger.info("Proxy pool manager is null, skip creating proxy context")
            return
        }

        if (proxyPoolManager.isEnabled) {
            createProxyContext()
        } else {
            logger.info("Proxy pool is disabled, skip creating proxy context")
        }
    }

    @Throws(ProxyException::class)
    private fun createProxyContext() {
        if (!isActive) {
            logger.warn("Privacy context is inactive, skip creating proxy context")
            return
        }

        if (!ProxyPoolManager.isProxyEnabled(conf)) {
            return
        }

        try {
            val proxyPoolManager0 = proxyPoolManager ?: throw ProxyException("Proxy pool manager is null")
            proxyContext = ProxyContext.create(driverContext, proxyPoolManager0)
            coreMetrics?.proxies?.mark()
        } catch (e: ProxyException) {
            throttlingLogger.warn(e.brief("Can not create proxy context - "))
        }
    }
}
