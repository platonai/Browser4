package ai.platon.browser4.driver.chrome.dom

import ai.platon.browser4.driver.chrome.dom.model.DOMRect
import ai.platon.browser4.driver.chrome.dom.model.MergedDOMTreeNode
import ai.platon.browser4.driver.chrome.dom.model.SnapshotNodeEx
import ai.platon.browser4.driver.chrome.dom.util.ScrollUtils
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.DisplayName

class ScrollUtilsTest {

    @Test
        @DisplayName("isActuallyScrollable returns true when scroll rect exceeds client rect")
    fun isactuallyscrollableReturnsTrueWhenScrollRectExceedsClientRect() {
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
    fun isactuallyscrollableReturnsFalseWhenOverflowHidden() {
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
    fun shouldshowscrollinfoHidesNestedScrollContainers() {
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
    fun getscrollinfotextDescribesDominantScrollAxes() {
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
