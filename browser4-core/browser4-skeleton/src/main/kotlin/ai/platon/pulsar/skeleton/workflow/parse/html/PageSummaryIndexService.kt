package ai.platon.pulsar.skeleton.workflow.parse.html

import ai.platon.pulsar.dom.FeaturedDocument

/**
 * Web Page Summary Index (WPSI) — generates a compressed, AI-readable YAML summary
 * from a parsed [FeaturedDocument].
 *
 * The summary is typically 0.1%–1% the size of the original HTML while preserving:
 * - Page metadata and type inference
 * - Landmark structure (header, nav, main, footer, etc.)
 * - Scored key content nodes with CSS selector hints
 * - Repeated structure detection (lists, tables)
 * - Aggregated statistics
 *
 * Every summary node carries its `vi` attribute (bounding box) so it can be
 * traced back to the original DOM element.
 *
 * The algorithm is fully deterministic — no AI model is involved.
 */
object PageSummaryIndexService {

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Generate a WPSI YAML summary from a [FeaturedDocument].
     *
     * @param document  The parsed DOM document (never mutated — the algorithm
     *                  works on a clone).
     * @param pageUrl   The normalized page URL.
     * @param title     The page title (from [FeaturedDocument.title]).
     * @return A YAML string suitable for saving to a `.yml` file.
     */
    fun generate(
        document: FeaturedDocument,
        pageUrl: String,
        title: String,
    ): String {
        // Phase 1: clone and clean DOM
        val cleaned = FeaturedDocument(document.document.clone())
        cleaned.select("script, style, meta, link, noscript").remove()

        // Phase 2: index nodes by vi attribute (bounding box)
        val indexedNodes = indexNodes(cleaned)
        if (indexedNodes.isEmpty()) {
            return buildString {
                appendLine("page:")
                appendLine("  type: Empty")
                appendLine()
                appendLine("stats:")
                appendLine("  nodes: 0")
            }
        }

        // Phase 3: score nodes
        val scoredNodes = indexedNodes.map { node ->
            val score = computeNodeScore(node)
            val typeLabel = nodeTypeLabel(node.tag, node.text, node.className, node.id)
            SummaryScoredNode(node, score, typeLabel)
        }

        // Phase 4: landmark identification
        val landmarkTags = setOf("header", "nav", "main", "article", "aside", "footer", "section")
        val landmarks = indexedNodes.filter { it.tag in landmarkTags }

        // Phase 5: key node extraction (top 100 by score)
        val keyNodes = scoredNodes
            .filter { it.score > 0 }
            .sortedByDescending { it.score }
            .take(100)

        // Phase 6: list detection
        val lists = detectLists(indexedNodes)

        // Phase 7: table summary
        val tables = summarizeTables(indexedNodes)

        // Stats
        val stats = computeStats(indexedNodes)

        // Build YAML
        val pageType = inferPageType(indexedNodes)
        return buildYamlSummary(
            pageUrl = pageUrl,
            title = title,
            pageType = pageType,
            landmarks = landmarks,
            keyNodes = keyNodes,
            lists = lists,
            tables = tables,
            stats = stats,
        )
    }

    // =========================================================================
    // Data classes
    // =========================================================================

    /** Indexed DOM node, identified by its `vi` attribute (bounding box). */
    data class SummaryIndexedNode(
        val box: String,
        val element: org.jsoup.nodes.Element,
        val depth: Int,
        val tag: String,
        val text: String,
        val className: String,
        val id: String,
    )

    data class SummaryScoredNode(
        val indexed: SummaryIndexedNode,
        val score: Int,
        val typeLabel: String,
    )

    data class SummaryListGroup(
        val parentTag: String,
        val parentId: String,
        val parentClass: String,
        val itemTag: String,
        val count: Int,
        val samples: List<SummaryIndexedNode>,
    )

    data class SummaryTableInfo(
        val box: String,
        val rows: Int,
        val cols: Int,
        val headers: List<String>,
    )

    data class SummaryStats(
        val nodes: Int,
        val links: Int,
        val buttons: Int,
        val forms: Int,
        val tables: Int,
        val images: Int,
        val inputs: Int,
    )

    // =========================================================================
    // Phase implementations
    // =========================================================================

