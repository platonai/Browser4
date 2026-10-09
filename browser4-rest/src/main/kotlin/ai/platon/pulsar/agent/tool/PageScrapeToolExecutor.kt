package ai.platon.pulsar.agent.tool

import ai.platon.pulsar.agentic.model.ToolExample
import ai.platon.pulsar.agentic.model.ToolSpec
import ai.platon.pulsar.agentic.tools.builtin.AbstractToolExecutor
import ai.platon.pulsar.common.serialize.json.pulsarObjectMapper
import ai.platon.pulsar.rest.api.service.scrape.PageScrapeService
import ai.platon.pulsar.rest.session.PulsarSessionManager
import ai.platon.pulsar.skeleton.workflow.format.FormatOptionSchema
import kotlin.reflect.KClass

/**
 * Tool executor for the Firecrawl-compatible `formats` layer.
 *
 * Domain: `page`
 *
 * ## Methods
 *
 * - `scrape(sessionId, formats, onlyMainContent?)` — one request, one
 *   capture, many outputs, returned as one Firecrawl-v2-shaped document.
 * - `formats()` — capability discovery: every accepted format id, whether this
 *   deployment can deliver it right now, and why not when it cannot.
 *
 * ## `sessionId` is required (decision A2: "open first")
 *
 * The design draft had it optional ("no url means the session's current page"). That
 * branch is gone: with no `url` to target, only a session can say *which* page to read,
 * and choosing one here would read a different caller's page. So the argument is
 * declared **required** — the same shape `html_snapshot` and `webdb` already use
 * (`ToolSpec.Arg("sessionId", "String", null)`) — and `PageScrapeService` is what
 * actually refuses a blank one.
 *
 * Note the spec is *not* what enforces it: `ToolSpecValidator` lists `sessionId` in
 * `DEFAULT_CONTEXT_ARGS` and skips it (`ToolSpecValidator.kt:65`) before the
 * required-argument check, and the MCP layer strips it from the forwarded arguments
 * anyway. Declaring it required is for the documentation and discovery surfaces; a
 * null default on any *payload* argument would instead be actively wrong, because
 * there the validator really does treat it as required.
 *
 * ## The CLI surface is a static command pair, not a declared name
 *
 * `browser4-cli scrape` and `browser4-cli scrape formats` are `CommandDef`s in
 * `commands.rs` that map onto `page_scrape` / `page_formats`; these specs declare no
 * `cliName`. The declared-command mechanism only resolves a **spaced** invocation
 * (the CLI probes when it has two tokens), so a bare `scrape` cannot be reached that
 * way — and declaring a name here as well would give one action two entry points.
 *
 * ## What it deliberately does not expose yet
 *
 * - **`expires` / Firecrawl `maxAge`.** The capture step always captures (see
 *   `SnapshotFormatStepRunner`), because no `html_snapshot` tool reports a *stored*
 *   snapshot's identity. Accepting a window here would promise a cache reuse this
 *   deployment cannot deliver, so the argument is absent rather than ignored.
 * - **`url`.** Every step reads the session's active page, so a request-level URL used to
 *   produce a document about the wrong page; it is refused by name rather than accepted.
 *
 * `strict` **is** exposed: the three-state degradation contract is implemented, and the
 * code it fails with comes from the failure itself (503/502/504/400) rather than from a
 * table here.
 *
 * ## Why the document is returned as a map
 *
 * `AbstractToolExecutor` wraps any result that is not a String/Number/Boolean/
 * Map/Collection/Array in a `{type, description}` envelope, so returning a
 * `ScrapedDocument` would hand the caller `"ScrapedDocument(...)"` instead of the
 * payload. A map passes through untouched and is what the caller actually asked
 * for.
 */
