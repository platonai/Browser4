package ai.platon.pulsar.agentic.inference

import ai.platon.pulsar.agentic.model.AgentHistory
import ai.platon.pulsar.agentic.model.AgentState
import ai.platon.pulsar.api.model.BrowserUseState
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
}