    /** Phase 2: breadth-first traversal, indexing elements that carry a `vi` attr. */
    private fun indexNodes(document: FeaturedDocument): List<SummaryIndexedNode> {
        val result = mutableListOf<SummaryIndexedNode>()
        val queue = ArrayDeque<Pair<org.jsoup.nodes.Node, Int>>()
        queue.add(document.document to 0)
        while (queue.isNotEmpty()) {
            val (node, depth) = queue.removeFirst()
            if (node is org.jsoup.nodes.Element) {
                val box = node.attr("vi").trim()
                if (box.isNotEmpty()) {
                    result.add(
                        SummaryIndexedNode(
                            box = box,
                            element = node,
                            depth = depth,
                            tag = node.tagName().lowercase(),
                            text = node.ownText().trim(),
                            className = node.className(),
                            id = node.id(),
                        )
                    )
                }
                for (child in node.children()) {
                    queue.add(child to depth + 1)
                }
            }
        }
        return result
    }

    /** Phase 7: extract table dimensions and headers. */
    private fun summarizeTables(nodes: List<SummaryIndexedNode>): List<SummaryTableInfo> =
        nodes.filter { it.tag == "table" }.map { table ->
            val rows = table.element.select("tr").size
            val headers = table.element.select("th").map { it.text().trim() }.filter { it.isNotEmpty() }
            val cols = if (headers.isNotEmpty()) headers.size
            else table.element.select("tr").first()?.select("td,th")?.size ?: 0
            SummaryTableInfo(table.box, rows, cols, headers)
        }

    private fun computeStats(nodes: List<SummaryIndexedNode>): SummaryStats = SummaryStats(
        nodes = nodes.size,
        links = nodes.count { it.tag == "a" },
        buttons = nodes.count { it.tag == "button" },
        forms = nodes.count { it.tag == "form" },
        tables = nodes.count { it.tag == "table" },
        images = nodes.count { it.tag == "img" },
        inputs = nodes.count { it.tag == "input" },
    )

    // =========================================================================
    // Scoring
    // =========================================================================

    /**
     * Deterministic scoring weights:
     *   h1=100, h2=50, h3=30, h4=20, h5=10, h6=5
     *   button=50, input=50, select=40, textarea=40
     *   table=60, form=40
     *   img (with alt)=20, img (no alt)=5
     *   a (with text)=15, a (empty)=0
     *   landmark tags (header/nav/main/...)=15
     *   ul/ol (>3 children)=25, dl=20, li/dd/dt=10, label=25, option=5
     *   strong/em/b/i=10
     *   p — up to 15 based on text length
     *   div/span — up to 10 based on text length
     *   id bonus=10, class bonus=5
     */
    private fun computeNodeScore(node: SummaryIndexedNode): Int {
        val tag = node.tag
        val text = node.text

        val baseScore = when (tag) {
            "h1" -> 100
            "h2" -> 50
            "h3" -> 30
            "h4" -> 20
            "h5" -> 10
            "h6" -> 5
            "button" -> 50
            "input" -> 50
            "select" -> 40
            "textarea" -> 40
            "table" -> 60
            "form" -> 40
            "a" -> if (text.isNotBlank()) 15 else 0
            "img" -> if (node.element.attr("alt").isNotBlank()) 20 else 5
            "header", "nav", "main", "article", "aside", "footer", "section" -> 15
            "ul", "ol" -> if (node.element.childrenSize() > 3) 25 else 5
            "dl" -> 20
            "p" -> minOf(text.length / 4, 15)
            "li", "dd", "dt" -> 10
            "label" -> 25
            "option" -> 5
            "strong", "em", "b", "i" -> 10
            "div", "span" -> if (text.isNotEmpty()) minOf(text.length / 6, 10) else 0
            else -> if (text.isNotEmpty()) minOf(text.length / 10, 5) else 0
        }

        val idBonus = if (node.id.isNotBlank()) 10 else 0
        val classBonus = if (node.className.isNotBlank()) 5 else 0

        return baseScore + idBonus + classBonus
    }

    // =========================================================================
    // Type labels & selector hints
    // =========================================================================

