package ai.platon.pulsar.agentic.permission

/**
 * Thrown when a tool call is blocked by a DENY rule.
 *
 * All existing call sites of [ai.platon.pulsar.agentic.tools.AgentToolManager.execute]
 * already catch [Exception], so this propagates naturally as a tool error.
 */
class PermissionDeniedException(
    val decision: PermissionDecision.Denied,
) : SecurityException(decision.reason)

/**
 * Thrown when a tool call matches an ASK rule but no [PermissionAskHandler] is wired.
 *
 * The caller (MCP controller, CLI, agent loop) can catch this and surface it as a tool
 * error. When an ask handler IS wired, the handler is invoked instead and this exception
 * is never thrown.
 */
class PermissionRequestedException(
    val request: PermissionRequest,
) : Exception("Permission requested for ${request.domain}.${request.method}: ${request.message}")
