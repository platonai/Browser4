package ai.platon.pulsar.rest.api.controller

import ai.platon.pulsar.common.getLogger
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.multipart.MaxUploadSizeExceededException
import org.springframework.web.context.request.async.AsyncRequestTimeoutException

/**
 * Global exception handler for the REST layer.
 *
 * Covers low-level MVC errors that previously leaked as raw HTML or empty
 * 5xx bodies (e.g. oversized plugin uploads, async call timeouts) and
 * converts them into structured JSON responses the CLI can surface.
 */
@RestControllerAdvice
class GlobalExceptionHandler {

    private val logger = getLogger(GlobalExceptionHandler::class)

    /**
     * Multipart upload exceeded `spring.servlet.multipart.max-file-size` /
     * `max-request-size`. Without this handler Spring returns an HTML error
     * page that the CLI cannot parse.
     */
    @ExceptionHandler(MaxUploadSizeExceededException::class)
    fun handleMaxUploadSizeExceeded(e: MaxUploadSizeExceededException): ResponseEntity<Map<String, Any>> {
        logger.warn("Multipart upload exceeded size limit: {}", e.message)
        val body = mapOf(
            "error" to "Payload Too Large",
            "message" to
                "Uploaded file exceeds the server limit. " +
                "Increase spring.servlet.multipart.max-file-size / max-request-size " +
                "(currently configured to allow up to 100MB per request).",
            "status" to 413,
        )
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(body)
    }

    /**
     * A `@Async` / `WebAsyncTask` / `Callable` controller handler exceeded
     * `browser4.async.timeout-seconds`. Previously this surfaced as a raw 503
     * with no structured body.
     */
    @ExceptionHandler(AsyncRequestTimeoutException::class)
    fun handleAsyncTimeout(e: AsyncRequestTimeoutException): ResponseEntity<Map<String, Any>> {
        logger.warn("Async request timed out: {}", e.message)
        val body = mapOf(
            "error" to "Gateway Timeout",
            "message" to
                "The tool call exceeded the server async timeout " +
                "(browser4.async.timeout-seconds). Retry or increase the limit.",
            "status" to 504,
        )
        // 504 is more accurate than 503 for a timeout; the client can still
        // retry safely.
        return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT).body(body)
    }
}
