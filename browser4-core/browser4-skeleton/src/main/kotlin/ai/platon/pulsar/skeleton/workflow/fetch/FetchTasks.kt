package ai.platon.pulsar.skeleton.workflow.fetch

import ai.platon.pulsar.common.HtmlIntegrity
import ai.platon.pulsar.common.browser.BrowserType
import ai.platon.pulsar.common.browser.fingerprint.Fingerprint
import ai.platon.pulsar.common.config.CapabilityTypes
import ai.platon.pulsar.common.config.VolatileConfig
import ai.platon.pulsar.common.proxy.ProxyEntry
import ai.platon.pulsar.common.urls.URLUtils
import ai.platon.pulsar.persist.RetryScope
import ai.platon.pulsar.persist.WebPage
import ai.platon.pulsar.persist.model.GoraWebPage
import ai.platon.pulsar.skeleton.common.options.LoadOptions
import ai.platon.pulsar.skeleton.workflow.common.FetchEntry
import ai.platon.pulsar.skeleton.workflow.protocol.ForwardingResponse
import ai.platon.pulsar.skeleton.workflow.protocol.Response
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Created by Vincent on 16-10-15.
 * Copyright @ 2013-2016 Platon AI. All rights reserved
 */
data class UrlStat(
    var hostName: String,
    var urls: Int = 0,
    var indexUrls: Int = 0,
    var detailUrls: Int = 0,
    var searchUrls: Int = 0,
    var mediaUrls: Int = 0,
    var bbsUrls: Int = 0,
    var blogUrls: Int = 0,
    var tiebaUrls: Int = 0,
    var unknownUrls: Int = 0,
    var urlsTooLong: Int = 0,
    var urlsFromSeed: Int = 0,
    var pageViews: Int = 0
) : Comparable<UrlStat> {

    override fun compareTo(other: UrlStat): Int {
        val reverseHost = URLUtils.reverseHost(hostName)
        val reverseHost2 = URLUtils.reverseHost(other.hostName)

        return reverseHost.compareTo(reverseHost2)
    }
}

data class BatchStat(
    var numTasksSuccess: Long = 0,
    var totalSuccessBytes: Long = 0L
) {
    var startTime = Instant.now()
    val elapsedTime get() = Duration.between(startTime, Instant.now())

    val timePerPage get() = elapsedTime.dividedBy(1 + numTasksSuccess)
    val bytesPerPage get() = 1.0 * totalSuccessBytes / (0.1 + numTasksSuccess)
    val pagesPerSecond get() = numTasksSuccess / (0.1 + elapsedTime.seconds)
    val bytesPerSecond get() = 1.0 * totalSuccessBytes / (0.1 + elapsedTime.seconds)
}

class FetchTask constructor(
    val batchId: Int,
    val priority: Int,
    val page: WebPage,
    val fingerprint: Fingerprint,
    val batchSize: Int = 1,
    val batchTaskId: Int = 0,
    var batchStat: BatchStat? = null,
    // The task id
    val id: Int = instanceSequencer.incrementAndGet(),
    var nRetries: Int = 0 // The total number retries in a crawl
) : Comparable<FetchTask> {
    enum class State { NOT_READY, READY, WORKING, CANCELED, DONE }

    val state = AtomicReference(State.NOT_READY)

    var proxyEntry: ProxyEntry? = null
    val createdTime = Instant.now()

    val url get() = page.url
    val href get() = page.href
    val pageConf get() = page.conf
    val domain get() = URLUtils.getTopPrivateDomainOrNull(url) ?: ""
    val isCanceled get() = state.get() == State.CANCELED
    val isWorking get() = state.get() == State.WORKING

    // A task is ready when it about to enter a privacy context
    fun markReady() = state.set(State.READY)

    // A task is working when it enters the web driver
    fun startWork() = state.set(State.WORKING)
    fun cancel() = state.set(State.CANCELED)

    // A task is done if it exits in a privacy context
    fun done() = state.set(State.DONE)

    fun reset() {
        batchStat = null
        proxyEntry = null
        state.set(State.NOT_READY)
    }

    fun clone(): FetchTask {
        return FetchTask(
            batchId = batchId,
            batchTaskId = batchTaskId,
            batchSize = batchSize,
            priority = priority,
            page = page,
            fingerprint = fingerprint,
            nRetries = nRetries
        )
    }

    override fun compareTo(other: FetchTask): Int = id.compareTo(other.id)

    override fun equals(other: Any?): Boolean = other is FetchTask && id == other.id

    override fun hashCode(): Int = id

    override fun toString(): String = "$id"

    companion object {
        val DEFAULT_FINGERPRINT = Fingerprint(BrowserType.PULSAR_CHROME)
        val NIL = FetchTask(0, 0, GoraWebPage.NIL, DEFAULT_FINGERPRINT, id = 0)
        val instanceSequencer = AtomicInteger()

        fun create(url: String, conf: VolatileConfig): FetchTask {
            val page = FetchEntry.createPageShell(url, conf)
            return createFetchTask(page)
        }

        fun create(url: String, options: LoadOptions): FetchTask {
            val page = FetchEntry.createPageShell(url, options)
            return createFetchTask(page)
        }

        fun create(page: WebPage) = createFetchTask(page)

        fun create(page: WebPage, fingerprint: Fingerprint): FetchTask {
            val conf = page.conf
            val priority = conf.getUint(CapabilityTypes.BROWSER_WEB_DRIVER_PRIORITY, 0)
            return FetchTask(0, priority, page, fingerprint = fingerprint)
        }

        private fun createFetchTask(page: WebPage): FetchTask {
            val conf = page.conf
            val priority = conf.getUint(CapabilityTypes.BROWSER_WEB_DRIVER_PRIORITY, 0)
            val browserType = conf.getEnum(CapabilityTypes.BROWSER_TYPE, BrowserType.PULSAR_CHROME)
            val fingerprint = Fingerprint(browserType)
            return FetchTask(0, priority, page, fingerprint = fingerprint)
        }
    }
}

