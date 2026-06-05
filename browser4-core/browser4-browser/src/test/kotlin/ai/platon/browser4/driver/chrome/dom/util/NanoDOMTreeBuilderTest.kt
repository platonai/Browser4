package ai.platon.browser4.driver.chrome.dom.util

import ai.platon.browser4.chrome.dom.util.NanoDOMTreeBuilder
import ai.platon.pulsar.chrome.dom.model.CleanedDOMTreeNode
import ai.platon.pulsar.chrome.dom.model.CompactRect
import ai.platon.pulsar.chrome.dom.model.NodeType
import ai.platon.pulsar.chrome.dom.model.SerializableDOMTreeNode
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class NanoDOMTreeBuilderTest {
    @Test
    @DisplayName("toNanoTreeInRange includes overlapping leaf nodes")
    fun buildIncludesOverlappingLeafNodes() {
        val root = microNode(
            id = 1,
            y = 0.0,
            height = 200.0,
            children = listOf(
                microNode(id = 2, y = 5.0, height = 10.0),
                microNode(id = 3, y = 45.0, height = 20.0),
                microNode(id = 4, y = 90.0, height = 10.0)
            )
        )

        val nanoTree = root.toNanoTreeInRange(40.0, 70.0)

        val children = nanoTree.children
        assertNotNull(children, "Overlapping descendants should be preserved")
        assertEquals(listOf("0,3"), children!!.mapNotNull { it.locator })
        assertEquals(45.0, children.single().bounds?.y)
    }

    @Test
    @DisplayName("toNanoTreeInRange uses open-start closed-end semantics for point nodes")
    fun buildUsesOpenStartClosedEndSemanticsForPointNodes() {
        val root = microNode(
            id = 1,
            y = 0.0,
            height = 200.0,
            children = listOf(
                microNode(id = 2, y = 50.0, height = 0.0),
                microNode(id = 3, y = 60.0, height = 0.0)
            )
        )

        val nanoTree = root.toNanoTreeInRange(50.0, 60.0)

        val children = nanoTree.children
        assertNotNull(children, "A point on the closed end boundary should be included")
        assertEquals(listOf("0,3"), children!!.mapNotNull { it.locator })
    }

    @Test
    @DisplayName("toNanoTreeInRange merges nearby chunks and clamps negative starts")
    fun buildMergesNearbyChunksAndClampsNegativeStarts() {
        val root = microNode(
            id = 1,
            y = 0.0,
            height = 300.0,
            children = listOf(
                microNode(
                    id = 10,
                    y = 0.0,
                    height = 100.0,
                    children = listOf(microNode(id = 11, y = -10.0, height = 20.0))
                ),
                microNode(
                    id = 20,
                    y = 0.0,
                    height = 100.0,
                    children = listOf(microNode(id = 21, y = 35.0, height = 15.0))
                )
            )
        )
        val seenChunks = mutableListOf<Pair<Double, Double>>()
        val helper = NanoDOMTreeBuilder(root, seenChunks)

        val nanoTree = helper.build(-20.0, 80.0)

        assertEquals(listOf("0,10", "0,20"), nanoTree.children!!.mapNotNull { it.locator })
        assertEquals(listOf(0.0 to 35.0), seenChunks)
    }

    @Test
    @DisplayName("toNanoTreeInRange filters all descendants for invalid intervals")
    fun buildFiltersAllDescendantsForInvalidIntervals() {
        val root = microNode(
            id = 1,
            y = 0.0,
            height = 200.0,
            children = listOf(microNode(id = 2, y = 10.0, height = 20.0))
        )

        val nanoTree = root.toNanoTreeInRange(25.0, 25.0)

        assertNull(nanoTree.children, "Invalid intervals should not include descendants")
        assertEquals("0,1", nanoTree.locator)
    }

    @Test
    @DisplayName("toNanoTreeInRange canonical full range matches unfiltered output")
    fun buildMatchesUnfilteredOutput() {
        val root = microNode(
            id = 1,
            y = 0.0,
            height = 200.0,
            children = listOf(
                microNode(id = 2),
                microNode(id = 3, y = 0.0, height = 0.0),
                microNode(id = 4, y = 1_000_100.0, height = 10.0)
            )
        )

        val unfilteredNanoTree = root.toNanoTreeUnfiltered()
        val fullRangeNanoTree = root.toNanoTreeInRange(0.0, 1_000_000.0)

        assertEquals(unfilteredNanoTree, fullRangeNanoTree)
        assertEquals(unfilteredNanoTree, root.toNanoTree())
        assertEquals(listOf("0,2", "0,3", "0,4"), fullRangeNanoTree.children!!.mapNotNull { it.locator })
    }

    private fun microNode(
        id: Int,
        nodeName: String = "div",
        y: Double? = null,
        height: Double? = null,
        children: List<SerializableDOMTreeNode>? = null
    ): SerializableDOMTreeNode {
        val bounds = if (y == null && height == null) null else CompactRect(x = 0.0, y = y, width = 100.0, height = height)

        return SerializableDOMTreeNode(
            originalNode = cleanedNode(id = id, nodeName = nodeName, bounds = bounds),
            children = children
        )
    }

    private fun cleanedNode(
        id: Int,
        nodeName: String,
        bounds: CompactRect?
    ): CleanedDOMTreeNode {
        return CleanedDOMTreeNode(
            locator = "0,$id",
            frameId = "0",
            xpath = null,
            elementHash = "hash-$id",
            nodeId = id,
            backendNodeId = id,
            nodeType = NodeType.ELEMENT_NODE.value,
            nodeName = nodeName,
            nodeValue = null,
            attributes = null,
            sessionId = null,
            isScrollable = null,
            isVisible = true,
            isInteractable = null,
            interactiveIndex = null,
            clientRects = null,
            scrollRects = null,
            bounds = bounds,
            absoluteBounds = bounds,
            viewportIndex = 1,
            paintOrder = null,
            stackingContexts = null,
            contentDocument = null
        )
    }
}
