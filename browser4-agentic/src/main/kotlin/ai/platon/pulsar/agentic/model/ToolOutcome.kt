package ai.platon.pulsar.agentic.model

import ai.platon.pulsar.coding.PermissionAudit
import ai.platon.pulsar.common.Strings

/**
 * Bounded, model-facing summary of one tool execution — the minimal complete
 * information the agent needs to see after each step: which tool ran, whether
 * it succeeded, a one-line summary, the key evidence (truncated per tool
 * family), and any errors.
 *
 * Full results remain available in the persisted state logs; this envelope is
 * what gets rendered into the Execution History / previous-step-result message
 * so that context stays bounded (design: docs-dev/copilot/
 * browser4-agent-tool-disclosure-feedback-design.md §2).
 *
 * A `PERMISSION_DENIED | code=…` refusal counts as a failure ([ok] is false) even
 * though the tool call itself did not throw: a refusal the model reads as success
 * is a refusal the model retries.
 */
data class ToolOutcome(
    val domain: String,
    val method: String,
    val ok: Boolean,
    val summary: String,
    val body: String? = null,
    val errors: List<String> = emptyList(),
    val workspaceDelta: String? = null,
) {
    /** One-line header: `N. domain.method [ok|fail] summary` (caller adds the step number). */
    val header: String
        get() = "$domain.$method [${if (ok) "ok" else "fail"}] $summary"

    /** Full block for prompt rendering (header + indented body/errors/delta). */
    fun render(): String = buildString {
        appendLine(header)
        body?.takeIf { it.isNotBlank() }?.let {
            appendLine(it.trim().prependIndent("  "))
        }
        errors.forEach { appendLine("- ${it.trim()}".prependIndent("  ")) }
        workspaceDelta?.takeIf { it.isNotBlank() }?.let { appendLine("Δ $it") }
    }

    companion object {
        private const val DEFAULT_SUMMARY_LEN = 120
        private const val DEFAULT_BODY_LEN = 1_200
        private const val MAX_ERROR_LEN = 300

        /**
         * Char budgets for verbose tool families (everything else uses [DEFAULT_BODY_LEN]).
         *
         * Keyed by **method only**: the lookup used to be `"$domain.$method"`, so every
         * entry here was dead code and each tool fell back to [DEFAULT_BODY_LEN]. The
         * visible damage was `system.skillDoc`: SKILL.md came back as 1,290 chars of
         * `…[truncated 58125 chars]…` with no way to read further, so the model could
         * not reach the reference docs the prompt told it to consult (2026-10-28 run).
         */
        private val BODY_BUDGETS: Map<String, Int> = mapOf(
            "mvnBuild" to 3_000,
            "shell" to 3_000,
            "shellOutput" to 3_000,
            "read" to 1_600,
            "grep" to 1_600,
            "diagnostics" to 2_000,
            "ktSymbols" to 1_600,
            "ktReferences" to 1_600,
            // One paginated skill document. `SystemToolExecutor.skillDoc` already pages
            // at 8,000 chars, so a default page survives intact; an explicit larger
            // `limit` is cut here with a continuation hint (see [boundedBody]).
            "skillDoc" to 8_000,
        )

        /**
         * Build the envelope from an executed [ToolCallResult].
         *
         * @param domain explicit domain override — used when the caller already
         *   resolved the tool call but the result carries no [ActionDescription]
         *   (e.g. the native tool-calling loop); falls back to the
         *   actionDescription's toolCall, then "unknown".
         * @param method explicit method override (same fallback semantics).
         * @param workspaceDelta optional file-change delta (e.g. "files +0, lines +47/-44");
         *   callers with filesystem access may supply it for write-family tools.
         */
        fun from(
            result: ToolCallResult,
            domain: String? = null,
            method: String? = null,
            workspaceDelta: String? = null,
        ): ToolOutcome {
            val evaluate = result.evaluate
            val toolCall = result.actionDescription?.toolCall
            val resolvedDomain = domain ?: toolCall?.domain ?: "unknown"
            val resolvedMethod = method ?: toolCall?.method ?: "unknown"
            val exception = evaluate.exception

            val errors = mutableListOf<String>()
            var cause: Throwable? = exception?.cause
            while (cause != null && errors.size < 3) {
                (cause.message ?: cause.javaClass.simpleName)
                    .take(MAX_ERROR_LEN)
                    .takeIf { it.isNotBlank() }
                    ?.let { errors += it }
                cause = cause.cause
            }

            val raw = evaluate.value?.toString().orEmpty()
            // A policy refusal is a failure FOR THE MODEL even though the tool
            // executed and returned the refusal as a value (`PermissionAudit`'s
            // contract is "returned, never thrown"). Rendering it as `[ok]` hid
            // every refusal from `--follow` (which prints `[fail]` results only)
            // and from the model, which re-phrased the same denied path four
            // times in a live run because nothing said the call was refused.
            val refused = isPermissionRefusal(raw)
            val ok = evaluate.success && exception == null && !refused
            val summary = if (refused) buildRefusalSummary(raw) else buildSummary(ok, raw, exception)
            val budget = BODY_BUDGETS[resolvedMethod] ?: DEFAULT_BODY_LEN
            // Only `system.skillDoc` is paginated: its result is a slice of a document,
            // so the truncation marker must name the offset that continues THIS slice
            // (a bare "truncated 8,000 chars" is unreachable for a 59 KB SKILL.md).
            val continuation = if (resolvedMethod == "skillDoc") {
                { keptChars: String -> skillDocContinuation(raw, keptChars) }
            } else null
            val body = raw.takeIf { it.isNotBlank() }
                ?.let { boundedBody(it, budget, continuation) }

            return ToolOutcome(
                domain = resolvedDomain, method = resolvedMethod, ok = ok,
                summary = summary, body = body, errors = errors,
                workspaceDelta = workspaceDelta,
            )
        }

        private fun buildSummary(ok: Boolean, raw: String, exception: TcException?): String {
            if (!ok) {
                val reason = exception?.message ?: "execution failed"
                return "failed: " + Strings.compactInline(reason, DEFAULT_SUMMARY_LEN - 8)
            }
            val firstLine = raw.lineSequence().firstOrNull { it.isNotBlank() } ?: "done"
            return Strings.compactInline(firstLine, DEFAULT_SUMMARY_LEN)
        }

        /**
         * True when [raw] **starts** with a `PERMISSION_DENIED | code=…` refusal
         * envelope (see [PermissionAudit]).
         *
         * The envelope must lead the text: `memory.read` returns JSON that *contains*
         * earlier refusal events, and matching anywhere in the first line marked a
         * successful memory read as `[fail]` (observed 2026-10-28). Producers prefix
         * the envelope either directly (`coding.shell`) or with `Error: `
         * (`coding.fs`), so only that tolerates a prefix; the rest of a body is data.
         */
        private fun isPermissionRefusal(raw: String): Boolean {
            val firstLine = raw.lineSequence().firstOrNull { it.isNotBlank() }?.trimStart() ?: return false
            val candidate = firstLine.removePrefix("Error: ").trimStart()
            return candidate.startsWith(PermissionAudit.DENIED) && candidate.contains("| code=")
        }

        /**
         * The refusal text itself is the useful summary — `failed: execution failed`
         * would throw away the only part the model needs (the reason and the hint).
         */
        private fun buildRefusalSummary(raw: String): String {
            val firstLine = raw.lineSequence().firstOrNull { it.isNotBlank() } ?: PermissionAudit.DENIED
            return Strings.compactInline(firstLine, DEFAULT_SUMMARY_LEN)
        }

        /**
         * Bound [raw] to [budget] characters **without destroying its line structure**.
         *
         * The body is *content* (file text, command output), not prose: inlining it —
         * `Strings.compactInline` turns every newline into a space — made `coding.read`
         * return a whole file as a single line, so the model could not see line or row
         * boundaries. Observed 2026-10-28: a three-line input came back as
         * `ALPHA-1 BETA-2 GAMMA-3` and the model fell back to python to recover the
         * lines (and a CSV read back this way is unusable). Only the summary is
         * inlined; an over-budget body is cut in the middle like a CLI result.
         *
         * @param continuation when set, the result is **paginated content** rather than
         *   free text: the cut keeps only the head (a tail would repeat data the model
         *   already has, in the wrong order) and [continuation] renders the marker that
         *   names the offset continuing the slice.
         */
        private fun boundedBody(
            raw: String,
            budget: Int,
            continuation: ((String) -> String)? = null,
        ): String {
            val text = raw.replace("\r\n", "\n").trim()
            if (text.length <= budget) return text
            if (continuation != null) {
                // Reserve room for the marker, then render it from the text we actually
                // keep so the offset it names is exact.
                var keep = (budget - 160).coerceAtLeast(0)
                var head = text.take(keep)
                var marker = "\n" + continuation(head) + "\n"
                val overflow = head.length + marker.length - budget
                if (overflow > 0) {
                    keep = (keep - overflow).coerceAtLeast(0)
                    head = text.take(keep)
                    marker = "\n" + continuation(head) + "\n"
                }
                return head + marker
            }
            val omitted = text.length - budget
            val marker = "\n…[truncated $omitted chars]…\n"
            val keep = (budget - marker.length).coerceAtLeast(0)
            if (keep <= 0) return text.take(budget)
            val head = keep * 2 / 3
            val tail = keep - head
            return text.take(head) + marker + text.takeLast(tail)
        }

        /** `offset=(\d+)` in the pagination header [SystemToolExecutor] emits. */
        private val SKILL_DOC_OFFSET = Regex("""\boffset=(\d+)""")

        /**
         * Continuation marker for a truncated skill-document page: the next offset must
         * point into the DOCUMENT, not into this page (a page can start at 24,000).
         */
        private fun skillDocContinuation(raw: String, keptText: String): String {
            val omitted = raw.length - keptText.length
            val pageStart = raw.lineSequence().firstOrNull()
                ?.let { SKILL_DOC_OFFSET.find(it)?.groupValues?.get(1)?.toIntOrNull() }
            if (pageStart == null) return "…[truncated $omitted chars]…"
            val consumed = keptText.codePointCount(0, keptText.length)
            return "…[truncated $omitted chars; call system.skillDoc(name, offset=${pageStart + consumed})]…"
        }
    }
}
