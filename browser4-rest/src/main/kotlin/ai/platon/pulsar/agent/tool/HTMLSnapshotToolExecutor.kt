package ai.platon.pulsar.agent.tool

import ai.platon.pulsar.agentic.model.ToolSpec
import ai.platon.pulsar.agentic.tools.advanced.crawl.ScrapeRequest
import ai.platon.pulsar.agentic.tools.builtin.AbstractToolExecutor
import ai.platon.pulsar.chrome.Browser4WebDriver
import ai.platon.pulsar.common.serialize.json.pulsarObjectMapper
import ai.platon.pulsar.common.sql.SQLTemplate
import ai.platon.pulsar.dom.FeaturedDocument
import ai.platon.pulsar.rest.api.service.ScrapeService
import ai.platon.pulsar.rest.mcp.controller.*
import ai.platon.pulsar.rest.session.PulsarSessionManager
import ai.platon.pulsar.skeleton.workflow.parse.html.PageSummaryIndexService
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.node.ArrayNode
import ai.platon.pulsar.rest.session.ManagedSession
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
import java.time.Instant
import kotlin.reflect.KClass

/**
 * Tool executor that exposes DOM/HTML snapshot operations as MCP tools.
 *
 * Domain: `html_snapshot`
 *
 * Supported methods:
 * - `capture(sessionId)` — Capture the current page as an HTML snapshot with metadata
 * - `scrape(sessionId, field, selector?, attrName?)` — Extract text/html/attr from a single element
 * - `scrape_all(sessionId, field, selector?, attrName?, offset?, limit?)` — Extract from all matching elements
 * - `query(sessionId?, sql, url?)` — Execute an X-SQL query against the page
 * - `export(sessionId)` — Export the full HTML of the current page
 * - `summary(sessionId)` — Generate a page summary with link groups
 * - `inspect(sessionId, selector?, max?, depth?)` — Inspect the HTML snapshot for selector suggestions
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
        return sessionManager.getSession(sessionId)
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
            description = "Capture the current page as an HTML snapshot with metadata, interactive elements, and link groups."
        )

        toolSpec["scrape"] = ToolSpec(
            domain = domain,
            method = "scrape",
            arguments = listOf(
                ToolSpec.Arg("sessionId", "String", null),
                ToolSpec.Arg("field", "String", null),
                ToolSpec.Arg("selector", "String", ":root"),
                ToolSpec.Arg("attrName", "String", null),
            ),
            returnType = "String",
            description = "Extract text, textcontent, html, or an attribute value from a single element matching a CSS selector."
        )

        toolSpec["scrape_all"] = ToolSpec(
            domain = domain,
            method = "scrape_all",
            arguments = listOf(
                ToolSpec.Arg("sessionId", "String", null),
                ToolSpec.Arg("field", "String", null),
                ToolSpec.Arg("selector", "String", ":root"),
                ToolSpec.Arg("attrName", "String", null),
                ToolSpec.Arg("offset", "Int", "0"),
                ToolSpec.Arg("limit", "Int", "-1"),
            ),
            returnType = "String",
            description = "Extract text, textcontent, html, or attribute values from ALL elements matching a CSS selector."
        )

        toolSpec["query"] = ToolSpec(
            domain = domain,
            method = "query",
            arguments = listOf(
                ToolSpec.Arg("sql", "String", null),
                ToolSpec.Arg("url", "String", null),
                ToolSpec.Arg("sessionId", "String", null),
            ),
            returnType = "String",
            description = "Execute an X-SQL query against the current page or a specified URL."
        )

        toolSpec["export"] = ToolSpec(
            domain = domain,
            method = "export",
            arguments = listOf(
                ToolSpec.Arg("sessionId", "String", null),
                ToolSpec.Arg("clean", "Boolean", "false"),
            ),
            returnType = "String",
            description = "Export the full, pretty-printed HTML of the current page. Set clean=true to strip <script>, <style>, and non-standard attributes (keeps the vi attribute)."
        )

        toolSpec["summary"] = ToolSpec(
            domain = domain,
            method = "summary",
            arguments = listOf(
                ToolSpec.Arg("sessionId", "String", null),
            ),
            returnType = "String",
            description = "Generate a page summary including title, statistics, and detected link groups."
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
            description = "Inspect the HTML snapshot and suggest CSS selectors for recurring patterns."
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
            else -> throw IllegalArgumentException("Unsupported html_snapshot method: $functionName")
        }
    }

    // =========================================================================
    // Tool methods
    // =========================================================================

    private suspend fun capture(args: Map<String, Any?>, receiver: Any = Any()): String {
        val managed = resolveSession(args, receiver)

        return managed.withLock {
            // Live-DOM capture (primary): serialize the LIVE document of the
            // session's active tab directly through the driver — outerHTML (or
            // the annotated serializer when the page helper is available) plus
            // title/content-type, in ONE evaluation.  Interaction state that
            // only exists in the live document (form submission results,
            // JS-updated text, toggles, eval mutations) is therefore captured
            // as documented; the tab is never reloaded or disturbed.
            val live = captureLiveDocumentSnapshot(managed)
            if (live != null) {
                val document = parseLiveDocument(live)
                htmlSnapshotMetadataJson(
                    document = document,
                    url = live.url,
                    href = live.url,
                    sizeBytes = live.html.length.toLong(),
                    capturedAt = Instant.now().toString(),
                    contentType = live.contentType.ifBlank { "text/html" },
                )
            } else {
                // No usable live document on the session's tab (page not yet
                // loaded, non-navigable/internal document, closed driver):
                // archival fallback — the independent-load capture pipeline,
                // which never replays session interactions but is the only
                // option when there is no matching live tab.
                val pulsarSession = managed.agenticSession
                val page = pulsarSession.capture(managed.driver)
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
    }

    /**
     * Serialize the live document currently shown by the session's active tab.
     *
     * Uses a single CDP evaluation so URL, title, content type and content all
     * describe the same document at the same instant (see
     * [buildLiveDocumentJs]).  When the Browser4 page helper
     * (__pulsar_utils__) is absent — a session whose tab predates the backend
     * process, or a tab-new target that never received the runtime — the
     * annotated serializer is not available and plain `documentElement.outerHTML`
     * is returned instead.  Plain outerHTML is still a LIVE serialization and
     * keeps any vi (visual-information) attributes already present in the DOM.
     *
     * @return null when there is no usable live document (no driver, the
     * evaluation failed, the document is not a navigable http(s)/file page, or
     * its content is empty).
     */
    private suspend fun captureLiveDocumentSnapshot(managed: ManagedSession): LiveDocumentSnapshot? {
        val driver = runCatching { managed.driver }.getOrNull() ?: return null

        // Best-effort re-injection of the page helper so the annotated
        // serializer is available when possible.  Failure is not fatal — the
        // serialization below falls back to plain outerHTML.
        (driver as? Browser4WebDriver)?.let { b4 ->
            runCatching { b4.ensurePulsarUtilsInjected() }.onFailure {
                logger.debug("Failed to (re-)inject the page helper before live capture: {}", it.message)
            }
        }

        val raw = runCatching { driver.evaluate(buildLiveDocumentJs()) }.getOrNull()
        if (raw == null) return null
        return parseLiveDocumentBundle(raw.toString())
    }

    /** Parse a live serialized document into a [FeaturedDocument] (jsoup-backed, base URL = the live URL). */
    private fun parseLiveDocument(live: LiveDocumentSnapshot): FeaturedDocument =
        FeaturedDocument(org.jsoup.Jsoup.parse(live.html, live.url))

    /**
     * Serve an html snapshot read from the LIVE document of the session's
     * active tab when one is available, so reads never silently serve a stale
     * stored copy that predates session interactions.
     */
    private suspend fun liveDocumentOrNull(managed: ManagedSession): FeaturedDocument? {
        val live = captureLiveDocumentSnapshot(managed) ?: return null
        return runCatching { parseLiveDocument(live) }.getOrNull()
    }

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

            // Read the LIVE document when the tab shows one — never silently
            // serve a stored copy that predates session interactions.
            val liveDocument = liveDocumentOrNull(managed)
            if (liveDocument != null) {
                extractFrom(liveDocument)
            } else {
                val pulsarSession = managed.agenticSession
                val url = pulsarSession.normalize(managed.driver.currentUrl())
                val page = pulsarSession.getOrNull(url.urlString) ?: pulsarSession.capture(managed.driver)
                extractFrom(pulsarSession.parse(page))
            }
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

            // Read the LIVE document when the tab shows one — never silently
            // serve a stored copy that predates session interactions.
            val liveDocument = liveDocumentOrNull(managed)
            if (liveDocument != null) {
                extractAllFrom(liveDocument)
            } else {
                val pulsarSession = managed.agenticSession
                val url = pulsarSession.normalize(driver.currentUrl())
                val page = pulsarSession.getOrNull(url.urlString) ?: pulsarSession.capture(driver)
                extractAllFrom(pulsarSession.parse(page))
            }
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

        // A session is mandatory only when no URL is given — the target then IS
        // the session's live page.  With an explicit URL the query is a pure
        // page-store/webdb query and may run session-less (offline corpus).
        val managed = if (explicitUrl == null) {
            resolveSession(args, receiver)
        } else {
            runCatching { resolveSession(args, receiver) }.getOrNull()
        }

        val url = explicitUrl ?: run {
            val pulsarSession = managed!!.agenticSession
            pulsarSession.normalize(managed.driver.currentUrl()).urlString
        }

        // Live-page coherence: when the query target is the page the session is
        // CURRENTLY showing, seed the page store with a fresh capture of the
        // live tab (capture reads the driver document; it never navigates).
        // The X-SQL engine's load_and_select then serves the LIVE document —
        // login state, SPA updates and eval mutations included — instead of an
        // independent re-fetch that sees none of the session state.  Targets
        // that differ from the live page (or session-less offline queries)
        // keep the independent webdb load path untouched.
        if (managed != null && queriesCurrentLivePage(managed, url)) {
            seedLivePageForQuery(managed, url)
        }

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

    /**
     * Whether an X-SQL query targeting [targetUrl] should be seeded from the
     * live page of [managed] — i.e. the normalized target URL equals the
     * normalized URL of the page the session is currently showing.
     */
    private suspend fun queriesCurrentLivePage(managed: ManagedSession, targetUrl: String): Boolean {
        val pulsarSession = managed.agenticSession
        val currentUrl = runCatching {
            pulsarSession.normalize(managed.driver.currentUrl()).urlString
        }.getOrNull() ?: return false
        val normalizedTarget = runCatching {
            pulsarSession.normalize(targetUrl).urlString
        }.getOrNull() ?: return false
        return currentUrl == normalizedTarget
    }

    /**
     * Best-effort seeding of the page store with the live document of
     * [managed]'s active tab, keyed by [targetUrl].
     *
     * Mirrors the crawl pipeline's "pre-load the page into the WebDB cache so
     * load_and_select UDFs find the page" pattern, except the record comes
     * from a driver capture of the LIVE tab instead of a network fetch — the
     * capture reads the current document and never navigates.  A subsequent
     * load_and_select without `-refresh` then serves this record, so the
     * query reflects the state the user actually sees.
     *
     * The URL is seeded with `-refresh` so the capture bypasses any existing
     * in-memory cache record for the URL: a cache-hit shell would otherwise
     * keep serving the previous record (verified empirically — a second query
     * on the same URL returned the first query's page content).  With
     * `-refresh` the pipeline builds a fresh shell, fetches the LIVE document
     * through the bound driver, and overwrites the cache entry deterministically.
     *
     * Failure is non-fatal: the caller falls back to the independent load
     * path (the previous behavior) and logs why.
     */
    private suspend fun seedLivePageForQuery(managed: ManagedSession, targetUrl: String) {
        val seeded = try {
            managed.withLock {
                val pulsarSession = managed.agenticSession
                withTimeout(30_000) {
                    pulsarSession.capture(managed.driver, url = "$targetUrl -refresh")
                }
                pulsarSession.getOrNull(pulsarSession.normalize(targetUrl).urlString) != null
            }
        } catch (e: Exception) {
            logger.warn(
                "htmlsnapshot query: failed to seed '{}' from the live page ({}); " +
                    "the query falls back to the independent load path",
                targetUrl, e.message
            )
            false
        }
        if (!seeded) {
            logger.warn(
                "htmlsnapshot query: live-page seed for '{}' did not land in the page store; " +
                    "the query uses the independent load path",
                targetUrl
            )
        }
    }

    private suspend fun export(args: Map<String, Any?>, receiver: Any = Any()): String {
        val clean = paramBool(args, "clean", "export", required = false, default = false) ?: false
        val managed = resolveSession(args, receiver)

        return managed.withLock {
            val document = liveDocumentOrNull(managed)
                ?: run {
                    val pulsarSession = managed.agenticSession
                    val url = pulsarSession.normalize(driver.currentUrl())
                    val page = pulsarSession.getOrNull(url.urlString) ?: pulsarSession.capture(driver)
                    pulsarSession.parse(page)
                }

            if (clean) {
                cleanDocument(document)
            }

            document.outerHtml
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
    private fun cleanDocument(document: ai.platon.pulsar.dom.FeaturedDocument) {
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
            // Live document first (carries its URL), archival fallback otherwise.
            val live = captureLiveDocumentSnapshot(managed)
            if (live != null) {
                val document = parseLiveDocument(live)
                val title = document.title
                PageSummaryIndexService.generate(document, live.url, title)
            } else {
                val pulsarSession = managed.agenticSession
                val url = pulsarSession.normalize(driver.currentUrl())
                val page = pulsarSession.getOrNull(url.urlString) ?: pulsarSession.capture(driver)
                val document = pulsarSession.parse(page)
                val title = document.title
                PageSummaryIndexService.generate(document, url.urlString, title)
            }
        }
    }

    private suspend fun inspect(args: Map<String, Any?>, receiver: Any = Any()): String {
        val managed = resolveSession(args, receiver)

        return managed.withLock {
            val document = liveDocumentOrNull(managed)
                ?: run {
                    val pulsarSession = managed.agenticSession
                    val url = pulsarSession.normalize(driver.currentUrl())
                    val page = pulsarSession.getOrNull(url.urlString) ?: pulsarSession.capture(driver)
                    pulsarSession.parse(page)
                }

            val selector = paramString(args, "selector", "inspect", required = false, default = ":root")?.ifEmpty { ":root" } ?: ":root"
            val maxMatches = paramInt(args, "max", "inspect", required = false, default = 20) ?: 20
            val maxDepth = paramInt(args, "depth", "inspect", required = false, default = 5) ?: 5

            inspectDocument(document, selector, maxMatches, maxDepth)
        }
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
// Live-document capture helpers (html snapshot family)
//
// htmlsnapshot captures used to run the fetch pipeline's independent page
// load (or read a previously stored copy), which never reflects state created
// by session interactions (form submissions, toggles, eval mutations) — the
// pipeline's document and the interactive tab's document are different
// documents.  These helpers serialize the LIVE document of the session's
// active tab directly (single CDP evaluation) so capture/reads reflect the
// tab state, and the html snapshot family never silently serves stale
// pre-interaction content again.
// =========================================================================

/**
 * A live document serialized from the session's active tab in one CDP
 * evaluation: URL, title, content type and the serialized HTML all describe
 * the same document at the same instant.
 */
internal data class LiveDocumentSnapshot(
    val url: String,
    val title: String,
    val contentType: String,
    val html: String,
)

/**
 * JS source evaluated on the live tab to capture the document.
 *
 * The fields are joined with the \u0001 control separator (raw control
 * characters never occur in URLs, titles, content types or HTML content), so
 * the transport cost is a single CDP evaluation and the result splits apart
 * without a JSON round trip of the (potentially large) HTML.  The content is
 * serialized with the annotated serializer (`getAnnotatedHTML`) when the
 * Browser4 page helper is available — it preserves the vi (visual-information)
 * attributes downstream consumers rely on — and plain `outerHTML` otherwise.
 * Plain outerHTML is still a live serialization and keeps vi attributes that
 * are already present in the DOM.
 */
internal fun buildLiveDocumentJs(): String {
    val marker = "\u0001"
    return "document.URL + '$marker' + document.title + '$marker' + " +
        "(document.contentType || '') + '$marker' + " +
        "(window.__pulsar_utils__ && window.__pulsar_utils__.getAnnotatedHTML ? " +
        "window.__pulsar_utils__.getAnnotatedHTML() : " +
        "(document.documentElement ? document.documentElement.outerHTML : ''))"
}

/**
 * Split a raw live-document evaluation result ([buildLiveDocumentJs]) into a
 * [LiveDocumentSnapshot].
 *
 * @return null when the document is not usable for html snapshot capture: the
 * raw result is empty or truncated, the URL is not a navigable http(s)/file
 * page (about:blank, chrome-error, data:, ...), or the content is empty.
 */
internal fun parseLiveDocumentBundle(raw: String): LiveDocumentSnapshot? {
    if (raw.isEmpty()) return null
    val parts = raw.split("\u0001", limit = 4)
    if (parts.size < 4) return null
    val url = parts[0].trim()
    val title = parts[1]
    val contentType = parts[2]
    val html = parts[3]
    if (!url.startsWith("http://") && !url.startsWith("https://") && !url.startsWith("file://")) return null
    if (html.isBlank()) return null
    return LiveDocumentSnapshot(url = url, title = title, contentType = contentType, html = html)
}

/**
 * Build the html snapshot capture metadata JSON from a parsed [document].
 *
 * Pure function — shared by the live-DOM capture path and the archival
 * (independent-load) fallback so both produce the identical output structure
 * consumed by downstream get / get all / inspect / export / summary and the
 * CLI's metadata rendering.
 *
 * @param url The document URL (normalized for the archival path, live URL for
 * the live path).
 * @param href The full href of the document.
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
