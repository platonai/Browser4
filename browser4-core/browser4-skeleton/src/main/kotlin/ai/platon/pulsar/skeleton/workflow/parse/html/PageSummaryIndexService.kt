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
/**
 * Parsed bounding-box from a `vi` attribute.
 *
 * Supports three wire formats (auto-detected):
 * - **Base-36** (compact): comma-separated base-36 integers containing letters
 *   e.g. `"3g,cp,5k,1f"` → x=124, y=457, w=200, h=51
 * - **Compact decimal**: comma-separated decimal integers (no letters)
 *   e.g. `"0,0,1920,1080"`
 * - **Legacy decimal**: space-separated decimal numbers
 *   e.g. `"123.5 456.7 200.3 50.8"`
 */
data class ViBox(val x: Double, val y: Double, val w: Double, val h: Double) {
    companion object {
        /**
         * Parse a vi attribute string into a [ViBox].
         * Returns null when the string is blank or cannot be parsed.
         */
        fun parse(raw: String): ViBox? {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return null

            // Choose delimiter: comma (compact) or whitespace (legacy)
            val parts: List<String> = if (',' in trimmed) {
                trimmed.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            } else {
                trimmed.split("\\s+".toRegex()).filter { it.isNotEmpty() }
            }

            if (parts.size < 4) return null

            // Base-36 detection: any part contains a letter a–z (base-36 digits
            // for values ≥10). Pure-digit strings are always decimal to stay
            // compatible with existing comma-separated decimal fixtures.
            val isBase36 = parts.any { part ->
                part.any { c -> c in 'a'..'z' || c in 'A'..'Z' }
            }

            val nums = parts.take(4).map {
                if (isBase36) it.toIntOrNull(36)?.toDouble() else it.toDoubleOrNull()
            }
            if (nums.any { it == null }) return null

            return ViBox(nums[0]!!, nums[1]!!, nums[2]!!, nums[3]!!)
        }
    }
}

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

        // Phase 5: key node extraction (top 100 by score, deduplicated by type+text)
        // Deduplication avoids repetitive elements (e.g. 20 "Add to basket" buttons)
        // from crowding out diverse content. The first occurrence (highest score) is
        // kept; subsequent duplicates are dropped with a count tracked for display.
        val keyNodes = scoredNodes
            .filter { it.score > 0 }
            .sortedByDescending { it.score }
            .fold(mutableListOf<SummaryScoredNode>() to mutableMapOf<String, Int>()) { (acc, dupCounts), node ->
                val key = "${node.typeLabel}|${node.indexed.element.text().trim()}"
                if (key.isBlank()) {
                    acc.add(node)
                } else if (acc.none { "${it.typeLabel}|${it.indexed.element.text().trim()}" == key }) {
                    acc.add(node)
                } else {
                    dupCounts[key] = (dupCounts[key] ?: 1) + 1
                }
                acc to dupCounts
            }
            .let { (nodes, dupCounts) ->
                // Attach duplicate counts to the kept nodes for display
                nodes.map { node ->
                    val key = "${node.typeLabel}|${node.indexed.element.text().trim()}"
                    val dupCount = dupCounts[key]
                    if (dupCount != null && dupCount > 1) {
                        node.copy(duplicateCount = dupCount)
                    } else {
                        node
                    }
                }
            }
            .take(100)

        // Phase 6: list detection
        val lists = detectLists(indexedNodes)

        // Phase 6b: link group detection
        val linkGroups = detectLinkGroups(cleaned)

        // Phase 7: table summary
        val tables = summarizeTables(indexedNodes)

        // Stats
        val stats = computeStats(indexedNodes)

        // Build YAML
        val pageType = inferPageType(indexedNodes, pageUrl)
        return buildYamlSummary(
            pageUrl = pageUrl,
            title = title,
            pageType = pageType,
            landmarks = landmarks,
            keyNodes = keyNodes,
            lists = lists,
            linkGroups = linkGroups,
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
        /** Number of duplicates with the same (typeLabel, text) that were collapsed.
         * 1 means unique; >1 means this entry represents N identical elements. */
        val duplicateCount: Int = 1,
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

    /** A single extracted link within a sample item. */
    data class LinkInfo(
        val text: String,
        val href: String,
        val box: String,
    )

    /** One sample item from a link group. */
    data class LinkGroupItem(
        val box: String,
        val links: List<LinkInfo>,
        val hasImage: Boolean,
        val descendantCount: Int,
        val textLength: Int,
    )

    /** A detected link group — repeating visual card pattern containing links. */
    data class SummaryLinkGroup(
        val containerTag: String,
        val containerId: String,
        val containerClass: String,
        val containerSelector: String,
        val itemTag: String,
        val itemSelector: String,
        val count: Int,
        val columnCount: Int,
        val viewportWidth: Double,
        val viewportHeight: Double,
        val allHaveLinks: Boolean,
        val anyHaveImages: Boolean,
        val avgCardWidth: Double,
        val avgCardHeight: Double,
        val distinctTextCount: Int,
        val avgDescendants: Double,
        val samples: List<LinkGroupItem>,
        val score: Double,
    )

    // =========================================================================
    // Constants (link group detection)
    // =========================================================================

    private const val MIN_CARD_WIDTH = 80.0
    private const val MIN_CARD_HEIGHT = 30.0
    private const val MIN_LINK_GROUP_SIZE = 3
    private const val MIN_LINK_GROUP_SCORE = 20.0
    private const val WIDTH_TOLERANCE = 0.10   // 10%
    private const val HEIGHT_TOLERANCE = 0.15   // 15%
    private const val MAX_VIEWPORT_WIDTH_RATIO = 0.95
    private const val NAV_STRONG_MAX_HEIGHT = 36.0
    private const val NAV_STRONG_MAX_WIDTH = 120.0
    private const val NAV_MODERATE_PENALTY = 0.3
    private const val MAX_LINK_TEXT_LEN = 20.0
    private const val Y_SPACING_RATIO_THRESHOLD = 0.5
    private const val MAX_GAP_PX = 100.0
    private const val TOP_GROUPS = 5
    private const val MAX_SAMPLES = 3

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

    /**
     * Build the compact element reference format defined in Section 8:
     * `#closestId tag#id.class1.class2`
     */
    private fun buildElementRef(node: SummaryIndexedNode): String {
        val closestId = findClosestId(node.element)
        val idPart = if (closestId.isNotEmpty()) "#$closestId " else ""
        val ownId = node.id.takeIf { it.isNotBlank() }?.let { "#$it" } ?: ""
        val classPart = formatClassList(node.className)
        return "$idPart${node.tag}$ownId$classPart"
    }

    /** Find the id of the nearest ancestor element (up to [maxLevels] levels up). */
    private fun findClosestId(el: org.jsoup.nodes.Element, maxLevels: Int = 6): String {
        var current: org.jsoup.nodes.Element? = el.parent()
        var level = 0
        while (current != null && level < maxLevels) {
            val id = current.id()
            if (id.isNotBlank()) return id
            current = current.parent()
            level++
        }
        return ""
    }

    /** Format up to 2 CSS classes as `.class1.class2`, or empty string if none. */
    private fun formatClassList(className: String): String {
        if (className.isBlank()) return ""
        val classes = className.split("\\s+".toRegex()).take(2)
        return classes.joinToString("") { ".$it" }
    }

    /**
     * Truncate text to ≤5 words (Latin) or ≤5 characters (CJK).
     */
    private fun truncateSummaryText(text: String, maxWords: Int = 5): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return ""
        if (trimmed.any { isCJK(it) }) return trimmed.take(maxWords)
        val words = trimmed.split("\\s+".toRegex())
        return words.take(maxWords).joinToString(" ")
    }

    /** Check if a character is in a CJK Unicode range. */
    private fun isCJK(c: Char): Boolean {
        val cp = c.code
        return cp in 0x4E00..0x9FFF   // CJK Unified Ideographs
            || cp in 0x3400..0x4DBF   // CJK Unified Ideographs Extension A
            || cp in 0xF900..0xFAFF   // CJK Compatibility Ideographs
            || cp in 0x3040..0x309F   // Hiragana
            || cp in 0x30A0..0x30FF   // Katakana
            || cp in 0xAC00..0xD7AF   // Hangul Syllables
            || cp in 0x2E80..0x2EFF   // CJK Radicals Supplement
            || cp in 0x3000..0x303F   // CJK Symbols and Punctuation
            || cp in 0xFF00..0xFFEF   // Halfwidth and Fullwidth Forms
    }

    // =========================================================================
    // Page type inference
    // =========================================================================

    /** Heuristic page-type detection from DOM text, tags, and URL. */
    private fun inferPageType(nodes: List<SummaryIndexedNode>, pageUrl: String): String {
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
        // Use element.text() (all descendant text) rather than ownText() because
        // headlines often nest text inside child elements: <h1><a>headline</a></h1>.
        // ownText() would be empty for such headings, causing false negatives.
        val hasArticle = tags.contains("article") || nodes.any {
            it.tag in listOf("h1", "h2") && it.element.text().trim().length > 30
        }
        // Content richness: count content-bearing elements (headings, paragraphs)
        // with substantial text. A news portal with dozens of headlines should not
        // be classified as "Login / Auth" just because a login link exists.
        val contentNodeCount = nodes.count {
            it.tag in listOf("h1", "h2", "h3", "h4", "h5", "h6", "p") && it.element.text().trim().length > 10
        }
        val isContentRich = contentNodeCount >= 5
        val hasForm = tags.contains("form") && (text.contains("submit") || text.contains("提交"))
        val hasVideo = tags.contains("video") || text.contains("video") || text.contains("视频")

        // URL-based signals
        val urlLower = pageUrl.lowercase()
        val urlPath = try {
            java.net.URI(pageUrl).path.lowercase()
        } catch (_: Exception) {
            pageUrl.substringAfter("//").substringAfter("/").lowercase()
        }

        val isHomepage = urlPath.isBlank() || urlPath == "/" || urlPath == "/index.html" || urlPath == "/index.htm"
        val isProductDetailUrl = Regex("""/(dp|product|ip|item|gp/product)/""").containsMatchIn(urlPath)
        val isSearchUrl = urlPath.contains("search") ||
                Regex("""[?&](s|q|k|query|search|keyword)=""").containsMatchIn(urlLower)

        return when {
            // Strong content signals take priority over URL hints
            hasAddToCart -> "Product Detail"
            hasSearch && hasPrice -> "Search Results"
            hasArticle -> "Article / Content"
            // Only classify as Login/Auth when login signal is present AND the page
            // lacks article patterns AND is not content-rich. A news portal with a
            // small login link in the header has isContentRich=true from its headlines,
            // so it won't be misclassified here.
            hasLogin && !hasArticle && !isContentRich -> "Login / Auth"
            hasForm && !hasArticle -> "Form Page"
            hasVideo -> "Media Page"

            // URL-backed signals (refine or override content-ambiguous cases)
            isProductDetailUrl && hasPrice -> "Product Detail"
            isSearchUrl -> "Search Results"

            // Homepage detection (only when no strong content signal above)
            isHomepage && hasPrice -> "Product Listing"
            isHomepage -> "Homepage"

            // Remaining content heuristics
            isContentRich && isHomepage -> "News / Content"
            isContentRich -> "Article / Content"
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
    // Link group detection (Phase 6b)
    // =========================================================================

    /** Internal candidate collected from a DOM element with a bounding box and links. */
    private data class CardCandidate(
        val element: org.jsoup.nodes.Element,
        val x: Double, val y: Double, val w: Double, val h: Double,
        val area: Double,
        val hasLinks: Boolean,
        val hasImages: Boolean,
        val linkCount: Int,
        val textLength: Int,
    )

    /** Internal cluster produced by visual grouping phases. */
    private data class VisualCluster(
        val items: List<CardCandidate>,
        val meanWidth: Double,
        val meanHeight: Double,
        val xPositions: List<Double>,
        val yRegularity: Double,
        val mergedFrom: Int,
    )

    /**
     * Detect link groups — coherent collections of item cards where each card
     * contains one or more links.
     *
     * Algorithm: cluster by visual geometry (bounding box), then walk up the
     * DOM to find the nearest container element.
     */
    fun detectLinkGroups(document: FeaturedDocument): List<SummaryLinkGroup> {
        // Phase 0: extract viewport dimensions
        val (viewportW, viewportH) = extractViewport(document)
        // Phase 1: collect card candidates
        val candidates = collectCardCandidates(document, viewportW, viewportH)
        if (candidates.size < MIN_LINK_GROUP_SIZE) return emptyList()

        // Phase 2: cluster by visual similarity
        val widthGroups = clusterByWidth(candidates)
        val whGroups = widthGroups.flatMap { clusterByHeight(it) }
        val xClusters = whGroups.flatMap { clusterByX(it, viewportW) }

        // Phase 3: y-spacing regularity check
        val regularClusters = xClusters.filter { checkYRegularity(it) }
        if (regularClusters.isEmpty()) return emptyList()

        // Phase 4: merge columns into grids
        val visualGroups = mergeColumns(regularClusters)
        if (visualGroups.isEmpty()) return emptyList()

        // Phase 5: navigation menu suppression (strong signals only — reject)
        val nonNavGroups = visualGroups.filter { cluster ->
            val penalty = applyNavSuppression(cluster)
            penalty > 0.0
        }

        // Phase 6: find nearest container for each group
        data class GroupWithContainer(
            val cluster: VisualCluster,
            val container: org.jsoup.nodes.Element,
            val itemSelector: String,
        )

        val withContainers = nonNavGroups.mapNotNull { cluster ->
            val container = findContainer(cluster.items.map { it.element }, candidates)
            if (container != null) {
                val itemSelector = buildItemSelector(cluster.items.map { it.element })
                GroupWithContainer(cluster, container, itemSelector)
            } else {
                null
            }
        }

        // Phase 7: resolve overlapping groups + compute final scores
        val asTriples = withContainers.map { Triple(it.cluster, it.container, it.itemSelector) }
        val resolved = resolveOverlaps(asTriples, viewportW, viewportH)
        if (resolved.isEmpty()) return emptyList()

        // Phase 8: extract link data
        return resolved.map { (cluster, container, itemSelector) ->
            val items = cluster.items
            val allHaveLinks = items.all { it.hasLinks }
            val anyHaveImages = items.any { it.hasImages }
            val distinctTexts = items.map { it.element.text().trim() }
                .filter { it.isNotBlank() }.distinct()
            val avgDesc = items.map { it.element.select("*").size.toDouble() }.average()
            val avgW = items.map { it.w }.average()
            val avgH = items.map { it.h }.average()
            val samples = extractLinkData(items.take(MAX_SAMPLES).map { it.element })

            // Compute score
            val size = items.size.toDouble()
            var score = size * 10.0
            if (allHaveLinks) score += size * 5.0
            if (anyHaveImages) score += size * 3.0
            score += minOf(distinctTexts.size, 10) * 2.0
            score += minOf(avgDesc, 6.0) * 2.0
            val navPenalty = computeNavPenaltyForContainer(container, items)
            score *= navPenalty

            val containerTag = container.tagName().lowercase()
            val containerId = container.id()
            val containerClass = container.className()
            val containerSelector = buildSelectorHint(containerClass, containerId)
                .ifEmpty { containerTag }

            SummaryLinkGroup(
                containerTag = containerTag,
                containerId = containerId,
                containerClass = containerClass,
                containerSelector = containerSelector,
                itemTag = items.first().element.tagName().lowercase(),
                itemSelector = itemSelector,
                count = items.size,
                columnCount = cluster.mergedFrom,
                viewportWidth = viewportW,
                viewportHeight = viewportH,
                allHaveLinks = allHaveLinks,
                anyHaveImages = anyHaveImages,
                avgCardWidth = avgW,
                avgCardHeight = avgH,
                distinctTextCount = distinctTexts.size,
                avgDescendants = avgDesc,
                samples = samples,
                score = score,
            )
        }.filter { it.score >= MIN_LINK_GROUP_SCORE }
            .sortedByDescending { it.score }
            .take(TOP_GROUPS)
    }

    // =========================================================================
    // Phase 0: viewport extraction
    // =========================================================================

    private fun extractViewport(document: FeaturedDocument): Pair<Double, Double> {
        // Primary source: PulsarMetaInformation hidden input
        val meta = document.select("#PulsarMetaInformation").first()
        if (meta != null) {
            val viewport = meta.attr("view-port").trim()
            if (viewport.isNotBlank()) {
                val parts = viewport.split("x", "×", "X").map { it.trim() }
                if (parts.size == 2) {
                    val w = parts[0].toDoubleOrNull()
                    val h = parts[1].toDoubleOrNull()
                    if (w != null && h != null && w > 0 && h > 0) return w to h
                }
            }
        }

        // Fallback: <html> element's vi attribute
        val htmlEl = document.select("html").first()
        if (htmlEl != null) {
            val box = ViBox.parse(htmlEl.attr("vi"))
            if (box != null && box.w > 0 && box.h > 0) return box.w to box.h
        }

        // Default
        return 1920.0 to 1080.0
    }

    // =========================================================================
    // Phase 1: collect card candidates
    // =========================================================================

    private fun collectCardCandidates(
        document: FeaturedDocument,
        viewportW: Double,
        viewportH: Double,
    ): List<CardCandidate> {
        val candidates = mutableListOf<CardCandidate>()
        val maxW = viewportW * MAX_VIEWPORT_WIDTH_RATIO

        // Tags that are never card containers: controls, forms, scripts, styles,
        // embedded content, and other non-semantic structural elements.
        val excludedTags = setOf(
            // Controls
            "input", "button", "select", "textarea", "label", "fieldset",
            "legend", "datalist", "output", "option", "optgroup",
            // Forms
            "form",
            // Scripts & styles
            "script", "noscript", "style",
            // Embedded / media
            "iframe", "embed", "object", "canvas", "video", "audio",
            "svg", "math", "picture", "source", "track", "map", "area",
            // Structural / metadata
            "html", "head", "body", "meta", "link", "base", "title",
            "br", "hr", "wbr", "template", "slot",
        )

        for (el in document.select("*")) {
            val tag = el.tagName().lowercase()
            if (tag in excludedTags) continue

            val vi = el.attr("vi").trim()
            if (vi.isBlank()) continue
            if (vi.contains("_h=1")) continue

            val box = ViBox.parse(vi) ?: continue
            if (box.w <= 0 || box.h <= 0) continue

            // Size filters
            if (box.w < MIN_CARD_WIDTH || box.h < MIN_CARD_HEIGHT) continue
            if (box.w > maxW) continue

            // Must contain at least one link
            val links = el.select("a[href]")
            if (links.isEmpty()) continue

            val hasImages = el.select("img").isNotEmpty()

            candidates.add(
                CardCandidate(
                    element = el,
                    x = box.x,
                    y = box.y,
                    w = box.w,
                    h = box.h,
                    area = box.w * box.h,
                    hasLinks = true,
                    hasImages = hasImages,
                    linkCount = links.size,
                    textLength = el.text().length,
                )
            )
        }

        return candidates
    }

    // =========================================================================
    // Phase 2: visual clustering
    // =========================================================================

    /** Phase 2a: cluster by width (±WIDTH_TOLERANCE relative). */
    private fun clusterByWidth(candidates: List<CardCandidate>): List<List<CardCandidate>> {
        if (candidates.isEmpty()) return emptyList()
        val sorted = candidates.sortedBy { it.w }
        val groups = mutableListOf<MutableList<CardCandidate>>()
        var current = mutableListOf(sorted.first())

        for (c in sorted.drop(1)) {
            val ref = current.first().w
            if (ref > 0 && Math.abs(c.w - ref) / maxOf(c.w, ref) <= WIDTH_TOLERANCE) {
                current.add(c)
            } else {
                groups.add(current)
                current = mutableListOf(c)
            }
        }
        groups.add(current)
        return groups.filter { it.size >= MIN_LINK_GROUP_SIZE }
    }

    /** Phase 2b: within each width-group, cluster by height (±HEIGHT_TOLERANCE). */
    private fun clusterByHeight(candidates: List<CardCandidate>): List<List<CardCandidate>> {
        if (candidates.size < MIN_LINK_GROUP_SIZE) return emptyList()
        val sorted = candidates.sortedBy { it.h }
        val groups = mutableListOf<MutableList<CardCandidate>>()
        var current = mutableListOf(sorted.first())

        for (c in sorted.drop(1)) {
            val ref = current.first().h
            if (ref > 0 && Math.abs(c.h - ref) / maxOf(c.h, ref) <= HEIGHT_TOLERANCE) {
                current.add(c)
            } else {
                groups.add(current)
                current = mutableListOf(c)
            }
        }
        groups.add(current)
        return groups.filter { it.size >= MIN_LINK_GROUP_SIZE }
    }

    /** Phase 2c: within each (w,h) group, cluster by x-position.
     *  Minimum of 1 item per x-cluster — columns with few items are merged in Phase 4. */
    private fun clusterByX(candidates: List<CardCandidate>, viewportW: Double): List<List<CardCandidate>> {
        if (candidates.isEmpty()) return emptyList()
        val epsX = viewportW * 0.02  // 2% of viewport width
        val sorted = candidates.sortedBy { it.x }
        val groups = mutableListOf<MutableList<CardCandidate>>()
        var current = mutableListOf(sorted.first())

        for (c in sorted.drop(1)) {
            if (Math.abs(c.x - current.first().x) <= epsX) {
                current.add(c)
            } else {
                groups.add(current)
                current = mutableListOf(c)
            }
        }
        groups.add(current)
        return groups
    }

    // =========================================================================
    // Phase 3: Y-spacing regularity check
    // =========================================================================

    private fun checkYRegularity(cluster: List<CardCandidate>): Boolean {
        // Single-item clusters pass through — they may be merged in Phase 4
        if (cluster.size < 2) return true

        val sorted = cluster.sortedBy { it.y }
        val gaps = mutableListOf<Double>()
        for (i in 0 until sorted.size - 1) {
            val gap = sorted[i + 1].y - (sorted[i].y + sorted[i].h)
            gaps.add(gap)
        }

        if (gaps.isEmpty()) return cluster.size >= MIN_LINK_GROUP_SIZE

        // Detect and remove outlier gaps (gaps between separate sections).
        // A gap is an outlier if it's > 3× the median of positive gaps.
        val positiveGaps = gaps.filter { it > 0 }
        if (positiveGaps.isEmpty()) return true  // all overlapping

        val sortedGaps = positiveGaps.sorted()
        val medianGap = if (sortedGaps.size % 2 == 0)
            (sortedGaps[sortedGaps.size / 2] + sortedGaps[sortedGaps.size / 2 - 1]) / 2.0
        else sortedGaps[sortedGaps.size / 2]

        // Split at gaps > 3× median (section boundaries). Check the largest segment.
        val segments = mutableListOf<MutableList<Double>>()
        var current = mutableListOf<Double>()
        for (gap in gaps) {
            if (gap > 0 && medianGap > 0 && gap > 3.0 * medianGap && current.isNotEmpty()) {
                segments.add(current)
                current = mutableListOf()
            } else {
                current.add(gap)
            }
        }
        if (current.isNotEmpty()) segments.add(current)

        // A cluster is valid if its largest segment passes the regularity check
        val bestSegment = segments.maxByOrNull { it.size } ?: return false
        // For 1-2 gaps (2-3 items), skip outlier splitting and check directly
        if (bestSegment.size < 2) {
            // Small cluster: no meaningful outlier detection, check all gaps
            if (gaps.size == 1) {
                // Two items, one gap: check just this gap
                val gap = gaps[0]
                val medianH = sorted.map { it.h }.sorted().let { hs ->
                    if (hs.size % 2 == 0) (hs[hs.size / 2] + hs[hs.size / 2 - 1]) / 2.0
                    else hs[hs.size / 2]
                }
                return gap >= 0 && gap < 2.0 * medianH
            }
            if (gaps.size >= 2) return true  // pass through for merging
            return false
        }

        val meanGap = bestSegment.average()
        if (meanGap <= 0) return true

        val variance = bestSegment.map { (it - meanGap) * (it - meanGap) }.average()
        val stddev = Math.sqrt(variance)
        val ratio = if (meanGap > 0) stddev / meanGap else 1.0

        val isRegular = ratio <= Y_SPACING_RATIO_THRESHOLD

        val medianH = sorted.map { it.h }.sorted().let {
            if (it.size % 2 == 0) (it[it.size / 2] + it[it.size / 2 - 1]) / 2.0
            else it[it.size / 2]
        }
        val isClose = meanGap < 2.0 * medianH
        val allSmallGaps = bestSegment.all { it in 0.0..MAX_GAP_PX }

        return isRegular && (isClose || allSmallGaps)
    }

    // =========================================================================
    // Phase 4: merge columns into grids
    // =========================================================================

    private fun mergeColumns(clusters: List<List<CardCandidate>>): List<VisualCluster> {
        if (clusters.isEmpty()) return emptyList()

        // Assign each cluster a (widthGroup, heightGroup) signature
        data class ClusterWithSig(
            val items: List<CardCandidate>,
            val meanW: Double,
            val meanH: Double,
            val xPos: Double,
            val meanGap: Double,
        )

        val sigs = clusters.map { items ->
            ClusterWithSig(
                items = items,
                meanW = items.map { it.w }.average(),
                meanH = items.map { it.h }.average(),
                xPos = items.map { it.x }.average(),
                meanGap = computeMeanGap(items),
            )
        }

        val merged = mutableListOf<VisualCluster>()
        val used = mutableSetOf<Int>()

        for (i in sigs.indices) {
            if (i in used) continue
            val a = sigs[i]
            val sameGrid = mutableListOf(i)

            for (j in (i + 1) until sigs.size) {
                if (j in used) continue
                val b = sigs[j]

                // Same visual signature?
                val sameW = Math.abs(a.meanW - b.meanW) / maxOf(a.meanW, b.meanW, 1.0) <= WIDTH_TOLERANCE
                val sameH = Math.abs(a.meanH - b.meanH) / maxOf(a.meanH, b.meanH, 1.0) <= HEIGHT_TOLERANCE
                val diffX = Math.abs(a.xPos - b.xPos) > 1.0
                // Skip gap comparison if either cluster has <2 items or gaps are
                // non-positive (overlapping items, typical of wrapper layouts)
                val similarGap = if (a.items.size < 2 || b.items.size < 2) true
                else if (a.meanGap <= 0 && b.meanGap <= 0) true  // both overlapping
                else if (a.meanGap <= 0 || b.meanGap <= 0) false // one overlapping, one not
                else maxOf(a.meanGap, b.meanGap) / maxOf(minOf(a.meanGap, b.meanGap), 1.0) <= 2.0

                if (sameW && sameH && diffX && similarGap && yRangesOverlap(a.items, b.items)) {
                    sameGrid.add(j)
                }
            }

            used.addAll(sameGrid)
            val allItems = sameGrid.flatMap { sigs[it].items }
            val xPositions = sameGrid.map { sigs[it].xPos }.distinct()
            val yReg = computeYRegularity(allItems)

            merged.add(
                VisualCluster(
                    items = allItems,
                    meanWidth = allItems.map { it.w }.average(),
                    meanHeight = allItems.map { it.h }.average(),
                    xPositions = xPositions,
                    yRegularity = yReg,
                    mergedFrom = sameGrid.size,
                )
            )
        }

        return merged.filter { it.items.size >= MIN_LINK_GROUP_SIZE }
    }

    private fun computeMeanGap(items: List<CardCandidate>): Double {
        if (items.size < 2) return 0.0
        val sorted = items.sortedBy { it.y }
        val gaps = (0 until sorted.size - 1).map {
            sorted[it + 1].y - (sorted[it].y + sorted[it].h)
        }
        return gaps.filter { it > 0 }.average().takeIf { !it.isNaN() } ?: 0.0
    }

    private fun yRangesOverlap(a: List<CardCandidate>, b: List<CardCandidate>): Boolean {
        val aYs = a.map { it.y }.sorted()
        val bYs = b.map { it.y }.sorted()
        if (aYs.isEmpty() || bYs.isEmpty()) return false

        // Count how many items in A share a y-row with an item in B
        val epsY = 20.0  // px tolerance for "same row"
        val aInBRow = a.count { ca -> b.any { cb -> Math.abs(ca.y - cb.y) <= epsY } }
        return aInBRow.toDouble() / a.size >= 0.5
    }

    private fun computeYRegularity(items: List<CardCandidate>): Double {
        if (items.size < 2) return 1.0
        val sorted = items.sortedBy { it.y }
        val gaps = (0 until sorted.size - 1).map {
            sorted[it + 1].y - (sorted[it].y + sorted[it].h)
        }
        val meanGap = gaps.average()
        if (meanGap <= 0) return 0.0
        val variance = gaps.map { (it - meanGap) * (it - meanGap) }.average()
        return Math.sqrt(variance) / meanGap
    }

    // =========================================================================
    // Phase 5: navigation menu suppression
    // =========================================================================

    /**
     * Returns the penalty multiplier for a visual cluster based on nav signals.
     * Returns 0.0 if the group should be rejected entirely (strong nav signal).
     * Returns 1.0 if no nav signal.
     * Returns NAV_MODERATE_PENALTY for moderate signals.
     */
    private fun applyNavSuppression(cluster: VisualCluster): Double {
        val items = cluster.items
        val avgH = items.map { it.h }.average()
        val avgW = items.map { it.w }.average()
        val hasImages = items.any { it.hasImages }

        // Strong signals → reject
        if (!hasImages && avgH < NAV_STRONG_MAX_HEIGHT) return 0.0
        if (!hasImages && avgW < NAV_STRONG_MAX_WIDTH) return 0.0

        // Moderate signals are checked after container is found in Phase 6
        return 1.0
    }

    /** Moderate nav check that requires the container element. Called in Phase 7 scoring. */
    private fun computeNavPenaltyForContainer(
        container: org.jsoup.nodes.Element,
        items: List<CardCandidate>,
    ): Double {
        val containerTag = container.tagName().lowercase()
        val containerRole = container.attr("role").lowercase()

        // Direct semantic signal
        if (containerTag == "nav" || containerRole == "navigation") return NAV_MODERATE_PENALTY

        val hasImages = items.any { it.hasImages }
        if (hasImages) return 1.0

        // Check link text length
        val linkTexts = items.mapNotNull { c ->
            c.element.select("a[href]").first()?.text()?.trim()
        }.filter { it.isNotBlank() }
        val avgLinkTextLen = if (linkTexts.isNotEmpty()) linkTexts.map { it.length }.average() else 0.0

        // Single column + short link text + no images → moderate
        if (avgLinkTextLen < MAX_LINK_TEXT_LEN) return NAV_MODERATE_PENALTY

        return 1.0
    }

    // =========================================================================
    // Phase 6: find nearest container
    // =========================================================================

    private fun findContainer(
        items: List<org.jsoup.nodes.Element>,
        allCandidates: List<CardCandidate>,
    ): org.jsoup.nodes.Element? {
        if (items.isEmpty()) return null

        // Build parent chains and find LCA
        fun parentChain(el: org.jsoup.nodes.Element): List<org.jsoup.nodes.Element> {
            val chain = mutableListOf<org.jsoup.nodes.Element>()
            var cur: org.jsoup.nodes.Element? = el
            while (cur != null) {
                chain.add(cur)
                cur = cur.parent()
            }
            return chain
        }

        val chains = items.map { parentChain(it).toSet() }
        // LCA = deepest element present in all chains
        val common = chains.reduce { a, b -> a.intersect(b) }
        val lca = common.maxByOrNull { depthOf(it) } ?: return null

        // Tighten: if LCA is too generic, walk down to the tightest container
        var container = lca
        val tooGeneric = container.tagName().lowercase() in setOf("html", "body") ||
                allCandidates.count { isDescendantOf(it.element, container) } > allCandidates.size * 0.8

        if (tooGeneric) {
            // Walk down: find the child whose subtree contains the most items
            var tightened = true
            while (tightened) {
                tightened = false
                val children = container.children()
                val bestChild = children.maxByOrNull { child ->
                    items.count { item -> isDescendantOf(item, child) }
                }
                if (bestChild != null) {
                    val covered = items.count { item -> isDescendantOf(item, bestChild) }
                    if (covered >= items.size * 0.8) {
                        container = bestChild
                        tightened = true
                    }
                }
            }
        }

        return container
    }

    private fun depthOf(el: org.jsoup.nodes.Element): Int {
        var depth = 0
        var cur: org.jsoup.nodes.Element? = el.parent()
        while (cur != null) {
            depth++
            cur = cur.parent()
        }
        return depth
    }

    private fun isDescendantOf(
        descendant: org.jsoup.nodes.Element,
        ancestor: org.jsoup.nodes.Element,
    ): Boolean {
        var cur: org.jsoup.nodes.Element? = descendant
        while (cur != null) {
            if (cur === ancestor) return true
            cur = cur.parent()
        }
        return false
    }

    private fun buildItemSelector(items: List<org.jsoup.nodes.Element>): String {
        if (items.isEmpty()) return ""
        val sigs = items.map { item ->
            val tag = item.tagName().lowercase()
            val cls = item.className().trim()
            if (cls.isNotBlank()) {
                val classes = cls.split("\\s+".toRegex()).take(2).sorted().joinToString(".") { it }
                "$tag.$classes"
            } else tag
        }
        val mostCommon = sigs.groupBy { it }.maxByOrNull { it.value.size }
        return if (mostCommon != null && mostCommon.value.size >= items.size * 0.8) {
            if (mostCommon.key.contains(".")) ".${mostCommon.key.substringAfter(".")}"
            else mostCommon.key
        } else {
            items.first().tagName().lowercase()
        }
    }

    // =========================================================================
    // Phase 7: resolve overlapping groups
    // =========================================================================

    private fun resolveOverlaps(
        groups: List<Triple<VisualCluster, org.jsoup.nodes.Element, String>>,
        viewportW: Double,
        viewportH: Double,
    ): List<Triple<VisualCluster, org.jsoup.nodes.Element, String>> {
        if (groups.isEmpty()) return emptyList()

        // Sort by container depth descending (deepest first)
        val sorted = groups.sortedByDescending { (_, container, _) -> depthOf(container) }

        val claimed = mutableSetOf<org.jsoup.nodes.Element>()
        val kept = mutableListOf<Triple<VisualCluster, org.jsoup.nodes.Element, String>>()

        for ((cluster, container, itemSelector) in sorted) {
            val itemElements = cluster.items.map { it.element }.toSet()
            val uncovered = itemElements - claimed
            if (uncovered.size >= MIN_LINK_GROUP_SIZE && uncovered.size >= itemElements.size * 0.5) {
                kept.add(Triple(cluster, container, itemSelector))
                claimed.addAll(uncovered)
            }
        }

        return kept
    }

    // =========================================================================
    // Phase 8: extract link data from samples
    // =========================================================================

    private fun extractLinkData(items: List<org.jsoup.nodes.Element>): List<LinkGroupItem> {
        return items.map { item ->
            val links = item.select("a[href]").mapNotNull { a ->
                val href = a.attr("href").trim()
                val text = a.text().trim()
                if (href.isNotBlank() && text.isNotBlank()) {
                    LinkInfo(
                        text = text.take(100),
                        href = href.take(200),
                        box = a.attr("vi").trim(),
                    )
                } else null
            }
            LinkGroupItem(
                box = item.attr("vi").trim(),
                links = links,
                hasImage = item.select("img").isNotEmpty(),
                descendantCount = item.select("*").size,
                textLength = item.text().length,
            )
        }
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
        linkGroups: List<SummaryLinkGroup>,
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
                val ref = buildElementRef(sn.indexed)
                val selector = buildSelectorHint(sn.indexed.className, sn.indexed.id)
                val text = truncateSummaryText(sn.indexed.element.text().trim())
                appendLine("  - ref: ${ref.toYamlValue()}")
                appendLine("    box: ${sn.indexed.box}")
                appendLine("    type: ${sn.typeLabel}")
                appendLine("    score: ${sn.score}")
                if (sn.duplicateCount > 1) appendLine("    repeats: ${sn.duplicateCount}")
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

        if (linkGroups.isNotEmpty()) {
            appendLine("linkGroups:")
            for (lg in linkGroups) {
                val containerLabel = lg.containerTag + lg.containerSelector
                appendLine("  - container: ${containerLabel.toYamlValue()}")
                if (lg.containerSelector.isNotEmpty() && lg.containerSelector != lg.containerTag) {
                    appendLine("    selector: ${lg.containerSelector.toYamlValue()}")
                }
                appendLine("    itemTag: ${lg.itemTag}")
                appendLine("    itemSelector: ${lg.itemSelector.toYamlValue()}")
                appendLine("    count: ${lg.count}")
                appendLine("    columnCount: ${lg.columnCount}")
                appendLine("    viewportWidth: ${lg.viewportWidth}")
                appendLine("    viewportHeight: ${lg.viewportHeight}")
                appendLine("    allHaveLinks: ${lg.allHaveLinks}")
                appendLine("    anyHaveImages: ${lg.anyHaveImages}")
                appendLine("    avgCardWidth: ${lg.avgCardWidth}")
                appendLine("    avgCardHeight: ${lg.avgCardHeight}")
                appendLine("    distinctTextCount: ${lg.distinctTextCount}")
                appendLine("    avgDescendants: ${lg.avgDescendants}")
                if (lg.samples.isNotEmpty()) {
                    appendLine("    samples:")
                    for (sample in lg.samples) {
                        appendLine("      - box: ${sample.box}")
                        if (sample.links.isNotEmpty()) {
                            appendLine("        links:")
                            for (link in sample.links) {
                                appendLine("          - text: ${link.text.toYamlValue()}")
                                appendLine("            href: ${link.href.toYamlValue()}")
                                appendLine("            box: ${link.box}")
                            }
                        }
                        appendLine("        hasImage: ${sample.hasImage}")
                    }
                }
                appendLine("    score: ${lg.score}")
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
