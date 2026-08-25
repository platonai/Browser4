package ai.platon.pulsar.rest.config

import ai.platon.pulsar.agentic.AgenticSession
import ai.platon.pulsar.agentic.agents.BasicBrowserAgent
import ai.platon.pulsar.agentic.context.AgenticContext
import ai.platon.pulsar.agentic.tools.AgentToolManager
import ai.platon.pulsar.api.model.DisplayMode
import ai.platon.pulsar.skeleton.PulsarSettings
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * The MCP HTTP server must acquire its session from the Spring-wired
 * [AgenticContext] (so browser launches honor the server-wide
 * `browser.display.mode`) and must pass the requested display mode through
 * to the session settings.
 */
class McpHttpServerConfigurationTest {

    @AfterEach
    fun tearDown() {
        System.clearProperty("mcp.http.headless")
        System.clearProperty("mcp.http.port")
        System.clearProperty("mcp.http.host")
    }

    private fun stubSession(agenticContext: AgenticContext): AgenticSession {
        val session = mock<AgenticSession>()
        val agent = mock<BasicBrowserAgent>()
        val toolManager = mock<AgentToolManager>()
        whenever(agent.agentToolManager).thenReturn(toolManager)
        whenever(session.companionAgent).thenReturn(agent)
        whenever(agenticContext.getOrCreateSession(any<PulsarSettings>())).thenReturn(session)
        return session
    }

    @Test
    @DisplayName("mcp.http.headless=true forces HEADLESS display mode on the session")
    fun mcpHttpServerForcesHeadlessWhenRequested() {
        System.setProperty("mcp.http.headless", "true")
        val agenticContext = mock<AgenticContext>()
        stubSession(agenticContext)

        McpHttpServerConfiguration(agenticContext).mcpHttpServer()

        verify(agenticContext).getOrCreateSession(PulsarSettings(spa = true, displayMode = DisplayMode.HEADLESS))
    }

    @Test
    @DisplayName("mcp.http.headless unset leaves the display mode to the server default")
    fun mcpHttpServerLeavesDisplayModeToServerDefaultByDefault() {
        val agenticContext = mock<AgenticContext>()
        stubSession(agenticContext)

        McpHttpServerConfiguration(agenticContext).mcpHttpServer()

        verify(agenticContext).getOrCreateSession(PulsarSettings(spa = true, displayMode = null))
    }

    @Test
    @DisplayName("mcp.http.headless=false behaves like unset (server default applies)")
    fun mcpHttpServerWithExplicitFalseLeavesDisplayModeUnset() {
        System.setProperty("mcp.http.headless", "false")
        val agenticContext = mock<AgenticContext>()
        stubSession(agenticContext)

        McpHttpServerConfiguration(agenticContext).mcpHttpServer()

        verify(agenticContext).getOrCreateSession(PulsarSettings(spa = true, displayMode = null))
    }
}
