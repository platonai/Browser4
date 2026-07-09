@file:Suppress("unused", "MemberVisibilityCanBePrivate")

package ai.platon.pulsar.rest.mcp.controller

import ai.platon.pulsar.common.serialize.json.pulsarObjectMapper
import ai.platon.pulsar.dom.FeaturedDocument
import ai.platon.pulsar.skeleton.workflow.parse.html.PageSummaryIndexService
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode

// ---------------------------------------------------------------------------
// Shared utility helpers
// ---------------------------------------------------------------------------

internal fun Any?.toBooleanValue(): Boolean? = when (this) {
    is Boolean -> this
    is String -> this.toBooleanStrictOrNull()
    else -> null
}

/**
 * Parse pagination options from tool arguments and, when active, paginate
 * [text] by lines.  Returns a pair of (paginatedContent, paginationMeta).
 * When pagination is disabled (--all, no --page-size, or text fits), returns
 * the full text with a null meta.
 */
internal fun paginateIfRequested(
    text: String,
    args: Map<String, Any?>
): Pair<String, PaginationMeta?> {
    val showAll = args["all"].toBooleanValue() ?: false
    val pageSize = (args["page-size"] as? Number)?.toInt() ?: 0
    if (showAll || pageSize <= 0) return Pair(text, null)

    val page = (args["page"] as? Number)?.toInt() ?: 1
    val effectivePage = if (page < 1) 1 else page

    val lines = text.lines()
    val totalLines = lines.size
    if (totalLines <= pageSize) return Pair(text, null)

    val totalPages = (totalLines + pageSize - 1) / pageSize
    val currentPage = effectivePage.coerceAtMost(totalPages)
    val startLine = (currentPage - 1) * pageSize
    val endLine = (startLine + pageSize).coerceAtMost(totalLines)

    val pageContent = lines.subList(startLine, endLine).joinToString("\n")
    val meta = PaginationMeta(
        page = currentPage,
        totalPages = totalPages,
        totalLines = totalLines,
        pageSize = pageSize,
        truncated = true
    )
    return Pair(pageContent, meta)
}

/**
 * Returns true if the given value is an element reference pattern
 * (e.g. "e5", "backend:15") that should be rejected for static
 * HTML snapshot queries.
 */
internal fun isElementReference(value: String): Boolean {
    val trimmed = value.trim()
    return (trimmed.startsWith('e') && trimmed.length > 1
            && trimmed.substring(1).all { it.isDigit() })
            || trimmed.startsWith("backend:")
}

// =========================================================================
// html_snapshot_inspect — core algorithm
// =========================================================================

/**
 * Run the visual geometry first link group detection algorithm
 * ([PageSummaryIndexService.detectLinkGroups]) and extract the best
 * repeating-pattern selector.
 */
internal fun runVisualDetection(
    document: FeaturedDocument
): Pair<String?, List<PageSummaryIndexService.SummaryLinkGroup>> {
    val linkGroups = PageSummaryIndexService.detectLinkGroups(document)
    val bestSelector = linkGroups.maxByOrNull { it.score }?.itemSelector
    return Pair(bestSelector, linkGroups)
}

/**
 * Auto-discovers the best CSS selector for repeating content patterns on a page.
 */
