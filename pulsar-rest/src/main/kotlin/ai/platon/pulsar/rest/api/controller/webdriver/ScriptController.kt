package ai.platon.pulsar.rest.api.controller.webdriver

import ai.platon.pulsar.rest.api.dto.*
import ai.platon.pulsar.rest.api.service.webdriver.WebDriverSessionService
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

/**
 * Script execution controller.
 */
@RestController
@CrossOrigin
@RequestMapping(
    "/session/{sessionId}/execute",
    produces = [MediaType.APPLICATION_JSON_VALUE]
)
class ScriptController(
    private val sessionService: WebDriverSessionService
) {
    private val logger = LoggerFactory.getLogger(ScriptController::class.java)

    /**
     * Execute synchronous script.
     * POST /session/{sessionId}/execute/sync
     */
    @PostMapping("/sync", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun executeSync(
        @PathVariable sessionId: String,
        @RequestBody request: ExecuteScriptRequest
    ): ResponseEntity<Any> {
        logger.debug("Session {} executing sync script: {}", sessionId, request.script.take(100))
        
        if (!sessionService.sessionExists(sessionId)) {
            return notFound("invalid session id", "Session not found: $sessionId")
        }
        
        require(request.script.isNotBlank()) { "Script must not be blank" }
        
        // Mock: return a placeholder result
        val mockResult = mapOf(
            "executed" to true,
            "script" to request.script.take(50),
            "argsCount" to (request.args?.size ?: 0)
        )
        
        return ResponseEntity.ok(valueResponse(mockResult))
    }

    /**
     * Execute asynchronous script.
     * POST /session/{sessionId}/execute/async
     */
    @PostMapping("/async", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun executeAsync(
        @PathVariable sessionId: String,
        @RequestBody request: ExecuteScriptRequest
    ): ResponseEntity<Any> {
        logger.debug("Session {} executing async script: {}", sessionId, request.script.take(100))
        
        if (!sessionService.sessionExists(sessionId)) {
            return notFound("invalid session id", "Session not found: $sessionId")
        }
        
        require(request.script.isNotBlank()) { "Script must not be blank" }
        
        // Mock: return a placeholder result
        val mockResult = mapOf(
            "executed" to true,
            "async" to true,
            "script" to request.script.take(50),
            "argsCount" to (request.args?.size ?: 0)
        )
        
        return ResponseEntity.ok(valueResponse(mockResult))
    }

    private fun notFound(error: String, message: String): ResponseEntity<Any> {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(errorResponse(error, message))
    }
}
