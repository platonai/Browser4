package ai.platon.pulsar.agentic.inference

import ai.platon.pulsar.agentic.agents.AgentConfig
import ai.platon.pulsar.agentic.model.AgentHistory
import ai.platon.pulsar.agentic.model.AgentState
import ai.platon.pulsar.agentic.model.ExecutionContext
import ai.platon.pulsar.api.model.BrowserUseState
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.UUID

@DisplayName("PromptBuilder blank-message guards")
class PromptBuilderBlankMessageTest {

    @Test
    @DisplayName("fresh session first step has no blank user message and converts cleanly")
    fun freshSessionFirstStepHasNoBlankUserMessage() {
        // P0.1 golden regression: on HEAD a fresh session's empty history rendered
        // "" into an empty user message and LangChain4j conversion threw
        // "text cannot be null or blank" on the very first LLM call of every task.
        val builder = PromptBuilder()
        val context = ExecutionContext(
            step = 1,
            instruction = "Find the download link",
            event = "act",
            agentState = AgentState(1, "Find the download link", browserUseState = BrowserUseState.DUMMY),
            stateHistory = AgentHistory(),
            config = AgentConfig(),
            sessionId = UUID.randomUUID().toString(),
        )

        val messages = builder.buildMultistepAgentMessageListAll(context)

        assertFalse(
            messages.messages.any { it.role == "user" && it.content.isBlank() },
            "Fresh-session first step must not carry a blank user message"
        )
        assertDoesNotThrow { messages.toChatMessages() }
    }
}
