package ai.platon.pulsar.rest.api.webdriver.controller

import ai.platon.pulsar.rest.api.webdriver.dto.*
import ai.platon.pulsar.rest.api.webdriver.store.InMemoryStore
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

/**
 * Controller for PerceptiveAgent operations.
 * Provides AI-powered browser automation capabilities.
 */
@RestController
@CrossOrigin
@RequestMapping(
    "/session/{sessionId}/agent",
    produces = [MediaType.APPLICATION_JSON_VALUE]
)
class AgentController(
    private val store: InMemoryStore
) {
    private val logger = LoggerFactory.getLogger(AgentController::class.java)

    /**
     * Runs an autonomous agent task.
     * The agent observes the page, acts, and repeats until the goal is achieved.
     */
    @PostMapping("/run", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun run(
        @PathVariable sessionId: String,
        @RequestBody request: AgentRunRequest,
        response: HttpServletResponse
    ): ResponseEntity<Any> {
        logger.debug("Session {} running agent task: {}", sessionId, request.task.take(100))
        ControllerUtils.addRequestId(response)

        if (!store.sessionExists(sessionId)) {
            return ControllerUtils.notFound("session not found", "No active session with id $sessionId")
        }

        // Mock implementation - returns success with mock history
        val result = AgentRunResult(
            success = true,
            message = "Agent task completed (mock)",
            historySize = 1,
            processTraceSize = 1
        )
        return ResponseEntity.ok(AgentRunResponse(value = result))
    }

    /**
     * Observes the current page and returns potential actions.
     */
    @PostMapping("/observe", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun observe(
        @PathVariable sessionId: String,
        @RequestBody request: AgentObserveRequest,
        response: HttpServletResponse
    ): ResponseEntity<Any> {
        logger.debug("Session {} observing page with instruction: {}", sessionId, request.instruction?.take(100))
        ControllerUtils.addRequestId(response)

        if (!store.sessionExists(sessionId)) {
            return ControllerUtils.notFound("session not found", "No active session with id $sessionId")
        }

        // Mock implementation - returns a sample observation result
        val observeResult = ObserveResultDto(
            locator = "0,1",
            domain = "driver",
            method = "click",
            arguments = mapOf("selector" to "button.submit"),
            description = "Click the submit button (mock)",
            thinking = "Analyzing page elements...",
            nextGoal = "Complete the form submission",
            summary = "Found interactive elements on page"
        )
        return ResponseEntity.ok(AgentObserveResponse(value = listOf(observeResult)))
    }

    /**
     * Executes a single action on the page.
     */
    @PostMapping("/act", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun act(
        @PathVariable sessionId: String,
        @RequestBody request: AgentActRequest,
        response: HttpServletResponse
    ): ResponseEntity<Any> {
        logger.debug("Session {} executing action: {}", sessionId, request.action.take(100))
        ControllerUtils.addRequestId(response)

        if (!store.sessionExists(sessionId)) {
            return ControllerUtils.notFound("session not found", "No active session with id $sessionId")
        }

        // Mock implementation - returns success
        val result = ActResultDto(
            success = true,
            message = "Action executed successfully (mock)",
            action = request.action,
            isComplete = false,
            expression = "driver.click(\"button.submit\")"
        )
        return ResponseEntity.ok(AgentActResponse(value = result))
    }

    /**
     * Extracts structured data from the page.
     */
    @PostMapping("/extract", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun extract(
        @PathVariable sessionId: String,
        @RequestBody request: AgentExtractRequest,
        response: HttpServletResponse
    ): ResponseEntity<Any> {
        logger.debug("Session {} extracting data with instruction: {}", sessionId, request.instruction.take(100))
        ControllerUtils.addRequestId(response)

        if (!store.sessionExists(sessionId)) {
            return ControllerUtils.notFound("session not found", "No active session with id $sessionId")
        }

        // Mock implementation - returns sample extracted data
        val result = ExtractResultDto(
            success = true,
            message = "Data extracted successfully (mock)",
            data = mapOf(
                "title" to "Sample Page Title",
                "description" to "Sample description extracted from the page"
            )
        )
        return ResponseEntity.ok(AgentExtractResponse(value = result))
    }

    /**
     * Summarizes the page content.
     */
    @PostMapping("/summarize", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun summarize(
        @PathVariable sessionId: String,
        @RequestBody request: AgentSummarizeRequest,
        response: HttpServletResponse
    ): ResponseEntity<Any> {
        logger.debug("Session {} summarizing page with instruction: {}", sessionId, request.instruction?.take(100))
        ControllerUtils.addRequestId(response)

        if (!store.sessionExists(sessionId)) {
            return ControllerUtils.notFound("session not found", "No active session with id $sessionId")
        }

        // Mock implementation - returns sample summary
        val summary = "This is a mock summary of the page content. In a real implementation, " +
                "the AI model would analyze the page and provide a comprehensive summary."
        return ResponseEntity.ok(AgentSummarizeResponse(value = summary))
    }

    /**
     * Clears the agent's history.
     */
    @PostMapping("/clearHistory")
    fun clearHistory(
        @PathVariable sessionId: String,
        response: HttpServletResponse
    ): ResponseEntity<Any> {
        logger.debug("Session {} clearing agent history", sessionId)
        ControllerUtils.addRequestId(response)

        if (!store.sessionExists(sessionId)) {
            return ControllerUtils.notFound("session not found", "No active session with id $sessionId")
        }

        // Mock implementation - returns success
        return ResponseEntity.ok(AgentClearHistoryResponse(value = true))
    }
}
