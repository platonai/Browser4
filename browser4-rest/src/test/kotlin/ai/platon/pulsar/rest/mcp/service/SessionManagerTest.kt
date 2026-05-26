package ai.platon.pulsar.rest.mcp.service

import ai.platon.pulsar.agentic.AgenticSession
import ai.platon.pulsar.agentic.context.AgenticContext
import ai.platon.pulsar.common.SessionManager
import ai.platon.pulsar.skeleton.PulsarSettings
import ai.platon.pulsar.skeleton.browser.Browser
import ai.platon.pulsar.skeleton.browser.driver.WebDriver
import org.junit.jupiter.api.Assertions.*
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
    fun getOrCreateSessionUsesDefaultSessionWhenSessionIdIsMissingOrDefault() {
        val session = sessionManager.getOrCreateSession(null)
        val sameSession = sessionManager.getOrCreateSession(
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
    fun getOrCreateSessionUsesSequentialProfileModeForNamedSessionsByDefault() {
        val session = sessionManager.getOrCreateSession(
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
    fun getOrCreateSessionPreservesSequentialProfileModeForDefaultSession() {
        val session = sessionManager.getOrCreateSession(
            mapOf(
                "profileMode" to "SEQUENTIAL",
            )
        )

        assertEquals("default", session.sessionId)
        assertEquals("default", session.capabilities?.get("sessionId"))
        assertEquals("SEQUENTIAL", session.capabilities?.get("profileMode"))
    }

    @Test
    fun getOrCreateSessionPreservesSequentialProfileModeForNamedSessions() {
        val session = sessionManager.getOrCreateSession(
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
    fun getOrCreateSessionByIdUsesExplicitSessionIdWhenCapabilitiesAreMissing() {
        val session = sessionManager.getOrCreateSessionById("team-f")

        assertEquals("team-f", session.sessionId)
        assertEquals("team-f", session.capabilities?.get("sessionId"))
        assertEquals("SEQUENTIAL", session.capabilities?.get("profileMode"))
    }

    @Test
    fun swarmSessionDefaultsToSequentialProfileMode() {
        val session = sessionManager.swarmSession()

        assertEquals(SessionManager.SWARM_SESSION_ID, session.sessionId)
        assertEquals(SessionManager.SWARM_SESSION_ID, session.capabilities?.get("sessionId"))
        assertEquals("SEQUENTIAL", session.capabilities?.get("profileMode"))
    }

    @Test
    fun swarmSessionPreservesTemporaryProfileMode() {
        val session = sessionManager.swarmSession(
            mapOf(
                "profileMode" to "temporary",
            )
        )

        assertEquals(SessionManager.SWARM_SESSION_ID, session.sessionId)
        assertEquals(SessionManager.SWARM_SESSION_ID, session.capabilities?.get("sessionId"))
        assertEquals("TEMPORARY", session.capabilities?.get("profileMode"))
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
    fun getOrCreateSessionRecreatesInactiveCachedSession() {
        val inactiveSession = mockAgenticSession(isActive = false)
        val replacementSession = mockAgenticSession(isActive = true)
        Mockito.doReturn(inactiveSession, replacementSession)
            .`when`(agenticContext)
            .createSession(Mockito.any(PulsarSettings::class.java) ?: PulsarSettings())

        val firstSession = sessionManager.getOrCreateSession(mapOf("sessionId" to "team-c"))
        val secondSession = sessionManager.getOrCreateSession(mapOf("sessionId" to "team-c"))

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

    @Test
    fun getSessionRecreatesNamedSessionWhenCachedBrowserBecomesUnhealthy() {
        val browser = Mockito.mock(Browser::class.java)
        Mockito.`when`(browser.healthy()).thenReturn(true, false)

        val initialSession = mockAgenticSession(isActive = true, browser = browser)
        val replacementSession = mockAgenticSession(isActive = true)
        Mockito.doReturn(initialSession, replacementSession)
            .`when`(agenticContext)
            .createSession(Mockito.any(PulsarSettings::class.java) ?: PulsarSettings())

        val firstSession = sessionManager.getOrCreateSession(mapOf("sessionId" to "team-d"))
        val fetchedSession = sessionManager.getSession("team-d")

        requireNotNull(fetchedSession)
        assertNotSame(firstSession, fetchedSession)
        assertEquals("team-d", fetchedSession.sessionId)
        assertEquals("active", fetchedSession.status)
        verify(agenticContext, times(2)).createSession(Mockito.any(PulsarSettings::class.java) ?: PulsarSettings())
    }

    @Test
    fun getOrCreateSessionMarksReplacementStoppedWhenRecreatedSessionRemainsUnhealthy() {
        val unhealthyBrowser = Mockito.mock(Browser::class.java)
        Mockito.`when`(unhealthyBrowser.healthy()).thenReturn(false)
        val unhealthyDriver = Mockito.mock(WebDriver::class.java)
        Mockito.`when`(unhealthyDriver.healthy()).thenReturn(false)

        val inactiveSession = mockAgenticSession(isActive = true, browser = unhealthyBrowser)
        val unhealthyReplacement = mockAgenticSession(isActive = true, driver = unhealthyDriver)
        Mockito.doReturn(inactiveSession, unhealthyReplacement)
            .`when`(agenticContext)
            .createSession(Mockito.any(PulsarSettings::class.java) ?: PulsarSettings())

        val session = sessionManager.getOrCreateSession(mapOf("sessionId" to "team-e"))

        assertEquals("team-e", session.sessionId)
        assertEquals("stopped", session.status)
        assertSame(session, sessionManager.getAllSessions().single())
        verify(agenticContext, times(2)).createSession(Mockito.any(PulsarSettings::class.java) ?: PulsarSettings())
    }

    private fun mockAgenticSession(
        isActive: Boolean = true,
        browser: Browser? = null,
        driver: WebDriver? = null,
    ): AgenticSession {
        val session = Mockito.mock(AgenticSession::class.java)
        Mockito.`when`(session.isActive).thenReturn(isActive)
        Mockito.`when`(session.boundBrowser).thenReturn(browser)
        Mockito.`when`(session.boundDriver).thenReturn(driver)
        return session
    }
}

