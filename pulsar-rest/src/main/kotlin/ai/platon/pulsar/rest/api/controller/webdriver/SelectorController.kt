package ai.platon.pulsar.rest.api.controller.webdriver

import ai.platon.pulsar.rest.api.dto.*
import ai.platon.pulsar.rest.api.service.webdriver.WebDriverSessionService
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

/**
 * Selector-first element operations controller (Browser4 extension).
 * These endpoints provide a more ergonomic API for element interactions.
 */
@RestController
@CrossOrigin
@RequestMapping(
    "/session/{sessionId}/selectors",
    produces = [MediaType.APPLICATION_JSON_VALUE]
)
class SelectorController(
    private val sessionService: WebDriverSessionService
) {
    private val logger = LoggerFactory.getLogger(SelectorController::class.java)

    /**
     * Check if selector matches any element.
     * POST /session/{sessionId}/selectors/exists
     */
    @PostMapping("/exists", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun exists(
        @PathVariable sessionId: String,
        @RequestBody request: SelectorRequest
    ): ResponseEntity<Any> {
        logger.debug("Session {} checking selector exists: {}", sessionId, request.selector)
        
        if (!sessionService.sessionExists(sessionId)) {
            return notFound("invalid session id", "Session not found: $sessionId")
        }
        
        require(request.selector.isNotBlank()) { "Selector must not be blank" }
        
        // Mock: always return true for valid session
        return ResponseEntity.ok(valueResponse(true))
    }

    /**
     * Wait for selector to appear.
     * POST /session/{sessionId}/selectors/waitFor
     */
    @PostMapping("/waitFor", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun waitFor(
        @PathVariable sessionId: String,
        @RequestBody request: SelectorWaitRequest
    ): ResponseEntity<Any> {
        logger.debug("Session {} waiting for selector: {} (timeout: {}ms)", 
            sessionId, request.selector, request.timeout)
        
        if (!sessionService.sessionExists(sessionId)) {
            return notFound("invalid session id", "Session not found: $sessionId")
        }
        
        require(request.selector.isNotBlank()) { "Selector must not be blank" }
        
        // Mock: immediately return success
        return ResponseEntity.ok(valueResponse(true))
    }

    /**
     * Find element by selector.
     * POST /session/{sessionId}/selectors/element
     */
    @PostMapping("/element", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun findElement(
        @PathVariable sessionId: String,
        @RequestBody request: SelectorRequest
    ): ResponseEntity<Any> {
        logger.debug("Session {} finding element by selector: {}", sessionId, request.selector)
        
        if (!sessionService.sessionExists(sessionId)) {
            return notFound("invalid session id", "Session not found: $sessionId")
        }
        
        require(request.selector.isNotBlank()) { "Selector must not be blank" }
        
        val elementId = sessionService.storeElement(sessionId, request.selector)
            ?: return notFound("invalid session id", "Session not found: $sessionId")
        
        return ResponseEntity.ok(ElementRefResponse(value = ElementRef(elementId)))
    }

    /**
     * Find all elements matching selector.
     * POST /session/{sessionId}/selectors/elements
     */
    @PostMapping("/elements", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun findElements(
        @PathVariable sessionId: String,
        @RequestBody request: SelectorRequest
    ): ResponseEntity<Any> {
        logger.debug("Session {} finding elements by selector: {}", sessionId, request.selector)
        
        if (!sessionService.sessionExists(sessionId)) {
            return notFound("invalid session id", "Session not found: $sessionId")
        }
        
        require(request.selector.isNotBlank()) { "Selector must not be blank" }
        
        // Mock: return a single element for simplicity
        val elementId = sessionService.storeElement(sessionId, request.selector)
            ?: return notFound("invalid session id", "Session not found: $sessionId")
        
        return ResponseEntity.ok(ElementRefsResponse(value = listOf(ElementRef(elementId))))
    }

    /**
     * Click element by selector.
     * POST /session/{sessionId}/selectors/click
     */
    @PostMapping("/click", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun click(
        @PathVariable sessionId: String,
        @RequestBody request: SelectorRequest
    ): ResponseEntity<Any> {
        logger.debug("Session {} clicking selector: {}", sessionId, request.selector)
        
        if (!sessionService.sessionExists(sessionId)) {
            return notFound("invalid session id", "Session not found: $sessionId")
        }
        
        require(request.selector.isNotBlank()) { "Selector must not be blank" }
        
        // Mock: store element and return success
        sessionService.storeElement(sessionId, request.selector)
        
        return ResponseEntity.ok(valueResponse(null))
    }

    /**
     * Fill input by selector.
     * POST /session/{sessionId}/selectors/fill
     */
    @PostMapping("/fill", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun fill(
        @PathVariable sessionId: String,
        @RequestBody request: SelectorFillRequest
    ): ResponseEntity<Any> {
        logger.debug("Session {} filling selector: {} with value", sessionId, request.selector)
        
        if (!sessionService.sessionExists(sessionId)) {
            return notFound("invalid session id", "Session not found: $sessionId")
        }
        
        require(request.selector.isNotBlank()) { "Selector must not be blank" }
        
        // Mock: store element and return success
        sessionService.storeElement(sessionId, request.selector)
        
        return ResponseEntity.ok(valueResponse(null))
    }

    /**
     * Press key on element by selector.
     * POST /session/{sessionId}/selectors/press
     */
    @PostMapping("/press", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun press(
        @PathVariable sessionId: String,
        @RequestBody request: SelectorPressRequest
    ): ResponseEntity<Any> {
        logger.debug("Session {} pressing key {} on selector: {}", sessionId, request.key, request.selector)
        
        if (!sessionService.sessionExists(sessionId)) {
            return notFound("invalid session id", "Session not found: $sessionId")
        }
        
        require(request.selector.isNotBlank()) { "Selector must not be blank" }
        require(request.key.isNotBlank()) { "Key must not be blank" }
        
        // Mock: store element and return success
        sessionService.storeElement(sessionId, request.selector)
        
        return ResponseEntity.ok(valueResponse(null))
    }

    /**
     * Get outer HTML of element by selector.
     * POST /session/{sessionId}/selectors/outerHtml
     */
    @PostMapping("/outerHtml", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun outerHtml(
        @PathVariable sessionId: String,
        @RequestBody request: SelectorRequest
    ): ResponseEntity<Any> {
        logger.debug("Session {} getting outerHtml for selector: {}", sessionId, request.selector)
        
        if (!sessionService.sessionExists(sessionId)) {
            return notFound("invalid session id", "Session not found: $sessionId")
        }
        
        require(request.selector.isNotBlank()) { "Selector must not be blank" }
        
        // Mock: return placeholder HTML
        val mockHtml = "<div class=\"mock-element\">${request.selector}</div>"
        
        return ResponseEntity.ok(valueResponse(mockHtml))
    }

    /**
     * Take screenshot of element by selector.
     * POST /session/{sessionId}/selectors/screenshot
     */
    @PostMapping("/screenshot", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun screenshot(
        @PathVariable sessionId: String,
        @RequestBody request: SelectorRequest
    ): ResponseEntity<Any> {
        logger.debug("Session {} taking screenshot for selector: {}", sessionId, request.selector)
        
        if (!sessionService.sessionExists(sessionId)) {
            return notFound("invalid session id", "Session not found: $sessionId")
        }
        
        require(request.selector.isNotBlank()) { "Selector must not be blank" }
        
        // Mock: return placeholder base64 string (1x1 transparent PNG)
        val mockScreenshot = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg=="
        
        return ResponseEntity.ok(valueResponse(mockScreenshot))
    }

    private fun notFound(error: String, message: String): ResponseEntity<Any> {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(errorResponse(error, message))
    }
}
