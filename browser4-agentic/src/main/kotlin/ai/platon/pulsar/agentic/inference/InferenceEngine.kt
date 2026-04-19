package ai.platon.pulsar.agentic.inference

import ai.platon.browser4.driver.chrome.dom.SnapshotService
import ai.platon.pulsar.agentic.agents.BasicBrowserAgent
import ai.platon.pulsar.agentic.event.AgentEventBus
import ai.platon.pulsar.agentic.event.AgenticEvents
import ai.platon.pulsar.agentic.inference.action.ContextToAction
import ai.platon.pulsar.agentic.model.ActionDescription
import ai.platon.pulsar.agentic.model.AgentState
import ai.platon.pulsar.agentic.model.ExecutionContext
import ai.platon.pulsar.agentic.model.ExtractionSchema
import ai.platon.pulsar.common.AppPaths
import ai.platon.pulsar.common.DateTimes
import ai.platon.pulsar.common.MultiSinkMessageWriter
import ai.platon.pulsar.common.event.EventBus
import ai.platon.pulsar.common.serialize.json.pulsarObjectMapper
import ai.platon.pulsar.external.ModelResponse
import ai.platon.pulsar.skeleton.crawl.fetch.driver.AbstractWebDriver
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode
import java.nio.file.Path
import java.time.Instant
import java.util.*

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
 */
