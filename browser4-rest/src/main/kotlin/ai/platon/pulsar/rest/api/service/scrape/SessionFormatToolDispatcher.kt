package ai.platon.pulsar.rest.api.service.scrape

import ai.platon.pulsar.agentic.model.TcEvaluate
import ai.platon.pulsar.agentic.model.ToolCall
import ai.platon.pulsar.agentic.tools.CustomToolRegistry
import ai.platon.pulsar.agentic.tools.builtin.ToolExecutor
import ai.platon.pulsar.agentic.tools.AgentToolManager
import ai.platon.pulsar.agentic.agents.BasicBrowserAgent
import ai.platon.pulsar.rest.mcp.controller.CustomToolTargets
import ai.platon.pulsar.rest.session.PulsarSessionManager

/**
 * The production [FormatToolDispatcher]: it runs a format step exactly the way the
 * MCP layer would run the same tool call.
 *
 * Two dispatch routes, in the same order `MCPToolController` uses:
 *
 * 1. **[CustomToolRegistry]** — the plugin/business domains (`html_snapshot`,
 *    `markdown`, `image`, …), with the receiver resolved by [CustomToolTargets].
 * 2. **The session's [AgentToolManager]** — the **built-in** domains (`tab`,
 *    `browser`, `fs`, …), which are not in the registry at all.
 *
 * Missing the second route is not a subtle bug: every built-in method reports itself
 * unsupported, so a format that needs the live tab degrades with
 * `tab.screenshot is not supported in this deployment` while the tool is right
 * there. That is exactly what happened the first time `screenshot` ran for real —
 * the live stage had only ever been exercised against a fake runner.
 *
 * The addressed `sessionId` is also passed **in the arguments**, because an executor
 * documents its own resolution and must not depend on which of the two channels the
 * dispatch layer happened to use.
 *
 * A tool exception is rethrown as its **cause**: [ai.platon.pulsar.agentic.model.TcException]
 * wraps the real failure, and the engine's warnings are built from the message, so
 * rethrowing the wrapper would report "Exception when call tool: …" instead of the
 * actual reason.
 */
class SessionFormatToolDispatcher(
    private val customToolTargets: CustomToolTargets,
    private val sessionId: String?,
    /**
     * The addressed session's agent tool manager, which owns the built-in domains.
     * A lambda rather than a value because it is per-session and must be resolved
     * lazily — a request may run before its session exists.
     */
    private val agentToolManager: () -> AgentToolManager? = { null },
) : FormatToolDispatcher {

    override suspend fun call(domain: String, method: String, args: Map<String, Any?>): Any? {
        val executor = CustomToolRegistry.instance.get(domain)
        if (executor != null) {
            // A custom executor documents its own session resolution, so the addressed
            // session is passed in the arguments as a fallback beside the receiver.
            val callArgs = LinkedHashMap(args)
            if (!sessionId.isNullOrBlank()) {
                callArgs.putIfAbsent(SESSION_ID_ARG, sessionId)
            }
            val receiver = customToolTargets.resolve(executor, sessionId) ?: Any()
            return unwrap(executor.callFunctionOn(ToolCall(domain, method, callArgs), receiver))
        }

        // A built-in executor takes its session from the receiver/driver and validates
        // its arguments **strictly**, so an injected `sessionId` is rejected as
        // extraneous — `tab.screenshot` answers "Extraneous parameter 'sessionId' …
        // Allowed=[fullPage, format]". The MCP layer strips the transport arguments
        // before dispatch for exactly this reason; here the arguments are forwarded
        // untouched.
        val manager = agentToolManager() ?: throw IllegalArgumentException(
            "No tool executor is available for domain '$domain' in this deployment"
        )
        return unwrap(manager.execute(ToolCall(domain, method, LinkedHashMap(args))).evaluate)
    }

    override fun supports(domain: String, method: String): Boolean {
        CustomToolRegistry.instance.get(domain)?.let { return it.getToolSpecs().containsKey(method) }
        return runCatching { agentToolManager()?.getToolSpec(domain, method) != null }.getOrDefault(false)
    }

    private fun unwrap(evaluate: TcEvaluate): Any? {
        evaluate.exception?.let { failed ->
            // `TcException` is a data class, not a Throwable, and its `cause` is the
            // real failure.
            throw failed.cause ?: IllegalStateException(failed.toString())
        }
        return evaluate.value
    }

    companion object {
        /** The argument the whole tool layer uses to address a session. */
        const val SESSION_ID_ARG = "sessionId"

        /**
         * The production factory: one dispatcher per request, bound to the addressed
         * session, with both dispatch routes wired.
         */
        fun factory(
            sessionManager: PulsarSessionManager,
            customToolTargets: CustomToolTargets,
        ): FormatStepRunnerFactory = FormatStepRunnerFactory { sessionId ->
            SnapshotFormatStepRunner(
                SessionFormatToolDispatcher(customToolTargets, sessionId) {
                    agentToolManagerOf(sessionManager, sessionId)
                }
            )
        }

        /**
         * The addressed session's agent tool manager, or null when the session cannot
         * be resolved — the caller then reports the domain as unavailable rather than
         * guessing a different session's tools.
         */
        private fun agentToolManagerOf(sessionManager: PulsarSessionManager, sessionId: String?): AgentToolManager? {
            if (sessionId.isNullOrBlank()) return null
            val managed = runCatching { sessionManager.getOrRecoverSession(sessionId) }.getOrNull() ?: return null
            val agent = managed.agenticSession.companionAgent as? BasicBrowserAgent ?: return null
            return agent.agentToolManager
        }
    }
}
