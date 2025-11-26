package ai.platon.pulsar.rest.api.service.webdriver

import ai.platon.pulsar.rest.api.dto.*
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory storage for WebDriver sessions, elements, event configs and subscriptions.
 * This is a mock implementation for the skeleton API.
 */
@Service
class WebDriverSessionService {
    
    private val logger = LoggerFactory.getLogger(WebDriverSessionService::class.java)
    
    // Session storage: sessionId -> SessionData
    private val sessions = ConcurrentHashMap<String, SessionData>()
    
    // Element storage: sessionId -> (elementId -> selector)
    private val elements = ConcurrentHashMap<String, MutableMap<String, String>>()
    
    // Event configs: sessionId -> list of EventConfig
    private val eventConfigs = ConcurrentHashMap<String, MutableList<EventConfig>>()
    
    // Captured events: sessionId -> list of Event
    private val events = ConcurrentHashMap<String, MutableList<Event>>()
    
    // Event subscriptions: sessionId -> subscriptionId -> EventSubscriptionValue
    private val subscriptions = ConcurrentHashMap<String, MutableMap<String, EventSubscriptionValue>>()
    
    /**
     * Create a new session
     */
    fun createSession(request: NewSessionRequest): SessionData {
        val sessionId = UUID.randomUUID().toString()
        val capabilities = request.capabilities ?: Capabilities()
        
        val session = SessionData(
            sessionId = sessionId,
            capabilities = capabilities,
            status = "active"
        )
        
        sessions[sessionId] = session
        elements[sessionId] = ConcurrentHashMap()
        eventConfigs[sessionId] = mutableListOf()
        events[sessionId] = mutableListOf()
        subscriptions[sessionId] = ConcurrentHashMap()
        
        logger.debug("Created session: {}", sessionId)
        return session
    }
    
    /**
     * Get session by ID
     */
    fun getSession(sessionId: String): SessionData? {
        return sessions[sessionId]
    }
    
    /**
     * Delete a session
     */
    fun deleteSession(sessionId: String): Boolean {
        val removed = sessions.remove(sessionId) != null
        if (removed) {
            elements.remove(sessionId)
            eventConfigs.remove(sessionId)
            events.remove(sessionId)
            subscriptions.remove(sessionId)
            logger.debug("Deleted session: {}", sessionId)
        }
        return removed
    }
    
    /**
     * Check if session exists
     */
    fun sessionExists(sessionId: String): Boolean {
        return sessions.containsKey(sessionId)
    }
    
    /**
     * Update session URL
     */
    fun updateSessionUrl(sessionId: String, url: String): SessionData? {
        val session = sessions[sessionId] ?: return null
        val updated = session.copy(currentUrl = url)
        sessions[sessionId] = updated
        logger.debug("Session {} navigated to: {}", sessionId, url)
        return updated
    }
    
    /**
     * Update session status
     */
    fun updateSessionStatus(sessionId: String, status: String): SessionData? {
        val session = sessions[sessionId] ?: return null
        val updated = session.copy(status = status)
        sessions[sessionId] = updated
        logger.debug("Session {} status changed to: {}", sessionId, status)
        return updated
    }
    
    /**
     * Get current URL for session
     */
    fun getCurrentUrl(sessionId: String): String? {
        return sessions[sessionId]?.currentUrl
    }
    
    // ========== Element operations ==========
    
    /**
     * Store an element and return its ID
     */
    fun storeElement(sessionId: String, selector: String): String? {
        val sessionElements = elements[sessionId] ?: return null
        val elementId = generateElementId(selector)
        sessionElements[elementId] = selector
        return elementId
    }
    
    /**
     * Get selector for an element
     */
    fun getElementSelector(sessionId: String, elementId: String): String? {
        return elements[sessionId]?.get(elementId)
    }
    
    /**
     * Check if element exists
     */
    fun elementExists(sessionId: String, elementId: String): Boolean {
        return elements[sessionId]?.containsKey(elementId) == true
    }
    
    // ========== Event config operations ==========
    
    /**
     * Add event configuration
     */
    fun addEventConfig(sessionId: String, config: EventConfig): EventConfig? {
        val configs = eventConfigs[sessionId] ?: return null
        val configWithId = if (config.id.isNullOrEmpty()) {
            config.copy(id = UUID.randomUUID().toString())
        } else {
            config
        }
        configs.add(configWithId)
        logger.debug("Session {} added event config: {}", sessionId, configWithId.eventType)
        return configWithId
    }
    
    /**
     * Get all event configurations for a session
     */
    fun getEventConfigs(sessionId: String): List<EventConfig>? {
        return eventConfigs[sessionId]?.toList()
    }
    
    // ========== Event operations ==========
    
    /**
     * Add captured event
     */
    fun addEvent(sessionId: String, event: Event): Event? {
        val sessionEvents = events[sessionId] ?: return null
        sessionEvents.add(event)
        return event
    }
    
    /**
     * Get all captured events for a session
     */
    fun getEvents(sessionId: String): List<Event>? {
        return events[sessionId]?.toList()
    }
    
    // ========== Subscription operations ==========
    
    /**
     * Add event subscription
     */
    fun addSubscription(sessionId: String, subscription: EventSubscription): EventSubscriptionValue? {
        val sessionSubs = subscriptions[sessionId] ?: return null
        val subscriptionId = UUID.randomUUID().toString()
        val value = EventSubscriptionValue(subscriptionId, subscription.eventTypes)
        sessionSubs[subscriptionId] = value
        logger.debug("Session {} subscribed to events: {}", sessionId, subscription.eventTypes)
        return value
    }
    
    /**
     * Get all subscriptions for a session
     */
    fun getSubscriptions(sessionId: String): List<EventSubscriptionValue>? {
        return subscriptions[sessionId]?.values?.toList()
    }
}