class FetchResult(
    val task: FetchTask,
    var response: Response,
    var exception: Throwable? = null
) {
    operator fun component1() = task
    operator fun component2() = response
    operator fun component3() = exception

    val status get() = response.protocolStatus
    val isSuccess get() = status.isSuccess
    val isPrivacyRetry get() = status.isRetry(RetryScope.PRIVACY)
    val isCrawlRetry get() = status.isRetry(RetryScope.CRAWL)
    val isCanceled get() = status.isCanceled
    val isSmall get() = status.reason.toString() == HtmlIntegrity.TOO_SMALL.toString()

    fun canceled() {
        response = ForwardingResponse.canceled(task.page)
    }

    fun retry(retryScope: RetryScope, reason: String) {
        response = ForwardingResponse.retry(task.page, retryScope, reason)
    }

    fun failed(t: Throwable?) {
        response = ForwardingResponse.failed(task.page, t)
        exception = t
    }

    companion object {
        fun mockSuccess(task: FetchTask) = FetchResult(task, ForwardingResponse.mockSuccess(task.page))
        fun unchanged(task: FetchTask) = FetchResult(task, ForwardingResponse.unchanged(task.page))
        fun unfetched(task: FetchTask) = FetchResult(task, ForwardingResponse.unfetched(task.page))
        fun canceled(task: FetchTask) = FetchResult(task, ForwardingResponse.canceled(task.page))
        fun canceled(task: FetchTask, reason: String) =
            FetchResult(task, ForwardingResponse.canceled(task.page, reason))

        fun retry(task: FetchTask, retryScope: RetryScope, reason: String) =
            FetchResult(task, ForwardingResponse.retry(task.page, retryScope, reason))

        fun privacyRetry(task: FetchTask, reason: String) = retry(task, RetryScope.PRIVACY, reason)
        fun privacyRetry(task: FetchTask, reason: Exception) =
            FetchResult(task, ForwardingResponse.privacyRetry(task.page, reason))

        fun crawlRetry(task: FetchTask, reason: String) =
            FetchResult(task, ForwardingResponse.crawlRetry(task.page, reason))

        fun crawlRetry(task: FetchTask, delay: Duration, message: String) =
            FetchResult(task, ForwardingResponse.crawlRetry(task.page, message)).also { task.page.retryDelay = delay }

        fun crawlRetry(task: FetchTask, reason: Exception) =
            FetchResult(task, ForwardingResponse.crawlRetry(task.page, reason))

        fun crawlRetry(task: FetchTask, delay: Duration, reason: Exception) =
            FetchResult(task, ForwardingResponse.crawlRetry(task.page, reason)).also { task.page.retryDelay = delay }

        fun failed(task: FetchTask, e: Throwable?) = FetchResult(task, ForwardingResponse.failed(task.page, e))
    }
}
