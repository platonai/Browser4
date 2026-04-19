package ai.platon.browser4.driver.chrome.dom.util

import ai.platon.browser4.driver.chrome.dom.model.MergedDOMTreeNode
import ai.platon.browser4.driver.chrome.dom.model.NodeType
import java.util.concurrent.ConcurrentHashMap

/**
 * Utility functions for generating XPath expressions.
 */
object XPathUtils {

    // Cache for XPath calculations to prevent repeated computation
    private val xpathCache = ConcurrentHashMap<String, String>()

    // Cache for sibling index calculations
    private val siblingIndexCache = ConcurrentHashMap<String, Int>()

    /**
     * Generate XPath for a node based on its position in the tree.
     *
     * Enhanced Rules:
     * - Stops at shadow root boundaries (returns path up to shadow host)
     * - Stops at iframe boundaries (returns path up to iframe element)
     * - Uses 1-based indexing for sibling elements with same tag
     * - Includes id attribute when available for robustness
     * - Handles special cases like template elements, slots, and custom elements
     * - Caches results for performance
     *
     * @param node The target node
     * @param ancestors List of ancestor nodes from root to immediate parent
     * @param siblings Map of parent node IDs to their children (for index calculation)
     * @return XPath string
     */
    fun generateXPath(
        node: MergedDOMTreeNode,
        ancestors: List<MergedDOMTreeNode> = emptyList(),
        siblings: Map<Int, List<MergedDOMTreeNode>> = emptyMap()
    ): String {
        // Only generate XPath for element nodes
        if (node.nodeType != NodeType.ELEMENT_NODE) {
            return ""
        }

        // Check cache first
        val cacheKey = buildXPathCacheKey(node, ancestors)
        xpathCache[cacheKey]?.let { return it }

        val parts = mutableListOf<String>()

        // Build path from ancestors with enhanced boundary detection
        var insideShadowDOM = false
        var insideIFrame = false
        var stoppedAtBoundary = false

        for (i in ancestors.indices) {
            val ancestor = ancestors[i]

            // Skip non-element nodes
            if (ancestor.nodeType != NodeType.ELEMENT_NODE) continue

            // Check if this is a shadow host (has shadow roots)
            if (ancestor.shadowRoots.isNotEmpty()) {
                insideShadowDOM = true
                // For shadow host, add the element but mark that we're entering shadow DOM
                parts.add(buildXPathSegment(ancestor, ancestors.getOrNull(i - 1), siblings, insideShadowDOM))
                continue
            }

            // Check if this is an iframe - stop here
            if (ancestor.nodeName.equals("IFRAME", ignoreCase = true) ||
                ancestor.nodeName.equals("FRAME", ignoreCase = true)) {
                insideIFrame = true
                parts.add(buildXPathSegment(ancestor, ancestors.getOrNull(i - 1), siblings, insideIFrame))
                // Stop at iframe boundary
                stoppedAtBoundary = true
                break
            }

            // Check for template elements - they have special content handling
            if (ancestor.nodeName.equals("TEMPLATE", ignoreCase = true)) {
                parts.add(buildXPathSegment(ancestor, ancestors.getOrNull(i - 1), siblings, insideShadowDOM))
                // Continue but mark special handling
                continue
            }

            // Check for slot elements in shadow DOM
            if (insideShadowDOM && ancestor.nodeName.equals("SLOT", ignoreCase = true)) {
                parts.add(buildXPathSegment(ancestor, ancestors.getOrNull(i - 1), siblings, insideShadowDOM))
                continue
            }

            parts.add(buildXPathSegment(ancestor, ancestors.getOrNull(i - 1), siblings, insideShadowDOM))
        }

        // Add the target node itself with boundary context
        val parent = ancestors.lastOrNull()
        parts.add(buildXPathSegment(node, parent, siblings, insideShadowDOM || insideIFrame))

        // Build final XPath
        val xpath = if (parts.isEmpty()) {
            "/" + node.nodeName.lowercase()
        } else {
            "/" + parts.joinToString("/")
        }

        // Cache the result
        xpathCache[cacheKey] = xpath
        return xpath
    }

