package ai.platon.pulsar.agentic.agents

import ai.platon.pulsar.coding.VerificationReport

/**
 * Repair-round budget for a dev task (plan P1.3).
 *
 * When a verification gate fails, the agent should not "narrate" its way to a
 * completion: it gets the **structured diagnostics** back as one user message
 * and another attempt, bounded by `browser4.agent.repair.maxRounds`
 * ([AgentConfig.repairMaxRounds], default 3, `0` disables the loop). Past the
 * budget the task fails with the verdict as evidence instead of being reported
 * as done.
 *
 * The class is pure policy (no IO, no model): [decide] maps the latest
 * [VerificationReport] to an action, and [formatFeedback] renders the bounded
 * message. That keeps the loop unit-testable without a live model.
 */
class RepairLoop(
    val maxRounds: Int,
    private val maxFeedbackChars: Int = DEFAULT_MAX_FEEDBACK_CHARS,
) {
    /** What the engine should do with a completion claim, given the last verdict. */
    enum class Action {
        /** The verdict passed: accept the completion. */
        PASSED,

        /** The verdict failed and the budget allows another attempt. */
        RETRY,

        /** The verdict failed and the budget is spent: fail the task. */
        EXHAUSTED,

        /** The loop is switched off (`maxRounds <= 0`): behave as before. */
        DISABLED,
    }

    /** One decision: the action plus the round bookkeeping and the message to inject. */
    data class Decision(
        val action: Action,
        /** 1-based round number for [Action.RETRY] / used rounds for [Action.EXHAUSTED]. */
        val round: Int = 0,
        /** The user message to inject for [Action.RETRY]. */
        val message: String? = null,
    ) {
        val shouldRetry: Boolean get() = action == Action.RETRY
    }

    private var used = 0

    /** Repair rounds already spent on the current task. */
    val roundsUsed: Int get() = used

    /**
     * Decide what to do with [report].
     *
     * A passing verdict clears the budget (a later task-level failure starts
     * from round 1 again); a failing one spends one round per [Action.RETRY].
     */
    fun decide(report: VerificationReport): Decision {
        if (report.passed) {
            used = 0
            return Decision(Action.PASSED)
        }
        if (maxRounds <= 0) return Decision(Action.DISABLED)
        if (used >= maxRounds) return Decision(Action.EXHAUSTED, used)
        used++
        return Decision(Action.RETRY, used, formatFeedback(report, used, maxRounds, maxFeedbackChars))
    }

    /** Forget the rounds spent so far (new task / new run). */
    fun reset() {
        used = 0
    }

    companion object {
        /** Upper bound of the feedback message injected into the conversation. */
        const val DEFAULT_MAX_FEEDBACK_CHARS = 4_000

        /** Diagnostics listed in one feedback message (the rest are counted). */
        const val MAX_DIAGNOSTICS = 20

        /**
         * Render the bounded repair message for a failed verification round.
         *
         * Shape (stable — agents and supervisors parse it):
         * ```
         * Verification round 1/3 FAILED — compile browser4-coding failed: ...
         * Diagnostics (2):
         *   1. [error] path:12:5 — message
         * Fix the code, then re-run coding.verify(module="…") and only call …
         * ```
         */
        fun formatFeedback(
            report: VerificationReport,
            round: Int,
            maxRounds: Int,
            maxChars: Int = DEFAULT_MAX_FEEDBACK_CHARS,
        ): String {
            val reason = report.failureReason() ?: "verification did not pass"
            val allDiagnostics = report.diagnostics()
            val diagnostics = allDiagnostics.take(MAX_DIAGNOSTICS)

            val sb = StringBuilder()
            sb.appendLine("Verification round $round/$maxRounds FAILED — $reason")
            if (diagnostics.isNotEmpty()) {
                sb.appendLine("Diagnostics (${allDiagnostics.size}):")
                diagnostics.forEachIndexed { index, diagnostic ->
                    sb.appendLine("  ${index + 1}. ${diagnostic.format()}")
                }
                if (allDiagnostics.size > diagnostics.size) {
                    sb.appendLine("  ... (${allDiagnostics.size - diagnostics.size} more diagnostics omitted)")
                }
            }
            sb.append("Fix the code, then re-run ").append(verifyCall(report))
                .append(". Only call system.taskComplete when the verdict passes, ")
                .append("and keep the plan's acceptance checklist unchanged.")

            val text = sb.toString().trimEnd()
            if (text.length <= maxChars) return text
            val marker = "\n... (repair feedback truncated; the full verdict is in the verify tool output)"
            val budget = (maxChars - marker.length).coerceAtLeast(0)
            return text.take(budget) + marker
        }

        /** The `coding.verify(...)` call that reproduces the failed verdict. */
        private fun verifyCall(report: VerificationReport): String {
            val module = report.module
            val checks = report.requested.filter { it.isNotBlank() }
            if (module.isNullOrBlank()) return "coding.verify()"
            return if (checks.isEmpty()) {
                "coding.verify(module=\"$module\")"
            } else {
                "coding.verify(module=\"$module\", checks=\"${checks.joinToString(",")}\")"
            }
        }
    }
}
