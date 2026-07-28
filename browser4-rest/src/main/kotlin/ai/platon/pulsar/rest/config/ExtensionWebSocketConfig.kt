package ai.platon.pulsar.rest.config

import ai.platon.pulsar.rest.session.ExtensionWebSocketHandler
import ai.platon.pulsar.rest.session.PulsarSessionManager
import jakarta.annotation.PostConstruct
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.socket.config.annotation.EnableWebSocket
import org.springframework.web.socket.config.annotation.WebSocketConfigurer
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry

/**
 * Registers the WebSocket endpoint that the Browser4 Chrome Extension connects
 * to for CDP relay.
 *
 * The endpoint is exposed at `/ws/extension/{sessionId}` on the same Jetty
 * server that serves the REST API.  The Chrome Extension only connects to
 * loopback addresses (127.0.0.1 / [::1]) for security.
 */
@Configuration
@EnableWebSocket
class ExtensionWebSocketConfig(
    private val sessionManager: PulsarSessionManager
) : WebSocketConfigurer {

    @Value("\${server.port:8182}")
    private var serverPort: Int = 8182

    @PostConstruct
    fun init() {
        // Propagate the actual server port to the session manager so it can
        // construct correct ws:// URLs for extension connections.
        sessionManager.serverPort = serverPort
    }

    override fun registerWebSocketHandlers(registry: WebSocketHandlerRegistry) {
        registry.addHandler(extensionWebSocketHandler(), "/ws/extension/{sessionId}")
            .setAllowedOrigins("*") // extension connects from chrome-extension:// origin
    }

    @Bean
    fun extensionWebSocketHandler(): ExtensionWebSocketHandler {
        return ExtensionWebSocketHandler(sessionManager)
    }
}
