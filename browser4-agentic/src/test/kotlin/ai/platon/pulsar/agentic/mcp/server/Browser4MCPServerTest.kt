package ai.platon.pulsar.agentic.mcp.server

import ai.platon.pulsar.agentic.model.TcEvaluate
import ai.platon.pulsar.agentic.model.ToolCallResult
import ai.platon.pulsar.agentic.model.ToolSpec
import ai.platon.pulsar.agentic.tools.AgentToolExecutor
import ai.platon.pulsar.agentic.tools.builtin.ToolExecutor
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Unit tests for [Browser4MCPServer].
 *
 * These tests verify that:
 * - All expected MCP tools are registered based on executor tool specs from [AgentToolExecutor].
 * - Each tool handler routes its call through [AgentToolExecutor.execute].
 * - Each tool returns a non-error result when the manager succeeds.
 * - Each tool returns an error result (isError = true) when the manager throws or returns an exception.
 */
@DisplayName("Browser4MCPServer")
class Browser4MCPServerTest {

    private lateinit var toolManager: AgentToolExecutor
    private lateinit var driverExecutor: ToolExecutor
    private lateinit var fsExecutor: ToolExecutor
    private lateinit var systemExecutor: ToolExecutor
    private lateinit var mcpServer: Browser4MCPServer

    @BeforeEach
    fun setUp() {
        driverExecutor = mockk(relaxed = true)
        every { driverExecutor.domain } returns "tab"
        every { driverExecutor.getToolSpecs() } returns mapOf(
            "navigate" to ToolSpec(
                domain = "tab", method = "navigate",
                arguments = listOf(ToolSpec.Arg("url", "String", null)),
                returnType = "Unit", description = "Navigate the browser to the given URL."
            ),
            "click" to ToolSpec(
                domain = "tab", method = "click",
                arguments = listOf(
                    ToolSpec.Arg("selector", "String", null),
                    ToolSpec.Arg("modifier", "String", "null")
                ),
                returnType = "Unit", description = "Click an element."
            ),
            "scrollToTop" to ToolSpec(
                domain = "tab", method = "scrollToTop",
                arguments = emptyList(),
                returnType = "Double", description = "Scroll to top."
            ),
            "getText" to ToolSpec(
                domain = "tab", method = "getText",
                arguments = listOf(ToolSpec.Arg("selector", "String", null)),
                returnType = "String?", description = "Get text of an element."
            ),
        )

        fsExecutor = mockk(relaxed = true)
        every { fsExecutor.domain } returns "fs"
        every { fsExecutor.getToolSpecs() } returns mapOf(
            "writeString" to ToolSpec(
                domain = "fs", method = "writeString",
                arguments = listOf(
                    ToolSpec.Arg("filename", "String", null),
                    ToolSpec.Arg("content", "String", null),
                ),
                returnType = "Unit", description = "Write a string to a file."
            ),
            "readString" to ToolSpec(
                domain = "fs", method = "readString",
                arguments = listOf(ToolSpec.Arg("filename", "String", null)),
                returnType = "String", description = "Read a file."
            ),
        )

        systemExecutor = mockk(relaxed = true)
        every { systemExecutor.domain } returns "system"
        every { systemExecutor.getToolSpecs() } returns mapOf(
            "help" to ToolSpec(
                domain = "system", method = "help",
                arguments = listOf(ToolSpec.Arg("domain", "String", null)),
                returnType = "String", description = "Return documentation for a tool domain."
            )
        )

        toolManager = mockk(relaxed = true)
        every { toolManager.concreteExecutors } returns listOf(driverExecutor, fsExecutor, systemExecutor)

        mcpServer = Browser4MCPServer(
            toolManager = toolManager,
            serverInfo = Implementation(name = "browser4-test", version = "0.0.0")
        )
    }

    // -------------------------------------------------------------------------
    // Tool registration
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("registers driver tools discovered from AgentToolManager")
    fun registersDriverToolsFromManager() {
        val names = mcpServer.server.tools.keys
        assertTrue(names.contains("navigate"), "Expected navigate")
        assertTrue(names.contains("click"), "Expected click")
        assertTrue(names.contains("scroll_to_top"), "Expected scroll_to_top")
        assertTrue(names.contains("get_text"), "Expected get_text")
    }

    @Test
    @DisplayName("registers fs tools with domain prefix")
    fun registersFsToolsWithDomainPrefix() {
        val names = mcpServer.server.tools.keys
        assertTrue(names.contains("fs_write_string"), "Expected fs_write_string")
        assertTrue(names.contains("fs_read_string"), "Expected fs_read_string")
    }

    @Test
    @DisplayName("registers system tools without domain prefix")
    fun registersSystemToolsWithoutPrefix() {
        val names = mcpServer.server.tools.keys
        assertTrue(names.contains("help"), "Expected help")
        assertFalse(names.contains("system_help"), "Expected NO system_help prefix")
    }

