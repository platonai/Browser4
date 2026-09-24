package ai.platon.pulsar.rest.api.controller

import ai.platon.pulsar.rest.api.service.crawl.CrawlRequest
import ai.platon.pulsar.rest.api.service.crawl.CrawlResponse
import ai.platon.pulsar.rest.api.service.crawl.CrawlResumeResult
import ai.platon.pulsar.rest.api.service.crawl.CrawlService
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
        // A non-positive budget is clamped to the server default rather than
        // rejected (see CrawlService.resolveParallelTabs), but a caller that
        // explicitly asks for more tabs than the server will ever hand out
        // should hear about it instead of silently getting a smaller crawl.
        request.parallelTabs?.let {
            if (it > CrawlService.MAX_PARALLEL_TABS) {
                throw IllegalArgumentException(
                    "parallelTabs must be <= ${CrawlService.MAX_PARALLEL_TABS}, got $it"
                )
            }
        }
        logger.info(
            "Crawl request: url='{}' seeds={} depth={} args='{}' sql={} parallelTabs={}",
            request.url, request.urls?.size ?: 0, request.depth, request.args, request.sql != null,
            crawlService.resolveParallelTabs(request)
        )
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
     * Continue an interrupted crawl from its checkpoint.
     *
     * The task keeps its id: a resume is the same task continuing, not a new one.
     * URLs the first run already fetched are not requested again, the URLs that were
     * in flight and the discovered frontier are re-submitted, and terminally failed
     * URLs stay failed unless `retryFailed` asks for them.
     *
     * @param id the task UUID returned by [startCrawl]
     * @param force resume a task that is already in a successful terminal state
     * @param retryFailed re-submit the URLs that failed terminally before
     * @return what the call did; `resumed = false` carries the reason
     */
    @PostMapping("/{id}/resume", consumes = [MediaType.ALL_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE])
    fun resumeCrawl(
        @PathVariable(value = "id") taskId: String,
        @RequestParam(value = "force", defaultValue = "false") force: Boolean,
        @RequestParam(value = "retryFailed", defaultValue = "false") retryFailed: Boolean,
    ): CrawlResumeResult {
        if (taskId.isBlank()) {
            throw IllegalArgumentException("id must not be blank")
        }
        val result = crawlService.resume(taskId, force = force, retryFailed = retryFailed)
        logger.info(
            "Crawl resume request: id={} force={} retryFailed={} → resumed={} ({})",
            taskId, force, retryFailed, result.resumed, result.message
        )
        return result
    }

    /**
     * Remove all terminal-state tasks from the crawl task store.
     *
     * A checkpoint that still has work left is kept, so a cleared timed-out task can
     * still be resumed; `clear-all` is the request that discards resumable state.
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

    /**
     * A resume that conflicts with a running operation is a `409`, not a `500`: the
     * request was well-formed and the task exists, it just cannot be resumed while
     * its worker is alive (two workers on one checkpoint would fetch everything
     * twice).
     */
    @ResponseStatus(HttpStatus.CONFLICT)
    @ExceptionHandler(IllegalStateException::class)
    fun handleConflict(e: IllegalStateException): Map<String, Any> {
        logger.warn("Crawl request conflict: {}", e.message)
        return mapOf("error" to "Conflict", "message" to (e.message ?: ""))
    }
}
