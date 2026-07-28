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
 * Result of processing a single step within the agent loop.
 *
 * @property context The updated execution context after step processing
 * @property consecutiveNoOps Running count of consecutive no-op steps
 * @property shouldStop Whether the agent loop should terminate after this step
 */
data class StepProcessingResult(
    val context: ExecutionContext,
    val consecutiveNoOps: Int,
    val shouldStop: Boolean
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
