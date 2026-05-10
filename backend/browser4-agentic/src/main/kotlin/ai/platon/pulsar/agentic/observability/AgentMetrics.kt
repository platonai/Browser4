package ai.platon.pulsar.agentic.observability

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Business metrics collector for Agent operations.
 *
 * Tracks key metrics related to agent execution:
 * - Agent lifecycle (started, completed, failed)
 * - Action execution (success/failure rates, duration)
 * - Step counts and progress
 * - Retry and timeout metrics
 *
 * All metrics are prefixed with "agent." and include relevant tags.
 *
 * Example usage:
 * ```kotlin
 * // Record agent start
 * AgentMetrics.recordAgentStart("browser-agent")
 *
 * // Record action execution
 * AgentMetrics.recordActionExecution("click", true, 150)
 *
 * // Record step completion
 * AgentMetrics.recordStepCompleted("browser-agent", 5)
 * ```
 */
object AgentMetrics {

    private val registry: MeterRegistry = MetricsConfig.registry

    // Counters
    private val agentStartedCounter: Counter =
        registry.counter("agent.started", "component", "agent")

    private val agentCompletedCounter: Counter =
        registry.counter("agent.completed", "component", "agent")

    private val agentFailedCounter: Counter =
        registry.counter("agent.failed", "component", "agent")

    private val actionSuccessCounter: Counter =
        registry.counter("agent.actions.success", "component", "agent")

    private val actionFailureCounter: Counter =
        registry.counter("agent.actions.failure", "component", "agent")

    private val retryCounter: Counter =
        registry.counter("agent.retries", "component", "agent")

    private val timeoutCounter: Counter =
        registry.counter("agent.timeouts", "component", "agent")

    // Timers
    val actionDurationTimer: Timer =
        registry.timer("agent.action.duration", "component", "agent")

    private val resolveDurationTimer: Timer =
        registry.timer("agent.resolve.duration", "component", "agent")

    // Gauges for active counts
    private val activeAgentsCount = AtomicInteger(0)
    val activeActionsCount = AtomicInteger(0)

    // Step counts by agent type
    private val stepCountsByType = ConcurrentHashMap<String, AtomicLong>()

    init {
        // Register gauges
        Gauge.builder("agent.active.count", activeAgentsCount, AtomicInteger::toDouble)
            .tag("component", "agent")
            .description("Number of currently active agents")
            .register(registry)

        Gauge.builder("agent.active.actions", activeActionsCount, AtomicInteger::toDouble)
            .tag("component", "agent")
            .description("Number of currently executing actions")
            .register(registry)
    }

    /**
     * Record that an agent has started.
     *
     * @param agentType The type/name of the agent (e.g., "browser-agent")
     */
    fun recordAgentStart(agentType: String) {
        agentStartedCounter.increment()
        activeAgentsCount.incrementAndGet()
        registry.counter("agent.started.by.type", "agent_type", agentType).increment()
    }

    /**
     * Record that an agent has completed successfully.
     *
     * @param agentType The type/name of the agent
     * @param totalSteps Total number of steps executed
     */
    fun recordAgentCompleted(agentType: String, totalSteps: Int) {
        agentCompletedCounter.increment()
        activeAgentsCount.decrementAndGet()
        registry.counter("agent.completed.by.type", "agent_type", agentType).increment()
        registry.summary("agent.steps.total", "agent_type", agentType).record(totalSteps.toDouble())
    }

    /**
     * Record that an agent has failed.
     *
     * @param agentType The type/name of the agent
     * @param errorType The type of error (e.g., "timeout", "validation_error")
     */
    fun recordAgentFailed(agentType: String, errorType: String) {
        agentFailedCounter.increment()
        activeAgentsCount.decrementAndGet()
        registry.counter("agent.failed.by.type",
            "agent_type", agentType,
            "error_type", errorType
        ).increment()
    }

    /**
     * Record an action execution.
     *
     * @param actionType The type of action (e.g., "click", "type", "navigate")
     * @param success Whether the action succeeded
     * @param durationMs Duration in milliseconds
     */
    fun recordActionExecution(actionType: String, success: Boolean, durationMs: Long) {
        if (success) {
            actionSuccessCounter.increment()
            registry.counter("agent.actions.success.by.type", "action_type", actionType).increment()
        } else {
            actionFailureCounter.increment()
            registry.counter("agent.actions.failure.by.type", "action_type", actionType).increment()
        }

        registry.timer("agent.action.duration.by.type", "action_type", actionType, "success", success.toString())
            .record(java.time.Duration.ofMillis(durationMs))
    }

    /**
     * Record action execution using a timer.
     *
     * @param actionType The type of action
     * @param success Whether the action succeeded
     * @param block The action to execute and time
     * @return Result of the block
     */
    internal inline fun <T> recordActionTimed(actionType: String, success: Boolean, block: () -> T): T {
        activeActionsCount.incrementAndGet()
        val start = System.currentTimeMillis()
        return try {
            block()
        } finally {
            activeActionsCount.decrementAndGet()
            val duration = System.currentTimeMillis() - start
            actionDurationTimer.record(java.time.Duration.ofMillis(duration))
            registry.timer("agent.action.duration.by.type",
                "action_type", actionType,
                "success", success.toString()
            ).record(java.time.Duration.ofMillis(duration))
        }
    }

    /**
     * Record a step completion.
     *
     * @param agentType The type/name of the agent
     * @param stepNumber The current step number
     */
    fun recordStepCompleted(agentType: String, stepNumber: Int) {
        stepCountsByType.computeIfAbsent(agentType) { AtomicLong(0) }.set(stepNumber.toLong())
        registry.counter("agent.steps.completed", "agent_type", agentType).increment()
    }

    /**
     * Record a retry attempt.
     *
     * @param reason The reason for retry (e.g., "transient_error", "timeout")
     */
    fun recordRetry(reason: String) {
        retryCounter.increment()
        registry.counter("agent.retries.by.reason", "reason", reason).increment()
    }

    /**
     * Record a timeout.
     *
     * @param operation The operation that timed out (e.g., "action", "resolve")
     */
    fun recordTimeout(operation: String) {
        timeoutCounter.increment()
        registry.counter("agent.timeouts.by.operation", "operation", operation).increment()
    }

    /**
     * Record resolve operation duration.
     *
     * @param durationMs Duration in milliseconds
     */
    fun recordResolveDuration(durationMs: Long) {
        resolveDurationTimer.record(java.time.Duration.ofMillis(durationMs))
    }

    /**
     * Get current step count for an agent type.
     *
     * @param agentType The type/name of the agent
     * @return Current step count
     */
    fun getCurrentStepCount(agentType: String): Long {
        return stepCountsByType[agentType]?.get() ?: 0L
    }
}
