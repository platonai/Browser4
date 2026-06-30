package ai.platon.browser4.chrome.dom.model

/**
 * Options for filtering the ARIA snapshot output (YAML accessibility tree).
 *
 * These options govern the rendering/formatting phase only —
 * they do not affect CDP-level data collection (see [ai.platon.pulsar.chrome.dom.model.SnapshotOptions] for that).
 */
data class AriaSnapshotOptions(
    /** Only include interactive elements (buttons, links, inputs, etc.). */
    val interactive: Boolean = false,
    /** Always include href URLs for link elements (prevent URL-collapse). */
    val urls: Boolean = false,
    /** Aggressively remove empty/structural generic nodes. Enabled by default to keep snapshots lean. */
    val compact: Boolean = true,
    /** Maximum tree depth to render. -1 means no limit. */
    val maxDepth: Int = -1,
    /** CSS selector string to scope the snapshot to a specific subtree. */
    val selector: String? = null,
    /**
     * Resolved backendNodeId for the CSS selector in [selector].
     * Set by [ai.platon.browser4.chrome.handler.PageHandler] before rendering.
     * If non-null, renderers scope output to only this subtree.
     */
    val rootBackendNodeId: Int? = null,
    /** Viewport specification string (e.g., "3", "1,3,5", "2-4", "all"). */
    val viewports: String? = null,
    /** Include each element's bounding box as [box=x,y,width,height] in the output. Enabled by default so AI can understand page layout. */
    val boxes: Boolean = true,
    /** Maximum number of nodes to render. -1 means no limit. Useful for large pages (e.g. search results). */
    val maxNodes: Int = -1,
)
