package ai.platon.pulsar.rest.api.webdriver

import ai.platon.pulsar.rest.api.dto.*
import ai.platon.pulsar.rest.api.store.InMemoryStore
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.util.UUID

/**
 * Standard WebDriver element operations controller.
 * Handles element finding, clicking, and attribute retrieval.
 */
@RestController
@CrossOrigin
@RequestMapping("/session/{sessionId}")
class ElementController(
    private val store: InMemoryStore
) {
    private val logger = LoggerFactory.getLogger(ElementController::class.java)

    /**
     * Finds a single element using WebDriver locator strategy.
     *
     * @param sessionId The session ID
     * @param request The find element request with using and value
     * @return The element reference
     */
    @PostMapping("/element")
    fun findElement(
        @PathVariable sessionId: String,
        @RequestBody request: FindElementRequest,
        @RequestHeader(value = "X-Request-Id", required = false) requestId: String?
    ): ResponseEntity<*> {
        val effectiveRequestId = requestId ?: UUID.randomUUID().toString()
        logger.debug("Session {} finding element using {}: {}, X-Request-Id: {}", 
            sessionId, request.using, request.value, effectiveRequestId)

        // Convert WebDriver locator strategy to CSS selector
        val selector = convertToSelector(request.using, request.value)

        val element = store.getOrCreateElement(sessionId, selector)
            ?: return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .header("X-Request-Id", effectiveRequestId)
                .body(ErrorResponse(ErrorValue("no such session", "Session $sessionId not found")))

        return ResponseEntity.ok()
            .header("X-Request-Id", effectiveRequestId)
            .body(ValueResponse(ElementRef(webDriverElementId = element.elementId)))
    }

    /**
     * Finds all elements matching a WebDriver locator strategy.
     *
     * @param sessionId The session ID
     * @param request The find element request with using and value
     * @return List of element references
     */
    @PostMapping("/elements")
    fun findElements(
        @PathVariable sessionId: String,
        @RequestBody request: FindElementRequest,
        @RequestHeader(value = "X-Request-Id", required = false) requestId: String?
    ): ResponseEntity<*> {
        val effectiveRequestId = requestId ?: UUID.randomUUID().toString()
        logger.debug("Session {} finding elements using {}: {}, X-Request-Id: {}", 
            sessionId, request.using, request.value, effectiveRequestId)

        // Convert WebDriver locator strategy to CSS selector
        val selector = convertToSelector(request.using, request.value)

        val element = store.getOrCreateElement(sessionId, selector)
            ?: return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .header("X-Request-Id", effectiveRequestId)
                .body(ErrorResponse(ErrorValue("no such session", "Session $sessionId not found")))

        // In mock implementation, return single element in a list
        return ResponseEntity.ok()
            .header("X-Request-Id", effectiveRequestId)
            .body(ValueResponse(listOf(ElementRef(webDriverElementId = element.elementId))))
    }

    /**
     * Clicks an element by element ID.
     *
     * @param sessionId The session ID
     * @param elementId The element ID
     * @return Success or error
     */
    @PostMapping("/element/{elementId}/click")
    fun elementClick(
        @PathVariable sessionId: String,
        @PathVariable elementId: String,
        @RequestHeader(value = "X-Request-Id", required = false) requestId: String?
    ): ResponseEntity<*> {
        val effectiveRequestId = requestId ?: UUID.randomUUID().toString()
        logger.debug("Session {} clicking element {}, X-Request-Id: {}", sessionId, elementId, effectiveRequestId)

        if (!store.sessionExists(sessionId)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .header("X-Request-Id", effectiveRequestId)
                .body(ErrorResponse(ErrorValue("no such session", "Session $sessionId not found")))
        }

        if (store.getElement(sessionId, elementId) == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .header("X-Request-Id", effectiveRequestId)
                .body(ErrorResponse(ErrorValue("no such element", "Element $elementId not found")))
        }

        // In mock implementation, click is always successful
        return ResponseEntity.ok()
            .header("X-Request-Id", effectiveRequestId)
            .body(ValueResponse<Any?>(null))
    }

    /**
     * Sends keys to an element.
     *
     * @param sessionId The session ID
     * @param elementId The element ID
     * @param request The send keys request
     * @return Success or error
     */
    @PostMapping("/element/{elementId}/value")
    fun elementSendKeys(
        @PathVariable sessionId: String,
        @PathVariable elementId: String,
        @RequestBody request: SendKeysRequest,
        @RequestHeader(value = "X-Request-Id", required = false) requestId: String?
    ): ResponseEntity<*> {
        val effectiveRequestId = requestId ?: UUID.randomUUID().toString()
        logger.debug("Session {} sending keys to element {}, X-Request-Id: {}", sessionId, elementId, effectiveRequestId)

        if (!store.sessionExists(sessionId)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .header("X-Request-Id", effectiveRequestId)
                .body(ErrorResponse(ErrorValue("no such session", "Session $sessionId not found")))
        }

        val element = store.getElement(sessionId, elementId)
            ?: return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .header("X-Request-Id", effectiveRequestId)
                .body(ErrorResponse(ErrorValue("no such element", "Element $elementId not found")))

        // In mock implementation, update element text
        val text = request.text ?: request.value?.joinToString("") ?: ""
        element.text = element.text + text

        return ResponseEntity.ok()
            .header("X-Request-Id", effectiveRequestId)
            .body(ValueResponse<Any?>(null))
    }

    /**
     * Gets an element attribute.
     *
     * @param sessionId The session ID
     * @param elementId The element ID
     * @param name The attribute name
     * @return The attribute value or null
     */
    @GetMapping("/element/{elementId}/attribute/{name}")
    fun getElementAttribute(
        @PathVariable sessionId: String,
        @PathVariable elementId: String,
        @PathVariable name: String,
        @RequestHeader(value = "X-Request-Id", required = false) requestId: String?
    ): ResponseEntity<*> {
        val effectiveRequestId = requestId ?: UUID.randomUUID().toString()
        logger.debug("Session {} getting attribute {} from element {}, X-Request-Id: {}", 
            sessionId, name, elementId, effectiveRequestId)

        if (!store.sessionExists(sessionId)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .header("X-Request-Id", effectiveRequestId)
                .body(ErrorResponse(ErrorValue("no such session", "Session $sessionId not found")))
        }

        val element = store.getElement(sessionId, elementId)
            ?: return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .header("X-Request-Id", effectiveRequestId)
                .body(ErrorResponse(ErrorValue("no such element", "Element $elementId not found")))

        val value = element.attributes[name]

        return ResponseEntity.ok()
            .header("X-Request-Id", effectiveRequestId)
            .body(ValueResponse(value))
    }

    /**
     * Gets element text content.
     *
     * @param sessionId The session ID
     * @param elementId The element ID
     * @return The element text
     */
    @GetMapping("/element/{elementId}/text")
    fun getElementText(
        @PathVariable sessionId: String,
        @PathVariable elementId: String,
        @RequestHeader(value = "X-Request-Id", required = false) requestId: String?
    ): ResponseEntity<*> {
        val effectiveRequestId = requestId ?: UUID.randomUUID().toString()
        logger.debug("Session {} getting text from element {}, X-Request-Id: {}", sessionId, elementId, effectiveRequestId)

        if (!store.sessionExists(sessionId)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .header("X-Request-Id", effectiveRequestId)
                .body(ErrorResponse(ErrorValue("no such session", "Session $sessionId not found")))
        }

        val element = store.getElement(sessionId, elementId)
            ?: return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .header("X-Request-Id", effectiveRequestId)
                .body(ErrorResponse(ErrorValue("no such element", "Element $elementId not found")))

        return ResponseEntity.ok()
            .header("X-Request-Id", effectiveRequestId)
            .body(ValueResponse(element.text))
    }

    /**
     * Converts WebDriver locator strategy to CSS selector.
     */
    private fun convertToSelector(using: String, value: String): String {
        return when (using) {
            "css selector" -> value
            "id" -> "#$value"
            "name" -> "[name=\"$value\"]"
            "tag name" -> value
            "class name" -> ".$value"
            "link text" -> "a:contains(\"$value\")"
            "partial link text" -> "a:contains(\"$value\")"
            "xpath" -> "xpath:$value" // Store xpath as-is with prefix
            else -> value
        }
    }
}
