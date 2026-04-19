package ai.platon.pulsar.agentic.mcp.server

import ai.platon.pulsar.agentic.model.TcEvaluate
import ai.platon.pulsar.agentic.model.ToolCallResult
import ai.platon.pulsar.agentic.model.ToolSpec
import ai.platon.pulsar.agentic.tools.AgentToolExecutor
import ai.platon.pulsar.agentic.tools.builtin.ToolExecutor
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StdioClientTransport
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.io.PipedInputStream
import java.io.PipedOutputStream

/**
 * End-to-end tests for [Browser4MCPServer].
 *
 * These tests exercise the **full MCP protocol stack** by connecting a real MCP client
 * to a [Browser4MCPServer] (constructed from a mocked [AgentToolExecutor]) over in-process
 * STDIO pipes.  Unlike the unit tests in [Browser4MCPServerTest] (which call tool handlers
 * directly), these tests go through:
 *
 * 1. JSON-RPC initialization handshake (MCP `initialize` / `initialized`)
 * 2. `tools/list` request → response
 * 3. `tools/call` request → response (success and error cases)
 *
 * The [AgentToolExecutor] is mocked: executor specs drive tool registration, and
 * [AgentToolExecutor.execute] is mocked to simulate tool execution results.
 *
 * ## Transport
 * Two `PipedInputStream`/`PipedOutputStream` pairs create a bidirectional channel:
 * - **c2s** (client-to-server): client writes, server reads
 * - **s2c** (server-to-client): server writes, client reads
 *
 * The server runs in a background coroutine; the client connects synchronously before
 * each test.
 */
@Tag("E2ETest")
@Tag("mcp")
@DisplayName("Browser4MCPServer E2E (full MCP protocol, AgentToolManager-based)")
class Browser4MCPServerE2ETest {

    private lateinit var toolManager: AgentToolExecutor
    private lateinit var driverExecutor: ToolExecutor
    private lateinit var mcpServer: Browser4MCPServer
    private lateinit var client: Client
    private lateinit var serverJob: Job
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @BeforeEach
    fun setUp() = runBlocking {
        // Set up driver executor with a representative set of tool specs
        driverExecutor = mockk(relaxed = true)
        every { driverExecutor.domain } returns "tab"
        every { driverExecutor.getToolSpecs() } returns mapOf(
            "navigate" to ToolSpec("tab", "navigate",
                listOf(ToolSpec.Arg("url", "String", null)), "Unit", "Navigate the browser to a URL."),
            "reload" to ToolSpec("tab", "reload",
                emptyList(), "Unit", "Reload the current page."),
            "goBack" to ToolSpec("tab", "goBack",
                emptyList(), "Unit", "Navigate back."),
            "goForward" to ToolSpec("tab", "goForward",
                emptyList(), "Unit", "Navigate forward."),
            "click" to ToolSpec("tab", "click",
                listOf(ToolSpec.Arg("selector", "String", null)), "Unit", "Click an element."),
            "fill" to ToolSpec("tab", "fill",
                listOf(ToolSpec.Arg("selector", "String", null), ToolSpec.Arg("text", "String", null)),
                "Unit", "Fill an input."),
            "getText" to ToolSpec("tab", "getText",
                listOf(ToolSpec.Arg("selector", "String", null)), "String?", "Get element text."),
            "evaluate" to ToolSpec("tab", "evaluate",
                listOf(ToolSpec.Arg("expression", "String", null)), "Any?", "Evaluate JavaScript."),
            "waitForSelector" to ToolSpec("tab", "waitForSelector",
                listOf(ToolSpec.Arg("selector", "String", null)), "Unit", "Wait for a selector."),
            "currentUrl" to ToolSpec("tab", "currentUrl",
                emptyList(), "String", "Return the current URL."),
        )

        toolManager = mockk(relaxed = true)
        every { toolManager.concreteExecutors } returns listOf(driverExecutor)

        mcpServer = Browser4MCPServer(
            toolManager = toolManager,
            serverInfo = Implementation(name = "browser4-e2e-test", version = "1.0.0")
        )

        // ---- in-process bidirectional pipe ----
        val c2sIn = PipedInputStream()
        val c2sOut = PipedOutputStream(c2sIn)
        val s2cIn = PipedInputStream()
        val s2cOut = PipedOutputStream(s2cIn)

        // Start the server in a background coroutine
        val serverTransport = StdioServerTransport(
            inputStream = c2sIn.asSource().buffered(),
            outputStream = s2cOut.asSink().buffered()
        )
        serverJob = scope.launch {
            mcpServer.server.connect(serverTransport)
        }

        // Connect the MCP client
        val clientTransport = StdioClientTransport(
            input = s2cIn.asSource().buffered(),
            output = c2sOut.asSink().buffered()
        )
        client = Client(clientInfo = Implementation(name = "test-mcp-client", version = "1.0.0"))
        client.connect(clientTransport)
    }

    @AfterEach
    fun tearDown() = runBlocking {
        runCatching { client.close() }
        runCatching { mcpServer.server.close() }
        serverJob.cancel()
        scope.cancel()
    }

    // -------------------------------------------------------------------------
    // Tool listing tests
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("listTools returns all tools registered from AgentToolManager executors")
    fun listToolsReturnsAllRegisteredTools() = runBlocking {
        val result = client.listTools()
        assertNotNull(result)
        val expected = driverExecutor.getToolSpecs().size
        assertEquals(expected, result.tools.size,
            "Expected $expected tools, got: ${result.tools.map { it.name }}")
    }

    @Test
    @DisplayName("listTools includes driver navigation tools with snake_case names")
    fun listToolsIncludesNavigationTools() = runBlocking {
        val names = client.listTools().tools.map { it.name }.toSet()
        val expected = setOf("navigate", "go_back", "go_forward", "reload", "current_url")
        assertTrue(names.containsAll(expected),
            "Missing navigation tools: ${expected - names}")
    }