internal fun autoDiscoverRepeatingSelector(document: FeaturedDocument): String? {
    val structuralTags = setOf("html", "head", "body", "script", "style", "meta", "link", "noscript")
    val structuralBare = setOf("div", "span")

    var bestSelector: String? = null
    var bestScore = 0.0

    for (parent in document.select("*")) {
        val parentTag = parent.tagName().lowercase()
        if (parentTag in structuralTags && parentTag != "body") continue

        val children = parent.children()
        if (children.size < 2) continue

        val groups = mutableMapOf<String, MutableList<org.jsoup.nodes.Element>>()
        for (child in children) {
            val tag = child.tagName().lowercase()
            if (tag in structuralTags) continue
            val cls = child.className().trim()
            val sig = if (cls.isNotBlank()) {
                val classes = cls.split("\\s+".toRegex()).take(2).joinToString(".") { it }
                "$tag.$classes"
            } else {
                tag
            }
            groups.getOrPut(sig) { mutableListOf() }.add(child)
        }

        for ((sig, members) in groups) {
            if (members.size < 2) continue

            val hasClasses = sig.contains(".")
            val distinctText = members.map { it.text().trim() }.filter { it.isNotBlank() }.distinct().size
            val avgDesc = members.map { it.select("*").size.toDouble() }.average()
            val isStructuralDiv = !hasClasses && sig in structuralBare

            var score = members.size.toDouble()
            if (hasClasses) score *= 2.0

            score *= when {
                distinctText >= 5 -> 1.8
                distinctText >= 3 -> 1.4
                distinctText >= 2 -> 1.2
                else -> 1.0
            }

            score *= when {
                avgDesc >= 15 -> 2.0
                avgDesc >= 8 -> 1.6
                avgDesc >= 3 -> 1.2
                else -> 1.0
            }

            val membersWithImages = members.count { it.select("img").isNotEmpty() }
            val imageRatio = membersWithImages.toDouble() / members.size
            if (imageRatio >= 0.5) score *= 1.4

            val distinctChildTags = members.flatMap { member ->
                member.children().map { it.tagName().lowercase() }
            }.distinct().size
            score *= when {
                distinctChildTags >= 6 -> 1.5
                distinctChildTags >= 4 -> 1.3
                distinctChildTags >= 2 -> 1.1
                else -> 1.0
            }

            val avgTextLength = members.map { it.text().trim().length.toDouble() }.average()
            if (imageRatio < 0.3 && avgTextLength < 20.0) score *= 0.6

            if (isStructuralDiv) score *= 0.5

            if (score > bestScore) {
                bestScore = score
                bestSelector = if (hasClasses) ".${sig.substringAfter(".")}" else sig
            }
        }
    }

    return bestSelector
}

/**
 * Analyse a [document] and produce selector suggestions for recurring patterns.
 */
