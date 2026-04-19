package ai.platon.browser4.driver.chrome.dom

import ai.platon.browser4.driver.chrome.dom.model.*
import ai.platon.browser4.driver.chrome.dom.util.ScrollUtils

/**
 * Builds the compact `DOMState` representation that Browser4 ships to agents and serializers.
 *
 * The builder sits between three related node models:
 *
 * - `MergedDOMTree` is the richest form. It is the merged DOM/AX/snapshot node produced by snapshot capture and
 *   still contains full tree structure, geometry, accessibility metadata, and browser-facing identifiers.
 * - `TinyDOMTreeNode` is an intermediate builder tree. Each tiny node wraps a `MergedDOMTree` in `originalNode`
 *   and adds traversal decisions made upstream, such as `shouldDisplay`, `interactiveIndex`, paint-order flags,
 *   and other pruning hints. In other words, tiny nodes keep the original rich payload but annotate it with
 *   "how should the builder treat this node?" metadata.
 * - `MicroDOMTreeNode` is the final compact output. It replaces the rich `MergedDOMTree` payload with a
 *   `CleanedDOMTreeNode`, keeps only the fields that matter to downstream prompting/interaction, and recursively
 *   serializes children into a smaller LLM-facing tree.
 *
 * So the pipeline is intentionally:
 *
 * `MergedDOMTree` (raw merged browser model)
 * -> `OptimizedDOMTreeNode` (builder-oriented wrapper with pruning/display decisions)
 * -> `SerializableDOMTreeNode` (compact serialized agent model)
 */
object DOMStateBuilder {
    /**
     * Builds a `DOMState` from a preprocessed tiny tree.
     *
     * `DOMTinyTreeBuilder` does the expensive structural filtering first and hands this builder a
     * `TinyDOMTreeNode` tree. We then preserve those filtering decisions while compacting each node's
     * embedded `MergedDOMTree` into `CleanedDOMTreeNode` entries inside the final `MicroDOMTreeNode` tree.
     *
     * This split keeps Browser4's internal browser model (`MergedDOMTree`) available during transformation,
     * but prevents the final payload from carrying the full DOM/AX/snapshot graph into prompts.
     *
     * @param root The intermediate tiny tree root that still references the original rich browser nodes
     * @param includeAttributes List of attribute names to include (empty = use defaults)
     * @param options Serialization options for enhanced features
     * @return The compact DOM state containing the serialized micro tree plus lookup metadata
     */
    fun build(
        root: OptimizedDOMTreeNode,
        includeAttributes: List<String> = emptyList(),
        options: CompactOptions = CompactOptions()
    ): DOMState {
        val attrsList = includeAttributes.ifEmpty { DefaultIncludeAttributes.ATTRIBUTES }
        val includeAttributes = attrsList.map { it.lowercase() }.toSet()

        val frameIdSet = mutableSetOf<String>()
        // Traverse root to collect all frame ids
        collectFrameIds(root, frameIdSet)

        val frameIds = frameIdSet.toList()
        // Build a new LocatorMap for optimized element lookup
        val locatorMap = LocatorMap()

        // Detect top-level viewport height from the first HTML node's client rects
        val topViewportHeight: Double? = findTopLevelViewportHeight(root)

        val serializableDOMTree = buildSerializableDOMTreeNode(
            root,
            includeAttributes,
            emptyList(),
            locatorMap,
            frameIds,
            options,
            depth = 0,
            includeOrder = attrsList.map { it.lowercase() },
            topViewportHeight = topViewportHeight
        )

        val interactiveNodes = mutableListOf<SerializableDOMTreeNode>()
        collectInteractiveNodes(serializableDOMTree, interactiveNodes)

        // Export legacy selector map view for backward compatibility and diagnostics/tests
        val legacySelectorMap = locatorMap.toStringMap()
        return DOMState(serializableDOMTree, interactiveNodes, frameIds, legacySelectorMap, locatorMap, root)
    }

