package ai.platon.pulsar.rest.api.controller

import ai.platon.pulsar.rest.api.service.CrawlRequest
import ai.platon.pulsar.rest.api.service.CrawlResponse
import ai.platon.pulsar.rest.api.service.CrawlService
import ai.platon.pulsar.rest.session.PulsarSessionManager
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.*

@RestController
@CrossOrigin
@RequestMapping(
    "api/crawl",
    consumes = [MediaType.ALL_VALUE],
    produces = [MediaType.APPLICATION_JSON_VALUE]
)
class CrawlController(
    val sessionManager: PulsarSessionManager,
    val crawlService: CrawlService
) {
    private val logger = LoggerFactory.getLogger(CrawlController::class.java)

    /**
     * Start a new crawl task.
     *
     * @param request The crawl configuration: starting URL, LoadOptions args string, and depth
     * @return The task UUID for polling status/result
     */
    @PostMapping
    fun startCrawl(@RequestBody request: CrawlRequest): String {
        if (request.url.isBlank() && request.urls.isNullOrEmpty()) {
            throw IllegalArgumentException("url or urls must not be blank")
        }
        if (request.depth < 0) {
            throw IllegalArgumentException("depth must be >= 0, got ${request.depth}")
        }
        logger.info("Crawl request: url='{}' seeds={} depth={} args='{}' sql={}",
            request.url, request.urls?.size ?: 0, request.depth, request.args, request.sql != null)
        return crawlService.submit(request)
    }

    /**
     * Get the status or result of a crawl task.
     *
     * @param id The task UUID returned by [startCrawl]
     * @return The current crawl response (status + partial/final results)
     */
    @GetMapping("/{id}/status", consumes = [MediaType.ALL_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE])
    fun getStatus(@PathVariable(value = "id") taskId: String): CrawlResponse {
        if (taskId.isBlank()) {
            throw IllegalArgumentException("id must not be blank")
        }
        return crawlService.getResult(taskId)
    }

    /**
     * Get the final result of a crawl task. Same as [getStatus] but semantically
     * indicates the caller expects the crawl to be finished.
     *
     * @param id The task UUID returned by [startCrawl]
     * @return The crawl response with pages when complete
     */
    @GetMapping("/{id}/result", consumes = [MediaType.ALL_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE])
    fun getResult(@PathVariable(value = "id") taskId: String): CrawlResponse {
        if (taskId.isBlank()) {
            throw IllegalArgumentException("id must not be blank")
        }
        return crawlService.getResult(taskId)
    }

    /**
     * Cancel a running crawl task.
     *
     * @param id The task UUID returned by [startCrawl]
     * @return true if the task was found and cancelled
     */
    @PostMapping("/{id}/cancel", consumes = [MediaType.ALL_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE])
    fun cancelCrawl(@PathVariable(value = "id") taskId: String): Map<String, Any> {
        if (taskId.isBlank()) {
            throw IllegalArgumentException("id must not be blank")
        }
        val cancelled = crawlService.cancel(taskId)
        return mapOf("taskId" to taskId, "cancelled" to cancelled)
    }

    /**
     * Remove all terminal-state tasks from the crawl task store.
     *
     * @return the number of tasks removed
     */
    @PostMapping("/clear", consumes = [MediaType.ALL_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE])
    fun clearCrawls(): Map<String, Any> {
        val count = crawlService.clearTerminal()
        return mapOf("cleared" to count)
    }

    /**
     * Remove ALL tasks from the crawl task store, including actively-running ones.
     * Cancels running jobs before clearing.  Use with caution.
     *
     * @return the number of tasks removed
     */
    @PostMapping("/clear-all", consumes = [MediaType.ALL_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE])
    fun clearAllCrawls(): Map<String, Any> {
        val count = crawlService.clearAll()
        return mapOf("cleared" to count)
    }

    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler(IllegalArgumentException::class)
    fun handleBadRequest(e: IllegalArgumentException): Map<String, Any> {
        logger.warn("Bad crawl request: {}", e.message)
        return mapOf("error" to "Bad Request", "message" to (e.message ?: ""))
    }
}
