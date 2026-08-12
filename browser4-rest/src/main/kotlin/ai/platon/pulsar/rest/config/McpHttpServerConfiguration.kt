package ai.platon.pulsar.rest.config

import ai.platon.pulsar.agentic.agents.BasicBrowserAgent
import ai.platon.pulsar.agentic.context.AgenticContexts
import ai.platon.pulsar.agentic.mcp.server.McpHttpServer
import ai.platon.pulsar.common.getLogger
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Lazy
import org.springframework.context.event.EventListener

/**
 * Auto-configuration for the MCP-over-HTTP server.
 *
 * When `mcp.http.enabled` is `true` (the default), this configuration
 * starts an embedded Ktor HTTP server that exposes Browser4's browser
 * automation tools via the standard MCP Streamable HTTP (SSE) protocol.
 *
 * ## How it works
 *
 * 1. On [ApplicationReadyEvent], a dedicated [BasicBrowserAgent] session
 *    is acquired (reusing an existing one if available, or creating one).
 * 2. The agent's [AgentToolManager] is wrapped in a [McpHttpServer].
 * 3. The server starts on the configured port (default 8088) and accepts
 *    MCP client connections at `/mcp/sse`.
 *
 * ## Configuration
 *
 * ```
 * # application.properties
 * mcp.http.enabled=true        # enable/disable (default: true)
 * mcp.http.port=8088           # listen port (default: 8088)
 * mcp.http.host=0.0.0.0        # bind host (default: 0.0.0.0)
 * mcp.http.headless=false      # run Chrome in headless mode (default: false)
 * ```
 *
 * ## Clients
 *
 * Any MCP-compatible client can connect:
 * - Claude Desktop: configure `mcpServers` with a `url` pointing to
 *   `http://host:8088/mcp/sse` (streamable-http transport)
 * - Cursor / Windsurf: same URL in their MCP server configuration
 * - Custom clients: use the MCP SDK's `StreamableHttpClientTransport`
 */
@Configuration
@ConditionalOnProperty(name = ["mcp.http.enabled"], havingValue = "true", matchIfMissing = true)
class McpHttpServerConfiguration {

    private val logger = getLogger(this)

    /**
     * The MCP HTTP server instance.
     *
     * Marked [Lazy(false)] so it is instantiated eagerly even when
     * `spring.main.lazy-initialization=true`.
     */
    @Bean(destroyMethod = "stop")
    @Lazy(false)
    fun mcpHttpServer(): McpHttpServer {
        val port = System.getProperty("mcp.http.port")?.toIntOrNull() ?: McpHttpServer.DEFAULT_MCP_HTTP_PORT
        val host = System.getProperty("mcp.http.host", "0.0.0.0")
        val headless = System.getProperty("mcp.http.headless", "false").toBoolean()

        logger.info("Creating MCP HTTP server session (headless={})", headless)

        val session = AgenticContexts.getOrCreateSession(headless = headless)
        val agent = session.companionAgent as? BasicBrowserAgent
            ?: throw IllegalStateException(
                "MCP HTTP server requires a BasicBrowserAgent, but companion agent is ${session.companionAgent::class.simpleName}"
            )

        return McpHttpServer(
            toolManager = agent.agentToolManager,
            port = port,
            host = host,
        )
    }

    /**
     * Start the MCP HTTP server once the application is fully initialized.
     *
     * We use [ApplicationReadyEvent] rather than [jakarta.annotation.PostConstruct]
     * because the agent session may reference Spring-managed beans that aren't
     * fully available during post-construction.
     *
     * The server is obtained via the proxied [mcpHttpServer] bean method: Spring 7
     * no longer resolves `@EventListener` method parameters as beans — the method
     * always receives the published event as its argument.
     *
     * Start failures are logged, not rethrown: the MCP-over-HTTP transport is an
     * optional add-on, and a busy port (e.g. several Spring test contexts sharing
     * one JVM) must not prevent the application from starting.
     */
    @EventListener(ApplicationReadyEvent::class)
    fun onApplicationReady(event: ApplicationReadyEvent) {
        // Start after Spring Boot's own server has bound, avoiding port
        // conflicts and ensuring all session infrastructure is ready.
        runCatching { mcpHttpServer().start() }
            .onFailure { e ->
                logger.warn(
                    "MCP HTTP server did not start: {} — MCP-over-HTTP will be unavailable " +
                        "(set mcp.http.port if the configured port is already in use)",
                    e.message
                )
            }
    }
}
