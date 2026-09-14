package ai.platon.pulsar.agentic.tools

import ai.platon.pulsar.agentic.model.ToolSpec
import ai.platon.pulsar.agentic.observability.ToolMetrics
import ai.platon.pulsar.common.getLogger
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Reuse cache for idempotent read tools (requirement 10).
 *
 * A call is identified by `(sessionId, tool, canonicalArgs, specVersion)`, so a
 * cached answer is served only to the session that produced it, only when the
 * client asked the same question, and never after the tool's contract changed.
 *
 * **Invalidation is the safety net, the TTL is only a backstop**: any call that is
 * not itself cacheable — navigation, input, storage, task submission — clears the
 * session's entries, because it may have changed what the reads would report. A
 * session's entries are also dropped when it closes.
 *
 * Escape hatches, in the order a caller reaches for them:
 * - per call: `cache: false` in the arguments;
 * - per deployment: `-Dmcp.cache.enabled=false`;
 * - per tool: `ToolSpec.cacheable = false` / `cacheTtlMs = 0`;
 * - operationally: `DELETE /api/mcp/cache` (see `McpStatsController`).
 *
 * The cache is bounded ([maxEntries], LRU by insertion order) and stores rendered
 * text only — a tool whose payload is large is not cacheable by policy, so an entry
 * cannot blow up the heap.
 */
