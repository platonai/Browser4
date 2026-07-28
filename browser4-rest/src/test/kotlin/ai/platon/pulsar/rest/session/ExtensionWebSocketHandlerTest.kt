package ai.platon.pulsar.rest.session

import ai.platon.browser4.chrome.protocol.transport.ExtensionMessageSender
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.verify
import org.springframework.web.socket.WebSocketSession
import java.net.URI

/**
 * Unit tests for the extension WebSocket wiring.
 *
 * [ExtensionWebSocketHandler] inherits protected lifecycle methods from
 * Spring's [org.springframework.web.socket.handler.TextWebSocketHandler],
 * so we test the [SpringWebSocketMessageSender] adapter and the
 * [PulsarSessionManager] integration points directly.
 */
class ExtensionWebSocketHandlerTest {

    @Mock
    private lateinit var sessionManager: PulsarSessionManager

    @Mock
    private lateinit var webSocketSession: WebSocketSession

    private lateinit var handler: ExtensionWebSocketHandler

    private val sessionId = "test-session-123"

    @BeforeEach
    fun setUp() {
        MockitoAnnotations.openMocks(this)

        `when`(webSocketSession.uri).thenReturn(
            URI.create("ws://127.0.0.1:8182/ws/extension/$sessionId")
        )
        `when`(webSocketSession.isOpen).thenReturn(true)

        handler = ExtensionWebSocketHandler(sessionManager)
    }

    // ------------------------------------------------------------------
    // Connection lifecycle (via afterConnectionEstablished)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("afterConnectionEstablished creates sender and binds to session")
    fun `afterConnectionEstablished binds session`() {
        handler.afterConnectionEstablished(webSocketSession)

        val captor = argumentCaptor<ExtensionMessageSender>()
        verify(sessionManager).onExtensionConnected(
            org.mockito.kotlin.eq(sessionId),
            captor.capture()
        )

        val sender = captor.firstValue
        assertNotNull(sender)
        assertTrue(sender.isOpen)
    }

    @Test
    @DisplayName("afterConnectionClosed disconnects from session")
    fun `afterConnectionClosed disconnects session`() {
        handler.afterConnectionEstablished(webSocketSession)
        handler.afterConnectionClosed(webSocketSession, org.springframework.web.socket.CloseStatus.NORMAL)

        verify(sessionManager).onExtensionDisconnected(sessionId)
    }

    @Test
    @DisplayName("afterConnectionEstablished closes WS on bind failure")
    fun `closes websocket on bind failure`() {
        `when`(sessionManager.onExtensionConnected(any(), any())).thenThrow(
            IllegalStateException("No pending connection")
        )

        handler.afterConnectionEstablished(webSocketSession)
        verify(webSocketSession, org.mockito.Mockito.atLeastOnce()).close(
            any<org.springframework.web.socket.CloseStatus>()
        )
    }

    // ------------------------------------------------------------------
    // URI extraction (tested via afterConnectionEstablished)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("throws on URI without sessionId in path")
    fun `throws on missing sessionId`() {
        `when`(webSocketSession.uri).thenReturn(
            URI.create("ws://127.0.0.1:8182/ws/extension/")
        )

        assertThrows(IllegalArgumentException::class.java) {
            handler.afterConnectionEstablished(webSocketSession)
        }
    }

    @Test
    @DisplayName("throws on null URI")
    fun `throws on null URI`() {
        `when`(webSocketSession.uri).thenReturn(null)

        assertThrows(IllegalArgumentException::class.java) {
            handler.afterConnectionEstablished(webSocketSession)
        }
    }

    // ------------------------------------------------------------------
    // SpringWebSocketMessageSender
    // ------------------------------------------------------------------

    @Test
    @DisplayName("sender delegates send to WebSocketSession")
    fun `sender sends text message`() {
        handler.afterConnectionEstablished(webSocketSession)

        val captor = argumentCaptor<ExtensionMessageSender>()
        verify(sessionManager).onExtensionConnected(
            org.mockito.kotlin.eq(sessionId),
            captor.capture()
        )

        val sender = captor.firstValue
        sender.sendMessage("hello")

        val messageCaptor = argumentCaptor<org.springframework.web.socket.TextMessage>()
        verify(webSocketSession).sendMessage(messageCaptor.capture())
        assertEquals("hello", messageCaptor.firstValue.payload)
    }

    @Test
    @DisplayName("sender close closes underlying WebSocketSession")
    fun `sender close closes websocket`() {
        handler.afterConnectionEstablished(webSocketSession)

        val captor = argumentCaptor<ExtensionMessageSender>()
        verify(sessionManager).onExtensionConnected(
            org.mockito.kotlin.eq(sessionId),
            captor.capture()
        )

        val sender = captor.firstValue
        sender.close()
        verify(webSocketSession).close()
    }

    @Test
    @DisplayName("sender isOpen delegates to WebSocketSession")
    fun `sender isOpen delegates to session`() {
        `when`(webSocketSession.isOpen).thenReturn(true)
        handler.afterConnectionEstablished(webSocketSession)

        val captor = argumentCaptor<ExtensionMessageSender>()
        verify(sessionManager).onExtensionConnected(
            org.mockito.kotlin.eq(sessionId),
            captor.capture()
        )

        assertTrue(captor.firstValue.isOpen)
    }

    @Test
    @DisplayName("sender sendMessage is no-op when closed")
    fun `sender sendMessage when closed`() {
        `when`(webSocketSession.isOpen).thenReturn(false)
        handler.afterConnectionEstablished(webSocketSession)

        val captor = argumentCaptor<ExtensionMessageSender>()
        verify(sessionManager).onExtensionConnected(
            org.mockito.kotlin.eq(sessionId),
            captor.capture()
        )

        val sender = captor.firstValue
        assertFalse(sender.isOpen)
        // sendMessage should silently return when not open
        sender.sendMessage("should not throw")
    }
}
