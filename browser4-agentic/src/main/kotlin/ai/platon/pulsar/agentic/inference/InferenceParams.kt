package ai.platon.pulsar.agentic.inference

import ai.platon.pulsar.agentic.model.AgentState
import ai.platon.pulsar.agentic.model.ExecutionContext
import ai.platon.pulsar.agentic.model.ExtractionSchema
import com.fasterxml.jackson.databind.node.ObjectNode
import java.util.UUID

data class ExtractParams(
    val instruction: String,
    val agentState: AgentState,
    val schema: ExtractionSchema,
    val requestId: String = UUID.randomUUID().toString(),
    val userProvidedInstructions: String? = null,
)

data class ObserveParams(
    val context: ExecutionContext,
    /**
     * User provided additional system instructions
     * */
    val userProvidedInstructions: String? = null,
    val returnAction: Boolean = false,
    val multistep: Boolean = false,
    val logInferenceToFile: Boolean = false,
    val fromAct: Boolean = false,
)

/**
 * Data class to encapsulate the results of an extraction inference operation.
 * Used internally for event handling to avoid passing too many parameters.
 *
 * @property result The CLEAN schema payload (extraction fields only; task
 * bookkeeping such as token counts / progress is never merged into it).
 * @property extractedNode The raw object node returned by the extraction call.
 * @property metaNode The raw evaluation-metadata object returned by the
 * evaluation call (progress/completed from the advisory evaluator model).
 * @property completed Whether the extraction task is complete — `true` whenever
 * usable content is present in [result], otherwise the evaluator's judgment.
 * @property progress Free-text progress description from the evaluator.
 */
data class ExtractInferenceResult(
    val result: ObjectNode,
    val extractedNode: ObjectNode,
    val metaNode: ObjectNode,
    val completed: Boolean,
    val progress: String,
    val totalInferenceTimeMillis: Long,
    val inputTokenCount: Int,
    val outputTokenCount: Int,
    val totalTokenCount: Int
)
