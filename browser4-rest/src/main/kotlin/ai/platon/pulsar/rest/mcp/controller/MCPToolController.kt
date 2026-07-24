package ai.platon.pulsar.rest.mcp.controller

import ai.platon.pulsar.agentic.agents.BasicBrowserAgent
import ai.platon.pulsar.agentic.model.TcException
import ai.platon.pulsar.agentic.model.ToolCall
import ai.platon.pulsar.agentic.model.ToolSpec
import ai.platon.pulsar.agentic.tools.CustomToolRegistry
import ai.platon.pulsar.agentic.tools.builtin.ToolExecutor
import ai.platon.pulsar.core.api.WebDriver
import ai.platon.pulsar.rest.session.PulsarSessionManager
import ai.platon.pulsar.common.brief
import ai.platon.pulsar.common.serialize.json.pulsarObjectMapper
import ai.platon.pulsar.dom.FeaturedDocument
import ai.platon.pulsar.skeleton.workflow.parse.html.PageSummaryIndexService
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonSetter
import com.fasterxml.jackson.annotation.Nulls
import com.fasterxml.jackson.databind.node.ArrayNode
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

        /**
         * Build the JavaScript expression that restores focus to a selector
         * during batch execution.  Returns a self-invoking function that:
         *
         * 1. Queries the selector in the page
         * 2. Calls `.focus()` on the element if found
         * 3. Returns 'focused', 'missing', or 'invalid:<error>'
         *
         * The [selector] value is JSON-escaped via [pulsarObjectMapper] so
         * that special characters (quotes, backslashes) don't break the JS
         * string literal.
         */
        internal fun buildBatchFocusExpression(selector: String): String {
            if (selector.startsWith("backend:")) {
                return "''"
            }
            val selectorLiteral = pulsarObjectMapper().writeValueAsString(selector)
            return """
                (() => {
                    try {
                        const el = document.querySelector($selectorLiteral);
                        if (!el) return 'missing';
                        if (typeof el.focus === 'function') {
                            el.focus();
                        }
                        return document.activeElement === el ? 'focused' : 'unfocused';
                    } catch (error) {
                        return `invalid:${'$'}{error}`;
                    }
                })()
            """.trimIndent()
        }
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
                "command_batch" -> handleCommandBatch(request)
                // All other tools → dynamic dispatch through CustomToolRegistry or AgentToolManager
                else -> dispatchToToolExecutor(request)
            }
        } catch (e: Throwable) {
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
            """{"sessionId":"${s.sessionId}","url":"${s.url ?: ""}","status":"${s.status}","""
                .plus(""""createdAt":${s.createdAt},"lastAccessedAt":${s.lastAccessedAt}}""")
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

        // Extension-attached sessions (Browser4 Chrome Extension relay)
        val isExtension = args["extension"]?.let { ext ->
            ext is Boolean && ext || ext.toString().let { it == "true" || it.isNotBlank() }
        } ?: false

        if (isExtension) {
            val channel = (args["channel"] as? String)?.takeIf { it.isNotBlank() }
            val info = sessionManager.createExtensionAttachedSession(
                channel = channel,
                capabilities = args.filterKeys {
                    it != "extension" && it != "channel" && it != "sessionId"
                }.mapValues { it.value?.toString() }
            )
            logger.info(
                "MCP attach_browser: created extension session {} | wsEndpoint={} | channel={}",
                info.sessionId, info.wsEndpoint, channel ?: "default"
            )
            return ResponseEntity.ok(
                textResponse("""{"sessionId":"${info.sessionId}","wsEndpoint":"${info.wsEndpoint}"}""")
            )
        }

        val cdpEndpoint = (args["cdpEndpoint"] as? String)?.takeIf { it.isNotBlank() }
        val cdpPort = (args["cdpPort"] as? Number)?.toInt()

        require(cdpEndpoint != null || cdpPort != null) {
            "attach_browser requires either 'cdpEndpoint' (URL), 'cdpPort' (number), or 'extension' (boolean)"
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

    // =========================================================================
    // Batch command handler
    // =========================================================================

    private suspend fun handleCommandBatch(request: MCPToolCallRequest): ResponseEntity<MCPToolCallResponse> {
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
        return ResponseEntity.ok(textResponse(pulsarObjectMapper().writeValueAsString(body)))
    }

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
            MCPConstants.OP_PDF -> handleBatchPdf(index, step, currentSessionId)
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

        logger.info("Calling batch tool step: $index $tool ${arguments.entries.joinToString(" ") { "--${it.key}=${it.value}" }}")

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

    private suspend fun handleBatchPdf(
        index: Int,
        step: Map<String, Any?>,
        currentSessionId: String?
    ): BatchExecutionResult {
        val sessionId = requireSessionId(currentSessionId)
        val tool = step[MCPConstants.KEY_TOOL]?.toString()
            ?: throw IllegalArgumentException(MCPConstants.ERROR_MISSING_TOOL)
        val arguments =
            step[MCPConstants.KEY_ARGUMENTS].toAnyMap().orEmpty() + (MCPConstants.KEY_SESSION_ID to sessionId)
        val pdf = executeAgentToolText(tool, arguments)

        return BatchExecutionResult(index = index, ok = true, pdf = pdf)
    }

    // =========================================================================
    // Batch focus / mouse position restoration
    // =========================================================================

    private suspend fun restoreBatchFocus(sessionId: String, selector: String) {
        if (selector.startsWith("backend:")) {
            return
        }

        val focusExpression = buildBatchFocusExpression(selector)

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
            // Restore sessionId stripped by normalizeToolArguments — custom executors
            // (e.g. webdb_export) may need it.
            val sessionId = normalizedRequest.arguments["sessionId"]
            val execArgs = if (sessionId != null) {
                args.toMutableMap().also { it["sessionId"] = sessionId }
            } else {
                args
            }
            return dispatchToCustomExecutor(toolName, domain, execArgs, customExecutor, request)
        }

        // Fall back to per-session agent tool dispatch
        return dispatchToAgentToolExecutor(request)
    }

    /**
     * Extract the domain from an MCP tool name.
     *
     * Tool names follow the `domain_method` convention (e.g. `crawl_submit` →
     * `crawl`).  Compound domains that themselves contain underscores (e.g.
     * `html_snapshot_capture` → domain `html_snapshot`, method `capture`) are
     * resolved by checking the tool name prefix against every domain registered
     * in [CustomToolRegistry].
     *
     * When no registered domain matches, falls back to splitting on the first
     * `_` for backward compatibility with legacy names like `go_back` (domain
     * `go`, method `back`).  For names without `_`, the entire name is the
     * domain.
     */
    internal fun extractDomain(toolName: String): String {
        // 1) Check registered CustomToolRegistry domains first — these may
        //    contain underscores (e.g. "html_snapshot").  Pick the longest
        //    matching prefix so "html_snapshot" beats "html" when both are
        //    hypothetically registered.
        val knownDomains = CustomToolRegistry.instance.getAllDomains()
        val matchingDomain = knownDomains
            .filter { toolName.startsWith("${it}_") || toolName == it }
            .maxByOrNull { it.length }
        if (matchingDomain != null) {
            return matchingDomain
        }

        // 2) Fall back to legacy first-underscore splitting.
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
        // Derive method name from tool name: "pptx_generate" → "generate"
        val method = if (toolName.startsWith("${domain}_")) {
            toolName.substring(domain.length + 1)
        } else {
            toolName
        }

        // Resolve the receiver: for executors that require a WebDriver (e.g., pptx),
        // extract the session ID and get the session's driver.
        val receiver: Any = if (executor.receiverClass == WebDriver::class) {
            val sessionId = args["sessionId"]?.toString()
                ?: request.arguments?.get("sessionId")?.toString()
            if (sessionId != null) {
                val managed = sessionManager.getSession(sessionId)
                if (managed != null) {
                    try {
                        managed.driver
                    } catch (e: Exception) {
                        logger.warn("Failed to get driver for session {}: {}", sessionId, e.message)
                        Any()
                    }
                } else {
                    Any()
                }
            } else {
                Any()
            }
        } else {
            Any()
        }

        return try {
            val result = executor.callFunctionOn(ToolCall(domain, method, args.toMutableMap()), receiver)
            val evaluate = result
            val exception = evaluate.exception
            if (exception != null) {
                ResponseEntity.ok(errorResponse(buildErrorMessage(toolName, exception)))
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

        val result = agent.agentToolManager.execute(toolCall)

        val evaluate = result.evaluate
        evaluate.exception?.let { exception ->
            val errorMsg = buildString {
                append("$toolName failed: ${exception.message}")
                val causeMsg = exception.cause?.message
                if (causeMsg != null && causeMsg != exception.message) {
                    append(" ($causeMsg)")
                }
            }
            throw IllegalArgumentException(errorMsg)
        }
        // Distinguish JS null (className == "null") from JS undefined (className == "undefined")
        // and Kotlin Unit (no meaningful return value).
        // All three arrive as evaluate.value == null, but only JS null should produce visible output.
        return evaluate.value?.toString() ?: when (evaluate.className) {
            "null" -> "null"
            "undefined" -> "undefined"
            else -> ""
        }
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
                ResponseEntity.ok(errorResponse(buildErrorMessage(request.tool, exception)))
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

        // 1. Explicit mapping for legacy/special names.
        // Also includes essential tools whose specs are normally auto-generated from
        // @MCP-annotated WebDriver methods — the explicit entries ensure they resolve
        // even when ToolSpecGenerator cannot read WebDriver.kt from the classpath
        // (e.g. running from a JAR in CI).
        when (toolName) {
            "navigate" -> return ToolCall("tab", "navigate", args1)
            "reload" -> return ToolCall("tab", "reload", args1)
            "go_back" -> return ToolCall("tab", "goBack", args1)
            "go_forward" -> return ToolCall("tab", "goForward", args1)
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
    internal fun toMcpToolName(domain: String, method: String): String {
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
     * Build an error message for a tool call failure, enriching it with
     * contextual tips when the error matches known patterns (e.g. "not focusable").
     */
    private fun buildErrorMessage(toolName: String, exception: TcException): String {
        val message = exception.message ?: "unknown error"
        val sb = StringBuilder("$toolName failed: $message")

        // Contextual tips for known error patterns
        if (message.contains("not focusable", ignoreCase = true)) {
            sb.append(" Tip: Use 'click <ref>' first to focus the element")
        }

        // Explicit help from the tool executor
        if (!exception.help.isNullOrBlank()) {
            sb.append(" help: ${exception.help}")
        }

        return sb.toString()
    }

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

// =========================================================================
// html_snapshot_inspect — core algorithm (extracted for testability)
// =========================================================================

/**
 * Run the visual geometry first link group detection algorithm
 * ([PageSummaryIndexService.detectLinkGroups]) and extract the best
 * repeating-pattern selector.
 *
 * The visual algorithm clusters elements by bounding-box geometry
 * (width → height → x-position → y-spacing regularity), then walks
 * up the DOM to find the container. It is language-independent,
 * class-name-independent, and tolerant of varying internal DOM structure.
 *
 * @return Pair(bestItemSelector, linkGroups) where bestItemSelector is
 *   the itemSelector of the highest-scoring link group, or null if none
 *   found.
 */
/**
 * A single candidate selector discovered by [autoDiscoverRepeatingSelector],
 * ranked by its structural score. Returned as a list so the CLI can show
 * alternatives when the top pick isn't what the user wants (e.g. `li`
 * nav items on a news portal).
 */
data class DiscoveredSelector(
    val selector: String,
    val matchCount: Int,
    val score: Double,
    val sampleText: String,
)

private fun runVisualDetection(
    document: FeaturedDocument
): Pair<String?, List<PageSummaryIndexService.SummaryLinkGroup>> {
    val linkGroups = PageSummaryIndexService.detectLinkGroups(document)
    val bestSelector = linkGroups.maxByOrNull { it.score }?.itemSelector
    return Pair(bestSelector, linkGroups)
}

/**
 * Auto-discovers the best CSS selector for repeating content patterns on a page.
 *
 * Used as a fallback when the user-specified selector (e.g. default `:root`)
 * matches ≤1 element — making cross-match comparison impossible.
 *
 * Algorithm: walks the DOM, groups each parent's direct children by CSS
 * signature (tag + up to 2 classes), then scores each group by:
 *   size × class-boost(2.0x) × text-diversity(graduated) ×
 *   structural-richness(graduated) × image-presence(1.4x) ×
 *   child-tag-diversity(graduated) × short-text-penalty(0.6x) ×
 *   bare-div-penalty(0.5x)
 *
 * Product cards (images, diverse children, long varied text) score much higher
 * than navigation items (no images, shallow, short uniform text).
 *
 * @return a CSS selector string (e.g. ".product-card", "li"), or null if no
 *   suitable repeating pattern found (single article pages, etc.)
 */
internal fun autoDiscoverRepeatingSelector(document: FeaturedDocument, topN: Int = 5): List<DiscoveredSelector> {
    val structuralTags = setOf("html", "head", "body", "script", "style", "meta", "link", "noscript")
    val structuralBare = setOf("div", "span")

    val allCandidates = mutableListOf<DiscoveredSelector>()

    for (parent in document.select("*")) {
        val parentTag = parent.tagName().lowercase()
        if (parentTag in structuralTags && parentTag != "body") continue

        val children = parent.children()
        if (children.size < 2) continue

        // Group direct children by CSS signature: "tag.class1.class2" or bare "tag"
        val groups = mutableMapOf<String, MutableList<org.jsoup.nodes.Element>>()
        for (child in children) {
            val tag = child.tagName().lowercase()
            if (tag in structuralTags) continue
            val cls = child.className().trim()
            val sig = if (cls.isNotBlank()) {
                val classes = cls.split("\\s+".toRegex()).take(2).joinToString(".") { it }
                "$tag.$classes"
            } else {
                tag
            }
            groups.getOrPut(sig) { mutableListOf() }.add(child)
        }

        for ((sig, members) in groups) {
            if (members.size < 2) continue

            val hasClasses = sig.contains(".")
            // Use text() (all descendant text) rather than ownText() so compound
            // elements like product cards show content variance across matches.
            val distinctText = members.map { it.text().trim() }.filter { it.isNotBlank() }.distinct().size
            val avgDesc = members.map { it.select("*").size.toDouble() }.average()
            val isStructuralDiv = !hasClasses && sig in structuralBare

            var score = members.size.toDouble()
            // Class-based selectors are far more reusable than bare tags
            if (hasClasses) score *= 2.0

            // Graduated text diversity: product cards have distinct titles/prices;
            // navigation shortcuts are nearly uniform.
            score *= when {
                distinctText >= 5 -> 1.8
                distinctText >= 3 -> 1.4
                distinctText >= 2 -> 1.2
                else -> 1.0
            }

            // Graduated structural richness: product cards contain many nested
            // elements (img, h2, span, a); nav items are shallow.
            score *= when {
                avgDesc >= 15 -> 2.0
                avgDesc >= 8  -> 1.6
                avgDesc >= 3  -> 1.2
                else -> 1.0
            }

            // Image presence: product cards almost always contain <img> tags;
            // navigation shortcuts never do.
            val membersWithImages = members.count { it.select("img").isNotEmpty() }
            val imageRatio = membersWithImages.toDouble() / members.size
            if (imageRatio >= 0.5) score *= 1.4

            // Child tag-type diversity: diverse direct children (img, h2, span,
            // a, button) signal a content card rather than a simple nav item.
            val distinctChildTags = members.flatMap { member ->
                member.children().map { it.tagName().lowercase() }
            }.distinct().size
            score *= when {
                distinctChildTags >= 6 -> 1.5
                distinctChildTags >= 4 -> 1.3
                distinctChildTags >= 2 -> 1.1
                else -> 1.0
            }

            // Short-text penalty: navigation items have very brief labels
            // ("Home", "Next"); product cards have rich descriptive text.
            val avgTextLength = members.map { it.text().trim().length.toDouble() }.average()
            if (imageRatio < 0.3 && avgTextLength < 20.0) score *= 0.6

            // Text-length bonus: content elements (headlines, descriptions)
            // have significantly longer text than navigation shortcuts.
            // News headlines: 20-100+ chars; nav items: 2-8 chars.
            // This bonus counteracts the sheer-number advantage of nav <li>
            // elements on sites like people.com.cn (302 nav items vs 15 news).
            score *= when {
                avgTextLength >= 50 -> 2.5
                avgTextLength >= 30 -> 1.8
                avgTextLength >= 20 -> 1.4
                else -> 1.0
            }

            if (isStructuralDiv) score *= 0.5

            // Chrome penalty: patterns inside nav/header/footer/aside containers
            // (or elements with ARIA navigation roles) are page chrome, not
            // primary content. This prevents nav shortcuts from outscoring
            // product cards when they appear more frequently on the page.
            val chromeAncestorTags = setOf("nav", "header", "footer", "aside")
            val chromeAncestorRoles = setOf("navigation", "banner", "contentinfo", "complementary")
            var isChrome = false
            for (member in members.take(3)) {
                var anc: org.jsoup.nodes.Element? = member.parent()
                while (anc != null) {
                    if (anc.tagName().lowercase() in chromeAncestorTags) {
                        isChrome = true; break
                    }
                    val role = anc.attr("role").lowercase()
                    if (role in chromeAncestorRoles) {
                        isChrome = true; break
                    }
                    anc = anc.parent()
                }
                if (isChrome) break
            }
            if (isChrome) score *= 0.3

            // Viewport position weighting: elements near the top of the page
            // are more likely to be primary content. Navigation tends to sit
            // in a narrow band at the very top; product cards span the
            // main content area below.
            val yPositions = members.mapNotNull { member ->
                val vi = member.attr("vi").trim()
                if (vi.isNotBlank()) {
                    val parts = vi.split("\\s+".toRegex())
                    if (parts.size >= 2) parts[1].toDoubleOrNull() else null
                } else null
            }.take(5)
            if (yPositions.isNotEmpty()) {
                val avgY = yPositions.average()
                when {
                    // Prime content band (100–800px): title, price, key details
                    avgY in 100.0..800.0 -> score *= 1.3
                    // Deep page / footer region (> 2500px): likely related items
                    avgY > 2500.0 -> score *= 0.7
                    // Very top (< 100px): likely site header / nav bar
                    avgY in 0.0..100.0 -> score *= 0.8
                }
            }

            // Content-area bonus: if the parent is a semantic content container
            // (<main>, <article>, role="main"), it's more likely to hold
            // meaningful content than a generic <div> wrapper.
            val parentTagLc = parent.tagName().lowercase()
            val parentRole = parent.attr("role").lowercase()
            if (parentTagLc in setOf("main", "article") || parentRole == "main") {
                score *= 1.5
            }

            // Build usable CSS selector: use class if present, otherwise bare tag
            val usableSelector = if (hasClasses) ".${sig.substringAfter(".")}" else sig
            val sampleTexts = members.map { it.text().trim() }.filter { it.isNotBlank() }.take(3)
            allCandidates.add(
                DiscoveredSelector(
                    selector = usableSelector,
                    matchCount = members.size,
                    score = score,
                    sampleText = sampleTexts.joinToString(" | ")
                )
            )
        }
    }

    return allCandidates
        .sortedByDescending { it.score }
        .take(topN)
}

/**
 * Analyse a [document] and produce selector suggestions for recurring patterns.
 *
 * Pure function: takes a parsed document + parameters, returns a JSON result
 * string. Callable from tests without session/browser infrastructure.
 */
internal fun inspectDocument(
    document: FeaturedDocument,
    selector: String,
    maxMatches: Int,
    maxDepth: Int,
): String {
    // ── Visual geometry first detection ──────────────────────────────────
    // Always run visual detection early — it informs both auto-discovery
    // (when the user hasn't specified a selector) and speculative suggestions
    // (when the user's selector could be improved).
    val (visualBestSelector, visualLinkGroups) = runVisualDetection(document)

    // Auto-discovery: when the selector matches ≤1 element (e.g. default
    // :root), find the best repeating content pattern.
    // Prefer the visual geometry algorithm (language-independent,
    // class-name-independent, structure-tolerant). Fall back to the
    // structural-signature approach only when visual detection finds nothing.
    var effectiveSelector = selector
    var autoDiscovered = false
    var autoDiscoveredCandidates: List<DiscoveredSelector> = emptyList()
    var speculativeSuggestion: String? = null
    var speculativeMatchCount: Int? = null
    val initialMatchCount = document.select(selector).size
    if (initialMatchCount <= 1) {
        // If the user specified a container selector (matches exactly 1 element),
        // scope the discovery to descendants of that container rather than searching
        // the entire page. Only fall back to page-level discovery when the container
        // has no repeating children.
        val containerElement = if (initialMatchCount == 1 && selector != ":root") {
            document.selectFirst(selector)
        } else null

        if (containerElement != null) {
            // Try to discover repeating patterns within the container.
            // Build a minimal JSoup document from the container's inner HTML so the
            // discovery algorithm only sees the container's descendants.
            val innerHtml = containerElement.html()
            if (innerHtml.isNotBlank()) {
                val subDoc = FeaturedDocument(org.jsoup.Jsoup.parse(innerHtml))
                val candidates = autoDiscoverRepeatingSelector(subDoc)
                val discovered = candidates.firstOrNull()
                if (discovered != null) {
                    // Prefix the discovered selector with the container selector
                    effectiveSelector = "$selector ${discovered.selector}"
                    autoDiscovered = true
                }
            }
        }

        // If container-scoped discovery didn't find anything (or there's no container),
        // fall back to page-level discovery
        if (!autoDiscovered) {
            if (visualBestSelector != null) {
                effectiveSelector = visualBestSelector
                autoDiscovered = true
            } else {
                // Fallback: structural-signature discovery
                val candidates = autoDiscoverRepeatingSelector(document)
                val discovered = candidates.firstOrNull()
                if (discovered != null) {
                    effectiveSelector = discovered.selector
                    autoDiscovered = true
                }
                // Collect remaining candidates for the user to explore
                autoDiscoveredCandidates = candidates.drop(1)
            }
        }
    } else if (visualBestSelector != null && visualBestSelector != selector) {
        // Mode B (speculative): user's selector already matches ≥2, but visual
        // detection found a potentially better repeating pattern. Surface as a
        // suggestion without overriding the user's choice.
        speculativeSuggestion = visualBestSelector
        speculativeMatchCount = document.select(visualBestSelector).size
    }

    val matches = document.select(effectiveSelector).take(maxMatches)
    val matchCount = document.select(effectiveSelector).size

    // Pre-compute element weights for all interactive elements in the document.
    // Used to boost selector candidates that target high-importance elements.
    val interactiveSelector = "a[href], button, input:not([type=hidden]), select, textarea, " +
            "details, summary, " +
            "[role=button], [role=link], [role=checkbox], [role=radio], " +
            "[role=tab], [role=menuitem], [role=switch], [role=combobox], " +
            "[role=searchbox], [role=textbox], [role=slider], [role=spinbutton], " +
            "[role=option], [role=treeitem], " +
            "[tabindex]:not([tabindex=\"-1\"]), [contenteditable=true], " +
            "[onclick], [onkeydown], [onsubmit]"
    val elementWeightMap: Map<org.jsoup.nodes.Element, Int> = try {
        val allInteractive = document.select(interactiveSelector)
        computeInteractiveWeights(allInteractive).associate { (el, weight, _) -> el to weight }
    } catch (e: Exception) {
        emptyMap()
    }

    if (matches.isEmpty()) {
        return pulsarObjectMapper().createObjectNode().apply {
            put("matchCount", 0)
            put("selector", effectiveSelector)
            if (autoDiscovered) {
                put("autoDiscovered", true)
                put("originalSelector", selector)
            }
            if (autoDiscoveredCandidates.isNotEmpty()) {
                set<ArrayNode>("autoDiscoveredCandidates", candidatesToJson(autoDiscoveredCandidates))
            }
            if (speculativeSuggestion != null) {
                put("speculativeSuggestion", speculativeSuggestion)
                speculativeMatchCount?.let { put("speculativeMatchCount", it) }
            }
            putArray("suggestions")
            if (visualLinkGroups.isNotEmpty()) {
                set<ArrayNode>("linkGroups", linkGroupsToJson(visualLinkGroups))
            }
        }.toString()
    }

    // Build sample structures for the first 3 matches (Section 8 format)
    val samples = pulsarObjectMapper().createArrayNode()
    for (m in matches.take(3)) {
        val sample = pulsarObjectMapper().createObjectNode()
        // Section 8 element reference: "#closestId tag#id.class1.class2"
        sample.put("ref", buildElementRef(m))
        // Bounding box from vi attr
        val mBox = m.attr("vi")
        if (mBox.isNotBlank()) sample.put("box", mBox)
        // Text: full descendant text ≤5 words / ≤5 CJK chars
        // (ownText is often empty — e.g. <a><em>$</em><span>140</span></a>)
        val ownText = truncateText(m.text().trim())
        if (ownText.isNotBlank()) sample.put("text", ownText)

        // Direct children (also in Section 8 format)
        val children = pulsarObjectMapper().createArrayNode()
        for (child in m.children().take(20)) {
            val childEl = child as? org.jsoup.nodes.Element ?: continue
            val cObj = pulsarObjectMapper().createObjectNode()
            cObj.put("ref", buildElementRef(childEl))
            val cBox = childEl.attr("vi")
            if (cBox.isNotBlank()) cObj.put("box", cBox)
            val cText = truncateText(childEl.text().trim())
            if (cText.isNotBlank()) cObj.put("text", cText)
            children.add(cObj)
        }
        sample.set<ArrayNode>("children", children)
        samples.add(sample)
    }

    // Find recurring descendant selectors across matches
    data class SelectorCandidate(
        val selector: String,
        val tag: String,
        val selectorType: String, // "id", "class", "attr", "bare", "power"
    )

    // Track count + per-match text samples + max element weight for value previews
    class CandidateStats(
        var count: Int = 0,
        val textValues: MutableMap<Int, String> = mutableMapOf(), // matchIndex -> text
        var maxWeight: Int = 0, // highest weight among elements matching this candidate
    )

    val candidateStats = mutableMapOf<SelectorCandidate, CandidateStats>()

    // Attribute names worth surfacing as selectors (ordered by priority)
    val priorityAttrs = listOf("data-testid", "aria-label", "role", "itemprop")
    val dataAttrPattern = Regex("^data-.+")
    val structuralTags = setOf("html", "head", "body", "script", "style", "meta", "link", "noscript")

    for ((matchIndex, match) in matches.withIndex()) {
        val seen = mutableSetOf<SelectorCandidate>()
        for (desc in match.select("*")) {
            val depth = desc.parents().indexOfFirst { it === match } + 1
            if (depth < 0 || depth > maxDepth) continue
            val descTag = desc.tagName().lowercase()
            if (descTag in structuralTags) continue

            val descClass = desc.className()
            val descId = desc.id()
            val descText = desc.ownText().trim().take(80)

            // Build selector candidates: class/id first, then attribute, then bare tag
            val candidates = mutableListOf<Pair<String, String>>() // selector to type

            // 1. Class-based selector (primary)
            if (descClass.isNotBlank()) {
                val classes = descClass.split("\\s+".toRegex()).take(2).joinToString(".") { it }
                val sel = if (descId.isNotBlank()) "${descTag}.$classes#${descId}"
                else "${descTag}.$classes"
                candidates.add(sel to "class")
            } else if (descId.isNotBlank()) {
                candidates.add("${descTag}#${descId}" to "id")
            }

            // 2. Bare tag (fallback — lowest priority, always included)
            candidates.add(descTag to "bare")

            // 3. Attribute-based selectors from priority attrs
            for (attr in priorityAttrs) {
                val value = desc.attr(attr).trim()
                if (value.isNotBlank() && value.length <= 40) {
                    candidates.add("[$attr=\"$value\"]" to "attr")
                }
            }
            // 3b. Generic data-* attributes (lower priority than named ones)
            for (attr in desc.attributes()) {
                val key = attr.key
                if (key in priorityAttrs) continue // already handled above
                if (dataAttrPattern.matches(key)) {
                    val value = attr.value.trim()
                    if (value.isNotBlank() && value.length <= 40) {
                        candidates.add("[$key=\"$value\"]" to "attr")
                    }
                }
            }

            // 4. PowerCSS :expr() selectors from visual features (vi attribute)
            val vi = desc.attr("vi").trim()
            if (vi.isNotBlank()) {
                val viParts = vi.split("\\s+".toRegex())
                if (viParts.size >= 4) {
                    val viWidth = viParts[2].toDoubleOrNull()?.toInt() ?: 0
                    val viHeight = viParts[3].toDoubleOrNull()?.toInt() ?: 0

                    // Large elements are likely meaningful content blocks
                    if (viWidth >= 200) {
                        val w = (viWidth / 100) * 100 // round down to nearest 100
                        candidates.add("${descTag}:expr(width>${w})" to "power")
                        if (viHeight >= 100) {
                            val h = (viHeight / 100) * 100
                            candidates.add("${descTag}:expr(width>${w} && height>${h})" to "power")
                        }
                    }

                    // Image containers: elements that contain <img> descendants
                    val imgCount = desc.select("img").size
                    if (imgCount > 0) {
                        candidates.add("${descTag}:expr(img>0)" to "power")
                        if (viWidth >= 200) {
                            val w = (viWidth / 100) * 100
                            candidates.add("${descTag}:expr(width>${w} && img>0)" to "power")
                        }
                    }

                    // Link containers: elements that contain <a> descendants
                    val aCount = desc.select("a").size
                    if (aCount > 0) {
                        candidates.add("${descTag}:expr(a>0)" to "power")
                    }
                }
            }

            for ((sel, type) in candidates) {
                val candidate = SelectorCandidate(sel, descTag, type)
                if (seen.add(candidate)) {
                    val stats = candidateStats.getOrPut(candidate) { CandidateStats() }
                    stats.count++
                    if (descText.isNotBlank()) {
                        stats.textValues[matchIndex] = descText
                    }
                    // Track the highest element weight for this candidate
                    val elemWeight = elementWeightMap[desc] ?: 0
                    if (elemWeight > stats.maxWeight) {
                        stats.maxWeight = elemWeight
                    }
                }
            }
        }
    }

    // Filter to selectors appearing in >= 50% of matches (min 2 matches)
    val threshold = maxOf(2, (matches.size * 0.5).toInt())
    val filtered = candidateStats.entries.filter { it.value.count >= threshold }

    // ---- Quality scoring ----

    val semanticTags =
        setOf("h1", "h2", "h3", "h4", "h5", "h6", "a", "img", "button", "input", "select", "textarea", "label")

    fun distinctTextCount(stats: CandidateStats): Int =
        stats.textValues.values.filter { it.isNotBlank() }.distinct().size

    fun qualityScore(candidate: SelectorCandidate, stats: CandidateStats): Double {
        val n = stats.count.toDouble()
        // Specificity: class/id/attr selectors are more useful than bare tags
        val specificityPerMatch = when (candidate.selectorType) {
            "id" -> 0.7
            "class" -> 0.4
            "power" -> 0.35  // visual features: almost as stable as classes
            "attr" -> 0.2
            "bare" -> if (candidate.tag in setOf("div", "span")) -0.3 else -0.1
            else -> 0.0
        }
        // Distinctiveness: text that varies across matches signals unique data
        val distinctBoost = if (distinctTextCount(stats) >= 2) 0.3 else 0.0
        // Semantic tags (headings, links, form controls) carry more meaning
        val semanticBoost = if (candidate.tag in semanticTags) 0.2 else 0.0
        // Weight boost: candidates matching high-importance elements (buttons, primary controls,
        // prominent link groups) are more valuable. Normalize to 0..1 range (Tier 1 >= 1M).
        val weightBoost = if (stats.maxWeight > 0) {
            (stats.maxWeight / 1_000_000.0).coerceIn(0.0, 1.0) * 0.4
        } else 0.0
        return n + (specificityPerMatch * n) + (distinctBoost * n) + (semanticBoost * n) + (weightBoost * n)
    }

    // Sort by quality score descending, take top 40
    val ranked = filtered
        .sortedByDescending { (c, s) -> qualityScore(c, s) }
        .take(40)

    // Percentile-based quality tier (high = top quartile)
    val scores = ranked.map { (c, s) -> qualityScore(c, s) }
    val p75 = if (scores.isNotEmpty()) {
        val idx = (scores.size * 0.25).toInt().coerceIn(0, scores.size - 1)
        scores.sortedDescending()[idx]
    } else 0.0

    fun qualityTier(score: Double): String = when {
        score >= p75 -> "high"
        score >= p75 * 0.5 -> "medium"
        else -> "low"
    }

    // Build suggestions array
    val suggestions = pulsarObjectMapper().createArrayNode()
    for ((candidate, stats) in ranked) {
        val score = qualityScore(candidate, stats)
        val sug = pulsarObjectMapper().createObjectNode()
        sug.put("selector", candidate.selector)
        sug.put("tag", candidate.tag)
        // textPreview: first non-blank text (backward compat)
        val firstText = stats.textValues.values.firstOrNull { it.isNotBlank() }
        if (firstText != null) sug.put("textPreview", firstText)
        // textSamples: up to 3 distinct values across matches
        val distinctTexts = stats.textValues.values.filter { it.isNotBlank() }.distinct().take(3)
        if (distinctTexts.isNotEmpty()) {
            val samplesArr = pulsarObjectMapper().createArrayNode()
            distinctTexts.forEach { samplesArr.add(it) }
            sug.set<ArrayNode>("textSamples", samplesArr)
        }
        sug.put("matchCount", stats.count)
        sug.put("coverage", "%.0f%%".format(stats.count * 100.0 / matches.size))
        sug.put("quality", qualityTier(score))
        suggestions.add(sug)
    }

    // Reuse visual link groups detected early (visual geometry first algorithm)
    return pulsarObjectMapper().createObjectNode().apply {
        put("matchCount", matchCount)
        put("selector", effectiveSelector)
        put("analyzed", matches.size)
        if (autoDiscovered) {
            put("autoDiscovered", true)
            put("originalSelector", selector)
        }
        if (autoDiscoveredCandidates.isNotEmpty()) {
            set<ArrayNode>("autoDiscoveredCandidates", candidatesToJson(autoDiscoveredCandidates))
        }
        if (speculativeSuggestion != null) {
            put("speculativeSuggestion", speculativeSuggestion)
            speculativeMatchCount?.let { put("speculativeMatchCount", it) }
        }
        set<ArrayNode>("samples", samples)
        set<ArrayNode>("suggestions", suggestions)
        if (visualLinkGroups.isNotEmpty()) {
            set<ArrayNode>("linkGroups", linkGroupsToJson(visualLinkGroups))
        }
    }.toString()
}

// =========================================================================
// Element serialization utilities (Section 8 format)
// =========================================================================

/** Semantic ancestor tags used for grouping interactive elements. */
private val SEMANTIC_TAGS = setOf("nav", "form", "header", "main", "footer", "aside", "section", "article")

/** ARIA roles that indicate a semantic container. */
private val SEMANTIC_ROLES = setOf(
    "navigation", "search", "form", "banner", "contentinfo", "complementary",
    "main", "region", "article"
)

/**
 * Build the compact element reference format defined in Section 8:
 * `#closestId tag#id.class1.class2`
 *
 * - `#closestId`: id of the nearest ancestor that has an id attribute
 *   (empty string if none within 6 levels)
 * - `tag`: the element's own tag name
 * - `#id`: the element's own id (omitted if none)
 * - `.class1.class2`: up to 2 CSS classes (omitted if none)
 */
internal fun buildElementRef(el: org.jsoup.nodes.Element): String {
    val closestId = findClosestId(el)
    val idPart = if (closestId.isNotEmpty()) "#$closestId " else ""
    val ownId = el.id().takeIf { it.isNotBlank() }?.let { "#$it" } ?: ""
    val classPart = formatClassList(el)
    return "$idPart${el.tagName().lowercase()}$ownId$classPart"
}

/** Find the id of the nearest ancestor element (up to [maxLevels] levels up). */
internal fun findClosestId(el: org.jsoup.nodes.Element, maxLevels: Int = 6): String {
    var current: org.jsoup.nodes.Element? = el.parent()
    var level = 0
    while (current != null && level < maxLevels) {
        val id = current.id()
        if (id.isNotBlank()) return id
        current = current.parent()
        level++
    }
    return ""
}

/** Format up to 2 CSS classes as `.class1.class2`, or empty string if none. */
internal fun formatClassList(el: org.jsoup.nodes.Element): String {
    val cls = el.className().trim()
    if (cls.isBlank()) return ""
    val classes = cls.split("\\s+".toRegex()).take(2)
    return classes.joinToString("") { ".$it" }
}

/**
 * Truncate text to fit the Section 8 compact format:
 * ≤5 words for space-separated (Latin) languages, ≤5 characters for CJK.
 */
internal fun truncateText(text: String, maxWords: Int = 5): String {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return ""
    // If any CJK character is present, use character-based truncation
    if (trimmed.any { isCJK(it) }) {
        return trimmed.take(maxWords)
    }
    // Otherwise, word-based truncation
    val words = trimmed.split("\\s+".toRegex())
    return words.take(maxWords).joinToString(" ")
}

/** Check if a character is in a CJK (Chinese/Japanese/Korean) Unicode range. */
internal fun isCJK(c: Char): Boolean {
    val cp = c.code
    return cp in 0x4E00..0x9FFF   // CJK Unified Ideographs
        || cp in 0x3400..0x4DBF   // CJK Unified Ideographs Extension A
        || cp in 0xF900..0xFAFF   // CJK Compatibility Ideographs
        || cp in 0x3040..0x309F   // Hiragana
        || cp in 0x30A0..0x30FF   // Katakana
        || cp in 0xAC00..0xD7AF   // Hangul Syllables
        || cp in 0x2E80..0x2EFF   // CJK Radicals Supplement
        || cp in 0x3000..0x303F   // CJK Symbols and Punctuation
        || cp in 0xFF00..0xFFEF   // Halfwidth and Fullwidth Forms
}

/**
 * Find the nearest semantic ancestor for grouping.
 * Returns the tag name of the closest ancestor in [SEMANTIC_TAGS],
 * the ARIA role if the ancestor has a [SEMANTIC_ROLES] role,
 * or "Page" if no semantic ancestor is found within reasonable depth.
 */
internal fun findSemanticGroup(el: org.jsoup.nodes.Element): String {
    var current: org.jsoup.nodes.Element? = el.parent()
    var depth = 0
    while (current != null && depth < 10) {
        val tag = current.tagName().lowercase()
        if (tag in SEMANTIC_TAGS) return tag
        val role = current.attr("role").lowercase().trim()
        if (role in SEMANTIC_ROLES) return role
        // Also check for id-based grouping: common container patterns
        val id = current.id().lowercase().trim()
        if (id.isNotBlank() && (id.contains("nav") || id.contains("menu") ||
                id.contains("header") || id.contains("footer") ||
                id.contains("sidebar") || id.contains("content") ||
                id.contains("search") || id.contains("form"))
        ) {
            return id
        }
        current = current.parent()
        depth++
    }
    return "Page"
}

// =========================================================================
// Interactive Element Weighting
// =========================================================================

/**
 * Computes importance weights for interactive elements using a two-tier system.
 *
 * Tier 1 — Primary interactive controls (buttons, inputs, form controls, interactive ARIA roles).
 * Weight = 1_000_000 + area, ensuring they always rank above links.
 *
 * Tier 2 — Links (anchor elements with href). Links are grouped by x-coordinate (tolerance ε=10px),
 * then by area (20% relative tolerance). Each group's score = sum of member areas.
 * Links inherit their group's score as weight.
 *
 * Excluded: hidden (_h attr), aria-hidden, disabled, type=hidden, zero-area, pointer-events:none.
 *
 * @param elements Jsoup Elements to weight
 * @return sorted list of (element, weight, tier) ordered by weight descending
 */
internal fun computeInteractiveWeights(
    elements: List<org.jsoup.nodes.Element>
): List<Triple<org.jsoup.nodes.Element, Int, String>> {
    if (elements.isEmpty()) return emptyList()

    data class BoxInfo(
        val el: org.jsoup.nodes.Element,
        val x: Double, val y: Double, val w: Double, val h: Double,
        val area: Double, val tag: String, val role: String?
    )

    val infos = mutableListOf<BoxInfo>()

    for (el in elements) {
        // ---- exclusion ----
        if (el.attr("_h") == "1") continue
        if (el.attr("aria-hidden") == "true") continue
        if (el.hasAttr("disabled") || el.attr("aria-disabled") == "true") continue
        if (el.attr("type").lowercase() == "hidden") continue

        val style = el.attr("style")
        if ("pointer-events: none" in style.replace(" ", "") ||
            "pointer-events:none" in style.replace(" ", "")
        ) continue

        // ---- parse vi rect ----
        val vi = el.attr("vi")
        if (vi.isBlank()) continue
        val parts = vi.split("\\s+".toRegex())
        if (parts.size < 4) continue
        val x = parts[0].toDoubleOrNull() ?: continue
        val y = parts[1].toDoubleOrNull() ?: continue
        val w = parts[2].toDoubleOrNull() ?: continue
        val h = parts[3].toDoubleOrNull() ?: continue
        if (w <= 0 || h <= 0) continue

        val area = w * h
        val tag = el.tagName().lowercase()
        val role = el.attr("role").takeIf { it.isNotBlank() }?.lowercase()

        infos.add(BoxInfo(el, x, y, w, h, area, tag, role))
    }

    // ---- classify ----
    val tier1Tags = setOf("button", "input", "select", "textarea", "details", "summary")
    val tier1Roles = setOf(
        "button", "checkbox", "radio", "switch", "tab", "menuitem",
        "combobox", "searchbox", "textbox", "slider", "spinbutton", "option", "treeitem",
        "link", "menuitemcheckbox", "menuitemradio"
    )

    val tier1 = mutableListOf<Pair<BoxInfo, Int>>() // (info, weight)
    val links = mutableListOf<BoxInfo>()

    for (info in infos) {
        val isTier1 = info.tag in tier1Tags ||
                info.role in tier1Roles ||
                info.el.hasAttr("contenteditable") ||
                info.el.attr("contenteditable") == "true" ||
                info.el.hasAttr("onclick") ||
                info.el.hasAttr("onkeydown") ||
                info.el.hasAttr("onsubmit") ||
                (info.el.hasAttr("tabindex") && info.el.attr("tabindex") != "-1")

        if (isTier1) {
            tier1.add(info to (1_000_000 + info.area.toInt()))
        } else if (info.tag == "a" && info.el.hasAttr("href")) {
            links.add(info)
        }
    }

    // ---- Tier 2: link grouping ----
    val epsilon = 10.0        // x-coordinate tolerance (px)
    val areaTolerance = 0.2   // 20% relative area tolerance

    // Group by x-coordinate
    val xGroups = mutableListOf<MutableList<BoxInfo>>()
    for (link in links) {
        var found = false
        for (group in xGroups) {
            if (Math.abs(link.x - group.first().x) <= epsilon) {
                group.add(link)
                found = true
                break
            }
        }
        if (!found) {
            xGroups.add(mutableListOf(link))
        }
    }

    // Within each x-group, further group by area similarity
    val linkWeights = mutableMapOf<org.jsoup.nodes.Element, Int>()
    for (xGroup in xGroups) {
        xGroup.sortBy { it.area }

        val areaGroups = mutableListOf<MutableList<BoxInfo>>()
        for (link in xGroup) {
            var found = false
            for (group in areaGroups) {
                val refArea = group.first().area
                if (refArea > 0 && Math.abs(link.area - refArea) / refArea <= areaTolerance) {
                    group.add(link)
                    found = true
                    break
                }
            }
            if (!found) {
                areaGroups.add(mutableListOf(link))
            }
        }

        // Score each area-group and assign to all members
        for (areaGroup in areaGroups) {
            val score = areaGroup.sumOf { it.area }.toInt()
            for (link in areaGroup) {
                linkWeights[link.el] = score
            }
        }
    }

    // ---- build sorted result ----
    val result = mutableListOf<Triple<org.jsoup.nodes.Element, Int, String>>()

    // Tier 1: sort by weight descending
    tier1.sortedByDescending { it.second }.forEach { (info, weight) ->
        result.add(Triple(info.el, weight, "primary"))
    }

    // Tier 2: sort by weight descending (links in high-scoring groups first)
    links
        .filter { it.el in linkWeights }
        .sortedByDescending { linkWeights[it.el]!! }
        .forEach { link ->
            result.add(Triple(link.el, linkWeights[link.el]!!, "link"))
        }

    return result
}

// =========================================================================
// Link group serialization
// =========================================================================

/**
 * Serialize detected link groups to a Jackson [ArrayNode] for inclusion in
 * capture and inspect command outputs.
 *
 * Uses the same structure as the YAML output from [PageSummaryIndexService.generate]
 * so consumers get a consistent representation regardless of output format.
 */
internal fun linkGroupsToJson(
    linkGroups: List<PageSummaryIndexService.SummaryLinkGroup>,
): ArrayNode {
    val mapper = pulsarObjectMapper()
    val array = mapper.createArrayNode()
    for (lg in linkGroups) {
        val obj = mapper.createObjectNode().apply {
            val containerLabel = lg.containerTag + lg.containerSelector
            put("container", containerLabel)
            if (lg.containerSelector.isNotEmpty() && lg.containerSelector != lg.containerTag) {
                put("selector", lg.containerSelector)
            }
            put("itemTag", lg.itemTag)
            put("itemSelector", lg.itemSelector)
            put("count", lg.count)
            put("columnCount", lg.columnCount)
            put("viewportWidth", lg.viewportWidth)
            put("viewportHeight", lg.viewportHeight)
            put("allHaveLinks", lg.allHaveLinks)
            put("anyHaveImages", lg.anyHaveImages)
            put("avgCardWidth", lg.avgCardWidth)
            put("avgCardHeight", lg.avgCardHeight)
            put("distinctTextCount", lg.distinctTextCount)
            put("avgDescendants", lg.avgDescendants)
            if (lg.samples.isNotEmpty()) {
                val samplesArr = mapper.createArrayNode()
                for (sample in lg.samples) {
                    val sampleObj = mapper.createObjectNode().apply {
                        put("box", sample.box)
                        if (sample.links.isNotEmpty()) {
                            val linksArr = mapper.createArrayNode()
                            for (link in sample.links) {
                                val linkObj = mapper.createObjectNode().apply {
                                    put("text", link.text)
                                    put("href", link.href)
                                    put("box", link.box)
                                }
                                linksArr.add(linkObj)
                            }
                            set<ArrayNode>("links", linksArr)
                        }
                        put("hasImage", sample.hasImage)
                    }
                    samplesArr.add(sampleObj)
                }
                set<ArrayNode>("samples", samplesArr)
            }
            put("score", lg.score)
        }
        array.add(obj)
    }
    return array
}

/**
 * Serialize auto-discovered selector candidates into a JSON array.
 * Each entry contains the selector, match count, score, and sample text
 * so the CLI can display alternatives when the top pick isn't ideal
 * (e.g. `li` nav items on a news portal — the user can pick a better one).
 */
internal fun candidatesToJson(
    candidates: List<DiscoveredSelector>,
): ArrayNode {
    val mapper = pulsarObjectMapper()
    val array = mapper.createArrayNode()
    for (c in candidates) {
        val obj = mapper.createObjectNode().apply {
            put("selector", c.selector)
            put("matchCount", c.matchCount)
            put("score", c.score)
            put("sampleText", c.sampleText)
        }
        array.add(obj)
    }
    return array
}
