package ai.platon.pulsar.coding

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Tests for [CdpTrapCheck] — CDP pitfall awareness for browser-driver code.
 * Pure keyword scanning, always runs.
 */
class CdpTrapCheckTest {

    @Test
    @DisplayName("flags mouseWheel dispatch with the crbug advisory")
    fun mouseWheelTrap() {
        val src = """
            session.send(Input.dispatchMouseEvent, mapOf("type" to "mouseWheel"))
        """.trimIndent()
        val hits = CdpTrapCheck.check(src)
        val wheel = hits.firstOrNull { it.id == "crbug-444929150" }
        assertNotNull(wheel, "expected mouseWheel trap, got $hits")
        assertTrue(wheel!!.advisory.contains("passive: false"), wheel.advisory)
    }

    @Test
    @DisplayName("flags cursor-positioning when setSelectionRange is absent")
    fun cursorPositioningTrap() {
        val src = """
            DOM.focus(nodeId)
            session.send(Input.dispatchMouseEvent, mapOf("type" to "click"))
        """.trimIndent()
        val hits = CdpTrapCheck.check(src)
        val cursor = hits.firstOrNull { it.id == "cursor-positioning" }
        assertNotNull(cursor, "expected cursor-positioning trap, got $hits")
        assertTrue(cursor!!.advisory.contains("setSelectionRange"), cursor.advisory)
    }

    @Test
    @DisplayName("flags insertText without randomDelayMillis")
    fun insertTextRacingTrap() {
        val src = """
            for (ch in text) session.send(Input.insertText, mapOf("text" to ch.toString()))
        """.trimIndent()
        val hits = CdpTrapCheck.check(src)
        val racing = hits.firstOrNull { it.id == "insertText-racing" }
        assertNotNull(racing, "expected insertText-racing trap, got $hits")
        assertTrue(racing!!.advisory.contains("randomDelayMillis"), racing.advisory)
    }

    @Test
    @DisplayName("code that applies the fixes does not trigger traps")
    fun fixedCodeClean() {
        val src = """
            wheel.addEventListener("wheel", onWheel, { passive: false })
            element.setSelectionRange(99999, 99999)
            typeText(text, randomDelayMillis("type"))
        """.trimIndent()
        // setSelectionRange present → cursor trap silent; randomDelayMillis → insertText trap silent.
        assertTrue(CdpTrapCheck.check(src).none { it.id == "cursor-positioning" })
        assertTrue(CdpTrapCheck.check(src).none { it.id == "insertText-racing" })
    }

    @Test
    @DisplayName("unrelated file reports no traps")
    fun unrelatedFileClean() {
        val src = "val a = 1\nfun main() { println(a) }\n"
        assertTrue(CdpTrapCheck.check(src).isEmpty())
    }

    @Test
    @DisplayName("format returns a readable summary with trap ids")
    fun formatSummary() {
        val src = "Input.insertText with 0ms delay\n"
        val out = CdpTrapCheck.format(src)
        assertTrue(out.contains("CDP pitfalls detected"), out)
        assertTrue(out.contains("[insertText-racing]"), out)
    }

    @Test
    @DisplayName("format reports clean when nothing matches")
    fun formatClean() {
        val out = CdpTrapCheck.format("ordinary kotlin code\n")
        assertTrue(out.contains("No CDP pitfalls"), out)
    }
}
