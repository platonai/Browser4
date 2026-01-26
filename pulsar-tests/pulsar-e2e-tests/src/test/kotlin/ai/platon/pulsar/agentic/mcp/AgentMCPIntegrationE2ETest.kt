package ai.platon.pulsar.agentic.mcp

import ai.platon.pulsar.agentic.model.TcEvaluate
import ai.platon.pulsar.agentic.model.TcException
import ai.platon.pulsar.agentic.model.ToolCall
import ai.platon.pulsar.agentic.model.ToolSpec
import ai.platon.pulsar.agentic.tools.CustomToolRegistry
import ai.platon.pulsar.agentic.tools.builtin.ToolExecutor
import ai.platon.pulsar.common.brief
import ai.platon.pulsar.common.getLogger
import ai.platon.pulsar.test.mcp.MockMCPServer
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.*
import kotlin.reflect.KClass
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * End-to-end tests for Agent MCP integration.
 *
 * This test class validates the complete Agent-MCP integration flow:
 * 1. Agent loads MCP via a TestMCPToolExecutor (simulating MCPPluginRegistry)
 * 2. MCP tools are registered in CustomToolRegistry
 * 3. Agent calls MCP tools through the tool executor
 * 4. Results are properly returned through the tool execution pipeline
 *
 * These tests use MockMCPServer directly (not via HTTP) to validate the full
 * Agent tool execution pipeline without requiring a running Spring Boot server.
 *
 * Test coverage:
 * - MCPToolExecutor integration with CustomToolRegistry
 * - Tool discovery from MCP tools
 * - Tool execution through Agent's tool execution pipeline
 * - Error handling for invalid tool calls
 * - Tool registration and lifecycle management
 */
