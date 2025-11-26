package ai.platon.pulsar.rest.api.webdriver.controller

import ai.platon.pulsar.rest.api.webdriver.dto.*
import ai.platon.pulsar.rest.api.webdriver.store.InMemoryStore
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.util.UUID

/**
 * Controller for JavaScript execution.
 */
@RestController
@CrossOrigin
@RequestMapping("/session/{sessionId}/execute")
class ScriptController(
    private val store: InMemoryStore
) {
    private val logger = LoggerFactory.getLogger(ScriptController::class.java)

    /**
     * Execute script synchronously.
     */
    @PostMapping("/sync")
    fun executeSync(
        @PathVariable sessionId: String,
        @RequestBody request: ExecuteScriptRequest,
        @RequestHeader(value = "X-Request-Id", required = false) requestId: String?
    ): ResponseEntity<*> {
        val reqId = requestId ?: UUID.randomUUID().toString()
        logger.debug("Executing sync script in session: {}, X-Request-Id: {}", sessionId, reqId)

        store.getSession(sessionId)
            ?: return notFoundResponse(sessionId, reqId)

        // Mock: return null as script result
        // In real implementation, this would execute JavaScript in the browser
        val result: Any? = mockScriptExecution(request.script, request.args)

        return ResponseEntity.ok()
            .header("X-Request-Id", reqId)
            .body(WebDriverResponse(result))
    }

    /**
     * Execute script asynchronously.
     */
    @PostMapping("/async")
    fun executeAsync(
        @PathVariable sessionId: String,
        @RequestBody request: ExecuteScriptRequest,
        @RequestHeader(value = "X-Request-Id", required = false) requestId: String?
    ): ResponseEntity<*> {
        val reqId = requestId ?: UUID.randomUUID().toString()
        logger.debug("Executing async script in session: {}, X-Request-Id: {}", sessionId, reqId)

        store.getSession(sessionId)
            ?: return notFoundResponse(sessionId, reqId)

        // Mock: return null as script result
        // In real implementation, this would execute JavaScript asynchronously in the browser
        val result: Any? = mockScriptExecution(request.script, request.args)

        return ResponseEntity.ok()
            .header("X-Request-Id", reqId)
            .body(WebDriverResponse(result))
    }

    /**
     * Mock script execution for skeleton implementation.
     * Returns predictable values for common scripts.
     */
    private fun mockScriptExecution(script: String, args: List<Any?>?): Any? {
        return when {
            script.contains("return document.title") -> "Mock Page Title"
            script.contains("return document.URL") -> "https://example.com"
            script.contains("return arguments") -> args
            script.contains("return true") -> true
            script.contains("return false") -> false
            script.contains("return null") -> null
            else -> null
        }
    }

    private fun notFoundResponse(sessionId: String, reqId: String): ResponseEntity<WebDriverResponse<ErrorValue>> {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .header("X-Request-Id", reqId)
            .body(WebDriverResponse(ErrorValue("no such session", "Session $sessionId not found")))
    }
}
