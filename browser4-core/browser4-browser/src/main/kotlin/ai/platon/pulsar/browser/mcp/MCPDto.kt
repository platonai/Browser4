package ai.platon.pulsar.browser.mcp

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Minimal MCP DTOs matching the wire format of [ai.platon.pulsar.rest.mcp.controller.MCPToolController].
 */
@Serializable
data class MCPToolCallRequest(
    val tool: String,
    val arguments: Map<String, String>? = null
)

@Serializable
data class MCPToolCallResponse(
    val content: List<MCPContent>,
    val isError: Boolean = false
)

@Serializable
data class MCPContent(
    val type: String = "text",
    val text: String
)
