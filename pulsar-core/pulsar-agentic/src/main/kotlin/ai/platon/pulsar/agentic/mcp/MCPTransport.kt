package ai.platon.pulsar.agentic.mcp

import ai.platon.pulsar.common.getLogger
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import java.io.BufferedReader
import java.io.PrintWriter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/**
 * MCP Server Transport Layer.
 *
 * Provides transport mechanisms for MCP protocol communication:
 * - Stdio transport for command-line integration
 * - SSE (Server-Sent Events) transport for web-based clients
 *
 * @see <a href="https://modelcontextprotocol.io/specification/basic/transports">MCP Transports</a>
 * @author Vincent Zhang, ivincent.zhang@gmail.com, platon.ai
 */

// ============================================================================
// SSE Transport
// ============================================================================

/**
 * SSE (Server-Sent Events) session for MCP communication.
 *
 * Each session represents a connected client and handles bidirectional
 * communication through SSE for server-to-client and POST for client-to-server.
 */
class MCPSSESession(
    val sessionId: String,
    private val protocolHandler: MCPProtocolHandler = MCPProtocolHandler(),
) {
    private val logger = getLogger(this)
    private val mapper = jacksonObjectMapper()

    /** Channel for outgoing SSE events */
    private val eventChannel = Channel<SSEEvent>(Channel.BUFFERED)

    /** Whether this session is active */
    private val active = AtomicBoolean(true)

    /** Session creation timestamp */
    val createdAt: Long = System.currentTimeMillis()

    /** Last activity timestamp */
    private val lastActivityAt = AtomicLong(System.currentTimeMillis())

    /**
     * Handle an incoming JSON-RPC message.
     *
     * @param message The JSON-RPC message string
     * @return Response message or null for notifications
     */
    suspend fun handleMessage(message: String): String? {
        lastActivityAt.set(System.currentTimeMillis())

        return try {
            protocolHandler.handleRequest(message)
        } catch (e: Exception) {
            logger.error("Failed to handle MCP message in session $sessionId", e)
            mapper.writeValueAsString(
                JsonRpcResponse(
                    id = null,
                    error = JsonRpcError.internalError(e.message ?: "Unknown error")
                )
            )
        }
    }

    /**
     * Send an SSE event to the client.
     *
     * @param event The event to send
     */
    suspend fun sendEvent(event: SSEEvent) {
        if (active.get()) {
            eventChannel.send(event)
        }
    }

    /**
     * Send a JSON-RPC notification to the client.
     *
     * @param method Notification method name
     * @param params Optional notification parameters
     */
    suspend fun sendNotification(method: String, params: Any? = null) {
        val notification = protocolHandler.createNotification(method, params)
        sendEvent(SSEEvent(data = notification))
    }

    /**
     * Get the event flow for SSE streaming.
     */
    fun eventFlow(): Flow<SSEEvent> = eventChannel.receiveAsFlow()

    /**
     * Check if the session is active.
     */
    fun isActive(): Boolean = active.get()

    /**
     * Get the last activity timestamp.
     */
    fun getLastActivityTime(): Long = lastActivityAt.get()

    /**
     * Close this session.
     */
    fun close() {
        if (active.compareAndSet(true, false)) {
            eventChannel.close()
            logger.info("MCP SSE session closed: $sessionId")
        }
    }
}

/**
 * SSE Event data structure.
 */
data class SSEEvent(
    val id: String? = null,
    val event: String? = null,
    val data: String,
    val retry: Int? = null,
) {
    /**
     * Format the event for SSE transmission.
     */
    fun format(): String {
        val sb = StringBuilder()
        id?.let { sb.append("id: $it\n") }
        event?.let { sb.append("event: $it\n") }
        retry?.let { sb.append("retry: $it\n") }
        // Data can be multiline, each line needs "data: " prefix
        data.lines().forEach { line ->
            sb.append("data: $line\n")
        }
        sb.append("\n")
        return sb.toString()
    }
}

