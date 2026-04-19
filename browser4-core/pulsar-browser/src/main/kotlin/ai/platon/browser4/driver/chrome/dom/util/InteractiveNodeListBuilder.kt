package ai.platon.browser4.driver.chrome.dom.util

import ai.platon.browser4.driver.chrome.dom.model.CleanedDOMTreeNode
import ai.platon.browser4.driver.chrome.dom.model.DefaultIncludeAttributes
import ai.platon.browser4.driver.chrome.dom.model.InteractiveDOMTreeNode
import ai.platon.browser4.driver.chrome.dom.model.InteractiveDOMTreeNodeList
import ai.platon.browser4.driver.chrome.dom.model.SerializableDOMTreeNode
import ai.platon.pulsar.common.Strings
import org.apache.commons.lang3.StringUtils
import kotlin.collections.plusAssign

class InteractiveNodeListBuilder(
    private val root: SerializableDOMTreeNode,
    private val includeAllViewports: Boolean = false,
    private val currentViewportIndex: Int = 1,
    private val lastViewportIndex: Int = 10000,
    private val maxNonInteractiveTextLength: Int = 100,
) {
    companion object {
        fun slimHTML(n: SerializableDOMTreeNode): String? {
            val o = n.originalNode ?: return null

            val nodeName = o.nodeName
            val nodeValue = Strings.compactWhitespaces(o.nodeValue)

            fun normalizeAttrValue(attrValue: Any?): String? {
                if (attrValue == null) return null
                val compacted = Strings.compactWhitespaces(attrValue.toString().trim())
                return Strings.singleQuoteIfNonAlphanumeric(compacted)
            }

            val attrs = o.attributes
                ?.mapNotNull { (it.key to normalizeAttrValue(it.value)) }
                ?.joinToString(" ", " ") { (k, v) -> "$k=$v" }
                ?: ""
            return if (nodeValue == null) {
                "<${nodeName}$attrs />"
            } else {
                """<${nodeName}$attrs>$nodeValue</${nodeName}>"""
            }
        }

        fun estimatedSize(n: InteractiveDOMTreeNode): Int {
            return "[0,812]{1}(369,1659,87,13)".length + (n.textBefore?.length ?: 0) + (n.slimHTML?.length ?: 0)
        }

        fun estimatedSize(nodes: List<InteractiveDOMTreeNode>): Int {
            return nodes.sumOf { estimatedSize(it) }
        }
    }

    fun build(): InteractiveDOMTreeNodeList {
        val collected = mutableListOf<InteractiveDOMTreeNode>()

        // Keep mapping from interactive index -> backing DOM node id
        val interactiveNodeIdByIndex = mutableMapOf<Int, Int>()
        // Collect non-interactive node texts with their nodeIds
        val nonInteractiveTexts = mutableListOf<Pair<Int, String>>()

        fun nonInteractiveText(o: CleanedDOMTreeNode): String? {
            val sb = StringBuilder()
            fun appendToken(v: Any?) {
                val t = v?.toString()?.trim()
                if (!t.isNullOrEmpty()) {
                    if (sb.isNotEmpty()) sb.append(' ')
                    sb.append(t)
                }
            }
            // Prefer nodeValue
            appendToken(o.nodeValue)
            // Include meaningful attributes if any
            val attrs = o.attributes
            if (!attrs.isNullOrEmpty()) {
                DefaultIncludeAttributes.ATTRIBUTES.forEach { key ->
                    attrs[key]?.let { appendToken(it) }
                }
            }
            val s = Strings.compactWhitespaces(sb.toString())
            return s.ifEmpty { null }
        }

        fun visit(n: SerializableDOMTreeNode) {
            val o = n.originalNode
            val idx = n.interactiveIndex
            if (o != null && idx != null) {
                collected += InteractiveDOMTreeNode(
                    interactiveIndex = idx,
                    // remove prefix to reduce serialized size, align with Nano tree
                    backendNodeId = o.locator.substringAfterLast(":").substringAfterLast(","),
                    slimHTML = slimHTML(n),
                    textBefore = null,
                    viewportIndex = o.viewportIndex,
                    scrollable = o.isScrollable?.takeIf { it },
                    // All nodes are visible unless `invisible` == true explicitly
                    invisible = if (o.isVisible == true) null else true,
                    bounds = o.bounds?.round(),
                    clientRects = o.clientRects?.round(),
                    scrollRects = o.scrollRects?.round(),
                    absoluteBounds = o.absoluteBounds?.round(),
                    prevInteractiveIndex = null,
                    nextInteractiveIndex = null,
                )
                interactiveNodeIdByIndex[idx] = o.nodeId
            } else if (o != null) {
                val order = o.paintOrder ?: o.backendNodeId ?: o.nodeId
                nonInteractiveText(o)?.let { nonInteractiveTexts += (order to it) }
            }
            n.children?.forEach { visit(it) }
        }

        visit(root)

        // Build a map from interactiveIndex -> concatenated text of non-interactive nodes
        val textBeforeByInteractiveIndex = mutableMapOf<Int, String?>()
        val sortedInteractiveIdx = interactiveNodeIdByIndex.keys.sorted()
        if (sortedInteractiveIdx.isNotEmpty()) {
            // Sort non-interactive entries by nodeId once for efficient slicing
            val nonInteractiveSorted = nonInteractiveTexts.sortedBy { it.first }
            for (i in 0 until sortedInteractiveIdx.size) {
                val currentIdx = sortedInteractiveIdx[i]
                val currentNodeId = interactiveNodeIdByIndex[currentIdx] ?: continue
                val nextIdx = sortedInteractiveIdx.getOrNull(i + 1)
                if (nextIdx == null) {
                    // No next interactive node -> leave null
                    textBeforeByInteractiveIndex[currentIdx] = null
                    continue
                }
                val nextNodeId = interactiveNodeIdByIndex[nextIdx] ?: continue
                val low = minOf(currentNodeId, nextNodeId)
                val high = maxOf(currentNodeId, nextNodeId)
                val texts = nonInteractiveSorted
                    .asSequence()
                    .filter { (nodeId, _) -> nodeId in (low + 1)..<high }
                    .map { it.second }
                    .filter { it.isNotBlank() }
                    .toList()
                val joined = Strings.compactWhitespaces(texts.joinToString(" "))
                textBeforeByInteractiveIndex[currentIdx] = joined.ifEmpty { null }
            }
        }

        // Sort by interactive index, then by locator for stability and fill prev/next/textUntilNextNode
        val sorted = collected.sortedWith(compareBy({ it.interactiveIndex }, { it.backendNodeId ?: "" }))
        val nodes = sorted.mapIndexed { i, it ->
            val prev = if (i > 0) sorted[i - 1].interactiveIndex else null
            val next = if (i < sorted.lastIndex) sorted[i + 1].interactiveIndex else null
            var textBefore = textBeforeByInteractiveIndex.getOrDefault(it.interactiveIndex - 1, null)
            textBefore = textBefore?.takeIf { it.isNotBlank() }
                ?.let { StringUtils.abbreviateMiddle(textBefore, "...", maxNonInteractiveTextLength) }
            it.copy(
                prevInteractiveIndex = prev,
                nextInteractiveIndex = next,
                textBefore = textBefore
            )
        }

        var shorterNodeList = if (!includeAllViewports) {
            val desiredViewports = mutableSetOf(1, 2, currentViewportIndex, lastViewportIndex)
            nodes.filter { (it.viewportIndex ?: -1) in desiredViewports }
        } else nodes

        fun goodSize() = estimatedSize(shorterNodeList) <= 100_000

        if (goodSize()) return InteractiveDOMTreeNodeList(shorterNodeList)

        shorterNodeList = shorterNodeList.map {
            it.copy(textBefore = StringUtils.abbreviateMiddle(it.textBefore ?: "", "...", 50))
        }
        if (goodSize()) return InteractiveDOMTreeNodeList(shorterNodeList)

        val discardViewportIndexes = listOf(2, lastViewportIndex, 1)
        discardViewportIndexes.forEach { discardViewportIndex ->
            shorterNodeList = shorterNodeList.filterNot { it.viewportIndex == discardViewportIndex }
            if (goodSize()) return InteractiveDOMTreeNodeList(shorterNodeList)
        }

        shorterNodeList = shorterNodeList.filterNot { it.isAnchor() }
        if (goodSize()) return InteractiveDOMTreeNodeList(shorterNodeList)

        return InteractiveDOMTreeNodeList(shorterNodeList)
    }
}
