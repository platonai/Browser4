package ai.platon.pulsar.rest.session

import ai.platon.browser4.chrome.PulsarBrowser
import ai.platon.browser4.chrome.handler.transport.ExtensionChromeService
import ai.platon.browser4.chrome.handler.transport.ExtensionMessageSender
import ai.platon.browser4.common.B4Constants.BROWSER_PROFILE_MODE
import ai.platon.browser4.common.B4Constants.DEFAULT_SESSION_ID
import ai.platon.browser4.common.B4Constants.PROFILE_MODE_CAPABILITY
import ai.platon.browser4.common.B4Constants.SESSION_ID_CAPABILITY
import ai.platon.browser4.common.B4Constants.SWARM_SESSION_ID
import ai.platon.pulsar.agentic.context.AbstractAgenticContext
import ai.platon.pulsar.agentic.context.AgenticContext
import ai.platon.pulsar.agentic.context.AgenticContexts
import ai.platon.pulsar.browser.common.BrowserSettings
import ai.platon.pulsar.common.CheckState
import ai.platon.pulsar.common.browser.BrowserProfileMode
import ai.platon.pulsar.common.config.CapabilityTypes.BROWSER_CONTEXT_MODE
import ai.platon.pulsar.core.api.PulsarSettings
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.io.Closeable
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages AgenticSession-backed browser sessions and their lifecycle.
 *
 * This component is framework-agnostic and can be wired manually or exposed
 * through an external dependency injection container.
 */
