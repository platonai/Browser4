package ai.platon.pulsar.rest.api.webdriver.controller

import ai.platon.pulsar.rest.api.webdriver.dto.*
import ai.platon.pulsar.rest.api.webdriver.store.InMemoryStore
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.util.UUID

/**
 * Controller for event subscription and management.
 */
@RestController
@CrossOrigin
@RequestMapping("/session/{sessionId}")
class EventsController(
    private val store: InMemoryStore
) {
    private val logger = LoggerFactory.getLogger(EventsController::class.java)

    /**
     * Create event configuration.
     */
    @PostMapping("/event-configs")
    fun createEventConfig(
        @PathVariable sessionId: String,
        @RequestBody config: EventConfig,
        @RequestHeader(value = "X-Request-Id", required = false) requestId: String?
    ): ResponseEntity<*> {
        val reqId = requestId ?: UUID.randomUUID().toString()
        logger.debug("Creating event config for type '{}' in session: {}, X-Request-Id: {}", 
            config.eventType, sessionId, reqId)

        store.getSession(sessionId)
            ?: return notFoundResponse(sessionId, reqId)

        val savedConfig = store.addEventConfig(sessionId, config)

        return ResponseEntity.ok()
            .header("X-Request-Id", reqId)
            .body(WebDriverResponse(savedConfig))
    }

    /**
     * Get event configurations.
     */
    @GetMapping("/event-configs")
    fun getEventConfigs(
        @PathVariable sessionId: String,
        @RequestHeader(value = "X-Request-Id", required = false) requestId: String?
    ): ResponseEntity<*> {
        val reqId = requestId ?: UUID.randomUUID().toString()
        logger.debug("Getting event configs for session: {}, X-Request-Id: {}", sessionId, reqId)

        store.getSession(sessionId)
            ?: return notFoundResponse(sessionId, reqId)

        val configs = store.getEventConfigs(sessionId)

        return ResponseEntity.ok()
            .header("X-Request-Id", reqId)
            .body(WebDriverResponse(configs))
    }

    /**
     * Get events.
     */
    @GetMapping("/events")
    fun getEvents(
        @PathVariable sessionId: String,
        @RequestHeader(value = "X-Request-Id", required = false) requestId: String?
    ): ResponseEntity<*> {
        val reqId = requestId ?: UUID.randomUUID().toString()
        logger.debug("Getting events for session: {}, X-Request-Id: {}", sessionId, reqId)

        store.getSession(sessionId)
            ?: return notFoundResponse(sessionId, reqId)

        val events = store.getEvents(sessionId)

        return ResponseEntity.ok()
            .header("X-Request-Id", reqId)
            .body(WebDriverResponse(events))
    }

    /**
     * Subscribe to events.
     */
    @PostMapping("/events/subscribe")
    fun subscribeEvents(
        @PathVariable sessionId: String,
        @RequestBody request: EventSubscribeRequest,
        @RequestHeader(value = "X-Request-Id", required = false) requestId: String?
    ): ResponseEntity<*> {
        val reqId = requestId ?: UUID.randomUUID().toString()
        logger.debug("Subscribing to events {} in session: {}, X-Request-Id: {}", 
            request.eventTypes, sessionId, reqId)

        store.getSession(sessionId)
            ?: return notFoundResponse(sessionId, reqId)

        val subscription = Subscription(eventTypes = request.eventTypes)
        store.addSubscription(sessionId, subscription)

        return ResponseEntity.ok()
            .header("X-Request-Id", reqId)
            .body(WebDriverResponse(subscription))
    }

    private fun notFoundResponse(sessionId: String, reqId: String): ResponseEntity<WebDriverResponse<ErrorValue>> {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .header("X-Request-Id", reqId)
            .body(WebDriverResponse(ErrorValue("no such session", "Session $sessionId not found")))
    }
}