    private fun collectInteractiveNodes(root: SerializableDOMTree, interactiveNodes: MutableList<SerializableDOMTreeNode>) {
        root.takeIf { it.interactiveIndex != null }?.let { interactiveNodes.add(it) }
        root.children?.forEach {
            collectInteractiveNodes(it, interactiveNodes)
        }
    }

    private fun collectFrameIds(root: OptimizedDOMTreeNode, frameIds: MutableSet<String>) {
        root.originalNode.frameId?.let { frameIds.add(it) }
        root.children.forEach {
            collectFrameIds(it, frameIds)
        }
    }

    // Find the top-level HTML node's client height to use as viewport height
    private fun findTopLevelViewportHeight(root: OptimizedDOMTreeNode): Double? {
        var height: Double? = null
        fun dfs(n: OptimizedDOMTreeNode) {
            if (height != null) return
            val o = n.originalNode
            if (o.nodeName.equals("HTML", ignoreCase = true)) {
                height = o.snapshotNode?.clientRects?.height
                if (height != null && height!! > 0) return
            }
            n.children.forEach { dfs(it) }
        }
        dfs(root)
        return height
    }

    /**
     * Options for enhanced LLM serialization.
     */
    data class CompactOptions(
        val enablePaintOrderPruning: Boolean = true,
        val enableCompoundComponentDetection: Boolean = true,
        val enableAttributeCasingAlignment: Boolean = true,
        val maxPaintOrderThreshold: Int = 1000,
        val compoundComponentMinChildren: Int = 3,
        val preserveOriginalCasing: Boolean = false
    )

    /**
     * Recursively converts one `OptimizedDOMTreeNode` subtree into its `SerializableDOMTreeNode` equivalent.
     *
     * The important distinction is:
     * - the optimized node still points at the full `MergedDOMTreeNode` via `originalNode`, so this method can inspect
     *   geometry, AX role/name data, DOM attributes, and frame metadata while deciding how to serialize.
     * - the returned serializable node keeps only compact, prompt-safe fields and a cleaned copy of the original node.
     *
     * This is where the builder crosses the boundary from an internal transformation tree to the final
     * agent-facing representation.
     */
    private fun buildSerializableDOMTreeNode(
        node: OptimizedDOMTreeNode,
        includeAttributes: Set<String>,
        ancestors: List<MergedDOMTreeNode>,
        locatorMap: LocatorMap,
        frameIds: List<String>,
        options: CompactOptions,
        depth: Int = 0,
        includeOrder: List<String> = emptyList(),
        topViewportHeight: Double?
    ): SerializableDOMTreeNode {
        // Apply paint-order pruning if enabled
        if (options.enablePaintOrderPruning && shouldPruneByPaintOrder(node, options)) {
            // Return a pruned node with minimal information
            return createPrunedNode(node, ancestors, locatorMap, frameIds, topViewportHeight)
        }

        // Clean original node with enhanced attribute casing alignment
        val cleanedOriginal =
            cleanOriginalNodeEnhanced(node.originalNode, includeAttributes, options, includeOrder, frameIds, topViewportHeight)

        val showScrollInfo = ScrollUtils.shouldShowScrollInfo(node.originalNode, ancestors)
        val scrollInfoText = if (showScrollInfo) {
            ScrollUtils.getScrollInfoText(node.originalNode)
        } else {
            null
        }

        // Add to selector map with multiple keys for enhanced lookup
        addToLocatorMap(node.originalNode, frameIds, locatorMap)
        // Also add interactive index mapping if present on SlimNode
        node.interactiveIndex?.let { idx ->
            locatorMap.put(Locator.Type.INDEX, idx.toString(), node.originalNode)
        }

        // Detect compound components if enabled
        val isCompoundComponent = if (options.enableCompoundComponentDetection) {
            detectCompoundComponent(node, options)
        } else {
            false
        }

        // Recursively serialize children with enhanced logic (do not filter; prune per-node)
        val childAncestors = ancestors + node.originalNode
        val serializedChildren = node.children.map {
            buildSerializableDOMTreeNode(
                it,
                includeAttributes,
                childAncestors,
                locatorMap,
                frameIds,
                options,
                depth + 1,
                includeOrder,
                topViewportHeight
            )
        }

        return SerializableDOMTreeNode(
            shouldDisplay = node.shouldDisplay.takeIf { it },
            interactiveIndex = node.interactiveIndex,
            ignoredByPaintOrder = node.ignoredByPaintOrder.takeIf { it },
            excludedByParent = node.excludedByParent.takeIf { it },
            isCompoundComponent = isCompoundComponent.takeIf { it },
            originalNode = cleanedOriginal,
            children = serializedChildren.takeIf { it.isNotEmpty() },
            shouldShowScrollInfo = showScrollInfo.takeIf { it },
            scrollInfoText = scrollInfoText?.takeIf { it.isNotEmpty() },
        )
    }

