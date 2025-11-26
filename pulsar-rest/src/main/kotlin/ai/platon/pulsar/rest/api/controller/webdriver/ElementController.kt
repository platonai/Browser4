package ai.platon.pulsar.rest.api.controller.webdriver

import ai.platon.pulsar.rest.api.dto.*
import ai.platon.pulsar.rest.api.service.webdriver.WebDriverSessionService
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

/**
 * Standard WebDriver element operations controller.
 */
@RestController
@CrossOrigin
@RequestMapping(
    "/session/{sessionId}",
    produces = [MediaType.APPLICATION_JSON_VALUE]
)
class ElementController(
    private val sessionService: WebDriverSessionService
) {
    private val logger = LoggerFactory.getLogger(ElementController::class.java)

    /**
     * Find element using locator strategy.
     * POST /session/{sessionId}/element
     */
    @PostMapping("/element", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun findElement(
        @PathVariable sessionId: String,
        @RequestBody request: LocatorRequest
    ): ResponseEntity<Any> {
        logger.debug("Session {} finding element using {}: {}", sessionId, request.using, request.value)
        
        if (!sessionService.sessionExists(sessionId)) {
            return notFound("invalid session id", "Session not found: $sessionId")
        }
        
        require(request.using.isNotBlank()) { "Locator strategy must not be blank" }
        require(request.value.isNotBlank()) { "Locator value must not be blank" }
        
        // Convert locator to selector-like string for storage
        val selector = "${request.using}:${request.value}"
        val elementId = sessionService.storeElement(sessionId, selector)
            ?: return notFound("invalid session id", "Session not found: $sessionId")
        
        return ResponseEntity.ok(ElementRefResponse(value = ElementRef(elementId)))
    }

    /**
     * Find all elements using locator strategy.
     * POST /session/{sessionId}/elements
     */
    @PostMapping("/elements", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun findElements(
        @PathVariable sessionId: String,
        @RequestBody request: LocatorRequest
    ): ResponseEntity<Any> {
        logger.debug("Session {} finding elements using {}: {}", sessionId, request.using, request.value)
        
        if (!sessionService.sessionExists(sessionId)) {
            return notFound("invalid session id", "Session not found: $sessionId")
        }
        
        require(request.using.isNotBlank()) { "Locator strategy must not be blank" }
        require(request.value.isNotBlank()) { "Locator value must not be blank" }
        
        // Convert locator to selector-like string for storage
        val selector = "${request.using}:${request.value}"
        val elementId = sessionService.storeElement(sessionId, selector)
            ?: return notFound("invalid session id", "Session not found: $sessionId")
        
        // Mock: return a single element for simplicity
        return ResponseEntity.ok(ElementRefsResponse(value = listOf(ElementRef(elementId))))
    }

    /**
     * Click on element.
     * POST /session/{sessionId}/element/{elementId}/click
     */
    @PostMapping("/element/{elementId}/click")
    fun elementClick(
        @PathVariable sessionId: String,
        @PathVariable elementId: String
    ): ResponseEntity<Any> {
        logger.debug("Session {} clicking element: {}", sessionId, elementId)
        
        if (!sessionService.sessionExists(sessionId)) {
            return notFound("invalid session id", "Session not found: $sessionId")
        }
        
        if (!sessionService.elementExists(sessionId, elementId)) {
            return notFound("no such element", "Element not found: $elementId")
        }
        
        // Mock: return success
        return ResponseEntity.ok(valueResponse(null))
    }

    /**
     * Send keys to element.
     * POST /session/{sessionId}/element/{elementId}/value
     */
    @PostMapping("/element/{elementId}/value", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun elementSendKeys(
        @PathVariable sessionId: String,
        @PathVariable elementId: String,
        @RequestBody request: SendKeysRequest
    ): ResponseEntity<Any> {
        logger.debug("Session {} sending keys to element: {}", sessionId, elementId)
        
        if (!sessionService.sessionExists(sessionId)) {
            return notFound("invalid session id", "Session not found: $sessionId")
        }
        
        if (!sessionService.elementExists(sessionId, elementId)) {
            return notFound("no such element", "Element not found: $elementId")
        }
        
        // Mock: return success
        return ResponseEntity.ok(valueResponse(null))
    }

    /**
     * Get element attribute.
     * GET /session/{sessionId}/element/{elementId}/attribute/{name}
     */
    @GetMapping("/element/{elementId}/attribute/{name}")
    fun getElementAttribute(
        @PathVariable sessionId: String,
        @PathVariable elementId: String,
        @PathVariable name: String
    ): ResponseEntity<Any> {
        logger.debug("Session {} getting attribute {} for element: {}", sessionId, name, elementId)
        
        if (!sessionService.sessionExists(sessionId)) {
            return notFound("invalid session id", "Session not found: $sessionId")
        }
        
        if (!sessionService.elementExists(sessionId, elementId)) {
            return notFound("no such element", "Element not found: $elementId")
        }
        
        // Mock: return placeholder attribute value
        val mockValue = "mock-$name-value"
        return ResponseEntity.ok(valueResponse(mockValue))
    }

    /**
     * Get element text.
     * GET /session/{sessionId}/element/{elementId}/text
     */
    @GetMapping("/element/{elementId}/text")
    fun getElementText(
        @PathVariable sessionId: String,
        @PathVariable elementId: String
    ): ResponseEntity<Any> {
        logger.debug("Session {} getting text for element: {}", sessionId, elementId)
        
        if (!sessionService.sessionExists(sessionId)) {
            return notFound("invalid session id", "Session not found: $sessionId")
        }
        
        if (!sessionService.elementExists(sessionId, elementId)) {
            return notFound("no such element", "Element not found: $elementId")
        }
        
        // Mock: return placeholder text based on stored selector
        val selector = sessionService.getElementSelector(sessionId, elementId) ?: "unknown"
        val mockText = "Mock text for element: $selector"
        return ResponseEntity.ok(valueResponse(mockText))
    }

    private fun notFound(error: String, message: String): ResponseEntity<Any> {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(errorResponse(error, message))
    }
}
