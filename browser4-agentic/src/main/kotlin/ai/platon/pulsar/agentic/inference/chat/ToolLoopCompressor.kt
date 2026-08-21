package ai.platon.pulsar.agentic.inference.chat

import ai.platon.pulsar.common.brief
import ai.platon.pulsar.common.getLogger
import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.ChatMessage
import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.ToolExecutionResultMessage
import dev.langchain4j.data.message.UserMessage

/**
 * Automatic context compression for the per-step tool-calling loop.
 *
 * Mirrors the two-phase compaction of deepseek-harness
 * (`packages/compaction/compaction-basic` + `compaction-tool-result-pruner`):
 *
 * 1. **Model-free pruning** ([pruneToolResults]): every over-budget tool result
 *    keeps a head + tail and substitutes a fixed marker for the middle, so a
 *    single oversized output can never blow up the conversation.
 * 2. **Pressure-triggered region compaction** ([compressIfNeeded]): when the
 *    estimated conversation exceeds [thresholdTokens], the oldest rounds before
 *    a retained recent tail are summarized by the LLM (via [ToolLoopSummarizer])
 *    and replaced with one framed `<compacted-summary>` user message, exactly
 *    like deepseek-harness's checkpoint replacement. The cut is always at a
 *    round boundary (an assistant tool-call message with all of its results),
 *    which is the Kotlin analogue of their tool-pairing-balanced cut.
 *
 * System and leading user messages (instruction/history) are never removed.
 */
