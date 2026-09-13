package ai.platon.pulsar.agentic.mcp.server

import ai.platon.pulsar.agentic.mcp.McpToolAlias
import ai.platon.pulsar.agentic.mcp.McpToolNames
import ai.platon.pulsar.agentic.tools.AgentToolManager
import ai.platon.pulsar.agentic.tools.CustomToolRegistry
import ai.platon.pulsar.agentic.tools.builtin.ToolExecutor
import ai.platon.pulsar.common.getLogger
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.modelcontextprotocol.kotlin.sdk.server.mcpStatelessStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import java.util.concurrent.atomic.AtomicLong

/**
 * An embedded Ktor HTTP server that exposes Browser4's MCP tools over the
 * standard **stateless Streamable HTTP** transport.
 *
 * This is the HTTP counterpart to [Browser4MCPServerRunner]'s STDIO transport:
 * external MCP clients (Claude Desktop, Cursor, Windsurf, …) connect over HTTP
 * instead of requiring a local subprocess.
 *
 * ## Protocol
 *
 * - **POST /mcp** — one JSON-RPC request per HTTP request; the response is JSON.
 *   There is no handshake, no `Mcp-Session-Id` and no server→client stream.
 * - GET/DELETE /mcp answer `405 Method Not Allowed`.
 *
 * The previous implementation spoke HTTP+SSE (`GET /mcp/sse` +
 * `POST /mcp/message?sessionId=…`), a transport the MCP specification deprecated
 * in favour of Streamable HTTP. Client configuration therefore points at
 * `http://host:8088/mcp`.
 *
 * Session state lives in the *application* layer, not the protocol: a tool call
 * may carry a `sessionId` argument, which [ToolManagerResolver] maps to the
 * browser session to drive (see [ToolManagerResolver]).
 *
 * ## Usage
 *
 * ```kotlin
 * val server = McpHttpServer(toolManager, port = 8088)
 * server.start()
 * // ... MCP clients connect to http://localhost:8088/mcp
 * server.stop()
 * ```
 *
 * ## Spring Boot integration
 *
 * See `McpHttpServerConfiguration` in browser4-rest for auto-configuration that
 * starts this server as part of the Browser4 Spring Boot application lifecycle.
 *
 * @param toolManager The [AgentToolManager] providing browser automation tools.
 * @param port The port to listen on (default 8088).
 * @param host The hostname to bind to (default "0.0.0.0").
 * @param serverInfo MCP server identification for the initialize handshake.
 * @param toolManagerResolver Resolves which session a tool call runs against.
 *   Defaults to the single [toolManager] this server was started with.
 * @param customExecutors Supplies the plugin/business executors to advertise.
 * @param frontendAliases The extra `browser_*` spellings to advertise.
 * @param dnsRebindingProtection Whether to validate the `Host` header. Defaults
 *   to `true`; a client whose `Host` is neither loopback nor listed in
 *   [allowedHosts] is rejected with `403 Forbidden`.
 * @param allowedHosts Hostnames accepted in the `Host` header. `null` keeps the
 *   SDK default of `localhost`, `127.0.0.1` and `[::1]`.
 */
