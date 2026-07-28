package ai.platon.browser4.chrome.protocol.transport

/**
 * Abstraction over a WebSocket connection to the Browser4 Chrome Extension.
 *
 * This interface lives in browser4-core so that [ExtensionChromeService] and
 * [ExtensionDevToolsService] do not depend on Spring WebSocket types.  The
 * concrete implementation in browser4-rest wraps a
 * `org.springframework.web.socket.WebSocketSession`.
 *
 * Incoming message routing is handled externally via
 * [ExtensionChromeService.handleIncomingMessage] — this interface only
 * provides outgoing send capability.
 */
interface ExtensionMessageSender {
    /** True if the underlying connection is still open. */
    val isOpen: Boolean

    /** Send a text message to the extension. */
    fun sendMessage(text: String)

    /** Close the connection. */
    fun close()
}
