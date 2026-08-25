package ai.platon.pulsar.agentic.inference.chat

import ai.platon.pulsar.common.getLogger
import dev.langchain4j.data.message.ChatMessage
import dev.langchain4j.data.message.ToolExecutionResultMessage
import java.security.MessageDigest

/**
 * Deduplicates tool results inside one tool-calling loop, targeting the
 * web-task pattern of repeatedly viewing the same page (`ariaSnapshot`,
 * `snapshot`, `dump`, `htmlsnapshot`, `extract`, ...).
 *
 * Design: docs-dev/copilot/web-page-context-optimization-design.md
 *
 * - **Exact-duplicate folding** (any tool): a result whose normalized
 *   fingerprint was already seen is replaced by a self-sufficient reference
 *   (digest included), so the same content never enters the conversation
 *   twice — the single biggest waste in web loops that re-view the same page.
 * - **View diffing** (view tools only): new content that heavily overlaps the
 *   previous view of the same tool is replaced by a compact unified-style
 *   diff (common prefix/suffix kept as context), a fraction of the full
 *   snapshot that also sharpens the model's attention on what changed.
 *
 * All rewriting happens at **append time**, before the message is ever sent:
 * already-sent history keeps its shape, so the conversation only ever grows.
 * Not thread-safe — one instance per [AgentToolCallLoop].
 *
 * @param enabled           master switch.
 * @param diffEnabled       emit diffs for changed views (else full text).
 * @param diffMaxChars      hard cap on the diff body; a bigger diff falls
 *                          back to the full snapshot.
 * @param digestChars       digest length carried by references so they stay
 *                          self-sufficient even after compaction.
 * @param duplicateFoldEnabled  fold exact duplicates for NON-view tools too
 *                          (view tools always fold).
 * @param viewToolNames     tool names treated as page views; matched by
 *                          exact name or `domain.`-prefixed suffix, so both
 *                          `ariaSnapshot` and `tab.ariaSnapshot` work.
 * @param stripPatterns     regexes removed before fingerprinting/diffing —
 *                          volatile regions (bounding boxes, timestamps...)
 *                          that would otherwise make identical pages look
 *                          different.
 * @param ledger            optional compaction ledger recording every fold so
 *                          references stay resolvable after compression
 *                          (see CompactionLedger / compaction-traceability-design.md).
 */
