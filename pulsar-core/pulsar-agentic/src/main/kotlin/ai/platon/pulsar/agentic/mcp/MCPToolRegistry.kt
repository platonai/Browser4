package ai.platon.pulsar.agentic.mcp

import ai.platon.pulsar.agentic.ToolCallSpec
import ai.platon.pulsar.agentic.tools.CustomToolRegistry
import ai.platon.pulsar.common.getLogger
import java.util.concurrent.ConcurrentHashMap

/**
 * Registry for MCP tools and resources.
 *
 * This registry manages MCP-compatible tool definitions, resources, and prompts.
 * It integrates with the existing CustomToolRegistry to provide MCP views of
 * registered tools.
 *
 * @author Vincent Zhang, ivincent.zhang@gmail.com, platon.ai
 */
class MCPToolRegistry private constructor() {
    private val logger = getLogger(this)

    /** MCP tool definitions indexed by tool name */
    private val tools = ConcurrentHashMap<String, MCPToolDefinition>()

    /** MCP resources indexed by URI */
    private val resources = ConcurrentHashMap<String, MCPResource>()

    /** MCP resource templates */
    private val resourceTemplates = ConcurrentHashMap<String, MCPResourceTemplate>()

    /** MCP prompts indexed by name */
    private val prompts = ConcurrentHashMap<String, MCPPrompt>()

    /** Resource content providers */
    private val resourceProviders = ConcurrentHashMap<String, suspend (String) -> MCPResourceContents?>()

    companion object {
        /** Singleton instance */
        val instance: MCPToolRegistry by lazy { MCPToolRegistry() }
    }

    init {
        // Register built-in tools on initialization
        registerBuiltInTools()
    }

    /**
     * Register built-in tools from ToolSpecification.
     */
    private fun registerBuiltInTools() {
        try {
            val builtInTools = MCPToolConverter.getAllBuiltInMCPTools()
            builtInTools.forEach { tool ->
                tools[tool.name] = tool
            }
            logger.info("✓ Registered {} built-in MCP tools", builtInTools.size)
        } catch (e: Exception) {
            logger.warn("Failed to register built-in MCP tools: {}", e.message)
        }
    }

    /**
     * Synchronize with CustomToolRegistry to include custom tools.
     */
    fun syncWithCustomRegistry() {
        val customRegistry = CustomToolRegistry.instance
        val customSpecs = customRegistry.getAllToolCallSpecifications()

        customSpecs.forEach { spec ->
            val mcpTool = MCPToolConverter.toMCPToolDefinition(spec)
            tools[mcpTool.name] = mcpTool
        }

        logger.info("✓ Synced {} custom tools to MCP registry", customSpecs.size)
    }

    // ========================================================================
    // Tool Management
    // ========================================================================

    /**
     * Register an MCP tool definition.
     *
     * @param tool The tool definition to register
     * @throws IllegalArgumentException if a tool with the same name exists
     */
    fun registerTool(tool: MCPToolDefinition) {
        require(tool.name.isNotBlank()) { "Tool name must not be blank" }

        if (tools.containsKey(tool.name)) {
            throw IllegalArgumentException(
                "Tool '${tool.name}' is already registered. " +
                "Use unregisterTool() first if you want to replace it."
            )
        }

        tools[tool.name] = tool
        logger.info("✓ Registered MCP tool: {}", tool.name)
    }

    /**
     * Register a tool from ToolCallSpec.
     */
    fun registerTool(spec: ToolCallSpec) {
        val mcpTool = MCPToolConverter.toMCPToolDefinition(spec)
        registerTool(mcpTool)
    }

    /**
     * Unregister an MCP tool.
     *
     * @param name The tool name to unregister
     * @return true if removed, false if not found
     */
    fun unregisterTool(name: String): Boolean {
        val removed = tools.remove(name)
        if (removed != null) {
            logger.info("✓ Unregistered MCP tool: {}", name)
            return true
        }
        return false
    }

    /**
     * Get a tool by name.
     */
    fun getTool(name: String): MCPToolDefinition? = tools[name]

    /**
     * Get all registered tools.
     */
    fun getAllTools(): List<MCPToolDefinition> = tools.values.toList()

    /**
     * Get tools by domain.
     */
    fun getToolsByDomain(domain: String): List<MCPToolDefinition> {
        return tools.values.filter { it.name.startsWith("$domain.") }
    }

    /**
     * Check if a tool exists.
     */
    fun hasTool(name: String): Boolean = tools.containsKey(name)

