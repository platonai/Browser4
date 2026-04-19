package ai.platon.pulsar.skeleton.common.metrics

import com.codahale.metrics.*
import com.codahale.metrics.Histogram
import com.codahale.metrics.Timer
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.slf4j.Marker
import java.util.*
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.function.Supplier

/**
 * A reporter class for logging metrics values to a SLF4J [Logger] periodically, similar to
 * [ConsoleReporter] or [CsvReporter], but using the SLF4J framework instead. It also
 * supports specifying a [Marker] instance that can be used by custom appenders and filters
 * for the bound logging toolkit to further process metrics reports.
 */
class CodahaleSlf4jReporter private constructor(registry: MetricRegistry,
                                                private val loggerProxy: LoggerProxy,
                                                private val marker: Marker?,
                                                private val prefix: String,
                                                rateUnit: TimeUnit,
                                                durationUnit: TimeUnit,
                                                filter: MetricFilter,
                                                executor: ScheduledExecutorService?,
                                                shutdownExecutorOnStop: Boolean,
                                                disabledMetricAttributes: Set<MetricAttribute>
) : ScheduledReporter(registry, "logger-reporter",
        filter, rateUnit, durationUnit, executor, shutdownExecutorOnStop, disabledMetricAttributes) {
    enum class LoggingLevel {
        TRACE, DEBUG, INFO, WARN, ERROR
    }

    /**
     * A builder for [Slf4jReporter] instances. Defaults to logging to `metrics`, not
     * using a marker, converting rates to events/second, converting durations to milliseconds, and
     * not filtering metrics.
     */
    class Builder(private val registry: MetricRegistry) {
        private var logger = LoggerFactory.getLogger("metrics")
        private var loggingLevel = LoggingLevel.INFO
        private var marker: Marker? = null
        private var prefix = ""
        private var rateUnit = TimeUnit.SECONDS
        private var durationUnit = TimeUnit.MILLISECONDS
        private var filter = MetricFilter.ALL
        private var executor: ScheduledExecutorService? = null
        private var shutdownExecutorOnStop = true
        private var disabledMetricAttributes: Set<MetricAttribute> = setOf()

        /**
         * Specifies whether or not, the executor (used for reporting) will be stopped with same time with reporter.
         * Default value is true.
         * Setting this parameter to false, has the sense in combining with providing external managed executor via [.scheduleOn].
         *
         * @param shutdownExecutorOnStop if true, then executor will be stopped in same time with this reporter
         * @return `this`
         */
        fun shutdownExecutorOnStop(shutdownExecutorOnStop: Boolean): Builder {
            this.shutdownExecutorOnStop = shutdownExecutorOnStop
            return this
        }

        /**
         * Specifies the executor to use while scheduling reporting of metrics.
         * Default value is null.
         * Null value leads to executor will be auto created on start.
         *
         * @param executor the executor to use while scheduling reporting of metrics.
         * @return `this`
         */
        fun scheduleOn(executor: ScheduledExecutorService?): Builder {
            this.executor = executor
            return this
        }

        /**
         * Log metrics to the given logger.
         *
         * @param logger an SLF4J [Logger]
         * @return `this`
         */
        fun outputTo(logger: Logger): Builder {
            this.logger = logger
            return this
        }

        /**
         * Mark all logged metrics with the given marker.
         *
         * @param marker an SLF4J [Marker]
         * @return `this`
         */
        fun markWith(marker: Marker?): Builder {
            this.marker = marker
            return this
        }

        /**
         * Prefix all metric names with the given string.
         *
         * @param prefix the prefix for all metric names
         * @return `this`
         */
        fun prefixedWith(prefix: String): Builder {
            this.prefix = prefix
            return this
        }

        /**
         * Convert rates to the given time unit.
         *
         * @param rateUnit a unit of time
         * @return `this`
         */
        fun convertRatesTo(rateUnit: TimeUnit): Builder {
            this.rateUnit = rateUnit
            return this
        }

        /**
         * Convert durations to the given time unit.
         *
         * @param durationUnit a unit of time
         * @return `this`
         */
        fun convertDurationsTo(durationUnit: TimeUnit): Builder {
            this.durationUnit = durationUnit
            return this
        }

        /**
         * Only report metrics which match the given filter.
         *
         * @param filter a [MetricFilter]
         * @return `this`
         */
        fun filter(filter: MetricFilter): Builder {
            this.filter = filter
            return this
        }

        /**
         * Use Logging Level when reporting.
         *
         * @param loggingLevel a (@link Slf4jReporter.LoggingLevel}
         * @return `this`
         */
        fun withLoggingLevel(loggingLevel: LoggingLevel): Builder {
            this.loggingLevel = loggingLevel
            return this
        }

        /**
         * Don't report the passed metric attributes for all metrics (e.g. "p999", "stddev" or "m15").
         * See [MetricAttribute].
         *
         * @param disabledMetricAttributes a set of [MetricAttribute]
         * @return `this`
         */
        fun disabledMetricAttributes(disabledMetricAttributes: Set<MetricAttribute>): Builder {
            this.disabledMetricAttributes = disabledMetricAttributes
            return this
        }

        /**
         * Builds a [Slf4jReporter] with the given properties.
         *
         * @return a [Slf4jReporter]
         */
        fun build(): CodahaleSlf4jReporter {
            val loggerProxy = when (loggingLevel) {
                LoggingLevel.TRACE -> TraceLoggerProxy(logger)
                LoggingLevel.INFO -> InfoLoggerProxy(logger)
                LoggingLevel.WARN -> WarnLoggerProxy(logger)
                LoggingLevel.ERROR -> ErrorLoggerProxy(logger)
                LoggingLevel.DEBUG -> DebugLoggerProxy(logger)
            }

            return CodahaleSlf4jReporter(registry, loggerProxy, marker, prefix, rateUnit, durationUnit, filter, executor,
                    shutdownExecutorOnStop, disabledMetricAttributes)
        }
    }

    override fun report(gauges: SortedMap<String, Gauge<*>>,
                        counters: SortedMap<String, Counter>,
                        histograms: SortedMap<String, Histogram>,
                        meters: SortedMap<String, Meter>,
                        timers: SortedMap<String, Timer>) {
        if (loggerProxy.isEnabled(marker)) {
            val b = StringBuilder()
            for ((key, value) in gauges) {
                logGauge(b, key, value)
            }
            for ((key, value) in counters) {
                logCounter(b, key, value)
            }
            for ((key, value) in histograms) {
                logHistogram(b, key, value)
            }
            for ((key, value) in meters) {
                logMeter(b, key, value)
            }
            for ((key, value) in timers) {
                logTimer(b, key, value)
            }
        }
    }

    private fun logTimer(b: StringBuilder, name: String, timer: Timer) {
        val snapshot = timer.snapshot
        b.setLength(0)
        b.append("[TIMER] ").append(prefix(name)).append(" | ")
        appendCountIfEnabled(b, timer)
        appendLongDurationIfEnabled(b, MetricAttribute.MIN, Supplier { snapshot.min })
        appendLongDurationIfEnabled(b, MetricAttribute.MAX, Supplier { snapshot.max })
        appendDoubleDurationIfEnabled(b, MetricAttribute.MEAN, Supplier { snapshot.mean })
        appendDoubleDurationIfEnabled(b, MetricAttribute.STDDEV, Supplier { snapshot.stdDev })
        appendDoubleDurationIfEnabled(b, MetricAttribute.P50, Supplier { snapshot.median })
        appendDoubleDurationIfEnabled(b, MetricAttribute.P75, Supplier { snapshot.get75thPercentile() })
        appendDoubleDurationIfEnabled(b, MetricAttribute.P95, Supplier { snapshot.get95thPercentile() })
        appendDoubleDurationIfEnabled(b, MetricAttribute.P98, Supplier { snapshot.get98thPercentile() })
        appendDoubleDurationIfEnabled(b, MetricAttribute.P99, Supplier { snapshot.get99thPercentile() })
        appendDoubleDurationIfEnabled(b, MetricAttribute.P999, Supplier { snapshot.get999thPercentile() })
        appendMetered(b, timer)
        append(b, "rate_unit", rateUnit)
        append(b, "duration_unit", durationUnit)
        loggerProxy.log(marker, b.toString())
    }

    private fun logMeter(b: StringBuilder, name: String, meter: Meter) {
        b.setLength(0)
        b.append("[METER] ").append(prefix(name)).append(" | ")
        appendCountIfEnabled(b, meter)
        appendMetered(b, meter)
        append(b, "rate_unit", rateUnit)
        loggerProxy.log(marker, b.toString())
    }

    private fun logHistogram(b: StringBuilder, name: String, histogram: Histogram) {
        val snapshot = histogram.snapshot
        b.setLength(0)
        b.append("[HISTOGRAM] ").append(prefix(name)).append(" | ")
        appendCountIfEnabled(b, histogram)
        appendLongIfEnabled(b, MetricAttribute.MIN, Supplier { snapshot.min })
        appendLongIfEnabled(b, MetricAttribute.MAX, Supplier { snapshot.max })
        appendDoubleIfEnabled(b, MetricAttribute.MEAN, Supplier { snapshot.mean })
        appendDoubleIfEnabled(b, MetricAttribute.STDDEV, Supplier { snapshot.stdDev })
        appendDoubleIfEnabled(b, MetricAttribute.P50, Supplier { snapshot.median })
        appendDoubleIfEnabled(b, MetricAttribute.P75, Supplier { snapshot.get75thPercentile() })
        appendDoubleIfEnabled(b, MetricAttribute.P95, Supplier { snapshot.get95thPercentile() })
        appendDoubleIfEnabled(b, MetricAttribute.P98, Supplier { snapshot.get98thPercentile() })
        appendDoubleIfEnabled(b, MetricAttribute.P99, Supplier { snapshot.get99thPercentile() })
        appendDoubleIfEnabled(b, MetricAttribute.P999, Supplier { snapshot.get999thPercentile() })
        loggerProxy.log(marker, b.toString())
    }

    private fun logCounter(b: StringBuilder, name: String, counter: Counter) {
        b.setLength(0)
        b.append("[COUNTER] ").append(prefix(name)).append(" | ")
        append(b, MetricAttribute.COUNT.code, counter.count)
        loggerProxy.log(marker, b.toString())
    }

    private fun logGauge(b: StringBuilder, name: String, gauge: Gauge<*>) {
        b.setLength(0)
        b.append("[GAUGE] ").append(prefix(name)).append(" | ")
        append(b, "value", gauge.value)
        loggerProxy.log(marker, b.toString())
    }

    private fun appendLongDurationIfEnabled(b: StringBuilder, metricAttribute: MetricAttribute,
                                            durationSupplier: Supplier<Long>) {
        if (!disabledMetricAttributes.contains(metricAttribute)) {
            append(b, metricAttribute.code, convertDuration(durationSupplier.get().toDouble()))
        }
    }

    private fun appendDoubleDurationIfEnabled(b: StringBuilder, metricAttribute: MetricAttribute,
                                              durationSupplier: Supplier<Double>) {
        if (!disabledMetricAttributes.contains(metricAttribute)) {
            append(b, metricAttribute.code, convertDuration(durationSupplier.get()))
        }
    }

    private fun appendLongIfEnabled(b: StringBuilder, metricAttribute: MetricAttribute,
                                    valueSupplier: Supplier<Long>) {
        if (!disabledMetricAttributes.contains(metricAttribute)) {
            append(b, metricAttribute.code, valueSupplier.get())
        }
    }

    private fun appendDoubleIfEnabled(b: StringBuilder, metricAttribute: MetricAttribute,
                                      valueSupplier: Supplier<Double>) {
        if (!disabledMetricAttributes.contains(metricAttribute)) {
            append(b, metricAttribute.code, valueSupplier.get())
        }
    }

    private fun appendCountIfEnabled(b: StringBuilder, counting: Counting) {
        if (!disabledMetricAttributes.contains(MetricAttribute.COUNT)) {
            append(b, MetricAttribute.COUNT.code, counting.count)
        }
    }

    private fun appendMetered(b: StringBuilder, meter: Metered) {
        appendRateIfEnabled(b, MetricAttribute.M1_RATE, Supplier { meter.oneMinuteRate })
        appendRateIfEnabled(b, MetricAttribute.M5_RATE, Supplier { meter.fiveMinuteRate })
        appendRateIfEnabled(b, MetricAttribute.M15_RATE, Supplier { meter.fifteenMinuteRate })
        appendRateIfEnabled(b, MetricAttribute.MEAN_RATE, Supplier { meter.meanRate })
    }

    private fun appendRateIfEnabled(b: StringBuilder, metricAttribute: MetricAttribute, rateSupplier: Supplier<Double>) {
        if (!disabledMetricAttributes.contains(metricAttribute)) {
            append(b, metricAttribute.code, convertRate(rateSupplier.get()))
        }
    }

    private fun append(b: StringBuilder, key: String, value: Long) {
        b.append(" ").append(key).append('=').append(value)
    }

    private fun append(b: StringBuilder, key: String, value: Double) {
        b.append(" ").append(key).append('=').append(String.format("%.4f", value))
    }

    private fun append(b: StringBuilder, key: String, value: String) {
        b.append(" ").append(key).append('=').append(value)
    }

    private fun append(b: StringBuilder, key: String, value: Any) {
        b.append(" ").append(key).append('=').append(value)
    }

    override fun getRateUnit(): String {
        return "events/" + super.getRateUnit()
    }

    private fun prefix(vararg components: String): String {
        return MetricRegistry.name(prefix, *components)
    }

    /* private class to allow logger configuration */
    internal abstract class LoggerProxy(protected val logger: Logger) {
        abstract fun log(marker: Marker?, format: String?)
        abstract fun isEnabled(marker: Marker?): Boolean

    }

    /* private class to allow logger configuration */
    private class DebugLoggerProxy(logger: Logger) : LoggerProxy(logger) {
        override fun log(marker: Marker?, format: String?) {
            logger.debug(marker, format)
        }

        override fun isEnabled(marker: Marker?): Boolean {
            return logger.isDebugEnabled(marker)
        }
    }

    /* private class to allow logger configuration */
    private class TraceLoggerProxy(logger: Logger) : LoggerProxy(logger) {
        override fun log(marker: Marker?, format: String?) {
            logger.trace(marker, format)
        }

        override fun isEnabled(marker: Marker?): Boolean {
            return logger.isTraceEnabled(marker)
        }
    }

    /* private class to allow logger configuration */
    private class InfoLoggerProxy(logger: Logger) : LoggerProxy(logger) {
        override fun log(marker: Marker?, format: String?) {
            logger.info(marker, format)
        }

        override fun isEnabled(marker: Marker?): Boolean {
            return logger.isInfoEnabled(marker)
        }
    }

    /* private class to allow logger configuration */
    private class WarnLoggerProxy(logger: Logger) : LoggerProxy(logger) {
        override fun log(marker: Marker?, format: String?) {
            logger.warn(marker, format)
        }

        override fun isEnabled(marker: Marker?): Boolean {
            return logger.isWarnEnabled(marker)
        }
    }

    /* private class to allow logger configuration */
    private class ErrorLoggerProxy(logger: Logger) : LoggerProxy(logger) {
        override fun log(marker: Marker?, format: String?) {
            logger.error(marker, format)
        }

        override fun isEnabled(marker: Marker?): Boolean {
            return logger.isErrorEnabled(marker)
        }
    }

    companion object {
        /**
         * Returns a new [Builder] for [Slf4jReporter].
         *
         * @param registry the registry to report
         * @return a [Builder] instance for a [Slf4jReporter]
         */
        fun forRegistry(registry: MetricRegistry): Builder {
            return Builder(registry)
        }
    }

}
