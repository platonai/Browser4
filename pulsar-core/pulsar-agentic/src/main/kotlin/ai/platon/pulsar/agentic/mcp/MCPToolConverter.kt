package ai.platon.pulsar.agentic.mcp

import ai.platon.pulsar.agentic.ToolCallSpec
import ai.platon.pulsar.agentic.tools.ToolSpecification

/**
 * Converter for transforming Browser4 tool specifications to MCP format and vice versa.
 *
 * This converter enables bidirectional transformation between:
 * - Browser4-style tool specifications (ToolCallSpec)
 * - MCP JSON Schema tool definitions (MCPToolDefinition)
 *
 * @author Vincent Zhang, ivincent.zhang@gmail.com, platon.ai
 */
object MCPToolConverter {

    /**
     * Convert a ToolCallSpec to MCP tool definition.
     *
     * @param spec The Browser4 tool specification
     * @return MCP-compatible tool definition
     */
    fun toMCPToolDefinition(spec: ToolCallSpec): MCPToolDefinition {
        val properties = spec.arguments.associate { arg ->
            arg.name to MCPPropertySchema(
                type = kotlinTypeToJsonSchemaType(arg.type),
                description = null,  // Individual arg descriptions not available in ToolCallSpec
                default = parseDefaultValue(arg.defaultValue, arg.type),
            )
        }

        val required = spec.arguments
            .filter { it.defaultValue == null }
            .map { it.name }

        return MCPToolDefinition(
            name = "${spec.domain}.${spec.method}",
            description = spec.description ?: "Execute ${spec.method} in ${spec.domain} domain",
            inputSchema = MCPInputSchema(
                type = "object",
                properties = properties,
                required = required,
            )
        )
    }

    /**
     * Convert multiple ToolCallSpecs to MCP tool definitions.
     */
    fun toMCPToolDefinitions(specs: List<ToolCallSpec>): List<MCPToolDefinition> {
        return specs.map { toMCPToolDefinition(it) }
    }

    /**
     * Convert an MCP tool definition back to ToolCallSpec.
     *
     * @param definition The MCP tool definition
     * @return Browser4 tool specification
     */
    fun toToolCallSpec(definition: MCPToolDefinition): ToolCallSpec {
        val (domain, method) = parseToolName(definition.name)

        val arguments = definition.inputSchema.properties.map { (name, schema) ->
            ToolCallSpec.Arg(
                name = name,
                type = jsonSchemaTypeToKotlinType(schema.type),
                defaultValue = schema.default?.toString(),
            )
        }

        return ToolCallSpec(
            domain = domain,
            method = method,
            arguments = arguments,
            returnType = "Unit",  // MCP doesn't specify return types
            description = definition.description,
        )
    }

    /**
     * Parse all built-in tool specifications and convert to MCP format.
     */
    fun getAllBuiltInMCPTools(): List<MCPToolDefinition> {
        return parseBuiltInToolSpecs().map { toMCPToolDefinition(it) }
    }

    /**
     * Parse the built-in tool specification string into ToolCallSpec objects.
     */
    fun parseBuiltInToolSpecs(): List<ToolCallSpec> {
        val specs = mutableListOf<ToolCallSpec>()
        var currentDomain = ""

        ToolSpecification.TOOL_CALL_SPECIFICATION
            .lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .forEach { line ->
                when {
                    line.startsWith("// domain:") -> {
                        currentDomain = line.substringAfter("// domain:").trim()
                    }
                    line.startsWith("//") -> {
                        // Skip other comments
                    }
                    line.contains("(") -> {
                        val spec = parseToolCallLine(currentDomain, line)
                        if (spec != null) {
                            specs.add(spec)
                        }
                    }
                }
            }

        return specs
    }

    /**
     * Parse a single tool call line into a ToolCallSpec.
     *
     * Example: "driver.click(selector: String, modifier: String)"
     */
    private fun parseToolCallLine(defaultDomain: String, line: String): ToolCallSpec? {
        // Extract comment as description
        val description = if (line.contains("//")) {
            line.substringAfter("//").trim()
        } else null

        // Get the main expression without comment
        val expression = line.substringBefore("//").trim()

        // Parse domain.method(args): returnType
        val methodPart = expression.substringBefore("(").trim()
        val domain = if (methodPart.contains(".")) {
            methodPart.substringBefore(".")
        } else {
            defaultDomain
        }
        val method = methodPart.substringAfter(".", methodPart)

        // Parse arguments
        val argsStr = expression.substringAfter("(").substringBefore(")").trim()
        val arguments = if (argsStr.isBlank()) {
            emptyList()
        } else {
            parseArguments(argsStr)
        }

        // Parse return type
        val returnType = if (expression.contains("):")) {
            expression.substringAfter("):").trim()
        } else {
            "Unit"
        }

        return ToolCallSpec(
            domain = domain,
            method = method,
            arguments = arguments,
            returnType = returnType,
            description = description,
        )
    }

    /**
     * Parse argument string into list of Args.
     *
     * Example: "selector: String, timeoutMillis: Long = 3000"
     */
    private fun parseArguments(argsStr: String): List<ToolCallSpec.Arg> {
        if (argsStr.isBlank()) return emptyList()

        return argsStr.split(",").mapNotNull { argStr ->
            val trimmed = argStr.trim()
            if (trimmed.isBlank()) return@mapNotNull null

            val name = trimmed.substringBefore(":").trim()
            val typeAndDefault = trimmed.substringAfter(":").trim()

            val (type, defaultValue) = if (typeAndDefault.contains("=")) {
                val t = typeAndDefault.substringBefore("=").trim()
                val d = typeAndDefault.substringAfter("=").trim()
                t to d
            } else {
                typeAndDefault to null
            }

            ToolCallSpec.Arg(name, type, defaultValue)
        }
    }

    /**
     * Convert Kotlin type to JSON Schema type.
     */
    private fun kotlinTypeToJsonSchemaType(kotlinType: String): String {
        return when (kotlinType.lowercase().removeSuffix("?")) {
            "string" -> "string"
            "int", "integer" -> "integer"
            "long" -> "integer"
            "double", "float" -> "number"
            "boolean", "bool" -> "boolean"
            "list", "array" -> "array"
            "map", "object" -> "object"
            else -> "string"  // Default to string for unknown types
        }
    }

    /**
     * Convert JSON Schema type to Kotlin type.
     */
    private fun jsonSchemaTypeToKotlinType(jsonType: String): String {
        return when (jsonType.lowercase()) {
            "string" -> "String"
            "integer" -> "Long"
            "number" -> "Double"
            "boolean" -> "Boolean"
            "array" -> "List<Any>"
            "object" -> "Map<String, Any>"
            else -> "String"
        }
    }

    /**
     * Parse default value string to appropriate type.
     */
    private fun parseDefaultValue(value: String?, type: String): Any? {
        if (value == null) return null

        return when (kotlinTypeToJsonSchemaType(type)) {
            "integer" -> value.toLongOrNull()
            "number" -> value.toDoubleOrNull()
            "boolean" -> value.toBooleanStrictOrNull()
            else -> value.trim('"', '\'')
        }
    }

    /**
     * Parse tool name into domain and method.
     */
    private fun parseToolName(name: String): Pair<String, String> {
        return if (name.contains(".")) {
            name.substringBefore(".") to name.substringAfter(".")
        } else {
            "default" to name
        }
    }
}
