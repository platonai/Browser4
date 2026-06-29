package ai.platon.pulsar.rest.mcp.controller

import ai.platon.browser4.boot.skill.SkillService
import ai.platon.pulsar.agent.tool.UserCommandExecutor
import ai.platon.pulsar.common.brief
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
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
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
                    return@withLock jacksonObjectMapper().createObjectNode().apply {
                        put("matchCount", 0)
                        put("selector", selector)
                        putArray("suggestions")
                    }.toString()
                }

                // Build sample structures for the first 3 matches
                val samples = jacksonObjectMapper().createArrayNode()
                for (m in matches.take(3)) {
                    val sample = jacksonObjectMapper().createObjectNode()
                    sample.put("tag", m.tagName().lowercase())
                    val cls = m.className()
                    if (cls.isNotBlank()) sample.put("class", cls)
                    val id = m.id()
                    if (id.isNotBlank()) sample.put("id", id)
                    val ownText = m.ownText().trim()
                    if (ownText.isNotBlank()) sample.put("text", ownText.take(120))

                    // Direct children
                    val children = jacksonObjectMapper().createArrayNode()
                    for (child in m.children().take(20)) {
                        val cObj = jacksonObjectMapper().createObjectNode()
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
                val suggestions = jacksonObjectMapper().createArrayNode()
                for ((candidate, count) in recurring) {
                    val sug = jacksonObjectMapper().createObjectNode()
                    sug.put("selector", candidate.selector)
                    sug.put("tag", candidate.tag)
                    if (candidate.textPreview.isNotBlank()) {
                        sug.put("textPreview", candidate.textPreview)
                    }
                    sug.put("matchCount", count)
                    sug.put("coverage", "%.0f%%".format(count * 100.0 / matches.size))
                    suggestions.add(sug)
                }

                jacksonObjectMapper().createObjectNode().apply {
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