/**
 * Manager for MCP SSE sessions.
 */
class MCPSSESessionManager {
    companion object {
        /** Default session timeout: 30 minutes */
        const val DEFAULT_SESSION_TIMEOUT_MS = 30 * 60 * 1000L
        /** Cleanup task interval: 1 minute */
        const val CLEANUP_INTERVAL_MS = 60_000L
    }

    private val logger = getLogger(this)

    /** Active sessions indexed by session ID */
    private val sessions = ConcurrentHashMap<String, MCPSSESession>()

    /** Session timeout in milliseconds (default: 30 minutes) */
    var sessionTimeoutMs: Long = DEFAULT_SESSION_TIMEOUT_MS

    /** Cleanup job */
    private var cleanupJob: Job? = null

    /** Coroutine scope for background tasks */
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    init {
        startCleanupTask()
    }

    /**
     * Create a new SSE session.
     *
     * @return The new session
     */
    fun createSession(): MCPSSESession {
        val sessionId = generateSessionId()
        val session = MCPSSESession(sessionId)
        sessions[sessionId] = session
        logger.info("Created MCP SSE session: $sessionId")
        return session
    }

    /**
     * Get an existing session by ID.
     *
     * @param sessionId The session ID
     * @return The session or null if not found
     */
    fun getSession(sessionId: String): MCPSSESession? {
        return sessions[sessionId]
    }

    /**
     * Remove a session.
     *
     * @param sessionId The session ID to remove
     * @return true if removed, false if not found
     */
    fun removeSession(sessionId: String): Boolean {
        val session = sessions.remove(sessionId)
        session?.close()
        return session != null
    }

    /**
     * Get all active sessions.
     */
    fun getAllSessions(): List<MCPSSESession> {
        return sessions.values.toList()
    }

    /**
     * Get the number of active sessions.
     */
    fun getSessionCount(): Int = sessions.size

    /**
     * Broadcast a notification to all active sessions.
     *
     * @param method Notification method name
     * @param params Optional notification parameters
     */
    suspend fun broadcastNotification(method: String, params: Any? = null) {
        sessions.values.forEach { session ->
            if (session.isActive()) {
                try {
                    session.sendNotification(method, params)
                } catch (e: Exception) {
                    logger.warn("Failed to broadcast to session ${session.sessionId}", e)
                }
            }
        }
    }

    /**
     * Shutdown all sessions and stop background tasks.
     */
    fun shutdown() {
        cleanupJob?.cancel()
        sessions.values.forEach { it.close() }
        sessions.clear()
        scope.cancel()
        logger.info("MCP SSE session manager shutdown")
    }

    private fun generateSessionId(): String {
        return "mcp-${System.currentTimeMillis()}-${Random.nextInt(100000)}"
    }

    private fun startCleanupTask() {
        cleanupJob = scope.launch {
            while (isActive) {
                delay(CLEANUP_INTERVAL_MS)
                cleanupExpiredSessions()
            }
        }
    }

    private fun cleanupExpiredSessions() {
        val now = System.currentTimeMillis()
        val expiredSessions = sessions.entries.filter { (_, session) ->
            !session.isActive() || (now - session.getLastActivityTime() > sessionTimeoutMs)
        }

        expiredSessions.forEach { (sessionId, session) ->
            sessions.remove(sessionId)
            session.close()
            logger.info("Cleaned up expired MCP SSE session: $sessionId")
        }
    }
}

// ============================================================================
// Stdio Transport
// ============================================================================

/**
 * Stdio transport for MCP communication.
 *
 * This transport reads JSON-RPC messages from stdin and writes responses to stdout,
 * suitable for command-line tools and process-based integration.
 */
