package ai.platon.pulsar.chrome

import ai.platon.pulsar.common.serialize.json.pulsarObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Tests for [ConsoleMessageBuffer], the CDP-fed replacement for the page-side console wrapper.
 */
@DisplayName("Console message buffer")
class ConsoleMessageBufferTest {

    @Test
    @DisplayName("levels are normalized to the vocabulary the CLI used")
    fun testNormalizeLevel() {
        // `Console.messageAdded` reports `warning`, the historical page-side filter used `warn`.
        assertEquals("warn", ConsoleMessageBuffer.normalizeLevel("warning"))
        assertEquals("warn", ConsoleMessageBuffer.normalizeLevel("WARNING"))
        assertEquals("warn", ConsoleMessageBuffer.normalizeLevel(" Warn "))
        assertEquals("log", ConsoleMessageBuffer.normalizeLevel("LOG"))
        assertEquals("error", ConsoleMessageBuffer.normalizeLevel("error"))
        assertEquals("debug", ConsoleMessageBuffer.normalizeLevel("debug"))
        assertEquals("info", ConsoleMessageBuffer.normalizeLevel("info"))
        // A missing level must not drop the message: it becomes a plain log.
        assertEquals("log", ConsoleMessageBuffer.normalizeLevel(null))
        assertEquals("log", ConsoleMessageBuffer.normalizeLevel(""))
    }

    @Test
    @DisplayName("level priorities match the historical page-side filter")
    fun testLevelPriorities() {
        assertEquals(0, ConsoleMessageBuffer.levelPriority("error"))
        assertEquals(1, ConsoleMessageBuffer.levelPriority("warn"))
        assertEquals(1, ConsoleMessageBuffer.levelPriority("warning"))
        assertEquals(2, ConsoleMessageBuffer.levelPriority("info"))
        assertEquals(2, ConsoleMessageBuffer.levelPriority("log"))
        assertEquals(3, ConsoleMessageBuffer.levelPriority("debug"))
        // Unknown levels behaved like `info` before, and still do.
        assertEquals(2, ConsoleMessageBuffer.levelPriority("verbose"))
    }

    @Test
    @DisplayName("messages are buffered in order and empty ones are dropped")
    fun testAddKeepsOrderAndSkipsEmptyMessages() {
        val buffer = ConsoleMessageBuffer()
        assertTrue(buffer.add("log", "first"))
        assertFalse(buffer.add("log", null), "a message without text is not buffered")
        assertFalse(buffer.add("log", ""), "a message without text is not buffered")
        assertTrue(buffer.add("WARNING", "second"))

        assertEquals(2, buffer.size())
        val snapshot = buffer.snapshot("debug")
        assertEquals(listOf("first", "second"), snapshot.map { it.text })
        assertEquals(listOf("log", "warn"), snapshot.map { it.level })
    }

    @Test
    @DisplayName("the buffer is bounded and drops the oldest messages first")
    fun testBufferIsBounded() {
        val buffer = ConsoleMessageBuffer(limit = 3)
        (1..5).forEach { buffer.add("log", "message-$it") }

        assertEquals(3, buffer.size())
        assertEquals(listOf("message-3", "message-4", "message-5"), buffer.snapshot("debug").map { it.text })
    }

    @Test
    @DisplayName("snapshot filters by minimum level")
    fun testSnapshotFiltersByLevel() {
        val buffer = ConsoleMessageBuffer()
        buffer.add("debug", "debug-1")
        buffer.add("log", "log-1")
        buffer.add("info", "info-1")
        buffer.add("warning", "warn-1")
        buffer.add("error", "error-1")

        assertEquals(
            listOf("debug-1", "log-1", "info-1", "warn-1", "error-1"),
            buffer.snapshot("debug").map { it.text },
        )
        assertEquals(listOf("log-1", "info-1", "warn-1", "error-1"), buffer.snapshot("info").map { it.text })
        assertEquals(listOf("warn-1", "error-1"), buffer.snapshot("warn").map { it.text })
        assertEquals(listOf("error-1"), buffer.snapshot("error").map { it.text })
    }

    @Test
    @DisplayName("clear drops every buffered message")
    fun testClear() {
        val buffer = ConsoleMessageBuffer()
        buffer.add("log", "one")
        buffer.add("error", "two")

        buffer.clear()

        assertEquals(0, buffer.size())
        assertTrue(buffer.snapshot("debug").isEmpty())
    }

    @Test
    @DisplayName("toJson keeps the shape the CLI consumed before this change")
    fun testToJson() {
        val buffer = ConsoleMessageBuffer()
        buffer.add("log", "hello", timestamp = 1_700_000_000_000)
        buffer.add("warning", "careful", timestamp = 1_700_000_000_001)
        buffer.add("error", "boom", timestamp = 1_700_000_000_002)

        val node = pulsarObjectMapper().readTree(buffer.toJson("warn"))

        assertEquals(2, node.size(), "only warn and above: $node")
        assertEquals("warn", node[0]["level"].asText(), "warning is normalized: $node")
        assertEquals("careful", node[0]["text"].asText())
        assertEquals(1_700_000_000_001, node[0]["timestamp"].asLong())
        assertEquals("error", node[1]["level"].asText())
        assertEquals("boom", node[1]["text"].asText())
    }
}
