package ai.platon.pulsar.agentic.observability

import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.sdk.OpenTelemetrySdk
import kotlinx.coroutines.runBlocking
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import io.opentelemetry.sdk.trace.export.SpanExporter
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Requirement 8.2: a tool call produces an `mcp.tool.call` span.
 *
 * The spans are collected by a small exporter written here, so the assertions are on
 * real exported `SpanData` rather than on the code's intent. Two things are checked
 * beyond the happy path, because both have bitten similar integrations:
 *
 * - **no tracer, no failure**: with tracing unavailable (the shipped bundle has no OTel
 *   SDK — it is an optional dependency) the block still runs and its result is returned;
 * - **errors propagate unchanged**: an exception marks the span failed *and* still
 *   reaches the caller.
 */
@Tag("Unit")
@Tag("Fast")
@DisplayName("tool call tracing")
class ToolTracingTest {

    /** Collects exported spans in memory. */
    private class CollectingExporter : SpanExporter {
        val spans = mutableListOf<SpanData>()
        override fun export(spans: Collection<SpanData>): CompletableResultCode {
            synchronized(this.spans) { this.spans += spans }
            return CompletableResultCode.ofSuccess()
        }

        override fun flush(): CompletableResultCode = CompletableResultCode.ofSuccess()
        override fun shutdown(): CompletableResultCode = CompletableResultCode.ofSuccess()
    }

    private val exporter = CollectingExporter()
    private val provider: SdkTracerProvider = SdkTracerProvider.builder()
        .addSpanProcessor(SimpleSpanProcessor.create(exporter))
        .build()

    private fun tracer() = OpenTelemetrySdk.builder().setTracerProvider(provider).build()
        .getTracer("test")

    @AfterEach
    fun restoreBackend() {
        ToolTracing.useBackendForTest(null)
        provider.close()
    }

    /**
     * The spans exported for the test's own calls.
     *
     * The backend asks its tracer whether it records by starting (and ending) a probe
     * span; that span is an implementation detail rather than part of the contract under
     * test, so it is filtered out here instead of being asserted around everywhere.
     */
    private fun exportedSpans(): List<SpanData> =
        synchronized(exporter.spans) { exporter.spans.filterNot { it.name == PROBE_SPAN_NAME } }

    private companion object {
        /** Name of the backend's recording probe — see [exportedSpans]. */
        const val PROBE_SPAN_NAME = "tracing-probe"
    }

    @Test
    @DisplayName("a tool call exports one mcp.tool.call span with the call's facts")
    fun spanCarriesTheCallFacts() = runBlocking {
        ToolTracing.useBackendForTest(OtelSpanBackend(tracer()))

        val result = ToolTracing.withSpan("crawl_submit", "B", "session-7") { outcome ->
            outcome.record("OK")
            "task-1"
        }

        assertEquals("task-1", result, "the block's value must pass through untouched")

        val span = exportedSpans().single()
        assertEquals(ToolTracing.SPAN_NAME, span.name)
        assertEquals("crawl_submit", span.attributes.get(io.opentelemetry.api.common.AttributeKey.stringKey(ToolTracing.ATTR_TOOL)))
        assertEquals("B", span.attributes.get(io.opentelemetry.api.common.AttributeKey.stringKey(ToolTracing.ATTR_CHANNEL)))
        assertEquals("session-7", span.attributes.get(io.opentelemetry.api.common.AttributeKey.stringKey(ToolTracing.ATTR_SESSION)))
        assertEquals("OK", span.attributes.get(io.opentelemetry.api.common.AttributeKey.stringKey(ToolTracing.ATTR_OUTCOME)))
        assertEquals(StatusCode.OK, span.status.statusCode)
    }

    @Test
    @DisplayName("the session attribute is omitted when there is no session")
    fun sessionAttributeIsOptional() = runBlocking {
        ToolTracing.useBackendForTest(OtelSpanBackend(tracer()))

        ToolTracing.withSpan("list_sessions", "B", null) { "ok" }

        val span = exportedSpans().single()
        assertFalse(
            span.attributes.asMap().keys.any { it.key == ToolTracing.ATTR_SESSION },
            "an absent session must not be reported as an empty one",
        )
    }

    @Test
    @DisplayName("the span API hands consumers no OpenTelemetry type to compile against")
    fun outcomeSinkKeepsTheOptionalDependencyOptional() {
        // The OTel API is an `optional` dependency of this module, so a consumer such as
        // browser4-rest cannot even compile against `Span` (that is not a hypothetical:
        // passing the span to the block broke `browser4-rest`'s compilation). The block
        // therefore receives an OutcomeSink — the only type it has to know about.
        val methods = ToolTracing::class.java.methods.map { it.name }
        assertEquals(true, methods.contains("withSpan"), "withSpan is the entry point")

        val withSpan = ToolTracing::class.java.methods.first { it.name == "withSpan" }
        val leaksOtel = withSpan.parameterTypes.any { it.name.startsWith("io.opentelemetry") }
        assertEquals(false, leaksOtel, "withSpan must not expose OTel types: ${withSpan.parameterTypes.toList()}")
    }

    @Test
    @DisplayName("a failure marks the span failed and still reaches the caller")
    fun failuresAreRecordedAndRethrown() = runBlocking {
        ToolTracing.useBackendForTest(OtelSpanBackend(tracer()))

        // assertThrows takes a non-suspending lambda, so the call is wrapped by hand.
        var thrown: Throwable? = null
        try {
            ToolTracing.withSpan<String>("tab_click", "A", "s1") { throw IllegalArgumentException("element not found") }
        } catch (e: Throwable) {
            thrown = e
        }
        assertEquals("element not found", thrown?.message)

        val span = exportedSpans().single()
        assertEquals(StatusCode.ERROR, span.status.statusCode)
        assertEquals(true, span.events.any { it.name == "exception" })
    }

    @Test
    @DisplayName("without a tracer the call still runs — tracing never fails a tool call")
    fun withoutTracerTheBlockStillRuns() = runBlocking {
        // No override and tracing is off by default (the SDK is an optional dependency),
        // which is exactly the shipped configuration.
        ToolTracing.useBackendForTest(null)

        var ran = false
        val result = ToolTracing.withSpan("title", "A", "s1") { outcome ->
            ran = true
            outcome.record("OK")
            "Example Domain"
        }

        assertEquals("Example Domain", result)
        assertEquals(true, ran, "the block must run even with no tracer")
        assertEquals(emptyList<SpanData>(), exportedSpans(), "nothing may be exported when tracing is unavailable")
    }
}
