package ai.platon.pulsar.agentic.mcp.server

import ai.platon.pulsar.agentic.tools.AgentToolManager
import ai.platon.pulsar.agentic.tools.builtin.ToolExecutor
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * P0: the standard MCP server must expose the plugin/business tool domains that
 * live in [ai.platon.pulsar.agentic.tools.CustomToolRegistry], not only the
 * built-in executors of the [AgentToolManager] — otherwise an external MCP client
 * sees a fraction of what the private dispatcher offers.
 */
@DisplayName("Browser4MCPServer tool discovery sources")
class Browser4MCPServerToolSourcesTest {

    private lateinit var toolManager: AgentToolManager

    private fun serverWith(
        builtIn: List<ToolExecutor>,
        custom: List<ToolExecutor>,
    ): Browser4MCPServer {
        toolManager = mockk(relaxed = true)
        every { toolManager.registeredExecutors } returns builtIn.associateBy { it.domain }
        return Browser4MCPServer(
            toolManager = toolManager,
            serverInfo = Implementation(name = "browser4-test", version = "0.0.0"),
            customExecutors = { custom },
            frontendAliases = emptyList(),
        )
    }

    @Test
    @DisplayName("plugin/business domains are registered alongside the built-in ones")
    fun registersCustomDomainsAlongsideBuiltIns() {
        val server = serverWith(
            builtIn = listOf(FakeToolExecutor("tab", listOf("navigate"))),
            custom = listOf(FakeToolExecutor("command", listOf("run", "status"))),
        )

        val names = server.server.tools.keys
        assertTrue(names.contains("navigate"), "Got: $names")
        assertTrue(names.contains("command_run"), "Got: $names")
        assertTrue(names.contains("command_status"), "Got: $names")
        assertEquals(3, names.size, "Got: $names")
    }

    @Test
    @DisplayName("a custom tool executes through AgentToolManager, like a built-in one")
    fun customToolExecutesThroughManager() = runBlocking {
        val server = serverWith(
            builtIn = listOf(FakeToolExecutor("tab", listOf("navigate"))),
            custom = listOf(FakeToolExecutor("webdb", listOf("export"))),
        )
        coEvery { toolManager.execute(any()) } returns mcpToolCallResult(value = "exported")

        val result = server.invokeTool("webdb_export")

        assertEquals("exported", toolResultText(result))
        coVerify(exactly = 1) {
            toolManager.execute(match { it.domain == "webdb" && it.method == "export" })
        }
    }

    @Test
    @DisplayName("a custom tool clashing with a built-in name is skipped, never silently overwritten")
    fun conflictingCustomNameIsSkipped() {
        val server = serverWith(
            builtIn = listOf(FakeToolExecutor("skill", listOf("list"))),
            custom = listOf(FakeToolExecutor("skill", listOf("list", "info"))),
        )

        val names = server.server.tools.keys
        assertTrue(names.contains("skill_list"), "Got: $names")
        assertTrue(names.contains("skill_info"), "Got: $names")
        assertEquals(
            2, names.size,
            "The custom 'skill_list' must be dropped instead of shadowing the built-in one: $names"
        )
    }

    @Test
    @DisplayName("no plugins registered leaves the built-in tool set untouched")
    fun noCustomExecutorsLeavesBuiltInsUntouched() {
        val server = serverWith(
            builtIn = listOf(FakeToolExecutor("tab", listOf("navigate", "click"))),
            custom = emptyList(),
        )

        assertEquals(setOf("navigate", "click"), server.server.tools.keys)
    }

    @Test
    @DisplayName("executors that register after construction are picked up by refreshTools")
    fun lateRegisteredExecutorsArePickedUpByRefresh() = runBlocking {
        // Mirrors the Spring deployment: the MCP server bean is built during
        // context refresh, while the plugin scan wires ToolMount executors into
        // CustomToolRegistry a few seconds later. Without a refresh those domains
        // never reach `tools/list`.
        val lateExecutors = mutableListOf<ToolExecutor>()
        toolManager = mockk(relaxed = true)
        every { toolManager.registeredExecutors } returns
                mapOf("tab" to FakeToolExecutor("tab", listOf("navigate")))
        val server = Browser4MCPServer(
            toolManager = toolManager,
            serverInfo = Implementation(name = "browser4-test", version = "0.0.0"),
            customExecutors = { lateExecutors.toList() },
            frontendAliases = emptyList(),
        )
        assertEquals(setOf("navigate"), server.server.tools.keys)

        lateExecutors += FakeToolExecutor("webdb", listOf("export"))

        assertEquals(1, server.refreshTools(), "refresh must advertise the late executor's tool")
        assertEquals(setOf("navigate", "webdb_export"), server.server.tools.keys)

        // Idempotent — and the late tool is callable, not just listed.
        assertEquals(0, server.refreshTools(), "a second refresh must add nothing")
        coEvery { toolManager.execute(any()) } returns mcpToolCallResult(value = "exported")
        assertEquals("exported", toolResultText(server.invokeTool("webdb_export")))
        coVerify(exactly = 1) {
            toolManager.execute(match { it.domain == "webdb" && it.method == "export" })
        }
    }
}
