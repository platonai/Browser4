package ai.platon.pulsar.chrome.dom.model

import ai.platon.browser4.chrome.dom.DOMStateBuilder
import ai.platon.browser4.chrome.dom.impl.OptimizedDOMTreeBuilder
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class OptimizedDOMTreeBuilderTest {

    @Test
        @DisplayName("bbox filtering excludes contained non-exception children")
    fun bboxFilteringExcludesContainedNonExceptionChildren() {
        // Parent propagating element: <div role="button"> with bounds 0,0,100x40
        val parent = MergedDOMTreeNode(
            nodeId = 1,
            nodeName = "DIV",
            attributes = mapOf("role" to "button"),
            snapshotNode = SnapshotNodeEx(bounds = DOMRect(0.0, 0.0, 100.0, 40.0)),
            isVisible = true
        )
        // Child element fully inside: <span>
        val childInside = MergedDOMTreeNode(
            nodeId = 2,
            nodeName = "SPAN",
            snapshotNode = SnapshotNodeEx(bounds = DOMRect(5.0, 5.0, 20.0, 10.0)),
            isVisible = true
        )
        val root = parent.copy(children = listOf(childInside))

        val slim = OptimizedDOMTreeBuilder(root, enableBBoxFiltering = true).build()
        assertNotNull(slim)
        val childSlim = slim!!.children.firstOrNull()
        assertNotNull(childSlim)
        assertTrue(childSlim!!.excludedByParent, "Child inside propagating bounds should be excluded by parent")
    }

    @Test
        @DisplayName("interactive indices assigned only to visible and interactive nodes and appear in selector map")
    fun interactiveIndicesAssignedOnlyToVisibleAndInteractiveNodesAndAppearInSelectorMap() {
        // Two interactive visible buttons
        val btn1 = MergedDOMTreeNode(
            nodeId = 10,
            nodeName = "BUTTON",
            isVisible = true,
            isInteractable = true,
            elementHash = "btn1"
        )
        val btn2 = MergedDOMTreeNode(
            nodeId = 11,
            nodeName = "A",
            isVisible = true,
            isInteractable = true,
            elementHash = "btn2"
        )
        val root = MergedDOMTreeNode(
            nodeId = 9,
            nodeName = "DIV",
            isVisible = true,
            children = listOf(btn1, btn2)
        )

        val tree = OptimizedDOMTreeBuilder(root, enableBBoxFiltering = false).build()
        assertNotNull(tree)

        // Serialize to build selector map with index:* entries
        val state = DOMStateBuilder.build(tree!!)
        val keys = state.selectorMap.keys
        assertTrue(keys.any { it == "index:1" }, "Selector map should include index:1")
        assertTrue(keys.any { it == "index:2" }, "Selector map should include index:2")
    }

    @Test
        @DisplayName("optimizeTree prunes invisible non-scrollable parent with all children pruned")
    fun optimizetreePrunesInvisibleNonScrollableParentWithAllChildrenPruned() {
        // Root visible container
        val root = MergedDOMTreeNode(
            nodeId = 1,
            nodeName = "DIV",
            isVisible = true
        )
        // Invisible, non-scrollable parent with one child text that will be pruned (no snapshot, not visible)
        val invisibleParent = MergedDOMTreeNode(
            nodeId = 2,
            nodeName = "DIV",
            isVisible = false,
            isScrollable = false,
            children = listOf(
                MergedDOMTreeNode(
                    nodeId = 3,
                    nodeType = NodeType.TEXT_NODE,
                    nodeName = "#text",
                    nodeValue = " ", // trivial text
                    isVisible = false,
                    snapshotNode = null
                )
            )
        )
        val rootWithChild = root.copy(children = listOf(invisibleParent))

        val tree = OptimizedDOMTreeBuilder(rootWithChild, enableBBoxFiltering = false).build()
        assertNotNull(tree)
        // After createSimplifiedTree, the text child is pruned; then optimizeTree should drop the invisible parent
        val child = tree!!.children.firstOrNull()
        assertNull(child, "Invisible non-scrollable parent with no kept children should be pruned by optimizeTree")
    }
}
