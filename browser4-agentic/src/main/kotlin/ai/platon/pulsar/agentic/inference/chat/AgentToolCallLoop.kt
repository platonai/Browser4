package ai.platon.pulsar.agentic.inference.chat

import ai.platon.pulsar.agentic.inference.RequestTokenLimiter
import ai.platon.pulsar.agentic.tools.langchain4j.ToolExecutionCoordinator
import ai.platon.pulsar.agentic.tools.langchain4j.ToolSpecificationConverter
import ai.platon.pulsar.common.getLogger
import ai.platon.pulsar.external.BrowserChatModel
import ai.platon.pulsar.external.ModelResponse
import ai.platon.pulsar.external.ResponseState
import dev.langchain4j.agent.tool.ToolExecutionRequest
import dev.langchain4j.agent.tool.ToolSpecification as LangChain4jToolSpec
import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.ChatMessage
import dev.langchain4j.data.message.ToolExecutionResultMessage
import dev.langchain4j.model.chat.request.ChatRequest
import dev.langchain4j.model.chat.response.ChatResponse

/**
 * Manages multi-turn tool-calling via LangChain4j's native function-calling
 * protocol.
 *
 * The loop:
 * 1. Sends [initialMessages] + [toolSpecifications] to the model.
 * 2. If the response contains [ToolExecutionRequest]s, executes each via
 *    [ToolExecutionCoordinator] and appends the
 *    [AiMessage] + [ToolExecutionResultMessage]s to the conversation.
 * 3. Repeats until the model returns a text-only response or
 *    [maxIterations] is reached.
 *
 * @param model             The [BrowserChatModel] (uses [langChainChat]).
 * @param toolSpecifications Native tool specs to expose to the LLM.
 * @param coordinator       Executes individual tool-call requests.
 * @param maxIterations     Hard cap on model↔tool round-trips (default 40).
 */
