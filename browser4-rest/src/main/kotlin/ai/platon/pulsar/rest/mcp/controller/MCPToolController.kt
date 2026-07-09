package ai.platon.pulsar.rest.mcp.controller

import ai.platon.pulsar.agentic.agents.BasicBrowserAgent
import ai.platon.pulsar.agentic.model.ToolCall
import ai.platon.pulsar.agentic.model.ToolSpec
import ai.platon.pulsar.agentic.tools.CustomToolRegistry
import ai.platon.pulsar.agentic.tools.builtin.ToolExecutor
import ai.platon.pulsar.rest.session.PulsarSessionManager
import ai.platon.pulsar.common.brief
import ai.platon.pulsar.common.serialize.json.pulsarObjectMapper
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonSetter
import com.fasterxml.jackson.annotation.Nulls
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
    val isError: Boolean = false,
    @get:JsonProperty("_pagination")
    @param:JsonProperty("_pagination")
    val pagination: PaginationMeta? = null
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class MCPContent(
    @param:JsonProperty("type") val type: String = "text",
    @param:JsonProperty("text") val text: String
)

/**
 * Server-side pagination metadata attached to paginated MCP responses.
 * The CLI reads this to display a pagination footer without needing to
 * re-paginate locally.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class PaginationMeta(
    @param:JsonProperty("page") val page: Int,
    @param:JsonProperty("totalPages") val totalPages: Int,
    @param:JsonProperty("totalLines") val totalLines: Int,
    @param:JsonProperty("pageSize") val pageSize: Int,
    @param:JsonProperty("truncated") val truncated: Boolean = true
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
@ConditionalOnBean(PulsarSessionManager::class)
class MCPToolController(
    private val sessionManager: PulsarSessionManager,
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
            "browser_generate_locator" to "generate_locator",
            "browser_resize" to "resize",
            "browser_take_screenshot" to "screenshot",
            "browser_pdf_save" to "pdf",
            "browser_save_storage_state" to "save_storage_state",
            "browser_load_storage_state" to "load_storage_state",
            "browser_console_messages" to "consoleMessages",
            "browser_console_clear" to "consoleClear",
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
        val pdf: String? = null,
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

        logger.info("Calling tool: ${request.tool} " + request.arguments?.entries?.joinToString(" ") { "--" + it.key + "=" + it.value })

        return try {
            when (request.tool) {
                // Session lifecycle tools — remain inline (no session required to call these)
                "open_session" -> handleOpenSession(request)
                "close_session" -> handleCloseSession(request)
                "list_sessions" -> handleListSessions()
                "close_all_sessions" -> handleCloseAllSessions()
                "kill_all_sessions" -> handleKillAllSessions()
                "delete_session_data" -> handleDeleteSessionData(request)
                "attach_browser" -> handleAttachBrowser(request)
                "check_session_ready" -> handleCheckSessionReady(request)
                // All other tools → dynamic dispatch through CustomToolRegistry or AgentToolManager
                else -> dispatchToToolExecutor(request)
            }
        } catch (e: Exception) {
            logger.error("MCP tool call failed | tool={} | {}", request.tool, e.message, e)
            ResponseEntity.ok(errorResponse("${request.tool} failed: ${exceptionChainMessage(e)}"))
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

        // Slow path: compute tool names under a lock so only one request
        // initialises the cache.
        synchronized(this) {
            cachedToolNames?.let {
                return ResponseEntity.ok(mapOf("tools" to it))
            }

            val tools = linkedSetOf(
                // Session management
                "open_session", "close_session", "list_sessions",
                "close_all_sessions", "kill_all_sessions", "delete_session_data",
                "attach_browser", "check_session_ready",
            )

            // Include every frontend tool alias so the CLI readiness probe
            // (which checks for "open_session" + "browser_navigate") passes
            // without creating a throwaway session that would launch Chrome.
            tools.addAll(FRONTEND_TOOL_NAME_ALIASES.keys)

            // Composite / convenience tools that map to underlying domain tools.
            // These should always be advertised, even when no session is active.
            tools.addAll(
                listOf(
                    "browser_click",
                    "browser_handle_dialog",
                    "browser_tabs",
                )
            )

            // Enumerate tools from plugin-registered executors in CustomToolRegistry.
            // These include command, crawl, swarm, skill management, and DOM snapshot tools.
            CustomToolRegistry.instance.getAllExecutors().forEach { executor ->
                executor.getToolSpecs().keys.forEach { method ->
                    tools.add(toMcpToolName(executor.domain, method))
                }
            }

            val activeSession = sessionManager.getAllSessions().firstOrNull()
            if (activeSession != null) {
                // A real session already exists — enrich with per-agent tools.
                try {
                    val agent = activeSession.agenticSession.companionAgent as? BasicBrowserAgent
                    if (agent != null) {
                        tools.addAll(collectAdvertisedToolNames(agent.agentToolManager.getAllToolSpecs()))
                    }
                } catch (_: Exception) {
                    // Session may be mid-initialisation; the static set is sufficient.
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
        val capabilities = request.arguments?.get("capabilities") as? Map<String, String?>
        val session = sessionManager.getOrCreateSession(capabilities)

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

    private fun handleAttachBrowser(request: MCPToolCallRequest): ResponseEntity<MCPToolCallResponse> {
        val args = request.arguments ?: emptyMap()

        val cdpEndpoint = (args["cdpEndpoint"] as? String)?.takeIf { it.isNotBlank() }
        val cdpPort = (args["cdpPort"] as? Number)?.toInt()

        require(cdpEndpoint != null || cdpPort != null) {
            "attach_browser requires either 'cdpEndpoint' (URL) or 'cdpPort' (number)"
        }

        val session = sessionManager.createAttachedSession(
            cdpEndpoint = cdpEndpoint,
            cdpPort = cdpPort,
            capabilities = args.filterKeys {
                it != "cdpEndpoint" && it != "cdpPort" && it != "sessionId"
            }.mapValues { it.value?.toString() }
        )

        logger.info(
            "MCP attach_browser: created session {} attached to {}",
            session.sessionId,
            cdpEndpoint ?: "port $cdpPort"
        )
        return ResponseEntity.ok(
            textResponse("""{"sessionId":"${session.sessionId}"}""")
        )
    }

    /**
     * Checks whether an extension-attached session is ready (the extension has
     * connected via WebSocket). Used by the CLI to poll after launching the browser.
     */
    private fun handleCheckSessionReady(request: MCPToolCallRequest): ResponseEntity<MCPToolCallResponse> {
        val sessionId = request.arguments?.get("sessionId")?.toString()
            ?: throw IllegalArgumentException("Missing required parameter: sessionId")
        val ready = sessionManager.isExtensionSessionReady(sessionId)
        val session = sessionManager.getSession(sessionId)
        val healthy = if (session != null) {
            try {
                sessionManager.checkHealthyBlocking(session).isOK
            } catch (_: Exception) {
                false
            }
        } else {
            false
        }
        return ResponseEntity.ok(
            textResponse("""{"ready":$ready,"healthy":$healthy}""")
        )
    }

    // Dispatch to CustomToolRegistry or AgentToolManager
    // =========================================================================

    /**
     * Unified tool dispatch: tries plugin-registered executors in [CustomToolRegistry]
     * first, then falls back to the per-session agent's [AgentToolManager].
     *
     * Tool name resolution:
     * 1. Normalize via [normalizeFrontendToolCall] (applies frontend aliases)
     * 2. Extract domain from the normalized tool name
     * 3. Look up domain in [CustomToolRegistry.instance]
     * 4. If found → dispatch to the custom executor
     * 5. If not found → dispatch to the session agent's tool manager
     */
    private suspend fun dispatchToToolExecutor(request: MCPToolCallRequest): ResponseEntity<MCPToolCallResponse> {
        val normalizedRequest = normalizeFrontendToolCall(request.tool, request.arguments ?: emptyMap())
        val toolName = normalizedRequest.tool
        val args = normalizeToolArguments(toolName, normalizedRequest.arguments)

        // Extract domain from tool name and try CustomToolRegistry
        val domain = extractDomain(toolName)
        val customExecutor = CustomToolRegistry.instance.get(domain)
        if (customExecutor != null) {
            return dispatchToCustomExecutor(toolName, domain, args, customExecutor, request)
        }

        // Fall back to per-session agent tool dispatch
        return dispatchToAgentToolExecutor(request)
    }

    /**
     * Extract the domain from an MCP tool name.
     *
     * Domain is the prefix before the first `_` for names following the
     * `domain_method` convention (e.g. `crawl_submit` → `crawl`).
     * For names without `_`, the entire name is the domain.
     */
    private fun extractDomain(toolName: String): String {
        val underscoreIndex = toolName.indexOf('_')
        return if (underscoreIndex > 0) toolName.substring(0, underscoreIndex) else toolName
    }

    /**
     * Dispatch a tool call to a custom executor registered in [CustomToolRegistry].
     *
     * Converts the MCP tool name to a method name (the part after the domain prefix),
     * invokes the executor, and formats the result as an MCP response.
     */
    private suspend fun dispatchToCustomExecutor(
        toolName: String,
        domain: String,
        args: Map<String, Any?>,
        executor: ToolExecutor,
        request: MCPToolCallRequest,
    ): ResponseEntity<MCPToolCallResponse> {
        // Derive method name from tool name: "crawl_submit" → "submit"
        val method = if (toolName.startsWith("${domain}_")) {
            toolName.substring(domain.length + 1)
        } else {
            toolName
        }

        return try {
            val result = executor.callFunctionOn(ToolCall(domain, method, args.toMutableMap()))
            val evaluate = result
            val exception = evaluate.exception
            if (exception != null) {
                ResponseEntity.ok(errorResponse("$toolName failed: ${exception.message} help: ${exception.help}"))
            } else {
                val text = when (val v = evaluate.value) {
                    null -> if (evaluate.className == "null") "null" else ""
                    is String -> v
                    is Number, is Boolean -> v.toString()
                    is Map<*, *>, is Collection<*>, is Array<*> -> pulsarObjectMapper().writeValueAsString(v)
                    else -> pulsarObjectMapper().writeValueAsString(
                        mapOf(
                            "type" to (evaluate.className ?: v::class.qualifiedName),
                            "description" to v.toString()
                        )
                    )
                }

                val requestArgs = request.arguments ?: emptyMap()
                val (paginatedText, pagination) = paginateIfRequested(text, requestArgs)
                ResponseEntity.ok(textResponse(paginatedText, pagination))
            }
        } catch (e: Exception) {
            logger.warn("Custom executor failed | tool={} | domain={} | {}", toolName, domain, e.message)
            ResponseEntity.ok(errorResponse("$toolName failed: ${e.message}"))
        }
    }

    /**
     * Dispatch a tool call to the session's [AgentToolManager].
     *
     * This is the existing path for per-session browser/tab/system tools.
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
            val result = agent.agentToolManager.execute(toolCall)
            val evaluate = result.evaluate
            val exception = evaluate.exception
            if (exception != null) {
                ResponseEntity.ok(errorResponse("${request.tool} failed: ${exception.message} help: ${exception.help}"))
            } else {
                // Distinguish JS null (className == "null") from JS undefined (className == "undefined")
                // and Kotlin Unit (no meaningful return value).
                // All three arrive as evaluate.value == null, but only JS null should produce visible output.
                val text = when (val v = evaluate.value) {
                    null -> if (evaluate.className == "null") "null" else ""
                    is String -> v
                    is Number, is Boolean -> v.toString()
                    // Maps, Lists, arrays etc. — serialize as valid JSON
                    is Map<*, *>, is Collection<*>, is Array<*> -> pulsarObjectMapper().writeValueAsString(v)
                    // Non-serializable domain objects (WebDriver, Browser, etc.) —
                    // wrap in a description object so internal object graphs are never
                    // exposed to the client
                    else -> pulsarObjectMapper().writeValueAsString(
                        mapOf(
                            "type" to (evaluate.className ?: v::class.qualifiedName),
                            "description" to v.toString()
                        )
                    )
                }

                // Server-side pagination: when page/page-size are present, paginate
                // the result text to reduce network traffic for large snapshots.
                val requestArgs = request.arguments ?: emptyMap()
                val (paginatedText, pagination) = paginateIfRequested(text, requestArgs)
                ResponseEntity.ok(textResponse(paginatedText, pagination))
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
            "consoleMessages" -> return ToolCall("tab", "consoleMessages", args1)
            "consoleClear" -> return ToolCall("tab", "consoleClear", args1)
        }

        // 2. Generic mapping
        val specs = agent.agentToolManager.getAllToolSpecs()
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

    private fun textResponse(text: String): MCPToolCallResponse =
        MCPToolCallResponse(content = listOf(MCPContent(text = text)))

    private fun textResponse(text: String, pagination: PaginationMeta?): MCPToolCallResponse =
        MCPToolCallResponse(content = listOf(MCPContent(text = text)), pagination = pagination)

    private fun errorResponse(message: String): MCPToolCallResponse =
        MCPToolCallResponse(content = listOf(MCPContent(text = "ERROR: $message")), isError = true)

    /**
     * Build a chain of exception messages from [e] through all its causes,
     * joined by " ← ".  This ensures the CLI sees the root cause when the
     * outermost message is generic (e.g. "browser_navigate failed" wrapping
     * "Failed to launch browser" wrapping "libgbm.so.1: cannot open shared
     * object file").
     */
    private fun exceptionChainMessage(e: Throwable): String {
        val messages = LinkedHashSet<String>()
        var current: Throwable? = e
        while (current != null) {
            current.message?.let { messages.add(it) }
            current = current.cause
        }
        return messages.joinToString(" ← ")
    }

    private fun addRequestId(response: HttpServletResponse) {
        response.addHeader("X-Request-Id", UUID.randomUUID().toString())
    }
}