    /**
     * Build cache key for XPath calculation.
     */
    private fun buildXPathCacheKey(node: MergedDOMTreeNode, ancestors: List<MergedDOMTreeNode>): String {
        return buildString {
            append(node.nodeId)
            append(":")
            ancestors.forEach { append(it.nodeId).append(",") }
        }
    }

    /**
     * Build XPath segment for a single node.
     * Enhanced with caching, better index calculation, and special case handling.
     * Includes tag name, optional id predicate, and index for disambiguation.
     */
    private fun buildXPathSegment(
        node: MergedDOMTreeNode,
        parent: MergedDOMTreeNode?,
        siblings: Map<Int, List<MergedDOMTreeNode>>,
        insideBoundary: Boolean = false
    ): String {
        val tag = node.nodeName.lowercase()
        val id = node.attributes["id"]

        // If node has an ID, use it for robustness
        if (!id.isNullOrEmpty()) {
            return "$tag[@id='$id']"
        }

        // Check for special element types that need custom handling
        when {
            tag == "slot" && insideBoundary -> {
                // For slots in shadow DOM, include name attribute if available
                val name = node.attributes["name"]
                return if (!name.isNullOrEmpty()) {
                    "slot[@name='$name']"
                } else {
                    "slot"
                }
            }
            tag == "template" -> {
                // Template elements are special - they don't render content directly
                return "template"
            }
            tag.contains("-") -> {
                // Custom elements (web components) - use tag name as-is but check for special attributes
                val specialAttrs = listOf("is", "name", "slot")
                for (attr in specialAttrs) {
                    val value = node.attributes[attr]
                    if (!value.isNullOrEmpty()) {
                        return "$tag[@$attr='$value']"
                    }
                }
            }
        }

        // Calculate sibling index (1-based) with caching
        val index = if (parent != null) {
            val cacheKey = "${parent.nodeId}:${node.nodeId}:${node.nodeName}"
            siblingIndexCache[cacheKey]?.let { return@let if (it > 1) it else null }

            val parentChildren = siblings[parent.nodeId] ?: parent.children
            val sameTagSiblings = parentChildren.filter {
                it.nodeName.equals(node.nodeName, ignoreCase = true) &&
                it.nodeType == NodeType.ELEMENT_NODE
            }

            val idx = if (sameTagSiblings.size > 1) {
                val foundIdx = sameTagSiblings.indexOfFirst { it.nodeId == node.nodeId }
                if (foundIdx >= 0) foundIdx + 1 else null
            } else {
                null
            }

            // Cache the result
            if (idx != null) {
                siblingIndexCache[cacheKey] = idx
            }
            idx
        } else {
            null
        }

        return if (index != null && index > 1) {
            "$tag[$index]"
        } else {
            tag
        }
    }

    /**
     * Generate a simplified XPath for display purposes.
     * Uses only tag names and IDs, without indices.
     */
    fun generateSimpleXPath(node: MergedDOMTreeNode, ancestors: List<MergedDOMTreeNode> = emptyList()): String {
        val parts = ancestors.mapNotNull { ancestor ->
            if (ancestor.nodeType != NodeType.ELEMENT_NODE) return@mapNotNull null

            val tag = ancestor.nodeName.lowercase()
            val id = ancestor.attributes["id"]

            if (!id.isNullOrEmpty()) {
                "$tag[@id='$id']"
            } else {
                tag
            }
        }.toMutableList()

        // Add target node
        val tag = node.nodeName.lowercase()
        val id = node.attributes["id"]
        parts.add(if (!id.isNullOrEmpty()) "$tag[@id='$id']" else tag)

        return "/" + parts.joinToString("/")
    }
}
