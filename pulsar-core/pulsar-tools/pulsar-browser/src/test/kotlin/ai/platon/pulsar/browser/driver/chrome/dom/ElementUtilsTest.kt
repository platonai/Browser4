package ai.platon.pulsar.browser.driver.chrome.dom

import ai.platon.pulsar.browser.driver.chrome.dom.model.DOMRect
import ai.platon.pulsar.browser.driver.chrome.dom.model.DOMTreeNodeEx
import ai.platon.pulsar.browser.driver.chrome.dom.model.NodeType
import ai.platon.pulsar.browser.driver.chrome.dom.model.SnapshotNodeEx
import ai.platon.pulsar.browser.driver.chrome.dom.util.ElementUtils
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ElementUtilsTest {

    @Test
    fun `isVisible returns false for non-element nodes`() {
        val textNode = DOMTreeNodeEx(
            nodeType = NodeType.TEXT_NODE,
            nodeName = "#text",
            nodeValue = "Hello"
        )
        assertFalse(ElementUtils.isVisible(textNode))
    }

    @Test
    fun `isVisible returns false for element with display none`() {
        val node = DOMTreeNodeEx(
            nodeName = "DIV",
            snapshotNode = SnapshotNodeEx(
                computedStyles = mapOf("display" to "none"),
                clientRects = DOMRect(0.0, 0.0, 100.0, 50.0)
            )
        )
        assertFalse(ElementUtils.isVisible(node))
    }

    @Test
    fun `isVisible returns false for element with visibility hidden`() {
        val node = DOMTreeNodeEx(
            nodeName = "DIV",
            snapshotNode = SnapshotNodeEx(
                computedStyles = mapOf("visibility" to "hidden"),
                clientRects = DOMRect(0.0, 0.0, 100.0, 50.0)
            )
        )
        assertFalse(ElementUtils.isVisible(node))
    }

    @Test
    fun `isVisible returns false for element with zero opacity`() {
        val node = DOMTreeNodeEx(
            nodeName = "DIV",
            snapshotNode = SnapshotNodeEx(
                computedStyles = mapOf("opacity" to "0"),
                clientRects = DOMRect(0.0, 0.0, 100.0, 50.0)
            )
        )
        assertFalse(ElementUtils.isVisible(node))
    }

    @Test
    fun `isVisible returns false for element with zero dimensions`() {
        val node = DOMTreeNodeEx(
            nodeName = "DIV",
            snapshotNode = SnapshotNodeEx(
                computedStyles = mapOf("display" to "block"),
                clientRects = DOMRect(0.0, 0.0, 0.0, 0.0)
            )
        )
        assertFalse(ElementUtils.isVisible(node))
    }

    @Test
    fun `isVisible returns true for visible element`() {
        val node = DOMTreeNodeEx(
            nodeName = "DIV",
            snapshotNode = SnapshotNodeEx(
                computedStyles = mapOf("display" to "block", "visibility" to "visible"),
                clientRects = DOMRect(10.0, 20.0, 100.0, 50.0)
            )
        )
        assertTrue(ElementUtils.isVisible(node))
    }

    @Test
    fun `isInViewport returns true for element within viewport`() {
        val node = DOMTreeNodeEx(
            nodeName = "DIV",
            snapshotNode = SnapshotNodeEx(
                clientRects = DOMRect(100.0, 100.0, 200.0, 150.0)
            )
        )
        assertTrue(ElementUtils.isInViewport(node, 1920.0, 1080.0))
    }

    @Test
    fun `isInViewport returns false for element outside viewport`() {
        val node = DOMTreeNodeEx(
            nodeName = "DIV",
            snapshotNode = SnapshotNodeEx(
                clientRects = DOMRect(2000.0, 2000.0, 100.0, 100.0)
            )
        )
        assertFalse(ElementUtils.isInViewport(node, 1920.0, 1080.0))
    }

    @Test
    fun `boundsOverlap returns true for overlapping rectangles`() {
        val rect1 = DOMRect(0.0, 0.0, 100.0, 100.0)
        val rect2 = DOMRect(50.0, 50.0, 100.0, 100.0)
        assertTrue(ElementUtils.boundsOverlap(rect1, rect2))
    }

    @Test
    fun `boundsOverlap returns false for non-overlapping rectangles`() {
        val rect1 = DOMRect(0.0, 0.0, 50.0, 50.0)
        val rect2 = DOMRect(100.0, 100.0, 50.0, 50.0)
        assertFalse(ElementUtils.boundsOverlap(rect1, rect2))
    }

    @Test
    fun `intersectionArea calculates correct overlap area`() {
        val rect1 = DOMRect(0.0, 0.0, 100.0, 100.0)
        val rect2 = DOMRect(50.0, 50.0, 100.0, 100.0)
        val area = ElementUtils.intersectionArea(rect1, rect2)
        assertEquals(2500.0, area, 0.01)
    }

    @Test
    fun `intersectionArea returns zero for non-overlapping rectangles`() {
        val rect1 = DOMRect(0.0, 0.0, 50.0, 50.0)
        val rect2 = DOMRect(100.0, 100.0, 50.0, 50.0)
        val area = ElementUtils.intersectionArea(rect1, rect2)
        assertEquals(0.0, area, 0.01)
    }

    @Test
    fun `isInteractive returns true for button`() {
        val node = DOMTreeNodeEx(nodeName = "button")
        assertTrue(ElementUtils.isInteractive(node))
    }

    @Test
    fun `isInteractive returns true for element with onclick`() {
        val node = DOMTreeNodeEx(
            nodeName = "div",
            attributes = mapOf("onclick" to "handleClick()")
        )
        assertTrue(ElementUtils.isInteractive(node))
    }

    @Test
    fun `isInteractive returns true for element with button role`() {
        val node = DOMTreeNodeEx(
            nodeName = "div",
            attributes = mapOf("role" to "button")
        )
        assertTrue(ElementUtils.isInteractive(node))
    }

    @Test
    fun `isInteractive returns true for element with non-negative tabindex`() {
        val node = DOMTreeNodeEx(
            nodeName = "div",
            attributes = mapOf("tabindex" to "0")
        )
        assertTrue(ElementUtils.isInteractive(node))
    }

    @Test
    fun `isInteractive returns false for plain div`() {
        val node = DOMTreeNodeEx(nodeName = "div")
        assertFalse(ElementUtils.isInteractive(node))
    }

    @Test
    fun `getCenter returns correct center coordinates`() {
        val node = DOMTreeNodeEx(
            nodeName = "DIV",
            snapshotNode = SnapshotNodeEx(
                clientRects = DOMRect(100.0, 200.0, 50.0, 30.0)
            )
        )
        val center = ElementUtils.getCenter(node)
        assertNotNull(center)
        assertEquals(125.0, center!!.first, 0.01)
        assertEquals(215.0, center.second, 0.01)
    }

    @Test
    fun `findAncestor returns first matching ancestor`() {
        val grandparent = DOMTreeNodeEx(
            nodeName = "BODY",
            attributes = mapOf("class" to "container")
        )
        val parent = DOMTreeNodeEx(
            nodeName = "DIV"
        )
        val child = DOMTreeNodeEx(
            nodeName = "SPAN"
        )
        
        // Ancestors list: from immediate parent to root
        val ancestors = listOf(parent, grandparent)
        
        val found = ElementUtils.findAncestor(ancestors) { it.nodeName == "BODY" }
        assertNotNull(found)
        assertEquals("BODY", found?.nodeName)
    }

    @Test
    fun `findAncestor returns null if no match`() {
        val parent = DOMTreeNodeEx(nodeName = "DIV")
        val ancestors = listOf(parent)
        
        val found = ElementUtils.findAncestor(ancestors) { it.nodeName == "TABLE" }
        assertNull(found)
    }

    @Test
    fun `getDescendants returns all descendants`() {
        val child1 = DOMTreeNodeEx(nodeName = "SPAN")
        val child2 = DOMTreeNodeEx(nodeName = "A")
        val parent = DOMTreeNodeEx(
            nodeName = "DIV",
            children = listOf(child1, child2)
        )
        
        val descendants = ElementUtils.getDescendants(parent)
        assertEquals(2, descendants.size)
    }

    @Test
    fun `findDescendants returns matching descendants`() {
        val span = DOMTreeNodeEx(nodeName = "SPAN")
        val link = DOMTreeNodeEx(nodeName = "A")
        val div = DOMTreeNodeEx(nodeName = "DIV")
        val parent = DOMTreeNodeEx(
            nodeName = "SECTION",
            children = listOf(span, link, div)
        )
        
        val found = ElementUtils.findDescendants(parent, predicate = { it.nodeName == "A" })
        assertEquals(1, found.size)
        assertEquals("A", found[0].nodeName)
    }

    @Test
    fun `hasTextContent returns true for text node`() {
        val textNode = DOMTreeNodeEx(
            nodeType = NodeType.TEXT_NODE,
            nodeName = "#text",
            nodeValue = "Hello"
        )
        assertTrue(ElementUtils.hasTextContent(textNode))
    }

    @Test
    fun `hasTextContent returns false for blank text node`() {
        val textNode = DOMTreeNodeEx(
            nodeType = NodeType.TEXT_NODE,
            nodeName = "#text",
            nodeValue = "   "
        )
        assertFalse(ElementUtils.hasTextContent(textNode))
    }

    @Test
    fun `hasTextContent returns true for element with text children`() {
        val textNode = DOMTreeNodeEx(
            nodeType = NodeType.TEXT_NODE,
            nodeName = "#text",
            nodeValue = "Content"
        )
        val element = DOMTreeNodeEx(
            nodeName = "DIV",
            children = listOf(textNode)
        )
        assertTrue(ElementUtils.hasTextContent(element))
    }

    @Test
    fun `getTextContent extracts text from node and descendants`() {
        val text1 = DOMTreeNodeEx(
            nodeType = NodeType.TEXT_NODE,
            nodeName = "#text",
            nodeValue = "Hello"
        )
        val text2 = DOMTreeNodeEx(
            nodeType = NodeType.TEXT_NODE,
            nodeName = "#text",
            nodeValue = "World"
        )
        val span = DOMTreeNodeEx(
            nodeName = "SPAN",
            children = listOf(text2)
        )
        val div = DOMTreeNodeEx(
            nodeName = "DIV",
            children = listOf(text1, span)
        )
        
        val text = ElementUtils.getTextContent(div)
        assertTrue(text.contains("Hello"))
        assertTrue(text.contains("World"))
    }

    @Test
    fun `getTextContent respects maxLength`() {
        val text1 = DOMTreeNodeEx(
            nodeType = NodeType.TEXT_NODE,
            nodeName = "#text",
            nodeValue = "Hello World This Is A Long Text"
        )
        val div = DOMTreeNodeEx(
            nodeName = "DIV",
            children = listOf(text1)
        )
        
        val text = ElementUtils.getTextContent(div, maxLength = 10)
        assertTrue(text.length <= 10)
    }
}
