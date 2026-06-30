package ai.platon.pulsar.rest.mcp.controller

import ai.platon.browser4.boot.skill.SkillService
import ai.platon.pulsar.agent.tool.UserCommandExecutor
import ai.platon.pulsar.common.brief
import ai.platon.pulsar.common.serialize.json.pulsarObjectMapper
import ai.platon.pulsar.common.sql.SQLTemplate
import ai.platon.pulsar.rest.api.service.CrawlService
import ai.platon.pulsar.rest.api.service.ScrapeService
import ai.platon.pulsar.rest.api.service.SwarmService
import ai.platon.pulsar.rest.mcp.controller.dto.MCPContent
import ai.platon.pulsar.rest.mcp.controller.dto.MCPToolCallRequest
import ai.platon.pulsar.rest.mcp.controller.dto.MCPToolCallResponse
import ai.platon.pulsar.rest.mcp.controller.handler.CommandHandler
import ai.platon.pulsar.rest.mcp.controller.handler.CrawlMcpHandler
import ai.platon.pulsar.rest.mcp.controller.handler.DomSnapshotHandler
import ai.platon.pulsar.rest.mcp.controller.handler.SessionManagementHandler
import ai.platon.pulsar.rest.mcp.controller.handler.SkillMcpHandler
import ai.platon.pulsar.rest.mcp.controller.handler.SwarmMcpHandler
import ai.platon.pulsar.rest.mcp.controller.handler.ToolListHandler
import ai.platon.pulsar.rest.session.PulsarSessionManager
import ai.platon.pulsar.skeleton.workflow.parse.html.PageSummaryIndexService
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode

import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.util.*

