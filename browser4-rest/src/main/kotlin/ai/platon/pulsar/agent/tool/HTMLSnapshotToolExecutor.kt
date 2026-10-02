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
 * ## The contract: a fresh snapshot of the active page, then the operation
 *
 * This family is about the page the session is *showing*, so no command trusts an older copy of
 * it: a command first **captures** the active tab — serializing the document the tab already
 * shows, without navigating — and then operates on that snapshot.
 *
 *  - `capture` *returns* the snapshot: page metadata (url, href, title, size, timestamps,
 *    interactive elements, link groups).
 *  - every read (`get`, `get all`, `export`, `summary`, `inspect`, `readability`, `query`)
 *    *consumes* it, so a read sees the tab as it is right now — form submissions, SPA updates,
 *    `eval` mutations, login state — with no separate capture step.
 *
 * Two consequences, and both are deliberate:
 *
 *  1. A read of the active page also archives it, under the tab's **own** normalized url, with
 *     [MUST_WRITE_OPTION] semantics (it overwrites the stored copy — the flag is a *cache* flag,
 *     not a reload; see its KDoc).
 *  2. A read **never files a document under a url the caller passed.**  A url the tab does not
 *     show cannot be captured (a capture labels the document with the *tab's own* url), so such a
 *     target keeps the read-only path: the stored copy of that url, or an independent read-only
 *     load when the store has nothing — fetched on the shared scrape session, never on the
 *     session's own tab, so a read of another url cannot move the page out from under the caller.
 *     This is the trap the family used to fall into: the read fallback captured the live tab *and
 *     labelled it with the requested url*, so `readability <other-url>` returned the current page
 *     as if it were `<other-url>` and poisoned that url's store row.
 *
 * The url/href invariant holds throughout: the *normalized url* is the store identity, and the
 * *original href* travels beside it as the browser-facing address.
 *
 * ## Methods
 *
 * - `capture(sessionId)` — Serialize the live tab's document, persist it, and return metadata
 * - `scrape(sessionId, field, selector?, attrName?)` — Extract text/html/attr from a single element
 * - `scrape_all(sessionId, field, selector?, attrName?, offset?, limit?)` — Extract from all matching elements
 * - `query(sessionId?, sql, url?)` — Execute an X-SQL query against the active page, or against a stored url
 * - `export(sessionId)` — Export the full HTML of the current page
 * - `summary(sessionId)` — Generate a page summary with link groups
 * - `inspect(sessionId, selector?, max?, depth?)` — Inspect the HTML snapshot for selector suggestions
 * - `readability(sessionId, url?)` — Extract the readable article text of the active page, or of a stored url
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
            description = "Capture the active tab as an HTML snapshot and return its metadata: url, href, title, size, " +
                "timestamps, interactive elements (tag, class, id, aria, bounding box) and link groups. Capturing first is " +
                "what every htmlsnapshot command does — the reads (get, get all, export, summary, inspect, readability, " +
                "query) capture the active page too, then operate on that fresh snapshot, so the snapshot this command " +
                "returns is the same one they see. Use it when the metadata itself is what you need.",
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
                "Operates on a FRESH snapshot of the active page: the live tab is captured first, then read, so form " +
                "submissions, SPA updates and `eval` mutations are visible without a separate capture.",
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
                "Operates on a FRESH snapshot of the active page: the live tab is captured first, then read, so form " +
                "submissions, SPA updates and `eval` mutations are visible without a separate capture.",
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
            description = "Execute an X-SQL query against a FRESH snapshot of the active page (the live tab is captured " +
                "first, then queried, so the query sees the page as it is now). With a url argument instead: the query " +
                "targets THAT url's stored page — a url the tab does not show cannot be captured — and it runs " +
                "without a session, so offline corpus queries keep working. IMPORTANT: CSS selectors in X-SQL must use " +
                "single quotes (SQL syntax); double quotes mean SQL identifiers.",
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
            description = "Export the full, pretty-printed HTML of a FRESH snapshot of the active page (the live tab is " +
                "captured first, so the export is the page as it is now). Set clean=true to strip <script>, <style>, and " +
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
            description = "Generate a page summary including title, statistics, and detected link groups from a FRESH " +
                "snapshot of the active page (the live tab is captured first, so the summary is the page as it is now).",
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
            description = "Inspect the HTML snapshot and suggest CSS selectors for recurring patterns. Operates on a FRESH " +
                "snapshot of the active page: the live tab is captured first, then inspected.",
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
                ToolSpec.Arg("url", "String?", "null", "Read this URL's stored page instead of the active page."),
            ),
            returnType = "String",
            outputSchema = ToolResultSchemas.HTML_SNAPSHOT_READABILITY,
            description = "Extract the main article content (title, byline, site name, excerpt, cleaned HTML, plain text) with a " +
                "Readability-style heuristic. Without url it reads a FRESH snapshot of the active page (the live tab is " +
                "captured first). With url it reads THAT url's own stored page — or loads it read-only on the shared " +
                "scrape session, never on the caller's tab, when the store has nothing — because a url the tab does not " +
                "show cannot be captured, so the tab's document is never filed under it.",
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
     * Snapshot the session's active tab into the page store and return the snapshot's metadata.
     *
     * The snapshot is keyed by the *normalized* url of the tab — the identity every other lookup uses
     * (see `NormURL` and `PulsarSession.normalize`) — the raw address travels beside it as the
     * metadata `href`, and the row overwrites whatever the store held for that url.  Re-capturing is
     * therefore how a page that changed in the tab (a form submitted, a list sorted, `eval`-inserted
     * markup) reaches the store, and it is exactly what every read command in this family does first:
     * this command exists for the metadata (title, size, timestamps, interactive elements, link
     * groups) that the reads do not return.
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
            val address = requireArchivableActivePage(
                runCatching { managed.driver.currentUrl() }.getOrNull(),
                action = "capture",
            )

            // [MUST_WRITE_OPTION] is what makes the write real — it is a *cache* flag, not a reload:
            // see its KDoc for what it does, what it deliberately does not do (capture never
            // navigates), and why the second capture of a url needs it.
            val page = captureActivePage(managed, address)
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
     * The active tab's address, refusing a tab that shows nothing this family can snapshot — with the
     * address named, so the caller learns *what* was refused instead of reading an internal error.
     *
     * [action] is the command being attempted ("capture", "read", "query"), so the same refusal reads
     * naturally from either half of the family.
     */
    private fun requireArchivableActivePage(address: String?, action: String): String {
        val archivable = address?.takeIf { isArchivableAddress(it) }
        require(archivable != null) {
            "Nothing to $action: the active tab shows <${address ?: "no page"}>. Navigate to an http(s) " +
                "or file:// page first — about:blank and browser error pages have no snapshot."
        }
        return archivable
    }

    /**
     * Serialize the live document of the active tab into the page store and return the page that was
     * written — the snapshot this family operates on, keyed by the tab's own normalized url.
     *
     * [MUST_WRITE_OPTION] rides inside the url because `PulsarSession.capture(driver, url)` has no
     * options parameter; without it a second capture of the same url reports the tab while the store
     * keeps the older document (see the constant's KDoc).
     */
    private suspend fun captureActivePage(managed: ManagedSession, address: String): WebPage =
        managed.agenticSession.capture(managed.driver, url = "$address $MUST_WRITE_OPTION")

    /**
     * The normalized identity of the page the active tab shows, or null when the tab shows nothing
     * archivable (`about:blank`, a browser error page, no tab at all).
     */
    private suspend fun activePageKeyOrNull(managed: ManagedSession): String? {
        val address = runCatching { managed.driver.currentUrl() }.getOrNull() ?: return null
        if (!isArchivableAddress(address)) return null
        return normalizeOrNull(managed, address)
    }

    /** [url] normalized to the store identity, or null when it cannot be normalized at all. */
    private suspend fun normalizeOrNull(managed: ManagedSession, url: String): String? =
        runCatching { managed.agenticSession.normalize(url).urlString }.getOrNull()

    /**
     * The snapshot a command operates on: **the active page, captured a moment ago**, or — for a url
     * the tab does not show — that url's stored page.
     *
     * [requestedUrl] is what the caller asked for (`readability` and `query` accept one); null means
     * "whatever the tab is showing".
     *
     * When the target *is* the active page (no url, or a url that normalizes to the tab's own key) the
     * tab is captured first and the page that was just written is returned.  That is the family's
     * contract, and it is why a read sees form submissions, SPA updates and `eval` mutations without a
     * capture of its own — no read ever serves an older copy of the page on screen.
     *
     * A url the tab does *not* show cannot be captured — a capture labels the document with the tab's
     * own url, never with an argument — so that target stays on the read-only path
     * ([storedPageOrIndependentLoad]).  Nothing is ever filed under the requested url; that is the
     * invariant this branch exists for.
     */
    private suspend fun snapshotPageFor(managed: ManagedSession, requestedUrl: String?): WebPage {
        if (requestedUrl != null) {
            val requestedKey = normalizeOrNull(managed, requestedUrl) ?: requestedUrl
            if (requestedKey != activePageKeyOrNull(managed)) {
                return storedPageOrIndependentLoad(managed, requestedKey)
            }
        }

        val address = requireArchivableActivePage(
            runCatching { managed.driver.currentUrl() }.getOrNull(),
            action = "read",
        )
        return captureActivePage(managed, address)
    }

    /**
     * The url an X-SQL query targets, refreshing the active page's snapshot when the query is about
     * the page on screen.
     *
     * Without [explicitUrl] the target *is* the active page: it is captured first (the family's
     * contract) and the normalized identity that was just written is returned, so
     * `load_and_select(@url, ...)` serves the document the tab shows now.
     *
     * With [explicitUrl] the query may be about a page the session is not showing — an offline corpus
     * query, say — which no capture can produce.  Such a query stays session-optional: the session is
     * looked up **without recovery** and the url passes through untouched, unless the session is
     * showing exactly that page — then the caller asked about the active page, and it is refreshed
     * like any other read.
     */
    private suspend fun resolveQueryTarget(
        args: Map<String, Any?>,
        receiver: Any,
        explicitUrl: String?,
    ): String {
        if (explicitUrl == null) {
            val managed = resolveSession(args, receiver)
            val address = requireArchivableActivePage(
                runCatching { managed.driver.currentUrl() }.getOrNull(),
                action = "query",
            )
            // Capture, then query the identity that was just written — the same snapshot a read sees.
            val page = captureActivePage(managed, address)
            return page.url.ifBlank { normalizeOrNull(managed, address) ?: address }
        }

        val managed = liveSessionOrNull(args, receiver) ?: return explicitUrl
        val address = boundAddressOrNull(managed)?.takeIf { isArchivableAddress(it) } ?: return explicitUrl
        val requestedKey = normalizeOrNull(managed, explicitUrl) ?: return explicitUrl
        if (normalizeOrNull(managed, address) != requestedKey) return explicitUrl

        // The caller named the very page the session is showing: refresh it like any other read.
        captureActivePage(managed, address)
        return requestedKey
    }

    /**
     * The session named by the arguments **without creating or recovering anything** — or null.
     *
     * This is the manager's read-only lookup: it never launches a browser, never health-checks and
     * never recreates a session.  That matters for a url-targeted query, which is often a pure
     * page-store query about a page the session is not showing: asking whether the session is on that
     * url must not resurrect a browser as a side effect.
     */
    private fun liveSessionOrNull(args: Map<String, Any?>, receiver: Any): ManagedSession? {
        if (receiver is ManagedSession) return receiver
        val sessionId = args["sessionId"]?.toString()?.takeIf { it.isNotBlank() } ?: return null
        return sessionManager.getSession(sessionId)
    }

    /**
     * The address the session's **already bound** tab is showing, or null when the session has no
     * bound driver.  Never creates one: see [liveSessionOrNull].
     */
    private suspend fun boundAddressOrNull(managed: ManagedSession): String? =
        managed.agenticSession.boundDriver?.let { runCatching { it.currentUrl() }.getOrNull() }

    /**
     * The stored page of [url], or an independent read-only load when the store has nothing.
     *
     * This is the path for a url the active tab does **not** show — the only one that cannot be
     * captured.  Two invariants hold here:
     *
     *  * nothing is ever filed under [url] (no capture is involved at all), and
     *  * **the session's own tab is never touched.**  A browser load resolves its driver from the
     *    page config, which inherits the *session's* bound driver, so `agenticSession.load(...)` would
     *    navigate the tab the caller is looking at — a read that moves the page out from under the
     *    user.  (`HtmlSnapshotScenariosE2ETest#test1h` pins this: it reads a foreign url that is not in
     *    the store and then asserts the tab still shows the original page.)  The load therefore goes to
     *    the shared scrape session, which has no bound driver, so the fetch runs on a privacy-scoped
     *    driver — exactly the path `html_snapshot_query --url` takes for a corpus page.
     */
    private suspend fun storedPageOrIndependentLoad(managed: ManagedSession, url: String): WebPage {
        // The store first: the copy a capture (or any earlier load) filed is the cheapest answer, and
        // it needs no browser at all.
        managed.agenticSession.getOrNull(url)?.let { return it }

        val readOnlyUrl = "$url ${ScrapeAPIUtils.READ_ONLY_OPTION}"
        return sessionManager.ensureSwarmSession().agenticSession.load(readOnlyUrl)
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

            // The active page's FRESH snapshot: the live tab is captured first, then read — see the
            // contract at the bottom of this file.
            val pulsarSession = managed.agenticSession
            extractFrom(pulsarSession.parse(snapshotPageFor(managed, requestedUrl = null), noCache = true))
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

            // The active page's FRESH snapshot, as `get` does — see the contract at the bottom of this
            // file.
            val pulsarSession = managed.agenticSession
            extractAllFrom(pulsarSession.parse(snapshotPageFor(managed, requestedUrl = null), noCache = true))
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
        val url = resolveQueryTarget(args, receiver, explicitUrl)

        // The query runs against what [resolveQueryTarget] just captured whenever it is about the
        // active page, so `load_and_select(@url, ...)` serves the live document without the caller
        // having to capture first — the family's contract, see the banner at the bottom of this file.
        // For a url the tab does not show it is a pure page-store query: the engine's read-only load
        // path answers from the page cache when it can and otherwise loads the page independently.
        //
        // This used to seed the store from the live tab whenever the target happened to be the current
        // page — a read that wrote, on a target the caller never asked to capture.  Now the capture is
        // unconditional for the active page and always keyed by the tab's own url, so a query of
        // another page can never be answered with the tab's document.
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
            // The active page's FRESH snapshot — see the contract at the bottom of this file.
            val pulsarSession = managed.agenticSession
            val document = pulsarSession.parse(snapshotPageFor(managed, requestedUrl = null), noCache = true)

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
        /**
         * The load option that makes a capture actually **write**: `-refresh`.
         *
         * ### What it does
         *
         * It makes the load pipeline build a fresh page shell instead of reusing the process-wide
         * page-cache copy (`LoadComponent.getCachedPageOrNull` returns null for a refreshing url), and
         * marks the fetch state as `REFRESH` (retry counter reset).
         *
         * ### What it does NOT do
         *
         * It does **not** reload, re-navigate or re-fetch the page.  Which of the two things a
         * pipeline fetch does is decided by `page.hasVar(VAR_CAPTURE)` inside
         * `InteractiveBrowserEmulator.browseWithWebDriver`: capture mode calls `captureLivePage`
         * (serialize the document the tab already shows — no `driver.navigate`, no scroll, no
         * network), while the navigate path (`navigateAndInteract`) is the *other* branch.  The
         * document keeps coming from the tab, so interactions survive the capture.
         *
         * ### Why capture needs it
         *
         * A cached page shell poisons a capture twice over, and both bites land on the **second**
         * capture of a url — the first one is what puts that url into the page cache (`onLoaded`
         * caches every non-readonly load, capture included):
         *
         *  1. `createPageShell` pins the shell's content to the cached bytes: it calls
         *     `clearPersistContent()`, which sets `tmpContent`, and `GoraWebPage.content` returns
         *     `tmpContent` whenever that is non-null.  The freshly serialized live document is
         *     written into the persist buffer and then never read, so capture *reports* the previous
         *     document instead of the tab.
         *  2. `persist` deliberately drops the CONTENT field of a cached page
         *     (`if (page.isCached) page.unbox().clearDirty(GWebPage.Field.CONTENT.index)`), so the
         *     store keeps the previous bytes as well — with a fresh `prevFetchTime` on top, and
         *     served for the whole default `EXPIRES` window (decades).
         *
         * `HtmlSnapshotScenariosE2ETest#test1f` is the regression test: it captures, mutates the tab,
         * captures again, and reads the store back.  As a negative control, dropping this option from
         * the call below makes it fail (the second capture returns the first capture's document).
         *
         * Every command of the family captures — that is the contract (`captureActivePage` is the one
         * place that loads the tab) — so every one of them inherits this option, reads included.
         *
         * ### Why it travels inside the url string
         *
         * `PulsarSession.capture(driver, url)` has no `LoadOptions` parameter, and the normalizer
         * splits trailing arguments off the url (`URLUtils.splitUrlArgs`, see
         * `CombinedUrlNormalizer.normalize`), so `"$url -refresh"` is the supported spelling —
         * the same one `AbstractPulsarSession.open` uses.
         */
        internal const val MUST_WRITE_OPTION = "-refresh"

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
            // The active page's FRESH snapshot — see the contract at the bottom of this file.
            val pulsarSession = managed.agenticSession
            val page = snapshotPageFor(managed, requestedUrl = null)
            val document = pulsarSession.parse(page, noCache = true)
            PageSummaryIndexService.generate(document, page.url, document.title)
        }
    }

    private suspend fun inspect(args: Map<String, Any?>, receiver: Any = Any()): String {
        val managed = resolveSession(args, receiver)

        return managed.withLock {
            // The active page's FRESH snapshot — see the contract at the bottom of this file.
            val pulsarSession = managed.agenticSession
            val document = pulsarSession.parse(snapshotPageFor(managed, requestedUrl = null), noCache = true)

            val selector = paramString(args, "selector", "inspect", required = false, default = ":root")?.ifEmpty { ":root" } ?: ":root"
            val maxMatches = paramInt(args, "max", "inspect", required = false, default = 20) ?: 20
            val maxDepth = paramInt(args, "depth", "inspect", required = false, default = 5) ?: 5

            inspectDocument(document, selector, maxMatches, maxDepth)
        }
    }

    /**
     * Extract the main article content from the HTML snapshot using a Readability-style heuristic
     * (deterministic, no LLM).
     *
     * @param args `url` (optional) — the page to read.
     *   - Without it: a fresh snapshot of the **active page** (the live tab is captured first, then
     *     read), so the article is the one the tab shows right now.
     *   - With it: that url's **own** stored copy, or an independent read-only load when the store has
     *     nothing (loaded on the shared scrape session — the caller's tab is never navigated).  A url
     *     the tab does not show cannot be captured — a capture labels the document with the tab's own
     *     url — so the requested url's row is never filled with the tab's document.
     *     (This is the trap the old code fell into: its fallback called `capture(driver, url)`, so
     *     `readability <other-url>` stored the live tab's document — and returned its article — under
     *     `<other-url>`, poisoning every later lookup of that url.)
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

            val page = snapshotPageFor(managed, requestedUrl)
            val document = pulsarSession.parse(page, noCache = true)

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
// The html snapshot family's contract: a fresh snapshot, then the operation
//
// This family is about the page the session is SHOWING, so every command
// captures the active tab first — serializing the document the tab already
// shows, without navigating — and then works on that snapshot:
//
//   * `capture` returns the snapshot: page metadata, interactive elements,
//     link groups;
//   * `get`, `get all`, `export`, `summary`, `inspect`, `readability` and
//     `query` consume it, so a read sees form submissions, SPA updates and
//     `eval` mutations with no capture of its own.
//
// The capture is always keyed by the ACTIVE TAB's own normalized url, never by
// a url the caller passed.  A url the tab does not show therefore cannot be
// captured, and that target keeps the read-only path: the stored copy of that
// url, or an independent read-only load when the store has nothing — so nothing
// is ever filed under the requested url.
//
// The distinction matters because the underlying documents differ: the stored
// page is what the fetch pipeline produced, while form submissions, toggles and
// `eval` mutations exist only in the interactive tab.  Reads used to capture
// implicitly *under the requested url*, which is why `readability <other-url>`
// could return the tab's document as if it were `<other-url>` and poison that
// url's store row.  The fix is not "never capture on a read" — a read must see
// the page as it is now — it is "capture, keyed by the tab's own url".
// =========================================================================

/**
 * Build the html snapshot capture metadata JSON from a parsed [document].
 *
 * Pure function — the output structure consumed by downstream get / get all / inspect / export /
 * summary and the CLI's metadata rendering.  Only `capture` *returns* it (the reads in this family
 * work on the same snapshot but return their own results — see the contract at the bottom of this
 * file).
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
