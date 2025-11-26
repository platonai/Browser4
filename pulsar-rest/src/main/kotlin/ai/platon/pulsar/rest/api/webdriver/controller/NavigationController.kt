package ai.platon.pulsar.rest.api.webdriver.controller

import ai.platon.pulsar.rest.api.webdriver.dto.*
import ai.platon.pulsar.rest.api.webdriver.store.InMemoryStore
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.util.UUID

/**
 * Controller for WebDriver navigation operations.
 */
@RestController
@CrossOrigin
@RequestMapping("/session/{sessionId}")
class NavigationController(
    private val store: InMemoryStore
) {
    private val logger = LoggerFactory.getLogger(NavigationController::class.java)

    /**
     * Navigate to a URL.
     */
    @PostMapping("/url")
    fun navigateTo(
        @PathVariable sessionId: String,
        @RequestBody request: SetUrlRequest,
        @RequestHeader(value = "X-Request-Id", required = false) requestId: String?
    ): ResponseEntity<*> {
        val reqId = requestId ?: UUID.randomUUID().toString()
        logger.debug("Navigating session {} to URL: {}, X-Request-Id: {}", sessionId, request.url, reqId)

        val session = store.getSession(sessionId)
            ?: return notFoundResponse(sessionId, reqId)

        if (!store.updateSessionUrl(sessionId, request.url)) {
            return notFoundResponse(sessionId, reqId)
        }

        return ResponseEntity.ok()
            .header("X-Request-Id", reqId)
            .body(WebDriverResponse<Any?>(null))
    }

    /**
     * Get current URL.
     */
    @GetMapping("/url")
    fun getCurrentUrl(
        @PathVariable sessionId: String,
        @RequestHeader(value = "X-Request-Id", required = false) requestId: String?
    ): ResponseEntity<*> {
        val reqId = requestId ?: UUID.randomUUID().toString()
        logger.debug("Getting current URL for session: {}, X-Request-Id: {}", sessionId, reqId)

        val session = store.getSession(sessionId)
            ?: return notFoundResponse(sessionId, reqId)

        return ResponseEntity.ok()
            .header("X-Request-Id", reqId)
            .body(WebDriverResponse(session.currentUrl ?: ""))
    }

    /**
     * Get document URI.
     */
    @GetMapping("/documentUri")
    fun getDocumentUri(
        @PathVariable sessionId: String,
        @RequestHeader(value = "X-Request-Id", required = false) requestId: String?
    ): ResponseEntity<*> {
        val reqId = requestId ?: UUID.randomUUID().toString()
        logger.debug("Getting document URI for session: {}, X-Request-Id: {}", sessionId, reqId)

        val session = store.getSession(sessionId)
            ?: return notFoundResponse(sessionId, reqId)

        return ResponseEntity.ok()
            .header("X-Request-Id", reqId)
            .body(WebDriverResponse(session.documentUri ?: ""))
    }

    /**
     * Get base URI.
     */
    @GetMapping("/baseUri")
    fun getBaseUri(
        @PathVariable sessionId: String,
        @RequestHeader(value = "X-Request-Id", required = false) requestId: String?
    ): ResponseEntity<*> {
        val reqId = requestId ?: UUID.randomUUID().toString()
        logger.debug("Getting base URI for session: {}, X-Request-Id: {}", sessionId, reqId)

        val session = store.getSession(sessionId)
            ?: return notFoundResponse(sessionId, reqId)

        return ResponseEntity.ok()
            .header("X-Request-Id", reqId)
            .body(WebDriverResponse(session.baseUri ?: ""))
    }

    private fun notFoundResponse(sessionId: String, reqId: String): ResponseEntity<WebDriverResponse<ErrorValue>> {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .header("X-Request-Id", reqId)
            .body(WebDriverResponse(ErrorValue("no such session", "Session $sessionId not found")))
    }
}
