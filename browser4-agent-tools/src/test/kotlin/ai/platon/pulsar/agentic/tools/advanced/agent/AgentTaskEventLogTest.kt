package ai.platon.pulsar.agentic.tools.advanced.agent

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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

    @Test
    @DisplayName("an unpaired surrogate can never reach the wire, whichever field carries it")
    fun logKeepsEveryFieldStrictlySerializable() {
        val log = AgentTaskEventLog()
        // A provider can hand back a lone surrogate in any string it produced;
        // `text`/`arguments` are previewed by the sanitizer, `type`/`tool` are not.
        val event = log.append(
            type = "tool.call\uD83D",
            tool = "coding.write\uDE00",
            text = "half an emoji \uD83D",
        )

        assertEquals("tool.call\uFFFD", event.type)
        assertEquals("coding.write\uFFFD", event.tool)
        assertEquals("half an emoji \uFFFD", event.text, "a previewed field is repaired too")

        val json = String(
            ai.platon.pulsar.common.serialize.json.pulsarObjectMapper().writeValueAsBytes(event),
            Charsets.UTF_8,
        )
        assertFalse(
            Regex("""\\u[dD][89abAB][0-9a-fA-F]{2}""").containsMatchIn(json),
            "an unpaired surrogate escape breaks strict JSON readers (issue #620): $json",
        )
    }

    // ---- aggregate run statistics (the interactive `/stats` command) --------

    @Test
    @DisplayName("stats aggregate rounds, requests, tools, failures, steering and tokens")
    fun statsAggregateTheRunCounters() {
        val log = AgentTaskEventLog()
        log.append(AgentTaskEvent.TYPE_CREATED)
        log.append(AgentTaskEvent.TYPE_STARTED, text = "walk the site")
        log.append(AgentTaskEvent.TYPE_ROUND)
        log.append(
            AgentTaskEvent.TYPE_USAGE,
            inputTokens = 1_000,
            outputTokens = 200,
            totalTokens = 1_200,
        )
        log.append(AgentTaskEvent.TYPE_TOOL_CALL, tool = "b4.run")
        log.append(AgentTaskEvent.TYPE_TOOL_RESULT, tool = "b4.run", ok = true, durationMs = 20)
        log.append(AgentTaskEvent.TYPE_ROUND)
        // A provider that reports input/output but no total: accounted as their sum.
        log.append(AgentTaskEvent.TYPE_USAGE, inputTokens = 500, outputTokens = 50)
        // A provider that reports no usage at all: the request still happened.
        log.append(AgentTaskEvent.TYPE_USAGE)
        log.append(AgentTaskEvent.TYPE_TOOL_CALL, tool = "b4.run")
        log.append(AgentTaskEvent.TYPE_TOOL_RESULT, tool = "b4.run", ok = false, text = "boom")
        log.append(AgentTaskEvent.TYPE_TOOL_CALL, tool = "browser_snapshot")
        log.append(AgentTaskEvent.TYPE_STEER, text = "also the enterprise tier")

        val stats = log.stats()

        assertEquals(13, stats.events)
        assertEquals(0, stats.droppedEvents)
        assertEquals(2, stats.rounds)
        assertEquals(3, stats.modelRequests)
        assertEquals(3, stats.toolCalls)
        assertEquals(1, stats.toolFailures, "only the failed tool counts")
        assertEquals(1, stats.steerMessages)
        assertEquals(1_500, stats.inputTokens)
        assertEquals(250, stats.outputTokens)
        assertEquals(1_750, stats.totalTokens, "1200 + (500+50) + 0 for the usage-less request")
        assertEquals(mapOf("b4.run" to 2L, "browser_snapshot" to 1L), stats.toolCounts)
        assertTrue(stats.tokensReported)
    }

    @Test
    @DisplayName("stats keep counting steps the bounded buffer already evicted")
    fun statsOutliveTheBuffer() {
        val log = AgentTaskEventLog(capacity = 2)
        repeat(5) { log.append(AgentTaskEvent.TYPE_TOOL_CALL, tool = "coding.read") }

        val stats = log.stats()

        assertEquals(5, stats.toolCalls, "the counters are not bounded by the retained events")
        assertEquals(5, stats.events)
        assertEquals(3, stats.droppedEvents)
        assertEquals(2, log.size())
        assertEquals(mapOf("coding.read" to 5L), stats.toolCounts)
    }

    @Test
    @DisplayName("a report with no provider usage says so instead of claiming zero tokens")
    fun statsReportWhetherTokensWereReported() {
        val log = AgentTaskEventLog()
        log.append(AgentTaskEvent.TYPE_USAGE)

        val stats = log.stats()

        assertEquals(1, stats.modelRequests)
        assertEquals(0, stats.totalTokens)
        assertFalse(stats.tokensReported, "0 tokens and 'the provider said nothing' differ")
    }

    @Test
    @DisplayName("stats track the first and newest step timestamps")
    fun statsTrackTheRunTimeSpan() {
        val log = AgentTaskEventLog()
        log.append(AgentTaskEvent.TYPE_CREATED, epochMillis = 1_700_000_000_000)
        log.append(AgentTaskEvent.TYPE_DONE, epochMillis = 1_700_000_041_300)

        val stats = log.stats()

        assertEquals(1_700_000_000_000, stats.firstEpochMillis)
        assertEquals(1_700_000_041_300, stats.lastEpochMillis)
    }
}
