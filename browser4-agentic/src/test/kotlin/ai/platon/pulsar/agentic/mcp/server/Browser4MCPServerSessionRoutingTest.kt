package ai.platon.pulsar.agentic.mcp.server

import ai.platon.pulsar.agentic.model.ToolSpec
import ai.platon.pulsar.agentic.tools.AgentToolManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * P2: a multi-session MCP server routes a tool call to the session the client
 * named, and an unknown handle fails loudly instead of quietly driving the
 * server's own browser.
 */
@DisplayName("Browser4MCPServer session routing")
class Browser4MCPServerSessionRoutingTest {

    private val defaultManager = mockk<AgentToolManager>(relaxed = true)
    private val otherManager = mockk<AgentToolManager>(relaxed = true)

    private fun server(multiSession: Boolean): Browser4MCPServer {
        every { defaultManager.registeredExecutors } returns
                mapOf(
                    "tab" to FakeToolExecutor(
                        "tab",
                        listOf("navigate"),
                        listOf(ToolSpec.Arg("url", "String", null)),
                    )
                )

        val resolver = if (multiSession) {
            ToolManagerResolver.of(
                defaultToolManager = { defaultManager },
                lookup = { sessionId -> if (sessionId == "session-2") otherManager else null },
            )
        } else {
            ToolManagerResolver.single(defaultManager)
        }

        return Browser4MCPServer(
            toolManager = defaultManager,
            serverInfo = Implementation(name = "browser4-test", version = "0.0.0"),
            customExecutors = { emptyList() },
            toolManagerResolver = resolver,
            frontendAliases = emptyList(),
        )
    }

    @Test
    @DisplayName("without a session handle the call runs against the server's own session")
    fun withoutHandleUsesOwnSession() = runBlocking {
        val server = server(multiSession = true)
        coEvery { defaultManager.execute(any()) } returns mcpToolCallResult(value = "own")

        val result = server.server.tools["navigate"]!!.handler(
            mcpToolRequest("navigate", mapOf("url" to "https://example.com"))
        )

        assertEquals("own", toolResultText(result))
        coVerify(exactly = 1) { defaultManager.execute(any()) }
        coVerify(exactly = 0) { otherManager.execute(any()) }
    }

    @Test
    @DisplayName("an explicit session handle routes the call to that session")
    fun explicitHandleRoutesToThatSession() = runBlocking {
        val server = server(multiSession = true)
        coEvery { otherManager.execute(any()) } returns mcpToolCallResult(value = "other")

        val result = server.server.tools["navigate"]!!.handler(
            mcpToolRequest("navigate", mapOf("url" to "https://example.com", "sessionId" to "session-2"))
        )

        assertEquals("other", toolResultText(result))
        coVerify(exactly = 1) { otherManager.execute(any()) }
        coVerify(exactly = 0) { defaultManager.execute(any()) }
    }

    @Test
    @DisplayName("an unknown session handle fails loudly")
    fun unknownHandleFailsLoudly() = runBlocking {
        val server = server(multiSession = true)

        val result = server.server.tools["navigate"]!!.handler(
            mcpToolRequest("navigate", mapOf("url" to "https://example.com", "sessionId" to "missing"))
        )

        assertTrue(result.isError == true, "Expected an error result")
        assertTrue(
            toolResultText(result)?.contains("Session not found: missing") == true,
            "Got: ${toolResultText(result)}"
        )
        coVerify(exactly = 0) { defaultManager.execute(any()) }
        coVerify(exactly = 0) { otherManager.execute(any()) }
    }

    @Test
    @DisplayName("the session handle is consumed, not forwarded into tool arguments")
    fun handleIsNotForwardedToToolArguments() = runBlocking {
        val server = server(multiSession = true)
        coEvery { otherManager.execute(any()) } returns mcpToolCallResult(value = "other")

        server.server.tools["navigate"]!!.handler(
            mcpToolRequest("navigate", mapOf("url" to "https://example.com", "sessionId" to "session-2"))
        )

        coVerify(exactly = 1) {
            otherManager.execute(
                match { tc ->
                    tc.domain == "tab" && tc.method == "navigate" &&
                            tc.arguments["url"] == "https://example.com" &&
                            !tc.arguments.containsKey("sessionId")
                }
            )
        }
    }

    @Test
    @DisplayName("a multi-session server advertises an optional sessionId on every tool")
    fun multiSessionServerAdvertisesOptionalSessionId() {
        val schema = server(multiSession = true).server.tools["navigate"]!!.tool.inputSchema

        assertNotNull(schema.properties?.get("sessionId"), "Expected a sessionId property")
        assertFalse(
            schema.required?.contains("sessionId") == true,
            "sessionId must never be required, required=${schema.required}"
        )
    }

    @Test
    @DisplayName("a single-session server documents the handle as ignored")
    fun singleSessionServerDocumentsIgnoredHandle() {
        val schema = server(multiSession = false).server.tools["navigate"]!!.tool.inputSchema

        val description = schema.properties?.get("sessionId").toString()
        assertTrue(description.contains("Ignored"), "Expected the handle to document itself: $description")
    }
}
