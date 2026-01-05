package ai.platon.pulsar.agentic.mcp

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * MCP (Model Context Protocol) compatible data types for AI assistant integration.
 *
 * This file contains the core data structures required for MCP compatibility:
 * - Tool definitions with JSON Schema
 * - Resource definitions
 * - Protocol message types
 *
 * @see <a href="https://modelcontextprotocol.io/specification">MCP Specification</a>
 * @author Vincent Zhang, ivincent.zhang@gmail.com, platon.ai
 */

// ============================================================================
// Tool Definitions
// ============================================================================

/**
 * MCP-compatible tool definition.
 *
 * Represents a tool that can be invoked by Claude through the MCP protocol.
 *
 * Example JSON output:
 * ```json
 * {
 *   "name": "driver.click",
 *   "description": "Click on an element with the specified selector",
 *   "inputSchema": {
 *     "type": "object",
 *     "properties": {
 *       "selector": { "type": "string", "description": "CSS selector" }
 *     },
 *     "required": ["selector"]
 *   }
 * }
 * ```
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class MCPToolDefinition(
    /** Unique tool name, typically in format "domain.method" */
    val name: String,
    /** Human-readable description of what the tool does */
    val description: String,
    /** JSON Schema defining the tool's input parameters */
    val inputSchema: MCPInputSchema,
)

/**
 * JSON Schema for tool input parameters.
 *
 * Follows JSON Schema draft-07 format for parameter validation.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class MCPInputSchema(
    /** Schema type, always "object" for tool inputs */
    val type: String = "object",
    /** Map of property name to property schema */
    val properties: Map<String, MCPPropertySchema> = emptyMap(),
    /** List of required property names */
    val required: List<String> = emptyList(),
    /** Additional properties allowed flag */
    val additionalProperties: Boolean? = null,
)

/**
 * JSON Schema for a single property.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class MCPPropertySchema(
    /** Property type: string, number, integer, boolean, array, object */
    val type: String,
    /** Human-readable description */
    val description: String? = null,
    /** Default value if not provided */
    val default: Any? = null,
    /** Allowed values for enum types */
    @JsonProperty("enum")
    val enumValues: List<Any>? = null,
    /** Schema for array items (when type is "array") */
    val items: MCPPropertySchema? = null,
    /** Nested properties (when type is "object") */
    val properties: Map<String, MCPPropertySchema>? = null,
    /** Required properties (when type is "object") */
    val required: List<String>? = null,
)

// ============================================================================
// Resource Definitions
// ============================================================================

/**
 * MCP Resource definition.
 *
 * Resources represent data that can be read by the LLM, such as files,
 * database records, or API responses.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class MCPResource(
    /** Unique URI identifying this resource */
    val uri: String,
    /** Human-readable name */
    val name: String,
    /** Description of what this resource contains */
    val description: String? = null,
    /** MIME type of the resource content */
    val mimeType: String? = null,
)

/**
 * Resource contents returned when reading a resource.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class MCPResourceContents(
    /** URI of the resource */
    val uri: String,
    /** MIME type of the content */
    val mimeType: String? = null,
    /** Text content (for text-based resources) */
    val text: String? = null,
    /** Base64-encoded binary content (for binary resources) */
    val blob: String? = null,
)

/**
 * Resource template for dynamic resource URIs.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class MCPResourceTemplate(
    /** URI template with placeholders, e.g., "file:///{path}" */
    val uriTemplate: String,
    /** Human-readable name */
    val name: String,
    /** Description of the resource template */
    val description: String? = null,
    /** MIME type of resources matching this template */
    val mimeType: String? = null,
)

// ============================================================================
// Prompt Definitions
// ============================================================================

/**
 * MCP Prompt definition.
 *
 * Prompts are reusable templates that can be invoked with arguments.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class MCPPrompt(
    /** Unique prompt name */
    val name: String,
    /** Description of what this prompt does */
    val description: String? = null,
    /** Arguments that can be passed to this prompt */
    val arguments: List<MCPPromptArgument> = emptyList(),
)

/**
 * Argument definition for a prompt.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class MCPPromptArgument(
    /** Argument name */
    val name: String,
    /** Description of this argument */
    val description: String? = null,
    /** Whether this argument is required */
    val required: Boolean = false,
)

/**
 * Message returned when getting a prompt.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class MCPPromptMessage(
    /** Role: "user" or "assistant" */
    val role: String,
    /** Message content */
    val content: MCPMessageContent,
)

/**
 * Content of a prompt message.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class MCPMessageContent(
    /** Content type: "text" or "image" */
    val type: String,
    /** Text content (when type is "text") */
    val text: String? = null,
    /** Image data (when type is "image") */
    val data: String? = null,
    /** MIME type (when type is "image") */
    val mimeType: String? = null,
)

// ============================================================================
// Tool Call Types
// ============================================================================

/**
 * MCP tool call request.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class MCPToolCall(
    /** Name of the tool to call */
    val name: String,
    /** Arguments to pass to the tool */
    val arguments: Map<String, Any?> = emptyMap(),
) {
    /**
     * Extract domain from tool name (e.g., "driver" from "driver.click")
     */
    @get:JsonIgnore
    val domain: String
        get() = name.substringBefore('.', name)

    /**
     * Extract method from tool name (e.g., "click" from "driver.click")
     */
    @get:JsonIgnore
    val method: String
        get() = name.substringAfter('.', name)
}

/**
 * MCP tool call result.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class MCPToolResult(
    /** Result content */
    val content: List<MCPToolResultContent>,
    /** Whether the tool call resulted in an error */
    val isError: Boolean = false,
)

/**
 * Content of a tool result.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class MCPToolResultContent(
    /** Content type: "text" or "image" */
    val type: String,
    /** Text content (when type is "text") */
    val text: String? = null,
    /** Image data (when type is "image") */
    val data: String? = null,
    /** MIME type (when type is "image") */
    val mimeType: String? = null,
)

// ============================================================================
// Server Information
// ============================================================================

/**
 * MCP Server capabilities.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class MCPServerCapabilities(
    /** Tool-related capabilities */
    val tools: MCPToolCapabilities? = null,
    /** Resource-related capabilities */
    val resources: MCPResourceCapabilities? = null,
    /** Prompt-related capabilities */
    val prompts: MCPPromptCapabilities? = null,
    /** Logging capabilities */
    val logging: MCPLoggingCapabilities? = null,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class MCPToolCapabilities(
    /** Whether tool list can change dynamically */
    val listChanged: Boolean? = null,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class MCPResourceCapabilities(
    /** Whether resource list can change dynamically */
    val listChanged: Boolean? = null,
    /** Whether server supports resource subscriptions */
    val subscribe: Boolean? = null,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class MCPPromptCapabilities(
    /** Whether prompt list can change dynamically */
    val listChanged: Boolean? = null,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class MCPLoggingCapabilities(
    /** Supported logging levels */
    val levels: List<String>? = null,
)

/**
 * Server information returned during initialization.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class MCPServerInfo(
    /** Server name */
    val name: String,
    /** Server version */
    val version: String,
)

/**
 * Implementation information.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class MCPImplementation(
    /** Implementation name */
    val name: String,
    /** Implementation version */
    val version: String,
)
