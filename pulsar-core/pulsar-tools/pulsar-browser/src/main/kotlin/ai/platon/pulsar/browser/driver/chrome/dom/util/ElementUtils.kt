package ai.platon.pulsar.browser.driver.chrome.dom.util

import ai.platon.pulsar.browser.driver.chrome.dom.model.DOMRect
import ai.platon.pulsar.browser.driver.chrome.dom.model.DOMTreeNodeEx
import ai.platon.pulsar.browser.driver.chrome.dom.model.NodeType

/**
 * Utility functions for DOM element operations.
 */
object ElementUtils {

    /**
     * Check if an element is visible based on its bounds and computed styles.
     *
     * @param node The DOM node to check
     * @return true if the element is considered visible
     */
    fun isVisible(node: DOMTreeNodeEx): Boolean {
        // Only element nodes can be visible
        if (node.nodeType != NodeType.ELEMENT_NODE) {
            return false
        }

        val snapshot = node.snapshotNode ?: return false
        
        // Check computed styles for visibility
        val styles = snapshot.computedStyles
        if (styles != null && styles.isNotEmpty()) {
            val display = styles["display"]?.lowercase()
            val visibility = styles["visibility"]?.lowercase()
            val opacity = styles["opacity"]?.toDoubleOrNull()
            
            if (display == "none") return false
            if (visibility == "hidden") return false
            if (opacity != null && opacity <= 0.0) return false
        }

        // Check bounds - element must have non-zero dimensions
        val clientRect = snapshot.clientRects ?: return false
        if (clientRect.width <= 0.0 || clientRect.height <= 0.0) {
            return false
        }

        return true
    }

    /**
     * Check if an element is within the viewport bounds.
     *
     * @param node The DOM node to check
     * @param viewportWidth The viewport width
     * @param viewportHeight The viewport height
     * @return true if the element is within viewport
     */
    fun isInViewport(node: DOMTreeNodeEx, viewportWidth: Double, viewportHeight: Double): Boolean {
        val snapshot = node.snapshotNode ?: return false
        val clientRect = snapshot.clientRects ?: return false
        
        // Element must be at least partially visible in viewport
        return clientRect.x < viewportWidth &&
               clientRect.y < viewportHeight &&
               clientRect.x + clientRect.width > 0.0 &&
               clientRect.y + clientRect.height > 0.0
    }

    /**
     * Check if two element bounds overlap.
     *
     * @param rect1 First rectangle
     * @param rect2 Second rectangle
     * @return true if rectangles overlap
     */
    fun boundsOverlap(rect1: DOMRect, rect2: DOMRect): Boolean {
        return !(rect1.x + rect1.width < rect2.x ||
                 rect2.x + rect2.width < rect1.x ||
                 rect1.y + rect1.height < rect2.y ||
                 rect2.y + rect2.height < rect1.y)
    }

    /**
     * Calculate the area of intersection between two rectangles.
     *
     * @param rect1 First rectangle
     * @param rect2 Second rectangle
     * @return Intersection area, or 0.0 if no overlap
     */
    fun intersectionArea(rect1: DOMRect, rect2: DOMRect): Double {
        if (!boundsOverlap(rect1, rect2)) {
            return 0.0
        }

        val x1 = maxOf(rect1.x, rect2.x)
        val y1 = maxOf(rect1.y, rect2.y)
        val x2 = minOf(rect1.x + rect1.width, rect2.x + rect2.width)
        val y2 = minOf(rect1.y + rect1.height, rect2.y + rect2.height)

        return (x2 - x1) * (y2 - y1)
    }

    /**
     * Check if an element is interactive based on its tag and attributes.
     *
     * @param node The DOM node to check
     * @return true if the element is considered interactive
     */
    fun isInteractive(node: DOMTreeNodeEx): Boolean {
        if (node.nodeType != NodeType.ELEMENT_NODE) {
            return false
        }

        val tag = node.nodeName.lowercase()
        
        // Known interactive tags
        if (tag in setOf("a", "button", "input", "select", "textarea")) {
            return true
        }

        // Check for click handlers or role attributes
        val onclick = node.attributes["onclick"]
        val role = node.attributes["role"]
        
        if (!onclick.isNullOrEmpty()) return true
        if (role in setOf("button", "link", "checkbox", "radio", "menuitem")) {
            return true
        }

        // Check tabindex
        val tabindex = node.attributes["tabindex"]?.toIntOrNull()
        if (tabindex != null && tabindex >= 0) {
            return true
        }

        return false
    }

