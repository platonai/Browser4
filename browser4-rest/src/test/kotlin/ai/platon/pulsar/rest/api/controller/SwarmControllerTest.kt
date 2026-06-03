package ai.platon.pulsar.rest.api.controller

import ai.platon.browser4.common.B4Constants.PROFILE_MODE_CAPABILITY
import ai.platon.browser4.common.B4Constants.SESSION_ID_CAPABILITY
import ai.platon.browser4.common.B4Constants.SWARM_SESSION_ID
import ai.platon.pulsar.agentic.AgenticSession
import ai.platon.pulsar.agentic.tools.advanced.crawl.ScrapeRequest
import ai.platon.pulsar.agentic.tools.advanced.crawl.ScrapeResponse
import ai.platon.pulsar.common.ManagedSession
import ai.platon.pulsar.common.PulsarSessionManager
import ai.platon.pulsar.common.ResourceStatus
import ai.platon.pulsar.persist.metadata.ProtocolStatusCodes
import ai.platon.pulsar.rest.api.service.SwarmService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor

class SwarmControllerTest {
    @Test
    fun openReturnsSafeSessionResponse() {
        val sessionManager = Mockito.mock(PulsarSessionManager::class.java)
        val swarmService = Mockito.mock(SwarmService::class.java)
        val agenticSession = Mockito.mock(AgenticSession::class.java)
        val controller = SwarmController(sessionManager, swarmService)
        val capabilities = mapOf("profileMode" to "TEMPORARY")
        val managedSession = ManagedSession(
            sessionId = SWARM_SESSION_ID,
            agenticSession = agenticSession,
            capabilities = mapOf(
                SESSION_ID_CAPABILITY to SWARM_SESSION_ID,
                PROFILE_MODE_CAPABILITY to "TEMPORARY",
                "custom" to "value",
            ),
            status = "active",
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
    fun submitWithBlankPayloadThrowsIllegalArgumentException() {
        val sessionManager = Mockito.mock(PulsarSessionManager::class.java)
        val swarmService = Mockito.mock(SwarmService::class.java)
        val controller = SwarmController(sessionManager, swarmService)

        val exception = assertThrows<IllegalArgumentException> {
            controller.submit("   ")
        }
        assertEquals("Request body must be a non-blank URL or X-SQL", exception.message)
        verify(swarmService, never()).submit(any<ScrapeRequest>())
    }

    @Test
    fun submitWithValidUrlReturnsUuid() {
        val sessionManager = Mockito.mock(PulsarSessionManager::class.java)
        val swarmService = Mockito.mock(SwarmService::class.java)
        val controller = SwarmController(sessionManager, swarmService)

        Mockito.`when`(swarmService.submit(any<ScrapeRequest>())).thenReturn("mock-uuid")

        val result = controller.submit("https://example.com")

        assertEquals("mock-uuid", result)
        val captor = argumentCaptor<ScrapeRequest>()
        verify(swarmService).submit(captor.capture())
        assertEquals(
            "select dom_base_uri(dom) as url from load_and_select('https://example.com', ':root')",
            captor.firstValue.sql
        )
    }

    @Test
    fun submitWithInvalidSqlThrowsIllegalArgumentException() {
        val sessionManager = Mockito.mock(PulsarSessionManager::class.java)
        val swarmService = Mockito.mock(SwarmService::class.java)
        val controller = SwarmController(sessionManager, swarmService)

        assertThrows<IllegalArgumentException> {
            controller.submit("DROP TABLE users")
        }
        verify(swarmService, never()).submit(any<ScrapeRequest>())
    }

    // -----------------------------------------------------------------
    // count() tests
    // -----------------------------------------------------------------

    @Test
    fun countWithStatusCodeDelegatesToService() {
        val sessionManager = Mockito.mock(PulsarSessionManager::class.java)
        val swarmService = Mockito.mock(SwarmService::class.java)
        val controller = SwarmController(sessionManager, swarmService)

        Mockito.`when`(swarmService.count(200)).thenReturn(5)

        val result = controller.count(200)
        assertEquals(5, result)
        verify(swarmService).count(200)
    }

    @Test
    fun countWithDefaultDelegatesToService() {
        val sessionManager = Mockito.mock(PulsarSessionManager::class.java)
        val swarmService = Mockito.mock(SwarmService::class.java)
        val controller = SwarmController(sessionManager, swarmService)

        Mockito.`when`(swarmService.count(0)).thenReturn(10)

        val result = controller.count()
        assertEquals(10, result)
        verify(swarmService).count(0)
    }

    // -----------------------------------------------------------------
    // status() tests — query-param endpoint
    // -----------------------------------------------------------------

    @Test
    fun statusWithBlankUuidThrowsIllegalArgumentException() {
        val sessionManager = Mockito.mock(PulsarSessionManager::class.java)
        val swarmService = Mockito.mock(SwarmService::class.java)
        val controller = SwarmController(sessionManager, swarmService)

        val exception = assertThrows<IllegalArgumentException> {
            controller.status("   ")
        }
        assertEquals("uuid must not be blank", exception.message)
        verify(swarmService, never()).getStatus(any())
    }

    @Test
    fun statusDelegatesToService() {
        val sessionManager = Mockito.mock(PulsarSessionManager::class.java)
        val swarmService = Mockito.mock(SwarmService::class.java)
        val controller = SwarmController(sessionManager, swarmService)

        val expectedResponse = ScrapeResponse("task-1", ResourceStatus.SC_OK, ProtocolStatusCodes.SC_OK)
        Mockito.`when`(swarmService.getStatus(any())).thenReturn(expectedResponse)

        val result = controller.status("task-1")
        assertEquals(expectedResponse, result)
        verify(swarmService).getStatus(any())
    }

    // -----------------------------------------------------------------
    // getStatus() tests — path-variable endpoint
    // -----------------------------------------------------------------

    @Test
    fun getStatusWithBlankIdThrowsIllegalArgumentException() {
        val sessionManager = Mockito.mock(PulsarSessionManager::class.java)
        val swarmService = Mockito.mock(SwarmService::class.java)
        val controller = SwarmController(sessionManager, swarmService)

        val exception = assertThrows<IllegalArgumentException> {
            controller.getStatus("   ")
        }
        assertEquals("id must not be blank", exception.message)
        verify(swarmService, never()).getStatus(any())
    }

    @Test
    fun getStatusDelegatesToService() {
        val sessionManager = Mockito.mock(PulsarSessionManager::class.java)
        val swarmService = Mockito.mock(SwarmService::class.java)
        val controller = SwarmController(sessionManager, swarmService)

        val expectedResponse = ScrapeResponse("task-2", ResourceStatus.SC_OK, ProtocolStatusCodes.SC_OK)
        Mockito.`when`(swarmService.getStatus(any())).thenReturn(expectedResponse)

        val result = controller.getStatus("task-2")
        assertEquals(expectedResponse, result)
        verify(swarmService).getStatus(any())
    }

    // -----------------------------------------------------------------
    // getResult() tests — returns same as getStatus
    // -----------------------------------------------------------------

    @Test
    fun getResultWithBlankIdThrowsIllegalArgumentException() {
        val sessionManager = Mockito.mock(PulsarSessionManager::class.java)
        val swarmService = Mockito.mock(SwarmService::class.java)
        val controller = SwarmController(sessionManager, swarmService)

        val exception = assertThrows<IllegalArgumentException> {
            controller.getResult("   ")
        }
        assertEquals("id must not be blank", exception.message)
        verify(swarmService, never()).getStatus(any())
    }

    @Test
    fun getResultDelegatesToGetStatus() {
        val sessionManager = Mockito.mock(PulsarSessionManager::class.java)
        val swarmService = Mockito.mock(SwarmService::class.java)
        val controller = SwarmController(sessionManager, swarmService)

        val expectedResponse = ScrapeResponse("task-3", ResourceStatus.SC_OK, ProtocolStatusCodes.SC_OK)
        Mockito.`when`(swarmService.getStatus(any())).thenReturn(expectedResponse)

        val result = controller.getResult("task-3")
        assertEquals(expectedResponse, result)
        verify(swarmService).getStatus(any())
    }
}
