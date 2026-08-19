package ai.platon.pulsar.agentic.inference

import ai.platon.pulsar.agentic.agents.AgentConfig
import ai.platon.pulsar.agentic.agents.BasicBrowserAgent
import ai.platon.pulsar.agentic.inference.detail.PageStateTracker
import ai.platon.pulsar.agentic.model.*
import ai.platon.pulsar.api.model.BrowserUseState
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AgentStateManagerTest {

    private lateinit var tempDir: Path
    private lateinit var stateManager: AgentStateManager

    @BeforeEach
    fun setUp(@TempDir dir: Path) {
        tempDir = dir
        val agent = mockk<BasicBrowserAgent>(relaxed = true)
        every { agent.logDir } returns tempDir
        every { agent.config } returns AgentConfig()
        val pageStateTracker = mockk<PageStateTracker>(relaxed = true)
        stateManager = AgentStateManager(agent, pageStateTracker)
    }

    private fun context(step: Int, sessionId: String = "s1", event: String = "act"): ExecutionContext {
        return ExecutionContext(
            step = step,
            instruction = "instr",
            event = event,
            agentState = AgentState(step, "instr", browserUseState = BrowserUseState.DUMMY),
            stateHistory = AgentHistory(),
            config = AgentConfig(),
            sessionId = sessionId,
        )
    }

    private fun actionDescription(): ActionDescription {
        return ActionDescription(
            instruction = "instr",
            observeElements = listOf(
                ObserveElement(toolCall = ToolCall("tab", "click", mutableMapOf("selector" to "#go")))
            )
        )
    }

    // ─────────────────────────────────────────────────────────────────────
    // P1: DetailedActResult.exception must reach AgentState.exception
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("updateAgentState forwards DetailedActResult.exception to AgentState")
    fun updateAgentStateForwardsExceptionToAgentState() {
        val ctx = context(step = 1)
        val ad = actionDescription()
        val toolCallResult = ToolCallResult(
            evaluate = TcEvaluate(expression = "click"),
            actionDescription = ad
        )
        val exception = IllegalStateException("boom")
        val detailed = DetailedActResult(
            actionDescription = ad,
            toolCallResult = toolCallResult,
            description = "tool failed",
            exception = exception
        )

        stateManager.updateAgentState(ctx, detailed)

        assertNotNull(ctx.agentState.exception)
        assertEquals("boom", ctx.agentState.exception?.message)
        assertFalse(ctx.agentState.isSuccess)
        assertTrue(ctx.agentState.hasErrors)
    }

    @Test
    @DisplayName("updateAgentState without exception records a successful state")
    fun updateAgentStateWithoutExceptionRecordsSuccess() {
        val ctx = context(step = 1)
        val ad = actionDescription()
        val toolCallResult = ToolCallResult(
            evaluate = TcEvaluate(expression = "click"),
            actionDescription = ad
        )
        val detailed = DetailedActResult(
            actionDescription = ad,
            toolCallResult = toolCallResult,
            description = "tool ok"
        )

        stateManager.updateAgentState(ctx, detailed)

        assertEquals(null, ctx.agentState.exception)
        assertTrue(ctx.agentState.isSuccess)
        assertFalse(ctx.agentState.hasErrors)
    }

    // ─────────────────────────────────────────────────────────────────────
    // P3: completed states must reach the state-history.jsonl audit stream
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("completed state is appended to state-history.jsonl with isComplete and summary")
    fun completedStateIsPersistedToStateHistoryJsonl() {
        val ctx = context(step = 1)
        // 1. context creation writes the pre-action state (mirrors buildExecutionContext)
        stateManager.writeAgentState(ctx.agentState, ctx.sessionId)
        // 2. action execution writes the updated state (mirrors updateAgentState)
        val ad = actionDescription()
        stateManager.updateAgentState(
            ctx, DetailedActResult(ad, ToolCallResult(TcEvaluate(expression = "click"), actionDescription = ad), "ok")
        )
        // 3. task completion mutates the state and re-persists it (mirrors onTaskCompletion)
        ctx.agentState.isComplete = true
        ctx.agentState.summary = "all done"
        stateManager.writeAgentState(ctx.agentState, ctx.sessionId)

        val lines = Files.readAllLines(tempDir.resolve("task-s1").resolve("state-history.jsonl"))
        assertEquals(3, lines.size, "bare + updated + completed snapshots")
        val last = lines.last()
        assertTrue(last.contains("\"isComplete\":true"), "last snapshot must carry the completion flag: $last")
        assertTrue(last.contains("\"summary\":\"all done\""), "last snapshot must carry the summary: $last")
    }

    // ─────────────────────────────────────────────────────────────────────
    // P4: rollback must keep the on-disk history consistent with memory
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("removeLastIfStep truncates the rolled-back entry from disk history files")
    fun removeLastIfStepTruncatesDiskHistory() {
        val ctx = context(step = 2)
        stateManager.setActiveContext(ctx)

        val state1 = AgentState(1, "instr", browserUseState = BrowserUseState.DUMMY)
        val state2 = AgentState(2, "instr", browserUseState = BrowserUseState.DUMMY)
        stateManager.addToHistory(state1)
        stateManager.addToHistory(state2)

        val jsonl = tempDir.resolve("task-s1").resolve("history.jsonl")
        val log = tempDir.resolve("task-s1").resolve("history.log")
        assertEquals(2, stateManager.stateHistory.states.size)
        assertEquals(2, Files.readAllLines(jsonl).size)
        assertEquals(2, Files.readAllLines(log).size)

        stateManager.removeLastIfStep(2)

        assertEquals(1, stateManager.stateHistory.states.size)
        assertEquals(1, stateManager.stateHistory.states.single().step)
        assertEquals(1, Files.readAllLines(jsonl).size)
        assertEquals(1, Files.readAllLines(log).size)
        assertTrue(Files.readAllLines(jsonl).single().contains("\"step\":1"))
    }

    @Test
    @DisplayName("removeLastIfStep is a no-op when the last entry step is below the rollback step")
    fun removeLastIfStepKeepsFilesWhenStepDoesNotMatch() {
        val ctx = context(step = 1)
        stateManager.setActiveContext(ctx)

        stateManager.addToHistory(AgentState(1, "instr", browserUseState = BrowserUseState.DUMMY))
        stateManager.removeLastIfStep(5)

        assertEquals(1, stateManager.stateHistory.states.size)
        val jsonl = tempDir.resolve("task-s1").resolve("history.jsonl")
        assertEquals(1, Files.readAllLines(jsonl).size)
    }
}
