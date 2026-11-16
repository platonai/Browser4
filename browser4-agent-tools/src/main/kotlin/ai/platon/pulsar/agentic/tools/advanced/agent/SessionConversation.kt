package ai.platon.pulsar.agentic.tools.advanced.agent

import ai.platon.pulsar.agentic.model.AgentHistory
import ai.platon.pulsar.common.ResourceStatus
import ai.platon.pulsar.common.config.ImmutableConfig

/**
 * One earlier task of a session, as the session's conversation remembers it.
 *
 * Deliberately a *summary* of the task, not its transcript: the point is to give
 * the next task the background a human would have after reading the previous
 * result — what was asked, how it ended, what it produced, what it left open.
 *
 * @property instruction what the user asked for (one line, bounded)
 * @property status `done`, `failed` or `cancelled`
 * @property summary the agent's own closing report, when it gave one
 * @property findings the completion report's key findings
 * @property files the files the run changed
 * @property problems the open problems the run reported
 */
internal data class SessionTaskRecord(
    val instruction: String,
    val status: String,
    val summary: String? = null,
    val findings: List<String> = emptyList(),
    val files: List<String> = emptyList(),
    val problems: List<String> = emptyList(),
    val elapsedMs: Long? = null,
)

/** Heading of the block [SessionConversation.renderBlock] produces. */
internal const val SESSION_BLOCK_MARKER = "## Session context"

/**
 * One place the user redirected the session, as the block reports it.
 *
 * Exactly one of [afterTask] / [duringTask] is set: a redirection arrives either as
 * the next task's instruction or as a line typed into the running one.
 *
 * @property afterTask the task whose instruction redirected the session (`null` for
 *   a mid-task line)
 * @property duringTask the task that was running when the line was typed (`null` for
 *   a task instruction)
 * @property text what the user said, one line and bounded
 */
private data class Correction(
    val afterTask: Int? = null,
    val duringTask: Int? = null,
    val text: String,
) {
    /** `task 5: "actually, ship the CLI flag first"` / `during task 5: "no, CSV"`. */
    fun render(): String {
        val whenText = when {
            afterTask != null -> "task $afterTask"
            duringTask != null -> "during task $duringTask"
            else -> "this session"
        }
        return "$whenText: \"$text\""
    }
}

/**
 * Whether [text] reads like the user REDIRECTING the work rather than adding a step.
 *
 * Deliberately a small, conservative cue list, and deliberately biased: a false
 * POSITIVE is harmless — the line is rendered as a user correction, which is exactly
 * what it is, and it is bounded to three entries — while a false NEGATIVE only means
 * the correction keeps the position every instruction already has, at the top of the
 * next message. Nothing is ever *replaced* by this test: the goal stays visible and
 * the correction is rendered above it as superseding.
 */
internal fun looksLikeCorrection(text: String): Boolean {
    val lower = text.lowercase()
    return CORRECTION_CUES.any { lower.contains(it) }
}

/**
 * Phrases that mark a redirection, English and Chinese.
 *
 * Kept in one place and unit-tested, because a silently growing list is how a
 * heuristic starts eating ordinary instructions.
 */
internal val CORRECTION_CUES: List<String> = listOf(
    "actually",
    "instead",
    "no longer",
    "not anymore",
    "forget about",
    "forget the",
    "scratch that",
    "change of plan",
    "changed my mind",
    "stop working on",
    "new direction",
    "let's switch",
    "let us switch",
    "revise the",
    "修正",
    "改一下",
    "改用",
    "改成",
    "改为",
    "换成",
    "不要",
    "别再",
    "先不",
    "放弃",
    "重新定义",
)

