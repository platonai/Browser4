package ai.platon.pulsar.agentic.observability

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import java.util.concurrent.TimeUnit

/**
 * Metrics for CLI subprocess execution (design §4.1): call count, latency,
 * timeouts, output truncation and background-job escalations.
 */
object CliMetrics {
    private val registry: MeterRegistry = MetricsConfig.registry

    private val callsCounter: Counter = registry.counter("browser4.cli.calls", "component", "cli")
    private val timeoutCounter: Counter = registry.counter("browser4.cli.timeouts", "component", "cli")
    private val truncatedCounter: Counter = registry.counter("browser4.cli.truncated", "component", "cli")
    private val jobCounter: Counter = registry.counter("browser4.cli.jobs", "component", "cli")
    private val durationTimer: Timer = registry.timer("browser4.cli.duration", "component", "cli")

    /** Record one CLI subprocess call (duration, timeout, output truncation). */
    fun recordCall(durationMs: Long, timedOut: Boolean, truncated: Boolean) {
        // Metrics are best-effort — never let a registry failure break a call.
        runCatching {
            callsCounter.increment()
            durationTimer.record(durationMs, TimeUnit.MILLISECONDS)
            if (timedOut) timeoutCounter.increment()
            if (truncated) truncatedCounter.increment()
        }
    }

    /** Record a long command escalated to a background job. */
    fun recordJobEscalation() {
        runCatching { jobCounter.increment() }
    }
}
