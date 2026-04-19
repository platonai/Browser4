package ai.platon.pulsar.agentic.inference.action

import ai.platon.pulsar.agentic.event.AgentEventBus
import ai.platon.pulsar.agentic.event.AgenticEvents
import ai.platon.pulsar.agentic.inference.AgentMessageList
import ai.platon.pulsar.agentic.model.ActionDescription
import ai.platon.pulsar.agentic.model.ExecutionContext
import ai.platon.pulsar.common.AppPaths
import ai.platon.pulsar.common.ExperimentalApi
import ai.platon.pulsar.common.brief
import ai.platon.pulsar.common.config.ImmutableConfig
import ai.platon.pulsar.common.event.EventBus
import ai.platon.pulsar.common.getLogger
import ai.platon.pulsar.external.BrowserChatModel
import ai.platon.pulsar.external.ChatModelFactory
import ai.platon.pulsar.external.ModelResponse
import ai.platon.pulsar.external.ResponseState
import java.nio.file.Files

open class ContextToAction(
    val conf: ImmutableConfig
) {
    private val logger = getLogger(this)

    val baseDir = AppPaths.get("tta")

    val chatModel: BrowserChatModel get() = ChatModelFactory.getOrCreate(conf)

    val tta = TextToAction(conf)

    init {
        Files.createDirectories(baseDir)
    }

    @ExperimentalApi
    open suspend fun generate(messages: AgentMessageList, context: ExecutionContext): ActionDescription {
        onWillGenerate(context, messages)

        try {
            val instruction = context.instruction

            val response = generateResponseRaw(messages, context.screenshotB64)

            val actionDescription = tta.modelResponseToActionDescription(instruction, context.agentState, response)

            require(context.agentState.actionDescription == actionDescription) {
                "Required: context.agentState.actionDescription == actionDescription"
            }

            onDidGenerate(context, messages, actionDescription)

            return actionDescription
        } catch (e: Exception) {
            val errorResponse = ModelResponse("Unknown exception" + e.brief(), ResponseState.OTHER)
            val actionDescription = ActionDescription(
                context.instruction,
                exception = e,
                modelResponse = errorResponse,
                context = context
            )
            context.agentState.actionDescription = actionDescription

            return actionDescription
        } finally {
        }
    }

    @ExperimentalApi
    open suspend fun generateResponseRaw(messages: AgentMessageList, screenshotB64: String? = null): ModelResponse {
        val systemMessage = messages.systemMessages().joinToString("\n")
        val userMessage = messages.userMessages().joinToString("\n")

        val category = "cta"
        val response = if (screenshotB64 != null) {
            chatModel.call(
                systemMessage,
                userMessage,
                imageUrl = null,
                b64Image = screenshotB64,
                mediaType = "image/jpeg", category = category
            )
        } else {
            chatModel.call(systemMessage, userMessage, category = category)
        }

        return response
    }

    private fun onWillGenerate(context: ExecutionContext, messages: AgentMessageList) {
        // Emit AgentEventBus inference event
        AgentEventBus.emitInferenceEvent(
            eventType = AgenticEvents.ContextToAction.ON_WILL_GENERATE,
            agentId = context.uuid,
            message = "Starting LLM inference",
            metadata = mapOf(
                "context" to context.sid,
                "step" to context.step
            )
        )

        EventBus.emit(
            AgenticEvents.ContextToAction.ON_WILL_GENERATE, mapOf(
                "context" to context,
                "messages" to messages
            )
        )
    }

    private fun onDidGenerate(
        context: ExecutionContext,
        messages: AgentMessageList,
        actionDescription: ActionDescription
    ) {
        val modelResponse = actionDescription.modelResponse!!

        // Emit AgentEventBus inference event
        AgentEventBus.emitInferenceEvent(
            eventType = AgenticEvents.ContextToAction.ON_DID_GENERATE,
            agentId = context.uuid,
            message = "LLM inference completed,",
            metadata = mapOf(
                "context" to context.sid,
                "step" to context.step,
                "inputToken" to modelResponse.tokenUsage.inputTokenCount,
                "outputToken" to modelResponse.tokenUsage.outputTokenCount,
                "totalToken" to modelResponse.tokenUsage.totalTokenCount
            )
        )

        EventBus.emit(
            AgenticEvents.ContextToAction.ON_DID_GENERATE, mapOf(
                "context" to context,
                "messages" to messages,
                "actionDescription" to actionDescription
            )
        )
    }
}
