package ai.platon.pulsar.agentic.inference.chat

import ai.platon.pulsar.agentic.inference.RequestTokenLimiter
import ai.platon.pulsar.agentic.model.ToolSpec
import ai.platon.pulsar.agentic.tools.langchain4j.ToolExecutionCoordinator
import ai.platon.pulsar.agentic.tools.langchain4j.ToolSpecificationConverter
import ai.platon.pulsar.external.BrowserChatModel
import dev.langchain4j.agent.tool.ToolExecutionRequest
import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.ChatMessage
import dev.langchain4j.data.message.ToolExecutionResultMessage
import dev.langchain4j.data.message.UserMessage
import dev.langchain4j.model.chat.request.ChatRequest
import dev.langchain4j.model.chat.response.ChatResponse
import dev.langchain4j.model.output.TokenUsage
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

@DisplayName("AgentToolCallLoop overflow digest and limiter ordering")
class AgentToolCallLoopTest {

    private fun toolRequest(id: String, name: String = "coding.read", arguments: String = "{}"): ToolExecutionRequest =
        ToolExecutionRequest.builder().id(id).name(name).arguments(arguments).build()

    private fun chatResponseOf(aiMessage: AiMessage): ChatResponse =
        ChatResponse.builder().aiMessage(aiMessage).build()

