package ai.platon.pulsar.agentic.mcp

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

/**
 * Renderer for MCP tool definitions.
 *
 * Provides JSON serialization for MCP tools compatible with Claude's expectations.
 *
 * @author Vincent Zhang, ivincent.zhang@gmail.com, platon.ai
 */
object MCPToolRenderer {

    private val mapper: ObjectMapper = jacksonObjectMapper().apply {
        setSerializationInclusion(JsonInclude.Include.NON_NULL)
        enable(SerializationFeature.INDENT_OUTPUT)
    }

    private val compactMapper: ObjectMapper = jacksonObjectMapper().apply {
        setSerializationInclusion(JsonInclude.Include.NON_NULL)
        disable(SerializationFeature.INDENT_OUTPUT)
    }

    /**
     * Render a single MCP tool definition to JSON.
     *
     * @param tool The tool definition to render
     * @param pretty Whether to format the JSON with indentation
     * @return JSON string representation
     */
    fun renderTool(tool: MCPToolDefinition, pretty: Boolean = true): String {
        return if (pretty) {
            mapper.writeValueAsString(tool)
        } else {
            compactMapper.writeValueAsString(tool)
        }
    }

    /**
     * Render multiple MCP tool definitions to JSON array.
     *
     * @param tools List of tool definitions
     * @param pretty Whether to format the JSON with indentation
     * @return JSON array string
     */
    fun renderTools(tools: List<MCPToolDefinition>, pretty: Boolean = true): String {
        return if (pretty) {
            mapper.writeValueAsString(tools)
        } else {
            compactMapper.writeValueAsString(tools)
        }
    }

    /**
     * Render tools/list response.
     *
     * @param tools List of tool definitions
     * @param cursor Optional cursor for pagination
     * @return JSON response for tools/list
     */
    fun renderToolsListResponse(tools: List<MCPToolDefinition>, cursor: String? = null): String {
        val response = mapOf(
            "tools" to tools,
            "nextCursor" to cursor
        ).filterValues { it != null }
        return mapper.writeValueAsString(response)
    }

    /**
     * Render a tool result.
     *
     * @param result The tool result
     * @param pretty Whether to format the JSON with indentation
     * @return JSON string representation
     */
    fun renderToolResult(result: MCPToolResult, pretty: Boolean = true): String {
        return if (pretty) {
            mapper.writeValueAsString(result)
        } else {
            compactMapper.writeValueAsString(result)
        }
    }

    /**
     * Create a text tool result.
     *
     * @param text The text content
     * @param isError Whether this is an error result
     * @return MCPToolResult with text content
     */
    fun createTextResult(text: String, isError: Boolean = false): MCPToolResult {
        return MCPToolResult(
            content = listOf(
                MCPToolResultContent(
                    type = "text",
                    text = text
                )
            ),
            isError = isError
        )
    }

    /**
     * Create an error tool result.
     *
     * @param errorMessage The error message
     * @return MCPToolResult with error flag
     */
    fun createErrorResult(errorMessage: String): MCPToolResult {
        return createTextResult(errorMessage, isError = true)
    }

    /**
     * Create an image tool result.
     *
     * @param base64Data Base64-encoded image data
     * @param mimeType MIME type of the image (e.g., "image/png")
     * @return MCPToolResult with image content
     */
    fun createImageResult(base64Data: String, mimeType: String = "image/png"): MCPToolResult {
        return MCPToolResult(
            content = listOf(
                MCPToolResultContent(
                    type = "image",
                    data = base64Data,
                    mimeType = mimeType
                )
            ),
            isError = false
        )
    }

    /**
     * Create a mixed content tool result.
     *
     * @param contents List of content items
     * @param isError Whether this is an error result
     * @return MCPToolResult with multiple content items
     */
    fun createMixedResult(contents: List<MCPToolResultContent>, isError: Boolean = false): MCPToolResult {
        return MCPToolResult(
            content = contents,
            isError = isError
        )
    }

    /**
     * Render resource list response.
     *
     * @param resources List of resources
     * @param cursor Optional cursor for pagination
     * @return JSON response for resources/list
     */
    fun renderResourcesListResponse(resources: List<MCPResource>, cursor: String? = null): String {
        val response = mapOf(
            "resources" to resources,
            "nextCursor" to cursor
        ).filterValues { it != null }
        return mapper.writeValueAsString(response)
    }

    /**
     * Render resource contents response.
     *
     * @param contents List of resource contents
     * @return JSON response for resources/read
     */
    fun renderResourceContentsResponse(contents: List<MCPResourceContents>): String {
        return mapper.writeValueAsString(mapOf("contents" to contents))
    }

    /**
     * Render prompts list response.
     *
     * @param prompts List of prompts
     * @param cursor Optional cursor for pagination
     * @return JSON response for prompts/list
     */
    fun renderPromptsListResponse(prompts: List<MCPPrompt>, cursor: String? = null): String {
        val response = mapOf(
            "prompts" to prompts,
            "nextCursor" to cursor
        ).filterValues { it != null }
        return mapper.writeValueAsString(response)
    }

    /**
     * Render prompt get response.
     *
     * @param description Optional description
     * @param messages List of prompt messages
     * @return JSON response for prompts/get
     */
    fun renderPromptGetResponse(description: String?, messages: List<MCPPromptMessage>): String {
        val response = mapOf(
            "description" to description,
            "messages" to messages
        ).filterValues { it != null }
        return mapper.writeValueAsString(response)
    }

    /**
     * Render server capabilities.
     *
     * @param capabilities Server capabilities
     * @return JSON string
     */
    fun renderCapabilities(capabilities: MCPServerCapabilities): String {
        return mapper.writeValueAsString(capabilities)
    }

    /**
     * Get the ObjectMapper instance for custom serialization needs.
     */
    fun getMapper(): ObjectMapper = mapper
}
