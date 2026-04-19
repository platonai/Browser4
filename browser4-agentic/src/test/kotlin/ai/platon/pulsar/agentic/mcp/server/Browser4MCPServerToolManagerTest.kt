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
 * Tests for [Browser4MCPServer] when constructed from [AgentToolExecutor].
 *
 * Validates that:
 * - Tools are discovered dynamically from [AgentToolExecutor.concreteExecutors] and their
 *   [ToolSpec] metadata, rather than being registered with hard-coded schemas.
 * - Every MCP tool handler routes its call through [AgentToolExecutor.execute].
 * - The snake_case MCP tool names are derived correctly from domain + method.
 * - Tools with and without optional arguments are registered correctly.
 */
@DisplayName("Browser4MCPServer - AgentToolManager-based tool registration")
class Browser4MCPServerToolManagerTest {

    private lateinit var toolManager: AgentToolExecutor
    private lateinit var driverExecutor: ToolExecutor
    private lateinit var fsExecutor: ToolExecutor
    private lateinit var mcpServer: Browser4MCPServer

    @BeforeEach
    fun setUp() {
        // Create minimal executor mocks with representative tool specs
        driverExecutor = mockk(relaxed = true)
        every { driverExecutor.domain } returns "tab"
        every { driverExecutor.getToolSpecs() } returns mapOf(
            "navigate" to ToolSpec(
                domain = "tab",
                method = "navigate",
                arguments = listOf(ToolSpec.Arg("url", "String", null)),
                returnType = "Unit",
                description = "Navigate the browser to the given URL."
            ),
            "click" to ToolSpec(
                domain = "tab",
                method = "click",
                arguments = listOf(
                    ToolSpec.Arg("selector", "String", null),
                    ToolSpec.Arg("modifier", "String", "null")
                ),
                returnType = "Unit",
                description = "Click on an element."
            ),
            "scrollToTop" to ToolSpec(
                domain = "tab",
                method = "scrollToTop",
                arguments = emptyList(),
                returnType = "Double",
                description = "Scroll to top of the page."
            ),
        )

        fsExecutor = mockk(relaxed = true)
        every { fsExecutor.domain } returns "fs"
        every { fsExecutor.getToolSpecs() } returns mapOf(
            "writeString" to ToolSpec(
                domain = "fs",
                method = "writeString",
                arguments = listOf(
                    ToolSpec.Arg("filename", "String", null),
                    ToolSpec.Arg("content", "String", null),
                ),
                returnType = "Unit",
                description = "Write a string to a file."
            ),
        )

        toolManager = mockk(relaxed = true)
        every { toolManager.concreteExecutors } returns listOf(driverExecutor, fsExecutor)

        mcpServer = Browser4MCPServer(
            toolManager = toolManager,
            serverInfo = Implementation(name = "browser4-test-tm", version = "0.0.0"),
        )
    }

    // -------------------------------------------------------------------------
    // Tool discovery via AgentToolManager
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("registers driver tools discovered from AgentToolManager")
    fun registersDriverToolsFromManager() {
        val names = mcpServer.server.tools.keys
        assertTrue(names.contains("navigate"), "Expected 'navigate' tool to be registered")
        assertTrue(names.contains("click"), "Expected 'click' tool to be registered")
        assertTrue(names.contains("scroll_to_top"), "Expected 'scroll_to_top' tool to be registered")
    }

    @Test
    @DisplayName("registers fs tools with domain prefix from AgentToolManager")
    fun registersFsToolsWithDomainPrefix() {
        val names = mcpServer.server.tools.keys
        assertTrue(names.contains("fs_write_string"), "Expected 'fs_write_string' tool to be registered")
    }

    @Test
    @DisplayName("total tool count matches sum of executor tool specs")
    fun toolCountMatchesExecutorSpecs() {
        val expected = driverExecutor.getToolSpecs().size + fsExecutor.getToolSpecs().size
        assertEquals(expected, mcpServer.server.tools.size,
            "Expected $expected tools, got: ${mcpServer.server.tools.keys}")
    }

