package ai.platon.pulsar.rest.api.service.scrape

import ai.platon.pulsar.agentic.model.ToolCall
import ai.platon.pulsar.agentic.tools.CustomToolRegistry
import ai.platon.pulsar.agentic.tools.builtin.ToolExecutor
import ai.platon.pulsar.rest.mcp.controller.CustomToolTargets

/**
 * The production [FormatToolDispatcher]: it runs a format step through the very
 * executor the MCP layer would pick for the same domain.
 *
 * Resolution mirrors `MCPToolController`'s custom-executor path:
 *
 * 1. the executor is looked up in [CustomToolRegistry] by domain — the same
 *    registry the MCP dispatcher and the plugin wiring populate, so a format step
 *    and a client-driven tool call cannot reach different code;
 * 2. the receiver comes from [CustomToolTargets], which hands `html_snapshot` the
 *    addressed `ManagedSession` and page-bound executors their driver;
 * 3. the addressed `sessionId` is also passed **in the arguments**, because an
 *    executor documents its own resolution and must not depend on which of the two
 *    channels the dispatch layer happened to use.
 *
 * A tool exception is rethrown as its **cause**: [ai.platon.pulsar.agentic.model.TcException]
 * wraps the real failure, and the engine's warnings are built from the message, so
 * rethrowing the wrapper would report "Exception when call tool: …" instead of the
 * actual reason.
 *
 * @property customToolTargets the receiver resolution shared with the MCP layer.
 * @property sessionId the addressed session, or null when the caller named none.
 */
class SessionFormatToolDispatcher(
    private val customToolTargets: CustomToolTargets,
    private val sessionId: String?,
) : FormatToolDispatcher {

    override suspend fun call(domain: String, method: String, args: Map<String, Any?>): Any? {
        val executor = executorFor(domain)
        val receiver = customToolTargets.resolve(executor, sessionId) ?: Any()
        val callArgs = LinkedHashMap<String, Any?>(args)
        if (!sessionId.isNullOrBlank()) {
            callArgs.putIfAbsent(SESSION_ID_ARG, sessionId)
        }

        val evaluate = executor.callFunctionOn(ToolCall(domain, method, callArgs), receiver)
        evaluate.exception?.let { failed ->
            // `TcException` is a data class, not a Throwable, and its `cause` is the
            // real failure. Throwing the wrapper would report "Exception when call
            // tool: …" instead of the actual reason the engine builds warnings from.
            throw failed.cause ?: IllegalStateException(failed.toString())
        }
        return evaluate.value
    }

    override fun supports(domain: String, method: String): Boolean =
        runCatching { executorFor(domain).getToolSpecs().containsKey(method) }.getOrDefault(false)

    private fun executorFor(domain: String): ToolExecutor =
        CustomToolRegistry.instance.get(domain)
            ?: throw IllegalArgumentException(
                "No tool executor is registered for domain '$domain' in this deployment"
            )

    companion object {
        /** The argument the whole tool layer uses to address a session. */
        const val SESSION_ID_ARG = "sessionId"

        /**
         * The production factory: one dispatcher per request, bound to the
         * addressed session.
         */
        fun factory(customToolTargets: CustomToolTargets): FormatStepRunnerFactory =
            FormatStepRunnerFactory { sessionId ->
                SnapshotFormatStepRunner(SessionFormatToolDispatcher(customToolTargets, sessionId))
            }
    }
}
