package ai.platon.pulsar.agentic.model

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
            val key = "$resolvedDomain.$resolvedMethod"
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
            val ok = evaluate.success && exception == null
            val summary = buildSummary(ok, raw, exception)
            val body = raw.takeIf { it.isNotBlank() }
                ?.let { Strings.compactInline(it, BODY_BUDGETS[key] ?: DEFAULT_BODY_LEN) }

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
    }
}
