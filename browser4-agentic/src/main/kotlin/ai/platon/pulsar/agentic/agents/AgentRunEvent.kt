package ai.platon.pulsar.agentic.agents

/**
 * One observable step of an agent run, reported to the task runner while the
 * run is still in flight.
 *
 * The status envelope (`processState` + a counter that is only bumped every few
 * tool calls) is far too coarse for a live view: the CLI engine can execute
 * dozens of tools inside one step, and `agent run --follow` / the interactive
 * REPL are supposed to show WHAT the agent is doing. [RobustBrowserAgent]
 * reports every step here; the task runner turns it into an
 * `AgentTaskEvent` in the task's bounded event log, which
 * `GET /api/commands/{id}/events` serves.
 *
 * The payload is **raw**: truncation and credential redaction happen on the way
 * into the log, not here — the agent must not depend on a presentation policy.
 *
 * @property type one of the `TYPE_*` constants
 * @property tool tool name for [TOOL_CALL] / [TOOL_RESULT]
 * @property arguments the tool's argument JSON, verbatim
 * @property ok tool outcome (`null` for non-tool events)
 * @property durationMs tool execution time
 * @property text model text / steering text / usage category (`compaction`)
 * @property turn 1-based outer agent round
 * @property inputTokens prompt tokens the model request processed, prompt-cache
 *   reads and writes included ([USAGE] only; `null` when the provider reported no
 *   usage).  Normalized across protocols on purpose: OpenAI-compatible providers
 *   already count cache hits inside `prompt_tokens`, Anthropic-style ones report
 *   them separately, and every consumer (budget, `/stats`, the cache-hit ratio)
 *   must see one meaning.
 * @property outputTokens completion tokens of the model request
 * @property totalTokens the request's accounted total: [inputTokens] + output
 *   tokens
 * @property cachedInputTokens prompt tokens served from the provider's prompt
 *   cache for this request ([USAGE] only; `null` — not `0` — when the provider
 *   reported no cache details)
 * @property cacheWriteInputTokens prompt tokens this request wrote into the
 *   provider's prompt cache (Anthropic-style APIs; `null` when unreported).  A
 *   write is billed above the base input price, which is why it is reported apart
 *   from the reads.
 * @property budgetTokens the run's effective token budget ([USAGE] only): `0` means
 *   unlimited, `null` when the run reports no budget
 * @property cumulativeTokens charged tokens consumed by this run so far, this
 *   request included ([USAGE] only) — cache-miss prompt tokens + output, the
 *   number the budget and the "how close to the abort are we" question use;
 *   prompt-cache reads are excluded (they cost ~10% of the base price)
 * @property callId the [dev.langchain4j.agent.tool.ToolExecutionRequest] id of one
 *   tool invocation, shared by its [TOOL_CALL] and [TOOL_RESULT]. The runner stores
 *   it on the event so a consumer can pair call and result exactly instead of by
 *   tool name (parallel calls of one tool are indistinguishable by name).
 * @property detail bounded structured payload of a decision event ([VERIFY_VERDICT],
 *   [GATE_EVALUATED], [REPAIR_ROUND], …). It is **raw** here, like the other fields:
 *   the runner previews, redacts and bounds it on the way into the log.
 * @property mutations cumulative file mutations of the run as of this step (gauge,
 *   [ROUND_START] only): the engine's own progress signal, exported so a supervisor
 *   can compute "N tool calls / 0 files changed" without reading the trace.
 * @property readOnlyRounds consecutive coding rounds without a mutation (gauge,
 *   [ROUND_START] only) — the number the "stop surveying, start editing" nudge fires on.
 * */
