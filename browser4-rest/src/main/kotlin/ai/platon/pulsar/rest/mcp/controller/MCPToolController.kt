package ai.platon.pulsar.rest.mcp.controller

import ai.platon.browser4.boot.skill.SkillService
import ai.platon.pulsar.agent.tool.UserCommandExecutor
import ai.platon.pulsar.agentic.agents.BasicBrowserAgent
import ai.platon.pulsar.agentic.tools.AgentToolManager
import ai.platon.pulsar.agentic.tools.advanced.crawl.ScrapeRequest
import ai.platon.pulsar.common.brief
import ai.platon.pulsar.common.serialize.json.pulsarObjectMapper
import ai.platon.pulsar.common.sql.SQLTemplate
import ai.platon.pulsar.dom.FeaturedDocument
import ai.platon.pulsar.rest.api.service.CrawlService
import ai.platon.pulsar.rest.api.service.ScrapeService
import ai.platon.pulsar.rest.api.service.SwarmService
import ai.platon.pulsar.rest.mcp.controller.dto.MCPContent
import ai.platon.pulsar.rest.mcp.controller.dto.MCPToolCallRequest
import ai.platon.pulsar.rest.mcp.controller.dto.MCPToolCallResponse
import ai.platon.pulsar.rest.mcp.controller.handler.BatchExecutionResponse
import ai.platon.pulsar.rest.mcp.controller.handler.BatchExecutionResult
import ai.platon.pulsar.rest.mcp.controller.handler.BatchMousePosition
import ai.platon.pulsar.rest.mcp.controller.handler.CommandHandler
import ai.platon.pulsar.rest.mcp.controller.handler.CrawlMcpHandler
import ai.platon.pulsar.rest.mcp.controller.handler.DomSnapshotHandler
import ai.platon.pulsar.rest.mcp.controller.handler.SessionManagementHandler
import ai.platon.pulsar.rest.mcp.controller.handler.SkillMcpHandler
import ai.platon.pulsar.rest.mcp.controller.handler.SwarmMcpHandler
import ai.platon.pulsar.rest.mcp.controller.handler.ToolListHandler
import ai.platon.pulsar.rest.session.PulsarSessionManager
import ai.platon.pulsar.skeleton.workflow.parse.html.PageSummaryIndexService
import com.fasterxml.jackson.annotation.JsonProperty
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

