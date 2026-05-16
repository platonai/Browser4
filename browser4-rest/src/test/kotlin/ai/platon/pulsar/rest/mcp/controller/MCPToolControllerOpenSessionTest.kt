package ai.platon.pulsar.rest.mcp.controller

import ai.platon.pulsar.rest.mcp.service.SessionManager
import ai.platon.pulsar.rest.mcp.service.SessionManager.ManagedSession
import ai.platon.pulsar.agentic.tools.high.command.CommandService
import jakarta.servlet.http.HttpServletResponse
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.http.HttpStatus

class MCPToolControllerOpenSessionTest {
    @Test
    fun openSessionForwardsSequentialProfileModeCapabilities() {
        runBlocking {
            val sessionManager = Mockito.mock(SessionManager::class.java)
            val commandService = Mockito.mock(CommandService::class.java)
            val response = Mockito.mock(HttpServletResponse::class.java)
            val managedSession = Mockito.mock(ManagedSession::class.java)
            val controller = MCPToolController(sessionManager, commandService)
            val request = MCPToolCallRequest(
                tool = "open_session",
                arguments = mapOf(
                    "capabilities" to mapOf(
                        "profileMode" to "SEQUENTIAL",
                    )
                )
            )

            Mockito.`when`(managedSession.sessionId).thenReturn("sequential-session-id")
            Mockito.`when`(sessionManager.createSession(mapOf("profileMode" to "SEQUENTIAL"))).thenReturn(managedSession)

            val result = controller.callTool(request, response)

            assertEquals(HttpStatus.OK, result.statusCode)
            Mockito.verify(sessionManager).createSession(mapOf("profileMode" to "SEQUENTIAL"))
        }
    }
}

