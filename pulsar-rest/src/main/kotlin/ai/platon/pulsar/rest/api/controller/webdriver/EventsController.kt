package ai.platon.pulsar.rest.api.controller.webdriver

import ai.platon.pulsar.rest.api.dto.*
import ai.platon.pulsar.rest.api.service.webdriver.WebDriverSessionService
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

/**
 * Event configuration and subscription controller.
 */
@RestController
@CrossOrigin
@RequestMapping(
    "/session/{sessionId}",
    produces = [MediaType.APPLICATION_JSON_VALUE]
)
class EventsController(
    private val sessionService: WebDriverSessionService
) {
    private val logger = LoggerFactory.getLogger(EventsController::class.java)

    /**
     * Create event configuration.
     * POST /session/{sessionId}/event-configs
     */
    @PostMapping("/event-configs", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun createEventConfig(
        @PathVariable sessionId: String,
        @RequestBody config: EventConfig
    ): ResponseEntity<Any> {
        logger.debug("Session {} creating event config: {}", sessionId, config.eventType)
        
        if (!sessionService.sessionExists(sessionId)) {
            return notFound("invalid session id", "Session not found: $sessionId")
        }
        
        require(config.eventType.isNotBlank()) { "Event type must not be blank" }
        
        val savedConfig = sessionService.addEventConfig(sessionId, config)
            ?: return notFound("invalid session id", "Session not found: $sessionId")
        
        return ResponseEntity.ok(EventConfigResponse(value = savedConfig))
    }

    /**
     * Get all event configurations.
     * GET /session/{sessionId}/event-configs
     */
    @GetMapping("/event-configs")
    fun getEventConfigs(@PathVariable sessionId: String): ResponseEntity<Any> {
        logger.debug("Session {} getting event configs", sessionId)
        
        val configs = sessionService.getEventConfigs(sessionId)
            ?: return notFound("invalid session id", "Session not found: $sessionId")
        
        return ResponseEntity.ok(EventConfigsResponse(value = configs))
    }

    /**
     * Get captured events.
     * GET /session/{sessionId}/events
     */
    @GetMapping("/events")
    fun getEvents(@PathVariable sessionId: String): ResponseEntity<Any> {
        logger.debug("Session {} getting events", sessionId)
        
        val events = sessionService.getEvents(sessionId)
            ?: return notFound("invalid session id", "Session not found: $sessionId")
        
        return ResponseEntity.ok(EventsResponse(value = events))
    }

    /**
     * Subscribe to events.
     * POST /session/{sessionId}/events/subscribe
     */
    @PostMapping("/events/subscribe", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun subscribeEvents(
        @PathVariable sessionId: String,
        @RequestBody subscription: EventSubscription
    ): ResponseEntity<Any> {
        logger.debug("Session {} subscribing to events: {}", sessionId, subscription.eventTypes)
        
        if (!sessionService.sessionExists(sessionId)) {
            return notFound("invalid session id", "Session not found: $sessionId")
        }
        
        require(subscription.eventTypes.isNotEmpty()) { "Event types must not be empty" }
        
        val savedSubscription = sessionService.addSubscription(sessionId, subscription)
            ?: return notFound("invalid session id", "Session not found: $sessionId")
        
        return ResponseEntity.ok(EventSubscriptionResponse(value = savedSubscription))
    }

    private fun notFound(error: String, message: String): ResponseEntity<Any> {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(errorResponse(error, message))
    }
}
