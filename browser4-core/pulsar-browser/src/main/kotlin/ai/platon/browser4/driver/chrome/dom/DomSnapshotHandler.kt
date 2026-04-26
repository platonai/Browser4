package ai.platon.browser4.driver.chrome.dom

import ai.platon.browser4.driver.chrome.RemoteDevTools
import ai.platon.browser4.driver.chrome.experimental.CDP
import ai.platon.browser4.driver.chrome.dom.model.DOMRect
import ai.platon.browser4.driver.chrome.dom.model.SnapshotNodeEx
import ai.platon.browser4.driver.chrome.dom.util.DomDebug
import ai.platon.cdt.kt.protocol.support.annotations.Experimental
import ai.platon.cdt.kt.protocol.support.annotations.Optional
import ai.platon.cdt.kt.protocol.support.annotations.ParamName
import ai.platon.cdt.kt.protocol.types.domsnapshot.CaptureSnapshot
import ai.platon.pulsar.common.getLogger

/**
 * Handler for DOMSnapshot domain operations.
 * Captures and processes layout snapshots with style and rect information.
 */
class DomSnapshotHandler(devTools: RemoteDevTools) {
    private val logger = getLogger(this)
    private val tracer get() = logger.takeIf { it.isTraceEnabled }
    private val cdp = CDP(devTools)

    /**
     * Enhanced capture with absolute coordinates and stacking context analysis.
     * This method provides comprehensive layout information for interaction indices.
     */
    suspend fun captureSnapshot(
        includeStyles: Boolean = true,
        includePaintOrder: Boolean = true,
        includeDomRects: Boolean = true,
        includeAbsoluteCoords: Boolean = true,
        devicePixelRatio: Double = 1.0
    ): Map<Int, SnapshotNodeEx> {
        val computedStyles = if (includeStyles) REQUIRED_COMPUTED_STYLES else emptyList()
        val snapshot = captureSnapshot(
            computedStyles,
            includePaintOrder = includePaintOrder,
            includeDOMRects = includeDomRects,
            includeBlendedBackgroundColors = null,
            includeTextColorOpacities = null,
        )

        val byBackend = mutableMapOf<Int, SnapshotNodeEx>()
        val strings = snapshot.strings

        var totalRows = 0
        for (doc in snapshot.documents) {
            val nodeTree = doc.nodes
            val layout = doc.layout

            val backendIds: List<Int> = nodeTree.backendNodeId ?: emptyList()
            val nodeIndex: List<Int> = layout.nodeIndex

            val bounds = layout.bounds
            // The offset rect of nodes. Only available when includeDOMRects is set to true
            // val offsetRects = layout.offsetRects // not used currently
            val scrollRects = layout.scrollRects ?: emptyList()
            val clientRects = layout.clientRects ?: emptyList()
            val paintOrders = if (includePaintOrder) layout.paintOrders ?: emptyList() else emptyList()

            val rows = nodeIndex.size
            totalRows += rows

            // Build style maps per layout row
            val stylesIdx = layout.styles
            val styleMaps: List<Map<String, String>> = if (includeStyles) {
                stylesIdx.map { idxs ->
                    val m = mutableMapOf<String, String>()
                    var i = 0
                    while (i + 1 < idxs.size) {
                        val k = strings.getOrNull(idxs[i]) ?: ""
                        val v = strings.getOrNull(idxs[i + 1]) ?: ""
                        if (k.isNotEmpty()) m[k] = v
                        i += 2
                    }
                    m
                }
            } else {
                List(rows) { emptyMap() }
            }

            // Analyze stacking contexts
            val stackingContexts: List<Int> = layout.stackingContexts.index.let { rareIndices ->
                if (rareIndices.isEmpty()) {
                    emptyList()
                } else {
                    val flags = IntArray(rows)
                    rareIndices.forEach { idx ->
                        if (idx in 0 until rows) {
                            flags[idx] = 1
                        }
                    }
                    flags.toList()
                }
            }

            fun parseBoundsRow(rowIdx: Int): DOMRect? {
                val arr = bounds.getOrNull(rowIdx) ?: return null
                return when (arr.size) {
                    8 -> DOMRect.fromBoundsArray(arr)
                    4, 5, 6, 7 -> DOMRect.fromRectArray(arr)
                    else -> null
                }
            }

            fun scaleRectToCssPx(rect: DOMRect?): DOMRect? {
                if (rect == null) return null
                val dpr = if (devicePixelRatio.takeIf { it.isFinite() && it > 0 } != null) devicePixelRatio else 1.0
                return if (dpr == 1.0) rect else DOMRect(
                    rect.x / dpr,
                    rect.y / dpr,
                    rect.width / dpr,
                    rect.height / dpr
                )
            }

            for (row in 0 until rows) {
                val nIdx = nodeIndex.getOrNull(row) ?: continue
                val backendId = backendIds.getOrNull(nIdx) ?: continue

                // Extract cursor and clickability from styles
                val styles = styleMaps.getOrNull(row) ?: emptyMap()
                val cursor = styles["cursor"]
                val isClickable = cursor in setOf("pointer", "hand")

                // Parse layout bounds and scale to CSS pixels
                val rawBoundsRect = parseBoundsRow(row)?.roundTo(1)
                val boundsRect = scaleRectToCssPx(rawBoundsRect)?.roundTo(1)
                val absoluteBounds = if (includeAbsoluteCoords) boundsRect else null

                val snapshotEx = SnapshotNodeEx(
                    isClickable = isClickable,
                    cursorStyle = cursor,
                    bounds = boundsRect,
                    clientRects = DOMRect.fromRectArray(clientRects.getOrNull(row) ?: emptyList()),
                    scrollRects = DOMRect.fromRectArray(scrollRects.getOrNull(row) ?: emptyList()),
                    computedStyles = styles.takeIf { it.isNotEmpty() },
                    paintOrder = paintOrders.getOrNull(row),
                    stackingContexts = stackingContexts.getOrNull(row),
                    absoluteBounds = absoluteBounds
                )

                byBackend[backendId] = snapshotEx
            }
        }

        if (logger.isDebugEnabled) {
            logger.debug("Bounds summary: {}", DomDebug.summarize(byBackend))
        }

        tracer?.trace(
            "DOMSnapshot captured | entries={} rowsApprox={} styles={} paintOrder={}",
            byBackend.size, totalRows, includeStyles, includePaintOrder
        )

        return byBackend
    }

