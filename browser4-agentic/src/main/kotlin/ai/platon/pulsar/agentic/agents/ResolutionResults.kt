package ai.platon.pulsar.agentic.agents

import ai.platon.pulsar.agentic.ActResult
import ai.platon.pulsar.agentic.model.ExecutionContext
import ai.platon.pulsar.external.ModelResponse

/**
 * Result of a full resolution attempt, pairing the final execution context with the act result.
 */
data class ResolveResult(
    val context: ExecutionContext,
    val result: ActResult
)

/**
 * Why the agent loop terminated. Used to decide whether the final result is a success
 * or must be reported as a failure (a loop that exits without task completion is a failure,
 * even if the LLM's final summary reads optimistically).
 */
enum class StopReason {
    /** The agent decided the task is complete (actResult.isComplete). */
    COMPLETED,

    /** The consecutive no-op limit was reached while the task was NOT complete. */
    NOOP_LIMIT,

    /** The loop exited because maxSteps was reached without completion. */
    MAX_STEPS,
}

/**
 * Result of generating a summary via LLM.
 *
 * @property context The execution context at time of summarization
 * @property modelResponse The raw model response containing the summary text
 */
data class SummarizeResult(
    val context: ExecutionContext,
    val modelResponse: ModelResponse
)
