package ai.platon.pulsar.agentic.skills

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.registerKotlinModule

/**
 * Renderer for exporting skills to various formats.
 *
 * Supports:
 * - JSON format
 * - Simple YAML-like format (JSON-based)
 * - Claude Desktop skills.json format
 *
 * @author Vincent Zhang, ivincent.zhang@gmail.com, platon.ai
 */
object SkillRenderer {

    private val jsonMapper = ObjectMapper()
        .registerKotlinModule()
        .enable(SerializationFeature.INDENT_OUTPUT)

    // ========================================================================
    // JSON Export
    // ========================================================================

    /**
     * Render a skill to JSON.
     */
    fun renderSkillJson(skill: ClaudeSkill): String {
        return jsonMapper.writeValueAsString(skill)
    }

    /**
     * Render multiple skills to JSON array.
     */
    fun renderSkillsJson(skills: List<ClaudeSkill>): String {
        return jsonMapper.writeValueAsString(skills)
    }

    /**
     * Render a skill package to JSON.
     */
    fun renderPackageJson(pkg: SkillPackage): String {
        return jsonMapper.writeValueAsString(pkg)
    }

    // ========================================================================
    // YAML-like Export (JSON-based since YAML library not available)
    // ========================================================================

    /**
     * Render a skill to YAML-like format.
     * Note: Uses simplified JSON format as YAML library is not available.
     */
    fun renderSkillYaml(skill: ClaudeSkill): String {
        return convertToSimpleYaml(jsonMapper.writeValueAsString(skill))
    }

    /**
     * Render multiple skills to YAML-like format.
     */
    fun renderSkillsYaml(skills: List<ClaudeSkill>): String {
        val json = jsonMapper.writeValueAsString(mapOf("skills" to skills))
        return convertToSimpleYaml(json)
    }

    /**
     * Simple JSON to YAML-like conversion.
     */
    private fun convertToSimpleYaml(json: String): String {
        // Simple conversion: just use readable JSON as a YAML-like format
        // This is acceptable since YAML is a superset of JSON
        return json
    }

    // ========================================================================
    // Claude Desktop Format
    // ========================================================================

    /**
     * Convert a skill to Claude Desktop format.
     */
    fun toClaudeDesktopSkill(skill: ClaudeSkill): ClaudeDesktopSkill {
        return ClaudeDesktopSkill(
            name = skill.name,
            description = skill.description,
            instructions = skill.instructions,
            tools = skill.tools.map { tool ->
                ClaudeDesktopTool(
                    name = tool.name,
                    description = tool.description,
                    inputSchema = buildInputSchemaMap(tool.inputSchema),
                )
            },
            examples = skill.examples?.map { example ->
                ClaudeDesktopExample(
                    input = example.input,
                    steps = example.steps.map { step ->
                        "${step.tool}(${formatArguments(step.arguments)})"
                    },
                )
            },
        )
    }

    /**
     * Render skills to Claude Desktop skills.json format.
     */
    fun renderClaudeDesktopConfig(skills: List<ClaudeSkill>): String {
        val config = ClaudeDesktopSkillsConfig(
            skills = skills.map { toClaudeDesktopSkill(it) },
            version = "1.0",
        )
        return jsonMapper.writeValueAsString(config)
    }

    /**
     * Render a single skill to Claude Desktop format.
     */
    fun renderClaudeDesktopSkill(skill: ClaudeSkill): String {
        return jsonMapper.writeValueAsString(toClaudeDesktopSkill(skill))
    }

    // ========================================================================
    // MCP Tools List Format
    // ========================================================================

    /**
     * Render skill tools in MCP format.
     */
    fun renderSkillToolsMcp(skill: ClaudeSkill): String {
        val mcpTools = skill.tools.map { it.toMCPToolDefinition() }
        return jsonMapper.writeValueAsString(mcpTools)
    }

    // ========================================================================
    // Markdown Documentation
    // ========================================================================

    /**
     * Render skill documentation in Markdown format.
     */
    fun renderSkillMarkdown(skill: ClaudeSkill): String {
        return buildString {
            appendLine("# ${skill.displayName}")
            appendLine()
            appendLine(skill.description)
            appendLine()
            appendLine("## Category")
            appendLine(skill.category.displayName)
            appendLine()
            appendLine("## Instructions")
            appendLine()
            appendLine(skill.instructions)
            appendLine()
            appendLine("## Tools")
            appendLine()
            skill.tools.forEach { tool ->
                appendLine("### ${tool.name}")
                appendLine()
                appendLine(tool.description)
                appendLine()
                if (tool.inputSchema.properties.isNotEmpty()) {
                    appendLine("**Parameters:**")
                    appendLine()
                    appendLine("| Name | Type | Required | Description |")
                    appendLine("|------|------|----------|-------------|")
                    tool.inputSchema.properties.forEach { (name, schema) ->
                        val required = if (name in tool.inputSchema.required) "Yes" else "No"
                        val desc = schema.description ?: "-"
                        appendLine("| $name | ${schema.type} | $required | $desc |")
                    }
                    appendLine()
                }
            }
            if (!skill.examples.isNullOrEmpty()) {
                appendLine("## Examples")
                appendLine()
                skill.examples.forEachIndexed { index, example ->
                    appendLine("### Example ${index + 1}")
                    appendLine()
                    appendLine("**Input:** ${example.input}")
                    appendLine()
                    if (!example.output.isNullOrBlank()) {
                        appendLine("**Expected Output:** ${example.output}")
                        appendLine()
                    }
                    appendLine("**Steps:**")
                    appendLine()
                    example.steps.forEachIndexed { stepIndex, step ->
                        appendLine("${stepIndex + 1}. `${step.tool}(${formatArguments(step.arguments)})`")
                        if (!step.description.isNullOrBlank()) {
                            appendLine("   - ${step.description}")
                        }
                    }
                    appendLine()
                }
            }
            if (skill.metadata != null) {
                appendLine("## Metadata")
                appendLine()
                appendLine("- **Version:** ${skill.metadata.version}")
                skill.metadata.author?.let { appendLine("- **Author:** $it") }
                skill.metadata.tags?.let { appendLine("- **Tags:** ${it.joinToString(", ")}") }
            }
        }
    }

    // ========================================================================
    // Private Helpers
    // ========================================================================

    private fun buildInputSchemaMap(schema: SkillToolInputSchema): Map<String, Any> {
        val result = mutableMapOf<String, Any>(
            "type" to schema.type,
        )
        if (schema.properties.isNotEmpty()) {
            result["properties"] = schema.properties.mapValues { (_, prop) ->
                buildPropertySchemaMap(prop)
            }
        }
        if (schema.required.isNotEmpty()) {
            result["required"] = schema.required
        }
        return result
    }

    private fun buildPropertySchemaMap(schema: SkillPropertySchema): Map<String, Any?> {
        val result = mutableMapOf<String, Any?>(
            "type" to schema.type,
        )
        schema.description?.let { result["description"] = it }
        schema.default?.let { result["default"] = it }
        schema.enumValues?.let { result["enum"] = it }
        return result.filterValues { it != null }
    }

    private fun formatArguments(args: Map<String, Any>): String {
        return args.entries.joinToString(", ") { (key, value) ->
            val formattedValue = when (value) {
                is String -> "\"$value\""
                else -> value.toString()
            }
            "$key=$formattedValue"
        }
    }
}
