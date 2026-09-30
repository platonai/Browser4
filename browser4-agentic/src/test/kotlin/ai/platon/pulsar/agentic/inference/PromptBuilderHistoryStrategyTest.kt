package ai.platon.pulsar.agentic.inference

import ai.platon.pulsar.agentic.agents.AgentConfig
import ai.platon.pulsar.agentic.model.ActionDescription
import ai.platon.pulsar.agentic.model.AgentHistory
import ai.platon.pulsar.agentic.model.AgentState
import ai.platon.pulsar.agentic.model.ExecutionContext
import ai.platon.pulsar.api.model.BrowserUseState
import ai.platon.pulsar.external.ModelResponse
import ai.platon.pulsar.external.ResponseState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PromptBuilderHistoryStrategyTest {

    @Test
    fun emptyHistoryRendersNonBlankPlaceholder() {
        // P0.1 regression: an empty history used to render "" which flowed into
        // a blank user message and crashed the LangChain4j conversion on a
        // fresh task's first step. A non-blank placeholder must be rendered.
        val builder = PromptBuilder()

        val rendered = builder.buildAgentStateHistoryMessage(AgentHistory())
        assertTrue(rendered.isNotBlank(), "Empty history must render a non-blank placeholder")
        assertEquals("No execution history yet.", rendered)
    }

    @Test
    fun smallHistoryKeepsDetailedEntries() {
        val builder = PromptBuilder()
        val history = AgentHistory(
            mutableListOf(
                createState(1, "navigate", nextGoal = "Open the product page"),
                createState(2, "click", nextGoal = "Inspect the details tab")
            )
        )

        val rendered = builder.buildAgentStateHistoryMessage(history)

        assertTrue(rendered.contains("showing all 2 recorded steps"))
        assertTrue(rendered.contains("\"step\":1"))
        assertTrue(rendered.contains("\"step\":2"))
        assertTrue(rendered.contains("### Recent Steps"))
    }

    private fun createState(step: Int, method: String, nextGoal: String): AgentState {
        return AgentState(
            step = step,
            instruction = "Do something",
            browserUseState = BrowserUseState.DUMMY,
            method = method,
            nextGoal = nextGoal,
        )
    }

    @Test
    fun prevToolCallResultRendersModelErrorWithoutToolResult() {
        // P0.2: a loop-overflow step has a modelError but NO toolCallResult —
        // the overflow digest must still reach the next step's prompt.
        val builder = PromptBuilder()
        val prevState = AgentState(1, "Do something", browserUseState = BrowserUseState.DUMMY)
        prevState.actionDescription = ActionDescription(
            "Do something",
            modelResponse = ModelResponse("", ResponseState.STOP)
                .copy(modelError = "Tool call loop exceeded max iterations (12); executed: coding.read"),
        )
        val agentState = AgentState(2, "Do something", browserUseState = BrowserUseState.DUMMY)
        agentState.prevState = prevState
        val context = ExecutionContext(
            step = 2,
            instruction = "Do something",
            event = "act",
            agentState = agentState,
            stateHistory = AgentHistory(),
            config = AgentConfig(),
            sessionId = "session-1",
        )

        val rendered = builder.buildPrevToolCallResultMessage(context)

        assertTrue(rendered.contains("Previous model error"), rendered)
        assertTrue(rendered.contains("Tool call loop exceeded max iterations (12); executed: coding.read"), rendered)
        assertTrue(rendered.contains("no tool result"), rendered)
    }
}
