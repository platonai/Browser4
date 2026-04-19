package ai.platon.pulsar.skeleton.crawl.parse

import ai.platon.pulsar.common.CheckState
import ai.platon.pulsar.common.config.Parameterized
import ai.platon.pulsar.skeleton.crawl.parse.html.ParseContext

/**
 * Extension point for DOM-based parsers. A parse filter can have children, so all parse filters form a tree.
 * All parse filters at the same level are run sequentially.
 */
interface ParseFilter : Parameterized, AutoCloseable {
    val id: Int

    /**
     * The parent filter
     * */
    var parent: ParseFilter?
    /**
     * The child filters
     * */
    val children: List<ParseFilter>

    /**
     * The id of the parent filter
     * */
    val parentId get() = parent?.id ?: 0
    /**
     * Check if this filter is the root in the filter tree.
     * */
    val isRoot get() = parent == null
    /**
     * Check if this filter is a leaf in the filter tree.
     * */
    val isLeaf get() = children.isEmpty()

    /**
     * Add the filter to the head of the child filter list.
     * */
    fun addFirst(child: ParseFilter)

    /**
     * Add the filter to the tail of the child filter list.
     * */
    fun addLast(child: ParseFilter)

    /**
     * Initialize the filter.
     * */
    fun initialize()

    /**
     * Check if this parse filter is relevant to this page.
     * Sometimes we may want to write a separate [ParseFilter] for a category.
     * */
    fun isRelevant(parseContext: ParseContext): CheckState

    /**
     * Invoke before the filtering.
     * */
    fun onBeforeFilter(parseContext: ParseContext)

    /**
     * Do the filtering.
     * */
    fun filter(parseContext: ParseContext): FilterResult

    /**
     * Invoke after the filtering.
     * */
    fun onAfterFilter(parseContext: ParseContext)

    /**
     * Close the filter.
     * */
    override fun close() {}
}
