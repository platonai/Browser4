package ai.platon.pulsar.rest.session

import ai.platon.pulsar.agentic.AgenticSession
import ai.platon.pulsar.agentic.PerceptiveAgent
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Container for session-related objects.
 *
 * The driverMutex ensures that WebDriver operations are executed serially, not in parallel.
 * This is critical because WebDriver methods must not be called concurrently.
 *
 * @property sessionId The REST-level session id, which is distinct with [AgenticSession.uuid]
 * @property agenticSession The managed [AgenticSession]
 * @property capabilities The capabilities used to create the [AgenticSession]
 */
data class ManagedSession(
    val sessionId: String,
    val agenticSession: AgenticSession,
    val capabilities: Map<String, String?>?,
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
