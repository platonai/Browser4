package ai.platon.pulsar.rest.api.webdriver.controller

import ai.platon.pulsar.rest.api.webdriver.dto.*
import ai.platon.pulsar.rest.api.webdriver.store.InMemoryStore
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.util.UUID

/**
 * Controller for CSS selector-based element operations.
 */
@RestController
@CrossOrigin
@RequestMapping("/session/{sessionId}/selectors")
class SelectorController(
    private val store: InMemoryStore
) {
    private val logger = LoggerFactory.getLogger(SelectorController::class.java)

    /**
     * Check if element exists by selector.
     */
    @PostMapping("/exists")
    fun exists(
        @PathVariable sessionId: String,
        @RequestBody request: SelectorRef,
        @RequestHeader(value = "X-Request-Id", required = false) requestId: String?
    ): ResponseEntity<*> {
        val reqId = requestId ?: UUID.randomUUID().toString()
        logger.debug("Checking existence for selector '{}' in session: {}, X-Request-Id: {}", 
            request.selector, sessionId, reqId)

        store.getSession(sessionId)
            ?: return notFoundResponse(sessionId, reqId)

        // Mock: always return true for non-empty selectors
        val exists = request.selector.isNotBlank()

        return ResponseEntity.ok()
            .header("X-Request-Id", reqId)
            .body(WebDriverResponse(exists))
    }

    /**
     * Wait for element by selector.
     */
    @PostMapping("/waitFor")
    fun waitFor(
        @PathVariable sessionId: String,
        @RequestBody request: SelectorWaitRequest,
        @RequestHeader(value = "X-Request-Id", required = false) requestId: String?
    ): ResponseEntity<*> {
        val reqId = requestId ?: UUID.randomUUID().toString()
        logger.debug("Waiting for selector '{}' in session: {}, timeout: {}ms, X-Request-Id: {}", 
            request.selector, sessionId, request.timeout, reqId)

        store.getSession(sessionId)
            ?: return notFoundResponse(sessionId, reqId)

        // Mock: simulate wait and return true
        return ResponseEntity.ok()
            .header("X-Request-Id", reqId)
            .body(WebDriverResponse(true))
    }

    /**
     * Find single element by selector.
     */
    @PostMapping("/element")
    fun findElement(
        @PathVariable sessionId: String,
        @RequestBody request: SelectorRef,
        @RequestHeader(value = "X-Request-Id", required = false) requestId: String?
    ): ResponseEntity<*> {
        val reqId = requestId ?: UUID.randomUUID().toString()
        logger.debug("Finding element by selector '{}' in session: {}, X-Request-Id: {}", 
            request.selector, sessionId, reqId)

        store.getSession(sessionId)
            ?: return notFoundResponse(sessionId, reqId)

        val element = store.createElement(sessionId, request.selector, "css selector", request.selector)

        return ResponseEntity.ok()
            .header("X-Request-Id", reqId)
            .body(WebDriverResponse(ElementRef(element.elementId)))
    }

    /**
     * Find multiple elements by selector.
     */
    @PostMapping("/elements")
    fun findElements(
        @PathVariable sessionId: String,
        @RequestBody request: SelectorRef,
        @RequestHeader(value = "X-Request-Id", required = false) requestId: String?
    ): ResponseEntity<*> {
        val reqId = requestId ?: UUID.randomUUID().toString()
        logger.debug("Finding elements by selector '{}' in session: {}, X-Request-Id: {}", 
            request.selector, sessionId, reqId)

        store.getSession(sessionId)
            ?: return notFoundResponse(sessionId, reqId)

        // Mock: return a single element for simplicity
        val element = store.createElement(sessionId, request.selector, "css selector", request.selector)

        return ResponseEntity.ok()
            .header("X-Request-Id", reqId)
            .body(WebDriverResponse(listOf(ElementRef(element.elementId))))
    }

    /**
     * Click element by selector.
     */
    @PostMapping("/click")
    fun click(
        @PathVariable sessionId: String,
        @RequestBody request: SelectorRef,
        @RequestHeader(value = "X-Request-Id", required = false) requestId: String?
    ): ResponseEntity<*> {
        val reqId = requestId ?: UUID.randomUUID().toString()
        logger.debug("Clicking element by selector '{}' in session: {}, X-Request-Id: {}", 
            request.selector, sessionId, reqId)

        store.getSession(sessionId)
            ?: return notFoundResponse(sessionId, reqId)

        // Mock: return success
        return ResponseEntity.ok()
            .header("X-Request-Id", reqId)
            .body(WebDriverResponse<Any?>(null))
    }

    /**
     * Fill element by selector.
     */
    @PostMapping("/fill")
    fun fill(
        @PathVariable sessionId: String,
        @RequestBody request: SelectorFillRequest,
        @RequestHeader(value = "X-Request-Id", required = false) requestId: String?
    ): ResponseEntity<*> {
        val reqId = requestId ?: UUID.randomUUID().toString()
        logger.debug("Filling element by selector '{}' with value in session: {}, X-Request-Id: {}", 
            request.selector, sessionId, reqId)

        store.getSession(sessionId)
            ?: return notFoundResponse(sessionId, reqId)

        // Mock: return success
        return ResponseEntity.ok()
            .header("X-Request-Id", reqId)
            .body(WebDriverResponse<Any?>(null))
    }

    /**
     * Press key on element by selector.
     */
    @PostMapping("/press")
    fun press(
        @PathVariable sessionId: String,
        @RequestBody request: SelectorPressRequest,
        @RequestHeader(value = "X-Request-Id", required = false) requestId: String?
    ): ResponseEntity<*> {
        val reqId = requestId ?: UUID.randomUUID().toString()
        logger.debug("Pressing key '{}' on element by selector '{}' in session: {}, X-Request-Id: {}", 
            request.key, request.selector, sessionId, reqId)

        store.getSession(sessionId)
            ?: return notFoundResponse(sessionId, reqId)

        // Mock: return success
        return ResponseEntity.ok()
            .header("X-Request-Id", reqId)
            .body(WebDriverResponse<Any?>(null))
    }

    /**
     * Get outer HTML by selector.
     */
    @PostMapping("/outerHtml")
    fun outerHtml(
        @PathVariable sessionId: String,
        @RequestBody request: SelectorRef,
        @RequestHeader(value = "X-Request-Id", required = false) requestId: String?
    ): ResponseEntity<*> {
        val reqId = requestId ?: UUID.randomUUID().toString()
        logger.debug("Getting outer HTML for selector '{}' in session: {}, X-Request-Id: {}", 
            request.selector, sessionId, reqId)

        store.getSession(sessionId)
            ?: return notFoundResponse(sessionId, reqId)

        // Mock: return placeholder HTML
        val mockHtml = "<div class=\"mock-element\">${request.selector}</div>"

        return ResponseEntity.ok()
            .header("X-Request-Id", reqId)
            .body(WebDriverResponse(mockHtml))
    }

    /**
     * Take screenshot of element by selector.
     */
    @PostMapping("/screenshot")
    fun screenshot(
        @PathVariable sessionId: String,
        @RequestBody request: SelectorRef,
        @RequestHeader(value = "X-Request-Id", required = false) requestId: String?
    ): ResponseEntity<*> {
        val reqId = requestId ?: UUID.randomUUID().toString()
        logger.debug("Taking screenshot for selector '{}' in session: {}, X-Request-Id: {}", 
            request.selector, sessionId, reqId)

        store.getSession(sessionId)
            ?: return notFoundResponse(sessionId, reqId)

        // Mock: return placeholder base64 (1x1 transparent PNG)
        val mockBase64 = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg=="

        return ResponseEntity.ok()
            .header("X-Request-Id", reqId)
            .body(WebDriverResponse(mockBase64))
    }

    private fun notFoundResponse(sessionId: String, reqId: String): ResponseEntity<WebDriverResponse<ErrorValue>> {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .header("X-Request-Id", reqId)
            .body(WebDriverResponse(ErrorValue("no such session", "Session $sessionId not found")))
    }
}
