package ai.platon.pulsar.agentic.inference.detail

import ai.platon.pulsar.common.getLogger
import java.util.concurrent.atomic.AtomicInteger

/**
 * Circuit breaker to prevent infinite loops from repeated failures.
 *
 * Tracks different types of failures and trips when consecutive failures
 * exceed configured thresholds.
 *
 * @param maxLLMFailures Maximum consecutive LLM failures before tripping
 * @param maxValidationFailures Maximum consecutive validation failures before tripping
 * @param maxExecutionFailures Maximum consecutive execution failures before tripping
 */
class CircuitBreaker(
    private val maxLLMFailures: Int = 5,
    private val maxValidationFailures: Int = 8,
    private val maxExecutionFailures: Int = 3
) {
    private val logger = getLogger(this)

    private val llmFailureCounter = AtomicInteger(0)
    private val validationFailureCounter = AtomicInteger(0)
    private val executionFailureCounter = AtomicInteger(0)

    enum class FailureType {
        LLM_FAILURE,
        VALIDATION_FAILURE,
        EXECUTION_FAILURE
    }

    /**
     * Record a failure and check if circuit should trip.
     *
     * @param type Type of failure
     * @return true if circuit has tripped, false otherwise
     * @throws CircuitBreakerTrippedException if threshold exceeded
     */
    fun recordFailure(type: FailureType): Int {
        val count = when (type) {
            FailureType.LLM_FAILURE -> {
                val c = llmFailureCounter.incrementAndGet()
                if (c >= maxLLMFailures) {
                    logger.error("🔌 Circuit breaker TRIPPED: {} consecutive LLM failures", c)
                    throw CircuitBreakerTrippedException(
                        "Circuit breaker: $c consecutive LLM failures (max: $maxLLMFailures)", type, c
                    )
                }
                c
            }
            FailureType.VALIDATION_FAILURE -> {
                val c = validationFailureCounter.incrementAndGet()
                if (c >= maxValidationFailures) {
                    logger.error("🔌 Circuit breaker TRIPPED: {} consecutive validation failures", c)
                    throw CircuitBreakerTrippedException(
                        "Circuit breaker: $c consecutive validation failures (max: $maxValidationFailures)", type, c
                    )
                }
                c
            }
            FailureType.EXECUTION_FAILURE -> {
                val c = executionFailureCounter.incrementAndGet()
                if (c >= maxExecutionFailures) {
                    logger.error("🔌 Circuit breaker TRIPPED: {} consecutive execution failures", c)
                    throw CircuitBreakerTrippedException(
                        "Circuit breaker: $c consecutive execution failures (max: $maxExecutionFailures)", type, c
                    )
                }
                c
            }
        }

        logger.debug("🔌 Failure recorded type={} count={}", type, count)
        return count
    }

    /**
     * Record a success and reset the corresponding failure counter.
     */
    fun recordSuccess(type: FailureType) {
        when (type) {
            FailureType.LLM_FAILURE -> llmFailureCounter.set(0)
            FailureType.VALIDATION_FAILURE -> validationFailureCounter.set(0)
            FailureType.EXECUTION_FAILURE -> executionFailureCounter.set(0)
        }
    }

    /**
     * Reset all failure counters.
     */
    fun reset() {
        llmFailureCounter.set(0)
        validationFailureCounter.set(0)
        executionFailureCounter.set(0)
        logger.info("🔌 Circuit breaker RESET")
    }

    /**
     * Get current failure counts.
     */
    fun getFailureCounts(): Map<FailureType, Int> = mapOf(
        FailureType.LLM_FAILURE to llmFailureCounter.get(),
        FailureType.VALIDATION_FAILURE to validationFailureCounter.get(),
        FailureType.EXECUTION_FAILURE to executionFailureCounter.get()
    )

    /**
     * Check if circuit is healthy (no counters near threshold).
     */
    fun isHealthy(): Boolean {
        return llmFailureCounter.get() <= maxLLMFailures / 2 &&
               validationFailureCounter.get() <= maxValidationFailures / 2 &&
               executionFailureCounter.get() <= maxExecutionFailures / 2
    }
}

/**
 * Exception thrown when circuit breaker trips.
 */
class CircuitBreakerTrippedException(
    message: String,
    val failureType: CircuitBreaker.FailureType,
    val failureCount: Int
) : PerceptiveAgentError.PermanentError(message)
