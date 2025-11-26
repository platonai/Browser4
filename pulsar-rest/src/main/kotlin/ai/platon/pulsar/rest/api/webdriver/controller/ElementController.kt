package ai.platon.pulsar.rest.api.webdriver.controller

import ai.platon.pulsar.rest.api.webdriver.dto.*
import ai.platon.pulsar.rest.api.webdriver.store.InMemoryStore
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.util.UUID

/**
 * Controller for element reference-based operations.
 */
@RestController
@CrossOrigin
@RequestMapping("/session/{sessionId}")
class ElementController(
    private val store: InMemoryStore
) {
    private val logger = LoggerFactory.getLogger(ElementController::class.java)

    /**
     * Find single element using W3C WebDriver strategy.
     */
    @PostMapping("/element")
    fun findElement(
        @PathVariable sessionId: String,
        @RequestBody request: FindElementRequest,
        @RequestHeader(value = "X-Request-Id", required = false) requestId: String?
    ): ResponseEntity<*> {
        val reqId = requestId ?: UUID.randomUUID().toString()
        logger.debug("Finding element using '{}' = '{}' in session: {}, X-Request-Id: {}", 
            request.using, request.value, sessionId, reqId)

        store.getSession(sessionId)
            ?: return sessionNotFoundResponse(sessionId, reqId)

        val selector = convertToSelector(request.using, request.value)
        val element = store.createElement(sessionId, selector, request.using, request.value)

        return ResponseEntity.ok()
            .header("X-Request-Id", reqId)
            .body(WebDriverResponse(ElementRef(element.elementId)))
    }

    /**
     * Find multiple elements using W3C WebDriver strategy.
     */
    @PostMapping("/elements")
    fun findElements(
        @PathVariable sessionId: String,
        @RequestBody request: FindElementRequest,
        @RequestHeader(value = "X-Request-Id", required = false) requestId: String?
    ): ResponseEntity<*> {
        val reqId = requestId ?: UUID.randomUUID().toString()
        logger.debug("Finding elements using '{}' = '{}' in session: {}, X-Request-Id: {}", 
            request.using, request.value, sessionId, reqId)

        store.getSession(sessionId)
            ?: return sessionNotFoundResponse(sessionId, reqId)

        // Mock: return a single element for simplicity
        val selector = convertToSelector(request.using, request.value)
        val element = store.createElement(sessionId, selector, request.using, request.value)

        return ResponseEntity.ok()
            .header("X-Request-Id", reqId)
            .body(WebDriverResponse(listOf(ElementRef(element.elementId))))
    }

    /**
     * Click element by reference.
     */
    @PostMapping("/element/{elementId}/click")
    fun clickElement(
        @PathVariable sessionId: String,
        @PathVariable elementId: String,
        @RequestHeader(value = "X-Request-Id", required = false) requestId: String?
    ): ResponseEntity<*> {
        val reqId = requestId ?: UUID.randomUUID().toString()
        logger.debug("Clicking element {} in session: {}, X-Request-Id: {}", elementId, sessionId, reqId)

        store.getSession(sessionId)
            ?: return sessionNotFoundResponse(sessionId, reqId)

        store.getElementForSession(sessionId, elementId)
            ?: return elementNotFoundResponse(elementId, reqId)

        // Mock: return success
        return ResponseEntity.ok()
            .header("X-Request-Id", reqId)
            .body(WebDriverResponse<Any?>(null))
    }

    /**
     * Send keys to element.
     */
    @PostMapping("/element/{elementId}/value")
    fun sendKeysToElement(
        @PathVariable sessionId: String,
        @PathVariable elementId: String,
        @RequestBody request: ElementValueRequest,
        @RequestHeader(value = "X-Request-Id", required = false) requestId: String?
    ): ResponseEntity<*> {
        val reqId = requestId ?: UUID.randomUUID().toString()
        logger.debug("Sending keys to element {} in session: {}, X-Request-Id: {}", elementId, sessionId, reqId)

        store.getSession(sessionId)
            ?: return sessionNotFoundResponse(sessionId, reqId)

        val element = store.getElementForSession(sessionId, elementId)
            ?: return elementNotFoundResponse(elementId, reqId)

        // Mock: store the value as text
        val text = request.text ?: request.value?.joinToString("") ?: ""
        element.text = text

        return ResponseEntity.ok()
            .header("X-Request-Id", reqId)
            .body(WebDriverResponse<Any?>(null))
    }

    /**
     * Get element attribute.
     */
    @GetMapping("/element/{elementId}/attribute/{name}")
    fun getElementAttribute(
        @PathVariable sessionId: String,
        @PathVariable elementId: String,
        @PathVariable name: String,
        @RequestHeader(value = "X-Request-Id", required = false) requestId: String?
    ): ResponseEntity<*> {
        val reqId = requestId ?: UUID.randomUUID().toString()
        logger.debug("Getting attribute '{}' from element {} in session: {}, X-Request-Id: {}", 
            name, elementId, sessionId, reqId)

        store.getSession(sessionId)
            ?: return sessionNotFoundResponse(sessionId, reqId)

        val element = store.getElementForSession(sessionId, elementId)
            ?: return elementNotFoundResponse(elementId, reqId)

        // Mock: return attribute value from stored map or null
        val value = element.attributes[name]

        return ResponseEntity.ok()
            .header("X-Request-Id", reqId)
            .body(WebDriverResponse(value))
    }

    /**
     * Get element text.
     */
    @GetMapping("/element/{elementId}/text")
    fun getElementText(
        @PathVariable sessionId: String,
        @PathVariable elementId: String,
        @RequestHeader(value = "X-Request-Id", required = false) requestId: String?
    ): ResponseEntity<*> {
        val reqId = requestId ?: UUID.randomUUID().toString()
        logger.debug("Getting text from element {} in session: {}, X-Request-Id: {}", 
            elementId, sessionId, reqId)

        store.getSession(sessionId)
            ?: return sessionNotFoundResponse(sessionId, reqId)

        val element = store.getElementForSession(sessionId, elementId)
            ?: return elementNotFoundResponse(elementId, reqId)

        // Mock: return stored text or empty string
        val text = element.text ?: ""

        return ResponseEntity.ok()
            .header("X-Request-Id", reqId)
            .body(WebDriverResponse(text))
    }

    /**
     * Convert W3C WebDriver locator strategy to CSS selector.
     */
    private fun convertToSelector(using: String, value: String): String {
        return when (using) {
            FindElementRequest.STRATEGY_CSS -> value
            FindElementRequest.STRATEGY_ID -> "#$value"
            FindElementRequest.STRATEGY_NAME -> "[name=\"$value\"]"
            FindElementRequest.STRATEGY_TAG_NAME -> value
            FindElementRequest.STRATEGY_CLASS_NAME -> ".$value"
            FindElementRequest.STRATEGY_XPATH -> value // Store xpath as-is
            FindElementRequest.STRATEGY_LINK_TEXT -> "a:contains('$value')"
            FindElementRequest.STRATEGY_PARTIAL_LINK_TEXT -> "a:contains('$value')"
            else -> value
        }
    }

    private fun sessionNotFoundResponse(sessionId: String, reqId: String): ResponseEntity<WebDriverResponse<ErrorValue>> {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .header("X-Request-Id", reqId)
            .body(WebDriverResponse(ErrorValue("no such session", "Session $sessionId not found")))
    }

    private fun elementNotFoundResponse(elementId: String, reqId: String): ResponseEntity<WebDriverResponse<ErrorValue>> {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .header("X-Request-Id", reqId)
            .body(WebDriverResponse(ErrorValue("no such element", "Element $elementId not found")))
    }
}
