package ai.platon.pulsar.agentic.mcp.server

import ai.platon.pulsar.agentic.model.ToolCall
import ai.platon.pulsar.agentic.model.ToolSpec
import ai.platon.pulsar.agentic.tools.AgentToolExecutor
import ai.platon.pulsar.common.getLogger
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Browser4 MCP Server — exposes browser automation capabilities as MCP tools.
 *
 * This server allows external MCP clients (Claude Desktop, Cursor, Windsurf, etc.)
 * to drive a real browser through the Model Context Protocol.
 *
 * Tools are discovered dynamically from the [AgentToolExecutor]'s registered executors and their
 * [ai.platon.pulsar.agentic.model.ToolSpec] metadata, keeping registration in sync with the
 * internal agent tool-call infrastructure.
 *
 * @param toolManager The [AgentToolExecutor] to use for tool discovery and execution.
 * @param serverInfo MCP server identification (name and version).
 */
class Browser4MCPServer(
    private val toolManager: AgentToolExecutor,
    serverInfo: Implementation = Implementation(name = "browser4-mcp-server", version = "1.0.0"),
) {
    private val logger = getLogger(this)

    val server: Server = Server(
        serverInfo = serverInfo,
        options = ServerOptions(
            capabilities = ServerCapabilities(
                tools = ServerCapabilities.Tools(listChanged = false)
            )
        ),
        instructions = """
            Browser4 MCP Server gives you full control over a real Chrome browser.
            Use the tools in order: navigate first, then interact, then read content.
            Always call wait_for_selector after actions that trigger page loads or dynamic updates.
        """.trimIndent()
    ) {
        registerToolsFromManager(toolManager)
    }

    // -------------------------------------------------------------------------
    // Helpers — build ToolSchema property descriptors
    // -------------------------------------------------------------------------

    private fun stringProp(description: String): JsonObject =
        JsonObject(mapOf("type" to JsonPrimitive("string"), "description" to JsonPrimitive(description)))

    private fun numberProp(description: String): JsonObject =
        JsonObject(mapOf("type" to JsonPrimitive("number"), "description" to JsonPrimitive(description)))

    private fun intProp(description: String): JsonObject =
        JsonObject(mapOf("type" to JsonPrimitive("integer"), "description" to JsonPrimitive(description)))

    private fun boolProp(description: String): JsonObject =
        JsonObject(mapOf("type" to JsonPrimitive("boolean"), "description" to JsonPrimitive(description)))

    private fun arrayProp(description: String, itemType: String = "string"): JsonObject =
        JsonObject(
            mapOf(
                "type" to JsonPrimitive("array"),
                "description" to JsonPrimitive(description),
                "items" to JsonObject(mapOf("type" to JsonPrimitive(itemType)))
            )
        )

    // -------------------------------------------------------------------------
    // Argument parsing
    // -------------------------------------------------------------------------

    private fun arg(arguments: JsonObject?, key: String): String? =
        arguments?.get(key)?.toString()?.trim('"')

    // -------------------------------------------------------------------------
    // Result helpers
    // -------------------------------------------------------------------------

    private fun textResult(text: String): CallToolResult =
        CallToolResult(content = listOf(TextContent(text = text)))

    private fun errorResult(message: String): CallToolResult {
        logger.warn("MCP tool error: {}", message)
        return CallToolResult(content = listOf(TextContent(text = "ERROR: $message")), isError = true)
    }

    // -------------------------------------------------------------------------
    // AgentToolManager-based dynamic tool registration
    // -------------------------------------------------------------------------

    /**
     * Register all MCP tools by discovering executors and their [ToolSpec] metadata
     * from [toolManager]. Every tool handler routes its call through
     * [AgentToolExecutor.execute], matching the internal agent execution path.
     */
    private fun Server.registerToolsFromManager(toolManager: AgentToolExecutor) {
        for (executor in toolManager.concreteExecutors) {
            val specs = executor.getToolSpecs()
            if (specs.isEmpty()) continue
            for ((method, spec) in specs) {
                val mcpName = toMcpToolName(executor.domain, method)
                val description = spec.description?.trim()?.ifBlank { null }
                    ?: "${executor.domain}.$method"
                val inputSchema = buildSchemaFromSpec(spec)

                addTool(name = mcpName, description = description, inputSchema = inputSchema) { request ->
                    val args = buildArgsMap(request.params.arguments, spec)
                    val tc = ToolCall(
                        domain = executor.domain,
                        method = method,
                        arguments = args.toMutableMap(),
                    )
                    runCatching { toolManager.execute(tc) }
                        .fold(
                            onSuccess = { result ->
                                val evaluate = result.evaluate
                                val exc = evaluate.exception
                                if (exc != null) {
                                    errorResult("$mcpName failed: ${exc.cause?.message ?: exc.expression}")
                                } else {
                                    textResult(evaluate.value?.toString() ?: "")
                                }
                            },
                            onFailure = { errorResult("$mcpName failed: ${it.message}") }
                        )
                }
            }
        }

        logger.info("Registered {} MCP tools from AgentToolManager", toolManager.concreteExecutors.sumOf { it.getToolSpecs().size })
    }

    /**
     * Convert a domain + camelCase method name to a snake_case MCP tool name.
     *
     * Tab and system domains use just the snake_case method name (no prefix).
     * All other domains prepend `{domain}_` to disambiguate.
     *
     * Examples:
     * - tab.goBack     -> go_back
     * - browser.switchTab -> browser_switch_tab
     * - fs.writeString    -> fs_write_string
     * - agent.extract     -> agent_extract
     * - system.help       -> help
     */
    private fun toMcpToolName(domain: String, method: String): String {
        val snake = method.replace(Regex("([A-Z])")) { "_${it.groupValues[1].lowercase()}" }
        return when (domain) {
            "tab", "system" -> snake
            else -> "${domain}_$snake"
        }
    }

    /**
     * Build a [ToolSchema] from a [ToolSpec], mapping Kotlin type names to JSON Schema types.
     */
    private fun buildSchemaFromSpec(spec: ToolSpec): ToolSchema {
        val props = spec.arguments.associate { arg ->
            arg.name to typeToJsonProp(arg.type, arg.name)
        }
        val required = spec.arguments
            .filter { it.defaultValue == null }
            .map { it.name }
        return ToolSchema(
            properties = if (props.isEmpty()) null else JsonObject(props),
            required = required.ifEmpty { null },
        )
    }

    /**
     * Map a Kotlin type string to a JSON Schema property descriptor.
     */
    private fun typeToJsonProp(type: String, name: String): JsonObject {
        val normalised = type.trimEnd('?').trim()
        return when {
            normalised.startsWith("List<") || normalised.startsWith("Array<") -> arrayProp(name)
            normalised.lowercase() in setOf("string") -> stringProp(name)
            normalised.lowercase() in setOf("int", "integer", "long", "short") -> intProp(name)
            normalised.lowercase() in setOf("double", "float", "number") -> numberProp(name)
            normalised.lowercase() in setOf("boolean", "bool") -> boolProp(name)
            else -> stringProp(name)
        }
    }

    /**
     * Extract all argument values from the MCP request's JSON arguments object into
     * a plain [Map] suitable for [ToolCall.arguments].
     */
    private fun buildArgsMap(arguments: JsonObject?, spec: ToolSpec): Map<String, Any?> {
        if (arguments == null) return emptyMap()
        return spec.arguments.mapNotNull { argSpec ->
            val raw = arg(arguments, argSpec.name) ?: return@mapNotNull null
            val value: Any? = when {
                argSpec.type.trimEnd('?').lowercase() in setOf("int", "integer") -> raw.toIntOrNull() ?: raw
                argSpec.type.trimEnd('?').lowercase() == "long" -> raw.toLongOrNull() ?: raw
                argSpec.type.trimEnd('?').lowercase() in setOf("double", "float") -> raw.toDoubleOrNull() ?: raw
                argSpec.type.trimEnd('?').lowercase() in setOf("boolean", "bool") -> raw.toBooleanStrictOrNull() ?: raw
                else -> raw
            }
            argSpec.name to value
        }.toMap()
    }
}
