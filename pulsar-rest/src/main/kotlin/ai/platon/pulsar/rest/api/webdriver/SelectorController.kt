package ai.platon.pulsar.rest.api.webdriver

import ai.platon.pulsar.rest.api.dto.*
import ai.platon.pulsar.rest.api.store.InMemoryStore
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.util.Base64
import java.util.UUID

/**
 * Selector-first element operations controller (Browser4 extensions).
 * Provides convenient selector-based operations that are not part of the standard WebDriver spec.
 */
@RestController
@CrossOrigin
@RequestMapping("/session/{sessionId}/selectors")
class SelectorController(
    private val store: InMemoryStore
) {
    private val logger = LoggerFactory.getLogger(SelectorController::class.java)

    /**
     * Checks if an element matching the selector exists.
     *
     * @param sessionId The session ID
     * @param request The selector reference
     * @return Whether the element exists
     */
    @PostMapping("/exists")
    fun selectorExists(
        @PathVariable sessionId: String,
        @RequestBody request: SelectorRef,
        @RequestHeader(value = "X-Request-Id", required = false) requestId: String?
    ): ResponseEntity<*> {
        val effectiveRequestId = requestId ?: UUID.randomUUID().toString()
        logger.debug("Session {} checking selector exists: {}, X-Request-Id: {}", sessionId, request.selector, effectiveRequestId)

        if (!store.sessionExists(sessionId)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .header("X-Request-Id", effectiveRequestId)
                .body(ErrorResponse(ErrorValue("no such session", "Session $sessionId not found")))
        }

        // In mock implementation, selector always exists
        return ResponseEntity.ok()
            .header("X-Request-Id", effectiveRequestId)
            .body(ValueResponse(SelectorExistsValue(exists = true)))
    }

    /**
     * Waits for a selector to appear.
     *
     * @param sessionId The session ID
     * @param request The wait request with selector and timeout
     * @return Whether the element was found and its ID
     */
    @PostMapping("/waitFor")
    fun selectorWaitFor(
        @PathVariable sessionId: String,
        @RequestBody request: SelectorWaitRequest,
        @RequestHeader(value = "X-Request-Id", required = false) requestId: String?
    ): ResponseEntity<*> {
        val effectiveRequestId = requestId ?: UUID.randomUUID().toString()
        logger.debug("Session {} waiting for selector: {}, timeout: {}, X-Request-Id: {}", 
            sessionId, request.selector, request.timeout, effectiveRequestId)

        val element = store.getOrCreateElement(sessionId, request.selector)
            ?: return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .header("X-Request-Id", effectiveRequestId)
                .body(ErrorResponse(ErrorValue("no such session", "Session $sessionId not found")))

        // In mock implementation, element is always found immediately
        return ResponseEntity.ok()
            .header("X-Request-Id", effectiveRequestId)
            .body(ValueResponse(SelectorWaitValue(found = true, elementId = element.elementId)))
    }

    /**
     * Finds a single element by selector.
     *
     * @param sessionId The session ID
     * @param request The selector reference
     * @return The element reference
     */
    @PostMapping("/element")
    fun selectorFindElement(
        @PathVariable sessionId: String,
        @RequestBody request: SelectorRef,
        @RequestHeader(value = "X-Request-Id", required = false) requestId: String?
    ): ResponseEntity<*> {
        val effectiveRequestId = requestId ?: UUID.randomUUID().toString()
        logger.debug("Session {} finding element by selector: {}, X-Request-Id: {}", sessionId, request.selector, effectiveRequestId)

        val element = store.getOrCreateElement(sessionId, request.selector)
            ?: return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .header("X-Request-Id", effectiveRequestId)
                .body(ErrorResponse(ErrorValue("no such session", "Session $sessionId not found")))

        return ResponseEntity.ok()
            .header("X-Request-Id", effectiveRequestId)
            .body(ValueResponse(ElementRef(webDriverElementId = element.elementId)))
    }

    /**
     * Finds all elements matching a selector.
     *
     * @param sessionId The session ID
     * @param request The selector reference
     * @return List of element references
     */
    @PostMapping("/elements")
    fun selectorFindElements(
        @PathVariable sessionId: String,
        @RequestBody request: SelectorRef,
        @RequestHeader(value = "X-Request-Id", required = false) requestId: String?
    ): ResponseEntity<*> {
        val effectiveRequestId = requestId ?: UUID.randomUUID().toString()
        logger.debug("Session {} finding elements by selector: {}, X-Request-Id: {}", sessionId, request.selector, effectiveRequestId)

        val element = store.getOrCreateElement(sessionId, request.selector)
            ?: return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .header("X-Request-Id", effectiveRequestId)
                .body(ErrorResponse(ErrorValue("no such session", "Session $sessionId not found")))

        // In mock implementation, return single element in a list
        return ResponseEntity.ok()
            .header("X-Request-Id", effectiveRequestId)
            .body(ValueResponse(listOf(ElementRef(webDriverElementId = element.elementId))))
    }

    /**
     * Clicks an element by selector.
     *
     * @param sessionId The session ID
     * @param request The selector reference
     * @return Success or error
     */
    @PostMapping("/click")
    fun selectorClick(
        @PathVariable sessionId: String,
        @RequestBody request: SelectorRef,
        @RequestHeader(value = "X-Request-Id", required = false) requestId: String?
    ): ResponseEntity<*> {
        val effectiveRequestId = requestId ?: UUID.randomUUID().toString()
        logger.debug("Session {} clicking selector: {}, X-Request-Id: {}", sessionId, request.selector, effectiveRequestId)

        if (!store.sessionExists(sessionId)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .header("X-Request-Id", effectiveRequestId)
                .body(ErrorResponse(ErrorValue("no such session", "Session $sessionId not found")))
        }

        // In mock implementation, click is always successful
        store.getOrCreateElement(sessionId, request.selector)

        return ResponseEntity.ok()
            .header("X-Request-Id", effectiveRequestId)
            .body(ValueResponse<Any?>(null))
    }

    /**
     * Fills an element by selector with a value.
     *
     * @param sessionId The session ID
     * @param request The fill request with selector and value
     * @return Success or error
     */
    @PostMapping("/fill")
    fun selectorFill(
        @PathVariable sessionId: String,
        @RequestBody request: SelectorFillRequest,
        @RequestHeader(value = "X-Request-Id", required = false) requestId: String?
    ): ResponseEntity<*> {
        val effectiveRequestId = requestId ?: UUID.randomUUID().toString()
        logger.debug("Session {} filling selector: {} with value, X-Request-Id: {}", sessionId, request.selector, effectiveRequestId)

        val element = store.getOrCreateElement(sessionId, request.selector)
            ?: return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .header("X-Request-Id", effectiveRequestId)
                .body(ErrorResponse(ErrorValue("no such session", "Session $sessionId not found")))

        // In mock implementation, update element text
        element.text = request.value

        return ResponseEntity.ok()
            .header("X-Request-Id", effectiveRequestId)
            .body(ValueResponse<Any?>(null))
    }

    /**
     * Presses a key on an element by selector.
     *
     * @param sessionId The session ID
     * @param request The press request with selector and key
     * @return Success or error
     */
    @PostMapping("/press")
    fun selectorPress(
        @PathVariable sessionId: String,
        @RequestBody request: SelectorPressRequest,
        @RequestHeader(value = "X-Request-Id", required = false) requestId: String?
    ): ResponseEntity<*> {
        val effectiveRequestId = requestId ?: UUID.randomUUID().toString()
        logger.debug("Session {} pressing key {} on selector: {}, X-Request-Id: {}", sessionId, request.key, request.selector, effectiveRequestId)

        if (!store.sessionExists(sessionId)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .header("X-Request-Id", effectiveRequestId)
                .body(ErrorResponse(ErrorValue("no such session", "Session $sessionId not found")))
        }

        // In mock implementation, key press is always successful
        store.getOrCreateElement(sessionId, request.selector)

        return ResponseEntity.ok()
            .header("X-Request-Id", effectiveRequestId)
            .body(ValueResponse<Any?>(null))
    }

    /**
     * Gets the outer HTML of an element by selector.
     *
     * @param sessionId The session ID
     * @param request The selector reference
     * @return The outer HTML string
     */
    @PostMapping("/outerHtml")
    fun selectorOuterHtml(
        @PathVariable sessionId: String,
        @RequestBody request: SelectorRef,
        @RequestHeader(value = "X-Request-Id", required = false) requestId: String?
    ): ResponseEntity<*> {
        val effectiveRequestId = requestId ?: UUID.randomUUID().toString()
        logger.debug("Session {} getting outer HTML for selector: {}, X-Request-Id: {}", sessionId, request.selector, effectiveRequestId)

        val element = store.getOrCreateElement(sessionId, request.selector)
            ?: return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .header("X-Request-Id", effectiveRequestId)
                .body(ErrorResponse(ErrorValue("no such session", "Session $sessionId not found")))

        // In mock implementation, return mock HTML
        val mockHtml = "<div id=\"${element.elementId}\">${element.text}</div>"

        return ResponseEntity.ok()
            .header("X-Request-Id", effectiveRequestId)
            .body(ValueResponse(mockHtml))
    }

    /**
     * Takes a screenshot of an element by selector.
     *
     * @param sessionId The session ID
     * @param request The selector reference
     * @return Base64-encoded screenshot
     */
    @PostMapping("/screenshot")
    fun selectorScreenshot(
        @PathVariable sessionId: String,
        @RequestBody request: SelectorRef,
        @RequestHeader(value = "X-Request-Id", required = false) requestId: String?
    ): ResponseEntity<*> {
        val effectiveRequestId = requestId ?: UUID.randomUUID().toString()
        logger.debug("Session {} taking screenshot for selector: {}, X-Request-Id: {}", sessionId, request.selector, effectiveRequestId)

        if (!store.sessionExists(sessionId)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .header("X-Request-Id", effectiveRequestId)
                .body(ErrorResponse(ErrorValue("no such session", "Session $sessionId not found")))
        }

        store.getOrCreateElement(sessionId, request.selector)

        // In mock implementation, return a minimal valid PNG (1x1 transparent pixel)
        val mockPng = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
            0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
            0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15.toByte(), 0xC4.toByte(),
            0x89.toByte(), 0x00, 0x00, 0x00, 0x0A, 0x49, 0x44, 0x41,
            0x54, 0x78, 0x9C.toByte(), 0x63, 0x00, 0x01, 0x00, 0x00,
            0x05, 0x00, 0x01, 0x0D, 0x0A, 0x2D, 0xB4.toByte(), 0x00,
            0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44, 0xAE.toByte(),
            0x42, 0x60, 0x82.toByte()
        )

        return ResponseEntity.ok()
            .header("X-Request-Id", effectiveRequestId)
            .body(ValueResponse(Base64.getEncoder().encodeToString(mockPng)))
    }
}
