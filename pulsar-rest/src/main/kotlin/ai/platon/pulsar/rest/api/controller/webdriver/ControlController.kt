package ai.platon.pulsar.rest.api.controller.webdriver

import ai.platon.pulsar.rest.api.dto.*
import ai.platon.pulsar.rest.api.service.webdriver.WebDriverSessionService
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

/**
 * Browser control operations controller.
 */
@RestController
@CrossOrigin
@RequestMapping(
    "/session/{sessionId}/control",
    produces = [MediaType.APPLICATION_JSON_VALUE]
)
class ControlController(
    private val sessionService: WebDriverSessionService
) {
    private val logger = LoggerFactory.getLogger(ControlController::class.java)

    /**
     * Delay execution.
     * POST /session/{sessionId}/control/delay
     */
    @PostMapping("/delay", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun delay(
        @PathVariable sessionId: String,
        @RequestBody request: DelayRequest
    ): ResponseEntity<Any> {
        logger.debug("Session {} delaying for {}ms", sessionId, request.milliseconds)
        
        if (!sessionService.sessionExists(sessionId)) {
            return notFound("invalid session id", "Session not found: $sessionId")
        }
        
        if (request.milliseconds < 0) {
            return badRequest("invalid argument", "Delay must be non-negative")
        }
        
        // Mock: don't actually delay, just return success
        // In a real implementation, this would delay the session
        
        return ResponseEntity.ok(valueResponse(null))
    }

    /**
     * Pause session.
     * POST /session/{sessionId}/control/pause
     */
    @PostMapping("/pause")
    fun pause(@PathVariable sessionId: String): ResponseEntity<Any> {
        logger.debug("Session {} pausing", sessionId)
        
        if (!sessionService.sessionExists(sessionId)) {
            return notFound("invalid session id", "Session not found: $sessionId")
        }
        
        // Update session status to paused
        sessionService.updateSessionStatus(sessionId, "paused")
        
        return ResponseEntity.ok(valueResponse(null))
    }

    /**
     * Stop session.
     * POST /session/{sessionId}/control/stop
     */
    @PostMapping("/stop")
    fun stop(@PathVariable sessionId: String): ResponseEntity<Any> {
        logger.debug("Session {} stopping", sessionId)
        
        if (!sessionService.sessionExists(sessionId)) {
            return notFound("invalid session id", "Session not found: $sessionId")
        }
        
        // Update session status to stopped
        sessionService.updateSessionStatus(sessionId, "stopped")
        
        return ResponseEntity.ok(valueResponse(null))
    }

    private fun notFound(error: String, message: String): ResponseEntity<Any> {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(errorResponse(error, message))
    }

    private fun badRequest(error: String, message: String): ResponseEntity<Any> {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(errorResponse(error, message))
    }
}
