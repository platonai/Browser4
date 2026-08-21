package ai.platon.pulsar.agentic.tools.advanced.agent

import ai.platon.pulsar.agentic.AgenticSession
import ai.platon.pulsar.agentic.agents.BasicBrowserAgent
import ai.platon.pulsar.agentic.model.AgentHistory
import ai.platon.pulsar.agentic.model.AgentState
import ai.platon.pulsar.api.model.BrowserUseState
import ai.platon.pulsar.common.ResourceStatus
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class StatefulAgentRunnerTest {

    private lateinit var tempDir: Path

    @BeforeEach
    fun setUp(@TempDir dir: Path) {
        tempDir = dir
        // Route the runner's JSONL persistence into the temp dir
        System.setProperty("browser4.data.dir", dir.toString())
    }

    @AfterEach
    fun tearDown() {
        System.clearProperty("browser4.data.dir")
    }

    private fun mockAgent(history: AgentHistory, lastRunSessionId: String?): BasicBrowserAgent {
        val agent = mockk<BasicBrowserAgent>(relaxed = true)
        every { agent.stateHistory } returns history
        every { agent.lastRunSessionId } returns lastRunSessionId
        return agent
    }

    private fun mockSession(agent: BasicBrowserAgent): AgenticSession {
        val session = mockk<AgenticSession>(relaxed = true)
        every { session.companionAgent } returns agent
        return session
    }

    private fun state(step: Int, instruction: String, sessionId: String): AgentState =
        AgentState(step, instruction, browserUseState = BrowserUseState.DUMMY, sessionId = sessionId)

    // ─────────────────────────────────────────────────────────────────────
    // P2: status history is scoped to the task's execution session
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("successful run stores the task-scoped history on the status")
    fun successfulRunStoresTaskScopedHistory() = runBlocking {
        // Shared accumulated history: previous task's states exist alongside the current task's
        val agent = mockAgent(
            history = AgentHistory(
                mutableListOf(state(1, "prev task", "prev-run"), state(1, "current task", "run-1"))
            ),
            lastRunSessionId = "run-1"
        )
        coEvery { agent.run(any<String>()) } returns AgentHistory(
            mutableListOf(state(1, "current task", "run-1"), state(2, "current task", "run-1"))
        )
        val runner = StatefulAgentRunner(mockSession(agent))
        try {
            val status = AgentTaskStatus()
            runner.execute("current task", status)

            val history = status.agentHistory
            assertNotNull(history, "status must carry the agent history")
            assertEquals(2, history!!.states.size)
            assertTrue(history.states.all { it.sessionId == "run-1" }, "no cross-task states allowed")
            assertEquals("current task", history.states.single { it.step == 1 }.instruction)
        } finally {
            runner.close()
        }
    }

    @Test
    @DisplayName("failed run still stores a task-scoped, detached history snapshot")
    fun failedRunStoresTaskScopedDetachedSnapshot() = runBlocking {
        val agent = mockAgent(
            history = AgentHistory(
                mutableListOf(state(1, "prev task", "prev-run"), state(1, "current task", "run-2"))
            ),
            lastRunSessionId = "run-2"
        )
        coEvery { agent.run(any<String>()) } throws IllegalStateException("kaboom")
        val runner = StatefulAgentRunner(mockSession(agent))
        try {
            val status = AgentTaskStatus()
            runner.execute("current task", status)

            assertTrue(status.isDone)
            assertNotEquals(ResourceStatus.SC_OK, status.statusCode)

            val history = status.agentHistory
            assertNotNull(history, "status must carry a scoped history even on failure")
            assertEquals(1, history!!.states.size)
            assertEquals("run-2", history.states.single().sessionId)
            assertEquals("current task", history.states.single().instruction)

            // The snapshot is detached: later mutation of the live history cannot leak in.
            agent.stateHistory.states.clear()
            assertEquals(1, status.agentHistory!!.states.size)
        } finally {
            runner.close()
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // P2.5: JSONL restore never regresses a terminal status
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("restoreFromDisk keeps a terminal status when a later JSONL row is non-terminal")
    fun restoreFromDiskKeepsTerminalStatus() {
        val agent = mockAgent(AgentHistory(), "run-r")
        val runner1 = StatefulAgentRunner(mockSession(agent))
        try {
            // Out-of-order append glitch: the done row lands before a stale
            // created row for the same id.
            val done = AgentTaskStatus(id = "t-prio", statusCode = ResourceStatus.SC_OK, processState = "done").apply {
                startedTime = null; lastModifiedTime = null; finishTime = null
            }
            runner1.persistence.append(done)
            val created = AgentTaskStatus(id = "t-prio", statusCode = ResourceStatus.SC_CREATED, processState = "created").apply {
                startedTime = null; lastModifiedTime = null; finishTime = null
            }
            runner1.persistence.append(created)
        } finally {
            runner1.close()
        }

        // A fresh runner (simulated restart) restores from the same JSONL.
        val runner2 = StatefulAgentRunner(mockSession(agent))
        try {
            val restored = runner2.getStatus("t-prio")
            assertNotNull(restored, "terminal task must be restored from the JSONL")
            assertEquals("done", restored!!.processState,
                "a non-terminal row must not overwrite the restored terminal status")
        } finally {
            runner2.close()
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // P6: concurrent task submissions are serialized per session
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("concurrent task executions on the same session never overlap")
    fun concurrentExecutionsAreSerialized() = runBlocking {
        val agent = mockAgent(history = AgentHistory(), lastRunSessionId = "run-x")
        val active = AtomicInteger(0)
        val maxActive = AtomicInteger(0)
        coEvery { agent.run(any<String>()) } coAnswers {
            val current = active.incrementAndGet()
            maxActive.accumulateAndGet(current) { a, b -> maxOf(a, b) }
            delay(50)
            active.decrementAndGet()
            AgentHistory()
        }
        val runner = StatefulAgentRunner(mockSession(agent))
        try {
            val statuses = (1..3).map { AgentTaskStatus() }
            coroutineScope {
                statuses.forEach { status ->
                    launch(Dispatchers.Default) { runner.execute("task", status) }
                }
            }
            assertEquals(1, maxActive.get(), "agent runs must not overlap")
            statuses.forEach { assertTrue(it.isDone) }
        } finally {
            runner.close()
        }
    }
}
