package ai.platon.pulsar.agentic.observability

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import java.util.concurrent.atomic.AtomicInteger

/**
 * Business metrics collector for Tool call operations.
 *
 * Tracks key metrics related to tool execution:
 * - Tool call counts (by tool name, success/failure)
 * - Tool execution duration
 * - Active tool calls
 * - Tool validation metrics
 *
 * All metrics are prefixed with "tool." and include relevant tags.
 *
 * Example usage:
 * ```kotlin
 * // Record tool call
 * ToolMetrics.recordToolCall("browser.click", true, 250)
 *
 * // Record with timer
 * val result = ToolMetrics.recordToolCallTimed("browser.navigate") {
 *     // Execute tool
 *     performNavigation()
 * }
 *
 * // Record validation failure
 * ToolMetrics.recordValidationFailure("browser.click", "invalid_selector")
 * ```
 */
object ToolMetrics {

    private val registry: MeterRegistry = MetricsConfig.registry

    // Counters
    private val toolCallCounter: Counter =
        registry.counter("tool.calls.total", "component", "tool")

    private val toolSuccessCounter: Counter =
        registry.counter("tool.calls.success", "component", "tool")

    private val toolFailureCounter: Counter =
        registry.counter("tool.calls.failure", "component", "tool")

    private val validationFailureCounter: Counter =
        registry.counter("tool.validation.failures", "component", "tool")

    // Timers
    private val toolExecutionTimer: Timer =
        registry.timer("tool.execution.duration", "component", "tool")

    // Gauges
    val activeToolCallsCount = AtomicInteger(0)

    init {
        Gauge.builder("tool.active.calls", activeToolCallsCount, AtomicInteger::toDouble)
            .tag("component", "tool")
            .description("Number of currently executing tool calls")
            .register(registry)
    }

    /**
     * Record a tool call execution.
     *
     * @param toolName The name of the tool (e.g., "browser.click", "system.execute")
     * @param success Whether the call succeeded
     * @param durationMs Duration in milliseconds
     */
    fun recordToolCall(toolName: String, success: Boolean, durationMs: Long) {
        toolCallCounter.increment()

        if (success) {
            toolSuccessCounter.increment()
            registry.counter("tool.calls.success.by.name", "tool_name", toolName).increment()
        } else {
            toolFailureCounter.increment()
            registry.counter("tool.calls.failure.by.name", "tool_name", toolName).increment()
        }

        registry.timer("tool.execution.duration.by.name",
            "tool_name", toolName,
            "success", success.toString()
        ).record(java.time.Duration.ofMillis(durationMs))
    }

    /**
     * Record tool call execution using a timer.
     *
     * @param toolName The name of the tool
     * @param block The tool execution to time
     * @return Result of the block
     */
    internal inline fun <T> recordToolCallTimed(toolName: String, block: () -> T): T {
        activeToolCallsCount.incrementAndGet()
        toolCallCounter.increment()

        val startTime = System.currentTimeMillis()
        var success = false

        return try {
            val result = block()
            success = true
            result
        } catch (e: Exception) {
            success = false
            throw e
        } finally {
            activeToolCallsCount.decrementAndGet()
            val duration = System.currentTimeMillis() - startTime

            if (success) {
                toolSuccessCounter.increment()
                registry.counter("tool.calls.success.by.name", "tool_name", toolName).increment()
            } else {
                toolFailureCounter.increment()
                registry.counter("tool.calls.failure.by.name", "tool_name", toolName).increment()
            }

            toolExecutionTimer.record(java.time.Duration.ofMillis(duration))
            registry.timer("tool.execution.duration.by.name",
                "tool_name", toolName,
                "success", success.toString()
            ).record(java.time.Duration.ofMillis(duration))
        }
    }

    /**
     * Record a validation failure.
     *
     * @param toolName The name of the tool
     * @param validationType The type of validation that failed (e.g., "selector", "parameter")
     */
    fun recordValidationFailure(toolName: String, validationType: String) {
        validationFailureCounter.increment()
        registry.counter("tool.validation.failures.by.type",
            "tool_name", toolName,
            "validation_type", validationType
        ).increment()
    }

    /**
     * Record tool registration.
     *
     * @param toolName The name of the registered tool
     * @param toolType The type/category of the tool (e.g., "browser", "system", "custom")
     */
    fun recordToolRegistration(toolName: String, toolType: String) {
        registry.counter("tool.registrations",
            "tool_name", toolName,
            "tool_type", toolType
        ).increment()
    }

    /**
     * Get the current number of active tool calls.
     *
     * @return Number of active tool calls
     */
    fun getActiveToolCallsCount(): Int = activeToolCallsCount.get()
}
