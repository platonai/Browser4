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
 *
 * Web-task extensions (design: docs-dev/copilot/web-page-context-optimization-design.md):
 * - [retainLatestPageView]: the round holding the most recent FULL page view
 *   is never compacted — the page state is the model's working context;
 * - [enforceResultTokenBudget]: caps the cumulative estimated tokens of tool
 *   results per request, shrinking the OLDEST results first and protecting
 *   the newest one;
 * - prune skips already-compact forms (PageViewDeduper references/diffs).
 *
 * Traceability & transaction extensions (design:
 * docs-dev/copilot/compaction-traceability-design.md):
 * - every prune/compaction is recorded on [ledger] so historical references
 *   stay resolvable after compression (structural, not digest-only);
 * - compaction is transactional: stability check before commit, mandatory
 *   shrink (summary must be smaller than the shadowed span), and structure
 *   validation of the checkpoint (required `## sections`);
 * - [compactForOverflow] forces one useful reduction for provider-confirmed
 *   context-window overflow (prune first, then compact with a zero tail).
 *
 * Knowledge-document protection: tool results whose name is in
 * [protectedToolNames] (e.g. `system_skillDoc` — the on-demand SKILL.md
 * loader) are exempt from head/middle/tail pruning and from the cumulative
 * result-token budget, so the model can actually READ the loaded document
 * instead of a 1.2 KB shard of it. They remain eligible for whole-region
 * compaction ([compressIfNeeded]) — after which the model can simply
 * re-fetch the document.
 */
