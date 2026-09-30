package ai.platon.pulsar.agentic.model

import ai.platon.pulsar.api.model.BrowserUseState
import ai.platon.pulsar.common.serialize.json.pulsarObjectMapper
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AgentHistoryTest {

    private fun state(step: Int, sessionId: String?): AgentState =
        AgentState(step, "instr", browserUseState = BrowserUseState.DUMMY, sessionId = sessionId)

    // ─────────────────────────────────────────────────────────────────────
    // P2: task-scoped views of the shared accumulated history
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("statesFor filters states by execution session")
    fun statesForFiltersBySession() {
        val history = AgentHistory(
            mutableListOf(
                state(1, "run-1"),
                state(2, "run-1"),
                state(1, "run-2"),
            )
        )

        val run1 = history.statesFor("run-1")
        assertEquals(listOf(1, 2), run1.map { it.step })
        assertTrue(run1.all { it.sessionId == "run-1" })

        val run2 = history.statesFor("run-2")
        assertEquals(listOf(1), run2.map { it.step })
    }

    @Test
    @DisplayName("snapshotFor returns a detached, session-scoped copy")
    fun snapshotForReturnsDetachedScopedCopy() {
        val history = AgentHistory(
            mutableListOf(
                state(1, "run-1"),
                state(2, "run-1"),
                state(1, "run-2"),
            )
        )

        val snapshot = history.snapshotFor("run-1")
        assertEquals(listOf(1, 2), snapshot.states.map { it.step })
        assertEquals(2, snapshot.totalSteps)

        // Mutating the live history (appends or trims) must not affect the snapshot.
        history.states.clear()
        history.states.add(state(9, "run-3"))
        assertEquals(2, snapshot.states.size)
        assertTrue(snapshot.states.all { it.sessionId == "run-1" })
    }

    @Test
    @DisplayName("snapshotFor unknown or null session returns an empty history")
    fun snapshotForUnknownSessionIsEmpty() {
        val history = AgentHistory(mutableListOf(state(1, "run-1")))
        assertTrue(history.snapshotFor("nope").states.isEmpty())
        assertTrue(history.snapshotFor(null).states.isEmpty())
        assertTrue(AgentHistory().snapshotFor("run-1").states.isEmpty())
    }

    // ─────────────────────────────────────────────────────────────────────
    // P5: AgentState/AgentHistory Jackson round-trip (task JSONL persistence)
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("agent history jackson round-trip preserves state fields including exception")
    fun agentHistoryJacksonRoundTripPreservesStateFields() {
        val mapper = pulsarObjectMapper()
        val state = AgentState(1, "Open example.com", browserUseState = BrowserUseState.DUMMY, sessionId = "run-1").apply {
            description = "clicked the button"
            domain = "tab"
            method = "click"
            thinking = "the button matches the goal"
            nextGoal = "verify the result page"
            evaluationPreviousGoal = "success"
            isComplete = true
            summary = "done"
            exception = IllegalStateException("boom")
        }
        val history = AgentHistory(mutableListOf(state))

        val json = mapper.writeValueAsString(history)
        val restored = mapper.readValue(json, AgentHistory::class.java)

        assertEquals(1, restored.states.size)
        val rs = restored.states.single()
        assertEquals(1, rs.step)
        assertEquals("run-1", rs.sessionId)
        assertEquals("tab", rs.domain)
        assertEquals("click", rs.method)
        assertEquals("clicked the button", rs.description)
        assertEquals("success", rs.evaluationPreviousGoal)
        assertEquals(true, rs.isComplete)
        assertEquals("done", rs.summary)
        assertEquals("boom", rs.exception?.message)
    }
}
