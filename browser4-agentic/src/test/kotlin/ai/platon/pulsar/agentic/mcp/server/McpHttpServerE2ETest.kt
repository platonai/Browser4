package ai.platon.pulsar.agentic.mcp.server

import ai.platon.pulsar.agentic.model.TcEvaluate
import ai.platon.pulsar.agentic.model.ToolCallResult
import ai.platon.pulsar.agentic.model.ToolSpec
import ai.platon.pulsar.agentic.tools.AgentToolManager
import ai.platon.pulsar.agentic.tools.builtin.ToolExecutor
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.sse.SSE
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.SseClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.net.ServerSocket

/**
 * End-to-end tests for [McpHttpServer] over the MCP Streamable HTTP protocol.
 *
 * These tests:
 * 1. Start an [McpHttpServer] on a random available port with a mocked
 *    [AgentToolManager] (no real browser).
 * 2. Connect an MCP [Client] via [StreamableHttpClientTransport] over a
 *    Ktor CIO HTTP client.
 * 3. Verify `tools/list` and `tools/call` through the full protocol stack:
 *    HTTP → SSE → JSON-RPC → [Browser4MCPServer] → [AgentToolManager].
 */
@Tag("mcp")
@DisplayName("McpHttpServer E2E (full MCP Streamable HTTP protocol)")
class McpHttpServerE2ETest {

    private lateinit var toolManager: AgentToolManager
    private lateinit var mcpHttpServer: McpHttpServer
    private lateinit var client: Client
    private lateinit var httpClient: HttpClient

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var testPort: Int = 0

    @BeforeEach
    fun setUp() {
        runBlocking {

        // Find an available port
        testPort = ServerSocket(0).use { it.localPort }

        // Set up driver executor with tool specs (same pattern as Browser4MCPServerE2ETest)
        val driverExecutor: ToolExecutor = mockk(relaxed = true)
        every { driverExecutor.domain } returns "tab"
        every { driverExecutor.getToolSpecs() } returns mapOf(
            "navigate" to ToolSpec("tab", "navigate",
                listOf(ToolSpec.Arg("url", "String", null)), "Unit",
                "Navigate the browser to a URL."),
            "reload" to ToolSpec("tab", "reload",
                emptyList(), "Unit", "Reload the current page."),
            "goBack" to ToolSpec("tab", "goBack",
                emptyList(), "Unit", "Navigate back."),
            "goForward" to ToolSpec("tab", "goForward",
                emptyList(), "Unit", "Navigate forward."),
            "click" to ToolSpec("tab", "click",
                listOf(ToolSpec.Arg("selector", "String", null)), "Unit",
                "Click an element."),
            "fill" to ToolSpec("tab", "fill",
                listOf(ToolSpec.Arg("selector", "String", null), ToolSpec.Arg("text", "String", null)),
                "Unit", "Fill an input."),
            "getText" to ToolSpec("tab", "getText",
                listOf(ToolSpec.Arg("selector", "String", null)), "String?",
                "Get element text."),
            "evaluate" to ToolSpec("tab", "evaluate",
                listOf(ToolSpec.Arg("expression", "String", null)), "Any?",
                "Evaluate JavaScript."),
        )

        toolManager = mockk(relaxed = true)
        every { toolManager.registeredExecutors } returns listOf(driverExecutor).associateBy { it.domain }

        // Start the MCP HTTP server
        mcpHttpServer = McpHttpServer(
            toolManager = toolManager,
            port = testPort,
            serverInfo = Implementation(name = "browser4-e2e-http-test", version = "1.0.0"),
        )
        mcpHttpServer.start()

        // Connect the MCP client via Streamable HTTP
        httpClient = HttpClient(CIO) {
            install(SSE)
        }
        val transport = SseClientTransport(
            httpClient,
            "http://localhost:$testPort/mcp/sse",
        )
        client = Client(clientInfo = Implementation(name = "test-mcp-client", version = "1.0.0"))
        client.connect(transport)

        } // runBlocking
    }

    @AfterEach
    fun tearDown() {
        runBlocking {
            runCatching { client.close() }
            runCatching { httpClient.close() }
            runCatching { mcpHttpServer.stop() }
        }
    }

    // -------------------------------------------------------------------------
    // Tool listing
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("listTools returns all registered tools over HTTP SSE")
    fun listToolsReturnsAllRegisteredTools() = runBlocking {
        val result = client.listTools()
        assertNotNull(result)
        assertEquals(8, result.tools.size,
            "Expected 8 tools, got: ${result.tools.map { it.name }}")
    }

    @Test
    @DisplayName("listTools returns snake_case tool names over HTTP SSE")
    fun listToolsReturnsCorrectNames() = runBlocking {
        val names = client.listTools().tools.map { it.name }.toSet()
        val expected = setOf("navigate", "go_back", "go_forward", "reload", "click", "fill", "get_text", "evaluate")
        assertTrue(names.containsAll(expected),
            "Missing tools: ${expected - names}")
    }

