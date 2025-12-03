package ai.platon.pulsar.rest.api.webdriver.store

import ai.platon.pulsar.rest.api.webdriver.dto.Event
import ai.platon.pulsar.rest.api.webdriver.dto.EventConfig
import ai.platon.pulsar.rest.api.webdriver.dto.EventConfigWithId
import ai.platon.pulsar.rest.api.webdriver.dto.SubscriptionData
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory storage for WebDriver sessions, elements, event configurations, and subscriptions.
 * This is a mock implementation for demonstration purposes - no real browser integration.
 */
@Component
class InMemoryStore {

    private val logger = LoggerFactory.getLogger(InMemoryStore::class.java)

    /**
     * Session data class holding all session-related state.
     */
    data class SessionData(
        val sessionId: String,
        val capabilities: Map<String, Any?>? = null,
        var url: String? = null,
        var status: String = "active", // active, paused, stopped
        val elements: MutableMap<String, ElementData> = ConcurrentHashMap(),
        val eventConfigs: MutableMap<String, EventConfigWithId> = ConcurrentHashMap(),
        val events: MutableList<Event> = Collections.synchronizedList(mutableListOf()),
        val subscriptions: MutableMap<String, SubscriptionData> = ConcurrentHashMap()
    )

    /**
     * Element data class holding element metadata.
     */
    data class ElementData(
        val elementId: String,
        val selector: String,
        val strategy: String = "css",
        var text: String = "Mock element text",
        var outerHtml: String = "<div>Mock element</div>",
        val attributes: MutableMap<String, String> = ConcurrentHashMap()
    )

    private val sessions: MutableMap<String, SessionData> = ConcurrentHashMap()

    /**
     * Creates a new session with the given capabilities.
     *
     * @param capabilities Optional capabilities map.
     * @return The created session data.
     */
    fun createSession(capabilities: Map<String, Any?>? = null): SessionData {
        val sessionId = UUID.randomUUID().toString()
        val session = SessionData(sessionId = sessionId, capabilities = capabilities)
        sessions[sessionId] = session
        logger.debug("Created session: {}", sessionId)
        return session
    }

    /**
     * Retrieves a session by ID.
     *
     * @param sessionId The session identifier.
     * @return The session data, or null if not found.
     */
    fun getSession(sessionId: String): SessionData? {
        return sessions[sessionId]
    }

    /**
     * Deletes a session by ID.
     *
     * @param sessionId The session identifier.
     * @return True if the session was deleted, false if not found.
     */
    fun deleteSession(sessionId: String): Boolean {
        val removed = sessions.remove(sessionId)
        if (removed != null) {
            logger.debug("Deleted session: {}", sessionId)
        }
        return removed != null
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
        logger.debug("Session {} status changed to: {}", sessionId, status)
        return true
    }

    /**
     * Generates an element ID by hashing the selector string.
     *
     * @param selector The selector string.
     * @return A unique element identifier based on the selector hash.
     */
    fun generateElementId(selector: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val hashBytes = md.digest(selector.toByteArray())
        return hashBytes.take(16).joinToString("") { "%02x".format(it) }
    }

    /**
     * Creates or retrieves an element for a session.
     *
     * @param sessionId The session identifier.
     * @param selector The CSS selector or XPath.
     * @param strategy The selector strategy (css or xpath).
     * @return The element data, or null if session not found.
     */
    fun getOrCreateElement(sessionId: String, selector: String, strategy: String = "css"): ElementData? {
        val session = sessions[sessionId] ?: return null
        val elementId = generateElementId(selector)

        return session.elements.getOrPut(elementId) {
            val element = ElementData(
                elementId = elementId,
                selector = selector,
                strategy = strategy,
                outerHtml = "<div data-selector=\"$selector\">Mock element for $selector</div>"
            )
            logger.debug("Created element {} for selector: {}", elementId, selector)
            element
        }
    }

    /**
     * Retrieves an element by ID for a session.
     *
     * @param sessionId The session identifier.
     * @param elementId The element identifier.
     * @return The element data, or null if not found.
     */
    fun getElement(sessionId: String, elementId: String): ElementData? {
        return sessions[sessionId]?.elements?.get(elementId)
    }

    /**
     * Adds an event configuration to a session.
     *
     * @param sessionId The session identifier.
     * @param eventConfig The event configuration.
     * @return The created config with ID, or null if session not found.
     */
    fun addEventConfig(sessionId: String, eventConfig: EventConfig): EventConfigWithId? {
        val session = sessions[sessionId] ?: return null
        val configId = UUID.randomUUID().toString()
        val configWithId = EventConfigWithId(
            configId = configId,
            eventType = eventConfig.eventType,
            enabled = eventConfig.enabled
        )
        session.eventConfigs[configId] = configWithId
        logger.debug("Added event config {} for session: {}", configId, sessionId)
        return configWithId
    }

    /**
     * Retrieves all event configurations for a session.
     *
     * @param sessionId The session identifier.
     * @return List of event configurations, or null if session not found.
     */
    fun getEventConfigs(sessionId: String): List<EventConfigWithId>? {
        return sessions[sessionId]?.eventConfigs?.values?.toList()
    }

    /**
     * Adds an event to a session.
     *
     * @param sessionId The session identifier.
     * @param event The event to add.
     * @return True if successful, false if session not found.
     */
    fun addEvent(sessionId: String, event: Event): Boolean {
        val session = sessions[sessionId] ?: return false
        // events is already a synchronized list, so direct add is thread-safe
        session.events.add(event)
        return true
    }

    /**
     * Retrieves all events for a session.
     *
     * @param sessionId The session identifier.
     * @return List of events, or null if session not found.
     */
    fun getEvents(sessionId: String): List<Event>? {
        val events = sessions[sessionId]?.events ?: return null
        // Synchronized iteration for thread safety with synchronizedList
        synchronized(events) {
            return events.toList()
        }
    }

    /**
     * Creates a subscription for a session.
     *
     * @param sessionId The session identifier.
     * @param eventTypes List of event types to subscribe to.
     * @return The subscription data, or null if session not found.
     */
    fun createSubscription(sessionId: String, eventTypes: List<String>): SubscriptionData? {
        val session = sessions[sessionId] ?: return null
        val subscriptionId = UUID.randomUUID().toString()
        val subscription = SubscriptionData(
            subscriptionId = subscriptionId,
            eventTypes = eventTypes
        )
        session.subscriptions[subscriptionId] = subscription
        logger.debug("Created subscription {} for session: {}", subscriptionId, sessionId)
        return subscription
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
}
