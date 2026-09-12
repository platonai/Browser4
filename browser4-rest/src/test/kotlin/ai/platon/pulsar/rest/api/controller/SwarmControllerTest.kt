package ai.platon.pulsar.rest.api.controller

import ai.platon.pulsar.common.B4Constants.PROFILE_MODE_CAPABILITY
import ai.platon.pulsar.common.B4Constants.SESSION_ID_CAPABILITY
import ai.platon.pulsar.common.B4Constants.SWARM_SESSION_ID
import ai.platon.pulsar.agentic.AgenticSession
import ai.platon.pulsar.agentic.tools.advanced.crawl.ScrapeRequest
import ai.platon.pulsar.agentic.tools.advanced.crawl.ScrapeResponse
import ai.platon.pulsar.agentic.tools.advanced.crawl.ScrapeStatusRequest
import ai.platon.pulsar.agentic.tools.advanced.crawl.SwarmFacade
import ai.platon.pulsar.agentic.tools.advanced.crawl.SwarmFacadeRegistry
import ai.platon.pulsar.rest.session.ManagedSession
import ai.platon.pulsar.rest.session.PulsarSessionManager
import ai.platon.pulsar.rest.session.SessionStatus
import ai.platon.pulsar.common.ResourceStatus
import ai.platon.pulsar.persist.metadata.ProtocolStatusCodes
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq

class SwarmControllerTest {

    private val facade: SwarmFacade = Mockito.mock(SwarmFacade::class.java)

    @BeforeEach
    fun registerFacade() {
        SwarmFacadeRegistry.instance.register(facade)
    }

    @AfterEach
    fun unregisterFacade() {
        SwarmFacadeRegistry.instance.unregister()
    }

    private fun newController(sessionManager: PulsarSessionManager): SwarmController {
        return SwarmController(sessionManager)
    }

    @Test
    fun openReturnsSafeSessionResponse() {
        val sessionManager = Mockito.mock(PulsarSessionManager::class.java)
        val agenticSession = Mockito.mock(AgenticSession::class.java)
        val controller = newController(sessionManager)
        val capabilities = mapOf("profileMode" to "TEMPORARY")
        val managedSession = ManagedSession(
            sessionId = SWARM_SESSION_ID,
            agenticSession = agenticSession,
            capabilities = mapOf(
                SESSION_ID_CAPABILITY to SWARM_SESSION_ID,
                PROFILE_MODE_CAPABILITY to "TEMPORARY",
                "custom" to "value",
            ),
            status = SessionStatus.ACTIVE,
            createdAt = 1L,
            lastAccessedAt = 2L,
        )

        Mockito.`when`(sessionManager.ensureSwarmSession(capabilities)).thenReturn(managedSession)

        val response = controller.open(capabilities)

        assertEquals(SWARM_SESSION_ID, response.sessionId)
        assertEquals("active", response.status)
        assertEquals("TEMPORARY", response.profileMode)
        verify(sessionManager).ensureSwarmSession(capabilities)
    }

    // -----------------------------------------------------------------
    // submit() tests
    // -----------------------------------------------------------------

    @Test
    fun closeAbortsPendingTasksAndReportsCount() {
        val sessionManager = Mockito.mock(PulsarSessionManager::class.java)
        val controller = newController(sessionManager)

        Mockito.`when`(facade.abortPendingTasks("Swarm session was closed; task dropped")).thenReturn(3)
        Mockito.`when`(sessionManager.deleteSession(SWARM_SESSION_ID)).thenReturn(true)

        val result = controller.close()

        assertEquals(true, result["closed"])
        assertEquals(3, result["abortedPendingTasks"])
        verify(facade).abortPendingTasks("Swarm session was closed; task dropped")
        verify(sessionManager).deleteSession(SWARM_SESSION_ID)
    }

    @Test
    fun submitWithBlankPayloadThrowsIllegalArgumentException() {
        val sessionManager = Mockito.mock(PulsarSessionManager::class.java)
        val controller = newController(sessionManager)

        val exception = assertThrows<IllegalArgumentException> {
            controller.submit("   ")
        }
        assertEquals("Request body must be a non-blank URL or X-SQL", exception.message)
        verify(facade, never()).submit(any<ScrapeRequest>(), anyOrNull())
    }

    @Test
    fun submitWithValidUrlReturnsUuid() {
        val sessionManager = Mockito.mock(PulsarSessionManager::class.java)
        val controller = newController(sessionManager)

        Mockito.`when`(facade.submit(any<ScrapeRequest>(), anyOrNull())).thenReturn("mock-uuid")

        val result = controller.submit("https://example.com")

        assertEquals("mock-uuid", result)
        val captor = argumentCaptor<ScrapeRequest>()
        verify(facade).submit(captor.capture(), anyOrNull())
        assertEquals(
            "select dom_base_uri(dom) as url from load_and_select('https://example.com', ':root')",
            captor.firstValue.sql
        )
    }

