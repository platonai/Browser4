package ai.platon.browser4.driver.chrome.dom

import ai.platon.browser4.driver.chrome.dom.model.DOMRect
import ai.platon.browser4.driver.chrome.dom.model.MergedDOMTreeNode
import ai.platon.browser4.driver.chrome.dom.model.NodeType
import ai.platon.browser4.driver.chrome.dom.model.OptimizedDOMTreeNode
import kotlin.math.max
import kotlin.math.min

/**
 * Build an optimized DOM tree:
 * - Create simplified tree (skip disabled tags, include iframe content, preserve shadow DOM)
 * - Optimize tree (remove non-meaningful parents)
 * - Apply bounding box filtering with propagating bounds (mark excluded_by_parent)
 * - Assign interactive indices for visible+interactive elements only
 */
class OptimizedDOMTreeBuilder(
    private val root: MergedDOMTreeNode,
    private val previousBackendNodeIds: Set<Int> = emptySet(),
    private val enableBBoxFiltering: Boolean = true,
    private val containmentThreshold: Double = DEFAULT_CONTAINMENT_THRESHOLD,
    private val maxPaintOrderThreshold: Int = DEFAULT_MAX_PAINT_ORDER_THRESHOLD,
) {
    companion object {
        private val DISABLED_ELEMENTS = setOf("style", "script", "head", "meta", "link", "title")

        // 99% containment by default
        private const val DEFAULT_CONTAINMENT_THRESHOLD = 0.99
        private const val DEFAULT_MAX_PAINT_ORDER_THRESHOLD = 1000

        private data class PropagatingBounds(
            val tag: String,
            val bounds: DOMRect,
            val nodeId: Int,
            val depth: Int,
        )

        // Patterns that propagate bounds to descendants
        private val PROPAGATING_ELEMENTS: List<Pair<String, String?>> = listOf(
            // tag to optional role
            "a" to null,
            "button" to null,
            "div" to "button",
            "div" to "combobox",
            "span" to "button",
            "span" to "combobox",
            "input" to "combobox",
        )
    }

    private var interactiveCounter = 1

    fun build(): OptimizedDOMTreeNode? {
        val tinyTree = optimizeDOMTree(root) ?: return null
        val optimized = optimizeTree(tinyTree)
        val filtered = if (enableBBoxFiltering) applyBoundingBoxFiltering(optimized) else optimized
        return assignInteractiveIndices(filtered)
    }

    /** Create enhanced tree with key decisions: skip disabled tags, include iframe/frame content, shadow DOM. */
    private fun optimizeDOMTree(node: MergedDOMTreeNode, depth: Int = 0): OptimizedDOMTreeNode? {
        return when (node.nodeType) {
            NodeType.DOCUMENT_NODE -> {
                // Return first non-null tiny tree child among all children and shadow roots
                node.children.firstNotNullOfOrNull { optimizeDOMTree(it, depth + 1) }
                    ?: node.shadowRoots.firstNotNullOfOrNull { optimizeDOMTree(it, depth + 1) }
            }

            NodeType.DOCUMENT_FRAGMENT_NODE -> {
                // Shadow DOM fragment - always include to preserve shadow content
                val children = (node.children + node.shadowRoots).mapNotNull { optimizeDOMTree(it, depth + 1) }
                OptimizedDOMTreeNode(
                    originalNode = node,
                    children = children,
                    shouldDisplay = true,
                    isShadowHost = false,
                    // Mark high paint order as ignored
                    ignoredByPaintOrder = (node.snapshotNode?.paintOrder ?: Int.MIN_VALUE) > maxPaintOrderThreshold
                )
            }

            NodeType.ELEMENT_NODE -> {
                val tag = node.nodeName.lowercase()
                if (tag in DISABLED_ELEMENTS) return null

                // iframe/frame: include content document children
                if (node.nodeName.equals("IFRAME", true) || node.nodeName.equals("FRAME", true)) {
                    val doc = node.contentDocument
                    val children = doc?.children?.mapNotNull { optimizeDOMTree(it, depth + 1) } ?: emptyList()
                    return OptimizedDOMTreeNode(
                        originalNode = node,
                        children = children,
                        shouldDisplay = true,
                        isShadowHost = node.shadowRoots.isNotEmpty(),
                        ignoredByPaintOrder = (node.snapshotNode?.paintOrder ?: Int.MIN_VALUE) > maxPaintOrderThreshold
                    )
                }

                var isVisible = node.isVisible == true
                val isScrollable = node.isScrollable == true
                val hasShadowContent = node.shadowRoots.isNotEmpty()
                val isShadowHost = hasShadowContent
                val alwaysKeep = isAlwaysKeptControl(node)

                // Force visible if has aria-/pseudo validation-like attributes
                if (!isVisible && node.attributes.isNotEmpty()) {
                    val hasValidationAttrs = node.attributes.keys.any { it.startsWith("aria-", true) || it.startsWith("pseudo", true) }
                    if (hasValidationAttrs) isVisible = true
                }

                // Include if meaningful
                if (alwaysKeep || isVisible || isScrollable || node.children.isNotEmpty() || hasShadowContent) {
                    val children = (node.children + node.shadowRoots).mapNotNull { optimizeDOMTree(it, depth + 1) }
                    return OptimizedDOMTreeNode(
                        originalNode = node,
                        children = children,
                        shouldDisplay = true,
                        isShadowHost = isShadowHost,
                        ignoredByPaintOrder = (node.snapshotNode?.paintOrder ?: Int.MIN_VALUE) > maxPaintOrderThreshold
                    )
                }
                null
            }

            NodeType.TEXT_NODE -> {
                val visible = node.snapshotNode != null && node.isVisible == true
                val text = node.nodeValue.trim()
                if (visible && text.length > 1) {
                    OptimizedDOMTreeNode(
                        originalNode = node,
                        children = emptyList(),
                        shouldDisplay = true,
                        ignoredByPaintOrder = (node.snapshotNode.paintOrder ?: Int.MIN_VALUE) > maxPaintOrderThreshold
                    )
                } else null
            }

            else -> null
        }
    }

    /** Remove non-meaningful parents: keep if visible or scrollable or is text or has children */
    private fun optimizeTree(node: OptimizedDOMTreeNode?): OptimizedDOMTreeNode? {
        node ?: return null
        val optimizedChildren = node.children.mapNotNull { optimizeTree(it) }
        val newNode = node.copy(children = optimizedChildren)
        // Consider node visible solely based on isVisible flag; do not require snapshot presence
        val isVisible = newNode.originalNode.isVisible == true
        return if (isVisible ||
            newNode.originalNode.isScrollable == true ||
            newNode.originalNode.nodeType == NodeType.TEXT_NODE ||
            newNode.children.isNotEmpty() ||
            isAlwaysKeptControl(newNode.originalNode)) {
            newNode
        } else null
    }

    /** Apply bounding-box filtering with propagating parents; mark excluded_by_parent on contained children. */
    private fun applyBoundingBoxFiltering(node: OptimizedDOMTreeNode?): OptimizedDOMTreeNode? {
        node ?: return null
        return filterRecursive(node, activeBounds = null, depth = 0)
    }

    private fun filterRecursive(node: OptimizedDOMTreeNode, activeBounds: PropagatingBounds?, depth: Int): OptimizedDOMTreeNode {
        var excluded = false
        // Exclude if sufficiently contained in active bounds
        if (activeBounds != null && shouldExcludeChild(node, activeBounds)) {
            excluded = true
        }

        // Start new propagation if this node matches
        val newBounds = if (isPropagatingElement(node.originalNode)) {
            node.originalNode.snapshotNode?.bounds?.let {
                PropagatingBounds(tag = node.originalNode.nodeName.lowercase(), bounds = it, nodeId = node.originalNode.nodeId, depth = depth)
            }
        } else null

        val propagate = newBounds ?: activeBounds
        val newChildren = node.children.map { filterRecursive(it, propagate, depth + 1) }
        return node.copy(children = newChildren, excludedByParent = excluded)
    }

    private fun shouldExcludeChild(node: OptimizedDOMTreeNode, activeBounds: PropagatingBounds): Boolean {
        val original = node.originalNode
        if (original.nodeType == NodeType.TEXT_NODE) return false
        if (isAlwaysKeptControl(original)) return false
        val childBounds = original.snapshotNode?.bounds ?: return false
        if (!isContained(childBounds, activeBounds.bounds, containmentThreshold)) return false

        // Exceptions - keep these even if contained
        val tag = original.nodeName.lowercase()
        val role = original.attributes["role"]

        // 1. form elements always kept
        if (tag in setOf("input", "select", "textarea", "label")) return false
        // 2. if child also propagates
        if (isPropagatingElement(original)) return false
        // 3. explicit onclick
        if (original.attributes.containsKey("onclick")) return false
        // 4. meaningful aria-label
        original.attributes["aria-label"]?.let { if (it.isNotBlank()) return false }
        // 5. roles suggesting interactivity
        if (role in setOf("button", "link", "checkbox", "radio", "tab", "menuitem", "option")) return false

        return true
    }

    private fun isContained(child: DOMRect, parent: DOMRect, threshold: Double): Boolean {
        val xOverlap = max(0.0, min(child.x + child.width, parent.x + parent.width) - max(child.x, parent.x))
        val yOverlap = max(0.0, min(child.y + child.height, parent.y + parent.height) - max(child.y, parent.y))
        val intersection = xOverlap * yOverlap
        val area = child.width * child.height
        if (area <= 0.0) return false
        val ratio = intersection / area
        return ratio >= threshold
    }

    private fun isPropagatingElement(node: MergedDOMTreeNode): Boolean {
        val tag = node.nodeName.lowercase()
        val role = node.attributes["role"]
        return PROPAGATING_ELEMENTS.any { (t, r) -> (t == tag) && (r == null || r == role) }
    }

    private fun isAlwaysKeptControl(node: MergedDOMTreeNode): Boolean {
        val tag = node.nodeName.lowercase()
        if (tag == "a") return false
        if (tag == "button" || tag == "select" || tag == "textarea") return true
        if (tag == "input") {
            val type = node.attributes["type"]?.lowercase()
            if (type == "hidden") return false
            return true
        }
        val role = node.attributes["role"]?.lowercase()
        if (role in setOf("textbox", "searchbox", "combobox", "button", "switch", "slider", "spinbutton")) {
            return true
        }
        val isContentEditable = node.attributes["contenteditable"]?.lowercase() == "true"
        return isContentEditable
    }

    /** Assign interactive indices in DFS order to nodes that are visible + interactive and not excluded/ignored. */
    private fun assignInteractiveIndices(node: OptimizedDOMTreeNode?): OptimizedDOMTreeNode? {
        node ?: return null
        return assignRecursive(node)
    }

    private fun assignRecursive(node: OptimizedDOMTreeNode): OptimizedDOMTreeNode {
        // Compute this node's index if qualifies
        val qualifies = !node.excludedByParent && !node.ignoredByPaintOrder &&
                (node.originalNode.isInteractable == true) && (node.originalNode.isVisible == true)

        val index = if (qualifies) interactiveCounter++ else null
        val newChildren = node.children.map { assignRecursive(it) }

        // isNew: mark if backend node id not in previous set (best-effort)
        val backendId = node.originalNode.backendNodeId
        val isNew = backendId != null && backendId !in previousBackendNodeIds

        return node.copy(children = newChildren, interactiveIndex = index, isNew = isNew)
    }
}
