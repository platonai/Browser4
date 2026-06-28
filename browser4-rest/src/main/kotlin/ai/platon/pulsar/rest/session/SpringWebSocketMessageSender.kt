package ai.platon.pulsar.rest.session

import ai.platon.browser4.chrome.handler.transport.ExtensionMessageSender
import org.slf4j.LoggerFactory
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import java.io.IOException

/**
 * Adapts a Spring [WebSocketSession] to the [ExtensionMessageSender] interface
 * defined in browser4-core.
 *
 * Sends text messages directly on the Spring [WebSocketSession].  Incoming
 * messages from the extension are routed through
 * [PulsarSessionManager.routeExtensionMessage] rather than through this
 * adapter.
 */
class SpringWebSocketMessageSender(
    private val wsSession: WebSocketSession
) : ExtensionMessageSender {

    private val logger = LoggerFactory.getLogger(SpringWebSocketMessageSender::class.java)

    override val isOpen: Boolean get() = wsSession.isOpen

    override fun sendMessage(text: String) {
        if (!isOpen) return
        try {
            synchronized(wsSession) {
                wsSession.sendMessage(TextMessage(text))
            }
        } catch (e: IOException) {
            logger.warn("Failed to send WebSocket message: {}", e.message)
        }
    }

    override fun close() {
        if (isOpen) {
            try { wsSession.close() } catch (_: Exception) {}
        }
    }
}