@Tag("E2ETest")
@Tag("mcp")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AgentMCPIntegrationE2ETest {

    private lateinit var mockMCPServer: MockMCPServer
    private val serverName = "agent-test-mcp-server"

    @BeforeAll
    fun setUpAll() {
        // Create MockMCPServer directly
        mockMCPServer = MockMCPServer(
            serverName = serverName,
            serverVersion = "1.0.0"
        )
        assertTrue(mockMCPServer.isRunning(), "MockMCPServer should be running")
    }

    @AfterAll
    fun tearDownAll() {
        mockMCPServer.close()
    }

    @AfterEach
    fun tearDown() {
        // Clean up the global CustomToolRegistry to avoid test interference
        CustomToolRegistry.instance.getAllDomains()
            .filter { it.startsWith("mcp.") }
            .forEach { CustomToolRegistry.instance.unregister(it) }
    }

    /**
     * Creates a TestMCPToolExecutor that simulates MCP tool execution
     * by delegating to the MockMCPServer's direct API.
     */
    private fun createTestMCPToolExecutor(): TestMCPToolExecutor {
        return TestMCPToolExecutor(serverName, mockMCPServer)
    }

    // ========== MCPToolExecutor Registration Tests ==========

    @Test
    @DisplayName("test MCPToolExecutor registers in CustomToolRegistry")
    fun testMCPToolExecutorRegistersInCustomToolRegistry(): Unit = runBlocking {
        // Given: A test MCP tool executor
        val toolExecutor = createTestMCPToolExecutor()

        // When: We register the executor in CustomToolRegistry
        CustomToolRegistry.instance.register(toolExecutor)

        // Then: The executor should be registered
        val domain = "mcp.$serverName"
        assertTrue(CustomToolRegistry.instance.contains(domain), "CustomToolRegistry should contain MCP domain")
        assertEquals(domain, toolExecutor.domain)
    }

    @Test
    @DisplayName("test MCPToolExecutor discovers tools from MCP server")
    fun testMCPToolExecutorDiscoversToolsFromMCPServer(): Unit = runBlocking {
        // Given: A test MCP tool executor
        val toolExecutor = createTestMCPToolExecutor()

        // Then: Tools should be discovered
        val availableTools = toolExecutor.getAvailableToolNames()
        assertEquals(3, availableTools.size, "Should discover 3 tools from test server")
        assertTrue(availableTools.containsAll(listOf("echo", "add", "multiply")))
    }

    // ========== Agent Tool Execution Tests ==========

    @Test
    @DisplayName("test Agent executes echo tool via MCPToolExecutor")
    fun testAgentExecutesEchoToolViaMCPToolExecutor(): Unit = runBlocking {
        // Given: A registered MCP tool executor
        val toolExecutor = createTestMCPToolExecutor()
        CustomToolRegistry.instance.register(toolExecutor)

        // When: We execute the echo tool
        val toolCall = ToolCall(
            domain = "mcp.$serverName",
            method = "echo",
            arguments = mutableMapOf("message" to "Hello from Agent E2E test!")
        )

        val result = toolExecutor.callFunctionOn(toolCall, Any())

        // Then: The result should contain the echoed message
        assertNotNull(result.value, "Result value should not be null")
        assertTrue(
            result.value.toString().contains("Hello from Agent E2E test!"),
            "Result should contain the input message, but got: ${result.value}"
        )
        Assertions.assertNull(result.exception, "There should be no exception")
    }

    @Test
    @DisplayName("test Agent executes add tool via MCPToolExecutor")
    fun testAgentExecutesAddToolViaMCPToolExecutor(): Unit = runBlocking {
        // Given: A registered MCP tool executor
        val toolExecutor = createTestMCPToolExecutor()

        // When: We execute the add tool
        val toolCall = ToolCall(
            domain = "mcp.$serverName",
            method = "add",
            arguments = mutableMapOf("a" to 25, "b" to 17)
        )

        val result = toolExecutor.callFunctionOn(toolCall, Any())

        // Then: The result should contain the sum
        assertNotNull(result.value)
        assertTrue(result.value.toString().contains("42"), "Sum should be 42, but got: ${result.value}")
        Assertions.assertNull(result.exception)
    }

    @Test
    @DisplayName("test Agent executes multiply tool via MCPToolExecutor")
    fun testAgentExecutesMultiplyToolViaMCPToolExecutor(): Unit = runBlocking {
        // Given: A registered MCP tool executor
        val toolExecutor = createTestMCPToolExecutor()

        // When: We execute the multiply tool
        val toolCall = ToolCall(
            domain = "mcp.$serverName",
            method = "multiply",
            arguments = mutableMapOf("a" to 6, "b" to 7)
        )

        val result = toolExecutor.callFunctionOn(toolCall, Any())

        // Then: The result should contain the product
        assertNotNull(result.value)
        assertTrue(result.value.toString().contains("42"), "Product should be 42, but got: ${result.value}")
        Assertions.assertNull(result.exception)
    }

    // ========== CustomToolRegistry Integration Tests ==========

    @Test
    @DisplayName("test CustomToolRegistry provides MCP tools to Agent")
    fun testCustomToolRegistryProvidesMCPToolsToAgent(): Unit = runBlocking {
        // Given: A registered MCP tool executor
        val toolExecutor = createTestMCPToolExecutor()
        CustomToolRegistry.instance.register(toolExecutor)

        val domain = "mcp.$serverName"

        // When: We retrieve the tool executor from CustomToolRegistry
        val customExecutor = CustomToolRegistry.instance.get(domain)

        // Then: The executor should be available and functional
        assertNotNull(customExecutor, "CustomToolRegistry should provide the MCP tool executor")
        assertEquals(domain, customExecutor.domain)

        // And: We can execute tools through it
        val toolCall = ToolCall(
            domain = domain,
            method = "echo",
            arguments = mutableMapOf("message" to "Via CustomToolRegistry")
        )

        val result = customExecutor.callFunctionOn(toolCall, Any())
        assertNotNull(result.value)
        assertTrue(result.value.toString().contains("Via CustomToolRegistry"))
    }

    @Test
    @DisplayName("test Agent can list all registered MCP tools")
    fun testAgentCanListAllRegisteredMCPTools(): Unit = runBlocking {
        // Given: A registered MCP tool executor
        val toolExecutor = createTestMCPToolExecutor()
        CustomToolRegistry.instance.register(toolExecutor)

        // When: We list all custom tools
        val allDomains = CustomToolRegistry.instance.getAllDomains()

        // Then: MCP domain should be listed
        assertTrue(
            allDomains.any { it.startsWith("mcp.") },
            "CustomToolRegistry should contain MCP domains"
        )
    }

    // ========== Error Handling Tests ==========

    @Test
    @DisplayName("test Agent handles missing required argument gracefully")
    fun testAgentHandlesMissingRequiredArgumentGracefully(): Unit = runBlocking {
        // Given: A registered MCP tool executor
        val toolExecutor = createTestMCPToolExecutor()

        // When: We execute a tool with missing required argument
        val toolCall = ToolCall(
            domain = "mcp.$serverName",
            method = "echo",
            arguments = mutableMapOf()  // Missing 'message' argument
        )

        val result = toolExecutor.callFunctionOn(toolCall, Any())

        // Then: The result should indicate an error
        // The MCP server returns an error response which is captured in either:
        // - result.exception (if tool execution failed)
        // - result.value containing error message (if server returned error response)
        val hasException = result.exception != null
        val hasErrorInValue = result.value?.toString()?.let { value ->
            value.contains("Error", ignoreCase = true) || value.contains("required", ignoreCase = true)
        } ?: false

        assertTrue(hasException || hasErrorInValue, "Should indicate error for missing argument")
    }

    @Test
    @DisplayName("test Agent handles non-existent tool gracefully")
    fun testAgentHandlesNonExistentToolGracefully(): Unit = runBlocking {
        // Given: A registered MCP tool executor
        val toolExecutor = createTestMCPToolExecutor()

        // When: We try to execute a non-existent tool
        val toolCall = ToolCall(
            domain = "mcp.$serverName",
            method = "non_existent_tool",
            arguments = mutableMapOf()
        )

        val result = toolExecutor.callFunctionOn(toolCall, Any())

        // Then: The result should indicate an error
        assertNotNull(result.exception, "Should have an exception for non-existent tool")
    }

    @Test
    @DisplayName("test Agent handles disconnected client gracefully")
    fun testAgentHandlesDisconnectedClientGracefully(): Unit = runBlocking {
        // Given: A disconnected MCP tool executor
        val toolExecutor = createTestMCPToolExecutor()
        toolExecutor.disconnect()

        // When: We try to execute a tool
        val toolCall = ToolCall(
            domain = "mcp.$serverName",
            method = "echo",
            arguments = mutableMapOf("message" to "test")
        )

        val result = toolExecutor.callFunctionOn(toolCall, Any())

        // Then: The result should indicate the client is not connected
        assertNotNull(result.exception, "Should have an exception for disconnected client")
        assertTrue(
            result.exception?.cause?.message?.contains("not connected", ignoreCase = true) == true,
            "Exception should mention client not connected"
        )
    }

    // ========== Lifecycle Management Tests ==========

    @Test
    @DisplayName("test CustomToolRegistry unregisters MCP tools correctly")
    fun testCustomToolRegistryUnregistersMCPToolsCorrectly(): Unit = runBlocking {
        // Given: A registered MCP tool executor
        val toolExecutor = createTestMCPToolExecutor()
        val domain = "mcp.$serverName"
        CustomToolRegistry.instance.register(toolExecutor)
        assertTrue(CustomToolRegistry.instance.contains(domain))

        // When: We unregister the tools
        val unregistered = CustomToolRegistry.instance.unregister(domain)

        // Then: The tools should be unregistered
        assertTrue(unregistered, "Should successfully unregister MCP tools")
        Assertions.assertFalse(
            CustomToolRegistry.instance.contains(domain),
            "CustomToolRegistry should no longer contain the MCP domain"
        )
    }

    // ========== Sequential Operations Tests ==========

    @Test
    @DisplayName("test Agent executes multiple MCP tools sequentially")
    fun testAgentExecutesMultipleMCPToolsSequentially(): Unit = runBlocking {
        // Given: A registered MCP tool executor
        val toolExecutor = createTestMCPToolExecutor()

        // When: We execute multiple tools sequentially

        // First: echo
        var toolCall = ToolCall(
            domain = "mcp.$serverName",
            method = "echo",
            arguments = mutableMapOf("message" to "First call")
        )
        var result = toolExecutor.callFunctionOn(toolCall, Any())
        assertNotNull(result.value)
        assertTrue(result.value.toString().contains("First call"))

        // Second: add
        toolCall = ToolCall(
            domain = "mcp.$serverName",
            method = "add",
            arguments = mutableMapOf("a" to 10, "b" to 20)
        )
        result = toolExecutor.callFunctionOn(toolCall, Any())
        assertNotNull(result.value)
        assertTrue(result.value.toString().contains("30"))

        // Third: multiply
        toolCall = ToolCall(
            domain = "mcp.$serverName",
            method = "multiply",
            arguments = mutableMapOf("a" to 5, "b" to 5)
        )
        result = toolExecutor.callFunctionOn(toolCall, Any())
        assertNotNull(result.value)
        assertTrue(result.value.toString().contains("25"))

        // Then: Server should still be functional
        assertTrue(mockMCPServer.isRunning())
        assertTrue(toolExecutor.isConnected())
    }

    @Test
    @DisplayName("test Agent recovers from error and continues with valid calls")
    fun testAgentRecoversFromErrorAndContinuesWithValidCalls(): Unit = runBlocking {
        // Given: A registered MCP tool executor
        val toolExecutor = createTestMCPToolExecutor()

        // When: First call succeeds
        var toolCall = ToolCall(
            domain = "mcp.$serverName",
            method = "echo",
            arguments = mutableMapOf("message" to "Success")
        )
        var result = toolExecutor.callFunctionOn(toolCall, Any())
        Assertions.assertNull(result.exception)

        // Second call fails (missing argument)
        toolCall = ToolCall(
            domain = "mcp.$serverName",
            method = "add",
            arguments = mutableMapOf("a" to 5)  // Missing 'b'
        )
        result = toolExecutor.callFunctionOn(toolCall, Any())
        // Error is handled gracefully

        // Third call succeeds
        toolCall = ToolCall(
            domain = "mcp.$serverName",
            method = "multiply",
            arguments = mutableMapOf("a" to 4, "b" to 6)
        )
        result = toolExecutor.callFunctionOn(toolCall, Any())
        assertNotNull(result.value)
        assertTrue(result.value.toString().contains("24"))

        // Then: Server and client should be stable
        assertTrue(mockMCPServer.isRunning())
        assertTrue(toolExecutor.isConnected())
    }

    // ========== Help Documentation Tests ==========

    @Test
    @DisplayName("test MCPToolExecutor provides help for tools")
    fun testMCPToolExecutorProvidesHelpForTools(): Unit = runBlocking {
        // Given: A registered MCP tool executor
        val toolExecutor = createTestMCPToolExecutor()

        // When: We request help for a tool
        val echoHelp = toolExecutor.help("echo")
        val addHelp = toolExecutor.help("add")

        // Then: Help should be provided
        assertTrue(echoHelp.isNotEmpty(), "Echo tool should have help documentation")
        assertTrue(addHelp.isNotEmpty(), "Add tool should have help documentation")

        // And: General help should list all tools
        val generalHelp = toolExecutor.help()
        assertTrue(generalHelp.isNotEmpty(), "General help should be available")
    }

    @Test
    @DisplayName("test MCPToolExecutor provides help for non-existent tool")
    fun testMCPToolExecutorProvidesHelpForNonExistentTool(): Unit = runBlocking {
        // Given: A registered MCP tool executor
        val toolExecutor = createTestMCPToolExecutor()

        // When: We request help for a non-existent tool
        val help = toolExecutor.help("non_existent_tool")

        // Then: Help should indicate the tool is not found
        assertTrue(
            help.contains("not found", ignoreCase = true),
            "Help should indicate tool not found"
        )
    }
}

