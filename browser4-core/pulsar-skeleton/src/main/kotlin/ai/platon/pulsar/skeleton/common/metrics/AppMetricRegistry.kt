package ai.platon.pulsar.skeleton.common.metrics

import ai.platon.pulsar.common.DateTimes
import ai.platon.pulsar.common.prependReadableClassName
import com.codahale.metrics.*
import kotlin.reflect.KClass

class AppMetricRegistry : MetricRegistry() {

    private val elapsedToday get() = DateTimes.elapsedToday.seconds.coerceAtMost(DateTimes.elapsed.seconds)
    private val elapsedThisHour get() = DateTimes.elapsedThisHour.seconds.coerceAtMost(DateTimes.elapsed.seconds)

    val enumCounters: MutableMap<Enum<*>, Counter> = mutableMapOf()
    val dailyCounters = mutableSetOf<Counter>()
    val hourlyCounters = mutableSetOf<Counter>()

    fun name(obj: Any, name: String, separator: String = "."): String {
        return prependReadableClassName(obj, name, separator)
    }

    fun name(obj: Any, ident: String, name: String, separator: String): String {
        return prependReadableClassName(obj, ident, name, separator)
    }

    fun counter(obj: Any, ident: String, name: String): Counter {
        val fullName = name(obj, "$ident.c", name, ".")
        if (metrics.containsKey(fullName)) {
            return metrics[fullName] as Counter
        }

        val counter = counter(fullName)
        when {
            "daily" in fullName -> dailyCounters.add(counter)
            "hourly" in fullName -> hourlyCounters.add(counter)
        }
        return counter
    }

    fun counter(obj: Any, name: String) = counter(obj, "", name)

    fun meter(obj: Any, ident: String, name: String): Meter {
        val fullName = name(obj, "$ident.m", name, ".")
        return (metrics[fullName] as? Meter) ?: meter(fullName)
    }

    fun meter(obj: Any, name: String) = meter(obj, "", name)

    fun histogram(obj: Any, ident: String, name: String): Histogram {
        val fullName = name(obj, "$ident.h", name, ".")
        return (metrics[fullName] as? Histogram) ?: histogram(fullName)
    }

    fun histogram(obj: Any, name: String) = histogram(obj, "", name)

    fun <T : Metric> register(obj: Any, name: String, metric: T) = register(obj, "", name, metric)

    fun <T : Metric> register(obj: Any, ident: String, name: String, metric: T) {
        val fullName = name(obj, ident, name, ".")
        if (!metrics.containsKey(fullName)) {
            register(fullName, metric)
        }
    }

    fun <T : Metric> registerAll(obj: Any, metrics: Map<String, T>) =
        metrics.forEach { (name, metric) -> register(obj, name, metric) }

    fun <T : Metric> registerAll(obj: Any, ident: String, metrics: Map<String, T>) =
        metrics.forEach { (name, metric) -> register(obj, ident, name, metric) }

    fun <T : Enum<T>> register(counterClass: Class<T>, ident: String = "", withGauges: Boolean = false) {
        val enumConstants = counterClass.enumConstants
        if (withGauges) {
            enumConstants.associateWithTo(enumCounters) { counterAndGauge(counterClass, ident, it.name) }
        } else {
            enumConstants.associateWithTo(enumCounters) { counter(counterClass, ident, it.name) }
        }
    }

    fun <T : Enum<T>> register(counterClass: KClass<T>, ident: String = "", withGauges: Boolean = false) {
        return register(counterClass.java, ident, withGauges)
    }

    fun counterAndGauge(obj: Any, ident: String, name: String): Counter {
        val counter = counter(obj, ident, name)

        register(obj, "$ident.g", name, Gauge { counter.count })
        register(obj, "$ident.g", "$name/s", Gauge { 1.0 * counter.count / elapsedSeconds(counter) })

        return counter
    }

    private fun elapsedSeconds(counter: Counter): Long {
        val fullName = metrics.entries.firstOrNull { it.value == counter }?.key ?: ""
        return when {
            "daily" in fullName -> elapsedToday
            "hourly" in fullName -> elapsedThisHour
            else -> DateTimes.elapsed.seconds
        }.coerceAtLeast(1)
    }

    fun counterAndGauge(obj: Any, name: String) = counterAndGauge(obj, "", name)

    fun dailyCounterAndGauge(obj: Any, ident: String, name: String) = counterAndGauge(obj, "$ident.daily", name)

    fun dailyCounterAndGauge(obj: Any, name: String) = dailyCounterAndGauge(obj, "", name)

    fun hourlyCounterAndGauge(obj: Any, ident: String, name: String) = counterAndGauge(obj, "$ident.hourly", name)

    fun hourlyCounterAndGauge(obj: Any, name: String) = hourlyCounterAndGauge(obj, "", name)

    fun multiMetric(obj: Any, ident: String, name: String): MultiMetric {
        val counter = counterAndGauge(obj, ident, name)
        val dailyCounter = dailyCounterAndGauge(obj, ident, name)
        val hourlyCounter = hourlyCounterAndGauge(obj, ident, name)
        val meter = meter(obj, ident, name)
        return MultiMetric(counter, dailyCounter, hourlyCounter, meter)
    }

    fun multiMetric(obj: Any, name: String) = multiMetric(obj, "", name)

    fun resetDailyCounters() {
        dailyCounters.forEach { it.dec(it.count) }
    }

    fun resetHourlyCounters() {
        hourlyCounters.forEach { it.dec(it.count) }
    }

    fun <T : Enum<T>> setValue(counter: T, value: Int) {
        enumCounters[counter]?.let { it.dec(it.count); it.inc(value.toLong()) }
    }
}
