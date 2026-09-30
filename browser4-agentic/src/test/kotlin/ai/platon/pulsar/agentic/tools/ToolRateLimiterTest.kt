package ai.platon.pulsar.agentic.tools

import ai.platon.pulsar.agentic.mcp.McpToolNames
import ai.platon.pulsar.agentic.model.RateLimit
import ai.platon.pulsar.agentic.model.ToolSpec
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The limiter is a token bucket with two scopes and a staged rollout. These tests
 * pin the three things that decide whether it is safe to turn on: what each tool's
 * limit is, that a throttled call costs nothing, and that no mode other than
 * `error` ever changes a call's outcome.
 */
@DisplayName("Tool rate limiting")
class ToolRateLimiterTest {

    private var nowNanos = 0L

    private fun spec(domain: String, method: String, rateLimit: RateLimit? = null) =
        ToolSpec(domain = domain, method = method, description = "test", rateLimit = rateLimit)

    private fun limiter(
        mode: ToolRateLimiter.Mode = ToolRateLimiter.Mode.ERROR,
        overrides: Map<String, RateLimit> = emptyMap(),
    ) = ToolRateLimiter(modeProvider = { mode }, overrideProvider = { overrides }, clock = { nowNanos })

    private fun ToolRateLimiter.acquire(times: Int, spec: ToolSpec, sessionId: String? = "s1") =
        (1..times).map { acquire(spec, sessionId) }

    // ---------------------------------------------------------------------
    // What limit applies
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("the limit is derived from the tool's class")
    fun limitsAreDerivedFromTheToolClass() {
        val overrides = emptyMap<String, RateLimit>()

        assertEquals(
            ToolRateLimitPolicy.BROWSER_ACTION,
            ToolRateLimitPolicy.limitFor(spec("tab", "navigate"), overrides),
            "page-driving tools are the ones a rate limit protects the browser from",
        )
        assertEquals(
            ToolRateLimitPolicy.TASK_SUBMISSION,
            ToolRateLimitPolicy.limitFor(spec("crawl", "submit"), overrides),
        )
        assertEquals(
            ToolRateLimitPolicy.TASK_SUBMISSION,
            ToolRateLimitPolicy.limitFor(spec("command", "run"), overrides),
        )
        assertEquals(
            ToolRateLimitPolicy.LOCAL_WORK,
            ToolRateLimitPolicy.limitFor(spec("coding", "mvnBuild"), overrides),
        )
        assertEquals(
            ToolRateLimitPolicy.UNLIMITED,
            ToolRateLimitPolicy.limitFor(spec("crawl", "status"), overrides),
            "polling a task must never be throttled",
        )
        assertEquals(
            ToolRateLimitPolicy.UNLIMITED,
            ToolRateLimitPolicy.limitFor(spec("tab", "title"), overrides),
        )
        assertEquals(
            ToolRateLimitPolicy.UNLIMITED,
            ToolRateLimitPolicy.limitFor(spec("skill", "list"), overrides),
        )
    }

    @Test
    @DisplayName("a declared or configured limit wins over the derived one")
    fun declaredAndConfiguredLimitsWin() {
        val declared = RateLimit(1.0, 1)
        assertEquals(
            declared,
            ToolRateLimitPolicy.limitFor(spec("tab", "click", declared), emptyMap()),
            "spec.rateLimit wins",
        )
        assertEquals(
            RateLimit(0.5, 1),
            ToolRateLimitPolicy.limitFor(spec("tab", "click"), mapOf("click" to RateLimit(0.5, 1))),
            "an override by tool name wins",
        )
        assertEquals(
            ToolRateLimitPolicy.UNLIMITED,
            ToolRateLimitPolicy.limitFor(spec("tab", "click"), mapOf("tab" to ToolRateLimitPolicy.UNLIMITED)),
            "an override by domain wins",
        )
    }

    @Test
    @DisplayName("overrides parse `name=perSec/burst` and `name=off`, ignoring junk")
    fun overridesAreParsed() {
        val parsed = ToolRateLimiter.parseOverrides("click=5/10; crawl = 0.5/3 ;webdb=off;broken;nope=abc")

        assertEquals(RateLimit(5.0, 10), parsed["click"])
        assertEquals(RateLimit(0.5, 3), parsed["crawl"])
        assertEquals(ToolRateLimitPolicy.UNLIMITED, parsed["webdb"])
        assertFalse(parsed.containsKey("broken"))
        assertFalse(parsed.containsKey("nope"))
        assertTrue(ToolRateLimiter.parseOverrides(null).isEmpty())
        assertTrue(ToolRateLimiter.parseOverrides("  ").isEmpty())
    }

    // ---------------------------------------------------------------------
    // Token bucket behaviour
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("the burst is spent, then the sustained rate refills it")
    fun burstThenRefill() {
        val limiter = limiter()
        val navigate = spec("tab", "navigate")
        val burst = ToolRateLimitPolicy.BROWSER_ACTION.burst

        assertTrue(
            limiter.acquire(burst, navigate).all { !it.throttled },
            "the first $burst calls fit in the burst",
        )

        val denied = limiter.acquire(navigate, "s1")
        assertTrue(denied.throttled, "the bucket must run dry")
        assertTrue(denied.retryAfterMs in 1..200, "retryAfterMs=${denied.retryAfterMs} should be one token's worth")
        assertNotNull(denied.scope)

        // One token's worth of time at 10/s.
        nowNanos += 100_000_000
        assertFalse(limiter.acquire(navigate, "s1").throttled, "a refilled bucket must allow the call again")
    }

