package ai.platon.pulsar.rest.api.controller

import ai.platon.pulsar.rest.api.service.MCPService
import com.fasterxml.jackson.databind.JsonNode
import kotlinx.coroutines.runBlocking
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.*

/**
 * MCP (Model Context Protocol) Controller.
 *
 * This controller exposes Browser4 as an MCP server, allowing MCP clients
 * (such as Claude Desktop, Cursor, or custom AI assistants) to interact
 * with Browser4's capabilities through the standardized MCP protocol.
 *
 * The controller provides three main endpoints:
 * - GET /api/mcp/info - Returns server information
 * - POST /api/mcp/list_tools - Lists all available tools
 * - POST /api/mcp/call_tool - Executes a specific tool
 *
 * All endpoints follow the MCP protocol specification for request/response formats.
 *
 * @property mcpService The MCP service that handles tool execution.
 */
@RestController
@CrossOrigin
@RequestMapping("api/mcp")
class MCPController(
    private val mcpService: MCPService
) {

    /**
     * Get server information.
     *
     * This endpoint returns metadata about the MCP server including its name,
     * version, and capabilities.
     *
     * @return Server information map.
     */
    @GetMapping("/info", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun getServerInfo(): Map<String, Any> {
        return mcpService.getServerInfo()
    }

    /**
     * List all available tools.
     *
     * This endpoint returns the list of all tools exposed by the Browser4 MCP server.
     * Each tool includes its name, description, and input schema definition.
     *
     * MCP clients call this endpoint to discover what capabilities are available.
     *
     * @return Map containing the list of tools.
     */
    @PostMapping("/list_tools", produces = [MediaType.APPLICATION_JSON_VALUE])
    @ResponseBody
    fun listTools(): Map<String, Any> {
        return mcpService.listTools()
    }

    /**
     * Execute a tool.
     *
     * This endpoint executes a specific tool with the provided arguments.
     * The request must include the tool name and arguments in MCP format.
     *
     * Request format:
     * ```json
     * {
     *   "name": "tool_name",
     *   "arguments": {
     *     "param1": "value1",
     *     "param2": "value2"
     *   }
     * }
     * ```
     *
     * @param request The tool call request containing name and arguments.
     * @return The result of the tool execution in MCP format.
     */
    @PostMapping(
        "/call_tool",
        produces = [MediaType.APPLICATION_JSON_VALUE],
        consumes = [MediaType.APPLICATION_JSON_VALUE]
    )
    @ResponseBody
    fun callTool(@RequestBody request: JsonNode): Map<String, Any> = runBlocking {
        val toolName = request.get("name")?.asText()
            ?: throw IllegalArgumentException("Tool name is required")
        val arguments = request.get("arguments") ?: request

        mcpService.callTool(toolName, arguments)
    }
}
