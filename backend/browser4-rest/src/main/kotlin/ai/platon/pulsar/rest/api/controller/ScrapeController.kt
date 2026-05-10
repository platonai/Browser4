package ai.platon.pulsar.rest.api.controller

import ai.platon.pulsar.agentic.tools.high.crawl.ScrapeRequest
import ai.platon.pulsar.agentic.tools.high.crawl.ScrapeResponse
import ai.platon.pulsar.rest.api.entities.ScrapeStatusRequest
import ai.platon.pulsar.rest.api.service.ScrapeService
import jakarta.servlet.http.HttpServletRequest
import org.springframework.context.ApplicationContext
import org.springframework.http.MediaType
import org.springframework.http.codec.ServerSentEvent
import org.springframework.web.bind.annotation.*
import reactor.core.publisher.Flux

@RestController
@CrossOrigin
@RequestMapping(
    "api/x",
    consumes = [MediaType.ALL_VALUE],
    produces = [MediaType.APPLICATION_JSON_VALUE]
)
class ScrapeController(
    val applicationContext: ApplicationContext,
    val scrapeService: ScrapeService,
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
     * @param sql The SQL to execute
     * @return The uuid of the scrape task
     * */
    @PostMapping("submit")
    fun submitJob(@RequestBody sql: String): String {
        return scrapeService.submitJob(ScrapeRequest(sql))
    }

    /**
     * @param sql The SQL to execute
     * @return The uuid of the scrape task
     * */
    @PostMapping("s")
    fun submitJobLegacy(@RequestBody sql: String): String {
        return submitJob(sql)
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

    @GetMapping(value = ["/{id}/stream"], produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun streamEvents(@PathVariable id: String): Flux<ServerSentEvent<ScrapeResponse>> {
        return scrapeService.streamEvents(id)
    }
}
