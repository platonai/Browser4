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

