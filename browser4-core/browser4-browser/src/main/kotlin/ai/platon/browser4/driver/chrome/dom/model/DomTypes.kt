package ai.platon.browser4.driver.chrome.dom.model

/**
 * Marker for target and session details.
 */
data class PageTarget(
    val targetId: String? = null,
    val frameId: String? = null,
    val sessionId: String? = null
)

/**
 * Options controlling snapshot breadth/depth and expensive fields.
 */
data class SnapshotOptions(
    val maxDepth: Int = 1000, // maxDepth < 0 means full tree
    val includeAX: Boolean = true,
    val includeSnapshot: Boolean = true,
    val includeStyles: Boolean = true,
    val includePaintOrder: Boolean = true,
    val includeDOMRects: Boolean = true,
    val includeScrollAnalysis: Boolean = true,
    val includeVisibility: Boolean = true,
    val includeInteractivity: Boolean = true
)

/**
 * Result from collecting all trees (DOM, AX, Snapshot) for a target.
 */
data class TargetTrees(
    val snapshot: Map<String, Any>? = null,
    val domTree: MergedDOMTree = MergedDOMTree(),
    val axTree: List<AXNodeEx> = emptyList(),
    val devicePixelRatio: Double = 1.0,
    val cdpTiming: Map<String, Long> = emptyMap(),
    val options: SnapshotOptions = SnapshotOptions(),

    // Internal mappings for merging
    val snapshotByBackendId: Map<Int, SnapshotNodeEx> = emptyMap(),
    val axByBackendId: Map<Int, AXNodeEx> = emptyMap(),
    val axTreeByFrameId: Map<String, List<AXNodeEx>> = emptyMap(),
    val domByBackendId: Map<Int, MergedDOMTreeNode> = emptyMap()
)

/**
 * Criteria for finding a DOM element.
 */
data class ElementRefCriteria(
    val cssSelector: String? = null,
    val xPath: String? = null,
    val elementHash: String? = null,
    val backendNodeId: Int? = null
)