    /** Human-readable type label for a node. */
    private fun nodeTypeLabel(tag: String, text: String, className: String, id: String): String {
        if (tag.startsWith("h") && tag.length == 2 && tag[1] in '1'..'6') return tag
        return when (tag) {
            "a" -> "link"
            "p" -> "text"
            "ul", "ol" -> "list"
            "li" -> "item"
            "button" -> "button"
            "input" -> "input"
            "img" -> "image"
            "form" -> "form"
            "table" -> "table"
            "select" -> "select"
            "textarea" -> "textarea"
            "label" -> "label"
            "nav" -> "navigation"
            "header" -> "header"
            "footer" -> "footer"
            "main" -> "main"
            "aside" -> "aside"
            "section" -> "section"
            "article" -> "article"
            "strong", "em", "b", "i" -> "emphasis"
            else -> tag
        }
    }

    /** CSS selector hint from id (preferred) or class. */
    private fun buildSelectorHint(className: String, id: String): String {
        if (id.isNotBlank()) return "#$id"
        if (className.isNotBlank()) return ".$className"
        return ""
    }

    // =========================================================================
    // Page type inference
    // =========================================================================

    /** Heuristic page-type detection from DOM text and tags. */
    private fun inferPageType(nodes: List<SummaryIndexedNode>): String {
        val text = nodes.joinToString(" ") { it.text }.lowercase()
        val tags = nodes.map { it.tag }.toSet()

        val hasPrice = text.contains("$") || text.contains("¥") || text.contains("€") ||
                Regex("""\$\d+""").containsMatchIn(text) ||
                Regex("""\d+\.\d{2}""").containsMatchIn(text)
        val hasAddToCart = text.contains("add to cart") || text.contains("buy now") ||
                text.contains("加入购物车") || text.contains("立即购买")
        val hasProduct = text.contains("product") || text.contains("商品") || text.contains("产品")
        val hasSearch = tags.contains("input") && (text.contains("search") || text.contains("搜索"))
        val hasLogin = text.contains("login") || text.contains("sign in") || text.contains("登录")
        val hasArticle = tags.contains("article") || nodes.any {
            it.tag in listOf("h1", "h2") && it.text.length > 30
        }
        val hasForm = tags.contains("form") && (text.contains("submit") || text.contains("提交"))
        val hasVideo = tags.contains("video") || text.contains("video") || text.contains("视频")

        return when {
            hasAddToCart || (hasPrice && hasProduct) -> "Product Detail"
            hasSearch && hasPrice -> "Search Results"
            hasLogin && !hasArticle -> "Login / Auth"
            hasForm && !hasArticle -> "Form Page"
            hasVideo -> "Media Page"
            hasArticle -> "Article / Content"
            text.contains("blog") || text.contains("博客") -> "Blog"
            text.contains("forum") || text.contains("论坛") -> "Forum"
            text.contains("documentation") || text.contains("文档") -> "Documentation"
            else -> "General Page"
        }
    }

    // =========================================================================
    // List detection
    // =========================================================================

    /**
     * Detect repeated structures (lists of similar items).
     *
     * Heuristic: find parent elements whose direct children share the same tag
     * and appear at least [minItems] times.
     */
    private fun detectLists(
        nodes: List<SummaryIndexedNode>,
        minItems: Int = 3,
    ): List<SummaryListGroup> {
        val groups = mutableListOf<SummaryListGroup>()
        val seenParents = mutableSetOf<String>()

        for (node in nodes) {
            if (node.box in seenParents) continue
            val children = node.element.children()
            if (children.size < minItems) continue

            val byTag = children.groupBy { it.tagName().lowercase() }
            for ((childTag, items) in byTag) {
                if (items.size < minItems) continue
                if (childTag in setOf("div", "li", "tr", "article", "section", "option")) {
                    val samples = items.take(3).mapNotNull { child ->
                        nodes.firstOrNull { it.element === child }
                    }
                    if (samples.isNotEmpty()) {
                        seenParents.add(node.box)
                        groups.add(
                            SummaryListGroup(
                                parentTag = node.tag,
                                parentId = node.id,
                                parentClass = node.className,
                                itemTag = childTag,
                                count = items.size,
                                samples = samples,
                            )
                        )
                    }
                }
            }
        }

        return groups.sortedByDescending { it.count }.take(5)
    }

    // =========================================================================
    // YAML builder
    // =========================================================================

