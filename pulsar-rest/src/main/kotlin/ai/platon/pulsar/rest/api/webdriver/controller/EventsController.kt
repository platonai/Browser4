package ai.platon.pulsar.rest.api.webdriver.controller

import ai.platon.pulsar.rest.api.webdriver.dto.*
import ai.platon.pulsar.rest.api.webdriver.store.InMemoryStore
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.util.UUID

/**
 * Controller for event configuration and subscription.
 */
@RestController
@CrossOrigin
@RequestMapping(
    "/session/{sessionId}",
    produces = [MediaType.APPLICATION_JSON_VALUE]
)
class EventsController(
    private val store: InMemoryStore
) {
    private val logger = LoggerFactory.getLogger(EventsController::class.java)

    /**
     * Creates an event configuration.
     */
    @PostMapping("/event-configs", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun createEventConfig(
        @PathVariable sessionId: String,
        @RequestBody request: EventConfig,
        response: HttpServletResponse
    ): ResponseEntity<Any> {
        logger.debug("Session {} creating event config for type: {}", sessionId, request.eventType)
        addRequestId(response)

        val config = store.addEventConfig(sessionId, request)
            ?: return notFound("session not found", "No active session with id $sessionId")

        return ResponseEntity.ok(EventConfigResponse(value = config))
    }

    /**
     * Gets all event configurations for a session.
     */
    @GetMapping("/event-configs")
    fun getEventConfigs(
        @PathVariable sessionId: String,
        response: HttpServletResponse
    ): ResponseEntity<Any> {
        logger.debug("Session {} getting event configs", sessionId)
        addRequestId(response)

        val configs = store.getEventConfigs(sessionId)
            ?: return notFound("session not found", "No active session with id $sessionId")

        return ResponseEntity.ok(EventConfigsResponse(value = configs))
    }

    /**
     * Gets all events for a session.
     */
    @GetMapping("/events")
    fun getEvents(
        @PathVariable sessionId: String,
        response: HttpServletResponse
    ): ResponseEntity<Any> {
        logger.debug("Session {} getting events", sessionId)
        addRequestId(response)

        val events = store.getEvents(sessionId)
            ?: return notFound("session not found", "No active session with id $sessionId")

        return ResponseEntity.ok(EventsResponse(value = events))
    }

    /**
     * Subscribes to events.
     */
    @PostMapping("/events/subscribe", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun subscribeToEvents(
        @PathVariable sessionId: String,
        @RequestBody request: SubscribeRequest,
        response: HttpServletResponse
    ): ResponseEntity<Any> {
        logger.debug("Session {} subscribing to events: {}", sessionId, request.eventTypes)
        addRequestId(response)

        val subscription = store.createSubscription(sessionId, request.eventTypes)
            ?: return notFound("session not found", "No active session with id $sessionId")

        return ResponseEntity.ok(SubscriptionResponse(value = subscription))
    }

    private fun addRequestId(response: HttpServletResponse) {
        response.addHeader("X-Request-Id", UUID.randomUUID().toString())
    }

    private fun notFound(error: String, message: String): ResponseEntity<Any> {
        return ResponseEntity.status(404).body(
            ErrorResponse(
                value = ErrorResponse.ErrorValue(
                    error = error,
                    message = message
                )
            )
        )
    }
}
