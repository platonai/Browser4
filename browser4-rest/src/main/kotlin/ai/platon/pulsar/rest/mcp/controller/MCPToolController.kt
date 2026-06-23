package ai.platon.pulsar.rest.mcp.controller

import ai.platon.pulsar.agent.tool.UserCommandExecutor
import ai.platon.pulsar.rest.session.PulsarSessionManager
import ai.platon.pulsar.common.brief
import ai.platon.pulsar.rest.api.service.ScrapeService
import ai.platon.pulsar.rest.mcp.controller.dto.MCPToolCallRequest
import ai.platon.pulsar.rest.mcp.controller.dto.MCPToolCallResponse
import ai.platon.pulsar.rest.mcp.controller.dto.MCPContent
import ai.platon.pulsar.rest.mcp.controller.handler.*
import com.fasterxml.jackson.databind.ObjectMapper
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
) {
    private val logger = LoggerFactory.getLogger(MCPToolController::class.java)

    // Handler delegates
    private val sessionHandler = SessionManagementHandler(sessionManager)
    private val commandHandler = CommandHandler(sessionManager, commandExecutor, objectMapper)
    private val domSnapshotHandler = DomSnapshotHandler(sessionManager, scrapeService, objectMapper)
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
                // Command tools
                "command_run" -> commandHandler.handleCommandRun(request)
                "command_batch" -> commandHandler.handleCommandBatch(request)
                "command_status" -> commandHandler.handleCommandStatus(request)
                "command_result" -> commandHandler.handleCommandResult(request)
                // DOM snapshot tools
                "dom_snapshot_capture" -> domSnapshotHandler.handleDomSnapshotCapture(request)
                "dom_snapshot_scrape" -> domSnapshotHandler.handleDomSnapshotScrape(request)
                "dom_snapshot_query" -> domSnapshotHandler.handleDomSnapshotQuery(request)
                "dom_snapshot_export" -> domSnapshotHandler.handleDomSnapshotExport(request)
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

    private fun requireSessionId(arguments: Map<String, Any?>): String {
        return arguments[MCPConstants.KEY_SESSION_ID]?.toString()
            ?: throw IllegalArgumentException("Missing required parameter: ${MCPConstants.KEY_SESSION_ID}")
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
