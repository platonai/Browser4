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
import java.util.concurrent.atomic.AtomicInteger
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

    /**
     * A checkpoint summary carrying every required `## section` — the default
     * mock output, since compaction now validates the structure before landing.
     */
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

    private fun compressor(
        enabled: Boolean = true,
        thresholdTokens: Long = 500L,
        retainTokens: Long = 100L,
        summarizer: ToolLoopSummarizer = ToolLoopSummarizer { validSummary() },
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
            validSummary()
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

    @Test
    @DisplayName("enforceResultTokenBudget shrinks oldest results first and protects the newest")
    fun enforceResultTokenBudgetPrunesOldestFirst() {
        val messages = mutableListOf<ChatMessage>()
        messages += SystemMessage.from("system")
        for (i in 1..3) {
            messages += roundMessage(i, body = "x".repeat(3_000))
        }
        val c = ToolLoopCompressor(
            enabled = true, thresholdTokens = 1_000_000L, retainTokens = 1_000_000L,
            pruneThresholdChars = 1_500, pruneHeadChars = 800, pruneTailChars = 400,
            summarizer = ToolLoopSummarizer { "summary" }, maxResultTokens = 2_000,
        )

        val changed = c.enforceResultTokenBudget(messages)

        assertTrue(changed)
        val results = messages.filterIsInstance<ToolExecutionResultMessage>()
        assertEquals(3, results.size, "no result may be dropped (round pairing must survive)")
        assertTrue(results[0].text().contains(ToolLoopCompressor.PRUNE_MARKER), "oldest must be shrunk")
        assertTrue(results[1].text().contains(ToolLoopCompressor.PRUNE_MARKER), "second oldest must be shrunk")
        assertEquals("x".repeat(3_000), results[2].text(), "newest result must stay full")
        assertTrue(c.estimateTotal(messages.filterIsInstance<ToolExecutionResultMessage>()) <= 2_000L)
    }

    @Test
    @DisplayName("enforceResultTokenBudget is a no-op within budget or when disabled")
    fun enforceResultTokenBudgetSkipsWhenWithinBudget() {
        val within = mutableListOf<ChatMessage>()
        within += roundMessage(1, body = "x".repeat(500))
        val c = ToolLoopCompressor(
            enabled = true, thresholdTokens = 1_000_000L, retainTokens = 1_000_000L,
            pruneThresholdChars = 1_500, pruneHeadChars = 800, pruneTailChars = 400,
            summarizer = ToolLoopSummarizer { "summary" }, maxResultTokens = 10_000,
        )
        assertFalse(c.enforceResultTokenBudget(within))
        assertEquals("x".repeat(500), (within[1] as ToolExecutionResultMessage).text())

        val off = ToolLoopCompressor(
            enabled = true, thresholdTokens = 1_000_000L, retainTokens = 1_000_000L,
            pruneThresholdChars = 1_500, pruneHeadChars = 800, pruneTailChars = 400,
            summarizer = ToolLoopSummarizer { "summary" }, maxResultTokens = 0,
        )
        val over = mutableListOf<ChatMessage>()
        over += roundMessage(1, body = "x".repeat(3_000))
        assertFalse(off.enforceResultTokenBudget(over))
    }

    @Test
    @DisplayName("pruneToolResults skips already-compact PageViewDeduper forms")
    fun pruneToolResultsSkipsCompactForms() {
        val messages = mutableListOf<ChatMessage>()
        messages += roundMessage(1, body = "x".repeat(2_000) + PageViewDeduper.DUPLICATE_MARKER + "0 (fp=abc)] digest")

        assertFalse(compressor().pruneToolResults(messages))
        assertTrue((messages[1] as ToolExecutionResultMessage).text().length > 2_000,
            "compact forms must survive pruning untouched")
    }

    @Test
    @DisplayName("pruneToolResults leaves protected knowledge-document results whole")
    fun pruneToolResultsSkipsProtectedToolNames() {
        val messages = mutableListOf<ChatMessage>()
        // A loaded SKILL.md (~50KB) must reach the model whole, never as an
        // 800+400-char shard.
        val skillBody = "## Core Loop\n" + "y".repeat(50_000)
        messages += roundMessage(1, tool = "system_skillDoc", body = skillBody)

        val c = ToolLoopCompressor(
            enabled = true, thresholdTokens = 1_000_000L, retainTokens = 1_000_000L,
            pruneThresholdChars = 1_500, pruneHeadChars = 800, pruneTailChars = 400,
            protectedToolNames = setOf("system_skillDoc"),
            summarizer = ToolLoopSummarizer { "summary" },
        )

        assertFalse(c.pruneToolResults(messages))
        assertEquals(skillBody, (messages[1] as ToolExecutionResultMessage).text())
    }

    @Test
    @DisplayName("enforceResultTokenBudget leaves protected knowledge-document results whole")
    fun enforceResultTokenBudgetSkipsProtectedToolNames() {
        val messages = mutableListOf<ChatMessage>()
        messages += SystemMessage.from("system")
        // Old unprotected results get shrunk to fit the budget; the protected
        // skillDoc result stays full even though it alone exceeds the cap.
        messages += roundMessage(1, tool = "coding.read", body = "x".repeat(3_000))
        val skillBody = "## Page State\n" + "y".repeat(50_000)
        messages += roundMessage(2, tool = "system_skillDoc", body = skillBody)

        val c = ToolLoopCompressor(
            enabled = true, thresholdTokens = 1_000_000L, retainTokens = 1_000_000L,
            pruneThresholdChars = 1_500, pruneHeadChars = 800, pruneTailChars = 400,
            maxResultTokens = 2_000,
            protectedToolNames = setOf("system_skillDoc"),
            summarizer = ToolLoopSummarizer { "summary" },
        )

        val changed = c.enforceResultTokenBudget(messages)

        assertTrue(changed, "the unprotected result must be shrunk")
        val results = messages.filterIsInstance<ToolExecutionResultMessage>()
        assertTrue(results[0].text().contains(ToolLoopCompressor.PRUNE_MARKER))
        assertEquals(skillBody, results[1].text(), "protected result must stay full")
    }

    @Test
    @DisplayName("retainLatestPageView keeps the round with the newest full page view out of compaction")
    fun retainLatestPageViewKeepsViewRound() {
        val messages = mutableListOf<ChatMessage>()
        messages += SystemMessage.from("system")
        messages += UserMessage.from("instruction")
        for (i in 1..2) messages += roundMessage(i, body = "body-$i " + "y".repeat(300))
        // Round 3: a page view.
        messages += roundMessage(3, tool = "tab.ariaSnapshot", body = "tab.ariaSnapshot [ok] snapshot\n" + "y".repeat(300))
        for (i in 4..6) messages += roundMessage(i, body = "body-$i " + "y".repeat(300))

        val retained = ToolLoopCompressor(
            enabled = true, thresholdTokens = 500L, retainTokens = 100L,
            pruneThresholdChars = 1_500, pruneHeadChars = 800, pruneTailChars = 400,
            summarizer = ToolLoopSummarizer { validSummary() },
            retainLatestPageView = true, viewToolNames = setOf("ariaSnapshot"),
        )
        val kept = runBlocking { retained.compressIfNeeded(messages) }

        assertTrue(kept)
        assertTrue(messages.any { it is ToolExecutionResultMessage && it.toolName() == "tab.ariaSnapshot" },
            "the page-view round must survive compaction when retention is on")

        val plain = ToolLoopCompressor(
            enabled = true, thresholdTokens = 500L, retainTokens = 100L,
            pruneThresholdChars = 1_500, pruneHeadChars = 800, pruneTailChars = 400,
            summarizer = ToolLoopSummarizer { validSummary() },
            retainLatestPageView = false, viewToolNames = setOf("ariaSnapshot"),
        )
        val messages2 = mutableListOf<ChatMessage>()
        messages2 += SystemMessage.from("system")
        messages2 += UserMessage.from("instruction")
        for (i in 1..2) messages2 += roundMessage(i, body = "body-$i " + "y".repeat(300))
        messages2 += roundMessage(3, tool = "tab.ariaSnapshot", body = "tab.ariaSnapshot [ok] snapshot\n" + "y".repeat(300))
        for (i in 4..6) messages2 += roundMessage(i, body = "body-$i " + "y".repeat(300))
        val compacted = runBlocking { plain.compressIfNeeded(messages2) }

        assertTrue(compacted)
        assertFalse(messages2.any { it is ToolExecutionResultMessage && it.toolName() == "tab.ariaSnapshot" },
            "the page-view round may be compacted when retention is off")
    }

    @Test
    @DisplayName("compaction instruction asks for a Page State timeline")
    fun compactionInstructionIncludesPageState() {
        assertTrue(ToolLoopCompressor.COMPACTION_INSTRUCTION.contains("## Page State"))
        assertTrue(ToolLoopCompressor.COMPACTION_INSTRUCTION.contains("\"(none)\" if no page was viewed"))
    }

    @Test
    @DisplayName("a summary no smaller than the shadowed span is rejected and recorded as a failed attempt")
    fun shrinkFailureAbandonsCompaction() {
        val ledger = CompactionLedger()
        val c = ToolLoopCompressor(
            enabled = true, thresholdTokens = 500L, retainTokens = 100L,
            pruneThresholdChars = 1_500, pruneHeadChars = 800, pruneTailChars = 400,
            summarizer = ToolLoopSummarizer { validSummary() + "\n" + "y".repeat(5_000) },
            ledger = ledger,
        )
        val messages = baseConversation(rounds = 6)
        val before = messages.toList()

        val compacted = runBlocking { c.compressIfNeeded(messages) }

        assertFalse(compacted, "an oversized summary must not land")
        assertEquals(before, messages, "the conversation must stay untouched")
        val failed = ledger.entries.filterIsInstance<CompactionLedger.Entry.Compacted>().single()
        assertTrue(failed.failure!!.contains("not smaller"), "failure reason must be recorded: ${failed.failure}")
        assertEquals(-1, failed.replacementIndex)
    }

    @Test
    @DisplayName("a structure-invalid summary is retried, then abandoned with a ledger trace")
    fun structureValidationRetriesThenAbandons() {
        val attempts = AtomicInteger()
        val ledger = CompactionLedger()
        val c = ToolLoopCompressor(
            enabled = true, thresholdTokens = 500L, retainTokens = 100L,
            pruneThresholdChars = 1_500, pruneHeadChars = 800, pruneTailChars = 400,
            summarizer = ToolLoopSummarizer {
                attempts.incrementAndGet()
                "## Summary\n- incomplete checkpoint, no required sections"
            },
            ledger = ledger, summarizationRetries = 1,
        )
        val messages = baseConversation(rounds = 6)
        val before = messages.toList()

        val compacted = runBlocking { c.compressIfNeeded(messages) }

        assertFalse(compacted)
        assertEquals(before, messages)
        assertEquals(2, attempts.get(), "initial attempt + one retry must both fail structure validation")
        val failed = ledger.entries.filterIsInstance<CompactionLedger.Entry.Compacted>().single()
        assertTrue(failed.failure!!.contains("required sections"), "structure failure must be recorded: ${failed.failure}")
    }

    @Test
    @DisplayName("a valid summary on retry lands after a structure-invalid first attempt")
    fun structureValidationRecoversOnRetry() {
        val attempts = AtomicInteger()
        val c = ToolLoopCompressor(
            enabled = true, thresholdTokens = 500L, retainTokens = 100L,
            pruneThresholdChars = 1_500, pruneHeadChars = 800, pruneTailChars = 400,
            summarizer = ToolLoopSummarizer {
                if (attempts.incrementAndGet() == 1) "## Summary\n- incomplete" else validSummary()
            },
            summarizationRetries = 1,
        )
        val messages = baseConversation(rounds = 6)

        val compacted = runBlocking { c.compressIfNeeded(messages) }

        assertTrue(compacted, "the retry must land a valid checkpoint")
        assertEquals(2, attempts.get())
        assertTrue(messages.any { it is UserMessage && it.singleText().contains(ToolLoopCompressor.SUMMARY_OPEN_TAG) })
    }

    @Test
    @DisplayName("a surface change during summarization abandons the transaction")
    fun stabilityCheckAbandonsWhenSurfaceChanges() {
        val messages = baseConversation(rounds = 6)
        val c = compressor(thresholdTokens = 500L, retainTokens = 100L, summarizer = ToolLoopSummarizer {
            // Simulate a concurrent rewrite of the span the summary was built from.
            messages.add(2, UserMessage.from("intruder"))
            validSummary()
        })

        val compacted = runBlocking { c.compressIfNeeded(messages) }

        assertFalse(compacted, "a changed span must never be replaced by a stale summary")
        // The summarizer's side effect (the intruder) stays, but no checkpoint
        // landed and every original round survives untouched.
        assertTrue(messages[2] is UserMessage && (messages[2] as UserMessage).singleText() == "intruder")
        assertFalse(messages.any { it is UserMessage && it.singleText().contains(ToolLoopCompressor.SUMMARY_OPEN_TAG) },
            "no checkpoint may land on a stale summary")
        assertEquals(6, messages.count { it is AiMessage && !it.toolExecutionRequests().isNullOrEmpty() },
            "all original rounds must survive")
        assertEquals(6, messages.count { it is ToolExecutionResultMessage })
    }

    @Test
    @DisplayName("retainLatestPageView prefers the FULL snapshot round over a later diff form")
    fun retainLatestPageViewPrefersFullSnapshotOverDiff() {
        val messages = mutableListOf<ChatMessage>()
        messages += SystemMessage.from("system")
        messages += UserMessage.from("instruction")
        for (i in 1..2) messages += roundMessage(i, body = "body-$i " + "y".repeat(300))
        // Round 3: the last FULL page view.
        messages += roundMessage(3, tool = "tab.ariaSnapshot", body = "tab.ariaSnapshot [ok] snapshot\n" + "y".repeat(300))
        // Round 4: a PageViewDeduper DIFF form — compact, not a full view.
        messages += roundMessage(
            4, tool = "tab.ariaSnapshot",
            body = "tab.ariaSnapshot [ok] snapshot\n" + PageViewDeduper.DIFF_MARKER + "2 (call=c4, fp=abc)] 自上次视图以来的变化",
        )
        for (i in 5..6) messages += roundMessage(i, body = "body-$i " + "y".repeat(300))

        val c = ToolLoopCompressor(
            enabled = true, thresholdTokens = 450L, retainTokens = 100L,
            pruneThresholdChars = 1_500, pruneHeadChars = 800, pruneTailChars = 400,
            summarizer = ToolLoopSummarizer { validSummary() },
            retainLatestPageView = true, viewToolNames = setOf("ariaSnapshot"),
        )

        val kept = runBlocking { c.compressIfNeeded(messages) }

        assertTrue(kept)
        val fullView = messages.filterIsInstance<ToolExecutionResultMessage>()
            .single { it.toolName() == "tab.ariaSnapshot" && !PageViewDeduper.isCompactForm(it.text()) }
        assertTrue(messages.contains(fullView), "the FULL snapshot round must survive: ${fullView.text().take(80)}")
    }

    @Test
    @DisplayName("pruneToolResults records shadow prices on the ledger")
    fun pruneRecordsShadowPrices() {
        val ledger = CompactionLedger()
        val messages = mutableListOf<ChatMessage>()
        messages += roundMessage(1, body = "x".repeat(3_000))

        val changed = ToolLoopCompressor(
            enabled = true, thresholdTokens = 1_000_000L, retainTokens = 1_000_000L,
            pruneThresholdChars = 1_500, pruneHeadChars = 800, pruneTailChars = 400,
            summarizer = ToolLoopSummarizer { validSummary() }, ledger = ledger,
        ).pruneToolResults(messages)

        assertTrue(changed)
        val pruned = ledger.entries.filterIsInstance<CompactionLedger.Entry.Pruned>().single()
        assertEquals("call-1", pruned.callId)
        assertTrue(pruned.shadowedTokens > 0)
        assertTrue(pruned.removedTokens > 0)
        assertTrue(ledger.estimatePruneSavings() > 0)
    }

    @Test
    @DisplayName("compactForOverflow forces a reduction below the pressure threshold with a zero tail")
    fun compactForOverflowForcesReduction() {
        val c = ToolLoopCompressor(
            enabled = true, thresholdTokens = 1_000_000L, retainTokens = 24_000L,
            pruneThresholdChars = 1_500, pruneHeadChars = 800, pruneTailChars = 400,
            summarizer = ToolLoopSummarizer { validSummary() },
        )
        val messages = baseConversation(rounds = 6)
        val before = messages.toList()

        // Pressure path: far below the threshold — no compaction.
        assertFalse(runBlocking { c.compressIfNeeded(messages) })
        assertEquals(before, messages)

        // Overflow path: forces a reduction regardless of the threshold.
        val recovered = runBlocking { c.compactForOverflow(messages) }

        assertTrue(recovered, "overflow recovery must land a durable reduction")
        assertTrue(messages.any { it is UserMessage && it.singleText().contains(ToolLoopCompressor.SUMMARY_OPEN_TAG) })
        // Zero retained tail: only the newest round may survive.
        assertEquals(1, messages.count { it is AiMessage && !it.toolExecutionRequests().isNullOrEmpty() })
    }

    @Test
    @DisplayName("compactForOverflow prunes over-budget results even when no round is compactable")
    fun compactForOverflowPrunesWhenNoRoundCompacts() {
        val c = ToolLoopCompressor(
            enabled = true, thresholdTokens = 1_000_000L, retainTokens = 24_000L,
            pruneThresholdChars = 1_500, pruneHeadChars = 800, pruneTailChars = 400,
            summarizer = ToolLoopSummarizer { validSummary() },
        )
        val messages = mutableListOf<ChatMessage>()
        messages += UserMessage.from("instruction")
        messages += roundMessage(1, body = "x".repeat(3_000))

        val recovered = runBlocking { c.compactForOverflow(messages) }

        assertTrue(recovered, "a prune-only recovery still counts as a durable reduction")
        assertTrue((messages[2] as ToolExecutionResultMessage).text().contains(ToolLoopCompressor.PRUNE_MARKER))
    }
}