    @Test
    @DisplayName("total tool count matches sum of executor tool specs")
    fun toolCountMatchesExecutorSpecs() {
        val expected = driverExecutor.getToolSpecs().size +
                fsExecutor.getToolSpecs().size +
                systemExecutor.getToolSpecs().size
        assertEquals(expected, mcpServer.server.tools.size,
            "Expected $expected tools, got: ${mcpServer.server.tools.keys}")
    }

    @Test
    @DisplayName("driver methods use snake_case without domain prefix")
    fun driverMethodsUseSnakeCaseWithoutPrefix() {
        assertTrue(mcpServer.server.tools.containsKey("scroll_to_top"), "Expected scroll_to_top")
        assertFalse(mcpServer.server.tools.containsKey("driver_scroll_to_top"), "Expected NO driver_ prefix")
    }

    // -------------------------------------------------------------------------
    // Tool invocation routes through AgentToolManager
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("navigate tool handler routes call through AgentToolManager.execute")
    fun navigateToolRoutesCallThroughManager() = runBlocking {
        coEvery { toolManager.execute(any()) } returns toolCallResult(value = "Navigated to https://example.com")

        val tool = mcpServer.server.tools["navigate"]!!
        val request = buildRequest("navigate", mapOf("url" to "https://example.com"))
        val result = tool.handler(request)

        assertFalse(result.isError == true, "Expected success result")
        coVerify(exactly = 1) {
            toolManager.execute(match { tc ->
                tc.domain == "tab" && tc.method == "navigate" &&
                        tc.arguments["url"] == "https://example.com"
            })
        }
    }

    @Test
    @DisplayName("fs_write_string tool handler routes call through AgentToolManager.execute")
    fun fsWriteStringRoutesCallThroughManager() = runBlocking {
        coEvery { toolManager.execute(any()) } returns toolCallResult(value = "OK")

        val tool = mcpServer.server.tools["fs_write_string"]!!
        val request = buildRequest("fs_write_string", mapOf("filename" to "out.txt", "content" to "hello"))
        val result = tool.handler(request)

        assertFalse(result.isError == true)
        coVerify(exactly = 1) {
            toolManager.execute(match { tc ->
                tc.domain == "fs" && tc.method == "writeString" &&
                        tc.arguments["filename"] == "out.txt" && tc.arguments["content"] == "hello"
            })
        }
    }

    @Test
    @DisplayName("tool handler returns the value from AgentToolManager result")
    fun toolHandlerReturnsResultValue() = runBlocking {
        coEvery { toolManager.execute(any()) } returns toolCallResult(value = "navigated")

        val tool = mcpServer.server.tools["navigate"]!!
        val result = tool.handler(buildRequest("navigate", mapOf("url" to "https://example.com")))

        assertFalse(result.isError == true)
        val text = (result.content.firstOrNull() as? TextContent)?.text
        assertEquals("navigated", text)
    }

    // -------------------------------------------------------------------------
    // Error handling
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("tool handler returns error when AgentToolManager throws")
    fun toolHandlerReturnsErrorOnManagerException() = runBlocking {
        coEvery { toolManager.execute(any()) } throws RuntimeException("driver crashed")

        val tool = mcpServer.server.tools["navigate"]!!
        val result = tool.handler(buildRequest("navigate", mapOf("url" to "https://example.com")))

        assertTrue(result.isError == true, "Expected error result when manager throws")
        val text = (result.content.firstOrNull() as? TextContent)?.text
        assertTrue(text?.contains("driver crashed") == true)
    }

    @Test
    @DisplayName("tool handler returns error when TcEvaluate contains an exception")
    fun toolHandlerReturnsErrorWhenEvaluateHasException() = runBlocking {
        val evaluate = TcEvaluate(
            expression = "driver.navigate(url=\"bad\")",
            cause = RuntimeException("navigation failed"),
        )
        coEvery { toolManager.execute(any()) } returns toolCallResult(evaluate = evaluate)

        val tool = mcpServer.server.tools["navigate"]!!
        val result = tool.handler(buildRequest("navigate", mapOf("url" to "https://bad.url")))

        assertTrue(result.isError == true, "Expected error result when TcEvaluate has exception")
    }

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    private fun buildRequest(
        toolName: String,
        arguments: Map<String, String>
    ): io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest {
        val jsonArgs = arguments.entries.associate { (k, v) ->
            k to kotlinx.serialization.json.JsonPrimitive(v)
        }.let { kotlinx.serialization.json.JsonObject(it) }

        return io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest(
            params = io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams(
                name = toolName,
                arguments = jsonArgs
            )
        )
    }


    private fun toolCallResult(value: Any? = null, evaluate: TcEvaluate? = null): ToolCallResult {
        val resolvedEvaluate = evaluate ?: TcEvaluate(value = value)
        return ToolCallResult(
            evaluate = resolvedEvaluate,
            message = resolvedEvaluate.exception?.message,
        )
    }

}
