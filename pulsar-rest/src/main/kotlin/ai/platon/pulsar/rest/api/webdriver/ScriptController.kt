package ai.platon.pulsar.rest.api.webdriver

import ai.platon.pulsar.rest.api.dto.*
import ai.platon.pulsar.rest.api.store.InMemoryStore
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.util.UUID

/**
 * JavaScript execution controller.
 * Handles synchronous and asynchronous script execution.
 */
@RestController
@CrossOrigin
@RequestMapping("/session/{sessionId}/execute")
class ScriptController(
    private val store: InMemoryStore
) {
    private val logger = LoggerFactory.getLogger(ScriptController::class.java)

    /**
     * Executes JavaScript synchronously.
     *
     * @param sessionId The session ID
     * @param request The script execution request
     * @return The script result
     */
    @PostMapping("/sync")
    fun executeSync(
        @PathVariable sessionId: String,
        @RequestBody request: ExecuteScriptRequest,
        @RequestHeader(value = "X-Request-Id", required = false) requestId: String?
    ): ResponseEntity<*> {
        val effectiveRequestId = requestId ?: UUID.randomUUID().toString()
        logger.debug("Session {} executing sync script, X-Request-Id: {}", sessionId, effectiveRequestId)

        if (!store.sessionExists(sessionId)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .header("X-Request-Id", effectiveRequestId)
                .body(ErrorResponse(ErrorValue("no such session", "Session $sessionId not found")))
        }

        // In mock implementation, return null for script execution
        // Real implementation would execute JavaScript in browser context
        return ResponseEntity.ok()
            .header("X-Request-Id", effectiveRequestId)
            .body(ValueResponse<Any?>(null))
    }

    /**
     * Executes JavaScript asynchronously.
     *
     * @param sessionId The session ID
     * @param request The script execution request
     * @return The script result
     */
    @PostMapping("/async")
    fun executeAsync(
        @PathVariable sessionId: String,
        @RequestBody request: ExecuteScriptRequest,
        @RequestHeader(value = "X-Request-Id", required = false) requestId: String?
    ): ResponseEntity<*> {
        val effectiveRequestId = requestId ?: UUID.randomUUID().toString()
        logger.debug("Session {} executing async script, X-Request-Id: {}", sessionId, effectiveRequestId)

        if (!store.sessionExists(sessionId)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .header("X-Request-Id", effectiveRequestId)
                .body(ErrorResponse(ErrorValue("no such session", "Session $sessionId not found")))
        }

        // In mock implementation, return null for script execution
        // Real implementation would execute async JavaScript in browser context
        return ResponseEntity.ok()
            .header("X-Request-Id", effectiveRequestId)
            .body(ValueResponse<Any?>(null))
    }
}
