package ai.platon.pulsar.rest.api.entities

import ai.platon.pulsar.agentic.model.AgentHistory
import ai.platon.pulsar.agentic.model.AgentState
import ai.platon.pulsar.api.model.BrowserUseState
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CommandAgentStateTest {

    private fun state(step: Int, sessionId: String? = null): AgentState =
        AgentState(step, "instr", browserUseState = BrowserUseState.DUMMY, sessionId = sessionId)

    @Test
    @DisplayName("AgentState maps all projected fields to CommandAgentState")
    fun agentStateMapsToCommandAgentState() {
        val state = state(step = 2, sessionId = "run-1").apply {
            description = "clicked the button"
            event = "act"
            domain = "tab"
            method = "click"
            thinking = "the button matches the goal"
            nextGoal = "verify the result page"
            evaluationPreviousGoal = "failed"
            summary = "clicked"
            isComplete = true
        }

        val mapped = state.toCommandAgentState()

        assertEquals(2, mapped.step)
        assertEquals("instr", mapped.instruction)
        assertEquals("clicked the button", mapped.description)
        assertEquals("act", mapped.event)
        assertEquals("tab", mapped.domain)
        assertEquals("click", mapped.method)
        assertEquals("the button matches the goal", mapped.thinking)
        assertEquals("verify the result page", mapped.nextGoal)
        assertEquals("failed", mapped.evaluationPreviousGoal)
        assertEquals("clicked", mapped.summary)
        assertEquals(true, mapped.isComplete)
        assertEquals(state.timestamp.toString(), mapped.timestamp)
    }

    @Test
    @DisplayName("AgentHistory maps all states in order and lastOrNull returns the tail")
    fun agentHistoryMapsToCommandAgentHistory() {
        val history = AgentHistory(
            mutableListOf(state(1, "run-1"), state(2, "run-1"))
        )

        val mapped = history.toCommandAgentHistory()

        assertEquals(2, mapped.states.size)
        assertEquals(listOf(1, 2), mapped.states.map { it.step })
        assertEquals(2, mapped.lastOrNull()?.step)
    }

    @Test
    @DisplayName("empty AgentHistory maps to an empty CommandAgentHistory")
    fun emptyHistoryMapsToEmptyCommandHistory() {
        val mapped = AgentHistory().toCommandAgentHistory()
        assertEquals(0, mapped.states.size)
        assertNull(mapped.lastOrNull())
    }
}
