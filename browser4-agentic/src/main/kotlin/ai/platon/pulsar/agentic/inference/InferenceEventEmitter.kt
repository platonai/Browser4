package ai.platon.pulsar.agentic.inference

import ai.platon.pulsar.agentic.event.AgentEventBus
import ai.platon.pulsar.agentic.event.AgenticEvents
import ai.platon.pulsar.common.event.EventBus
import ai.platon.pulsar.external.ModelResponse

/**
 * Emits lifecycle events for inference operations via both [AgentEventBus]
 * (for SSE streaming to external clients) and [EventBus] (for local in-process listeners).
 *
 * Stateless singleton — all event buses are accessed via static/companion methods.
 */
object InferenceEventEmitter {

    fun onWillExtract(params: ExtractParams) {
        AgentEventBus.emitInferenceEvent(
            eventType = AgenticEvents.InferenceEngine.ON_WILL_EXTRACT,
            agentId = params.requestId,
            message = "Starting extraction inference",
            metadata = mapOf(
                "instruction" to params.instruction.take(100),
                "requestId" to params.requestId
            )
        )

        EventBus.emit(
            AgenticEvents.InferenceEngine.ON_WILL_EXTRACT, mapOf(
                "params" to params
            )
        )
    }

    fun onDidExtract(params: ExtractParams, inferenceResult: ExtractInferenceResult) {
        AgentEventBus.emitInferenceEvent(
            eventType = AgenticEvents.InferenceEngine.ON_DID_EXTRACT,
            agentId = params.requestId,
            message = "Extraction inference completed",
            metadata = mapOf(
                "requestId" to params.requestId,
                "completed" to inferenceResult.completed,
                "progress" to inferenceResult.progress,
                "duration" to inferenceResult.totalInferenceTimeMillis,
                "inputToken" to inferenceResult.inputTokenCount,
                "outputToken" to inferenceResult.outputTokenCount,
                "totalToken" to inferenceResult.totalTokenCount
            )
        )

        EventBus.emit(
            AgenticEvents.InferenceEngine.ON_DID_EXTRACT, mapOf(
                "params" to params,
                "result" to inferenceResult.result,
                "extractedNode" to inferenceResult.extractedNode,
                "metaNode" to inferenceResult.metaNode
            )
        )
    }

    fun onWillSummarize(instruction: String?, messages: AgentMessageList, textContent: String) {
        AgentEventBus.emitInferenceEvent(
            eventType = AgenticEvents.InferenceEngine.ON_WILL_SUMMARIZE,
            agentId = null,
            message = "Starting summarization inference",
            metadata = mapOf(
                "instruction" to instruction,
                "textContentLength" to textContent.length
            )
        )

        EventBus.emit(
            AgenticEvents.InferenceEngine.ON_WILL_SUMMARIZE, mapOf(
                "instruction" to instruction,
                "messages" to messages,
                "textContent" to textContent,
            )
        )
    }

    fun onDidSummarize(
        instruction: String?,
        textContent: String,
        response: ModelResponse,
        inferenceTimeMillis: Long
    ) {
        AgentEventBus.emitInferenceEvent(
            eventType = AgenticEvents.InferenceEngine.ON_DID_SUMMARIZE,
            agentId = null,
            message = "Summarization inference completed",
            metadata = mapOf(
                "instruction" to instruction,
                "resultLength" to response.content.length,
                "duration" to inferenceTimeMillis,
                "inputToken" to response.tokenUsage.inputTokenCount,
                "outputToken" to response.tokenUsage.outputTokenCount,
                "totalToken" to response.tokenUsage.totalTokenCount
            )
        )

        EventBus.emit(
            AgenticEvents.InferenceEngine.ON_DID_SUMMARIZE, mapOf(
                "instruction" to instruction,
                "textContentLength" to textContent.length,
                "result" to response.content,
                "tokenUsage" to response.tokenUsage
            )
        )
    }
}
