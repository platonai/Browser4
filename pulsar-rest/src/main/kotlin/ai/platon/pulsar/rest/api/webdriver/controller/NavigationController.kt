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
 * Controller for page navigation operations.
 */
@RestController
@CrossOrigin
@RequestMapping(
    "/session/{sessionId}",
    produces = [MediaType.APPLICATION_JSON_VALUE]
)
class NavigationController(
    private val store: InMemoryStore
) {
    private val logger = LoggerFactory.getLogger(NavigationController::class.java)

    /**
     * Navigates to a URL.
     */
    @PostMapping("/url", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun navigateTo(
        @PathVariable sessionId: String,
        @RequestBody request: SetUrlRequest,
        response: HttpServletResponse
    ): ResponseEntity<Any> {
        logger.debug("Session {} navigating to: {}", sessionId, request.url)
        addRequestId(response)

        if (!store.sessionExists(sessionId)) {
            return notFound("session not found", "No active session with id $sessionId")
        }

        store.setSessionUrl(sessionId, request.url)
        return ResponseEntity.ok(WebDriverResponse<Any?>(value = null))
    }

    /**
     * Gets the current URL.
     */
    @GetMapping("/url")
    fun getCurrentUrl(
        @PathVariable sessionId: String,
        response: HttpServletResponse
    ): ResponseEntity<Any> {
        logger.debug("Getting current URL for session: {}", sessionId)
        addRequestId(response)

        val session = store.getSession(sessionId)
            ?: return notFound("session not found", "No active session with id $sessionId")

        return ResponseEntity.ok(UrlResponse(value = session.url))
    }

    /**
     * Gets the document URI.
     */
    @GetMapping("/documentUri")
    fun getDocumentUri(
        @PathVariable sessionId: String,
        response: HttpServletResponse
    ): ResponseEntity<Any> {
        logger.debug("Getting document URI for session: {}", sessionId)
        addRequestId(response)

        val session = store.getSession(sessionId)
            ?: return notFound("session not found", "No active session with id $sessionId")

        // In a mock implementation, document URI is the same as URL
        return ResponseEntity.ok(UrlResponse(value = session.url))
    }

    /**
     * Gets the base URI.
     */
    @GetMapping("/baseUri")
    fun getBaseUri(
        @PathVariable sessionId: String,
        response: HttpServletResponse
    ): ResponseEntity<Any> {
        logger.debug("Getting base URI for session: {}", sessionId)
        addRequestId(response)

        val session = store.getSession(sessionId)
            ?: return notFound("session not found", "No active session with id $sessionId")

        // Extract base URI from URL (mock implementation)
        val baseUri = session.url?.let { url ->
            try {
                val uri = java.net.URI(url)
                "${uri.scheme}://${uri.host}${if (uri.port > 0) ":${uri.port}" else ""}"
            } catch (e: Exception) {
                url
            }
        }

        return ResponseEntity.ok(UrlResponse(value = baseUri))
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
