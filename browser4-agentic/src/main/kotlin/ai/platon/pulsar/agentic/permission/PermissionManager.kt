package ai.platon.pulsar.agentic.permission

import ai.platon.pulsar.agentic.model.ToolCall

/**
 * Central facade for the Browser4 Permission System.
 *
 * Wires together a [PermissionEvaluator] with a [ToolCallPermissionAnalyzer] and an
 * optional [PermissionAskHandler]. The manager starts **disabled** — all calls to
 * [check] return [PermissionDecision.Allowed] until a policy is installed.
 *
 * ## Usage
 *
 * ```kotlin
 * // Disabled by default (backward compatible)
 * val manager = PermissionManager.disabled("agent-1", "session-1")
 *
 * // Install a policy
 * val policy = PermissionDefaults.readOnlyProfile()
 * manager.installPolicy(policy)
 *
 * // Check a tool call
 * val decision = manager.check(toolCall, topDomain) // may throw
 * ```
 *
 * @property agentId the agent's UUID for scope matching
 * @property sessionId the session's identifier for scope matching
 */
class PermissionManager private constructor(
    private var evaluator: PermissionEvaluator?,
    private val analyzer: ToolCallPermissionAnalyzer,
    val agentId: String,
    val sessionId: String,
    private var askHandler: PermissionAskHandler?,
) {
    /** Whether a permission policy is currently active. */
    val isEnabled: Boolean get() = evaluator != null

    /**
     * Checks whether [tc] (already normalized, with resolved [topDomain]) is permitted.
     *
     * @return [PermissionDecision.Allowed] if the tool call may proceed
     * @throws PermissionDeniedException if a DENY rule matches
     * @throws PermissionRequestedException if an ASK rule matches but no handler is wired
     */
    suspend fun check(tc: ToolCall, topDomain: String): PermissionDecision {
        if (!isEnabled) {
            return PermissionDecision.Allowed(null, "permission manager is disabled")
        }

        val request = analyzer.analyze(tc, topDomain, agentId, sessionId)
        val decision = evaluator!!.evaluate(request)

        return when (decision) {
            is PermissionDecision.Allowed -> decision
            is PermissionDecision.Denied -> throw PermissionDeniedException(decision)
            is PermissionDecision.Ask -> handleAsk(decision)
        }
    }

    /**
     * Explains why a tool call would be allowed/denied/asked without actually checking.
     */
    fun explain(tc: ToolCall, topDomain: String): String {
        if (!isEnabled) {
            return "'${tc.domain}.${tc.method}' → ALLOW (permission manager is disabled)"
        }
        val request = analyzer.analyze(tc, topDomain, agentId, sessionId)
        return evaluator!!.explain(request)
    }

    /**
     * Installs a [PermissionPolicy], enabling the manager.
     */
    fun installPolicy(policy: PermissionPolicy) {
        evaluator = PermissionEvaluator(policy)
    }

    /**
     * Removes the current policy, returning to disabled state.
     */
    fun uninstallPolicy() {
        evaluator = null
    }

    /**
     * Sets or replaces the ask handler callback.
     */
    fun setAskHandler(handler: PermissionAskHandler?) {
        askHandler = handler
    }

    /**
     * Creates a copy of this manager with a different ask handler.
     */
    fun withAskHandler(handler: PermissionAskHandler?): PermissionManager {
        return PermissionManager(evaluator, analyzer, agentId, sessionId, handler)
    }

    // ---- private ----

    private suspend fun handleAsk(decision: PermissionDecision.Ask): PermissionDecision {
        val response = askHandler?.onPermissionRequest(decision.request)
        return when {
            response == null -> throw PermissionRequestedException(decision.request)
            response.granted -> PermissionDecision.Allowed(
                decision.rule, "granted by user${response.note?.let { ": $it" } ?: ""}"
            )
            else -> throw PermissionDeniedException(
                PermissionDecision.Denied(
                    decision.rule,
                    "denied by user${response.note?.let { ": $it" } ?: ""}"
                )
            )
        }
    }

    companion object {
        /**
         * Creates a disabled manager that allows everything.
         */
        fun disabled(agentId: String = "", sessionId: String = ""): PermissionManager {
            return PermissionManager(null, ToolCallPermissionAnalyzer(), agentId, sessionId, null)
        }

        /**
         * Creates an enabled manager with the given [policy].
         */
        fun create(
            policy: PermissionPolicy,
            agentId: String = "",
            sessionId: String = "",
            askHandler: PermissionAskHandler? = null,
        ): PermissionManager {
            val evaluator = PermissionEvaluator(policy)
            return PermissionManager(evaluator, ToolCallPermissionAnalyzer(), agentId, sessionId, askHandler)
        }
    }
}
