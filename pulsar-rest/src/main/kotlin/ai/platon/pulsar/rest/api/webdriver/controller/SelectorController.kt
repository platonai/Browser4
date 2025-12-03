package ai.platon.pulsar.rest.api.webdriver.controller

import ai.platon.pulsar.rest.api.webdriver.dto.*
import ai.platon.pulsar.rest.api.webdriver.store.InMemoryStore
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.util.Base64
import java.util.UUID

/**
 * Controller for selector-first element operations.
 */
@RestController
@CrossOrigin
@RequestMapping(
    "/session/{sessionId}/selectors",
    produces = [MediaType.APPLICATION_JSON_VALUE]
)
class SelectorController(
    private val store: InMemoryStore
) {
    private val logger = LoggerFactory.getLogger(SelectorController::class.java)

    /**
     * Checks if an element matching the selector exists.
     */
    @PostMapping("/exists", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun selectorExists(
        @PathVariable sessionId: String,
        @RequestBody request: SelectorRef,
        response: HttpServletResponse
    ): ResponseEntity<Any> {
        logger.debug("Session {} checking selector exists: {}", sessionId, request.selector)
        addRequestId(response)

        if (!store.sessionExists(sessionId)) {
            return notFound("session not found", "No active session with id $sessionId")
        }

        // Mock implementation always returns true for demonstration
        return ResponseEntity.ok(ExistsResponse(value = ExistsResponse.ExistsValue(exists = true)))
    }

    /**
     * Waits for an element matching the selector to appear.
     */
    @PostMapping("/waitFor", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun waitForSelector(
        @PathVariable sessionId: String,
        @RequestBody request: WaitForRequest,
        response: HttpServletResponse
    ): ResponseEntity<Any> {
        logger.debug("Session {} waiting for selector: {} (timeout: {}ms)", sessionId, request.selector, request.timeout)
        addRequestId(response)

        if (!store.sessionExists(sessionId)) {
            return notFound("session not found", "No active session with id $sessionId")
        }

        // Mock implementation - immediately returns success
        return ResponseEntity.ok(WebDriverResponse<Any?>(value = null))
    }

    /**
     * Finds a single element by selector.
     */
    @PostMapping("/element", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun findElementBySelector(
        @PathVariable sessionId: String,
        @RequestBody request: SelectorRef,
        response: HttpServletResponse
    ): ResponseEntity<Any> {
        logger.debug("Session {} finding element by selector: {}", sessionId, request.selector)
        addRequestId(response)

        val element = store.getOrCreateElement(sessionId, request.selector, request.strategy)
            ?: return notFound("session not found", "No active session with id $sessionId")

        return ResponseEntity.ok(ElementResponse(value = ElementRef(elementId = element.elementId)))
    }

    /**
     * Finds all elements matching the selector.
     */
    @PostMapping("/elements", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun findElementsBySelector(
        @PathVariable sessionId: String,
        @RequestBody request: SelectorRef,
        response: HttpServletResponse
    ): ResponseEntity<Any> {
        logger.debug("Session {} finding elements by selector: {}", sessionId, request.selector)
        addRequestId(response)

        val element = store.getOrCreateElement(sessionId, request.selector, request.strategy)
            ?: return notFound("session not found", "No active session with id $sessionId")

        // Mock implementation returns a single element
        return ResponseEntity.ok(ElementsResponse(value = listOf(ElementRef(elementId = element.elementId))))
    }

    /**
     * Clicks an element by selector.
     */
    @PostMapping("/click", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun clickBySelector(
        @PathVariable sessionId: String,
        @RequestBody request: SelectorRef,
        response: HttpServletResponse
    ): ResponseEntity<Any> {
        logger.debug("Session {} clicking selector: {}", sessionId, request.selector)
        addRequestId(response)

        if (!store.sessionExists(sessionId)) {
            return notFound("session not found", "No active session with id $sessionId")
        }

        // Mock implementation - creates element reference and returns success
        store.getOrCreateElement(sessionId, request.selector, request.strategy)
        return ResponseEntity.ok(WebDriverResponse<Any?>(value = null))
    }

    /**
     * Fills an input element by selector.
     */
    @PostMapping("/fill", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun fillBySelector(
        @PathVariable sessionId: String,
        @RequestBody request: FillRequest,
        response: HttpServletResponse
    ): ResponseEntity<Any> {
        logger.debug("Session {} filling selector: {} with value: {}", sessionId, request.selector, request.value)
        addRequestId(response)

        if (!store.sessionExists(sessionId)) {
            return notFound("session not found", "No active session with id $sessionId")
        }

        // Mock implementation
        store.getOrCreateElement(sessionId, request.selector, request.strategy)
        return ResponseEntity.ok(WebDriverResponse<Any?>(value = null))
    }

    /**
     * Presses a key on an element by selector.
     */
    @PostMapping("/press", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun pressBySelector(
        @PathVariable sessionId: String,
        @RequestBody request: PressRequest,
        response: HttpServletResponse
    ): ResponseEntity<Any> {
        logger.debug("Session {} pressing key: {} on selector: {}", sessionId, request.key, request.selector)
        addRequestId(response)

        if (!store.sessionExists(sessionId)) {
            return notFound("session not found", "No active session with id $sessionId")
        }

        // Mock implementation
        store.getOrCreateElement(sessionId, request.selector, request.strategy)
        return ResponseEntity.ok(WebDriverResponse<Any?>(value = null))
    }

    /**
     * Gets the outer HTML of an element by selector.
     */
    @PostMapping("/outerHtml", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun getOuterHtmlBySelector(
        @PathVariable sessionId: String,
        @RequestBody request: SelectorRef,
        response: HttpServletResponse
    ): ResponseEntity<Any> {
        logger.debug("Session {} getting outerHtml for selector: {}", sessionId, request.selector)
        addRequestId(response)

        val element = store.getOrCreateElement(sessionId, request.selector, request.strategy)
            ?: return notFound("session not found", "No active session with id $sessionId")

        return ResponseEntity.ok(HtmlResponse(value = element.outerHtml))
    }

    /**
     * Takes a screenshot of an element by selector.
     */
    @PostMapping("/screenshot", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun screenshotBySelector(
        @PathVariable sessionId: String,
        @RequestBody request: SelectorRef,
        response: HttpServletResponse
    ): ResponseEntity<Any> {
        logger.debug("Session {} taking screenshot of selector: {}", sessionId, request.selector)
        addRequestId(response)

        if (!store.sessionExists(sessionId)) {
            return notFound("session not found", "No active session with id $sessionId")
        }

        // Mock implementation - returns a small placeholder base64 PNG
        val mockPng = Base64.getEncoder().encodeToString("mock-screenshot-data".toByteArray())
        return ResponseEntity.ok(ScreenshotResponse(value = mockPng))
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
