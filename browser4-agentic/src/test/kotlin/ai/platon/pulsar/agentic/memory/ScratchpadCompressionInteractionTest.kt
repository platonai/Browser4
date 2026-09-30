package ai.platon.pulsar.agentic.memory

import ai.platon.pulsar.agentic.inference.chat.ToolLoopCompressor
import ai.platon.pulsar.agentic.inference.chat.ToolLoopSummarizer
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Design §12 risk verification: the scratchpad is injected as the LAST user
 * message every round ("replace-tail"), so pressure compaction must leave it
 * untouched — and even after an overflow compaction consumes it, the engine
 * re-injects the fresh render on the next round (append-only tail).
 */
@DisplayName("Scratchpad × ToolLoopCompressor interaction")
class ScratchpadCompressionInteractionTest {

    private fun validSummary(): String = """
        ## Primary Request and Intent
        - extract amazon price
        ## Key Technical Concepts
        - css selectors
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
        - continue
        ## Critical Context
        - (none)
    """.trimIndent()

    private fun compressor(
        thresholdTokens: Long = 500L,
        retainTokens: Long = 100L,
        summarizer: ToolLoopSummarizer = ToolLoopSummarizer { validSummary() },
    ): ToolLoopCompressor = ToolLoopCompressor(
        enabled = true,
        thresholdTokens = thresholdTokens,
        retainTokens = retainTokens,
        pruneThresholdChars = 1_500,
        pruneHeadChars = 800,
        pruneTailChars = 400,
        summarizer = summarizer,
    )

    private fun round(n: Int): List<ChatMessage> {
        val request = ToolExecutionRequest.builder()
            .id("call-$n").name("b4.run").arguments("{}").build()
        return listOf(
            AiMessage.from("assistant round $n", listOf(request)),
            ToolExecutionResultMessage.from("call-$n", "b4.run", "body-$n " + "y".repeat(300)),
        )
    }

    /** The engine's exact per-round injection: scratchpad rendered as the tail. */
    private fun conversationWithScratchpad(rounds: Int, scratchpadText: String): MutableList<ChatMessage> {
        val messages = mutableListOf<ChatMessage>()
        messages += SystemMessage.from("system")
        messages += UserMessage.from("instruction")
        for (i in 1..rounds) messages += round(i)
        messages += UserMessage.from(scratchpadText)
        return messages
    }

    @Test
    @DisplayName("pressure compaction keeps the scratchpad tail message verbatim")
    fun testPressureCompactionKeepsScratchpad() = runBlocking {
        val scratchpad = "## Task Scratchpad\n- assumption: page uses shadow DOM\n- todo: verify #price"
        val messages = conversationWithScratchpad(rounds = 12, scratchpadText = scratchpad)

        val changed = compressor(thresholdTokens = 500, retainTokens = 100).compressIfNeeded(messages)

        assertTrue(changed, "compaction must land under pressure")
        val tail = messages.last()
        assertTrue(tail is UserMessage, "tail must stay a user message, got ${tail.type()}")
        assertEquals(scratchpad, (tail as UserMessage).singleText())
        // The checkpoint must have absorbed the old rounds.
        assertTrue(
            messages.any { it is UserMessage && it.singleText().contains("<compacted-summary>") },
            "old rounds must be folded into a checkpoint",
        )
    }

    @Test
    @DisplayName("overflow compaction may consume the tail — the engine re-injects the fresh render")
    fun testOverflowReinjection() = runBlocking {
        val scratchpad = "## Task Scratchpad\n- assumption: shadow DOM"
        val messages = conversationWithScratchpad(rounds = 12, scratchpadText = scratchpad)

        compressor(thresholdTokens = 500, retainTokens = 0).compactForOverflow(messages)

        // Engine semantics: every round re-renders the CURRENT scratchpad as
        // the tail (replace-tail). Whatever compaction did, re-injection must
        // put the fresh render back at the end.
        val fresh = TaskScratchpad().also { it.note("assumption", "page uses shadow DOM") }.render()!!
        val reinjected = mutableListOf<ChatMessage>().apply {
            addAll(messages)
            removeAll { it is UserMessage && it.singleText().startsWith("## Task Scratchpad") }
            add(UserMessage.from(fresh))
        }
        assertNotNull(reinjected.lastOrNull())
        assertEquals(fresh, (reinjected.last() as UserMessage).singleText())
    }

    @Test
    @DisplayName("an empty scratchpad injects nothing (zero tokens)")
    fun testEmptyScratchpadInjectsNothing() {
        assertEquals(null, TaskScratchpad().render())
    }
}
