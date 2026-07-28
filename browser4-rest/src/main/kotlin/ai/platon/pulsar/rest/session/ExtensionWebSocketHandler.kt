package ai.platon.pulsar.rest.session

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.handler.TextWebSocketHandler
import org.springframework.web.socket.adapter.NativeWebSocketSession
import java.io.IOException
import java.net.URI
import java.time.Duration
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

            // Disable the Jetty connector's 30s idle timeout on this
            // WebSocket connection.  Sending pings keeps the Chrome side
            // alive, but Jetty's idle timeout is based on *incoming* data
            // — outbound pings don't reset it.  Setting to 5 minutes
            // prevents the server from closing the connection while idle.
            configureIdleTimeout(session)

            // Schedule periodic keepalive pings to prevent the WebSocket from
            // being closed by idle-timeout on the Chrome side.
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

    /**
     * Sets the Jetty WebSocket idle timeout to 5 minutes on the native session.
     *
     * Jetty's connector enforces a 30s idle timeout on connections by default.
     * This timeout is based on *incoming* data — outbound pings (sent by
     * [startPing]) do not reset it.  Without this, the server closes the
     * WebSocket after 30s of not receiving any message from the extension.
     */
    private fun configureIdleTimeout(wsSession: WebSocketSession) {
        try {
            val native = (wsSession as? NativeWebSocketSession)?.nativeSession
            if (native != null) {
                // Use reflection to avoid a hard compile-time dependency on
                // Jetty's Session class (the Jetty jar is present at runtime
                // but we keep the import surface clean).
                val setIdleTimeout = native.javaClass.getMethod("setIdleTimeout", Duration::class.java)
                setIdleTimeout.invoke(native, Duration.ofMinutes(5))
                logger.debug("Set idle timeout to 5min on native session | class={}", native.javaClass.simpleName)
            }
        } catch (e: Exception) {
            logger.debug("Could not configure idle timeout on native session: {}", e.message)
        }
    }

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