    /**
     * Determine if a node should be pruned based on paint order.
     */
    private fun shouldPruneByPaintOrder(node: OptimizedDOMTreeNode, options: CompactOptions): Boolean {
        val paintOrder = node.originalNode.snapshotNode?.paintOrder ?: return false
        return paintOrder > options.maxPaintOrderThreshold
    }

    /**
     * Detect if a node represents a compound component.
     */
    private fun detectCompoundComponent(node: OptimizedDOMTreeNode, options: CompactOptions): Boolean {
        val originalNode = node.originalNode
        val tag = originalNode.nodeName.lowercase()

        // Input types that usually render compound controls
        val inputCompoundTypes = setOf(
            "date", "time", "datetime-local", "month", "week", "range", "number", "color", "file"
        )

        // Check for common compound component patterns
        val hasCompoundStructure = when {
            // Specific controls that have built-in compound UI
            tag == "select" || tag == "details" || tag == "audio" || tag == "video" -> true

            // Input with certain types
            tag == "input" && originalNode.attributes["type"]?.lowercase() in inputCompoundTypes -> true

            // Form components
            tag == "form" && node.children.size >= options.compoundComponentMinChildren -> true

            // List components
            tag in setOf("ul", "ol", "dl") && node.children.size >= options.compoundComponentMinChildren -> true

            // Table components
            tag == "table" && node.children.size >= options.compoundComponentMinChildren -> true

            // Navigation components
            tag == "nav" && node.children.size >= options.compoundComponentMinChildren -> true

            // Custom elements (web components)
            originalNode.nodeName.contains("-") && node.children.size >= options.compoundComponentMinChildren -> true

            // Check for ARIA roles that indicate compound components
            originalNode.axNode?.role in setOf(
                "list",
                "grid",
                "tree",
                "tablist",
                "menu",
                "toolbar",
                "navigation"
            ) -> true

            else -> false
        }

        return hasCompoundStructure
    }

    /**
     * Create a pruned node with minimal information for high paint-order elements.
     */
    private fun createPrunedNode(
        node: OptimizedDOMTreeNode,
        ancestors: List<MergedDOMTreeNode>,
        locatorMap: LocatorMap,
        frameIds: List<String>,
        topViewportHeight: Double?
    ): SerializableDOMTreeNode {
        val viewportIndex = computeViewportIndex(node.originalNode, topViewportHeight)

        val prunedOriginal = CleanedDOMTreeNode(
            locator = createNodeLocator(node.originalNode, frameIds).absoluteSelector,
            nodeId = node.originalNode.nodeId,
            backendNodeId = node.originalNode.backendNodeId,
            nodeType = node.originalNode.nodeType.value,
            nodeName = node.originalNode.nodeName.lowercase(),
            nodeValue = node.originalNode.nodeValue.takeIf { it.isNotEmpty() },
            attributes = null, // Minimal attributes for pruned nodes
            frameId = node.originalNode.frameId,
            sessionId = node.originalNode.sessionId,
            isScrollable = null,
            isVisible = null,
            isInteractable = null,
            xpath = node.originalNode.xpath,
            elementHash = node.originalNode.elementHash,
            interactiveIndex = node.originalNode.interactiveIndex,
            bounds = null, // No bounds for pruned nodes
            clientRects = null,
            scrollRects = null,
            absoluteBounds = null,
            /** A 1-based viewport index */
            viewportIndex = viewportIndex,
            paintOrder = null,
            stackingContexts = null,
            contentDocument = null
        )

        // Add to selector map with enhanced lookup keys
        addToLocatorMap(node.originalNode, frameIds, locatorMap)

        return SerializableDOMTreeNode(
            shouldDisplay = null, // Pruned nodes are not displayed (null means false)
            interactiveIndex = node.interactiveIndex,
            ignoredByPaintOrder = true, // Mark as ignored by paint order
            excludedByParent = node.excludedByParent.takeIf { it },
            isCompoundComponent = null,
            originalNode = prunedOriginal,
            children = null, // No children for pruned nodes
            shouldShowScrollInfo = null,
            scrollInfoText = null
        )
    }