class PageViewDeduper(
    val enabled: Boolean = true,
    val diffEnabled: Boolean = true,
    val diffMaxChars: Int = DIFF_MAX_CHARS_DEFAULT,
    val digestChars: Int = DIGEST_CHARS_DEFAULT,
    val duplicateFoldEnabled: Boolean = true,
    val viewToolNames: Set<String> = DEFAULT_VIEW_TOOL_NAMES,
    val stripPatterns: List<Regex> = DEFAULT_STRIP_PATTERNS,
    private val ledger: CompactionLedger? = null,
) {
    private val logger = getLogger(PageViewDeduper::class)

    /** fingerprint → index in the conversation of the first FULL occurrence. */
    private val seenFingerprints = mutableMapOf<String, Int>()

    /** view tool → state of its most recent DISTINCT view. */
    private val lastViewByTool = mutableMapOf<String, ViewState>()

    /**
     * Called by the loop when compression rewrote history: recorded message
     * indices may be stale, so the maps are cleared and rebuilt from the
     * post-compression conversation on the next [decorate].
     */
    fun reset() {
        seenFingerprints.clear()
        lastViewByTool.clear()
    }

    /**
     * Decide the form in which [result] enters the conversation.
     *
     * The returned message is always a [ToolExecutionResultMessage] so the
     * round structure (assistant tool call + its results) stays
     * protocol-valid for the provider.
     *
     * @param result   the freshly executed result (raw, pre-decorated).
     * @param messages the conversation the result is about to be appended to
     *                 (the new message's index is `messages.size`).
     */
    fun decorate(result: ToolExecutionResultMessage, messages: List<ChatMessage>): ToolExecutionResultMessage {
        if (!enabled) return result
        val text = result.text()
        if (text.isBlank()) return result

        val tool = result.toolName()
        val isView = isViewTool(tool)
        val fp = fingerprint(text)
        val newIndex = messages.size

        // Exact-duplicate folding: identical content never enters twice.
        // For view tools always; for other tools only when enabled.
        val firstIndex = seenFingerprints[fp]
        if (firstIndex != null && (isView || duplicateFoldEnabled)) {
            logger.debug("dedup: {} folded into a reference to result #{}", tool, firstIndex)
            ledger?.recordFolded(result.id(), firstIndex, newIndex)
            return referenceMessage(result, firstIndex, fp, isView)
        }
        if (firstIndex == null) seenFingerprints[fp] = newIndex

        // View diffing: new content of a view tool, compared against the most
        // recent distinct view of the SAME tool (full text kept, so diffs
        // chain against snapshots, not against earlier diff forms).
        var decorated = result
        if (isView && diffEnabled) {
            val prev = lastViewByTool[tool]
            if (prev != null && prev.index != newIndex) {
                buildDiff(prev.normalizedText, normalize(text))?.let { diff ->
                    ledger?.recordFolded(result.id(), prev.index, newIndex)
                    decorated = diffMessage(result, prev.index, prev.fingerprint, diff)
                }
            }
        }

        if (isView) lastViewByTool[tool] = ViewState(newIndex, fp, normalize(text))
        ledger?.registerResult(result.id(), newIndex)
        return decorated
    }

    /** True when [toolName] is a configured page-view tool (exact or `domain.` suffix). */
    fun isViewTool(toolName: String): Boolean = matchesViewTool(toolName, viewToolNames)

    /**
     * Normalized form used for fingerprinting and diffing: strip volatile
     * regions ([stripPatterns]), collapse whitespace, drop blank lines.
     */
    fun normalize(text: String): String = normalizeOf(text, stripPatterns)

    /** SHA-256 fingerprint (hex, [FINGERPRINT_CHARS] chars) of [normalize]d text. */
    fun fingerprint(text: String): String = fingerprintOf(text, stripPatterns)

    /**
     * Lightweight prefix/suffix line diff between two page texts.
     *
     * Returns null when the change is not worth a diff (identical texts,
     * changed ratio above [MAX_CHANGED_RATIO], or the diff body exceeds
     * [diffMaxChars]) — the caller then falls back to the full snapshot.
     */
    fun buildDiff(previousNormalized: String, currentNormalized: String): String? {
        val before = previousNormalized.lineSequence().filter { it.isNotBlank() }.toList()
        val after = currentNormalized.lineSequence().filter { it.isNotBlank() }.toList()

        // Longest common prefix and suffix; the middle is the change region.
        var prefix = 0
        while (prefix < before.size && prefix < after.size && before[prefix] == after[prefix]) prefix++
        var suffix = 0
        while (suffix < before.size - prefix && suffix < after.size - prefix &&
            before[before.size - 1 - suffix] == after[after.size - 1 - suffix]
        ) suffix++

        val removed = before.subList(prefix, before.size - suffix)
        val added = after.subList(prefix, after.size - suffix)
        if (removed.isEmpty() && added.isEmpty()) return null

        val changed = removed.size + added.size
        val total = maxOf(before.size, after.size)
        if (changed.toDouble() / total > MAX_CHANGED_RATIO) return null

        val sb = StringBuilder()
        val ctxBefore = maxOf(0, prefix - CONTEXT_LINES)
        for (i in ctxBefore until prefix) sb.appendLine("  " + before[i])
        removed.forEach { sb.appendLine("- " + it) }
        added.forEach { sb.appendLine("+ " + it) }
        val tailStart = before.size - suffix
        for (i in tailStart until minOf(before.size, tailStart + CONTEXT_LINES)) sb.appendLine("  " + before[i])

        return sb.toString().trimEnd().takeIf { it.length <= diffMaxChars }
    }

    private fun referenceMessage(
        result: ToolExecutionResultMessage,
        firstIndex: Int,
        fp: String,
        isView: Boolean,
    ): ToolExecutionResultMessage {
        val header = result.text().lineSequence().firstOrNull().orEmpty()
        val digest = result.text().trim().take(digestChars)
        val hint = if (isView) " 如需强制刷新请调用时传 refresh=true。" else ""
        val text = buildString {
            if (header.isNotBlank()) appendLine(header)
            append("$DUPLICATE_MARKER$firstIndex (call=${result.id()}, fp=$fp)] 内容与之前完全一致，未重复发送全文。$hint 摘要: $digest")
        }
        return ToolExecutionResultMessage.from(result.id(), result.toolName(), text)
    }

    private fun diffMessage(
        result: ToolExecutionResultMessage,
        prevIndex: Int,
        prevFp: String,
        diff: String,
    ): ToolExecutionResultMessage {
        val header = result.text().lineSequence().firstOrNull().orEmpty()
        val text = buildString {
            if (header.isNotBlank()) appendLine(header)
            appendLine("$DIFF_MARKER$prevIndex (call=${result.id()}, fp=$prevFp)] 自上次视图以来的变化（- 旧 / + 新）：")
            append(diff)
        }
        return ToolExecutionResultMessage.from(result.id(), result.toolName(), text)
    }

    /** Latest distinct view of a tool: where the full snapshot lives + its text. */
    private data class ViewState(val index: Int, val fingerprint: String, val normalizedText: String)

    companion object {
        /** Tool-name matching shared with [ToolLoopCompressor] retention logic. */
        fun matchesViewTool(toolName: String, viewToolNames: Set<String>): Boolean =
            toolName in viewToolNames || viewToolNames.any { toolName.endsWith(".$it") }

        /** True when [text] is already a compact reference/diff form (skipped by pruners). */
        fun isCompactForm(text: String): Boolean =
            text.contains(DUPLICATE_MARKER) || text.contains(DIFF_MARKER)

        /**
         * Normalize with explicit [stripPatterns] — the static twin of the
         * instance [normalize], usable from trace/log writers that have no
         * deduper instance (e.g. the CLI engine's page timeline).
         */
        fun normalizeOf(text: String, stripPatterns: List<Regex>): String {
            var t = text
            for (pattern in stripPatterns) t = pattern.replace(t, "")
            return t.lineSequence()
                .map { it.trim().replace(WHITESPACE, " ") }
                .filter { it.isNotEmpty() }
                .joinToString("\n")
        }

        /**
         * SHA-256 fingerprint (hex, [FINGERPRINT_CHARS] chars) with explicit
         * [stripPatterns] — same normalization as the instance [fingerprint],
         * so timeline fingerprints match the deduper's reference digests.
         */
        fun fingerprintOf(text: String, stripPatterns: List<Regex> = DEFAULT_STRIP_PATTERNS): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(normalizeOf(text, stripPatterns).toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }.take(FINGERPRINT_CHARS)
        }

        const val DUPLICATE_MARKER = "[duplicate of result #"
        const val DIFF_MARKER = "[page diff vs result #"

        /** Fingerprint hex length (64 bits of entropy — ample for dedup keys). */
        const val FINGERPRINT_CHARS = 16

        const val DIFF_MAX_CHARS_DEFAULT = 3_000
        const val DIGEST_CHARS_DEFAULT = 300

        /** Changed lines vs total lines above which a diff is not worth it. */
        const val MAX_CHANGED_RATIO = 0.6

        /** Context lines around the change region in a diff. */
        const val CONTEXT_LINES = 3

        val DEFAULT_VIEW_TOOL_NAMES = setOf("ariaSnapshot", "textContent", "snapshot", "dump", "htmlsnapshot", "extract")

        /** Volatile regions removed before fingerprinting (bounding boxes...). */
        val DEFAULT_STRIP_PATTERNS = listOf(Regex("\\[box=[^\\]]*\\]"))

        val WHITESPACE = Regex("\\s+")
    }
}
