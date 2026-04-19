package ai.platon.pulsar.agentic.tools.specs

import ai.platon.pulsar.agentic.model.ToolSpec
import ai.platon.pulsar.agentic.tools.CustomToolRegistry

/**
 * Supported formats for rendering tool-call specifications.
 */
enum class ToolSpecFormat {
    /**
     * Kotlin-like syntax: `domain.method(arg: Type = default): ReturnType`
     */
    KOTLIN,
    
    /**
     * JSON format: structured JSON array with tool definitions
     */
    JSON
}

/**
 * Renders tool-call specifications (signatures) into a prompt-friendly string.
 *
 * Built-in tools are emitted verbatim from [ToolSpecification.TOOL_CALL_SPECIFICATION] (no parsing/re-rendering),
 * and then an optional "CustomTool" section is appended for runtime-registered tools.
 *
 * Supports two formats:
 * - [ToolSpecFormat.KOTLIN]: Kotlin-like syntax (default)
 * - [ToolSpecFormat.JSON]: JSON format for structured tool definitions
 */
object ToolCallSpecificationRenderer {

    /**
     * Render built-in tool-call specs from [ToolSpecification.TOOL_CALL_SPECIFICATION] (verbatim)
     * plus optional custom tool-call specs.
     */
    fun render(
        includeCustomDomains: Boolean = true,
        customDomainFilter: ((String) -> Boolean)? = null,
    ): String {
        val builtIn = ToolSpecification.TOOL_CALL_SPECIFICATION.trimEnd()

        if (!includeCustomDomains) {
            return builtIn
        }

        val customSpecs = CustomToolRegistry.instance.getAllDomains()
            .asSequence()
            .filter { customDomainFilter?.invoke(it) ?: true }
            .flatMap { CustomToolRegistry.instance.getToolCallSpecifications(it).asSequence() }
            .toList()

        if (customSpecs.isEmpty()) {
            return builtIn
        }

        val custom = renderCustomTools(customSpecs)

        return buildString {
            append(builtIn)
            append("\n\n")
            append("// CustomTool\n")
            append(custom)
        }.trimEnd()
    }

    /**
     * Render tool-call specs in the specified format.
     *
     * @param format The output format (KOTLIN or JSON)
     * @param includeCustomDomains Whether to include custom domain tools
     * @param customDomainFilter Optional filter for custom domains
     * @return Formatted tool specifications string
     */
    fun render(
        format: ToolSpecFormat,
        includeCustomDomains: Boolean = true,
        customDomainFilter: ((String) -> Boolean)? = null,
    ): String {
        return when (format) {
            ToolSpecFormat.KOTLIN -> render(includeCustomDomains, customDomainFilter)
            ToolSpecFormat.JSON -> renderJson(includeCustomDomains, customDomainFilter)
        }
    }

    /**
     * Render built-in and custom tool-call specs as JSON.
     *
     * @param includeCustomDomains Whether to include custom domain tools
     * @param customDomainFilter Optional filter for custom domains
     * @return JSON formatted tool specifications
     */
    fun renderJson(
        includeCustomDomains: Boolean = true,
        customDomainFilter: ((String) -> Boolean)? = null,
    ): String {
        val builtInSpecs = parseBuiltInSpecifications()
        
        val customSpecs = if (includeCustomDomains) {
            CustomToolRegistry.instance.getAllDomains()
                .asSequence()
                .filter { customDomainFilter?.invoke(it) ?: true }
                .flatMap { CustomToolRegistry.instance.getToolCallSpecifications(it).asSequence() }
                .toList()
        } else {
            emptyList()
        }
        
        val allSpecs = (builtInSpecs + customSpecs)
            .distinctBy { distinctKey(it) }
            .sortedWith(compareBy({ it.domain }, { it.method }, { it.arguments.size }))
        
        return renderSpecsAsJson(allSpecs)
    }

    /**
     * Render a list of [ToolSpec] into kotlin-like signatures.
     */
    fun render(specs: List<ToolSpec>): String {
        return specs
            .asSequence()
            .distinctBy { distinctKey(it) }
            .sortedWith(compareBy<ToolSpec>({ it.domain }, { it.method }, { it.arguments.size }))
            .joinToString("\n") { renderSpec(it) }
    }

    /**
     * Render a list of [ToolSpec] into JSON format.
     */
    fun renderAsJson(specs: List<ToolSpec>): String {
        val sortedSpecs = specs
            .distinctBy { distinctKey(it) }
            .sortedWith(compareBy({ it.domain }, { it.method }, { it.arguments.size }))
        return renderSpecsAsJson(sortedSpecs)
    }

    /**
     * Parse built-in tool specifications from [ToolSpecification.TOOL_CALL_SPECIFICATION].
     *
     * @return List of parsed [ToolSpec] objects
     */
    fun parseBuiltInSpecifications(): List<ToolSpec> {
        val specs = mutableListOf<ToolSpec>()
        var currentDomain = ""
        
        ToolSpecification.TOOL_CALL_SPECIFICATION.lines().forEach { line ->
            val trimmed = line.trim()
            
            when {
                trimmed.isEmpty() -> { /* skip empty lines */ }
                trimmed.startsWith("// domain:") -> {
                    currentDomain = trimmed.substringAfter("// domain:").trim()
                }
                trimmed.startsWith("//") -> { /* skip other comments */ }
                trimmed.contains("(") -> {
                    parseToolSpec(trimmed, currentDomain)?.let { specs.add(it) }
                }
            }
        }
        
        return specs
    }

