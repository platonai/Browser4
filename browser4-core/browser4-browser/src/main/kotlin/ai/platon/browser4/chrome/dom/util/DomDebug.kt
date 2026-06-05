package ai.platon.browser4.chrome.dom.util

import ai.platon.browser4.chrome.dom.DOMSerializer
import ai.platon.pulsar.chrome.dom.model.*
import java.util.*
import kotlin.math.round

/**
 * Debugging helpers to summarize core DOM types for quick inspection.
 */
object DomDebug {
    // Basic stats for a tree
    data class TreeStats(
        val depth: Int,
        val nodes: Int,
        val leaves: Int
    ) {
        override fun toString(): String = "depth=$depth, nodes=$nodes, leaves=$leaves"
    }

    // Bounds stats for a tree
    data class BoundsStats(
        val zero: Int,
        val positive: Int,
        val missing: Int
    ) {
        override fun toString(): String = "zero=$zero, positive=$positive, missing=$missing"
    }

    // ----- Helpers -----

    private fun rectOf(n: MergedDOMTreeNode): DOMRect? {
        val s = n.snapshotNode
        return s?.clientRects ?: s?.absoluteBounds ?: s?.bounds ?: n.absolutePosition
    }

    private fun labelOfNode(n: MergedDOMTreeNode): String {
        val attrs = n.attributes
        val id = attrs["id"]?.let { "#${it}" } ?: ""
        val klass = attrs["class"]?.let { "." + it.split(Regex("\\s+")).take(2).joinToString(".") } ?: ""
        val name = n.nodeName.ifBlank { "?" }
        return (name + id + klass).trim()
    }

    private fun labelOfNano(n: NanoDOMTree): String {
        return buildString {
            val name = n.nodeName ?: "?"
            append(name)
            val id = (n.attributes?.get("id") as? String)?.takeIf { it.isNotBlank() }
            if (!id.isNullOrBlank()) append("#").append(id)
            val cls = (n.attributes?.get("class") as? String)
                ?.trim()?.split(Regex("\\s+")).orEmpty().take(2)
                .filter { it.isNotBlank() }
            if (cls.isNotEmpty()) append(".").append(cls.joinToString("."))
        }
    }

    private fun briefWithBounds(n: MergedDOMTreeNode): String {
        val r = rectOf(n)
        val hashShort = n.elementHash?.take(12)
        return "nodeId=${n.nodeId}, name=${n.nodeName}, label=${labelOfNode(n)}, hash=${hashShort}, bounds=${r}"
    }

    private fun briefWithBounds(n: OptimizedDOMTreeNode): String {
        val o = n.originalNode
        val r = rectOf(o)
        val hashShort = o.elementHash?.take(12)
        return "nodeId=${o.nodeId}, name=${o.nodeName}, label=${labelOfNode(o)}, hash=${hashShort}, bounds=${r}"
    }

    private fun briefWithBounds(n: NanoDOMTree): String {
        val r = n.clientRects ?: n.absoluteBounds ?: n.bounds
        return "locator=${n.locator}, label=${labelOfNano(n)}, bounds=${r}"
    }

    private fun midTwoWithBounds(root: MergedDOMTreeNode): Pair<MergedDOMTreeNode?, MergedDOMTreeNode?> {
        val eligible = mutableListOf<MergedDOMTreeNode>()
        fun dfs(n: MergedDOMTreeNode) {
            val r = rectOf(n)
            if (hasNonZeroXY(r)) eligible += n
            n.children.forEach { dfs(it) }
            n.shadowRoots.forEach { dfs(it) }
            n.contentDocument?.let { dfs(it) }
        }
        dfs(root)
        return middleTwo(eligible)
    }

    private fun midTwoWithBounds(root: OptimizedDOMTreeNode): Pair<OptimizedDOMTreeNode?, OptimizedDOMTreeNode?> {
        val eligible = mutableListOf<OptimizedDOMTreeNode>()
        fun dfs(n: OptimizedDOMTreeNode) {
            val r = rectOf(n.originalNode)
            if (hasNonZeroXY(r)) eligible += n
            n.children.forEach { dfs(it) }
        }
        dfs(root)
        return middleTwo(eligible)
    }

