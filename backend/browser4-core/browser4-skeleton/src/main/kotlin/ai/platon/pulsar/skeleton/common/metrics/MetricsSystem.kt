package ai.platon.pulsar.skeleton.common.metrics

import ai.platon.pulsar.common.*
import ai.platon.pulsar.common.chrono.scheduleAtFixedRate
import ai.platon.pulsar.common.config.CapabilityTypes
import ai.platon.pulsar.common.config.ImmutableConfig
import ai.platon.pulsar.skeleton.common.AppSystemInfo
import com.codahale.metrics.*
import com.codahale.metrics.graphite.GraphiteReporter
import com.codahale.metrics.graphite.PickledGraphite
import com.google.common.util.concurrent.ThreadFactoryBuilder
import org.slf4j.LoggerFactory
import java.net.InetSocketAddress
import java.nio.file.Files
import java.time.Duration
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

object MetricFilters {

    fun startsWith(prefix: String) = MetricFilter { name: String, metric -> name.startsWith(prefix) }

    fun endsWith(suffix: String) = MetricFilter { name, metric -> name.endsWith(suffix) }

    fun notEndsWith(suffix: String) = MetricFilter { name, metric -> !name.endsWith(suffix) }

    fun contains(substring: String) = MetricFilter { name, metric -> name.contains(substring) }

    fun notContains(substring: String) = MetricFilter { name, metric -> !name.contains(substring) }

    fun not(filter: MetricFilter) = MetricFilter { name, metric -> !filter.matches(name, metric) }
}

class MultiMetric(
    val counter: Counter,
    val dailyCounter: Counter,
    val hourlyCounter: Counter,
    val meter: Meter
) {
    fun mark() = inc(1)

    fun inc(n: Long) {
        counter.inc(n)
        dailyCounter.inc(n)
        hourlyCounter.inc(n)
        meter.mark(n)
    }

    fun inc(n: Int) = inc(n.toLong())

    /**
     * trigger the meter updating
     * */
    fun update() {
        inc(1)
        inc(-1)
    }

    /**
     * Reset the counters
     * */
    fun reset() {
        inc(-counter.count)
    }
}

