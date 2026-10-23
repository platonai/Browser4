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
    @DisplayName("value is sanitized (whitespace collapsed and truncated)")
    fun testValueSanitized() {
        val pad = TaskScratchpad()
        pad.note("k", "  abc  def  ".repeat(60)) // whitespace + length
        val value = pad.get("k")!!
        assertTrue(value.length <= TaskScratchpad.VALUE_MAX_CHARS)
        assertFalse(value.contains("  "))
    }

    @Test
    @DisplayName("a note longer than the cap is truncated with an explicit notice")
    fun testTruncationIsReported() {
        // The old code cut the value at 200 chars and answered "Note saved: <key>",
        // so a refactor plan silently turned into a dangling fragment.
        val pad = TaskScratchpad(maxChars = 10_000)
        val long = "Target: Browser4WebDriver.kt " + "y".repeat(2_000)
        val confirmation = pad.note("refactor-state", long)

        assertTrue(confirmation.contains("truncated"), confirmation)
        assertEquals(TaskScratchpad.VALUE_MAX_CHARS, pad.get("refactor-state")!!.length)
        // A note that fits is saved verbatim without a truncation notice.
        assertEquals("Note saved: goal", pad.note("goal", "confirm login flow"))
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