/**
 * REST controller that exposes Browser4 MCP tools over HTTP.
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
    private val commandExecutor: UserCommandExecutor,
    private val scrapeService: ScrapeService? = null,
    private val objectMapper: ObjectMapper,
    private val swarmService: SwarmService? = null,
    private val crawlService: CrawlService? = null,
    private val skillService: SkillService? = null,
) {
    private val logger = LoggerFactory.getLogger(MCPToolController::class.java)

    // Handler delegates
    private val sessionHandler = SessionManagementHandler(sessionManager)
    private val commandHandler = CommandHandler(sessionManager, commandExecutor, objectMapper)
    private val domSnapshotHandler = DomSnapshotHandler(sessionManager, scrapeService, objectMapper)
    private val swarmMcpHandler = swarmService?.let { SwarmMcpHandler(it, objectMapper) }
    private val crawlMcpHandler = crawlService?.let { CrawlMcpHandler(it, objectMapper) }
    private val skillMcpHandler = skillService?.let { SkillMcpHandler(it, objectMapper) }
    private val toolListHandler = ToolListHandler(sessionManager)

    // =========================================================================
    // Tool call endpoint
    // =========================================================================

    @PostMapping("/call-tool", consumes = [MediaType.APPLICATION_JSON_VALUE])
    suspend fun callTool(
        @RequestBody request: MCPToolCallRequest,
        response: HttpServletResponse
    ): ResponseEntity<MCPToolCallResponse> {
        addRequestId(response)

        logger.info("Calling tool: ${request.tool} " + request.arguments?.entries?.joinToString(" ") { "--" + it.key + "=" + it.value })

        return try {
            when (request.tool) {
                // Session management tools
                "open_session" -> sessionHandler.handleOpenSession(request)
                "close_session" -> sessionHandler.handleCloseSession(request)
                "list_sessions" -> sessionHandler.handleListSessions()
                "close_all_sessions" -> sessionHandler.handleCloseAllSessions()
                "kill_all_sessions" -> sessionHandler.handleKillAllSessions()
                "delete_session_data" -> sessionHandler.handleDeleteSessionData(request)
                "attach_browser" -> sessionHandler.handleAttachBrowser(request)
                "check_session_ready" -> sessionHandler.handleCheckSessionReadiness(request)
                // Command tools
                "command_run" -> commandHandler.handleCommandRun(request)
                "command_batch" -> commandHandler.handleCommandBatch(request)
                "command_status" -> commandHandler.handleCommandStatus(request)
                "command_result" -> commandHandler.handleCommandResult(request)
                // DOM snapshot tools
                "dom_snapshot_capture" -> domSnapshotHandler.handleDomSnapshotCapture(request)
                "dom_snapshot_scrape" -> domSnapshotHandler.handleDomSnapshotScrape(request)
                "dom_snapshot_scrape_all" -> domSnapshotHandler.handleDomSnapshotScrapeAll(request)
                "dom_snapshot_query" -> domSnapshotHandler.handleDomSnapshotQuery(request)
                "dom_snapshot_export" -> domSnapshotHandler.handleDomSnapshotExport(request)
                "dom_snapshot_summary" -> domSnapshotHandler.handleDomSnapshotSummary(request)
                "dom_snapshot_inspect" -> handleDomSnapshotInspect(request)
                // Swarm tools
                "swarm_submit" -> requireHandler(swarmMcpHandler).handleSwarmSubmit(request)
                "swarm_query" -> requireHandler(swarmMcpHandler).handleSwarmQuery(request)
                "swarm_status" -> requireHandler(swarmMcpHandler).handleSwarmStatus(request)
                "swarm_result" -> requireHandler(swarmMcpHandler).handleSwarmResult(request)
                // Crawl tools
                "crawl_submit" -> requireHandler(crawlMcpHandler).handleCrawlSubmit(request)
                "crawl_status" -> requireHandler(crawlMcpHandler).handleCrawlStatus(request)
                "crawl_result" -> requireHandler(crawlMcpHandler).handleCrawlResult(request)
                // Skill management tools
                "skill_list" -> requireHandler(skillMcpHandler).handleSkillList()
                "skill_info" -> requireHandler(skillMcpHandler).handleSkillInfo(request)
                "skill_install" -> requireHandler(skillMcpHandler).handleSkillInstall(request)
                "skill_uninstall" -> requireHandler(skillMcpHandler).handleSkillUninstall(request)
                "skill_reload" -> requireHandler(skillMcpHandler).handleSkillReload(request)
                // All other tools are dispatched to the session's agent
                else -> dispatchToAgentToolExecutor(request)
            }
        } catch (e: Exception) {
            logger.error("MCP tool call failed | tool={} | {}", request.tool, e.message, e)
            ResponseEntity.ok(errorResponse("${request.tool} failed: ${e.message}"))
        }
    }

    // =========================================================================
    // Tool listing endpoint
    // =========================================================================

    @GetMapping("/tools")
    fun listTools(
        response: HttpServletResponse
    ): ResponseEntity<Any> {
        addRequestId(response)
        val tools = toolListHandler.listToolNames()
        return ResponseEntity.ok(mapOf("tools" to tools))
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

    // =========================================================================
    // Command tool handlers
    // =========================================================================

    /**
     * Execute a plain command via the unified [AgentToolManager] path.
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
     * [AgentToolManager] and maps the result to an [MCPToolCallResponse].
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

    private suspend fun executeBatchStep(
        index: Int,
        step: Map<String, Any?>,
        currentSessionId: String?,
    ): BatchExecutionResult {
        val op = step[MCPConstants.KEY_OP]?.toString()
            ?: throw IllegalArgumentException(MCPConstants.ERROR_MISSING_OP)

        // Validate that only DOM operations are allowed in batch
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

    private suspend fun restoreBatchFocus(sessionId: String, selector: String) {
        if (selector.startsWith("backend:")) {
            return
        }

        val selectorLiteral = pulsarObjectMapper().writeValueAsString(selector)
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

        val result = agent.agentToolManager.execute(toolCall)

        val evaluate = result.evaluate
        evaluate.exception?.let { exception ->
            throw IllegalArgumentException("$toolName failed: ${exception.message} help: ${exception.help}")
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

    // =========================================================================
    // DOM snapshot handlers
    // =========================================================================

    private suspend fun handleDomSnapshotCapture(
        request: MCPToolCallRequest
    ): ResponseEntity<MCPToolCallResponse> {
        val sessionId = requireSessionId(request)
        val managed = sessionManager.getSession(sessionId)
            ?: return ResponseEntity.ok(errorResponse("${MCPConstants.ERROR_SESSION_NOT_FOUND}$sessionId"))

        return try {
            val metadata = managed.withLock {
                val pulsarSession = managed.agenticSession
                val page = pulsarSession.capture(managed.driver)
                val document = pulsarSession.parse(page, noCache = true)
                val title = document.title

                // Count images and links
                val imageCount = document.select("img").size
                val linkCount = document.select("a").size

                // Extract non-trivial interactive elements
                val interactiveSelector = "a[href], button, input:not([type=hidden]), select, textarea, " +
                    "details, summary, " +
                    "[role=button], [role=link], [role=checkbox], [role=radio], " +
                    "[role=tab], [role=menuitem], [role=switch], [role=combobox], " +
                    "[role=searchbox], [role=textbox], [role=slider], [role=spinbutton], " +
                    "[role=option], [role=treeitem], " +
                    "[tabindex]:not([tabindex=\"-1\"]), [contenteditable=true], " +
                    "[onclick], [onkeydown], [onsubmit]"
                val maxInteractive = 100
                val interactiveElements = document.select(interactiveSelector).take(maxInteractive).map { el ->
                    val obj = pulsarObjectMapper().createObjectNode()
                    obj.put("tag", el.tagName().lowercase())
                    val cls = el.className()
                    if (cls.isNotBlank()) obj.put("class", cls)
                    val id = el.id()
                    if (id.isNotBlank()) obj.put("id", id)
                    // Collect aria-* attributes
                    val ariaAttrs = el.attributes().filter { it.key.startsWith("aria-") }
                    if (ariaAttrs.isNotEmpty()) {
                        val ariaObj = pulsarObjectMapper().createObjectNode()
                        for (attr in ariaAttrs) {
                            ariaObj.put(attr.key, attr.value)
                        }
                        obj.set<ObjectNode>("aria", ariaObj)
                    }
                    // Bounding box (vi attribute, injected by feature_calculator.js)
                    val box = el.attr("vi")
                    if (box.isNotBlank()) obj.put("box", box)
                    obj
                }

                val json = pulsarObjectMapper().createObjectNode().apply {
                    put("url", page.url) // normalized url and can be served as the key to retrieve the page from database
                    put("href", page.href) // the href from an anchor, or the user-typed url
                    put("sizeBytes", page.contentLength.toString())
                    put("capturedAt", page.prevFetchTime.toString())
                    put("contentType", page.contentType) // should be html/text
                    put("title", title)
                    put("imageCount", imageCount)
                    put("linkCount", linkCount)
                    putArray("interactiveElements").addAll(interactiveElements)
                }
                json.toString()
            }
            ResponseEntity.ok(textResponse(metadata))
        } catch (e: Exception) {
            logger.error("dom_snapshot_capture failed | {}", e.message, e)
            ResponseEntity.ok(errorResponse("dom_snapshot_capture failed: ${e.message}"))
        }
    }

    private suspend fun handleDomSnapshotScrape(
        request: MCPToolCallRequest
    ): ResponseEntity<MCPToolCallResponse> {
        val sessionId = requireSessionId(request)
        val args = request.arguments ?: emptyMap()
        val field = args["field"]?.toString() ?: ""
        val selector = args["selector"]?.toString()?.ifEmpty { ":root" } ?: ":root"
        val attrName = args["attrName"]?.toString()

        // Validate field
        if (field !in setOf("text", "html", "attr")) {
            return ResponseEntity.ok(errorResponse("Unknown field '$field'. Use text, html, or attr."))
        }

        // Validate attr field requires an attribute name
        if (field == "attr" && attrName.isNullOrBlank()) {
            return ResponseEntity.ok(errorResponse("The 'attr' field requires an attribute name."))
        }

        // Reject element references
        if (isElementReference(selector)) {
            return ResponseEntity.ok(
                errorResponse(
                    "Element references ('$selector') are not supported in domsnapshot get. Use a CSS selector instead."
                )
            )
        }

        val managed = sessionManager.getSession(sessionId)
            ?: return ResponseEntity.ok(errorResponse("${MCPConstants.ERROR_SESSION_NOT_FOUND}$sessionId"))

        return try {
            val result = managed.withLock {
                val pulsarSession = managed.agenticSession
                // Use current URL to match the key used when pages are stored via domsnapshot capture.
                // driver.currentUrl() reflects the actual page after navigations/redirects, whereas
                // driver.userTypedUrl() stays at the originally-typed URL and misses search-results pages.
                val url = pulsarSession.normalize(driver.currentUrl())
                // Retrieve from database if exists, otherwise, capture a new dom snapshot
                val page = pulsarSession.getOrNull(url.urlString) ?: pulsarSession.capture(managed.driver)
                // Parse the HTML to a DOM, the document can be cached
                val document = pulsarSession.parse(page)

                when (field) {
                    "text" -> document.selectFirstOrNull(selector)?.text() ?: ""
                    "html" -> document.selectFirstOrNull(selector)?.html() ?: ""
                    "attr" -> document.selectFirstOrNull(selector)?.attr(attrName!!) ?: ""
                    else -> ""
                }
            }

            ResponseEntity.ok(textResponse(result))
        } catch (e: Exception) {
            logger.error("dom_snapshot_scrape failed | {}", e.message, e)
            ResponseEntity.ok(errorResponse("dom_snapshot_scrape failed: ${e.message}"))
        }
    }

    /**
     * Like [handleDomSnapshotScrape] but returns ALL matching elements (querySelectorAll
     * semantics) instead of only the first.  Supports [offset] and [limit] for pagination.
     */
    private suspend fun handleDomSnapshotScrapeAll(
        request: MCPToolCallRequest
    ): ResponseEntity<MCPToolCallResponse> {
        val sessionId = requireSessionId(request)
        val args = request.arguments ?: emptyMap()
        val field = args["field"]?.toString() ?: ""
        val selector = args["selector"]?.toString()?.ifEmpty { ":root" } ?: ":root"
        val attrName = args["attrName"]?.toString()
        val offset = (args["offset"] as? Number)?.toInt() ?: 0
        val limit = (args["limit"] as? Number)?.toInt() ?: -1

        // Validate field
        if (field !in setOf("text", "html", "attr")) {
            return ResponseEntity.ok(errorResponse("Unknown field '$field'. Use text, html, or attr."))
        }

        // Validate attr field requires an attribute name
        if (field == "attr" && attrName.isNullOrBlank()) {
            return ResponseEntity.ok(errorResponse("The 'attr' field requires an attribute name."))
        }

        // Reject element references
        if (isElementReference(selector)) {
            return ResponseEntity.ok(
                errorResponse(
                    "Element references ('$selector') are not supported in domsnapshot get. Use a CSS selector instead."
                )
            )
        }

        val managed = sessionManager.getSession(sessionId)
            ?: return ResponseEntity.ok(errorResponse("${MCPConstants.ERROR_SESSION_NOT_FOUND}$sessionId"))

        return try {
            val results = managed.withLock {
                val pulsarSession = managed.agenticSession
                val url = pulsarSession.normalize(managed.driver.currentUrl())
                val page = pulsarSession.getOrNull(url.urlString) ?: pulsarSession.capture(managed.driver)
                val document = pulsarSession.parse(page)

                val elements = document.select(selector)
                val paginated = if (offset > 0) elements.drop(offset) else elements
                val limited = if (limit > 0) paginated.take(limit) else paginated

                limited.map { element ->
                    when (field) {
                        "text" -> element.text()
                        "html" -> element.html()
                        "attr" -> element.attr(attrName!!) ?: ""
                        else -> ""
                    }
                }
            }

            val json = pulsarObjectMapper().writeValueAsString(results)
            val (paginatedJson, pagination) = paginateIfRequested(json, args)
            ResponseEntity.ok(textResponse(paginatedJson, pagination))
        } catch (e: Exception) {
            logger.error("dom_snapshot_scrape_all failed | {}", e.message, e)
            ResponseEntity.ok(errorResponse("dom_snapshot_scrape_all failed: ${e.message}"))
        }
    }

    private suspend fun handleDomSnapshotQuery(
        request: MCPToolCallRequest
    ): ResponseEntity<MCPToolCallResponse> {
        val scrapeService = this.scrapeService
            ?: return ResponseEntity.ok(errorResponse("ScrapeService is not available"))

        val args = request.arguments ?: emptyMap()
        val sql = args["sql"]?.toString() ?: return ResponseEntity.ok(errorResponse("Missing 'sql'"))

        // Resolve URL: use explicit URL if provided, otherwise fall back to the current session's page URL
        val url = args["url"]?.toString()?.takeIf { it.isNotBlank() }
            ?: run {
                val sessionId = requireSessionId(request)
                val managed = sessionManager.getSession(sessionId)
                    ?: return ResponseEntity.ok(errorResponse("${MCPConstants.ERROR_SESSION_NOT_FOUND}$sessionId"))
                val pulsarSession = managed.agenticSession
                pulsarSession.normalize(managed.driver.currentUrl()).urlString
            }

        // SQLTemplate.createSQL(url) replaces the @url placeholder with a properly
        // escaped URL value. @url must appear UNQUOTED in the SQL — the template
        // engine handles quoting internally. The correct form is:
        //   FROM load_and_select(@url, ':root')
        // NOT:
        //   FROM load_and_select('@url', ':root')
        val processedSql = SQLTemplate(sql).createSQL(url)

        return try {
            val response = scrapeService.executeQuery(ScrapeRequest(processedSql))
            val json = pulsarObjectMapper().writeValueAsString(response)
            ResponseEntity.ok(textResponse(json))
        } catch (e: Exception) {
            logger.error("dom_snapshot_query failed | {}", e.message, e)
            ResponseEntity.ok(errorResponse("dom_snapshot_query failed: ${e.message}"))
        }
    }

    private suspend fun handleDomSnapshotExport(
        request: MCPToolCallRequest
    ): ResponseEntity<MCPToolCallResponse> {
        val sessionId = requireSessionId(request)
        val managed = sessionManager.getSession(sessionId)
            ?: return ResponseEntity.ok(errorResponse("${MCPConstants.ERROR_SESSION_NOT_FOUND}$sessionId"))

        return try {
            val html = managed.withLock {
                val pulsarSession = managed.agenticSession
                val url = pulsarSession.normalize(managed.driver.currentUrl())
                // Use getOrNull + capture fallback to get browser-captured HTML
                val page = pulsarSession.getOrNull(url.urlString) ?: pulsarSession.capture(managed.driver)
                val document = pulsarSession.parse(page)
                // good, the exported HTML is pretty formatted, so grep works on it
                document.outerHtml
            }
            val args = request.arguments ?: emptyMap()
            val (paginatedHtml, pagination) = paginateIfRequested(html, args)
            ResponseEntity.ok(textResponse(paginatedHtml, pagination))
        } catch (e: Exception) {
            logger.error("dom_snapshot_export failed | {}", e.message, e)
            ResponseEntity.ok(errorResponse("dom_snapshot_export failed: ${e.message}"))
        }
    }

    private suspend fun handleDomSnapshotSummary(
        request: MCPToolCallRequest
    ): ResponseEntity<MCPToolCallResponse> {
        val sessionId = requireSessionId(request)
        val managed = sessionManager.getSession(sessionId)
            ?: return ResponseEntity.ok(errorResponse("${MCPConstants.ERROR_SESSION_NOT_FOUND}$sessionId"))

        return try {
            val summary = managed.withLock {
                val pulsarSession = managed.agenticSession
                val url = pulsarSession.normalize(managed.driver.currentUrl())
                // Use getOrNull + capture fallback to get browser-captured HTML
                // (which has vi attributes), rather than load() which may reload
                // from the web without vi attributes.
                val page = pulsarSession.getOrNull(url.urlString) ?: pulsarSession.capture(managed.driver)
                val document = pulsarSession.parse(page)
                val title = document.title
                val pageUrl = url.urlString

                PageSummaryIndexService.generate(document, pageUrl, title)
            }
            ResponseEntity.ok(textResponse(summary))
        } catch (e: Exception) {
            logger.error("dom_snapshot_summary failed | {}", e.message, e)
            ResponseEntity.ok(errorResponse("dom_snapshot_summary failed: ${e.message}"))
        }
    }

    /**
     * Inspect the DOM snapshot and suggest CSS selectors for recurring patterns.
     *
     * When [selector] matches multiple elements (e.g. `.product-card`), the
     * command compares descendant structures across matches to identify
     * recurring child selectors — useful for discovering selectors for titles,
     * prices, ratings, images, etc.
     */
    private suspend fun handleDomSnapshotInspect(
        request: MCPToolCallRequest
    ): ResponseEntity<MCPToolCallResponse> {
        val sessionId = requireSessionId(request)
        val managed = sessionManager.getSession(sessionId)
            ?: return ResponseEntity.ok(errorResponse("${MCPConstants.ERROR_SESSION_NOT_FOUND}$sessionId"))

        return try {
            val result = managed.withLock {
                val pulsarSession = managed.agenticSession
                val url = pulsarSession.normalize(managed.driver.currentUrl())
                // Use getOrNull + capture fallback to get browser-captured HTML
                // (which has vi attributes), rather than load() which may reload
                // from the web without vi attributes.
                val page = pulsarSession.getOrNull(url.urlString) ?: pulsarSession.capture(managed.driver)
                val document = pulsarSession.parse(page)

                val args = request.arguments ?: emptyMap()
                val selector = args["selector"]?.toString()?.ifEmpty { ":root" } ?: ":root"
                val maxMatches = (args["max"] as? Number)?.toInt() ?: 10
                val maxDepth = (args["depth"] as? Number)?.toInt() ?: 5

                val matches = document.select(selector).take(maxMatches)
                val matchCount = document.select(selector).size

                if (matches.isEmpty()) {
                    return@withLock pulsarObjectMapper().createObjectNode().apply {
                        put("matchCount", 0)
                        put("selector", selector)
                        putArray("suggestions")
                    }.toString()
                }

                // Build sample structures for the first 3 matches
                val samples = pulsarObjectMapper().createArrayNode()
                for (m in matches.take(3)) {
                    val sample = pulsarObjectMapper().createObjectNode()
                    sample.put("tag", m.tagName().lowercase())
                    val cls = m.className()
                    if (cls.isNotBlank()) sample.put("class", cls)
                    val id = m.id()
                    if (id.isNotBlank()) sample.put("id", id)
                    val ownText = m.ownText().trim()
                    if (ownText.isNotBlank()) sample.put("text", ownText.take(120))

                    // Direct children
                    val children = pulsarObjectMapper().createArrayNode()
                    for (child in m.children().take(20)) {
                        val cObj = pulsarObjectMapper().createObjectNode()
                        cObj.put("tag", child.tagName().lowercase())
                        val cCls = child.className()
                        if (cCls.isNotBlank()) cObj.put("class", cCls)
                        val cId = child.id()
                        if (cId.isNotBlank()) cObj.put("id", cId)
                        val cText = (child as? org.jsoup.nodes.Element)?.ownText()?.trim()
                        if (!cText.isNullOrBlank()) cObj.put("text", cText.take(80))
                        children.add(cObj)
                    }
                    sample.set<ArrayNode>("children", children)
                    samples.add(sample)
                }

                // Find recurring descendant selectors across matches
                data class SelectorCandidate(
                    val selector: String,
                    val tag: String,
                    val textPreview: String,
                )

                val candidateCounts = mutableMapOf<SelectorCandidate, Int>()

                for (match in matches) {
                    val seen = mutableSetOf<SelectorCandidate>()
                    for (desc in match.select("*")) {
                        val depth = desc.parents().indexOfFirst { it === match } + 1
                        if (depth < 0 || depth > maxDepth) continue
                        if (desc.tagName().lowercase() in setOf("html", "head", "body", "script", "style", "meta", "link", "noscript")) continue

                        val descTag = desc.tagName().lowercase()
                        val descClass = desc.className()
                        val descId = desc.id()
                        val descText = desc.ownText().trim().take(80)

                        // Build a short selector: tag + class(es)
                        val shortSelector = if (descClass.isNotBlank()) {
                            val classes = descClass.split("\\s+".toRegex()).take(2).joinToString(".") { it }
                            if (descId.isNotBlank()) "${descTag}.$classes#${descId}"
                            else "${descTag}.$classes"
                        } else if (descId.isNotBlank()) {
                            "${descTag}#${descId}"
                        } else {
                            descTag
                        }

                        val candidate = SelectorCandidate(shortSelector, descTag, descText)
                        if (seen.add(candidate)) {
                            candidateCounts[candidate] = (candidateCounts[candidate] ?: 0) + 1
                        }
                    }
                }

                // Filter to selectors appearing in >= 50% of matches (min 2 matches)
                val threshold = maxOf(2, (matches.size * 0.5).toInt())
                val recurring = candidateCounts.entries
                    .filter { it.value >= threshold }
                    .sortedByDescending { it.value }
                    .take(40)

                // Build suggestions array
                val suggestions = pulsarObjectMapper().createArrayNode()
                for ((candidate, count) in recurring) {
                    val sug = pulsarObjectMapper().createObjectNode()
                    sug.put("selector", candidate.selector)
                    sug.put("tag", candidate.tag)
                    if (candidate.textPreview.isNotBlank()) {
                        sug.put("textPreview", candidate.textPreview)
                    }
                    sug.put("matchCount", count)
                    sug.put("coverage", "%.0f%%".format(count * 100.0 / matches.size))
                    suggestions.add(sug)
                }

                pulsarObjectMapper().createObjectNode().apply {
                    put("matchCount", matchCount)
                    put("selector", selector)
                    put("analyzed", matches.size)
                    set<ArrayNode>("samples", samples)
                    set<ArrayNode>("suggestions", suggestions)
                }.toString()
            }
            ResponseEntity.ok(textResponse(result))
        } catch (e: Exception) {
            logger.error("dom_snapshot_inspect failed | {}", e.message, e)
            ResponseEntity.ok(errorResponse("dom_snapshot_inspect failed: ${e.message}"))
        }
    }

    // =========================================================================
    // Dispatch to per-session AgentToolManager
    // =========================================================================

    private suspend fun dispatchToAgentToolExecutor(request: MCPToolCallRequest): ResponseEntity<MCPToolCallResponse> {
        val normalizedRequest = normalizeFrontendToolCall(request.tool, request.arguments ?: emptyMap())
        val sessionId = requireSessionId(normalizedRequest.arguments)
        val managed = sessionManager.getSession(sessionId)
            ?: return ResponseEntity.ok(errorResponse("Session not found: $sessionId"))

        val toolName = normalizedRequest.tool
        val args = ArgumentNormalizerFactory.normalize(toolName, normalizedRequest.arguments)

        return try {
            val text = commandHandler.executeAgentToolText(toolName, args + (MCPConstants.KEY_SESSION_ID to sessionId))
            ResponseEntity.ok(textResponse(text))
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

    // =========================================================================
    // Frontend tool name normalization
    // =========================================================================

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

    // =========================================================================
    // Helpers
    // =========================================================================

    private data class NormalizedToolCall(
        val tool: String,
        val arguments: Map<String, Any?>
    )

    private fun requireSessionId(request: MCPToolCallRequest): String {
        return request.arguments?.get("sessionId")?.toString()
            ?: throw IllegalArgumentException("Missing required parameter: sessionId")
    }

    private fun requireSessionId(arguments: Map<String, Any?>): String {
        return arguments[MCPConstants.KEY_SESSION_ID]?.toString()
            ?: throw IllegalArgumentException("Missing required parameter: ${MCPConstants.KEY_SESSION_ID}")
    }

    private fun <T> requireHandler(handler: T?): T {
        return handler ?: throw IllegalStateException("Handler is not available. Check service dependencies.")
    }

    private fun textResponse(text: String): MCPToolCallResponse =
        MCPToolCallResponse(content = listOf(MCPContent(text = text)))

    private fun errorResponse(message: String): MCPToolCallResponse =
        MCPToolCallResponse(content = listOf(MCPContent(text = "ERROR: $message")), isError = true)

    private fun addRequestId(response: HttpServletResponse) {
        response.addHeader("X-Request-Id", UUID.randomUUID().toString())
    }

    private fun Any?.toBooleanValue(): Boolean? = when (this) {
        is Boolean -> this
        is String -> this.toBooleanStrictOrNull()
        else -> null
    }
}
