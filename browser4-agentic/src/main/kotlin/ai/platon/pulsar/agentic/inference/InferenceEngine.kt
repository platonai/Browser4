package ai.platon.pulsar.agentic.inference

import ai.platon.pulsar.agentic.agents.BasicBrowserAgent
import ai.platon.pulsar.agentic.inference.action.ContextToAction
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
    // Reuse the agent's ContextToAction so vision-support discovery is shared
    private val cta = agent.cta
    private val inferenceLogger = InferenceLogger(agent.uuid, agent.startTime)

    /**
     * Run the two-stage structured extraction pipeline and return an
     * [ExtractInferenceResult] whose [ExtractInferenceResult.result] is the
     * CLEAN schema payload — the extraction fields exactly as the model
     * returned them, with no task bookkeeping merged in.
     *
     * Token counts, timing and the evaluation metadata (progress/completed)
     * are carried separately by [ExtractInferenceResult] and surfaced through
     * inference events/logs; they never leak into the payload the caller
     * asked the model to produce.
     *
     * The [ExtractInferenceResult.completed] flag is truthful: it is `true`
     * whenever usable content is present in the payload, even if the advisory
     * evaluation call reported `completed: false` (a successful extraction
     * must never look incomplete).
     */
    suspend fun extract(params: ExtractParams): ExtractInferenceResult {
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

        // The model is asked for the schema fields ONLY, but live responses can
        // echo the engine's own bookkeeping back into the payload (the summary
        // keys inputToken/outputToken/totalToken/inferenceTimeMillis and an
        // evaluator-shaped metadata{progress,completed} object).  Strip those
        // deterministically here — the ExtractResultEnvelopeTest contract says
        // data must contain exactly the requested schema fields, so payload
        // contamination must never reach callers.
        val extractedNode: ObjectNode = runCatching {
            pulsarObjectMapper().readTree(extractResponse.content) as? ObjectNode
                ?: JsonNodeFactory.instance.objectNode()
        }.getOrElse { JsonNodeFactory.instance.objectNode() }
        stripInferenceBookkeeping(extractedNode)

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
                "inputToken" to extractResponse.tokenUsage.effectiveInputTokenCount,
                "outputToken" to extractResponse.tokenUsage.outputTokenCount,
                "totalToken" to extractResponse.tokenUsage.effectiveTotalTokenCount,
                "cachedInputToken" to extractResponse.tokenUsage.cachedInputTokenCount,
                "cacheWriteInputToken" to extractResponse.tokenUsage.cacheWriteInputTokenCount,
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
        val metaCompleted = metaNode.path("completed").asBoolean(false)

        // The completion flag must reflect reality: once usable content is
        // present the extraction counts as complete, even if the advisory
        // metadata call reported "completed": false (it may signal that more
        // viewports remain).  Only when no content was produced do we fall
        // back to the evaluator model's judgment.
        val contentPresent = extractedNode.size() > 0
        val completed = metaCompleted || contentPresent

        inferenceLogger.logSummary(
            filename = "extract.jsonl",
            payload = mapOf(
                "extractInferenceType" to "metadata",
                "timestamp" to timestamp,
                "inputToken" to metadataResponse.tokenUsage.effectiveInputTokenCount,
                "outputToken" to metadataResponse.tokenUsage.outputTokenCount,
                "totalToken" to metadataResponse.tokenUsage.effectiveTotalTokenCount,
                "cachedInputToken" to metadataResponse.tokenUsage.cachedInputTokenCount,
                "cacheWriteInputToken" to metadataResponse.tokenUsage.cacheWriteInputTokenCount,
                "inferenceTimeMillis" to DateTimes.elapsedTime(metadataStartTime).toMillis()
            )
        )

        val usage1 = extractResponse.tokenUsage
        val usage2 = metadataResponse.tokenUsage
        // Both calls may report cache details; normalize their sum so the prompt
        // count is cache-inclusive exactly once (see TokenUsage.normalized).
        val summedUsage = (usage1 + usage2).normalized()
        val inputTokenCount = summedUsage.inputTokenCount
        val outputTokenCount = summedUsage.outputTokenCount
        val totalTokenCount = summedUsage.totalTokenCount

        val totalInferenceTimeMillis = DateTimes.elapsedTime(extractStartTime).toMillis()

        // The user payload is the clean extraction result ONLY.  Bookkeeping
        // (metadata{progress,completed} and the token/time metrics) must not
        // be merged into it — it pollutes schema-constrained output and makes
        // the completion flag indistinguishable from a user field.
        val result: ObjectNode = extractedNode.deepCopy()

        val inferenceResult = ExtractInferenceResult(
            result = result,
            extractedNode = extractedNode,
            metaNode = metaNode,
            completed = completed,
            progress = progress,
            totalInferenceTimeMillis = totalInferenceTimeMillis,
            inputTokenCount = inputTokenCount,
            outputTokenCount = outputTokenCount,
            totalTokenCount = totalTokenCount,
            cachedInputTokenCount = summedUsage.cachedInputTokenCount,
            cacheWriteInputTokenCount = summedUsage.cacheWriteInputTokenCount,
        )
        InferenceEventEmitter.onDidExtract(params, inferenceResult)

        return inferenceResult
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

    companion object {
        /**
         * Deterministically remove engine bookkeeping that the model echoed into
         * the extraction payload.  The extract prompt asks for schema fields only,
         * but live model responses sometimes carry the engine's own summary keys
         * back (token/timing counters, and an evaluator-shaped metadata object
         * with progress/completed).  Per the ExtractResultEnvelopeTest contract,
         * data must contain exactly the requested schema fields, so these keys are
         * stripped recursively — never merged into the user payload, never exposed
         * as if they were extracted fields.
         */
        internal fun stripInferenceBookkeeping(node: ObjectNode) {
            // Summary keys the engine logs (never schema fields). The cache
            // breakdown belongs here too: a model that echoes the bookkeeping back
            // must not inject cache counters into the user payload.
            for (key in listOf(
                "inputToken", "outputToken", "totalToken",
                "cachedInputToken", "cacheWriteInputToken", "inferenceTimeMillis",
            )) {
                node.remove(key)
            }
            // Evaluator-shaped metadata echo: an object whose keys are progress /
            // completed (or a metadata prefix).  Leave a legitimate user field
            // named "metadata" (e.g. product metadata) alone unless it has this
            // bookkeeping shape.
            val metadata = node.get("metadata")
            if (metadata is ObjectNode) {
                val keys = metadata.fieldNames().asSequence().toSet()
                if ("progress" in keys || "completed" in keys) {
                    node.remove("metadata")
                }
            }
            // Recurse into remaining containers.
            val fields = node.fields().asSequence().map { it.value }.toList()
            for (child in fields) {
                when (child) {
                    is ObjectNode -> stripInferenceBookkeeping(child)
                    is com.fasterxml.jackson.databind.node.ArrayNode ->
                        child.forEach { if (it is ObjectNode) stripInferenceBookkeeping(it) }
                    else -> {}
                }
            }
        }
    }
}
