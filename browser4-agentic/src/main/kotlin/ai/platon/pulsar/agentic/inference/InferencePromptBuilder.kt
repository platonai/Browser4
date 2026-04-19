package ai.platon.pulsar.agentic.inference

import com.fasterxml.jackson.databind.node.ObjectNode

object InferencePromptBuilder {

    private val promptBuilder = PromptBuilder()

    fun buildObserveMessages(params: ObserveParams): AgentMessageList {
        return if (params.multistep) {
            // Multistep agents uses start with agent.run()
            promptBuilder.buildMultistepAgentMessageListAll(params.context)
        } else {
            // Single step agents uses observe() -> act()
            promptBuilder.buildSingleObserveMessageListAll(params, params.context)
        }
    }

    fun buildExtractionPrompt(params: ExtractParams): AgentMessageList {
        val messages = AgentMessageList()

        messages.addLast(promptBuilder.buildExtractSystemPrompt(params.userProvidedInstructions))
        messages.addUser(promptBuilder.buildExtractUserRequestPrompt(params), "user_request")
        messages.addLast(promptBuilder.buildExtractPageContentUserPrompt(params))

        return messages
    }

    fun buildExtractionEvaluationPrompt(
        params: ExtractParams,
        extractedNode: ObjectNode,
    ): AgentMessageList {
        val metadataMessages = AgentMessageList()
        val metadataSystem = promptBuilder.buildExtractionEvaluationSystemPrompt()
        // For metadata, pass the extracted object directly
        val metadataUser = promptBuilder.buildExtractionEvaluationUserPrompt(params.instruction, extractedNode, params.agentState)

        metadataMessages.addLast(metadataSystem)
        metadataMessages.addLast(metadataUser)

        return metadataMessages
    }

    fun buildSummaryPrompt(instruction: String?, textContent: String): AgentMessageList {
        val messages = AgentMessageList()

        if (instruction.isNullOrBlank()) {
            messages.addUser("Provide a summary for the following text.")
        } else {
            messages.addUser("Generate a summary of the following text based on the user's instructions.")
            messages.addUser("""<user_request>$instruction</user_request>""")
        }
        messages.addUser("\n\n$textContent\n\n".trimIndent())

        return messages
    }
}