    private fun loop(
        model: BrowserChatModel,
        coordinator: ToolExecutionCoordinator,
        maxIterations: Int = 100,
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
    @DisplayName("onToolRequest callback receives name and arguments before execution")
    fun onToolRequestCallbackReceivesNameAndArgs() = runBlocking {
        val model = mockk<BrowserChatModel>()
        val coordinator = mockk<ToolExecutionCoordinator>()
        val callMessage = AiMessage.from("calling", listOf(toolRequest("c1", name = "coding.read")))
        coEvery { model.langChainChat(any<ChatRequest>(), any()) } returns chatResponseOf(callMessage)
        every { coordinator.execute(any()) } returns
            ToolExecutionResultMessage.from("c1", "coding.read", "ok")

        val seen = mutableListOf<Pair<String, String>>()
        val loop = AgentToolCallLoop(
            model = model,
            toolSpecifications = emptyList(),
            coordinator = coordinator,
            maxIterations = 1,
            onToolRequest = { name, args -> seen += name to args },
        )

        loop.generate(listOf(UserMessage.from("do it")))

        assertEquals(listOf("coding.read" to "{}"), seen)
    }

    @Test
    @DisplayName("onToolDecorated callback sees the raw result and the decorated form")
    fun onToolDecoratedCallbackReceivesRawAndDecorated() = runBlocking {
        val model = mockk<BrowserChatModel>()
        val coordinator = mockk<ToolExecutionCoordinator>()
        val viewText = "Page: https://example.com\nTitle: Example\n- link [ref=e5]\n"
        // Round 1: full view. Round 2: same content folded to a reference.
        // Round 3: text-only finish.
        coEvery { model.langChainChat(any<ChatRequest>(), any()) } returnsMany listOf(
            chatResponseOf(AiMessage.from("viewing", listOf(toolRequest("c1", name = "snapshot")))),
            chatResponseOf(AiMessage.from("viewing again", listOf(toolRequest("c2", name = "snapshot")))),
            chatResponseOf(AiMessage.from("done")),
        )
        every { coordinator.execute(any()) } returns
            ToolExecutionResultMessage.from("c1", "snapshot", viewText)

        val seen = mutableListOf<Triple<String, String, String>>()
        val loop = AgentToolCallLoop(
            model = model,
            toolSpecifications = emptyList(),
            coordinator = coordinator,
            maxIterations = 10,
            pageViewDeduper = PageViewDeduper(),
            onToolDecorated = { req, raw, decorated ->
                seen += Triple(req.id() ?: "", raw.text(), decorated.text())
            },
        )

        loop.generate(listOf(UserMessage.from("do it")))

        assertEquals(2, seen.size, "one callback per executed tool")
        // First view enters full; the identical second view folds to a reference.
        assertEquals(viewText, seen[0].third)
        assertTrue(seen[1].third.contains(PageViewDeduper.DUPLICATE_MARKER), "duplicate must fold")
        assertEquals("c1", seen[0].first)
        assertEquals("c2", seen[1].first)
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
    @DisplayName("pageViewDeduper folds repeated page content at append time (raw result still traced)")
    fun pageViewDeduperFoldsRepeatedViews() = runBlocking {
        val model = mockk<BrowserChatModel>()
        val coordinator = mockk<ToolExecutionCoordinator>()
        val call1 = AiMessage.from("calling", listOf(toolRequest("c1", name = "tab.ariaSnapshot")))
        val call2 = AiMessage.from("calling again", listOf(toolRequest("c2", name = "tab.ariaSnapshot")))
        val finalMessage = AiMessage.from("done")
        coEvery { model.langChainChat(any<ChatRequest>(), any()) } returnsMany listOf(
            chatResponseOf(call1),
            chatResponseOf(call2),
            chatResponseOf(finalMessage),
        )
        val page = "tab.ariaSnapshot [ok] snapshot\n  heading: Home\n  link: About"
        every { coordinator.execute(any()) } returns
            ToolExecutionResultMessage.from("c1", "tab.ariaSnapshot", page)

        val seen = mutableListOf<List<ChatMessage>>()
        val traced = mutableListOf<ToolExecutionResultMessage>()
        val loop = AgentToolCallLoop(
            model = model,
            toolSpecifications = emptyList(),
            coordinator = coordinator,
            maxIterations = 3,
            pageViewDeduper = PageViewDeduper(),
            onBeforeGenerate = { msgs, _ -> seen += msgs },
            onToolResult = { _, result, _ -> traced += result },
        )

        loop.generate(listOf(UserMessage.from("do it")))

        // Both executions happened and the raw results were traced.
        assertEquals(2, traced.size)
        assertEquals(page, traced[1].text(), "run tracing must see the RAW result, not the folded form")

        // The third request carries the first view in full and the second as a reference.
        val resultTexts = seen[2].filterIsInstance<ToolExecutionResultMessage>().map { it.text() }
        assertEquals(2, resultTexts.size)
        assertTrue(resultTexts[0].contains("heading: Home"), "first view must stay full: ${resultTexts[0]}")
        assertTrue(resultTexts[1].contains(PageViewDeduper.DUPLICATE_MARKER), "second view must fold: ${resultTexts[1]}")
    }

    @Test
    @DisplayName("disclosure: the initial request carries only the curated set plus the meta tools")
    fun disclosureInitialRequestCarriesCuratedSet() = runBlocking {
        val model = mockk<BrowserChatModel>()
        val coordinator = mockk<ToolExecutionCoordinator>()
        val requests = mutableListOf<ChatRequest>()
        val finalMessage = AiMessage.from("done")
        coEvery { model.langChainChat(any<ChatRequest>(), any()) } answers {
            requests += firstArg<ChatRequest>()
            chatResponseOf(finalMessage)
        }

        val all = ToolSpecificationConverter.toToolSpecifications(
            listOf(
                ToolSpec("tab", "navigate"),
                ToolSpec("coding", "read"),
                ToolSpec("coding", "ktSymbols"),
            )
        )
        val loop = AgentToolCallLoop(
            model = model,
            toolSpecifications = listOf(all.first { it.name() == ToolSpecificationConverter.toolName("tab", "navigate") }),
            coordinator = coordinator,
            allToolSpecifications = all,
            maxIterations = 1,
        )

        loop.generate(listOf(UserMessage.from("do it")))

        val specNames = requests.single().toolSpecifications().map { it.name() }.toSet()
        assertTrue(specNames.contains(ToolSpecificationConverter.toolName("tab", "navigate")))
        assertFalse(specNames.contains(ToolSpecificationConverter.toolName("coding", "read")),
            "the long tail must not leak into the initial request")
        assertTrue(specNames.contains(ToolDisclosureTools.LIST_TOOLS_NAME), "meta tools must always be exposed")
        assertTrue(specNames.contains(ToolDisclosureTools.EXPOSE_TOOLS_NAME), "meta tools must always be exposed")
    }

    @Test
    @DisplayName("onBeforeGenerate reports the exact tool specs sent with the request (not the full registry)")
    fun onBeforeGenerateReportsExactRequestToolSpecifications() = runBlocking {
        val model = mockk<BrowserChatModel>()
        val coordinator = mockk<ToolExecutionCoordinator>()
        val requests = mutableListOf<ChatRequest>()
        val finalMessage = AiMessage.from("done")
        coEvery { model.langChainChat(any<ChatRequest>(), any()) } answers {
            requests += firstArg<ChatRequest>()
            chatResponseOf(finalMessage)
        }

        val all = ToolSpecificationConverter.toToolSpecifications(
            listOf(
                ToolSpec("tab", "navigate"),
                ToolSpec("coding", "read"),
                ToolSpec("coding", "ktSymbols"),
            )
        )
        // Prompt tracing must see the CURATED set + meta tools, never the
        // full registry (regression: cli-prompt dumps used to log all specs).
        val seen = mutableListOf<Set<String>>()
        val loop = AgentToolCallLoop(
            model = model,
            toolSpecifications = listOf(all.first { it.name() == ToolSpecificationConverter.toolName("tab", "navigate") }),
            coordinator = coordinator,
            allToolSpecifications = all,
            maxIterations = 1,
            onBeforeGenerate = { _, specs -> seen += specs.map { it.name() }.toSet() },
        )

        loop.generate(listOf(UserMessage.from("do it")))

        assertEquals(1, seen.size, "one spec dump per model request")
        val sentNames = requests.single().toolSpecifications().map { it.name() }.toSet()
        assertEquals(
            sentNames, seen.single(),
            "the reported specs must equal the specs actually sent in the request"
        )
        assertTrue(seen.single().contains(ToolDisclosureTools.LIST_TOOLS_NAME), "meta tools must be reported")
        assertTrue(seen.single().contains(ToolDisclosureTools.EXPOSE_TOOLS_NAME), "meta tools must be reported")
        assertFalse(seen.single().contains(ToolSpecificationConverter.toolName("coding", "ktSymbols")),
            "the long tail must not be reported as sent")
    }

    @Test
    @DisplayName("disclosure: listTools/exposeTools are intercepted and the next request carries expanded specs")
    fun disclosureExposeToolsExpandsNextRequest() = runBlocking {
        val model = mockk<BrowserChatModel>()
        val coordinator = mockk<ToolExecutionCoordinator>()
        val requests = mutableListOf<ChatRequest>()
        val callList = AiMessage.from(
            "listing",
            listOf(toolRequest("c1", name = ToolDisclosureTools.LIST_TOOLS_NAME))
        )
        val callExpose = AiMessage.from(
            "exposing",
            listOf(toolRequest(
                "c2", name = ToolDisclosureTools.EXPOSE_TOOLS_NAME,
                arguments = """{"toolNames":["coding_read"]}""",
            ))
        )
        val finalMessage = AiMessage.from("done")
        coEvery { model.langChainChat(any<ChatRequest>(), any()) } answers {
            requests += firstArg<ChatRequest>()
            when (requests.size) {
                1 -> chatResponseOf(callList)
                2 -> chatResponseOf(callExpose)
                else -> chatResponseOf(finalMessage)
            }
        }
        every { coordinator.execute(any()) } returns
            ToolExecutionResultMessage.from("unexpected", "unexpected", "coordinator must not run meta tools")

        val all = ToolSpecificationConverter.toToolSpecifications(
            listOf(ToolSpec("tab", "navigate"), ToolSpec("coding", "read"))
        )
        val loop = AgentToolCallLoop(
            model = model,
            toolSpecifications = listOf(all.first { it.name() == ToolSpecificationConverter.toolName("tab", "navigate") }),
            coordinator = coordinator,
            allToolSpecifications = all,
            maxIterations = 3,
        )

        loop.generate(listOf(UserMessage.from("do it")))

        assertEquals(3, requests.size)
        verify(exactly = 0) { coordinator.execute(any()) }

        val second = requests[1].toolSpecifications().map { it.name() }.toSet()
        assertFalse(second.contains(ToolSpecificationConverter.toolName("coding", "read")),
            "exposeTools result lands AFTER this round's request")
        val third = requests[2].toolSpecifications().map { it.name() }.toSet()
        assertTrue(third.contains(ToolSpecificationConverter.toolName("coding", "read")),
            "the next request must carry the expanded set")
        assertTrue(third.contains(ToolDisclosureTools.LIST_TOOLS_NAME), "meta tools stay exposed")
        assertTrue(third.contains(ToolSpecificationConverter.toolName("tab", "navigate")))
    }

    @Test
    @DisplayName("onModelResponse receives the 1-based round number and the raw response (usage) after every request")
    fun onModelResponseReceivesRoundAndUsage() = runBlocking {
        val model = mockk<BrowserChatModel>()
        val coordinator = mockk<ToolExecutionCoordinator>()
        val callMessage = AiMessage.from("calling", listOf(toolRequest("c1")))
        val finalMessage = AiMessage.from("done")
        val usage = TokenUsage(100, 20, 120)
        coEvery { model.langChainChat(any<ChatRequest>(), any()) } returnsMany listOf(
            ChatResponse.builder().aiMessage(callMessage).tokenUsage(usage).build(),
            ChatResponse.builder().aiMessage(finalMessage).build(),
        )
        every { coordinator.execute(any()) } returns
            ToolExecutionResultMessage.from("c1", "coding.read", "ok")

        val seen = mutableListOf<Pair<Int, ChatResponse>>()
        val loop = AgentToolCallLoop(
            model = model,
            toolSpecifications = emptyList(),
            coordinator = coordinator,
            maxIterations = 2,
            onModelResponse = { seq, response -> seen += seq to response },
        )

        loop.generate(listOf(UserMessage.from("do it")))

        assertEquals(2, seen.size, "one callback per model request")
        assertEquals(1, seen[0].first)
        assertEquals(2, seen[1].first)
        assertEquals(100, seen[0].second.tokenUsage()?.inputTokenCount(),
            "the callback must carry the provider usage for token persistence")
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

    @Test
    @DisplayName("onBeforeGenerate receives the exact message list before every model request")
    fun onBeforeGenerateReceivesExactMessageList() = runBlocking {
        val model = mockk<BrowserChatModel>()
        val coordinator = mockk<ToolExecutionCoordinator>()
        val callMessage = AiMessage.from("calling", listOf(toolRequest("c1", name = "coding.read")))
        val finalMessage = AiMessage.from("done")
        coEvery { model.langChainChat(any<ChatRequest>(), any()) } returnsMany listOf(
            chatResponseOf(callMessage),
            chatResponseOf(finalMessage),
        )
        every { coordinator.execute(any()) } returns
            ToolExecutionResultMessage.from("c1", "coding.read", "the full result")

        val seen = mutableListOf<List<ChatMessage>>()
        val loop = AgentToolCallLoop(
            model = model,
            toolSpecifications = emptyList(),
            coordinator = coordinator,
            maxIterations = 2,
            onBeforeGenerate = { msgs, _ -> seen += msgs },
        )

        loop.generate(listOf(UserMessage.from("do it")))

        assertEquals(2, seen.size, "one dump per model request")
        // First request: only the initial user message.
        assertEquals(
            listOf("do it"),
            seen[0].filterIsInstance<UserMessage>().map { it.singleText() },
            "first request must be the initial user message"
        )
        // Second request: initial user message + tool-call AiMessage + tool result.
        assertTrue(seen[1].any { it is AiMessage && !it.toolExecutionRequests().isNullOrEmpty() },
            "second request must carry the tool-call message: $seen")
        assertTrue(seen[1].any { it is ToolExecutionResultMessage && it.text() == "the full result" },
            "second request must carry the tool result text: $seen")
    }

    @Test
    @DisplayName("onToolResult receives request, result and duration after every execution")
    fun onToolResultReceivesRequestResultAndDuration() = runBlocking {
        val model = mockk<BrowserChatModel>()
        val coordinator = mockk<ToolExecutionCoordinator>()
        val callMessage = AiMessage.from("calling", listOf(toolRequest("c1", name = "coding.read")))
        coEvery { model.langChainChat(any<ChatRequest>(), any()) } returns chatResponseOf(callMessage)
        every { coordinator.execute(any()) } answers {
            val request = firstArg<ToolExecutionRequest>()
            ToolExecutionResultMessage.from(request.id(), request.name(), "ok")
        }
        val seen = mutableListOf<Triple<ToolExecutionRequest, ToolExecutionResultMessage, Long>>()
        val loop = AgentToolCallLoop(
            model = model,
            toolSpecifications = emptyList(),
            coordinator = coordinator,
            maxIterations = 1,
            onToolResult = { req, result, durationMs -> seen += Triple(req, result, durationMs) },
        )

        loop.generate(listOf(UserMessage.from("do it")))

        assertEquals(1, seen.size, "one callback per executed tool")
        assertEquals("coding.read", seen[0].first.name())
        assertEquals("coding.read", seen[0].second.toolName())
        assertEquals("ok", seen[0].second.text())
        assertTrue(seen[0].third >= 0, "duration must be non-negative, got ${seen[0].third}")
    }

    @Test
    @DisplayName("context-window overflow prunes, compacts and retries the request")
    fun overflowRecoveryCompactsAndRetries() = runBlocking {
        val model = mockk<BrowserChatModel>()
        val coordinator = mockk<ToolExecutionCoordinator>()
        val callMessage = AiMessage.from("calling", listOf(toolRequest("c1")))
        val finalMessage = AiMessage.from("done")
        val calls = AtomicInteger()
        coEvery { model.langChainChat(any<ChatRequest>(), any()) } answers {
            when (calls.incrementAndGet()) {
                1 -> chatResponseOf(callMessage)
                2 -> throw IllegalStateException("context window exceeded: this request is too large")
                else -> chatResponseOf(finalMessage)
            }
        }
        every { coordinator.execute(any()) } returns
            ToolExecutionResultMessage.from("c1", "coding.read", "y".repeat(3_000))
        val ledger = CompactionLedger()
        val compressor = ToolLoopCompressor(
            enabled = true,
            thresholdTokens = 1_000_000L, // pressure off — overflow recovery alone must save the step
            retainTokens = 1_000_000L,
            pruneThresholdChars = 100,
            pruneHeadChars = 50,
            pruneTailChars = 50,
            summarizer = ToolLoopSummarizer { validSummary() },
            ledger = ledger,
        )

        val response = AgentToolCallLoop(
            model = model,
            toolSpecifications = emptyList(),
            coordinator = coordinator,
            maxIterations = 10,
            compressor = compressor,
            maxOverflowRetries = 1,
            compactionLedger = ledger,
        ).generate(listOf(UserMessage.from("do it")))

        assertEquals(3, calls.get(), "the overflowing request must be retried once")
        assertTrue(response.modelError.isNullOrEmpty(),
            "overflow recovery must let the loop finish: ${response.modelError}")
        assertTrue(ledger.entries.any { it is CompactionLedger.Entry.Pruned },
            "the recovery must land a durable prune")
    }

    @Test
    @DisplayName("overflow recovery stops retrying after maxOverflowRetries and propagates the error")
    fun overflowRecoveryRespectsRetryLimit() = runBlocking {
        val model = mockk<BrowserChatModel>()
        val coordinator = mockk<ToolExecutionCoordinator>()
        val callMessage = AiMessage.from("calling", listOf(toolRequest("c1")))
        val calls = AtomicInteger()
        coEvery { model.langChainChat(any<ChatRequest>(), any()) } answers {
            if (calls.incrementAndGet() == 1) chatResponseOf(callMessage)
            else throw IllegalStateException("context window exceeded: still too large")
        }
        every { coordinator.execute(any()) } returns
            ToolExecutionResultMessage.from("c1", "coding.read", "y".repeat(3_000))
        val compressor = ToolLoopCompressor(
            enabled = true,
            thresholdTokens = 1_000_000L,
            retainTokens = 1_000_000L,
            pruneThresholdChars = 100,
            pruneHeadChars = 50,
            pruneTailChars = 50,
            summarizer = ToolLoopSummarizer { validSummary() },
        )

        val outcome = runCatching {
            AgentToolCallLoop(
                model = model,
                toolSpecifications = emptyList(),
                coordinator = coordinator,
                maxIterations = 10,
                compressor = compressor,
                maxOverflowRetries = 1,
            ).generate(listOf(UserMessage.from("do it")))
        }

        val error = outcome.exceptionOrNull()
        assertTrue(error != null && error.message!!.contains("context window exceeded"),
            "the original overflow error must propagate after the retry budget is spent")
    }

    @Test
    @DisplayName("overflow recovery is disabled when maxOverflowRetries is 0")
    fun overflowRecoveryDisabledByConfig() = runBlocking {
        val model = mockk<BrowserChatModel>()
        val coordinator = mockk<ToolExecutionCoordinator>()
        coEvery { model.langChainChat(any<ChatRequest>(), any()) } throws
            IllegalStateException("context window exceeded")
        every { coordinator.execute(any()) } returns
            ToolExecutionResultMessage.from("c1", "coding.read", "ok")

        val outcome = runCatching {
            AgentToolCallLoop(
                model = model,
                toolSpecifications = emptyList(),
                coordinator = coordinator,
                maxIterations = 10,
                compressor = ToolLoopCompressor(
                    enabled = true, thresholdTokens = 1L, retainTokens = 1L,
                    pruneThresholdChars = 1_500, pruneHeadChars = 800, pruneTailChars = 400,
                    summarizer = ToolLoopSummarizer { validSummary() },
                ),
                maxOverflowRetries = 0,
            ).generate(listOf(UserMessage.from("do it")))
        }

        assertTrue(outcome.exceptionOrNull() != null, "with retries disabled the error must propagate immediately")
    }

    private fun validSummary(): String = """
        ## Primary Request and Intent
        - build plugin
        ## Key Technical Concepts
        - kotlin
        ## Files and Code
        - (none)
        ## Errors and Fixes
        - (none)
        ## Pending Jobs
        - (none)
        ## Current Work
        - (none)
        ## Page State
        - (none)
        ## Next Step
        - write tests
        ## Critical Context
        - (none)
    """.trimIndent()
}