class AgentToolCallLoop(
    private val model: BrowserChatModel,
    private val toolSpecifications: List<LangChain4jToolSpec>,
    private val coordinator: ToolExecutionCoordinator,
    private val maxIterations: Int = 40,
    private val requestTokenLimiter: RequestTokenLimiter = RequestTokenLimiter(),
    private val compressor: ToolLoopCompressor? = null,
    private val onToolExecuted: () -> Unit = {},
    /** Called for every tool request before execution (name, arguments JSON). */
    private val onToolRequest: (String, String) -> Unit = { _, _ -> },
    /**
     * Called after every executed tool with its request, result message and
     * execution duration — feeds complete run tracing (name, arguments, full
     * result text).
     */
    private val onToolResult: (ToolExecutionRequest, ToolExecutionResultMessage, Long) -> Unit = { _, _, _ -> },
    /**
     * Called with the EXACT message list right before each model request —
     * feeds complete prompt logging (system prompt, history, tool results and
     * tool-call messages exactly as sent to the LLM).
     */
    private val onBeforeGenerate: (List<ChatMessage>) -> Unit = {},
) {
    private val logger = getLogger(AgentToolCallLoop::class)

    /**
     * Run the tool-calling loop and return a [ModelResponse] containing the
     * final assistant text (still in JSON ActionDescription format, so
     * downstream parsing is unchanged).
     */
    suspend fun generate(initialMessages: List<ChatMessage>): ModelResponse {
        val messages = initialMessages.toMutableList()
        var response: ChatResponse? = null
        // Accumulate real usage across all rounds — previously only the final
        // response's usage was reported, undercounting consumption by every
        // intermediate tool-calling round.
        var totalInput = 0
        var totalOutput = 0
        var totalTotal = 0
        val executedTools = mutableListOf<String>()

        for (iteration in 0 until maxIterations) {
            // Automatic context compression: prune over-budget tool results,
            // then compact the oldest rounds into a checkpoint when pressure
            // exceeds the threshold. Keeps the loop's O(N²) re-send cost
            // bounded without discarding the recent tail.
            //
            // Runs BEFORE the token-limit check: the limiter must validate the
            // post-compression context, otherwise a halting exception would
            // fire on a list the compressor could have fixed.
            if (compressor != null) {
                val pruned = compressor.pruneToolResults(messages)
                val compacted = compressor.compressIfNeeded(messages)
                if (pruned || compacted) {
                    logger.info("🧹 tool-loop compression applied (pruned={}, compacted={})", pruned, compacted)
                }
            }

            // Halt the task when the accumulated messages would exceed the
            // per-request token limit — tool results can grow the context
            // beyond the model's limit across multi-round loops.
            requestTokenLimiter.enforce(messages)

            // Complete-prompt hook: the message list at this point is EXACTLY
            // what the model will see on this round-trip (post-compression,
            // pre-request). Dumping it here preserves the full prompt even when
            // the round later overflows or the caller discards the list.
            onBeforeGenerate(messages)

            val request = ChatRequest.builder()
                .messages(messages)
                .toolSpecifications(toolSpecifications)
                .build()

            response = model.langChainChat(request, "cta")
            val aiMessage = response.aiMessage()
            messages.add(aiMessage)

            response.tokenUsage()?.let { u ->
                totalInput += u.inputTokenCount() ?: 0
                totalOutput += u.outputTokenCount() ?: 0
                totalTotal += u.totalTokenCount() ?: 0
            }

            val toolRequests = aiMessage.toolExecutionRequests()
            if (toolRequests.isNullOrEmpty()) {
                // No more tool calls — return the final response
                logger.debug("Tool loop finished after ${iteration + 1} round(s)")
                return chatResponseToModelResponse(response, totalInput, totalOutput, totalTotal)
            }

            logger.debug("Tool loop round ${iteration + 1}: executing ${toolRequests.size} tool(s)")

            // Execute each tool request and append results
            for (request in toolRequests) {
                onToolRequest(request.name(), request.arguments() ?: "{}")
                val executedAt = System.currentTimeMillis()
                val resultMessage: ToolExecutionResultMessage = coordinator.execute(request)
                onToolResult(request, resultMessage, System.currentTimeMillis() - executedAt)
                messages.add(resultMessage)
                executedTools += request.name()
                onToolExecuted()
            }
        }

        // Max iterations exhausted — return last response with error. The error
        // carries the executed-tool names PLUS a bounded digest of the newest
        // results, so the caller can persist real progress instead of discarding
        // a whole step's work (the results list itself dies with the loop).
        logger.warn("Tool loop exceeded max iterations ($maxIterations)")
        val executed = executedTools.distinct().joinToString(", ")
        return chatResponseToModelResponse(response!!, totalInput, totalOutput, totalTotal).let { mr ->
            mr.copy(modelError = OVERFLOW_ERROR_PREFIX + " ($maxIterations)" +
                (if (executed.isNotBlank()) "; executed: $executed" else "") +
                overflowProgressSummary(messages))
        }
    }

    /**
     * Bounded progress digest of the newest tool results for the overflow error:
     * up to [OVERFLOW_SUMMARY_RESULTS] results, first line only (<=200 chars each),
     * whole digest capped at [OVERFLOW_SUMMARY_MAX_CHARS].
     */
    private fun overflowProgressSummary(messages: List<ChatMessage>): String {
        val summaries = messages.filterIsInstance<ToolExecutionResultMessage>()
            .takeLast(OVERFLOW_SUMMARY_RESULTS)
            .map { m ->
                val firstLine = m.text().lineSequence().firstOrNull().orEmpty().trim().take(200)
                "${m.toolName()} -> $firstLine"
            }
        return if (summaries.isEmpty()) "" else "; results: " +
            summaries.joinToString(" | ").take(OVERFLOW_SUMMARY_MAX_CHARS)
    }

    private fun chatResponseToModelResponse(
        response: ChatResponse,
        summedInput: Int = 0,
        summedOutput: Int = 0,
        summedTotal: Int = 0,
    ): ModelResponse {
        val content = response.aiMessage().text() ?: ""
        // Prefer summed usage when non-zero (multi-round loop); fall back to the
        // single-response usage for the trivial single-round case.
        val usage = response.tokenUsage()
        val input = if (summedInput > 0) summedInput else (usage?.inputTokenCount() ?: 0)
        val output = if (summedOutput > 0) summedOutput else (usage?.outputTokenCount() ?: 0)
        val total = if (summedTotal > 0) summedTotal else (usage?.totalTokenCount() ?: 0)
        return ModelResponse(
            content = content,
            state = ResponseState.STOP,
            tokenUsage = ai.platon.pulsar.external.TokenUsage(
                inputTokenCount = input,
                outputTokenCount = output,
                totalTokenCount = total,
            ),
        )
    }

    companion object {
        /** Prefix of the modelError set when the loop exhausts [maxIterations]. */
        const val OVERFLOW_ERROR_PREFIX = "Tool call loop exceeded max iterations"

        /** How many newest tool results the overflow digest summarizes. */
        const val OVERFLOW_SUMMARY_RESULTS = 6

        /** Hard cap on the whole overflow result digest. */
        const val OVERFLOW_SUMMARY_MAX_CHARS = 2_000
    }
}

/**
 * Thrown when the tool-calling loop exhausts its iteration budget AND the
 * operator enabled `browser4.agent.toolLoop.failOnOverflow` — converts the
 * "success with no action" overflow step into an explicit step failure.
 */
class ToolLoopOverflowException(message: String) : IllegalStateException(message)
