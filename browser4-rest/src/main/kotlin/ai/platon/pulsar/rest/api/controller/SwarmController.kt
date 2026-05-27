package ai.platon.pulsar.rest.api.controller

import ai.platon.pulsar.agentic.tools.advanced.crawl.ScrapeRequest
import ai.platon.pulsar.agentic.tools.advanced.crawl.ScrapeResponse
import ai.platon.pulsar.agentic.tools.advanced.crawl.common.ScrapeAPIUtils
import ai.platon.pulsar.common.SessionManager
import ai.platon.pulsar.rest.api.entities.ScrapeStatusRequest
import ai.platon.pulsar.rest.api.entities.SessionResponse
import ai.platon.pulsar.rest.api.entities.toSessionResponse
import ai.platon.pulsar.rest.api.service.SwarmService
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.*

@RestController
@CrossOrigin
@RequestMapping(
    "api/swarm",
    consumes = [MediaType.ALL_VALUE],
    produces = [MediaType.APPLICATION_JSON_VALUE]
)
class SwarmController(
    val sessionManager: SessionManager,
    val swarmService: SwarmService
) {
    /**
     * Create or get the swarm session. The swarm session is a special session that can be shared across multiple
     * requests and has non-permanent profiles.
     *
     * The swarm session is designed to be shared across multiple requests, so it should not be recreated if it already
     * exists. The capabilities can be used to create the swarm session profile if the swarm session does not exist, or
     * will be ignored if the swarm session is already exists.
     *
     * @param capabilities The capabilities to create or update the swarm session profile
     * */
    @PostMapping
    fun getOrCreate(@RequestBody capabilities: Map<String, Any?>): SessionResponse {
        return sessionManager.ensureSwarmSession(capabilities).toSessionResponse()
    }

    /**
     * Submit a URL to scrape or submit an X-SQL to execute
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
        return swarmService.submit(ScrapeRequest(sql))
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
        return swarmService.count(status)
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
        return swarmService.getStatus(request)
    }

    @GetMapping("/{id}/status", consumes = [MediaType.ALL_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE])
    fun getStatus(
        @PathVariable(value = "id") uuid: String,
        httpRequest: HttpServletRequest,
    ): ScrapeResponse {
        val request = ScrapeStatusRequest(uuid)
        return swarmService.getStatus(request)
    }

    @GetMapping("/{id}/result", consumes = [MediaType.ALL_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE])
    fun getResult(
        @PathVariable(value = "id") uuid: String,
        httpRequest: HttpServletRequest,
    ): ScrapeResponse {
        return getStatus(uuid, httpRequest)
    }
}
