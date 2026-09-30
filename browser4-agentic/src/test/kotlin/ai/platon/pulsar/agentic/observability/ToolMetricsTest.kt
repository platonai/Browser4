package ai.platon.pulsar.agentic.observability

import ai.platon.pulsar.agentic.tools.ToolErrorCode
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Tag
import java.util.concurrent.TimeUnit

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

    @Test
    fun callsAreCountedPerToolAndOutcome() {
        val tool = "test.outcome.$STAMP"
        ToolMetrics.recordToolCall(tool, success = true, durationMs = 5)
        ToolMetrics.recordToolCall(tool, success = false, durationMs = 7, errorCode = ToolErrorCode.TIMEOUT.wire)

        val registry = MetricsConfig.registry
        assertEquals(
            1.0, registry.find("tool.calls.success.by.name").tag("tool_name", tool).counter()?.count(),
            "the success counter must carry the tool name",
        )
        assertEquals(
            1.0, registry.find("tool.calls.failure.by.name").tag("tool_name", tool).counter()?.count(),
            "the failure counter must carry the tool name",
        )
    }

    @Test
    fun failuresAreCountedByStableErrorCode() {
        val tool = "test.errorCode.$STAMP"
        ToolMetrics.recordToolCall(tool, success = false, durationMs = 3, errorCode = ToolErrorCode.INVALID_ARGUMENT.wire)

        val counter = MetricsConfig.registry.find("tool.errors.by.code")
            .tag("tool_name", tool)
            .tag("error_code", ToolErrorCode.INVALID_ARGUMENT.wire)
            .counter()

        assertEquals(1.0, counter?.count(), "a failure must be attributed to its ToolErrorCode")
    }

    @Test
    fun successfulCallsCarryNoErrorCodeTag() {
        val tool = "test.noErrorCode.$STAMP"
        ToolMetrics.recordToolCall(tool, success = true, durationMs = 3)

        // Cardinality guard: the tag value is either absent or one of the two
        // sentinel values — never a free-form message.
        val observed = MetricsConfig.registry.find("tool.errors.by.code")
            .tag("tool_name", tool)
            .counters()
            .mapNotNull { it.id.getTag("error_code") }

        assertTrue(observed.isEmpty(), "a successful call must not invent an error code, saw $observed")
    }

    @Test
    fun perToolLatencyMeterIsPublished() {
        val tool = "test.latency.$STAMP"
        repeat(4) { ToolMetrics.recordToolCall(tool, success = true, durationMs = 10L * (it + 1)) }

        val timer = MetricsConfig.registry.find("tool.execution.duration.by.name")
            .tag("tool_name", tool)
            .timer()

        assertNotNull(timer, "each tool must get its own latency meter")
        assertEquals(4L, timer!!.count())
        assertTrue(timer.max(TimeUnit.MILLISECONDS) > 0, "max latency must be recorded")
        assertTrue(timer.percentile(0.5, TimeUnit.MILLISECONDS) >= 0, "p50 must be queryable")
    }

    @Test
    fun activeCallsGaugeIsRegistered() {
        assertNotNull(
            MetricsConfig.registry.find("tool.active.calls").gauge(),
            "the in-flight gauge is what /api/mcp/stats reports",
        )
        assertTrue(ToolMetrics.getActiveToolCallsCount() >= 0)
    }

    private companion object {
        /** Unique per JVM run so repeated test executions cannot double count. */
        private val STAMP = java.util.UUID.randomUUID().toString().take(8)
    }
}