    @Test
    @DisplayName("a throttled call consumes nothing, so a retry is free")
    fun throttledCallsCostNothing() {
        val limiter = limiter()
        val click = spec("tab", "click")
        val burst = ToolRateLimitPolicy.BROWSER_ACTION.burst

        limiter.acquire(burst, click)
        repeat(5) { limiter.acquire(click, "s1") }

        // If the rejected calls had consumed tokens, waiting exactly one token's
        // worth of time would still be throttled.
        nowNanos += 100_000_000
        assertFalse(limiter.acquire(click, "s1").throttled)
    }

    @Test
    @DisplayName("one session is bounded on its own, and the fleet is bounded globally")
    fun sessionsAreIsolatedButGloballyBounded() {
        val limiter = limiter()
        val click = spec("tab", "click")
        val burst = ToolRateLimitPolicy.BROWSER_ACTION.burst

        // Session A spends its own budget ...
        limiter.acquire(burst, click, sessionId = "a")
        val spent = limiter.acquire(click, "a")
        assertTrue(spent.throttled, "the per-session bucket binds first")
        assertEquals("session:a:tab", spent.scope)

        // ... which must not throttle another session: the global bucket tolerates
        // `globalMultiplier` sessions' worth of traffic.
        assertFalse(limiter.acquire(click, "b").throttled, "another session has its own budget")

        // Once the fleet exhausts the global bucket, even a fresh session waits.
        listOf("c", "d", "e").forEach { session -> limiter.acquire(burst, click, session) }
        val global = limiter.acquire(click, "f")
        assertTrue(global.throttled)
        assertEquals("global:tab", global.scope, "the aggregate cap is what protects the backend")
    }

    @Test
    @DisplayName("unlimited tools and OFF mode never throttle, however hard they are called")
    fun unlimitedToolsAndOffModeNeverThrottle() {
        val limiter = limiter()
        val title = spec("tab", "title")

        assertTrue(limiter.acquire(500, title).none { it.throttled }, "read-only tools are unlimited")

        val off = ToolRateLimiter(
            modeProvider = { ToolRateLimiter.Mode.OFF },
            overrideProvider = { emptyMap() },
            clock = { nowNanos },
        )
        assertTrue(off.acquire(500, spec("tab", "click")).none { it.throttled })
    }

    // ---------------------------------------------------------------------
    // Rollout modes
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("shadow mode reports the finding without changing the outcome")
    fun shadowModeOnlyReports() {
        val limiter = limiter(mode = ToolRateLimiter.Mode.SHADOW)
        val click = spec("tab", "click")

        limiter.acquire(ToolRateLimitPolicy.BROWSER_ACTION.burst, click)
        val decision = limiter.acquire(click, "s1")

        assertTrue(decision.throttled, "the finding is reported in every mode")
        assertFalse(decision.enforced, "shadow mode must not reject")
    }

    @Test
    @DisplayName("error mode enforces, and the message tells the client how long to wait")
    fun errorModeEnforces() {
        val limiter = limiter(mode = ToolRateLimiter.Mode.ERROR)
        val submit = spec("crawl", "submit")

        limiter.acquire(ToolRateLimitPolicy.TASK_SUBMISSION.burst, submit)
        val decision = limiter.acquire(submit, "s1")

        assertTrue(decision.enforced)
        val message = decision.rejectionMessage("crawl_submit")
        assertTrue(message.contains("crawl_submit"), message)
        assertTrue(message.contains("${decision.retryAfterMs} ms"), message)
        assertEquals(
            5000L, decision.retryAfterMs,
            "0.2/s means one token every five seconds, and the burst is already spent",
        )
    }

    @Test
    @DisplayName("default mode is shadow: the rollout observes before it enforces")
    fun defaultModeIsShadow() {
        System.clearProperty("mcp.rateLimit.mode")
        assertEquals(ToolRateLimiter.Mode.SHADOW, ToolRateLimiter.Mode.fromSystemProperties())

        for ((value, expected) in listOf(
            "error" to ToolRateLimiter.Mode.ERROR,
            "off" to ToolRateLimiter.Mode.OFF,
            "nonsense" to ToolRateLimiter.Mode.SHADOW,
        )) {
            System.setProperty("mcp.rateLimit.mode", value)
            assertEquals(expected, ToolRateLimiter.Mode.fromSystemProperties(), "mode '$value'")
        }
        System.clearProperty("mcp.rateLimit.mode")
    }

    // ---------------------------------------------------------------------
    // Housekeeping
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("a session's buckets are released, and idle buckets are purged")
    fun bucketsDoNotAccumulate() {
        val limiter = limiter()
        val click = spec("tab", "click")

        limiter.acquire(click, "gone")
        assertTrue(limiter.snapshot().keys.any { it.startsWith("session:gone:") })
        limiter.forgetSession("gone")
        assertFalse(limiter.snapshot().keys.any { it.startsWith("session:gone:") })

        // A long-running server must not keep one bucket per session id forever.
        repeat(600) { index -> limiter.acquire(click, "session-$index") }
        nowNanos += 11L * 60 * 1_000_000_000
        limiter.acquire(click, "fresh")
        assertTrue(
            limiter.snapshot().size < 600,
            "idle buckets must be purged, saw ${limiter.snapshot().size}",
        )
        assertTrue(limiter.snapshot().keys.any { it.contains("fresh") }, "the live bucket survives")
    }

    @Test
    @DisplayName("the tool name used by overrides and metrics is the wire name")
    fun wireNameIsUsed() {
        assertEquals("navigate", McpToolNames.toMcpToolName("tab", "navigate"))
        assertEquals("crawl_submit", McpToolNames.toMcpToolName("crawl", "submit"))
        assertEquals("html_snapshot_capture", McpToolNames.toMcpToolName("html_snapshot", "capture"))
        assertNull(ToolRateLimiter.parseOverrides("")["anything"])
    }
}
