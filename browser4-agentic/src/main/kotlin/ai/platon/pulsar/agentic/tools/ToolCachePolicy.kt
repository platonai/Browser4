package ai.platon.pulsar.agentic.tools

import ai.platon.pulsar.agentic.model.ToolSpec

/**
 * Which tool results may be reused, and for how long.
 *
 * Caching the wrong call is a correctness bug, so this is an **allow-list**: a
 * method is cached only when its result is a pure function of the session state
 * and the tool's arguments — and any state-changing call in the same session
 * invalidates everything cached for it ([ToolResultCache.invalidateSession]).
 *
 * Deriving the defaults here beats declaring them on 137 specs, but a tool that
 * knows better can override with [ToolSpec.cacheable] / [ToolSpec.cacheTtlMs].
 *
 * Deliberately **not** cached by default:
 * - anything that mutates the page (navigation, input, dialogs, storage);
 * - large payloads (`html_snapshot_*`, `screenshot`, `pdf`) — a cache holding
 *   megabytes per entry is a memory leak with extra steps;
 * - `consoleMessages` / `networkRequests`, whose `clear` argument makes them
 *   non-idempotent in practice.
 */
object ToolCachePolicy {

    /** Page reads pinned to the current document; invalidated by any page action. */
    const val PAGE_READ_TTL_MS = 1_000L

    /** Task status changes on its own; a short TTL only smooths polling loops. */
    const val TASK_STATUS_TTL_MS = 500L

    /** `domain.method` → TTL (ms), the lowest-precedence source. */
    private val DEFAULT_TTL_MS: Map<String, Long> = mapOf(
        "tab.title" to PAGE_READ_TTL_MS,
        "tab.currentUrl" to PAGE_READ_TTL_MS,
        "tab.url" to PAGE_READ_TTL_MS,
        "tab.ariaSnapshot" to PAGE_READ_TTL_MS,
        "tab.exists" to PAGE_READ_TTL_MS,
        "tab.isVisible" to PAGE_READ_TTL_MS,
        "tab.isEnabled" to PAGE_READ_TTL_MS,
        "tab.isChecked" to PAGE_READ_TTL_MS,
        "tab.getText" to PAGE_READ_TTL_MS,
        "tab.getAttribute" to PAGE_READ_TTL_MS,
        "tab.dialogStatus" to PAGE_READ_TTL_MS,
        "tab.frameList" to PAGE_READ_TTL_MS,

        "crawl.status" to TASK_STATUS_TTL_MS,
        "crawl.result" to TASK_STATUS_TTL_MS,
        "command.status" to TASK_STATUS_TTL_MS,
        "command.result" to TASK_STATUS_TTL_MS,
        "swarm.status" to TASK_STATUS_TTL_MS,
        "swarm.result" to TASK_STATUS_TTL_MS,
    )

    /**
     * The TTL of [spec], or `null` when its result must not be reused.
     *
     * Precedence: [ToolSpec.cacheTtlMs] → the domain/method default → (when the
     * spec explicitly says `cacheable = true`) a page-read TTL. `cacheable = false`
     * and a non-positive TTL both mean "never cache".
     */
    fun ttlMs(spec: ToolSpec, multiplier: Double = 1.0): Long? {
        if (spec.cacheable == false) return null

        val declared = spec.cacheTtlMs
        if (declared != null && declared <= 0L) return null

        val base = declared
            ?: DEFAULT_TTL_MS["${spec.domain}.${spec.method}"]
            ?: if (spec.cacheable == true) PAGE_READ_TTL_MS else return null

        return (base * multiplier.coerceAtLeast(0.0)).toLong().coerceAtLeast(1L)
    }

    /** Whether a result of [spec] may be served from the cache. */
    fun cacheable(spec: ToolSpec, multiplier: Double = 1.0): Boolean = ttlMs(spec, multiplier) != null

    /** The methods cached by default — reported by `/api/mcp/cache/stats`. */
    fun defaultCacheableTools(): List<String> = DEFAULT_TTL_MS.keys.sorted()
}
