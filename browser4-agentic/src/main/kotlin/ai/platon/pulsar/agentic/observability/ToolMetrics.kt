package ai.platon.pulsar.agentic.observability

import ai.platon.pulsar.common.getLogger
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Business metrics collector for MCP tool calls — the single source of the
 * numbers reported by `GET /api/mcp/stats` and exported to Prometheus.
 *
 * Both MCP channels feed these meters, so the numbers are comparable across
 * channel A (standard MCP server) and channel B (private `/mcp/call-tool`):
 *
 * | Meter | Type | Tags | Meaning |
 * |---|---|---|---|
 * | `tool.calls.total` | counter | `component` | every dispatched call |
 * | `tool.calls.success` / `tool.calls.failure` | counter | `component` | outcome split |
 * | `tool.calls.success.by.name` / `tool.calls.failure.by.name` | counter | `tool_name` | outcome per tool |
 * | `tool.errors.by.code` | counter | `tool_name`, `error_code` | stable `ToolErrorCode` |
 * | `tool.execution.duration` | timer | `component` | global latency |
 * | `tool.execution.duration.by.name` | timer | `tool_name` | p50/p95/p99 per tool |
 * | `tool.active.calls` | gauge | `component` | in-flight calls |
 * | `tool.validation.failures[.by.type]` | counter | `tool_name`, `validation_type` | argument rejected before dispatch |
 * | `tool.validation.shadow.violations` | counter | `tool_name`, `validation_type` | argument violation seen but **not** enforced (built-in spec in shadow mode) |
 * | `tool.rate.limits` | counter | `tool_name`, `kind`(rejected/shadow) | throttled calls |
 * | `tool.rate.limits.by.scope` | counter | `scope_type`(session/global), `kind` | which bucket bound |
 * | `tool.cache.access` | counter | `tool_name`, `result`(hit/miss) | result-cache lookups |
 * | `tool.cache.invalidations` / `tool.cache.evictions` | counter | — | entries dropped (state change / size bound) |
 * | `batch.calls` / `batch.calls.by.outcome` / `batch.calls.bailed` | counter | `outcome`(succeeded/failed) | batch requests |
 * | `batch.steps` / `batch.steps.by.kind` | counter | `kind`(requested/executed/failed/cached) | per-step accounting |
 * | `batch.duration` | timer | — | end-to-end batch latency |
 * | `session.active` | gauge | — | live browser sessions (supplier registered by the deployment) |
 * | `async.queue.depth` | gauge | — | long-running tool tasks executing right now |
 *
 * Cardinality is bounded on purpose: `tool_name` is a closed set (the specs
 * registered at startup) and `error_code` is a 14-value enum — never a raw
 * message, session id or argument value.
 *
 * Example usage:
 * ```kotlin
 * // Record tool call
 * ToolMetrics.recordToolCall("browser.click", true, 250)
 *
 * // Record with timer
 * val result = ToolMetrics.recordToolCallTimed("browser.navigate") {
 *     // Execute tool
 *     performNavigation()
 * }
 *
 * // Record validation failure
 * ToolMetrics.recordValidationFailure("browser.click", "invalid_selector")
 * ```
 */
object ToolMetrics {

    private val logger = getLogger(ToolMetrics::class)

    /**
     * The backing registry. Rebindable at startup via [bindTo]: inside Spring the
     * meters must live in the application's own registry, otherwise
     * `/actuator/metrics` (and `/actuator/prometheus`, when the registry is on the
     * classpath) would report every metric except the tool ones.
     */
    @Volatile
    private var registry: MeterRegistry = MetricsConfig.registry

    // Gauges
    val activeToolCallsCount = AtomicInteger(0)

    /**
     * Gauge suppliers registered by the deployment, keyed by meter name, so a
     * [bindTo] can install them on the registry Spring actually scrapes.
     */
    private val supplierGauges = ConcurrentHashMap<String, () -> Number>()

    /** Description per supplier gauge, replayed on rebinding. */
    private val supplierGaugeDescriptions = ConcurrentHashMap<String, String>()

    init {
        bindGauge(registry)
    }

    /**
     * Rebinds every tool meter to [meterRegistry].
     *
     * Meters are looked up per call through the current binding, so a rebind
     * takes effect immediately; counters already created on the previous
     * registry simply stop receiving updates. Called once at startup by the
     * REST layer when a Spring `MeterRegistry` bean exists.
     *
     * @return true when the binding changed
     */
    fun bindTo(meterRegistry: MeterRegistry): Boolean {
        if (meterRegistry === registry) return false
        registry = meterRegistry
        bindGauge(meterRegistry)
        supplierGauges.forEach { (name, supplier) ->
            registerSupplierGaugeOn(
                meterRegistry, name,
                supplierGaugeDescriptions[name] ?: name, supplier
            )
        }
        logger.info("Tool metrics bound to {}", meterRegistry::class.simpleName)
        return true
    }

    /** The registry the tool meters are currently written to. */
    fun currentRegistry(): MeterRegistry = registry

