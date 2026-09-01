package ai.platon.pulsar.rest.mcp.service

import ai.platon.pulsar.common.B4Constants.SWARM_SESSION_ID
import ai.platon.pulsar.agentic.AgenticSession
import ai.platon.pulsar.agentic.GenericAgenticSession
import ai.platon.pulsar.agentic.context.AgenticContexts
import ai.platon.pulsar.agentic.context.GenericAgenticContext
import ai.platon.pulsar.common.CheckState
import ai.platon.pulsar.rest.session.PulsarSessionManager
import ai.platon.pulsar.rest.session.SessionKind
import ai.platon.pulsar.rest.session.SessionStatus
import ai.platon.pulsar.common.config.VolatileConfig
import ai.platon.pulsar.api.Browser
import ai.platon.pulsar.api.WebDriver
import ai.platon.pulsar.skeleton.PulsarSettings
import ai.platon.pulsar.chrome.protocol.transport.ExtensionMessageSender
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations
import org.springframework.context.support.GenericApplicationContext
import java.net.InetSocketAddress
import java.time.Duration

class PulsarSessionManagerTest {
    @Mock
    private lateinit var agenticContext: GenericAgenticContext

    private lateinit var sessionManager: PulsarSessionManager

    @BeforeEach
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        val appContext = Mockito.mock(GenericApplicationContext::class.java)
        Mockito.`when`(agenticContext.applicationContext).thenReturn(appContext)
        AgenticContexts.create(agenticContext)
        Mockito.doAnswer {
            mockAgenticSession()
        }.`when`(agenticContext).createSession(Mockito.any(PulsarSettings::class.java) ?: PulsarSettings())
        sessionManager = PulsarSessionManager(agenticContext)
    }

    @AfterEach
    fun tearDown() {
        AgenticContexts.close()
    }

    @Test
    fun getOrCreateSessionUsesDefaultSessionWhenSessionIdIsMissingOrEnsureDefault() {
        val session = sessionManager.getOrCreateSession(null)
        val sameSession = sessionManager.getOrCreateSession(
            mapOf(
                "sessionId" to "DEFAULT",
                "profileMode" to "TEMPORARY",
            )
        )

        // DEFAULT is resolved to a stable UUID; both calls produce the same session
        val expectedId = session.sessionId
        assertNotEquals("DEFAULT", expectedId, "DEFAULT should be resolved to a UUID")
        assertEquals(expectedId, session.capabilities?.get("sessionId"))
        assertEquals(expectedId, sameSession.sessionId)
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

        // Named sessions get UUID-based IDs; the display name is a label, not the ID
        assertNotEquals("team-a", session.sessionId, "Named session should use UUID, not raw name")
        assertEquals(session.sessionId, session.capabilities?.get("sessionId"))
        assertEquals("SEQUENTIAL", session.capabilities?.get("profileMode"))
        // Named sessions bind a dedicated context dir derived from the session id
        val contextDir = session.capabilities?.get("contextDir")
        assertNotNull(contextDir, "Named sessions must bind a dedicated context dir")
        assertTrue(contextDir!!.endsWith("cx.${session.sessionId}"), "Context dir must be keyed by the session id: $contextDir")
    }

    @Test
    fun getOrCreateSessionPreservesSequentialProfileModeForEnsureDefaultSession() {
        val session = sessionManager.getOrCreateSession(
            mapOf(
                "profileMode" to "SEQUENTIAL",
            )
        )

        // DEFAULT is resolved to a stable UUID
        val expectedId = session.sessionId
        assertNotEquals("DEFAULT", expectedId, "DEFAULT should be resolved to a UUID")
        assertEquals(expectedId, session.capabilities?.get("sessionId"))
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

        // Named sessions get UUID-based IDs; the display name is a label, not the ID
        assertNotEquals("team-b", session.sessionId, "Named session should use UUID, not raw name")
        assertEquals(session.sessionId, session.capabilities?.get("sessionId"))
        assertEquals("SEQUENTIAL", session.capabilities?.get("profileMode"))
        assertNotNull(session.capabilities?.get("contextDir"), "Named sessions must bind a dedicated context dir")
        assertSame(session, sessionManager.getSession("team-b"))
    }

    @Test
    fun getOrCreateSessionByIdUsesExplicitSessionIdWhenCapabilitiesAreMissing() {
        val session = sessionManager.getOrCreateSession("team-f")

        assertEquals("team-f", session.sessionId)
        assertEquals("team-f", session.capabilities?.get("sessionId"))
        assertEquals("SEQUENTIAL", session.capabilities?.get("profileMode"))
        assertNotNull(session.capabilities?.get("contextDir"), "Named session by explicit id must bind a dedicated context dir")
    }

    @Test
    fun namedSessionsBindSameDedicatedContextDirAcrossReopens() {
        val first = sessionManager.getOrCreateSession(mapOf("sessionId" to "team-stable"))
        val second = sessionManager.getOrCreateSession(mapOf("sessionId" to "team-stable"))

        assertEquals(first.sessionId, second.sessionId, "Same display name must resolve to the same UUID")
        val firstDir = first.capabilities?.get("contextDir")
        val secondDir = second.capabilities?.get("contextDir")
        assertNotNull(firstDir, "Named sessions must carry a dedicated context dir capability")
        assertEquals(firstDir, secondDir, "Reopening a named session must keep the same context dir")
        assertTrue(
            firstDir!!.contains("named") && firstDir.endsWith("cx.${first.sessionId}"),
            "Expected a named-group context dir keyed by the session id, but got $firstDir"
        )
    }

    @Test
    fun differentNamedSessionsGetDifferentContextDirs() {
        val teamX = sessionManager.getOrCreateSession(mapOf("sessionId" to "team-x"))
        val teamY = sessionManager.getOrCreateSession(mapOf("sessionId" to "team-y"))

        assertNotEquals(teamX.sessionId, teamY.sessionId)
        assertNotNull(teamX.capabilities?.get("contextDir"))
        assertNotNull(teamY.capabilities?.get("contextDir"))
        assertNotEquals(teamX.capabilities?.get("contextDir"), teamY.capabilities?.get("contextDir"))
    }

    @Test
    fun namedSessionWithExplicitTemporaryModeStaysTemporaryAndGetsNoContextDir() {
        val session = sessionManager.getOrCreateSession(
            mapOf("sessionId" to "team-tmp", "profileMode" to "TEMPORARY")
        )

        assertEquals("TEMPORARY", session.capabilities?.get("profileMode"))
        assertNull(session.capabilities?.get("contextDir"), "TEMPORARY named sessions must not pin a context dir")
    }

    @Test
    fun defaultAndSwarmSessionsDoNotGetNamedContextDir() {
        val defaultSession = sessionManager.getOrCreateSession(
            mapOf("sessionId" to "DEFAULT", "profileMode" to "SEQUENTIAL")
        )
        assertNull(defaultSession.capabilities?.get("contextDir"), "Default sessions must keep rotating through the pool")

        val swarmSession = sessionManager.ensureSwarmSession(mapOf("profileMode" to "SEQUENTIAL"))
        assertNull(swarmSession.capabilities?.get("contextDir"), "Swarm sessions must keep rotating through the pool")
    }

    @Test
    fun ensureSwarmSessionDefaultsToSequentialProfileMode() {
        val session = sessionManager.ensureSwarmSession()

        assertEquals(SWARM_SESSION_ID, session.sessionId)
        assertEquals(SWARM_SESSION_ID, session.capabilities?.get("sessionId"))
        assertEquals("SEQUENTIAL", session.capabilities?.get("profileMode"))
    }

    @Test
    fun ensureSwarmSessionPreservesTemporaryProfileMode() {
        val session = sessionManager.ensureSwarmSession(
            mapOf(
                "profileMode" to "temporary",
            )
        )

        assertEquals(SWARM_SESSION_ID, session.sessionId)
        assertEquals(SWARM_SESSION_ID, session.capabilities?.get("sessionId"))
        assertEquals("TEMPORARY", session.capabilities?.get("profileMode"))
    }

    @Test
    fun ensureSwarmSessionCreatesFreshSessionAfterDelete() {
        // Regression guard for issue #577: a closed swarm session must never be
        // resurrected. If it were, every new swarm task would be submitted to a
        // closed session and stay "queued" forever.
        val first = sessionManager.ensureSwarmSession()

        assertTrue(sessionManager.deleteSession(SWARM_SESSION_ID))

        val second = sessionManager.ensureSwarmSession()
        assertNotNull(second)
        assertNotSame(
            first.agenticSession,
            second.agenticSession,
            "A closed swarm session must be replaced with a fresh session"
        )
    }

    @Test
    fun getAllSessionsDoesNotCreateEnsureDefaultSessionOnDemand() {
        val sessions = sessionManager.getAllSessions()

        assertEquals(0, sessions.size)
    }

    @Test
    fun getOrRecoverSessionCreatesEnsureDefaultSessionOnDemand() {
        // The default session is created on demand by the recovery path only;
        // the pure-lookup getSession must NOT create it (see getSessionIsPureLookup).
        val session = sessionManager.getOrRecoverSession("default")

        assertNotNull(session)
        // DEFAULT is resolved to a stable UUID; it is no longer the literal "DEFAULT"
        val expectedId = session!!.sessionId
        assertNotEquals("DEFAULT", expectedId, "DEFAULT should be resolved to a UUID")
        assertEquals(expectedId, session.capabilities?.get("sessionId"))
        // Default sessions get SEQUENTIAL profile mode by default
        assertNotNull(session.capabilities?.get("profileMode"))
    }

    @Test
    fun getSessionIsPureLookupWithoutCreationOrRecovery() {
        // getSession must be side-effect free: no on-demand default creation...
        assertNull(sessionManager.getSession("default"), "getSession must not create the default session")

        // ...and no health-check recreation.
        val browser = Mockito.mock(Browser::class.java)
        Mockito.`when`(browser.healthy()).thenReturn(CheckState(0), CheckState(-1))
        val initialSession = mockAgenticSession(isActive = true, browser = browser)
        val replacementSession = mockAgenticSession(isActive = true)
        Mockito.doReturn(initialSession, replacementSession)
            .`when`(agenticContext)
            .createSession(Mockito.any(PulsarSettings::class.java) ?: PulsarSettings())

        val created = sessionManager.getOrCreateSession(mapOf("sessionId" to "team-pure"))
        val fetched = sessionManager.getSession("team-pure")

        assertSame(created, fetched, "Pure lookup returns the cached session")
        verify(agenticContext, times(1)).createSession(
            Mockito.any(PulsarSettings::class.java) ?: PulsarSettings()
        )
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

        // Named sessions get UUID-based IDs
        assertNotEquals("team-c", firstSession.sessionId, "Named session should use UUID, not raw name")
        assertEquals(firstSession.sessionId, firstSession.capabilities?.get("sessionId"))
        assertEquals("SEQUENTIAL", firstSession.capabilities?.get("profileMode"))
        assertEquals(SessionStatus.ACTIVE, firstSession.status)
        assertEquals(firstSession.sessionId, secondSession.sessionId) // same UUID for same display name
        assertEquals("SEQUENTIAL", secondSession.capabilities?.get("profileMode"))
        assertEquals(SessionStatus.ACTIVE, secondSession.status)
        assertSame(firstSession, secondSession)
        assertSame(secondSession, sessionManager.getSession("team-c"))
        verify(agenticContext, times(2)).createSession(Mockito.any(PulsarSettings::class.java) ?: PulsarSettings())
    }

    @Test
    fun getOrRecoverSessionRecreatesNamedSessionWhenCachedBrowserBecomesUnhealthy() {
        val browser = Mockito.mock(Browser::class.java)
        Mockito.`when`(browser.healthy()).thenReturn(CheckState(0), CheckState(-1))

        val initialSession = mockAgenticSession(isActive = true, browser = browser)
        val replacementSession = mockAgenticSession(isActive = true)
        Mockito.doReturn(initialSession, replacementSession)
            .`when`(agenticContext)
            .createSession(Mockito.any(PulsarSettings::class.java) ?: PulsarSettings())

        val firstSession = sessionManager.getOrCreateSession(mapOf("sessionId" to "team-d"))
        val fetchedSession = sessionManager.getOrRecoverSession("team-d")

        requireNotNull(fetchedSession)
        assertNotSame(firstSession, fetchedSession)
        // Named sessions get UUID-based IDs
        assertNotEquals("team-d", fetchedSession.sessionId, "Named session should use UUID, not raw name")
        assertEquals(fetchedSession.sessionId, fetchedSession.capabilities?.get("sessionId"))
        assertEquals(SessionStatus.ACTIVE, fetchedSession.status)
        verify(agenticContext, times(2)).createSession(Mockito.any(PulsarSettings::class.java) ?: PulsarSettings())
    }

    @Test
    fun getOrCreateSessionMarksReplacementStoppedWhenRecreatedSessionRemainsUnhealthy() {
        val unhealthyBrowser = Mockito.mock(Browser::class.java)
        Mockito.`when`(unhealthyBrowser.healthy()).thenReturn(CheckState(-1))
        val unhealthyDriver = Mockito.mock(WebDriver::class.java)
        runBlocking { Mockito.`when`(unhealthyDriver.healthy()).thenReturn(CheckState(-1)) }

        val inactiveSession = mockAgenticSession(isActive = true, browser = unhealthyBrowser)
        val unhealthyReplacement = mockAgenticSession(isActive = true, driver = unhealthyDriver)
        Mockito.doReturn(inactiveSession, unhealthyReplacement)
            .`when`(agenticContext)
            .createSession(Mockito.any(PulsarSettings::class.java) ?: PulsarSettings())

        val session = sessionManager.getOrCreateSession(mapOf("sessionId" to "team-e"))

        // Named sessions get UUID-based IDs
        assertNotEquals("team-e", session.sessionId, "Named session should use UUID, not raw name")
        assertEquals(session.sessionId, session.capabilities?.get("sessionId"))
        assertEquals(SessionStatus.STOPPED, session.status)
        assertSame(session, sessionManager.getAllSessions().single())
        verify(agenticContext, times(2)).createSession(Mockito.any(PulsarSettings::class.java) ?: PulsarSettings())
    }

    @Test
    fun extensionAttachedSessionNotRecreatedWhenNeverConnected() {
        // An extension-attached session whose extension never connected (or has
        // disconnected) must NOT be silently recreated as a Browser4-CDP session.
        val info = sessionManager.createExtensionAttachedSession(channel = "chrome")
        val sessionId = info.sessionId

        val session = sessionManager.getSession(sessionId)
        assertNotNull(session, "Extension-attached session should exist")
        assertEquals(SessionStatus.STOPPED, session!!.status,
            "Extension session without active WebSocket should be inactive, not recreated")

        // Verify only one agenticSession was created (the initial one — no recreation)
        verify(agenticContext, times(1)).createSession(
            Mockito.any(PulsarSettings::class.java) ?: PulsarSettings()
        )
    }

    @Test
    fun extensionAttachedSessionNotRecreatedAfterDisconnect() {
        // Simulate: extension connects, then disconnects. The session must be
        // inactive (not silently recreated as Browser4-CDP).
        val info = sessionManager.createExtensionAttachedSession(channel = "chrome")
        val sessionId = info.sessionId

        // Extension connects
        val mockSender = Mockito.mock(ExtensionMessageSender::class.java)
        Mockito.`when`(mockSender.isOpen).thenReturn(true)
        sessionManager.onExtensionConnected(sessionId, mockSender)

        // Verify session is active while extension is connected
        val activeSession = sessionManager.getSession(sessionId)
        assertNotNull(activeSession)
        assertEquals(SessionStatus.ACTIVE, activeSession!!.status,
            "Session should be active while extension WebSocket is connected")

        // Extension disconnects
        sessionManager.onExtensionDisconnected(sessionId)

        // Verify session is inactive (not recreated)
        val inactiveSession = sessionManager.getSession(sessionId)
        assertNotNull(inactiveSession, "Extension-attached session should still exist after disconnect")
        assertEquals(SessionStatus.STOPPED, inactiveSession!!.status,
            "Disconnected extension session should be inactive, not recreated as Browser4-CDP")

        // Verify still only one agenticSession (no recreation)
        verify(agenticContext, times(1)).createSession(
            Mockito.any(PulsarSettings::class.java) ?: PulsarSettings()
        )
    }

    @Test
    fun extensionAttachedSessionReadyOnlyWhenConnected() {
        val info = sessionManager.createExtensionAttachedSession(channel = "chrome")
        val sessionId = info.sessionId

        // Not ready before extension connects
        assertFalse(sessionManager.isExtensionSessionReady(sessionId),
            "Session should not be ready before extension connects")

        // Extension connects — now ready
        val mockSender = Mockito.mock(ExtensionMessageSender::class.java)
        Mockito.`when`(mockSender.isOpen).thenReturn(true)
        sessionManager.onExtensionConnected(sessionId, mockSender)

        assertTrue(sessionManager.isExtensionSessionReady(sessionId),
            "Session should be ready after extension connects")

        // Extension disconnects — not ready
        sessionManager.onExtensionDisconnected(sessionId)

        assertFalse(sessionManager.isExtensionSessionReady(sessionId),
            "Session should not be ready after extension disconnects")
    }

    @Test
    fun deleteSessionCleansUpExtensionTracking() {
        // Create an extension-attached session, then delete it. A subsequent
        // getSession should return null (session fully removed).
        val info = sessionManager.createExtensionAttachedSession(channel = "chrome")
        val sessionId = info.sessionId

        assertNotNull(sessionManager.getSession(sessionId))

        sessionManager.deleteSession(sessionId)

        assertNull(sessionManager.getSession(sessionId),
            "Deleted extension session should be fully removed")
        assertFalse(sessionManager.isExtensionSessionReady(sessionId),
            "Deleted extension session should not report as ready")
    }

    @Test
    fun regularSessionStillRecreatedWhenUnhealthy() {
        // Non-extension sessions should still be recreated when unhealthy.
        // This is a regression guard — our extension fix must not alter the
        // normal session lifecycle.
        val browser = Mockito.mock(Browser::class.java)
        Mockito.`when`(browser.healthy()).thenReturn(CheckState(0), CheckState(-1))

        val initialSession = mockAgenticSession(isActive = true, browser = browser)
        val replacementSession = mockAgenticSession(isActive = true)
        Mockito.doReturn(initialSession, replacementSession)
            .`when`(agenticContext)
            .createSession(Mockito.any(PulsarSettings::class.java) ?: PulsarSettings())

        val firstSession = sessionManager.getOrCreateSession(mapOf("sessionId" to "team-recreate"))
        val fetchedSession = sessionManager.getOrRecoverSession("team-recreate")

        requireNotNull(fetchedSession)
        assertNotSame(firstSession, fetchedSession,
            "Regular (non-extension) unhealthy session should be recreated")
        assertEquals(SessionStatus.ACTIVE, fetchedSession.status)
        verify(agenticContext, times(2)).createSession(
            Mockito.any(PulsarSettings::class.java) ?: PulsarSettings()
        )
    }

    @Test
    fun deleteSessionByDisplayNameResolvesToUuid() {
        val session = sessionManager.getOrCreateSession(mapOf("sessionId" to "team-delete-by-name"))

        // Deleting by display name must resolve to the UUID and actually
        // remove the session — closing by name must not silently "fail".
        assertTrue(sessionManager.deleteSession("team-delete-by-name"))
        assertNull(sessionManager.getSession("team-delete-by-name"))
        assertNull(sessionManager.getSession(session.sessionId))
    }

    @Test
    fun reapIdleSessionsReapsOnlyIdleNonDefaultSessions() {
        // A manager with a short idle timeout so the reaper logic is
        // testable without waiting hours.
        val manager = PulsarSessionManager(agenticContext, Duration.ofMillis(50))

        // The default session is never reaped.
        val defaultSession = manager.getOrCreateSession(null)
        // The shared swarm session is never reaped.
        val swarmSession = manager.ensureSwarmSession()
        // Named session A: created and then left idle.
        val idleSession = manager.getOrCreateSession(mapOf("sessionId" to "team-idle"))
        // Named session B: created now, accessed again right before the sweep.
        val freshSession = manager.getOrCreateSession(mapOf("sessionId" to "team-fresh"))

        Thread.sleep(120)

        // Touch the fresh session after the idle window has elapsed.
        manager.getSession("team-fresh")

        val reaped = manager.reapIdleSessions()

        assertEquals(1, reaped, "Only the idle named session should be reaped")
        assertNotNull(manager.getSession(defaultSession.sessionId), "Default session must never be reaped")
        assertNotNull(manager.getSession(swarmSession.sessionId), "Swarm session must never be reaped")
        assertNull(manager.getSession(idleSession.sessionId), "Idle named session should be reaped")
        assertNotNull(manager.getSession(freshSession.sessionId), "Recently accessed session should be kept")
    }

    @Test
    fun reapIdleSessionsKeepsAttachedSessions() {
        // Attached (CDP / extension) sessions reference external browsers —
        // the idle reaper must never tear them down implicitly.
        val info = sessionManager.createExtensionAttachedSession(channel = "chrome")

        Thread.sleep(120)

        assertEquals(0, sessionManager.reapIdleSessions())
        assertNotNull(sessionManager.getSession(info.sessionId),
            "Extension-attached session must not be reaped")
    }

    @Test
    fun displayNameRegistryPersistsAcrossManagerRestarts() {
        val registryFile = java.nio.file.Files.createTempFile("session-registry", ".json")
        try {
            val manager1 = PulsarSessionManager(agenticContext, registryFile = registryFile)
            val session = manager1.getOrCreateSession(mapOf("sessionId" to "team-persist"))

            // A new manager (simulating a backend restart) restores the mapping.
            val manager2 = PulsarSessionManager(agenticContext, registryFile = registryFile)
            val restored = manager2.getOrCreateSession(mapOf("sessionId" to "team-persist"))

            assertEquals(
                session.sessionId, restored.sessionId,
                "Display name must resolve to the same UUID after a restart"
            )
        } finally {
            java.nio.file.Files.deleteIfExists(registryFile)
        }
    }

    @Test
    fun attachedSessionIsMarkedCdpAttachedAndNotRecreated() {
        // Regression guard for the attach flow: `attach --cdp` sessions must be
        // marked CDP_ATTACHED (non-owned) so resolveHealthySession never
        // silently recreates them with a fresh Browser4-launched Chrome.
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/json/version") { ex ->
            val body = """{"Browser":"Chrome/Test","webSocketDebuggerUrl":"ws://127.0.0.1:0/devtools/browser/x"}"""
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, body.toByteArray().size.toLong())
            ex.responseBody.use { it.write(body.toByteArray()) }
        }
        server.createContext("/json") { ex ->
            val body = """[{"id":"t1","type":"page","url":"https://example.com"}]"""
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, body.toByteArray().size.toLong())
            ex.responseBody.use { it.write(body.toByteArray()) }
        }
        server.start()
        try {
            val port = server.address.port
            val info = sessionManager.createAttachedSession(cdpEndpoint = "http://127.0.0.1:$port")

            val session = sessionManager.getSession(info.sessionId)
            requireNotNull(session)
            assertEquals(SessionKind.CDP_ATTACHED, session.kind,
                "Attach-created sessions must be CDP_ATTACHED (non-owned)")
            assertFalse(session.ownsBrowser,
                "CDP-attached sessions do not own their browser")

            // Re-fetching must return the same session — never a recreation.
            val fetched = sessionManager.getOrCreateSession(info.sessionId)
            assertSame(session, fetched, "Attached session must never be recreated")
            verify(agenticContext, times(1)).createSession(
                Mockito.any(PulsarSettings::class.java) ?: PulsarSettings()
            )
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun displayNameRegistryDropsMappingOnDelete() {
        val registryFile = java.nio.file.Files.createTempFile("session-registry", ".json")
        try {
            val manager = PulsarSessionManager(agenticContext, registryFile = registryFile)
            val session = manager.getOrCreateSession(mapOf("sessionId" to "team-persist-delete"))
            manager.deleteSession(session.sessionId)

            // After deletion, a restarted manager must resolve the display
            // name to a NEW UUID (the old mapping was removed).
            val manager2 = PulsarSessionManager(agenticContext, registryFile = registryFile)
            val reopened = manager2.getOrCreateSession(mapOf("sessionId" to "team-persist-delete"))

            assertNotEquals(
                session.sessionId, reopened.sessionId,
                "Deleted session's display name must get a fresh UUID"
            )
        } finally {
            java.nio.file.Files.deleteIfExists(registryFile)
        }
    }

    @Test
    fun sessionWithLostDriverLinkIsRecoveredViaInPlaceDriverReconnect() {
        // pulsar 4.11.5+: the driver can reconnect to the same tab in place.
        // Recovery must prefer that over creating a new driver on the browser.
        val browser = Mockito.mock(Browser::class.java)
        Mockito.`when`(browser.healthy()).thenReturn(CheckState(0, "Browser is healthy"))

        val staleDriver = Mockito.mock(WebDriver::class.java)
        runBlocking {
            Mockito.`when`(staleDriver.healthy()).thenReturn(
                CheckState(503, "WebDriver is not open - the connection to the backend tab is lost")
            )
            Mockito.`when`(staleDriver.reconnect()).thenReturn(true)
        }

        val agenticSession = mockAgenticSession(isActive = true, browser = browser, driver = staleDriver)
        Mockito.doReturn(agenticSession)
            .`when`(agenticContext)
            .createSession(Mockito.any(PulsarSettings::class.java) ?: PulsarSettings())

        val session = sessionManager.getOrCreateSession(mapOf("sessionId" to "reconnect-driver-inplace"))

        assertSame(agenticSession, session.agenticSession,
            "Session must not be recreated when the driver can reconnect in place")
        assertEquals(SessionStatus.ACTIVE, session.status)
        // The same driver/tab was reconnected — no new driver must be created.
        Mockito.verify(browser, Mockito.never()).newDriver(Mockito.anyString())
        verify(agenticContext, times(1)).createSession(
            Mockito.any(PulsarSettings::class.java) ?: PulsarSettings()
        )
    }

    @Test
    fun sessionWithLostDriverLinkIsRecoveredWithoutRecreatingBrowser() {
        // Regression guard for issue #571: when the browser process is healthy
        // but the driver link (backend tab connection) is lost — e.g. after
        // machine sleep killed the CDP websocket — the session must rebind a
        // fresh driver to the SAME browser (preserving the Chrome profile,
        // cookies and manual logins) instead of recreating the session with a
        // fresh anonymous profile.
        val browser = Mockito.mock(Browser::class.java)
        Mockito.`when`(browser.healthy()).thenReturn(CheckState(0, "Browser is healthy"))

        val replacementDriver = Mockito.mock(WebDriver::class.java)
        runBlocking {
            Mockito.`when`(replacementDriver.healthy()).thenReturn(CheckState(0, "WebDriver is healthy"))
        }
        Mockito.`when`(browser.newDriver(Mockito.anyString())).thenReturn(replacementDriver)

        val staleDriver = Mockito.mock(WebDriver::class.java)
        runBlocking {
            Mockito.`when`(staleDriver.healthy()).thenReturn(
                CheckState(503, "WebDriver is not open - the connection to the backend tab is lost")
            )
            // In-place reconnect unsupported (returns false) — recovery must
            // fall back to binding a fresh driver on the same browser.
            Mockito.`when`(staleDriver.reconnect()).thenReturn(false)
        }

        val agenticSession = mockAgenticSession(isActive = true, browser = browser, driver = staleDriver)
        Mockito.doReturn(agenticSession)
            .`when`(agenticContext)
            .createSession(Mockito.any(PulsarSettings::class.java) ?: PulsarSettings())

        val session = sessionManager.getOrCreateSession(mapOf("sessionId" to "recover-driver-link"))

        assertSame(agenticSession, session.agenticSession,
            "Session must not be recreated when the driver link can be recovered on the same browser")
        assertEquals(SessionStatus.ACTIVE, session.status)
        verify(browser).newDriver(Mockito.anyString())
        verify(agenticContext, times(1)).createSession(
            Mockito.any(PulsarSettings::class.java) ?: PulsarSettings()
        )
    }

    private fun mockAgenticSession(
        isActive: Boolean = true,
        browser: Browser? = null,
        driver: WebDriver? = null,
    ): AgenticSession {
        val session = Mockito.mock(GenericAgenticSession::class.java)
        Mockito.`when`(session.isActive).thenReturn(isActive)
        Mockito.`when`(session.boundBrowser).thenReturn(browser)
        Mockito.`when`(session.boundDriver).thenReturn(driver)
        val config = Mockito.mock(VolatileConfig::class.java)
        Mockito.`when`(config.getWithFallback(Mockito.anyString(), Mockito.anyString())).thenReturn("SEQUENTIAL")
        Mockito.`when`(session.sessionConfig).thenReturn(config)
        return session
    }
}

