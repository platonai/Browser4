package ai.platon.pulsar.rest.api.webdriver

import ai.platon.pulsar.rest.api.dto.*
import ai.platon.pulsar.rest.api.store.InMemoryStore
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.util.UUID

/**
 * WebDriver navigation controller.
 * Handles URL navigation and retrieval.
 */
@RestController
@CrossOrigin
@RequestMapping("/session/{sessionId}")
class NavigationController(
    private val store: InMemoryStore
) {
    private val logger = LoggerFactory.getLogger(NavigationController::class.java)

    /**
     * Navigates to a URL.
     *
     * @param sessionId The session ID
     * @param request The URL to navigate to
     * @return Success or error response
     */
    @PostMapping("/url")
    fun navigateTo(
        @PathVariable sessionId: String,
        @RequestBody request: SetUrlRequest,
        @RequestHeader(value = "X-Request-Id", required = false) requestId: String?
    ): ResponseEntity<*> {
        val effectiveRequestId = requestId ?: UUID.randomUUID().toString()
        logger.debug("Session {} navigating to {}, X-Request-Id: {}", sessionId, request.url, effectiveRequestId)

        if (!store.sessionExists(sessionId)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .header("X-Request-Id", effectiveRequestId)
                .body(ErrorResponse(ErrorValue("no such session", "Session $sessionId not found")))
        }

        store.setSessionUrl(sessionId, request.url)

        return ResponseEntity.ok()
            .header("X-Request-Id", effectiveRequestId)
            .body(ValueResponse<Any?>(null))
    }

    /**
     * Gets the current URL.
     *
     * @param sessionId The session ID
     * @return The current URL or error
     */
    @GetMapping("/url")
    fun getCurrentUrl(
        @PathVariable sessionId: String,
        @RequestHeader(value = "X-Request-Id", required = false) requestId: String?
    ): ResponseEntity<*> {
        val effectiveRequestId = requestId ?: UUID.randomUUID().toString()
        logger.debug("Session {} getting current URL, X-Request-Id: {}", sessionId, effectiveRequestId)

        val session = store.getSession(sessionId)
            ?: return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .header("X-Request-Id", effectiveRequestId)
                .body(ErrorResponse(ErrorValue("no such session", "Session $sessionId not found")))

        return ResponseEntity.ok()
            .header("X-Request-Id", effectiveRequestId)
            .body(ValueResponse(session.url ?: ""))
    }

    /**
     * Gets the document URI.
     *
     * @param sessionId The session ID
     * @return The document URI or error
     */
    @GetMapping("/documentUri")
    fun getDocumentUri(
        @PathVariable sessionId: String,
        @RequestHeader(value = "X-Request-Id", required = false) requestId: String?
    ): ResponseEntity<*> {
        val effectiveRequestId = requestId ?: UUID.randomUUID().toString()
        logger.debug("Session {} getting document URI, X-Request-Id: {}", sessionId, effectiveRequestId)

        val session = store.getSession(sessionId)
            ?: return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .header("X-Request-Id", effectiveRequestId)
                .body(ErrorResponse(ErrorValue("no such session", "Session $sessionId not found")))

        // In mock implementation, document URI is the same as URL
        return ResponseEntity.ok()
            .header("X-Request-Id", effectiveRequestId)
            .body(ValueResponse(session.url ?: ""))
    }

    /**
     * Gets the base URI.
     *
     * @param sessionId The session ID
     * @return The base URI or error
     */
    @GetMapping("/baseUri")
    fun getBaseUri(
        @PathVariable sessionId: String,
        @RequestHeader(value = "X-Request-Id", required = false) requestId: String?
    ): ResponseEntity<*> {
        val effectiveRequestId = requestId ?: UUID.randomUUID().toString()
        logger.debug("Session {} getting base URI, X-Request-Id: {}", sessionId, effectiveRequestId)

        val session = store.getSession(sessionId)
            ?: return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .header("X-Request-Id", effectiveRequestId)
                .body(ErrorResponse(ErrorValue("no such session", "Session $sessionId not found")))

        // In mock implementation, base URI is derived from URL
        val baseUri = session.url?.let { url ->
            try {
                val uri = java.net.URI(url)
                "${uri.scheme}://${uri.host}${if (uri.port != -1) ":${uri.port}" else ""}"
            } catch (_: Exception) {
                url
            }
        } ?: ""

        return ResponseEntity.ok()
            .header("X-Request-Id", effectiveRequestId)
            .body(ValueResponse(baseUri))
    }
}