    private fun bindGauge(target: MeterRegistry) {
        // Re-registering an existing Gauge id is a no-op that logs a warning, so
        // check first — `bindTo` may be called more than once in tests.
        if (target.find("tool.active.calls").gauge() == null) {
            Gauge.builder("tool.active.calls", activeToolCallsCount, AtomicInteger::toDouble)
                .tag("component", "tool")
                .description("Number of currently executing tool calls")
                .register(target)
        }
    }

    /**
     * Record a tool call execution.
     *
     * @param toolName The name of the tool (e.g., "browser.click", "system.execute")
     * @param success Whether the call succeeded
     * @param durationMs Duration in milliseconds
     * @param errorCode Stable failure code (`ToolErrorCode.wire`), when the call
     *   failed — exposed as the `tool.errors.by.code` counter so a dashboard can
     *   tell a retryable failure from a client mistake.
     */
    fun recordToolCall(toolName: String, success: Boolean, durationMs: Long, errorCode: String? = null) {
        registry.counter("tool.calls.total", "component", "tool").increment()

        if (success) {
            registry.counter("tool.calls.success", "component", "tool").increment()
            registry.counter("tool.calls.success.by.name", "tool_name", toolName).increment()
        } else {
            registry.counter("tool.calls.failure", "component", "tool").increment()
            registry.counter("tool.calls.failure.by.name", "tool_name", toolName).increment()
        }
        errorCode?.takeIf { it.isNotBlank() }?.let { code ->
            registry.counter(
                "tool.errors.by.code",
                "tool_name", toolName,
                "error_code", code,
            ).increment()
        }

        val duration = java.time.Duration.ofMillis(durationMs)
        registry.timer("tool.execution.duration", "component", "tool").record(duration)
        perToolTimer(toolName).record(duration)
    }

    /**
     * One latency meter per tool, deliberately **without** a `success` tag: a
     * single meter per tool keeps the percentile series directly readable by
     * `/api/mcp/stats` and bounds cardinality. The success/failure split lives
     * in `tool.calls.success.by.name` / `tool.calls.failure.by.name`.
     */
    private fun perToolTimer(toolName: String): Timer =
        Timer.builder("tool.execution.duration.by.name")
            .tag("tool_name", toolName)
            // Percentiles are what `/api/mcp/stats` and the dashboards read.
            .publishPercentiles(0.5, 0.95, 0.99)
            .publishPercentileHistogram()
            .register(registry)

    /**
     * Record tool call execution using a timer.
     *
     * @param toolName The name of the tool
     * @param block The tool execution to time
     * @return Result of the block
     */
    inline fun <T> recordToolCallTimed(toolName: String, block: () -> T): T {
        activeToolCallsCount.incrementAndGet()

        val startTime = System.currentTimeMillis()
        var success = false

        return try {
            block().also { success = true }
        } finally {
            activeToolCallsCount.decrementAndGet()
            recordToolCall(toolName, success, System.currentTimeMillis() - startTime)
        }
    }

    /**
     * Record a cache lookup outcome (requirement 10).
     *
     * @param toolName The advertised tool name
     * @param hit `true` when the call was answered from the cache
     */
    fun recordCacheAccess(toolName: String, hit: Boolean) {
        registry.counter("tool.cache.access", "tool_name", toolName, "result", if (hit) "hit" else "miss").increment()
    }

    /** Record the entries dropped because a call may have changed the session state. */
    fun recordCacheInvalidation(entries: Int) {
        registry.counter("tool.cache.invalidations").increment(entries.toDouble())
    }

    /** Record the entries dropped to keep the cache bounded. */
    fun recordCacheEviction(entries: Long) {
        registry.counter("tool.cache.evictions").increment(entries.toDouble())
    }

    /**
     * Record a batch request (requirement 14).
     *
     * Counters are batched-oriented and deliberately separate from the per-tool
     * ones: a batch is one client request, and its steps also record their own
     * `tool.*` metrics as usual.
     *
     * @param requested steps the client asked for
     * @param executed steps actually run (fewer when `bail` stopped the batch)
     * @param failures steps that failed
     * @param cached steps served from the result cache
     */
    fun recordBatch(requested: Int, executed: Int, failures: Int, cached: Int, durationMs: Long) {
        // Every meter keeps a fixed set of tag keys: Prometheus rejects a name whose
        // tag keys vary between registrations, so totals and breakdowns are separate
        // meters rather than one meter with an optional tag.
        registry.counter("batch.calls").increment()
        registry.counter("batch.calls.by.outcome", "outcome", if (failures > 0) "failed" else "succeeded").increment()
        if (executed < requested) registry.counter("batch.calls.bailed").increment()

        registry.counter("batch.steps").increment(requested.toDouble())
        registry.counter("batch.steps.by.kind", "kind", "requested").increment(requested.toDouble())
        registry.counter("batch.steps.by.kind", "kind", "executed").increment(executed.toDouble())
        if (failures > 0) {
            registry.counter("batch.steps.by.kind", "kind", "failed").increment(failures.toDouble())
        }
        if (cached > 0) {
            registry.counter("batch.steps.by.kind", "kind", "cached").increment(cached.toDouble())
        }

        registry.timer("batch.duration").record(java.time.Duration.ofMillis(durationMs))
    }

