package ai.platon.pulsar.agentic.agents

import ai.platon.pulsar.agentic.common.SecretRedactor
import java.time.Instant

/**
 * Tunables of goal mode (`docs-dev/copilot/agent-goal-mode.md`).
 *
 * Goal mode is on by default and has no opt-in: every `agent run` task is a goal
 * task, because the alternative — a long run whose original instruction has been
 * compacted away — is the failure this feature exists to prevent. `off` exists
 * for a deployment that needs the 2026-11-22 behaviour bit-for-bit.
 */
object GoalConfig {

    const val MODE_PROP = "browser4.agent.goal.mode"

    /** Pin the goal every round and gate the plan at finish (the default). */
    const val MODE_PINNED = "pinned"

    /** No pinning, no plan gate: the pre-goal-mode behaviour. */
    const val MODE_OFF = "off"

    /** The goal came from the instruction the task was submitted with. */
    const val SOURCE_INSTRUCTION = "instruction"

    /** The operator replaced the goal while the task was running (`/goal`, `command.goal`). */
    const val SOURCE_OPERATOR = "operator"

    /** Goal text kept verbatim; longer instructions are truncated with a marker. */
    const val DEFAULT_MAX_CHARS = 800

    /** Plan steps kept; a model that writes 500 steps must not blow up the context. */
    const val DEFAULT_MAX_STEPS = 12

    /** One step's text, bounded. */
    const val MAX_STEP_CHARS = 160

    /** Upper bound of the pinned block appended to every request. */
    const val DEFAULT_PINNED_MAX_CHARS = 1_500

    /** Rounds between two `goal.reassert` reminders; `0` disables them. */
    const val DEFAULT_REASSERT_EVERY_ROUNDS = 6

    /** Done-when entries kept. */
    const val MAX_DONE_WHEN = 6

    /** Marker for a truncated goal text. */
    const val TRUNCATED = " …(truncated)"

    fun mode(): String = System.getProperty(MODE_PROP, MODE_PINNED).trim().lowercase()

    val enabled: Boolean get() = mode() != MODE_OFF

    fun maxChars(): Int = intProp("browser4.agent.goal.maxChars", DEFAULT_MAX_CHARS, 80, 8_000)

    fun maxSteps(): Int = intProp("browser4.agent.goal.maxSteps", DEFAULT_MAX_STEPS, 1, 64)

    fun pinnedMaxChars(): Int =
        intProp("browser4.agent.goal.pinnedMaxChars", DEFAULT_PINNED_MAX_CHARS, 200, 20_000)

    fun reassertEveryRounds(): Int =
        intProp("browser4.agent.goal.reassertEveryRounds", DEFAULT_REASSERT_EVERY_ROUNDS, 0, 1_000)

    /** Clamp a JVM property so a typo cannot make the pinned block unbounded. */
    private fun intProp(key: String, default: Int, min: Int, max: Int): Int =
        runCatching { System.getProperty(key)?.trim()?.toInt() ?: default }
            .getOrDefault(default)
            .coerceIn(min, max)
}

/**
 * Where one plan step stands.
 *
 * [DROPPED] with a [GoalStep.reason] is the honest way out of a step the run
 * decided not to do; without a reason it counts as unfinished
 * ([GoalStep.isOpen]), because "dropped" alone is indistinguishable from
 * forgetting.
 */
enum class GoalStepStatus(val wire: String) {
    PENDING("pending"),
    DONE("done"),
    DROPPED("dropped"),
    ;

    companion object {
        fun parse(raw: String?): GoalStepStatus? =
            raw?.trim()?.lowercase()?.let { value -> entries.firstOrNull { it.wire == value } }
    }
}

/** One step of the plan the model committed to. */
data class GoalStep(
    val text: String,
    val status: GoalStepStatus = GoalStepStatus.PENDING,
    val reason: String? = null,
) {
    /** Pending, or dropped without a reason: the run still owes work or an explanation. */
    val isOpen: Boolean
        get() = status == GoalStepStatus.PENDING ||
            (status == GoalStepStatus.DROPPED && reason.isNullOrBlank())

    internal val marker: String
        get() = when (status) {
            GoalStepStatus.PENDING -> "▢"
            GoalStepStatus.DONE -> "✔"
            GoalStepStatus.DROPPED -> "✖"
        }
}