private data class ExtractInferenceResult(
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

class InferenceEngine(
    private val agent: BasicBrowserAgent
) {
    private val session = agent.session
    private val cta = ContextToAction(session.sessionConfig)
    private val auxRunLogDir: Path by lazy { getRunLogDir0() }
    private val auxLogger by lazy { MultiSinkMessageWriter(auxRunLogDir) }

    val snapshotService: SnapshotService
        get() = (session.getOrCreateBoundDriver() as? AbstractWebDriver)?.snapshotService
            ?: throw IllegalStateException("Bound driver is not AbstractWebDriver")

    suspend fun observe(params: ObserveParams, context: ExecutionContext): ActionDescription {
        val messages = InferencePromptBuilder.buildObserveMessages(params)

        val startTime = Instant.now()
        val actionType = if (params.fromAct) "act" else "observe"
        val timestamp = AppPaths.fromNow()

        val llmInputFile = log(
            subdirectory = actionType,
            filename = "$timestamp.request.json",
            requestId = context.uuid,
            messages = messages.messages
        )

        val actionDescription = cta.generate(messages, context)
        requireNotNull(context.agentState.actionDescription) {
            "Field should be set: context.agentState.actionDescription"
        }
        val modelResponse = requireNotNull(actionDescription.modelResponse) {
            "Field should be set: actionDescription.modelResponse"
        }

        val inferenceTimeMillis = DateTimes.elapsedTime(startTime).toMillis()

        val llmOutputFile = log(
            subdirectory = actionType,
            filename = "$timestamp.response.json",
            payload = mapOf(
                "requestId" to context.uuid,
                "modelResponse" to modelResponse
            )
        )

        logSummary(
            filename = "$actionType.jsonl",
            payload = mapOf(
                "${actionType}InferenceType" to actionType,
                "timestamp" to timestamp,
                "llmInputFile" to llmInputFile,
                "llmOutputFile" to llmOutputFile,
                "inputToken" to modelResponse.tokenUsage.inputTokenCount,
                "outputToken" to modelResponse.tokenUsage.outputTokenCount,
                "totalToken" to modelResponse.tokenUsage.totalTokenCount,
                "inferenceTimeMillis" to DateTimes.elapsedTime(startTime).toMillis()
            )
        )

        return actionDescription
    }

    /**
     * Returns an ObjectNode with extracted fields expanded at top-level, plus:
     *   - metadata: { progress, completed }
     *   - inputTokenCount, outputTokenCount, totalTokenCount, inferenceTimeMillis
     */
    suspend fun extract(params: ExtractParams): ObjectNode {
        onWillExtractInfer(params)

        val messages = InferencePromptBuilder.buildExtractionPrompt(params)

        // 1) Extraction call -----------------------------------------------------------------
        val timestamp = AppPaths.fromNow()
        val filename = "extract.jsonl"
        val llmInputFile: Path? = log(
            subdirectory = "extract",
            filename = filename,
            requestId = params.requestId,
            messages = messages.messages
        )

        val extractStartTime = Instant.now()
        val extractResponse: ModelResponse = cta.generateResponseRaw(messages)

        val extractedNode: ObjectNode = runCatching {
            pulsarObjectMapper().readTree(extractResponse.content) as? ObjectNode
                ?: JsonNodeFactory.instance.objectNode()
        }.getOrElse { JsonNodeFactory.instance.objectNode() }

        val extractOutputFile: Path = log(
            subdirectory = "extract",
            filename = filename,
            payload = mapOf(
                "requestId" to params.requestId,
                "response" to extractedNode
            )
        )

        logSummary(
            filename = filename,
            payload = mapOf(
                "extractInferenceType" to "extract",
                "timestamp" to timestamp,
                "llmInputFile" to llmInputFile,
                "llmOutputFile" to extractOutputFile,
                "inputToken" to extractResponse.tokenUsage.inputTokenCount,
                "outputToken" to extractResponse.tokenUsage.outputTokenCount,
                "totalToken" to extractResponse.tokenUsage.totalTokenCount,
                "inferenceTimeMillis" to DateTimes.elapsedTime(extractStartTime).toMillis()
            )
        )

        // 2) Metadata call -------------------------------------------------------------------
        val metadataMessages = InferencePromptBuilder.buildExtractionEvaluationPrompt(params, extractedNode)

        val metadataStartTime = Instant.now()
        val metadataResponse = cta.generateResponseRaw(metadataMessages)

        val metaNode: ObjectNode = runCatching {
            pulsarObjectMapper().readTree(metadataResponse.content) as? ObjectNode
                ?: JsonNodeFactory.instance.objectNode()
        }.getOrElse { JsonNodeFactory.instance.objectNode() }
        val progress = metaNode.path("progress").asText("")
        val completed = metaNode.path("completed").asBoolean(false)

        logSummary(
            filename = "extract.jsonl",
            payload = mapOf(
                "extractInferenceType" to "metadata",
                "timestamp" to timestamp,
                "inputToken" to metadataResponse.tokenUsage.inputTokenCount,
                "outputToken" to metadataResponse.tokenUsage.outputTokenCount,
                "totalToken" to metadataResponse.tokenUsage.totalTokenCount,
                "inferenceTimeMillis" to DateTimes.elapsedTime(metadataStartTime).toMillis()
            )
        )

        val usage1 = extractResponse.tokenUsage
        val usage2 = metadataResponse.tokenUsage
        val inputTokenCount = usage1.inputTokenCount + usage2.inputTokenCount
        val outputTokenCount = usage1.outputTokenCount + usage2.outputTokenCount
        val totalTokenCount = usage1.totalTokenCount + usage2.totalTokenCount

        val totalInferenceTimeMillis = DateTimes.elapsedTime(extractStartTime).toMillis()

        val result: ObjectNode = (extractedNode.deepCopy()).apply {
            set<ObjectNode>("metadata", JsonNodeFactory.instance.objectNode().apply {
                put("progress", progress)
                put("completed", completed)
            })
            put("inputToken", inputTokenCount)
            put("outputToken", outputTokenCount)
            put("totalToken", totalTokenCount)
            put("inferenceTimeMillis", totalInferenceTimeMillis)
        }

        val inferenceResult = ExtractInferenceResult(
            result = result,
            extractedNode = extractedNode,
            metaNode = metaNode,
            completed = completed,
            progress = progress,
            totalInferenceTimeMillis = totalInferenceTimeMillis,
            inputTokenCount = inputTokenCount,
            outputTokenCount = outputTokenCount,
            totalTokenCount = totalTokenCount
        )
        onDidExtractInfer(params, inferenceResult)

        return result
    }

    suspend fun summarize(instruction: String?, textContent: String): String {
        val messages = InferencePromptBuilder.buildSummaryPrompt(instruction, textContent)

        val startTime = Instant.now()

        onWillSummarizeInfer(instruction, messages, textContent)

        val response = cta.generateResponseRaw(messages)

        val inferenceTimeMillis = DateTimes.elapsedTime(startTime).toMillis()

        onDidSummarizeInfer(instruction, textContent, response, inferenceTimeMillis)

        // TODO: count token usage

        return response.content
    }

    private fun onWillExtractInfer(params: ExtractParams) {
        // Emit AgentEventBus inference event
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

    private fun onDidExtractInfer(params: ExtractParams, inferenceResult: ExtractInferenceResult) {
        // Emit AgentEventBus inference event
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

    private fun onWillSummarizeInfer(instruction: String?, messages: AgentMessageList, textContent: String) {
        // Emit AgentEventBus inference event
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

    private fun onDidSummarizeInfer(
        instruction: String?,
        textContent: String,
        response: ModelResponse,
        inferenceTimeMillis: Long
    ) {
        // Emit AgentEventBus inference event
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

    private fun logSummary(filename: String, payload: Map<String, Any?>): Path {
        val path = auxRunLogDir.resolve("summary").resolve(filename)
        return auxLogger.writeTo(payload, path)
    }

    private fun log(subdirectory: String, filename: String, payload: Map<String, Any?>): Path {
        val path = auxRunLogDir.resolve(subdirectory).resolve(filename)
        return auxLogger.writeTo(payload, path)
    }

    private fun log(
        subdirectory: String, requestId: String, filename: String,
        messages: List<Any>, enabled: Boolean = true
    ): Path? {
        if (!enabled) return null

        val payload = mapOf("requestId" to requestId, "messages" to messages)
        val path = auxRunLogDir.resolve(subdirectory).resolve(filename)
        return auxLogger.writeTo(payload, path)
    }

    private fun getRunLogDir0(): Path {
        val auxLogDir = AppPaths.detectAuxiliaryLogDir().resolve("agent")
        val agentId = agent.uuid.toString()
        return auxLogDir.resolve(AppPaths.fromTime(agent.startTime)).resolve(agentId)
    }
}
