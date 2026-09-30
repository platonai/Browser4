package ai.platon.pulsar.agentic.observability

/**
 * The trace side of a tool call (requirement 8.2): one span per call, named
 * [SPAN_NAME], carrying the same facts the log lines carry — channel, tool, session,
 * and the outcome code.
 *
 * ## Why this file mentions no OpenTelemetry type, not even indirectly
 *
 * The OpenTelemetry API and SDK are **`optional` dependencies of `browser4-agentic`**,
 * so they are not transitive: `browser4-rest` neither compiles nor runs with them. All
 * three consequences were observed, in this order:
 *
 * 1. passing a `Span` into the caller's lambda broke `browser4-rest`'s **compilation**;
 * 2. returning `Span.current()` / calling `span.end()` here broke it at **runtime**
 *    (`NoClassDefFoundError: io/opentelemetry/api/trace/Span`);
 * 3. merely *touching* [OpenTelemetryConfig] broke it again
 *    (`NoClassDefFoundError: io/opentelemetry/api/OpenTelemetry`) — initializing that
 *    object loads the API types it holds.
 *
 * Hence: this object talks only in [OutcomeSink] and [ToolSpanBackend]; the class that
 * does mention OpenTelemetry ([OtelSpanBackend]) is loaded **by name and only after the
 * API class was found**; and the enablement rule lives in the backend too, so nothing
 * here reads the configuration. A tool call in a deployment without OpenTelemetry then
 * takes one `Class.forName` that fails and runs the block — no span, no error.
 */
object ToolTracing {

    /** The span name every tool call produces. */
    const val SPAN_NAME = "mcp.tool.call"

    const val ATTR_TOOL = "tool"
    const val ATTR_CHANNEL = "channel"
    const val ATTR_SESSION = "session"
    const val ATTR_OUTCOME = "outcome"

    /** The OTel classes whose presence decides whether spans are possible at all. */
    private val REQUIRED_CLASSES = listOf(
        "io.opentelemetry.api.trace.Span",
        // The SDK too, not just the API: the backend reads OpenTelemetryConfig, which
        // references SDK types, and loading it without them fails the same way.
        "io.opentelemetry.sdk.OpenTelemetrySdk",
    )

    /** The backend implementation, loaded reflectively — see the class note. */
    private const val BACKEND_CLASS = "ai.platon.pulsar.agentic.observability.OtelSpanBackend"

    /**
     * How a caller reports the outcome without ever touching an OpenTelemetry type.
     */
    fun interface OutcomeSink {
        /** Record how the call ended (`OK`, or the stable error code). */
        fun record(outcome: String)
    }

    @Volatile
    private var backendForTest: ToolSpanBackend? = null

    /** Resolved once; `null` means the optional dependency is not on the classpath. */
    private val classpathBackend: ToolSpanBackend? by lazy { loadBackend() }

    /** The effective backend: a test override when present, else what the classpath gives. */
    private val backend: ToolSpanBackend? get() = backendForTest ?: classpathBackend

    /** Whether a span will actually be produced. */
    fun isEnabled(): Boolean = backend?.isEnabled == true

    /**
     * Overrides the backend (tests), returning the previous one so it can be restored.
     */
    internal fun useBackendForTest(backend: ToolSpanBackend?): ToolSpanBackend? {
        val previous = backendForTest
        backendForTest = backend
        return previous
    }

    /**
     * Run [block] inside one [SPAN_NAME] span, or plainly when tracing is unavailable.
     *
     * The span records the tool, channel and session up front; [block] gets an
     * [OutcomeSink] to attach the outcome code. An exception marks the span failed and
     * is rethrown untouched — observability never changes the call's semantics.
     */
    suspend fun <T> withSpan(
        tool: String,
        channel: String,
        sessionId: String?,
        block: suspend (OutcomeSink) -> T,
    ): T {
        val backend = backend?.takeIf { it.isEnabled } ?: return block(OutcomeSink { })
        return backend.withSpan(SPAN_NAME, tool, channel, sessionId, block)
    }

    /**
     * Loads the OTel-backed implementation, or `null` when it cannot be used.
     *
     * `initialize = false` on the probe keeps the check free of side effects; everything
     * that needs the configuration lives in the backend, which is only reached once the
     * API turned out to be present.
     */
    private fun loadBackend(): ToolSpanBackend? {
        val classLoader = ToolTracing::class.java.classLoader
        val present = REQUIRED_CLASSES.all { name ->
            runCatching { Class.forName(name, false, classLoader) }.isSuccess
        }
        if (!present) return null

        return runCatching {
            Class.forName(BACKEND_CLASS, true, classLoader)
                .getDeclaredConstructor()
                .newInstance() as ToolSpanBackend
        }.getOrNull()
    }
}

/**
 * The span operations, expressed without a single OpenTelemetry type so the contract
 * can live in a module whose consumers do not depend on OpenTelemetry.
 */
interface ToolSpanBackend {

    /** Whether this backend records spans (false for a no-op tracer). */
    val isEnabled: Boolean

    /** Run [block] inside one span, reporting the outcome through [ToolTracing.OutcomeSink]. */
    suspend fun <T> withSpan(
        spanName: String,
        tool: String,
        channel: String,
        sessionId: String?,
        block: suspend (ToolTracing.OutcomeSink) -> T,
    ): T
}
