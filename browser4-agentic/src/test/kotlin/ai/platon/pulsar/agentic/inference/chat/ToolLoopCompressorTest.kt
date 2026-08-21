package ai.platon.pulsar.agentic.inference.chat

import dev.langchain4j.agent.tool.ToolExecutionRequest
import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.ChatMessage
import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.ToolExecutionResultMessage
import dev.langchain4j.data.message.UserMessage
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@DisplayName("ToolLoopCompressor automatic context compression")
class ToolLoopCompressorTest {

    private fun roundMessage(
        n: Int,
        tool: String = "coding.read",
        body: String = "coding.read [ok] file\ncontent",
    ): List<ChatMessage> {
        val request = ToolExecutionRequest.builder()
            .id("call-$n")
            .name(tool)
            .arguments("{}")
            .build()
        return listOf(
            AiMessage.from("assistant round $n", listOf(request)),
            ToolExecutionResultMessage.from("call-$n", tool, body),
        )
    }

    private fun compressor(
        enabled: Boolean = true,
        thresholdTokens: Long = 500L,
        retainTokens: Long = 100L,
        summarizer: ToolLoopSummarizer = ToolLoopSummarizer { "## Summary\n- done" },
    ): ToolLoopCompressor = ToolLoopCompressor(
        enabled = enabled,
        thresholdTokens = thresholdTokens,
        retainTokens = retainTokens,
        pruneThresholdChars = 1_500,
        pruneHeadChars = 800,
        pruneTailChars = 400,
        summarizer = summarizer,
    )

    private fun baseConversation(rounds: Int): MutableList<ChatMessage> {
        val messages = mutableListOf<ChatMessage>()
        messages += SystemMessage.from("system")
        messages += UserMessage.from("instruction")
        for (i in 1..rounds) {
            messages += roundMessage(i, body = "body-$i " + "y".repeat(300))
        }
        return messages
    }

    @Test
    @DisplayName("pruneToolResults keeps head and tail with a marker for over-budget results")
    fun pruneToolResultsPrunesOverBudgetResult() {
        val messages = mutableListOf<ChatMessage>()
        messages += SystemMessage.from("system")
        messages += UserMessage.from("instruction")
        messages += roundMessage(1, body = "x".repeat(3_000))

        val changed = compressor().pruneToolResults(messages)

        assertTrue(changed)
        val result = messages[3] as ToolExecutionResultMessage
        assertTrue(result.text().contains(ToolLoopCompressor.PRUNE_MARKER))
        assertTrue(result.text().startsWith("x".repeat(800)))
        assertTrue(result.text().endsWith("x".repeat(400)))
        assertTrue(result.text().length < 3_000)
    }

    @Test
    @DisplayName("pruneToolResults leaves under-budget results untouched")
    fun pruneToolResultsKeepsShortResults() {
        val messages = mutableListOf<ChatMessage>()
        messages += SystemMessage.from("system")
        messages += UserMessage.from("instruction")
        messages += roundMessage(1, body = "short result")

        assertFalse(compressor().pruneToolResults(messages))
        assertEquals("short result", (messages[3] as ToolExecutionResultMessage).text())
    }

    @Test
    @DisplayName("pruneToolResults is a no-op when disabled")
    fun pruneToolResultsDisabled() {
        val messages = mutableListOf<ChatMessage>()
        messages += roundMessage(1, body = "x".repeat(3_000))

        assertFalse(compressor(enabled = false).pruneToolResults(messages))
        assertTrue((messages[1] as ToolExecutionResultMessage).text().length == 3_000)
    }

    @Test
    @DisplayName("compressIfNeeded replaces old rounds with a framed checkpoint and keeps the recent tail")
    fun compressIfNeededCompactsOldRounds() {
        val summarizerCalls = mutableListOf<List<ChatMessage>>()
        val c = compressor(thresholdTokens = 500L, retainTokens = 100L) { prefix ->
            summarizerCalls += prefix
            "## Primary Request and Intent\n- build plugin\n\n## Next Step\n- write tests"
        }
        val messages = baseConversation(rounds = 6)

        val compacted = runBlocking { c.compressIfNeeded(messages) }

        assertTrue(compacted)
        assertEquals(1, summarizerCalls.size)

        // System and leading user messages survive.
        assertEquals("system", (messages[0] as SystemMessage).text())
        assertEquals("instruction", (messages[1] as UserMessage).singleText())

        // One framed checkpoint message replaced the old rounds.
        val checkpoint = messages[2] as UserMessage
        assertTrue(checkpoint.singleText().contains(ToolLoopCompressor.SUMMARY_OPEN_TAG))
        assertTrue(checkpoint.singleText().contains(ToolLoopCompressor.SUMMARY_CLOSE_TAG))
        assertTrue(checkpoint.singleText().contains("build plugin"))

        // Recent tail rounds remain verbatim (assistant call + tool result).
        assertTrue(messages.any { it is AiMessage && !it.toolExecutionRequests().isNullOrEmpty() })
        assertTrue(messages.any { it is ToolExecutionResultMessage })

        // Estimate after compaction is below the pressure threshold.
        assertTrue(c.estimateTotal(messages) <= 500L)
    }