internal fun inspectDocument(
    document: FeaturedDocument,
    selector: String,
    maxMatches: Int,
    maxDepth: Int,
): String {
    val (visualBestSelector, visualLinkGroups) = runVisualDetection(document)

    var effectiveSelector = selector
    var autoDiscovered = false
    var speculativeSuggestion: String? = null
    var speculativeMatchCount: Int? = null
    val initialMatchCount = document.select(selector).size
    if (initialMatchCount <= 1) {
        val containerElement = if (initialMatchCount == 1 && selector != ":root") {
            document.selectFirst(selector)
        } else null

        if (containerElement != null) {
            val innerHtml = containerElement.html()
            if (innerHtml.isNotBlank()) {
                val subDoc = FeaturedDocument(org.jsoup.Jsoup.parse(innerHtml))
                val discovered = autoDiscoverRepeatingSelector(subDoc)
                if (discovered != null) {
                    effectiveSelector = "$selector $discovered"
                    autoDiscovered = true
                }
            }
        }

        if (!autoDiscovered) {
            if (visualBestSelector != null) {
                effectiveSelector = visualBestSelector
                autoDiscovered = true
            } else {
                val discovered = autoDiscoverRepeatingSelector(document)
                if (discovered != null) {
                    effectiveSelector = discovered
                    autoDiscovered = true
                }
            }
        }
    } else if (visualBestSelector != null && visualBestSelector != selector) {
        speculativeSuggestion = visualBestSelector
        speculativeMatchCount = document.select(visualBestSelector).size
    }

    val matches = document.select(effectiveSelector).take(maxMatches)
    val matchCount = document.select(effectiveSelector).size

    val interactiveSelector = "a[href], button, input:not([type=hidden]), select, textarea, " +
            "details, summary, " +
            "[role=button], [role=link], [role=checkbox], [role=radio], " +
            "[role=tab], [role=menuitem], [role=switch], [role=combobox], " +
            "[role=searchbox], [role=textbox], [role=slider], [role=spinbutton], " +
            "[role=option], [role=treeitem], " +
            "[tabindex]:not([tabindex=\"-1\"]), [contenteditable=true], " +
            "[onclick], [onkeydown], [onsubmit]"
    val elementWeightMap: Map<org.jsoup.nodes.Element, Int> = try {
        val allInteractive = document.select(interactiveSelector)
        computeInteractiveWeights(allInteractive).associate { (el, weight, _) -> el to weight }
    } catch (e: Exception) {
        emptyMap()
    }

    if (matches.isEmpty()) {
        return pulsarObjectMapper().createObjectNode().apply {
            put("matchCount", 0)
            put("selector", effectiveSelector)
            if (autoDiscovered) {
                put("autoDiscovered", true)
                put("originalSelector", selector)
            }
            if (speculativeSuggestion != null) {
                put("speculativeSuggestion", speculativeSuggestion)
                speculativeMatchCount?.let { put("speculativeMatchCount", it) }
            }
            putArray("suggestions")
            if (visualLinkGroups.isNotEmpty()) {
                set<ArrayNode>("linkGroups", linkGroupsToJson(visualLinkGroups))
            }
        }.toString()
    }

    // Build sample structures for the first 3 matches
    val samples = pulsarObjectMapper().createArrayNode()
    for (m in matches.take(3)) {
        val sample = pulsarObjectMapper().createObjectNode()
        sample.put("ref", buildElementRef(m))
        val mBox = m.attr("vi")
        if (mBox.isNotBlank()) sample.put("box", mBox)
        val ownText = truncateText(m.text().trim())
        if (ownText.isNotBlank()) sample.put("text", ownText)

        val children = pulsarObjectMapper().createArrayNode()
        for (child in m.children().take(20)) {
            val childEl = child as? org.jsoup.nodes.Element ?: continue
            val cObj = pulsarObjectMapper().createObjectNode()
            cObj.put("ref", buildElementRef(childEl))
            val cBox = childEl.attr("vi")
            if (cBox.isNotBlank()) cObj.put("box", cBox)
            val cText = truncateText(childEl.text().trim())
            if (cText.isNotBlank()) cObj.put("text", cText)
            children.add(cObj)
        }
        sample.set<ArrayNode>("children", children)
        samples.add(sample)
    }

    // Find recurring descendant selectors across matches
    data class SelectorCandidate(
        val selector: String,
        val tag: String,
        val selectorType: String,
    )

    class CandidateStats(
        var count: Int = 0,
        val textValues: MutableMap<Int, String> = mutableMapOf(),
        var maxWeight: Int = 0,
    )

    val candidateStats = mutableMapOf<SelectorCandidate, CandidateStats>()

    val priorityAttrs = listOf("data-testid", "aria-label", "role", "itemprop")
    val dataAttrPattern = Regex("^data-.+")
    val structuralTags = setOf("html", "head", "body", "script", "style", "meta", "link", "noscript")

    for ((matchIndex, match) in matches.withIndex()) {
        val seen = mutableSetOf<SelectorCandidate>()
        for (desc in match.select("*")) {
            val depth = desc.parents().indexOfFirst { it === match } + 1
            if (depth < 0 || depth > maxDepth) continue
            val descTag = desc.tagName().lowercase()
            if (descTag in structuralTags) continue

            val descClass = desc.className()
            val descId = desc.id()
            val descText = desc.ownText().trim().take(80)

            val candidates = mutableListOf<Pair<String, String>>()

            if (descClass.isNotBlank()) {
                val classes = descClass.split("\\s+".toRegex()).take(2).joinToString(".") { it }
                val sel = if (descId.isNotBlank()) "${descTag}.$classes#${descId}"
                else "${descTag}.$classes"
                candidates.add(sel to "class")
            } else if (descId.isNotBlank()) {
                candidates.add("${descTag}#${descId}" to "id")
            }

            candidates.add(descTag to "bare")

            for (attr in priorityAttrs) {
                val value = desc.attr(attr).trim()
                if (value.isNotBlank() && value.length <= 40) {
                    candidates.add("[$attr=\"$value\"]" to "attr")
                }
            }
            for (attr in desc.attributes()) {
                val key = attr.key
                if (key in priorityAttrs) continue
                if (dataAttrPattern.matches(key)) {
                    val value = attr.value.trim()
                    if (value.isNotBlank() && value.length <= 40) {
                        candidates.add("[$key=\"$value\"]" to "attr")
                    }
                }
            }

            val vi = desc.attr("vi").trim()
            if (vi.isNotBlank()) {
                val viParts = vi.split("\\s+".toRegex())
                if (viParts.size >= 4) {
                    val viWidth = viParts[2].toDoubleOrNull()?.toInt() ?: 0
                    val viHeight = viParts[3].toDoubleOrNull()?.toInt() ?: 0

                    if (viWidth >= 200) {
                        val w = (viWidth / 100) * 100
                        candidates.add("${descTag}:expr(width>${w})" to "power")
                        if (viHeight >= 100) {
                            val h = (viHeight / 100) * 100
                            candidates.add("${descTag}:expr(width>${w} && height>${h})" to "power")
                        }
                    }

                    val imgCount = desc.select("img").size
                    if (imgCount > 0) {
                        candidates.add("${descTag}:expr(img>0)" to "power")
                        if (viWidth >= 200) {
                            val w = (viWidth / 100) * 100
                            candidates.add("${descTag}:expr(width>${w} && img>0)" to "power")
                        }
                    }

                    val aCount = desc.select("a").size
                    if (aCount > 0) {
                        candidates.add("${descTag}:expr(a>0)" to "power")
                    }
                }
            }

            for ((sel, type) in candidates) {
                val candidate = SelectorCandidate(sel, descTag, type)
                if (seen.add(candidate)) {
                    val stats = candidateStats.getOrPut(candidate) { CandidateStats() }
                    stats.count++
                    if (descText.isNotBlank()) {
                        stats.textValues[matchIndex] = descText
                    }
                    val elemWeight = elementWeightMap[desc] ?: 0
                    if (elemWeight > stats.maxWeight) {
                        stats.maxWeight = elemWeight
                    }
                }
            }
        }
    }

    // Filter to selectors appearing in >= 50% of matches (min 2 matches)
    val threshold = maxOf(2, (matches.size * 0.5).toInt())
    val filtered = candidateStats.entries.filter { it.value.count >= threshold }

    val semanticTags =
        setOf("h1", "h2", "h3", "h4", "h5", "h6", "a", "img", "button", "input", "select", "textarea", "label")

    fun distinctTextCount(stats: CandidateStats): Int =
        stats.textValues.values.filter { it.isNotBlank() }.distinct().size

    fun qualityScore(candidate: SelectorCandidate, stats: CandidateStats): Double {
        val n = stats.count.toDouble()
        val specificityPerMatch = when (candidate.selectorType) {
            "id" -> 0.7
            "class" -> 0.4
            "power" -> 0.35
            "attr" -> 0.2
            "bare" -> if (candidate.tag in setOf("div", "span")) -0.3 else -0.1
            else -> 0.0
        }
        val distinctBoost = if (distinctTextCount(stats) >= 2) 0.3 else 0.0
        val semanticBoost = if (candidate.tag in semanticTags) 0.2 else 0.0
        val weightBoost = if (stats.maxWeight > 0) {
            (stats.maxWeight / 1_000_000.0).coerceIn(0.0, 1.0) * 0.4
        } else 0.0
        return n + (specificityPerMatch * n) + (distinctBoost * n) + (semanticBoost * n) + (weightBoost * n)
    }

    val ranked = filtered
        .sortedByDescending { (c, s) -> qualityScore(c, s) }
        .take(40)

    val scores = ranked.map { (c, s) -> qualityScore(c, s) }
    val p75 = if (scores.isNotEmpty()) {
        val idx = (scores.size * 0.25).toInt().coerceIn(0, scores.size - 1)
        scores.sortedDescending()[idx]
    } else 0.0

    fun qualityTier(score: Double): String = when {
        score >= p75 -> "high"
        score >= p75 * 0.5 -> "medium"
        else -> "low"
    }

    val suggestions = pulsarObjectMapper().createArrayNode()
    for ((candidate, stats) in ranked) {
        val score = qualityScore(candidate, stats)
        val sug = pulsarObjectMapper().createObjectNode()
        sug.put("selector", candidate.selector)
        sug.put("tag", candidate.tag)
        val firstText = stats.textValues.values.firstOrNull { it.isNotBlank() }
        if (firstText != null) sug.put("textPreview", firstText)
        val distinctTexts = stats.textValues.values.filter { it.isNotBlank() }.distinct().take(3)
        if (distinctTexts.isNotEmpty()) {
            val samplesArr = pulsarObjectMapper().createArrayNode()
            distinctTexts.forEach { samplesArr.add(it) }
            sug.set<ArrayNode>("textSamples", samplesArr)
        }
        sug.put("matchCount", stats.count)
        sug.put("coverage", "%.0f%%".format(stats.count * 100.0 / matches.size))
        sug.put("quality", qualityTier(score))
        suggestions.add(sug)
    }

    return pulsarObjectMapper().createObjectNode().apply {
        put("matchCount", matchCount)
        put("selector", effectiveSelector)
        put("analyzed", matches.size)
        if (autoDiscovered) {
            put("autoDiscovered", true)
            put("originalSelector", selector)
        }
        if (speculativeSuggestion != null) {
            put("speculativeSuggestion", speculativeSuggestion)
            speculativeMatchCount?.let { put("speculativeMatchCount", it) }
        }
        set<ArrayNode>("samples", samples)
        set<ArrayNode>("suggestions", suggestions)
        if (visualLinkGroups.isNotEmpty()) {
            set<ArrayNode>("linkGroups", linkGroupsToJson(visualLinkGroups))
        }
    }.toString()
}

