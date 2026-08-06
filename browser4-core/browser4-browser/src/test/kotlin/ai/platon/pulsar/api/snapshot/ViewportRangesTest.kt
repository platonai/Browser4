package ai.platon.pulsar.api.snapshot

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class ViewportRangesTest {

    private fun ranges(scrollY: Double, viewportHeight: Double, vararg indices: Int) =
        ViewportRanges.computeYAxisRanges(scrollY, viewportHeight, indices.toList())

    @Test
    @DisplayName("-v 0 is the currently visible screen")
    fun testViewport0IsCurrentVisibleScreen() {
        assertEquals(listOf(500.0 to 1300.0), ranges(500.0, 800.0, 0))
        assertEquals(listOf(0.0 to 800.0), ranges(0.0, 800.0, 0))
    }

    @Test
    @DisplayName("-v 1 is one screen below the current position")
    fun testViewport1IsBelowCurrent() {
        assertEquals(listOf(1300.0 to 2100.0), ranges(500.0, 800.0, 1))
        assertEquals(listOf(800.0 to 1600.0), ranges(0.0, 800.0, 1))
    }

    @Test
    @DisplayName("-v -1 is one screen above the current position")
    fun testViewportMinus1IsAboveCurrent() {
        assertEquals(listOf(1200.0 to 2000.0), ranges(2000.0, 800.0, -1))
    }

    @Test
    @DisplayName("-v -1 clamps to the top when scrolled less than one screen")
    fun testViewportMinus1ClampedToTop() {
        // Band [-300, 500) clamps to [0, 500).
        assertEquals(listOf(0.0 to 500.0), ranges(500.0, 800.0, -1))
    }

    @Test
    @DisplayName("-v -1 at the top of the page is empty (nothing above)")
    fun testViewportMinus1AtTopIsEmpty() {
        assertEquals(emptyList<Pair<Double, Double>>(), ranges(0.0, 800.0, -1))
    }

    @Test
    @DisplayName("-v -2 fully above the document produces no interval")
    fun testViewportMinus2FullyAboveTopIsEmpty() {
        // Band [scrollY-2vh, scrollY-vh] = [-1100, -300) → clamps to empty.
        assertEquals(emptyList<Pair<Double, Double>>(), ranges(500.0, 800.0, -2))
    }

    @Test
    @DisplayName("-v -2 is captured when scrolled far enough")
    fun testViewportMinus2ScrolledEnough() {
        // Band [2500-1600, 2500-800) = [900, 1700).
        assertEquals(listOf(900.0 to 1700.0), ranges(2500.0, 800.0, -2))
    }

    @Test
    @DisplayName("contiguous indices merge into one interval")
    fun testContiguousIndicesMerge() {
        // -v 0,1,2 at scrollY=500 → [500, 2900).
        assertEquals(listOf(500.0 to 2900.0), ranges(500.0, 800.0, 0, 1, 2))
    }

    @Test
    @DisplayName("disjoint indices produce separate intervals")
    fun testDisjointIndicesSeparateIntervals() {
        // -v 0,2 at scrollY=500 → [500, 1300) and [2100, 2900).
        assertEquals(
            listOf(500.0 to 1300.0, 2100.0 to 2900.0),
            ranges(500.0, 800.0, 0, 2)
        )
    }

    @Test
    @DisplayName("negative and positive indices spanning the scroll position merge after clamping")
    fun testNegativeAndPositiveSpanningScrollMerge() {
        // -v -1,0,1 at scrollY=500 → union [0, 2100).
        assertEquals(listOf(0.0 to 2100.0), ranges(500.0, 800.0, -1, 0, 1))
    }

    @Test
    @DisplayName("negative and positive indices with a gap stay separate")
    fun testNegativeAndPositiveGapSeparate() {
        // -v -1,1 at scrollY=2000 → [-1]=[1200, 2000), [1]=[2800, 3600).
        assertEquals(
            listOf(1200.0 to 2000.0, 2800.0 to 3600.0),
            ranges(2000.0, 800.0, -1, 1)
        )
    }

    @Test
    @DisplayName("partial overlap with the top merges the clamped bands")
    fun testPartialTopOverlapMerges() {
        // -v -1,0 at scrollY=300 → [-1]=[0, 300), [0]=[300, 1100) → [0, 1100).
        assertEquals(listOf(0.0 to 1100.0), ranges(300.0, 800.0, -1, 0))
    }

    @Test
    @DisplayName("empty indices produce no intervals")
    fun testEmptyIndices() {
        assertEquals(emptyList<Pair<Double, Double>>(), ranges(500.0, 800.0))
    }

    @Test
    @DisplayName("non-positive viewport height produces no intervals")
    fun testInvalidViewportHeight() {
        assertEquals(emptyList<Pair<Double, Double>>(), ranges(500.0, 0.0, 0))
        assertEquals(emptyList<Pair<Double, Double>>(), ranges(500.0, -800.0, 0))
    }

    @Test
    @DisplayName("NaN scroll position produces no intervals")
    fun testNaNSafe() {
        assertEquals(emptyList<Pair<Double, Double>>(), ranges(Double.NaN, 800.0, 0))
    }

    @Test
    @DisplayName("unsorted and duplicate indices are normalized")
    fun testUnsortedAndDuplicatesNormalized() {
        // -v 2,0,0,1 == -v 0,1,2.
        assertEquals(listOf(500.0 to 2900.0), ranges(500.0, 800.0, 2, 0, 0, 1))
    }

    @Test
    @DisplayName("indices beyond the document height extend past the end (renderer finds nothing)")
    fun testLargePositiveIndexBeyondDocument() {
        assertEquals(listOf(80_000.0 to 80_800.0), ranges(0.0, 800.0, 100))
    }
}