/**
 * The cross-task conversation of one agent session.
 *
 * ## Why this exists
 *
 * Every `agent run` task used to start from zero: the runner executed task after
 * task on the same companion agent, but `agent.run(instruction)` builds a fresh
 * conversation, so task N had no idea what task N-1 had done. In the interactive
 * session (`agent run -i`) that is the visible complaint — "each task is
 * independent, the session's context is lost" — and it forces the user to restate
 * what the previous run already established.
 *
 * The runner therefore keeps a compacted record of the session's finished tasks and
 * prepends it (as a `## Session context` block) to the next instruction. The next
 * run knows what was asked, what changed on disk, and what is still open.
 *
 * ## Compression, and the "forgot the original task" failure it must avoid
 *
 * The block ships in EVERY prompt of the next run, so it must stay small no matter
 * how long the session runs. Two mechanisms, both automatic:
 *
 * 1. **Bounded records** — each task keeps a one-line instruction, a bounded
 *    summary, and bounded findings/files/problems lists.
 * 2. **Budget-driven compaction** ([renderBlock]) — the rendered block is kept
 *    under [budgetChars] (`browser4.agent.session.contextTokens`, default 1200
 *    tokens ≈ 4.8 KB). When it does not fit, the OLDEST tasks stop being rendered
 *    in detail and are rolled up into a single compacted line; if even that does
 *    not fit, the remaining records are rendered in digest form.
 *
 * ## Focus guarantees (the thing a naive recency-biased compaction gets wrong)
 *
 * Dropping the oldest first is what a multi-round session cannot afford: the oldest
 * record is the *founding* task, so "implement feature X end to end" was the first
 * thing folded into "and N more" and the first thing evicted at [MAX_RECORDS] —
 * after a dozen sub-tasks the model saw only a changelog and no goal at all. Three
 * explicit guarantees prevent that:
 *
 * 1. **The session goal is anchored.** [goal] is the instruction of the first
 *    recorded task; it is never rolled up, never evicted, and rendered near the top
 *    of every block until the session ends (or `/new` starts a new one).
 * 2. **A goal can be revised, and the revision wins.** A mid-session redirection —
 *    a task instruction that reads like one, or a line typed into a running task —
 *    is kept in [corrections] and rendered ABOVE the goal as superseding it, the
 *    block says the instruction at the top of the message wins over both, and the
 *    goal line no longer claims to be current. The goal is an anchor, never an
 *    authority: a block that kept insisting on the founding task would fight the
 *    very instruction the user just typed — the mirror image of losing it.
 * 3. **Open problems survive the fold.** Problems an *undetailed* (older) task
 *    reported are re-listed in their own section, so "the CI gate was not run" from
 *    task 2 is still actionable in task 9.
 * 4. **Degradation never deletes the newest task to keep stale titles.** The
 *    fallback chain ends in [minimal] (newest direction + newest task), not in a
 *    top-down truncation that would keep the header and stale rollup while cutting
 *    the most relevant bullet.
 *
 * The newest task is always kept — it is the one the next instruction most likely
 * refers to — and the goal line names what the whole session is for, with any
 * later correction stated as overriding it.
 *
 * A session that never ran a task renders nothing, so a one-shot `agent run` is
 * completely unaffected.
 *
 * ## Threading
 *
 * Not thread-safe, and does not need to be: the runner executes one task at a time
 * ([StatefulAgentRunner]'s `runMutex`), so [record] (end of a task), [renderBlock]
 * (start of the next one) and [noteUserInput] (that task's own steering drain) never
 * overlap. The runner rebuilds the conversation when it rebuilds the session.
 *
 * @param budgetChars hard cap on the rendered block, in characters (floored at
 *   [MIN_BUDGET_CHARS]: a goal plus one task cannot say anything useful below that)
 * @param detailEntries how many of the newest tasks keep their details
 */