    private fun midTwoWithBounds(root: NanoDOMTree): Pair<NanoDOMTree?, NanoDOMTree?> {
        val eligible = mutableListOf<NanoDOMTree>()
        fun dfs(n: NanoDOMTree?) {
            if (n == null) return
            val r = n.clientRects ?: n.absoluteBounds ?: n.bounds
            if (hasNonZeroXY(r)) eligible += n
            n.children?.forEach { dfs(it) }
        }
        dfs(root)
        return middleTwo(eligible)
    }

    // ----- Stats calculators -----

    fun stats(root: MergedDOMTreeNode): TreeStats {
        var maxDepth = 0
        var count = 0
        var leaves = 0
        fun dfs(n: MergedDOMTreeNode, d: Int) {
            count++
            if (d > maxDepth) maxDepth = d
            val totalChildren = n.children.size + n.shadowRoots.size + if (n.contentDocument != null) 1 else 0
            if (totalChildren == 0) leaves++
            n.children.forEach { dfs(it, d + 1) }
            n.shadowRoots.forEach { dfs(it, d + 1) }
            n.contentDocument?.let { dfs(it, d + 1) }
        }
        dfs(root, 1)
        return TreeStats(maxDepth, count, leaves)
    }

    fun stats(root: OptimizedDOMTreeNode): TreeStats {
        var maxDepth = 0
        var count = 0
        var leaves = 0
        fun dfs(n: OptimizedDOMTreeNode, d: Int) {
            count++
            if (d > maxDepth) maxDepth = d
            if (n.children.isEmpty()) leaves++
            n.children.forEach { dfs(it, d + 1) }
        }
        dfs(root, 1)
        return TreeStats(maxDepth, count, leaves)
    }

    // New: stats for NanoDOMTree
    fun stats(root: NanoDOMTree): TreeStats {
        var maxDepth = 0
        var count = 0
        var leaves = 0
        fun dfs(n: NanoDOMTree?, d: Int) {
            if (n == null) return
            count++
            if (d > maxDepth) maxDepth = d
            val children = n.children ?: emptyList()
            if (children.isEmpty()) leaves++
            children.forEach { dfs(it, d + 1) }
        }
        dfs(root, 1)
        return TreeStats(maxDepth, count, leaves)
    }

    // ----- Bounds stats calculators -----

    fun boundsStats(root: MergedDOMTreeNode): BoundsStats {
        var zero = 0
        var positive = 0
        var missing = 0
        fun dfs(n: MergedDOMTreeNode) {
            val r = rectOf(n)
            if (r == null) missing++
            else if (r.width > 0 && r.height > 0) positive++
            else zero++
            n.children.forEach { dfs(it) }
            n.shadowRoots.forEach { dfs(it) }
            n.contentDocument?.let { dfs(it) }
        }
        dfs(root)
        return BoundsStats(zero, positive, missing)
    }

    fun boundsStats(root: OptimizedDOMTreeNode): BoundsStats {
        var zero = 0
        var positive = 0
        var missing = 0
        fun dfs(n: OptimizedDOMTreeNode) {
            val o = n.originalNode
            val r = rectOf(o)
            if (r == null) missing++
            else if (r.width > 0 && r.height > 0) positive++
            else zero++
            n.children.forEach { dfs(it) }
        }
        dfs(root)
        return BoundsStats(zero, positive, missing)
    }

    // New: bounds stats for NanoDOMTree using CompactRect precedence clientRects -> absoluteBounds -> bounds
    fun boundsStats(root: NanoDOMTree): BoundsStats {
        var zero = 0
        var positive = 0
        var missing = 0
        fun toNonZero(w: Double?): Double = w ?: 0.0
        fun dfs(n: NanoDOMTree?) {
            if (n == null) return
            val r = n.clientRects ?: n.absoluteBounds ?: n.bounds
            if (r == null) {
                missing++
            } else {
                val w = toNonZero(r.width)
                val h = toNonZero(r.height)
                if (w > 0.0 && h > 0.0) positive++ else zero++
            }
            n.children?.forEach { dfs(it) }
        }
        dfs(root)
        return BoundsStats(zero, positive, missing)
    }

