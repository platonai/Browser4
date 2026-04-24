package ai.platon.pulsar.skeleton

import ai.platon.pulsar.common.*
import ai.platon.pulsar.common.AppPaths.PATH_UNREACHABLE_HOSTS
import ai.platon.pulsar.common.chrono.scheduleAtFixedRate
import ai.platon.pulsar.common.config.CapabilityTypes.FETCH_MAX_HOST_FAILURES
import ai.platon.pulsar.common.config.CapabilityTypes.PARSE_MAX_URL_LENGTH
import ai.platon.pulsar.common.config.ImmutableConfig
import ai.platon.pulsar.common.config.Parameterized
import ai.platon.pulsar.common.config.Params
import ai.platon.pulsar.common.emoji.PopularEmoji
import ai.platon.pulsar.common.measure.ByteUnitConverter
import ai.platon.pulsar.persist.WebDb
import ai.platon.pulsar.persist.WebPage
import ai.platon.pulsar.skeleton.common.AppSystemInfo
import ai.platon.pulsar.skeleton.common.message.MiscMessageWriter
import ai.platon.pulsar.skeleton.common.metrics.MetricsSystem
import ai.platon.pulsar.skeleton.session.AbstractPulsarSession
import ai.platon.pulsar.skeleton.workflow.common.InternalURLUtil
import ai.platon.pulsar.skeleton.workflow.component.LoadComponent
import ai.platon.pulsar.skeleton.workflow.component.ParseComponent
import ai.platon.pulsar.skeleton.workflow.fetch.UrlStat
import ai.platon.pulsar.skeleton.workflow.parse.html.JsoupParser
import com.codahale.metrics.Gauge
import com.google.common.collect.ConcurrentHashMultiset
import org.slf4j.LoggerFactory
import java.net.MalformedURLException
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentSkipListSet
import java.util.concurrent.atomic.AtomicBoolean

