package ai.platon.pulsar.rest.mcp.controller

import ai.platon.pulsar.agentic.tools.builtin.ToolExecutor
import ai.platon.pulsar.core.api.WebDriver
import ai.platon.pulsar.rest.session.ManagedSession
import ai.platon.pulsar.rest.session.PulsarSessionManager
import ai.platon.pulsar.common.getLogger

/**
 * Resolves the receiver object a custom-domain tool must be invoked with.
 *
 * The private dispatcher (`MCPToolController`) and the standard MCP server
 * (`McpHttpServer` via `ToolTargetResolver`) both need this mapping, and they
 * must agree: a tool resolved one way through `/mcp/call-tool` and another way
 * through `POST /mcp` is a bug waiting to happen.
 *
 * Resolution rules:
 * - `WebDriver`-receiver executors (page-bound plugin tools, e.g. captcha) get
 *   the addressed session's driver;
 * - `PulsarSessionManager`-receiver executors (html_snapshot, webdb) get the
 *   resolved [ManagedSession] — they call back into the manager for drivers;
 * - everything else gets the Spring bean matching its declared `receiverClass`
 *   (`UserCommandExecutor`, `SkillService`, `CrawlService`, …), falling back to
 *   a placeholder for executors that ignore their receiver.
 *
 * @param sessionManager the REST-layer session registry
 * @param beanResolver resolves a Spring bean by type; returns `null` when absent
 */
class CustomToolTargets(
    private val sessionManager: PulsarSessionManager,
    private val beanResolver: (Class<*>) -> Any? = { null },
) {
    private val logger = getLogger(this)

    /** The receiver for [executor], or `null` when nothing usable is available. */
    fun resolve(executor: ToolExecutor, sessionId: String?): Any? {
        val receiverClass = executor.receiverClass
        val managed = resolveSession(sessionId)

        return when {
            receiverClass == WebDriver::class -> managed?.let { driverOf(it) } ?: PLACEHOLDER
            receiverClass == PulsarSessionManager::class -> managed ?: PLACEHOLDER
            else -> beanResolver(receiverClass.java) ?: PLACEHOLDER
        }
    }

    private fun resolveSession(sessionId: String?): ManagedSession? {
        if (sessionId.isNullOrBlank()) return null
        return runCatching { sessionManager.getOrRecoverSession(sessionId) }
            .onFailure { logger.warn("Session lookup failed | sessionId={} | {}", sessionId, it.message) }
            .getOrNull()
    }

    private fun driverOf(managed: ManagedSession): Any? =
        runCatching { managed.driver }
            .onFailure { logger.warn("Driver lookup failed | sessionId={} | {}", managed.sessionId, it.message) }
            .getOrNull()

    private companion object {
        /**
         * Passed to executors that declare a receiver they never read; `null`
         * would break the `receiver: Any` contract of `callFunctionOn`.
         */
        val PLACEHOLDER: Any = Any()
    }
}
