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
 * @property text model text / steering text
 * @property turn 1-based outer agent round
 * */
data class AgentRunEvent(
    val type: String,
    val tool: String? = null,
    val arguments: String? = null,
    val ok: Boolean? = null,
    val durationMs: Long? = null,
    val text: String? = null,
    val turn: Int? = null,
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
    }
}
