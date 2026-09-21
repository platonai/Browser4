package ai.platon.pulsar.agentic.observability

import ai.platon.pulsar.common.getLogger
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
 * OpenTelemetry configuration for distributed tracing in browser4-agentic module.
 *
 * This configuration:
 * - Initializes OpenTelemetry SDK with OTLP exporter
 * - Configures service name and version from environment
 * - Sets up W3C trace context propagation
 * - Provides tracer instances for instrumentation
 *
 * Configuration via environment variables:
 * - OTEL_TRACES_ENABLED: enable tracing (default: **false** — the SDK and the OTLP
 *   exporter are optional dependencies, so the shipped bundle cannot export spans
 *   unless they are added to it)
 * - OTEL_EXPORTER_OTLP_ENDPOINT: OTLP collector endpoint (default: http://localhost:4317)
 * - OTEL_SERVICE_NAME: Service name (default: browser4-agentic)
 * - OTEL_SERVICE_VERSION: Service version (default: 4.8.1-SNAPSHOT)
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

    private val logger = getLogger(OpenTelemetryConfig::class)

    /**
     * Whether tracing is on. **Opt-in**, because the default deployment cannot trace:
     * the OpenTelemetry SDK and the OTLP exporter are `optional` dependencies of this
     * module, so the shipped runtime bundle does not contain them, and there is no
     * collector listening on the default endpoint.
     *
     * Defaulting to on made every deployment walk the SDK path with classes that are
     * not there — `NoClassDefFoundError` the first time anything asked for a tracer,
     * i.e. the *tool call* would fail because *tracing* was unavailable. Set
     * `OTEL_TRACES_ENABLED=true` (with the SDK and a collector present) to opt in.
     */
    private val isTracingEnabled: Boolean =
        System.getenv("OTEL_TRACES_ENABLED")?.toBoolean() ?: false

    private val otlpEndpoint: String =
        System.getenv("OTEL_EXPORTER_OTLP_ENDPOINT") ?: "http://localhost:4317"

    private val serviceName: String =
        System.getenv("OTEL_SERVICE_NAME") ?: "browser4-agentic"

    private val serviceVersion: String =
        System.getenv("OTEL_SERVICE_VERSION") ?: "4.8.1-SNAPSHOT"

    /**
     * Whether tracing is enabled by configuration (before checking the classpath).
     */
    val tracingRequested: Boolean get() = isTracingEnabled

    /**
     * OpenTelemetry SDK instance — or the API's no-op when tracing is off **or** the SDK
     * classes are absent.
     *
     * The SDK is an optional dependency, so "the class is missing" is a normal
     * deployment, not an error: it must degrade to no tracing rather than throw
     * `NoClassDefFoundError` into a tool call. `runCatching` catches `Throwable`
     * precisely because `NoClassDefFoundError` is an `Error`, not an `Exception`.
     */
    val openTelemetry: OpenTelemetry by lazy {
        if (!isTracingEnabled) {
            return@lazy OpenTelemetry.noop()
        }

        runCatching { buildSdk() }.getOrElse { e ->
            logger.info(
                "OpenTelemetry tracing requested but the SDK is unavailable ({}); continuing without spans",
                e.message ?: e::class.simpleName,
            )
            OpenTelemetry.noop()
        }
    }

    private fun buildSdk(): OpenTelemetry {
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
        return OpenTelemetrySdk.builder()
            .setTracerProvider(tracerProvider)
            .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
            .buildAndRegisterGlobal()
    }

    /**
     * Tracer instance for browser4-agentic instrumentation.
     */
    val tracer: Tracer by lazy {
        openTelemetry.getTracer("ai.platon.pulsar.agentic", serviceVersion)
    }

    /**
     * Shutdown the OpenTelemetry SDK and flush remaining spans.
     * Should be called on application shutdown.
     *
     * Defensive for the same reason as [openTelemetry]: the SDK types may not be on the
     * classpath, and a shutdown path must not throw into the caller's shutdown.
     */
    fun shutdown() {
        runCatching {
            if (isTracingEnabled && openTelemetry is OpenTelemetrySdk) {
                (openTelemetry as OpenTelemetrySdk).sdkTracerProvider.shutdown()
            }
        }.onFailure { e ->
            logger.debug("OpenTelemetry shutdown skipped: {}", e.message ?: e::class.simpleName)
        }
    }
}
