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
        if (request.url.isBlank()) {
            throw IllegalArgumentException("url must not be blank")
        }
        if (request.depth < 1) {
            throw IllegalArgumentException("depth must be >= 1, got ${request.depth}")
        }
        logger.info("Crawl request: url='{}' depth={} args='{}'", request.url, request.depth, request.args)
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

    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler(IllegalArgumentException::class)
    fun handleBadRequest(e: IllegalArgumentException): Map<String, Any> {
        logger.warn("Bad crawl request: {}", e.message)
        return mapOf("error" to "Bad Request", "message" to (e.message ?: ""))
    }
}