class ToolLoopCompressor(
    val enabled: Boolean,
    val thresholdTokens: Long,
    val retainTokens: Long,
    val pruneThresholdChars: Int,
    val pruneHeadChars: Int,
    val pruneTailChars: Int,
    val retainLatestPageView: Boolean = false,
    val viewToolNames: Set<String> = emptySet(),
    val maxResultTokens: Long = 0,
    /** Tool-result names exempt from pruning and the result-token budget. */
    val protectedToolNames: Set<String> = emptySet(),
    /** Traceability ledger; when null no audit trail is recorded. */
    private val ledger: CompactionLedger? = null,
    /** Reject a summary that is not smaller than the content it shadows. */
    val requireShrink: Boolean = true,
    /** Extra summarization attempts after a blank / shrink / structure failure. */
    val summarizationRetries: Int = 1,
    /** Structured audit logging of every compaction transaction. */
    val audit: Boolean = true,
    /** Trailing-lambda-friendly summarizer — must stay the LAST parameter. */
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
     * was rewritten. Never touches other message types, and skips already
     * compact forms (PageViewDeduper references/diffs) and protected
     * knowledge-document results ([protectedToolNames]).
     */
    fun pruneToolResults(messages: MutableList<ChatMessage>): Boolean {
        if (!enabled) return false
        var changed = false
        for (index in messages.indices) {
            val message = messages[index] as? ToolExecutionResultMessage ?: continue
            if (message.toolName() in protectedToolNames) continue
            val text = message.text()
            if (text.length <= pruneThresholdChars) continue
            if (PageViewDeduper.isCompactForm(text)) continue
            val replacement = text.take(pruneHeadChars) + PRUNE_MARKER + text.takeLast(pruneTailChars)
            messages[index] = ToolExecutionResultMessage.from(message.id(), message.toolName(), replacement)
            val before = estimateTokens(text)
            val after = estimateTokens(replacement)
            ledger?.recordPruned(message.id(), index, index, before, (before - after).coerceAtLeast(0))
            changed = true
        }
        return changed
    }

    /**
     * Cumulative tool-result budget: when the estimated tokens of all tool
     * results exceed [maxResultTokens], shrink the OLDEST results (head +
     * marker + tail) until the budget fits. The newest result is always
     * protected — it is the model's working state. Compact reference/diff
     * forms are already small and are skipped, as are protected
     * knowledge-document results ([protectedToolNames]). Returns whether
     * anything was rewritten. No-op when [maxResultTokens] <= 0.
     */
    fun enforceResultTokenBudget(messages: MutableList<ChatMessage>): Boolean {
        if (!enabled || maxResultTokens <= 0) return false
        val resultIndices = messages.indices.filter { messages[it] is ToolExecutionResultMessage }
        if (resultIndices.isEmpty()) return false
        var total = resultIndices.sumOf { estimateTokens((messages[it] as ToolExecutionResultMessage).text()) }
        if (total <= maxResultTokens) return false

        var changed = false
        val newestIndex = resultIndices.last()
        for (index in resultIndices) {
            if (total <= maxResultTokens) break
            if (index == newestIndex) continue
            val message = messages[index] as ToolExecutionResultMessage
            if (message.toolName() in protectedToolNames) continue
            val text = message.text()
            if (text.length <= pruneThresholdChars) continue
            if (PageViewDeduper.isCompactForm(text)) continue
            val replacement = text.take(pruneHeadChars) + PRUNE_MARKER + text.takeLast(pruneTailChars)
            total -= estimateTokens(text)
            messages[index] = ToolExecutionResultMessage.from(message.id(), message.toolName(), replacement)
            total += estimateTokens(replacement)
            ledger?.recordPruned(
                message.id(), index, index,
                estimateTokens(text), (estimateTokens(text) - estimateTokens(replacement)).coerceAtLeast(0),
            )
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
    suspend fun compressIfNeeded(messages: MutableList<ChatMessage>): Boolean =
        compressCore(messages, retainTokens, "pressure")

    /**
     * Provider-confirmed context-window overflow recovery: force one useful
     * reduction below the normal pressure threshold — prune every over-budget
     * result first, then compact with a ZERO retained tail (deepseek-harness
     * `context-overflow` trigger analogue). Returns whether any durable
     * reduction landed. The caller retries the request afterwards.
     */
    suspend fun compactForOverflow(messages: MutableList<ChatMessage>): Boolean {
        if (!enabled) return false
        val pruned = pruneToolResults(messages)
        val compacted = compressCore(messages, 0L, "context-overflow")
        return pruned || compacted
    }

    /**
     * Transactional core shared by pressure and overflow compaction:
     * prepare (measure + snapshot the shadowed span) → summarize with
     * validation retries (blank / shrink / structure) → stability check →
     * commit (atomic replace + ledger record). Every abandoned attempt is
     * recorded on the ledger and leaves the conversation untouched.
     */
    private suspend fun compressCore(
        messages: MutableList<ChatMessage>,
        retainTokensOverride: Long,
        reason: String,
    ): Boolean {
        if (!enabled) return false
        val total = estimateTotal(messages)
        // Overflow bypasses the pressure threshold: the provider already
        // rejected the request, so any useful reduction is worth landing.
        if (total <= thresholdTokens && reason != "context-overflow") return false

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
            if (accumulated >= retainTokensOverride) break
        }
        // Web-task extension: never compact the round holding the most recent
        // FULL page view — the page state is the model's working context, and
        // references/diffs in later rounds may point back at it. A compact
        // reference/diff form does NOT count as a full view: when the newest
        // view result is a diff, the full snapshot round is the one to keep.
        if (retainLatestPageView && viewToolNames.isNotEmpty() && keepFrom < rounds.size) {
            val latestFullViewRound = rounds.indexOfLast { round ->
                messages.subList(round.start, round.end + 1).any {
                    it is ToolExecutionResultMessage
                        && PageViewDeduper.matchesViewTool(it.toolName(), viewToolNames)
                        && !PageViewDeduper.isCompactForm(it.text())
                }
            }
            val latestViewRound = rounds.indexOfLast { round ->
                messages.subList(round.start, round.end + 1).any {
                    it is ToolExecutionResultMessage && PageViewDeduper.matchesViewTool(it.toolName(), viewToolNames)
                }
            }
            val target = if (latestFullViewRound >= 0) latestFullViewRound else latestViewRound
            if (target in 0 until keepFrom) keepFrom = target
        }
        if (keepFrom == 0) return false

        val compactableEnd = rounds[keepFrom].start
        val compactableStart = rounds.first().start
        if (compactableEnd <= compactableStart) return false
        val compactedRounds = rounds.take(keepFrom)

        val prefix = messages.subList(0, compactableEnd).toList()
        val shadowed = messages.subList(compactableStart, compactableEnd).toList()
        val shadowedTokens = estimateTotal(shadowed)
        val shadowedRange = compactableStart until compactableEnd

        // Summarize with validation retries: blank output, a summary no
        // smaller than the shadowed span, or a structure-invalid checkpoint
        // all fail the attempt (deepseek-harness shrink enforcement).
        var summary: String? = null
        var lastFailure: String? = null
        for (attempt in 0..summarizationRetries.coerceAtLeast(0)) {
            val candidate = try {
                summarizer.summarize(prefix)
            } catch (e: Exception) {
                logger.warn("🧹 tool-loop compaction summarization failed: {}", e.brief())
                lastFailure = "summarization failed: ${e.message ?: e.javaClass.simpleName}"
                null
            }
            if (candidate.isNullOrBlank()) {
                lastFailure = "blank summary"
                continue
            }
            val candidateFramed = frame(candidate)
            if (requireShrink && estimateTokens(candidateFramed) >= shadowedTokens) {
                lastFailure = "summary not smaller than shadowed content"
                continue
            }
            if (!summaryStructureValid(candidate)) {
                lastFailure = "summary missing required sections"
                continue
            }
            summary = candidate
            break
        }
        if (summary == null) {
            ledger?.recordCompacted(reason, shadowedRange, -1, shadowedTokens, 0L, failure = lastFailure)
            logger.warn("🧹 tool-loop compaction abandoned ({}): {}", reason, lastFailure)
            return false
        }

        // Stability check: the shadowed span must still hold the exact
        // messages the summary was built from (a concurrent rewrite would
        // shift indices and replace the wrong history).
        if (messages.subList(compactableStart, compactableEnd) != shadowed) {
            ledger?.recordCompacted(
                reason, shadowedRange, -1, shadowedTokens, 0L,
                failure = "surface changed during summarization",
            )
            logger.warn("🧹 tool-loop compaction abandoned ({}): surface changed during summarization", reason)
            return false
        }

        val framed = frame(summary)
        val before = estimateTotal(messages)
        val replacementIndex = compactableStart
        messages.subList(compactableStart, compactableEnd).clear()
        messages.add(replacementIndex, UserMessage.from(framed))
        val after = estimateTotal(messages)
        val replacementTokens = estimateTokens(framed)
        val compactionId = ledger?.recordCompacted(
            reason, shadowedRange, replacementIndex, shadowedTokens, replacementTokens,
        ) ?: "n/a"
        if (audit) {
            logger.info(
                "🧹 tool-loop compaction ({}): compacted {} round(s) (seqs {}-{}), " +
                    "shadowed ~{} tokens -> checkpoint ~{} tokens (id={}), est {} -> {} tokens",
                reason, compactedRounds.size, compactableStart, compactableEnd - 1,
                shadowedTokens, replacementTokens, compactionId, before, after,
            )
        }
        return true
    }

    /** Frame raw summary text as a durable checkpoint user-message body. */
    private fun frame(summary: String): String =
        "$CHECKPOINT_PREAMBLE\n\n$SUMMARY_OPEN_TAG\n${summary.trim()}\n$SUMMARY_CLOSE_TAG"

    /**
     * Whether the summary carries every section the compaction instruction
     * requires. The checkpoint is the model's only trace of the compacted
     * history, so a summary that drops a section (e.g. Page State) is
     * rejected and the attempt is retried/abandoned instead of landed.
     */
    private fun summaryStructureValid(summary: String): Boolean =
        REQUIRED_SUMMARY_SECTIONS.all { summary.contains(it) }

    companion object {
        /** Fixed marker substituted for every removed tool-result middle span. */
        const val PRUNE_MARKER = "\n\n[... tool result middle pruned ...]\n\n"

        /**
         * Every `## section` heading the compaction instruction mandates.
         * A checkpoint missing any of these is rejected by structure
         * validation (see [ToolLoopCompressor.summaryStructureValid]).
         */
        val REQUIRED_SUMMARY_SECTIONS = listOf(
            "## Primary Request and Intent",
            "## Key Technical Concepts",
            "## Files and Code",
            "## Errors and Fixes",
            "## Pending Jobs",
            "## Current Work",
            "## Page State",
            "## Next Step",
            "## Critical Context",
        )

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
                "## Page State\n" +
                "- [for every distinct page viewed: FULL absolute URL (never truncated or elided), title, " +
                "fingerprint and what changed since the previous view (diff highlights); " +
                "write \"(none)\" if no page was viewed]\n" +
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
                "- If a SKILL document (e.g. SKILL.md or a reference doc loaded via system.skillDoc) was loaded " +
                "earlier and its content is no longer available in the conversation, note in " +
                "\"## Critical Context\" that it must be reloaded via system.skillDoc(name) when its details are needed.\n" +
                "- Output only the checkpoint text: do not call any tool or take any other action.\n" +
                "- If the conversation already contains a <compacted-summary> block, it is a PRIOR checkpoint. Do not copy it forward verbatim: preserve still-true facts, drop stale ones, and merge newer information into a single consolidated summary under the same structure."
    }
}

/** Pluggable summarizer: condenses a message prefix into checkpoint text. */
fun interface ToolLoopSummarizer {
    suspend fun summarize(prefixMessages: List<ChatMessage>): String
}
