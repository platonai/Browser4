package ai.platon.pulsar.rest.openapi

import ai.platon.pulsar.agentic.AgenticSession
import ai.platon.pulsar.agentic.PerceptiveAgent
import ai.platon.pulsar.rest.openapi.service.SessionManager
import kotlinx.coroutines.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Tag
import org.mockito.Mockito
import java.util.concurrent.atomic.AtomicInteger

/**
 * Unit tests for session concurrency behavior.
 * Tests the last-request-wins strategy for same-session concurrent operations.
 */
@Tag("UnitTest")
class SessionConcurrencyTest {

    private fun createTestSession(sessionId: String): SessionManager.ManagedSession {
        val mockAgenticSession = Mockito.mock(AgenticSession::class.java)
        val mockAgent = Mockito.mock(PerceptiveAgent::class.java)
        Mockito.`when`(mockAgenticSession.companionAgent).thenReturn(mockAgent)
        
        return SessionManager.ManagedSession(
            sessionId = sessionId,
            pulsarSession = mockAgenticSession,
            capabilities = null
        )
    }

    @Test
    @DisplayName("test request ID increments correctly")
    fun testRequestIdIncrement() {
        val session = createTestSession("test-session")

        val requestId1 = session.newRequest()
        val requestId2 = session.newRequest()
        val requestId3 = session.newRequest()

        assertTrue(requestId2 > requestId1, "Request IDs should increment")
        assertTrue(requestId3 > requestId2, "Request IDs should increment")
        assertEquals(requestId1 + 1, requestId2, "Request IDs should increment by 1")
        assertEquals(requestId2 + 1, requestId3, "Request IDs should increment by 1")
    }

    @Test
    @DisplayName("test only latest request is valid")
    fun testOnlyLatestRequestIsValid() {
        val session = createTestSession("test-session")

        val requestId1 = session.newRequest()
        assertTrue(session.isLatestRequest(requestId1), "First request should be latest")

        val requestId2 = session.newRequest()
        assertFalse(session.isLatestRequest(requestId1), "First request should not be latest anymore")
        assertTrue(session.isLatestRequest(requestId2), "Second request should be latest")

        val requestId3 = session.newRequest()
        assertFalse(session.isLatestRequest(requestId1), "First request should not be latest")
        assertFalse(session.isLatestRequest(requestId2), "Second request should not be latest")
        assertTrue(session.isLatestRequest(requestId3), "Third request should be latest")
    }

    @Test
    @DisplayName("test concurrent request creation maintains order")
    fun testConcurrentRequestCreation() = runBlocking {
        val session = createTestSession("test-session")

        val requestCount = 100
        val requests = mutableListOf<Long>()

        // Create requests concurrently
        val jobs = (1..requestCount).map {
            launch(Dispatchers.Default) {
                val requestId = session.newRequest()
                synchronized(requests) {
                    requests.add(requestId)
                }
            }
        }

        jobs.joinAll()

        assertEquals(requestCount, requests.size, "All requests should be created")
        
        // All request IDs should be unique
        val uniqueRequests = requests.toSet()
        assertEquals(requestCount, uniqueRequests.size, "All request IDs should be unique")

        // The highest request ID should be the latest
        val maxRequestId = requests.maxOrNull()!!
        assertTrue(session.isLatestRequest(maxRequestId), "Highest request ID should be latest")

        // All other request IDs should not be latest
        requests.filter { it != maxRequestId }.forEach { requestId ->
            assertFalse(session.isLatestRequest(requestId), "Non-max request ID $requestId should not be latest")
        }
    }