/**
 * An operator's goal for one task, plus the plan the run committed to.
 *
 * ## Why it is engine state, not a message
 *
 * A long run is compacted, repaired, nudged and re-summarized: the first user
 * message (the task) does not survive to round 40. Keeping the goal in the
 * message history would therefore tie the run's memory of its own goal to the
 * callousness of the compressor. The goal lives on the agent, and
 * [pinnedBlock] re-renders it into **every** request — so no amount of
 * compression, repair rounds or elapsed time can drop it.
 *
 * ## Why the model cannot change it
 *
 * [text] only changes through [amended], which the engine calls for an operator
 * action (`/goal`, `command.goal`) — never from a tool call. The model owns
 * [steps] (via `goal.plan`): a plan may evolve, the goal may not. A model that
 * cannot meet the goal must report it (`system.taskComplete(problems=[…])`) or
 * drop the step with a reason, instead of quietly redefining success.
 *
 * @property text the operator's words, verbatim (bounded by [GoalConfig.maxChars])
 * @property steps the plan the run declared, in order
 * @property doneWhen the run's own acceptance criteria, rendered back every round
 * @property source [GoalConfig.SOURCE_INSTRUCTION] or [GoalConfig.SOURCE_OPERATOR]
 * @property revision incremented by [amended] only — one per goal change
 * @property planRevision incremented by [withPlan] only, so the engine can emit
 *   one `plan.update` per actual change instead of one per round
 * @property changedFrom the previous goal text when this one replaced it
 * @property changedReason why the goal changed (`operator: /goal`)
 */
