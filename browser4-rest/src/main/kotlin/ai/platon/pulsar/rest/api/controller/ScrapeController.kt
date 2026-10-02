package ai.platon.pulsar.rest.api.controller

import ai.platon.pulsar.agentic.tools.advanced.crawl.ScrapeRequest
import ai.platon.pulsar.agentic.tools.advanced.crawl.ScrapeResponse
import ai.platon.pulsar.agentic.tools.advanced.crawl.ScrapeStatusRequest
import ai.platon.pulsar.agentic.tools.advanced.crawl.common.ScrapeAPIUtils
import ai.platon.pulsar.rest.api.service.ScrapeService
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.codec.ServerSentEvent
import org.springframework.web.bind.annotation.*
import reactor.core.publisher.Flux

/**
 * Scraping service allows the user post an X-SQL or a URL to scrape a web page.
 * */
@RestController
@CrossOrigin
@RequestMapping(
    "api/x",
    consumes = [MediaType.ALL_VALUE],
    produces = [MediaType.APPLICATION_JSON_VALUE]
)
class ScrapeController(
    val scrapeService: ScrapeService
) {
    private val logger = LoggerFactory.getLogger(ScrapeController::class.java)

    /**
     * A malformed payload is the caller's mistake, not a server failure.
     *
     * Both `/api/x/submit` branches below throw [IllegalArgumentException] for a payload they refuse
     * (a malformed url, a statement that is not a single SELECT).  Without a handler Spring answers
     * 500 for those, which tells the caller to retry something that can never succeed — the crawl and
     * swarm endpoints have mapped the same exception to 400 all along.
     */
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler(IllegalArgumentException::class)
    fun handleBadRequest(e: IllegalArgumentException): Map<String, Any> {
        logger.warn("Bad scrape request: {}", e.message)
        return mapOf("error" to "Bad Request", "message" to (e.message ?: ""))
    }
    /**
     * @param sql The SQL to execute
     * @return The response
     * */
    @PostMapping("execute")
    fun execute(@RequestBody sql: String): ScrapeResponse {
        return scrapeService.executeQuery(ScrapeRequest(sql))
    }

    /**
     * @param sql The SQL to execute
     * @return The response
     * */
    @PostMapping("/e")
    fun executeLegacy(@RequestBody sql: String): ScrapeResponse {
        return execute(sql)
    }

    /**
     * Submit a URL to scrape or submit an X-SQL to execute
     *
     * TODO: check if the task should be submitted to the URLPool
     *
     * @param payload The url to scrape or an X-SQL to execute
     * */
    @PostMapping("submit")
    fun submit(@RequestBody payload: String): String {
        val payload = payload.trim()

        val sql = if (payload.startsWith("http")) {
            // The payload is a url plus optional LoadOptions ("<url> -expires 1d
            // -requireNotBlank '#productTitle'"), and it is embedded in an X-SQL string literal, so
            // both halves have to be handled here: the url has to be valid *now* instead of in the
            // job half an hour later, and the literal has to be escaped.  Entry-page hrefs carry
            // apostrophes, and interpolating one raw broke the statement — reported as "Invalid URL
            // or X-SQL" about a perfectly good url — while letting the url text escape the literal.
            // Both belong to the shared implementation, see ScrapeAPIUtils.
            ScrapeAPIUtils.requireStandardUrl(payload)
            val literal = ScrapeAPIUtils.escapeSqlStringLiteral(payload)
            "select dom_base_uri(dom) as url from load_and_select('$literal', ':root')"
        } else payload

        runCatching { ScrapeAPIUtils.checkSql(sql) }.onFailure {
            throw IllegalArgumentException("Invalid URL or X-SQL: >>>$payload<<<")
        }

        // return the UUID which can be used to retrieve the scrape result later
        return scrapeService.submitJob(ScrapeRequest(sql))
    }

    /**
     * @param sql The SQL to execute
     * @return The uuid of the scrape task
     * */
    @PostMapping("s")
    fun submitLegacy(@RequestBody sql: String): String {
        return submit(sql)
    }

    /**
     * @param status The status of the scrape task to be counted
     * @return The execution result
     * */
    @GetMapping("count", consumes = [MediaType.ALL_VALUE])
    fun count(
        @RequestParam(value = "status", required = false) status: Int = 0
    ): Int {
        return scrapeService.count(status)
    }

    /**
     * @param status The status of the scrape task to be counted
     * @return The execution result
     * */
    @GetMapping("c", consumes = [MediaType.ALL_VALUE])
    fun countLegacy(
        @RequestParam(value = "status", required = false) status: Int = 0
    ): Int {
        return count(status)
    }

    /**
     * @param uuid The uuid of the task last submitted
     * @return The execution result
     * */
    @GetMapping("status", consumes = [MediaType.ALL_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE])
    fun status(
        @RequestParam(value = "uuid") uuid: String
    ): ScrapeResponse {
        val request = ScrapeStatusRequest(uuid)
        return scrapeService.getStatus(request)
    }

    @GetMapping("/{id}/status", consumes = [MediaType.ALL_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE])
    fun getStatus(
        @PathVariable(value = "id") uuid: String,
        httpRequest: HttpServletRequest,
    ): ScrapeResponse {
        val request = ScrapeStatusRequest(uuid)
        return scrapeService.getStatus(request)
    }

    @GetMapping("/{id}/result", consumes = [MediaType.ALL_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE])
    fun getResult(
        @PathVariable(value = "id") uuid: String,
        httpRequest: HttpServletRequest,
    ): ScrapeResponse {
        return getStatus(uuid, httpRequest)
    }

    @GetMapping(value = ["/{id}/stream"], produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun streamEvents(@PathVariable id: String): Flux<ServerSentEvent<ScrapeResponse>> {
        return scrapeService.streamEvents(id)
    }
}
