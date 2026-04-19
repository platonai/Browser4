package ai.platon.browser4.driver.chrome.dom.util

import ai.platon.browser4.driver.chrome.dom.model.*
import ai.platon.pulsar.common.getLogger
import ai.platon.pulsar.common.math.roundTo
import kotlin.math.max

class NanoDOMTreeBuilder constructor(
    private val serializableTree: SerializableDOMTreeNode,
    private val seenChunks: MutableList<Pair<Double, Double>>,
) {
    companion object {
        private const val CANONICAL_FULL_TREE_END_Y = 1_000_000.0
    }

    private val logger = getLogger(this)

    private var numNodes = 0

    fun build(startY: Double = 0.0, endY: Double = CANONICAL_FULL_TREE_END_Y): NanoDOMTree {
        if (startY <= 0.0 && endY >= CANONICAL_FULL_TREE_END_Y) {
            return buildUnfiltered()
        }

        val tree = buildInRangeRecursive(serializableTree, startY, endY)

        if (seenChunks.size > 1) {
            val merged = mergeChunks()
            seenChunks.clear()
            merged.map { max(0.0, it.first) to max(0.0, it.second) }.toCollection(seenChunks)
        }

        val y1 = startY.roundTo(1)
        val y2 = endY.roundTo(1)
        logger.info("""📤 Nano-tree generated | nodes: $numNodes | Y-axis: ($y1, $y2] | seen chunks: $seenChunks""")

        return tree
    }

    fun buildInRangeRecursive(
        microNode: SerializableDOMTreeNode,
        startY: Double = 0.0,
        endY: Double = CANONICAL_FULL_TREE_END_Y
    ): NanoDOMTree {
        // Create the current node from the micro node
        val root = newNode(microNode) ?: return NanoDOMTree()

        // Recursively create child nano nodes, filter out empty placeholders
        val childNanoList = microNode.children
            ?.asSequence()
            ?.filter { inYRange(it, startY, endY) }
            ?.mapNotNull { child ->
                buildInRangeRecursive(child, startY, endY)
                    .takeUnless { it == NanoDOMTree() }
            }
            ?.toList()

        return if (childNanoList.isNullOrEmpty()) {
            root
        } else {
            val y1 = childNanoList.minOf { it.bounds?.y ?: 100000.0 } // remove nulls
            val y2 = childNanoList.maxOf { it.bounds?.y ?: -100000.0 } // remove nulls

            seenChunks.add(Pair(y1, y2))
            numNodes++
            root.copy(children = childNanoList)
        }
    }

    fun buildUnfiltered(): NanoDOMTree {
        return buildUnfilteredRecursive(serializableTree)
    }

    private fun buildUnfilteredRecursive(microNode: SerializableDOMTreeNode): NanoDOMTree {
        // Create the current node from the micro node
        val root = newNode(microNode) ?: return NanoDOMTree()

        // Recursively create child nano nodes, filter out empty placeholders
        val childNanoList = microNode.children?.map { buildUnfilteredRecursive(it) }

        return if (childNanoList.isNullOrEmpty()) {
            root
        } else {
            root.copy(children = childNanoList)
        }
    }

    private fun newNode(n: SerializableDOMTreeNode?): NanoDOMTree? {
        val o = n?.originalNode ?: return null

        // remove locator's prefix to reduce serialized size
        return NanoDOMTree(
            o.locator.substringAfterLast(":"),
            o.nodeName,
            o.nodeValue,
            o.attributes,
            scrollable = o.isScrollable,
            interactive = o.isInteractable,
            // All nodes are visible unless `invisible` == true explicitly.
            invisible = if (o.isVisible == true) null else true,
            clientRects = o.clientRects?.round(),
            scrollRects = o.scrollRects?.round(),
            bounds = o.bounds?.round(),
            absoluteBounds = o.absoluteBounds?.round(),
            viewportIndex = o.viewportIndex,
            serializableTreeNode = n,
        )
    }

    /**
     * Returns true if any portion of the node lies within the vertical interval (startY, endY],
     * where startY and endY are coordinates along the page’s y-axis.
     * */
    fun inYRange(no: SerializableDOMTreeNode, startY: Double, endY: Double): Boolean {
        // Invalid interval: (startY, endY] must have start < end
        if (startY.isNaN() || endY.isNaN() || startY >= endY) return false

        val o = no.originalNode ?: return false
        // Prefer absolute page coordinates first, then bounds, then client rects
        val r = (o.absoluteBounds ?: o.bounds ?: o.clientRects)?.uncompact() ?: return false
        val y = r.y
        val h = r.height

        // If height is missing or non-positive, treat it as a point at y
        if (!(h > 0.0)) {
            // open at start, closed at end: (startY, endY]
            return y > startY && y <= endY
        }

        val top = y
        val bottom = y + h
        // Any overlap between [top, bottom] and (startY, endY]
        // Open at start (>) and closed at end (<=)
        return top <= endY && bottom > startY
    }

    fun mergeChunks(): List<Pair<Double, Double>> {
        // merge chunks in seenChunks that intersects
        if (seenChunks.isEmpty()) {
            return emptyList()
        }
        if (seenChunks.size == 1) {
            return seenChunks
        }

        val eps = 50
        // Normalize and sort by start
        val sorted = seenChunks
            .map { (s, e) -> if (s <= e) s to e else e to s }
            .sortedBy { it.first }

        val merged = ArrayList<Pair<Double, Double>>()
        var current = sorted[0]
        for (i in 1 until sorted.size) {
            val next = sorted[i]
            val (cStart, cEnd) = current
            val (nStart, nEnd) = next
            // Intersects or touches within epsilon
            if (nStart <= cEnd + eps) {
                // merge by extending the end
                current = cStart to maxOf(cEnd, nEnd)
            } else {
                merged += current
                current = next
            }
        }
        merged += current
        return merged
    }
}