    // Compute viewport index (1-based) using absolute Y and top-level viewport height
    fun computeViewportIndex(node: MergedDOMTreeNode, topViewportHeight: Double?): Int? {
        val vh = topViewportHeight ?: return null
        if (!vh.isFinite() || vh <= 0.0) return null
        // Prefer absolute bounds from snapshot; fallback to absolutePosition or bounds
        val y = node.snapshotNode?.absoluteBounds?.y
            ?: node.absolutePosition?.y
            ?: node.snapshotNode?.bounds?.y
            ?: return null
        if (!y.isFinite()) return null
        val base = if (y < 0) 0.0 else y
        val idx = kotlin.math.floor(base / vh).toInt() + 1
        return if (idx < 1) 1 else idx
    }

    /**
     * Enhanced cleanOriginalNode with attribute casing alignment and improved filtering.
     */
    private fun cleanOriginalNodeEnhanced(
        node: MergedDOMTreeNode,
        includeAttributes: Set<String>,
        options: CompactOptions,
        includeOrder: List<String>,
        frameIds: List<String>,
        topViewportHeight: Double?
    ): CleanedDOMTreeNode {
        // Filter attributes with enhanced casing alignment
        val filteredAttrs: Map<String, String> = if (options.enableAttributeCasingAlignment) {
            alignAttributeCasing(node.attributes, includeAttributes, options)
        } else {
            node.attributes.filterKeys { key ->
                key.lowercase() in includeAttributes
            }
        }

        // Extract AX attributes if present with enhanced processing
        val axAttrs = linkedMapOf<String, String>()
        node.axNode?.let { ax ->
            ax.role?.let { axAttrs["role"] = it }
            ax.name?.let { axAttrs["ax_name"] = it }
            ax.properties?.forEach { prop ->
                val key = if (options.enableAttributeCasingAlignment) {
                    alignAttributeName(prop.name, options)
                } else {
                    prop.name.lowercase()
                }
                if (key in includeAttributes) {
                    val v = prop.value
                    when (v) {
                        is Boolean -> axAttrs[key] = v.toString().lowercase()
                        null -> {}
                        else -> {
                            val s = v.toString().trim()
                            if (s.isNotEmpty()) axAttrs[key] = s
                        }
                    }
                }
            }
        }

        // Merge DOM and AX attributes (DOM first, AX overrides)
        val merged = linkedMapOf<String, String>()
        filteredAttrs.forEach { (k, v) -> merged[k] = v }
        axAttrs.forEach { (k, v) -> merged[k] = v }

        // Remove 'role' that duplicates node name (align with Python)
        val nodeNameLower = node.nodeName.lowercase()
        if (merged["role"] != null && merged["role"]!!.equals(nodeNameLower, ignoreCase = true)) {
            merged.remove("role")
        }

        // Remove duplicate long values keeping first occurrence according to include order (>5 length like Python)
        if (merged.size > 1) {
            val seen = mutableMapOf<String, String>() // value -> key
            val keysToRemove = mutableSetOf<String>()
            // iterate in includeOrder priority if provided, otherwise current order
            val orderedKeys =
                includeOrder.filter { merged.containsKey(it) } + merged.keys.filter { it !in includeOrder }
            for (key in orderedKeys) {
                val value = merged[key] ?: continue
                if (value.length > 5) {
                    val existingKey = seen[value]
                    if (existingKey != null && existingKey != key) {
                        keysToRemove.add(key)
                    } else {
                        seen[value] = key
                    }
                }
            }
            keysToRemove.forEach { merged.remove(it) }
        }

        // Get snapshot info with enhanced processing
        val snapshot = node.snapshotNode
        val bounds = snapshot?.bounds
        val clientRects = snapshot?.clientRects
        val scrollRects = snapshot?.scrollRects
        val absoluteBounds = snapshot?.absoluteBounds
        val paintOrder = snapshot?.paintOrder
        val stackingContexts = snapshot?.stackingContexts

        // Compute viewport index using absolute bounds and top-level viewport height
        val viewportIndex = computeViewportIndex(node, topViewportHeight)

        return CleanedDOMTreeNode(
            locator = createNodeLocator(node, frameIds).absoluteSelector,
            nodeId = node.nodeId,
            backendNodeId = node.backendNodeId,
            nodeType = node.nodeType.value,
            nodeName = if (options.preserveOriginalCasing) node.nodeName else node.nodeName.lowercase(),
            nodeValue = node.nodeValue.takeIf { it.isNotEmpty() },
            attributes = merged.takeIf { it.isNotEmpty() },
            frameId = node.frameId,
            sessionId = node.sessionId,
            isScrollable = node.isScrollable?.takeIf { it },
            isVisible = node.isVisible?.takeIf { it },
            isInteractable = node.isInteractable?.takeIf { it },
            xpath = node.xpath,
            elementHash = node.elementHash,
            interactiveIndex = node.interactiveIndex,
            clientRects = clientRects?.compact(),
            scrollRects = scrollRects?.compact(),
            bounds = bounds?.compact(),
            absoluteBounds = absoluteBounds?.compact(),
            /** A 1-based viewport index */
            viewportIndex = viewportIndex,
            paintOrder = paintOrder,
            stackingContexts = stackingContexts,
            // contentDocument is cleaned recursively if present
            contentDocument = node.contentDocument?.let {
                cleanOriginalNodeEnhanced(
                    it,
                    includeAttributes,
                    options,
                    includeOrder,
                    frameIds,
                    topViewportHeight
                )
            }
        )
    }

