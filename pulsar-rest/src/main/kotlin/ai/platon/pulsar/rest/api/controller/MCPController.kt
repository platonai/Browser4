package ai.platon.pulsar.rest.api.controller

import ai.platon.pulsar.agentic.mcp.*
import ai.platon.pulsar.rest.api.service.MCPService
import kotlinx.coroutines.runBlocking
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.*

/**
 * REST API Controller for MCP (Model Context Protocol) operations.
 *
 * Provides endpoints for:
 * - Tool listing and execution
 * - Resource listing and reading
 * - Prompt listing and retrieval
 * - JSON-RPC protocol handling
 *
 * Note: This controller uses Spring MVC (not WebFlux), so `runBlocking` is used
 * to bridge coroutines with servlet-based threading, consistent with other controllers
 * in this project.
 *
 * @author Vincent Zhang, ivincent.zhang@gmail.com, platon.ai
 */
@RestController
@CrossOrigin
@RequestMapping(
    "api/mcp",
    produces = [MediaType.APPLICATION_JSON_VALUE]
)
class MCPController(
    private val mcpService: MCPService
) {
    // ========================================================================
    // Tool Endpoints
    // ========================================================================

    /**
     * List all available MCP tools.
     *
     * @return List of tool definitions
     */
    @GetMapping("/tools")
    fun listTools(): ToolsListResponse {
        return ToolsListResponse(tools = mcpService.listTools())
    }

    /**
     * List tools by domain.
     *
     * @param domain The domain to filter by (e.g., "driver", "browser", "fs")
     * @return List of tool definitions in the specified domain
     */
    @GetMapping("/tools/domain/{domain}")
    fun listToolsByDomain(@PathVariable domain: String): ToolsListResponse {
        return ToolsListResponse(tools = mcpService.listToolsByDomain(domain))
    }

    /**
     * Get a specific tool by name.
     *
     * @param name Tool name (e.g., "driver.click")
     * @return Tool definition or null if not found
     */
    @GetMapping("/tools/{name}")
    fun getTool(@PathVariable name: String): MCPToolDefinition? {
        return mcpService.getTool(name)
    }

    /**
     * Execute a tool call.
     *
     * @param request Tool call request containing tool name and arguments
     * @return Tool execution result
     */
    @PostMapping("/tools/call", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun executeTool(@RequestBody request: ToolCallRequest): MCPToolResult {
        return runBlocking {
            val toolCall = MCPToolCall(
                name = request.name,
                arguments = request.arguments ?: emptyMap()
            )
            mcpService.executeTool(toolCall)
        }
    }

    /**
     * Validate a tool call without executing it.
     *
     * @param request Tool call request to validate
     * @return Validation result
     */
    @PostMapping("/tools/validate", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun validateTool(@RequestBody request: ToolCallRequest): ValidationResponse {
        val toolCall = MCPToolCall(
            name = request.name,
            arguments = request.arguments ?: emptyMap()
        )
        val result = mcpService.validateToolCall(toolCall)
        return ValidationResponse(
            valid = result.valid,
            error = result.error
        )
    }

    /**
     * Get help information for a specific tool.
     *
     * @param name Tool name (e.g., "driver.click")
     * @return Help text or error message
     */
    @GetMapping("/tools/{name}/help")
    fun getToolHelp(@PathVariable name: String): Map<String, String?> {
        val help = mcpService.getToolHelp(name)
        return if (help != null) {
            mapOf("help" to help)
        } else {
            mapOf("error" to "Tool not found: $name")
        }
    }

    // ========================================================================
    // Resource Endpoints
    // ========================================================================

    /**
     * List all available resources.
     *
     * @return List of resources
     */
    @GetMapping("/resources")
    fun listResources(): ResourcesListResponse {
        return ResourcesListResponse(resources = mcpService.listResources())
    }

    /**
     * List all resource templates.
     *
     * @return List of resource templates
     */
    @GetMapping("/resources/templates")
    fun listResourceTemplates(): ResourceTemplatesListResponse {
        return ResourceTemplatesListResponse(resourceTemplates = mcpService.listResourceTemplates())
    }

    /**
     * Read resource contents.
     *
     * @param uri Resource URI
     * @return Resource contents or null if not found
     */
    @GetMapping("/resources/read")
    fun readResource(@RequestParam uri: String): ResourceReadResponse? {
        return runBlocking {
            val contents = mcpService.readResource(uri)
            contents?.let { ResourceReadResponse(contents = listOf(it)) }
        }
    }

    // ========================================================================
    // Prompt Endpoints
    // ========================================================================

    /**
     * List all available prompts.
     *
     * @return List of prompts
     */
    @GetMapping("/prompts")
    fun listPrompts(): PromptsListResponse {
        return PromptsListResponse(prompts = mcpService.listPrompts())
    }

    /**
     * Get a specific prompt by name.
     *
     * @param name Prompt name
     * @return Prompt or null if not found
     */
    @GetMapping("/prompts/{name}")
    fun getPrompt(@PathVariable name: String): MCPPrompt? {
        return mcpService.getPrompt(name)
    }

    // ========================================================================
    // Server Information Endpoints
    // ========================================================================

    /**
     * Get server capabilities.
     *
     * @return Server capabilities
     */
    @GetMapping("/capabilities")
    fun getCapabilities(): MCPServerCapabilities {
        return mcpService.getServerCapabilities()
    }

    /**
     * Get server information.
     *
     * @return Server info
     */
    @GetMapping("/info")
    fun getServerInfo(): MCPServerInfo {
        return mcpService.getServerInfo()
    }

    /**
     * Get registry statistics.
     *
     * @return Stats map with counts
     */
    @GetMapping("/stats")
    fun getStats(): Map<String, Int> {
        return mcpService.getStats()
    }

    // ========================================================================
    // JSON-RPC Protocol Endpoint
    // ========================================================================

    /**
     * Handle JSON-RPC requests.
     *
     * This endpoint accepts MCP JSON-RPC 2.0 requests and returns appropriate responses.
     *
     * @param request JSON-RPC request string
     * @return JSON-RPC response string
     */
    @PostMapping(
        "/rpc",
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE]
    )
    fun handleJsonRpc(@RequestBody request: String): String {
        return runBlocking {
            mcpService.handleJsonRpcRequest(request) ?: "{}"
        }
    }
}

// ============================================================================
// Request/Response DTOs
// ============================================================================

/**
 * Request for tool call execution.
 */
data class ToolCallRequest(
    /** Tool name (e.g., "driver.click") */
    val name: String,
    /** Tool arguments */
    val arguments: Map<String, Any?>? = null
)

/**
 * Response for tools/list.
 */
data class ToolsListResponse(
    val tools: List<MCPToolDefinition>
)

/**
 * Response for tool validation.
 */
data class ValidationResponse(
    val valid: Boolean,
    val error: String? = null
)

/**
 * Response for resources/list.
 */
data class ResourcesListResponse(
    val resources: List<MCPResource>
)

/**
 * Response for resources/templates/list.
 */
data class ResourceTemplatesListResponse(
    val resourceTemplates: List<MCPResourceTemplate>
)

/**
 * Response for resources/read.
 */
data class ResourceReadResponse(
    val contents: List<MCPResourceContents>
)

/**
 * Response for prompts/list.
 */
data class PromptsListResponse(
    val prompts: List<MCPPrompt>
)