    @Test
    fun submitWithInvalidSqlThrowsIllegalArgumentException() {
        val sessionManager = Mockito.mock(PulsarSessionManager::class.java)
        val controller = newController(sessionManager)

        assertThrows<IllegalArgumentException> {
            controller.submit("DROP TABLE users")
        }
        verify(facade, never()).submit(any<ScrapeRequest>(), anyOrNull())
    }

    @Test
    fun submitWithoutFacadeThrowsSwarmNotInstalled() {
        val sessionManager = Mockito.mock(PulsarSessionManager::class.java)
        val controller = newController(sessionManager)
        SwarmFacadeRegistry.instance.unregister()

        assertThrows<SwarmNotInstalledException> {
            controller.submit("https://example.com")
        }
    }

    @Test
    fun submitWithUrlContainingApostropheEscapesTheSqlLiteral() {
        // Entry-page hrefs can contain apostrophes; interpolating them raw would
        // both break the statement and let the URL text escape the literal.
        val sessionManager = Mockito.mock(PulsarSessionManager::class.java)
        val controller = newController(sessionManager)

        Mockito.`when`(facade.submit(any<ScrapeRequest>(), anyOrNull())).thenReturn("mock-uuid")

        controller.submit("https://example.com/o'brien?q=it's -refresh")

        val captor = argumentCaptor<ScrapeRequest>()
        verify(facade).submit(captor.capture(), anyOrNull())
        assertEquals(
            "select dom_base_uri(dom) as url from load_and_select(" +
                "'https://example.com/o''brien?q=it''s -refresh', ':root')",
            captor.firstValue.sql
        )
    }

    @Test
    fun submitWithLoadOptionsPassesThemThroughTheSqlLiteral() {
        val sessionManager = Mockito.mock(PulsarSessionManager::class.java)
        val controller = newController(sessionManager)

        Mockito.`when`(facade.submit(any<ScrapeRequest>(), anyOrNull())).thenReturn("mock-uuid")

        controller.submit("https://example.com/p/1 -refresh -requireNotBlank #title -nMaxRetry 3")

        val captor = argumentCaptor<ScrapeRequest>()
        verify(facade).submit(captor.capture(), anyOrNull())
        assertEquals(
            "select dom_base_uri(dom) as url from load_and_select(" +
                "'https://example.com/p/1 -refresh -requireNotBlank #title -nMaxRetry 3', ':root')",
            captor.firstValue.sql
        )
    }

    // -----------------------------------------------------------------
    // submit() batch id tests
    // -----------------------------------------------------------------

    @Test
    fun submitStampsTheBatchIdOnTheRequest() {
        val sessionManager = Mockito.mock(PulsarSessionManager::class.java)
        val controller = newController(sessionManager)

        Mockito.`when`(facade.submit(any<ScrapeRequest>(), anyOrNull())).thenReturn("mock-uuid")

        controller.submit("https://example.com", "batch-42")

        val captor = argumentCaptor<ScrapeRequest>()
        verify(facade).submit(captor.capture(), eq("batch-42"))
        assertEquals("batch-42", captor.firstValue.batchId)
    }

    @Test
    fun submitWithoutBatchIdLeavesItNull() {
        val sessionManager = Mockito.mock(PulsarSessionManager::class.java)
        val controller = newController(sessionManager)

        Mockito.`when`(facade.submit(any<ScrapeRequest>(), anyOrNull())).thenReturn("mock-uuid")

        controller.submit("https://example.com")

        val captor = argumentCaptor<ScrapeRequest>()
        verify(facade).submit(captor.capture(), eq(null))
        assertEquals(null, captor.firstValue.batchId)
    }

    @Test
    fun submitTreatsBlankBatchIdAsAbsent() {
        val sessionManager = Mockito.mock(PulsarSessionManager::class.java)
        val controller = newController(sessionManager)

        Mockito.`when`(facade.submit(any<ScrapeRequest>(), anyOrNull())).thenReturn("mock-uuid")

        controller.submit("https://example.com", "   ")

        val captor = argumentCaptor<ScrapeRequest>()
        verify(facade).submit(captor.capture(), eq(null))
        assertEquals(null, captor.firstValue.batchId)
    }

    // -----------------------------------------------------------------
    // batchStatus() tests
    // -----------------------------------------------------------------

