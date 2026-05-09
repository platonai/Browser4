package ai.platon.pulsar.rest.mcp.controller

import ai.platon.pulsar.agentic.agents.BasicBrowserAgent
import ai.platon.pulsar.agentic.model.ToolCall
import ai.platon.pulsar.agentic.model.ToolSpec
import ai.platon.pulsar.agentic.tools.AgentToolExecutor
import ai.platon.pulsar.agentic.tools.high.command.CommandService
import ai.platon.pulsar.common.brief
import ai.platon.pulsar.rest.mcp.service.SessionManager
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonSetter
import com.fasterxml.jackson.annotation.Nulls
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.util.*

// ---------------------------------------------------------------------------
// DTOs
// ---------------------------------------------------------------------------

/**
 * Request body for calling an MCP tool.
 */
data class MCPToolCallRequest(
    @param:JsonProperty("tool") val tool: String,
    @param:JsonProperty("arguments") val arguments: Map<String, Any?>? = null
)

/**
 * Response from an MCP tool call.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class MCPToolCallResponse(
    @get:JsonProperty("content")
    @param:JsonProperty("content")
    val content: List<MCPContent>,
    @get:JsonProperty("isError")
    @param:JsonSetter(nulls = Nulls.SKIP)
    @param:JsonProperty("isError")
    val isError: Boolean = false
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class MCPContent(
    @param:JsonProperty("type") val type: String = "text",
    @param:JsonProperty("text") val text: String
)

// ---------------------------------------------------------------------------
// Controller
// ---------------------------------------------------------------------------

/**
 * REST controller that exposes Browser4 MCP tools over HTTP.
 *
 * This allows the browser4-cli (and any HTTP client) to invoke MCP tools
 * through a simple REST endpoint instead of STDIO.
 *
 * Session management tools (open_session, close_session, list_sessions, etc.)
 * are handled directly by this controller.
 */
