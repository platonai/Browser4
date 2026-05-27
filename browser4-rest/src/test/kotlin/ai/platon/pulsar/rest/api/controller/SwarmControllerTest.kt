package ai.platon.pulsar.rest.api.controller

import ai.platon.browser4.common.B4Constants.PROFILE_MODE_CAPABILITY
import ai.platon.browser4.common.B4Constants.SESSION_ID_CAPABILITY
import ai.platon.browser4.common.B4Constants.SWARM_SESSION_ID
import ai.platon.pulsar.agentic.AgenticSession
import ai.platon.pulsar.common.SessionManager
import ai.platon.pulsar.rest.api.service.SwarmService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.Mockito.verify

class SwarmControllerTest {
    @Test
    fun openReturnsSafeSessionResponse() {
        val sessionManager = Mockito.mock(SessionManager::class.java)
        val swarmService = Mockito.mock(SwarmService::class.java)
        val agenticSession = Mockito.mock(AgenticSession::class.java)
        val controller = SwarmController(sessionManager, swarmService)
        val capabilities = mapOf("profileMode" to "TEMPORARY")
        val managedSession = SessionManager.ManagedSession(
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
        assertEquals("value", response.capabilities?.get("custom"))
        verify(sessionManager).ensureSwarmSession(capabilities)
    }
}