class MCPStdioTransport(
    private val protocolHandler: MCPProtocolHandler = MCPProtocolHandler(),
    private val input: BufferedReader = System.`in`.bufferedReader(),
    private val output: PrintWriter = PrintWriter(System.out, true),
) {
    private val logger = getLogger(this)
    private val mapper = jacksonObjectMapper()
    private val running = AtomicBoolean(false)

    /**
     * Start the stdio transport loop.
     *
     * This method blocks and continuously reads from stdin until stopped.
     */
    suspend fun start() {
        if (!running.compareAndSet(false, true)) {
            logger.warn("MCP stdio transport is already running")
            return
        }

        logger.info("MCP stdio transport started")

        try {
            while (running.get()) {
                val line = withContext(Dispatchers.IO) {
                    input.readLine()
                } ?: break // EOF

                if (line.isBlank()) continue

                try {
                    val response = protocolHandler.handleRequest(line)
                    if (response != null) {
                        output.println(response)
                        output.flush()
                    }
                } catch (e: Exception) {
                    val errorResponse = JsonRpcResponse(
                        id = null,
                        error = JsonRpcError.internalError(e.message ?: "Unknown error")
                    )
                    output.println(mapper.writeValueAsString(errorResponse))
                    output.flush()
                }
            }
        } finally {
            running.set(false)
            logger.info("MCP stdio transport stopped")
        }
    }

    /**
     * Stop the stdio transport.
     */
    fun stop() {
        running.set(false)
    }

    /**
     * Check if the transport is running.
     */
    fun isRunning(): Boolean = running.get()

    /**
     * Send a notification to stdout.
     *
     * @param method Notification method name
     * @param params Optional notification parameters
     */
    fun sendNotification(method: String, params: Any? = null) {
        val notification = protocolHandler.createNotification(method, params)
        output.println(notification)
        output.flush()
    }
}

// ============================================================================
// MCP Server
// ============================================================================

/**
 * MCP Server that supports multiple transports.
 *
 * This is the main entry point for running an MCP server that can handle
 * both stdio and SSE-based clients.
 */
class MCPServer(
    val name: String = "Browser4",
    val version: String = "4.2.0",
) {
    private val logger = getLogger(this)

    /** SSE session manager */
    val sseSessionManager = MCPSSESessionManager()

    /** Stdio transport (lazy initialized) */
    private var stdioTransport: MCPStdioTransport? = null

    /** Server running state */
    private val running = AtomicBoolean(false)

    /**
     * Start the MCP server with stdio transport.
     *
     * This method blocks until the transport is stopped.
     */
    suspend fun startStdio() {
        if (!running.compareAndSet(false, true)) {
            logger.warn("MCP server is already running")
            return
        }

        logger.info("Starting MCP server (stdio mode): $name v$version")

        val transport = MCPStdioTransport(
            protocolHandler = MCPProtocolHandler(
                serverName = name,
                serverVersion = version
            )
        )
        stdioTransport = transport

        try {
            transport.start()
        } finally {
            running.set(false)
            stdioTransport = null
        }
    }

    /**
     * Create a new SSE session.
     *
     * @return The new session
     */
    fun createSSESession(): MCPSSESession {
        return sseSessionManager.createSession()
    }

    /**
     * Get an SSE session by ID.
     */
    fun getSSESession(sessionId: String): MCPSSESession? {
        return sseSessionManager.getSession(sessionId)
    }

    /**
     * Stop the server.
     */
    fun stop() {
        stdioTransport?.stop()
        sseSessionManager.shutdown()
        running.set(false)
        logger.info("MCP server stopped")
    }

    /**
     * Check if the server is running.
     */
    fun isRunning(): Boolean = running.get()

    /**
     * Broadcast a notification to all SSE clients.
     */
    suspend fun broadcastNotification(method: String, params: Any? = null) {
        sseSessionManager.broadcastNotification(method, params)
    }
}

/**
 * Singleton instance of the MCP server.
 */
object MCPServerInstance {
    val server: MCPServer by lazy { MCPServer() }
}
