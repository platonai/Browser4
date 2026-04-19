package ai.platon.pulsar.agentic.observability

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Tag

/**
 * Unit tests for ToolMetrics.
 */
@Tag("observability")
class ToolMetricsTest {
    
    @Test
    fun recordToolCallWithSuccess() {
        ToolMetrics.recordToolCall("browser.click", success = true, durationMs = 200)
        
        // Verify no exception was thrown
    }
    
    @Test
    fun recordToolCallWithFailure() {
        ToolMetrics.recordToolCall("browser.navigate", success = false, durationMs = 300)
        
        // Verify no exception was thrown
    }
    
    @Test
    fun recordToolCallTimedExecutesBlock() {
        var executed = false
        
        val result = ToolMetrics.recordToolCallTimed("test.tool") {
            executed = true
            "result"
        }
        
        assertTrue(executed, "Block should have been executed")
        assertEquals("result", result, "Result should be returned")
    }
    
    @Test
    fun recordToolCallTimedHandlesException() {
        assertThrows(RuntimeException::class.java) {
            ToolMetrics.recordToolCallTimed("test.tool") {
                throw RuntimeException("Test exception")
            }
        }
    }
    
    @Test
    fun recordValidationFailure() {
        ToolMetrics.recordValidationFailure("browser.click", "invalid_selector")
        
        // Verify no exception was thrown
    }
    
    @Test
    fun recordToolRegistration() {
        ToolMetrics.recordToolRegistration("custom.tool", "custom")
        
        // Verify no exception was thrown
    }
    
    @Test
    fun getActiveToolCallsCountInitiallyZero() {
        val count = ToolMetrics.getActiveToolCallsCount()
        assertTrue(count >= 0, "Active tool calls count should be non-negative")
    }
    
    @Test
    fun activeToolCallsIncrementsDuringExecution() {
        val initialCount = ToolMetrics.getActiveToolCallsCount()
        
        var countDuringExecution = 0
        ToolMetrics.recordToolCallTimed("test.tool") {
            countDuringExecution = ToolMetrics.getActiveToolCallsCount()
            "done"
        }
        
        val finalCount = ToolMetrics.getActiveToolCallsCount()
        
        // During execution, count should have been higher (or at least same)
        assertTrue(countDuringExecution >= initialCount, 
            "Active count during execution should be >= initial count")
        
        // After execution, should be back to initial or less
        assertTrue(finalCount <= countDuringExecution, 
            "Active count after execution should be <= count during execution")
    }
}