class CoreMetrics(
    private val messageWriter: MiscMessageWriter,
    conf: ImmutableConfig
) : Parameterized, AutoCloseable {

    companion object {
        var runningChromeProcesses = 0
        var usedMemory = 0L
        var cpuLoad = 0.0

        init {
            mapOf(
                "version" to Gauge { AppContext.APP_VERSION },
                "runningChromeProcesses" to Gauge { runningChromeProcesses },
                "usedMemory" to Gauge { Strings.compactFormat(usedMemory) },
                "cpuLoad" to Gauge { String.format("%.2f", cpuLoad) },

                "pulsarSessionPageCacheHits" to Gauge { AbstractPulsarSession.pageCacheHits },
                "pulsarSessionPageCacheHits/s" to Gauge { 1.0 * AbstractPulsarSession.pageCacheHits.get() / DateTimes.elapsedSeconds() },
                "pulsarSessionDocumentCacheHits" to Gauge { AbstractPulsarSession.documentCacheHits },
                "pulsarSessionDocumentCacheHits/s" to Gauge { 1.0 * AbstractPulsarSession.documentCacheHits.get() / DateTimes.elapsedSeconds() },

                "parses" to Gauge { ParseComponent.numParses.get() },
                "parses/s" to Gauge { 1.0 * ParseComponent.numParses.get() / DateTimes.elapsedSeconds() },
                "jsoupParses" to Gauge { JsoupParser.numJsoupParses.get() },
                "jsoupParses/s" to Gauge { 1.0 * JsoupParser.numJsoupParses.get() / DateTimes.elapsedSeconds() },

                "loadCompPageCacheHits" to Gauge { LoadComponent.pageCacheHits },
                "loadCompPageCacheHits/s" to Gauge { 1.0 * LoadComponent.pageCacheHits.get() / DateTimes.elapsedSeconds() },
                "loadCompDbGets" to Gauge { LoadComponent.dbGetCount },
                "loadCompDbGets/s" to Gauge { 1.0 * LoadComponent.dbGetCount.get() / DateTimes.elapsedSeconds() },

                // TODO: dbGets/dbPuts should be a multiMetric
                "dbGets" to Gauge { WebDb.dbGetCount },
                "dbGets/s" to Gauge { 1.0 * WebDb.dbGetCount.get() / DateTimes.elapsedSeconds() },
                "dbGetAveMillis" to Gauge { WebDb.dbGetAveMillis },
                "dbPuts" to Gauge { WebDb.dbPutCount },
                "dbPuts/s" to Gauge { 1.0 * WebDb.dbPutCount.get() / DateTimes.elapsedSeconds() },
                "dbPutAveMillis" to Gauge { WebDb.dbPutAveMillis },
            ).forEach { MetricsSystem.reg.register(this, it.key, it.value) }
        }
    }

    private val logger = LoggerFactory.getLogger(CoreMetrics::class.java)!!
    val groupMode = InternalURLUtil.GroupMode.BY_HOST
    val maxHostFailureEvents = conf.getInt(FETCH_MAX_HOST_FAILURES, 20)

    /**
     * The limitation of url length
     */
    private var maxUrlLength: Int = conf.getInt(PARSE_MAX_URL_LENGTH, 1024)

    /**
     * The start time of the program process
     */
    val startTime = Instant.now()

    /**
     * The elapsed time since the program process starts
     */
    val elapsedTime get() = Duration.between(startTime, Instant.now())

    /**
     * The elapsed time in seconds since the program process starts
     */
    val elapsedSeconds get() = elapsedTime.seconds.coerceAtLeast(1)

    /**
     * Tracking statistics for each host
     */
    val urlStatistics = ConcurrentHashMap<String, UrlStat>()

    /**
     * Tracking unreachable hosts
     */
    val unreachableHosts = ConcurrentSkipListSet<String>()

    /**
     * Tracking hosts who are failed to fetch tasks.
     * A host is considered to be an unreachable host if there are too many failure
     */
    val timeoutUrls = ConcurrentSkipListSet<String>()
    val movedUrls = ConcurrentSkipListSet<String>()
    val failedUrls = ConcurrentSkipListSet<String>()
    val deadUrls = ConcurrentSkipListSet<String>()
    val failedHosts = ConcurrentHashMultiset.create<String>()

    private val registry = MetricsSystem.reg

    val meterTotalNetworkIFsRecvMBytes = registry.meter(this, "totalNetworkIFsRecvMBytes")

    /**
     * The total bytes of page content of all success web pages
     * */
    val fetchTasks = registry.meter(this, "fetchTasks")
    val successFetchTasks = registry.meter(this, "successFetchTasks")
    val finishedFetchTasks = registry.meter(this, "finishedFetchTasks")

    val proxies = registry.meter(this, "proxies")

    val persists = registry.multiMetric(this, "persists")
    val contentPersists = registry.multiMetric(this, "contentPersists")
    val meterContentMBytes = registry.multiMetric(this, "contentBytes")
    val persistContentMBytes = registry.multiMetric(this, "persistContentMBytes")
    val meterContentBytes = registry.meter(this, "contentBytes")

//    val dbGets = registry.multiMetric(this, "dbGets")
//    val dbPuts = registry.multiMetric(this, "dbPuts")

    val histogramContentBytes = registry.histogram(this, "contentBytes")
    val pageImages = registry.histogram(this, "pageImages")
    val pageAnchors = registry.histogram(this, "pageAnchors")
    val pageNumbers = registry.histogram(this, "pageNumbers")
    val pageSmallTexts = registry.histogram(this, "pageSmallTexts")
    val pageHeights = registry.histogram(this, "pageHeights")

    val realTimeNetworkIFsRecvBytes get() = AppSystemInfo.networkIFsReceivedBytes()

    /**
     * The total all bytes received by the hardware at the application startup
     * */
    val initNetworkIFsRecvBytes by lazy { realTimeNetworkIFsRecvBytes }

    /**
     * The total all bytes received by the hardware last read from system
     * */
    var totalNetworkIFsRecvBytes = 0L

    /**
     * The total bytes received by the hardware from the application startup
     * */
    val networkIFsRecvBytes
        get() = (totalNetworkIFsRecvBytes - initNetworkIFsRecvBytes).coerceAtLeast(0L)
    val networkIFsRecvBytesPerSecond get() = networkIFsRecvBytes / elapsedSeconds
    val networkIFsRecvBytesPerPage get() = networkIFsRecvBytes / successFetchTasks.count

    val fetchTasksPerSecond get() = fetchTasks.count.toFloat() / elapsedSeconds
    val finishedFetchTasksPerSecond get() = finishedFetchTasks.count.toFloat() / elapsedSeconds
    val successFetchTasksPerSecond get() = successFetchTasks.count.toFloat() / elapsedSeconds

    var enableReporter = true
    var lastTaskTime = Instant.EPOCH
        private set
    var lastReportTime = Instant.EPOCH
        private set

    private var lastSystemInfoRefreshTime = 0L
    private var reportTimer: Timer? = null
    private val closed = AtomicBoolean()

    init {
        runCatching {
            if (Files.exists(PATH_UNREACHABLE_HOSTS)) {
                Files.readAllLines(PATH_UNREACHABLE_HOSTS).toCollection(unreachableHosts)
            }
        }.onFailure { warnInterruptible(this, it) }

        // params.withLogger(logger).info(true)
    }

    override fun getParams(): Params {
        return Params.of(
            "maxUrlLength", maxUrlLength,
            "timeoutUrls", timeoutUrls.size,
            "failedUrls", failedUrls.size,
            "deadUrls", deadUrls.size
        )
    }

    fun start() {
        if (enableReporter) {
            startReporter()
        }
    }

    fun isReachable(url: String) = !isUnreachable(url)

    fun isUnreachable(url: String): Boolean {
        val host = InternalURLUtil.getHost(url, groupMode) ?: return true
        return unreachableHosts.contains(host)
    }

    fun isFailed(url: String) = failedUrls.contains(url)

    fun trackFailedUrl(url: String) {
        failedUrls.add(url)
    }

    fun trackFailedUrls(urls: Collection<String>) {
        failedUrls.addAll(urls)
    }

    fun isTimeout(url: String): Boolean {
        return timeoutUrls.contains(url)
    }

    fun markFetchTaskStart() {
        markFetchTaskStart(1)
    }

    fun markFetchTaskStart(size: Int) {
        lastTaskTime = Instant.now()
        fetchTasks.mark(size.toLong())
    }

    /**
     * Available hosts statistics
     */
    fun trackSuccess(page: WebPage) {
        val url = page.url
        val host = InternalURLUtil.getHost(url, groupMode) ?: throw MalformedURLException(url)

        // The host is reachable
        unreachableHosts.remove(host)
        failedHosts.remove(host)

        successFetchTasks.mark()
        finishedFetchTasks.mark()

        // update system info for about every 30 seconds
        if (finishedFetchTasks.count % 30 == 0L) {
            updateSystemInfo()
        }

        val bytes = page.contentLength
        histogramContentBytes.update(bytes)
        meterContentBytes.mark(bytes)
        meterContentMBytes.inc(ByteUnitConverter.convert(bytes, "M").toLong())

        page.activeDOMStatTrace["lastStat"]?.apply {
            pageAnchors.update(na)
            pageImages.update(ni)
            pageNumbers.update(nnm)
            pageSmallTexts.update(nst)
            pageHeights.update(h)
        }

        val urlStats = urlStatistics.computeIfAbsent(host) { UrlStat(it) }

        ++urlStats.urls
        ++urlStats.pageViews

        val pageCategory = page.pageCategory.toPageCategory()
        when {
            pageCategory.isIndex -> ++urlStats.indexUrls
            pageCategory.isDetail -> ++urlStats.detailUrls
            pageCategory.isMedia -> ++urlStats.mediaUrls
            pageCategory.isSearch -> ++urlStats.searchUrls
            pageCategory.isBBS -> ++urlStats.bbsUrls
            pageCategory.isTieBa -> ++urlStats.tiebaUrls
            pageCategory.isBlog -> ++urlStats.blogUrls
            pageCategory.isUnknown -> ++urlStats.unknownUrls
        }

        val depth = page.distance
        if (depth == 1) {
            ++urlStats.urlsFromSeed
        }

        if (url.length > maxUrlLength) {
            ++urlStats.urlsTooLong
            messageWriter.debugLongUrls(url)
        }

        urlStatistics[host] = urlStats

        if (finishedFetchTasksPerSecond > 0.5) {
            reportSuccessTasks()
        }
    }

    fun trackTimeout(url: String) {
        timeoutUrls.add(url)
    }

    fun trackMoved(url: String) {
        movedUrls.add(url)
    }

    /**
     * @param url The url
     * @return true if the host is unreachable
     */
    fun trackHostUnreachable(url: String, occurrences: Int = 1): Boolean {
        val host = InternalURLUtil.getHost(url, groupMode)
        if (host.isNullOrEmpty()) {
            logger.warn("Malformed url identified as gone | <{}>", url)
            return false
        }

        // special hosts
        if (host.contains("amazon.com")) {
            return false
        }

        if (unreachableHosts.contains(host)) {
            return false
        }

        failedHosts.add(host, occurrences)
        val failures = failedHosts.count(host)
        // Only the exception occurs for unknownHostEventCount, it's really add to the black list
        if (failures > maxHostFailureEvents) {
            unreachableHosts.add(host)
            logger.info("Host unreachable | {} failures | {}", failures, host)
            return true
        }

        return false
    }

    fun countHostTasks(host: String): Int {
        val failedTasks = failedHosts.count(host)
        val (_, numUrls) = urlStatistics[host] ?: return failedTasks
        return numUrls + failedTasks
    }

    private fun logSuccess() {
        if (successFetchTasks.count == 0L) {
            return
        }

        val seconds = elapsedSeconds.coerceAtLeast(1)
        val count = successFetchTasks.count.coerceAtLeast(1)
        val bytes = meterContentBytes.count
        val proxyFmt = if (proxies.count > 0) " using %s proxies" else ""
        val symbol = PopularEmoji.DELIVERY_TRUCK
        val format = "%d pages in %s(%.2f pages/s) successfully$proxyFmt | content: %s, %s/s, %s/p"
        // format += " | net recv: %s, %s/s, %s/p | total net recv: %s"
        val message = String.format(
            format,
            count,
            elapsedTime.readable(),
            successFetchTasks.meanRate,
            proxies.count,
            Strings.compactFormat(bytes),
            Strings.compactFormat(bytes / seconds),
            Strings.compactFormat(bytes / count),
            Strings.compactFormat(networkIFsRecvBytes),
            Strings.compactFormat(networkIFsRecvBytesPerSecond),
            Strings.compactFormat(networkIFsRecvBytesPerPage),
            Strings.compactFormat(totalNetworkIFsRecvBytes)
        )

        // keep some text in the line, so IDE like idea can track to the line.
        logger.info("$symbol Fetched $message")
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            reportTimer?.cancel()
            reportTimer = null

            if (successFetchTasks.count > 0) {
                logSuccess()
            }

            if (fetchTasks.count > 0) {
                logAvailableHosts()
            }

            if (unreachableHosts.isNotEmpty()) {
                logger.debug("There are " + unreachableHosts.size + " unreachable hosts")
                AppFiles.logUnreachableHosts(unreachableHosts)
            }
        }
    }

    fun updateSystemInfo() {
        runCatching { updateSystemInfo0() }.onFailure { warnInterruptible(this, it) }
    }

    @Throws(Exception::class)
    private fun updateSystemInfo0() {
        val currentTimeMillis = System.currentTimeMillis()
        if (Duration.ofMillis(currentTimeMillis - lastSystemInfoRefreshTime).seconds < 60) {
            return
        }
        lastSystemInfoRefreshTime = currentTimeMillis

        totalNetworkIFsRecvBytes = AppSystemInfo.networkIFsReceivedBytes().coerceAtLeast(totalNetworkIFsRecvBytes)
        meterTotalNetworkIFsRecvMBytes.mark(totalNetworkIFsRecvBytes / 1024 / 1024)

        runningChromeProcesses = Runtimes.countSystemProcess("chrome")
        usedMemory = AppSystemInfo.usedMemory ?: 0
        cpuLoad = AppSystemInfo.systemCpuLoad
    }

    private fun startReporter() {
        if (reportTimer == null) {
            reportTimer = Timer("CoreMetrics", true)
            val delay = Duration.ofMinutes(1)
            reportTimer?.scheduleAtFixedRate(delay, Duration.ofMinutes(1)) { reportSuccessTasks() }
        }
    }

    private fun reportSuccessTasks() {
        if (!logger.isInfoEnabled) {
            return
        }

        if (successFetchTasks.count == 0L) {
            return
        }

        val now = Instant.now()
        if (Duration.between(lastReportTime, now).seconds < 60) {
            return
        }
        lastReportTime = Instant.now()

        if (finishedFetchTasksPerSecond > 0.5) {
            reportWhenHighThroughput()
        } else {
            reportWhenLowThroughput()
        }
    }

    private fun reportWhenLowThroughput() {
        val now = Instant.now()
        if (Duration.between(lastTaskTime, now).seconds > 8 * 60) {
            // the system is idle
            return
        }

        logSuccess()
    }

    private fun reportWhenHighThroughput() {
        val i = finishedFetchTasks.count
        // successTasksPerSecond is about to be 1.0
        val a = successFetchTasksPerSecond * 60
        val period = 10 + when {
            i < 100 -> a / 3
            i < 10000 -> a
            else -> a * 2 + 60 * (i % 3 - 1) // generate: 60, 120, 180, 60, 120, 180, ...
        }.toInt()

        if (i % period == 0L) {
            logSuccess()
        } else if (DateTimes.elapsedTime(lastReportTime).seconds > 4 * 60) {
            // have to report every 4 minutes
            logSuccess()
        }
    }

    private fun logAvailableHosts() {
        val report = StringBuilder("Total " + urlStatistics.size + " available hosts")
        report.append('\n')

        urlStatistics.values.sorted().map { (hostName, urls, indexUrls, detailUrls) ->
            String.format(
                "%40s -> %-15s %-15s %-15s",
                hostName,
                "total : $urls",
                "index : $indexUrls",
                "detail : $detailUrls"
            )
        }.joinTo(report, "\n") { it }

        logger.info(report.toString())
    }
}