    // ----- Helpers (added) -----
    private fun hasNonZeroXY(r: DOMRect?): Boolean = r?.let { it.x != 0.0 || it.y != 0.0 } == true
    private fun hasNonZeroXY(r: CompactRect?): Boolean = r?.let { (it.x ?: 0.0) != 0.0 || (it.y ?: 0.0) != 0.0 } == true

    private fun <T> middleTwo(list: List<T>): Pair<T?, T?> {
        if (list.isEmpty()) return Pair(null, null)
        if (list.size == 1) return Pair(list[0], null)
        val j = (list.size - 1) / 2
        val k = minOf(j + 1, list.size - 1)
        return Pair(list[j], list[k])
    }

    // ----- Bounds sampling helpers -----

    private fun xyOf(r: DOMRect?): Pair<Double, Double>? = r?.let { it.x to it.y }
    private fun xyOf(r: CompactRect?): Pair<Double, Double>? = r?.let { (it.x ?: 0.0) to (it.y ?: 0.0) }

    private fun round1(v: Double): Double = round(v * 10.0) / 10.0

    private fun formatXYList(pairs: List<Pair<Double, Double>>, limit: Int = 20): String {
        if (pairs.isEmpty()) return "[]"
        val head = pairs.take(limit)
        val body = head.joinToString(prefix = "[", postfix = "]") { p -> "(${round1(p.first)},${round1(p.second)})" }
        val remain = pairs.size - head.size
        return if (remain > 0) "$body +${remain} more" else body
    }

    private fun collectXY(root: MergedDOMTreeNode): List<Pair<Double, Double>> {
        val list = mutableListOf<Pair<Double, Double>>()
        fun dfs(n: MergedDOMTreeNode) {
            xyOf(rectOf(n))?.let { list += it }
            n.children.forEach { dfs(it) }
            n.shadowRoots.forEach { dfs(it) }
            n.contentDocument?.let { dfs(it) }
        }
        dfs(root)
        return list
    }

    private fun collectXY(root: OptimizedDOMTreeNode): List<Pair<Double, Double>> {
        val list = mutableListOf<Pair<Double, Double>>()
        fun dfs(n: OptimizedDOMTreeNode) {
            xyOf(rectOf(n.originalNode))?.let { list += it }
            n.children.forEach { dfs(it) }
        }
        dfs(root)
        return list
    }

    private fun collectXY(root: NanoDOMTree): List<Pair<Double, Double>> {
        val list = mutableListOf<Pair<Double, Double>>()
        fun dfs(n: NanoDOMTree?) {
            if (n == null) return
            xyOf(n.clientRects ?: n.absoluteBounds ?: n.bounds)?.let { list += it }
            n.children?.forEach { dfs(it) }
        }
        dfs(root)
        return list
    }

    private fun filterGt(pairs: List<Pair<Double, Double>>, threshold: Double): List<Pair<Double, Double>> =
        pairs.filter { it.first > threshold && it.second > threshold }

    private fun sortedGt50(pairs: List<Pair<Double, Double>>): List<Pair<Double, Double>> =
        filterGt(pairs, 50.0).sortedWith(compareBy({ it.first }, { it.second }))

    // ----- Summaries -----

    // Helpers to convert nodes to map structures for summaries
    fun toMidNodeMap(n: MergedDOMTreeNode): Map<String, Any?> = linkedMapOf(
        "nodeId" to n.nodeId,
        "backendNodeId" to n.backendNodeId,
        "nodeType" to n.nodeType,
        "name" to n.nodeName,
        "label" to labelOfNode(n),
        "hash" to n.elementHash?.take(12),
        "bounds" to (n.snapshotNode?.clientRects ?: n.absolutePosition),
    )

    fun toMidNodeMap(n: OptimizedDOMTreeNode): Map<String, Any?> = toMidNodeMap(n.originalNode)
    fun toMidNodeMap(n: NanoDOMTree): Map<String, Any?> = linkedMapOf(
        "locator" to n.locator,
        "label" to labelOfNano(n),
        "bounds" to (n.clientRects ?: n.absoluteBounds ?: n.bounds)
    )

