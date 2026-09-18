package ai.platon.pulsar.rest.api.controller

import ai.platon.pulsar.agentic.tools.advanced.crawl.QueryRequest
import ai.platon.pulsar.agentic.tools.advanced.crawl.ScrapeRequest
import ai.platon.pulsar.agentic.tools.advanced.crawl.ScrapeResponse
import ai.platon.pulsar.agentic.tools.advanced.crawl.ScrapeStatusRequest
import ai.platon.pulsar.agentic.tools.advanced.crawl.SwarmFacade
import ai.platon.pulsar.agentic.tools.advanced.crawl.SwarmFacadeRegistry
import ai.platon.pulsar.agentic.tools.advanced.crawl.common.ScrapeAPIUtils
import ai.platon.pulsar.common.B4Constants.SWARM_SESSION_ID
import ai.platon.pulsar.rest.session.PulsarSessionManager
import ai.platon.pulsar.rest.api.entities.SessionResponse
import ai.platon.pulsar.rest.api.entities.toSessionResponse
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
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
    val sessionManager: PulsarSessionManager,
) {
    private val logger = LoggerFactory.getLogger(SwarmController::class.java)

    /**
     * The swarm backend is provided by the browser4-swarm plugin through
     * [SwarmFacadeRegistry]. Without the plugin we respond with a clear
     * "not installed" error instead of a partial implementation.
     */
    private fun facade(): SwarmFacade = SwarmFacadeRegistry.instance.get()
        ?: throw SwarmNotInstalledException()

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
    fun open(@RequestBody capabilities: Map<String, String?>?): SessionResponse {
        return sessionManager.ensureSwarmSession(capabilities).toSessionResponse()
    }

    /**
     * Close the swarm session and abort all of its pending tasks.
     *
     * Pending tasks belong to the live swarm session and can never be consumed
     * after it closes; without this cleanup they stay "queued" forever and leak
     * across sessions. Closed tasks are marked as failed with a clear reason.
     *
     * @return The number of aborted pending tasks.
     * */
    @DeleteMapping
    fun close(): Map<String, Any> {
        // The swarm session is core infrastructure owned by the REST session
        // manager; the facade only owns the task store, so abort its pending
        // tasks here and release the session below.
        val aborted = facade().abortPendingTasks("Swarm session was closed; task dropped")
        runCatching { sessionManager.deleteSession(SWARM_SESSION_ID) }
            .onFailure { logger.warn("Failed to close the swarm session: {}", it.message) }
        logger.info("Swarm session closed, {} pending task(s) aborted", aborted)
        return mapOf("closed" to true, "abortedPendingTasks" to aborted)
    }

    /**
     * Submit a URL to scrape or submit an X-SQL to execute
     *
     * @param payload The url to scrape or an X-SQL to execute
     * @param batchId Optional id shared by every task of one batch submission;
     *        tasks carrying it can be tracked and summarised as a group.
     * */
    @PostMapping("submit")
    fun submit(
        @RequestBody payload: String,
        @RequestParam(value = "batchId", required = false) batchId: String? = null,
    ): String {
        if (payload.isBlank()) {
            throw IllegalArgumentException("Request body must be a non-blank URL or X-SQL")
        }

        val payload = payload.trim()
        logger.info("Swarm submit: batch={} payload='{}'", batchId, payload.take(200))

        val sql = if (payload.startsWith("http")) {
            // The payload is a URL plus optional LoadOptions ("<url> -expires 1d
            // -requireNotBlank '#productTitle'"), and it is embedded in an X-SQL
            // string literal.  Entry-page hrefs legitimately contain apostrophes,
            // and an unescaped quote would both break the statement and let the
            // URL text escape the literal — escape it instead of rejecting it.
            val literal = escapeSqlStringLiteral(payload)
            "select dom_base_uri(dom) as url from load_and_select('$literal', ':root')"
        } else payload

        runCatching { ScrapeAPIUtils.checkSql(sql) }.onFailure {
            throw IllegalArgumentException("Invalid URL or X-SQL: >>>$payload<<<")
        }

        // Returns raw UUID string (not JSON-wrapped). CLI depends on this format.
        val normalizedBatchId = batchId?.trim()?.takeIf { it.isNotEmpty() }
        return facade().submit(ScrapeRequest(sql, normalizedBatchId), normalizedBatchId)
    }

    /** Escape a value for use inside a single-quoted X-SQL string literal. */
    private fun escapeSqlStringLiteral(value: String): String =
        value.replace("'", "''").replace("\r", " ").replace("\n", " ")

    /**
     * Submit an X-SQL query to execute against a loaded webpage.
     *
     * The query is an X-SQL statement with @url placeholder that will be substituted
     * with the provided url and args. For example:
     * ```
     * SELECT dom_base_uri(dom) AS url, dom_first_text(dom, '#title') AS title
     * FROM load_and_select(@url, 'body');
     * ```
     *
     * @param query The query request containing url, args, and the X-SQL query
     * @return The task UUID for tracking the query execution
     */
    @PostMapping("query")
    fun query(@RequestBody query: QueryRequest): String {
        logger.info("Swarm query: batch='{}' url='{}' query='{}'", query.batchId, query.url, query.query.take(200))
        return facade().submit(query)
    }

    /**
     * Aggregate status of one batch submission: task counts, the batch's
     * wall-clock window and per-task rows (each with its duration).
     *
     * A batch id is assigned by the caller when submitting (the CLI generates one
     * per `swarm submit` / `swarm query` invocation and stamps every task with
     * it); this endpoint answers "is my batch done, and how long did it take?"
     * in a single request instead of one status call per task.
     * */
    @GetMapping("/batch/{batchId}", consumes = [MediaType.ALL_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE])
    fun batchStatus(
        @PathVariable(value = "batchId") batchId: String,
    ): Map<String, Any?> {
        if (batchId.isBlank()) {
            throw IllegalArgumentException("batchId must not be blank")
        }
        return facade().batchStatus(batchId)
    }

    /**
     * @param status The status of the scrape task to be counted
     * @return The execution result
     * */
    @GetMapping("count", consumes = [MediaType.ALL_VALUE])
    fun count(
        @RequestParam(value = "status", required = false) status: Int = 0,
    ): Int {
        return facade().count(status)
    }

    /**
     * @param uuid The uuid of the task last submitted
     * @return The execution result
     * */
    @GetMapping("status", consumes = [MediaType.ALL_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE])
    fun status(
        @RequestParam(value = "uuid") uuid: String,
    ): ScrapeResponse {
        if (uuid.isBlank()) {
            throw IllegalArgumentException("uuid must not be blank")
        }
        val request = ScrapeStatusRequest(uuid)
        return facade().getStatus(request)
    }

    @GetMapping("/{id}/status", consumes = [MediaType.ALL_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE])
    fun getStatus(
        @PathVariable(value = "id") uuid: String,
    ): ScrapeResponse {
        if (uuid.isBlank()) {
            throw IllegalArgumentException("id must not be blank")
        }
        val request = ScrapeStatusRequest(uuid)
        return facade().getStatus(request)
    }

    @GetMapping("/{id}/result", consumes = [MediaType.ALL_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE])
    fun getResult(
        @PathVariable(value = "id") uuid: String,
    ): ScrapeResponse {
        if (uuid.isBlank()) {
            throw IllegalArgumentException("id must not be blank")
        }
        return getStatus(uuid)
    }

    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler(IllegalArgumentException::class)
    fun handleBadRequest(e: IllegalArgumentException): Map<String, Any> {
        logger.warn("Bad request: {}", e.message)
        return mapOf("error" to "Bad Request", "message" to (e.message ?: ""))
    }

    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    @ExceptionHandler(SwarmNotInstalledException::class)
    fun handleSwarmNotInstalled(e: SwarmNotInstalledException): Map<String, Any> {
        logger.warn("Swarm backend unavailable: {}", e.message)
        return mapOf("error" to "Swarm not installed", "message" to (e.message ?: ""))
    }
}
