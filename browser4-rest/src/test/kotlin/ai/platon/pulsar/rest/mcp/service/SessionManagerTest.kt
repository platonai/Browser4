package ai.platon.pulsar.rest.mcp.service

import ai.platon.pulsar.agentic.AgenticSession
import ai.platon.pulsar.agentic.context.AgenticContext
import ai.platon.pulsar.skeleton.PulsarSettings
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

class SessionManagerTest {
    @Mock
    private lateinit var agenticContext: AgenticContext

    private lateinit var sessionManager: SessionManager

    @BeforeEach
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        Mockito.doAnswer {
            mockAgenticSession()
        }.`when`(agenticContext).createSession(Mockito.any(PulsarSettings::class.java) ?: PulsarSettings())
        sessionManager = SessionManager(agenticContext)
    }

    @Test
    fun createSessionUsesDefaultSessionWhenSessionIdIsMissingOrDefault() {
        val session = sessionManager.createSession(null)
        val sameSession = sessionManager.createSession(
            mapOf(
                "sessionId" to "DEFAULT",
                "profileMode" to "TEMPORARY",
            )
        )

        assertEquals("default", session.sessionId)
        assertEquals("default", session.capabilities?.get("sessionId"))
        assertEquals("DEFAULT", session.capabilities?.get("profileMode"))
        assertSame(session, sameSession)
        verify(agenticContext, times(1)).createSession(Mockito.any(PulsarSettings::class.java) ?: PulsarSettings())
    }

    @Test
    fun createSessionUsesSequentialProfileModeForNamedSessionsByDefault() {
        val session = sessionManager.createSession(
            mapOf(
                "sessionId" to "team-a",
                "profileMode" to "DEFAULT",
            )
        )

        assertEquals("team-a", session.sessionId)
        assertEquals("team-a", session.capabilities?.get("sessionId"))
        assertEquals("SEQUENTIAL", session.capabilities?.get("profileMode"))
    }

    @Test
    fun createSessionPreservesSequentialProfileModeForDefaultSession() {
        val session = sessionManager.createSession(
            mapOf(
                "profileMode" to "SEQUENTIAL",
            )
        )

        assertEquals("default", session.sessionId)
        assertEquals("default", session.capabilities?.get("sessionId"))
        assertEquals("SEQUENTIAL", session.capabilities?.get("profileMode"))
    }

    @Test
    fun createSessionPreservesSequentialProfileModeForNamedSessions() {
        val session = sessionManager.createSession(
            mapOf(
                "sessionId" to "team-b",
                "profileMode" to "sequential",
            )
        )

        assertEquals("team-b", session.sessionId)
        assertEquals("SEQUENTIAL", session.capabilities?.get("profileMode"))
        assertSame(session, sessionManager.getSession("team-b"))
    }

    @Test
    fun getAllSessionsDoesNotCreateDefaultSessionOnDemand() {
        val sessions = sessionManager.getAllSessions()

        assertEquals(0, sessions.size)
    }

    @Test
    fun getSessionCreatesDefaultSessionOnDemand() {
        val session = sessionManager.getSession("default")

        assertEquals("default", session?.sessionId)
        assertEquals("default", session?.capabilities?.get("sessionId"))
        assertEquals("DEFAULT", session?.capabilities?.get("profileMode"))
    }

    @Test
    fun createSessionRecreatesInactiveCachedSession() {
        val inactiveSession = mockAgenticSession(isActive = false)
        val replacementSession = mockAgenticSession(isActive = true)
        Mockito.doReturn(inactiveSession, replacementSession)
            .`when`(agenticContext)
            .createSession(Mockito.any(PulsarSettings::class.java) ?: PulsarSettings())

        val firstSession = sessionManager.createSession(mapOf("sessionId" to "team-c"))
        val secondSession = sessionManager.createSession(mapOf("sessionId" to "team-c"))

        assertEquals("team-c", firstSession.sessionId)
        assertEquals("SEQUENTIAL", firstSession.capabilities?.get("profileMode"))
        assertEquals("active", firstSession.status)
        assertEquals("team-c", secondSession.sessionId)
        assertEquals("SEQUENTIAL", secondSession.capabilities?.get("profileMode"))
        assertEquals("active", secondSession.status)
        assertSame(firstSession, secondSession)
        assertSame(secondSession, sessionManager.getSession("team-c"))
        verify(agenticContext, times(2)).createSession(Mockito.any(PulsarSettings::class.java) ?: PulsarSettings())
    }

    private fun mockAgenticSession(isActive: Boolean = true): AgenticSession {
        val session = Mockito.mock(AgenticSession::class.java)
        Mockito.`when`(session.isActive).thenReturn(isActive)
        return session
    }
}

