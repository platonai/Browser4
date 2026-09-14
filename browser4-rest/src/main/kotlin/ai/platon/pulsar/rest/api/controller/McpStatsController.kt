package ai.platon.pulsar.rest.api.controller

import ai.platon.pulsar.agentic.observability.MetricsConfig
import ai.platon.pulsar.agentic.observability.ToolMetrics
import ai.platon.pulsar.agentic.tools.specs.ToolResultValidator
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.CrossOrigin
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.concurrent.TimeUnit

/**
 * Observability endpoint for the MCP tool interface (requirement 8).
 *
 * The numbers come from the registry the tool meters are currently bound to —
 * `ai.platon.pulsar.agentic.observability.ToolMetrics.currentRegistry()`. Both
 * channels write the same meters (channel A: standard MCP server, channel B:
 * `/mcp/call-tool`), so a call is counted exactly once whichever channel served
 * it. Inside Spring the registry is the application's own
 * ([ai.platon.pulsar.rest.config.McpToolMetricsConfiguration]), which also makes
 * the tool metrics visible to
 * `/actuator/metrics` and — when `micrometer-registry-prometheus` ships in the
 * bundle — to `/actuator/prometheus`.
 *
 * `p50Ms`/`p95Ms`/`p99Ms` are real percentiles: the per-tool timer publishes
 * `0.5/0.95/0.99` (`ToolMetrics.perToolTimer`). A tool that has not been called
 * yet reports `null` rather than a misleading `0`.
 *
 * ```
 * curl -s localhost:8182/api/mcp/stats | jq
 * curl -s localhost:8182/api/mcp/stats?top=20
 * ```
 */
@RestController
@CrossOrigin
@RequestMapping(path = ["/api/mcp"], produces = ["application/json"])
class McpStatsController {

    private val registry: MeterRegistry get() = ToolMetrics.currentRegistry()

    /**
     * Per-tool call counts, failure codes and latency percentiles.
     *
     * @param top how many of the slowest tools (by p95) to list separately
     */
    @GetMapping("/stats")
    fun stats(@RequestParam(name = "top", defaultValue = "10") top: Int): ResponseEntity<Any> {
        val limit = top.coerceIn(0, 100)
        val totalCalls = counterSum("tool.calls.total")
        val totalFailures = counterSum("tool.calls.failure")
        val byTool = perToolStats()

        return ResponseEntity.ok(
            linkedMapOf<String, Any?>(
                "totalCalls" to totalCalls,
                "totalFailures" to totalFailures,
                "inflight" to ToolMetrics.getActiveToolCallsCount(),
                "failureRate" to if (totalCalls == 0L) 0.0 else totalFailures.toDouble() / totalCalls,
                "distinctTools" to byTool.size,
                "validationFailures" to counterSum("tool.validation.failures"),
                "resultSchemaViolations" to ToolResultValidator.totalViolations(),
                "errorCodes" to errorCodeTotals(),
                "registry" to registry.javaClass.simpleName,
                "prometheus" to MetricsConfig.isPrometheus(registry),
                "slowest" to byTool.sortedByDescending { it["p95Ms"] as? Double ?: -1.0 }.take(limit),
                "tools" to byTool.sortedByDescending { it["calls"] as? Long ?: 0L },
            )
        )
    }

    /** `tool_name` → latency, call counts and failure codes. */
    private fun perToolStats(): List<Map<String, Any?>> {
        val names = linkedSetOf<String>()
        names += tagValues("tool.calls.success.by.name")
        names += tagValues("tool.calls.failure.by.name")
        names += tagValues("tool.execution.duration.by.name")
        names += tagValues("tool.errors.by.code")

        return names.map { name ->
            val calls = counterSum("tool.calls.success.by.name", name) +
                counterSum("tool.calls.failure.by.name", name)
            val failures = counterSum("tool.calls.failure.by.name", name)
            val snapshot = registry.find("tool.execution.duration.by.name")
                .tag("tool_name", name)
                .timers()
                .maxByOrNull { it.count() }
                ?.takeSnapshot()
                ?.takeIf { it.count() > 0L }
            val percentiles = snapshot?.percentileValues()?.associateBy({ it.percentile() }, { it.value(TimeUnit.MILLISECONDS) })
                ?: emptyMap()

            linkedMapOf<String, Any?>(
                "tool" to name,
                "calls" to calls,
                "failures" to failures,
                "successRate" to if (calls == 0L) 1.0 else (calls - failures).toDouble() / calls,
                "p50Ms" to percentiles[0.5],
                "p95Ms" to percentiles[0.95],
                "p99Ms" to percentiles[0.99],
                "maxMs" to snapshot?.max(TimeUnit.MILLISECONDS),
                "errorCodes" to errorCodeTotals(name),
            )
        }
    }

    /** `error_code` → count, for one tool or for every tool. */
    private fun errorCodeTotals(toolName: String? = null): Map<String, Long> {
        val search = registry.find("tool.errors.by.code")
        if (toolName != null) search.tag("tool_name", toolName)
        return search.counters()
            .groupBy { it.id.getTag("error_code") ?: "UNKNOWN" }
            .mapValues { (_, counters) -> counters.sumOf { it.count().toLong() } }
            .toSortedMap()
    }

    /** Sums every counter registered under [name], optionally for one tool. */
    private fun counterSum(name: String, toolName: String? = null): Long {
        val search = registry.find(name)
        if (toolName != null) search.tag("tool_name", toolName)
        return search.counters().sumOf { it.count().toLong() }
    }

    /** All distinct `tool_name` tag values seen for [name]. */
    private fun tagValues(name: String): List<String> = registry.find(name)
        .meters()
        .mapNotNull { it.id.getTag("tool_name") }
}
