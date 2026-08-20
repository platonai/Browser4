package ai.platon.pulsar.agentic.inference.chat

import ai.platon.pulsar.agentic.inference.RequestTokenLimiter
import ai.platon.pulsar.agentic.tools.langchain4j.ToolExecutionCoordinator
import ai.platon.pulsar.agentic.tools.langchain4j.ToolSpecificationConverter
import ai.platon.pulsar.common.getLogger
import ai.platon.pulsar.external.BrowserChatModel
import ai.platon.pulsar.external.ModelResponse
import ai.platon.pulsar.external.ResponseState
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
 * @param maxIterations     Hard cap on model↔tool round-trips (default 5).
 */
class AgentToolCallLoop(
    private val model: BrowserChatModel,
    private val toolSpecifications: List<LangChain4jToolSpec>,
    private val coordinator: ToolExecutionCoordinator,
    private val maxIterations: Int = 5,
    private val requestTokenLimiter: RequestTokenLimiter = RequestTokenLimiter(),
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
            // Halt the task when the accumulated messages would exceed the
            // per-request token limit — tool results can grow the context
            // beyond the model's limit across multi-round loops.
            requestTokenLimiter.enforce(messages)

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
                val resultMessage: ToolExecutionResultMessage = coordinator.execute(request)
                messages.add(resultMessage)
                executedTools += request.name()
            }
        }

        // Max iterations exhausted — return last response with error
        logger.warn("Tool loop exceeded max iterations ($maxIterations)")
        val executed = executedTools.distinct().joinToString(", ")
        return chatResponseToModelResponse(response!!, totalInput, totalOutput, totalTotal).let { mr ->
            mr.copy(modelError = "Tool call loop exceeded max iterations ($maxIterations)" +
                if (executed.isNotBlank()) "; executed: $executed" else "")
        }
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
}
