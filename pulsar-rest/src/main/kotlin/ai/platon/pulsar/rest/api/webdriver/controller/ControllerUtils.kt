package ai.platon.pulsar.rest.api.webdriver.controller

import ai.platon.pulsar.rest.api.webdriver.dto.ErrorResponse
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.ResponseEntity
import java.util.UUID

/**
 * Shared utilities for WebDriver API controllers.
 */
object ControllerUtils {
    /**
     * Adds a unique X-Request-Id header to the response.
     *
     * @param response The HTTP response to add the header to.
     */
    fun addRequestId(response: HttpServletResponse) {
        response.addHeader("X-Request-Id", UUID.randomUUID().toString())
    }

    /**
     * Creates a 404 Not Found response with WebDriver-style error format.
     *
     * @param error The error code.
     * @param message The error message.
     * @return A 404 ResponseEntity with the error details.
     */
    fun notFound(error: String, message: String): ResponseEntity<Any> {
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