    @Test
    fun batchStatusDelegatesToFacade() {
        val sessionManager = Mockito.mock(PulsarSessionManager::class.java)
        val controller = newController(sessionManager)
        val payload = mapOf<String, Any?>("batchId" to "batch-1", "total" to 3)

        Mockito.`when`(facade.batchStatus("batch-1")).thenReturn(payload)

        val result = controller.batchStatus("batch-1")

        assertEquals(payload, result)
        verify(facade).batchStatus("batch-1")
    }

    @Test
    fun batchStatusWithBlankBatchIdThrows() {
        val sessionManager = Mockito.mock(PulsarSessionManager::class.java)
        val controller = newController(sessionManager)

        assertThrows<IllegalArgumentException> {
            controller.batchStatus("  ")
        }
        verify(facade, never()).batchStatus(Mockito.anyString())

    }

    // -----------------------------------------------------------------
    // count() tests
    // -----------------------------------------------------------------

    @Test
    fun countWithStatusCodeDelegatesToFacade() {
        val sessionManager = Mockito.mock(PulsarSessionManager::class.java)
        val controller = newController(sessionManager)

        Mockito.`when`(facade.count(200)).thenReturn(5)

        val result = controller.count(200)
        assertEquals(5, result)
        verify(facade).count(200)
    }

    @Test
    fun countWithDefaultDelegatesToFacade() {
        val sessionManager = Mockito.mock(PulsarSessionManager::class.java)
        val controller = newController(sessionManager)

        Mockito.`when`(facade.count(0)).thenReturn(10)

        val result = controller.count()
        assertEquals(10, result)
        verify(facade).count(0)
    }

    // -----------------------------------------------------------------
    // status() tests — query-param endpoint
    // -----------------------------------------------------------------

    @Test
    fun statusWithBlankUuidThrowsIllegalArgumentException() {
        val sessionManager = Mockito.mock(PulsarSessionManager::class.java)
        val controller = newController(sessionManager)

        val exception = assertThrows<IllegalArgumentException> {
            controller.status("   ")
        }
        assertEquals("uuid must not be blank", exception.message)
        verify(facade, never()).getStatus(any())
    }

    @Test
    fun statusDelegatesToFacade() {
        val sessionManager = Mockito.mock(PulsarSessionManager::class.java)
        val controller = newController(sessionManager)

        val expectedResponse = ScrapeResponse("task-1", ResourceStatus.SC_OK, ProtocolStatusCodes.SC_OK)
        Mockito.`when`(facade.getStatus(any<ScrapeStatusRequest>())).thenReturn(expectedResponse)

        val result = controller.status("task-1")
        assertEquals(expectedResponse, result)
        verify(facade).getStatus(any())
    }

    // -----------------------------------------------------------------
    // getStatus() tests — path-variable endpoint
    // -----------------------------------------------------------------

    @Test
    fun getStatusWithBlankIdThrowsIllegalArgumentException() {
        val sessionManager = Mockito.mock(PulsarSessionManager::class.java)
        val controller = newController(sessionManager)

        val exception = assertThrows<IllegalArgumentException> {
            controller.getStatus("   ")
        }
        assertEquals("id must not be blank", exception.message)
        verify(facade, never()).getStatus(any())
    }

    @Test
    fun getStatusDelegatesToFacade() {
        val sessionManager = Mockito.mock(PulsarSessionManager::class.java)
        val controller = newController(sessionManager)

        val expectedResponse = ScrapeResponse("task-2", ResourceStatus.SC_OK, ProtocolStatusCodes.SC_OK)
        Mockito.`when`(facade.getStatus(any<ScrapeStatusRequest>())).thenReturn(expectedResponse)

        val result = controller.getStatus("task-2")
        assertEquals(expectedResponse, result)
        verify(facade).getStatus(any())
    }

    // -----------------------------------------------------------------
    // getResult() tests — returns same as getStatus
    // -----------------------------------------------------------------

    @Test
    fun getResultWithBlankIdThrowsIllegalArgumentException() {
        val sessionManager = Mockito.mock(PulsarSessionManager::class.java)
        val controller = newController(sessionManager)

        val exception = assertThrows<IllegalArgumentException> {
            controller.getResult("   ")
        }
        assertEquals("id must not be blank", exception.message)
        verify(facade, never()).getStatus(any())
    }

    @Test
    fun getResultDelegatesToGetStatus() {
        val sessionManager = Mockito.mock(PulsarSessionManager::class.java)
        val controller = newController(sessionManager)

        val expectedResponse = ScrapeResponse("task-3", ResourceStatus.SC_OK, ProtocolStatusCodes.SC_OK)
        Mockito.`when`(facade.getStatus(any<ScrapeStatusRequest>())).thenReturn(expectedResponse)

        val result = controller.getResult("task-3")
        assertEquals(expectedResponse, result)
        verify(facade).getStatus(any())
    }
}