    @Test
    @DisplayName("compressIfNeeded does nothing below the pressure threshold")
    fun compressIfNeededSkipsBelowThreshold() {
        var summarizerCalled = false
        val c = compressor(thresholdTokens = 1_000_000L) { summarizerCalled = true; "summary" }
        val messages = baseConversation(rounds = 2)

        assertFalse(runBlocking { c.compressIfNeeded(messages) })
        assertFalse(summarizerCalled)
        assertEquals(2 + 2 * 2, messages.size)
    }

    @Test
    @DisplayName("compressIfNeeded keeps everything when the whole history fits the retention budget")
    fun compressIfNeededKeepsAllWhenRetentionCoversEverything() {
        val c = compressor(thresholdTokens = 1L, retainTokens = 1_000_000L)
        val messages = baseConversation(rounds = 6)

        assertFalse(runBlocking { c.compressIfNeeded(messages) })
        assertEquals(2 + 2 * 6, messages.size)
    }

    @Test
    @DisplayName("compressIfNeeded survives rounds without a tool result")
    fun compressIfNeededHandlesResultLessRounds() {
        val messages = mutableListOf<ChatMessage>()
        messages += SystemMessage.from("system")
        messages += UserMessage.from("instruction")
        for (i in 1..5) {
            val request = ToolExecutionRequest.builder().id("call-$i").name("coding.read").arguments("{}").build()
            messages += AiMessage.from("assistant round $i", listOf(request))
            messages += ToolExecutionResultMessage.from("call-$i", "coding.read", "body-$i " + "z".repeat(300))
        }
        // A round whose assistant message has no following result.
        messages += AiMessage.from(
            "assistant round 6",
            listOf(ToolExecutionRequest.builder().id("call-6").name("coding.workspaceRoot").arguments("{}").build())
        )

        val compacted = runBlocking {
            compressor(thresholdTokens = 300L, retainTokens = 100L).compressIfNeeded(messages)
        }

        assertTrue(compacted)
        assertTrue(messages.any { it is UserMessage && it.singleText().contains(ToolLoopCompressor.SUMMARY_OPEN_TAG) })
    }

    @Test
    @DisplayName("compressIfNeeded leaves messages untouched when summarization fails")
    fun compressIfNeededSurvivesSummarizerFailure() {
        val c = compressor(thresholdTokens = 1L) { throw IllegalStateException("llm down") }
        val messages = baseConversation(rounds = 6)
        val before = messages.toList()

        assertFalse(runBlocking { c.compressIfNeeded(messages) })
        assertEquals(before, messages)
    }

    @Test
    @DisplayName("compressIfNeeded is a no-op when disabled")
    fun compressIfNeededDisabled() {
        var summarizerCalled = false
        val c = compressor(enabled = false, thresholdTokens = 1L) { summarizerCalled = true; "summary" }
        val messages = baseConversation(rounds = 6)

        assertFalse(runBlocking { c.compressIfNeeded(messages) })
        assertFalse(summarizerCalled)
        assertNotNull(messages[0] as SystemMessage)
    }

    @Test
    @DisplayName("findRounds groups assistant tool calls with their results")
    fun findRoundsGroupsCallsAndResults() {
        val messages = baseConversation(rounds = 3)

        val rounds = compressor().findRounds(messages)

        assertEquals(3, rounds.size)
        assertEquals(2, rounds[0].start)
        assertEquals(3, rounds[0].end)
        assertEquals(4, rounds[1].start)
        assertEquals(5, rounds[1].end)
        assertEquals(6, rounds[2].start)
        assertEquals(7, rounds[2].end)
    }
}
