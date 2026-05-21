package ai.platon.pulsar.rest.mcp.service

import ai.platon.pulsar.agentic.AgenticSession
import ai.platon.pulsar.agentic.PerceptiveAgent
import ai.platon.pulsar.agentic.context.AgenticContext
import ai.platon.pulsar.core.api.PulsarSettings
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages WebDriver sessions with real AgenticContext instances.
 * Handles session lifecycle, cleanup, and browser integration.
 * Only active when AgenticContext is available (production mode).
 */
@Service
@ConditionalOnBean(AgenticContext::class)
class SessionManager(
    val agenticContext: AgenticContext
) {
    companion object {
        private const val DEFAULT_SESSION_ID = "default"
        private const val SESSION_ID_CAPABILITY = "sessionId"
        private const val PROFILE_MODE_CAPABILITY = "profileMode"
        private const val DEFAULT_PROFILE_MODE = "DEFAULT"
        private const val TEMPORARY_PROFILE_MODE = "TEMPORARY"
        private const val SEQUENTIAL_PROFILE_MODE = "SEQUENTIAL"
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
     * Creates a new browser session with the specified capabilities.
     *
     * @param capabilities Optional browser capabilities (browserName, etc.)
     * @return The created managed session.
     */
    fun getOrCreateSession(capabilities: Map<String, Any?>? = null): ManagedSession {
        val normalizedCapabilities = normalizeCapabilities(capabilities)
        val sessionId = normalizedCapabilities.getValue(SESSION_ID_CAPABILITY).toString()
        val session = sessions.computeIfAbsent(sessionId) {
            createManagedSession(sessionId, normalizedCapabilities)
        }

        val activeSession = resolveHealthySession(sessionId, normalizedCapabilities, session)

        activeSession.lastAccessedAt = System.currentTimeMillis()
        return activeSession
    }

    fun checkHealthy(session: ManagedSession): Boolean {
        var healthy = session.agenticSession.isActive

        if (healthy) {
            healthy = session.agenticSession.boundBrowser?.healthy() ?: true
            if (healthy) {
                healthy = session.agenticSession.boundDriver?.healthy() ?: true
            }
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

    private fun normalizeCapabilities(capabilities: Map<String, Any?>?): Map<String, Any?> {
        val normalizedCapabilities = LinkedHashMap(capabilities.orEmpty())
        val requestedSessionId = normalizedCapabilities[SESSION_ID_CAPABILITY]?.toString()?.trim()
        val sessionId = if (requestedSessionId.isNullOrBlank() || requestedSessionId.equals(DEFAULT_SESSION_ID, ignoreCase = true)) {
            DEFAULT_SESSION_ID
        } else {
            requestedSessionId
        }

        normalizedCapabilities[SESSION_ID_CAPABILITY] = sessionId
        normalizedCapabilities[PROFILE_MODE_CAPABILITY] = when {
            normalizedCapabilities[PROFILE_MODE_CAPABILITY]?.toString()?.equals(SEQUENTIAL_PROFILE_MODE, ignoreCase = true) == true -> SEQUENTIAL_PROFILE_MODE
            sessionId.equals(DEFAULT_SESSION_ID, ignoreCase = true) -> DEFAULT_PROFILE_MODE
            else -> SEQUENTIAL_PROFILE_MODE
        }

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
            // Close the agent to release browser resources
            session.agent.close()

            val pulsarSession = session.agenticSession
            val browser = pulsarSession.boundBrowser

            // Close session
            pulsarSession.close()
            // Close the companion browser if it exists
            if (browser != null) {
                pulsarSession.context.browserManager.closeBrowser(browser)
            }

            logger.info("Deleted session {} and released resources", sessionId)
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
     * Cleanup method called on shutdown.
     */
    @PreDestroy
    fun shutdown() {
        logger.info("Shutting down SessionManager, closing {} active sessions", sessions.size)
        sessions.keys.toList().forEach { sessionId ->
            deleteSession(sessionId)
        }
    }
}
