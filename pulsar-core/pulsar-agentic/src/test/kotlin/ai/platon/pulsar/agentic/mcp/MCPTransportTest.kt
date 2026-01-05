package ai.platon.pulsar.agentic.mcp

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Tests for MCP Transport layer.
 */
class MCPTransportTest {

    // ========================================================================
    // SSE Session Tests
    // ========================================================================

    @Test
    fun `test SSE session creation`() {
        val session = MCPSSESession("test-session-1")

        assertEquals("test-session-1", session.sessionId)
        assertTrue(session.isActive())
        assertTrue(session.createdAt > 0)
    }

    @Test
    fun `test SSE session handles message`() = runBlocking {
        val session = MCPSSESession("test-session-2")

        val request = """
            {
                "jsonrpc": "2.0",
                "id": 1,
                "method": "ping"
            }
        """.trimIndent()

        val response = session.handleMessage(request)

        assertNotNull(response)
        assertTrue(response!!.contains("\"result\""))
    }

    @Test
    fun `test SSE session handles invalid message`() = runBlocking {
        val session = MCPSSESession("test-session-3")

        val response = session.handleMessage("invalid json")

        assertNotNull(response)
        assertTrue(response!!.contains("\"error\""))
    }

    @Test
    fun `test SSE session close`() {
        val session = MCPSSESession("test-session-4")

        assertTrue(session.isActive())
        session.close()
        assertFalse(session.isActive())
    }

    @Test
    fun `test SSE session event sending`() = runBlocking {
        val session = MCPSSESession("test-session-5")

        // Launch a coroutine to collect events
        val events = mutableListOf<SSEEvent>()
        val collectJob = launch {
            session.eventFlow().take(2).toList(events)
        }

        // Send some events
        delay(50)
        session.sendEvent(SSEEvent(data = "event1"))
        session.sendEvent(SSEEvent(data = "event2"))

        // Wait for collection
        collectJob.join()

        assertEquals(2, events.size)
        assertEquals("event1", events[0].data)
        assertEquals("event2", events[1].data)

        session.close()
    }

    // ========================================================================
    // SSE Event Tests
    // ========================================================================

    @Test
    fun `test SSE event format basic`() {
        val event = SSEEvent(data = "hello world")
        val formatted = event.format()

        assertTrue(formatted.contains("data: hello world"))
        assertTrue(formatted.endsWith("\n\n"))
    }

    @Test
    fun `test SSE event format with all fields`() {
        val event = SSEEvent(
            id = "123",
            event = "message",
            data = "test data",
            retry = 5000
        )
        val formatted = event.format()

        assertTrue(formatted.contains("id: 123"))
        assertTrue(formatted.contains("event: message"))
        assertTrue(formatted.contains("data: test data"))
        assertTrue(formatted.contains("retry: 5000"))
    }

    @Test
    fun `test SSE event format multiline data`() {
        val event = SSEEvent(data = "line1\nline2\nline3")
        val formatted = event.format()

        assertTrue(formatted.contains("data: line1"))
        assertTrue(formatted.contains("data: line2"))
        assertTrue(formatted.contains("data: line3"))
    }

    // ========================================================================
    // SSE Session Manager Tests
    // ========================================================================

    @Test
    fun `test session manager creates session`() {
        val manager = MCPSSESessionManager()

        val session = manager.createSession()

        assertNotNull(session)
        assertTrue(session.sessionId.startsWith("mcp-"))
        assertEquals(1, manager.getSessionCount())

        manager.shutdown()
    }

    @Test
    fun `test session manager retrieves session`() {
        val manager = MCPSSESessionManager()

        val session = manager.createSession()
        val retrieved = manager.getSession(session.sessionId)

        assertEquals(session, retrieved)

        manager.shutdown()
    }

    @Test
    fun `test session manager removes session`() {
        val manager = MCPSSESessionManager()

        val session = manager.createSession()
        assertEquals(1, manager.getSessionCount())

        val removed = manager.removeSession(session.sessionId)
        assertTrue(removed)
        assertEquals(0, manager.getSessionCount())

        manager.shutdown()
    }

    @Test
    fun `test session manager returns null for unknown session`() {
        val manager = MCPSSESessionManager()

        val session = manager.getSession("nonexistent")
        assertNull(session)

        manager.shutdown()
    }

    @Test
    fun `test session manager lists all sessions`() {
        val manager = MCPSSESessionManager()

        manager.createSession()
        manager.createSession()
        manager.createSession()

        val sessions = manager.getAllSessions()
        assertEquals(3, sessions.size)

        manager.shutdown()
    }

    @Test
    fun `test session manager broadcast notification`() = runBlocking {
        val manager = MCPSSESessionManager()

        val session1 = manager.createSession()
        val session2 = manager.createSession()

        // Collect events
        val events1 = mutableListOf<SSEEvent>()
        val events2 = mutableListOf<SSEEvent>()

        val job1 = launch { session1.eventFlow().take(1).toList(events1) }
        val job2 = launch { session2.eventFlow().take(1).toList(events2) }

        delay(50)
        manager.broadcastNotification("notifications/test", mapOf("message" to "broadcast"))

        job1.join()
        job2.join()

        assertEquals(1, events1.size)
        assertEquals(1, events2.size)

        manager.shutdown()
    }

    @Test
    fun `test session manager shutdown closes all sessions`() {
        val manager = MCPSSESessionManager()

        val session1 = manager.createSession()
        val session2 = manager.createSession()

        assertTrue(session1.isActive())
        assertTrue(session2.isActive())

        manager.shutdown()

        // Sessions should be closed after shutdown
        assertFalse(session1.isActive())
        assertFalse(session2.isActive())
        assertEquals(0, manager.getSessionCount())
    }

    // ========================================================================
    // MCP Server Tests
    // ========================================================================

    @Test
    fun `test MCP server creation`() {
        val server = MCPServer(name = "TestServer", version = "1.0")

        assertEquals("TestServer", server.name)
        assertEquals("1.0", server.version)
        assertFalse(server.isRunning())

        server.stop()
    }

    @Test
    fun `test MCP server SSE session management`() {
        val server = MCPServer()

        val session = server.createSSESession()
        assertNotNull(session)

        val retrieved = server.getSSESession(session.sessionId)
        assertEquals(session, retrieved)

        server.stop()
    }

    @Test
    fun `test MCP server singleton instance`() {
        val instance1 = MCPServerInstance.server
        val instance2 = MCPServerInstance.server

        assertEquals(instance1, instance2)
    }
}