// =========================================================================
// Element serialization utilities (Section 8 format)
// =========================================================================

private val SEMANTIC_TAGS = setOf("nav", "form", "header", "main", "footer", "aside", "section", "article")

private val SEMANTIC_ROLES = setOf(
    "navigation", "search", "form", "banner", "contentinfo", "complementary",
    "main", "region", "article"
)

internal fun buildElementRef(el: org.jsoup.nodes.Element): String {
    val closestId = findClosestId(el)
    val idPart = if (closestId.isNotEmpty()) "#$closestId " else ""
    val ownId = el.id().takeIf { it.isNotBlank() }?.let { "#$it" } ?: ""
    val classPart = formatClassList(el)
    return "$idPart${el.tagName().lowercase()}$ownId$classPart"
}

internal fun findClosestId(el: org.jsoup.nodes.Element, maxLevels: Int = 6): String {
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

internal fun formatClassList(el: org.jsoup.nodes.Element): String {
    val cls = el.className().trim()
    if (cls.isBlank()) return ""
    val classes = cls.split("\\s+".toRegex()).take(2)
    return classes.joinToString("") { ".$it" }
}

internal fun truncateText(text: String, maxWords: Int = 5): String {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return ""
    if (trimmed.any { isCJK(it) }) {
        return trimmed.take(maxWords)
    }
    val words = trimmed.split("\\s+".toRegex())
    return words.take(maxWords).joinToString(" ")
}

internal fun isCJK(c: Char): Boolean {
    val cp = c.code
    return cp in 0x4E00..0x9FFF
        || cp in 0x3400..0x4DBF
        || cp in 0xF900..0xFAFF
        || cp in 0x3040..0x309F
        || cp in 0x30A0..0x30FF
        || cp in 0xAC00..0xD7AF
        || cp in 0x2E80..0x2EFF
        || cp in 0x3000..0x303F
        || cp in 0xFF00..0xFFEF
}

internal fun findSemanticGroup(el: org.jsoup.nodes.Element): String {
    var current: org.jsoup.nodes.Element? = el.parent()
    var depth = 0
    while (current != null && depth < 10) {
        val tag = current.tagName().lowercase()
        if (tag in SEMANTIC_TAGS) return tag
        val role = current.attr("role").lowercase().trim()
        if (role in SEMANTIC_ROLES) return role
        val id = current.id().lowercase().trim()
        if (id.isNotBlank() && (id.contains("nav") || id.contains("menu") ||
                id.contains("header") || id.contains("footer") ||
                id.contains("sidebar") || id.contains("content") ||
                id.contains("search") || id.contains("form"))
        ) {
            return id
        }
        current = current.parent()
        depth++
    }
    return "Page"
}

// =========================================================================
// Interactive Element Weighting
// =========================================================================

internal fun computeInteractiveWeights(
    elements: List<org.jsoup.nodes.Element>
): List<Triple<org.jsoup.nodes.Element, Int, String>> {
    if (elements.isEmpty()) return emptyList()

    data class BoxInfo(
        val el: org.jsoup.nodes.Element,
        val x: Double, val y: Double, val w: Double, val h: Double,
        val area: Double, val tag: String, val role: String?
    )

    val infos = mutableListOf<BoxInfo>()

    for (el in elements) {
        if (el.attr("_h") == "1") continue
        if (el.attr("aria-hidden") == "true") continue
        if (el.hasAttr("disabled") || el.attr("aria-disabled") == "true") continue
        if (el.attr("type").lowercase() == "hidden") continue

        val style = el.attr("style")
        if ("pointer-events: none" in style.replace(" ", "") ||
            "pointer-events:none" in style.replace(" ", "")
        ) continue

        val vi = el.attr("vi")
        if (vi.isBlank()) continue
        val parts = vi.split("\\s+".toRegex())
        if (parts.size < 4) continue
        val x = parts[0].toDoubleOrNull() ?: continue
        val y = parts[1].toDoubleOrNull() ?: continue
        val w = parts[2].toDoubleOrNull() ?: continue
        val h = parts[3].toDoubleOrNull() ?: continue
        if (w <= 0 || h <= 0) continue

        val area = w * h
        val tag = el.tagName().lowercase()
        val role = el.attr("role").takeIf { it.isNotBlank() }?.lowercase()

        infos.add(BoxInfo(el, x, y, w, h, area, tag, role))
    }

    val tier1Tags = setOf("button", "input", "select", "textarea", "details", "summary")
    val tier1Roles = setOf(
        "button", "checkbox", "radio", "switch", "tab", "menuitem",
        "combobox", "searchbox", "textbox", "slider", "spinbutton", "option", "treeitem",
        "link", "menuitemcheckbox", "menuitemradio"
    )

    val tier1 = mutableListOf<Pair<BoxInfo, Int>>()
    val links = mutableListOf<BoxInfo>()

    for (info in infos) {
        val isTier1 = info.tag in tier1Tags ||
                info.role in tier1Roles ||
                info.el.hasAttr("contenteditable") ||
                info.el.attr("contenteditable") == "true" ||
                info.el.hasAttr("onclick") ||
                info.el.hasAttr("onkeydown") ||
                info.el.hasAttr("onsubmit") ||
                (info.el.hasAttr("tabindex") && info.el.attr("tabindex") != "-1")

        if (isTier1) {
            tier1.add(info to (1_000_000 + info.area.toInt()))
        } else if (info.tag == "a" && info.el.hasAttr("href")) {
            links.add(info)
        }
    }

    val epsilon = 10.0
    val areaTolerance = 0.2

    val xGroups = mutableListOf<MutableList<BoxInfo>>()
    for (link in links) {
        var found = false
        for (group in xGroups) {
            if (Math.abs(link.x - group.first().x) <= epsilon) {
                group.add(link)
                found = true
                break
            }
        }
        if (!found) {
            xGroups.add(mutableListOf(link))
        }
    }

    val linkWeights = mutableMapOf<org.jsoup.nodes.Element, Int>()
    for (xGroup in xGroups) {
        xGroup.sortBy { it.area }

        val areaGroups = mutableListOf<MutableList<BoxInfo>>()
        for (link in xGroup) {
            var found = false
            for (group in areaGroups) {
                val refArea = group.first().area
                if (refArea > 0 && Math.abs(link.area - refArea) / refArea <= areaTolerance) {
                    group.add(link)
                    found = true
                    break
                }
            }
            if (!found) {
                areaGroups.add(mutableListOf(link))
            }
        }

        for (areaGroup in areaGroups) {
            val score = areaGroup.sumOf { it.area }.toInt()
            for (link in areaGroup) {
                linkWeights[link.el] = score
            }
        }
    }

    val result = mutableListOf<Triple<org.jsoup.nodes.Element, Int, String>>()

    tier1.sortedByDescending { it.second }.forEach { (info, weight) ->
        result.add(Triple(info.el, weight, "primary"))
    }

    links
        .filter { it.el in linkWeights }
        .sortedByDescending { linkWeights[it.el]!! }
        .forEach { link ->
            result.add(Triple(link.el, linkWeights[link.el]!!, "link"))
        }

    return result
}

// =========================================================================
// Link group serialization
// =========================================================================

internal fun linkGroupsToJson(
    linkGroups: List<PageSummaryIndexService.SummaryLinkGroup>,
): ArrayNode {
    val mapper = pulsarObjectMapper()
    val array = mapper.createArrayNode()
    for (lg in linkGroups) {
        val obj = mapper.createObjectNode().apply {
            val containerLabel = lg.containerTag + lg.containerSelector
            put("container", containerLabel)
            if (lg.containerSelector.isNotEmpty() && lg.containerSelector != lg.containerTag) {
                put("selector", lg.containerSelector)
            }
            put("itemTag", lg.itemTag)
            put("itemSelector", lg.itemSelector)
            put("count", lg.count)
            put("columnCount", lg.columnCount)
            put("viewportWidth", lg.viewportWidth)
            put("viewportHeight", lg.viewportHeight)
            put("allHaveLinks", lg.allHaveLinks)
            put("anyHaveImages", lg.anyHaveImages)
            put("avgCardWidth", lg.avgCardWidth)
            put("avgCardHeight", lg.avgCardHeight)
            put("distinctTextCount", lg.distinctTextCount)
            put("avgDescendants", lg.avgDescendants)
            if (lg.samples.isNotEmpty()) {
                val samplesArr = mapper.createArrayNode()
                for (sample in lg.samples) {
                    val sampleObj = mapper.createObjectNode().apply {
                        put("box", sample.box)
                        if (sample.links.isNotEmpty()) {
                            val linksArr = mapper.createArrayNode()
                            for (link in sample.links) {
                                val linkObj = mapper.createObjectNode().apply {
                                    put("text", link.text)
                                    put("href", link.href)
                                    put("box", link.box)
                                }
                                linksArr.add(linkObj)
                            }
                            set<ArrayNode>("links", linksArr)
                        }
                        put("hasImage", sample.hasImage)
                    }
                    samplesArr.add(sampleObj)
                }
                set<ArrayNode>("samples", samplesArr)
            }
            put("score", lg.score)
        }
        array.add(obj)
    }
    return array
}