    private fun parseToolSpec(line: String, fallbackDomain: String): ToolSpec? {
        // Handle inline comments
        val mainPart = line.substringBefore("//").trim()
        val comment = line.substringAfter("//", "").trim().takeIf { line.contains("//") }
        
        // Parse: domain.method(args): ReturnType
        val domainMethodPart = mainPart.substringBefore("(")
        val argsPart = mainPart.substringAfter("(").substringBefore(")")
        val returnPart = mainPart.substringAfter(")").removePrefix(":").trim()
        
        val domain: String
        val method: String
        
        if (domainMethodPart.contains(".")) {
            domain = domainMethodPart.substringBefore(".")
            method = domainMethodPart.substringAfter(".")
        } else {
            domain = fallbackDomain
            method = domainMethodPart
        }
        
        if (domain.isBlank() || method.isBlank()) {
            return null
        }
        
        val arguments = parseArguments(argsPart)
        val returnType = returnPart.ifBlank { "Unit" }
        
        return ToolSpec(
            domain = domain,
            method = method,
            arguments = arguments,
            returnType = returnType,
            description = comment
        )
    }

    private fun parseArguments(argsPart: String): List<ToolSpec.Arg> {
        if (argsPart.isBlank()) return emptyList()
        
        val args = mutableListOf<ToolSpec.Arg>()
        val tokens = splitTopLevelArgs(argsPart)
        
        for (token in tokens) {
            val trimmed = token.trim()
            if (trimmed.isEmpty()) continue
            
            // Parse: name: Type = defaultValue
            val colonIndex = trimmed.indexOf(':')
            if (colonIndex <= 0) continue
            
            val name = trimmed.substring(0, colonIndex).trim()
            val rest = trimmed.substring(colonIndex + 1).trim()
            
            val equalsIndex = findTopLevelEquals(rest)
            val type: String
            val defaultValue: String?
            
            if (equalsIndex >= 0) {
                type = rest.substring(0, equalsIndex).trim()
                defaultValue = rest.substring(equalsIndex + 1).trim()
            } else {
                type = rest
                defaultValue = null
            }
            
            args.add(ToolSpec.Arg(name, type, defaultValue))
        }
        
        return args
    }

    private fun splitTopLevelArgs(s: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var depth = 0
        
        for (c in s) {
            when {
                c == ',' && depth == 0 -> {
                    result.add(current.toString())
                    current.clear()
                }
                c in "<([" -> {
                    depth++
                    current.append(c)
                }
                c in ">)]" -> {
                    depth--
                    current.append(c)
                }
                else -> current.append(c)
            }
        }
        
        if (current.isNotEmpty()) {
            result.add(current.toString())
        }
        
        return result
    }

    private fun findTopLevelEquals(s: String): Int {
        var depth = 0
        for ((index, c) in s.withIndex()) {
            when {
                c == '=' && depth == 0 -> return index
                c in "<([" -> depth++
                c in ">)]" -> depth--
            }
        }
        return -1
    }

    private fun renderCustomTools(specs: List<ToolSpec>): String {
        // Custom specs are rendered using our structured format so the model can see the signature.
        return render(specs)
    }

    private fun renderSpec(spec: ToolSpec): String {
        val args = spec.arguments.joinToString(prefix = "(", postfix = ")") { it.expression }
        val returnPart = spec.returnType.takeIf { it.isNotBlank() && it != "Unit" }?.let { ": $it" } ?: ""
        return "${spec.domain}.${spec.method}$args$returnPart".trim()
    }

    private fun renderSpecsAsJson(specs: List<ToolSpec>): String {
        val toolsJson = specs.map { spec ->
            buildJsonToolObject(spec)
        }
        
        return buildString {
            appendLine("{")
            appendLine("""  "tools": [""")
            toolsJson.forEachIndexed { index, json ->
                append(json)
                if (index < toolsJson.size - 1) {
                    appendLine(",")
                } else {
                    appendLine()
                }
            }
            appendLine("  ]")
            append("}")
        }
    }

    private fun buildJsonToolObject(spec: ToolSpec): String {
        val indent = "    "
        return buildString {
            appendLine("$indent{")
            appendLine("""$indent  "domain": "${escapeJson(spec.domain)}",""")
            appendLine("""$indent  "method": "${escapeJson(spec.method)}",""")
            
            // Parameters
            appendLine("""$indent  "parameters": [""")
            spec.arguments.forEachIndexed { index, arg ->
                append("$indent    {")
                append(""""name": "${escapeJson(arg.name)}", """)
                append(""""type": "${escapeJson(arg.type)}"""")
                if (arg.defaultValue != null) {
                    append(""", "default": "${escapeJson(arg.defaultValue)}"""")
                }
                append("}")
                if (index < spec.arguments.size - 1) {
                    appendLine(",")
                } else {
                    appendLine()
                }
            }
            appendLine("$indent  ],")
            
            // Return type
            append("""$indent  "returns": "${escapeJson(spec.returnType)}"""")
            
            // Description
            if (!spec.description.isNullOrBlank()) {
                appendLine(",")
                append("""$indent  "description": "${escapeJson(spec.description)}"""")
            }
            appendLine()
            append("$indent}")
        }
    }

    private fun escapeJson(s: String): String {
        return s.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }

    private fun distinctKey(spec: ToolSpec): String {
        val argsKey = spec.arguments.joinToString(",") { it.expression }
        return "${spec.domain}.${spec.method}($argsKey):${spec.returnType}".trim()
    }
}
