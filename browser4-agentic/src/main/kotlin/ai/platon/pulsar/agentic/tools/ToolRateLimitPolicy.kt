package ai.platon.pulsar.agentic.tools

import ai.platon.pulsar.agentic.mcp.McpToolNames
import ai.platon.pulsar.agentic.model.RateLimit
import ai.platon.pulsar.agentic.model.ToolSpec

/**
 * The default rate limit of a tool, derived from its domain and method.
 *
 * Deriving beats declaring: 249 tools would otherwise need 249 hand-written
 * limits kept in sync with the code, and the interesting distinctions are
 * domain-level anyway. A tool that genuinely needs its own limit sets
 * [ToolSpec.rateLimit], which always wins.
 *
 * The buckets:
 *
 * | Class | Examples | Limit |
 * |---|---|---|
 * | browser actions | `tab_navigate`, `tab_click`, `tab_type` | 10/s, burst 20 |
 * | task submission | `crawl_submit`, `swarm_submit`, `command_run` | 0.2/s, burst 2 |
 * | local work | `coding_*`, `fs_*` | 5/s, burst 10 |
 * | read-only | `*_status`, `*_result`, `*_list`, `tab_title`, `tab_current_url` | unlimited |
 *
 * Only the *inbound* call rate is limited. A limit is not a queue: a rejected
 * call returns `RATE_LIMITED` with `retryAfterMs` and nothing happens on the
 * browser, which keeps retries idempotent (the same reason argument validation
 * runs before dispatch).
 *
 * @property browserActionPerSecond sustained rate for page-driving tools
 * @property browserActionBurst tokens available at once for those tools
 */
object ToolRateLimitPolicy {

    /** Read-only methods: cheap, idempotent, and safe to call at any rate. */
    private val READ_ONLY_METHOD_PREFIXES = listOf(
        "status", "result", "list", "get", "read", "search", "query", "inspect",
        "summary", "stats", "doc", "help", "is", "exists", "current", "title",
        "count", "describe", "history",
    )

    /** Methods that create work elsewhere: crawl/swarm/command submission. */
    private val SUBMISSION_METHODS = setOf("submit", "run", "start", "schedule", "exec", "execute")

    /** Domains whose tools drive the shared browser session. */
    private val BROWSER_DOMAINS = setOf("tab", "browser", "page", "element")

    /** Domains whose tools do local, non-browser work. */
    private val LOCAL_DOMAINS = setOf("coding", "fs", "system")

    val BROWSER_ACTION = RateLimit(permitsPerSecond = 10.0, burst = 20)
    val TASK_SUBMISSION = RateLimit(permitsPerSecond = 0.2, burst = 2)
    val LOCAL_WORK = RateLimit(permitsPerSecond = 5.0, burst = 10)

    /** Unlimited: `RateLimit.unlimited` is the documented "do not limit" value. */
    val UNLIMITED = RateLimit(permitsPerSecond = 0.0, burst = 1)

    /**
     * The limit that applies to [spec].
     *
     * [ToolSpec.rateLimit] wins when present; otherwise the domain/method class
     * decides. Unknown domains (plugins) get [BROWSER_ACTION] only when they touch
     * the page — everything else is unlimited by default, so a third-party plugin
     * is never throttled by a rule written for browser automation.
     */
    fun limitFor(spec: ToolSpec, overrides: Map<String, RateLimit> = emptyMap()): RateLimit {
        overrides[spec.mcpToolName()]?.let { return it }
        overrides[spec.domain]?.let { return it }
        spec.rateLimit?.let { return it }

        if (isReadOnly(spec.method)) return UNLIMITED
        if (spec.domain in BROWSER_DOMAINS) return BROWSER_ACTION
        if (spec.method.lowercase() in SUBMISSION_METHODS) return TASK_SUBMISSION
        if (spec.domain in LOCAL_DOMAINS) return LOCAL_WORK
        return UNLIMITED
    }

    /** Whether the method only observes state (never mutates the page). */
    fun isReadOnly(method: String): Boolean {
        val lower = method.lowercase()
        if (lower in SUBMISSION_METHODS) return false
        return READ_ONLY_METHOD_PREFIXES.any { lower.startsWith(it) }
    }
}

/**
 * The MCP tool name of a spec (`crawl_submit`, `tab.navigate` → `navigate`).
 *
 * Same helper the servers register tools with, so rate-limit overrides, metrics
 * and logs all use the name a client actually sends.
 */
fun ToolSpec.mcpToolName(): String = McpToolNames.toMcpToolName(domain, method)
