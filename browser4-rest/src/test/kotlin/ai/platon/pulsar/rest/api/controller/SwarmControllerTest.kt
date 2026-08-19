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
import org.mockito.kotlin.argumentCaptor

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
    fun submitWithBlankPayloadThrowsIllegalArgumentException() {
        val sessionManager = Mockito.mock(PulsarSessionManager::class.java)
        val controller = newController(sessionManager)

        val exception = assertThrows<IllegalArgumentException> {
            controller.submit("   ")
        }
        assertEquals("Request body must be a non-blank URL or X-SQL", exception.message)
        verify(facade, never()).submit(any<ScrapeRequest>())
    }

    @Test
    fun submitWithValidUrlReturnsUuid() {
        val sessionManager = Mockito.mock(PulsarSessionManager::class.java)
        val controller = newController(sessionManager)

        Mockito.`when`(facade.submit(any<ScrapeRequest>())).thenReturn("mock-uuid")

        val result = controller.submit("https://example.com")

        assertEquals("mock-uuid", result)
        val captor = argumentCaptor<ScrapeRequest>()
        verify(facade).submit(captor.capture())
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
        verify(facade, never()).submit(any<ScrapeRequest>())
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
