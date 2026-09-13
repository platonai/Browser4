package ai.platon.pulsar.rest.config

import ai.platon.pulsar.agentic.agents.BasicBrowserAgent
import ai.platon.pulsar.agentic.context.AgenticContext
import ai.platon.pulsar.agentic.mcp.server.McpHttpServer
import ai.platon.pulsar.agentic.mcp.server.ToolManagerResolver
import ai.platon.pulsar.agentic.mcp.server.ToolTargetResolver
import ai.platon.pulsar.agentic.tools.AgentToolManager
import ai.platon.pulsar.api.model.DisplayMode
import ai.platon.pulsar.common.getLogger
import ai.platon.pulsar.rest.mcp.controller.CustomToolTargets
import ai.platon.pulsar.rest.session.PulsarSessionManager
import ai.platon.pulsar.skeleton.PulsarSettings
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.ApplicationContext
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
 * automation tools via the standard MCP **stateless Streamable HTTP** protocol.
 *
 * ## How it works
 *
 * 1. On [ApplicationReadyEvent], a dedicated [BasicBrowserAgent] session
 *    is acquired (reusing an existing one if available, or creating one).
 * 2. The agent's [AgentToolManager] is wrapped in a [McpHttpServer].
 * 3. The server starts on the configured port (default 8088) and accepts
 *    MCP client requests at `POST /mcp` (one JSON-RPC message per request;
 *    GET/DELETE answer `405`).
 *
 * ## Configuration
 *
 * These keys are read as **JVM system properties** (`-D` flags), not from
 * `application.properties`:
 *
 * ```
 * -Dmcp.http.enabled=true        # enable/disable (default: true)
 * -Dmcp.http.port=8088           # listen port (default: 8088)
 * -Dmcp.http.host=0.0.0.0        # bind host (default: 0.0.0.0)
 * -Dmcp.http.headless=false      # run Chrome in headless mode (default: false)
 * -Dmcp.http.allowedHosts=a,b    # Host header allow-list (default: loopback)
 * -Dmcp.http.dnsRebindingProtection=true   # Host header validation (default: true)
 * ```
 *
 * `dnsRebindingProtection` rejects requests whose `Host` header is neither
 * loopback nor listed in `allowedHosts`. Binding to a concrete interface
 * (`mcp.http.host=192.168.1.5`) trusts that host automatically; a wildcard bind
 * keeps the loopback-only default, so a client reaching the server by any other
 * name must be listed explicitly.
 *
 * ## Session acquisition
 *
 * The session is acquired from the Spring-wired [AgenticContext] so the
 * session browser launches through the server-wide configuration
 * (`browser.display.mode`, which ships as HEADLESS). Using
 * `AgenticContexts.getOrCreateSession(...)` here instead created a throwaway
 * `StaticAgenticContext` whose configuration does not carry the server
 * default — its session browser then launched HEADED (a visible window) on
 * every backend start, even when the CLI used headless mode.
 *
 * ## Clients
 *
 * Any MCP-compatible client can connect:
 * - Claude Desktop: configure `mcpServers` with a `url` pointing to
 *   `http://host:8088/mcp` (streamable-http transport)
 * - Cursor / Windsurf: same URL in their MCP server configuration
 * - Custom clients: use the MCP SDK's `StreamableHttpClientTransport`
 * ## AOT training mode
 *
 * During the CLI's JVM AOT cache training run (`-Dbrowser4.aot.training=true`,
 * see `ensure_aot_cache_trained` in `cli/browser4-cli`), this configuration is
 * skipped entirely: the eager [mcpHttpServer] bean would otherwise create an
 * agent session — and with it launch a browser — just to record class loading.
 * The training run then boots the Spring context with the minimal surface and
 * exits on context refresh (`spring.context.exit=onRefresh`).
 */
