package ai.platon.pulsar.rest.api.controller

import ai.platon.pulsar.agentic.mcp.*
import ai.platon.pulsar.common.getLogger
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.reactive.asPublisher
import kotlinx.coroutines.runBlocking
import org.springframework.http.MediaType
import org.springframework.http.codec.ServerSentEvent
import org.springframework.web.bind.annotation.*
import reactor.core.publisher.Flux
import java.util.concurrent.ConcurrentHashMap

/**
 * SSE (Server-Sent Events) Controller for MCP communication.
 *
 * Provides SSE-based transport for MCP protocol, enabling real-time
 * bidirectional communication with web-based clients.
 *
 * The MCP SSE transport uses:
 * - SSE for server-to-client messages (responses, notifications)
 * - POST requests for client-to-server messages (requests)
 *
 * @see <a href="https://modelcontextprotocol.io/specification/basic/transports#http-with-sse">MCP SSE Transport</a>
 * @author Vincent Zhang, ivincent.zhang@gmail.com, platon.ai
 */
@RestController
@CrossOrigin
@RequestMapping("api/mcp/sse")
class MCPSSEController {
    private val logger = getLogger(this)

    /** SSE session manager */
    private val sessionManager = MCPSSESessionManager()

    /** Session to endpoint mapping for message routing */
    private val sessionEndpoints = ConcurrentHashMap<String, String>()

    // ========================================================================
    // Session Management
    // ========================================================================

    /**
     * Create a new MCP SSE session and return the SSE stream.
     *
     * This endpoint establishes an SSE connection for receiving server messages.
     * The client should use the returned session ID for subsequent POST requests.
     *
     * @return SSE stream of MCP messages
     */
    @GetMapping(
        value = ["/connect"],
        produces = [MediaType.TEXT_EVENT_STREAM_VALUE]
    )
    fun connect(): Flux<ServerSentEvent<String>> {
        val session = sessionManager.createSession()
        val sessionId = session.sessionId

        logger.info("MCP SSE client connected: $sessionId")

        // Send initial endpoint message
        val endpointUrl = "/api/mcp/sse/message?sessionId=$sessionId"
        sessionEndpoints[sessionId] = endpointUrl

        // Convert the session's event flow to a Flux
        val eventFlux = Flux.from(
            session.eventFlow().map { event ->
                val builder = ServerSentEvent.builder<String>()
                event.id?.let { builder.id(it) }
                builder.event(event.event ?: "message")
                builder.data(event.data)
                builder.build()
            }.asPublisher()
        )

        // Send the endpoint URL as the first message
        val initialEvent = ServerSentEvent.builder<String>()
            .event("endpoint")
            .data(endpointUrl)
            .build()

        return Flux.concat(
            Flux.just(initialEvent),
            eventFlux
        ).doOnCancel {
            logger.info("MCP SSE client disconnected: $sessionId")
            sessionManager.removeSession(sessionId)
            sessionEndpoints.remove(sessionId)
        }.doOnTerminate {
            sessionManager.removeSession(sessionId)
            sessionEndpoints.remove(sessionId)
        }
    }

    /**
     * Handle incoming MCP messages for a session.
     *
     * This endpoint receives JSON-RPC requests from the client and returns responses.
     *
     * @param sessionId The session ID from the SSE connection
     * @param message The JSON-RPC message
     * @return JSON-RPC response
     */
    @PostMapping(
        value = ["/message"],
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE]
    )
    fun handleMessage(
        @RequestParam sessionId: String,
        @RequestBody message: String
    ): String {
        val session = sessionManager.getSession(sessionId)
            ?: return createErrorResponse("Session not found: $sessionId")

        return runBlocking {
            try {
                session.handleMessage(message) ?: "{}"
            } catch (e: Exception) {
                logger.error("Failed to handle MCP message for session $sessionId", e)
                createErrorResponse(e.message ?: "Unknown error")
            }
        }
    }

    /**
     * Close an MCP SSE session.
     *
     * @param sessionId The session ID to close
     * @return Success message
     */
    @PostMapping("/disconnect")
    fun disconnect(@RequestParam sessionId: String): Map<String, Any> {
        val removed = sessionManager.removeSession(sessionId)
        sessionEndpoints.remove(sessionId)

        return if (removed) {
            logger.info("MCP SSE session closed by client: $sessionId")
            mapOf("success" to true, "sessionId" to sessionId)
        } else {
            mapOf("success" to false, "error" to "Session not found")
        }
    }

    // ========================================================================
    // Session Information
    // ========================================================================

    /**
     * Get information about a specific session.
     *
     * @param sessionId The session ID
     * @return Session info or error
     */
    @GetMapping("/session/{sessionId}")
    fun getSessionInfo(@PathVariable sessionId: String): Map<String, Any> {
        val session = sessionManager.getSession(sessionId)
            ?: return mapOf("error" to "Session not found", "sessionId" to sessionId)

        return mapOf(
            "sessionId" to session.sessionId,
            "active" to session.isActive(),
            "createdAt" to session.createdAt,
            "lastActivityAt" to session.getLastActivityTime(),
            "endpoint" to (sessionEndpoints[sessionId] ?: "unknown")
        )
    }

    /**
     * List all active sessions (admin endpoint).
     *
     * @return List of session summaries
     */
    @GetMapping("/sessions")
    fun listSessions(): Map<String, Any> {
        val sessions = sessionManager.getAllSessions()
        return mapOf(
            "count" to sessions.size,
            "sessions" to sessions.map { session ->
                mapOf(
                    "sessionId" to session.sessionId,
                    "active" to session.isActive(),
                    "createdAt" to session.createdAt
                )
            }
        )
    }

    /**
     * Get SSE transport statistics.
     *
     * @return Transport stats
     */
    @GetMapping("/stats")
    fun getStats(): Map<String, Any> {
        return mapOf(
            "activeSessions" to sessionManager.getSessionCount(),
            "transport" to "sse"
        )
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private val mapper = jacksonObjectMapper()

    private fun createErrorResponse(message: String): String {
        val response = JsonRpcResponse(
            id = null,
            error = JsonRpcError.internalError(message)
        )
        return mapper.writeValueAsString(response)
    }
}