class PageScrapeToolExecutor(
    private val pageScrapeService: PageScrapeService,
) : AbstractToolExecutor() {

    override val domain: String = "page"

    override val receiverClass: KClass<*> = PulsarSessionManager::class

    init {
        toolSpec["scrape"] = ToolSpec(
            domain = domain,
            method = "scrape",
            arguments = listOf(
                // `ToolSpecValidator` treats a null `defaultValue` as **required**,
                // whatever the declared type says (`String?` included), so every
                // optional argument spells its default as the literal "null" — the
                // convention the rest of the tool layer already uses.
                //
                // `sessionId` is the one deliberate exception (see the class KDoc): it
                // is a transport argument the validator skips, and every other domain
                // declares it required. `PageScrapeService` is what refuses a blank one.
                ToolSpec.Arg(
                    "sessionId", "String", null,
                    "The session whose page to scrape. Required: open the page first (`open <url>`), " +
                        "because this layer reads the page a session is already on.",
                ),
                ToolSpec.Arg(
                    "formats", "List<Any>", "null",
                    "The outputs to produce, in request order. A string names a format (`markdown`); " +
                        "an object adds its options (`{\"type\":\"screenshot\",\"fullPage\":true}`). " +
                        "Accepts the list, a comma-separated string, or a JSON array. " +
                        "Omitted or empty means `[\"markdown\"]`.",
                ),
                ToolSpec.Arg(
                    "onlyMainContent", "Boolean", "true",
                    "Derive markdown from the readable article instead of the whole cleaned page.",
                ),
                ToolSpec.Arg(
                    "strict", "Boolean", "null",
                    "Fail the request instead of degrading when a requested format cannot be delivered: " +
                        "503 for a format this deployment cannot deliver, 502/504 for one that ran and failed, " +
                        "400 for a request this build cannot honour. Default false, which omits the field, " +
                        "notes the reason in `warning` and still returns every other format.",
                ),
            ),
            returnType = "Map<String, Any?>",
            description = "Scrape a page once and return every requested format in one Firecrawl-compatible " +
                "document. All formats are derived from a single capture, so `markdown` and `links` describe " +
                "the same page state and eight formats cost one page load. Formats this deployment cannot " +
                "deliver are omitted from the document and named in its `warning`; " +
                "`metadata.formatsDelivered` tells 'not requested' from 'requested but unavailable'.",
            examples = listOf(
                ToolExample(
                    title = "Markdown and links from the current page",
                    // Example arguments are strings; a list-valued argument is
                    // written as the JSON text a caller would pass.
                    args = mapOf("formats" to """["markdown","links"]"""),
                    runnable = true,
                ),
                ToolExample(
                    title = "Full-page screenshot plus the raw HTML",
                    args = mapOf(
                        "formats" to """[{"type":"screenshot","fullPage":true},"rawHtml"]""",
                    ),
                ),
            ),
        )

        toolSpec["formats"] = ToolSpec(
            domain = domain,
            method = "formats",
            arguments = emptyList(),
            returnType = "List<Map<String, Any?>>",
            description = "List every accepted output format, whether this deployment can deliver it right now, " +
                "and why not when it cannot. `available` is a configuration answer, not a promise: a registered " +
                "contributor whose service is running can still fail at call time, and that failure is reported " +
                "per request in the document's `warning`.",
            examples = listOf(ToolExample(title = "List the available formats", runnable = true)),
        )
    }

    override suspend fun callFunctionOn(
        domain: String, functionName: String, args: Map<String, Any?>, receiver: Any
    ): Any? {
        require(domain == this.domain) { "Unsupported domain: $domain" }

        return when (functionName) {
            "scrape" -> scrape(args)
            "formats" -> pageScrapeService.formats()
            else -> throw IllegalArgumentException("Unsupported page method: $functionName")
        }
    }

    /**
     * One request → one document.
     *
     * The `formats` argument is validated by the same schema the rest of the layer
     * uses, so an unknown id or an illegal combination is rejected **here**, before
     * anything is captured — the caller learns about a typo without paying for a
     * page load.
     */
    private suspend fun scrape(args: Map<String, Any?>): Map<String, Any?> {
        val sessionId = args["sessionId"]?.toString()?.takeIf { it.isNotBlank() }
        val onlyMainContent = args["onlyMainContent"]?.let { toBoolean(it, "onlyMainContent") } ?: true
        val strict = args["strict"]?.let { toBoolean(it, "strict") } ?: false
        val formats = FormatOptionSchema.parse(formatArgs(args)).requireValid()

        val document = pageScrapeService.scrape(
            formats = formats,
            sessionId = sessionId,
            onlyMainContent = onlyMainContent,
            strict = strict,
        )

        @Suppress("UNCHECKED_CAST")
        return pulsarObjectMapper().convertValue(document, Map::class.java) as Map<String, Any?>
    }

    /**
     * The `formats` argument as the schema's loosely-typed input list.
     *
     * A caller may send the list, or spell it as text: `--formats markdown,links`
     * reaches here as one string, and a client that JSON-encodes the argument sends
     * the array as text. All three spellings mean the same request, so they are
     * normalized here rather than making the caller guess which one this tool wants.
     */
    private fun formatArgs(args: Map<String, Any?>): List<Any?> = when (val raw = args["formats"]) {
        null -> emptyList()
        is String -> parseFormatsText(raw)
        is List<*> -> raw
        else -> throw IllegalArgumentException(
            "Invalid 'formats' argument: expected a list of format names or objects, got ${raw::class.simpleName}"
        )
    }

    /** A single id, a comma-separated list, or the JSON array a client encoded. */
    private fun parseFormatsText(raw: String): List<Any?> {
        val text = raw.trim()
        if (text.isEmpty()) return emptyList()
        if (text.startsWith(JSON_ARRAY_PREFIX)) {
            return runCatching { pulsarObjectMapper().readValue(text, List::class.java) as List<*> }
                .getOrElse {
                    throw IllegalArgumentException("Invalid 'formats' JSON: ${it.message}", it)
                }
        }
        return text.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun toBoolean(raw: Any, name: String): Boolean = when (raw) {
        is Boolean -> raw
        is String -> raw.toBooleanStrictOrNull()
            ?: throw IllegalArgumentException("Invalid '$name' argument: expected true or false, got '$raw'")

        else -> throw IllegalArgumentException("Invalid '$name' argument: expected true or false, got $raw")
    }

    private companion object {
        /** Enough to tell an encoded array from a comma-separated list. */
        const val JSON_ARRAY_PREFIX = "["
    }
}
