package ai.platon.pulsar.rest.api.controller

import ai.platon.pulsar.agentic.tools.advanced.format.FormatFailureException
import ai.platon.pulsar.common.serialize.json.pulsarObjectMapper
import ai.platon.pulsar.rest.api.service.scrape.ArtifactStore
import ai.platon.pulsar.rest.api.service.scrape.PageScrapeService
import ai.platon.pulsar.skeleton.workflow.format.FormatOptionSchema
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.core.type.TypeReference
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.CrossOrigin
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.nio.file.Files

/**
 * The Firecrawl-compatible formats layer over HTTP.
 *
 * This is a **thin shell**: [PageScrapeService] holds the request-level logic and
 * the MCP tool domain `page` already exposes the same service, so this controller
 * only translates a JSON body into a call and the document into the
 * `{success, data}` envelope. Anything decided here would be decided twice.
 *
 * `POST /api/scrape` — one request, one capture, many outputs.
 * `GET  /api/scrape/formats` — capability discovery.
 * `GET  /api/scrape/media/{name}` — the bytes of an artifact the document pointed at.
 *
 * ## Not here yet
 *
 * The asynchronous face from the design draft
 * (`/api/scrape/submit` + `/{id}/status|result|stream`) is **not** implemented.
 * Every format this build can currently deliver answers from one capture in one
 * synchronous pass; the async surface exists for media downloads and LLM calls,
 * which are Phase 3. Adding it now would be an untested envelope with no caller.
 *
 * @property pageScrapeService the shared request pipeline.
 */
@RestController
@CrossOrigin
@RequestMapping(
    "api/scrape",
    consumes = [MediaType.ALL_VALUE],
    produces = [MediaType.APPLICATION_JSON_VALUE]
)
class PageScrapeController(
    private val pageScrapeService: PageScrapeService,
) {
    private val logger = LoggerFactory.getLogger(PageScrapeController::class.java)

    /**
     * The artifact layout, shared with the runner that writes the files.
     *
     * A second instance rather than an injected one, and not a second source of truth:
     * [ArtifactStore] is stateless and derives its kind table from `AppPaths`, so
     * instances are interchangeable by construction.
     */
    private val artifactStore = ArtifactStore()

    /**
     * A refused payload is the caller's mistake, not a server failure.
     *
     * `FormatOptionSchema.requireValid()` throws [IllegalArgumentException] for an
     * unknown format id or an illegal combination, and a typo must come back as 400
     * so the caller can fix it — never as a 500, which would suggest retrying.
     */
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler(IllegalArgumentException::class)
    fun handleBadRequest(e: IllegalArgumentException): Map<String, Any> {
        logger.warn("Bad page scrape request: {}", e.message)
        return mapOf("success" to false, "error" to "Bad Request", "message" to (e.message ?: ""))
    }

    /**
     * A `strict` request that could not be satisfied.
     *
     * The status is taken from the failure's own `ToolErrorCode` — 503 for a format this
     * deployment cannot deliver, 502/504 for one that ran and failed, 400 for a request
     * this build cannot honour — rather than from a table here. The layer that classified
     * the failure is the one that knows why, and a second mapping would drift away from
     * `ToolErrorMapper`, which the MCP channel also reports through.
     *
     * `hint` and `retryable` travel with the code because a caller's next move depends on
     * them: retrying a 503 is pointless, retrying a 502 is not.
     */
    @ExceptionHandler(FormatFailureException::class)
    fun handleFormatFailure(e: FormatFailureException): ResponseEntity<Map<String, Any?>> {
        logger.warn("Strict page scrape could not be satisfied: {}", e.message)
        return ResponseEntity.status(e.code.httpStatus).body(
            linkedMapOf(
                "success" to false,
                "error" to e.code.wire,
                "retryable" to e.code.retryable,
                "hint" to e.code.hint,
                "message" to (e.message ?: ""),
            )
        )
    }

    /**
     * Scrape once and return every requested format.
     *
     * Validation happens before the service is called, so an unknown id or an
     * illegal combination is refused without paying for a page load.
     *
     * @param request the formats to produce and the request-level options.
     * @return `{success, data}` where `data` is the Firecrawl-shaped document.
     */
    @PostMapping
    suspend fun scrape(@RequestBody request: PageScrapeRequestBody): Map<String, Any?> {
        // Kept in the DTO only so this can be refused by name. The field cannot be
        // quietly dropped: Jackson ignores unknown properties here, so removing it
        // would make `{"url": …}` silently scrape the *active* page instead — the
        // exact failure this guard exists to prevent.
        require(request.url == null) {
            "'url' is not supported yet: a request-level URL was forwarded but the reads always " +
                "targeted the session's active page, which returned a document about the wrong page. " +
                "Open the page first (`open <url>`), then scrape it. Per-request URL targeting arrives " +
                "with Stage 0 (read-only load on the shared scrape session)."
        }

        val formats = FormatOptionSchema.parse(request.formats.orEmpty()).requireValid()

        val document = pageScrapeService.scrape(
            formats = formats,
            sessionId = request.sessionId,
            onlyMainContent = request.onlyMainContent ?: true,
            strict = request.strict ?: false,
        )

        return mapOf(
            "success" to true,
            "data" to pulsarObjectMapper().convertValue<Map<String, Any?>>(document, DOCUMENT_TYPE),
        )
    }

    /**
     * What this deployment can deliver, and why not when it cannot.
     *
     * `available` is a configuration answer, not a promise — a registered
     * contributor whose service is up now can still fail at call time, and that is
     * reported per request in the document's `warning`.
     */
    @GetMapping("formats")
    fun formats(): Map<String, Any?> = mapOf(
        "success" to true,
        "data" to pageScrapeService.formats(),
    )

    /**
     * Serve back the bytes of an artifact a scrape returned.
     *
     * A scrape answers with a **path on this host** — that is the artifact policy, and
     * the honest shape for a self-hosted tool — but a caller on another machine cannot
     * dereference it. This route is that caller's way in: take the file name out of the
     * returned path and ask for it here.
     *
     * Only a **name** is ever accepted, never a path; [ArtifactStore] explains why the
     * rules are refusals rather than sanitising. A string that cannot be an artifact name
     * is the caller's mistake and comes back as 400 through the handler above, while a
     * well-formed name that is not there is 404.
     *
     * The design draft had this as `/api/scrape/{id}/media/{name}`, which assumed the
     * asynchronous face. There is no `{id}` to have: this request is synchronous and
     * stateless, so the artifact's own name is its identity.
     *
     * @param name the artifact file name, e.g. `pdf-20261007-014500-123-ab12cd34.pdf`.
     * @return the bytes, with the artifact's media type and a `Content-Disposition`
     *   naming the file.
     */
    @GetMapping("media/{name}")
    fun media(@PathVariable name: String): ResponseEntity<ByteArray> {
        val path = artifactStore.resolve(name) ?: return artifactNotFound(name)

        val bytes = runCatching { Files.readAllBytes(path) }.getOrElse { error ->
            // It was readable a moment ago; answering "not found" keeps the reply about
            // the artifact rather than about the server's internals.
            logger.warn("Artifact is no longer readable | name={} | {}", name, error.message)
            return artifactNotFound(name)
        }

        val contentType = artifactStore.kindOfName(name)?.contentType
            ?: MediaType.APPLICATION_OCTET_STREAM_VALUE
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(contentType))
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"$name\"")
            .contentLength(bytes.size.toLong())
            .body(bytes)
    }

    /**
     * A missing artifact, as JSON.
     *
     * The same `{success, error, message}` envelope every other failure on this route
     * uses, so a caller does not have to special-case this one status to find out why.
     */
    private fun artifactNotFound(name: String): ResponseEntity<ByteArray> {
        val body = pulsarObjectMapper().writeValueAsBytes(
            linkedMapOf(
                "success" to false,
                "error" to "Not Found",
                "message" to "no artifact named '$name' on this host; artifact files are temporary " +
                    "and are removed with the process temp tree",
            )
        )
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
    }

    private companion object {
        /**
         * The document's JSON shape, spelled out so the conversion is checked by the
         * compiler instead of an unchecked cast.
         */
        val DOCUMENT_TYPE = object : TypeReference<Map<String, Any?>>() {}
    }
}

