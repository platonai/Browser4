package ai.platon.pulsar.rest.api.controller

import ai.platon.pulsar.agentic.tools.advanced.crawl.ScrapeRequest
import ai.platon.pulsar.agentic.tools.advanced.crawl.ScrapeResponse
import ai.platon.pulsar.agentic.tools.advanced.crawl.common.ScrapeAPIUtils
import ai.platon.pulsar.rest.api.entities.ScrapeStatusRequest
import ai.platon.pulsar.rest.api.service.ScrapeService
import jakarta.servlet.http.HttpServletRequest
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
            "select dom_base_uri(dom) as url from load_and_select('$payload', ':root')"
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
        @RequestParam(value = "status", required = false) status: Int = 0,
        httpRequest: HttpServletRequest,
    ): Int {
        return scrapeService.count(status)
    }

    /**
     * @param status The status of the scrape task to be counted
     * @return The execution result
     * */
    @GetMapping("c", consumes = [MediaType.ALL_VALUE])
    fun countLegacy(
        @RequestParam(value = "status", required = false) status: Int = 0,
        httpRequest: HttpServletRequest,
    ): Int {
        return count(status, httpRequest)
    }

    /**
     * @param uuid The uuid of the task last submitted
     * @return The execution result
     * */
    @GetMapping("status", consumes = [MediaType.ALL_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE])
    fun status(
        @RequestParam(value = "uuid") uuid: String,
        httpRequest: HttpServletRequest,
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
