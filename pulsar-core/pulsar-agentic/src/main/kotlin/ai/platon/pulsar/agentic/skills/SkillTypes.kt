package ai.platon.pulsar.agentic.skills

import ai.platon.pulsar.agentic.mcp.MCPToolDefinition
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonInclude

/**
 * Claude Skills compatible data types for AI assistant integration.
 *
 * This file contains the core data structures required for Claude Skills compatibility:
 * - Skill definitions with tools and examples
 * - Skill metadata and versioning
 * - Export formats for Claude Desktop
 *
 * @see <a href="https://docs.anthropic.com/en/docs/build-with-claude/tool-use">Claude Tool Use</a>
 * @author Vincent Zhang, ivincent.zhang@gmail.com, platon.ai
 */

// ============================================================================
// Skill Definitions
// ============================================================================

/**
 * Claude Skill definition.
 *
 * A skill is a reusable capability unit that groups related tools together
 * with instructions and examples for how to use them.
 *
 * Example JSON output:
 * ```json
 * {
 *   "name": "web_browsing",
 *   "description": "Browse and interact with web pages",
 *   "instructions": "Use this skill to navigate websites...",
 *   "tools": [...],
 *   "examples": [...]
 * }
 * ```
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ClaudeSkill(
    /** Unique skill name in snake_case */
    val name: String,
    /** Human-readable display name */
    val displayName: String,
    /** Brief description of what this skill does */
    val description: String,
    /** Detailed instructions for how to use this skill */
    val instructions: String,
    /** List of tools available in this skill */
    val tools: List<SkillTool>,
    /** Usage examples */
    val examples: List<SkillExample>? = null,
    /** Skill metadata */
    val metadata: SkillMetadata? = null,
    /** Skill category */
    val category: SkillCategory = SkillCategory.BROWSER_AUTOMATION,
) {
    /**
     * Get all tool names in this skill
     */
    @get:JsonIgnore
    val toolNames: List<String>
        get() = tools.map { it.name }

    /**
     * Get the number of tools in this skill
     */
    @get:JsonIgnore
    val toolCount: Int
        get() = tools.size
}

/**
 * Skill category for organization.
 */
enum class SkillCategory(val displayName: String) {
    BROWSER_AUTOMATION("Browser Automation"),
    DATA_EXTRACTION("Data Extraction"),
    FORM_AUTOMATION("Form Automation"),
    FILE_OPERATIONS("File Operations"),
    NAVIGATION("Navigation"),
    SYSTEM("System"),
    CUSTOM("Custom"),
}

/**
 * Tool definition within a skill.
 *
 * Based on MCP tool definition but with additional skill-specific metadata.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class SkillTool(
    /** Tool name in format "domain.method" */
    val name: String,
    /** Human-readable description */
    val description: String,
    /** JSON Schema for input parameters */
    val inputSchema: SkillToolInputSchema,
    /** Whether this tool may cause navigation */
    val mayNavigate: Boolean = false,
    /** Whether this tool is read-only (no side effects) */
    val readOnly: Boolean = false,
    /** Typical use cases for this tool */
    val useCases: List<String>? = null,
) {
    /**
     * Convert to MCP tool definition.
     */
    fun toMCPToolDefinition(): MCPToolDefinition {
        return MCPToolDefinition(
            name = name,
            description = description,
            inputSchema = inputSchema.toMCPInputSchema(),
        )
    }
}

/**
 * JSON Schema for tool input parameters (skill-specific).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class SkillToolInputSchema(
    /** Schema type, always "object" for tool inputs */
    val type: String = "object",
    /** Map of property name to property schema */
    val properties: Map<String, SkillPropertySchema> = emptyMap(),
    /** List of required property names */
    val required: List<String> = emptyList(),
    /** Additional properties allowed flag */
    val additionalProperties: Boolean? = null,
) {
    fun toMCPInputSchema(): ai.platon.pulsar.agentic.mcp.MCPInputSchema {
        return ai.platon.pulsar.agentic.mcp.MCPInputSchema(
            type = type,
            properties = properties.mapValues { it.value.toMCPPropertySchema() },
            required = required,
            additionalProperties = additionalProperties,
        )
    }
}

