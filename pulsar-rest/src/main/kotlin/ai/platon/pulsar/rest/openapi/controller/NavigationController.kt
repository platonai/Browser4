package ai.platon.pulsar.rest.openapi.controller

import ai.platon.pulsar.rest.openapi.dto.SetUrlRequest
import ai.platon.pulsar.rest.openapi.dto.WebDriverResponse
import ai.platon.pulsar.rest.openapi.exception.RequestSupersededException
import ai.platon.pulsar.rest.openapi.service.SessionManager
import jakarta.servlet.http.HttpServletResponse
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

/**
 * Controller for page navigation operations.
 */
@RestController
@CrossOrigin
@RequestMapping(
    "/session/{sessionId}",
    produces = [MediaType.APPLICATION_JSON_VALUE]
)
@ConditionalOnBean(SessionManager::class)
class NavigationController(
    private val sessionManager: SessionManager,
    @param:Value($$"${pulsar.stub.mode:false}")
    private val stubMode: Boolean = false
) {
    private val logger = LoggerFactory.getLogger(NavigationController::class.java)

    /**
     * Navigates to a URL.
     * Implements last-request-wins strategy: if multiple navigation requests arrive
     * for the same session, only the latest one will be executed.
     */
    @PostMapping("/url", consumes = [MediaType.APPLICATION_JSON_VALUE])
    suspend fun navigateTo(
        @PathVariable sessionId: String,
        @RequestBody request: SetUrlRequest,
        response: HttpServletResponse
    ): ResponseEntity<Any> {
        logger.debug("Session {} navigating to: {}", sessionId, request.url)
        ControllerUtils.addRequestId(response)

        val session = sessionManager.getSession(sessionId)
            ?: return ControllerUtils.notFound("session not found", "No active session with id $sessionId")

        if (stubMode) {
            sessionManager.setSessionUrl(sessionId, request.url)
            return ResponseEntity.ok(WebDriverResponse<Any?>(value = null))
        }

        // Register this as a new request - makes it the "latest" and supersedes any pending requests
        val requestId = session.newRequest()
        logger.debug("Session {} navigation request {} registered", sessionId, requestId)

        try {
            // Try to acquire the mutex - if another operation is running, wait for it
            session.mutex.withLock {
                // Before executing, check if this is still the latest request
                if (!session.isLatestRequest(requestId)) {
                    logger.debug(
                        "Session {} navigation request {} was superseded, skipping execution",
                        sessionId,
                        requestId
                    )
                    throw RequestSupersededException(sessionId, requestId)
                }

                // Still the latest request, proceed with navigation
                logger.debug("Session {} navigation request {} executing", sessionId, requestId)
                val driver = session.pulsarSession.getOrCreateBoundDriver()
                driver.navigateTo(request.url)
            }
            sessionManager.setSessionUrl(sessionId, request.url)
            logger.debug("Session {} navigation request {} completed successfully", sessionId, requestId)
        } catch (e: RequestSupersededException) {
            // Request was superseded - return success but log for debugging
            logger.info("Session {} navigation to {} was superseded by newer request", sessionId, request.url)
            return ResponseEntity.ok(WebDriverResponse<Any?>(value = null))
        } catch (e: Exception) {
            logger.error("Error navigating to URL: {}", e.message, e)
            return ControllerUtils.errorResponse("navigation error", "Failed to navigate: ${e.message}")
        }

        return ResponseEntity.ok(WebDriverResponse<Any?>(value = null))
    }

    /**
     * Gets the current URL.
     */
    @GetMapping("/url")
    fun getCurrentUrl(
        @PathVariable sessionId: String,
        response: HttpServletResponse
    ): ResponseEntity<Any> {
        logger.debug("Session {} getting current URL", sessionId)
        ControllerUtils.addRequestId(response)

        val session = sessionManager.getSession(sessionId)
            ?: return ControllerUtils.notFound("session not found", "No active session with id $sessionId")

        // Return the stored URL from session
        val url = session.url ?: "about:blank"

        return ResponseEntity.ok(WebDriverResponse(value = url))
    }

    /**
     * Gets the document URI.
     */
    @GetMapping("/documentUri")
    fun getDocumentUri(
        @PathVariable sessionId: String,
        response: HttpServletResponse
    ): ResponseEntity<Any> {
        logger.debug("Session {} getting document URI", sessionId)
        ControllerUtils.addRequestId(response)

        val session = sessionManager.getSession(sessionId)
            ?: return ControllerUtils.notFound("session not found", "No active session with id $sessionId")

        // For now, return the current URL (in real implementation, this could be different from URL)
        val uri = session.url ?: "about:blank"

        return ResponseEntity.ok(WebDriverResponse(value = uri))
    }

    /**
     * Gets the base URI.
     */
    @GetMapping("/baseUri")
    fun getBaseUri(
        @PathVariable sessionId: String,
        response: HttpServletResponse
    ): ResponseEntity<Any> {
        logger.debug("Session {} getting base URI", sessionId)
        ControllerUtils.addRequestId(response)

        val session = sessionManager.getSession(sessionId)
            ?: return ControllerUtils.notFound("session not found", "No active session with id $sessionId")

        // Extract base URI from current URL (protocol + host)
        val baseUri = session.url?.let { url ->
            try {
                val uri = java.net.URI(url)
                "${uri.scheme}://${uri.host}${if (uri.port > 0 && uri.port != 80 && uri.port != 443) ":${uri.port}" else ""}"
            } catch (e: Exception) {
                url
            }
        } ?: "about:blank"

        return ResponseEntity.ok(WebDriverResponse(value = baseUri))
    }

    /**
     * Reloads the current page.
     * Implements last-request-wins strategy.
     */
    @PostMapping("/reload")
    suspend fun reload(
        @PathVariable sessionId: String,
        response: HttpServletResponse
    ): ResponseEntity<Any> {
        logger.debug("Session {} reloading page", sessionId)
        ControllerUtils.addRequestId(response)

        val managed = sessionManager.getSession(sessionId)
            ?: return ControllerUtils.notFound("session not found", "No active session with id $sessionId")

        val requestId = managed.newRequest()
        logger.debug("Session {} reload request {} registered", sessionId, requestId)

        return try {
            managed.mutex.withLock {
                if (!managed.isLatestRequest(requestId)) {
                    logger.debug("Session {} reload request {} was superseded, skipping", sessionId, requestId)
                    throw RequestSupersededException(sessionId, requestId)
                }
                logger.debug("Session {} reload request {} executing", sessionId, requestId)
                managed.driver.reload()
            }
            logger.debug("Session {} reload request {} completed", sessionId, requestId)
            ResponseEntity.ok(WebDriverResponse<Any?>(value = null))
        } catch (e: RequestSupersededException) {
            logger.info("Session {} reload was superseded by newer request", sessionId)
            ResponseEntity.ok(WebDriverResponse<Any?>(value = null))
        } catch (e: Exception) {
            logger.error("Reload failed | sessionId={} | {}", sessionId, e.message)
            ControllerUtils.errorResponse("navigation error", e.message ?: "Failed to reload")
        }
    }

    /**
     * Navigates back in browser history.
     * Implements last-request-wins strategy.
     */
    @PostMapping("/back")
    suspend fun goBack(
        @PathVariable sessionId: String,
        response: HttpServletResponse
    ): ResponseEntity<Any> {
        logger.debug("Session {} going back", sessionId)
        ControllerUtils.addRequestId(response)

        val managed = sessionManager.getSession(sessionId)
            ?: return ControllerUtils.notFound("session not found", "No active session with id $sessionId")

        val requestId = managed.newRequest()
        logger.debug("Session {} goBack request {} registered", sessionId, requestId)

        return try {
            managed.mutex.withLock {
                if (!managed.isLatestRequest(requestId)) {
                    logger.debug("Session {} goBack request {} was superseded, skipping", sessionId, requestId)
                    throw RequestSupersededException(sessionId, requestId)
                }
                logger.debug("Session {} goBack request {} executing", sessionId, requestId)
                managed.driver.goBack()
            }
            logger.debug("Session {} goBack request {} completed", sessionId, requestId)
            ResponseEntity.ok(WebDriverResponse<Any?>(value = null))
        } catch (e: RequestSupersededException) {
            logger.info("Session {} goBack was superseded by newer request", sessionId)
            ResponseEntity.ok(WebDriverResponse<Any?>(value = null))
        } catch (e: Exception) {
            logger.error("Go back failed | sessionId={} | {}", sessionId, e.message)
            ControllerUtils.errorResponse("navigation error", e.message ?: "Failed to go back")
        }
    }

    /**
     * Navigates forward in browser history.
     * Implements last-request-wins strategy.
     */
    @PostMapping("/forward")
    suspend fun goForward(
        @PathVariable sessionId: String,
        response: HttpServletResponse
    ): ResponseEntity<Any> {
        logger.debug("Session {} going forward", sessionId)
        ControllerUtils.addRequestId(response)

        val managed = sessionManager.getSession(sessionId)
            ?: return ControllerUtils.notFound("session not found", "No active session with id $sessionId")

        val requestId = managed.newRequest()
        logger.debug("Session {} goForward request {} registered", sessionId, requestId)

        return try {
            managed.mutex.withLock {
                if (!managed.isLatestRequest(requestId)) {
                    logger.debug("Session {} goForward request {} was superseded, skipping", sessionId, requestId)
                    throw RequestSupersededException(sessionId, requestId)
                }
                logger.debug("Session {} goForward request {} executing", sessionId, requestId)
                managed.driver.goForward()
            }
            logger.debug("Session {} goForward request {} completed", sessionId, requestId)
            ResponseEntity.ok(WebDriverResponse<Any?>(value = null))
        } catch (e: RequestSupersededException) {
            logger.info("Session {} goForward was superseded by newer request", sessionId)
            ResponseEntity.ok(WebDriverResponse<Any?>(value = null))
        } catch (e: Exception) {
            logger.error("Go forward failed | sessionId={} | {}", sessionId, e.message)
            ControllerUtils.errorResponse("navigation error", e.message ?: "Failed to go forward")
        }
    }

    /**
     * Gets the page title.
     * Implements last-request-wins strategy.
     */
    @GetMapping("/title")
    suspend fun getTitle(
        @PathVariable sessionId: String,
        response: HttpServletResponse
    ): ResponseEntity<Any> {
        logger.debug("Session {} getting title", sessionId)
        ControllerUtils.addRequestId(response)

        val managed = sessionManager.getSession(sessionId)
            ?: return ControllerUtils.notFound("session not found", "No active session with id $sessionId")

        val requestId = managed.newRequest()
        logger.debug("Session {} getTitle request {} registered", sessionId, requestId)

        return try {
            val title = managed.mutex.withLock {
                if (!managed.isLatestRequest(requestId)) {
                    logger.debug("Session {} getTitle request {} was superseded, skipping", sessionId, requestId)
                    throw RequestSupersededException(sessionId, requestId)
                }
                logger.debug("Session {} getTitle request {} executing", sessionId, requestId)
                managed.driver.title()
            }
            logger.debug("Session {} getTitle request {} completed", sessionId, requestId)
            ResponseEntity.ok(WebDriverResponse(value = title))
        } catch (e: RequestSupersededException) {
            logger.info("Session {} getTitle was superseded by newer request", sessionId)
            ResponseEntity.ok(WebDriverResponse(value = ""))
        } catch (e: Exception) {
            logger.error("Get title failed | sessionId={} | {}", sessionId, e.message)
            ControllerUtils.errorResponse("webdriver error", e.message ?: "Failed to get title")
        }
    }

    /**
     * Brings the browser window to the front.
     * Implements last-request-wins strategy.
     */
    @PostMapping("/bringToFront")
    suspend fun bringToFront(
        @PathVariable sessionId: String,
        response: HttpServletResponse
    ): ResponseEntity<Any> {
        logger.debug("Session {} bringing window to front", sessionId)
        ControllerUtils.addRequestId(response)

        val managed = sessionManager.getSession(sessionId)
            ?: return ControllerUtils.notFound("session not found", "No active session with id $sessionId")

        val requestId = managed.newRequest()
        logger.debug("Session {} bringToFront request {} registered", sessionId, requestId)

        return try {
            managed.mutex.withLock {
                if (!managed.isLatestRequest(requestId)) {
                    logger.debug("Session {} bringToFront request {} was superseded, skipping", sessionId, requestId)
                    throw RequestSupersededException(sessionId, requestId)
                }
                logger.debug("Session {} bringToFront request {} executing", sessionId, requestId)
                managed.driver.bringToFront()
            }
            logger.debug("Session {} bringToFront request {} completed", sessionId, requestId)
            ResponseEntity.ok(WebDriverResponse<Any?>(value = null))
        } catch (e: RequestSupersededException) {
            logger.info("Session {} bringToFront was superseded by newer request", sessionId)
            ResponseEntity.ok(WebDriverResponse<Any?>(value = null))
        } catch (e: Exception) {
            logger.error("Bring to front failed | sessionId={} | {}", sessionId, e.message)
            ControllerUtils.errorResponse("webdriver error", e.message ?: "Failed to bring to front")
        }
    }
}