data class AgentRunEvent(
    val type: String,
    val tool: String? = null,
    val arguments: String? = null,
    val ok: Boolean? = null,
    val durationMs: Long? = null,
    val text: String? = null,
    val turn: Int? = null,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val totalTokens: Int? = null,
    /** Prompt tokens served from the provider cache ([USAGE] only; `null` = unreported). */
    val cachedInputTokens: Int? = null,
    /** Prompt tokens written to the provider cache ([USAGE] only; `null` = unreported). */
    val cacheWriteInputTokens: Int? = null,
    /** Effective token budget of this run ([USAGE] only; 0 = unlimited). */
    val budgetTokens: Long? = null,
    /** Tokens consumed by this run so far, this request included ([USAGE] only). */
    val cumulativeTokens: Long? = null,
    /** Id of the one tool invocation this step belongs to ([TOOL_CALL] / [TOOL_RESULT]). */
    val callId: String? = null,
    /** Structured payload of a decision event, bounded on the way into the task log. */
    val detail: Map<String, Any?>? = null,
    /** Cumulative file mutations of the run ([ROUND_START] only; gauge). */
    val mutations: Long? = null,
    /** Consecutive coding rounds without a mutation ([ROUND_START] only; gauge). */
    val readOnlyRounds: Int? = null,
) {
    companion object {
        /** A new outer round started; a model request is about to be made. */
        const val ROUND_START = "round.start"

        /** The model requested a tool, before it executes. */
        const val TOOL_CALL = "tool.call"

        /** A tool finished, with its outcome and duration. */
        const val TOOL_RESULT = "tool.result"

        /** The model answered with plain text instead of a tool call. */
        const val MODEL_TEXT = "model.text"

        /** A user steering message was injected into the run. */
        const val STEER = "steer"

        /**
         * A queued follow-up message was delivered at the stop point.
         *
         * The model had answered without tool calls; the follow-up is appended as a real
         * user message and the loop continues the same conversation (pi's `followUp`).
         */
        const val FOLLOW_UP = "follow_up"

        /**
         * One successful model request, with the usage the provider reported.
         *
         * The task runner counts these as the run's LLM calls and sums their
         * tokens (see `AgentTaskRunStats`): reporting usage per request keeps the
         * accounting honest across multi-request rounds and compaction calls,
         * where a single end-of-run total would have to be guessed.
         */
        const val USAGE = "run.usage"

        /** [text] category of a [USAGE] event raised by a compaction model call. */
        const val USAGE_CATEGORY_COMPACTION = "compaction"

        // ── Decision events (issue #652) ─────────────────────────────────────
        //
        // The engine's own course-correction signals. They were tracer/log-only until
        // 2026-11-22, so an external supervisor could watch a run but not judge it.
        // Each one carries a bounded `detail` payload; the type strings are mirrored
        // on `AgentTaskEvent.TYPE_*`, and an existing consumer ignores unknown types.

        /** One `coding.verify` result was parsed (`detail`: passed, module, checks[]). */
        const val VERIFY_VERDICT = "verify.verdict"

        /** One completion-report evaluation finished, pass or fail (`detail`: passed, strict, violations[], unlistedFiles[]). */
        const val GATE_EVALUATED = "gate.evaluated"

        /** A failing verification was fed back for another round (`detail`: round, maxRounds, reason). */
        const val REPAIR_ROUND = "repair.round"

        /** The repair budget is spent while verification still fails (`detail`: roundsUsed, verdict). */
        const val REPAIR_EXHAUSTED = "repair.exhausted"

        /** The completion report omitted written files; one repair round was asked for (`detail`: round, missing[]). */
        const val FINISH_REPAIR = "finish.repair"

        /** The report's `filesChanged` was completed from the mutation trace (`detail`: added[], source). */
        const val FINISH_AMEND = "finish.amend"

        /** The engine injected a correction (`detail`: kind=coding|budget, …). */
        const val NUDGE = "nudge"

        /** The isolation gate decided on the merge (`detail`: decision, changedFiles, reason). */
        const val ISOLATION_MERGE = "isolation.merge"

        /** `detail.kind` of a [NUDGE] raised by the coding progress rule. */
        const val NUDGE_CODING = "coding"

        /** `detail.kind` of a [NUDGE] raised by the token-budget thresholds. */
        const val NUDGE_BUDGET = "budget"
    }
}
