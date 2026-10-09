package ai.platon.pulsar.agentic.tools.advanced.agent

import ai.platon.pulsar.agentic.model.AgentHistory
import ai.platon.pulsar.agentic.model.AgentState
import ai.platon.pulsar.api.model.BrowserUseState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class TaskScopedHistoryTest {

    private fun state(instruction: String) = AgentState(
        step = 1,
        instruction = instruction,
        browserUseState = BrowserUseState.DUMMY,
    )

    private fun historyOf(vararg instructions: String) =
        AgentHistory(instructions.mapTo(mutableListOf()) { state(it) })

    @Test
    @DisplayName("keeps only states produced after startIndex")
    fun keepsOnlyStatesFromCurrentTask() {
        val history = historyOf("task-1", "task-2", "task-3")

        val scoped = StatefulAgentRunner.taskScopedHistory(history, startIndex = 1)

        assertEquals(listOf("task-2", "task-3"), scoped.states.map { it.instruction })
    }

    @Test
    @DisplayName("startIndex zero keeps every state")
    fun startIndexZeroKeepsAll() {
        val history = historyOf("a", "b")

        val scoped = StatefulAgentRunner.taskScopedHistory(history, startIndex = 0)

        assertEquals(2, scoped.states.size)
    }

    @Test
    @DisplayName("startIndex beyond size yields an empty history")
    fun startIndexBeyondSizeIsEmpty() {
        val history = historyOf("a")

        val scoped = StatefulAgentRunner.taskScopedHistory(history, startIndex = 5)

        assertEquals(0, scoped.states.size)
    }

    @Test
    @DisplayName("returned history is a detached copy immune to later appends")
    fun snapshotIsDetachedFromCumulativeHistory() {
        val history = historyOf("old", "current")
        val scoped = StatefulAgentRunner.taskScopedHistory(history, startIndex = 1)
        assertNotSame(history.states, scoped.states)

        // A later task appends to the shared cumulative history; the published
        // snapshot must not grow retroactively.
        history.states.add(state("future-task"))

        assertEquals(listOf("current"), scoped.states.map { it.instruction })
        assertEquals(3, history.states.size)
    }
}
