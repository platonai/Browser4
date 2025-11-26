package ai.platon.pulsar.rest.api.store

import ai.platon.pulsar.rest.api.dto.Event
import ai.platon.pulsar.rest.api.dto.EventConfig
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory store for WebDriver sessions, elements, event configurations, and subscriptions.
 * This is a mock implementation for testing and development purposes.
 */
@Component
class InMemoryStore {

    /**
     * Session data holder.
     */
    data class SessionData(
        val sessionId: String,
        val capabilities: Map<String, Any?>,
        var url: String? = null,
        var status: String = "active",
        val elements: ConcurrentHashMap<String, ElementData> = ConcurrentHashMap(),
        val eventConfigs: ConcurrentHashMap<String, EventConfig> = ConcurrentHashMap(),
        val events: MutableList<Event> = mutableListOf(),
        val subscriptions: ConcurrentHashMap<String, SubscriptionData> = ConcurrentHashMap()
    )

    /**
     * Element data holder.
     */
    data class ElementData(
        val elementId: String,
        val selector: String,
        var text: String = "",
        val attributes: MutableMap<String, String> = mutableMapOf()
    )

    /**
     * Subscription data holder.
     */
    data class SubscriptionData(
        val subscriptionId: String,
        val eventTypes: List<String>,
        val callback: String?
    )

    private val sessions = ConcurrentHashMap<String, SessionData>()

    /**
     * Creates a new session.
     *
     * @param capabilities Session capabilities
     * @return The created session data
     */
    fun createSession(capabilities: Map<String, Any?>): SessionData {
        val sessionId = UUID.randomUUID().toString()
        val session = SessionData(sessionId, capabilities)
        sessions[sessionId] = session
        return session
    }

    /**
     * Gets a session by ID.
     *
     * @param sessionId The session ID
     * @return The session data or null if not found
     */
    fun getSession(sessionId: String): SessionData? = sessions[sessionId]

    /**
     * Deletes a session.
     *
     * @param sessionId The session ID
     * @return true if the session was deleted, false if not found
     */
    fun deleteSession(sessionId: String): Boolean = sessions.remove(sessionId) != null

    /**
     * Updates the session URL.
     *
     * @param sessionId The session ID
     * @param url The new URL
     * @return true if updated, false if session not found
     */
    fun setSessionUrl(sessionId: String, url: String): Boolean {
        val session = sessions[sessionId] ?: return false
        session.url = url
        return true
    }

    /**
     * Updates the session status.
     *
     * @param sessionId The session ID
     * @param status The new status
     * @return true if updated, false if session not found
     */
    fun setSessionStatus(sessionId: String, status: String): Boolean {
        val session = sessions[sessionId] ?: return false
        session.status = status
        return true
    }

    /**
     * Generates an element ID from a selector string using SHA-256 hash.
     * Uses the full hash to minimize collision probability.
     *
     * @param selector The CSS selector
     * @return A deterministic element ID (64 hex characters)
     */
    fun generateElementId(selector: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(selector.toByteArray())
        return hash.joinToString("") { "%02x".format(it) }
    }

    /**
     * Gets or creates an element for a selector.
     *
     * @param sessionId The session ID
     * @param selector The CSS selector
     * @return The element data or null if session not found
     */
    fun getOrCreateElement(sessionId: String, selector: String): ElementData? {
        val session = sessions[sessionId] ?: return null
        val elementId = generateElementId(selector)
        return session.elements.computeIfAbsent(elementId) {
            ElementData(elementId, selector)
        }
    }

    /**
     * Gets an element by ID.
     *
     * @param sessionId The session ID
     * @param elementId The element ID
     * @return The element data or null if not found
     */
    fun getElement(sessionId: String, elementId: String): ElementData? {
        val session = sessions[sessionId] ?: return null
        return session.elements[elementId]
    }

    /**
     * Adds an event configuration.
     *
     * @param sessionId The session ID
     * @param config The event configuration
     * @return The event config with ID or null if session not found
     */
    fun addEventConfig(sessionId: String, config: EventConfig): EventConfig? {
        val session = sessions[sessionId] ?: return null
        val configWithId = if (config.id == null) {
            config.copy(id = UUID.randomUUID().toString())
        } else {
            config
        }
        session.eventConfigs[configWithId.id!!] = configWithId
        return configWithId
    }

    /**
     * Gets all event configurations for a session.
     *
     * @param sessionId The session ID
     * @return List of event configs or null if session not found
     */
    fun getEventConfigs(sessionId: String): List<EventConfig>? {
        val session = sessions[sessionId] ?: return null
        return session.eventConfigs.values.toList()
    }

    /**
     * Adds an event.
     *
     * @param sessionId The session ID
     * @param event The event to add
     * @return true if added, false if session not found
     */
    fun addEvent(sessionId: String, event: Event): Boolean {
        val session = sessions[sessionId] ?: return false
        synchronized(session.events) {
            session.events.add(event)
        }
        return true
    }

    /**
     * Gets events since a timestamp.
     *
     * @param sessionId The session ID
     * @param since Timestamp to get events since (epoch millis), or null for all
     * @return List of events or null if session not found
     */
    fun getEvents(sessionId: String, since: Long?): List<Event>? {
        val session = sessions[sessionId] ?: return null
        synchronized(session.events) {
            return if (since != null) {
                session.events.filter { it.timestamp > since }
            } else {
                session.events.toList()
            }
        }
    }

    /**
     * Creates an event subscription.
     *
     * @param sessionId The session ID
     * @param eventTypes The event types to subscribe to
     * @param callback Optional callback URL
     * @return The subscription ID or null if session not found
     */
    fun createSubscription(sessionId: String, eventTypes: List<String>, callback: String?): String? {
        val session = sessions[sessionId] ?: return null
        val subscriptionId = UUID.randomUUID().toString()
        session.subscriptions[subscriptionId] = SubscriptionData(subscriptionId, eventTypes, callback)
        return subscriptionId
    }

    /**
     * Checks if a session exists.
     *
     * @param sessionId The session ID
     * @return true if the session exists
     */
    fun sessionExists(sessionId: String): Boolean = sessions.containsKey(sessionId)
}
