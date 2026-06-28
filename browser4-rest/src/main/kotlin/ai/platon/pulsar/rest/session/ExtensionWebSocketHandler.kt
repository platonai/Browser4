package ai.platon.pulsar.rest.session

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.handler.TextWebSocketHandler
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

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
        } catch (e: Exception) {
            logger.error("Failed to bind extension connection | sessionId={} | {}", sessionId, e.message, e)
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
    }

    override fun afterConnectionClosed(session: WebSocketSession, status: CloseStatus) {
        val sessionId = extractSessionId(session.uri)
        logger.info("Extension WebSocket disconnected | sessionId={} | code={} | reason={}", sessionId, status.code, status.reason)
        wsSessions.remove(sessionId)
        sessionManager.onExtensionDisconnected(sessionId)
    }

    private fun extractSessionId(uri: URI?): String {
        val path = uri?.path ?: throw IllegalArgumentException("No path in WebSocket URI")
        return path.substringAfterLast('/').takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("Missing sessionId in WebSocket path: $path")
    }
}
