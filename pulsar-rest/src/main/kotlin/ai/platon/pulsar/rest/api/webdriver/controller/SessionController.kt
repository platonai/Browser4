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
 * Controller for WebDriver session management.
 */
@RestController
@CrossOrigin
@RequestMapping(
    produces = [MediaType.APPLICATION_JSON_VALUE]
)
class SessionController(
    private val store: InMemoryStore
) {
    private val logger = LoggerFactory.getLogger(SessionController::class.java)

    /**
     * Creates a new WebDriver session.
     */
    @PostMapping("/session", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun createSession(
        @RequestBody(required = false) request: NewSessionRequest?,
        response: HttpServletResponse
    ): ResponseEntity<NewSessionResponse> {
        logger.debug("Creating new session with capabilities: {}", request?.capabilities)
        addRequestId(response)

        val session = store.createSession(request?.capabilities)
        val responseBody = NewSessionResponse(
            value = NewSessionResponse.SessionValue(
                sessionId = session.sessionId,
                capabilities = session.capabilities
            )
        )
        return ResponseEntity.ok(responseBody)
    }

    /**
     * Retrieves session details.
     */
    @GetMapping("/session/{sessionId}")
    fun getSession(
        @PathVariable sessionId: String,
        response: HttpServletResponse
    ): ResponseEntity<Any> {
        logger.debug("Getting session: {}", sessionId)
        addRequestId(response)

        val session = store.getSession(sessionId)
            ?: return notFound("session not found", "No active session with id $sessionId")

        val responseBody = SessionDetails(
            value = SessionDetails.SessionDetailsValue(
                sessionId = session.sessionId,
                url = session.url,
                status = session.status,
                capabilities = session.capabilities
            )
        )
        return ResponseEntity.ok(responseBody)
    }

    /**
     * Deletes a session.
     */
    @DeleteMapping("/session/{sessionId}")
    fun deleteSession(
        @PathVariable sessionId: String,
        response: HttpServletResponse
    ): ResponseEntity<Any> {
        logger.debug("Deleting session: {}", sessionId)
        addRequestId(response)

        val deleted = store.deleteSession(sessionId)
        if (!deleted) {
            return notFound("session not found", "No active session with id $sessionId")
        }

        return ResponseEntity.ok(WebDriverResponse<Any?>(value = null))
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
