package ai.platon.pulsar.agentic.inference.chat

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
) {
    private val logger = getLogger(AgentToolCallLoop::class)

    /**
     * Run the tool-calling loop and return a [ModelResponse] containing the
     * final assistant text (still in JSON ActionDescription format, so
     * downstream parsing is unchanged).
     */
    suspend fun generate(initialMessages: List<ChatMessage>): ModelResponse {
        var messages = initialMessages.toMutableList()
        var response: ChatResponse? = null

        for (iteration in 0 until maxIterations) {
            val request = ChatRequest.builder()
                .messages(messages)
                .toolSpecifications(toolSpecifications)
                .build()

            response = model.langChainChat(request, "cta")
            val aiMessage = response.aiMessage()
            messages.add(aiMessage)

            val toolRequests = aiMessage.toolExecutionRequests()
            if (toolRequests.isNullOrEmpty()) {
                // No more tool calls — return the final response
                logger.debug("Tool loop finished after ${iteration + 1} round(s)")
                return chatResponseToModelResponse(response)
            }

            logger.debug("Tool loop round ${iteration + 1}: executing ${toolRequests.size} tool(s)")

            // Execute each tool request and append results
            for (request in toolRequests) {
                val resultMessage: ToolExecutionResultMessage = coordinator.execute(request)
                messages.add(resultMessage)
            }
        }

        // Max iterations exhausted — return last response with error
        logger.warn("Tool loop exceeded max iterations ($maxIterations)")
        return chatResponseToModelResponse(response!!).let { mr ->
            mr.copy(modelError = "Tool call loop exceeded max iterations ($maxIterations)")
        }
    }

    private fun chatResponseToModelResponse(response: ChatResponse): ModelResponse {
        val content = response.aiMessage().text() ?: ""
        val usage = response.tokenUsage()
        return ModelResponse(
            content = content,
            state = ResponseState.STOP,
            tokenUsage = ai.platon.pulsar.external.TokenUsage(
                inputTokenCount = usage?.inputTokenCount() ?: 0,
                outputTokenCount = usage?.outputTokenCount() ?: 0,
                totalTokenCount = usage?.totalTokenCount() ?: 0,
            ),
        )
    }
}
