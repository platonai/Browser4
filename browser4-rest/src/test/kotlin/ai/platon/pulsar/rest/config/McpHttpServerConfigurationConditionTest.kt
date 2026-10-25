package ai.platon.pulsar.rest.config

import ai.platon.pulsar.agentic.AgenticSession
import ai.platon.pulsar.agentic.agents.BasicBrowserAgent
import ai.platon.pulsar.agentic.context.AgenticContext
import ai.platon.pulsar.agentic.tools.AgentToolManager
import ai.platon.pulsar.skeleton.PulsarSettings
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The configuration's own session acquisition, stubbed: the eager
 * `mcpHttpServer` bean needs a `BasicBrowserAgent`, and these tests are about
 * whether that bean exists at all.
 */
private fun stubAgenticContext(): AgenticContext {
    val session = mock<AgenticSession>()
    val agent = mock<BasicBrowserAgent>()
    whenever(agent.agentToolManager).thenReturn(mock<AgentToolManager>())
    whenever(session.companionAgent).thenReturn(agent)

    val context = mock<AgenticContext>()
    whenever(context.getOrCreateSession(any<PulsarSettings>())).thenReturn(session)
    return context
}

/** Supplies the stubbed [AgenticContext] the configuration requires. */
@Configuration(proxyBeanMethods = false)
class StubAgenticContextConfiguration {
    @Bean
    fun agenticContext(): AgenticContext = stubAgenticContext()
}

/**
 * `mcp.http.enabled` is **opt-in**: the standard MCP-over-HTTP endpoint stays off
 * unless it is explicitly enabled.
 *
 * Enabling it eagerly acquires an agent session at startup, and creating that
 * session launches a browser. Default-on therefore added a headless browser to
 * *every* backend start — including CLI-only workflows, which talk to the REST
 * dispatcher and never speak MCP-over-HTTP.
 */
@DisplayName("MCP-over-HTTP server is opt-in")
class McpHttpServerConfigurationConditionTest {

    @AfterEach
    fun tearDown() {
        System.clearProperty("mcp.http.enabled")
        System.clearProperty("mcp.http.headless")
        System.clearProperty("mcp.http.port")
        System.clearProperty("mcp.http.host")
    }

    private fun runner(): ApplicationContextRunner = ApplicationContextRunner()
        .withUserConfiguration(StubAgenticContextConfiguration::class.java)
        .withUserConfiguration(McpHttpServerConfiguration::class.java)

    @Test
    @DisplayName("with no property the MCP-over-HTTP server is not created")
    fun disabledByDefault() {
        runner().run { context ->
            assertFalse(
                context.containsBean("mcpHttpServer"),
                "mcp.http.enabled must default to false; beans=${context.beanDefinitionNames}"
            )
        }
    }

    @Test
    @DisplayName("mcp.http.enabled=false keeps the server off")
    fun explicitlyDisabled() {
        runner()
            .withPropertyValues("mcp.http.enabled=false")
            .run { context ->
                assertFalse(
                    context.containsBean("mcpHttpServer"),
                    "mcp.http.enabled=false must not create the server"
                )
            }
    }

    @Test
    @DisplayName("mcp.http.enabled=true opts the server in")
    fun enabledOptsIn() {
        runner()
            .withPropertyValues("mcp.http.enabled=true")
            .run { context ->
                assertTrue(
                    context.containsBean("mcpHttpServer"),
                    "mcp.http.enabled=true must create the server"
                )
            }
    }
}