@RestController
@CrossOrigin
@RequestMapping(
    path = ["/mcp"],
    produces = [MediaType.APPLICATION_JSON_VALUE]
)
@ConditionalOnBean(SessionManager::class)
class MCPToolController(
    private val sessionManager: SessionManager,
    private val commandService: CommandService,
) {
    companion object {
        private val FRONTEND_TOOL_NAME_ALIASES: Map<String, String> = mapOf(
            "browser_navigate" to "navigate",
            "browser_snapshot" to "aria_snapshot",
            "browser_navigate_back" to "go_back",
            "browser_navigate_forward" to "go_forward",
            "browser_reload" to "reload",
            "browser_press_key" to "press",
            "browser_press_sequentially" to "type",
            "browser_keydown" to "keydown",
            "browser_keyup" to "keyup",
            "browser_mouse_move_xy" to "mousemove",
            "browser_mouse_down" to "mousedown",
            "browser_mouse_up" to "mouseup",
            "browser_mouse_wheel" to "mousewheel",
            "browser_drag" to "drag",
            "browser_type" to "fill",
            "browser_hover" to "hover",
            "browser_select_option" to "select_option",
            "browser_file_upload" to "upload",
            "browser_check" to "check",
            "browser_uncheck" to "uncheck",
            "browser_evaluate" to "evaluate_value",
            "browser_resize" to "resize",
            "browser_take_screenshot" to "screenshot",
        )

        private const val CLEAR_SESSION_STORAGE_SCRIPT = """
            (() => {
                const result = {
                    localStorageCleared: false,
                    sessionStorageCleared: false,
                    errors: []
                };
                try {
                    window.localStorage.clear();
                    result.localStorageCleared = true;
                } catch (error) {
                    result.errors.push("localStorage: " + error);
                }
                try {
                    window.sessionStorage.clear();
                    result.sessionStorageCleared = true;
                } catch (error) {
                    result.errors.push("sessionStorage: " + error);
                }
                return JSON.stringify(result);
            })()
        """
    }

    private val logger = LoggerFactory.getLogger(MCPToolController::class.java)

    private fun requireSessionId(sessionId: String?): String {
        return sessionId ?: throw IllegalArgumentException(MCPConstants.ERROR_NO_ACTIVE_SESSION)
    }

    /**
     * Cached tool names for the /tools endpoint.
     * Tool specs are static (determined by executor classes, not session state),
     * so we cache after the first successful enumeration to avoid creating
     * a throwaway session on every probe request.
     */
    @Volatile
    private var cachedToolNames: List<String>? = null

    private data class NormalizedToolCall(
        val tool: String,
        val arguments: Map<String, Any?>
    )

    private data class BatchMousePosition(
        val x: Double,
        val y: Double,
    )

    private data class BatchExecutionResult(
        val index: Int,
        val ok: Boolean,
        val durationMillis: Long = 0,
        val sessionId: String? = null,
        val text: String? = null,
        val error: String? = null,
        val pageUrl: String? = null,
        val pageTitle: String? = null,
        val snapshot: String? = null,
        val screenshot: String? = null,
    )

    private data class BatchExecutionResponse(
        val sessionId: String?,
        val failureCount: Int,
        val stoppedOnError: Boolean,
        val results: List<BatchExecutionResult>,
    )

    // =========================================================================
    // Tool call endpoint
    // =========================================================================

    /**
     * Call an MCP tool.
     *
     * Session management tools (`open_session`, `close_session`, `list_sessions`,
     * `close_all_sessions`, `kill_all_sessions`, `delete_session_data`) do not
     * require a sessionId.
     *
     * All other tools require the `sessionId` to be provided in the request body
     * or via the path variable.
     */
    @PostMapping("/call-tool", consumes = [MediaType.APPLICATION_JSON_VALUE])
    suspend fun callTool(
        @RequestBody request: MCPToolCallRequest,
        response: HttpServletResponse
    ): ResponseEntity<MCPToolCallResponse> {
        addRequestId(response)

        return try {
            when (request.tool) {
                // Session management tools
                "open_session" -> handleOpenSession(request)
                "close_session" -> handleCloseSession(request)
                "list_sessions" -> handleListSessions()
                "close_all_sessions" -> handleCloseAllSessions()
                "kill_all_sessions" -> handleKillAllSessions()
                "delete_session_data" -> handleDeleteSessionData(request)
                // Command tools — delegate to CommandService (no session required)
                "command_run" -> handleCommandRun(request)
                "command_batch" -> handleCommandBatch(request)
                "command_status" -> handleCommandStatus(request)
                "command_result" -> handleCommandResult(request)
                // All other tools are dispatched to the session's agent
                else -> dispatchToAgentToolExecutor(request)
            }
        } catch (e: Exception) {
            logger.error("MCP tool call failed | tool={} | {}", request.tool, e.message, e)
            ResponseEntity.ok(errorResponse("${request.tool} failed: ${e.message}"))
        }
    }

    /**
     * List available MCP tools.
     *
     * Tool specs are static (they come from executor class definitions, not session state),
     * so we cache the result after the first successful enumeration. This avoids creating
     * and destroying a throwaway session on every probe — which previously caused a
     * create→launch-browser→close cycle every time the CLI polled this endpoint.
     */
    @GetMapping("/tools")
    fun listTools(
        response: HttpServletResponse
    ): ResponseEntity<Any> {
        addRequestId(response)

        // Fast path: return cached tool names if already computed
        cachedToolNames?.let {
            return ResponseEntity.ok(mapOf("tools" to it))
        }

        // Slow path: compute tool names under a lock so only one request creates a session
        synchronized(this) {
            cachedToolNames?.let {
                return ResponseEntity.ok(mapOf("tools" to it))
            }

            val tools = linkedSetOf(
                // Session management
                "open_session", "close_session", "list_sessions",
                "close_all_sessions", "kill_all_sessions", "delete_session_data",
                // Command tools (no session required)
                "command_run", "command_batch", "command_status", "command_result"
            )

            val activeSession = sessionManager.getAllSessions().firstOrNull()
            val managedSession = activeSession ?: sessionManager.createSession(null)
            val deleteAfterListing = activeSession == null

            try {
                val agent = managedSession.agenticSession.companionAgent as? BasicBrowserAgent
                if (agent != null) {
                    tools.addAll(collectAdvertisedToolNames(agent.toolExtractor.getAllToolSpecs()))
                }
            } finally {
                if (deleteAfterListing) {
                    sessionManager.deleteSession(managedSession.sessionId)
                }
            }

            val result = tools.toList()
            cachedToolNames = result
            return ResponseEntity.ok(mapOf("tools" to result))
        }
    }

    // =========================================================================
    // Session management handlers
    // =========================================================================

    private fun handleOpenSession(request: MCPToolCallRequest): ResponseEntity<MCPToolCallResponse> {
        val capabilities = request.arguments?.get("capabilities") as? Map<String, Any?>
        val session = sessionManager.createSession(capabilities)

        // Navigate to initial URL if provided
        val url = request.arguments?.get("url")?.toString()
        // Navigate operation is handled in the client side

        logger.info("MCP open_session: created session {}", session.sessionId)
        return ResponseEntity.ok(
            textResponse("""{"sessionId":"${session.sessionId}"}""")
        )
    }

    private fun handleCloseSession(request: MCPToolCallRequest): ResponseEntity<MCPToolCallResponse> {
        val sessionId = requireSessionId(request)
        val deleted = sessionManager.deleteSession(sessionId)
        return if (deleted) {
            ResponseEntity.ok(textResponse("Session closed"))
        } else {
            ResponseEntity.ok(errorResponse("Session not found: $sessionId"))
        }
    }

    private fun handleListSessions(): ResponseEntity<MCPToolCallResponse> {
        val sessions = sessionManager.getAllSessions().map { s ->
            """{"sessionId":"${s.sessionId}","url":"${s.url ?: ""}","status":"${s.status}"}"""
        }
        return ResponseEntity.ok(textResponse("[${sessions.joinToString(",")}]"))
    }

    private fun handleCloseAllSessions(): ResponseEntity<MCPToolCallResponse> {
        val count = sessionManager.deleteAllSessions()
        return ResponseEntity.ok(textResponse("Closed $count session(s)"))
    }

    private fun handleKillAllSessions(): ResponseEntity<MCPToolCallResponse> {
        val count = sessionManager.deleteAllSessions()
        return ResponseEntity.ok(textResponse("Killed $count session(s)"))
    }

    private suspend fun handleDeleteSessionData(request: MCPToolCallRequest): ResponseEntity<MCPToolCallResponse> {
        val sessionId = requireSessionId(request)
        val managed = sessionManager.getSession(sessionId)
            ?: return ResponseEntity.ok(errorResponse("Session not found: $sessionId"))

        managed.withLock {
            driver.clearBrowserCookies()
            val storageResult = driver.evaluate(CLEAR_SESSION_STORAGE_SCRIPT)?.toString().orEmpty()
            if (storageResult.isNotBlank() && !storageResult.contains("\"errors\":[]")) {
                logger.warn(
                    "delete_session_data completed with partial storage cleanup | sessionId={} | result={}",
                    sessionId,
                    storageResult
                )
            }
        }

        return ResponseEntity.ok(textResponse("User data deleted for session"))
    }

    // =========================================================================
    // Command tool handlers
    // TODO: simplify command tool handling
    // =========================================================================

    /**
     * Execute a plain command via the unified [AgentToolExecutor] path.
     *
     * When `async=true` (default), returns the task ID string immediately.
     * When `async=false`, blocks until execution completes and returns the [CommandStatus] as JSON.
     */
    private suspend fun handleCommandRun(request: MCPToolCallRequest): ResponseEntity<MCPToolCallResponse> =
        dispatchToCommandToolExecutor("command_run", "run", request.arguments ?: emptyMap())

    private suspend fun handleCommandBatch(request: MCPToolCallRequest): ResponseEntity<MCPToolCallResponse> {
        val args = request.arguments ?: emptyMap()
        val stepMaps = (args["steps"] as? List<*>)?.mapIndexed { index, step ->
            val stepMap = step.toAnyMap()
                ?: throw IllegalArgumentException("Batch step at index $index must be an object.")
            index to stepMap
        } ?: throw IllegalArgumentException("command_batch requires a 'steps' array.")

        val bail = args["bail"].toBooleanValue() ?: false
        var currentSessionId = args["sessionId"]?.toString()?.takeIf { it.isNotBlank() }
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
            if (result.ok) {
                currentSessionId = when (step["op"]?.toString()) {
                    "open" -> result.sessionId
                    "close" -> null
                    else -> currentSessionId
                }
            }
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

    /**
     * Get the status of a command task by its ID.
     */
    private suspend fun handleCommandStatus(request: MCPToolCallRequest): ResponseEntity<MCPToolCallResponse> =
        dispatchToCommandToolExecutor("command_status", "status", request.arguments ?: emptyMap())

    /**
     * Get the result of a completed command task by its ID.
     */
    private suspend fun handleCommandResult(request: MCPToolCallRequest): ResponseEntity<MCPToolCallResponse> =
        dispatchToCommandToolExecutor("command_result", "result", request.arguments ?: emptyMap())

    /**
     * Common dispatcher for command tool calls — invokes the command agent's
     * [AgentToolExecutor] and maps the result to an [MCPToolCallResponse].
     *
     * @param toolDisplayName Human-readable tool name for error messages.
     * @param method The command domain method to invoke (`run`, `status`, or `result`).
     * @param args The raw request arguments.
     */
    private suspend fun dispatchToCommandToolExecutor(
        toolDisplayName: String,
        method: String,
        args: Map<String, Any?>,
    ): ResponseEntity<MCPToolCallResponse> {
        return try {
            val toolExecutor = getCommandAgentToolExecutor()
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

    private fun getCommandAgentToolExecutor(): AgentToolExecutor {
        val commandAgent = commandService.session.companionAgent as? BasicBrowserAgent
            ?: throw IllegalStateException("CommandService session agent does not support tools")
        return commandAgent.toolExtractor.also { it.registerCustomTarget("command", commandService) }
    }

    private suspend fun executeBatchStep(
        index: Int,
        step: Map<String, Any?>,
        currentSessionId: String?,
    ): BatchExecutionResult {
        val op = step[MCPConstants.KEY_OP]?.toString()
            ?: throw IllegalArgumentException(MCPConstants.ERROR_MISSING_OP)

        return when (op) {
            MCPConstants.OP_OPEN -> handleBatchOpen(index, step, currentSessionId)
            MCPConstants.OP_CLOSE -> handleBatchClose(index, currentSessionId)
            MCPConstants.OP_TOOL -> handleBatchTool(index, step, currentSessionId)
            MCPConstants.OP_SNAPSHOT -> handleBatchSnapshot(index, step, currentSessionId)
            MCPConstants.OP_SCREENSHOT -> handleBatchScreenshot(index, step, currentSessionId)
            else -> throw IllegalArgumentException("${MCPConstants.ERROR_UNSUPPORTED_OP}$op")
        }
    }

    private suspend fun handleBatchOpen(index: Int, step: Map<String, Any?>, currentSessionId: String?): BatchExecutionResult {
        val capabilities = step["capabilities"].toAnyMap().takeIf { !it.isNullOrEmpty() }
        
        currentSessionId?.let { sessionId ->
            val existingSession = sessionManager.getSession(sessionId)
                ?: throw IllegalArgumentException("${MCPConstants.ERROR_SESSION_NOT_FOUND}$sessionId")
            if (capabilities != null && capabilities != existingSession.capabilities) {
                logger.info(
                    "Batch open reusing existing session {} and ignoring requested capabilities {}",
                    sessionId,
                    capabilities,
                )
            }
            return BatchExecutionResult(
                index = index,
                ok = true,
                sessionId = sessionId,
                text = "${MCPConstants.SESSION_ALREADY_OPEN_PREFIX}$sessionId",
            )
        }
        
        val managedSession = sessionManager.createSession(capabilities)
        val sessionId = managedSession.sessionId
        return BatchExecutionResult(
            index = index,
            ok = true,
            sessionId = sessionId,
            text = "${MCPConstants.SESSION_OPENED_PREFIX}$sessionId"
        )
    }

    private suspend fun handleBatchClose(index: Int, currentSessionId: String?): BatchExecutionResult {
        val sessionId = requireSessionId(currentSessionId)
        val deleted = sessionManager.deleteSession(sessionId)
        if (!deleted) {
            throw IllegalArgumentException("${MCPConstants.ERROR_SESSION_NOT_FOUND}$sessionId")
        }
        return BatchExecutionResult(index = index, ok = true, text = MCPConstants.SESSION_CLOSED)
    }

    private suspend fun handleBatchTool(index: Int, step: Map<String, Any?>, currentSessionId: String?): BatchExecutionResult {
        val sessionId = requireSessionId(currentSessionId)
        
        step[MCPConstants.KEY_PRE_FOCUS_SELECTOR]?.toString()?.takeIf { it.isNotBlank() }?.let {
            restoreBatchFocus(sessionId, it)
        }
        step[MCPConstants.KEY_PRE_MOUSE_POSITION].toBatchMousePosition()?.let {
            restoreBatchMousePosition(sessionId, it)
        }
        
        val tool = step[MCPConstants.KEY_TOOL]?.toString()
            ?: throw IllegalArgumentException(MCPConstants.ERROR_MISSING_TOOL)
        val arguments = step[MCPConstants.KEY_ARGUMENTS].toAnyMap().orEmpty() + (MCPConstants.KEY_SESSION_ID to sessionId)
        val text = executeAgentToolText(tool, arguments)
        
        return BatchExecutionResult(index = index, ok = true, text = text.ifBlank { null })
    }

    private suspend fun handleBatchSnapshot(index: Int, step: Map<String, Any?>, currentSessionId: String?): BatchExecutionResult {
        val sessionId = requireSessionId(currentSessionId)
        val tool = step[MCPConstants.KEY_TOOL]?.toString()
            ?: throw IllegalArgumentException(MCPConstants.ERROR_MISSING_TOOL)
        val arguments = step[MCPConstants.KEY_ARGUMENTS].toAnyMap().orEmpty() + (MCPConstants.KEY_SESSION_ID to sessionId)
        
        val pageUrl = executeAgentToolText(MCPConstants.TOOL_PAGE_URL, mapOf(MCPConstants.KEY_SESSION_ID to sessionId))
        val pageTitle = executeAgentToolText(MCPConstants.TOOL_PAGE_TITLE, mapOf(MCPConstants.KEY_SESSION_ID to sessionId))
        val snapshot = executeAgentToolText(tool, arguments)
        
        return BatchExecutionResult(
            index = index,
            ok = true,
            pageUrl = pageUrl,
            pageTitle = pageTitle,
            snapshot = snapshot,
        )
    }

    private suspend fun handleBatchScreenshot(index: Int, step: Map<String, Any?>, currentSessionId: String?): BatchExecutionResult {
        val sessionId = requireSessionId(currentSessionId)
        val tool = step[MCPConstants.KEY_TOOL]?.toString()
            ?: throw IllegalArgumentException(MCPConstants.ERROR_MISSING_TOOL)
        val arguments = step[MCPConstants.KEY_ARGUMENTS].toAnyMap().orEmpty() + (MCPConstants.KEY_SESSION_ID to sessionId)
        val screenshot = executeAgentToolText(tool, arguments)
        
        return BatchExecutionResult(index = index, ok = true, screenshot = screenshot)
    }

    private suspend fun restoreBatchFocus(sessionId: String, selector: String) {
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

    private suspend fun restoreBatchMousePosition(sessionId: String, position: BatchMousePosition) {
        executeAgentToolText(
            "browser_mouse_move_xy",
            mapOf(MCPConstants.KEY_SESSION_ID to sessionId, "x" to position.x, "y" to position.y),
        )
    }

    private suspend fun executeAgentToolText(toolName: String, args: Map<String, Any?>): String {
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
        val normalizedRequest = normalizeFrontendToolCall(toolName, args)
        val normalizedTool = normalizedRequest.tool
        val normalizedArgs = normalizeToolArguments(normalizedTool, normalizedRequest.arguments)
        val toolCall = resolveMcpToolCall(normalizedTool, normalizedArgs, agent)
            ?: throw IllegalArgumentException("Unknown tool: $toolName")

        val result = agent.toolExtractor.execute(toolCall)

        val evaluate = result.evaluate
        evaluate.exception?.let { exception ->
            throw IllegalArgumentException("$toolName failed: ${exception.message} help: ${exception.help}")
        }
        return evaluate.value?.toString() ?: ""
    }

    private fun Any?.toAnyMap(): Map<String, Any?>? {
        if (this !is Map<*, *>) {
            return null
        }
        return this.entries.associate { (key, value) -> key.toString() to value }
    }

    private fun Any?.toBatchMousePosition(): BatchMousePosition? {
        val map = this.toAnyMap() ?: return null
        val x = (map["x"] as? Number)?.toDouble() ?: return null
        val y = (map["y"] as? Number)?.toDouble() ?: return null
        return BatchMousePosition(x, y)
    }

    // =========================================================================
    // Dispatch to per-session AgentToolManager
    // =========================================================================

    /**
     * Dispatch a tool call to the session's AgentToolManager.
     *
     * This replaces the manual tool implementation by delegating to the central
     * tool registry in [AgentToolExecutor].
     */
    private suspend fun dispatchToAgentToolExecutor(request: MCPToolCallRequest): ResponseEntity<MCPToolCallResponse> {
        val normalizedRequest = normalizeFrontendToolCall(request.tool, request.arguments ?: emptyMap())
        val sessionId = requireSessionId(normalizedRequest.arguments)
        val managed = sessionManager.getSession(sessionId)
            ?: return ResponseEntity.ok(errorResponse("Session not found: $sessionId"))

        val agent = managed.agenticSession.companionAgent as? BasicBrowserAgent
            ?: return ResponseEntity.ok(errorResponse("Session agent does not support tools"))

        val toolName = normalizedRequest.tool
        val args = normalizeToolArguments(toolName, normalizedRequest.arguments)

        // Find the matching tool in AgentToolManager
        val toolCall = resolveMcpToolCall(toolName, args, agent)
            ?: return ResponseEntity.ok(errorResponse("Unknown tool: ${request.tool}"))

        return try {
            val result = agent.toolExtractor.execute(toolCall)
            val evaluate = result.evaluate
            val exception = evaluate.exception
            if (exception != null) {
                ResponseEntity.ok(errorResponse("${request.tool} failed: ${exception.message} help: ${exception.help}"))
            } else {
                ResponseEntity.ok(textResponse(evaluate.value?.toString() ?: ""))
            }
        } catch (e: Exception) {
            logger.warn(
                "MCP tool execution failed | tool={} | normalizedTool={} | {}",
                request.tool,
                toolName,
                e.brief()
            )
            ResponseEntity.ok(errorResponse("${request.tool} failed: ${e.brief()}"))
        }
    }

    /**
     * Resolve a tool call from a tool name and arguments, using explicit mappings for legacy/special names
     * */
    private fun resolveMcpToolCall(toolName: String, args: Map<String, Any?>, agent: BasicBrowserAgent): ToolCall? {
        val args1 = args.toMutableMap()

        // 1. Explicit mapping for legacy/special names
        when (toolName) {
            "page_title" -> return ToolCall("tab", "title", args1)
            "page_url" -> return ToolCall("tab", "currentUrl", args1) // or just rely on pageUrl if it exists
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
        val specs = agent.toolExtractor.getAllToolSpecs()
        for ((domain, methods) in specs) {
            for ((method, _) in methods) {
                val mcpName = toMcpToolName(domain, method)
                if (mcpName == toolName) {
                    return ToolCall(domain, method, args1)
                }
            }
        }

        return null
    }

    private fun collectAdvertisedToolNames(toolSpecs: Map<String, Map<String, ToolSpec>>): Set<String> {
        val tools = linkedSetOf<String>()

        for ((domain, methods) in toolSpecs) {
            for (method in methods.keys) {
                tools.add(toMcpToolName(domain, method))
            }
        }

        val tabMethods = toolSpecs["tab"].orEmpty().keys
        val tabMcpNames = tabMethods.map { toMcpToolName("tab", it) }.toSet()
        val browserMethods = toolSpecs["browser"].orEmpty().keys

        val legacyTabMappings = mapOf(
            "keyDown" to "keydown",
            "keyUp" to "keyup",
            "mouseMove" to "mousemove",
            "mouseDown" to "mousedown",
            "mouseUp" to "mouseup",
            "mouseWheel" to "mousewheel",
        )
        legacyTabMappings.forEach { (method, advertisedName) ->
            if (method in tabMethods) {
                tools.add(advertisedName)
            }
        }

        if ("title" in tabMethods) {
            tools.add("page_title")
        }
        if ("currentUrl" in tabMethods) {
            tools.add("page_url")
        }

        val browserTabAliases = mapOf(
            "switchTab" to listOf("switch_tab", "tab_select"),
            "newTab" to listOf("tab_new"),
            "closeTab" to listOf("close_tab", "tab_close"),
            "listTabs" to listOf("tab_list"),
        )
        browserTabAliases.forEach { (method, aliases) ->
            if (method in browserMethods) {
                tools.addAll(aliases)
            }
        }

        FRONTEND_TOOL_NAME_ALIASES.forEach { (frontendTool, internalTool) ->
            if (internalTool in tools) {
                tools.add(frontendTool)
            }
        }

        if ("click" in tabMcpNames || "dblclick" in tabMcpNames) {
            tools.add("browser_click")
        }
        if ("dialog_accept" in tabMcpNames || "dialog_dismiss" in tabMcpNames) {
            tools.add("browser_handle_dialog")
        }
        if (browserMethods.any { it in browserTabAliases.keys }) {
            tools.add("browser_tabs")
        }

        return tools
    }

    /**
     * Convert domain+method to snake_case MCP tool name.
     * Must match logic in Browser4MCPServer.
     */
    private fun toMcpToolName(domain: String, method: String): String {
        val snake = method.replace(Regex("([A-Z])")) { "_${it.groupValues[1].lowercase()}" }
        return when (domain) {
            "tab", "system" -> snake
            else -> "${domain}_$snake"
        }
    }

    private fun normalizeFrontendToolCall(toolName: String, args: Map<String, Any?>): NormalizedToolCall {
        if (toolName == "browser_tabs") {
            val action = args["action"]?.toString()
            val resolvedTool = when (action) {
                "list" -> "tab_list"
                "new" -> "tab_new"
                "close" -> "tab_close"
                "select" -> "tab_select"
                else -> toolName
            }
            return NormalizedToolCall(
                tool = resolvedTool,
                arguments = args.toMutableMap().apply { remove("action") }
            )
        }

        if (toolName == "browser_handle_dialog") {
            val accept = args["accept"].toBooleanValue()
            return NormalizedToolCall(
                tool = if (accept == false) "dialog_dismiss" else "dialog_accept",
                arguments = args.toMutableMap().apply { remove("accept") }
            )
        }

        if (toolName == "browser_click") {
            val doubleClick = args["doubleClick"].toBooleanValue()
            return NormalizedToolCall(
                tool = if (doubleClick == true) "dblclick" else "click",
                arguments = args.toMutableMap().apply { remove("doubleClick") }
            )
        }

        return NormalizedToolCall(
            tool = FRONTEND_TOOL_NAME_ALIASES[toolName] ?: toolName,
            arguments = args
        )
    }

    private fun normalizeToolArguments(toolName: String, args: Map<String, Any?>): Map<String, Any?> {
        return ArgumentNormalizerFactory.normalize(toolName, args)
    }

    private fun Any?.toBooleanValue(): Boolean? = when (this) {
        is Boolean -> this
        is String -> this.toBooleanStrictOrNull()
        else -> null
    }

    private fun snakeToCamel(key: String): String {
        if (!key.contains("_")) {
            return key
        }

        val parts = key.split("_").filter { it.isNotEmpty() }
        if (parts.isEmpty()) {
            return key
        }

        return buildString {
            append(parts.first())
            parts.drop(1).forEach { append(it.replaceFirstChar { c -> c.uppercase() }) }
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private fun requireSessionId(request: MCPToolCallRequest): String {
        return request.arguments?.get("sessionId")?.toString()
            ?: throw IllegalArgumentException("Missing required parameter: sessionId")
    }

    private fun requireSessionId(arguments: Map<String, Any?>): String {
        return arguments[MCPConstants.KEY_SESSION_ID]?.toString()
            ?: throw IllegalArgumentException("Missing required parameter: ${MCPConstants.KEY_SESSION_ID}")
    }

    private fun requireArg(args: Map<String, Any?>, key: String): String {
        return args[key]?.toString()
            ?: throw IllegalArgumentException("Missing required parameter: $key")
    }

    private fun textResponse(text: String): MCPToolCallResponse =
        MCPToolCallResponse(content = listOf(MCPContent(text = text)))

    private fun errorResponse(message: String): MCPToolCallResponse =
        MCPToolCallResponse(content = listOf(MCPContent(text = "ERROR: $message")), isError = true)

    private fun addRequestId(response: HttpServletResponse) {
        response.addHeader("X-Request-Id", UUID.randomUUID().toString())
    }
}