class ToolResultCache(
    private val enabledProvider: () -> Boolean = Companion::enabled,
    private val ttlMultiplierProvider: () -> Double = Companion::ttlMultiplier,
    private val maxEntriesProvider: () -> Int = { maxEntries },
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** One reusable answer. */
    data class Cached(val text: String, val ageMs: Long, val specVersion: String)

    private class Entry(val text: String, val specVersion: String, val createdAt: Long, val expiresAt: Long)

    private val entries = ConcurrentHashMap<String, Entry>()

    private val hits = AtomicLong()
    private val misses = AtomicLong()

    val enabled: Boolean get() = enabledProvider()

    /**
     * The cached answer for this call, or `null` when it must be executed.
     *
     * A hit is a *served* answer: it counts towards `tool.cache.hits`, and an entry
     * that has expired is dropped so the next call recomputes it.
     *
     * @param bypass `true` when the client asked for fresh data (`cache: false`);
     *   the transport parses that flag, so the cache never guesses from argument
     *   spellings
     */
    fun get(spec: ToolSpec, sessionId: String?, args: Map<String, Any?>, bypass: Boolean = false): Cached? {
        if (bypass || !enabled) return null
        // A self-managed tool (batch.run) does its own per-step lookups.
        if (ToolCachePolicy.selfManaged(spec)) return null
        if (!ToolCachePolicy.cacheable(spec, ttlMultiplierProvider())) return null

        val key = key(spec, sessionId, args) ?: return null
        val entry = entries[key] ?: run { misses.incrementAndGet(); return null }

        val now = clock()
        if (now >= entry.expiresAt || entry.specVersion != specVersion(spec)) {
            entries.remove(key)
            misses.incrementAndGet()
            return null
        }

        hits.incrementAndGet()
        ToolMetrics.recordCacheAccess(spec.mcpToolName(), hit = true)
        return Cached(entry.text, now - entry.createdAt, entry.specVersion)
    }

    /**
     * Store a successful result for reuse, or invalidate the session's entries.
     *
     * A tool the policy does not consider an idempotent read may have changed what
     * every read in that session reports (a click, a navigation, a form fill), so it
     * drops the session's cache instead of writing to it. That is the invalidation
     * rule; the TTL is only the backstop for state the server cannot observe.
     *
     * @param bypass the client asked for fresh data: the answer is *not served* to
     *   that call ([get]), but it is still stored — a forced refresh is exactly the
     *   moment the cache should learn the new value
     */
    fun put(
        spec: ToolSpec,
        sessionId: String?,
        args: Map<String, Any?>,
        text: String,
        success: Boolean,
        bypass: Boolean = false,
    ) {
        if (!enabled) return

        // A self-managed tool (batch.run) judges cacheability per step: invalidating
        // here would wipe the reads its own read-only steps just refreshed.
        if (ToolCachePolicy.selfManaged(spec)) return

        // Invalidate first, and regardless of the outcome: a click that failed
        // halfway may still have changed the page.
        val ttl = ToolCachePolicy.ttlMs(spec, ttlMultiplierProvider())
        if (ttl == null) {
            invalidateSession(sessionId)
            return
        }
        if (!success) return

        val key = key(spec, sessionId, args) ?: return
        val now = clock()
        entries[key] = Entry(text, specVersion(spec), now, now + ttl)
        evictIfCrowded()
    }

    /** Drop every entry of one session (a page action, or the session closing). */
    fun invalidateSession(sessionId: String?) {
        val prefix = "${sessionId ?: "-"}|"
        val removed = entries.keys.filter { it.startsWith(prefix) }
        removed.forEach { entries.remove(it) }
        if (removed.isNotEmpty()) {
            ToolMetrics.recordCacheInvalidation(removed.size)
            logger.debug("tool.cache invalidated {} entries of session {}", removed.size, sessionId)
        }
    }

    /** Drop everything (manual clear, or a deployment-wide setting changed). */
    fun clear() = entries.clear()

    /** Hit/miss counters plus the current size, for `/api/mcp/cache/stats`. */
    fun stats(): Stats = Stats(
        enabled = enabled,
        entries = entries.size,
        hits = hits.get(),
        misses = misses.get(),
        ttlMultiplier = ttlMultiplierProvider(),
    )

    data class Stats(
        val enabled: Boolean,
        val entries: Int,
        val hits: Long,
        val misses: Long,
        val ttlMultiplier: Double,
    ) {
        val hitRate: Double get() = if (hits + misses == 0L) 0.0 else hits.toDouble() / (hits + misses)
    }

    /**
     * The cache key: session, tool, canonical arguments and the spec version.
     *
     * Arguments are canonicalised (sorted, transport arguments dropped, nested
     * structures ordered) and hashed, so the key length does not depend on the
     * payload and two spellings of the same call share one entry.
     */
    private fun key(spec: ToolSpec, sessionId: String?, args: Map<String, Any?>): String? {
        val canonical = canonicalArgs(args) ?: return null
        return "${sessionId ?: "-"}|${spec.mcpToolName()}|${shortHash(canonical)}|${specVersion(spec)}"
    }

    /** The tool's contract fingerprint: change the spec, and cached answers expire. */
    private fun specVersion(spec: ToolSpec): String =
        shortHash("${spec.expression}|${spec.returnType}|${spec.outputSchema.orEmpty()}")

    private fun canonicalArgs(args: Map<String, Any?>): String? = runCatching {
        args.entries
            .filter { (name, _) -> name !in TRANSPORT_ARGS && name != "cache" }
            .sortedBy { it.key }
            .joinToString(",") { (name, value) -> "$name=${canonicalValue(value)}" }
    }.getOrNull()

    private fun canonicalValue(value: Any?): String = when (value) {
        null -> "null"
        is Map<*, *> -> value.entries
            .map { (k, v) -> "${k}=${canonicalValue(v)}" }
            .sorted()
            .joinToString(",", "{", "}")

        is Collection<*> -> value.joinToString(",", "[", "]") { canonicalValue(it) }
        is Array<*> -> value.joinToString(",", "[", "]") { canonicalValue(it) }
        else -> value.toString()
    }

    private fun shortHash(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .take(6)
        .joinToString("") { "%02x".format(it) }

    /** Keep the map bounded: the oldest insertions go first. */
    private fun evictIfCrowded() {
        val max = maxEntriesProvider().coerceAtLeast(1)
        if (entries.size <= max) return
        val excess = entries.size - max
        entries.entries
            .sortedBy { it.value.createdAt }
            .take(excess)
            .forEach { entries.remove(it.key) }
        ToolMetrics.recordCacheEviction(excess.toLong())
    }

    companion object {
        private val logger = getLogger(ToolResultCache::class)

        /** Arguments the transport owns; they never take part in the key. */
        private val TRANSPORT_ARGS = setOf("sessionId")

        /** The instance both MCP channels share inside one JVM. */
        val shared: ToolResultCache by lazy { ToolResultCache() }

        /** `-Dmcp.cache.enabled` — off disables both reading and writing. */
        fun enabled(): Boolean = System.getProperty("mcp.cache.enabled", "true").toBoolean()

        /** `-Dmcp.cache.ttlMultiplier` — shrink or stretch every TTL for tuning. */
        fun ttlMultiplier(): Double =
            System.getProperty("mcp.cache.ttlMultiplier")?.toDoubleOrNull()?.coerceIn(0.0, 100.0) ?: 1.0

        /** `-Dmcp.cache.maxEntries` — upper bound on cached answers. */
        val maxEntries: Int
            get() = System.getProperty("mcp.cache.maxEntries")?.toIntOrNull()?.coerceIn(1, 100_000) ?: 2_000
    }
}
