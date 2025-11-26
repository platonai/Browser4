package ai.platon.pulsar.rest.api.webdriver.controller

import ai.platon.pulsar.rest.api.webdriver.dto.*
import ai.platon.pulsar.rest.api.webdriver.store.InMemoryStore
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.util.UUID

/**
 * Controller for browser control operations.
 */
@RestController
@CrossOrigin
@RequestMapping("/session/{sessionId}/control")
class ControlController(
    private val store: InMemoryStore
) {
    private val logger = LoggerFactory.getLogger(ControlController::class.java)

    /**
     * Add delay.
     */
    @PostMapping("/delay")
    fun delay(
        @PathVariable sessionId: String,
        @RequestBody request: DelayRequest,
        @RequestHeader(value = "X-Request-Id", required = false) requestId: String?
    ): ResponseEntity<*> {
        val reqId = requestId ?: UUID.randomUUID().toString()
        logger.debug("Adding delay of {}ms in session: {}, X-Request-Id: {}", request.ms, sessionId, reqId)

        store.getSession(sessionId)
            ?: return notFoundResponse(sessionId, reqId)

        // Mock: sleep for the requested duration (capped at 5 seconds for safety)
        val sleepTime = minOf(request.ms.toLong(), 5000L)
        if (sleepTime > 0) {
            Thread.sleep(sleepTime)
        }

        return ResponseEntity.ok()
            .header("X-Request-Id", reqId)
            .body(WebDriverResponse<Any?>(null))
    }

    /**
     * Pause session.
     */
    @PostMapping("/pause")
    fun pause(
        @PathVariable sessionId: String,
        @RequestHeader(value = "X-Request-Id", required = false) requestId: String?
    ): ResponseEntity<*> {
        val reqId = requestId ?: UUID.randomUUID().toString()
        logger.debug("Pausing session: {}, X-Request-Id: {}", sessionId, reqId)

        store.getSession(sessionId)
            ?: return notFoundResponse(sessionId, reqId)

        store.pauseSession(sessionId)

        return ResponseEntity.ok()
            .header("X-Request-Id", reqId)
            .body(WebDriverResponse<Any?>(null))
    }

    /**
     * Stop session.
     */
    @PostMapping("/stop")
    fun stop(
        @PathVariable sessionId: String,
        @RequestHeader(value = "X-Request-Id", required = false) requestId: String?
    ): ResponseEntity<*> {
        val reqId = requestId ?: UUID.randomUUID().toString()
        logger.debug("Stopping session: {}, X-Request-Id: {}", sessionId, reqId)

        store.getSession(sessionId)
            ?: return notFoundResponse(sessionId, reqId)

        store.stopSession(sessionId)

        return ResponseEntity.ok()
            .header("X-Request-Id", reqId)
            .body(WebDriverResponse<Any?>(null))
    }

    private fun notFoundResponse(sessionId: String, reqId: String): ResponseEntity<WebDriverResponse<ErrorValue>> {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .header("X-Request-Id", reqId)
            .body(WebDriverResponse(ErrorValue("no such session", "Session $sessionId not found")))
    }
}
