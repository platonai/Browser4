package ai.platon.pulsar.browser.mcp

/**
 * Minimal MCP DTOs matching the wire format of [ai.platon.pulsar.rest.mcp.controller.MCPToolController]
 * without Spring/Jackson annotations.
 */
data class MCPToolCallRequest(
    val tool: String,
    val arguments: Map<String, Any?>? = null
)

data class MCPToolCallResponse(
    val content: List<MCPContent>,
    val isError: Boolean = false
)

data class MCPContent(
    val type: String = "text",
    val text: String
)
