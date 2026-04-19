package ai.platon.browser4.driver.chrome.dom

import ai.platon.cdt.kt.protocol.types.accessibility.AXNode
import ai.platon.cdt.kt.protocol.types.page.FrameTree
import ai.platon.browser4.driver.chrome.RemoteDevTools
import ai.platon.pulsar.common.getLogger

class AccessibilityHandler(
    private val devTools: RemoteDevTools
) {
    private val logger = getLogger(this)
    private val tracer get() = logger.takeIf { it.isTraceEnabled }

    private val isActive get() = devTools.isOpen
    private val pageAPI get() = devTools.page.takeIf { isActive }
    private val accessibilityAPI get() = devTools.accessibility.takeIf { isActive }

    @Volatile
    private var accessibilityEnabled = false

    /**
     * Recursively collects AX trees for the target frame or every frame/iframe reachable from the
     * current page. Each returned node is annotated with a frameId so that callers can associate
     * AX information with DOM/backend nodes across frames.
     */
    suspend fun getFullAXTree(targetFrameId: String? = null, depth: Int? = null): AccessibilityTreeResult {
        // Ensure the Accessibility domain is enabled
        ensureEnabled()

        val page = pageAPI ?: return AccessibilityTreeResult.EMPTY
        val accessibility = accessibilityAPI ?: return AccessibilityTreeResult.EMPTY

        // Small retry loop to wait for AX cache to populate on dynamic pages
        repeat(5) { attempt ->
            val frameTree = try {
                page.getFrameTree()
            } catch (e: Exception) {
                tracer?.debug("Page.getFrameTree failed, using last known tree | err={}", e.toString())
                page.getFrameTree()
            }

            val frameById = linkedMapOf<String, FrameTree>()
            collectFrameTree(frameTree, frameById)

            val frameIds: List<String> = when {
                targetFrameId == null -> frameById.keys.toList()
                frameById.containsKey(targetFrameId) -> listOf(targetFrameId)
                else -> emptyList()
            }

            if (frameIds.isEmpty()) {
                // Fallback: try fetching AX tree without specifying a frameId (root document)
                val nodes = runCatching { accessibility.getFullAXTree(depth) }.getOrElse { emptyList() }
                if (nodes.isNotEmpty()) {
                    val rootFrameId = frameTree.frame.id
                    return singleFrameResult(nodes, rootFrameId)
                }
                // If a specific target frame was requested, try that directly as well
                if (targetFrameId != null) {
                    val targeted = runCatching { accessibility.getFullAXTree(depth) }.getOrElse { emptyList() }
                    if (targeted.isNotEmpty()) return singleFrameResult(targeted, targetFrameId)
                }
                // Wait a bit and retry
                if (attempt < 4) Thread.sleep(250) else return AccessibilityTreeResult.EMPTY
            } else {
                val seenPairs = mutableSetOf<Pair<String?, String>>()
                val allNodes = mutableListOf<AXNode>()
                val byFrame = LinkedHashMap<String, MutableList<AXNode>>(frameIds.size)
                val byBackend = LinkedHashMap<Int, MutableList<AXNode>>()

                frameIds.forEach { frameId ->
                    val nodes = runCatching { accessibility.getFullAXTree(depth) }
                        .onFailure { e -> logger.warn("Accessibility.getFullAXTree failed | frameId={} err={}", frameId, e.toString()) }
                        .getOrElse { emptyList() }
                    if (nodes.isEmpty()) {
                        return@forEach
                    }
                    val frameBucket = byFrame.getOrPut(frameId) { mutableListOf() }
                    nodes.forEach { node ->
                        val stamped = stampFrameId(node, frameId)
                        val key = frameId to stamped.nodeId
                        if (seenPairs.add(key)) {
                            val backendId = stamped.backendDOMNodeId
                            if (backendId != null) {
                                byBackend.getOrPut(backendId) { mutableListOf() } += stamped
                            }
                            // add to frame bucket and all nodes once per unique node
                            frameBucket += stamped
                            allNodes += stamped
                        }
                    }
                }

                if (allNodes.isNotEmpty()) {
                    tracer?.debug("AX trees collected | frames={} totalNodes={} backends={}", frameIds.size, allNodes.size, byBackend.size)
                    return AccessibilityTreeResult(
                        nodes = allNodes,
                        nodesByFrameId = byFrame.mapValues { it.value.toList() },
                        nodesByBackendNodeId = byBackend.mapValues { it.value.toList() }
                    )
                }
                // Wait a bit and retry
                if (attempt < 4) Thread.sleep(250)
            }
        }

        return AccessibilityTreeResult.EMPTY
    }

    data class AccessibilityTreeResult(
        val nodes: List<AXNode>,
        val nodesByFrameId: Map<String, List<AXNode>>,
        val nodesByBackendNodeId: Map<Int, List<AXNode>>,
    ) {
        companion object {
            val EMPTY = AccessibilityTreeResult(emptyList(), emptyMap(), emptyMap())
        }
    }

    private fun collectFrameTree(tree: FrameTree, acc: MutableMap<String, FrameTree>) {
        val queue = ArrayDeque<FrameTree>()
        queue.add(tree)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            val frame = current.frame
            val frameId = frame?.id
            if (!frameId.isNullOrBlank()) {
                acc.putIfAbsent(frameId, current)
            }
            current.childFrames?.forEach { queue.add(it) }
        }
    }

    private fun stampFrameId(node: AXNode, frameId: String): AXNode {
        return node
    }

    private fun singleFrameResult(nodes: List<AXNode>, frameId: String): AccessibilityTreeResult {
        val stamped = nodes.map { stampFrameId(it, frameId) }
        val backendBuckets = mutableMapOf<Int, MutableList<AXNode>>()
        stamped.forEach { node ->
            val backendId = node.backendDOMNodeId ?: return@forEach
            backendBuckets.getOrPut(backendId) { mutableListOf() } += node
        }
        return AccessibilityTreeResult(
            nodes = stamped,
            nodesByFrameId = mapOf(frameId to stamped),
            nodesByBackendNodeId = backendBuckets.mapValues { it.value.toList() }
        )
    }

    private suspend fun ensureEnabled() {
        if (!isActive) return
        // Enable Page/DOM domains to stabilize frame tree & AX associations
        runCatching { devTools.page.enable() }
        runCatching { devTools.dom.enable() }

        val a11y = accessibilityAPI ?: return
        if (!accessibilityEnabled) {
            runCatching { a11y.enable() }
                .onFailure { e -> logger.warn("Accessibility.enable failed | err={}", e.toString()) }
            accessibilityEnabled = true
        }
    }
}
