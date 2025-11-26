package ai.platon.pulsar.rest.api.webdriver

import ai.platon.pulsar.rest.api.dto.*
import ai.platon.pulsar.rest.api.store.InMemoryStore
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.util.UUID

/**
 * Browser control operations controller.
 * Handles delay, pause, and stop operations.
 */
@RestController
@CrossOrigin
@RequestMapping("/session/{sessionId}/control")
class ControlController(
    private val store: InMemoryStore
) {
    private val logger = LoggerFactory.getLogger(ControlController::class.java)

    /**
     * Adds a delay.
     *
     * @param sessionId The session ID
     * @param request The delay request with milliseconds
     * @return Success or error
     */
    @PostMapping("/delay")
    fun controlDelay(
        @PathVariable sessionId: String,
        @RequestBody request: DelayRequest,
        @RequestHeader(value = "X-Request-Id", required = false) requestId: String?
    ): ResponseEntity<*> {
        val effectiveRequestId = requestId ?: UUID.randomUUID().toString()
        logger.debug("Session {} adding delay of {} ms, X-Request-Id: {}", sessionId, request.ms, effectiveRequestId)

        if (!store.sessionExists(sessionId)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .header("X-Request-Id", effectiveRequestId)
                .body(ErrorResponse(ErrorValue("no such session", "Session $sessionId not found")))
        }

        // In mock implementation, we don't actually delay
        // Real implementation would wait for specified time

        return ResponseEntity.ok()
            .header("X-Request-Id", effectiveRequestId)
            .body(ValueResponse<Any?>(null))
    }

    /**
     * Pauses the session.
     *
     * @param sessionId The session ID
     * @return Success or error
     */
    @PostMapping("/pause")
    fun controlPause(
        @PathVariable sessionId: String,
        @RequestHeader(value = "X-Request-Id", required = false) requestId: String?
    ): ResponseEntity<*> {
        val effectiveRequestId = requestId ?: UUID.randomUUID().toString()
        logger.debug("Session {} pausing, X-Request-Id: {}", sessionId, effectiveRequestId)

        if (!store.sessionExists(sessionId)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .header("X-Request-Id", effectiveRequestId)
                .body(ErrorResponse(ErrorValue("no such session", "Session $sessionId not found")))
        }

        store.setSessionStatus(sessionId, "paused")

        return ResponseEntity.ok()
            .header("X-Request-Id", effectiveRequestId)
            .body(ValueResponse<Any?>(null))
    }

    /**
     * Stops the session.
     *
     * @param sessionId The session ID
     * @return Success or error
     */
    @PostMapping("/stop")
    fun controlStop(
        @PathVariable sessionId: String,
        @RequestHeader(value = "X-Request-Id", required = false) requestId: String?
    ): ResponseEntity<*> {
        val effectiveRequestId = requestId ?: UUID.randomUUID().toString()
        logger.debug("Session {} stopping, X-Request-Id: {}", sessionId, effectiveRequestId)

        if (!store.sessionExists(sessionId)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .header("X-Request-Id", effectiveRequestId)
                .body(ErrorResponse(ErrorValue("no such session", "Session $sessionId not found")))
        }

        store.setSessionStatus(sessionId, "stopped")

        return ResponseEntity.ok()
            .header("X-Request-Id", effectiveRequestId)
            .body(ValueResponse<Any?>(null))
    }
}
