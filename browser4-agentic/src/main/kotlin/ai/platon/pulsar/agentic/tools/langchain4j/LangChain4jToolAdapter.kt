package ai.platon.pulsar.agentic.tools.langchain4j

import ai.platon.pulsar.agentic.model.ToolSpec
import ai.platon.pulsar.agentic.tools.builtin.AbstractToolExecutor
import ai.platon.pulsar.common.getLogger

/**
 * Adapts the Browser4 tool system for use with LangChain4j's tool-calling framework.
 *
 * LangChain4j uses `@Tool` annotations on methods and a `ToolSpecification` model
 * that differs from Browser4's internal [ToolSpec]. This adapter bridges the two,
 * converting Browser4 tool specifications into LangChain4j-compatible definitions
 * and routing LangChain4j tool execution requests back through Browser4's executors.
 *
 * ## Architecture
 *
 * ```
 * LLM (via LangChain4j)  →  ToolExecutionRequest  →  LangChain4jToolAdapter
 *                                                            │
 *                                                            ▼
 *                                               AgentToolManager.execute(tc)
 * ```
 *
 * ## Usage
 *
 * ```kotlin
 * val adapter = LangChain4jToolAdapter(agentToolManager)
 * val langChain4jTools = adapter.toLangChain4jTools()
 *
 * val assistant = AiServices.builder(CodingAssistant::class.java)
 *     .chatLanguageModel(model)
 *     .tools(langChain4jTools)
 *     .build()
 * ```
 */
