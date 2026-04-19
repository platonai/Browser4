package ai.platon.pulsar.rest.mcp.service

import ai.platon.pulsar.agentic.AgenticSession
import ai.platon.pulsar.agentic.PerceptiveAgent
import ai.platon.pulsar.agentic.context.AgenticContext
import ai.platon.pulsar.core.api.PulsarSettings
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.stereotype.Service
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.minutes

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

    // Coroutine scope for periodic cleanup of idle sessions
    private val cleanupScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    init {
        // Schedule periodic cleanup of idle sessions (every 5 minutes)
        cleanupScope.launch {
            while (isActive) {
                delay(5.minutes)
                cleanupIdleSessions()
            }
        }
    }

    /**
     * Creates a new browser session with the specified capabilities.
     *
     * @param capabilities Optional browser capabilities (browserName, etc.)
     * @return The created managed session.
     */
    fun createSession(capabilities: Map<String, Any?>? = null): ManagedSession {
        val sessionId = UUID.randomUUID().toString()

        val context = agenticContext

        val settings = PulsarSettings.parse(capabilities)
        val agenticSession = context.createSession(settings)

        val session = ManagedSession(
            sessionId = sessionId,
            agenticSession = agenticSession,
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
            session.agenticSession.close()

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
     * Cleans up idle sessions that haven't been accessed for more than 30 minutes.
     */
    private fun cleanupIdleSessions() {
        val idleThreshold = System.currentTimeMillis() - 30.minutes.inWholeMilliseconds
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
    @PreDestroy
    fun shutdown() {
        logger.info("Shutting down SessionManager, closing {} active sessions", sessions.size)
        sessions.keys.toList().forEach { sessionId ->
            deleteSession(sessionId)
        }
        cleanupScope.cancel()
    }
}
