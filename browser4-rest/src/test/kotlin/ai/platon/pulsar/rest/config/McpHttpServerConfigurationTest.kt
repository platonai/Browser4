package ai.platon.pulsar.rest.config

import ai.platon.pulsar.agentic.AgenticSession
import ai.platon.pulsar.agentic.agents.BasicBrowserAgent
import ai.platon.pulsar.agentic.context.AgenticContext
import ai.platon.pulsar.agentic.tools.AgentToolManager
import ai.platon.pulsar.api.model.DisplayMode
import ai.platon.pulsar.rest.session.PulsarSessionManager
import ai.platon.pulsar.skeleton.PulsarSettings
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.NoSuchBeanDefinitionException
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.ApplicationContext

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

    /**
     * A session provider that resolves to nothing: these tests only assert how the
     * MCP server's own session is created, not session-handle routing.
     */
    private fun stubSessionManagerProvider(): ObjectProvider<PulsarSessionManager> {
        val provider = mock<ObjectProvider<PulsarSessionManager>>()
        whenever(provider.ifAvailable).thenReturn(null)
        return provider
    }

    /**
     * An application context that resolves no beans: the tests only assert how the
     * MCP server's own session is created, not tool-target routing.
     */
    private fun stubApplicationContext(): ApplicationContext {
        val context = mock<ApplicationContext>()
        whenever(context.getBean(any<Class<*>>())).thenThrow(NoSuchBeanDefinitionException("stub"))
        return context
    }

    @Test
    @DisplayName("mcp.http.headless=true forces HEADLESS display mode on the session")
    fun mcpHttpServerForcesHeadlessWhenRequested() {
        System.setProperty("mcp.http.headless", "true")
        val agenticContext = mock<AgenticContext>()
        stubSession(agenticContext)

        McpHttpServerConfiguration(agenticContext, stubSessionManagerProvider(), stubApplicationContext()).mcpHttpServer()

        verify(agenticContext).getOrCreateSession(PulsarSettings(spa = true, displayMode = DisplayMode.HEADLESS))
    }

    @Test
    @DisplayName("mcp.http.headless unset leaves the display mode to the server default")
    fun mcpHttpServerLeavesDisplayModeToServerDefaultByDefault() {
        val agenticContext = mock<AgenticContext>()
        stubSession(agenticContext)

        McpHttpServerConfiguration(agenticContext, stubSessionManagerProvider(), stubApplicationContext()).mcpHttpServer()

        verify(agenticContext).getOrCreateSession(PulsarSettings(spa = true, displayMode = null))
    }

    @Test
    @DisplayName("mcp.http.headless=false behaves like unset (server default applies)")
    fun mcpHttpServerWithExplicitFalseLeavesDisplayModeUnset() {
        System.setProperty("mcp.http.headless", "false")
        val agenticContext = mock<AgenticContext>()
        stubSession(agenticContext)

        McpHttpServerConfiguration(agenticContext, stubSessionManagerProvider(), stubApplicationContext()).mcpHttpServer()

        verify(agenticContext).getOrCreateSession(PulsarSettings(spa = true, displayMode = null))
    }
}
