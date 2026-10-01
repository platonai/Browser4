package ai.platon.pulsar.agent.tool

import ai.platon.pulsar.agentic.model.ToolExample
import ai.platon.pulsar.agentic.model.ToolSpec
import ai.platon.pulsar.agentic.tools.advanced.crawl.ScrapeRequest
import ai.platon.pulsar.agentic.tools.advanced.crawl.common.ScrapeAPIUtils
import ai.platon.pulsar.agentic.tools.builtin.AbstractToolExecutor
import ai.platon.pulsar.agentic.tools.specs.ToolResultSchemas
import ai.platon.pulsar.common.serialize.json.pulsarObjectMapper
import ai.platon.pulsar.common.sql.SQLTemplate
import ai.platon.pulsar.dom.FeaturedDocument
import ai.platon.pulsar.persist.WebPage
import ai.platon.pulsar.rest.api.service.ScrapeService
import ai.platon.pulsar.rest.mcp.controller.*
import ai.platon.pulsar.rest.session.PulsarSessionManager
import ai.platon.pulsar.skeleton.workflow.parse.html.PageSummaryIndexService
import ai.platon.pulsar.skeleton.workflow.parse.html.ReadabilityExtractor
import ai.platon.pulsar.skeleton.workflow.parse.html.ReadabilityResult
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import ai.platon.pulsar.rest.session.ManagedSession
import org.slf4j.LoggerFactory
import kotlin.reflect.KClass

/**
 * Tool executor that exposes DOM/HTML snapshot operations as MCP tools.
 *
 * Domain: `html_snapshot`
 *
 * ## The read/write contract
 *
 * `capture` is the **only** command that writes: it serializes the document the active tab
 * already shows and persists it under the tab's normalized url, overwriting any older copy of
 * that url.  Every other command is a **read** and serves the page store (`webdb`) — the stored
 * copy when there is one, otherwise an independent read-only load.  Nothing but `capture` ever
 * files a document.
 *
 * So the order to read the live document is always: `capture` first, then read.  A `query`,
 * `get`, `readability`, `summary`, `inspect` or `export` that runs on a page which was never
 * captured either serves an older stored copy or an independent load — never the live tab,
 * even when the target url is the page currently on screen.  (Commands used to fall back to
 * capturing the live tab, which made a read write, and — worse — filed the live tab's document
 * under whatever url the caller passed, so `readability <other-url>` returned the current page
 * as if it were `<other-url>`.)
 *
 * The url/href invariant holds throughout: the *normalized url* is the store identity, and the
 * *original href* travels beside it as the browser-facing address.
 *
 * ## Methods
 *
 * - `capture(sessionId)` — Serialize the live tab's document, persist it, and return metadata
 * - `scrape(sessionId, field, selector?, attrName?)` — Extract text/html/attr from a single element
 * - `scrape_all(sessionId, field, selector?, attrName?, offset?, limit?)` — Extract from all matching elements
 * - `query(sessionId?, sql, url?)` — Execute an X-SQL query against the stored page
 * - `export(sessionId)` — Export the full HTML of the current page
 * - `summary(sessionId)` — Generate a page summary with link groups
 * - `inspect(sessionId, selector?, max?, depth?)` — Inspect the HTML snapshot for selector suggestions
 * - `readability(sessionId, url?)` — Extract the readable article text of a stored page
 */