data class AgentGoal(
    val text: String,
    val steps: List<GoalStep> = emptyList(),
    val doneWhen: List<String> = emptyList(),
    val source: String = GoalConfig.SOURCE_INSTRUCTION,
    val revision: Int = 1,
    val planRevision: Int = 0,
    val changedFrom: String? = null,
    val changedReason: String? = null,
    val updatedAt: Instant = Instant.now(),
) {

    val hasPlan: Boolean get() = steps.isNotEmpty()

    val doneSteps: List<GoalStep> get() = steps.filter { it.status == GoalStepStatus.DONE }

    val droppedSteps: List<GoalStep> get() = steps.filter { it.status == GoalStepStatus.DROPPED }

    /** Steps the run still owes an outcome for (pending, or dropped without a reason). */
    val openSteps: List<GoalStep> get() = steps.filter { it.isOpen }

    /** `2/5 done, 1 dropped` — the shape both the pinned block and `/goal` use. */
    fun progressLabel(): String {
        if (steps.isEmpty()) return "no plan yet"
        val parts = mutableListOf("${doneSteps.size}/${steps.size} done")
        if (droppedSteps.isNotEmpty()) parts += "${droppedSteps.size} dropped"
        if (openSteps.isNotEmpty()) parts += "${openSteps.size} pending"
        return parts.joinToString(", ")
    }

    /**
     * Replace the plan. Steps and `doneWhen` are bounded here — the tool layer
     * only validates shape, so the one place that guarantees "the pinned block
     * cannot grow without limit" is this function.
     */
    fun withPlan(steps: List<GoalStep>, doneWhen: List<String> = this.doneWhen): AgentGoal {
        val boundedSteps = steps.asSequence()
            .map { it.copy(text = it.text.trim().take(GoalConfig.MAX_STEP_CHARS)) }
            .filter { it.text.isNotEmpty() }
            .take(GoalConfig.maxSteps())
            .toList()
        val boundedDoneWhen = doneWhen.asSequence()
            .map { it.trim().take(GoalConfig.MAX_STEP_CHARS) }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(GoalConfig.MAX_DONE_WHEN)
            .toList()
        return copy(
            steps = boundedSteps,
            doneWhen = boundedDoneWhen,
            planRevision = planRevision + 1,
            updatedAt = Instant.now(),
        )
    }

    /**
     * Replace the goal text — the operator's channel only.
     *
     * The plan is kept: it is a record of what this run is doing, and an operator
     * who narrows the goal usually wants the work in flight to continue. What
     * changes is the target every later round is pinned to.
     */
    fun amended(newText: String, reason: String? = null, source: String = GoalConfig.SOURCE_OPERATOR): AgentGoal {
        val text = boundedText(newText)
        return copy(
            text = text,
            source = source,
            revision = revision + 1,
            changedFrom = this.text.take(200),
            changedReason = reason,
            updatedAt = Instant.now(),
        )
    }

    /**
     * The block appended to every request: the goal, the plan's progress and the
     * one rule that makes the goal binding.
     *
     * Order is deliberate — goal first (what to do beats what is known), then the
     * plan, then the rule. The block is bounded by [maxChars]: the tail is the
     * most expensive real estate in the request, and a plan that outgrew it would
     * push real work out of the context window.
     *
     * @param round the 1-based round this block belongs to
     * @param reassert also ask the model to restate the goal and name its step
     *   (long runs stop acting on a paragraph they have read 30 times)
     */
    fun pinnedBlock(
        round: Int,
        reassert: Boolean = false,
        maxChars: Int = GoalConfig.pinnedMaxChars(),
    ): String {
        val head = buildString {
            appendLine("## Goal (pinned by the engine — it does not change)")
            appendLine(text)
            appendLine()
            append("Round ").append(round).append(" · ").append(progressLabel())
            if (source == GoalConfig.SOURCE_OPERATOR) {
                append(" · replaced by the operator")
                changedReason?.takeIf { it.isNotBlank() }?.let { append(" (").append(it.take(120)).append(')') }
            }
        }
        val body = buildString {
            if (steps.isNotEmpty()) {
                appendLine()
                appendLine("Plan (update it with goal.plan):")
                steps.forEachIndexed { index, step ->
                    val reason = step.reason?.takeIf { it.isNotBlank() }?.let { " — $it" }.orEmpty()
                    appendLine("  ${step.marker} ${index + 1}. ${step.text}$reason")
                }
            }
            if (doneWhen.isNotEmpty()) {
                appendLine()
                appendLine("Done when:")
                doneWhen.forEach { appendLine("  - $it") }
            }
            if (reassert) {
                appendLine()
                appendLine(
                    "⏱ goal.check ($round rounds in): before your next tool call, restate this " +
                        "goal in one line, name the plan step you are on, and drop any work that " +
                        "does not serve them."
                )
            }
            appendLine()
            append(
                "Rule: every action must serve this goal. If it cannot be met, report that " +
                    "(problems=[...] in system.taskComplete) or mark the step dropped with a reason — " +
                    "never substitute an easier goal."
            )
        }
        val text = head + body
        if (text.length <= maxChars) return text
        // Truncate on a line boundary so the block never ends mid-sentence, and say so.
        val marker = "\n… (pinned goal block truncated; goal.plan and /goal carry the rest)"
        val budget = (maxChars - marker.length).coerceAtLeast(0)
        val cut = text.take(budget)
        val lastBreak = cut.lastIndexOf('\n')
        val kept = if (lastBreak > 0) cut.substring(0, lastBreak) else cut
        return kept + marker
    }

    /**
     * What the supervision surface serves (`CommandStatus.goal`, `/goal`).
     *
     * Redacted on the way out: the goal is an operator sentence and may carry a
     * credential ("log in with password …"). The model still sees the original —
     * it was given the instruction — but a status envelope, an event, a log line
     * and a CLI panel are reads, and reads are redacted like every other trace.
     */
    fun statusView(round: Int? = null): AgentGoalStatus = AgentGoalStatus(
        text = SecretRedactor.redactText(text),
        source = source,
        revision = revision,
        changedFrom = changedFrom?.let { SecretRedactor.redactText(it) },
        changedReason = changedReason,
        steps = steps.map { step ->
            step.copy(
                text = SecretRedactor.redactText(step.text),
                reason = step.reason?.let { SecretRedactor.redactText(it) },
            )
        },
        doneWhen = doneWhen.map { SecretRedactor.redactText(it) },
        pending = openSteps.size,
        done = doneSteps.size,
        dropped = droppedSteps.size,
        round = round,
        updatedAt = updatedAt,
    )

    companion object {
        /**
         * The goal of a one-shot task: the instruction itself, verbatim.
         *
         * No flag, no mode: `agent run "<task>"` *is* a goal task. Interactive
         * sessions start the same way; `/goal` is what lets the operator move the
         * target afterwards, and it is the only thing that can.
         */
        fun fromInstruction(instruction: String): AgentGoal = AgentGoal(text = boundedText(instruction))

        /** Trim, collapse runs of blank lines and bound the text. */
        internal fun boundedText(raw: String): String {
            val normalized = raw.trim()
            if (normalized.length <= GoalConfig.maxChars()) return normalized
            val keep = GoalConfig.maxChars() - GoalConfig.TRUNCATED.length
            return normalized.take(keep.coerceAtLeast(0)) + GoalConfig.TRUNCATED
        }
    }
}