    /**
     * Get the center point of an element's bounding box.
     *
     * @param node The DOM node
     * @return Pair of (x, y) coordinates, or null if bounds unavailable
     */
    fun getCenter(node: DOMTreeNodeEx): Pair<Double, Double>? {
        val snapshot = node.snapshotNode ?: return null
        val clientRect = snapshot.clientRects ?: return null
        
        val x = clientRect.x + clientRect.width / 2.0
        val y = clientRect.y + clientRect.height / 2.0
        
        return Pair(x, y)
    }

    /**
     * Find the first ancestor matching a predicate from an ancestors list.
     *
     * @param ancestors The list of ancestor nodes
     * @param predicate The condition to match
     * @return The first matching ancestor or null
     */
    fun findAncestor(ancestors: List<DOMTreeNodeEx>, predicate: (DOMTreeNodeEx) -> Boolean): DOMTreeNodeEx? {
        return ancestors.firstOrNull(predicate)
    }

    /**
     * Get all descendant nodes using depth-first traversal.
     *
     * @param node The root node
     * @param maxDepth Maximum depth to traverse
     * @return List of all descendant nodes
     */
    fun getDescendants(node: DOMTreeNodeEx, maxDepth: Int = Int.MAX_VALUE): List<DOMTreeNodeEx> {
        val descendants = mutableListOf<DOMTreeNodeEx>()
        
        fun traverse(current: DOMTreeNodeEx, depth: Int) {
            if (depth >= maxDepth) return
            
            for (child in current.children) {
                descendants.add(child)
                traverse(child, depth + 1)
            }
        }
        
        traverse(node, 0)
        return descendants
    }

    /**
     * Find all descendants matching a predicate.
     *
     * @param node The root node
     * @param predicate The condition to match
     * @param maxDepth Maximum depth to traverse
     * @return List of matching descendants
     */
    fun findDescendants(
        node: DOMTreeNodeEx,
        predicate: (DOMTreeNodeEx) -> Boolean,
        maxDepth: Int = Int.MAX_VALUE
    ): List<DOMTreeNodeEx> {
        val matches = mutableListOf<DOMTreeNodeEx>()
        
        fun traverse(current: DOMTreeNodeEx, depth: Int) {
            if (depth >= maxDepth) return
            
            for (child in current.children) {
                if (predicate(child)) {
                    matches.add(child)
                }
                traverse(child, depth + 1)
            }
        }
        
        traverse(node, 0)
        return matches
    }

    /**
     * Check if a node has any text content.
     *
     * @param node The DOM node
     * @return true if node or its descendants contain text
     */
    fun hasTextContent(node: DOMTreeNodeEx): Boolean {
        if (node.nodeType == NodeType.TEXT_NODE) {
            return !node.nodeValue.isNullOrBlank()
        }
        
        // Check children recursively
        return node.children.any { hasTextContent(it) }
    }

    /**
     * Get the visible text content of a node and its descendants.
     *
     * @param node The DOM node
     * @param maxLength Maximum length of text to extract
     * @return Extracted text content
     */
    fun getTextContent(node: DOMTreeNodeEx, maxLength: Int = Int.MAX_VALUE): String {
        val text = StringBuilder()
        
        fun extractText(current: DOMTreeNodeEx) {
            if (text.length >= maxLength) return
            
            if (current.nodeType == NodeType.TEXT_NODE) {
                val value = current.nodeValue?.trim()
                if (!value.isNullOrEmpty()) {
                    if (text.isNotEmpty()) {
                        text.append(" ")
                    }
                    text.append(value)
                }
            } else if (current.nodeType == NodeType.ELEMENT_NODE) {
                for (child in current.children) {
                    extractText(child)
                }
            }
        }
        
        extractText(node)
        return text.toString().take(maxLength).trim()
    }
}