    /**
     * Record a throttled call.
     *
     * @param toolName The advertised tool name
     * @param scope Which bucket bound: `session:<id>:<domain>` or `global:<domain>`
     * @param enforced `true` when the call was rejected, `false` when the limiter
     *   only observed it (shadow mode). Kept apart for the same reason as
     *   [recordShadowViolation]: a dispatched call is not a failure.
     */
    fun recordRateLimited(toolName: String, scope: String, enforced: Boolean) {
        val kind = if (enforced) "rejected" else "shadow"
        registry.counter("tool.rate.limits",
            "tool_name", toolName,
            "kind", kind
        ).increment()
        registry.counter("tool.rate.limits.by.scope",
            "scope_type", scope.substringBefore(':'),
            "kind", kind
        ).increment()
    }

    /**
     * Record a validation failure.
     *
     * @param toolName The name of the tool
     * @param validationType The type of validation that failed (e.g., "selector", "parameter")
     */
    fun recordValidationFailure(toolName: String, validationType: String) {
        registry.counter("tool.validation.failures", "component", "tool").increment()
        registry.counter("tool.validation.failures.by.type",
            "tool_name", toolName,
            "validation_type", validationType
        ).increment()
    }

    /**
     * Record a contract violation that was **observed but not enforced** — a
     * shadow-mode violation for a built-in tool whose spec mirrors the upstream
     * `WebDriver` interface.
     *
     * Kept separate from [recordValidationFailure] on purpose: a shadow violation
     * means the call was dispatched anyway, so folding it into the rejection
     * counter would make `/api/mcp/stats` claim failures that never happened.
     *
     * @param toolName The advertised tool name
     * @param validationType The stable [ai.platon.pulsar.agentic.tools.ToolErrorCode]
     *   wire value the violation maps to
     */
    fun recordShadowViolation(toolName: String, validationType: String) {
        registry.counter("tool.validation.shadow.violations",
            "tool_name", toolName,
            "validation_type", validationType
        ).increment()
    }

    /**
     * Record tool registration.
     *
     * @param toolName The name of the registered tool
     * @param toolType The type/category of the tool (e.g., "browser", "system", "custom")
     */
    fun recordToolRegistration(toolName: String, toolType: String) {
        registry.counter("tool.registrations",
            "tool_name", toolName,
            "tool_type", toolType
        ).increment()
    }

    /**
     * Get the current number of active tool calls.
     *
     * @return Number of active tool calls
     */
    fun getActiveToolCallsCount(): Int = activeToolCallsCount.get()

    // -------------------------------------------------------------------------
    // Gauges whose value only the deployment can know
    // -------------------------------------------------------------------------

    /**
     * Registers the **session gauge** (`session.active`).
     *
     * `browser4-agentic` cannot count browser sessions — that registry lives in the
     * REST layer — so the deployment hands over a supplier instead of this module
     * growing a dependency on it. Without a registered supplier the gauge is simply
     * absent, which is honest: a metric nobody can measure must not report `0`.
     *
     * @param supplier current number of live sessions; must be cheap to call,
     *   because a scrape calls it on every collection
     */
    fun registerSessionCountSupplier(supplier: () -> Number) {
        registerSupplierGauge("session.active", "Live browser sessions", supplier)
    }

    /**
     * Registers the **async back-pressure gauge** (`async.queue.depth`).
     *
     * The count is of long-running tool tasks currently executing (crawl/command
     * submissions); it is the number a dashboard watches before the rate limiter
     * starts rejecting traffic.
     */
    fun registerAsyncTaskCountSupplier(supplier: () -> Number) {
        registerSupplierGauge(
            "async.queue.depth",
            "Long-running tool tasks currently executing",
            supplier
        )
    }

    /**
     * Binds [supplier] to a gauge, on the current registry **and** on every
     * registry a later [bindTo] switches to — otherwise the deployer's gauges
     * would silently keep writing to the standalone registry after the Spring
     * rebinding.
     */
    private fun registerSupplierGauge(name: String, description: String, supplier: () -> Number) {
        supplierGauges[name] = supplier
        supplierGaugeDescriptions[name] = description
        registerSupplierGaugeOn(registry, name, description, supplier)
    }

    private fun registerSupplierGaugeOn(
        target: MeterRegistry,
        name: String,
        description: String,
        supplier: () -> Number,
    ) {
        // Re-registering an existing id is a no-op that logs a warning, so check
        // first — `bindTo` may be called more than once in tests.
        if (target.find(name).gauge() != null) return
        Gauge.builder(name) { supplier().toDouble() }
            .description(description)
            .register(target)
    }
}