/**
 * The `POST /api/scrape` body.
 *
 * Field shape follows Firecrawl v2 (`scrapeOptions`) so a migrating caller keeps
 * its payload. Every field is optional: omitting `formats` means `["markdown"]`,
 * omitting `url` means "the page the session is on".
 *
 * Deliberately absent, because this deployment cannot honour it yet — accepting it would
 * be a lie rather than a gap:
 *
 * - `maxAge` / `expires`: see `SnapshotFormatStepRunner`; the capture step always
 *   captures.
 *
 * @property url **not supported yet** — passing it is refused with an explanation
 *   rather than silently ignored (the reads target the session's active page, so
 *   honouring it would have returned a document about the wrong page). Kept in the
 *   DTO so the refusal can name the field; `open <url>` first is the way today.
 * @property sessionId the session to scrape; required — see [PageScrapeService].
 * @property formats format names, or objects carrying their options.
 * @property onlyMainContent derive markdown from the readable article.
 * @property strict fail instead of degrading when a requested format is not delivered.
 *   The status comes from the failure itself (503/502/504/400), so a caller can act on
 *   it; the default `false` keeps Firecrawl's behaviour of omitting the field, saying why
 *   in `warning`, and returning everything else.
 *
 * Every property carries an explicit `@param:JsonProperty`, the convention every
 * request DTO in this module follows (`rest/mcp/controller/dto/McpDtos.kt`). Without
 * it the binding is not guaranteed here: a live `POST /api/scrape` came through as an
 * all-defaults object — so `formats` validated as empty and `sessionId` was null —
 * while the request itself parsed without error, which is the failure mode that
 * looks like a service bug.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class PageScrapeRequestBody(
    @param:JsonProperty("url") val url: String? = null,
    @param:JsonProperty("sessionId") val sessionId: String? = null,
    @param:JsonProperty("formats") val formats: List<Any?>? = null,
    @param:JsonProperty("onlyMainContent") val onlyMainContent: Boolean? = null,
    @param:JsonProperty("strict") val strict: Boolean? = null,
)