    /**
     * Returns a document snapshot, including the full DOM tree of the root node (including iframes,
     * template contents, and imported documents) in a flattened array, as well as layout and
     * white-listed computed style information for the nodes. Shadow DOM in the returned DOM tree is
     * flattened.
     * @param computedStyles Whitelist of computed styles to return.
     * @param includePaintOrder Whether to include layout object paint orders into the snapshot.
     * @param includeDOMRects Whether to include DOM rectangles (offsetRects, clientRects, scrollRects) into the snapshot
     * @param includeBlendedBackgroundColors Whether to include blended background colors in the snapshot (default: false).
     * Blended background color is achieved by blending background colors of all elements
     * that overlap with the current element.
     * @param includeTextColorOpacities Whether to include text color opacity in the snapshot (default: false).
     * An element might have the opacity property set that affects the text color of the element.
     * The final text color opacity is computed based on the opacity of all overlapping elements.
     */
    private suspend fun captureSnapshot(
        @ParamName("computedStyles") computedStyles: List<String>,
        @ParamName("includePaintOrder") @Optional includePaintOrder: Boolean? = null,
        @ParamName("includeDOMRects") @Optional includeDOMRects: Boolean? = null,
        @ParamName("includeBlendedBackgroundColors") @Optional @Experimental includeBlendedBackgroundColors: Boolean? = null,
        @ParamName("includeTextColorOpacities") @Optional @Experimental includeTextColorOpacities: Boolean? = null,
    ): CaptureSnapshot {
        return try {
            cdp.domSnapshotCaptureSnapshot(
                computedStyles,
                includePaintOrder = includePaintOrder,
                includeDOMRects = includeDOMRects,
                includeBlendedBackgroundColors = includeBlendedBackgroundColors,
                includeTextColorOpacities = includeTextColorOpacities
            )
        } catch (e: Exception) {
            logger.warn("DOMSnapshot.captureSnapshot failed | err={}", e.toString())
            tracer?.debug("captureSnapshot exception", e)
            CaptureSnapshot(emptyList(), emptyList())
        }
    }

    companion object {
        /**
         * Required computed styles for proper visibility, scroll detection, and absolute positioning.
         */
        val REQUIRED_COMPUTED_STYLES = listOf(
            "display",
            "visibility",
            "opacity",
            "overflow",
            "overflow-x",
            "overflow-y",
            "cursor",
            "pointer-events",
            "position",
            "transform",
            "z-index",
            "top",
            "left",
            "right",
            "bottom",
            "width",
            "height",
            "margin-top",
            "margin-left",
            "margin-right",
            "margin-bottom",
            "padding-top",
            "padding-left",
            "padding-right",
            "padding-bottom"
        )
    }
}