data class PaginationMeta(
    @param:JsonProperty("page") val page: Int,
    @param:JsonProperty("totalPages") val totalPages: Int,
    @param:JsonProperty("totalLines") val totalLines: Int,
    @param:JsonProperty("pageSize") val pageSize: Int,
    @param:JsonProperty("truncated") val truncated: Boolean = true
)

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

        // Reject queries that use '.' as a literal URL in DOM_LOAD_AND_SELECT / load_and_select.
        // The '.' is not a valid URL — use the unquoted @url placeholder instead:
        //   FROM load_and_select(@url, ':root')           ← correct
        //   FROM load_and_select('.', ':root')            ← incorrect
        val dotUrlPattern = Regex(
            """(?:DOM_)?LOAD_AND_SELECT\s*\(\s*['"]\.['"]""",
            RegexOption.IGNORE_CASE
        )
        if (dotUrlPattern.containsMatchIn(sql)) {
            return ResponseEntity.ok(errorResponse(
                "Invalid URL '.' in DOM_LOAD_AND_SELECT. " +
                    "Use the unquoted @url placeholder to reference the current page URL. " +
                    "Example: FROM load_and_select(@url, ':root') — not FROM load_and_select('.', ':root'). " +
                    "See: https://docs.browser4.ai/x-sql for details."
            ))
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

                inspectDocument(document, selector, maxMatches, maxDepth)
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
            // Server-side pagination: when page/page-size are present, paginate
            // the result text to reduce network traffic for large snapshots.
            val requestArgs = request.arguments ?: emptyMap()
            val (paginatedText, pagination) = paginateIfRequested(text, requestArgs)
            ResponseEntity.ok(textResponse(paginatedText, pagination))
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

    private fun textResponse(text: String, pagination: PaginationMeta?): MCPToolCallResponse =
        MCPToolCallResponse(content = listOf(MCPContent(text = text)), pagination = pagination)

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

// =========================================================================
// Shared utilities
// =========================================================================

internal fun Any?.toBooleanValue(): Boolean? = when (this) {
    is Boolean -> this
    is String -> this.toBooleanStrictOrNull()
    else -> null
}

internal fun paginateIfRequested(
    text: String,
    args: Map<String, Any?>
): Pair<String, PaginationMeta?> {
    val showAll = args["all"].toBooleanValue() ?: false
    val pageSize = (args["page-size"] as? Number)?.toInt() ?: 0
    if (showAll || pageSize <= 0) return Pair(text, null)

    val page = (args["page"] as? Number)?.toInt() ?: 1
    val effectivePage = if (page < 1) 1 else page

    val lines = text.lines()
    val totalLines = lines.size
    if (totalLines <= pageSize) return Pair(text, null)

    val totalPages = (totalLines + pageSize - 1) / pageSize
    val currentPage = effectivePage.coerceAtMost(totalPages)
    val startLine = (currentPage - 1) * pageSize
    val endLine = (startLine + pageSize).coerceAtMost(totalLines)

    val pageContent = lines.subList(startLine, endLine).joinToString("\n")
    return Pair(
        pageContent,
        PaginationMeta(
            page = currentPage,
            totalPages = totalPages,
            totalLines = totalLines,
            pageSize = pageSize,
            truncated = currentPage < totalPages
        )
    )
}

// =========================================================================
// dom_snapshot_inspect — core algorithm (extracted for testability)
// =========================================================================

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
    val matches = document.select(selector).take(maxMatches)
    val matchCount = document.select(selector).size

    if (matches.isEmpty()) {
        return pulsarObjectMapper().createObjectNode().apply {
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
        val selectorType: String, // "id", "class", "attr", "bare", "power"
    )

    // Track count + per-match text samples for value previews
    class CandidateStats(
        var count: Int = 0,
        val textValues: MutableMap<Int, String> = mutableMapOf(), // matchIndex -> text
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
                }
            }
        }
    }

    // Filter to selectors appearing in >= 50% of matches (min 2 matches)
    val threshold = maxOf(2, (matches.size * 0.5).toInt())
    val filtered = candidateStats.entries.filter { it.value.count >= threshold }

    // ---- Quality scoring ----

    val semanticTags = setOf("h1", "h2", "h3", "h4", "h5", "h6", "a", "img", "button", "input", "select", "textarea", "label")

    fun distinctTextCount(stats: CandidateStats): Int =
        stats.textValues.values.filter { it.isNotBlank() }.distinct().size

    fun qualityScore(candidate: SelectorCandidate, stats: CandidateStats): Double {
        val n = stats.count.toDouble()
        // Specificity: class/id/attr selectors are more useful than bare tags
        val specificityPerMatch = when (candidate.selectorType) {
            "id"    -> 0.7
            "class" -> 0.4
            "power" -> 0.35  // visual features: almost as stable as classes
            "attr"  -> 0.2
            "bare"  -> if (candidate.tag in setOf("div", "span")) -0.3 else -0.1
            else    -> 0.0
        }
        // Distinctiveness: text that varies across matches signals unique data
        val distinctBoost = if (distinctTextCount(stats) >= 2) 0.3 else 0.0
        // Semantic tags (headings, links, form controls) carry more meaning
        val semanticBoost = if (candidate.tag in semanticTags) 0.2 else 0.0
        return n + (specificityPerMatch * n) + (distinctBoost * n) + (semanticBoost * n)
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

    return pulsarObjectMapper().createObjectNode().apply {
        put("matchCount", matchCount)
        put("selector", selector)
        put("analyzed", matches.size)
        set<ArrayNode>("samples", samples)
        set<ArrayNode>("suggestions", suggestions)
    }.toString()
}