class ToolLoopCompressor(
    val enabled: Boolean,
    val thresholdTokens: Long,
    val retainTokens: Long,
    val pruneThresholdChars: Int,
    val pruneHeadChars: Int,
    val pruneTailChars: Int,
    private val summarizer: ToolLoopSummarizer,
) {
    private val logger = getLogger(ToolLoopCompressor::class.java)

    /**
     * One tool-calling round inside the message list: an [AiMessage] carrying
     * tool-execution requests plus all immediately following
     * [ToolExecutionResultMessage]s (until the next AiMessage).
     */
    data class Round(val start: Int, val end: Int, val request: AiMessage)

    /**
     * Cheap deterministic token estimate (English-heavy code/tool output).
     * Uses the same chars-per-token heuristic as the design doc.
     */
    fun estimateTokens(text: String): Long = (text.length / 3.5).toLong().coerceAtLeast(1)

    /** Best-effort text of any [ChatMessage] for token estimation. */
    private fun messageText(message: ChatMessage): String = when (message) {
        is SystemMessage -> message.text()
        is UserMessage -> message.singleText() ?: ""
        is AiMessage -> message.text() ?: ""
        is ToolExecutionResultMessage -> message.text()
        else -> message.toString()
    }

    /** Total estimated tokens of the whole message list. */
    fun estimateTotal(messages: List<ChatMessage>): Long =
        messages.sumOf { estimateTokens(messageText(it)) }

    /**
     * Split the message list into tool-calling rounds. A round starts at an
     * [AiMessage] whose `toolExecutionRequests()` is non-empty and ends before
     * the next [AiMessage]; any [ToolExecutionResultMessage]s directly after
     * the call belong to the same round.
     */
    fun findRounds(messages: List<ChatMessage>): List<Round> {
        val rounds = mutableListOf<Round>()
        var index = 0
        while (index < messages.size) {
            val message = messages[index]
            if (message is AiMessage && !message.toolExecutionRequests().isNullOrEmpty()) {
                var end = index
                while (end + 1 < messages.size && messages[end + 1] is ToolExecutionResultMessage) {
                    end++
                }
                rounds += Round(index, end, message)
                index = end + 1
            } else {
                index++
            }
        }
        return rounds
    }

    /**
     * Model-free per-result pruning: for each over-budget tool result keep
     * [pruneHeadChars] + marker + [pruneTailChars]. Returns whether anything
     * was rewritten. Never touches other message types.
     */
    fun pruneToolResults(messages: MutableList<ChatMessage>): Boolean {
        if (!enabled) return false
        var changed = false
        for (index in messages.indices) {
            val message = messages[index] as? ToolExecutionResultMessage ?: continue
            val text = message.text()
            if (text.length <= pruneThresholdChars) continue
            val replacement = text.take(pruneHeadChars) + PRUNE_MARKER + text.takeLast(pruneTailChars)
            messages[index] = ToolExecutionResultMessage.from(message.id(), message.toolName(), replacement)
            changed = true
        }
        return changed
    }

    /**
     * Pressure-triggered region compaction: when the estimated conversation
     * exceeds [thresholdTokens], summarize every round older than the retained
     * recent tail into one framed checkpoint user message. The tail is the
     * newest rounds whose combined estimate reaches [retainTokens]; the cut is
     * always at a round boundary. Returns whether a compaction landed.
     */
    suspend fun compressIfNeeded(messages: MutableList<ChatMessage>): Boolean {
        if (!enabled) return false
        val total = estimateTotal(messages)
        if (total <= thresholdTokens) return false

        val rounds = findRounds(messages)
        if (rounds.isEmpty()) return false

        // Keep a recent tail: accumulate round estimates from the end until the
        // retention budget is met; when the whole history fits, there is
        // nothing to compact (keepFrom ends at 0, mirroring deepseek-harness).
        var keepFrom = rounds.size
        var accumulated = 0L
        for (roundIndex in rounds.indices.reversed()) {
            accumulated += estimateTotal(messages.subList(rounds[roundIndex].start, rounds[roundIndex].end + 1))
            keepFrom = roundIndex
            if (accumulated >= retainTokens) break
        }
        if (keepFrom == 0) return false

        val compactableEnd = rounds[keepFrom].start
        val compactableStart = rounds.first().start
        if (compactableEnd <= compactableStart) return false
        val compactedRounds = rounds.take(keepFrom)

        val prefix = messages.subList(0, compactableEnd).toList()
        val summary = try {
            summarizer.summarize(prefix)
        } catch (e: Exception) {
            logger.warn("🧹 tool-loop compaction summarization failed: {}", e.brief())
            return false
        }
        if (summary.isBlank()) return false

        val framed = "$CHECKPOINT_PREAMBLE\n\n$SUMMARY_OPEN_TAG\n${summary.trim()}\n$SUMMARY_CLOSE_TAG"
        val before = estimateTotal(messages)
        messages.subList(compactableStart, compactableEnd).clear()
        messages.add(compactableStart, UserMessage.from(framed))
        val after = estimateTotal(messages)
        logger.info(
            "🧹 tool-loop compaction: compacted {} round(s) ({}-{}), est {} -> {} tokens",
            compactedRounds.size, compactedRounds.first().start, compactedRounds.last().end, before, after
        )
        return true
    }

    companion object {
        /** Fixed marker substituted for every removed tool-result middle span. */
        const val PRUNE_MARKER = "\n\n[... tool result middle pruned ...]\n\n"

        const val SUMMARY_OPEN_TAG = "<compacted-summary>"
        const val SUMMARY_CLOSE_TAG = "</compacted-summary>"

        /** Framing that makes the replacement user message established context. */
        const val CHECKPOINT_PREAMBLE =
            "This is an automatically generated checkpoint condensing an earlier span of the " +
                "conversation to free up context. Treat the captured context as established " +
                "background and build on it without restating it. Continue the task directly " +
                "from the messages that follow, without acknowledging this checkpoint."

        /**
         * The summarization directive delivered as the FINAL user message after
         * the replayed conversation, so the auxiliary call is a prefix of the
         * routed request (provider KV-cache reuse), mirroring deepseek-harness.
         */
        const val COMPACTION_INSTRUCTION =
            "You are now acting as a compaction engine for this AI coding assistant. Condense " +
                "the conversation ABOVE into a structured checkpoint that lets another model " +
                "resume the work with no loss of essential context.\n" +
                "\n" +
                "Output EXACTLY the Markdown structure below: keep every section, in order. Use " +
                "terse bullets, not prose paragraphs. Write \"(none)\" for an empty section — " +
                "never drop a section.\n" +
                "\n" +
                "## Primary Request and Intent\n" +
                "- [the user's original and evolving goals; quote verbatim where the exact wording matters]\n" +
                "\n" +
                "## Key Technical Concepts\n" +
                "- [technologies, frameworks, patterns, and conventions in play]\n" +
                "\n" +
                "## Files and Code\n" +
                "- [exact path: why it matters, key changes or snippets]\n" +
                "\n" +
                "## Errors and Fixes\n" +
                "- [error: how it was resolved, plus any related user feedback]\n" +
                "\n" +
                "## Pending Jobs\n" +
                "- [explicitly requested work not yet completed]\n" +
                "\n" +
                "## Current Work\n" +
                "- [precisely what was in progress at this checkpoint]\n" +
                "\n" +
                "## Next Step\n" +
                "- [the single next action, directly in line with the most recent request, or \"(none)\"]\n" +
                "\n" +
                "## Critical Context\n" +
                "- [decisions and their rationale, constraints, user preferences, open questions, data needed to continue]\n" +
                "\n" +
                "Rules:\n" +
                "- Write concise English engineering prose. Preserve exact file paths, commands, error strings, identifiers, numeric values, function signatures, and syntax fragments.\n" +
                "- Capture user feedback and explicit instructions faithfully, especially corrections.\n" +
                "- Do NOT mention this summarization request or that the context was compacted.\n" +
                "- Output only the checkpoint text: do not call any tool or take any other action.\n" +
                "- If the conversation already contains a <compacted-summary> block, it is a PRIOR checkpoint. Do not copy it forward verbatim: preserve still-true facts, drop stale ones, and merge newer information into a single consolidated summary under the same structure."
    }
}

/** Pluggable summarizer: condenses a message prefix into checkpoint text. */
fun interface ToolLoopSummarizer {
    suspend fun summarize(prefixMessages: List<ChatMessage>): String
}
