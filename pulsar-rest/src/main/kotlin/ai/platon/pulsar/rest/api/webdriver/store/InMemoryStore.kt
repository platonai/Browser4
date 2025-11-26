package ai.platon.pulsar.rest.api.webdriver.store

import ai.platon.pulsar.rest.api.webdriver.dto.*
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory store for WebDriver sessions, elements, event configs, and subscriptions.
 * This is a mock implementation for API skeleton development.
 */
@Component
class InMemoryStore {

    private val logger = LoggerFactory.getLogger(InMemoryStore::class.java)

    private val sessions = ConcurrentHashMap<String, SessionState>()
    private val elements = ConcurrentHashMap<String, ElementState>()
    private val eventConfigs = ConcurrentHashMap<String, MutableList<EventConfig>>()
    private val events = ConcurrentHashMap<String, MutableList<Event>>()
    private val subscriptions = ConcurrentHashMap<String, MutableList<Subscription>>()

    // Session operations

    fun createSession(request: NewSessionRequest): SessionState {
        val sessionId = UUID.randomUUID().toString()
        val capabilities = request.capabilities ?: request.desiredCapabilities ?: emptyMap()
        val session = SessionState(
            sessionId = sessionId,
            capabilities = capabilities
        )
        sessions[sessionId] = session
        eventConfigs[sessionId] = mutableListOf()
        events[sessionId] = mutableListOf()
        subscriptions[sessionId] = mutableListOf()
        logger.debug("Created session: {}", sessionId)
        return session
    }

    fun getSession(sessionId: String): SessionState? {
        return sessions[sessionId]
    }

    fun deleteSession(sessionId: String): Boolean {
        val removed = sessions.remove(sessionId)
        if (removed != null) {
            // Cleanup related data
            elements.entries.removeIf { it.value.sessionId == sessionId }
            eventConfigs.remove(sessionId)
            events.remove(sessionId)
            subscriptions.remove(sessionId)
            logger.debug("Deleted session: {}", sessionId)
            return true
        }
        return false
    }

    fun updateSessionUrl(sessionId: String, url: String): Boolean {
        val session = sessions[sessionId] ?: return false
        session.currentUrl = url
        session.documentUri = url
        session.baseUri = extractBaseUri(url)
        logger.debug("Updated session {} URL to: {}", sessionId, url)
        return true
    }

    fun pauseSession(sessionId: String): Boolean {
        val session = sessions[sessionId] ?: return false
        session.isPaused = true
        logger.debug("Paused session: {}", sessionId)
        return true
    }

    fun stopSession(sessionId: String): Boolean {
        val session = sessions[sessionId] ?: return false
        session.isStopped = true
        logger.debug("Stopped session: {}", sessionId)
        return true
    }

    // Element operations

    fun createElement(sessionId: String, selector: String, strategy: String? = null, value: String? = null): ElementState {
        val elementId = generateElementId(sessionId, selector)
        val element = ElementState(
            elementId = elementId,
            sessionId = sessionId,
            selector = selector,
            locatorStrategy = strategy,
            locatorValue = value
        )
        elements[elementId] = element
        logger.debug("Created element {} for session {} with selector: {}", elementId, sessionId, selector)
        return element
    }

    fun getElement(elementId: String): ElementState? {
        return elements[elementId]
    }

    fun getElementForSession(sessionId: String, elementId: String): ElementState? {
        val element = elements[elementId]
        return if (element?.sessionId == sessionId) element else null
    }

    /**
     * Generate a deterministic element ID based on session and selector.
     * This allows finding the same element with the same selector to return
     * a consistent element ID.
     */
    private fun generateElementId(sessionId: String, selector: String): String {
        val combined = "$sessionId:$selector"
        return combined.hashCode().toUInt().toString(16).padStart(8, '0')
    }

    // Event config operations

    fun addEventConfig(sessionId: String, config: EventConfig): EventConfig {
        val configs = eventConfigs.getOrPut(sessionId) { mutableListOf() }
        configs.add(config)
        logger.debug("Added event config {} for session {}", config.id, sessionId)
        return config
    }

    fun getEventConfigs(sessionId: String): List<EventConfig> {
        return eventConfigs[sessionId]?.toList() ?: emptyList()
    }

    // Event operations

    fun addEvent(sessionId: String, event: Event): Event {
        val eventList = events.getOrPut(sessionId) { mutableListOf() }
        eventList.add(event)
        return event
    }

    fun getEvents(sessionId: String): List<Event> {
        return events[sessionId]?.toList() ?: emptyList()
    }

    // Subscription operations

    fun addSubscription(sessionId: String, subscription: Subscription): Subscription {
        val subs = subscriptions.getOrPut(sessionId) { mutableListOf() }
        subs.add(subscription)
        logger.debug("Added subscription {} for session {}", subscription.subscriptionId, sessionId)
        return subscription
    }

    fun getSubscriptions(sessionId: String): List<Subscription> {
        return subscriptions[sessionId]?.toList() ?: emptyList()
    }

    // Helper functions

    private fun extractBaseUri(url: String): String {
        return try {
            val uri = java.net.URI(url)
            "${uri.scheme}://${uri.host}${if (uri.port > 0) ":${uri.port}" else ""}"
        } catch (e: Exception) {
            url
        }
    }

    // Stats for debugging

    fun getStats(): Map<String, Int> {
        return mapOf(
            "sessions" to sessions.size,
            "elements" to elements.size,
            "eventConfigs" to eventConfigs.values.sumOf { it.size },
            "events" to events.values.sumOf { it.size },
            "subscriptions" to subscriptions.values.sumOf { it.size }
        )
    }
}