class HTMLSnapshotToolExecutor(
    private val sessionManager: PulsarSessionManager,
    private val scrapeService: ScrapeService? = null,
) : AbstractToolExecutor() {

    private val logger = LoggerFactory.getLogger(HTMLSnapshotToolExecutor::class.java)

    /**
     * Resolve the ManagedSession from either the receiver (passed by
     * [dispatchToCustomExecutor] from the controller's sessionManager)
     * or by looking it up via the injected sessionManager.
     */
    private fun resolveSession(args: Map<String, Any?>, receiver: Any): ManagedSession {
        if (receiver is ManagedSession) return receiver
        val sessionId = requireSessionId(args)
        return sessionManager.getOrRecoverSession(sessionId)
            ?: throw IllegalArgumentException("Session not found: $sessionId")
    }

    override val domain: String = "html_snapshot"
    override val receiverClass: KClass<*> = PulsarSessionManager::class

    init {
        toolSpec["capture"] = ToolSpec(
            domain = domain,
            method = "capture",
            arguments = listOf(
                ToolSpec.Arg("sessionId", "String", null),
            ),
            returnType = "String",
            description = "Capture the current page as an HTML snapshot with metadata, interactive elements, and link groups. " +
                "This is the only html_snapshot command that WRITES: it serializes the live tab's document and stores it " +
                "under the tab's normalized url, overwriting the stored copy. Run it before any read that must see the " +
                "live document.",
            examples = listOf(
                ToolExample(
                    title = "Snapshot the page the session is on",
                    args = mapOf("sessionId" to "<session-id>"),
                ),
            ),
        )

        toolSpec["scrape"] = ToolSpec(
            domain = domain,
            method = "scrape",
            arguments = listOf(
                ToolSpec.Arg("sessionId", "String", null),
                ToolSpec.Arg("field", "String", null),
                ToolSpec.Arg("selector", "String", ":root"),
                ToolSpec.Arg("attrName", "String?", "null", "Attribute to read when field=attr."),
            ),
            returnType = "String",
            description = "Extract text, textcontent, html, or an attribute value from a single element matching a CSS selector. " +
                "Reads the stored snapshot of the current page (read-only) — run 'htmlsnapshot capture' first to snapshot " +
                "the live document.",
            examples = listOf(
                ToolExample(
                    title = "Read one field",
                    args = mapOf("sessionId" to "<session-id>", "field" to "title", "selector" to "h1"),
                ),
                ToolExample(
                    title = "Read a link target",
                    args = mapOf(
                        "sessionId" to "<session-id>",
                        "field" to "attr",
                        "selector" to "a",
                        "attrName" to "href",
                    ),
                    notes = "field=attr requires attrName",
                ),
            ),
        )

        toolSpec["scrape_all"] = ToolSpec(
            domain = domain,
            method = "scrape_all",
            arguments = listOf(
                ToolSpec.Arg("sessionId", "String", null),
                ToolSpec.Arg("field", "String", null),
                ToolSpec.Arg("selector", "String", ":root"),
                ToolSpec.Arg("attrName", "String?", "null", "Attribute to read when field=attr."),
                ToolSpec.Arg("offset", "Int", "0"),
                ToolSpec.Arg("limit", "Int", "-1"),
            ),
            returnType = "String",
            outputSchema = ToolResultSchemas.HTML_SNAPSHOT_SCRAPE_ALL,
            description = "Extract text, textcontent, html, or attribute values from ALL elements matching a CSS selector. " +
                "Reads the stored snapshot of the current page (read-only) — run 'htmlsnapshot capture' first to snapshot " +
                "the live document.",
            examples = listOf(
                ToolExample(
                    title = "Read every product title",
                    args = mapOf(
                        "sessionId" to "<session-id>",
                        "field" to "text",
                        "selector" to ".product > h2",
                        "limit" to "20",
                    ),
                ),
            ),
        )

        toolSpec["query"] = ToolSpec(
            domain = domain,
            method = "query",
            arguments = listOf(
                ToolSpec.Arg("sql", "String", null),
                ToolSpec.Arg("url", "String?", "null", "Page to query; defaults to the session's current page."),
                ToolSpec.Arg("sessionId", "String", null),
            ),
            returnType = "String",
            outputSchema = ToolResultSchemas.HTML_SNAPSHOT_QUERY,
            description = "Execute an X-SQL query against the STORED page of the session's current page, or of a specified URL. " +
                "Read-only: it serves the stored copy (or loads the page independently when the store is empty) and never " +
                "captures the live tab — run 'htmlsnapshot capture' first to query the live document.",
            examples = listOf(
                ToolExample(
                    title = "Run X-SQL against the current page",
                    args = mapOf(
                        "sessionId" to "<session-id>",
                        "sql" to "select dom_first_text(dom, 'h1') as title",
                    ),
                ),
            ),
        )

        toolSpec["export"] = ToolSpec(
            domain = domain,
            method = "export",
            arguments = listOf(
                ToolSpec.Arg("sessionId", "String", null),
                ToolSpec.Arg("clean", "Boolean", "false"),
            ),
            returnType = "String",
            description = "Export the full, pretty-printed HTML of the stored snapshot of the current page (read-only; run " +
                "'htmlsnapshot capture' first to snapshot the live document). Set clean=true to strip <script>, <style>, and " +
                "non-standard attributes (keeps the vi attribute).",
            examples = listOf(
                ToolExample(
                    title = "Export the page HTML",
                    args = mapOf("sessionId" to "<session-id>", "clean" to "true"),
                ),
            ),
        )

        toolSpec["summary"] = ToolSpec(
            domain = domain,
            method = "summary",
            arguments = listOf(
                ToolSpec.Arg("sessionId", "String", null),
            ),
            returnType = "String",
            description = "Generate a page summary including title, statistics, and detected link groups from the stored " +
                "snapshot of the current page (read-only; run 'htmlsnapshot capture' first to snapshot the live document).",
            examples = listOf(
                ToolExample(title = "Summarise the current page", args = mapOf("sessionId" to "<session-id>")),
            ),
        )

        toolSpec["inspect"] = ToolSpec(
            domain = domain,
            method = "inspect",
            arguments = listOf(
                ToolSpec.Arg("sessionId", "String", null),
                ToolSpec.Arg("selector", "String", ":root"),
                ToolSpec.Arg("max", "Int", "20"),
                ToolSpec.Arg("depth", "Int", "5"),
            ),
            returnType = "String",
            outputSchema = ToolResultSchemas.HTML_SNAPSHOT_INSPECT,
            description = "Inspect the HTML snapshot and suggest CSS selectors for recurring patterns. Reads the stored " +
                "snapshot of the current page (read-only; run 'htmlsnapshot capture' first to snapshot the live document).",
            examples = listOf(
                ToolExample(
                    title = "Find selectors for repeated cards",
                    args = mapOf("sessionId" to "<session-id>", "selector" to ".product", "max" to "10"),
                ),
            ),
        )

        toolSpec["readability"] = ToolSpec(
            domain = domain,
            method = "readability",
            arguments = listOf(
                ToolSpec.Arg("sessionId", "String", null),
                ToolSpec.Arg("url", "String?", "null", "Read this URL from the page store instead of the session's current page."),
            ),
            returnType = "String",
            outputSchema = ToolResultSchemas.HTML_SNAPSHOT_READABILITY,
            description = "Extract the main article content (title, byline, site name, excerpt, cleaned HTML, plain text) with a " +
                "Readability-style heuristic from a STORED page: the stored snapshot of the session's current page, or of url " +
                "when given. Read-only — it never captures the live tab, so a url other than the current page is never filed " +
                "with the current page's content. Run 'htmlsnapshot capture' first to read the live document.",
            examples = listOf(
                ToolExample(
                    title = "Extract the article of the current page",
                    args = mapOf("sessionId" to "<session-id>"),
                ),
            ),
        )
    }

    override suspend fun callFunctionOn(
        domain: String, functionName: String, args: Map<String, Any?>, receiver: Any
    ): Any? {
        require(domain == this.domain) { "Unsupported domain: $domain" }

        return when (functionName) {
            "capture" -> capture(args, receiver)
            "scrape" -> scrape(args, receiver)
            "scrape_all", "scrapeAll" -> scrapeAll(args, receiver)
            "query" -> query(args, receiver)
            "export" -> export(args, receiver)
            "summary" -> summary(args, receiver)
            "inspect" -> inspect(args, receiver)
            "readability" -> readability(args, receiver)
            else -> throw IllegalArgumentException("Unsupported html_snapshot method: $functionName")
        }
    }

    // =========================================================================
    // Tool methods
    // =========================================================================

    /**
     * Archive the live document of the session's active tab into the page store and return its
     * metadata.
     *
     * **Capture is this family's only writer, and it always writes.**  It stores the page — the row
     * is keyed by the *normalized* url of the tab, the identity every other lookup uses (see `NormURL`
     * and `PulsarSession.normalize`), and the raw address travels beside it as the metadata `href` —
     * overwriting any older copy of that url, cached or stored.  Re-capturing is therefore how a page
     * that changed in the tab (a form submitted, a list sorted, `eval`-inserted markup) reaches the
     * store.
     *
     * Every read command (`get`, `get all`, `export`, `summary`, `inspect`, `readability`, `query`)
     * serves that stored copy, or loads the page independently (read-only) when the store has
     * nothing; none of them captures, and none of them writes.  A read that has to see the *live*
     * document — form submission results, SPA updates, `eval` mutations, login state — therefore runs
     * **after** this command.
     *
     * The capture never navigates: it serializes the document the tab already shows, with the capture
     * annotations (vi bounding boxes and the `normalizedURI` link) produced by the fetch pipeline.  A
     * tab that shows no archivable document (`about:blank`, a `chrome-error` page) is refused here by
     * name, instead of failing deeper in the pipeline with an internal message.
     */
    private suspend fun capture(args: Map<String, Any?>, receiver: Any = Any()): String {
        val managed = resolveSession(args, receiver)

        return managed.withLock {
            val pulsarSession = managed.agenticSession
            val currentUrl = runCatching { managed.driver.currentUrl() }.getOrNull()
            require(!currentUrl.isNullOrBlank() && isArchivableAddress(currentUrl)) {
                "Nothing to capture: the active tab shows <${currentUrl ?: ""}>. Navigate to an http(s) " +
                    "or file:// page first — about:blank and browser error pages have no snapshot."
            }

            // `session.capture(driver)` serializes the live document AND persists it, under the
            // normalized url of the tab.
            //
            // `-refresh` is what makes that write real.  Without it the load pipeline may build the
            // page from an in-memory cache shell, and `persist` deliberately drops the CONTENT field
            // of a cached shell (LoadComponent.persist) — the capture would return the new document
            // while the store kept the old one, stamped with a fresh fetch time and therefore served
            // for the whole default EXPIRES window (decades).  The refresh only bypasses that cache;
            // the document still comes from the tab, never from the network, so capture never
            // navigates.
            val page = pulsarSession.capture(managed.driver, url = "$currentUrl -refresh")
            val document = pulsarSession.parse(page, noCache = true)

            htmlSnapshotMetadataJson(
                document = document,
                url = page.url,
                href = page.href ?: page.url,
                sizeBytes = page.contentLength.toLong(),
                capturedAt = page.prevFetchTime.toString(),
                contentType = page.contentType,
            )
        }
    }

    /**
     * True when [url] is a document this family archives: an http(s) page, or a local file.
     */
    private fun isArchivableAddress(url: String): Boolean =
        url.startsWith("http://") || url.startsWith("https://") || url.startsWith("file://")

    /**
     * The page a **read** command serves for [url]: the stored copy, or an independent read-only load
     * when the store has nothing.
     *
     * `-readonly` is the query path's own sealing option: it serves the page cache when it can,
     * otherwise it loads the page independently, and it never writes the result back — so a read
     * stays a read.  Capturing the live tab here instead (which is what this used to do) was wrong
     * twice over: it wrote to the store as a side effect of a read, and `capture` labels *whatever
     * the tab shows* with the url it is handed, so a read of a different url filed the live tab's
     * document under that url.
     */
    private suspend fun storedPageOrIndependentLoad(managed: ManagedSession, url: String): WebPage {
        val pulsarSession = managed.agenticSession
        return pulsarSession.getOrNull(url)
            ?: pulsarSession.load("$url ${ScrapeAPIUtils.READ_ONLY_OPTION}")
    }

    /** The normalized identity of the page the session's active tab is showing. */
    private suspend fun currentPageUrl(managed: ManagedSession): String =
        managed.agenticSession.normalize(managed.driver.currentUrl()).urlString

    private suspend fun scrape(args: Map<String, Any?>, receiver: Any = Any()): String {
        val field = paramString(args, "field", "scrape")!!
        val selector = paramString(args, "selector", "scrape", required = false, default = ":root")?.ifEmpty { ":root" } ?: ":root"
        val attrName = paramString(args, "attrName", "scrape", required = false)

        if (field !in setOf("text", "textcontent", "html", "attr")) {
            throw IllegalArgumentException("Unknown field '$field'. Use text, textcontent, html, or attr.")
        }
        if (field == "attr" && attrName.isNullOrBlank()) {
            throw IllegalArgumentException("The 'attr' field requires an attribute name.")
        }
        if (isElementReference(selector)) {
            throw IllegalArgumentException("Element references ('$selector') are not supported in htmlsnapshot get. Use a CSS selector instead.")
        }

        val managed = resolveSession(args, receiver)

        return managed.withLock {
            fun extractFrom(document: FeaturedDocument): String {
                // `get` follows querySelector semantics: return the first match only.
                // (`get all` — scrapeAll — returns the full array via querySelectorAll.)
                return when (field) {
                    "text" -> document.selectFirstOrNull(selector)?.text() ?: ""
                    "textcontent" -> document.selectFirstOrNull(selector)?.text() ?: ""
                    "html" -> document.selectFirstOrNull(selector)?.html() ?: ""
                    "attr" -> document.selectFirstOrNull(selector)?.attr(attrName!!) ?: ""
                    else -> ""
                }
            }

            // Serve the STORE: the stored copy of the current page, or an independent read-only load
            // when there is none.  A read never captures the live tab — see the read/write contract
            // at the bottom of this file.
            val pulsarSession = managed.agenticSession
            extractFrom(pulsarSession.parse(storedPageOrIndependentLoad(managed, currentPageUrl(managed))))
        }
    }

    private suspend fun scrapeAll(args: Map<String, Any?>, receiver: Any = Any()): String {
        val field = paramString(args, "field", "scrape_all")!!
        val selector = paramString(args, "selector", "scrape_all", required = false, default = ":root")?.ifEmpty { ":root" } ?: ":root"
        val attrName = paramString(args, "attrName", "scrape_all", required = false)
        val offset = paramInt(args, "offset", "scrape_all", required = false, default = 0) ?: 0
        val limit = paramInt(args, "limit", "scrape_all", required = false, default = -1) ?: -1

        if (field !in setOf("text", "textcontent", "html", "attr")) {
            throw IllegalArgumentException("Unknown field '$field'. Use text, textcontent, html, or attr.")
        }
        if (field == "attr" && attrName.isNullOrBlank()) {
            throw IllegalArgumentException("The 'attr' field requires an attribute name.")
        }
        if (isElementReference(selector)) {
            throw IllegalArgumentException("Element references ('$selector') are not supported in htmlsnapshot get. Use a CSS selector instead.")
        }

        val managed = resolveSession(args, receiver)

        val results = managed.withLock {
            fun extractAllFrom(document: FeaturedDocument): List<String> {
                val elements = document.select(selector)
                val paginated = if (offset > 0) elements.drop(offset) else elements
                val limited = if (limit > 0) paginated.take(limit) else paginated

                return limited.map { element ->
                    when (field) {
                        "text" -> element.text()
                        "textcontent" -> element.text()
                        "html" -> element.html()
                        "attr" -> element.attr(attrName!!)
                        else -> ""
                    }
                }
            }

            // Serve the STORE, as `get` does — see the read/write contract at the bottom of this file.
            val pulsarSession = managed.agenticSession
            extractAllFrom(pulsarSession.parse(storedPageOrIndependentLoad(managed, currentPageUrl(managed))))
        }

        @Suppress("UNCHECKED_CAST")
        val resultList = results as List<Any?>
        return pulsarObjectMapper().copy()
            .setSerializationInclusion(JsonInclude.Include.ALWAYS)
            .writeValueAsString(resultList)
    }

    private suspend fun query(args: Map<String, Any?>, receiver: Any = Any()): String {
        val scrapeService = this.scrapeService
            ?: throw IllegalArgumentException("ScrapeService is not available")

        val sql = paramString(args, "sql", "query")!!

        // Reject queries that use '.' as a literal URL
        val dotUrlPattern = Regex(
            """(?:DOM_)?LOAD_AND_SELECT\s*\(\s*['"]\.['"]""",
            RegexOption.IGNORE_CASE
        )
        if (dotUrlPattern.containsMatchIn(sql)) {
            throw IllegalArgumentException(
                "Invalid URL '.' in DOM_LOAD_AND_SELECT. " +
                    "Use the unquoted @url placeholder to reference the current page URL."
            )
        }

        val explicitUrl = paramString(args, "url", "query", required = false)?.takeIf { it.isNotBlank() }

        // A session is needed only when no URL is given: the target is then the URL of the page the
        // session is currently showing (and the query serves that URL's stored copy, as above).  With
        // an explicit URL the query is a pure page-store/webdb query: it runs session-less (offline
        // corpus queries included) and never touches a session, which is also why it cannot pick up
        // the live tab's document any more.
        val url = explicitUrl ?: resolveSession(args, receiver).let { managed ->
            managed.agenticSession.normalize(managed.driver.currentUrl()).urlString
        }

        // Serve the STORE — see the read/write contract at the bottom of this file.  The
        // X-SQL engine's load_and_select goes through the read-only load path, which answers
        // from the page cache when it can and otherwise loads the page independently; it
        // never captures the live tab.  A query that must reflect the LIVE document runs
        // after `html_snapshot_capture`, which owns every write in this family.
        //
        // This used to seed the store from the live tab whenever the target happened to be
        // the current page — a read that wrote, on a target the caller never asked to
        // capture.  Removed: the live document reaches the store through `capture` (which
        // always archives the active tab, under that tab's own url), and `query` only reads.
        val processedSql = SQLTemplate(sql).createSQL(url)
        val response = scrapeService.executeQuery(ScrapeRequest(processedSql))

        // DOM_FIRST_IMG (and the rest of the DOM_*_IMG family) evaluates its
        // selector through an img-scanning path that does not parse PowerCSS
        // :expr(...) pseudo-selectors — any :expr filter silently matches
        // nothing (no error, no warning). DOM_FIRST_ATTR / DOM_SELECT_FIRST /
        // FROM selectors do honor :expr. Log a warning so a silent no-match
        // is not mistaken for a page without images. (Engine parity fix is
        // tracked upstream and lands with the pulsar-ql dependency bump.)
        if (hasDomFirstImgExpr(processedSql)) {
            logger.warn(
                "X-SQL query uses a DOM_*_IMG function with a PowerCSS :expr(...) selector, which " +
                    "the engine does not evaluate (matches nothing, silently). Filter images with " +
                    "DOM_FIRST_ATTR(DOM, sel, 'src') or DOM_SELECT_FIRST(DOM, sel) + DOM_ABS_SRC " +
                    "instead.\nSQL: $processedSql"
            )
        }

        // H2 reports errors through the response body (statusCode 417) rather
        // than an exception.  H2 treats double quotes as identifier quotes, so
        // a CSS selector written as DOM_LOAD_AND_SELECT(@url, "a") fails with a
        // confusing "Column a not found" message.  Only append the single-quote
        // hint when the failing statement actually contains a double-quoted
        // argument inside a DOM_LOAD_AND_SELECT call — unrelated quoted-column
        // errors (e.g. a genuinely missing table column) pass through untouched.
        val rawMessage = response.message
        if (rawMessage != null && shouldAppendSelectorQuoteHint(rawMessage, processedSql)) {
            response.message = rawMessage + " — CSS selectors inside DOM_LOAD_AND_SELECT must use " +
                "SINGLE quotes (H2 treats double quotes as identifier quotes). " +
                "Example: DOM_LOAD_AND_SELECT(@url, 'a')"
        }

        // DOM_FIRST_FLOAT/DOM_FIRST_INTEGER return a custom H2 value type, so
        // comparing them to a numeric literal in WHERE/HAVING makes H2 fall
        // back to hex-decoding the value's string form ("899.99") and die with
        // the opaque 'Hexadecimal string contains non-hex character' error
        // (SQL 90004-197) — while the same expression works in SELECT and
        // ORDER BY.  Append a corrective hint only when the failing statement
        // actually calls one of these functions.
        val castHintMessage = response.message
        if (castHintMessage != null && shouldAppendDomFirstFloatCastHint(castHintMessage, processedSql)) {
            response.message = castHintMessage + " — DOM_FIRST_FLOAT/DOM_FIRST_INTEGER return a custom " +
                "value type that H2 cannot compare to a numeric literal in WHERE/HAVING (it tries to " +
                "hex-decode the value). Wrap the function in a numeric cast: " +
                "WHERE CAST(DOM_FIRST_FLOAT(DOM, '.price', 0.0) AS DOUBLE) >= 25.0 — or use " +
                "STR_FIRST_FLOAT(DOM_FIRST_TEXT(DOM, '.price'), 0.0)."
        }

        return pulsarObjectMapper().copy()
            .setSerializationInclusion(JsonInclude.Include.ALWAYS)
            .writeValueAsString(response)
    }

    private suspend fun export(args: Map<String, Any?>, receiver: Any = Any()): String {
        val clean = paramBool(args, "clean", "export", required = false, default = false) ?: false
        val managed = resolveSession(args, receiver)

        return managed.withLock {
            // Serve the STORE — see the read/write contract at the bottom of this file.
            val pulsarSession = managed.agenticSession
            val document = pulsarSession.parse(storedPageOrIndependentLoad(managed, currentPageUrl(managed)))

            if (clean) {
                // --clean must change the serialized OUTPUT, not just the parsed
                // tree.  FeaturedDocument.outerHtml can serve a cached annotated
                // serialization that ignores in-place mutations, which made the
                // clean export byte-identical to the raw one.  Re-parse the
                // serialized HTML into a fresh jsoup tree, clean that, and
                // serialize it — the mutations provably reach the output.
                val rawHtml = document.outerHtml
                val fresh = org.jsoup.Jsoup.parse(rawHtml)
                cleanDocument(fresh)
                fresh.outerHtml()
            } else {
                document.outerHtml
            }
        }
    }

    /**
     * Strip scripts, styles, and non-standard attributes from HTML.
     *
     * Removes:
     * - `<script>` and `<style>` elements (including their content)
     * - `<noscript>` elements
     * - HTML comments (within elements)
     * - Non-standard attributes on all elements (keeps `vi`, standard HTML5 attrs,
     *   `aria-*`, `role`, `data-*`, and microdata `item*` attrs)
     */
    private fun cleanDocument(document: org.jsoup.nodes.Document) {
        document.select("script, style, noscript").remove()

        for (el in document.select("*")) {
            val comments = el.childNodes().filterIsInstance<org.jsoup.nodes.Comment>()
            for (c in comments) {
                c.remove()
            }

            val attrsToRemove = el.attributes().asList()
                .map { it.key }
                .filter { key -> !STANDARD_HTML_ATTRIBUTES.contains(key) && !isStandardAttributePrefix(key) }
                .toList()
            for (key in attrsToRemove) {
                el.removeAttr(key)
            }
        }
    }

    private fun isStandardAttributePrefix(name: String): Boolean {
        return name.startsWith("aria-") ||
            name.startsWith("data-") ||
            (name.startsWith("item") && (name == "itemscope" || name == "itemtype" ||
                name == "itemprop" || name == "itemid" || name == "itemref"))
    }

    companion object {
        internal fun shouldAppendSelectorQuoteHint(message: String, sql: String): Boolean =
            message.contains("not found") &&
                message.contains("SQL statement") &&
                Regex("""DOM_LOAD_AND_SELECT\s*\([^)]*"[^"]*"""", RegexOption.IGNORE_CASE).containsMatchIn(sql)

        /**
         * True when the H2 error message is the hex-decoding failure produced by
         * comparing DOM_FIRST_FLOAT / DOM_FIRST_INTEGER (custom H2 value type)
         * to a numeric literal in WHERE/HAVING, and the failing SQL actually
         * calls one of those functions — i.e. a query that works in SELECT /
         * ORDER BY but dies in a predicate, not a user typo.
         */
        internal fun shouldAppendDomFirstFloatCastHint(message: String, sql: String): Boolean =
            message.contains("Hexadecimal string contains non-hex character") &&
                Regex("""DOM_FIRST_(FLOAT|INTEGER)\s*\(""", RegexOption.IGNORE_CASE).containsMatchIn(sql)

        /**
         * True when the SQL feeds a DOM_*_IMG function (DOM_FIRST_IMG /
         * DOM_NTH_IMG / DOM_ALL_IMGS) with a PowerCSS :expr(...) pseudo-selector.
         * The img-scanning selector path ignores :expr, so such a query matches
         * nothing without an error.
         */
        internal fun hasDomFirstImgExpr(sql: String): Boolean =
            Regex("""(?:DOM_FIRST_IMG|DOM_NTH_IMG|DOM_ALL_IMGS)\s*\([^)]*:expr\s*\(""", RegexOption.IGNORE_CASE)
                .containsMatchIn(sql)

        private val STANDARD_HTML_ATTRIBUTES: Set<String> = setOf(
            "accesskey", "autocapitalize", "autofocus", "class", "contenteditable",
            "dir", "draggable", "enterkeyhint", "hidden", "id", "inert", "inputmode",
            "is", "lang", "nonce", "popover", "slot", "spellcheck", "style",
            "tabindex", "title", "translate", "writingsuggestions",
            "accept", "action", "align", "alt", "async", "autocomplete",
            "autoplay", "charset", "checked", "cite", "cols", "colspan",
            "content", "controls", "coords", "crossorigin", "datetime", "decoding",
            "default", "defer", "dirname", "disabled", "download",
            "enctype", "for", "form", "formaction", "formenctype", "formmethod",
            "formnovalidate", "formtarget", "headers", "height", "high", "href",
            "hreflang", "http-equiv", "integrity", "kind", "label", "list",
            "loading", "loop", "low", "max", "maxlength", "media", "method",
            "min", "minlength", "multiple", "muted", "name", "nomodule",
            "novalidate", "open", "optimum", "pattern", "ping", "placeholder",
            "playsinline", "popovertarget", "popovertargetaction", "poster",
            "preload", "readonly", "referrerpolicy", "rel", "required",
            "reversed", "rows", "rowspan", "sandbox", "scope", "selected",
            "shape", "size", "sizes", "span", "src", "srcdoc", "srclang",
            "srcset", "start", "step", "target", "type", "usemap", "value",
            "width", "wrap",
            "role",
            "vi",
        )
    }

    private suspend fun summary(args: Map<String, Any?>, receiver: Any = Any()): String {
        val managed = resolveSession(args, receiver)

        return managed.withLock {
            // Serve the STORE — see the read/write contract at the bottom of this file.
            val pulsarSession = managed.agenticSession
            val url = currentPageUrl(managed)
            val document = pulsarSession.parse(storedPageOrIndependentLoad(managed, url))
            PageSummaryIndexService.generate(document, url, document.title)
        }
    }

    private suspend fun inspect(args: Map<String, Any?>, receiver: Any = Any()): String {
        val managed = resolveSession(args, receiver)

        return managed.withLock {
            // Serve the STORE — see the read/write contract at the bottom of this file.
            val pulsarSession = managed.agenticSession
            val document = pulsarSession.parse(storedPageOrIndependentLoad(managed, currentPageUrl(managed)))

            val selector = paramString(args, "selector", "inspect", required = false, default = ":root")?.ifEmpty { ":root" } ?: ":root"
            val maxMatches = paramInt(args, "max", "inspect", required = false, default = 20) ?: 20
            val maxDepth = paramInt(args, "depth", "inspect", required = false, default = 5) ?: 5

            inspectDocument(document, selector, maxMatches, maxDepth)
        }
    }

    /**
     * Extract the main article content from the stored HTML snapshot using a
     * Readability-style heuristic (deterministic, no LLM).
     *
     * @param args `url` (optional) — the page to read. Without it, the session's current page is
     *   used. Either way the page comes from the **store**, or from an independent read-only load
     *   when the store has nothing; the live tab is never captured here.
     *
     *   This is where a capture of a *different* url used to be filed under the requested url: the
     *   old fallback called `capture(driver, url)`, and a capture labels whatever the tab shows with
     *   the url it is handed — so `readability <other-url>` could store the live tab's document (and
     *   return its article) under `<other-url>`, poisoning every later lookup of that url. Run
     *   `html_snapshot_capture` first when the live document is what you want to read.
     *
     * @return JSON with title, byline, siteName, excerpt, length, confidence,
     *   textContent and cleaned article content HTML.
     */
    private suspend fun readability(args: Map<String, Any?>, receiver: Any = Any()): String {
        val managed = resolveSession(args, receiver)

        return managed.withLock {
            val pulsarSession = managed.agenticSession
            val requestedUrl = paramString(args, "url", "readability", required = false)
                ?.takeIf { it.isNotBlank() }
            val url = requestedUrl?.let { pulsarSession.normalize(it).urlString }
                ?: currentPageUrl(managed)

            val page = storedPageOrIndependentLoad(managed, url)
            val document = pulsarSession.parse(page)

            val result = ReadabilityExtractor().extract(document.document)
                ?: throw IllegalArgumentException(
                    "No readable article content found on this page (text below threshold or no article-like structure). " +
                        "Try a page with substantial text, or use `htmlsnapshot get text \"<selector>\"` for explicit extraction."
                )

            readabilityPayload(result, page.url).toString()
        }
    }

    /**
     * The `html_snapshot readability` payload: the nine fields of [ReadabilityResult]
     * plus the page URL the extractor could not know.
     *
     * Extracted from the `withLock` block so the shape the contract promises can be
     * tested without a session — [ToolResultSchemas.HTML_SNAPSHOT_READABILITY]
     * describes exactly this, and `HTMLSnapshotResultSchemaTest` drives it with a
     * real `ReadabilityExtractor` result.
     */
    internal fun readabilityPayload(result: ReadabilityResult, fallbackUrl: String): ObjectNode =
        pulsarObjectMapper().createObjectNode().apply {
            put("url", result.url.ifBlank { fallbackUrl })
            put("title", result.title)
            put("byline", result.byline)
            put("siteName", result.siteName)
            put("excerpt", result.excerpt)
            put("length", result.length)
            put("confidence", result.confidence)
            put("textContent", result.textContent)
            put("content", result.content)
        }

    // =========================================================================
    // Helpers
    // =========================================================================

    private fun requireSessionId(args: Map<String, Any?>): String {
        return args["sessionId"]?.toString()
            ?: throw IllegalArgumentException("Missing required parameter: sessionId")
    }
}

// =========================================================================
// The html snapshot family's read/write contract
//
// `capture` is the family's only writer: it archives the LIVE document of the
// session's active tab into the page store, keyed by the normalized url of that
// tab, overwriting whatever the store held for that url, and returns its
// metadata.  Every read (`get`, `get all`, `export`, `summary`, `inspect`,
// `readability`, `query`) serves that stored copy, or loads the page
// independently (read-only, never written back) when the store has nothing for
// the url — none of them captures.
//
// The split matters because the two are different documents: the stored page is
// what the fetch pipeline produced, while form submissions, toggles and `eval`
// mutations exist only in the interactive tab.  A read that must see the live
// document therefore runs *after* a capture.  Reads used to capture implicitly,
// which both wrote to the store behind a read's back and — because a capture
// labels whatever the tab shows with the url it is handed — filed the live tab's
// document under a *different* url.
// =========================================================================

/**
 * Build the html snapshot capture metadata JSON from a parsed [document].
 *
 * Pure function — the output structure consumed by downstream get / get all / inspect / export /
 * summary and the CLI's metadata rendering.  Only `capture` produces it: every other command in this
 * family is a read (see the read/write contract at the bottom of this file).
 *
 * @param url The document URL: the normalized url, which is the store identity.
 * @param href The browser-facing address of the document (the raw url, query and fragment included).
 * @param sizeBytes Serialized content length.
 * @param capturedAt Capture timestamp (ISO-8601).
 * @param contentType The document content type.
 */
internal fun htmlSnapshotMetadataJson(
    document: FeaturedDocument,
    url: String,
    href: String,
    sizeBytes: Long,
    capturedAt: String,
    contentType: String,
): String {
    val imageCount = document.select("img").size
    val linkCount = document.select("a").size

    val interactiveSelector = "a[href], button, input:not([type=hidden]), select, textarea, " +
            "details, summary, " +
            "[role=button], [role=link], [role=checkbox], [role=radio], " +
            "[role=tab], [role=menuitem], [role=switch], [role=combobox], " +
            "[role=searchbox], [role=textbox], [role=slider], [role=spinbutton], " +
            "[role=option], [role=treeitem], " +
            "[tabindex]:not([tabindex=\"-1\"]), [contenteditable=true], " +
            "[onclick], [onkeydown], [onsubmit]"
    val maxInteractive = 100
    val allInteractive = document.select(interactiveSelector).take(maxInteractive * 2)
    val weighted = computeInteractiveWeights(allInteractive).take(maxInteractive)
    val interactiveElements = weighted.map { (el, weight, tier) ->
        val obj = pulsarObjectMapper().createObjectNode()
        obj.put("ref", buildElementRef(el))
        val box = el.attr("vi")
        if (box.isNotBlank()) obj.put("box", box)
        val ownText = truncateText(el.text().trim())
        if (ownText.isNotBlank()) obj.put("text", ownText)
        obj.put("weight", weight)
        obj.put("tier", tier)
        obj.put("semanticGroup", findSemanticGroup(el))
        obj
    }

    val linkGroups = PageSummaryIndexService.detectLinkGroups(document)

    return pulsarObjectMapper().createObjectNode().apply {
        put("url", url)
        put("href", href)
        put("sizeBytes", sizeBytes.toString())
        put("capturedAt", capturedAt)
        put("contentType", contentType)
        put("title", document.title)
        put("imageCount", imageCount)
        put("linkCount", linkCount)
        putArray("interactiveElements").addAll(interactiveElements)
        if (linkGroups.isNotEmpty()) {
            set<ArrayNode>("linkGroups", linkGroupsToJson(linkGroups))
        }
    }.toString()
}
