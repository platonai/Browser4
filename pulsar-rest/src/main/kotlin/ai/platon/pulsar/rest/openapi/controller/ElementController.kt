package ai.platon.pulsar.rest.openapi.controller

import ai.platon.pulsar.rest.openapi.dto.*
import ai.platon.pulsar.rest.openapi.exception.RequestSupersededException
import ai.platon.pulsar.rest.openapi.service.SessionManager
import ai.platon.pulsar.rest.openapi.store.InMemoryStore
import ai.platon.pulsar.skeleton.crawl.fetch.driver.WebDriverException
import jakarta.servlet.http.HttpServletResponse
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

/**
 * Controller for element operations by element ID.
 */
@RestController
@CrossOrigin
@RequestMapping(
    "/session/{sessionId}",
    produces = [MediaType.APPLICATION_JSON_VALUE]
)
@ConditionalOnBean(SessionManager::class)
class ElementController(
    private val sessionManager: SessionManager,
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
        ControllerUtils.addRequestId(response)

        if (!sessionManager.sessionExists(sessionId)) {
            return ControllerUtils.notFound("session not found", "No active session with id $sessionId")
        }

        // Convert WebDriver strategy to selector
        val selector = convertToSelector(request.using, request.value)
        val element = store.getOrCreateElement(sessionId, selector, "css")

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
        ControllerUtils.addRequestId(response)

        if (!sessionManager.sessionExists(sessionId)) {
            return ControllerUtils.notFound("session not found", "No active session with id $sessionId")
        }

        val selector = convertToSelector(request.using, request.value)
        val element = store.getOrCreateElement(sessionId, selector, "css")

        return ResponseEntity.ok(ElementsResponse(value = listOf(ElementRef(elementId = element.elementId))))
    }

    /**
     * Clicks an element by ID.
     * Implements last-request-wins strategy.
     */
    @PostMapping("/element/{elementId}/click")
    suspend fun clickElement(
        @PathVariable sessionId: String,
        @PathVariable elementId: String,
        response: HttpServletResponse
    ): ResponseEntity<Any> {
        logger.debug("Session {} clicking element: {}", sessionId, elementId)
        ControllerUtils.addRequestId(response)

        val managed = sessionManager.getSession(sessionId)
            ?: return ControllerUtils.notFound("session not found", "No active session with id $sessionId")

        val element = store.getElement(sessionId, elementId)
            ?: return ControllerUtils.notFound("no such element", "Element with id $elementId not found")

        val requestId = managed.newRequest()

        return try {
            managed.mutex.withLock {
                if (!managed.isLatestRequest(requestId)) {
                    throw RequestSupersededException(sessionId, requestId)
                }
                managed.driver.click(element.selector)
            }
            ResponseEntity.ok(WebDriverResponse<Any?>(value = null))
        } catch (e: RequestSupersededException) {
            logger.info("Session {} clickElement was superseded by newer request", sessionId)
            ResponseEntity.ok(WebDriverResponse<Any?>(value = null))
        } catch (e: WebDriverException) {
            logger.error("Element click failed | sessionId={} elementId={} | {}", sessionId, elementId, e.message)
            ControllerUtils.errorResponse("webdriver error", e.message ?: "WebDriver error")
        } catch (e: Exception) {
            logger.error("Element click failed | sessionId={} elementId={} | {}", sessionId, elementId, e.message, e)
            ControllerUtils.errorResponse("internal error", e.message ?: "Internal error")
        }
    }

    /**
     * Sends keys to an element by ID.
     * Implements last-request-wins strategy.
     */
    @PostMapping("/element/{elementId}/value", consumes = [MediaType.APPLICATION_JSON_VALUE])
    suspend fun sendKeysToElement(
        @PathVariable sessionId: String,
        @PathVariable elementId: String,
        @RequestBody request: SendKeysRequest,
        response: HttpServletResponse
    ): ResponseEntity<Any> {
        logger.debug("Session {} sending keys to element: {}", sessionId, elementId)
        ControllerUtils.addRequestId(response)

        val managed = sessionManager.getSession(sessionId)
            ?: return ControllerUtils.notFound("session not found", "No active session with id $sessionId")

        val element = store.getElement(sessionId, elementId)
            ?: return ControllerUtils.notFound("no such element", "Element with id $elementId not found")

        val requestId = managed.newRequest()

        return try {
            managed.mutex.withLock {
                if (!managed.isLatestRequest(requestId)) {
                    throw RequestSupersededException(sessionId, requestId)
                }
                managed.driver.fill(element.selector, request.text)
            }
            ResponseEntity.ok(WebDriverResponse<Any?>(value = null))
        } catch (e: RequestSupersededException) {
            logger.info("Session {} sendKeysToElement was superseded by newer request", sessionId)
            ResponseEntity.ok(WebDriverResponse<Any?>(value = null))
        } catch (e: WebDriverException) {
            logger.error("Send keys failed | sessionId={} elementId={} | {}", sessionId, elementId, e.message)
            ControllerUtils.errorResponse("webdriver error", e.message ?: "WebDriver error")
        } catch (e: Exception) {
            logger.error("Send keys failed | sessionId={} elementId={} | {}", sessionId, elementId, e.message, e)
            ControllerUtils.errorResponse("internal error", e.message ?: "Internal error")
        }
    }

    /**
     * Gets an element attribute by name.
     * Implements last-request-wins strategy.
     */
    @GetMapping("/element/{elementId}/attribute/{name}")
    suspend fun getElementAttribute(
        @PathVariable sessionId: String,
        @PathVariable elementId: String,
        @PathVariable name: String,
        response: HttpServletResponse
    ): ResponseEntity<Any> {
        logger.debug("Session {} getting attribute {} from element: {}", sessionId, name, elementId)
        ControllerUtils.addRequestId(response)

        val managed = sessionManager.getSession(sessionId)
            ?: return ControllerUtils.notFound("session not found", "No active session with id $sessionId")

        val element = store.getElement(sessionId, elementId)
            ?: return ControllerUtils.notFound("no such element", "Element with id $elementId not found")

        val requestId = managed.newRequest()

        return try {
            val value = managed.mutex.withLock {
                if (!managed.isLatestRequest(requestId)) {
                    throw RequestSupersededException(sessionId, requestId)
                }
                managed.driver.selectFirstAttributeOrNull(element.selector, name)
            }
            ResponseEntity.ok(AttributeResponse(value = value ?: ""))
        } catch (e: RequestSupersededException) {
            logger.info("Session {} getElementAttribute was superseded by newer request", sessionId)
            ResponseEntity.ok(AttributeResponse(value = ""))
        } catch (e: WebDriverException) {
            logger.error(
                "Get attribute failed | sessionId={} elementId={} name={} | {}",
                sessionId,
                elementId,
                name,
                e.message
            )
            ControllerUtils.errorResponse("webdriver error", e.message ?: "WebDriver error")
        } catch (e: Exception) {
            logger.error(
                "Get attribute failed | sessionId={} elementId={} name={} | {}",
                sessionId,
                elementId,
                name,
                e.message,
                e
            )
            ControllerUtils.errorResponse("internal error", e.message ?: "Internal error")
        }
    }

    /**
     * Gets an element's text content.
     * Implements last-request-wins strategy.
     */
    @GetMapping("/element/{elementId}/text")
    suspend fun getElementText(
        @PathVariable sessionId: String,
        @PathVariable elementId: String,
        response: HttpServletResponse
    ): ResponseEntity<Any> {
        logger.debug("Session {} getting text from element: {}", sessionId, elementId)
        ControllerUtils.addRequestId(response)

        val managed = sessionManager.getSession(sessionId)
            ?: return ControllerUtils.notFound("session not found", "No active session with id $sessionId")

        val element = store.getElement(sessionId, elementId)
            ?: return ControllerUtils.notFound("no such element", "Element with id $elementId not found")

        val requestId = managed.newRequest()

        return try {
            val text = managed.mutex.withLock {
                if (!managed.isLatestRequest(requestId)) {
                    throw RequestSupersededException(sessionId, requestId)
                }
                managed.driver.selectFirstTextOrNull(element.selector) ?: ""
            }
            ResponseEntity.ok(TextResponse(value = text))
        } catch (e: RequestSupersededException) {
            logger.info("Session {} getElementText was superseded by newer request", sessionId)
            ResponseEntity.ok(TextResponse(value = ""))
        } catch (e: WebDriverException) {
            logger.error("Get text failed | sessionId={} elementId={} | {}", sessionId, elementId, e.message)
            ControllerUtils.errorResponse("webdriver error", e.message ?: "WebDriver error")
        } catch (e: Exception) {
            logger.error("Get text failed | sessionId={} elementId={} | {}", sessionId, elementId, e.message, e)
            ControllerUtils.errorResponse("internal error", e.message ?: "Internal error")
        }
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
}