/**
 * The supervised form of an [AgentGoal] (`AgentTaskStatus.goal` → `CommandStatus.goal`).
 *
 * A data class of plain fields so it crosses the module boundary (agent-tools and
 * the REST layer both serve it) without dragging the agent along, and so a
 * follower that read the status after a restart still sees what the run was for.
 */
data class AgentGoalStatus(
    val text: String,
    val source: String,
    val revision: Int,
    val changedFrom: String? = null,
    val changedReason: String? = null,
    val steps: List<GoalStep> = emptyList(),
    val doneWhen: List<String> = emptyList(),
    val pending: Int = 0,
    val done: Int = 0,
    val dropped: Int = 0,
    val round: Int? = null,
    val updatedAt: Instant? = null,
) {
    val hasPlan: Boolean get() = steps.isNotEmpty()

    /** True when something the run promised is still unfinished. */
    val hasOpenSteps: Boolean get() = pending > 0

    /** Texts of the steps the run still owes an outcome for (rule 9's evidence). */
    fun openStepTexts(): List<String> = steps.filter { it.isOpen }.map { it.text }

    /**
     * One line for a panel: `<goal> · plan 2/5` plus the drift hint when the
     * operator moved the target.
     */
    fun summary(maxChars: Int = 120): String {
        val oneLine = text.split('\n').joinToString(" ").trim()
        val short = if (oneLine.length <= maxChars) oneLine else oneLine.take(maxChars - 1) + "…"
        val plan = if (steps.isEmpty()) "no plan yet" else "$done/${steps.size} done" +
            (if (dropped > 0) ", $dropped dropped" else "") +
            (if (pending > 0) ", $pending pending" else "")
        val drift = if (revision > 1) " · goal changed r$revision" else ""
        return "$short · plan $plan$drift"
    }
}

/**
 * The engine's live goal state for one run, shared by the agent and the tool
 * executor ([GoalToolExecutor]'s receiver holds this same object).
 *
 * A mutable box rather than a plain `var` on the agent: `goal.plan` runs on a
 * tool-dispatch thread and must be able to replace the goal without reaching
 * back into the agent, and the round loop must see the new plan on its next
 * iteration.
 */
class GoalState(initial: AgentGoal) {

    @Volatile
    var goal: AgentGoal = initial

    /** Bumped by every accepted `goal.plan`, so the loop emits one event per change. */
    @Volatile
    var acceptedPlans: Int = 0

    /** Last error a `goal.plan` call produced, surfaced in the tool result. */
    @Volatile
    var lastError: String? = null

    val text: String get() = goal.text

    /**
     * Apply a `goal.plan` call.
     *
     * Blank step text is refused loudly instead of silently dropped: a model that
     * sends `steps=[]` means "no plan", which is what clearing the plan does —
     * an all-blank list would otherwise look like a successful update that
     * erased the plan.
     */
    fun applyPlan(steps: List<GoalStep>, doneWhen: List<String>?): AgentGoal {
        val usable = steps.filter { it.text.isNotBlank() }
        require(usable.isNotEmpty() || steps.isEmpty()) {
            "goal.plan: every step needs non-blank text"
        }
        goal = goal.withPlan(usable, doneWhen ?: goal.doneWhen)
        acceptedPlans++
        lastError = null
        return goal
    }

    /** Apply an operator goal change (the only path that may replace the goal). */
    fun amend(text: String, reason: String?): AgentGoal {
        goal = goal.amended(text, reason)
        return goal
    }
}

/**
 * One operator goal change waiting to be picked up by the run.
 *
 * Delivered like steering but kept a distinct type: steering is a message the
 * model reads, a goal amendment is engine state the model is *told about*.
 */
data class PendingGoalAmendment(
    val id: String,
    val text: String,
    val reason: String? = null,
)
