package ai.platon.pulsar.rest.api.controller

import ai.platon.pulsar.agentic.AgenticSession
import ai.platon.pulsar.common.SessionManager
import ai.platon.pulsar.rest.api.service.SwarmService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.Mockito.verify

class SwarmControllerTest {
    @Test
    fun getOrCreateReturnsSafeSessionResponse() {
        val sessionManager = Mockito.mock(SessionManager::class.java)
        val swarmService = Mockito.mock(SwarmService::class.java)
        val agenticSession = Mockito.mock(AgenticSession::class.java)
        val controller = SwarmController(sessionManager, swarmService)
        val capabilities = mapOf("profileMode" to "TEMPORARY")
        val managedSession = SessionManager.ManagedSession(
            sessionId = SessionManager.SWARM_SESSION_ID,
            agenticSession = agenticSession,
            capabilities = mapOf(
                SessionManager.SESSION_ID_CAPABILITY to SessionManager.SWARM_SESSION_ID,
                SessionManager.PROFILE_MODE_CAPABILITY to "TEMPORARY",
                "custom" to "value",
            ),
            status = "active",
            createdAt = 1L,
            lastAccessedAt = 2L,
        )

        Mockito.`when`(sessionManager.ensureSwarmSession(capabilities)).thenReturn(managedSession)

        val response = controller.getOrCreate(capabilities)

        assertEquals(SessionManager.SWARM_SESSION_ID, response.sessionId)
        assertEquals("active", response.status)
        assertEquals("TEMPORARY", response.profileMode)
        assertEquals("value", response.capabilities?.get("custom"))
        verify(sessionManager).ensureSwarmSession(capabilities)
    }
}