    /**
     * Align attribute casing to match Python implementation and improve consistency.
     */
    private fun alignAttributeCasing(
        attributes: Map<String, String>,
        includeAttributes: Set<String>,
        options: CompactOptions
    ): Map<String, String> {
        return attributes.mapNotNull { (key, value) ->
            val alignedKey = alignAttributeName(key, options)
            if (alignedKey in includeAttributes) {
                alignedKey to value
            } else {
                null
            }
        }.toMap()
    }

    /**
     * Align attribute name casing for consistency with Python implementation.
     */
    private fun alignAttributeName(attributeName: String, options: CompactOptions): String {
        if (options.preserveOriginalCasing) return attributeName

        // Convert to lowercase for consistency
        val lowerName = attributeName.lowercase()

        // Special casing for known attributes that should maintain specific formats
        return when (lowerName) {
            "classname" -> "class" // Normalize className to class
            "htmlfor" -> "for" // Normalize htmlFor to for
            "readonly" -> "readonly" // Keep consistent casing
            "disabled" -> "disabled"
            "checked" -> "checked"
            "selected" -> "selected"
            "multiple" -> "multiple"
            "required" -> "required"
            "autofocus" -> "autofocus"
            "autoplay" -> "autoplay"
            "controls" -> "controls"
            "loop" -> "loop"
            "muted" -> "muted"
            else -> lowerName
        }
    }

