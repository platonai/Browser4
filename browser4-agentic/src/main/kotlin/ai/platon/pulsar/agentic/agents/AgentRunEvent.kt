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
    }
}
