package ai.platon.pulsar.test.self

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.util.regex.Pattern
import org.junit.jupiter.api.DisplayName

@Tag("TestInfraCheck")
class AccessibilityAndDragDropTest {

    private fun readResource(path: String): String {
        val url = javaClass.classLoader.getResource("static/$path")
            ?: fail("Resource not found: $path")
        return url.openStream().use { it.readBytes().toString(StandardCharsets.UTF_8) }
    }

    private val interactiveFiles = listOf(
        "generated/interactive-1.html",
        "generated/interactive-2.html",
        "generated/interactive-3.html",
        "generated/interactive-4.html",
        "generated/interactive-screens.html"
    )

    @Test
        @DisplayName("buttons with data-action have aria-label and role semantics")
    fun buttonsWithDataActionHaveAriaLabelAndRoleSemantics() {
        val buttonPattern = Pattern.compile("<button[^>]*data-action=\"([^\"]+)\"[\\s\\S]*?>")
        interactiveFiles.forEach { file ->
            val html = readResource(file)
            val m = buttonPattern.matcher(html)
            var found = 0
            while (m.find()) {
                found++
                val snippet = m.group()!!
                assertTrue(snippet.contains("aria-label="), "Button with data-action='${m.group(1)}' in $file missing aria-label")
            }
            assertTrue(found > 0, "Expected at least one actionable button in $file")
        }
    }

    @Test
        @DisplayName("toggle message buttons manage aria-expanded and controls attribute")
    fun toggleMessageButtonsManageAriaExpandedAndControlsAttribute() {
        val html = readResource("generated/interactive-1.html") + readResource("generated/interactive-screens.html")
        assertTrue(html.contains("id=\"toggleMessageButton\""), "Missing toggleMessageButton id")
        val pattern = Pattern.compile("<button[^>]*id=\"toggleMessageButton\"[^>]*>")
        val m = pattern.matcher(html)
        var count = 0
        while (m.find()) {
            val btn = m.group()!!
            assertTrue(btn.contains("aria-controls=\"hiddenMessage\""), "toggleMessageButton missing aria-controls")
            assertTrue(btn.contains("aria-expanded="), "toggleMessageButton missing aria-expanded")
            count++
        }
        assertTrue(count >= 2, "Expected at least two toggleMessageButton occurrences across pages")
    }

    @Test
        @DisplayName("hidden message elements have aria-hidden role region")
    fun hiddenMessageElementsHaveAriaHiddenRoleRegion() {
        val pattern = Pattern.compile("<p[^>]*id=\"hiddenMessage\"[^>]*>")
        val aggregated = interactiveFiles.joinToString("\n") { readResource(it) }
        val m = pattern.matcher(aggregated)
        var n = 0
        while (m.find()) {
            val tag = m.group()!!
            assertTrue(tag.contains("aria-hidden="), "hiddenMessage missing aria-hidden")
            assertTrue(tag.contains("role=\"region\""), "hiddenMessage missing role region")
            n++
        }
        assertTrue(n >= 2, "Expected hiddenMessage in multiple pages")
    }

    @Test
        @DisplayName("drag-drop list items have sequential data-order and unique ids")
    fun dragDropListItemsHaveSequentialDataOrderAndUniqueIds() {
        val html = readResource("generated/interactive-4.html")
        // Capture list items
        val itemPattern = Pattern.compile("<li[^>]*data-order=\"(\\d+)\"[^>]*>([\\s\\S]*?)</li>")
        val m = itemPattern.matcher(html)
        val orders = mutableListOf<Int>()
        val texts = mutableListOf<String>()
        while (m.find()) {
            orders += m.group(1).toInt()
            texts += m.group(2).trim()
        }
        assertTrue(orders.isNotEmpty(), "No draggable list items found")
        val sorted = orders.sorted()
        assertEquals(sorted, orders, "data-order values should already be in ascending order")
        assertEquals((1..orders.size).toList(), orders, "data-order should be contiguous starting at 1")
        // Simulate reordering: move last to first
        val reordered = listOf(orders.last()) + orders.dropLast(1)
        // Validate that a hypothetical reorder results in same set
        assertEquals(orders.toSet(), reordered.toSet(), "Reordered set must match original set")
        assertEquals(texts.size, texts.toSet().size, "List item texts should be unique for clarity")
    }

    @Test
        @DisplayName("data-role and data-component exist on structural sections")
    fun dataRoleAndDataComponentExistOnStructuralSections() {
        val sectionPattern = Pattern.compile("<(header|section)[^>]*id=\"([^\"]+)\"[^>]*>")
        interactiveFiles.forEach { file ->
            val html = readResource(file)
            val m = sectionPattern.matcher(html)
            while (m.find()) {
                val tag = m.group()!!
                assertTrue(tag.contains("data-role="), "Structural element ${m.group(2)} in $file missing data-role")
                assertTrue(tag.contains("data-component="), "Structural element ${m.group(2)} in $file missing data-component")
            }
        }
    }
}