    @Test
    @DisplayName("each tool has a non-blank description and inputSchema over HTTP")
    fun eachToolHasDescriptionAndSchema() = runBlocking {
        val tools = client.listTools().tools
        tools.forEach { tool ->
            assertFalse(tool.description.isNullOrBlank(),
                "Tool '${tool.name}' has a blank description")
            assertNotNull(tool.inputSchema,
                "Tool '${tool.name}' has no inputSchema")
        }
    }

    // -------------------------------------------------------------------------
    // Tool execution
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("navigate succeeds over HTTP SSE")
    fun navigateSucceedsOverHttp() = runBlocking {
        coEvery { toolManager.execute(any()) } returns toolCallResult(value = "Navigated to https://example.com")

        val result = client.callTool("navigate", mapOf("url" to "https://example.com"))
        assertFalse(result.isError == true)
        val text = (result.content.firstOrNull() as? TextContent)?.text
        assertTrue(text?.contains("https://example.com") == true,
            "Expected URL in result, got: $text")
    }

    @Test
    @DisplayName("click succeeds over HTTP SSE")
    fun clickSucceedsOverHttp() = runBlocking {
        coEvery { toolManager.execute(any()) } returns toolCallResult(value = "Clicked #submit-btn")

        val result = client.callTool("click", mapOf("selector" to "#submit-btn"))
        assertFalse(result.isError == true)
        val text = (result.content.firstOrNull() as? TextContent)?.text
        assertTrue(text?.contains("#submit-btn") == true)
    }

    @Test
    @DisplayName("get_text returns element text over HTTP SSE")
    fun getTextReturnsElementTextOverHttp() = runBlocking {
        coEvery { toolManager.execute(any()) } returns toolCallResult(value = "Welcome")

        val result = client.callTool("get_text", mapOf("selector" to "h1"))
        assertFalse(result.isError == true)
        val text = (result.content.firstOrNull() as? TextContent)?.text
        assertEquals("Welcome", text)
    }

    @Test
    @DisplayName("evaluate returns JavaScript result over HTTP SSE")
    fun evaluateReturnsJsResultOverHttp() = runBlocking {
        coEvery { toolManager.execute(any()) } returns toolCallResult(value = "42")

        val result = client.callTool("evaluate", mapOf("expression" to "1 + 1"))
        assertFalse(result.isError == true)
        val text = (result.content.firstOrNull() as? TextContent)?.text
        assertEquals("42", text)
    }

    // -------------------------------------------------------------------------
    // Error handling
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("AgentToolManager exception propagates as isError=true over HTTP SSE")
    fun managerExceptionPropagatesAsErrorOverHttp() = runBlocking {
        coEvery { toolManager.execute(any()) } throws RuntimeException("BrowserProtocol disconnected")

        val result = client.callTool("navigate", mapOf("url" to "https://example.com"))
        assertTrue(result.isError == true,
            "Expected isError=true when AgentToolManager throws")
        val text = (result.content.firstOrNull() as? TextContent)?.text
        assertTrue(text?.contains("BrowserProtocol disconnected") == true,
            "Expected error message in result, got: $text")
    }

    @Test
    @DisplayName("TcEvaluate with exception propagates as isError=true over HTTP SSE")
    fun evaluateExceptionPropagatesAsErrorOverHttp() = runBlocking {
        val evaluate = TcEvaluate(
            expression = "evaluate(expression=\"bad\")",
            cause = RuntimeException("SyntaxError"),
        )
        coEvery { toolManager.execute(any()) } returns toolCallResult(evaluate = evaluate)

        val result = client.callTool("evaluate", mapOf("expression" to "{{bad"))
        assertTrue(result.isError == true)
    }

    // -------------------------------------------------------------------------
    // Multiple calls / session reuse
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("multiple sequential tool calls succeed over a single HTTP SSE connection")
    fun multipleSequentialCallsSucceedOverHttp() = runBlocking {
        coEvery { toolManager.execute(match { it.method == "navigate" }) } returns toolCallResult(value = "navigated")
        coEvery { toolManager.execute(match { it.method == "getText" }) } returns toolCallResult(value = "headline")
        coEvery { toolManager.execute(match { it.method == "evaluate" }) } returns toolCallResult(value = "42")

        val nav = client.callTool("navigate", mapOf("url" to "https://example.com"))
        assertFalse(nav.isError == true, "navigate failed")

        val text = client.callTool("get_text", mapOf("selector" to "h1"))
        assertFalse(text.isError == true, "get_text failed")
        assertEquals("headline", (text.content.firstOrNull() as? TextContent)?.text)

        val js = client.callTool("evaluate", mapOf("expression" to "1 + 1"))
        assertFalse(js.isError == true, "evaluate failed")
        assertEquals("42", (js.content.firstOrNull() as? TextContent)?.text)
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun toolCallResult(value: Any? = null, evaluate: TcEvaluate? = null): ToolCallResult {
        val resolvedEvaluate = evaluate ?: TcEvaluate(value = value)
        return ToolCallResult(
            evaluate = resolvedEvaluate,
            message = resolvedEvaluate.exception?.message,
        )
    }
}
