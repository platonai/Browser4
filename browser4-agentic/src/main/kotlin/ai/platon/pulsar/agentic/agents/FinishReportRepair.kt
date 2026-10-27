package ai.platon.pulsar.agentic.agents

/**
 * What to do with a completion report the finish gate rejected.
 *
 * Why this exists (2026-10-27 incident): a browsing run writes one helper script
 * per `b4 run eval --file`, so a 13-minute run left **37** files in its
 * workspace. `system.taskComplete(filesChanged=[...])` is a hand-written list and
 * the model rebuilt it from a lossy memory of the run (each inner-loop call only
 * survives as a bounded digest after the 40-iteration cut-off): it reported 20.
 * Rule 7 then rejected the completion with
 * `the run modified 17 file(s) the report does not list: ev.js, evf.js, …` and
 * the strict gate threw after the work — a filed, verified GitHub issue — was
 * already done. The same gate has no repair round, so the task went red.
 *
 * Policy:
 *
 * 1. **Repair first.** The model gets the exact missing list back as a user
 *    message and up to [maxRounds] attempts (the same budget as the
 *    `coding.verify` [RepairLoop]), so an honest report is the normal outcome.
 * 2. **Amend instead of failing.** When the budget is spent — or the loop is
 *    already over — the report is completed from the run's own mutation trace.
 *    The files really were written, so refusing the completion would only mark a
 *    successful run failed; the amendment is logged as a warning and the amended
 *    list is what the agent state and history persist.
 * 3. **Still reject a lie.** Only the rule-7 omission is repairable
 *    ([FinishReportGate.Verdict.onlyUnlistedFiles]). A phantom `filesChanged`
 *    entry, an invented gate, zero executed tool calls or a fabricated
 *    `VERDICT_JSON` remain hard failures.
 *
 * Pure policy (no IO, no model) so the decision table is unit-testable, exactly
 * like [RepairLoop].
 */
internal class FinishReportRepair(
    val maxRounds: Int,
    /**
     * When false the omission is rejected outright (`-Dbrowser4.agent.finish.unlistedFiles=reject`),
     * which restores the fail-fast behaviour that produced the incident.
     */
    private val amendUnlistedFiles: Boolean = true,
    private val maxFeedbackChars: Int = DEFAULT_MAX_FEEDBACK_CHARS,
) {

    /** What the engine should do with a completion claim, given the verdict. */
    enum class Action {
        /** Nothing to repair: accept the completion as reported. */
        PASSED,

        /** Ask the model for a complete report; the budget allows another attempt. */
        RETRY,

        /** Complete `filesChanged` from the run's trace and accept. */
        AMEND,

        /** Fail the task: the violation is not a repairable omission. */
        REJECT,
    }

    /** One decision plus the round bookkeeping and the payload it needs. */
    data class Decision(
        val action: Action,
        /** 1-based repair round for [Action.RETRY], used rounds otherwise. */
        val round: Int = 0,
        /** The user message to inject for [Action.RETRY]. */
        val message: String? = null,
        /** For [Action.AMEND]: the report's list, completed from the trace. */
        val amendedFiles: List<String>? = null,
    )

    private var used = 0

    /** Repair rounds already spent on the current task. */
    val roundsUsed: Int get() = used

    /** Forget the rounds spent so far (new task / new run). */
    fun reset() {
        used = 0
    }

    /**
     * Decide what to do with [verdict].
     *
     * @param reportedFiles the report's `filesChanged` — kept first (and in its
     *   original order) when the list is amended.
     * @param canRepair false when the run's loop cannot take another round (the
     *   turn budget is gone); the decision then goes straight to amend/reject.
     */
    fun decide(
        verdict: FinishReportGate.Verdict,
        reportedFiles: List<String> = emptyList(),
        canRepair: Boolean = true,
    ): Decision {
        if (verdict.passed) {
            // A passing report starts the next failure from round 1 again.
            used = 0
            return Decision(Action.PASSED)
        }
        if (!verdict.onlyUnlistedFiles || !amendUnlistedFiles) {
            return Decision(Action.REJECT, used)
        }
        if (canRepair && maxRounds > 0 && used < maxRounds) {
            used++
            return Decision(
                Action.RETRY, used,
                formatRepair(verdict, used, maxRounds, maxFeedbackChars),
            )
        }
        return Decision(Action.AMEND, used, amendedFiles = merge(reportedFiles, verdict.unlistedFiles))
    }

    companion object {
        /** Upper bound of the repair message injected into the conversation. */
        const val DEFAULT_MAX_FEEDBACK_CHARS = 4_000

        /** Missing files listed in one repair message (the rest are counted). */
        const val MAX_LISTED_FILES = 40

        /**
         * Render the bounded repair message for a rejected finish report.
         *
         * Shape (stable — agents and supervisors parse it):
         * ```
         * Finish report round 1/3 rejected — the run modified 17 file(s) that
         * filesChanged does not list:
         *   1. ev.js
         *   ...
         * Fix: ...
         * ```
         */
        fun formatRepair(
            verdict: FinishReportGate.Verdict,
            round: Int,
            maxRounds: Int,
            maxChars: Int = DEFAULT_MAX_FEEDBACK_CHARS,
        ): String {
            val missing = verdict.unlistedFiles
            val listed = missing.take(MAX_LISTED_FILES)
            val sb = StringBuilder()
            sb.appendLine(
                "Finish report round $round/$maxRounds rejected — the run modified " +
                    "${missing.size} file(s) that filesChanged does not list:"
            )
            listed.forEachIndexed { index, path ->
                sb.appendLine("  ${index + 1}. $path")
            }
            if (missing.size > listed.size) {
                sb.appendLine("  ... (${missing.size - listed.size} more omitted)")
            }
            sb.append(
                "Fix: coding.changeSummary lists exactly what this run wrote — copy that list into " +
                    "filesChanged and call system.taskComplete again. Files that are not deliverables " +
                    "belong under .b4scratch/, which the gate does not count. " +
                    "This is a bookkeeping check: the work itself is not re-run."
            )

            val text = sb.toString().trimEnd()
            if (text.length <= maxChars) return text
            val marker = "\n... (finish repair feedback truncated; coding.changeSummary has the full list)"
            val budget = (maxChars - marker.length).coerceAtLeast(0)
            return text.take(budget) + marker
        }

        /**
         * The report's list plus the files it omitted, de-duplicated by normalized
         * path so `./ev.js` in the report does not come back as `ev.js`.
         */
        internal fun merge(reported: List<String>, unlisted: List<String>): List<String> {
            val out = ArrayList<String>(reported.size + unlisted.size)
            val seen = HashSet<String>()
            fun add(raw: String) {
                val trimmed = raw.trim()
                if (trimmed.isEmpty()) return
                val key = normalize(trimmed)
                if (key.isEmpty() || !seen.add(key)) return
                out += trimmed
            }
            reported.forEach(::add)
            // Trace paths are already normalized (they are workspace-relative).
            unlisted.forEach(::add)
            return out
        }

        private fun normalize(raw: String): String =
            raw.trim().trim('"').replace('\\', '/').removePrefix("./").removePrefix("/")
    }
}
