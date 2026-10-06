package ai.platon.pulsar.rest.api.controller

import ai.platon.pulsar.common.serialize.json.pulsarObjectMapper
import ai.platon.pulsar.rest.api.service.scrape.PageScrapeService
import ai.platon.pulsar.skeleton.workflow.format.FormatOptionSchema
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.core.type.TypeReference
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.CrossOrigin
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

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
 * Deliberately absent, because this deployment cannot honour them yet — accepting
 * them would be a lie rather than a gap:
 *
 * - `maxAge` / `expires`: see `SnapshotFormatStepRunner`; the capture step always
 *   captures.
 * - `strict`: the three-state degradation contract is not implemented, so every
 *   unavailable format degrades with a `warning`.
 *
 * @property url **not supported yet** — passing it is refused with an explanation
 *   rather than silently ignored (the reads target the session's active page, so
 *   honouring it would have returned a document about the wrong page). Kept in the
 *   DTO so the refusal can name the field; `open <url>` first is the way today.
 * @property sessionId the session to scrape; null means the bound session.
 * @property formats format names, or objects carrying their options.
 * @property onlyMainContent derive markdown from the readable article.
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
)