internal class SessionConversation(
    budgetChars: Int = DEFAULT_CONTEXT_TOKENS * CHARS_PER_TOKEN,
    private val detailEntries: Int = DEFAULT_DETAIL_ENTRIES,
) {
    private val budgetChars = budgetChars.coerceAtLeast(MIN_BUDGET_CHARS)

    private val records = mutableListOf<SessionTaskRecord>()

    /**
     * Later user corrections, oldest first: a task instruction that redirects the
     * session, or a line the user typed into a running task.
     *
     * A goal is an anchor, not an authority. The user routinely changes direction
     * mid-session ("actually, ship the CLI flag first"), and a block that kept
     * repeating the founding task as *current* would fight the very instruction it
     * is supposed to support — the mirror image of losing the goal. So corrections
     * are recorded separately, rendered above the history, and stated to supersede
     * the goal where they conflict; the instruction at the top of the message always
     * wins over both.
     *
     * Bounded ([CORRECTION_ENTRIES] × [CORRECTION_CHARS]): the newest few are what
     * matters, and this rides in every prompt.
     */
    private val corrections = mutableListOf<Correction>()

    /**
     * The session's founding task: what the user originally set out to do.
     *
     * Set from the FIRST recorded instruction and never rewritten — later tasks are
     * means to this end, a *correction* is recorded in [corrections] instead (both
     * facts stay visible; neither silently replaces the other). Survives eviction and
     * every compaction step, which is the whole point: the goal is the one fact a long
     * session must not lose.
     */
    var goal: String? = null
        private set

    /** Finished tasks this session remembers (bounded by [MAX_RECORDS]). */
    val size: Int get() = records.size

    /** A copy of the remembered tasks, oldest first — for tests and diagnostics. */
    fun snapshot(): List<SessionTaskRecord> = records.toList()

    /** The recorded corrections, oldest first — for tests and diagnostics. */
    fun correctionTexts(): List<String> = corrections.map { it.text }

    fun clear() {
        records.clear()
        corrections.clear()
        goal = null
    }

    /**
     * Remember [status], a task that just reached a terminal state.
     *
     * A task with no instruction to remember is skipped: an empty bullet would
     * spend the next run's context on nothing.
     */
    fun record(status: AgentTaskStatus) {
        val finalState = status.agentHistory?.finalResult
        val instruction = (status.submittedTask ?: finalState?.instruction.orEmpty())
            .singleLine()
            .take(INSTRUCTION_CHARS)
        if (instruction.isBlank()) return
        // Anchor the session goal on the first task only. A later task is a step
        // toward that goal, never a replacement for it — the failure mode this guards
        // against is a long session whose block silently starts describing task 9's
        // local sub-goal as if it were the point of the session. A task that REDIRECTS
        // the session is a correction, not a sub-goal: it is recorded below and
        // rendered as superseding the goal.
        if (goal == null) {
            goal = instruction
        } else if (looksLikeCorrection(instruction)) {
            addCorrection(Correction(afterTask = records.size + 1, text = instruction))
        }

        records += SessionTaskRecord(
            instruction = instruction,
            status = statusWord(status),
            summary = (finalState?.summary ?: status.failureReason ?: status.message)
                ?.singleLine()
                ?.take(SUMMARY_CHARS)
                ?.takeIf { it.isNotBlank() },
            findings = bounded(finalState?.keyFindings, MAX_FINDINGS, FINDING_CHARS),
            // A run that aborted before its completion report still left artifacts:
            // the runner snapshots them into `deliverables`.
            files = bounded(
                finalState?.filesChanged?.takeIf { it.isNotEmpty() } ?: status.deliverables,
                MAX_FILES, FILE_CHARS,
            ),
            problems = bounded(finalState?.problems, MAX_PROBLEMS, FINDING_CHARS),
            elapsedMs = status.startedTime?.let { started ->
                status.finishTime?.let { finished ->
                    java.time.Duration.between(started, finished).toMillis()
                }
            },
        )
        while (records.size > MAX_RECORDS) {
            records.removeAt(0)
        }
    }

    /**
     * Remember what the user typed into a RUNNING task (steering).
     *
     * A mid-task line is the most direct statement of intent a session gets — "no,
     * use CSV not XLSX" — and it used to die with the run it steered: the agent drained
     * it, and the next task knew nothing. Recording it here is what keeps a correction
     * alive for the rest of the session.
     *
     * Every steering line is kept (they are rare: a human at the prompt), bounded to
     * the newest [CORRECTION_ENTRIES]. A line that reads like a redirection also marks
     * the running task's position, so the block can say when it happened.
     */
    fun noteUserInput(messages: List<String>) {
        messages.forEach { message ->
            val text = message.singleLine().take(CORRECTION_CHARS)
            if (text.isBlank()) return@forEach
            addCorrection(Correction(duringTask = records.size + 1, text = text))
        }
    }

    /** Append a correction, newest last, deduplicated and bounded. */
    private fun addCorrection(correction: Correction) {
        if (corrections.any { it.text.equals(correction.text, ignoreCase = true) }) return
        corrections += correction
        while (corrections.size > CORRECTION_ENTRIES) {
            corrections.removeAt(0)
        }
    }

    /**
     * The `## Session context` block for the next task's instruction, or `""` when
     * this session has not finished a task yet.
     *
     * The block is compacted until it fits [budgetChars] (see the class KDoc), so the
     * caller can always prepend it without checking a budget itself. Every step of
     * that chain keeps the session goal and the newest task (see the class KDoc's
     * focus guarantees).
     */
    fun renderBlock(): String {
        if (records.isEmpty()) return ""

        val total = records.size
        var detail = detailEntries.coerceIn(1, total)
        while (true) {
            val text = compose(detail, digestOnly = false)
            if (text.length <= budgetChars || detail <= 1) {
                if (text.length <= budgetChars) return text
                break
            }
            detail--
        }

        // Only the newest task is left in detail and it still does not fit: shrink it
        // to its instruction + outcome (the goal and the open problems stay).
        val digest = compose(detail = 1, digestOnly = true)
        if (digest.length <= budgetChars) return digest
        // Last resort: goal + newest task, no history at all. Deliberately NOT a
        // top-down truncation — that would keep the header and stale rollup and cut
        // the newest bullet, i.e. lose exactly what the next task refers to.
        return minimal()
    }

    private fun compose(detail: Int, digestOnly: Boolean): String {
        val olderCount = (records.size - detail).coerceAtLeast(0)
        val older = records.take(olderCount)
        val recent = records.takeLast(detail)

        val lines = mutableListOf<String>()
        lines += SESSION_BLOCK_MARKER
        lines += ""
        lines += "This session already ran ${records.size} earlier task(s). Treat them as background:"
        lines += "do not repeat work a completed task finished, build on the files it changed, and"
        lines += "continue from where an interrupted one stopped. The instruction at the top of this"
        lines += "message is the task to perform NOW — if it contradicts anything below, it wins."
        // Corrections first: a redirection outranks both the founding goal and the
        // history, and it must never be the thing a fold removes.
        if (corrections.isNotEmpty()) {
            lines += ""
            lines += "User corrections (they supersede the goal below where they conflict):"
            corrections.forEach { lines += "  - ${it.render()}" }
        }
        // Anchor the goal only once the founding task stops being rendered in detail —
        // below that point the bullet already carries it, and repeating it would spend
        // the budget twice. From the first fold onward it is the one line that says what
        // the whole session is for.
        if (older.isNotEmpty()) {
            goal?.let {
                lines += ""
                lines += "Session goal (set by the first task; later corrections supersede it): \"$it\""
            }
        }
        // Problems reported by a task that is about to be folded out of detail: they are
        // unresolved, so they must not disappear with the bullet that carried them.
        val olderProblems = openProblems(older)
        if (olderProblems.isNotEmpty()) {
            lines += ""
            lines += "Problems earlier tasks reported as unresolved (unless a later task resolved them):"
            olderProblems.forEach { lines += "  ⚠ $it" }
        }
        lines += ""

        if (older.isNotEmpty()) {
            lines += "- ${olderCount} earlier task(s), compacted: " + rollup(older)
        }
        for (record in recent) {
            lines += renderRecord(record, digestOnly)
        }
        return lines.joinToString("\n")
    }

    /**
     * The newest direction + the newest task: the smallest block that still says what
     * the session is for now and where it stopped.
     */
    private fun minimal(): String = buildString {
        appendLine(SESSION_BLOCK_MARKER)
        appendLine()
        appendLine("This session ran ${records.size} earlier task(s); build on their work instead of")
        appendLine("repeating it. The instruction above is the task to perform NOW.")
        // The newest user correction outranks the founding goal: a redirect is what the
        // session is for NOW, and at this size only one of the two can be stated.
        val newestCorrection = corrections.lastOrNull()
        when {
            newestCorrection != null ->
                appendLine("Latest user correction (supersedes the original goal): ${newestCorrection.render()}")
            // With a single task the goal IS the bullet below it.
            records.size > 1 && goal != null ->
                appendLine("Session goal (set by the first task): \"$goal\"")
        }
        appendLine()
        // No summary: at this budget the instruction + outcome is all that fits, and the
        // goal already says what the work is for.
        val newest = records.last()
        appendLine("- \"${newest.instruction}\" — ${newest.status}")
    }.trimEnd()

    /** The unresolved problems of the tasks that are NOT rendered in detail. */
    private fun openProblems(older: List<SessionTaskRecord>): List<String> = older
        .flatMap { record -> record.problems.map { "\"${record.instruction}\": $it" } }
        .takeLast(OPEN_PROBLEMS)
        .map { it.take(PROBLEM_CHARS) }

    /** One detailed bullet: the task, its outcome, and only the fields it has. */
    private fun renderRecord(record: SessionTaskRecord, digestOnly: Boolean): String = buildString {
        append("- \"").append(record.instruction).append("\" — ").append(record.status)
        record.elapsedMs?.takeIf { it >= 1_000 }?.let { append(" in ").append(formatElapsed(it)) }
        record.summary?.takeIf { it.isNotBlank() && it != record.instruction }?.let {
            append(" — ").append(it)
        }
        if (digestOnly) return@buildString
        if (record.findings.isNotEmpty()) {
            append("\n  findings: ").append(record.findings.joinToString("; "))
        }
        if (record.files.isNotEmpty()) {
            append("\n  files: ").append(record.files.joinToString(", "))
        }
        if (record.problems.isNotEmpty()) {
            append("\n  problems: ").append(record.problems.joinToString("; "))
        }
    }

    /** `"fix the build" (done), "extract prices" (failed), … and 3 more`. */
    private fun rollup(older: List<SessionTaskRecord>): String {
        // The newest of the folded band, because those are the tasks closest to the
        // current work. The founding task is not at risk here: it is anchored
        // verbatim as the session goal above.
        val titles = older.takeLast(ROLLUP_TITLES).joinToString(", ") {
            "\"${it.instruction}\" (${it.status})"
        }
        val hidden = older.size - older.takeLast(ROLLUP_TITLES).size
        val text = if (hidden > 0) "$titles, and $hidden more" else titles
        return text.take(ROLLUP_CHARS)
    }

    companion object {
        /** Process-wide switch: `browser4.agent.session.contextEnabled` (default true). */
        const val ENABLED_KEY = "browser4.agent.session.contextEnabled"

        /** Block budget in estimated tokens: `browser4.agent.session.contextTokens`. */
        const val TOKENS_KEY = "browser4.agent.session.contextTokens"

        /** Default block budget: ~4.8 KB of prompt, small next to a page snapshot. */
        const val DEFAULT_CONTEXT_TOKENS = 1_200

        /** The conventional chars-per-token estimate used everywhere in this engine. */
        const val CHARS_PER_TOKEN = 4

        /** How many of the newest tasks keep their details by default. */
        const val DEFAULT_DETAIL_ENTRIES = 3

        /** Hard bound on the remembered tasks, whatever the rendering keeps. */
        const val MAX_RECORDS = 12

        /** Titles named by the compacted line before it says "and N more". */
        const val ROLLUP_TITLES = 3

        /** Bound on the compacted line itself. */
        const val ROLLUP_CHARS = 400

        /**
         * Smallest budget the block will honour (≈150 tokens).
         *
         * A goal plus one task is already ~500 characters; a smaller cap could only be
         * met by dropping the goal or the newest task, which is exactly the failure the
         * focus guarantees forbid. The floor keeps the promise honest instead of
         * silently degrading into a block that no longer says anything.
         */
        const val MIN_BUDGET_CHARS = 600
        /** How many folded-out problems are re-listed in their own section. */
        const val OPEN_PROBLEMS = 3

        /** How many user corrections the block carries (newest kept). */
        const val CORRECTION_ENTRIES = 3

        /** Bound on one correction line. */
        internal const val CORRECTION_CHARS = 160

        /** Bound on one re-listed problem line. */
        internal const val PROBLEM_CHARS = 120

        internal const val INSTRUCTION_CHARS = 120
        internal const val SUMMARY_CHARS = 240
        internal const val FINDING_CHARS = 100

        internal const val MAX_FINDINGS = 3
        internal const val MAX_FILES = 5
        internal const val MAX_PROBLEMS = 3

        internal const val FILE_CHARS = 80

        /**
         * The conversation a session should keep, or `null` when the feature is
         * switched off (`browser4.agent.session.contextEnabled=false`).
         */
        fun fromConf(conf: ImmutableConfig): SessionConversation? = fromSettings(
            enabled = conf.getBoolean(ENABLED_KEY, true),
            tokens = conf.get(TOKENS_KEY)?.trim()?.toIntOrNull(),
        )

        /**
         * [fromConf] as a pure function of the two settings, so the switch and the
         * budget can be unit-tested without an [ImmutableConfig].
         *
         * A non-positive budget disables the injection instead of shipping a broken
         * header into every prompt.
         */
        fun fromSettings(enabled: Boolean, tokens: Int?): SessionConversation? {
            if (!enabled) return null
            val resolved = tokens ?: DEFAULT_CONTEXT_TOKENS
            if (resolved <= 0) return null
            return SessionConversation(budgetChars = resolved * CHARS_PER_TOKEN)
        }
    }
}

