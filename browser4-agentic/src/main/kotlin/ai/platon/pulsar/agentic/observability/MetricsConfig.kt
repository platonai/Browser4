package ai.platon.pulsar.agentic.observability

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.binder.jvm.ClassLoaderMetrics
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics
import io.micrometer.core.instrument.binder.system.ProcessorMetrics
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry

/**
 * Micrometer configuration for metrics collection and Prometheus export.
 *
 * This configuration:
 * - Initializes Prometheus meter registry
 * - Registers JVM metrics (memory, GC, threads, classloader)
 * - Provides common metrics tags
 * - Exposes Prometheus scrape endpoint data
 *
 * Configuration via environment variables:
 * - METRICS_ENABLED: Enable/disable metrics collection (default: true)
 * - METRICS_PREFIX: Prefix for all metrics (default: pulsar_agentic)
 * - METRICS_COMMON_TAGS: Comma-separated key:value pairs for common tags
 *
 * Example usage:
 * ```kotlin
 * // Get the registry
 * val registry = MetricsConfig.registry
 *
 * // Create a counter
 * registry.counter("operations.total", "type", "browser").increment()
 *
 * // Create a timer
 * val timer = registry.timer("operation.duration", "operation", "act")
 * timer.record { /* your operation */ }
 *
 * // Export to Prometheus
 * val prometheusData = MetricsConfig.scrape()
 * ```
 */
object MetricsConfig {

    private val isMetricsEnabled: Boolean =
        System.getenv("METRICS_ENABLED")?.toBoolean() ?: true

    private val metricsPrefix: String =
        System.getenv("METRICS_PREFIX") ?: "pulsar_agentic"

    /**
     * Common tags applied to all metrics.
     */
    private val commonTags: Map<String, String> by lazy {
        val tagsEnv = System.getenv("METRICS_COMMON_TAGS") ?: ""
        if (tagsEnv.isBlank()) {
            mapOf(
                "service" to "pulsar-agentic",
                "version" to "4.7.0-SNAPSHOT"
            )
        } else {
            tagsEnv.split(",")
                .mapNotNull {
                    val parts = it.trim().split(":")
                    if (parts.size == 2) parts[0] to parts[1] else null
                }
                .toMap()
        }
    }

    /**
     * Prometheus meter registry for metrics collection.
     */
    val registry: MeterRegistry by lazy {
        if (!isMetricsEnabled) {
            return@lazy SimpleMeterRegistry()
        }

        val prometheusRegistry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)

        // Add common tags
        commonTags.forEach { (key, value) ->
            prometheusRegistry.config().commonTags(key, value)
        }

        // Add metrics prefix if configured
        if (metricsPrefix.isNotBlank()) {
            prometheusRegistry.config().commonTags("prefix", metricsPrefix)
        }

        // Register JVM metrics
        JvmMemoryMetrics().bindTo(prometheusRegistry)
        JvmGcMetrics().bindTo(prometheusRegistry)
        JvmThreadMetrics().bindTo(prometheusRegistry)
        ClassLoaderMetrics().bindTo(prometheusRegistry)
        ProcessorMetrics().bindTo(prometheusRegistry)

        prometheusRegistry
    }

    /**
     * Get Prometheus scrape data in text format.
     * This is the data that Prometheus will scrape from /metrics endpoint.
     *
     * @return Prometheus text format metrics data
     */
    fun scrape(): String {
        return if (registry is PrometheusMeterRegistry) {
            (registry as PrometheusMeterRegistry).scrape()
        } else {
            ""
        }
    }

    /**
     * Close the meter registry and cleanup resources.
     */
    fun close() {
        if (registry is PrometheusMeterRegistry) {
            (registry as PrometheusMeterRegistry).close()
        }
    }
}
