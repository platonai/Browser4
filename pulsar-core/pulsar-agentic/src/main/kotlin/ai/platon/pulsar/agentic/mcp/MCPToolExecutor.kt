package ai.platon.pulsar.agentic.mcp

import ai.platon.pulsar.agentic.*
import ai.platon.pulsar.agentic.tools.AgentToolManager
import ai.platon.pulsar.common.getLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * MCP Tool Executor that integrates with AgentToolManager.
 *
 * This class bridges MCP tool calls to the existing Browser4 tool execution system,
 * providing async execution support for MCP protocol.
 *
 * @author Vincent Zhang, ivincent.zhang@gmail.com, platon.ai
 */
class MCPToolExecutor(
    private val toolManager: AgentToolManager? = null,
) {
    private val logger = getLogger(this)
    private val registry = MCPToolRegistry.instance

    /**
     * Execute an MCP tool call asynchronously.
     *
     * @param toolCall The MCP tool call to execute
     * @return MCP tool result
     */
    suspend fun execute(toolCall: MCPToolCall): MCPToolResult {
        return withContext(Dispatchers.IO) {
            try {
                val tool = registry.getTool(toolCall.name)
                    ?: return@withContext MCPToolRenderer.createErrorResult("Tool not found: ${toolCall.name}")

                // Convert MCP tool call to internal ToolCall format
                val internalToolCall = ToolCall(
                    domain = toolCall.domain,
                    method = toolCall.method,
                    arguments = toolCall.arguments.mapValues { it.value?.toString() }.toMutableMap(),
                    description = tool.description
                )

                // If we have a tool manager, execute the tool through it
                if (toolManager != null) {
                    executeWithToolManager(internalToolCall, toolCall)
                } else {
                    // Without tool manager, return a validation response
                    logger.info("MCP tool call (no executor): {} with args: {}", toolCall.name, toolCall.arguments)
                    MCPToolRenderer.createTextResult(
                        "Tool '${toolCall.name}' validated. Arguments: ${toolCall.arguments}. " +
                        "Note: No AgentToolManager available for execution."
                    )
                }
            } catch (e: Exception) {
                logger.error("Failed to execute MCP tool: ${toolCall.name}", e)
                MCPToolRenderer.createErrorResult("Execution failed: ${e.message}")
            }
        }
    }

    /**
     * Execute tool call using AgentToolManager.
     */
    private suspend fun executeWithToolManager(
        internalToolCall: ToolCall,
        mcpToolCall: MCPToolCall
    ): MCPToolResult {
        logger.info("Executing MCP tool via AgentToolManager: {} with args: {}",
            mcpToolCall.name, mcpToolCall.arguments)

        // Extract selector-like parameter for locator (common patterns: selector, locator, element)
        val locatorParam = internalToolCall.arguments["selector"]
            ?: internalToolCall.arguments["locator"]
            ?: internalToolCall.arguments["element"]

        val actionDescription = ActionDescription(
            instruction = "MCP tool call: ${mcpToolCall.name}",
            observeElements = listOf(
                ObserveElement(
                    toolCall = internalToolCall,
                    locator = locatorParam,
                )
            )
        )

        return try {
            val result = toolManager!!.execute(actionDescription)

            if (result.success) {
                val evaluate = result.evaluate
                val value = evaluate?.value
                val description = evaluate?.description

                // Format the result based on value type
                when (value) {
                    is String -> MCPToolRenderer.createTextResult(value)
                    is Number -> MCPToolRenderer.createTextResult(value.toString())
                    is Boolean -> MCPToolRenderer.createTextResult(value.toString())
                    is Map<*, *> -> MCPToolRenderer.createTextResult(
                        value.entries.joinToString(", ") { "${it.key}: ${it.value}" }
                    )
                    is List<*> -> MCPToolRenderer.createTextResult(
                        value.joinToString(", ") { it.toString() }
                    )
                    null -> {
                        val message = description ?: result.message ?: "Tool executed successfully"
                        MCPToolRenderer.createTextResult(message)
                    }
                    else -> MCPToolRenderer.createTextResult(value.toString())
                }
            } else {
                val errorMessage = result.evaluate?.exception?.cause?.message
                    ?: result.message
                    ?: "Tool execution failed"
                MCPToolRenderer.createErrorResult(errorMessage)
            }
        } catch (e: Exception) {
            logger.error("Tool execution failed: ${mcpToolCall.name}", e)
            MCPToolRenderer.createErrorResult("Execution error: ${e.message}")
        }
    }

    /**
     * Validate a tool call without executing it.
     *
     * @param toolCall The MCP tool call to validate
     * @return Validation result
     */
    fun validate(toolCall: MCPToolCall): ValidationResult {
        val tool = registry.getTool(toolCall.name)
            ?: return ValidationResult(
                valid = false,
                error = "Tool not found: ${toolCall.name}"
            )

        // Check required parameters
        val missingParams = tool.inputSchema.required.filter { param ->
            !toolCall.arguments.containsKey(param)
        }

        if (missingParams.isNotEmpty()) {
            return ValidationResult(
                valid = false,
                error = "Missing required parameters: ${missingParams.joinToString()}"
            )
        }

        return ValidationResult(valid = true)
    }

    /**
     * Get help information for a tool.
     *
     * @param toolName The tool name (domain.method)
     * @return Help text or null if tool not found
     */
    fun getHelp(toolName: String): String? {
        val tool = registry.getTool(toolName) ?: return null

        return buildString {
            appendLine("Tool: ${tool.name}")
            appendLine("Description: ${tool.description}")
            appendLine()
            appendLine("Parameters:")
            tool.inputSchema.properties.forEach { (name, schema) ->
                val required = if (name in tool.inputSchema.required) " (required)" else ""
                appendLine("  - $name: ${schema.type}$required")
                schema.description?.let { appendLine("    $it") }
                schema.enumValues?.let { appendLine("    Allowed values: $it") }
                schema.default?.let { appendLine("    Default: $it") }
            }
        }
    }
}

/**
 * Validation result for tool call validation.
 */
data class ValidationResult(
    val valid: Boolean,
    val error: String? = null
)
