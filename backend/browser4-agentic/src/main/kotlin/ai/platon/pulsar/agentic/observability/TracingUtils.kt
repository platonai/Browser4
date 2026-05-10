package ai.platon.pulsar.agentic.observability

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.context.Context

/**
 * Utility functions for distributed tracing with OpenTelemetry.
 * 
 * Provides convenient helpers for creating and managing spans:
 * - Automatic span lifecycle management
 * - Error recording and status setting
 * - Attribute setting helpers
 * - Context propagation
 * 
 * Example usage:
 * ```kotlin
 * // Basic span
 * TracingUtils.withSpan("operation-name") {
 *     // Your code here
 * }
 * 
 * // Span with attributes
 * TracingUtils.withSpan("agent.act", mapOf("action" to "click", "element" to "button")) {
 *     performAction()
 * }
 * 
 * // Manual span management
 * val span = TracingUtils.startSpan("custom-operation")
 * try {
 *     // Your code
 *     span.addEvent("Important event occurred")
 * } catch (e: Exception) {
 *     TracingUtils.recordError(span, e)
 *     throw e
 * } finally {
 *     span.end()
 * }
 * ```
 */
object TracingUtils {
    
    private val tracer: Tracer = OpenTelemetryConfig.tracer
    
    /**
     * Execute a block within a new span.
     * The span is automatically ended when the block completes or throws an exception.
     * 
     * @param spanName Name of the span
     * @param attributes Optional attributes to add to the span
     * @param spanKind The kind of span (default: INTERNAL)
     * @param block The code to execute within the span
     * @return Result of the block
     */
    inline fun <T> withSpan(
        spanName: String,
        attributes: Map<String, String> = emptyMap(),
        spanKind: SpanKind = SpanKind.INTERNAL,
        block: (Span) -> T
    ): T {
        val span = startSpan(spanName, attributes, spanKind)
        return try {
            val result = block(span)
            span.setStatus(StatusCode.OK)
            result
        } catch (e: Exception) {
            recordError(span, e)
            throw e
        } finally {
            span.end()
        }
    }
    
    /**
     * Execute a suspending block within a new span.
     * 
     * @param spanName Name of the span
     * @param attributes Optional attributes to add to the span
     * @param spanKind The kind of span (default: INTERNAL)
     * @param block The suspending code to execute within the span
     * @return Result of the block
     */
    suspend inline fun <T> withSpanSuspend(
        spanName: String,
        attributes: Map<String, String> = emptyMap(),
        spanKind: SpanKind = SpanKind.INTERNAL,
        crossinline block: suspend (Span) -> T
    ): T {
        val span = startSpan(spanName, attributes, spanKind)
        return try {
            val result = block(span)
            span.setStatus(StatusCode.OK)
            result
        } catch (e: Exception) {
            recordError(span, e)
            throw e
        } finally {
            span.end()
        }
    }
    
    /**
     * Start a new span with the given name and attributes.
     * The caller is responsible for ending the span.
     * 
     * @param spanName Name of the span
     * @param attributes Optional attributes to add to the span
     * @param spanKind The kind of span (default: INTERNAL)
     * @return The created span
     */
    fun startSpan(
        spanName: String,
        attributes: Map<String, String> = emptyMap(),
        spanKind: SpanKind = SpanKind.INTERNAL
    ): Span {
        val spanBuilder = tracer.spanBuilder(spanName)
            .setSpanKind(spanKind)
        
        // Add attributes
        attributes.forEach { (key, value) ->
            spanBuilder.setAttribute(key, value)
        }
        
        return spanBuilder.startSpan()
    }
    
    /**
     * Record an error in the given span and set the span status to ERROR.
     * 
     * @param span The span to record the error in
     * @param exception The exception to record
     */
    fun recordError(span: Span, exception: Exception) {
        span.recordException(exception)
        span.setStatus(StatusCode.ERROR, exception.message ?: "Unknown error")
    }
    
    /**
     * Add a string attribute to the span.
     * 
     * @param span The span to add the attribute to
     * @param key The attribute key
     * @param value The attribute value
     */
    fun setAttribute(span: Span, key: String, value: String) {
        span.setAttribute(key, value)
    }
    
    /**
     * Add a long attribute to the span.
     * 
     * @param span The span to add the attribute to
     * @param key The attribute key
     * @param value The attribute value
     */
    fun setAttribute(span: Span, key: String, value: Long) {
        span.setAttribute(key, value)
    }
    
    /**
     * Add a boolean attribute to the span.
     * 
     * @param span The span to add the attribute to
     * @param key The attribute key
     * @param value The attribute value
     */
    fun setAttribute(span: Span, key: String, value: Boolean) {
        span.setAttribute(key, value)
    }
    
    /**
     * Add an event to the span.
     * 
     * @param span The span to add the event to
     * @param eventName Name of the event
     * @param attributes Optional attributes for the event
     */
    fun addEvent(span: Span, eventName: String, attributes: Map<String, String> = emptyMap()) {
        if (attributes.isEmpty()) {
            span.addEvent(eventName)
        } else {
            val attrs = Attributes.builder()
            attributes.forEach { (key, value) ->
                attrs.put(AttributeKey.stringKey(key), value)
            }
            span.addEvent(eventName, attrs.build())
        }
    }
    
    /**
     * Get the current span from the context.
     * 
     * @return The current span, or null if no span is active
     */
    fun getCurrentSpan(): Span {
        return Span.current()
    }
    
    /**
     * Check if tracing is enabled.
     * 
     * @return true if tracing is enabled, false otherwise
     */
    fun isTracingEnabled(): Boolean {
        return System.getenv("OTEL_TRACES_ENABLED")?.toBoolean() ?: true
    }
}