@Configuration
@ConditionalOnProperty(name = ["mcp.http.enabled"], havingValue = "true", matchIfMissing = true)
@ConditionalOnProperty(name = ["browser4.aot.training"], havingValue = "false", matchIfMissing = true)
class McpHttpServerConfiguration(
    /**
     * The Spring-wired agentic context that owns browser sessions. Injecting
     * it (instead of going through [ai.platon.pulsar.agentic.context.AgenticContexts])
     * keeps the MCP session on the server-wide configuration, so a browser
     * launched for this session honors `browser.display.mode` (HEADLESS by
     * default) and the session never pops up an unexpected headed window.
     */
    private val agenticContext: AgenticContext,
    /**
     * Provider for the REST-layer session registry, used to route a tool call
     * that carries an explicit `sessionId` to that session's agent. Resolved
     * lazily so this configuration does not depend on the manager's own
     * initialisation order.
     */
    private val sessionManagerProvider: ObjectProvider<PulsarSessionManager>,
    /**
     * Used to resolve the collaborating bean an executor declares as its
     * `receiverClass` (e.g. `UserCommandExecutor` for the `command` domain).
     */
    private val applicationContext: ApplicationContext,
) {
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
        val allowedHosts = System.getProperty("mcp.http.allowedHosts")
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.takeIf { it.isNotEmpty() }
        val dnsRebindingProtection = System.getProperty(
            "mcp.http.dnsRebindingProtection",
            McpHttpServer.DEFAULT_DNS_REBINDING_PROTECTION.toString(),
        ).toBoolean()

        logger.info("Creating MCP HTTP server session (headless={})", headless)

        // Reuse an existing session when available, otherwise create one.
        // The display mode is only forced when mcp.http.headless=true; when
        // false (the default) the server-wide browser.display.mode applies.
        val displayMode = if (headless) DisplayMode.HEADLESS else null
        val session = agenticContext.getOrCreateSession(PulsarSettings(spa = true, displayMode = displayMode))
        val agent = session.companionAgent as? BasicBrowserAgent
            ?: throw IllegalStateException(
                "MCP HTTP server requires a BasicBrowserAgent, but companion agent is ${session.companionAgent::class.simpleName}"
            )

        return McpHttpServer(
            toolManager = agent.agentToolManager,
            port = port,
            host = host,
            toolManagerResolver = sessionResolver(agent),
            toolTargetResolver = customToolTargetResolver(),
            dnsRebindingProtection = dnsRebindingProtection,
            allowedHosts = allowedHosts,
        )
    }

    /**
     * Session resolution for MCP-over-HTTP.
     *
     * Without a `sessionId` argument a tool call drives this server's own session
     * (the pre-existing behaviour). With one, the call is routed to the matching
     * `PulsarSessionManager` session — so an MCP client can drive the very browser
     * the CLI opened (`browser4-cli open`, `attach`, named sessions) instead of
     * silently reaching a second, unrelated browser.
     *
     * The provider is resolved lazily: [PulsarSessionManager] must not be pulled
     * in while this configuration is being created.
     */
    private fun sessionResolver(defaultAgent: BasicBrowserAgent): ToolManagerResolver = ToolManagerResolver.of(
        defaultToolManager = { defaultAgent.agentToolManager },
        lookup = { sessionId ->
            val sessionManager = sessionManagerProvider.ifAvailable
            if (sessionManager == null) {
                logger.warn("MCP session lookup failed: PulsarSessionManager is not available")
                null
            } else {
                runCatching { sessionManager.getOrRecoverSession(sessionId) }
                    .onFailure { logger.warn("MCP session lookup failed | sessionId={} | {}", sessionId, it.message) }
                    .getOrNull()
                    ?.agenticSession
                    ?.companionAgent as? BasicBrowserAgent
            }?.agentToolManager
        },
    )

    /**
     * Resolve the receiver a custom-domain tool needs.
     *
     * Without this the standard MCP server advertised the plugin/business domains
     * but could only execute the ones whose receiver the agent happened to bind —
     * `memory_*`, `experience_*`, `html_snapshot_*`, `webdb_*`, `crawl_*` and
     * `command_*` all failed with "no target object is available". The mapping is
     * shared with the private dispatcher ([CustomToolTargets]) so both channels
     * resolve a tool the same way.
     */
    private fun customToolTargetResolver(): ToolTargetResolver = ToolTargetResolver { executor, sessionId ->
        val sessionManager = sessionManagerProvider.ifAvailable
        if (sessionManager == null) {
            logger.warn("MCP tool target resolution skipped: PulsarSessionManager is not available")
            null
        } else {
            CustomToolTargets(sessionManager) { type ->
                runCatching { applicationContext.getBean(type) }.getOrNull()
            }.resolve(executor, sessionId)
        }
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