    fun summarize(trees: TargetTrees): Map<String, Any?> {
        val s = stats(trees.domTree)
        val b = boundsStats(trees.domTree)
        val (mid1, mid2) = midTwoWithBounds(trees.domTree)
        val xyAll = collectXY(trees.domTree)
        val gt200 = filterGt(xyAll, 200.0)
        val gt50Sorted = sortedGt50(xyAll)
        return linkedMapOf(
            "type" to "TargetTrees",
            "devicePixelRatio" to trees.devicePixelRatio,
            "timingsMs" to trees.cdpTiming,
            "options" to trees.options.toString(),
            "axTree.size" to trees.axTree.size,
            "snapshotByBackendId.size" to trees.snapshotByBackendId.size,
            "domByBackendId.size" to trees.domByBackendId.size,
            "domTree.stats" to s,
            "domTree.boundsStats" to b,
            "domTree.bounds.zeroNonZero" to mapOf("zero" to b.zero, "nonZero" to b.positive),
            "domTree.midBoundsNode1" to (mid1?.let { toMidNodeMap(it) }),
            "domTree.midBoundsNode2" to (mid2?.takeIf { it !== mid1 }?.let { toMidNodeMap(it) }),
            "bounds.samples.gt200.count" to gt200.size,
            "bounds.coords.gt50.sorted" to gt50Sorted
        )
    }

    fun summarize(node: MergedDOMTreeNode, includeTreeStats: Boolean = true): Map<String, Any?> {
        val attrs = node.attributes
        val id = attrs["id"]?.let { "#${it}" } ?: ""
        val klass = attrs["class"]?.let { "." + it.split(Regex("\\s+")).take(2).joinToString(".") } ?: ""
        val label = (node.nodeName.ifBlank { "?" } + id + klass).trim()
        val hashShort = node.elementHash?.take(12)
        val xPathShort = node.xpath?.takeLast(40)
        val counts = if (includeTreeStats) stats(node) else null
        val b = if (includeTreeStats) boundsStats(node) else null
        val midPair = if (includeTreeStats) midTwoWithBounds(node) else null
        val xyAll = collectXY(node)
        val gt200 = filterGt(xyAll, 200.0)
        val gt50Sorted = sortedGt50(xyAll)
        val (mid1, mid2) = midPair ?: Pair(null, null)
        return linkedMapOf(
            "type" to "MergedDOMTree",
            "nodeId" to node.nodeId,
            "backendNodeId" to node.backendNodeId,
            "nodeType" to node.nodeType,
            "name" to node.nodeName,
            "label" to label,
            "xPath" to xPathShort,
            "elementHash" to hashShort,
            "scrollable" to node.isScrollable,
            "visible" to node.isVisible,
            "interactable" to node.isInteractable,
            "interactiveIndex" to node.interactiveIndex,
            "bounds" to (node.snapshotNode?.clientRects ?: node.absolutePosition),
            "stats" to counts,
            "boundsStats" to b,
            "bounds.zeroNonZero" to (b?.let { mapOf("zero" to it.zero, "nonZero" to it.positive) }),
            "midBoundsNode1" to (mid1?.let { toMidNodeMap(it) }),
            "midBoundsNode2" to (mid2?.takeIf { it !== mid1 }?.let { toMidNodeMap(it) }),
            "bounds.samples.gt200.count" to gt200.size,
            "bounds.coords.gt50.sorted" to gt50Sorted
        )
    }

    fun summarize(el: DOMInteractedElement): Map<String, Any?> {
        val hashShort = el.elementHash.take(12)
        val xPathShort = el.xpath?.takeLast(60)
        val xy = xyOf(el.bounds)
        val gt200 = if (xy != null && xy.first > 200.0 && xy.second > 200.0) 1 else 0
        val gt50Sorted = if (xy != null && xy.first > 50.0 && xy.second > 50.0) listOf(xy) else emptyList()
        return linkedMapOf(
            "type" to "DOMInteractedElement",
            "elementHash" to hashShort,
            "xPath" to xPathShort,
            "visible" to el.isVisible,
            "interactable" to el.isInteractable,
            "bounds" to el.bounds,
            "bounds.samples.gt200.count" to gt200,
            "bounds.coords.gt50.sorted" to gt50Sorted
        )
    }

