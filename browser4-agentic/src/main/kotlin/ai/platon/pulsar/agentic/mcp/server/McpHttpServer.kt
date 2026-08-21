package ai.platon.pulsar.agentic.mcp.server

import ai.platon.pulsar.agentic.tools.AgentToolManager
import ai.platon.pulsar.common.getLogger
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.ktor.server.sse.ServerSSESession
import io.ktor.server.sse.sse
import io.modelcontextprotocol.kotlin.sdk.server.SseServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ConcurrentHashMap

/**
 * An embedded Ktor HTTP server that exposes Browser4's MCP tools via the standard
 * MCP Streamable HTTP (SSE) transport.
 *
 * This is the HTTP counterpart to [Browser4MCPServerRunner]'s STDIO transport.
 * It allows external MCP clients (Claude Desktop, Cursor, Windsurf, etc.) to
 * connect to Browser4 over HTTP instead of requiring a local subprocess.
 *
 * ## Protocol
 *
 * - **GET  /mcp/sse** — establishes an SSE stream for server→client messages.
 *   The server responds with an `endpoint` event containing the session-scoped
 *   POST URL.
 * - **POST /mcp/message?sessionId=...** — client→server JSON-RPC messages.
 *
 * ## Usage
 *
 * ```kotlin
 * val server = McpHttpServer(toolManager, port = 8088)
 * server.start()
 * // ... MCP clients connect to http://localhost:8088/mcp/sse
 * server.stop()
 * ```
 *
 * ## Spring Boot integration
 *
 * See [McpHttpServerConfiguration] in browser4-rest for auto-configuration that
 * starts this server as part of the Browser4 Spring Boot application lifecycle.
 *
 * @param toolManager The [AgentToolManager] providing browser automation tools.
 * @param port The port to listen on (default 8088).
 * @param host The hostname to bind to (default "0.0.0.0").
 * @param serverInfo MCP server identification for the initialize handshake.
 */
class McpHttpServer(
    private val toolManager: AgentToolManager,
    private val port: Int = DEFAULT_MCP_HTTP_PORT,
    private val host: String = "0.0.0.0",
    serverInfo: Implementation = Implementation(name = "browser4-mcp-server", version = "1.0.0"),
) {
    companion object {
        /** Default port for the MCP HTTP server. */
        const val DEFAULT_MCP_HTTP_PORT = 8088
    }

    private val logger = getLogger(this)

    /** The shared Browser4 MCP server — one instance handles all client sessions. */
    private val mcpServer = Browser4MCPServer(toolManager, serverInfo)

    /** Tracks active SSE transports by session ID so POST requests can be routed. */
    private val transports = ConcurrentHashMap<String, SseServerTransport>()

    private var engine: EmbeddedServer<*, *>? = null

    /** The port the engine actually bound — [port] unless an ephemeral fallback kicked in. */
    @Volatile
    private var boundPort: Int = port

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
        logger.info("MCP HTTP server listening on http://{}:{}/mcp/sse", host, listenPort)
    }

    /** Whether a TCP server can bind [candidatePort] right now. */
    private fun isPortFree(candidatePort: Int): Boolean = try {
        java.net.ServerSocket(candidatePort).use { true }
    } catch (_: java.io.IOException) {
        false
    }

    private fun startOnPort(listenPort: Int) {
        engine = embeddedServer(CIO, port = listenPort, host = host) {
            install(SSE)

            routing {
                route("/mcp") {
                    // SSE endpoint — each GET establishes a new SSE stream for
                    // server→client messages (tools/list responses, tool call
                    // results, notifications).
                    sse("/sse") {
                        onSseConnect(this@McpHttpServer, this)
                    }

                    // POST endpoint — client→server JSON-RPC messages.
                    post("/message") {
                        onPostMessage(this)
                    }
                }
            }
        }.start(wait = false)
    }

    /**
     * The port the embedded server actually bound. Differs from [port] only
     * after an ephemeral fallback.
     */
    val actualPort: Int get() = boundPort

    /**
     * Stop the embedded Ktor server, closing all active SSE connections.
     */
    fun stop() {
        logger.info("Stopping MCP HTTP server ({} active sessions)", transports.size)
        transports.values.forEach { transport ->
            runCatching { runBlocking { transport.close() } }
        }
        transports.clear()
        engine?.stop(gracePeriodMillis = 3000L, timeoutMillis = 5000L)
        engine = null
        logger.info("MCP HTTP server stopped")
    }

    /** Returns the number of active MCP client sessions. */
    val activeSessions: Int get() = transports.size

    // -------------------------------------------------------------------------
    // Internal — SSE connection lifecycle
    // -------------------------------------------------------------------------

    /**
     * Handle a new SSE connection from a client.
     *
     * 1. Creates an [SseServerTransport] wrapping the Ktor [ServerSSESession]
     * 2. Registers it so POST requests can find it by session ID
     * 3. Connects the transport to [mcpServer] via [Browser4MCPServer.server.createSession]
     * 4. Blocks until the SSE stream is closed by the client
     */
    private suspend fun onSseConnect(server: McpHttpServer, sseSession: ServerSSESession) {
        val transport = SseServerTransport(
            endpoint = "/message",
            session = sseSession,
        )

        server.transports[transport.sessionId] = transport
        server.logger.info(
            "MCP SSE connection established: sessionId={} (total={})",
            transport.sessionId,
            server.transports.size,
        )

        try {
            // createSession() calls transport.start() which sends the endpoint
            // event to the client, then handles the initialize handshake.
            server.mcpServer.server.createSession(transport)
            // Block until the SSE stream ends (client disconnects or error).
            awaitCancellation()
        } catch (_: CancellationException) {
            // Normal SSE disconnect — the client closed the connection.
        } catch (e: Exception) {
            server.logger.warn(
                "MCP SSE session error: sessionId={} | {}",
                transport.sessionId,
                e.message,
            )
        } finally {
            server.transports.remove(transport.sessionId)
            runCatching { transport.close() }
            server.logger.info(
                "MCP SSE connection closed: sessionId={} (total={})",
                transport.sessionId,
                server.transports.size,
            )
        }
    }

    /**
     * Handle a POST request containing a JSON-RPC message from the client.
     *
     * The request must include a `sessionId` query parameter so we can locate
     * the corresponding SSE transport.
     */
    private suspend fun onPostMessage(ctx: RoutingContext) {
        // queryParameters is a member property on ApplicationRequest, accessed
        // via ctx.call.request — no explicit import needed.
        val sessionId = ctx.call.request.queryParameters["sessionId"]
        if (sessionId.isNullOrBlank()) {
            ctx.call.respondText(
                text = "Missing 'sessionId' query parameter",
                status = HttpStatusCode.BadRequest,
            )
            return
        }

        val transport = transports[sessionId]
        if (transport == null) {
            ctx.call.respondText(
                text = "Session not found: $sessionId",
                status = HttpStatusCode.NotFound,
            )
            return
        }

        transport.handlePostMessage(ctx.call)
    }
}