/**
 * The instruction without the `## Session context` block this session appended.
 *
 * The block is appended after the task text (and after a resume block), so it is
 * always a suffix: cutting at its heading recovers what the user actually asked
 * for. Storing the block as the submitted task would nest blocks on a later resume
 * and misreport the task in `agent status`.
 */
internal fun stripSessionBlock(instruction: String): String {
    val index = instruction.indexOf("\n$SESSION_BLOCK_MARKER")
    if (index >= 0) return instruction.substring(0, index).trimEnd()
    return if (instruction.startsWith(SESSION_BLOCK_MARKER)) "" else instruction
}

/**
 * Strip the session block from every recorded instruction of [history].
 *
 * The prompt that RAN carried the block; the record must not. It is a per-run
 * prelude of up to a few KB, and left in place it multiplies into every published
 * history snapshot — `command_result.agentHistory`, the JSONL task rows and the
 * next run's rendered Execution History — while `status.submittedTask` already
 * holds the real task text.
 */
internal fun stripSessionBlockFromHistory(history: AgentHistory?) {
    history?.states?.forEach { state ->
        if (state.instruction.contains(SESSION_BLOCK_MARKER)) {
            state.instruction = stripSessionBlock(state.instruction)
        }
    }
}

/** How a terminal status is remembered in the block. */
private fun statusWord(status: AgentTaskStatus): String = when {
    status.cancelled -> "cancelled"
    status.statusCode == ResourceStatus.SC_OK -> "done"
    else -> "failed"
}

/** `2m 13s` / `45s` — short, because it rides in a bullet. */
internal fun formatElapsed(elapsedMs: Long): String {
    val totalSeconds = (elapsedMs / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return if (minutes > 0) "${minutes}m ${seconds}s" else "${seconds}s"
}

/** One line, whitespace collapsed. */
private fun String.singleLine(): String = split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")

/** At most [max] entries, each shortened to [chars], blanks dropped. */
private fun bounded(values: List<String>?, max: Int, chars: Int): List<String> = values.orEmpty()
    .asSequence()
    .map { it.singleLine() }
    .filter { it.isNotBlank() }
    .map { if (it.length > chars) it.take(chars) + "…" else it }
    .take(max)
    .toList()
