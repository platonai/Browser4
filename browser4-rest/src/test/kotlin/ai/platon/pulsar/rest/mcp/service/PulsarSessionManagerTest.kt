package ai.platon.pulsar.rest.mcp.service

import ai.platon.browser4.common.B4Constants.DEFAULT_SESSION_ID
import ai.platon.browser4.common.B4Constants.SWARM_SESSION_ID
import ai.platon.pulsar.agentic.AgenticSession
import ai.platon.pulsar.agentic.GenericAgenticSession
import ai.platon.pulsar.agentic.context.AgenticContexts
import ai.platon.pulsar.agentic.context.GenericAgenticContext
import ai.platon.pulsar.agentic.tools.AttachFailureException
import ai.platon.pulsar.agentic.tools.ToolErrorCode
import ai.platon.pulsar.api.BrowserManager
import ai.platon.pulsar.api.BrowserProtocol
import ai.platon.pulsar.api.model.BrowserSettings
import ai.platon.pulsar.api.model.BrowserTab
import ai.platon.pulsar.chrome.PulsarBrowser
import ai.platon.pulsar.chrome.PulsarWebDriver
import ai.platon.pulsar.common.CheckState
import ai.platon.pulsar.rest.session.PulsarSessionManager
import ai.platon.pulsar.rest.session.SessionKind
import ai.platon.pulsar.rest.session.SessionStatus
import ai.platon.pulsar.rest.session.ManagedSession
import ai.platon.pulsar.rest.session.BrowserIdentity
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
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.ArgumentCaptor
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

        // Explicit ids go through the same name -> UUID resolution as the
        // capabilities path, so both entry points address the same session.
        assertNotEquals("team-f", session.sessionId, "Explicit id should resolve to a UUID, not the raw name")
        assertEquals(session.sessionId, session.capabilities?.get("sessionId"))
        assertEquals("SEQUENTIAL", session.capabilities?.get("profileMode"))
        assertNotNull(session.capabilities?.get("contextDir"), "Named session by explicit id must bind a dedicated context dir")
        assertSame(session, sessionManager.getSession("team-f"), "Display name must resolve back to the same session")
    }

    @Test
    fun explicitIdAndCapabilitiesEntryPointsResolveToSameSessionAndContextDir() {
        val byId = sessionManager.getOrCreateSession("team-cross")
        val byCapabilities = sessionManager.getOrCreateSession(mapOf("sessionId" to "team-cross"))

        assertEquals(byId.sessionId, byCapabilities.sessionId,
            "Both entry points must resolve the same display name to the same UUID")
        assertSame(byId, byCapabilities, "Both entry points must land on the same session")
        assertEquals(
            byId.capabilities?.get("contextDir"),
            byCapabilities.capabilities?.get("contextDir"),
            "Both entry points must bind the same dedicated context dir"
        )
        assertSame(byId, sessionManager.getSession(byId.sessionId),
            "A session addressed by its resolved UUID must be found")
    }

    @Test
    fun defaultSessionByExplicitIdGetsNoNamedContextDir() {
        val session = sessionManager.getOrCreateSession(DEFAULT_SESSION_ID)

        // DEFAULT addressed through the explicit-id entry point must behave
        // exactly like the capabilities path: stable default UUID, no named
        // context dir (regression guard for a DEFAULT misjudged as named).
        assertNotEquals(DEFAULT_SESSION_ID, session.sessionId, "DEFAULT should be resolved to a UUID")
        assertEquals(session.sessionId, session.capabilities?.get("sessionId"))
        assertNull(session.capabilities?.get("contextDir"), "Default sessions must not pin a named context dir")
        assertSame(session, sessionManager.getOrCreateSession(mapOf("sessionId" to DEFAULT_SESSION_ID)),
            "DEFAULT by explicit id and by capability must land on the same session")
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

        // The session must be marked "attached", so its (lazy) driver creation can
        // never launch a replacement browser next to the user's browser while the
        // extension is not connected.
        val settingsCaptor = ArgumentCaptor.forClass(PulsarSettings::class.java)
        verify(agenticContext).createSession(settingsCaptor.capture() ?: PulsarSettings())
        assertEquals(
            true, settingsCaptor.value.attached,
            "An extension-attached session must never launch a browser of its own"
        )
    }

    @Test
    fun ownedSessionMayLaunchABrowser() {
        // The counterpart of the attached-session guard: a Browser4-launched
        // session owns its browser, so the lazy launch policy must not be attached.
        sessionManager.getOrCreateSession(mapOf("sessionId" to "owned-session"))

        val settingsCaptor = ArgumentCaptor.forClass(PulsarSettings::class.java)
        verify(agenticContext).createSession(settingsCaptor.capture() ?: PulsarSettings())
        assertNotEquals(
            true, settingsCaptor.value.attached,
            "An owned session must be allowed to launch its browser (lazily)"
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

            // A CDP-attached session references the user's browser and must never
            // launch one of its own (lazy launch must not add a second window).
            val settingsCaptor = ArgumentCaptor.forClass(PulsarSettings::class.java)
            verify(agenticContext).createSession(settingsCaptor.capture() ?: PulsarSettings())
            assertEquals(
                true, settingsCaptor.value.attached,
                "A CDP-attached session must never launch a browser of its own"
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
    fun attachOverADeadBrowserWebSocketFailsLoudly() {
        // The browser-level WebSocket path (Chrome's built-in remote debugging)
        // must fail at attach time when nothing answers on the socket — never
        // bind a browser that drives nothing. The failure carries its own code
        // (deliberately not an IllegalArgumentException, so it never triggers the
        // signature/help dump).
        val deadPort = java.net.ServerSocket(0).use { it.localPort }
        val endpoint = "ws://127.0.0.1:$deadPort/devtools/browser/8b91cacf-d8aa-4fa3-8b45-687c7de7af8a"

        val failure = assertThrows(AttachFailureException::class.java) {
            sessionManager.createAttachedSession(cdpEndpoint = endpoint)
        }

        assertEquals(ToolErrorCode.ENDPOINT_UNREACHABLE, failure.code)
        val message = failure.message ?: ""
        assertTrue(message.contains("could not be attached over its browser-level WebSocket"), message)
        assertTrue(message.contains(endpoint), message)
        assertTrue(message.contains("retry attach"), message)
        // No session may be registered: the attach never succeeded.
        assertNull(sessionManager.getSession(endpoint))
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

    @Test
    fun cdpAttachedGateReturnsNullForHealthyAttachedSession() {
        val browser = Mockito.mock(Browser::class.java)
        runBlocking {
            Mockito.`when`(browser.healthy()).thenReturn(CheckState())
        }
        val session = ManagedSession(
            sessionId = "alive-attached",
            agenticSession = mockAgenticSession(isActive = true, browser = browser),
            capabilities = mapOf(),
            kind = SessionKind.CDP_ATTACHED,
        )

        assertNull(sessionManager.cdpAttachedSessionUnavailable(session))
    }

    @Test
    fun cdpAttachedGateReportsDeadAttachedBrowserAndMarksInactive() {
        val browser = Mockito.mock(Browser::class.java)
        val session = ManagedSession(
            sessionId = "dead-attached",
            agenticSession = mockAgenticSession(isActive = false, browser = browser),
            capabilities = mapOf(),
            kind = SessionKind.CDP_ATTACHED,
            browserIdentity = BrowserIdentity("chrome", "Google Chrome", "138.0.0.0"),
        )

        val failure = sessionManager.cdpAttachedSessionUnavailable(session)
        assertNotNull(failure)
        val message = failure!!.message
        assertTrue(message.contains("dead-attached"), "message should name the session: $message")
        assertTrue(message.contains("Google Chrome 138.0.0.0"), "message should identify the browser: $message")
        assertTrue(message.contains("Re-attach", ignoreCase = true), "message should advise re-attach: $message")
        assertEquals(
            ToolErrorCode.ENDPOINT_UNREACHABLE, failure.code,
            "a browser that cannot answer its own health check is unreachable"
        )
        assertEquals(SessionStatus.STOPPED, session.status, "dead attached session must be marked inactive")
    }

    @Test
    @DisplayName("the liveness gate blames the dead tab, not the browser, when the browser is alive")
    fun cdpAttachedGateDistinguishesDeadTabFromLiveBrowser() {
        // The reported defect: a session whose driven tab was closed externally
        // was told its browser had been "closed or crashed" while `curl` on the
        // CDP port answered 200 — so the user hunted for a crash that never
        // happened (attach-remote-debug#4). A live browser with a dead driver
        // link is a different fact and needs a different message AND code.
        val browser = Mockito.mock(Browser::class.java)
        runBlocking { Mockito.`when`(browser.healthy()).thenReturn(CheckState(0, "Browser is healthy")) }

        val deadDriver = Mockito.mock(WebDriver::class.java)
        Mockito.`when`(deadDriver.browser).thenReturn(browser)
        runBlocking {
            Mockito.`when`(deadDriver.healthy()).thenReturn(
                CheckState(503, "WebDriver is not open - the connection to the backend tab is lost")
            )
        }

        val session = ManagedSession(
            sessionId = "dead-tab",
            agenticSession = mockAgenticSession(isActive = true, browser = browser, driver = deadDriver),
            capabilities = mapOf(),
            kind = SessionKind.CDP_ATTACHED,
            browserIdentity = BrowserIdentity("chrome", "Google Chrome", "155.0.0.0"),
        )

        val failure = sessionManager.cdpAttachedSessionUnavailable(session)
        assertNotNull(failure)
        val message = failure!!.message
        assertEquals(
            ToolErrorCode.ENDPOINT_NO_PAGE_TARGETS, failure.code,
            "a live browser with no drivable page is not an unreachable endpoint"
        )
        assertTrue(
            message.contains("still running"),
            "the browser is alive and the message must say so: $message"
        )
        assertFalse(
            message.contains("no longer reachable"),
            "the browser was never unreachable, so the message must not claim it was: $message"
        )
        assertTrue(message.contains("Google Chrome 155.0.0.0"), message)
        assertTrue(message.contains("dead-tab"), message)
        assertEquals(SessionStatus.STOPPED, session.status)
    }

    @Test
    fun cdpAttachedGateIgnoresOwnedSessionsEvenWhenUnhealthy() {
        val browser = Mockito.mock(Browser::class.java)
        val session = ManagedSession(
            sessionId = "owned",
            agenticSession = mockAgenticSession(isActive = false, browser = browser),
            capabilities = mapOf(),
            kind = SessionKind.BROWSER4_LAUNCHED,
        )

        // Owned sessions have their own recovery/recreation path; the gate must
        // never block them with an attach-specific message.
        assertNull(sessionManager.cdpAttachedSessionUnavailable(session))
    }

    @Test
    fun cdpAttachedGateIgnoresAttachedSessionThatNeverBoundABrowser() {
        val session = ManagedSession(
            sessionId = "unbound-attached",
            agenticSession = mockAgenticSession(isActive = true, browser = null),
            capabilities = mapOf(),
            kind = SessionKind.CDP_ATTACHED,
        )

        // Nothing bound yet — let the normal tool path report its own error.
        assertNull(sessionManager.cdpAttachedSessionUnavailable(session))
    }

    @Test
    @DisplayName("re-attach rebuilds the bound driver on the browser it just bound")
    fun reAttachRebuildsTheBoundDriverOnTheNewlyBoundBrowser() {
        // The reported defect: re-attach reported success, rebound the browser
        // wrapper, and left the OLD driver bound — so the session kept failing on
        // a driver stuck in INIT and the documented recovery could not recover
        // (attach-remote-debug#1). The session's bound driver must be one built on
        // the browser this attach bound.
        val server = cdpStubServer()
        try {
            val port = server.address.port
            val bindings = SessionBindings()
            val session = mockBindingSession(bindings)
            Mockito.doReturn(session)
                .`when`(agenticContext)
                .createSession(Mockito.any(PulsarSettings::class.java) ?: PulsarSettings())

            val capabilities = mapOf("sessionId" to "reattach-guard")
            var browserIndex = 0
            val construction = Mockito.mockConstruction(PulsarBrowser::class.java) { browser, _ ->
                stubAttachedBrowser(browser, port, browserIndex++)
            }
            try {
                sessionManager.createAttachedSession(
                    cdpEndpoint = "http://127.0.0.1:$port", capabilities = capabilities
                )
                val firstBrowser = construction.constructed()[0]

                // A first attach binds no driver eagerly; the session's first
                // command builds one on that wrapper. Simulate that driver, its tab
                // having been closed externally: unhealthy, but still the session's
                // bound driver.
                val staleDriver = Mockito.mock(PulsarWebDriver::class.java)
                Mockito.`when`(staleDriver.browser).thenReturn(firstBrowser)
                runBlocking {
                    Mockito.`when`(staleDriver.healthy()).thenReturn(
                        CheckState(503, "WebDriver is not open - the connection to the backend tab is lost")
                    )
                }
                bindings.driver = staleDriver
                bindings.browser = firstBrowser

                // The documented recovery: attach again to the same endpoint.
                sessionManager.createAttachedSession(
                    cdpEndpoint = "http://127.0.0.1:$port", capabilities = capabilities
                )

                val reboundBrowser = construction.constructed()[1]
                val rebound = requireNotNull(bindings.driver) {
                    "re-attach must leave the session with a usable driver"
                }
                assertNotSame(staleDriver, rebound, "the stale driver must not survive the re-attach")
                assertTrue(rebound is PulsarWebDriver, "the bound driver must be a pulsar driver")
                val pulsarDriver = rebound as PulsarWebDriver
                assertSame(
                    reboundBrowser, pulsarDriver.browser,
                    "the bound driver must be built on the browser this attach bound"
                )
                // The replacement came from a page tab of the NEW wrapper, not from
                // the wrapper the stale driver was driving.
                Mockito.verify(reboundBrowser).newDriverForTab(pulsarDriver.chromeTab)
                // The stale driver's tab is deliberately left open: it may be the
                // user's visible page, and a re-attach must not cost them a tab.
                Mockito.verify(staleDriver, Mockito.never()).close()
            } finally {
                construction.close()
            }
        } finally {
            server.stop(0)
        }
    }

    @Test
    @DisplayName("closing one attached session of a shared browser detaches instead of tearing it down")
    fun closingOneSharedAttachedSessionDetachesOnlyThatSession() {
        // The reported defect: two sessions attached to one browser shared its
        // browser object, so closing one retired the sibling's driver and the tab
        // it drove — contradicting the documented session independence
        // (attach-remote-debug#3). Closing the last one owns the teardown again.
        val server = cdpStubServer()
        try {
            val port = server.address.port
            val browserManager = Mockito.mock(BrowserManager::class.java)
            Mockito.`when`(agenticContext.browserManager).thenReturn(browserManager)

            val bindingsA = SessionBindings()
            val bindingsB = SessionBindings()
            val sessionA = mockBindingSession(bindingsA)
            val sessionB = mockBindingSession(bindingsB)
            Mockito.`when`(sessionA.context).thenReturn(agenticContext)
            Mockito.`when`(sessionB.context).thenReturn(agenticContext)
            val created = ArrayDeque(listOf(sessionA, sessionB))
            Mockito.doAnswer { created.removeFirst() }
                .`when`(agenticContext).createSession(Mockito.any(PulsarSettings::class.java) ?: PulsarSettings())

            var browserIndex = 0
            val construction = Mockito.mockConstruction(PulsarBrowser::class.java) { browser, _ ->
                stubAttachedBrowser(browser, port, browserIndex++)
            }
            try {
                val a = sessionManager.createAttachedSession(
                    cdpEndpoint = "http://127.0.0.1:$port",
                    capabilities = mapOf("sessionId" to "shared-a")
                )
                val b = sessionManager.createAttachedSession(
                    cdpEndpoint = "http://127.0.0.1:$port",
                    capabilities = mapOf("sessionId" to "shared-b")
                )
                val browserA = construction.constructed()[0]
                val browserB = construction.constructed()[1]

                // Each session drives its own tab through its own driver.
                val driverA = Mockito.mock(PulsarWebDriver::class.java)
                val driverB = Mockito.mock(PulsarWebDriver::class.java)
                bindingsA.driver = driverA
                bindingsA.browser = browserA
                bindingsB.driver = driverB
                bindingsB.browser = browserB

                val report = sessionManager.closeSession(a.sessionId)
                assertNotNull(report)
                assertTrue(report!!.detachedOnly, "the close must report that it only detached")
                assertEquals(listOf(b.sessionId), report.sharedWith)
                // The shared browser object must not be closed...
                Mockito.verify(browserA, Mockito.never()).close()
                Mockito.verify(browserManager, Mockito.never())
                    .closeBrowser(Mockito.any(Browser::class.java) ?: browserA)
                // ...only the closing session's own driver goes, and that session
                // releases its bindings so the close that follows cannot reach the
                // shared browser either.
                Mockito.verify(driverA).close()
                Mockito.verify(driverB, Mockito.never()).close()
                assertNull(bindingsA.driver)
                assertNull(bindingsA.browser)
                assertSame(browserB, bindingsB.browser, "the sibling keeps its browser")
                assertSame(driverB, bindingsB.driver, "the sibling keeps its driver")

                // Last one out: nothing else drives the browser, so the close owns
                // the teardown again.
                val lastReport = sessionManager.closeSession(b.sessionId)
                assertNotNull(lastReport)
                assertFalse(lastReport!!.detachedOnly)
                assertTrue(lastReport.sharedWith.isEmpty(), lastReport.sharedWith.toString())
                Mockito.verify(browserManager).closeBrowser(browserB)
            } finally {
                construction.close()
            }
        } finally {
            server.stop(0)
        }
    }

    /**
     * A CDP discovery stub: `/json/version` names a browser and `/json` lists one
     * page target, which is what the attach verification probe reads.
     */
    private fun cdpStubServer(): HttpServer {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/json/version") { ex ->
            val body = """{"Browser":"Chrome/155.0.0.0","webSocketDebuggerUrl":"ws://127.0.0.1:0/devtools/browser/x"}"""
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
        return server
    }

    /**
     * Stubs one mock attached-browser wrapper: it reports [port] (so two sessions
     * can be seen to drive the same endpoint) and offers one page tab to bind a
     * driver to.
     */
    private fun stubAttachedBrowser(browser: PulsarBrowser, port: Int, index: Int) {
        Mockito.`when`(browser.port).thenReturn(port)
        Mockito.`when`(browser.settings).thenReturn(BrowserSettings())
        val tab = BrowserTab().apply {
            id = "tab-$index"; type = "page"; url = "https://example.com/$index"
        }
        Mockito.`when`(browser.listTabs()).thenReturn(arrayOf(tab))
        val raw = Mockito.mock(PulsarWebDriver::class.java)
        Mockito.`when`(raw.guid).thenReturn("guid-$index")
        Mockito.`when`(raw.chromeTab).thenReturn(tab)
        Mockito.`when`(raw.browserProtocol).thenReturn(Mockito.mock(BrowserProtocol::class.java))
        Mockito.`when`(raw.browser).thenReturn(browser)
        Mockito.`when`(browser.newDriverForTab(tab)).thenReturn(raw)
    }

    /**
     * What one session has bound — the contract the manager actually uses:
     * `boundDriver` / `boundBrowser` answer with what `bind*` stored, and stop
     * answering once `unbind*` removed it. Which bean the library's real config
     * resolves behind those accessors is the library's business; these tests pin
     * what the manager asks of it.
     */
    private class SessionBindings {
        var driver: WebDriver? = null
        var browser: Browser? = null
    }

    /**
     * A session whose binding contract is deterministic, so a test can hand it a
     * stale driver and observe exactly which driver it is left holding.
     */
    private fun mockBindingSession(bindings: SessionBindings): AgenticSession {
        val anyDriver = Mockito.mock(WebDriver::class.java)
        val anyBrowser = Mockito.mock(PulsarBrowser::class.java)
        val session = Mockito.mock(GenericAgenticSession::class.java)
        Mockito.`when`(session.isActive).thenReturn(true)
        Mockito.`when`(session.boundDriver).thenAnswer { bindings.driver }
        Mockito.`when`(session.boundBrowser).thenAnswer { bindings.browser }
        Mockito.doAnswer { bindings.driver = null }
            .`when`(session).unbindDriver(Mockito.any(WebDriver::class.java) ?: anyDriver)
        Mockito.doAnswer { bindings.browser = null }
            .`when`(session).unbindBrowser(Mockito.any(Browser::class.java) ?: anyBrowser)
        Mockito.doAnswer { invocation ->
            val driver = invocation.getArgument<WebDriver>(0)
            bindings.driver = driver
            bindings.browser = driver.browser
        }.`when`(session).bindDriver(Mockito.any(WebDriver::class.java) ?: anyDriver)
        Mockito.doAnswer { invocation ->
            bindings.browser = invocation.getArgument<Browser>(0)
        }.`when`(session).bindBrowser(Mockito.any(Browser::class.java) ?: anyBrowser)
        return session
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