    @Test
    @DisplayName("test concurrent operations with mutex ensures serialization")
    fun testConcurrentOperationsWithMutex() = runBlocking {
        val session = createTestSession("test-session")

        val executedRequests = mutableListOf<Long>()
        val cancelledCount = AtomicInteger(0)

        // Simulate concurrent operations
        val jobs = (1..10).map { index ->
            launch(Dispatchers.Default) {
                // Small delay to allow requests to pile up
                delay(5L * index)
                val requestId = session.newRequest()
                
                // Simulate acquiring the mutex
                session.mutex.lock()
                try {
                    // Check if still the latest request
                    if (session.isLatestRequest(requestId)) {
                        // Simulate some work
                        delay(10)
                        synchronized(executedRequests) {
                            executedRequests.add(requestId)
                        }
                    } else {
                        cancelledCount.incrementAndGet()
                    }
                } finally {
                    session.mutex.unlock()
                }
            }
        }

        jobs.joinAll()

        // At least some requests should have been cancelled
        assertTrue(cancelledCount.get() > 0, "Some requests should be cancelled")
        
        // The number of executed + cancelled should equal total
        assertEquals(10, executedRequests.size + cancelledCount.get(), 
            "Total executed + cancelled should equal total requests")
        
        // The last request in the list should be one of the executed ones
        val lastRequestId = 10L // Since we create 10 requests, the last one will be 10
        assertTrue(executedRequests.any { it >= lastRequestId - 2 }, 
            "One of the last requests should have executed")
    }

    @Test
    @DisplayName("test request supersession during execution")
    fun testRequestSupersessionDuringExecution() = runBlocking {
        val session = createTestSession("test-session")

        val executedCount = AtomicInteger(0)
        val supersededCount = AtomicInteger(0)

        // First request starts and holds the mutex
        val job1 = launch(Dispatchers.Default) {
            val requestId = session.newRequest()
            session.mutex.lock()
            try {
                // Simulate long operation
                delay(100)
                if (session.isLatestRequest(requestId)) {
                    executedCount.incrementAndGet()
                } else {
                    supersededCount.incrementAndGet()
                }
            } finally {
                session.mutex.unlock()
            }
        }

        // Give first job time to acquire mutex
        delay(20)

        // While first job is running, submit a second request
        val job2 = launch(Dispatchers.Default) {
            val requestId = session.newRequest()
            // This should supersede the first request
            session.mutex.lock()
            try {
                if (session.isLatestRequest(requestId)) {
                    executedCount.incrementAndGet()
                } else {
                    supersededCount.incrementAndGet()
                }
            } finally {
                session.mutex.unlock()
            }
        }

        listOf(job1, job2).joinAll()

        // First request should be superseded, second should execute
        assertEquals(1, executedCount.get(), "One request should execute")
        assertEquals(1, supersededCount.get(), "One request should be superseded")
    }

    @Test
    @DisplayName("test multiple sessions operate independently")
    fun testMultipleSessionsOperateIndependently() = runBlocking {
        val session1 = createTestSession("session-1")
        val session2 = createTestSession("session-2")

        // Create requests for both sessions
        val requestId1 = session1.newRequest()
        val requestId2 = session2.newRequest()

        // Each session should consider its own request as latest
        assertTrue(session1.isLatestRequest(requestId1), "Session 1 request should be latest in session 1")
        assertTrue(session2.isLatestRequest(requestId2), "Session 2 request should be latest in session 2")

        // Create more requests
        val requestId3 = session1.newRequest()
        val requestId4 = session2.newRequest()

        // Sessions should not interfere with each other
        assertFalse(session1.isLatestRequest(requestId1), "Old session 1 request should not be latest")
        assertTrue(session1.isLatestRequest(requestId3), "New session 1 request should be latest")
        assertFalse(session2.isLatestRequest(requestId2), "Old session 2 request should not be latest")
        assertTrue(session2.isLatestRequest(requestId4), "New session 2 request should be latest")
    }

    @Test
    @DisplayName("test rapid successive requests cancel all but last")
    fun testRapidSuccessiveRequestsCancelAllButLast() = runBlocking {
        val session = createTestSession("test-session")

        val requestCount = 50
        val requestIds = mutableListOf<Long>()

        // Create many requests rapidly
        repeat(requestCount) {
            val requestId = session.newRequest()
            requestIds.add(requestId)
        }

        // Only the last request should be valid
        val lastRequestId = requestIds.last()
        assertTrue(session.isLatestRequest(lastRequestId), "Last request should be latest")

        // All other requests should be invalid
        requestIds.dropLast(1).forEach { requestId ->
            assertFalse(session.isLatestRequest(requestId), "Non-last request $requestId should not be latest")
        }
    }
}
