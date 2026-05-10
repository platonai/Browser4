package ai.platon.browser4.driver.chrome.dom.util

import ai.platon.browser4.driver.chrome.dom.model.MergedDOMTreeNode

/**
 * Utility functions for scroll-related logic.
 */
object ScrollUtils {

    /**
     * Determine if a node is actually scrollable.
     *
     * Rules:
     * - Respect CDP isScrollable early return
     * - Require snapshot and rects
     * - Compare scrollRect vs clientRect dimensions (+1 tolerance)
     * - Allow scrolling only if CSS overflow allows (auto/scroll/overlay)
     * - If no CSS info, allow only common scrollable container tags
     */
    fun isActuallyScrollable(node: MergedDOMTreeNode): Boolean {
        // Respect CDP detection first
        if (node.isScrollable == true) {
            return true
        }

        val snapshot = node.snapshotNode ?: return false

        // Get rects; both must exist
        val clientRect = snapshot.clientRects ?: return false
        val scrollRect = snapshot.scrollRects ?: return false

        // Content larger than visible area indicates potential scrolling
        val hasVerticalScroll = scrollRect.height > clientRect.height + 1.0
        val hasHorizontalScroll = scrollRect.width > clientRect.width + 1.0
        if (!hasVerticalScroll && !hasHorizontalScroll) {
            return false
        }

        // Check computed CSS to ensure scrolling is allowed
        val styles = snapshot.computedStyles
        if (styles != null && styles.isNotEmpty()) {
            val overflow = styles["overflow"]?.lowercase() ?: "visible"
            val overflowX = styles["overflow-x"]?.lowercase() ?: overflow
            val overflowY = styles["overflow-y"]?.lowercase() ?: overflow

            // Only allow if any overflow property explicitly allows scrolling
            val allows = overflow in setOf("auto", "scroll", "overlay") ||
                overflowX in setOf("auto", "scroll", "overlay") ||
                overflowY in setOf("auto", "scroll", "overlay")

            return allows
        }

        // No CSS info: be conservative, allow only common scrollable containers
        val tag = node.nodeName.uppercase()
        val scrollableTags = setOf("DIV", "MAIN", "SECTION", "ARTICLE", "ASIDE", "BODY", "HTML")
        return tag in scrollableTags
    }

    /**
     * Generate scroll information text for display.
     *
     * @param node The DOM node
     * @return Human-readable scroll info or null if not scrollable
     */
    fun getScrollInfoText(node: MergedDOMTreeNode): String? {
        if (!isActuallyScrollable(node)) return null

        val snapshot = node.snapshotNode ?: return null
        val clientRect = snapshot.clientRects ?: return null
        val scrollRect = snapshot.scrollRects ?: return null

        val tag = node.nodeName.uppercase()

        // Special formatting for iframes
        if (tag == "IFRAME" || tag == "FRAME") {
            return buildString {
                append("iframe scrollable")
                val id = node.attributes["id"]
                val name = node.attributes["name"]
                if (!id.isNullOrEmpty()) append(" id='$id'")
                else if (!name.isNullOrEmpty()) append(" name='$name'")
            }
        }

        // Regular elements - show dimensions
        return buildString {
            append("scrollable")

            val isHorizontal = scrollRect.width > clientRect.width + 1.0
            val isVertical = scrollRect.height > clientRect.height + 1.0

            if (isHorizontal && isVertical) {
                append(" (both)")
            } else if (isHorizontal) {
                append(" (horizontal)")
            } else if (isVertical) {
                append(" (vertical)")
            }

            append(" [${clientRect.width.toInt()}x${clientRect.height.toInt()}")
            append(" < ${scrollRect.width.toInt()}x${scrollRect.height.toInt()}]")
        }
    }

    /**
     * Check if scroll info should be displayed for this node.
     * Avoids duplicating scroll info for nested scroll containers.
     *
     * @param node The DOM node
     * @param ancestors List of ancestor nodes
     * @return true if scroll info should be shown
     */
    fun shouldShowScrollInfo(node: MergedDOMTreeNode, ancestors: List<MergedDOMTreeNode>): Boolean {
        if (!isActuallyScrollable(node)) return false

        // Check if any ancestor is also scrollable in the same direction
        val snapshot = node.snapshotNode ?: return true
        val clientRect = snapshot.clientRects ?: return true
        val scrollRect = snapshot.scrollRects ?: return true

        val nodeHorizontalScroll = scrollRect.width > clientRect.width + 1.0
        val nodeVerticalScroll = scrollRect.height > clientRect.height + 1.0

        // Look for scrollable ancestors
        for (ancestor in ancestors.reversed()) {
            if (!isActuallyScrollable(ancestor)) continue

            val ancestorSnapshot = ancestor.snapshotNode ?: continue
            val ancestorClient = ancestorSnapshot.clientRects ?: continue
            val ancestorScroll = ancestorSnapshot.scrollRects ?: continue

            val ancestorHorizontal = ancestorScroll.width > ancestorClient.width + 1.0
            val ancestorVertical = ancestorScroll.height > ancestorClient.height + 1.0

            // If ancestor scrolls in same direction, hide current node's scroll info
            if ((nodeHorizontalScroll && ancestorHorizontal) ||
                (nodeVerticalScroll && ancestorVertical)) {
                return false
            }
        }

        return true
    }
}