    private fun cleanOriginalNode(
        node: MergedDOMTreeNode,
        includeAttributes: Set<String>,
        frameIds: List<String>,
    ): CleanedDOMTreeNode {
        // Filter attributes
        val filteredAttrs = node.attributes.filterKeys { key ->
            key.lowercase() in includeAttributes
        }

        // Extract AX attributes if present
        val axAttrs = mutableMapOf<String, Any>()
        node.axNode?.let { ax ->
            ax.role?.let { axAttrs["role"] = it }
            ax.name?.let { axAttrs["ax_name"] = it }
            ax.properties?.forEach { prop ->
                val key = prop.name.lowercase()
                if (key in includeAttributes) {
                    prop.value?.let { axAttrs[key] = it }
                }
            }
        }

        // Merge DOM and AX attributes
        val mergedAttrs = filteredAttrs + axAttrs

        // Get snapshot info
        val snapshot = node.snapshotNode
        val bounds = snapshot?.bounds
        val clientRects = snapshot?.clientRects
        val scrollRects = snapshot?.scrollRects

        return CleanedDOMTreeNode(
            locator = createNodeLocator(node, frameIds).absoluteSelector,
            nodeId = node.nodeId,
            backendNodeId = node.backendNodeId,
            nodeType = node.nodeType.value,
            nodeName = node.nodeName.lowercase(),
            nodeValue = node.nodeValue.takeIf { it.isNotEmpty() },
            attributes = mergedAttrs.takeIf { it.isNotEmpty() },
            frameId = node.frameId,
            sessionId = node.sessionId,
            isScrollable = node.isScrollable,
            isVisible = node.isVisible,
            isInteractable = node.isInteractable,
            xpath = node.xpath,
            elementHash = node.elementHash,
            interactiveIndex = node.interactiveIndex,
            bounds = bounds?.compact(),
//            absoluteBounds = absoluteBounds,
//            viewportIndex = viewportIndex,
            clientRects = clientRects?.compact(),
            scrollRects = scrollRects?.compact(),
            // contentDocument is cleaned recursively if present
            contentDocument = node.contentDocument?.let { cleanOriginalNode(it, includeAttributes, frameIds) }
        )
    }

    /**
     * Add node to enhanced selector map with multiple lookup keys (via LocatorMap).
     * Supports element hash, XPath, backend node ID, frame/backend combo and node ID for comprehensive element lookup.
     */
    private fun addToLocatorMap(
        node: MergedDOMTreeNode,
        frameIds: List<String>,
        locatorMap: LocatorMap
    ) {
        // Add by element hash (primary key)
        node.elementHash?.takeIf { it.isNotBlank() }?.let { h ->
            locatorMap.put(Locator.Type.HASH, h, node)
        }

        // Add by XPath (secondary key)
        node.xpath?.takeIf { it.isNotBlank() }?.let { xp ->
            locatorMap.put(Locator.Type.XPATH, xp, node)
        }

        val frameId = node.frameId
        val backendNodeId = node.backendNodeId

        // Add by backend node ID (tertiary key)
        backendNodeId?.let { bn ->
            locatorMap.put(Locator.Type.BACKEND_NODE_ID, bn.toString(), node)
        }

        // Add by `$frameId-$backendNodeId` as node ID
        if (frameId != null && backendNodeId != null) {
            val frameIdIndex = frameIds.indexOf(frameId).takeIf { it > 0 } ?: 0
            locatorMap.put(FBNLocator(frameId, backendNodeId), node)
            locatorMap.put(FBNLocator(frameIdIndex, backendNodeId), node)
        }

        // Add by node ID (fallback key)
        locatorMap.put(Locator.Type.NODE_ID, node.nodeId.toString(), node)
    }

    private fun createNodeLocator(node: MergedDOMTreeNode, frameIds: List<String>): FBNLocator {
        // Returns -1 if the list does not contain element
        val frameIndex = frameIds.indexOf(node.frameId).takeIf { it > 0 } ?: 0
        val backendNodeId = node.backendNodeId ?: 0
        return FBNLocator(frameIndex, backendNodeId)
    }
}
