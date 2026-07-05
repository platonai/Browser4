package ai.platon.pulsar.chrome.dom.model

import ai.platon.browser4.chrome.dom.util.ScrollUtils
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class ScrollUtilsTest {

    @Test
    @DisplayName("isActuallyScrollable returns true when scroll rect exceeds client rect")
    fun isActuallyScrollableReturnsTrueWhenScrollRectExceedsClientRect() {
        val node = MergedDOMTreeNode(
            nodeName = "DIV",
            snapshotNode = SnapshotNodeEx(
                computedStyles = mapOf("overflow" to "auto"),
                clientRects = DOMRect(0.0, 0.0, 200.0, 150.0),
                scrollRects = DOMRect(0.0, 0.0, 400.0, 300.0)
            )
        )

        assertTrue(ScrollUtils.isActuallyScrollable(node))
    }

    @Test
    @DisplayName("isActuallyScrollable returns false when overflow hidden")
    fun isActuallyScrollableReturnsFalseWhenOverflowHidden() {
        val node = MergedDOMTreeNode(
            nodeName = "DIV",
            snapshotNode = SnapshotNodeEx(
                computedStyles = mapOf("overflow" to "hidden"),
                clientRects = DOMRect(0.0, 0.0, 200.0, 150.0),
                scrollRects = DOMRect(0.0, 0.0, 400.0, 300.0)
            )
        )

        assertFalse(ScrollUtils.isActuallyScrollable(node))
    }

    @Test
    @DisplayName("shouldShowScrollInfo hides nested scroll containers")
    fun shouldShowScrollInfoHidesNestedScrollContainers() {
        val outer = MergedDOMTreeNode(
            nodeName = "DIV",
            snapshotNode = SnapshotNodeEx(
                computedStyles = mapOf("overflow" to "auto"),
                clientRects = DOMRect(0.0, 0.0, 300.0, 300.0),
                scrollRects = DOMRect(0.0, 0.0, 600.0, 600.0)
            )
        )
        val inner = MergedDOMTreeNode(
            nodeName = "DIV",
            snapshotNode = SnapshotNodeEx(
                computedStyles = mapOf("overflow" to "auto"),
                clientRects = DOMRect(0.0, 0.0, 150.0, 150.0),
                scrollRects = DOMRect(0.0, 0.0, 400.0, 400.0)
            )
        )

        assertTrue(ScrollUtils.shouldShowScrollInfo(outer, emptyList()))
        assertFalse(ScrollUtils.shouldShowScrollInfo(inner, listOf(outer)))
    }

    @Test
    @DisplayName("getScrollInfoText describes dominant scroll axes")
    fun getScrollInfoTextDescribesDominantScrollAxes() {
        val node = MergedDOMTreeNode(
            nodeName = "DIV",
            snapshotNode = SnapshotNodeEx(
                computedStyles = mapOf("overflow-x" to "auto", "overflow-y" to "hidden"),
                clientRects = DOMRect(0.0, 0.0, 150.0, 150.0),
                scrollRects = DOMRect(0.0, 0.0, 300.0, 150.0)
            )
        )

        val info = ScrollUtils.getScrollInfoText(node)
        assertEquals("scrollable (horizontal) [150x150 < 300x150]", info)
    }
}
