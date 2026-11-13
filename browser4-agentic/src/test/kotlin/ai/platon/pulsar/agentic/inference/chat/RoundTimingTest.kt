package ai.platon.pulsar.agentic.inference.chat

import ai.platon.pulsar.agentic.memory.AgentEventLog
import ai.platon.pulsar.agentic.memory.AgentMemorySink
import ai.platon.pulsar.agentic.memory.EventBuffer
import ai.platon.pulsar.agentic.memory.RoundTimed
import ai.platon.pulsar.agentic.memory.toJson
import ai.platon.pulsar.agentic.memory.toMemoryEvent
import ai.platon.pulsar.agentic.tools.langchain4j.ToolExecutionCoordinator
import ai.platon.pulsar.external.BrowserChatModel
import dev.langchain4j.agent.tool.ToolExecutionRequest
import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.ToolExecutionResultMessage
import dev.langchain4j.data.message.UserMessage
import dev.langchain4j.model.chat.request.ChatRequest
import dev.langchain4j.model.chat.response.ChatResponse
import dev.langchain4j.model.openai.OpenAiTokenUsage
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * P0-0: a tool round must report where its wall clock went.
 *
 * The efficiency plan ranked "reduce per-call overhead" and "remove serialization" without
 * being able to tell either apart from model latency, because the recorded buckets were
 * `build` / `tool` / everything-else. These tests pin the split:
 * `modelMs` (provider round-trip) + `toolsMs` (tool bodies) + `callbacksMs` (the loop's own
 * per-call plumbing) — and the `round_timing` memory event that carries it off-process.
 */
@Tag("Fast")
@DisplayName("Round timing attribution (P0-0): model / tools / callbacks")
class RoundTimingTest {

    private fun toolRequest(id: String, name: String = "coding.read"): ToolExecutionRequest =
        ToolExecutionRequest.builder().id(id).name(name).arguments("{}").build()

    private fun chatResponseOf(aiMessage: AiMessage): ChatResponse =
        ChatResponse.builder().aiMessage(aiMessage).build()

    private fun loop(
        model: BrowserChatModel,
        coordinator: ToolExecutionCoordinator,
        onToolRequest: (String, String) -> Unit = { _, _ -> },
        onRoundTiming: (RoundTiming) -> Unit,
    ) = AgentToolCallLoop(
        model = model,
        toolSpecifications = emptyList(),
        coordinator = coordinator,
        maxIterations = 10,
        onToolRequest = onToolRequest,
        onRoundTiming = onRoundTiming,
    )

    @Test
    @DisplayName("one round reports its calls, the tool time and the callback time separately")
    fun roundIsAttributed() = runBlocking {
        val model = mockk<BrowserChatModel>()
        val coordinator = mockk<ToolExecutionCoordinator>()
        val requests = (1..4).map { toolRequest("c$it") }
        coEvery { model.langChainChat(any<ChatRequest>(), any()) } returnsMany listOf(
            chatResponseOf(AiMessage.from("working", requests)),
            chatResponseOf(AiMessage.from("done")),
        )
        every { coordinator.execute(any()) } answers {
            Thread.sleep(25)
            val request = firstArg<ToolExecutionRequest>()
            ToolExecutionResultMessage.from(request.id() ?: "", request.name(), "ok")
        }

        val timings = mutableListOf<RoundTiming>()
        val startedAt = System.nanoTime()
        loop(
            model, coordinator,
            onToolRequest = { _, _ -> Thread.sleep(5) },
            onRoundTiming = { timings += it },
        ).generate(listOf(UserMessage.from("do it")))
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000

        assertEquals(2, timings.size, "the tool round plus the closing round: $timings")
        val timing = timings.first()
        assertEquals(1, timing.round)
        assertEquals(4, timing.calls)
        // 4 × 25 ms of tool bodies — allow for timer granularity, not for a missing bucket.
        assertTrue(timing.toolsMs >= 60, "toolsMs must contain the tool bodies: $timing")
        // 4 × 5 ms of observer work.
        assertTrue(timing.callbacksMs >= 8, "callbacksMs must contain the observers: $timing")
        assertTrue(timing.modelMs >= 0)
        assertEquals(timing.modelMs + timing.toolsMs + timing.callbacksMs, timing.totalMs)
        // The three buckets are inside the round, so their sum cannot exceed the wall clock.
        assertTrue(timing.totalMs <= elapsedMs + 5, "buckets ${timing.totalMs}ms vs wall ${elapsedMs}ms")
    }

    @Test
    @DisplayName("each tool round is reported once, in order, and the closing round is measured too")
    fun everyRoundIsReported() = runBlocking {
        val model = mockk<BrowserChatModel>()
        val coordinator = mockk<ToolExecutionCoordinator>()
        coEvery { model.langChainChat(any<ChatRequest>(), any()) } returnsMany listOf(
            chatResponseOf(AiMessage.from("r1", listOf(toolRequest("a1")))),
            chatResponseOf(AiMessage.from("r2", listOf(toolRequest("a2"), toolRequest("a3")))),
            chatResponseOf(AiMessage.from("done")),
        )
        every { coordinator.execute(any()) } returns
            ToolExecutionResultMessage.from("x", "coding.read", "ok")

        val timings = mutableListOf<RoundTiming>()
        loop(model, coordinator, onRoundTiming = { timings += it })
            .generate(listOf(UserMessage.from("do it")))

        // Round 3 is the text-only closing round: calls = 0, model time still counted.
        assertEquals(listOf(1, 2, 3), timings.map { it.round })
        assertEquals(listOf(1, 2, 0), timings.map { it.calls })
        assertEquals(0L, timings.last().toolsMs, "the closing round runs no tools")
        assertEquals(0L, timings.last().callbacksMs, "the closing round runs no tool callbacks")
    }

