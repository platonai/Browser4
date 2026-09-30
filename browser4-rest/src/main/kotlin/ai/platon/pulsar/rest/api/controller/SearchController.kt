package ai.platon.pulsar.rest.api.controller

import ai.platon.pulsar.rest.api.service.search.SearchRequest
import ai.platon.pulsar.rest.api.service.search.SearchResponse
import ai.platon.pulsar.rest.api.service.search.SearchService
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.*

/**
 * REST endpoints for the search API.
 *
 * Mirrors [CrawlController]'s shape: POST submits a task and returns the UUID
 * for polling; GET `/{id}/status` and `/{id}/result` read it back; POST
 * `/{id}/cancel` stops a running task.  Terminal tasks can be dropped with
 * `/clear` (preserves none here — search has no checkpoint to keep) or
 * `/clear-all` (cancels running tasks too).
 */
@RestController
@CrossOrigin
@RequestMapping(
    "api/search",
    consumes = [MediaType.ALL_VALUE],
    produces = [MediaType.APPLICATION_JSON_VALUE]
)
class SearchController(
    val searchService: SearchService
) {
    private val logger = LoggerFactory.getLogger(SearchController::class.java)

    /**
     * Submit a search task. Returns the task UUID immediately; poll
     * [getStatus]/[getResult] to read the outcome.
     */
    @PostMapping
    fun submitSearch(@RequestBody request: SearchRequest): String {
        if (request.query.isBlank()) {
            throw IllegalArgumentException("query must not be blank")
        }
        if (request.maxResults <= 0) {
            throw IllegalArgumentException("maxResults must be > 0, got ${request.maxResults}")
        }
        logger.info(
            "Search request: query='{}' maxResults={} scrape={} topic={} timeRange={} timeout={}ms",
            request.query, request.maxResults, request.scrape, request.topic,
            request.timeRange, request.taskTimeoutMillis
        )
        return searchService.submit(request)
    }

    /**
     * Get the status or partial result of a search task.
     */
    @GetMapping("/{id}/status", consumes = [MediaType.ALL_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE])
    fun getStatus(@PathVariable(value = "id") taskId: String): SearchResponse {
        if (taskId.isBlank()) {
            throw IllegalArgumentException("id must not be blank")
        }
        return searchService.getResult(taskId)
    }

    /**
     * Get the final result of a search task. Same as [getStatus] but semantically
     * indicates the caller expects the task to be finished.
     */
    @GetMapping("/{id}/result", consumes = [MediaType.ALL_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE])
    fun getResult(@PathVariable(value = "id") taskId: String): SearchResponse {
        if (taskId.isBlank()) {
            throw IllegalArgumentException("id must not be blank")
        }
        return searchService.getResult(taskId)
    }

    /**
     * Cancel a running search task.
     *
     * @return `cancelled=true` if the task was found and cancelled; `false` otherwise.
     */
    @PostMapping("/{id}/cancel", consumes = [MediaType.ALL_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE])
    fun cancelSearch(@PathVariable(value = "id") taskId: String): Map<String, Any> {
        if (taskId.isBlank()) {
            throw IllegalArgumentException("id must not be blank")
        }
        val cancelled = searchService.cancel(taskId)
        return mapOf("taskId" to taskId, "cancelled" to cancelled)
    }

    /**
     * Remove all terminal-state tasks from the search task store.
     */
    @PostMapping("/clear", consumes = [MediaType.ALL_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE])
    fun clearSearches(): Map<String, Any> {
        val count = searchService.clearTerminal()
        return mapOf("cleared" to count)
    }

    /**
     * Remove ALL tasks from the search task store, including actively-running ones.
     * Cancels running jobs before clearing.  Use with caution.
     */
    @PostMapping("/clear-all", consumes = [MediaType.ALL_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE])
    fun clearAllSearches(): Map<String, Any> {
        val count = searchService.clearAll()
        return mapOf("cleared" to count)
    }

    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler(IllegalArgumentException::class)
    fun handleBadRequest(e: IllegalArgumentException): Map<String, Any> {
        logger.warn("Bad search request: {}", e.message)
        return mapOf("error" to "Bad Request", "message" to (e.message ?: ""))
    }

    @ResponseStatus(HttpStatus.CONFLICT)
    @ExceptionHandler(IllegalStateException::class)
    fun handleConflict(e: IllegalStateException): Map<String, Any> {
        logger.warn("Search request conflict: {}", e.message)
        return mapOf("error" to "Conflict", "message" to (e.message ?: ""))
    }
}