    @Test
    @DisplayName("listTools includes element interaction tools")
    fun listToolsIncludesInteractionTools() = runBlocking {
        val names = client.listTools().tools.map { it.name }.toSet()
        val expected = setOf("click", "fill", "get_text", "evaluate", "wait_for_selector")
        assertTrue(names.containsAll(expected),
            "Missing interaction tools: ${expected - names}")
    }

    @Test
    @DisplayName("each tool has a non-blank description and an inputSchema")
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
    // Successful tool execution tests
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("navigate succeeds and returns result over full MCP protocol")
    fun navigateSucceedsViaMCP() = runBlocking {
        coEvery { toolManager.execute(any()) } returns toolCallResult(value = "Navigated to https://example.com")

        val result = client.callTool("navigate", mapOf("url" to "https://example.com"))
        assertFalse(result.isError == true)
        val text = (result.content.firstOrNull() as? TextContent)?.text
        assertTrue(text?.contains("https://example.com") == true,
            "Expected URL in result, got: $text")
    }

    @Test
    @DisplayName("current_url returns the URL result from AgentToolManager over MCP")
    fun currentUrlReturnsDriverUrlViaMCP() = runBlocking {
        coEvery { toolManager.execute(any()) } returns toolCallResult(value = "https://example.com/page")

        val result = client.callTool("current_url", emptyMap())
        assertFalse(result.isError == true)
        val text = (result.content.firstOrNull() as? TextContent)?.text
        assertEquals("https://example.com/page", text)
    }

    @Test
    @DisplayName("click succeeds and returns result over MCP")
    fun clickSucceedsViaMCP() = runBlocking {
        coEvery { toolManager.execute(any()) } returns toolCallResult(value = "Clicked #submit-btn")

        val result = client.callTool("click", mapOf("selector" to "#submit-btn"))
        assertFalse(result.isError == true)
        val text = (result.content.firstOrNull() as? TextContent)?.text
        assertTrue(text?.contains("#submit-btn") == true)
    }

    @Test
    @DisplayName("get_text returns element text over MCP")
    fun getTextReturnsElementTextViaMCP() = runBlocking {
        coEvery { toolManager.execute(any()) } returns toolCallResult(value = "Welcome")

        val result = client.callTool("get_text", mapOf("selector" to "h1"))
        assertFalse(result.isError == true)
        val text = (result.content.firstOrNull() as? TextContent)?.text
        assertEquals("Welcome", text)
    }

    @Test
    @DisplayName("evaluate returns JavaScript result over MCP")
    fun evaluateReturnsJsResultViaMCP() = runBlocking {
        coEvery { toolManager.execute(any()) } returns toolCallResult(value = "My Page Title")

        val result = client.callTool("evaluate", mapOf("expression" to "document.title"))
        assertFalse(result.isError == true)
        val text = (result.content.firstOrNull() as? TextContent)?.text
        assertEquals("My Page Title", text)
    }

    @Test
    @DisplayName("reload succeeds over MCP")
    fun reloadSucceedsViaMCP() = runBlocking {
        coEvery { toolManager.execute(any()) } returns toolCallResult(value = "Page reloaded")

        val result = client.callTool("reload", emptyMap())
        assertFalse(result.isError == true)
    }

    // -------------------------------------------------------------------------
    // Error handling tests
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("AgentToolManager exception propagates as isError=true over MCP")
    fun managerExceptionPropagatesAsErrorViaMCP() = runBlocking {
        coEvery { toolManager.execute(any()) } throws RuntimeException("CDP disconnected")

        val result = client.callTool("navigate", mapOf("url" to "https://example.com"))
        assertTrue(result.isError == true,
            "Expected isError=true when AgentToolManager throws")
        val text = (result.content.firstOrNull() as? TextContent)?.text
        assertTrue(text?.contains("CDP disconnected") == true,
            "Expected error message in result, got: $text")
    }

    @Test
    @DisplayName("TcEvaluate with exception propagates as isError=true over MCP")
    fun evaluateExceptionPropagatesAsErrorViaMCP() = runBlocking {
        val evaluate = TcEvaluate(expression = "evaluate(expression=\"bad\")", cause = RuntimeException("SyntaxError"))
        coEvery { toolManager.execute(any()) } returns toolCallResult(evaluate = evaluate)

        val result = client.callTool("evaluate", mapOf("expression" to "{{bad"))
        assertTrue(result.isError == true)
    }

    // -------------------------------------------------------------------------
    // Multiple sequential calls
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("multiple sequential tool calls succeed over a single MCP connection")
    fun multipleSequentialCallsSucceedViaMCP() = runBlocking {
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
    // Tool registration validated via AgentToolManager routing
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("all tool calls route through AgentToolManager.execute with correct domain and method")
    fun allToolCallsRouteThroughManager() = runBlocking {
        coEvery { toolManager.execute(any()) } returns toolCallResult(value = "ok")

        // Call navigate - should route to domain=driver, method=navigate
        client.callTool("navigate", mapOf("url" to "https://example.com"))

        // Call fill - should route to domain=driver, method=fill
        client.callTool("fill", mapOf("selector" to "input", "text" to "hello"))

        // Verify all calls went through execute
        io.mockk.coVerify(exactly = 2) { toolManager.execute(any()) }
    }

    private fun toolCallResult(value: Any? = null, evaluate: TcEvaluate? = null): ToolCallResult {
        val resolvedEvaluate = evaluate ?: TcEvaluate(value = value)
        return ToolCallResult(
            evaluate = resolvedEvaluate,
            message = resolvedEvaluate.exception?.message,
        )
    }
}
