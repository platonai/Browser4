package ai.platon.pulsar.rest.api.controller.webdriver

import ai.platon.pulsar.rest.api.dto.*
import ai.platon.pulsar.rest.api.service.webdriver.WebDriverSessionService
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

/**
 * Session management controller following W3C WebDriver specification.
 */
@RestController
@CrossOrigin
@RequestMapping(
    produces = [MediaType.APPLICATION_JSON_VALUE]
)
class SessionController(
    private val sessionService: WebDriverSessionService
) {
    private val logger = LoggerFactory.getLogger(SessionController::class.java)

    /**
     * Create a new session.
     * POST /session
     */
    @PostMapping("/session", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun createSession(@RequestBody request: NewSessionRequest): ResponseEntity<Any> {
        logger.debug("Creating new session with capabilities: {}", request.capabilities)
        
        val session = sessionService.createSession(request)
        
        val response = NewSessionResponse(
            value = NewSessionValue(
                sessionId = session.sessionId,
                capabilities = session.capabilities
            )
        )
        
        return ResponseEntity.ok(response)
    }

    /**
     * Get session details.
     * GET /session/{sessionId}
     */
    @GetMapping("/session/{sessionId}")
    fun getSession(@PathVariable sessionId: String): ResponseEntity<Any> {
        logger.debug("Getting session: {}", sessionId)
        
        val session = sessionService.getSession(sessionId)
            ?: return notFound("invalid session id", "Session not found: $sessionId")
        
        return ResponseEntity.ok(SessionResponse(value = session))
    }

    /**
     * Delete a session.
     * DELETE /session/{sessionId}
     */
    @DeleteMapping("/session/{sessionId}")
    fun deleteSession(@PathVariable sessionId: String): ResponseEntity<Any> {
        logger.debug("Deleting session: {}", sessionId)
        
        if (!sessionService.deleteSession(sessionId)) {
            return notFound("invalid session id", "Session not found: $sessionId")
        }
        
        return ResponseEntity.ok(valueResponse(null))
    }

    private fun notFound(error: String, message: String): ResponseEntity<Any> {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(errorResponse(error, message))
    }
}
