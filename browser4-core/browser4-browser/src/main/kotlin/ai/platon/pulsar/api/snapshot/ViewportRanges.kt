package ai.platon.pulsar.api.snapshot

/**
 * Converts scroll-relative viewport indices into document Y-axis intervals.
 *
 * Viewport indices are scroll-relative:
 * - `0`  — the screen currently visible in the browser window,
 * - `1`  — the screen one viewport-height below the current position,
 * - `-1` — the screen one viewport-height above the current position.
 *
 * The page itself is never scrolled — the snapshot is filtered by Y-range, so
 * the current scroll position [scrollY] anchors every interval.
 */
object ViewportRanges {
    /**
     * @param scrollY Current vertical scroll position in document coordinates (px).
     * @param viewportHeight The visible viewport height in px.
     * @param indices Scroll-relative viewport indices. Order and duplicates are irrelevant.
     * @return Sorted, non-overlapping `(startY, endY)` intervals covering the requested
     *   viewports. Intervals are clamped to the top of the page (>= 0). Indices whose
     *   entire interval lies above the top of the page produce no interval.
     */
    fun computeYAxisRanges(
        scrollY: Double,
        viewportHeight: Double,
        indices: List<Int>
    ): List<Pair<Double, Double>> {
        if (viewportHeight <= 0.0 || scrollY.isNaN() || viewportHeight.isNaN()) return emptyList()

        val sortedIndices = indices.distinct().sorted()
        if (sortedIndices.isEmpty()) return emptyList()

        return mergeContiguous(sortedIndices).mapNotNull { (startIdx, endIdx) ->
            val startY = (scrollY + startIdx * viewportHeight).coerceAtLeast(0.0)
            val endY = (scrollY + (endIdx + 1) * viewportHeight).coerceAtLeast(0.0)
            if (endY > startY) startY to endY else null
        }
    }

    /**
     * Merge contiguous indices into `(start, end)` pairs.
     * E.g., `[0, 1, 2, 4, 6, 7]` → `[(0, 2), (4, 4), (6, 7)]`.
     */
    private fun mergeContiguous(sortedIndices: List<Int>): List<Pair<Int, Int>> {
        val result = mutableListOf<Pair<Int, Int>>()
        var start = sortedIndices[0]
        var end = start
        for (i in 1 until sortedIndices.size) {
            if (sortedIndices[i] == end + 1) {
                end = sortedIndices[i]
            } else {
                result.add(start to end)
                start = sortedIndices[i]
                end = start
            }
        }
        result.add(start to end)
        return result
    }
}
