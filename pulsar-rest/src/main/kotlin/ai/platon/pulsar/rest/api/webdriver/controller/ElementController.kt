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
 * Controller for element operations by element ID.
 */
@RestController
@CrossOrigin
@RequestMapping(
    "/session/{sessionId}",
    produces = [MediaType.APPLICATION_JSON_VALUE]
)
class ElementController(
    private val store: InMemoryStore
) {
    private val logger = LoggerFactory.getLogger(ElementController::class.java)

    /**
     * Finds a single element using WebDriver locator strategy.
     */
    @PostMapping("/element", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun findElement(
        @PathVariable sessionId: String,
        @RequestBody request: FindElementRequest,
        response: HttpServletResponse
    ): ResponseEntity<Any> {
        logger.debug("Session {} finding element using {}: {}", sessionId, request.using, request.value)
        addRequestId(response)

        // Convert WebDriver strategy to selector
        val selector = convertToSelector(request.using, request.value)
        val element = store.getOrCreateElement(sessionId, selector, "css")
            ?: return notFound("session not found", "No active session with id $sessionId")

        return ResponseEntity.ok(ElementResponse(value = ElementRef(elementId = element.elementId)))
    }

    /**
     * Finds all elements using WebDriver locator strategy.
     */
    @PostMapping("/elements", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun findElements(
        @PathVariable sessionId: String,
        @RequestBody request: FindElementRequest,
        response: HttpServletResponse
    ): ResponseEntity<Any> {
        logger.debug("Session {} finding elements using {}: {}", sessionId, request.using, request.value)
        addRequestId(response)

        val selector = convertToSelector(request.using, request.value)
        val element = store.getOrCreateElement(sessionId, selector, "css")
            ?: return notFound("session not found", "No active session with id $sessionId")

        // Mock implementation returns a single element
        return ResponseEntity.ok(ElementsResponse(value = listOf(ElementRef(elementId = element.elementId))))
    }

    /**
     * Clicks an element by ID.
     */
    @PostMapping("/element/{elementId}/click")
    fun clickElement(
        @PathVariable sessionId: String,
        @PathVariable elementId: String,
        response: HttpServletResponse
    ): ResponseEntity<Any> {
        logger.debug("Session {} clicking element: {}", sessionId, elementId)
        addRequestId(response)

        if (!store.sessionExists(sessionId)) {
            return notFound("session not found", "No active session with id $sessionId")
        }

        val element = store.getElement(sessionId, elementId)
            ?: return notFound("no such element", "Element with id $elementId not found")

        // Mock implementation - just log the click
        logger.debug("Clicked element: {}", element.selector)
        return ResponseEntity.ok(WebDriverResponse<Any?>(value = null))
    }

    /**
     * Sends keys to an element by ID.
     */
    @PostMapping("/element/{elementId}/value", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun sendKeysToElement(
        @PathVariable sessionId: String,
        @PathVariable elementId: String,
        @RequestBody request: SendKeysRequest,
        response: HttpServletResponse
    ): ResponseEntity<Any> {
        logger.debug("Session {} sending keys to element: {}", sessionId, elementId)
        addRequestId(response)

        if (!store.sessionExists(sessionId)) {
            return notFound("session not found", "No active session with id $sessionId")
        }

        val element = store.getElement(sessionId, elementId)
            ?: return notFound("no such element", "Element with id $elementId not found")

        // Mock implementation - update element text
        element.text = request.text
        return ResponseEntity.ok(WebDriverResponse<Any?>(value = null))
    }

    /**
     * Gets an element attribute by name.
     */
    @GetMapping("/element/{elementId}/attribute/{name}")
    fun getElementAttribute(
        @PathVariable sessionId: String,
        @PathVariable elementId: String,
        @PathVariable name: String,
        response: HttpServletResponse
    ): ResponseEntity<Any> {
        logger.debug("Session {} getting attribute {} from element: {}", sessionId, name, elementId)
        addRequestId(response)

        if (!store.sessionExists(sessionId)) {
            return notFound("session not found", "No active session with id $sessionId")
        }

        val element = store.getElement(sessionId, elementId)
            ?: return notFound("no such element", "Element with id $elementId not found")

        // Mock implementation - return from attributes map or generate mock value
        val value = element.attributes[name] ?: "mock-$name-value"
        return ResponseEntity.ok(AttributeResponse(value = value))
    }

    /**
     * Gets an element's text content.
     */
    @GetMapping("/element/{elementId}/text")
    fun getElementText(
        @PathVariable sessionId: String,
        @PathVariable elementId: String,
        response: HttpServletResponse
    ): ResponseEntity<Any> {
        logger.debug("Session {} getting text from element: {}", sessionId, elementId)
        addRequestId(response)

        if (!store.sessionExists(sessionId)) {
            return notFound("session not found", "No active session with id $sessionId")
        }

        val element = store.getElement(sessionId, elementId)
            ?: return notFound("no such element", "Element with id $elementId not found")

        return ResponseEntity.ok(TextResponse(value = element.text))
    }

    /**
     * Converts WebDriver locator strategy to CSS selector.
     */
    private fun convertToSelector(using: String, value: String): String {
        return when (using) {
            "css selector" -> value
            "xpath" -> value
            "id" -> "#$value"
            "name" -> "[name=\"$value\"]"
            "class name" -> ".$value"
            "tag name" -> value
            else -> value
        }
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
