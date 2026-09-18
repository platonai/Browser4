package ai.platon.pulsar.agentic.mcp.server

import ai.platon.pulsar.agentic.mcp.McpToolNames
import ai.platon.pulsar.agentic.model.ToolSpec
import ai.platon.pulsar.agentic.tools.AgentToolManager
import ai.platon.pulsar.agentic.tools.builtin.ToolExecutor
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * P4: the standard MCP server also answers to the Playwright-MCP style
 * `browser_*` names, so an agent trained on another browser MCP server reaches a
 * working tool instead of "unknown tool".
 */
@DisplayName("Browser4MCPServer frontend aliases")
class Browser4MCPServerAliasesTest {

    private lateinit var toolManager: AgentToolManager

    private fun serverWith(
        methods: List<String>,
        aliases: List<ai.platon.pulsar.agentic.mcp.McpToolAlias> = McpToolNames.frontendAliases,
        arguments: List<ToolSpec.Arg> = emptyList(),
    ): Browser4MCPServer {
        toolManager = mockk(relaxed = true)
        every { toolManager.registeredExecutors } returns
                mapOf("tab" to FakeToolExecutor("tab", methods, arguments))
        return Browser4MCPServer(
            toolManager = toolManager,
            serverInfo = Implementation(name = "browser4-test", version = "0.0.0"),
            customExecutors = { emptyList() },
            frontendAliases = aliases,
        )
    }

    @Test
    @DisplayName("aliases are advertised next to their canonical tool")
    fun aliasesAreAdvertisedNextToCanonical() {
        val names = serverWith(listOf("navigate", "click")).server.tools.keys

        assertTrue(names.contains("navigate"), "Got: $names")
        assertTrue(names.contains("browser_navigate"), "Got: $names")
        // browser_click is a private-dispatcher composite, not part of the alias table.
        assertFalse(names.contains("browser_click"), "Got: $names")
    }

    @Test
    @DisplayName("an alias description names the canonical tool it stands for")
    fun aliasDescriptionNamesCanonicalTool() {
        val description = serverWith(listOf("navigate")).server.tools["browser_navigate"]!!.tool.description

        assertTrue(description?.contains("Alias of 'navigate'") == true, "Got: $description")
    }

    @Test
    @DisplayName("calling an alias runs the canonical domain and method")
    fun aliasRoutesToCanonicalTool() = runBlocking {
        val server = serverWith(
            methods = listOf("navigate"),
            arguments = listOf(ToolSpec.Arg("url", "String", null)),
        )
        coEvery { toolManager.execute(any()) } returns mcpToolCallResult(value = "navigated")

        val result = server.invokeTool(
            "browser_navigate",
            mcpArgs("url" to "https://example.com"),
        )

        assertEquals("navigated", toolResultText(result))
        coVerify(exactly = 1) {
            toolManager.execute(
                match { it.domain == "tab" && it.method == "navigate" && it.arguments["url"] == "https://example.com" }
            )
        }
    }

    @Test
    @DisplayName("aliases whose canonical tool is absent are not registered")
    fun aliasesWithoutCanonicalToolAreSkipped() {
        val server = serverWith(listOf("click"))

        val names = server.server.tools.keys
        assertFalse(names.contains("browser_navigate"), "An alias without its canonical tool is a dead entry: $names")
        assertEquals(setOf("click"), names)
    }

    @Test
    @DisplayName("an alias keeps the canonical tool's argument schema")
    fun aliasKeepsCanonicalSchema() {
        val server = serverWith(listOf("navigate"))

        assertEquals(
            server.server.tools["navigate"]!!.tool.inputSchema.properties?.keys,
            server.server.tools["browser_navigate"]!!.tool.inputSchema.properties?.keys,
        )
    }

    @Test
    @DisplayName("an empty alias list keeps the tool list canonical-only")
    fun emptyAliasListKeepsCanonicalOnly() {
        val names = serverWith(listOf("navigate"), aliases = emptyList()).server.tools.keys

        assertEquals(setOf("navigate"), names)
    }
}
