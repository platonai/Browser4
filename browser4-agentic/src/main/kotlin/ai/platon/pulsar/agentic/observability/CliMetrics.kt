package ai.platon.pulsar.agentic.observability

import ai.platon.pulsar.agentic.cli.CliTiming
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

    // E-4 segments: what one `b4.run` actually spends its wall clock on. `duration`
    // alone could not tell "the CLI started slowly" from "the page was slow".
    private val launchTimer: Timer = registry.timer("browser4.cli.launch", "component", "cli")
    private val startupTimer: Timer = registry.timer("browser4.cli.startup", "component", "cli")
    private val httpTimer: Timer = registry.timer("browser4.cli.http", "component", "cli")
    private val serverTimer: Timer = registry.timer("browser4.cli.server", "component", "cli")

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

    /**
     * Record the per-segment timing the CLI reported for one invocation.
     *
     * `launch` + `startup` + `http` is the invocation's own duration; `server` is
     * the backend's share of `http`, so `http - server` is transport. Best-effort,
     * like [recordCall].
     */
    fun recordTiming(timing: CliTiming) {
        runCatching {
            timing.launchMs?.let { launchTimer.record(it, TimeUnit.MILLISECONDS) }
            startupTimer.record(timing.startupMs, TimeUnit.MILLISECONDS)
            httpTimer.record(timing.httpMs, TimeUnit.MILLISECONDS)
            serverTimer.record(timing.serverMs, TimeUnit.MILLISECONDS)
        }
    }

    /** Record a long command escalated to a background job. */
    fun recordJobEscalation() {
        runCatching { jobCounter.increment() }
    }
}

