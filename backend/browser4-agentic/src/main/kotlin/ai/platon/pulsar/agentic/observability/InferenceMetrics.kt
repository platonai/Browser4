package ai.platon.pulsar.agentic.observability

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Business metrics collector for LLM Inference operations.
 *
 * Tracks key metrics related to LLM/model inference:
 * - Inference call counts (by model, success/failure)
 * - Inference duration and latency
 * - Token usage (input/output)
 * - Circuit breaker activations
 * - Retry attempts
 *
 * All metrics are prefixed with "inference." and include relevant tags.
 *
 * Example usage:
 * ```kotlin
 * // Record inference call
 * InferenceMetrics.recordInferenceCall("gpt-4", true, 1500)
 *
 * // Record token usage
 * InferenceMetrics.recordTokenUsage("gpt-4", 100, 250)
 *
 * // Record circuit breaker trip
 * InferenceMetrics.recordCircuitBreakerTrip("consecutive_failures")
 * ```
 */
object InferenceMetrics {

    private val registry: MeterRegistry = MetricsConfig.registry

    // Counters
    val inferenceCallCounter: Counter =
        registry.counter("inference.calls.total", "component", "inference")

    private val inferenceSuccessCounter: Counter =
        registry.counter("inference.calls.success", "component", "inference")

    private val inferenceFailureCounter: Counter =
        registry.counter("inference.calls.failure", "component", "inference")

    private val circuitBreakerTripCounter: Counter =
        registry.counter("inference.circuit_breaker.trips", "component", "inference")

    private val inferenceRetryCounter: Counter =
        registry.counter("inference.retries", "component", "inference")

    // Timers
    val inferenceDurationTimer: Timer =
        registry.timer("inference.duration", "component", "inference")

    // Gauges and atomic counters
    val activeInferenceCount = AtomicInteger(0)
    private val totalInputTokens = AtomicLong(0)
    private val totalOutputTokens = AtomicLong(0)

    init {
        Gauge.builder("inference.active.calls", activeInferenceCount, AtomicInteger::toDouble)
            .tag("component", "inference")
            .description("Number of currently active inference calls")
            .register(registry)

        Gauge.builder("inference.tokens.input.total", totalInputTokens, AtomicLong::toDouble)
            .tag("component", "inference")
            .description("Total input tokens processed")
            .register(registry)

        Gauge.builder("inference.tokens.output.total", totalOutputTokens, AtomicLong::toDouble)
            .tag("component", "inference")
            .description("Total output tokens generated")
            .register(registry)
    }

    /**
     * Record an inference call.
     *
     * @param modelName The name of the model used (e.g., "gpt-4", "claude-3")
     * @param success Whether the call succeeded
     * @param durationMs Duration in milliseconds
     */
    fun recordInferenceCall(modelName: String, success: Boolean, durationMs: Long) {
        inferenceCallCounter.increment()

        if (success) {
            inferenceSuccessCounter.increment()
            registry.counter("inference.calls.success.by.model", "model_name", modelName).increment()
        } else {
            inferenceFailureCounter.increment()
            registry.counter("inference.calls.failure.by.model", "model_name", modelName).increment()
        }

        registry.timer("inference.duration.by.model",
            "model_name", modelName,
            "success", success.toString()
        ).record(java.time.Duration.ofMillis(durationMs))
    }

    /**
     * Record inference call execution using a timer.
     *
     * @param modelName The name of the model
     * @param block The inference operation to time
     * @return Result of the block
     */
    internal inline fun <T> recordInferenceCallTimed(modelName: String, block: () -> T): T {
        activeInferenceCount.incrementAndGet()
        inferenceCallCounter.increment()

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
            activeInferenceCount.decrementAndGet()
            val duration = System.currentTimeMillis() - startTime

            if (success) {
                inferenceSuccessCounter.increment()
                registry.counter("inference.calls.success.by.model", "model_name", modelName).increment()
            } else {
                inferenceFailureCounter.increment()
                registry.counter("inference.calls.failure.by.model", "model_name", modelName).increment()
            }

            inferenceDurationTimer.record(java.time.Duration.ofMillis(duration))
            registry.timer("inference.duration.by.model",
                "model_name", modelName,
                "success", success.toString()
            ).record(java.time.Duration.ofMillis(duration))
        }
    }

    /**
     * Record token usage for an inference call.
     *
     * @param modelName The name of the model
     * @param inputTokens Number of input tokens
     * @param outputTokens Number of output tokens
     */
    fun recordTokenUsage(modelName: String, inputTokens: Int, outputTokens: Int) {
        totalInputTokens.addAndGet(inputTokens.toLong())
        totalOutputTokens.addAndGet(outputTokens.toLong())

        registry.counter("inference.tokens.input", "model_name", modelName)
            .increment(inputTokens.toDouble())
        registry.counter("inference.tokens.output", "model_name", modelName)
            .increment(outputTokens.toDouble())

        // Record token efficiency (output/input ratio)
        if (inputTokens > 0) {
            val ratio = outputTokens.toDouble() / inputTokens.toDouble()
            registry.gauge("inference.tokens.ratio",
                listOf(io.micrometer.core.instrument.Tag.of("model_name", modelName)),
                ratio
            )
        }
    }

    /**
     * Record a circuit breaker trip.
     *
     * @param reason The reason for the trip (e.g., "consecutive_failures", "timeout_threshold")
     */
    fun recordCircuitBreakerTrip(reason: String) {
        circuitBreakerTripCounter.increment()
        registry.counter("inference.circuit_breaker.trips.by.reason", "reason", reason).increment()
    }

    /**
     * Record a retry attempt.
     *
     * @param modelName The name of the model
     * @param reason The reason for retry (e.g., "rate_limit", "timeout")
     */
    fun recordRetry(modelName: String, reason: String) {
        inferenceRetryCounter.increment()
        registry.counter("inference.retries.by.model",
            "model_name", modelName,
            "reason", reason
        ).increment()
    }

    /**
     * Record inference error by type.
     *
     * @param modelName The name of the model
     * @param errorType The type of error (e.g., "timeout", "rate_limit", "api_error")
     */
    fun recordInferenceError(modelName: String, errorType: String) {
        registry.counter("inference.errors",
            "model_name", modelName,
            "error_type", errorType
        ).increment()
    }

    /**
     * Record prompt size.
     *
     * @param modelName The name of the model
     * @param promptSize Size of the prompt in characters
     */
    fun recordPromptSize(modelName: String, promptSize: Int) {
        registry.summary("inference.prompt.size", "model_name", modelName)
            .record(promptSize.toDouble())
    }

    /**
     * Get the current number of active inference calls.
     *
     * @return Number of active inference calls
     */
    fun getActiveInferenceCount(): Int = activeInferenceCount.get()

    /**
     * Get total input tokens processed.
     *
     * @return Total input tokens
     */
    fun getTotalInputTokens(): Long = totalInputTokens.get()

    /**
     * Get total output tokens generated.
     *
     * @return Total output tokens
     */
    fun getTotalOutputTokens(): Long = totalOutputTokens.get()
}
