package ai.platon.pulsar.agentic.inference

import ai.platon.pulsar.agentic.agents.BasicBrowserAgent
import ai.platon.pulsar.agentic.inference.action.ContextToAction
import ai.platon.pulsar.agentic.model.ActionDescription
import ai.platon.pulsar.agentic.model.ExecutionContext
import ai.platon.browser4.api.AbstractWebDriver
import ai.platon.browser4.api.snapshot.SnapshotService
import ai.platon.pulsar.common.AppPaths
import ai.platon.pulsar.common.DateTimes
import ai.platon.pulsar.common.serialize.json.pulsarObjectMapper
import ai.platon.pulsar.external.ModelResponse
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode
import java.nio.file.Path
import java.time.Instant

class InferenceEngine(
    private val agent: BasicBrowserAgent
) {
    private val session = agent.session
    private val cta = ContextToAction(session.sessionConfig)
    private val inferenceLogger = InferenceLogger(agent.uuid, agent.startTime)

    val snapshotService: SnapshotService
        get() = (session.getOrCreateBoundDriver() as? AbstractWebDriver)?.snapshotService
            ?: throw IllegalStateException("Bound driver is not AbstractWebDriver")

    suspend fun observe(params: ObserveParams, context: ExecutionContext): ActionDescription {
        val messages = InferencePromptBuilder.buildObserveMessages(params)

        val startTime = Instant.now()
        val actionType = if (params.fromAct) "act" else "observe"
        val timestamp = AppPaths.fromNow()

        val llmInputFile = inferenceLogger.log(
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

        val llmOutputFile = inferenceLogger.log(
            subdirectory = actionType,
            filename = "$timestamp.response.json",
            payload = mapOf(
                "requestId" to context.uuid,
                "modelResponse" to modelResponse
            )
        )

        inferenceLogger.logSummary(
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
        InferenceEventEmitter.onWillExtract(params)

        val messages = InferencePromptBuilder.buildExtractionPrompt(params)

        // 1) Extraction call -----------------------------------------------------------------
        val timestamp = AppPaths.fromNow()
        val filename = "extract.jsonl"
        val llmInputFile: Path? = inferenceLogger.log(
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

        val extractOutputFile: Path = inferenceLogger.log(
            subdirectory = "extract",
            filename = filename,
            payload = mapOf(
                "requestId" to params.requestId,
                "response" to extractedNode
            )
        )

        inferenceLogger.logSummary(
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

        inferenceLogger.logSummary(
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
        InferenceEventEmitter.onDidExtract(params, inferenceResult)

        return result
    }

    suspend fun summarize(instruction: String?, textContent: String): String {
        val messages = InferencePromptBuilder.buildSummaryPrompt(instruction, textContent)

        val startTime = Instant.now()

        InferenceEventEmitter.onWillSummarize(instruction, messages, textContent)

        val response = cta.generateResponseRaw(messages)

        val inferenceTimeMillis = DateTimes.elapsedTime(startTime).toMillis()

        InferenceEventEmitter.onDidSummarize(instruction, textContent, response, inferenceTimeMillis)

        // TODO: count token usage

        return response.content
    }
}
