package ai.platon.pulsar.agent.tool

import ai.platon.pulsar.agentic.model.ToolSpec
import ai.platon.pulsar.agentic.tools.advanced.crawl.ScrapeRequest
import ai.platon.pulsar.agentic.tools.builtin.AbstractToolExecutor
import ai.platon.pulsar.common.serialize.json.pulsarObjectMapper
import ai.platon.pulsar.common.sql.SQLTemplate
import ai.platon.pulsar.rest.api.service.ScrapeService
import ai.platon.pulsar.rest.mcp.controller.*
import ai.platon.pulsar.rest.session.PulsarSessionManager
import ai.platon.pulsar.skeleton.workflow.parse.html.PageSummaryIndexService
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.node.ArrayNode
import kotlin.reflect.KClass

/**
 * Tool executor that exposes DOM/HTML snapshot operations as MCP tools.
 *
 * Domain: `dom_snapshot`
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
            description = "Extract text, html, or an attribute value from a single element matching a CSS selector."
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
            description = "Extract text, html, or attribute values from ALL elements matching a CSS selector."
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
            ),
            returnType = "String",
            description = "Export the full, pretty-printed HTML of the current page."
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
            "capture" -> capture(args)
            "scrape" -> scrape(args)
            "scrape_all" -> scrapeAll(args)
            "query" -> query(args)
            "export" -> export(args)
            "summary" -> summary(args)
            "inspect" -> inspect(args)
            else -> throw IllegalArgumentException("Unsupported html_snapshot method: $functionName")
        }
    }

    // =========================================================================
    // Tool methods
    // =========================================================================

    private suspend fun capture(args: Map<String, Any?>): String {
        val sessionId = requireSessionId(args)
        val managed = sessionManager.getSession(sessionId)
            ?: throw IllegalArgumentException("Session not found: $sessionId")

        return managed.withLock {
            val pulsarSession = managed.agenticSession
            val page = pulsarSession.capture(managed.driver)
            val document = pulsarSession.parse(page, noCache = true)
            val title = document.title

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

            pulsarObjectMapper().createObjectNode().apply {
                put("url", page.url)
                put("href", page.href)
                put("sizeBytes", page.contentLength.toString())
                put("capturedAt", page.prevFetchTime.toString())
                put("contentType", page.contentType)
                put("title", title)
                put("imageCount", imageCount)
                put("linkCount", linkCount)
                putArray("interactiveElements").addAll(interactiveElements)
                if (linkGroups.isNotEmpty()) {
                    set<ArrayNode>("linkGroups", linkGroupsToJson(linkGroups))
                }
            }.toString()
        }
    }

    private suspend fun scrape(args: Map<String, Any?>): String {
        val sessionId = requireSessionId(args)
        val field = paramString(args, "field", "scrape")!!
        val selector = paramString(args, "selector", "scrape", required = false, default = ":root")?.ifEmpty { ":root" } ?: ":root"
        val attrName = paramString(args, "attrName", "scrape", required = false)

        if (field !in setOf("text", "html", "attr")) {
            throw IllegalArgumentException("Unknown field '$field'. Use text, html, or attr.")
        }
        if (field == "attr" && attrName.isNullOrBlank()) {
            throw IllegalArgumentException("The 'attr' field requires an attribute name.")
        }
        if (isElementReference(selector)) {
            throw IllegalArgumentException("Element references ('$selector') are not supported in htmlsnapshot get. Use a CSS selector instead.")
        }

        val managed = sessionManager.getSession(sessionId)
            ?: throw IllegalArgumentException("Session not found: $sessionId")

        return managed.withLock {
            val pulsarSession = managed.agenticSession
            val url = pulsarSession.normalize(driver.currentUrl())
            val page = pulsarSession.getOrNull(url.urlString) ?: pulsarSession.capture(managed.driver)
            val document = pulsarSession.parse(page)

            when (field) {
                "text" -> document.selectFirstOrNull(selector)?.text() ?: ""
                "html" -> document.selectFirstOrNull(selector)?.html() ?: ""
                "attr" -> document.selectFirstOrNull(selector)?.attr(attrName!!) ?: ""
                else -> ""
            }
        }
    }

    private suspend fun scrapeAll(args: Map<String, Any?>): String {
        val sessionId = requireSessionId(args)
        val field = paramString(args, "field", "scrape_all")!!
        val selector = paramString(args, "selector", "scrape_all", required = false, default = ":root")?.ifEmpty { ":root" } ?: ":root"
        val attrName = paramString(args, "attrName", "scrape_all", required = false)
        val offset = paramInt(args, "offset", "scrape_all", required = false, default = 0) ?: 0
        val limit = paramInt(args, "limit", "scrape_all", required = false, default = -1) ?: -1

        if (field !in setOf("text", "html", "attr")) {
            throw IllegalArgumentException("Unknown field '$field'. Use text, html, or attr.")
        }
        if (field == "attr" && attrName.isNullOrBlank()) {
            throw IllegalArgumentException("The 'attr' field requires an attribute name.")
        }
        if (isElementReference(selector)) {
            throw IllegalArgumentException("Element references ('$selector') are not supported in htmlsnapshot get. Use a CSS selector instead.")
        }

        val managed = sessionManager.getSession(sessionId)
            ?: throw IllegalArgumentException("Session not found: $sessionId")

        val results = managed.withLock {
            val pulsarSession = managed.agenticSession
            val url = pulsarSession.normalize(driver.currentUrl())
            val page = pulsarSession.getOrNull(url.urlString) ?: pulsarSession.capture(managed.driver)
            val document = pulsarSession.parse(page)

            val elements = document.select(selector)
            val paginated = if (offset > 0) elements.drop(offset) else elements
            val limited = if (limit > 0) paginated.take(limit) else paginated

            limited.map { element ->
                when (field) {
                    "text" -> element.text()
                    "html" -> element.html()
                    "attr" -> element.attr(attrName!!)
                    else -> ""
                }
            }
        }

        @Suppress("UNCHECKED_CAST")
        val resultList = results as List<Any?>
        return pulsarObjectMapper().copy()
            .setSerializationInclusion(JsonInclude.Include.ALWAYS)
            .writeValueAsString(resultList)
    }

    private suspend fun query(args: Map<String, Any?>): String {
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

        val url = paramString(args, "url", "query", required = false)?.takeIf { it.isNotBlank() }
            ?: run {
                val sessionId = requireSessionId(args)
                val managed = sessionManager.getSession(sessionId)
                    ?: throw IllegalArgumentException("Session not found: $sessionId")
                val pulsarSession = managed.agenticSession
                pulsarSession.normalize(managed.driver.currentUrl()).urlString
            }

        val processedSql = SQLTemplate(sql).createSQL(url)
        val response = scrapeService.executeQuery(ScrapeRequest(processedSql))
        return pulsarObjectMapper().copy()
            .setSerializationInclusion(JsonInclude.Include.ALWAYS)
            .writeValueAsString(response)
    }

    private suspend fun export(args: Map<String, Any?>): String {
        val sessionId = requireSessionId(args)
        val managed = sessionManager.getSession(sessionId)
            ?: throw IllegalArgumentException("Session not found: $sessionId")

        return managed.withLock {
            val pulsarSession = managed.agenticSession
            val url = pulsarSession.normalize(driver.currentUrl())
            val page = pulsarSession.getOrNull(url.urlString) ?: pulsarSession.capture(managed.driver)
            val document = pulsarSession.parse(page)
            document.outerHtml
        }
    }

    private suspend fun summary(args: Map<String, Any?>): String {
        val sessionId = requireSessionId(args)
        val managed = sessionManager.getSession(sessionId)
            ?: throw IllegalArgumentException("Session not found: $sessionId")

        return managed.withLock {
            val pulsarSession = managed.agenticSession
            val url = pulsarSession.normalize(driver.currentUrl())
            val page = pulsarSession.getOrNull(url.urlString) ?: pulsarSession.capture(managed.driver)
            val document = pulsarSession.parse(page)
            val title = document.title
            val pageUrl = url.urlString

            PageSummaryIndexService.generate(document, pageUrl, title)
        }
    }

    private suspend fun inspect(args: Map<String, Any?>): String {
        val sessionId = requireSessionId(args)
        val managed = sessionManager.getSession(sessionId)
            ?: throw IllegalArgumentException("Session not found: $sessionId")

        return managed.withLock {
            val pulsarSession = managed.agenticSession
            val url = pulsarSession.normalize(driver.currentUrl())
            val page = pulsarSession.getOrNull(url.urlString) ?: pulsarSession.capture(managed.driver)
            val document = pulsarSession.parse(page)

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
