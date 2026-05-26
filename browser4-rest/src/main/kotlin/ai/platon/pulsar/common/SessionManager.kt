package ai.platon.pulsar.common

import ai.platon.pulsar.agentic.AgenticSession
import ai.platon.pulsar.agentic.PerceptiveAgent
import ai.platon.pulsar.agentic.context.AgenticContext
import ai.platon.pulsar.common.browser.BrowserProfileMode
import ai.platon.pulsar.core.api.PulsarSettings
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages AgenticSession-backed browser sessions and their lifecycle.
 *
 * This component is framework-agnostic and can be wired manually or exposed
 * through an external dependency injection container.
 */
class SessionManager(
    val agenticContext: AgenticContext
) : Closeable {
    companion object {
        const val DEFAULT_SESSION_ID = "default"
        const val SWARM_SESSION_ID = "swarm"

        const val SESSION_ID_CAPABILITY = "sessionId"
        const val PROFILE_MODE_CAPABILITY = "profileMode"
    }

    private val logger = LoggerFactory.getLogger(SessionManager::class.java)

    /**
     * Container for session-related objects.
     *
     * The driverMutex ensures that WebDriver operations are executed serially, not in parallel.
     * This is critical because WebDriver methods must not be called concurrently.
     */
    data class ManagedSession(
        val sessionId: String,
        val agenticSession: AgenticSession,
        val capabilities: Map<String, Any?>?,
        var url: String? = null,
        var status: String = "active", // active, paused, stopped
        val createdAt: Long = System.currentTimeMillis(),
        var lastAccessedAt: Long = System.currentTimeMillis(),
    ) {
        val mutex: Mutex = Mutex()

        val driver get() = agenticSession.getOrCreateBoundDriver()
        val agent: PerceptiveAgent get() = agenticSession.companionAgent

        suspend inline fun <R> withLock(block: ManagedSession.() -> R): R {
            return mutex.withLock(null) {
                this.block()
            }
        }
    }

    private val sessions = ConcurrentHashMap<String, ManagedSession>()

    /**
     * The default session is a special session that can be accessed without specifying a session ID.
     * It is created on demand and shared across all requests that do not specify a session ID.
     * The profile mode is pinned to DEFAULT.
     * */
    fun defaultSession(capabilities: Map<String, Any?>? = null): ManagedSession {
        return getOrCreateSessionById(DEFAULT_SESSION_ID, capabilities)
    }

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
    fun swarmSession(capabilities: Map<String, Any?>? = null): ManagedSession {
        return getOrCreateSessionById(SWARM_SESSION_ID, capabilities)
    }

    /**
     *
     * */
    fun getOrCreateSessionById(sessionId: String, capabilities: Map<String, Any?>? = null): ManagedSession {
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
    fun getOrCreateSession(capabilities: Map<String, Any?>? = null): ManagedSession {
        val normalizedCapabilities = normalizeCapabilities(capabilities = capabilities)
        val sessionId = normalizedCapabilities.getValue(SESSION_ID_CAPABILITY).toString()
        val session = sessions.computeIfAbsent(sessionId) {
            createManagedSession(sessionId, normalizedCapabilities)
        }

        val activeSession = resolveHealthySession(sessionId, normalizedCapabilities, session)
        activeSession.lastAccessedAt = System.currentTimeMillis()
        return activeSession
    }

    fun checkHealthy(session: ManagedSession): Boolean {
        val s = session.agenticSession
        val browser = s.boundBrowser
        val driver = s.boundDriver

        if (driver != null && driver.browser != browser) {
            logger.warn(
                "Inconsistent driver/browser. Driver {} state: {} browser {} state: {}",
                driver.id, driver.readableState, browser?.id, browser?.readableState
            )
        }

        var healthy = s.isActive
        if (!healthy) {
            logger.warn("AgenticSession {} is not healthy", s.id)
        }

        if (healthy) {
            healthy = browser?.healthy() ?: true
            if (!healthy && browser != null) {
                logger.warn("Bound browser {} is unhealthy, state: {}", browser.id, browser.readableState)
            }

            if (healthy) {
                healthy = s.boundDriver?.healthy() ?: true
                if (!healthy && driver != null) {
                    logger.warn("Bound driver {} is unhealthy, state: {}", driver.id, driver.readableState)
                }
            }
        }

        if (!healthy) {
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
        capabilities: Map<String, Any?>,
        session: ManagedSession,
    ): ManagedSession {
        if (checkHealthy(session)) {
            return markSessionActive(session)
        }

        val recreatedSession = recreateUnhealthySession(sessionId, capabilities, session)
        return if (checkHealthy(recreatedSession)) {
            markSessionActive(recreatedSession)
        } else {
            markSessionInactive(recreatedSession)
            logger.warn("Replacement session {} is still unhealthy after recreation", sessionId)
            recreatedSession
        }
    }

    private fun createManagedSession(sessionId: String, capabilities: Map<String, Any?>): ManagedSession {
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

    private fun recreateUnhealthySession(
        sessionId: String,
        capabilities: Map<String, Any?>,
        staleSession: ManagedSession,
    ): ManagedSession {
        return sessions.compute(sessionId) { _, existingSession ->
            when {
                existingSession == null -> createManagedSession(sessionId, capabilities)
                checkHealthy(existingSession) -> {
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
        capabilities: Map<String, Any?>?,
    ): Map<String, Any?> {
        val normalizedCapabilities = LinkedHashMap(capabilities.orEmpty())
        val hasExplicitSessionId = !explicitSessionId.isNullOrBlank()
        val requestedSessionId = normalizedCapabilities[SESSION_ID_CAPABILITY]?.toString()?.trim()
        val sessionId = when {
            explicitSessionId.equals(DEFAULT_SESSION_ID, ignoreCase = true) -> DEFAULT_SESSION_ID
            explicitSessionId.equals(SWARM_SESSION_ID, ignoreCase = true) -> SWARM_SESSION_ID
            hasExplicitSessionId -> requireNotNull(explicitSessionId).trim()
            requestedSessionId.isNullOrBlank() || requestedSessionId.equals(DEFAULT_SESSION_ID, ignoreCase = true) -> DEFAULT_SESSION_ID
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
            hasExplicitSessionId && sessionId.equals(DEFAULT_SESSION_ID, ignoreCase = true) -> BrowserProfileMode.DEFAULT
            sessionId.equals(DEFAULT_SESSION_ID, ignoreCase = true) && requestedProfileMode == BrowserProfileMode.SEQUENTIAL -> BrowserProfileMode.SEQUENTIAL
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

            logger.info("---------------------MANAGED SESSION BEGIN----------------------------")
            logger.info(
                "Deleting session {}, closing pulsar session #{} {}",
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

            logger.info("Deleted session {} and released resources", sessionId)
            logger.info("----------------------MANAGED SESSION END---------------------------")
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