class LangChain4jToolAdapter(
    private val toolManager: Any, // AgentToolManager — using Any to avoid circular deps
) {
    private val logger = getLogger(LangChain4jToolAdapter::class)

    /**
     * Convert all registered Browser4 tool specifications to LangChain4j-compatible
     * tool definitions. Each (domain, method) pair becomes a named tool.
     *
     * @return List of tool definitions suitable for LangChain4j AiServices
     */
    fun toToolDefinitions(): List<LangChain4jToolDefinition> {
        val specs = try {
            val method = toolManager::class.java.getMethod("getAllToolSpecs")
            @Suppress("UNCHECKED_CAST")
            method.invoke(toolManager) as? Map<String, Map<String, ToolSpec>> ?: emptyMap()
        } catch (e: Exception) {
            logger.warn("Failed to get tool specs: {}", e.message)
            return emptyList()
        }

        return specs.flatMap { (domain, methods) ->
            methods.map { (method, spec) ->
                LangChain4jToolDefinition(
                    name = "${domain}_${method}",
                    description = buildToolDescription(domain, method, spec),
                    parameters = buildToolParameters(spec),
                )
            }
        }
    }

    /**
     * Execute a tool call received from LangChain4j, routing it back through
     * the Browser4 tool manager.
     *
     * @param toolName The name of the tool to execute (format: "domain_method")
     * @param arguments The tool arguments as a JSON string
     * @return The tool execution result as a string
     */
    suspend fun execute(toolName: String, arguments: String): String {
        val parts = toolName.split("_", limit = 2)
        if (parts.size != 2) {
            return "Error: Invalid tool name format '$toolName' (expected 'domain_method')"
        }

        val domain = parts[0]
        val method = parts[1]

        // Parse arguments from JSON string
        val args = try {
            parseJsonToMap(arguments)
        } catch (e: Exception) {
            logger.warn("Failed to parse tool arguments: {}", e.message)
            emptyMap<String, Any?>()
        }

        return try {
            val executeMethod = toolManager::class.java.getMethod(
                "execute", ai.platon.pulsar.agentic.model.ToolCall::class.java
            )
            val tc = ai.platon.pulsar.agentic.model.ToolCall(
                domain = domain,
                method = method,
                arguments = args.toMutableMap(),
            )
            val result = executeMethod.invoke(toolManager, tc)
            result.toString()
        } catch (e: Exception) {
            logger.warn("Tool execution failed: $toolName — ${e.message}")
            "Error executing tool '$toolName': ${e.message}"
        }
    }

    private fun buildToolDescription(domain: String, method: String, spec: ToolSpec): String {
        val base = spec.description ?: "$domain.$method"
        val args = spec.arguments.joinToString(", ") { "${it.name}: ${it.type}" +
            if (it.defaultValue != null) " = ${it.defaultValue}" else "" }
        return "$base — Arguments: ($args)"
    }

    private fun buildToolParameters(spec: ToolSpec): Map<String, Any> {
        val properties = mutableMapOf<String, Map<String, Any>>()
        val required = mutableListOf<String>()

        for (arg in spec.arguments) {
            val isRequired = arg.defaultValue == null
            if (isRequired) required.add(arg.name)

            val paramType = when (arg.type.lowercase()) {
                "string" -> "string"
                "int", "integer", "long" -> "integer"
                "double", "float", "number" -> "number"
                "boolean", "bool" -> "boolean"
                "list<string>", "array" -> "array"
                else -> "string"
            }

            val paramDef = mutableMapOf<String, Any>("type" to paramType)
            if (!isRequired) {
                paramDef["description"] = "Optional. Default: ${arg.defaultValue}"
            }
            properties[arg.name] = paramDef
        }

        return mapOf(
            "type" to "object",
            "properties" to properties,
            "required" to required,
        )
    }

    private fun parseJsonToMap(json: String): Map<String, Any?> {
        if (json.isBlank()) return emptyMap()

        // Simple JSON parser — handles the common case of flat key-value objects
        // that LangChain4j typically produces for tool arguments
        val trimmed = json.trim()
        if (!trimmed.startsWith("{")) return emptyMap()

        val result = mutableMapOf<String, Any?>()
        val content = trimmed.removeSurrounding("{", "}").trim()
        if (content.isEmpty()) return result

        // Handle key-value pairs
        var pos = 0
        while (pos < content.length) {
            // Skip whitespace
            while (pos < content.length && content[pos].isWhitespace()) pos++
            if (pos >= content.length) break

            // Extract key
            val keyStart = content.indexOf('"', pos)
            if (keyStart < 0) break
            val keyEnd = content.indexOf('"', keyStart + 1)
            if (keyEnd < 0) break
            val key = content.substring(keyStart + 1, keyEnd)
            pos = keyEnd + 1

            // Skip colon
            while (pos < content.length && (content[pos].isWhitespace() || content[pos] == ':')) pos++
            if (pos >= content.length) break

            // Extract value
            when {
                content[pos] == '"' -> {
                    val valEnd = content.indexOf('"', pos + 1)
                    if (valEnd < 0) break
                    val value = content.substring(pos + 1, valEnd)
                    result[key] = value
                    pos = valEnd + 1
                }
                content[pos] == 't' && content.startsWith("true", pos) -> {
                    result[key] = true; pos += 4
                }
                content[pos] == 'f' && content.startsWith("false", pos) -> {
                    result[key] = false; pos += 5
                }
                content[pos] == 'n' && content.startsWith("null", pos) -> {
                    result[key] = null; pos += 4
                }
                content[pos].isDigit() || content[pos] == '-' -> {
                    val numStart = pos
                    while (pos < content.length && (content[pos].isDigit() || content[pos] == '.' || content[pos] == '-')) pos++
                    val numStr = content.substring(numStart, pos)
                    result[key] = numStr.toLongOrNull() ?: numStr.toDoubleOrNull() ?: numStr
                }
                else -> pos++
            }

            // Skip comma
            while (pos < content.length && (content[pos].isWhitespace() || content[pos] == ',')) pos++
        }

        return result
    }
}

/**
 * Simplified tool definition compatible with LangChain4j's tool specification model.
 */
data class LangChain4jToolDefinition(
    val name: String,
    val description: String,
    val parameters: Map<String, Any>,
)
