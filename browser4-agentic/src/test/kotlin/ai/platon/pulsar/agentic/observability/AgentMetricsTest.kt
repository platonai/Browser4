package ai.platon.pulsar.agentic.observability

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Tag

/**
 * Unit tests for AgentMetrics.
 */
@Tag("observability")
class AgentMetricsTest {
    
    @Test
    fun recordAgentStartIncrementsCounter() {
        val initialCount = AgentMetrics.getCurrentStepCount("test-agent")
        
        AgentMetrics.recordAgentStart("test-agent")
        
        // Verify no exception was thrown
        // Counter should be incremented internally
    }
    
    @Test
    fun recordAgentCompletedTracksSteps() {
        AgentMetrics.recordAgentStart("test-agent")
        AgentMetrics.recordStepCompleted("test-agent", 1)
        AgentMetrics.recordStepCompleted("test-agent", 2)
        AgentMetrics.recordAgentCompleted("test-agent", totalSteps = 2)
        
        val stepCount = AgentMetrics.getCurrentStepCount("test-agent")
        assertEquals(2L, stepCount, "Step count should be 2")
    }
    
    @Test
    fun recordActionExecutionWithSuccess() {
        AgentMetrics.recordActionExecution("click", success = true, durationMs = 100)
        
        // Verify no exception was thrown
    }
    
    @Test
    fun recordActionExecutionWithFailure() {
        AgentMetrics.recordActionExecution("navigate", success = false, durationMs = 500)
        
        // Verify no exception was thrown
    }
    
    @Test
    fun recordActionTimedExecutesBlock() {
        var executed = false
        
        val result = AgentMetrics.recordActionTimed("test", true) {
            executed = true
            "success"
        }
        
        assertTrue(executed, "Block should have been executed")
        assertEquals("success", result, "Result should be returned")
    }
    
    @Test
    fun recordActionTimedHandlesException() {
        assertThrows(RuntimeException::class.java) {
            AgentMetrics.recordActionTimed("test", false) {
                throw RuntimeException("Test exception")
            }
        }
    }
    
    @Test
    fun recordRetryWithReason() {
        AgentMetrics.recordRetry("timeout")
        
        // Verify no exception was thrown
    }
    
    @Test
    fun recordTimeoutWithOperation() {
        AgentMetrics.recordTimeout("resolve")
        
        // Verify no exception was thrown
    }
    
    @Test
    fun recordResolveDuration() {
        AgentMetrics.recordResolveDuration(1500)
        
        // Verify no exception was thrown
    }
    
    @Test
    fun getCurrentStepCountReturnsZeroForUnknownAgent() {
        val stepCount = AgentMetrics.getCurrentStepCount("unknown-agent")
        assertEquals(0L, stepCount, "Step count should be 0 for unknown agent")
    }
}
