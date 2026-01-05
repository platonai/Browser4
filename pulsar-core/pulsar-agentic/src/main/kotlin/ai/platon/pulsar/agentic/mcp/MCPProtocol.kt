package ai.platon.pulsar.agentic.mcp

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue

/**
 * MCP JSON-RPC Protocol Types and Handler.
 *
 * Implements the JSON-RPC 2.0 message format used by MCP protocol.
 *
 * @see <a href="https://www.jsonrpc.org/specification">JSON-RPC 2.0 Specification</a>
 * @author Vincent Zhang, ivincent.zhang@gmail.com, platon.ai
 */

// ============================================================================
// JSON-RPC Message Types
// ============================================================================

/**
 * JSON-RPC request message.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class JsonRpcRequest(
    val jsonrpc: String = "2.0",
    val id: Any?,  // Can be String, Int, or null for notifications
    val method: String,
    val params: JsonNode? = null,
)

/**
 * JSON-RPC response message.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class JsonRpcResponse(
    val jsonrpc: String = "2.0",
    val id: Any?,
    val result: Any? = null,
    val error: JsonRpcError? = null,
)

/**
 * JSON-RPC error object.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class JsonRpcError(
    val code: Int,
    val message: String,
    val data: Any? = null,
) {
    companion object {
        // Standard JSON-RPC error codes
        const val PARSE_ERROR = -32700
        const val INVALID_REQUEST = -32600
        const val METHOD_NOT_FOUND = -32601
        const val INVALID_PARAMS = -32602
        const val INTERNAL_ERROR = -32603

        fun parseError(message: String = "Parse error") =
            JsonRpcError(PARSE_ERROR, message)

        fun invalidRequest(message: String = "Invalid request") =
            JsonRpcError(INVALID_REQUEST, message)

        fun methodNotFound(method: String) =
            JsonRpcError(METHOD_NOT_FOUND, "Method not found: $method")

        fun invalidParams(message: String = "Invalid params") =
            JsonRpcError(INVALID_PARAMS, message)

        fun internalError(message: String = "Internal error") =
            JsonRpcError(INTERNAL_ERROR, message)
    }
}

/**
 * JSON-RPC notification (request without id).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class JsonRpcNotification(
    val jsonrpc: String = "2.0",
    val method: String,
    val params: Any? = null,
)

// ============================================================================
// MCP Protocol Messages
// ============================================================================

/**
 * Initialize request parameters.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class InitializeParams(
    val protocolVersion: String,
    val capabilities: ClientCapabilities,
    val clientInfo: MCPImplementation,
)

/**
 * Client capabilities sent during initialization.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ClientCapabilities(
    val roots: RootsCapability? = null,
    val sampling: SamplingCapability? = null,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class RootsCapability(
    val listChanged: Boolean? = null,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class SamplingCapability(
    val supported: Boolean? = null,
)

/**
 * Initialize result.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class InitializeResult(
    val protocolVersion: String,
    val capabilities: MCPServerCapabilities,
    val serverInfo: MCPServerInfo,
)

/**
 * Tools/call request parameters.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ToolsCallParams(
    val name: String,
    val arguments: Map<String, Any?> = emptyMap(),
)

/**
 * Resources/read request parameters.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ResourcesReadParams(
    val uri: String,
)

/**
 * Prompts/get request parameters.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class PromptsGetParams(
    val name: String,
    val arguments: Map<String, String> = emptyMap(),
)

// ============================================================================
// MCP Protocol Handler
// ============================================================================

/**
 * Handler for MCP protocol messages.
 *
 * This class processes JSON-RPC requests and produces appropriate responses
 * according to the MCP specification.
 */
