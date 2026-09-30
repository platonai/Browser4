package ai.platon.pulsar.agentic.tools

import ai.platon.pulsar.agentic.model.RateLimit
import ai.platon.pulsar.agentic.model.ToolSpec
import ai.platon.pulsar.agentic.observability.ToolMetrics
import ai.platon.pulsar.common.getLogger
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil
import kotlin.math.min

/**
 * Token-bucket rate limiter for MCP tool calls, shared by both channels.
 *
 * Two buckets guard every call:
 * - `session:<sessionId|->|<domain>` — one client cannot monopolise a session;
 * - `global:<domain>` — many concurrent sessions cannot stampede the backend.
 *
 * Both use the limit [ToolRateLimitPolicy] derives for the tool, so the global
 * bucket only binds once several sessions run the same domain at once.
 *
 * ## Rollout: observe first (same policy as argument validation)
 *
 * ```
 * -Dmcp.rateLimit.mode=shadow|error|off   # default: shadow
 * -Dmcp.rateLimit.overrides="tab_click=5/10;crawl=0.5/3;webdb=off"
 * ```
 *
 * `shadow` counts and logs what it *would* have rejected but lets the call
 * through; `error` rejects with [ToolErrorCode.RATE_LIMITED] and a
 * `retryAfterMs`; `off` skips the limiter entirely. Batch and crawl workloads
 * are exactly what a wrong default would break, so the default observes.
 *
 * Rejection happens **before** dispatch: nothing runs on the browser for a call
 * that was throttled, which keeps a retry idempotent (the same contract as
 * argument validation).
 */
