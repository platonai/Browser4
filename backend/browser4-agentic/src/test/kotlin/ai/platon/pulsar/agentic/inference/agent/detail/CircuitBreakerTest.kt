package ai.platon.pulsar.agentic.inference.agent.detail

import ai.platon.pulsar.agentic.inference.detail.CircuitBreaker
import ai.platon.pulsar.agentic.inference.detail.CircuitBreakerTrippedException
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.DisplayName

/**
 * Integration tests for CircuitBreaker component.
 */
class CircuitBreakerTest {

    private lateinit var circuitBreaker: CircuitBreaker

    @BeforeEach
    fun setUp() {
        circuitBreaker = CircuitBreaker(
            maxLLMFailures = 3,
            maxValidationFailures = 5,
            maxExecutionFailures = 2
        )
    }

    @Test
        @DisplayName("should trip on consecutive LLM failures")
    fun shouldTripOnConsecutiveLlmFailures() {
        // Record failures up to threshold
        circuitBreaker.recordFailure(CircuitBreaker.FailureType.LLM_FAILURE)
        circuitBreaker.recordFailure(CircuitBreaker.FailureType.LLM_FAILURE)

        // Should trip on 3rd failure
        val exception = assertThrows<CircuitBreakerTrippedException> {
            circuitBreaker.recordFailure(CircuitBreaker.FailureType.LLM_FAILURE)
        }

        assertEquals(CircuitBreaker.FailureType.LLM_FAILURE, exception.failureType)
        assertEquals(3, exception.failureCount)
    }

    @Test
        @DisplayName("should reset counter on success")
    fun shouldResetCounterOnSuccess() {
        // Record some failures
        circuitBreaker.recordFailure(CircuitBreaker.FailureType.LLM_FAILURE)
        circuitBreaker.recordFailure(CircuitBreaker.FailureType.LLM_FAILURE)

        // Record success
        circuitBreaker.recordSuccess(CircuitBreaker.FailureType.LLM_FAILURE)

        // Should be able to fail again without tripping
        circuitBreaker.recordFailure(CircuitBreaker.FailureType.LLM_FAILURE)
        circuitBreaker.recordFailure(CircuitBreaker.FailureType.LLM_FAILURE)

        val counts = circuitBreaker.getFailureCounts()
        assertEquals(2, counts[CircuitBreaker.FailureType.LLM_FAILURE])
    }

    @Test
        @DisplayName("should track different failure types independently")
    fun shouldTrackDifferentFailureTypesIndependently() {
        // Record LLM failures
        circuitBreaker.recordFailure(CircuitBreaker.FailureType.LLM_FAILURE)
        circuitBreaker.recordFailure(CircuitBreaker.FailureType.LLM_FAILURE)

        // Record validation failures
        circuitBreaker.recordFailure(CircuitBreaker.FailureType.VALIDATION_FAILURE)

        val counts = circuitBreaker.getFailureCounts()
        assertEquals(2, counts[CircuitBreaker.FailureType.LLM_FAILURE])
        assertEquals(1, counts[CircuitBreaker.FailureType.VALIDATION_FAILURE])
        assertEquals(0, counts[CircuitBreaker.FailureType.EXECUTION_FAILURE])
    }

    @Test
        @DisplayName("should reset all counters")
    fun shouldResetAllCounters() {
        // Record various failures
        circuitBreaker.recordFailure(CircuitBreaker.FailureType.LLM_FAILURE)
        circuitBreaker.recordFailure(CircuitBreaker.FailureType.VALIDATION_FAILURE)
        circuitBreaker.recordFailure(CircuitBreaker.FailureType.EXECUTION_FAILURE)

        // Reset
        circuitBreaker.reset()

        val counts = circuitBreaker.getFailureCounts()
        assertEquals(0, counts[CircuitBreaker.FailureType.LLM_FAILURE])
        assertEquals(0, counts[CircuitBreaker.FailureType.VALIDATION_FAILURE])
        assertEquals(0, counts[CircuitBreaker.FailureType.EXECUTION_FAILURE])
    }

    @Test
        @DisplayName("should report healthy state correctly")
    fun shouldReportHealthyStateCorrectly() {
        assertTrue(circuitBreaker.isHealthy())

        // Add one failure - should still be healthy
        circuitBreaker.recordFailure(CircuitBreaker.FailureType.LLM_FAILURE)
        assertTrue(circuitBreaker.isHealthy())

        // Add more failures to reach half threshold - should not be healthy
        circuitBreaker.recordFailure(CircuitBreaker.FailureType.LLM_FAILURE)
        assertFalse(circuitBreaker.isHealthy())
    }

    @Test
        @DisplayName("should trip on execution failures with lower threshold")
    fun shouldTripOnExecutionFailuresWithLowerThreshold() {
        circuitBreaker.recordFailure(CircuitBreaker.FailureType.EXECUTION_FAILURE)

        // Should trip on 2nd failure
        assertThrows<CircuitBreakerTrippedException> {
            circuitBreaker.recordFailure(CircuitBreaker.FailureType.EXECUTION_FAILURE)
        }
    }
}
