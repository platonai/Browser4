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
 * Controller for JavaScript execution.
 */
@RestController
@CrossOrigin
@RequestMapping(
    "/session/{sessionId}/execute",
    produces = [MediaType.APPLICATION_JSON_VALUE]
)
class ScriptController(
    private val store: InMemoryStore
) {
    private val logger = LoggerFactory.getLogger(ScriptController::class.java)

    /**
     * Executes a synchronous script.
     */
    @PostMapping("/sync", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun executeSync(
        @PathVariable sessionId: String,
        @RequestBody request: ScriptRequest,
        response: HttpServletResponse
    ): ResponseEntity<Any> {
        logger.debug("Session {} executing sync script: {}", sessionId, request.script.take(100))
        addRequestId(response)

        if (!store.sessionExists(sessionId)) {
            return notFound("session not found", "No active session with id $sessionId")
        }

        // Mock implementation - return null result
        return ResponseEntity.ok(ScriptResponse(value = null))
    }

    /**
     * Executes an asynchronous script.
     */
    @PostMapping("/async", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun executeAsync(
        @PathVariable sessionId: String,
        @RequestBody request: ScriptRequest,
        response: HttpServletResponse
    ): ResponseEntity<Any> {
        logger.debug("Session {} executing async script: {}", sessionId, request.script.take(100))
        addRequestId(response)

        if (!store.sessionExists(sessionId)) {
            return notFound("session not found", "No active session with id $sessionId")
        }

        // Mock implementation - return null result
        return ResponseEntity.ok(ScriptResponse(value = null))
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