/**
 * Test implementation of ToolExecutor that simulates MCP tool execution
 * by delegating to MockMCPServer.
 *
 * This allows testing the Agent → ToolExecutor → MCP integration without
 * requiring a real MCP transport (SSE, WebSocket, or STDIO), since MockMCPServer
 * only implements HTTP/JSON.
 */
class TestMCPToolExecutor(
    private val serverName: String,
    private val mockServer: MockMCPServer
) : ToolExecutor {

    private val logger = getLogger(this)
    private val objectMapper = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()
    private var connected = true

    override val domain: String
        get() = "mcp.$serverName"

    override val targetClass: KClass<*>
        get() = MockMCPServer::class

    /**
     * The list of tools available from the MCP server.
     */
    val availableTools: List<Tool> by lazy {
        loadToolsFromMockServer()
    }

    private val toolSpecs: Map<String, ToolSpec> by lazy {
        buildToolSpecs()
    }

    private fun loadToolsFromMockServer(): List<Tool> {
        val toolsResponse = mockServer.listTools()
        @Suppress("UNCHECKED_CAST")
        val toolsList = toolsResponse["tools"] as List<Map<String, Any>>

        return toolsList.map { toolMap ->
            val name = toolMap["name"] as String
            val description = toolMap["description"] as String
            @Suppress("UNCHECKED_CAST")
            val inputSchemaMap = toolMap["inputSchema"] as Map<String, Any>

            Tool(
                name = name,
                description = description,
                inputSchema = convertToToolSchema(inputSchemaMap)
            )
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun convertToToolSchema(schemaMap: Map<String, Any>): ToolSchema {
        val properties = (schemaMap["properties"] as? Map<String, Any>)?.let { props ->
            buildJsonObject {
                props.forEach { (key, value) ->
                    val propMap = value as Map<String, Any>
                    put(key, buildJsonObject {
                        propMap.forEach { (propKey, propValue) ->
                            put(propKey, JsonPrimitive(propValue.toString()))
                        }
                    })
                }
            }
        }

        val required = (schemaMap["required"] as? List<String>)

        return ToolSchema(
            properties = properties,
            required = required
        )
    }

    private fun buildToolSpecs(): Map<String, ToolSpec> {
        return availableTools.associate { tool ->
            val spec = convertMCPToolToSpec(tool)
            tool.name to spec
        }
    }

    private fun convertMCPToolToSpec(tool: Tool): ToolSpec {
        val args = extractArgumentsFromSchema(tool.inputSchema)

        return ToolSpec(
            domain = domain,
            method = tool.name,
            arguments = args,
            returnType = "Any?",
            description = tool.description
        )
    }

    private fun extractArgumentsFromSchema(inputSchema: ToolSchema?): List<ToolSpec.Arg> {
        val schema = inputSchema ?: return emptyList()

        val properties = schema.properties ?: return emptyList()
        val required = schema.required?.toSet().orEmpty()

        return properties.entries.map { (name, element) ->
            val schemaObj = element as? kotlinx.serialization.json.JsonObject
            val type = schemaObj?.get("type")?.let { (it as? JsonPrimitive)?.content } ?: "Any"
            val isRequired = name in required

            ToolSpec.Arg(
                name = name,
                type = mapJsonTypeToKotlinType(type),
                defaultValue = if (isRequired) null else "null"
            )
        }
    }

    private fun mapJsonTypeToKotlinType(jsonType: String): String {
        return when (jsonType.lowercase()) {
            "string" -> "String"
            "number" -> "Double"
            "integer" -> "Int"
            "boolean" -> "Boolean"
            "array" -> "List<Any>"
            "object" -> "Map<String, Any>"
            else -> "Any"
        }
    }

    override suspend fun callFunctionOn(tc: ToolCall, target: Any): TcEvaluate {
        val toolName = tc.method
        val args = tc.arguments
        val pseudoExpression = tc.pseudoExpression

        if (!connected) {
            val error = "MCP client for server '$serverName' is not connected"
            logger.warn(error)
            return TcEvaluate(
                value = null,
                className = "null",
                expression = pseudoExpression,
                exception = TcException(pseudoExpression, IllegalStateException(error))
            )
        }

        return try {
            // Call the tool on the mock MCP server
            val argumentsNode = objectMapper.valueToTree<com.fasterxml.jackson.databind.node.ObjectNode>(args)
            val request = objectMapper.createObjectNode().apply {
                put("name", toolName)
                set<com.fasterxml.jackson.databind.JsonNode>("arguments", argumentsNode)
            }

            val response = mockServer.callTool(request)

            @Suppress("UNCHECKED_CAST")
            val content = response["content"] as List<Map<String, Any>>
            val isError = response["isError"] as? Boolean ?: false

            val resultValue = content.joinToString("\n") { item ->
                item["text"] as? String ?: item.toString()
            }

            if (isError) {
                TcEvaluate(
                    value = resultValue,
                    className = "String",
                    expression = pseudoExpression,
                    exception = TcException(pseudoExpression, RuntimeException(resultValue))
                )
            } else {
                TcEvaluate(
                    value = resultValue,
                    className = "String",
                    expression = pseudoExpression
                )
            }
        } catch (e: Exception) {
            logger.warn("Error executing MCP tool '{}': {}", toolName, e.brief())
            val helpText = help(toolName)
            TcEvaluate(
                value = null,
                className = "null",
                expression = pseudoExpression,
                exception = TcException(pseudoExpression, e, helpText)
            )
        }
    }

    override fun help(): String {
        return toolSpecs.values.mapNotNull { spec ->
            spec.description?.let { "${spec.expression}\n  $it" }
        }.joinToString("\n\n")
    }

    override fun help(method: String): String {
        val spec = toolSpecs[method] ?: return "Tool '$method' not found in MCP server '$serverName'"
        return buildString {
            spec.description?.let { appendLine(it) }
            appendLine(spec.expression)
        }.trim()
    }

    /**
     * Gets the list of available tool names.
     */
    fun getAvailableToolNames(): List<String> {
        return availableTools.map { it.name }
    }

    /**
     * Checks if the executor is connected.
     */
    fun isConnected(): Boolean = connected

    /**
     * Disconnects the executor (for testing purposes).
     */
    fun disconnect() {
        connected = false
    }
}