    fun summarize(root: OptimizedDOMTreeNode): Map<String, Any?> {
        val s = stats(root)
        val b = boundsStats(root)
        val original = root.originalNode
        val hashShort = original.elementHash?.take(12)
        val (mid1, mid2) = midTwoWithBounds(root)
        val xyAll = collectXY(root)
        val gt200 = filterGt(xyAll, 200.0)
        val gt50Sorted = sortedGt50(xyAll)
        return linkedMapOf(
            "type" to "TinyNode",
            "from.nodeId" to original.nodeId,
            "from.name" to original.nodeName,
            "from.hash" to hashShort,
            "shouldDisplay" to root.shouldDisplay,
            "interactiveIndex" to root.interactiveIndex,
            "stats" to s,
            "boundsStats" to b,
            "bounds.zeroNonZero" to mapOf("zero" to b.zero, "nonZero" to b.positive),
            "midBoundsNode1" to (mid1?.let { toMidNodeMap(it) }),
            "midBoundsNode2" to (mid2?.takeIf { it !== mid1 }?.let { toMidNodeMap(it) }),
            "bounds.samples.gt200.count" to gt200.size,
            "bounds.coords.gt50.sorted" to gt50Sorted
        )
    }

    fun summarize(root: NanoDOMTree): Map<String, Any?> {
        val s = stats(root)
        val b = boundsStats(root)
        val label = labelOfNano(root)
        val (mid1, mid2) = midTwoWithBounds(root)
        val xyAll = collectXY(root)
        val gt200 = filterGt(xyAll, 200.0)
        val gt50Sorted = sortedGt50(xyAll)
        return linkedMapOf(
            "type" to "NanoDOMTree",
            "locator" to root.locator,
            "label" to label,
            "scrollable" to root.scrollable,
            "interactive" to root.interactive,
            "invisible" to root.invisible,
            "bounds" to (root.clientRects ?: root.absoluteBounds ?: root.bounds)?.roundTo(1),
            "stats" to s,
            "boundsStats" to b,
            "bounds.zeroNonZero" to mapOf("zero" to b.zero, "nonZero" to b.positive),
            "midBoundsNode1" to (mid1?.let { toMidNodeMap(it) }),
            "midBoundsNode2" to (mid2?.takeIf { it !== mid1 }?.let { toMidNodeMap(it) }),
            "bounds.samples.gt200.count" to gt200.size,
            "bounds.coords.gt50.sorted" to gt50Sorted
        )
    }

    fun summarize(snapshotNode: Map<Int, SnapshotNodeEx>): Map<String, Any?> {
        var zero = 0
        var positive = 0
        var missing = 0
        var withClientRects = 0
        var withAbsolute = 0
        var withBounds = 0
        val eligible = mutableListOf<Map.Entry<Int, SnapshotNodeEx>>()
        val xyAll = mutableListOf<Pair<Double, Double>>()
        snapshotNode.entries.forEach { (k, s) ->
            val r = s.clientRects ?: s.absoluteBounds ?: s.bounds
            if (s.clientRects != null) withClientRects++
            if (s.absoluteBounds != null) withAbsolute++
            if (s.bounds != null) withBounds++
            xyOf(r)?.let { xyAll += it }
            when {
                r == null -> missing++
                r.width > 0 && r.height > 0 -> {
                    positive++
                    if (hasNonZeroXY(r)) eligible += AbstractMap.SimpleEntry(k, s)
                }

                else -> {
                    zero++
                    if (hasNonZeroXY(r)) eligible += AbstractMap.SimpleEntry(k, s)
                }
            }
        }
        fun briefMap(entry: Map.Entry<Int, SnapshotNodeEx>): Map<String, Any?> {
            val s = entry.value
            val source = when {
                s.clientRects != null -> "clientRects"
                s.absoluteBounds != null -> "absoluteBounds"
                s.bounds != null -> "bounds"
                else -> "none"
            }
            val r = s.clientRects ?: s.absoluteBounds ?: s.bounds
            return linkedMapOf(
                "key" to entry.key,
                "source" to source,
                "rect" to r
            )
        }
        val (mid1, mid2) = middleTwo(eligible)
        val gt200 = filterGt(xyAll, 200.0)
        val gt50Sorted = sortedGt50(xyAll)
        return linkedMapOf(
            "type" to "SnapshotNodeExMap",
            "entries" to snapshotNode.size,
            "boundsStats" to mapOf("zero" to zero, "positive" to positive, "missing" to missing),
            "with" to mapOf(
                "clientRects" to withClientRects,
                "absoluteBounds" to withAbsolute,
                "bounds" to withBounds
            ),
            "bounds.zeroNonZero" to mapOf("zero" to zero, "nonZero" to positive),
            "midBoundsEntry1" to (mid1?.let { briefMap(it) }),
            "midBoundsEntry2" to (mid2?.takeIf { it.key != mid1?.key }?.let { briefMap(it) }),
            "bounds.samples.gt200.count" to gt200.size,
            "bounds.coords.gt50.sorted" to gt50Sorted
        )
    }

