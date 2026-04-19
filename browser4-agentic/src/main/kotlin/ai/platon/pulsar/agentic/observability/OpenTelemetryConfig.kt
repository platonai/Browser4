package ai.platon.pulsar.agentic.observability

import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import io.opentelemetry.context.propagation.ContextPropagators
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.resources.Resource
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor
import io.opentelemetry.semconv.ServiceAttributes
import java.util.concurrent.TimeUnit

/**
 * OpenTelemetry configuration for distributed tracing in pulsar-agentic module.
 *
 * This configuration:
 * - Initializes OpenTelemetry SDK with OTLP exporter
 * - Configures service name and version from environment
 * - Sets up W3C trace context propagation
 * - Provides tracer instances for instrumentation
 *
 * Configuration via environment variables:
 * - OTEL_EXPORTER_OTLP_ENDPOINT: OTLP collector endpoint (default: http://localhost:4317)
 * - OTEL_SERVICE_NAME: Service name (default: pulsar-agentic)
 * - OTEL_SERVICE_VERSION: Service version (default: 4.7.0-SNAPSHOT)
 * - OTEL_TRACES_ENABLED: Enable/disable tracing (default: true)
 *
 * Example usage:
 * ```kotlin
 * val tracer = OpenTelemetryConfig.tracer
 * val span = tracer.spanBuilder("operation-name").startSpan()
 * try {
 *     // Your code here
 * } finally {
 *     span.end()
 * }
 * ```
 */
object OpenTelemetryConfig {

    private val isTracingEnabled: Boolean =
        System.getenv("OTEL_TRACES_ENABLED")?.toBoolean() ?: true

    private val otlpEndpoint: String =
        System.getenv("OTEL_EXPORTER_OTLP_ENDPOINT") ?: "http://localhost:4317"

    private val serviceName: String =
        System.getenv("OTEL_SERVICE_NAME") ?: "pulsar-agentic"

    private val serviceVersion: String =
        System.getenv("OTEL_SERVICE_VERSION") ?: "4.7.0-SNAPSHOT"

    /**
     * OpenTelemetry SDK instance.
     */
    val openTelemetry: OpenTelemetry by lazy {
        if (!isTracingEnabled) {
            return@lazy OpenTelemetry.noop()
        }

        // Configure resource attributes
        val resource = Resource.getDefault().merge(
            Resource.create(
                Attributes.builder()
                    .put(ServiceAttributes.SERVICE_NAME, serviceName)
                    .put(ServiceAttributes.SERVICE_VERSION, serviceVersion)
                    .build()
            )
        )

        // Configure OTLP exporter
        val spanExporter = OtlpGrpcSpanExporter.builder()
            .setEndpoint(otlpEndpoint)
            .setTimeout(30, TimeUnit.SECONDS)
            .build()

        // Configure span processor
        val spanProcessor = BatchSpanProcessor.builder(spanExporter)
            .setScheduleDelay(100, TimeUnit.MILLISECONDS)
            .setMaxQueueSize(2048)
            .setMaxExportBatchSize(512)
            .setExporterTimeout(30, TimeUnit.SECONDS)
            .build()

        // Build tracer provider
        val tracerProvider = SdkTracerProvider.builder()
            .addSpanProcessor(spanProcessor)
            .setResource(resource)
            .build()

        // Build OpenTelemetry SDK
        OpenTelemetrySdk.builder()
            .setTracerProvider(tracerProvider)
            .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
            .buildAndRegisterGlobal()
    }

    /**
     * Tracer instance for pulsar-agentic instrumentation.
     */
    val tracer: Tracer by lazy {
        openTelemetry.getTracer("ai.platon.pulsar.agentic", serviceVersion)
    }

    /**
     * Shutdown the OpenTelemetry SDK and flush remaining spans.
     * Should be called on application shutdown.
     */
    fun shutdown() {
        if (isTracingEnabled && openTelemetry is OpenTelemetrySdk) {
            (openTelemetry as OpenTelemetrySdk).sdkTracerProvider.shutdown()
        }
    }
}
