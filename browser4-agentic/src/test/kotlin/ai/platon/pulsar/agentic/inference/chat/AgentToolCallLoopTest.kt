package ai.platon.pulsar.agentic.inference.chat

import ai.platon.pulsar.agentic.inference.RequestTokenLimiter
import ai.platon.pulsar.agentic.tools.langchain4j.ToolExecutionCoordinator
import ai.platon.pulsar.external.BrowserChatModel
import dev.langchain4j.agent.tool.ToolExecutionRequest
import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.ToolExecutionResultMessage
import dev.langchain4j.data.message.UserMessage
import dev.langchain4j.model.chat.request.ChatRequest
import dev.langchain4j.model.chat.response.ChatResponse
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

@DisplayName("AgentToolCallLoop overflow digest and limiter ordering")
class AgentToolCallLoopTest {

    private fun toolRequest(id: String, name: String = "coding.read"): ToolExecutionRequest =
        ToolExecutionRequest.builder().id(id).name(name).arguments("{}").build()

    private fun chatResponseOf(aiMessage: AiMessage): ChatResponse =
        ChatResponse.builder().aiMessage(aiMessage).build()

    private fun loop(
        model: BrowserChatModel,
        coordinator: ToolExecutionCoordinator,
        maxIterations: Int = 2,
        limiter: RequestTokenLimiter = RequestTokenLimiter(maxTokens = 500_000),
        compressor: ToolLoopCompressor? = null,
        onToolExecuted: () -> Unit = {},
    ): AgentToolCallLoop = AgentToolCallLoop(
        model = model,
        toolSpecifications = emptyList(),
        coordinator = coordinator,
        maxIterations = maxIterations,
        requestTokenLimiter = limiter,
        compressor = compressor,
        onToolExecuted = onToolExecuted,
    )

    @Test
    @DisplayName("overflow modelError carries executed tool names AND a bounded result digest")
    fun overflowModelErrorCarriesNamesAndResultDigest() = runBlocking {
        val model = mockk<BrowserChatModel>()
        val coordinator = mockk<ToolExecutionCoordinator>()
        val callMessage = AiMessage.from("calling", listOf(toolRequest("c1")))
        coEvery { model.langChainChat(any<ChatRequest>(), any()) } returns chatResponseOf(callMessage)
        every { coordinator.execute(any()) } returns
            ToolExecutionResultMessage.from("c1", "coding.read", "first line of the result\nsecond line")

        val response = loop(model, coordinator, maxIterations = 1).generate(listOf(UserMessage.from("do it")))

        val error = requireNotNull(response.modelError) { "overflow must set modelError" }
        assertTrue(error.startsWith(AgentToolCallLoop.OVERFLOW_ERROR_PREFIX), error)
        assertTrue(error.contains("; executed: coding.read"), "executed names must survive: $error")
        // The digest keeps the FIRST line of the newest result — real progress
        // instead of a bare tool-name list (P0.2-1).
        assertTrue(error.contains("; results: coding.read -> first line of the result"), error)
        assertFalse(error.contains("second line"), "digest must keep the first line only: $error")
    }

    @Test
    @DisplayName("overflow digest is bounded to the newest results")
    fun overflowDigestIsBounded() = runBlocking {
        val model = mockk<BrowserChatModel>()
        val coordinator = mockk<ToolExecutionCoordinator>()
        val callMessage = AiMessage.from("calling", listOf(toolRequest("c1")))
        coEvery { model.langChainChat(any<ChatRequest>(), any()) } returns chatResponseOf(callMessage)
        every { coordinator.execute(any()) } returns
            ToolExecutionResultMessage.from("c1", "coding.read", "x".repeat(10_000))

        val response = loop(model, coordinator, maxIterations = 1).generate(listOf(UserMessage.from("do it")))

        val errorText = requireNotNull(response.modelError) { "overflow must set modelError" }
        val digest = errorText.substringAfter("; results: ", "")
        assertTrue(digest.isNotEmpty(), "overflow digest expected: $errorText")
        assertTrue(
            digest.length <= AgentToolCallLoop.OVERFLOW_SUMMARY_MAX_CHARS,
            "digest must be capped, got ${digest.length}"
        )
    }

    @Test
    @DisplayName("compression runs before the token-limit check (prune saves an over-budget list)")
    fun compressionRunsBeforeTokenLimitEnforcement() = runBlocking {
        val model = mockk<BrowserChatModel>()
        val coordinator = mockk<ToolExecutionCoordinator>()
        val callMessage = AiMessage.from("calling", listOf(toolRequest("c1")))
        val finalMessage = AiMessage.from("done")
        coEvery { model.langChainChat(any<ChatRequest>(), any()) } returnsMany listOf(
            chatResponseOf(callMessage),
            chatResponseOf(finalMessage),
        )
        // One oversized result: with the old enforce-first order the limiter
        // halts before the compressor can prune; with compression-first the
        // pruned list fits and the loop completes normally.
        every { coordinator.execute(any()) } returns
            ToolExecutionResultMessage.from("c1", "coding.read", "y".repeat(3_000))
        val compressor = ToolLoopCompressor(
            enabled = true,
            thresholdTokens = 1_000_000L, // compaction off — pruning alone must save the step
            retainTokens = 1_000_000L,
            pruneThresholdChars = 100,
            pruneHeadChars = 200,
            pruneTailChars = 100,
            summarizer = ToolLoopSummarizer { "summary" },
        )
        val limiter = RequestTokenLimiter(maxTokens = 500)

        val response = loop(
            model, coordinator,
            maxIterations = 2,
            limiter = limiter,
            compressor = compressor,
        ).generate(listOf(UserMessage.from("x".repeat(100))))

        assertTrue(response.modelError.isNullOrEmpty(),
            "prune must shrink the list before the limiter halts: ${response.modelError}")
    }

    @Test
    @DisplayName("onToolExecuted fires once per executed tool")
    fun onToolExecutedFiresPerTool() = runBlocking {
        val model = mockk<BrowserChatModel>()
        val coordinator = mockk<ToolExecutionCoordinator>()
        val callMessage = AiMessage.from(
            "calling",
            listOf(toolRequest("c1"), toolRequest("c2", "coding.shell"))
        )
        coEvery { model.langChainChat(any<ChatRequest>(), any()) } returns chatResponseOf(callMessage)
        every { coordinator.execute(any()) } answers {
            val request = firstArg<ToolExecutionRequest>()
            ToolExecutionResultMessage.from(request.id(), request.name(), "ok")
        }
        val executions = AtomicInteger()

        loop(model, coordinator, maxIterations = 1, onToolExecuted = { executions.incrementAndGet() })
            .generate(listOf(UserMessage.from("do it")))

        org.junit.jupiter.api.Assertions.assertEquals(2, executions.get(),
            "the callback must fire for every executed tool (feeds the stall fuse)")
    }
}
