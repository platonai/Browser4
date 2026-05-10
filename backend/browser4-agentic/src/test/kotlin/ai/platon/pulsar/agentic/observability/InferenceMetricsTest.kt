package ai.platon.pulsar.agentic.observability

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Tag

/**
 * Unit tests for InferenceMetrics.
 */
@Tag("observability")
class InferenceMetricsTest {
    
    @Test
    fun recordInferenceCallWithSuccess() {
        InferenceMetrics.recordInferenceCall("gpt-4", success = true, durationMs = 1500)
        
        // Verify no exception was thrown
    }
    
    @Test
    fun recordInferenceCallWithFailure() {
        InferenceMetrics.recordInferenceCall("claude-3", success = false, durationMs = 2000)
        
        // Verify no exception was thrown
    }
    
    @Test
    fun recordInferenceCallTimedExecutesBlock() {
        var executed = false
        
        val result = InferenceMetrics.recordInferenceCallTimed("test-model") {
            executed = true
            "inference-result"
        }
        
        assertTrue(executed, "Block should have been executed")
        assertEquals("inference-result", result, "Result should be returned")
    }
    
    @Test
    fun recordInferenceCallTimedHandlesException() {
        assertThrows(RuntimeException::class.java) {
            InferenceMetrics.recordInferenceCallTimed("test-model") {
                throw RuntimeException("Inference error")
            }
        }
    }
    
    @Test
    fun recordTokenUsage() {
        val initialInput = InferenceMetrics.getTotalInputTokens()
        val initialOutput = InferenceMetrics.getTotalOutputTokens()
        
        InferenceMetrics.recordTokenUsage("gpt-4", inputTokens = 100, outputTokens = 250)
        
        val finalInput = InferenceMetrics.getTotalInputTokens()
        val finalOutput = InferenceMetrics.getTotalOutputTokens()
        
        assertTrue(finalInput >= initialInput + 100, 
            "Total input tokens should have increased by at least 100")
        assertTrue(finalOutput >= initialOutput + 250, 
            "Total output tokens should have increased by at least 250")
    }
    
    @Test
    fun recordCircuitBreakerTrip() {
        InferenceMetrics.recordCircuitBreakerTrip("consecutive_failures")
        
        // Verify no exception was thrown
    }
    
    @Test
    fun recordRetry() {
        InferenceMetrics.recordRetry("gpt-4", "rate_limit")
        
        // Verify no exception was thrown
    }
    
    @Test
    fun recordInferenceError() {
        InferenceMetrics.recordInferenceError("claude-3", "timeout")
        
        // Verify no exception was thrown
    }
    
    @Test
    fun recordPromptSize() {
        InferenceMetrics.recordPromptSize("gpt-4", promptSize = 1024)
        
        // Verify no exception was thrown
    }
    
    @Test
    fun getActiveInferenceCountNonNegative() {
        val count = InferenceMetrics.getActiveInferenceCount()
        assertTrue(count >= 0, "Active inference count should be non-negative")
    }
    
    @Test
    fun getTotalInputTokensNonNegative() {
        val tokens = InferenceMetrics.getTotalInputTokens()
        assertTrue(tokens >= 0, "Total input tokens should be non-negative")
    }
    
    @Test
    fun getTotalOutputTokensNonNegative() {
        val tokens = InferenceMetrics.getTotalOutputTokens()
        assertTrue(tokens >= 0, "Total output tokens should be non-negative")
    }
}