class ToolRateLimiter(
    /** Read per call so a test (or a runtime toggle) can change the mode. */
    private val modeProvider: () -> Mode = Mode.Companion::fromSystemProperties,
    /** Read per call so overrides can be changed without rebuilding the limiter. */
    private val overrideProvider: () -> Map<String, RateLimit> = { parseOverrides() },
    private val clock: () -> Long = System::nanoTime,
) {

    enum class Mode {
        /** No limiting at all. */
        OFF,

        /** Count and log what would be rejected, then allow the call. */
        SHADOW,

        /** Reject throttled calls with `RATE_LIMITED`. */
        ERROR;

        companion object {
            /** `-Dmcp.rateLimit.mode` — `shadow` (default), `error`, or `off`. */
            fun fromSystemProperties(): Mode =
                when (System.getProperty("mcp.rateLimit.mode", "shadow").trim().lowercase()) {
                    "off", "false", "none", "disabled" -> OFF
                    "error", "strict", "true", "enforce" -> ERROR
                    else -> SHADOW
                }
        }
    }

    /**
     * The limiter's verdict for one call.
     *
     * @property throttled whether a bucket ran out of tokens — *the finding*, in
     *   every mode; use [enforced] to decide whether to reject
     * @property retryAfterMs how long until one token is available
     * @property scope which bucket bound (`session…`/`global…`), `null` when unthrottled
     */
    data class Decision(
        val throttled: Boolean,
        val retryAfterMs: Long,
        val scope: String?,
        val limit: RateLimit,
        val mode: Mode,
    ) {
        /** Whether the caller must turn this into a `RATE_LIMITED` response. */
        val enforced: Boolean get() = throttled && mode == Mode.ERROR

        companion object {
            fun allowed(limit: RateLimit, mode: Mode) = Decision(false, 0, null, limit, mode)
        }

        /**
         * The message both channels send for an enforced rejection, so a client
         * sees the same text (and the same `retryAfterMs`) whichever channel
         * served the call.
         */
        fun rejectionMessage(toolName: String): String =
            "rate limit exceeded for $toolName (limit $limit); retry after $retryAfterMs ms"
    }

    private class Bucket(var tokens: Double, var lastRefillNanos: Long, var lastAccessNanos: Long)

    private val buckets = ConcurrentHashMap<String, Bucket>()

    /**
     * Take one token for a call to [spec].
     *
     * A call consumes a token from **both** buckets; when either is empty the call
     * is reported as throttled ([Decision.throttled]) and no token is consumed
     * from the other, so a rejected call costs nothing and can be retried.
     */
    fun acquire(spec: ToolSpec, sessionId: String?): Decision {
        val mode = modeProvider()
        val limit = ToolRateLimitPolicy.limitFor(spec, overrideProvider())
        if (mode == Mode.OFF || limit.unlimited) return Decision.allowed(limit, mode)

        val multiplier = globalMultiplier()
        val now = clock()
        // One session may spend `limit`; all sessions together may spend
        // `limit × multiplier`, so a single client cannot exceed the per-session
        // rate and a fleet of clients cannot exceed the domain's aggregate rate.
        val scoped = listOf(
            "session:${sessionId ?: "-"}:${spec.domain}" to limit,
            "global:${spec.domain}" to RateLimit(
                permitsPerSecond = limit.permitsPerSecond * multiplier,
                burst = (limit.burst * multiplier).coerceAtLeast(1),
            ),
        )

        // Check every bucket before consuming any, so a throttled call is a no-op.
        for ((key, bucketLimit) in scoped) {
            val denial = check(buckets[key], bucketLimit, now)
            if (denial != null) {
                purgeIfCrowded(now)
                return Decision(true, denial, key, limit, mode)
            }
        }
        scoped.forEach { (key, bucketLimit) -> consume(key, bucketLimit, now) }
        purgeIfCrowded(now)
        return Decision.allowed(limit, mode)
    }

    /** How many sessions' worth of traffic the global bucket tolerates. */
    private fun globalMultiplier(): Int {
        val raw = System.getProperty("mcp.rateLimit.globalMultiplier")
        return raw?.toIntOrNull()?.coerceIn(1, 64) ?: DEFAULT_GLOBAL_MULTIPLIER
    }

    /** Current buckets, for diagnostics (`GET /api/mcp/stats`). */
    fun snapshot(): Map<String, Double> = buckets.entries.associate { (key, bucket) -> key to bucket.tokens }

    /**
     * Log and count a throttled call.
     *
     * Called in every mode: in `shadow` the call proceeds but the finding is
     * recorded, in `error` it is recorded as a rejection. One line per throttled
     * call keeps this in the same order of magnitude as the call log itself.
     */
    fun report(toolName: String, decision: Decision) {
        if (!decision.throttled) return
        ToolMetrics.recordRateLimited(toolName, decision.scope ?: "unknown", decision.enforced)
        logger.warn(
            "tool.rate.limit tool={} scope={} limit={} retryAfterMs={} outcome={}",
            toolName, decision.scope, decision.limit, decision.retryAfterMs,
            if (decision.enforced) "REJECTED" else "SHADOW",
        )
    }

    /** Drop every bucket — sessions close, tests isolate. */
    fun reset() = buckets.clear()

    /** Release the buckets of one session, keeping the global ones. */
    fun forgetSession(sessionId: String) {
        val prefix = "session:$sessionId:"
        buckets.keys.filter { it.startsWith(prefix) }.forEach { buckets.remove(it) }
    }

    /** `retryAfterMs` for the bucket at [key], or `null` when a token is available now. */
    private fun check(bucket: Bucket?, limit: RateLimit, now: Long): Long? {
        if (bucket == null) return null
        synchronized(bucket) {
            refill(bucket, limit, now)
            if (bucket.tokens >= 1.0) return null
            val missing = 1.0 - bucket.tokens
            return ceil(missing / limit.permitsPerSecond * 1000.0).toLong().coerceAtLeast(1L)
        }
    }

    private fun consume(key: String, limit: RateLimit, now: Long) {
        val bucket = buckets.computeIfAbsent(key) { Bucket(limit.burst.toDouble(), now, now) }
        synchronized(bucket) {
            refill(bucket, limit, now)
            bucket.tokens = (bucket.tokens - 1.0).coerceAtLeast(0.0)
            bucket.lastAccessNanos = now
        }
    }

    private fun refill(bucket: Bucket, limit: RateLimit, now: Long) {
        val elapsedNanos = (now - bucket.lastRefillNanos).coerceAtLeast(0L)
        if (elapsedNanos == 0L) return
        val gained = elapsedNanos / 1_000_000_000.0 * limit.permitsPerSecond
        bucket.tokens = min(limit.burst.toDouble(), bucket.tokens + gained)
        bucket.lastRefillNanos = now
        bucket.lastAccessNanos = now
    }

    /**
     * Sessions come and go, so buckets must not accumulate: once the map is
     * crowded, buckets untouched for [IDLE_TTL_NANOS] are dropped. Without this a
     * long-running server would keep one bucket per session id forever.
     */
    private fun purgeIfCrowded(now: Long) {
        if (buckets.size <= MAX_BUCKETS) return
        buckets.entries.removeIf { (_, bucket) -> now - bucket.lastAccessNanos > IDLE_TTL_NANOS }
    }

    companion object {
        private val logger = getLogger(ToolRateLimiter::class)

        /** The instance both MCP channels share inside one JVM. */
        val shared: ToolRateLimiter by lazy { ToolRateLimiter() }

        private const val MAX_BUCKETS = 512
        private val IDLE_TTL_NANOS = 10L * 60 * 1_000_000_000

        /** `-Dmcp.rateLimit.globalMultiplier` — how many sessions the global bucket tolerates. */
        private const val DEFAULT_GLOBAL_MULTIPLIER = 4

        /**
         * `-Dmcp.rateLimit.overrides="tab_click=5/10;crawl=0.5/3;webdb=off"`.
         *
         * Each entry is `tool-or-domain=<permitsPerSecond>/<burst>` or
         * `tool-or-domain=off` for unlimited. Malformed entries are logged and
         * skipped rather than failing the call.
         */
        fun parseOverrides(raw: String? = System.getProperty("mcp.rateLimit.overrides")): Map<String, RateLimit> {
            if (raw.isNullOrBlank()) return emptyMap()
            return raw.split(';', ',')
                .mapNotNull { entry ->
                    val trimmed = entry.trim()
                    if (trimmed.isEmpty()) return@mapNotNull null
                    val name = trimmed.substringBefore('=').trim()
                    val value = trimmed.substringAfter('=', "").trim()
                    if (name.isEmpty() || value.isEmpty()) {
                        logger.warn("Ignoring malformed mcp.rateLimit.overrides entry '{}'", trimmed)
                        return@mapNotNull null
                    }
                    val limit = if (value.equals("off", true) || value == "0") {
                        ToolRateLimitPolicy.UNLIMITED
                    } else {
                        val perSecond = value.substringBefore('/').toDoubleOrNull()
                        val burst = value.substringAfter('/', "").toIntOrNull()
                            ?: ceil(perSecond ?: 0.0).toInt().coerceAtLeast(1)
                        if (perSecond == null || perSecond < 0.0) {
                            logger.warn("Ignoring malformed mcp.rateLimit.overrides value '{}'", trimmed)
                            return@mapNotNull null
                        }
                        RateLimit(perSecond, burst.coerceAtLeast(1))
                    }
                    name to limit
                }
                .toMap()
        }
    }
}
