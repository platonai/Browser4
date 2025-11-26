package ai.platon.pulsar.rest.api.controller.webdriver

import ai.platon.pulsar.rest.api.dto.*
import ai.platon.pulsar.rest.api.service.webdriver.WebDriverSessionService
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

/**
 * Navigation controller for URL operations.
 */
@RestController
@CrossOrigin
@RequestMapping(
    "/session/{sessionId}",
    produces = [MediaType.APPLICATION_JSON_VALUE]
)
class NavigationController(
    private val sessionService: WebDriverSessionService
) {
    private val logger = LoggerFactory.getLogger(NavigationController::class.java)

    /**
     * Navigate to URL.
     * POST /session/{sessionId}/url
     */
    @PostMapping("/url", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun navigateToUrl(
        @PathVariable sessionId: String,
        @RequestBody request: SetUrlRequest
    ): ResponseEntity<Any> {
        logger.debug("Session {} navigating to: {}", sessionId, request.url)
        
        if (!sessionService.sessionExists(sessionId)) {
            return notFound("invalid session id", "Session not found: $sessionId")
        }
        
        if (request.url.isBlank()) {
            return badRequest("invalid argument", "URL must not be blank")
        }
        
        // Mock navigation - just update the URL in session
        sessionService.updateSessionUrl(sessionId, request.url)
        
        return ResponseEntity.ok(valueResponse(null))
    }

    /**
     * Get current URL.
     * GET /session/{sessionId}/url
     */
    @GetMapping("/url")
    fun getCurrentUrl(@PathVariable sessionId: String): ResponseEntity<Any> {
        logger.debug("Getting current URL for session: {}", sessionId)
        
        val url = sessionService.getCurrentUrl(sessionId)
            ?: return notFound("invalid session id", "Session not found: $sessionId")
        
        return ResponseEntity.ok(valueResponse(url))
    }

    /**
     * Get document URI.
     * GET /session/{sessionId}/documentUri
     */
    @GetMapping("/documentUri")
    fun getDocumentUri(@PathVariable sessionId: String): ResponseEntity<Any> {
        logger.debug("Getting document URI for session: {}", sessionId)
        
        if (!sessionService.sessionExists(sessionId)) {
            return notFound("invalid session id", "Session not found: $sessionId")
        }
        
        // Mock: return current URL as document URI
        val url = sessionService.getCurrentUrl(sessionId) ?: ""
        return ResponseEntity.ok(valueResponse(url))
    }

    /**
     * Get base URI.
     * GET /session/{sessionId}/baseUri
     */
    @GetMapping("/baseUri")
    fun getBaseUri(@PathVariable sessionId: String): ResponseEntity<Any> {
        logger.debug("Getting base URI for session: {}", sessionId)
        
        if (!sessionService.sessionExists(sessionId)) {
            return notFound("invalid session id", "Session not found: $sessionId")
        }
        
        // Mock: extract base URI from current URL
        val url = sessionService.getCurrentUrl(sessionId) ?: ""
        val baseUri = try {
            if (url.isNotBlank()) {
                val uri = java.net.URI(url)
                "${uri.scheme}://${uri.host}${if (uri.port > 0) ":${uri.port}" else ""}"
            } else {
                ""
            }
        } catch (e: Exception) {
            url
        }
        
        return ResponseEntity.ok(valueResponse(baseUri))
    }

    private fun notFound(error: String, message: String): ResponseEntity<Any> {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(errorResponse(error, message))
    }

    private fun badRequest(error: String, message: String): ResponseEntity<Any> {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(errorResponse(error, message))
    }
}
