package ai.platon.pulsar.agentic.memory

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@DisplayName("TaskScratchpad")
class TaskScratchpadTest {

    @Test
    @DisplayName("note/get/render round-trip")
    fun testNoteRoundTrip() {
        val pad = TaskScratchpad()
        pad.note("goal", "confirm login flow")
        pad.note("assumption", "page uses shadow DOM")

        assertEquals("confirm login flow", pad.get("goal"))
        val rendered = pad.render()
        assertTrue(rendered!!.contains("## Task Scratchpad"))
        assertTrue(rendered.contains("- goal: confirm login flow"))
        assertTrue(rendered.contains("- assumption: page uses shadow DOM"))
    }

    @Test
    @DisplayName("rejects invalid keys")
    fun testInvalidKey() {
        val pad = TaskScratchpad()
        assertThrows<IllegalArgumentException> { pad.note("bad key!", "v") }
        assertThrows<IllegalArgumentException> { pad.note("", "v") }
        assertThrows<IllegalArgumentException> { pad.note("x".repeat(33), "v") }
    }

    @Test
    @DisplayName("evicts LRU entries when over budget")
    fun testEviction() {
        val pad = TaskScratchpad(maxChars = 200)
        // Each entry costs ~key(6) + value(50) + 4 chars; 20 entries far exceed 200.
        repeat(20) { idx -> pad.note("key%02d".format(idx), "value-" + "x".repeat(45)) }
        assertTrue(pad.size() < 20, "LRU eviction should have kicked in")
        assertNull(pad.get("key00"), "oldest entry must be evicted first")
    }

    @Test
    @DisplayName("value is sanitized (masked and truncated)")
    fun testValueSanitized() {
        val pad = TaskScratchpad()
        pad.note("k", "  abc  def  ".repeat(60)) // whitespace + length
        val value = pad.get("k")!!
        assertTrue(value.length <= 200)
        assertFalse(value.contains("  "))
    }

    @Test
    @DisplayName("render is null when empty; clear resets")
    fun testEmptyAndClear() {
        val pad = TaskScratchpad()
        assertNull(pad.render())
        pad.note("k", "v")
        pad.clear()
        assertNull(pad.render())
    }

    @Test
    @DisplayName("note overwrites same key and moves it to the tail")
    fun testOverwrite() {
        val pad = TaskScratchpad(maxChars = 300)
        pad.note("a", "1")
        pad.note("b", "2")
        pad.note("a", "updated")
        assertEquals("updated", pad.get("a"))
        assertEquals(2, pad.size())
    }
}