/**
 * JSON Schema for a single property (skill-specific).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class SkillPropertySchema(
    /** Property type: string, number, integer, boolean, array, object */
    val type: String,
    /** Human-readable description */
    val description: String? = null,
    /** Default value if not provided */
    val default: Any? = null,
    /** Allowed values for enum types */
    val enumValues: List<Any>? = null,
    /** Schema for array items (when type is "array") */
    val items: SkillPropertySchema? = null,
    /** Example values */
    val examples: List<Any>? = null,
) {
    fun toMCPPropertySchema(): ai.platon.pulsar.agentic.mcp.MCPPropertySchema {
        return ai.platon.pulsar.agentic.mcp.MCPPropertySchema(
            type = type,
            description = description,
            default = default,
            enumValues = enumValues,
            items = items?.toMCPPropertySchema(),
        )
    }
}

// ============================================================================
// Skill Examples
// ============================================================================

/**
 * Usage example for a skill.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class SkillExample(
    /** Natural language description of what the user wants */
    val input: String,
    /** Description of expected outcome */
    val output: String? = null,
    /** Step-by-step tool calls to achieve the goal */
    val steps: List<SkillExampleStep>,
    /** Tags for categorizing examples */
    val tags: List<String>? = null,
)

/**
 * A single step in a skill example.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class SkillExampleStep(
    /** Tool name to call */
    val tool: String,
    /** Arguments to pass to the tool */
    val arguments: Map<String, Any>,
    /** Description of what this step does */
    val description: String? = null,
)

// ============================================================================
// Skill Metadata
// ============================================================================

/**
 * Metadata about a skill.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class SkillMetadata(
    /** Skill version in semver format */
    val version: String = "1.0.0",
    /** Author information */
    val author: String? = null,
    /** License information */
    val license: String? = null,
    /** Tags for categorization */
    val tags: List<String>? = null,
    /** Homepage or documentation URL */
    val homepage: String? = null,
    /** Repository URL */
    val repository: String? = null,
    /** Minimum Browser4 version required */
    val minBrowser4Version: String? = null,
    /** Whether this skill is experimental */
    val experimental: Boolean = false,
    /** Whether this skill is deprecated */
    val deprecated: Boolean = false,
    /** Deprecation message if deprecated */
    val deprecationMessage: String? = null,
)

// ============================================================================
// Export Formats
// ============================================================================

/**
 * Claude Desktop skills.json format.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ClaudeDesktopSkillsConfig(
    /** List of skills */
    val skills: List<ClaudeDesktopSkill>,
    /** Configuration version */
    val version: String = "1.0",
)

/**
 * Individual skill in Claude Desktop format.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ClaudeDesktopSkill(
    /** Skill name */
    val name: String,
    /** Skill description */
    val description: String,
    /** Usage instructions */
    val instructions: String,
    /** Available tools */
    val tools: List<ClaudeDesktopTool>,
    /** Usage examples */
    val examples: List<ClaudeDesktopExample>? = null,
)

/**
 * Tool in Claude Desktop format.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ClaudeDesktopTool(
    /** Tool name */
    val name: String,
    /** Tool description */
    val description: String,
    /** Input schema */
    val inputSchema: Map<String, Any>,
)

/**
 * Example in Claude Desktop format.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ClaudeDesktopExample(
    /** User input */
    val input: String,
    /** Expected steps */
    val steps: List<String>,
)

// ============================================================================
// Skill Package
// ============================================================================

/**
 * Skill package for distribution.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class SkillPackage(
    /** Package name */
    val name: String,
    /** Package version */
    val version: String,
    /** Package description */
    val description: String,
    /** Skills in this package */
    val skills: List<ClaudeSkill>,
    /** Package metadata */
    val metadata: SkillPackageMetadata? = null,
)

/**
 * Skill package metadata.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class SkillPackageMetadata(
    /** Author information */
    val author: String? = null,
    /** License */
    val license: String? = null,
    /** Homepage URL */
    val homepage: String? = null,
    /** Repository URL */
    val repository: String? = null,
    /** Keywords for search */
    val keywords: List<String>? = null,
)