class McpHttpServer(
    private val toolManager: AgentToolManager,
    private val port: Int = DEFAULT_MCP_HTTP_PORT,
    private val host: String = "0.0.0.0",
    serverInfo: Implementation = Implementation(name = "browser4-mcp-server", version = "1.0.0"),
    toolManagerResolver: ToolManagerResolver = ToolManagerResolver.single(toolManager),
    customExecutors: () -> List<ToolExecutor> = { CustomToolRegistry.instance.getAllExecutors() },
    frontendAliases: List<McpToolAlias> = McpToolNames.frontendAliases,
    toolTargetResolver: ToolTargetResolver = ToolTargetResolver.NONE,
    private val dnsRebindingProtection: Boolean = DEFAULT_DNS_REBINDING_PROTECTION,
    private val allowedHosts: List<String>? = null,
) {
    companion object {
        /** Default port for the MCP HTTP server. */
        const val DEFAULT_MCP_HTTP_PORT = 8088

        /** The single Streamable HTTP endpoint all MCP clients post to. */
        const val MCP_ENDPOINT_PATH = "/mcp"

        /** Host names always trusted when [dnsRebindingProtection] is on. */
        val LOOPBACK_HOSTS: List<String> = listOf("localhost", "127.0.0.1", "[::1]")

        /** DNS rebinding protection is on unless a deployment opts out. */
        const val DEFAULT_DNS_REBINDING_PROTECTION = true
    }

    private val logger = getLogger(this)

    /**
     * The shared Browser4 MCP server — one instance serves every request.
     *
     * The stateless endpoint creates (and closes) one protocol session per
     * request, so the registered tool list stays constant and is built once at
     * startup instead of on every call.
     */
    private val mcpServer = Browser4MCPServer(
        toolManager = toolManager,
        serverInfo = serverInfo,
        customExecutors = customExecutors,
        toolManagerResolver = toolManagerResolver,
        frontendAliases = frontendAliases,
        toolTargetResolver = toolTargetResolver,
    )

    private var engine: EmbeddedServer<*, *>? = null

    /** The port the engine actually bound — [port] unless an ephemeral fallback kicked in. */
    @Volatile
    private var boundPort: Int = port

    /** Number of JSON-RPC requests served since startup. */
    private val requestCount = AtomicLong(0)

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    /**
     * Start the embedded Ktor HTTP server.
     *
     * This method returns immediately; the server runs on its own event-loop thread.
     * Call [stop] to shut it down.
     *
     * When [port] is already taken (e.g. a second backend instance on the same
     * host), the server falls back to an ephemeral OS-assigned port so
     * MCP-over-HTTP stays available instead of degrading to a WARN-only no-op.
     * The port is probed up front because the embedded engine binds
     * asynchronously and does not throw a usable [java.net.BindException]
     * to the caller.
     */
    fun start() {
        var listenPort = port
        if (!isPortFree(port)) {
            logger.warn(
                "MCP HTTP port {} is already in use — binding an ephemeral port instead",
                port
            )
            listenPort = java.net.ServerSocket(0).use { it.localPort }
        }
        boundPort = listenPort

        logger.info("Starting MCP HTTP server on {}:{}", host, listenPort)
        startOnPort(listenPort)
        logger.info(
            "MCP HTTP server listening on http://{}:{}{} (stateless Streamable HTTP, dnsRebindingProtection={}, allowedHosts={})",
            host, listenPort, MCP_ENDPOINT_PATH, dnsRebindingProtection,
            allowedHosts?.joinToString(",") ?: "default(loopback)",
        )
    }

    /** Whether a TCP server can bind [candidatePort] right now. */
    private fun isPortFree(candidatePort: Int): Boolean = try {
        java.net.ServerSocket(candidatePort).use { true }
    } catch (_: java.io.IOException) {
        false
    }

    private fun startOnPort(listenPort: Int) {
        engine = embeddedServer(CIO, port = listenPort, host = host) {
            mcpStatelessStreamableHttp(
                path = MCP_ENDPOINT_PATH,
                enableDnsRebindingProtection = dnsRebindingProtection,
                allowedHosts = resolvedAllowedHosts,
            ) {
                requestCount.incrementAndGet()
                // Plugin/business executors may have registered after this server
                // was built (the Spring plugin scan runs later than the bean), so
                // refresh before serving — otherwise `tools/list` under-reports.
                mcpServer.refreshTools()
                // The endpoint creates one protocol session per request and closes
                // it afterwards, so handing back the shared server is safe and keeps
                // tool registration off the request path.
                mcpServer.server
            }
        }.start(wait = false)
    }

    /**
     * The `Host` header allow-list handed to the SDK.
     *
     * An explicit [allowedHosts] wins. Otherwise, when the server is bound to a
     * concrete interface (e.g. `mcp.http.host=192.168.1.5`), that host is trusted
     * alongside loopback so the configured bind address is actually reachable;
     * with a wildcard bind the SDK default (loopback only) applies.
     */
    private val resolvedAllowedHosts: List<String>?
        get() = when {
            allowedHosts != null -> allowedHosts
            isWildcardBind(host) -> null
            else -> listOf(host) + LOOPBACK_HOSTS
        }

    private fun isWildcardBind(bindHost: String): Boolean =
        bindHost.isBlank() || bindHost == "0.0.0.0" || bindHost == "::" || bindHost == "[::]"

    /**
     * The port the embedded server actually bound. Differs from [port] only
     * after an ephemeral fallback.
     */
    val actualPort: Int get() = boundPort

    /**
     * Number of JSON-RPC requests served so far.
     *
     * Replaces the former `activeSessions` gauge: a stateless endpoint keeps no
     * long-lived connection, so "how many clients are connected" no longer exists
     * as a concept.
     */
    val servedRequests: Long get() = requestCount.get()

    /**
     * Stop the embedded Ktor server.
     */
    fun stop() {
        logger.info("Stopping MCP HTTP server ({} requests served)", servedRequests)
        engine?.stop(gracePeriodMillis = 3000L, timeoutMillis = 5000L)
        engine = null
        logger.info("MCP HTTP server stopped")
    }
}