class PulsarSessionManager(
    val agenticContext: AgenticContext
) : Closeable {
    private val logger = LoggerFactory.getLogger(PulsarSessionManager::class.java)

    private val sessions = ConcurrentHashMap<String, ManagedSession>()

    /**
     * The swarm session is a special session that used for swarm use cases. It is created on demand and shared
     * across all requests that specify the swarm session ID. The profile mode is pinned to SEQUENTIAL or TEMPORARY
     * based on the input capabilities, but defaults to SEQUENTIAL if not specified or invalid. The swarm session
     * is designed to be shared across multiple requests, so it should not be recreated if it already exists.
     * The capabilities can be used to create the swarm session profile if the swarm session does not exist,
     * or will be ignored if the swarm session is already exists.
     *
     * The swarm session may launch multiple browser contexts, each browser context isolated and has its own profile,
     * but all browser contexts share the same session-level profile which is determined by the profile mode.
     * */
    fun ensureSwarmSession(capabilities: Map<String, String?>? = null): ManagedSession {
        val sessionId = SWARM_SESSION_ID
        val normalizedCapabilities = normalizeCapabilities(sessionId, capabilities).toMutableMap()
        normalizedCapabilities[SESSION_ID_CAPABILITY] = sessionId
        // normalizedCapabilities[BROWSER_PROFILE_MODE] = browserProfileMode

        val settings = PulsarSettings.parse(normalizedCapabilities)
        val agenticSession = AgenticContexts.ensureSwarmSession(settings, (agenticContext as AbstractAgenticContext).applicationContext)

        // val pulsarSession = session.agenticSession

        // post check if the session is a swarm session
//        require(pulsarSession.boundBrowser == null)
//        require(pulsarSession.boundDriver == null)
        val conf = agenticSession.sessionConfig

        val browserProfileMode = conf.getWithFallback(BROWSER_PROFILE_MODE, BROWSER_CONTEXT_MODE)
        require(browserProfileMode == BrowserProfileMode.SEQUENTIAL.name || browserProfileMode == BrowserProfileMode.TEMPORARY.name) {
            "Swarm session must have profile mode SEQUENTIAL or TEMPORARY, but got $browserProfileMode"
        }

        val session = sessions.computeIfAbsent(sessionId) {
            ManagedSession(sessionId, agenticSession, normalizedCapabilities)
        }

        return session
    }

    fun getOrCreateSession(sessionId: String, capabilities: Map<String, String?>? = null): ManagedSession {
        // Route SWARM sessions before computeIfAbsent — ensureSwarmSession
        // internally calls computeIfAbsent, and ConcurrentHashMap forbids
        // nested computeIfAbsent on the same key.
        if (sessionId.equals(SWARM_SESSION_ID, ignoreCase = true)) {
            return ensureSwarmSession(capabilities)
        }

        val normalizedCapabilities = normalizeCapabilities(sessionId, capabilities)
        val session = sessions.computeIfAbsent(sessionId) {
            createManagedSession(sessionId, normalizedCapabilities)
        }

        val activeSession = resolveHealthySession(sessionId, normalizedCapabilities, session)

        activeSession.lastAccessedAt = System.currentTimeMillis()
        return activeSession
    }

    /**
     * Creates a new browser session with the specified capabilities.
     *
     * @param capabilities Optional browser capabilities (browserName, etc.)
     * @return The created managed session.
     */
    fun getOrCreateSession(capabilities: Map<String, String?>? = null): ManagedSession {
        val normalizedCapabilities = normalizeCapabilities(capabilities = capabilities)
        val sessionId = normalizedCapabilities.getValue(SESSION_ID_CAPABILITY).toString()

        // Route SWARM sessions before computeIfAbsent (see explanation above).
        if (sessionId.equals(SWARM_SESSION_ID, ignoreCase = true)) {
            return ensureSwarmSession(capabilities)
        }

        val session = sessions.computeIfAbsent(sessionId) {
            createManagedSession(sessionId, normalizedCapabilities)
        }
        val activeSession = resolveHealthySession(sessionId, normalizedCapabilities, session)
        activeSession.lastAccessedAt = System.currentTimeMillis()
        return activeSession
    }

    fun checkHealthyBlocking(session: ManagedSession): CheckState {
        return runBlocking { checkHealthy(session) }
    }

    suspend fun checkHealthy(session: ManagedSession): CheckState {
        val s = session.agenticSession
        val browser = s.boundBrowser
        val driver = s.boundDriver

        if (driver != null && driver.browser != browser) {
            logger.warn(
                "Inconsistent driver/browser. Driver {} state: {} browser {} state: {}",
                driver.id, driver.readableState, browser?.id, browser?.readableState
            )
        }

        var healthy = CheckState(if (s.isActive) 0 else -1)
        if (!healthy.isOK) {
            logger.warn("AgenticSession {} is not healthy", s.id)
        }

        if (healthy.isOK) {
            healthy = browser?.healthy() ?: CheckState()
            if (!healthy.isOK && browser != null) {
                logger.warn("Bound browser {} is unhealthy, state: {}", browser.id, browser.readableState)
            }

            if (healthy.isOK) {
                healthy = s.boundDriver?.healthy() ?: CheckState()
                if (!healthy.isOK && driver != null) {
                    logger.warn("Bound driver {} is unhealthy, state: {}", driver.id, driver.readableState)
                }
            }
        }

        if (!healthy.isOK) {
            logger.warn(
                "Session {} is unhealthy: session active={}, browser healthy={}, driver healthy={}",
                session.sessionId,
                s.isActive,
                s.boundBrowser?.healthy() ?: "N/A",
                s.boundDriver?.healthy() ?: "N/A"
            )
        }

        return healthy
    }

    private fun resolveHealthySession(
        sessionId: String,
        capabilities: Map<String, String?>,
        session: ManagedSession,
    ): ManagedSession {
        if (checkHealthyBlocking(session).isOK) {
            return markSessionActive(session)
        }

        val recreatedSession = recreateUnhealthySession(sessionId, capabilities, session)
        return if (checkHealthyBlocking(recreatedSession).isOK) {
            markSessionActive(recreatedSession)
        } else {
            markSessionInactive(recreatedSession)
            logger.warn("Replacement session {} is still unhealthy after recreation", sessionId)
            recreatedSession
        }
    }

    private fun createManagedSession(sessionId: String, capabilities: Map<String, String?>): ManagedSession {
        val settings = PulsarSettings.parse(capabilities)
        val agenticSession = agenticContext.createSession(settings)

        return ManagedSession(
            sessionId = sessionId,
            agenticSession = agenticSession,
            capabilities = capabilities,
            status = if (agenticSession.isActive) "active" else "stopped"
        ).also {
            logger.info("Created session {} with capabilities: {}", sessionId, capabilities)
        }
    }

    /**
     * Creates a session and attaches it to an already-running browser via CDP.
     *
     * Unlike [getOrCreateSession], this does not launch a new browser process.
     * Instead, it connects to an existing browser at the given CDP endpoint or port
     * and binds it to the newly created session.
     *
     * @param cdpEndpoint A CDP HTTP endpoint URL (e.g. "http://localhost:9222").
     * @param cdpPort A CDP port number. Used when [cdpEndpoint] is null.
     * @param capabilities Optional session capabilities.
     * @return The created managed session with the external browser bound.
     */
    fun createAttachedSession(
        cdpEndpoint: String? = null,
        cdpPort: Int? = null,
        capabilities: Map<String, String?>? = null,
    ): ManagedSession {
        require(cdpEndpoint != null || cdpPort != null) {
            "attach_browser requires either 'cdpEndpoint' (URL) or 'cdpPort' (number)"
        }

        val normalizedCapabilities = normalizeCapabilities(capabilities = capabilities)
        val sessionId = normalizedCapabilities.getValue(SESSION_ID_CAPABILITY).toString()

        val port = when {
            cdpPort != null -> cdpPort
            cdpEndpoint != null -> parsePortFromEndpoint(cdpEndpoint)
            else -> throw IllegalArgumentException("No CDP endpoint or port provided")
        }

        val session = sessions.computeIfAbsent(sessionId) {
            createManagedSession(sessionId, normalizedCapabilities)
        }

        // Bind the external browser to the session
        val browser = PulsarBrowser(port = port, settings = BrowserSettings())
        session.agenticSession.bindBrowser(browser)

        logger.info(
            "Attached session {} to browser at port {} (endpoint: {})",
            sessionId, port, cdpEndpoint ?: "N/A"
        )

        return session
    }

    // ------------------------------------------------------------------
    // Extension-attached sessions (Browser4 Chrome Extension relay)
    // ------------------------------------------------------------------

    /** Sessions waiting for the extension to connect via WebSocket. */
    private val pendingExtensionConnections = ConcurrentHashMap<String, PendingExtensionConnection>()

    /** Active extension browser instances keyed by sessionId. */
    private val extensionBrowsers = ConcurrentHashMap<String, ExtensionChromeService>()

    /** Injected by [ai.platon.pulsar.rest.config.ExtensionWebSocketConfig]. */
    @Volatile
    var serverPort: Int = 8182

    /**
     * Creates a pending session that will be bound to the Browser4 Chrome
     * Extension once it connects via WebSocket.
     *
     * @param channel Optional browser channel hint (chrome, msedge, etc.) — informational only.
     * @param capabilities Optional session capabilities.
     * @return Info containing the sessionId and the ws:// endpoint URL the extension should connect to.
     */
    fun createExtensionAttachedSession(
        channel: String? = null,
        capabilities: Map<String, String?>? = null,
    ): ExtensionSessionInfo {
        val normalizedCapabilities = normalizeCapabilities(capabilities = capabilities)
        val sessionId = normalizedCapabilities.getValue(SESSION_ID_CAPABILITY).toString()

        // Create the managed session (without a bound browser yet).
        val session = sessions.computeIfAbsent(sessionId) {
            createManagedSession(sessionId, normalizedCapabilities)
        }

        val wsEndpoint = "ws://127.0.0.1:$serverPort/ws/extension/$sessionId"
        pendingExtensionConnections[sessionId] = PendingExtensionConnection(
            sessionId = sessionId,
            wsEndpoint = wsEndpoint,
            createdAt = System.currentTimeMillis()
        )

        logger.info(
            "Created pending extension session {} at {} (channel={})",
            sessionId, wsEndpoint, channel ?: "default"
        )

        return ExtensionSessionInfo(sessionId, wsEndpoint)
    }

    /**
     * Called by [ExtensionWebSocketHandler] when an extension WebSocket
     * connection is established.  Creates an [ExtensionChromeService] wrapping
     * the connection and binds it as the browser for the pending session.
     */
    fun onExtensionConnected(sessionId: String, sender: ExtensionMessageSender) {
        val pending = pendingExtensionConnections.remove(sessionId)
            ?: throw IllegalStateException("No pending extension connection for session $sessionId")

        val managedSession = sessions[sessionId]
            ?: throw IllegalStateException("Session $sessionId not found")

        // Create the ExtensionChromeService that bridges the WebSocket
        // relay protocol to the internal ChromeService abstraction.
        val extChrome = ExtensionChromeService(sender, sessionId)

        // Wrap it as a PulsarBrowser so the session can use it.
        val browser = PulsarBrowser(
            id = ai.platon.pulsar.browser.BrowserId.RANDOM_TEMP,
            chrome = extChrome,
            settings = BrowserSettings(),
            launcher = null
        )

        // Bind the browser to the agentic session.
        managedSession.agenticSession.bindBrowser(browser)
        extensionBrowsers[sessionId] = extChrome

        logger.info(
            "Extension connected and bound to session {} | elapsed={}ms",
            sessionId, System.currentTimeMillis() - pending.createdAt
        )
    }

    /**
     * Routes an incoming text message from the extension WebSocket to the
     * appropriate [ExtensionChromeService].
     */
    fun routeExtensionMessage(sessionId: String, message: String) {
        extensionBrowsers[sessionId]?.handleIncomingMessage(message)
    }

    /**
     * Called when the extension WebSocket disconnects.  Closes the
     * [ExtensionChromeService] and marks the session stopped.
     */
    fun onExtensionDisconnected(sessionId: String) {
        val browser = extensionBrowsers.remove(sessionId)
        browser?.close()
        pendingExtensionConnections.remove(sessionId)

        logger.info("Extension disconnected from session {}", sessionId)

        // Mark the session as stopped so health checks reflect the state.
        sessions[sessionId]?.let { session ->
            if (session.status != "stopped") {
                session.status = "stopped"
            }
        }
    }

    /**
     * Returns true if the extension has connected and the session has a bound
     * extension browser.
     */
    fun isExtensionSessionReady(sessionId: String): Boolean {
        return extensionBrowsers.containsKey(sessionId)
    }

    // ------------------------------------------------------------------
    // Internal data classes
    // ------------------------------------------------------------------

    data class ExtensionSessionInfo(
        val sessionId: String,
        val wsEndpoint: String
    )

    private data class PendingExtensionConnection(
        val sessionId: String,
        val wsEndpoint: String,
        val createdAt: Long
    )

    /**
     * Extracts the port number from a CDP endpoint URL.
     * Handles formats: http://host:port, ws://host:port/path, host:port
     * IPv6 addresses are supported when bracketed (e.g. [::1]:9222).
     */
    private fun parsePortFromEndpoint(endpoint: String): Int {
        // Try parsing as-is first, then with an http:// scheme prepended.
        val uri = try {
            URI(endpoint)
        } catch (_: Exception) {
            URI.create("http://$endpoint")
        }

        if (uri.port > 0) return uri.port

        // Fallback: extract host:port from the authority-like portion.
        // Strip scheme and path, then parse the remaining host:port.
        val hostPort = endpoint
            .substringAfter("://")       // drop scheme (http://, ws://, etc.)
            .substringBefore('/')        // drop path / query / fragment
            .substringBefore('?')
            .substringBefore('#')

        // IPv6: the port follows the closing bracket, e.g. [::1]:9222
        if (hostPort.contains(']')) {
            val afterBracket = hostPort.substringAfterLast(']')
            if (afterBracket.startsWith(':')) {
                return afterBracket.removePrefix(":").toIntOrNull()
                    ?: throw IllegalArgumentException(
                        "Could not parse CDP port from endpoint: $endpoint")
            }
            throw IllegalArgumentException(
                "IPv6 address must be followed by :port in endpoint: $endpoint")
        }

        // Plain host:port (IPv4 or hostname)
        val lastColon = hostPort.lastIndexOf(':')
        if (lastColon >= 0) {
            return hostPort.substring(lastColon + 1).toIntOrNull()
                ?: throw IllegalArgumentException(
                    "Could not parse CDP port from endpoint: $endpoint")
        }

        throw IllegalArgumentException(
            "Could not parse CDP port from endpoint: $endpoint")
    }

    private fun recreateUnhealthySession(
        sessionId: String,
        capabilities: Map<String, String?>,
        staleSession: ManagedSession,
    ): ManagedSession {
        return sessions.compute(sessionId) { _, existingSession ->
            when {
                existingSession == null -> createManagedSession(sessionId, capabilities)
                checkHealthyBlocking(existingSession).isOK -> {
                    if (!existingSession.status.equals("active", ignoreCase = true)) {
                        existingSession.status = "active"
                    }
                    existingSession
                }

                else -> {
                    markSessionInactive(existingSession)
                    if (existingSession === staleSession) {
                        logger.warn("Cached session {} is unhealthy, creating a replacement", sessionId)
                    } else {
                        logger.warn("Concurrent cached session {} is unhealthy, creating a replacement", sessionId)
                    }
                    createManagedSession(sessionId, capabilities)
                }
            }
        }!!
    }

    private fun markSessionActive(session: ManagedSession): ManagedSession {
        if (!session.status.equals("active", ignoreCase = true)) {
            session.status = "active"
        }
        return session
    }

    private fun markSessionInactive(session: ManagedSession) {
        session.status = "stopped"
    }

    private fun normalizeCapabilities(
        explicitSessionId: String? = null,
        capabilities: Map<String, String?>?,
    ): Map<String, String?> {
        val normalizedCapabilities = LinkedHashMap(capabilities.orEmpty())
        val hasExplicitSessionId = !explicitSessionId.isNullOrBlank()
        val requestedSessionId = normalizedCapabilities[SESSION_ID_CAPABILITY]?.trim()
        val sessionId = when {
            explicitSessionId.equals(DEFAULT_SESSION_ID, ignoreCase = true) -> DEFAULT_SESSION_ID
            explicitSessionId.equals(SWARM_SESSION_ID, ignoreCase = true) -> SWARM_SESSION_ID
            hasExplicitSessionId -> explicitSessionId.trim()
            requestedSessionId.isNullOrBlank() || requestedSessionId.equals(
                DEFAULT_SESSION_ID,
                ignoreCase = true
            ) -> DEFAULT_SESSION_ID

            requestedSessionId.equals(SWARM_SESSION_ID, ignoreCase = true) -> SWARM_SESSION_ID
            else -> requestedSessionId
        }

        val requestedProfileMode = BrowserProfileMode.fromString(
            normalizedCapabilities[PROFILE_MODE_CAPABILITY]?.toString()
        )

        normalizedCapabilities[SESSION_ID_CAPABILITY] = sessionId
        normalizedCapabilities[PROFILE_MODE_CAPABILITY] = when {
            sessionId.equals(SWARM_SESSION_ID, ignoreCase = true) -> when (requestedProfileMode) {
                BrowserProfileMode.TEMPORARY -> BrowserProfileMode.TEMPORARY
                BrowserProfileMode.SEQUENTIAL -> BrowserProfileMode.SEQUENTIAL
                else -> BrowserProfileMode.SEQUENTIAL
            }

            hasExplicitSessionId && sessionId.equals(
                DEFAULT_SESSION_ID,
                ignoreCase = true
            ) -> BrowserProfileMode.DEFAULT

            sessionId.equals(
                DEFAULT_SESSION_ID,
                ignoreCase = true
            ) && requestedProfileMode == BrowserProfileMode.SEQUENTIAL -> BrowserProfileMode.SEQUENTIAL

            sessionId.equals(DEFAULT_SESSION_ID, ignoreCase = true) -> BrowserProfileMode.DEFAULT
            requestedProfileMode == BrowserProfileMode.SEQUENTIAL -> BrowserProfileMode.SEQUENTIAL
            else -> BrowserProfileMode.SEQUENTIAL
        }.name

        return normalizedCapabilities
    }

    /**
     * Retrieves a session by ID.
     *
     * @param sessionId The session identifier.
     * @return The managed session, or null if not found.
     */
    fun getSession(sessionId: String): ManagedSession? {
        val session = if (sessionId.equals(DEFAULT_SESSION_ID, ignoreCase = true)) {
            getOrCreateSession(mapOf(SESSION_ID_CAPABILITY to DEFAULT_SESSION_ID))
        } else {
            sessions[sessionId]?.let { existingSession ->
                val normalizedCapabilities = normalizeCapabilities(
                    sessionId,
                    existingSession.capabilities ?: mapOf(SESSION_ID_CAPABILITY to existingSession.sessionId)
                )
                resolveHealthySession(sessionId, normalizedCapabilities, existingSession)
            }
        }
        session?.lastAccessedAt = System.currentTimeMillis()
        return session
    }

    /**
     * Deletes a session and cleans up resources.
     *
     * @param sessionId The session identifier.
     * @return True if the session was deleted, false if not found.
     */
    fun deleteSession(sessionId: String): Boolean {
        val session = sessions.remove(sessionId) ?: return false

        try {
            val pulsarSession = session.agenticSession
            val browser = pulsarSession.boundBrowser

            // logger.info("---------------------DELETE MANAGED SESSION BEGIN----------------------------")
            logger.info(
                "---- Deleting session `{}`, closing pulsar session #{} {}",
                sessionId, pulsarSession.id, pulsarSession.display
            )

            // Close session
            pulsarSession.close()
            // Close the companion browser if it exists
            if (browser != null) {
                // might be already closed by the session, but we ensure it's closed here to release resources
                // TODO: remove this redundant close call after confirming that session.close() always closes the browser
                pulsarSession.context.browserManager.closeBrowser(browser)
            }

            logger.info("---- Deleted session `{}` and released resources", sessionId)
            // logger.info("----------------------DELETE MANAGED SESSION END---------------------------")
        } catch (e: Exception) {
            logger.error("Error closing session {}: {}", sessionId, e.message, e)
        }

        return true
    }

    /**
     * Returns all active sessions.
     *
     * @return A list of all managed sessions.
     */
    fun getAllSessions(): List<ManagedSession> {
        return sessions.values.toList()
    }

    /**
     * Deletes all active sessions and releases their resources.
     *
     * @return The number of sessions deleted.
     */
    fun deleteAllSessions(): Int {
        val count = sessions.size
        sessions.keys.toList().forEach { sessionId ->
            deleteSession(sessionId)
        }
        return count
    }

    /**
     * Closes all active sessions managed by this instance.
     */
    fun shutdown() {
        logger.info("Shutting down SessionManager, closing {} active sessions", sessions.size)
        sessions.keys.toList().forEach { sessionId ->
            deleteSession(sessionId)
        }
    }

    override fun close() {
        shutdown()
    }
}