    @Test
    @DisplayName("a throwing timing observer never fails the task")
    fun observerFailureIsContained() = runBlocking {
        val model = mockk<BrowserChatModel>()
        val coordinator = mockk<ToolExecutionCoordinator>()
        coEvery { model.langChainChat(any<ChatRequest>(), any()) } returnsMany listOf(
            chatResponseOf(AiMessage.from("r1", listOf(toolRequest("a1")))),
            chatResponseOf(AiMessage.from("done")),
        )
        every { coordinator.execute(any()) } returns
            ToolExecutionResultMessage.from("x", "coding.read", "ok")

        var seen = 0
        val response = loop(model, coordinator, onRoundTiming = {
            seen++
            error("observer exploded")
        }).generate(listOf(UserMessage.from("do it")))

        assertEquals(2, seen, "the observer ran for the tool round and the closing round")
        assertEquals("done", response.content, "measurement must not break the loop")
    }

    @Test
    @DisplayName("the round carries the provider's output and cache-hit tokens")
    fun roundCarriesTokens() = runBlocking {
        val model = mockk<BrowserChatModel>()
        val coordinator = mockk<ToolExecutionCoordinator>()
        // 1,000 input tokens of which 900 are prompt-cache hits, 120 output tokens.
        val usage = OpenAiTokenUsage.builder()
            .inputTokenCount(1_000)
            .outputTokenCount(120)
            .totalTokenCount(1_120)
            .inputTokensDetails(OpenAiTokenUsage.InputTokensDetails.builder().cachedTokens(900).build())
            .build()
        coEvery { model.langChainChat(any<ChatRequest>(), any()) } returnsMany listOf(
            ChatResponse.builder()
                .aiMessage(AiMessage.from("working", listOf(toolRequest("a1"))))
                .tokenUsage(usage).build(),
            ChatResponse.builder().aiMessage(AiMessage.from("done")).build(),
        )
        every { coordinator.execute(any()) } returns
            ToolExecutionResultMessage.from("x", "coding.read", "ok")

        val timings = mutableListOf<RoundTiming>()
        loop(model, coordinator, onRoundTiming = { timings += it })
            .generate(listOf(UserMessage.from("do it")))

        assertEquals(120, timings.first().outTokens, "output tokens are what model time is proportional to")
        assertEquals(900, timings.first().cachedInTokens, "cache hits explain cheap input")
        // A provider that reports no usage contributes nothing (never a guessed number).
        assertEquals(0, timings.last().outTokens)
    }

    @Test
    @DisplayName("the round_timing event round-trips through the memory event contract")
    fun roundTimedEventContract() {
        val event = RoundTimed(
            seq = 7, ts = 1_700_000_000_000, agentUuid = "agent-1", taskId = "task-1",
            round = 2, calls = 5, modelMs = 1_200, toolsMs = 300, callbacksMs = 90,
            outTokens = 640, cachedInTokens = 51_200,
        )
        val json = event.toJson()
        assertTrue(json.contains("\"round_timing\""), "type discriminator missing: $json")
        listOf("modelMs", "toolsMs", "callbacksMs", "calls", "round", "outTokens", "cachedInTokens").forEach { field ->
            assertTrue(json.contains(field), "$field missing from $json")
        }
        val parsed = json.toMemoryEvent()
        assertNotNull(parsed, "the event must deserialize back through the sealed contract")
        val typed = assertInstanceOf(RoundTimed::class.java, parsed)
        assertEquals(2, typed.round)
        assertEquals(5, typed.calls)
        assertEquals(1_200, typed.modelMs)
        assertEquals(300, typed.toolsMs)
        assertEquals(90, typed.callbacksMs)
        assertEquals(640, typed.outTokens)
        assertEquals(51_200, typed.cachedInTokens)
        assertEquals(1_590, typed.totalMs)
    }

    @Test
    @DisplayName("AgentMemorySink.roundTimed appends a readable round_timing event")
    fun sinkPersistsRoundTiming(@TempDir tmp: Path) = runBlocking {
        val log = AgentEventLog(tmp)
        val sink = AgentMemorySink(log, EventBuffer(64), enabled = true)

        sink.taskStarted("task-9", "agent-9", "do something", "cli-agent")
        sink.roundTimed("task-9", "agent-9", round = 1, calls = 3, modelMs = 500, toolsMs = 120, callbacksMs = 30)

        val events = log.readTask("agent-9", "task-9")
        val timing = events.filterIsInstance<RoundTimed>().singleOrNull()
        assertNotNull(timing, "expected one round_timing event, got: ${events.map { it::class.simpleName }}")
        assertEquals(3, timing!!.calls)
        assertEquals(650, timing.totalMs)
    }
}
