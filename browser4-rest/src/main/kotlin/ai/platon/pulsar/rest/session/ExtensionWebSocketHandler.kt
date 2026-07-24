package ai.platon.pulsar.rest.session

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.handler.TextWebSocketHandler
import java.io.IOException
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Spring [TextWebSocketHandler] that accepts WebSocket connections from the
 * Browser4 Chrome Extension and routes messages to the appropriate
 * [ExtensionChromeService] via [PulsarSessionManager].
 *
 * The `{sessionId}` path variable in `/ws/extension/{sessionId}` identifies
 * which pending extension-attached session the connection belongs to.
 */
@Component
class ExtensionWebSocketHandler(
    private val sessionManager: PulsarSessionManager
) : TextWebSocketHandler() {

    private val logger = LoggerFactory.getLogger(ExtensionWebSocketHandler::class.java)

    /** Active WebSocket sessions keyed by sessionId. */
    private val wsSessions = ConcurrentHashMap<String, WebSocketSession>()

    /** Scheduled ping futures per session for keepalive. */
    private val pingFutures = ConcurrentHashMap<String, ScheduledFuture<*>>()

    /** Single-thread executor for periodic keepalive pings. */
    private val pingExecutor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "extension-ws-ping").apply { isDaemon = true }
        }

    override fun afterConnectionEstablished(session: WebSocketSession) {
        val sessionId = extractSessionId(session.uri)

        val remoteAddr = session.remoteAddress
        logger.info("Extension WebSocket connected | sessionId={} | remote={}", sessionId, remoteAddr)

        try {
            // Wrap the Spring WebSocket session as an ExtensionMessageSender
            // and create the ExtensionChromeService + bind to the managed session.
            val sender = SpringWebSocketMessageSender(session)
            wsSessions[sessionId] = session

            sessionManager.onExtensionConnected(sessionId, sender)

            // Schedule periodic keepalive pings to prevent the WebSocket from
            // being closed by idle-timeout on the Jetty server or Chrome side.
            startPing(sessionId, session)
        } catch (e: Exception) {
            logger.warn("Failed to bind extension connection | sessionId={} | {}", sessionId, e.message, e)
            try { session.close(CloseStatus.SERVER_ERROR) } catch (_: Exception) {}
            wsSessions.remove(sessionId)
        }
    }

    override fun handleTextMessage(session: WebSocketSession, message: TextMessage) {
        val sessionId = extractSessionId(session.uri)
        sessionManager.routeExtensionMessage(sessionId, message.payload)
    }

    override fun handleTransportError(session: WebSocketSession, exception: Throwable) {
        val sessionId = extractSessionId(session.uri)
        logger.warn("Extension WebSocket transport error | sessionId={} | {}", sessionId, exception.message)

        // Transport errors typically mean the connection is dead.  Cancel the
        // keepalive ping and clean up session state so the CLI doesn't see a
        // zombie "Active" session with a broken transport.
        cancelPing(sessionId)
        wsSessions.remove(sessionId)
        sessionManager.onExtensionDisconnected(sessionId)
    }

    override fun afterConnectionClosed(session: WebSocketSession, status: CloseStatus) {
        val sessionId = extractSessionId(session.uri)
        logger.info(
            "Extension WebSocket disconnected | sessionId={} | code={} | reason={}",
            sessionId, status.code, status.reason
        )
        cancelPing(sessionId)
        wsSessions.remove(sessionId)
        sessionManager.onExtensionDisconnected(sessionId)
    }

    // ------------------------------------------------------------------
    // Keepalive
    // ------------------------------------------------------------------

    private fun startPing(sessionId: String, wsSession: WebSocketSession) {
        val future = pingExecutor.scheduleWithFixedDelay(
            { sendPing(sessionId, wsSession) },
            25,
            25,
            TimeUnit.SECONDS
        )
        pingFutures[sessionId] = future
    }

    private fun sendPing(sessionId: String, wsSession: WebSocketSession) {
        if (!wsSession.isOpen) {
            cancelPing(sessionId)
            return
        }
        try {
            synchronized(wsSession) {
                if (wsSession.isOpen) {
                    wsSession.sendMessage(TextMessage("""{"method":"ping"}"""))
                }
            }
        } catch (e: IOException) {
            logger.debug("Failed to send ping on extension WebSocket | sessionId={} | {}", sessionId, e.message)
        }
    }

    private fun cancelPing(sessionId: String) {
        pingFutures.remove(sessionId)?.cancel(true)
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun extractSessionId(uri: URI?): String {
        val path = uri?.path ?: throw IllegalArgumentException("No path in WebSocket URI")
        return path.substringAfterLast('/').takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("Missing sessionId in WebSocket path: $path")
    }
}
