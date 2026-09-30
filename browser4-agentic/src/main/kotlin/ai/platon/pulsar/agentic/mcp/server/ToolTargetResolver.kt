package ai.platon.pulsar.agentic.mcp.server

import ai.platon.pulsar.agentic.tools.builtin.ToolExecutor

/**
 * Resolves the receiver object a custom-domain tool must be invoked with.
 *
 * A tool that is advertised but cannot run is worse than an absent one: the
 * standard MCP server used to list every plugin/business domain while only the
 * ones whose receiver the agent happened to bind could execute — `memory_*`,
 * `experience_*`, `html_snapshot_*`, `webdb_*`, `crawl_*` and `command_*` all
 * failed with "no target object is available".
 *
 * The private dispatcher (browser4-rest) already knows how to resolve receivers
 * because it holds the Spring session registry and the service beans; this
 * interface lets the standard server reuse that knowledge instead of guessing:
 * before executing a custom-domain call the server asks the resolver for a
 * receiver and registers it as the domain's target.
 *
 * Implementations must return `null` when they cannot supply a receiver — the
 * dispatcher then falls back to the executor's own
 * [ToolExecutor.requiresReceiver] answer (service-backed executors run regardless).
 */
fun interface ToolTargetResolver {

    /**
     * @param executor the registry executor about to be invoked
     * @param sessionId the session handle the call addressed, or `null`
     * @return the receiver to bind for the executor's domain, or `null`
     */
    fun resolve(executor: ToolExecutor, sessionId: String?): Any?

    companion object {
        /** A resolver that never supplies a receiver (single-process / stdio deployments). */
        val NONE: ToolTargetResolver = ToolTargetResolver { _, _ -> null }
    }
}
