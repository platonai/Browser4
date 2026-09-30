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
 * Result of processing a single step within the agent loop.
 *
 * @property context The updated execution context after step processing
 * @property consecutiveNoOps Running count of consecutive no-op steps
 * @property shouldStop Whether the agent loop should terminate after this step
 * @property stopReason Why the loop should stop; null when the loop continues
 */
data class StepProcessingResult(
    val context: ExecutionContext,
    val consecutiveNoOps: Int,
    val shouldStop: Boolean,
    val stopReason: StopReason? = null
)

/**
 * Result of [RobustBrowserAgent.prepareStep]: the built context, the no-op counter
 * (which may have been incremented when the page state froze), and whether the loop
 * must stop before executing another act.
 */
data class PrepareStepResult(
    val context: ExecutionContext,
    val noOps: Int,
    val stop: Boolean
)

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
