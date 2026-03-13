package ai.platon.pulsar.rest.openapi.service

import ai.platon.pulsar.agentic.AgenticSession
import ai.platon.pulsar.agentic.PerceptiveAgent
import ai.platon.pulsar.agentic.context.AgenticContext
import ai.platon.pulsar.skeleton.context.PulsarContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.stereotype.Service
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Manages WebDriver sessions with real PulsarSession and AgenticSession instances.
 * Handles session lifecycle, cleanup, and browser integration.
 * Only active when PulsarContext is available (production mode).
 */
@Service
@ConditionalOnBean(PulsarContext::class)
class SessionManager(
    val pulsarContext: PulsarContext
) {
    private val logger = LoggerFactory.getLogger(SessionManager::class.java)

    /**
     * Container for session-related objects.
     *
     * The driverMutex ensures that WebDriver operations are executed serially, not in parallel.
     * This is critical because WebDriver methods must not be called concurrently.
     */
    data class ManagedSession(
        val sessionId: String,
        val pulsarSession: AgenticSession,
        val capabilities: Map<String, Any?>?,
        var url: String? = null,
        var status: String = "active", // active, paused, stopped
        val createdAt: Long = System.currentTimeMillis(),
        var lastAccessedAt: Long = System.currentTimeMillis(),
    ) {
        val mutex: Mutex = Mutex()

        val driver get() = pulsarSession.getOrCreateBoundDriver()
        val agent: PerceptiveAgent get() = pulsarSession.companionAgent

        suspend inline fun <R> withLock(block: ManagedSession.() -> R): R {
            return mutex.withLock(null) {
                this.block()
            }
        }
    }

    private val sessions = ConcurrentHashMap<String, ManagedSession>()

    // Cleanup executor for removing stale sessions
    private val cleanupExecutor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "session-cleanup").apply { isDaemon = true }
    }

    init {
        // Schedule periodic cleanup of idle sessions (every 5 minutes)
        cleanupExecutor.scheduleAtFixedRate(
            ::cleanupIdleSessions,
            5, 5, TimeUnit.MINUTES
        )
    }

    /**
     * Creates a new browser session with the specified capabilities.
     *
     * @param capabilities Optional browser capabilities (browserName, etc.)
     * @return The created managed session.
     */
    fun createSession(capabilities: Map<String, Any?>? = null): ManagedSession {
        val sessionId = UUID.randomUUID().toString()

        val context = pulsarContext as AgenticContext
        val agenticSession = context.createSession()

        val session = ManagedSession(
            sessionId = sessionId,
            pulsarSession = agenticSession,
            capabilities = capabilities
        )

        sessions[sessionId] = session
        logger.info("Created session {} with capabilities: {}", sessionId, capabilities)

        return session
    }

    /**
     * Retrieves a session by ID.
     *
     * @param sessionId The session identifier.
     * @return The managed session, or null if not found.
     */
    fun getSession(sessionId: String): ManagedSession? {
        val session = sessions[sessionId]
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

            // Close sessions
            session.pulsarSession.close()

            logger.info("Deleted session {} and released resources", sessionId)
        } catch (e: Exception) {
            logger.error("Error closing session {}: {}", sessionId, e.message, e)
        }

        return true
    }

    /**
     * Updates the URL for a session.
     *
     * @param sessionId The session identifier.
     * @param url The new URL.
     * @return True if successful, false if session not found.
     */
    fun setSessionUrl(sessionId: String, url: String): Boolean {
        val session = sessions[sessionId] ?: return false
        session.url = url
        session.lastAccessedAt = System.currentTimeMillis()
        logger.debug("Session {} navigated to: {}", sessionId, url)
        return true
    }

    /**
     * Updates the status of a session.
     *
     * @param sessionId The session identifier.
     * @param status The new status.
     * @return True if successful, false if session not found.
     */
    fun setSessionStatus(sessionId: String, status: String): Boolean {
        val session = sessions[sessionId] ?: return false
        session.status = status
        session.lastAccessedAt = System.currentTimeMillis()
        logger.debug("Session {} status changed to: {}", sessionId, status)
        return true
    }

    /**
     * Checks if a session exists.
     *
     * @param sessionId The session identifier.
     * @return True if the session exists.
     */
    fun sessionExists(sessionId: String): Boolean {
        return sessions.containsKey(sessionId)
    }

    /**
     * Gets the count of active sessions.
     *
     * @return Number of active sessions.
     */
    fun getActiveSessionCount(): Int {
        return sessions.size
    }

    /**
     * Returns all active managed sessions.
     *
     * @return List of all active managed sessions.
     */
    fun getAllSessions(): List<ManagedSession> {
        return sessions.values.toList()
    }

    /**
     * Deletes all active sessions and releases their resources.
     *
     * @return Number of sessions deleted.
     */
    fun deleteAllSessions(): Int {
        val count = sessions.size
        sessions.keys.toList().forEach { sessionId ->
            deleteSession(sessionId)
        }
        return count
    }

    /**
     * Cleans up idle sessions that haven't been accessed for more than 30 minutes.
     */
    private fun cleanupIdleSessions() {
        val idleThreshold = System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(30)
        val idleSessions = sessions.entries.filter { (_, session) ->
            session.lastAccessedAt < idleThreshold
        }

        if (idleSessions.isNotEmpty()) {
            logger.info("Cleaning up {} idle sessions", idleSessions.size)
            idleSessions.forEach { (sessionId, _) ->
                deleteSession(sessionId)
            }
        }
    }

    /**
     * Cleanup method called on shutdown.
     */
    fun shutdown() {
        logger.info("Shutting down SessionManager, closing {} active sessions", sessions.size)
        sessions.keys.toList().forEach { sessionId ->
            deleteSession(sessionId)
        }
        cleanupExecutor.shutdown()
        try {
            if (!cleanupExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                cleanupExecutor.shutdownNow()
            }
        } catch (e: InterruptedException) {
            cleanupExecutor.shutdownNow()
            Thread.currentThread().interrupt()
        }
    }
}
