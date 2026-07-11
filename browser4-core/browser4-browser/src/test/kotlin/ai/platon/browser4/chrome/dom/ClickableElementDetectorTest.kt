package ai.platon.browser4.chrome.dom

import ai.platon.browser4.api.model.DOMRect
import ai.platon.browser4.api.model.MergedDOMTreeNode
import ai.platon.browser4.api.model.SnapshotNodeEx
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class ClickableElementDetectorTest {

    private val detector = ClickableElementDetector()

    @Test
        @DisplayName("button tag is interactive")
    fun buttonTagIsInteractive() {
        val node = MergedDOMTreeNode(nodeName = "button")
        assertTrue(detector.isInteractive(node))
    }

    @Test
        @DisplayName("div with onclick is interactive")
    fun divWithOnclickIsInteractive() {
        val node = MergedDOMTreeNode(
            nodeName = "div",
            attributes = mapOf("onclick" to "doIt()")
        )
        assertTrue(detector.isInteractive(node))
    }

    @Test
        @DisplayName("div with role button is interactive")
    fun divWithRoleButtonIsInteractive() {
        val node = MergedDOMTreeNode(
            nodeName = "div",
            attributes = mapOf("role" to "button")
        )
        assertTrue(detector.isInteractive(node))
    }

    @Test
        @DisplayName("small iframe is not interactive, large iframe is")
    fun smallIframeIsNotInteractiveLargeIframeIs() {
        val small = MergedDOMTreeNode(
            nodeName = "iframe",
            snapshotNode = SnapshotNodeEx(bounds = DOMRect(0.0, 0.0, 80.0, 80.0))
        )
        val large = MergedDOMTreeNode(
            nodeName = "iframe",
            snapshotNode = SnapshotNodeEx(bounds = DOMRect(0.0, 0.0, 200.0, 200.0))
        )
        assertFalse(detector.isInteractive(small))
        assertTrue(detector.isInteractive(large))
    }

    @Test
        @DisplayName("html and body are not interactive")
    fun htmlAndBodyAreNotInteractive() {
        assertFalse(detector.isInteractive(MergedDOMTreeNode(nodeName = "html")))
        assertFalse(detector.isInteractive(MergedDOMTreeNode(nodeName = "body")))
    }

    @Test
        @DisplayName("cursor pointer implies interactive")
    fun cursorPointerImpliesInteractive() {
        val node = MergedDOMTreeNode(
            nodeName = "span",
            snapshotNode = SnapshotNodeEx(cursorStyle = "pointer")
        )
        assertTrue(detector.isInteractive(node))
    }

    @Test
        @DisplayName("icon sized element with aria-label is interactive")
    fun iconSizedElementWithAriaLabelIsInteractive() {
        val node = MergedDOMTreeNode(
            nodeName = "span",
            attributes = mapOf("aria-label" to "open"),
            snapshotNode = SnapshotNodeEx(bounds = DOMRect(0.0, 0.0, 20.0, 20.0))
        )
        assertTrue(detector.isInteractive(node))
    }
}