    fun summarizeStr(state: DOMState, maxItems: Int = 100): String {
        val map = summarize(state, maxItems).toMutableMap()
        return map.toString()
    }

    fun summarize(state: DOMState, maxItems: Int = 100): Map<String, Any?> {
        val totalEntries = state.selectorMap.size
        val keys = state.selectorMap.keys
        val xpathKeys = keys.count { it.startsWith("xpath:") }
        val backendKeys = keys.count { it.startsWith("backend:") }
        val nodeKeys = keys.count { it.startsWith("node:") }
        val hashKeys = totalEntries - xpathKeys - backendKeys - nodeKeys
        val uniqueNodes = state.selectorMap.values.distinctBy { it.nodeId }
        val uniqueCount = uniqueNodes.size
        val interactable = uniqueNodes.count { it.isInteractable == true }
        val visible = uniqueNodes.count { it.isVisible == true }
        val scrollable = uniqueNodes.count { it.isScrollable == true }
        val withXPath = uniqueNodes.count { !it.xpath.isNullOrBlank() }
        val withHash = uniqueNodes.count { !it.elementHash.isNullOrBlank() }
        val withSnapshot = uniqueNodes.count { it.snapshotNode != null }
        val withBounds = uniqueNodes.count { it.snapshotNode?.clientRects != null || it.snapshotNode?.absoluteBounds != null || it.absolutePosition != null }
        fun labelOf(n: MergedDOMTreeNode): String {
            val attrs = n.attributes
            val id = attrs["id"]?.let { "#${it}" } ?: ""
            val klass = attrs["class"]?.let { "." + it.split(Regex("\\s+")).take(2).joinToString(".") } ?: ""
            val name = n.nodeName.ifBlank { "?" }
            return (name + id + klass).trim()
        }
        val sample = uniqueNodes.take(3).map { labelOf(it) }
        val json = DOMSerializer.toJson(state.serializableTree)
        val eligible = uniqueNodes.filter { hasNonZeroXY(rectOf(it)) }
        val (mid1, mid2) = middleTwo(eligible)
        val xyAll = uniqueNodes.mapNotNull { xyOf(rectOf(it)) }
        val gt200 = filterGt(xyAll, 200.0).take(maxItems)
        val gt50Sorted = sortedGt50(xyAll).take(maxItems)
        return linkedMapOf(
            "type" to "DOMState",
            "json.length" to json.length,
            "selectorMap.entries" to totalEntries,
            "selectorMap.uniqueNodes" to uniqueCount,
            "selectorMap.keyBreakdown" to mapOf(
                "hashKeys" to hashKeys,
                "xpathKeys" to xpathKeys,
                "backendKeys" to backendKeys,
                "nodeKeys" to nodeKeys
            ),
            "nodes" to mapOf(
                "interactable" to interactable,
                "visible" to visible,
                "scrollable" to scrollable,
                "withXPath" to withXPath,
                "withHash" to withHash,
                "withSnapshot" to withSnapshot,
                "withBounds" to withBounds
            ),
            "sample" to sample,
            "midBoundsNode1" to (mid1?.let { toMidNodeMap(it) }),
            "midBoundsNode2" to (mid2?.takeIf { it !== mid1 }?.let { toMidNodeMap(it) }),
            "bounds.samples.gt200.count" to gt200.size,
            "bounds.coords.gt50.sorted" to gt50Sorted
        )
    }
}
