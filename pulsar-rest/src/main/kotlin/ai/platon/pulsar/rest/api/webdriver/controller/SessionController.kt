package ai.platon.pulsar.rest.api.webdriver.controller

import ai.platon.pulsar.rest.api.webdriver.dto.*
import ai.platon.pulsar.rest.api.webdriver.store.InMemoryStore
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.util.UUID

/**
 * Controller for WebDriver session management.
 */
@RestController
@CrossOrigin
@RequestMapping("/session")
class SessionController(
    private val store: InMemoryStore
) {
    private val logger = LoggerFactory.getLogger(SessionController::class.java)

    /**
     * Create a new browser session.
     */
    @PostMapping
    fun createSession(
        @RequestBody request: NewSessionRequest,
        @RequestHeader(value = "X-Request-Id", required = false) requestId: String?
    ): ResponseEntity<WebDriverResponse<SessionValue>> {
        val reqId = requestId ?: UUID.randomUUID().toString()
        logger.debug("Creating session, X-Request-Id: {}", reqId)

        val session = store.createSession(request)
        val value = SessionValue(
            sessionId = session.sessionId,
            capabilities = session.capabilities
        )

        return ResponseEntity.ok()
            .header("X-Request-Id", reqId)
            .body(WebDriverResponse(value))
    }

    /**
     * Get session information.
     */
    @GetMapping("/{sessionId}")
    fun getSession(
        @PathVariable sessionId: String,
        @RequestHeader(value = "X-Request-Id", required = false) requestId: String?
    ): ResponseEntity<*> {
        val reqId = requestId ?: UUID.randomUUID().toString()
        logger.debug("Getting session: {}, X-Request-Id: {}", sessionId, reqId)

        val session = store.getSession(sessionId)
            ?: return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .header("X-Request-Id", reqId)
                .body(WebDriverResponse(ErrorValue("no such session", "Session $sessionId not found")))

        val value = SessionValue(
            sessionId = session.sessionId,
            capabilities = session.capabilities
        )

        return ResponseEntity.ok()
            .header("X-Request-Id", reqId)
            .body(WebDriverResponse(value))
    }

    /**
     * Delete a browser session.
     */
    @DeleteMapping("/{sessionId}")
    fun deleteSession(
        @PathVariable sessionId: String,
        @RequestHeader(value = "X-Request-Id", required = false) requestId: String?
    ): ResponseEntity<*> {
        val reqId = requestId ?: UUID.randomUUID().toString()
        logger.debug("Deleting session: {}, X-Request-Id: {}", sessionId, reqId)

        val deleted = store.deleteSession(sessionId)
        if (!deleted) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .header("X-Request-Id", reqId)
                .body(WebDriverResponse(ErrorValue("no such session", "Session $sessionId not found")))
        }

        return ResponseEntity.ok()
            .header("X-Request-Id", reqId)
            .body(WebDriverResponse<Any?>(null))
    }
}
