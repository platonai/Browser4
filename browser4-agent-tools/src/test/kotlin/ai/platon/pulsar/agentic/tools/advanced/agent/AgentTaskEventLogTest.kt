package ai.platon.pulsar.agentic.tools.advanced.agent

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@Tag("Unit")
@Tag("Fast")
class AgentTaskEventLogTest {

    @Test
    @DisplayName("append assigns strictly increasing sequence numbers from 1")
    fun appendAssignsMonotonicSequenceNumbers() {
        val log = AgentTaskEventLog()
        val first = log.append(AgentTaskEvent.TYPE_CREATED)
        val second = log.append(AgentTaskEvent.TYPE_STARTED, text = "task")
        val third = log.append(AgentTaskEvent.TYPE_TOOL_CALL, tool = "coding.read", arguments = "{}")

        assertEquals(1L, first.seq)
        assertEquals(2L, second.seq)
        assertEquals(3L, third.seq)
        assertEquals(3L, log.lastSeq())
        assertEquals(3, log.size())
    }

    @Test
    @DisplayName("eventsAfter returns only newer events, oldest first")
    fun eventsAfterFiltersByCursor() {
        val log = AgentTaskEventLog()
        repeat(5) { index -> log.append("event.$index") }

        val page = log.eventsAfter(2)
        assertEquals(listOf(3L, 4L, 5L), page.map { it.seq })
        assertEquals(listOf("event.2", "event.3", "event.4"), page.map { it.type })
        assertTrue(log.eventsAfter(5).isEmpty(), "a caught-up cursor sees nothing")
        assertEquals(5, log.eventsAfter(0, limit = 100).size)
    }

    @Test
    @DisplayName("eventsAfter honors the page limit")
    fun eventsAfterHonorsLimit() {
        val log = AgentTaskEventLog()
        repeat(10) { log.append("event") }

        assertEquals(3, log.eventsAfter(0, limit = 3).size)
        assertEquals(listOf(9L, 10L), log.eventsAfter(8, limit = 3).map { it.seq })
        assertTrue(log.eventsAfter(0, limit = 0).isEmpty(), "limit 0 must return nothing")
    }

    @Test
    @DisplayName("the buffer drops the oldest events but keeps the cursor moving")
    fun bufferEvictsOldestEvents() {
        val log = AgentTaskEventLog(capacity = 3)
        repeat(5) { log.append("event.$it") }

        assertEquals(3, log.size())
        assertEquals(2L, log.droppedCount())
        assertEquals(5L, log.lastSeq(), "the cursor keeps growing past the buffer")
        // The retained tail is what a late follower gets — the gap is reported
        // through droppedCount(), never as an error.
        assertEquals(listOf(3L, 4L, 5L), log.eventsAfter(0).map { it.seq })
        assertEquals(listOf(4L, 5L), log.eventsAfter(3).map { it.seq })
    }

    @Test
    @DisplayName("full events keep their payload and timestamp")
    fun appendKeepsPayloadAndTimestamp() {
        val log = AgentTaskEventLog()
        val event = log.append(
            type = AgentTaskEvent.TYPE_TOOL_RESULT,
            tool = "coding.shell",
            arguments = """{"cmd":"ls"}""",
            ok = false,
            durationMs = 1_200,
            text = "exit 1",
            turn = 3,
            epochMillis = 1_700_000_000_000,
        )

        assertEquals(AgentTaskEvent.TYPE_TOOL_RESULT, event.type)
        assertEquals("coding.shell", event.tool)
        assertEquals(false, event.ok)
        assertEquals(1_200L, event.durationMs)
        assertEquals("exit 1", event.text)
        assertEquals(3, event.turn)
        assertEquals(1_700_000_000_000, event.epochMillis)
    }

    @Test
    @DisplayName("a non-positive capacity is rejected loudly")
    fun rejectsNonPositiveCapacity() {
        assertFailsWith<IllegalArgumentException> { AgentTaskEventLog(capacity = 0) }
    }
}
