package ai.platon.pulsar.rest.mcp.controller.handler

import ai.platon.browser4.common.B4Constants
import ai.platon.browser4.common.B4Constants.DEFAULT_SESSION_ID
import ai.platon.pulsar.agent.tool.CommandToolExecutor
import ai.platon.pulsar.agent.tool.UserCommandExecutor
import ai.platon.pulsar.agentic.agents.BasicBrowserAgent
import ai.platon.pulsar.agentic.model.ToolCall
import ai.platon.pulsar.agentic.tools.AgentToolManager
import ai.platon.pulsar.rest.session.PulsarSessionManager
import ai.platon.pulsar.rest.mcp.controller.ArgumentNormalizerFactory
import ai.platon.pulsar.rest.mcp.controller.FRONTEND_TOOL_NAME_ALIASES
import ai.platon.pulsar.rest.mcp.controller.MCPConstants
import ai.platon.pulsar.rest.mcp.controller.dto.MCPToolCallRequest
import ai.platon.pulsar.rest.mcp.controller.dto.MCPToolCallResponse
import ai.platon.pulsar.rest.mcp.controller.handler.SessionManagementHandler.Companion.errorResponse
import ai.platon.pulsar.rest.mcp.controller.handler.SessionManagementHandler.Companion.textResponse
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity

class CommandHandler(
    private val sessionManager: PulsarSessionManager,
    private val commandExecutor: UserCommandExecutor,
    private val objectMapper: ObjectMapper,
) {
    private val logger = LoggerFactory.getLogger(CommandHandler::class.java)

    private val commandToolExecutor = CommandToolExecutor()

    suspend fun handleCommandRun(request: MCPToolCallRequest): ResponseEntity<MCPToolCallResponse> =
        dispatchToCommandToolExecutor("command_run", "run", request.arguments ?: emptyMap())

    suspend fun handleCommandBatch(request: MCPToolCallRequest): ResponseEntity<MCPToolCallResponse> {
        val args = request.arguments ?: emptyMap()
        val stepMaps = (args["steps"] as? List<*>)?.mapIndexed { index, step ->
            val stepMap = step.toAnyMap()
                ?: throw IllegalArgumentException("Batch step at index $index must be an object.")
            index to stepMap
        } ?: throw IllegalArgumentException("command_batch requires a 'steps' array.")

        val bail = args["bail"].toBooleanValue() ?: false
        val currentSessionId = args["sessionId"]?.toString()?.takeIf { it.isNotBlank() }
        val results = mutableListOf<BatchExecutionResult>()
        var stoppedOnError = false

        for ((index, step) in stepMaps) {
            val startedAt = System.nanoTime()
            val result = try {
                executeBatchStep(index, step, currentSessionId)
            } catch (e: Exception) {
                BatchExecutionResult(index = index, ok = false, error = e.message ?: "Unknown batch execution error")
            }
            val durationMillis = (System.nanoTime() - startedAt) / 1_000_000

            results += result.copy(durationMillis = durationMillis)
            if (!result.ok && bail) {
                stoppedOnError = true
                break
            }
        }

        val body = BatchExecutionResponse(
            sessionId = currentSessionId,
            failureCount = results.count { !it.ok },
            stoppedOnError = stoppedOnError,
            results = results,
        )
        return ResponseEntity.ok(textResponse(jacksonObjectMapper().writeValueAsString(body)))
    }

    suspend fun handleCommandStatus(request: MCPToolCallRequest): ResponseEntity<MCPToolCallResponse> =
        dispatchToCommandToolExecutor("command_status", "status", request.arguments ?: emptyMap())

    suspend fun handleCommandResult(request: MCPToolCallRequest): ResponseEntity<MCPToolCallResponse> =
        dispatchToCommandToolExecutor("command_result", "result", request.arguments ?: emptyMap())

    // =========================================================================
    // Internal batch execution (self-contained — looks up sessions directly)
    // =========================================================================

    private suspend fun executeBatchStep(
        index: Int,
        step: Map<String, Any?>,
        currentSessionId: String?,
    ): BatchExecutionResult {
        val op = step[MCPConstants.KEY_OP]?.toString()
            ?: throw IllegalArgumentException(MCPConstants.ERROR_MISSING_OP)

        when (op) {
            MCPConstants.OP_OPEN, MCPConstants.OP_CLOSE -> {
                throw IllegalArgumentException(String.format(MCPConstants.ERROR_BATCH_NON_DOM_OP, op))
            }
        }

        return when (op) {
            MCPConstants.OP_TOOL -> handleBatchTool(index, step, currentSessionId)
            MCPConstants.OP_SNAPSHOT -> handleBatchSnapshot(index, step, currentSessionId)
            MCPConstants.OP_SCREENSHOT -> handleBatchScreenshot(index, step, currentSessionId)
            else -> throw IllegalArgumentException("${MCPConstants.ERROR_UNSUPPORTED_OP}$op")
        }
    }

    private suspend fun handleBatchTool(
        index: Int,
        step: Map<String, Any?>,
        currentSessionId: String?
    ): BatchExecutionResult {
        val sessionId = requireSessionId(currentSessionId)

        step[MCPConstants.KEY_PRE_FOCUS_SELECTOR]?.toString()?.takeIf { it.isNotBlank() }?.let {
            restoreBatchFocus(sessionId, it)
        }
        step[MCPConstants.KEY_PRE_MOUSE_POSITION].toBatchMousePosition()?.let {
            restoreBatchMousePosition(sessionId, it)
        }

        val tool = step[MCPConstants.KEY_TOOL]?.toString()
            ?: throw IllegalArgumentException(MCPConstants.ERROR_MISSING_TOOL)
        val arguments =
            step[MCPConstants.KEY_ARGUMENTS].toAnyMap().orEmpty() + (MCPConstants.KEY_SESSION_ID to sessionId)

        logger.info("Calling batch tool step: $index " + tool + " " + arguments.entries.joinToString(" ") { "--" + it.key + "=" + it.value })

        val text = executeAgentToolText(tool, arguments)

        return BatchExecutionResult(index = index, ok = true, text = text.ifBlank { null })
    }

    private suspend fun handleBatchSnapshot(
        index: Int,
        step: Map<String, Any?>,
        currentSessionId: String?
    ): BatchExecutionResult {
        val sessionId = requireSessionId(currentSessionId)
        val tool = step[MCPConstants.KEY_TOOL]?.toString()
            ?: throw IllegalArgumentException(MCPConstants.ERROR_MISSING_TOOL)
        val arguments =
            step[MCPConstants.KEY_ARGUMENTS].toAnyMap().orEmpty() + (MCPConstants.KEY_SESSION_ID to sessionId)

        val pageUrl = executeAgentToolText(MCPConstants.TOOL_PAGE_URL, mapOf(MCPConstants.KEY_SESSION_ID to sessionId))
        val pageTitle =
            executeAgentToolText(MCPConstants.TOOL_PAGE_TITLE, mapOf(MCPConstants.KEY_SESSION_ID to sessionId))
        val snapshot = executeAgentToolText(tool, arguments)

        return BatchExecutionResult(
            index = index,
            ok = true,
            pageUrl = pageUrl,
            pageTitle = pageTitle,
            snapshot = snapshot,
        )
    }

    private suspend fun handleBatchScreenshot(
        index: Int,
        step: Map<String, Any?>,
        currentSessionId: String?
    ): BatchExecutionResult {
        val sessionId = requireSessionId(currentSessionId)
        val tool = step[MCPConstants.KEY_TOOL]?.toString()
            ?: throw IllegalArgumentException(MCPConstants.ERROR_MISSING_TOOL)
        val arguments =
            step[MCPConstants.KEY_ARGUMENTS].toAnyMap().orEmpty() + (MCPConstants.KEY_SESSION_ID to sessionId)
        val screenshot = executeAgentToolText(tool, arguments)

        return BatchExecutionResult(index = index, ok = true, screenshot = screenshot)
    }

    // =========================================================================
    // Agent tool execution (delegates to session's agent)
    // =========================================================================

    internal suspend fun executeAgentToolText(toolName: String, args: Map<String, Any?>): String {
        val sessionId = requireSessionId(args)
        val managed = sessionManager.getSession(sessionId)
            ?: throw IllegalArgumentException("${MCPConstants.ERROR_SESSION_NOT_FOUND}$sessionId")

        val agent = managed.agenticSession.companionAgent as? BasicBrowserAgent
            ?: throw IllegalStateException("Session agent does not support tools")

        return executeAgentToolText(agent, toolName, args)
    }

    private suspend fun executeAgentToolText(
        agent: BasicBrowserAgent,
        toolName: String,
        args: Map<String, Any?>,
    ): String {
        val normalizedTool = FRONTEND_TOOL_NAME_ALIASES[toolName] ?: toolName
        val normalizedArgs = normalizeToolArguments(normalizedTool, args)
        val toolCall = resolveMcpToolCall(normalizedTool, normalizedArgs, agent)
            ?: throw IllegalArgumentException("Unknown tool: $toolName")

        val result = agent.agentToolManager.execute(toolCall)

        val evaluate = result.evaluate
        evaluate.exception?.let { exception ->
            throw IllegalArgumentException("$toolName failed: ${exception.message} help: ${exception.help}")
        }
        return evaluate.value?.toString() ?: ""
    }

    private fun resolveMcpToolCall(toolName: String, args: Map<String, Any?>, agent: BasicBrowserAgent): ToolCall? {
        val args1 = args.toMutableMap()

        // 1. Explicit mapping for legacy/special names
        when (toolName) {
            "page_title" -> return ToolCall("tab", "title", args1)
            "page_url" -> return ToolCall("tab", "currentUrl", args1)
            "switch_tab", "tab_select" -> return ToolCall("browser", "switchTab", args1)
            "tab_new" -> return ToolCall("browser", "newTab", args1)
            "tab_list" -> return ToolCall("browser", "listTabs", args1)
            "tab_close", "close_tab" -> return ToolCall("browser", "closeTab", args1)
            "keydown" -> return ToolCall("tab", "keyDown", args1)
            "keyup" -> return ToolCall("tab", "keyUp", args1)
            "mousemove" -> return ToolCall("tab", "mouseMove", args1)
            "mousedown" -> return ToolCall("tab", "mouseDown", args1)
            "mouseup" -> return ToolCall("tab", "mouseUp", args1)
            "mousewheel" -> return ToolCall("tab", "mouseWheel", args1)
        }

        // 2. Generic mapping
        val specs = agent.agentToolManager.getAllToolSpecs()
        for ((domain, methods) in specs) {
            for ((method, _) in methods) {
                val mcpName = ToolListHandler.toMcpToolName(domain, method)
                if (mcpName == toolName) {
                    return ToolCall(domain, method, args1)
                }
            }
        }

        return null
    }

    private fun normalizeToolArguments(toolName: String, args: Map<String, Any?>): Map<String, Any?> {
        return ArgumentNormalizerFactory.normalize(toolName, args)
    }

    // =========================================================================
    // Batch focus / mouse position restoration
    // =========================================================================

    internal suspend fun restoreBatchFocus(sessionId: String, selector: String) {
        if (selector.startsWith("backend:")) {
            return
        }

        val selectorLiteral = jacksonObjectMapper().writeValueAsString(selector)
        val focusExpression = $$"""
            (() => {
                try {
                    const el = document.querySelector($$selectorLiteral);
                    if (!el) return 'missing';
                    if (typeof el.focus === 'function') {
                        el.focus();
                    }
                    return document.activeElement === el ? 'focused' : 'unfocused';
                } catch (error) {
                    return `invalid:${error}`;
                }
            })()
        """.trimIndent()

        when (val result = executeAgentToolText(
            MCPConstants.TOOL_BROWSER_EVALUATE,
            mapOf(MCPConstants.KEY_SESSION_ID to sessionId, "expression" to focusExpression),
        ).trim()) {
            "focused" -> return
            "missing" -> throw IllegalArgumentException(
                "Saved active selector '$selector' no longer exists on the page."
            )

            "unfocused" -> throw IllegalArgumentException(
                "Failed to focus saved active selector '$selector' before keyboard command."
            )

            else -> {
                if (result.startsWith("invalid:")) {
                    throw IllegalArgumentException(
                        "Saved active selector '$selector' is not a valid query selector: $result"
                    )
                }
                throw IllegalArgumentException(
                    "Unexpected focus result for saved active selector '$selector': $result"
                )
            }
        }
    }

    internal suspend fun restoreBatchMousePosition(sessionId: String, position: BatchMousePosition) {
        executeAgentToolText(
            "browser_mouse_move_xy",
            mapOf(MCPConstants.KEY_SESSION_ID to sessionId, "x" to position.x, "y" to position.y),
        )
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    private suspend fun dispatchToCommandToolExecutor(
        toolDisplayName: String,
        method: String,
        args: Map<String, Any?>,
    ): ResponseEntity<MCPToolCallResponse> {
        val sessionId: String = args[B4Constants.SESSION_ID_CAPABILITY]?.toString() ?: DEFAULT_SESSION_ID

        return try {
            val toolExecutor = getCommandAgentToolManager(sessionId)
            val evaluate = toolExecutor.execute(ToolCall("command", method, args.toMutableMap())).evaluate
            if (evaluate.exception != null) {
                ResponseEntity.ok(errorResponse("$toolDisplayName failed: ${evaluate.exception!!.message}"))
            } else {
                ResponseEntity.ok(textResponse(evaluate.value?.toString() ?: ""))
            }
        } catch (e: Exception) {
            logger.error("{} failed | {}", toolDisplayName, e.message, e)
            ResponseEntity.ok(errorResponse("$toolDisplayName failed: ${e.message}"))
        }
    }

    private fun getCommandAgentToolManager(sessionId: String): AgentToolManager {
        val agentRunner = commandExecutor.ensureAgentRunner(sessionId)
        val commandAgent = agentRunner.session.companionAgent as? BasicBrowserAgent
            ?: throw IllegalStateException("CommandRunner session agent does not support tools")

        val agentToolManager = commandAgent.agentToolManager

        val domain = "command"
        if (!agentToolManager.hasToolExecutor(domain)) {
            agentToolManager.registerCustomToolExecutor(commandToolExecutor)
            agentToolManager.registerCustomTarget(domain, commandExecutor)
        }
        return agentToolManager
    }

    private fun requireSessionId(sessionId: String?): String {
        return sessionId ?: throw IllegalArgumentException(MCPConstants.ERROR_NO_ACTIVE_SESSION)
    }

    private fun requireSessionId(arguments: Map<String, Any?>): String {
        return arguments[MCPConstants.KEY_SESSION_ID]?.toString()
            ?: throw IllegalArgumentException("Missing required parameter: ${MCPConstants.KEY_SESSION_ID}")
    }
}
