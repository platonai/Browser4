package ai.platon.pulsar.rest.mcp.controller

import ai.platon.pulsar.rest.session.PulsarSessionManager
import ai.platon.pulsar.rest.session.ManagedSession
import ai.platon.pulsar.agent.tool.UserCommandExecutor
import ai.platon.pulsar.rest.mcp.controller.dto.MCPToolCallRequest
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import jakarta.servlet.http.HttpServletResponse
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.http.HttpStatus

class MCPToolControllerOpenSessionTest {
    private val objectMapper = jacksonObjectMapper()

    @Test
    fun openSessionForwardsSequentialProfileModeCapabilities() {
        runBlocking {
            val sessionManager = Mockito.mock(PulsarSessionManager::class.java)
            val commandService = Mockito.mock(UserCommandExecutor::class.java)
            val response = Mockito.mock(HttpServletResponse::class.java)
            val managedSession = Mockito.mock(ManagedSession::class.java)
            val controller = MCPToolController(sessionManager, commandService, objectMapper = objectMapper)
            val request = MCPToolCallRequest(
                tool = "open_session",
                arguments = mapOf(
                    "capabilities" to mapOf(
                        "profileMode" to "SEQUENTIAL",
                    )
                )
            )

            Mockito.`when`(managedSession.sessionId).thenReturn("sequential-session-id")
            Mockito.`when`(sessionManager.getOrCreateSession(mapOf("profileMode" to "SEQUENTIAL")))
                .thenReturn(managedSession)

            val result = controller.callTool(request, response)

            assertEquals(HttpStatus.OK, result.statusCode)
            Mockito.verify(sessionManager).getOrCreateSession(mapOf("profileMode" to "SEQUENTIAL"))
        }
    }
}

