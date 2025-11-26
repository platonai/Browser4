package ai.platon.pulsar.rest.api.webdriver

import ai.platon.pulsar.rest.api.dto.*
import ai.platon.pulsar.rest.api.store.InMemoryStore
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.util.UUID

/**
 * WebDriver session management controller.
 * Handles session creation, deletion, and information retrieval.
 */
@RestController
@CrossOrigin
@RequestMapping("/session")
class SessionController(
    private val store: InMemoryStore
) {
    private val logger = LoggerFactory.getLogger(SessionController::class.java)

    /**
     * Creates a new WebDriver session.
     *
     * @param request The session creation request with capabilities
     * @return The new session response with sessionId and capabilities
     */
    @PostMapping
    fun createSession(
        @RequestBody request: NewSessionRequest,
        @RequestHeader(value = "X-Request-Id", required = false) requestId: String?
    ): ResponseEntity<NewSessionResponse> {
        val effectiveRequestId = requestId ?: UUID.randomUUID().toString()
        logger.debug("Creating session, X-Request-Id: {}", effectiveRequestId)

        val capabilities = request.capabilities ?: request.desiredCapabilities ?: emptyMap()
        val session = store.createSession(capabilities)

        val response = NewSessionResponse(
            value = NewSessionValue(
                sessionId = session.sessionId,
                capabilities = session.capabilities
            )
        )

        return ResponseEntity.ok()
            .header("X-Request-Id", effectiveRequestId)
            .body(response)
    }

    /**
     * Gets session information.
     *
     * @param sessionId The session ID
     * @return Session information or 404 if not found
     */
    @GetMapping("/{sessionId}")
    fun getSession(
        @PathVariable sessionId: String,
        @RequestHeader(value = "X-Request-Id", required = false) requestId: String?
    ): ResponseEntity<*> {
        val effectiveRequestId = requestId ?: UUID.randomUUID().toString()
        logger.debug("Getting session {}, X-Request-Id: {}", sessionId, effectiveRequestId)

        val session = store.getSession(sessionId)
            ?: return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .header("X-Request-Id", effectiveRequestId)
                .body(ErrorResponse(ErrorValue("no such session", "Session $sessionId not found")))

        val response = SessionInfo(
            value = SessionInfoValue(
                sessionId = session.sessionId,
                capabilities = session.capabilities,
                url = session.url,
                status = session.status
            )
        )

        return ResponseEntity.ok()
            .header("X-Request-Id", effectiveRequestId)
            .body(response)
    }

    /**
     * Deletes a session.
     *
     * @param sessionId The session ID to delete
     * @return Success or 404 if not found
     */
    @DeleteMapping("/{sessionId}")
    fun deleteSession(
        @PathVariable sessionId: String,
        @RequestHeader(value = "X-Request-Id", required = false) requestId: String?
    ): ResponseEntity<*> {
        val effectiveRequestId = requestId ?: UUID.randomUUID().toString()
        logger.debug("Deleting session {}, X-Request-Id: {}", sessionId, effectiveRequestId)

        if (!store.deleteSession(sessionId)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .header("X-Request-Id", effectiveRequestId)
                .body(ErrorResponse(ErrorValue("no such session", "Session $sessionId not found")))
        }

        return ResponseEntity.ok()
            .header("X-Request-Id", effectiveRequestId)
            .body(ValueResponse<Any?>(null))
    }
}
