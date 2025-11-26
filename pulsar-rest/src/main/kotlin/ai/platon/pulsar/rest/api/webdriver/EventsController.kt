package ai.platon.pulsar.rest.api.webdriver

import ai.platon.pulsar.rest.api.dto.*
import ai.platon.pulsar.rest.api.store.InMemoryStore
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.util.UUID

/**
 * Event configuration and subscription controller.
 * Handles event configuration creation, listing, and subscriptions.
 */
@RestController
@CrossOrigin
@RequestMapping("/session/{sessionId}")
class EventsController(
    private val store: InMemoryStore
) {
    private val logger = LoggerFactory.getLogger(EventsController::class.java)

    /**
     * Creates an event configuration.
     *
     * @param sessionId The session ID
     * @param request The event configuration
     * @return The created event configuration with ID
     */
    @PostMapping("/event-configs")
    fun createEventConfig(
        @PathVariable sessionId: String,
        @RequestBody request: EventConfig,
        @RequestHeader(value = "X-Request-Id", required = false) requestId: String?
    ): ResponseEntity<*> {
        val effectiveRequestId = requestId ?: UUID.randomUUID().toString()
        logger.debug("Session {} creating event config for type {}, X-Request-Id: {}", 
            sessionId, request.eventType, effectiveRequestId)

        val config = store.addEventConfig(sessionId, request)
            ?: return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .header("X-Request-Id", effectiveRequestId)
                .body(ErrorResponse(ErrorValue("no such session", "Session $sessionId not found")))

        return ResponseEntity.ok()
            .header("X-Request-Id", effectiveRequestId)
            .body(ValueResponse(config))
    }

    /**
     * Lists all event configurations for a session.
     *
     * @param sessionId The session ID
     * @return List of event configurations
     */
    @GetMapping("/event-configs")
    fun listEventConfigs(
        @PathVariable sessionId: String,
        @RequestHeader(value = "X-Request-Id", required = false) requestId: String?
    ): ResponseEntity<*> {
        val effectiveRequestId = requestId ?: UUID.randomUUID().toString()
        logger.debug("Session {} listing event configs, X-Request-Id: {}", sessionId, effectiveRequestId)

        val configs = store.getEventConfigs(sessionId)
            ?: return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .header("X-Request-Id", effectiveRequestId)
                .body(ErrorResponse(ErrorValue("no such session", "Session $sessionId not found")))

        return ResponseEntity.ok()
            .header("X-Request-Id", effectiveRequestId)
            .body(ValueResponse(configs))
    }

    /**
     * Gets events for a session.
     *
     * @param sessionId The session ID
     * @param since Optional timestamp to get events since (epoch millis)
     * @return List of events
     */
    @GetMapping("/events")
    fun getEvents(
        @PathVariable sessionId: String,
        @RequestParam(required = false) since: Long?,
        @RequestHeader(value = "X-Request-Id", required = false) requestId: String?
    ): ResponseEntity<*> {
        val effectiveRequestId = requestId ?: UUID.randomUUID().toString()
        logger.debug("Session {} getting events since {}, X-Request-Id: {}", sessionId, since, effectiveRequestId)

        val events = store.getEvents(sessionId, since)
            ?: return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .header("X-Request-Id", effectiveRequestId)
                .body(ErrorResponse(ErrorValue("no such session", "Session $sessionId not found")))

        return ResponseEntity.ok()
            .header("X-Request-Id", effectiveRequestId)
            .body(ValueResponse(events))
    }

    /**
     * Creates an event subscription.
     *
     * @param sessionId The session ID
     * @param request The subscription request with event types
     * @return The subscription ID
     */
    @PostMapping("/events/subscribe")
    fun subscribeToEvents(
        @PathVariable sessionId: String,
        @RequestBody request: EventSubscription,
        @RequestHeader(value = "X-Request-Id", required = false) requestId: String?
    ): ResponseEntity<*> {
        val effectiveRequestId = requestId ?: UUID.randomUUID().toString()
        logger.debug("Session {} subscribing to events {}, X-Request-Id: {}", 
            sessionId, request.eventTypes, effectiveRequestId)

        val subscriptionId = store.createSubscription(sessionId, request.eventTypes, request.callback)
            ?: return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .header("X-Request-Id", effectiveRequestId)
                .body(ErrorResponse(ErrorValue("no such session", "Session $sessionId not found")))

        return ResponseEntity.ok()
            .header("X-Request-Id", effectiveRequestId)
            .body(ValueResponse(SubscriptionValue(subscriptionId)))
    }
}
