package ai.platon.pulsar.agentic.observability

import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.Tracer

/**
 * The only place in this module that mentions an OpenTelemetry type on the tool-call
 * path, and the only class on that path a deployment without OpenTelemetry can avoid
 * loading: [ToolTracing] reaches it by name, and only after the API class turned out to
 * be present.
 *
 * Everything it needs from the outside is the [tracer], which is injectable so a test
 * can collect real exported spans instead of asserting on intent.
 */
class OtelSpanBackend @JvmOverloads constructor(
    private val tracer: Tracer = OpenTelemetryConfig.tracer,
) : ToolSpanBackend {

    /**
     * Whether this tracer records.
     *
     * Asked of the tracer itself (a probe span) instead of reading the configuration
     * here: with tracing disabled [OpenTelemetryConfig] hands out the API's no-op tracer,
     * and "is it enabled" then has exactly one honest answer for both the shipped
     * default and an explicitly disabled deployment.
     */
    override val isEnabled: Boolean by lazy {
        val probe = tracer.spanBuilder("tracing-probe").startSpan()
        try {
            probe.isRecording
        } finally {
            probe.end()
        }
    }

    override suspend fun <T> withSpan(
        spanName: String,
        tool: String,
        channel: String,
        sessionId: String?,
        block: suspend (ToolTracing.OutcomeSink) -> T,
    ): T {
        val span = tracer.spanBuilder(spanName)
            .setSpanKind(SpanKind.INTERNAL)
            .setAttribute(ToolTracing.ATTR_TOOL, tool)
            .setAttribute(ToolTracing.ATTR_CHANNEL, channel)
            .apply {
                // An absent session is left out rather than written as an empty string:
                // "no session" and "empty session id" are different facts, and a
                // dashboard grouping by session must not merge them.
                sessionId?.takeIf { it.isNotBlank() }?.let { setAttribute(ToolTracing.ATTR_SESSION, it) }
            }
            .startSpan()

        val sink = ToolTracing.OutcomeSink { outcome -> span.setAttribute(ToolTracing.ATTR_OUTCOME, outcome) }
        return try {
            val result = block(sink)
            span.setStatus(StatusCode.OK)
            result
        } catch (e: Throwable) {
            span.recordException(e)
            span.setStatus(StatusCode.ERROR, e.message ?: e::class.simpleName ?: "error")
            throw e
        } finally {
            span.end()
        }
    }
}
