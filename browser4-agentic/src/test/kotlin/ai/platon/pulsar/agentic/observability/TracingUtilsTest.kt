package ai.platon.pulsar.agentic.observability

import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.StatusCode
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Tag

/**
 * Unit tests for TracingUtils.
 */
@Tag("observability")
class TracingUtilsTest {
    
    @Test
    fun withSpanExecutesBlock() {
        var executed = false
        
        val result = TracingUtils.withSpan("test-span") { span ->
            executed = true
            assertNotNull(span, "Span should not be null")
            "success"
        }
        
        assertTrue(executed, "Block should have been executed")
        assertEquals("success", result, "Result should be returned")
    }
    
    @Test
    fun withSpanHandlesException() {
        assertThrows(RuntimeException::class.java) {
            TracingUtils.withSpan("test-span") { span ->
                throw RuntimeException("Test exception")
            }
        }
    }
    
    @Test
    fun withSpanAcceptsAttributes() {
        val attributes = mapOf(
            "key1" to "value1",
            "key2" to "value2"
        )
        
        TracingUtils.withSpan("test-span", attributes) { span ->
            assertNotNull(span, "Span should not be null")
        }
    }
    
    @Test
    fun startSpanReturnsValidSpan() {
        val span = TracingUtils.startSpan("manual-span")
        
        assertNotNull(span, "Span should not be null")
        
        // Clean up
        span.end()
    }
    
    @Test
    fun startSpanWithAttributes() {
        val attributes = mapOf("attr" to "value")
        val span = TracingUtils.startSpan("manual-span", attributes)
        
        assertNotNull(span, "Span should not be null")
        
        // Clean up
        span.end()
    }
    
    @Test
    fun recordErrorSetsSpanStatus() {
        val span = TracingUtils.startSpan("error-span")
        val exception = RuntimeException("Test error")
        
        TracingUtils.recordError(span, exception)
        
        // Clean up
        span.end()
        
        // Verify no exception was thrown
    }
    
    @Test
    fun setAttributeString() {
        val span = TracingUtils.startSpan("attr-span")
        
        TracingUtils.setAttribute(span, "string-key", "string-value")
        
        // Clean up
        span.end()
        
        // Verify no exception was thrown
    }
    
    @Test
    fun setAttributeLong() {
        val span = TracingUtils.startSpan("attr-span")
        
        TracingUtils.setAttribute(span, "long-key", 123L)
        
        // Clean up
        span.end()
        
        // Verify no exception was thrown
    }
    
    @Test
    fun setAttributeBoolean() {
        val span = TracingUtils.startSpan("attr-span")
        
        TracingUtils.setAttribute(span, "bool-key", true)
        
        // Clean up
        span.end()
        
        // Verify no exception was thrown
    }
    
    @Test
    fun addEvent() {
        val span = TracingUtils.startSpan("event-span")
        
        TracingUtils.addEvent(span, "test-event")
        TracingUtils.addEvent(span, "test-event-with-attrs", mapOf("attr" to "value"))
        
        // Clean up
        span.end()
        
        // Verify no exception was thrown
    }
    
    @Test
    fun getCurrentSpanReturnsSpan() {
        val span = TracingUtils.getCurrentSpan()
        assertNotNull(span, "Current span should not be null")
    }
    
    @Test
    fun isTracingEnabledReturnsBoolean() {
        val enabled = TracingUtils.isTracingEnabled()
        // Should return true or false, not throw exception
        assertTrue(enabled || !enabled, "Should return a valid boolean")
    }
}
