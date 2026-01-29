package ai.platon.pulsar.rest.openapi.controller

import ai.platon.pulsar.rest.openapi.dto.*
import ai.platon.pulsar.rest.openapi.exception.RequestSupersededException
import ai.platon.pulsar.rest.openapi.service.SessionManager
import ai.platon.pulsar.skeleton.crawl.fetch.driver.WebDriverException
import jakarta.servlet.http.HttpServletResponse
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

/**
 * Controller for scrolling operations.
 */
@RestController
@CrossOrigin
@RequestMapping(
    "/session/{sessionId}/scroll",
    produces = [MediaType.APPLICATION_JSON_VALUE]
)
@ConditionalOnBean(SessionManager::class)
class ScrollController(
    private val sessionManager: SessionManager
) {
    private val logger = LoggerFactory.getLogger(ScrollController::class.java)

    /**
     * Scrolls down on the page.
     * Implements last-request-wins strategy.
     */
    @PostMapping("/down", consumes = [MediaType.APPLICATION_JSON_VALUE])
    suspend fun scrollDown(
        @PathVariable sessionId: String,
        @RequestBody request: ScrollCountRequest,
        response: HttpServletResponse
    ): ResponseEntity<Any> {
        logger.debug("Session {} scrolling down {} times", sessionId, request.count)
        ControllerUtils.addRequestId(response)

        val managed = sessionManager.getSession(sessionId)
            ?: return ControllerUtils.notFound("session not found", "No active session with id $sessionId")

        val requestId = managed.newRequest()

        return try {
            val scrollY = managed.mutex.withLock {
                if (!managed.isLatestRequest(requestId)) {
                    throw RequestSupersededException(sessionId, requestId)
                }
                managed.driver.scrollDown(request.count)
            }
            ResponseEntity.ok(WebDriverResponse(value = scrollY))
        } catch (e: RequestSupersededException) {
            logger.info("Session {} scrollDown was superseded by newer request", sessionId)
            ResponseEntity.ok(WebDriverResponse(value = 0))
        } catch (e: WebDriverException) {
            logger.error("Scroll down failed | sessionId={} | {}", sessionId, e.message)
            ControllerUtils.errorResponse("webdriver error", e.message ?: "WebDriver error")
        } catch (e: Exception) {
            logger.error("Scroll down failed | sessionId={} | {}", sessionId, e.message, e)
            ControllerUtils.errorResponse("internal error", e.message ?: "Internal error")
        }
    }

    /**
     * Scrolls up on the page.
     * Implements last-request-wins strategy.
     */
    @PostMapping("/up", consumes = [MediaType.APPLICATION_JSON_VALUE])
    suspend fun scrollUp(
        @PathVariable sessionId: String,
        @RequestBody request: ScrollCountRequest,
        response: HttpServletResponse
    ): ResponseEntity<Any> {
        logger.debug("Session {} scrolling up {} times", sessionId, request.count)
        ControllerUtils.addRequestId(response)

        val managed = sessionManager.getSession(sessionId)
            ?: return ControllerUtils.notFound("session not found", "No active session with id $sessionId")

        val requestId = managed.newRequest()

        return try {
            val scrollY = managed.mutex.withLock {
                if (!managed.isLatestRequest(requestId)) {
                    throw RequestSupersededException(sessionId, requestId)
                }
                managed.driver.scrollUp(request.count)
            }
            ResponseEntity.ok(WebDriverResponse(value = scrollY))
        } catch (e: RequestSupersededException) {
            logger.info("Session {} scrollUp was superseded by newer request", sessionId)
            ResponseEntity.ok(WebDriverResponse(value = 0))
        } catch (e: WebDriverException) {
            logger.error("Scroll up failed | sessionId={} | {}", sessionId, e.message)
            ControllerUtils.errorResponse("webdriver error", e.message ?: "WebDriver error")
        } catch (e: Exception) {
            logger.error("Scroll up failed | sessionId={} | {}", sessionId, e.message, e)
            ControllerUtils.errorResponse("internal error", e.message ?: "Internal error")
        }
    }

    /**
     * Scrolls to an element.
     * Implements last-request-wins strategy.
     */
    @PostMapping("/to", consumes = [MediaType.APPLICATION_JSON_VALUE])
    suspend fun scrollTo(
        @PathVariable sessionId: String,
        @RequestBody request: SelectorRef,
        response: HttpServletResponse
    ): ResponseEntity<Any> {
        logger.debug("Session {} scrolling to selector: {}", sessionId, request.selector)
        ControllerUtils.addRequestId(response)

        val managed = sessionManager.getSession(sessionId)
            ?: return ControllerUtils.notFound("session not found", "No active session with id $sessionId")

        val requestId = managed.newRequest()

        return try {
            val scrollY = managed.mutex.withLock {
                if (!managed.isLatestRequest(requestId)) {
                    throw RequestSupersededException(sessionId, requestId)
                }
                managed.driver.scrollTo(request.selector)
            }
            ResponseEntity.ok(WebDriverResponse(value = scrollY))
        } catch (e: RequestSupersededException) {
            logger.info("Session {} scrollTo was superseded by newer request", sessionId)
            ResponseEntity.ok(WebDriverResponse(value = 0))
        } catch (e: WebDriverException) {
            logger.error("Scroll to failed | sessionId={} selector={} | {}", sessionId, request.selector, e.message)
            ControllerUtils.errorResponse("webdriver error", e.message ?: "WebDriver error")
        } catch (e: Exception) {
            logger.error("Scroll to failed | sessionId={} selector={} | {}", sessionId, request.selector, e.message, e)
            ControllerUtils.errorResponse("internal error", e.message ?: "Internal error")
        }
    }

    /**
     * Scrolls to the top of the page.
     * Implements last-request-wins strategy.
     */
    @PostMapping("/top")
    suspend fun scrollToTop(
        @PathVariable sessionId: String,
        response: HttpServletResponse
    ): ResponseEntity<Any> {
        logger.debug("Session {} scrolling to top", sessionId)
        ControllerUtils.addRequestId(response)

        val managed = sessionManager.getSession(sessionId)
            ?: return ControllerUtils.notFound("session not found", "No active session with id $sessionId")

        val requestId = managed.newRequest()

        return try {
            val scrollY = managed.mutex.withLock {
                if (!managed.isLatestRequest(requestId)) {
                    throw RequestSupersededException(sessionId, requestId)
                }
                managed.driver.scrollToTop()
            }
            ResponseEntity.ok(WebDriverResponse(value = scrollY))
        } catch (e: RequestSupersededException) {
            logger.info("Session {} scrollToTop was superseded by newer request", sessionId)
            ResponseEntity.ok(WebDriverResponse(value = 0))
        } catch (e: WebDriverException) {
            logger.error("Scroll to top failed | sessionId={} | {}", sessionId, e.message)
            ControllerUtils.errorResponse("webdriver error", e.message ?: "WebDriver error")
        } catch (e: Exception) {
            logger.error("Scroll to top failed | sessionId={} | {}", sessionId, e.message, e)
            ControllerUtils.errorResponse("internal error", e.message ?: "Internal error")
        }
    }

    /**
     * Scrolls to the bottom of the page.
     * Implements last-request-wins strategy.
     */
    @PostMapping("/bottom")
    suspend fun scrollToBottom(
        @PathVariable sessionId: String,
        response: HttpServletResponse
    ): ResponseEntity<Any> {
        logger.debug("Session {} scrolling to bottom", sessionId)
        ControllerUtils.addRequestId(response)

        val managed = sessionManager.getSession(sessionId)
            ?: return ControllerUtils.notFound("session not found", "No active session with id $sessionId")

        val requestId = managed.newRequest()

        return try {
            val scrollY = managed.mutex.withLock {
                if (!managed.isLatestRequest(requestId)) {
                    throw RequestSupersededException(sessionId, requestId)
                }
                managed.driver.scrollToBottom()
            }
            ResponseEntity.ok(WebDriverResponse(value = scrollY))
        } catch (e: RequestSupersededException) {
            logger.info("Session {} scrollToBottom was superseded by newer request", sessionId)
            ResponseEntity.ok(WebDriverResponse(value = 0))
        } catch (e: WebDriverException) {
            logger.error("Scroll to bottom failed | sessionId={} | {}", sessionId, e.message)
            ControllerUtils.errorResponse("webdriver error", e.message ?: "WebDriver error")
        } catch (e: Exception) {
            logger.error("Scroll to bottom failed | sessionId={} | {}", sessionId, e.message, e)
            ControllerUtils.errorResponse("internal error", e.message ?: "Internal error")
        }
    }

    /**
     * Scrolls to a position on the page.
     * Implements last-request-wins strategy.
     */
    @PostMapping("/middle", consumes = [MediaType.APPLICATION_JSON_VALUE])
    suspend fun scrollToMiddle(
        @PathVariable sessionId: String,
        @RequestBody request: ScrollRatioRequest,
        response: HttpServletResponse
    ): ResponseEntity<Any> {
        logger.debug("Session {} scrolling to middle with ratio: {}", sessionId, request.ratio)
        ControllerUtils.addRequestId(response)

        val managed = sessionManager.getSession(sessionId)
            ?: return ControllerUtils.notFound("session not found", "No active session with id $sessionId")

        val requestId = managed.newRequest()

        return try {
            val scrollY = managed.mutex.withLock {
                if (!managed.isLatestRequest(requestId)) {
                    throw RequestSupersededException(sessionId, requestId)
                }
                managed.driver.scrollToMiddle(request.ratio)
            }
            ResponseEntity.ok(WebDriverResponse(value = scrollY))
        } catch (e: RequestSupersededException) {
            logger.info("Session {} scrollToMiddle was superseded by newer request", sessionId)
            ResponseEntity.ok(WebDriverResponse(value = 0))
        } catch (e: WebDriverException) {
            logger.error("Scroll to middle failed | sessionId={} | {}", sessionId, e.message)
            ControllerUtils.errorResponse("webdriver error", e.message ?: "WebDriver error")
        } catch (e: Exception) {
            logger.error("Scroll to middle failed | sessionId={} | {}", sessionId, e.message, e)
            ControllerUtils.errorResponse("internal error", e.message ?: "Internal error")
        }
    }

    /**
     * Scrolls by a specific amount of pixels.
     * Implements last-request-wins strategy.
     */
    @PostMapping("/by", consumes = [MediaType.APPLICATION_JSON_VALUE])
    suspend fun scrollBy(
        @PathVariable sessionId: String,
        @RequestBody request: ScrollByRequest,
        response: HttpServletResponse
    ): ResponseEntity<Any> {
        logger.debug("Session {} scrolling by {} pixels", sessionId, request.pixels)
        ControllerUtils.addRequestId(response)

        val managed = sessionManager.getSession(sessionId)
            ?: return ControllerUtils.notFound("session not found", "No active session with id $sessionId")

        val requestId = managed.newRequest()

        return try {
            val scrollY = managed.mutex.withLock {
                if (!managed.isLatestRequest(requestId)) {
                    throw RequestSupersededException(sessionId, requestId)
                }
                managed.driver.scrollBy(request.pixels, request.smooth)
            }
            ResponseEntity.ok(WebDriverResponse(value = scrollY))
        } catch (e: RequestSupersededException) {
            logger.info("Session {} scrollBy was superseded by newer request", sessionId)
            ResponseEntity.ok(WebDriverResponse(value = 0))
        } catch (e: WebDriverException) {
            logger.error("Scroll by failed | sessionId={} | {}", sessionId, e.message)
            ControllerUtils.errorResponse("webdriver error", e.message ?: "WebDriver error")
        } catch (e: Exception) {
            logger.error("Scroll by failed | sessionId={} | {}", sessionId, e.message, e)
            ControllerUtils.errorResponse("internal error", e.message ?: "Internal error")
        }
    }
}
