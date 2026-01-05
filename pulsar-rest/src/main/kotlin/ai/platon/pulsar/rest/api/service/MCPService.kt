package ai.platon.pulsar.rest.api.service

import ai.platon.pulsar.agentic.ToolCall
import ai.platon.pulsar.agentic.mcp.*
import ai.platon.pulsar.common.getLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service

/**
 * Service for MCP (Model Context Protocol) operations.
 *
 * Provides REST API support for:
 * - Tool listing and execution
 * - Resource listing and reading
 * - Prompt listing and retrieval
 *
 * @author Vincent Zhang, ivincent.zhang@gmail.com, platon.ai
 */
@Service
class MCPService {
    private val logger = getLogger(MCPService::class)

    private val registry = MCPToolRegistry.instance
    private val toolExecutor = MCPToolExecutor()
    private val protocolHandler = MCPProtocolHandler(registry)

    // ========================================================================
    // Tool Operations
    // ========================================================================

    /**
     * Get all available MCP tools.
     */
    fun listTools(): List<MCPToolDefinition> {
        return registry.getAllTools()
    }

    /**
     * Get tools by domain.
     */
    fun listToolsByDomain(domain: String): List<MCPToolDefinition> {
        return registry.getToolsByDomain(domain)
    }

    /**
     * Get a specific tool by name.
     */
    fun getTool(name: String): MCPToolDefinition? {
        return registry.getTool(name)
    }

    /**
     * Execute a tool call.
     *
     * @param toolCall The MCP tool call request
     * @return Tool execution result
     */
    suspend fun executeTool(toolCall: MCPToolCall): MCPToolResult {
        return toolExecutor.execute(toolCall)
    }

    /**
     * Validate a tool call without executing it.
     *
     * @param toolCall The MCP tool call to validate
     * @return Validation result
     */
    fun validateToolCall(toolCall: MCPToolCall): ValidationResult {
        return toolExecutor.validate(toolCall)
    }

    /**
     * Get help information for a tool.
     *
     * @param toolName The tool name (domain.method)
     * @return Help text or null if tool not found
     */
    fun getToolHelp(toolName: String): String? {
        return toolExecutor.getHelp(toolName)
    }

    // ========================================================================
    // Resource Operations
    // ========================================================================

    /**
     * Get all available resources.
     */
    fun listResources(): List<MCPResource> {
        return registry.getAllResources()
    }

    /**
     * Get all resource templates.
     */
    fun listResourceTemplates(): List<MCPResourceTemplate> {
        return registry.getAllResourceTemplates()
    }

    /**
     * Read resource contents.
     */
    suspend fun readResource(uri: String): MCPResourceContents? {
        return withContext(Dispatchers.IO) {
            registry.readResource(uri)
        }
    }

    /**
     * Register a new resource.
     */
    fun registerResource(resource: MCPResource) {
        registry.registerResource(resource)
    }

    // ========================================================================
    // Prompt Operations
    // ========================================================================

    /**
     * Get all available prompts.
     */
    fun listPrompts(): List<MCPPrompt> {
        return registry.getAllPrompts()
    }

    /**
     * Get a specific prompt by name.
     */
    fun getPrompt(name: String): MCPPrompt? {
        return registry.getPrompt(name)
    }

    /**
     * Register a new prompt.
     */
    fun registerPrompt(prompt: MCPPrompt) {
        registry.registerPrompt(prompt)
    }

    // ========================================================================
    // JSON-RPC Protocol Handling
    // ========================================================================

    /**
     * Handle a JSON-RPC request.
     *
     * @param requestJson The JSON-RPC request string
     * @return JSON-RPC response string
     */
    suspend fun handleJsonRpcRequest(requestJson: String): String? {
        return protocolHandler.handleRequest(requestJson)
    }

    /**
     * Get server capabilities.
     */
    fun getServerCapabilities(): MCPServerCapabilities {
        return MCPServerCapabilities(
            tools = MCPToolCapabilities(listChanged = true),
            resources = MCPResourceCapabilities(listChanged = true, subscribe = false),
            prompts = MCPPromptCapabilities(listChanged = true),
            logging = null
        )
    }

    /**
     * Get server information.
     */
    fun getServerInfo(): MCPServerInfo {
        return MCPServerInfo(
            name = "Browser4",
            version = "4.2.0"
        )
    }

    /**
     * Get registry statistics.
     */
    fun getStats(): Map<String, Int> {
        return registry.getStats()
    }
}
