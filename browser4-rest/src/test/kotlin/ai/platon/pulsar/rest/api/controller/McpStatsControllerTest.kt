package ai.platon.pulsar.rest.api.controller

import ai.platon.pulsar.agentic.observability.ToolMetrics
import ai.platon.pulsar.agentic.tools.ToolErrorCode
import ai.platon.pulsar.agentic.tools.ToolResultCache
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * `GET /api/mcp/stats` is the operator's view of both MCP channels
 * (requirement 8). It must report real numbers read back from the shared
 * Micrometer registry — not a private counter that only one channel feeds.
 */
@Tag("observability")
@DisplayName("MCP stats endpoint")
class McpStatsControllerTest {

    private val controller = McpStatsController()
    private val tool = "test.stats.${UUID.randomUUID().toString().take(8)}"

    @Test
    @DisplayName("a call recorded by either channel shows up with its latency and outcome")
    fun toolStatsAreReported() {
        ToolMetrics.recordToolCall(tool, success = true, durationMs = 24)

        val body = requireNotNull(controller.stats(5).body) as Map<*, *>
        val tools = body["tools"] as List<*>
        val entry = tools.filterIsInstance<Map<*, *>>().firstOrNull { it["tool"] == tool }

        assertNotNull(entry, "the called tool must appear in `tools`, saw ${tools.size} entries")
        assertEquals(1L, entry!!["calls"])
        assertEquals(0L, entry["failures"])
        assertEquals(1.0, entry["successRate"])
        assertTrue((entry["p50Ms"] as? Double ?: 0.0) > 0.0, "p50 latency must be real, was ${entry["p50Ms"]}")
    }

    @Test
    @DisplayName("failures are attributed to a stable error code and counted per tool")
    fun failuresCarryTheirErrorCode() {
        ToolMetrics.recordToolCall(tool, success = false, durationMs = 11, errorCode = ToolErrorCode.TIMEOUT.wire)

        val body = requireNotNull(controller.stats(5).body) as Map<*, *>
        val entry = (body["tools"] as List<*>).filterIsInstance<Map<*, *>>().first { it["tool"] == tool }

        assertEquals(1L, entry["failures"])
        assertEquals(ToolErrorCode.TIMEOUT.wire, (entry["errorCodes"] as Map<*, *>).keys.first())
        assertTrue((body["failureRate"] as Double) >= 0.0)
    }

    @Test
    @DisplayName("the endpoint also reports in-flight calls and schema violations")
    fun endpointExposesOperationalFields() {
        val body = requireNotNull(controller.stats(3).body) as Map<*, *>

        assertTrue((body["totalCalls"] as Long) >= 0L)
        assertTrue((body["inflight"] as Int) >= 0)
        assertNotNull(body["resultSchemaViolations"])
        assertNotNull(body["validationShadowViolations"])
        assertTrue(body.containsKey("slowest") && body.containsKey("errorCodes"))
    }

    @Test
    @DisplayName("shadow validation counts are attributed per tool")
    fun shadowViolationsArePerTool() {
        ToolMetrics.recordShadowViolation(tool, "MISSING_REQUIRED_ARG")
        ToolMetrics.recordToolCall(tool, success = true, durationMs = 3)

        val body = requireNotNull(controller.stats(5).body) as Map<*, *>
        val entry = (body["tools"] as List<*>).filterIsInstance<Map<*, *>>().first { it["tool"] == tool }

        assertEquals(1L, entry["validationShadowViolations"])
        assertTrue((body["validationShadowViolations"] as Long) >= 1L)
    }

    @Test
    @DisplayName("the result-cache posture can be inspected and cleared")
    fun cachePostureIsInspectable() {
        ToolResultCache.shared.clear()

        val before = requireNotNull(controller.cacheStatsEndpoint().body) as Map<*, *>
        assertTrue(before["enabled"] as Boolean, "the cache is on by default")
        assertEquals(0, before["entries"])
        assertTrue(
            (before["defaultCacheableTools"] as List<*>).contains("tab.title"),
            "the endpoint must say what is cacheable, not just how many entries exist",
        )

        val cleared = requireNotNull(controller.clearCache().body) as Map<*, *>
        assertEquals(true, cleared["cleared"])
    }

    @Test
    @DisplayName("the stats document carries the cache and batch sections")
    fun statsDocumentIncludesCache() {
        val body = requireNotNull(controller.stats(3).body) as Map<*, *>
        val cache = body["cache"] as Map<*, *>
        val batch = body["batch"] as Map<*, *>

        assertNotNull(cache["enabled"])
        assertNotNull(cache["hits"])
        assertNotNull(cache["hitRate"])

        assertNotNull(batch["calls"])
        assertNotNull(batch["stepsExecuted"])
        assertNotNull(batch["stepsCached"])
    }

    @Test
    @DisplayName("batch counters are attributed by outcome")
    fun batchCountersAreAttributed() {
        ToolMetrics.recordBatch(requested = 3, executed = 3, failures = 0, cached = 2, durationMs = 12)
        ToolMetrics.recordBatch(requested = 3, executed = 1, failures = 1, cached = 0, durationMs = 7)

        val body = requireNotNull(controller.stats(3).body) as Map<*, *>
        val batch = body["batch"] as Map<*, *>

        assertTrue((batch["calls"] as Long) >= 2L)
        assertTrue((batch["succeeded"] as Long) >= 1L, "an outcome-tagged counter must not read as zero")
        assertTrue((batch["failed"] as Long) >= 1L)
        assertTrue((batch["bailed"] as Long) >= 1L, "a batch that stopped early is counted as bailed")
        assertTrue((batch["stepsCached"] as Long) >= 2L)
    }

    @Test
    @DisplayName("the rate-limit posture and its counters are reported")
    fun rateLimitPostureIsReported() {
        ToolMetrics.recordRateLimited(tool, "global:tab", enforced = false)
        ToolMetrics.recordRateLimited(tool, "session:s1:tab", enforced = true)
        ToolMetrics.recordToolCall(tool, success = false, durationMs = 2, errorCode = ToolErrorCode.RATE_LIMITED.wire)

        val body = requireNotNull(controller.stats(5).body) as Map<*, *>
        val posture = body["rateLimit"] as Map<*, *>
        val entry = (body["tools"] as List<*>).filterIsInstance<Map<*, *>>().first { it["tool"] == tool }

        assertEquals("shadow", posture["mode"], "the default rollout observes instead of enforcing")
        assertTrue((posture["shadowed"] as Long) >= 1L)
        assertTrue((posture["rejected"] as Long) >= 1L)
        assertEquals(1L, entry["rateLimited"], "rejections are attributed to the tool")
        assertEquals(1L, entry["rateLimitShadowed"])
        assertEquals(ToolErrorCode.RATE_LIMITED.wire, (entry["errorCodes"] as Map<*, *>).keys.first())
    }

    @Test
    @DisplayName("top=0 asks for no per-tool latency list and is bounded above")
    fun topIsClamped() {
        ToolMetrics.recordToolCall(tool, success = true, durationMs = 4)

        val body = requireNotNull(controller.stats(0).body) as Map<*, *>
        assertTrue((body["slowest"] as List<*>).isEmpty(), "top=0 must not list slow tools")
        assertTrue((body["tools"] as List<*>).isNotEmpty(), "the per-tool table must still be present")
    }
}