class MCPProtocolHandler(
    private val registry: MCPToolRegistry = MCPToolRegistry.instance,
    private val serverName: String = "Browser4",
    private val serverVersion: String = "4.2.0",
) {
    private val mapper: ObjectMapper = jacksonObjectMapper()

    /** Protocol version supported by this handler */
    val protocolVersion = "2024-11-05"

    /** Whether the connection has been initialized */
    private var initialized = false

    /**
     * Handle a JSON-RPC request string.
     *
     * @param requestJson The JSON-RPC request as a string
     * @return JSON-RPC response as a string, or null for notifications
     */
    suspend fun handleRequest(requestJson: String): String? {
        return try {
            val request = mapper.readValue<JsonRpcRequest>(requestJson)
            handleRequest(request)?.let { mapper.writeValueAsString(it) }
        } catch (e: Exception) {
            val error = JsonRpcResponse(
                id = null,
                error = JsonRpcError.parseError(e.message ?: "Parse error")
            )
            mapper.writeValueAsString(error)
        }
    }

    /**
     * Handle a parsed JSON-RPC request.
     *
     * @param request The parsed request
     * @return JSON-RPC response, or null for notifications
     */
    suspend fun handleRequest(request: JsonRpcRequest): JsonRpcResponse? {
        // Notifications have no id and don't expect a response
        if (request.id == null && request.method == "notifications/initialized") {
            handleInitializedNotification()
            return null
        }

        return try {
            val result = when (request.method) {
                "initialize" -> handleInitialize(request)
                "tools/list" -> handleToolsList()
                "tools/call" -> handleToolsCall(request)
                "resources/list" -> handleResourcesList()
                "resources/read" -> handleResourcesRead(request)
                "resources/templates/list" -> handleResourceTemplatesList()
                "prompts/list" -> handlePromptsList()
                "prompts/get" -> handlePromptsGet(request)
                "ping" -> handlePing()
                else -> throw MethodNotFoundException(request.method)
            }

            JsonRpcResponse(id = request.id, result = result)
        } catch (e: MethodNotFoundException) {
            JsonRpcResponse(
                id = request.id,
                error = JsonRpcError.methodNotFound(e.method)
            )
        } catch (e: InvalidParamsException) {
            JsonRpcResponse(
                id = request.id,
                error = JsonRpcError.invalidParams(e.message ?: "Invalid params")
            )
        } catch (e: Exception) {
            JsonRpcResponse(
                id = request.id,
                error = JsonRpcError.internalError(e.message ?: "Internal error")
            )
        }
    }

    /**
     * Handle initialize request.
     */
    private fun handleInitialize(request: JsonRpcRequest): InitializeResult {
        val params = request.params?.let {
            mapper.treeToValue(it, InitializeParams::class.java)
        } ?: throw InvalidParamsException("Missing initialize params")

        return InitializeResult(
            protocolVersion = protocolVersion,
            capabilities = MCPServerCapabilities(
                tools = MCPToolCapabilities(listChanged = true),
                resources = MCPResourceCapabilities(
                    listChanged = true,
                    subscribe = false
                ),
                prompts = MCPPromptCapabilities(listChanged = true),
                logging = null
            ),
            serverInfo = MCPServerInfo(
                name = serverName,
                version = serverVersion
            )
        )
    }

    /**
     * Handle initialized notification.
     */
    private fun handleInitializedNotification() {
        initialized = true
    }

    /**
     * Handle tools/list request.
     */
    private fun handleToolsList(): Map<String, Any> {
        return mapOf("tools" to registry.getAllTools())
    }

    /**
     * Handle tools/call request.
     */
    private suspend fun handleToolsCall(request: JsonRpcRequest): MCPToolResult {
        val params = request.params?.let {
            mapper.treeToValue(it, ToolsCallParams::class.java)
        } ?: throw InvalidParamsException("Missing tools/call params")

        val tool = registry.getTool(params.name)
            ?: throw InvalidParamsException("Tool not found: ${params.name}")

        // Execute the tool - this would integrate with AgentToolManager
        // For now, return a placeholder indicating the tool exists
        return MCPToolRenderer.createTextResult(
            "Tool '${params.name}' called with arguments: ${params.arguments}"
        )
    }

    /**
     * Handle resources/list request.
     */
    private fun handleResourcesList(): Map<String, Any> {
        return mapOf("resources" to registry.getAllResources())
    }

    /**
     * Handle resources/read request.
     */
    private suspend fun handleResourcesRead(request: JsonRpcRequest): Map<String, Any> {
        val params = request.params?.let {
            mapper.treeToValue(it, ResourcesReadParams::class.java)
        } ?: throw InvalidParamsException("Missing resources/read params")

        val contents = registry.readResource(params.uri)
            ?: throw InvalidParamsException("Resource not found: ${params.uri}")

        return mapOf("contents" to listOf(contents))
    }

    /**
     * Handle resources/templates/list request.
     */
    private fun handleResourceTemplatesList(): Map<String, Any> {
        return mapOf("resourceTemplates" to registry.getAllResourceTemplates())
    }

    /**
     * Handle prompts/list request.
     */
    private fun handlePromptsList(): Map<String, Any> {
        return mapOf("prompts" to registry.getAllPrompts())
    }

    /**
     * Handle prompts/get request.
     */
    private fun handlePromptsGet(request: JsonRpcRequest): Map<String, Any> {
        val params = request.params?.let {
            mapper.treeToValue(it, PromptsGetParams::class.java)
        } ?: throw InvalidParamsException("Missing prompts/get params")

        val prompt = registry.getPrompt(params.name)
            ?: throw InvalidParamsException("Prompt not found: ${params.name}")

        // Return a placeholder message - actual implementation would generate messages
        return mapOf(
            "description" to (prompt.description ?: ""),
            "messages" to listOf(
                mapOf(
                    "role" to "user",
                    "content" to mapOf(
                        "type" to "text",
                        "text" to "Prompt: ${prompt.name}"
                    )
                )
            )
        )
    }

    /**
     * Handle ping request.
     */
    private fun handlePing(): Map<String, Any> {
        return emptyMap()
    }

    /**
     * Create a notification message.
     */
    fun createNotification(method: String, params: Any? = null): String {
        val notification = JsonRpcNotification(
            method = method,
            params = params
        )
        return mapper.writeValueAsString(notification)
    }

    /**
     * Check if the connection is initialized.
     */
    fun isInitialized(): Boolean = initialized
}

// Custom exceptions
class MethodNotFoundException(val method: String) : Exception("Method not found: $method")
class InvalidParamsException(message: String) : Exception(message)