    @Suppress("MaxLineLength")
    private fun buildYamlSummary(
        pageUrl: String,
        title: String,
        pageType: String,
        landmarks: List<SummaryIndexedNode>,
        keyNodes: List<SummaryScoredNode>,
        lists: List<SummaryListGroup>,
        tables: List<SummaryTableInfo>,
        stats: SummaryStats,
    ): String = buildString {
        appendLine("page:")
        if (title.isNotBlank()) appendLine("  title: ${title.toYamlValue()}")
        appendLine("  url: ${pageUrl.toYamlValue()}")
        appendLine("  type: ${pageType.toYamlValue()}")
        appendLine()

        if (landmarks.isNotEmpty()) {
            appendLine("structure:")
            for (lm in landmarks) {
                val selector = buildSelectorHint(lm.className, lm.id)
                appendLine("  - box: ${lm.box}")
                appendLine("    tag: ${lm.tag}")
                if (selector.isNotEmpty()) appendLine("    selector: ${selector.toYamlValue()}")
            }
            appendLine()
        }

        if (keyNodes.isNotEmpty()) {
            appendLine("content:")
            for (sn in keyNodes) {
                val selector = buildSelectorHint(sn.indexed.className, sn.indexed.id)
                val text = sn.indexed.text.take(80)
                appendLine("  - box: ${sn.indexed.box}")
                appendLine("    type: ${sn.typeLabel}")
                appendLine("    score: ${sn.score}")
                if (text.isNotEmpty()) appendLine("    text: ${text.toYamlValue()}")
                if (selector.isNotEmpty()) appendLine("    selector: ${selector.toYamlValue()}")
            }
            appendLine()
        }

        if (lists.isNotEmpty()) {
            appendLine("lists:")
            for (list in lists) {
                val parentSelector = buildSelectorHint(list.parentClass, list.parentId)
                val label = if (parentSelector.isNotEmpty()) "${list.parentTag}${parentSelector}"
                else list.parentTag
                appendLine("  - parentTag: ${label}")
                appendLine("    itemTag: ${list.itemTag}")
                appendLine("    count: ${list.count}")
                if (list.samples.isNotEmpty()) {
                    appendLine("    samples:")
                    for (s in list.samples.take(5)) {
                        appendLine("      - box: ${s.box}")
                        appendLine("        tag: ${s.tag}")
                        if (s.text.isNotEmpty()) appendLine("        text: ${s.text.take(60).toYamlValue()}")
                    }
                }
            }
            appendLine()
        }

        if (tables.isNotEmpty()) {
            appendLine("tables:")
            for (table in tables.take(10)) {
                if (table.rows > 0) {
                    appendLine("  - box: ${table.box}")
                    appendLine("    rows: ${table.rows}")
                    appendLine("    cols: ${table.cols}")
                    if (table.headers.isNotEmpty()) {
                        appendLine("    headers:")
                        for (h in table.headers) {
                            appendLine("      - ${h.toYamlValue()}")
                        }
                    }
                }
            }
            appendLine()
        }

        appendLine("stats:")
        appendLine("  nodes: ${stats.nodes}")
        appendLine("  links: ${stats.links}")
        appendLine("  buttons: ${stats.buttons}")
        appendLine("  forms: ${stats.forms}")
        appendLine("  tables: ${stats.tables}")
        appendLine("  images: ${stats.images}")
        appendLine("  inputs: ${stats.inputs}")
    }

    // =========================================================================
    // YAML string escaping
    // =========================================================================

    /**
     * Escape a string for safe inclusion as a YAML value.
     *
     * Strings containing special YAML characters, leading/trailing whitespace,
     * newlines, or values that look like booleans/numbers/null are wrapped in
     * double quotes with appropriate escaping.
     */
    private fun String.toYamlValue(): String {
        val trimmed = this.trim()
        val needsQuoting = trimmed.isEmpty() ||
                trimmed.any { it in setOf(':', '#', '"', '\'', '&', '*', '!', '|', '>', '%', '@', '`', '{', '}', '[', ']', '.', ' ') } ||
                trimmed.first() != this.first() || trimmed.last() != this.last() ||
                trimmed.any { it == '\n' || it == '\r' }
        return if (needsQuoting) {
            val escaped = trimmed
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r\n", "\\n")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
            "\"$escaped\""
        } else {
            when (trimmed.lowercase()) {
                "true", "false", "yes", "no", "on", "off", "null", "~", "y", "n" -> "\"$trimmed\""
                else -> {
                    if (trimmed.toDoubleOrNull() != null || trimmed.toLongOrNull() != null) {
                        "\"$trimmed\""
                    } else {
                        trimmed
                    }
                }
            }
        }
    }
}