open class MetricsSystem(
    conf: ImmutableConfig
) : AutoCloseable {
    companion object {
        init {
            // Spring boot do not support the companion object initialization, so we disable it.
            // Spring boot creates delegate classes which are not the same as the original class.
//            if (SharedMetricRegistries.tryGetDefault() == null) {
//                SharedMetricRegistries.setDefault(AppConstants.DEFAULT_METRICS_NAME, AppMetricRegistry())
//            }
        }

        /**
         * A shadow metric should not be displayed, nor be stored.
         * */
        const val SHADOW_METRIC_SYMBOL = "._."

        // Spring boot do not support the object initialization, so we disable it
//        val defaultMetricRegistry = SharedMetricRegistries.getDefault() as AppMetricRegistry
//        val reg = defaultMetricRegistry

        /**
         * The default metric registry
         * */
        val defaultMetricRegistry = AppMetricRegistry()

        // a shortcut to the default metric registry
        val reg = defaultMetricRegistry

        init {
            mapOf(
                "startTime" to Gauge { AppSystemInfo.startTime },
                "elapsedTime" to Gauge { AppSystemInfo.elapsedTime },
                "availableMemory" to Gauge { formatAvailableMemoryGauge() },
                "freeSpace" to Gauge { formatFreeSpaceGauge() }
            ).let { reg.registerAll(this, it) }
        }

        private fun formatAvailableMemoryGauge(): String {
            return AppSystemInfo.availableMemory?.runCatching { Strings.compactFormat(this) }?.getOrNull()
                ?: "Not available"
        }

        private fun formatFreeSpaceGauge(): List<String> {
            return AppSystemInfo.freeDiskSpaces.map { Strings.compactFormat(it) }
        }
    }

    private val logger = LoggerFactory.getLogger(MetricsSystem::class.java)
    private val timeIdent = DateTimes.formatNow("MMdd")
    private val isEnabled = conf.getBoolean(CapabilityTypes.METRICS_ENABLED, false)
    private val jobIdent = conf[CapabilityTypes.PARAM_JOB_NAME, DateTimes.now("HHmm")]
    private val reportDir = AppPaths.METRICS_DIR.resolve(timeIdent).resolve(jobIdent)

    val name = AppContext.APP_NAME
    val initialDelay = conf.getDuration("metrics.report.initial.delay", Duration.ofMinutes(3))
    val csvReportInterval = conf.getDuration("metrics.csv.report.interval", Duration.ofMinutes(5))
    val slf4jReportInterval = conf.getDuration("metrics.slf4j.report.interval", Duration.ofMinutes(2))
    val graphiteReportInterval = conf.getDuration("metrics.graphite.report.interval", Duration.ofMinutes(2))
    val counterReportInterval = conf.getDuration("metrics.counter.report.interval", Duration.ofSeconds(30))
    val graphiteServer = conf.get("graphite.server", "crawl2")
    val graphiteServerPort = conf.getInt("graphite.server.port", 2004)
    val batchSize = conf.getInt("graphite.pickled.batch.size", 100)

    private val metricRegistry = defaultMetricRegistry

    private val threadFactory = ThreadFactoryBuilder().setNameFormat("reporter-%d").build()
    private val executor = Executors.newSingleThreadScheduledExecutor(threadFactory)

    //    private val jmxReporter: JmxReporter = JmxReporter.forRegistry(metricRegistry)
//        .filter(MetricFilters.notContains(SHADOW_METRIC_SYMBOL))
//        .build()
//    private val csvReporter: CsvReporter = CsvReporter.forRegistry(metricRegistry)
//        .convertRatesTo(TimeUnit.SECONDS)
//        .convertDurationsTo(TimeUnit.MILLISECONDS)
//        .build(reportDir.toFile())
    private val slf4jReporter = CodahaleSlf4jReporter.forRegistry(metricRegistry)
        .scheduleOn(executor).shutdownExecutorOnStop(true)
        .outputTo(LoggerFactory.getLogger(MetricsSystem::class.java))
        .convertRatesTo(TimeUnit.SECONDS)
        .convertDurationsTo(TimeUnit.MILLISECONDS)
        .filter(MetricFilters.notContains(SHADOW_METRIC_SYMBOL))
        .build()
    private val pickledGraphite
        get() = graphiteServer.takeIf { NetUtil.testNetwork(it, graphiteServerPort) }
            ?.let { PickledGraphite(InetSocketAddress(it, graphiteServerPort), batchSize) }
    private var graphiteReporter: GraphiteReporter? = pickledGraphite?.let { pickled ->
        GraphiteReporter.forRegistry(metricRegistry)
            .prefixedWith(name)
            .convertRatesTo(TimeUnit.SECONDS)
            .convertDurationsTo(TimeUnit.MILLISECONDS)
            .filter(MetricFilters.notContains(SHADOW_METRIC_SYMBOL))
            .build(pickled)
    }
    private val hourlyTimer = java.util.Timer("MetricHourly", true)
    private val dailyTimer = java.util.Timer("MetricDaily", true)

    private val closed = AtomicBoolean()

    init {
        Files.createDirectories(reportDir)
    }

    fun inc(count: Int, vararg counters: Enum<*>) {
        counters.forEach {
            metricRegistry.enumCounters[it]?.inc(count.toLong())
        }
    }

    fun inc(vararg counters: Enum<*>) {
        inc(count = 1, counters = counters)
    }

    open fun start() {
        if (isEnabled) {
            // jmxReporter.start()
            // csvReporter.start(initialDelay.seconds, csvReportInterval.seconds, TimeUnit.SECONDS)
            slf4jReporter.start(initialDelay.seconds, slf4jReportInterval.seconds, TimeUnit.SECONDS)

            if (NetUtil.testNetwork(graphiteServer, graphiteServerPort)) {
                graphiteReporter?.start(initialDelay.seconds, graphiteReportInterval.seconds, TimeUnit.SECONDS)
                logger.info("GraphiteReporter is started, report interval: {}", graphiteReportInterval)
            }

            val now = LocalDateTime.now()
            var delay = Duration.between(now, now.plusHours(1).truncatedTo(ChronoUnit.HOURS))
            hourlyTimer.scheduleAtFixedRate(delay, Duration.ofHours(1)) { reg.resetHourlyCounters() }

            delay = Duration.between(now, now.plusDays(1).truncatedTo(ChronoUnit.DAYS))
            dailyTimer.scheduleAtFixedRate(delay, Duration.ofDays(1)) { reg.resetDailyCounters() }
        }
    }

    /**
     * Close the metrics system.
     *
     * Note: this object is closed by spring framework, but some reporter will report for the last time before close,
     * and the last report may throw exceptions if some metrics depends on the creation of spring beans.
     * */
    override fun close() {
        if (isEnabled && closed.compareAndSet(false, true)) {
            runCatching { doClose() }.onFailure { warnForClose(this, it) }
        }
    }

    private fun doClose() {
        hourlyTimer.cancel()
        dailyTimer.cancel()

//            csvReporter.close()
        slf4jReporter.close()
//            jmxReporter.close()
        graphiteReporter?.close()
        graphiteReporter = null
    }
}
