package ai.platon.pulsar.agentic.inference.chat

import ai.platon.pulsar.coding.SafeText

/**
 * Bounded, information-preserving excerpts of tool results, used by the
 * continuation digests that outlive a tool-loop round.
 *
 * The inner [AgentToolCallLoop] builds a local message list, so when it
 * overflows its iteration cap the tool calls/results die with it. The caller
 * feeds a digest of what executed back to the model ([AgentToolCallLoop]
 * overflow error and [ai.platon.pulsar.agentic.agents.cliRoundToolDigest]).
 *
 * That digest used to keep the **first line only** of each result. For
 * `coding.shell` the first line is `Session: devshell-N`, so a round of forty
 * shell commands was replaced by forty session ids and the model had to re-run
 * everything — the observed churn (23 overflows / 567 shell calls / 20M input
 * tokens in one run). The excerpt keeps the head *and* the tail instead, and
 * for shell results it starts at the captured stdout/stderr body rather than
 * the fixed `Session/Status/Exit Code/Duration` header.
 *
 * All cuts go through [SafeText], so an emoji at the boundary can never leave
 * a lone surrogate behind.
 */
internal object ToolResultDigest {

    /** Characters kept from the start of a result. */
    const val HEAD_CHARS = 160

    /** Characters kept from the end of a result — where failures are reported. */
    const val TAIL_CHARS = 280

    /** Results shorter than this are reproduced verbatim. */
    const val EXCERPT_CHARS = HEAD_CHARS + TAIL_CHARS

    private const val STDOUT_MARKER = "--- stdout ---"
    private const val STDERR_MARKER = "--- stderr ---"
    private const val SEPARATOR = " | "

    /**
     * A compact excerpt of [text]: the whole text when short, otherwise its head
     * and tail joined by an ellipsis. Empty results produce an empty excerpt.
     */
    fun excerpt(text: String): String {
        val body = bodyOf(text)
        if (body.isEmpty()) return ""
        if (body.length <= EXCERPT_CHARS) return body
        return "${SafeText.take(body, HEAD_CHARS)} … ${SafeText.takeLast(body, TAIL_CHARS)}"
    }

    /**
     * Render `label -> excerpt` entries into one digest of at most [maxChars].
     *
     * Entries are added **newest first** and the oldest are dropped when the
     * budget is exhausted, so a plain tail cut can never throw away the results
     * the model needs to continue (the failure mode of truncating the joined
     * string). The accepted entries are returned in chronological order.
     */
    fun digest(entries: List<Pair<String, String>>, maxChars: Int): String {
        if (entries.isEmpty() || maxChars <= 0) return ""
        val kept = ArrayDeque<String>()
        var length = 0
        for ((label, text) in entries.asReversed()) {
            val excerpt = excerpt(text)
            val piece = if (excerpt.isEmpty()) label else "$label -> $excerpt"
            val separator = if (kept.isEmpty()) 0 else SEPARATOR.length
            if (length + piece.length + separator > maxChars) break
            kept.addFirst(piece)
            length += piece.length + separator
        }
        if (kept.isEmpty()) {
            // A single entry larger than the whole budget: still answer with its
            // bounded head rather than an empty digest.
            val (label, text) = entries.last()
            val excerpt = excerpt(text)
            val piece = if (excerpt.isEmpty()) label else "$label -> $excerpt"
            return SafeText.take(piece, maxChars)
        }
        return kept.joinToString(SEPARATOR)
    }

    /**
     * The part of a result worth showing: for `coding.shell` output the command's
     * stdout/stderr (the header is constant noise), for everything else the
     * trimmed text. stdout and stderr are concatenated, so the tail of the
     * excerpt carries the diagnostics of a failed build.
     */
    private fun bodyOf(text: String): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return ""
        val stdout = bodyAfter(trimmed, STDOUT_MARKER)
        val stderr = bodyAfter(trimmed, STDERR_MARKER)
        val parts = listOf(stdout, stderr).filter { it.isNotEmpty() }
        return if (parts.isEmpty()) trimmed else parts.joinToString("\n")
    }

    private fun bodyAfter(text: String, marker: String): String {
        val at = text.indexOf(marker)
        if (at < 0) return ""
        val start = at + marker.length
        // The body ends where the other stream's marker starts (if any).
        val other = if (marker == STDOUT_MARKER) STDERR_MARKER else STDOUT_MARKER
        val end = text.indexOf(other, start).takeIf { it >= 0 } ?: text.length
        return text.substring(start, end).trim()
    }
}