    // ========================================================================
    // Resource Management
    // ========================================================================

    /**
     * Register an MCP resource.
     *
     * @param resource The resource to register
     * @param provider Optional content provider function
     */
    fun registerResource(
        resource: MCPResource,
        provider: (suspend (String) -> MCPResourceContents?)? = null
    ) {
        require(resource.uri.isNotBlank()) { "Resource URI must not be blank" }

        resources[resource.uri] = resource
        if (provider != null) {
            resourceProviders[resource.uri] = provider
        }
        logger.info("✓ Registered MCP resource: {}", resource.uri)
    }

    /**
     * Register a resource template.
     */
    fun registerResourceTemplate(
        template: MCPResourceTemplate,
        provider: suspend (String) -> MCPResourceContents?
    ) {
        require(template.uriTemplate.isNotBlank()) { "Resource template URI must not be blank" }

        resourceTemplates[template.uriTemplate] = template
        resourceProviders[template.uriTemplate] = provider
        logger.info("✓ Registered MCP resource template: {}", template.uriTemplate)
    }

    /**
     * Unregister a resource.
     */
    fun unregisterResource(uri: String): Boolean {
        val removed = resources.remove(uri)
        resourceProviders.remove(uri)
        if (removed != null) {
            logger.info("✓ Unregistered MCP resource: {}", uri)
            return true
        }
        return false
    }

    /**
     * Get a resource by URI.
     */
    fun getResource(uri: String): MCPResource? = resources[uri]

    /**
     * Get all registered resources.
     */
    fun getAllResources(): List<MCPResource> = resources.values.toList()

    /**
     * Get all resource templates.
     */
    fun getAllResourceTemplates(): List<MCPResourceTemplate> = resourceTemplates.values.toList()

    /**
     * Read resource contents.
     *
     * @param uri The resource URI
     * @return Resource contents or null if not found
     */
    suspend fun readResource(uri: String): MCPResourceContents? {
        // Try exact match first
        val provider = resourceProviders[uri]
        if (provider != null) {
            return provider(uri)
        }

        // Try template matching
        for ((template, templateProvider) in resourceProviders) {
            if (matchesTemplate(uri, template)) {
                return templateProvider(uri)
            }
        }

        return null
    }

    private fun matchesTemplate(uri: String, template: String): Boolean {
        // Simple template matching with proper regex escaping
        // TODO: Consider using a more robust URI template library (RFC 6570) for complex use cases
        val escapedTemplate = Regex.escape(template)
            .replace("\\{", "(?<")
            .replace("\\}", ">[^/]+)")
        return try {
            Regex(escapedTemplate).matches(uri)
        } catch (e: Exception) {
            // If regex construction fails, fall back to simple contains check
            false
        }
    }

    // ========================================================================
    // Prompt Management
    // ========================================================================

    /**
     * Register an MCP prompt.
     */
    fun registerPrompt(prompt: MCPPrompt) {
        require(prompt.name.isNotBlank()) { "Prompt name must not be blank" }
        prompts[prompt.name] = prompt
        logger.info("✓ Registered MCP prompt: {}", prompt.name)
    }

    /**
     * Unregister a prompt.
     */
    fun unregisterPrompt(name: String): Boolean {
        val removed = prompts.remove(name)
        if (removed != null) {
            logger.info("✓ Unregistered MCP prompt: {}", name)
            return true
        }
        return false
    }

    /**
     * Get a prompt by name.
     */
    fun getPrompt(name: String): MCPPrompt? = prompts[name]

    /**
     * Get all registered prompts.
     */
    fun getAllPrompts(): List<MCPPrompt> = prompts.values.toList()

    // ========================================================================
    // Utility Methods
    // ========================================================================

    /**
     * Clear all registered tools, resources, and prompts.
     */
    fun clear() {
        tools.clear()
        resources.clear()
        resourceTemplates.clear()
        resourceProviders.clear()
        prompts.clear()
        logger.info("✓ Cleared all MCP registrations")
    }

    /**
     * Reset to initial state (only built-in tools).
     */
    fun reset() {
        clear()
        registerBuiltInTools()
        logger.info("✓ Reset MCP registry to initial state")
    }

    /**
     * Get statistics about the registry.
     */
    fun getStats(): Map<String, Int> {
        return mapOf(
            "tools" to tools.size,
            "resources" to resources.size,
            "resourceTemplates" to resourceTemplates.size,
            "prompts" to prompts.size
        )
    }
}