    @Test
    @DisplayName("each registered tool's spec has a non-blank description")
    fun registeredToolsHaveDescriptions() {
        val allSpecs = toolManager.concreteExecutors
            .flatMap { executor -> executor.getToolSpecs().values }
        allSpecs.forEach { spec ->
            assertFalse(spec.description.isNullOrBlank(),
                "Spec for '${spec.domain}.${spec.method}' has a blank or null description")
        }
    }

    @Test
    @DisplayName("navigate tool spec has a required url parameter")
    fun navigateToolHasRequiredUrlParameter() {
        val spec = driverExecutor.getToolSpecs()["navigate"]
        assertNotNull(spec, "Expected 'navigate' spec in driver executor")
        val urlArg = spec!!.arguments.find { it.name == "url" }
        assertNotNull(urlArg, "Expected 'url' argument in navigate spec")
        assertNull(urlArg!!.defaultValue, "Expected 'url' to be required (no default value)")
    }

    @Test
    @DisplayName("click tool spec has an optional modifier parameter")
    fun clickToolHasOptionalModifierParameter() {
        val spec = driverExecutor.getToolSpecs()["click"]
        assertNotNull(spec, "Expected 'click' spec in driver executor")
        val modArg = spec!!.arguments.find { it.name == "modifier" }
        assertNotNull(modArg, "Expected 'modifier' argument in click spec")
        assertNotNull(modArg!!.defaultValue, "Expected 'modifier' to have a default value")
    }

    // -------------------------------------------------------------------------
    // Tool invocation routes through AgentToolManager
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("navigate tool handler routes call through AgentToolManager.execute")
    fun navigateToolRoutesCallThroughManager() = runBlocking {
        coEvery { toolManager.execute(any()) } returns toolCallResult(value = null)

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
    fun fsWriteStringToolRoutesCallThroughManager() = runBlocking {
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
    @DisplayName("tool handler returns the result value from AgentToolManager")
    fun toolHandlerReturnsResultFromManager() = runBlocking {
        coEvery { toolManager.execute(any()) } returns toolCallResult(value = "navigated")

        val tool = mcpServer.server.tools["navigate"]!!
        val result = tool.handler(buildRequest("navigate", mapOf("url" to "https://example.com")))

        assertFalse(result.isError == true)
        val text = (result.content.firstOrNull() as? TextContent)?.text
        assertEquals("navigated", text)
    }

    @Test
    @DisplayName("tool handler returns error when AgentToolManager throws")
    fun toolHandlerReturnsErrorOnManagerException() = runBlocking {
        coEvery { toolManager.execute(any()) } throws RuntimeException("driver crashed")

        val tool = mcpServer.server.tools["navigate"]!!
        val result = tool.handler(buildRequest("navigate", mapOf("url" to "https://example.com")))

        assertTrue(result.isError == true, "Expected error result when manager throws")
        val text = (result.content.firstOrNull() as? TextContent)?.text
        assertTrue(text?.contains("driver crashed") == true, "Expected error message in content")
    }

    @Test
    @DisplayName("tool handler returns error result when TcEvaluate contains an exception")
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
    // Tool naming: camelCase → snake_case
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("driver domain methods are registered with snake_case names and no domain prefix")
    fun driverMethodsUseSnakeCaseWithoutPrefix() {
        // driver.scrollToTop -> scroll_to_top  (no "driver_" prefix)
        assertTrue(mcpServer.server.tools.containsKey("scroll_to_top"),
            "Expected 'scroll_to_top' (no driver_ prefix)")
        assertFalse(mcpServer.server.tools.containsKey("driver_scroll_to_top"),
            "Expected NO 'driver_scroll_to_top'")
    }

    @Test
    @DisplayName("non-driver domain methods are registered with domain prefix")
    fun nonDriverMethodsUseDomainPrefix() {
        // fs.writeString -> fs_write_string
        assertTrue(mcpServer.server.tools.containsKey("fs_write_string"),
            "Expected 'fs_write_string'")
        assertFalse(mcpServer.server.tools.containsKey("write_string"),
            "Expected NO plain 'write_string' when registering via tool manager")
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
                arguments = jsonArgs,
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
