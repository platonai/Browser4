package ai.platon.pulsar.agentic.tools.advanced.agent

import ai.platon.browser4.common.B4Constants
import ai.platon.pulsar.agentic.AgenticSession
import ai.platon.pulsar.agentic.agents.AgentRunEvent
import ai.platon.pulsar.agentic.agents.BasicBrowserAgent
import ai.platon.pulsar.agentic.agents.RobustBrowserAgent
import ai.platon.pulsar.agentic.model.AgentHistory
import ai.platon.pulsar.agentic.model.AgentState
import ai.platon.pulsar.api.model.BrowserUseState
import ai.platon.pulsar.common.ResourceStatus
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.Runs
import io.mockk.slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertFalse
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
    @Tag("Slow")
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

    // ─────────────────────────────────────────────────────────────────────
    // P0: cancelling a queued task must leave a terminal status, not a zombie
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("cancelling a queued task marks its status terminal failed")
    fun cancelQueuedTaskMarksStatusTerminal() = runBlocking {
        val agent = mockAgent(history = AgentHistory(), lastRunSessionId = "run-q")
        coEvery { agent.run(any<String>()) } coAnswers {
            delay(3_000)
            AgentHistory()
        }
        val runner = StatefulAgentRunner(mockSession(agent))
        try {
            val running = runner.create()
            runner.submit("long running task", running)
            // Let the first task enter its run and hold the mutex.
            delay(200)
            val queued = runner.create()
            runner.submit("queued task", queued)
            delay(100)

            assertTrue(runner.cancel(queued.id), "the queued task's job must be tracked")
            assertTrue(queued.isDone, "a cancelled queued task must not stay 'created'")
            assertEquals(ResourceStatus.SC_EXPECTATION_FAILED, queued.statusCode)
            assertTrue(queued.cancelled, "a user-requested cancel must be flagged as cancelled")
            assertTrue(
                queued.failureReason!!.contains("cancel", ignoreCase = true),
                "the failure reason must say the task was cancelled",
            )
        } finally {
            runner.close()
        }
    }

    @Test
    @DisplayName("close aborts in-flight and queued tasks and marks their statuses terminal")
    fun closeAbortsInFlightAndQueuedTasks() = runBlocking {
        val agent = mockAgent(history = AgentHistory(), lastRunSessionId = "run-c")
        coEvery { agent.run(any<String>()) } coAnswers {
            delay(5_000)
            AgentHistory()
        }
        val runner = StatefulAgentRunner(mockSession(agent))
        val running = runner.create()
        val queued = runner.create()
        runner.submit("running task", running)
        runner.submit("queued task", queued)
        // Let the first task enter its run; the second stays queued on the mutex.
        delay(200)

        runner.close()

        assertTrue(running.isDone, "close must not leave a running task's status non-terminal")
        assertTrue(queued.isDone, "a task queued at shutdown must not stay 'created' forever")
        assertEquals(ResourceStatus.SC_EXPECTATION_FAILED, queued.statusCode)
        assertTrue(queued.cancelled, "a shutdown abort is reported as cancelled, not failed")
    }

    // ─────────────────────────────────────────────────────────────────────
    // P2.5: restore must not revive interrupted tasks as zombie queued rows
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("restoreFromDisk reports an interrupted non-terminal task as failed")
    fun restoreFromDiskMarksInterruptedNonTerminalAsFailed() {
        val agent = mockAgent(AgentHistory(), "run-z")
        val runner1 = StatefulAgentRunner(mockSession(agent))
        val createdId = try {
            // A task created but never executed: what a mid-flight crash leaves on disk.
            runner1.create().id
        } finally {
            runner1.close()
        }

        // A fresh runner (simulated restart) restores from the same JSONL.
        val runner2 = StatefulAgentRunner(mockSession(agent))
        try {
            val restored = runner2.getStatus(createdId)
            assertNotNull(restored, "the interrupted task must still be restorable")
            assertTrue(restored!!.isDone, "an interrupted task must not be restored as a live 'created' status")
            assertEquals(ResourceStatus.SC_EXPECTATION_FAILED, restored.statusCode)
            assertEquals("Interrupted by server restart", restored.failureReason)
            assertFalse(restored.cancelled, "a restart interruption is a failure, not a user cancel")
        } finally {
            runner2.close()
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Compaction TTL must be measured from the row's last activity, not createdTime
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("compaction keeps a recently finished task even when it was created long ago")
    fun compactionKeepsRecentlyFinishedTask() {
        val agent = mockAgent(AgentHistory(), "run-t")
        val runner = StatefulAgentRunner(mockSession(agent))
        try {
            runner.persistence.append(
                AgentTaskStatus(id = "t-old-created", statusCode = ResourceStatus.SC_OK, processState = "done").apply {
                    createdTime = Instant.now().minusSeconds(3 * 3600)
                    // A real finish goes through done()/failed(), whose refresh()
                    // bumps lastModifiedTime — exactly what lastActivity() judges.
                    finishTime = Instant.now().minusSeconds(60)
                    lastModifiedTime = finishTime
                }
            )
            runner.restoreFromDisk()
            runner.compactPersistence()

            assertNotNull(
                runner.getStatus("t-old-created"),
                "a task finished within the TTL must survive compaction despite its old createdTime",
            )
        } finally {
            runner.close()
        }
    }

    @Test
    @DisplayName("compaction removes a task whose finish is past the TTL")
    fun compactionRemovesTaskPastTheFinishTtl() {
        val agent = mockAgent(AgentHistory(), "run-e")
        val runner = StatefulAgentRunner(mockSession(agent))
        try {
            runner.persistence.append(
                AgentTaskStatus(id = "t-expired", statusCode = ResourceStatus.SC_OK, processState = "done").apply {
                    createdTime = Instant.now().minusSeconds(2 * 3600)
                    finishTime = Instant.now().minusSeconds(2 * 3600)
                }
            )
            // A large TTL during restore so the row is loaded; a small TTL for compaction.
            runner.taskTtlMinutes = 10_000
            runner.restoreFromDisk()
            assertNotNull(runner.getStatus("t-expired"))
            runner.taskTtlMinutes = 1
            runner.compactPersistence()

            assertNull(runner.getStatus("t-expired"), "a task finished past the TTL must be compacted out")
        } finally {
            runner.close()
        }
    }

    @Test
    @DisplayName("statusFromDisk reports the last row for an id without building a runner")
    fun statusFromDiskReportsLastRowWithoutBuildingARunner() {
        val agent = mockAgent(AgentHistory(), "run-d")
        val runner = StatefulAgentRunner(mockSession(agent))
        val createdId: String = try {
            runner.create().id
        } finally {
            runner.close()
        }

        // Two calls: the first scans the file, the second must hit the
        // fingerprint cache and return the same snapshot.
        val first = StatefulAgentRunner.statusFromDisk(createdId)
        val second = StatefulAgentRunner.statusFromDisk(createdId)

        assertNotNull(first, "a row on disk must be answerable without a runner")
        assertEquals(first!!.id, second!!.id)
        assertTrue(first.isDone, "an interrupted row is reported as a terminal failure")
        assertEquals("Interrupted by server restart", first.failureReason)
        assertFalse(first.cancelled, "a restart interruption is a failure, not a user cancel")

        // Unknown ids stay unknown; repeated polls reuse the cached snapshot
        // (same file fingerprint) instead of rescanning the file each time.
        assertNull(StatefulAgentRunner.statusFromDisk("no-such-id"))
        assertNull(StatefulAgentRunner.statusFromDisk("no-such-id"))
    }

    // ─────────────────────────────────────────────────────────────────────
    // pageStatusCode: the PAGE's HTTP status, or unknown — never 201
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a task whose page loaded with 200 reports the page's 200, not 201")
    fun taskReportsTheObservedPageStatus() = runBlocking {
        val agent = mockAgent(history = AgentHistory(), lastRunSessionId = "run-p")
        coEvery { agent.run(any<String>()) } returns AgentHistory()
        val driver = mockk<ai.platon.pulsar.api.WebDriver>(relaxed = true)
        coEvery { driver.evaluateValue(DocumentHttpStatus.RESPONSE_STATUS_EXPRESSION) } returns 200
        val session = mockSession(agent)
        every { session.boundDriver } returns driver
        val runner = StatefulAgentRunner(session)
        try {
            val status = AgentTaskStatus()
            runner.execute("read the page", status)

            assertEquals(200, status.pageStatusCode, "the page's own status must be reported")
            assertNotEquals(
                ResourceStatus.SC_CREATED,
                status.pageStatusCode,
                "201/'created' is a task-lifecycle code, not the page's HTTP status",
            )
        } finally {
            runner.close()
        }
    }

    @Test
    @DisplayName("a task whose page status cannot be observed reports unknown, never 201")
    fun taskReportsUnknownWhenThePageCannotBeRead() = runBlocking {
        val agent = mockAgent(history = AgentHistory(), lastRunSessionId = "run-u")
        coEvery { agent.run(any<String>()) } returns AgentHistory()
        val driver = mockk<ai.platon.pulsar.api.WebDriver>(relaxed = true)
        coEvery { driver.evaluateValue(any<String>()) } returns null
        val session = mockSession(agent)
        every { session.boundDriver } returns driver
        val runner = StatefulAgentRunner(session)
        try {
            val status = AgentTaskStatus()
            runner.execute("coding task", status)

            assertEquals(B4Constants.PAGE_STATUS_UNKNOWN, status.pageStatusCode)
            assertNotEquals(ResourceStatus.SC_CREATED, status.pageStatusCode)
        } finally {
            runner.close()
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Coding vs browser is explicit — never guessed from task text
    // ─────────────────────────────────────────────────────────────────────

    /**
     * A runner whose agent is a [RobustBrowserAgent] (the runner casts explicitly to it
     * to apply per-task overrides), recording the coding mode the agent observed while
     * its task ran.
     */
    private fun mockRobustAgent(history: AgentHistory, lastRunSessionId: String?): RobustBrowserAgent {
        val agent = mockk<RobustBrowserAgent>(relaxed = true)
        every { agent.stateHistory } returns history
        every { agent.lastRunSessionId } returns lastRunSessionId
        return agent
    }

    @Test
    @DisplayName("an explicit coding flag is in force while the task runs, reset after")
    fun explicitCodingFlagIsInForceWhileTheTaskRuns() = runBlocking {
        val agent = mockRobustAgent(history = AgentHistory(), lastRunSessionId = "run-coding")
        // Property writes on a relaxed mock are not stored in a backing field —
        // capture them explicitly so the test can observe the exact assignments
        // (the last write wins, mirroring real field semantics).
        val codingWrite = slot<Boolean>()
        every { agent.codingMode = capture(codingWrite) } just Runs
        every { agent.codingMode } answers { codingWrite.captured }
        var codingDuringRun: Boolean? = null
        coEvery { agent.run(any<String>()) } coAnswers {
            codingDuringRun = agent.codingMode
            AgentHistory()
        }
        val runner = StatefulAgentRunner(mockSession(agent))
        try {
            val status = AgentTaskStatus()
            runner.execute("fix pom.xml", status, coding = true)

            assertEquals(true, codingDuringRun, "the coding flag must be in force while the agent runs")
            assertEquals(false, agent.codingMode, "the coding-mode override must reset when the task ends")
        } finally {
            runner.close()
        }
    }

    @Test
    @DisplayName("an unset coding flag means a browser task — never guessed from text")
    fun unsetCodingFlagMeansABrowserTask() = runBlocking {
        val agent = mockRobustAgent(history = AgentHistory(), lastRunSessionId = "run-browse")
        val codingWrite = slot<Boolean>()
        every { agent.codingMode = capture(codingWrite) } just Runs
        every { agent.codingMode } answers { codingWrite.captured }
        var codingDuringRun: Boolean? = null
        coEvery { agent.run(any<String>()) } coAnswers {
            codingDuringRun = agent.codingMode
            AgentHistory()
        }
        val runner = StatefulAgentRunner(mockSession(agent))
        try {
            val status = AgentTaskStatus()
            // A task that LOOKS like file/build work must still be a browser task
            // when the caller does not pass the explicit coding flag.
            runner.execute("read src/main/kotlin/App.kt and write a.md, mvn compile", status)

            assertEquals(false, codingDuringRun, "task text must never trigger coding mode")
            assertFalse(agent.codingMode)
        } finally {
            runner.close()
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Restore TTL is measured from last activity, not creation
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("restore keeps a long-running task whose last activity is recent")
    fun restoreKeepsRecentlyActiveTask() {
        val agent = mockAgent(AgentHistory(), "run-long")
        val runner1 = StatefulAgentRunner(mockSession(agent))
        try {
            // Created three hours ago, still writing progress a minute ago: judging
            // this row by createdTime would drop the newest state of a live task.
            val running = AgentTaskStatus(
                id = "t-long",
                statusCode = ResourceStatus.SC_PROCESSING,
                processState = "in_progress",
            ).apply {
                createdTime = Instant.now().minusSeconds(3 * 60 * 60)
                lastModifiedTime = Instant.now().minusSeconds(60)
                startedTime = createdTime.plusSeconds(5)
                toolExecutions = 42
            }
            runner1.persistence.append(running)
        } finally {
            runner1.close()
        }

        val runner2 = StatefulAgentRunner(mockSession(agent))
        try {
            val restored = runner2.getStatus("t-long")
            assertNotNull(restored, "TTL must be measured from last activity, not creation")
            assertEquals(42, restored!!.toolExecutions, "the live tool counter survives a restart")
        } finally {
            runner2.close()
        }
    }

    @Test
    @DisplayName("restore drops a task with no activity for the TTL")
    fun restoreDropsStaleTask() {
        val agent = mockAgent(AgentHistory(), "run-stale")
        val runner1 = StatefulAgentRunner(mockSession(agent))
        try {
            val stale = AgentTaskStatus(
                id = "t-stale",
                statusCode = ResourceStatus.SC_PROCESSING,
                processState = "in_progress",
            ).apply {
                createdTime = Instant.now().minusSeconds(3 * 60 * 60)
                lastModifiedTime = createdTime
            }
            runner1.persistence.append(stale)
        } finally {
            runner1.close()
        }

        val runner2 = StatefulAgentRunner(mockSession(agent))
        try {
            assertNull(runner2.getStatus("t-stale"), "a task silent for the whole TTL must not revive")
        } finally {
            runner2.close()
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Live step log and steering (the interactive `agent run` data source)
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("create records a created step that a follower can read from cursor 0")
    fun createRecordsCreatedStep() {
        val runner = StatefulAgentRunner(mockSession(mockAgent(AgentHistory(), null)))
        try {
            val status = runner.create()

            val events = runner.events(status.id)
            assertEquals(1, events.size)
            assertEquals(AgentTaskEvent.TYPE_CREATED, events[0].type)
            assertEquals(1L, events[0].seq)

            // A follower that already consumed seq 1 sees nothing until the run
            // starts; the cursor must never rewind.
            assertTrue(runner.events(status.id, afterSeq = 1).isEmpty())
            assertTrue(runner.events("unknown-task").isEmpty(), "an unknown id must not invent events")
        } finally {
            runner.close()
        }
    }

    @Test
    @DisplayName("a failed run ends the log with a task.failed step carrying the reason")
    fun failedRunRecordsTerminalStep() = runBlocking {
        val agent = mockAgent(AgentHistory(), "run-1")
        coEvery { agent.run(any<String>()) } throws IllegalStateException("boom")
        val runner = StatefulAgentRunner(mockSession(agent))
        try {
            val status = runner.execute("do something")

            val events = runner.events(status.id)
            assertEquals(
                listOf(AgentTaskEvent.TYPE_CREATED, AgentTaskEvent.TYPE_STARTED, AgentTaskEvent.TYPE_FAILED),
                events.map { it.type },
            )
            assertTrue(events.last().text!!.contains("boom"), "the failure reason must reach the follower: ${events.last()}")
        } finally {
            runner.close()
        }
    }

    @Test
    @DisplayName("a cancelled run ends the log with a task.cancelled step")
    fun cancelledRunRecordsTerminalStep() = runBlocking {
        val agent = mockAgent(AgentHistory(), "run-1")
        coEvery { agent.run(any<String>()) } coAnswers {
            delay(60_000)
            AgentHistory()
        }
        val runner = StatefulAgentRunner(mockSession(agent))
        try {
            val status = runner.create()
            runner.submit("long task", status)
            withTimeout(5_000) {
                while (status.startedTime == null) delay(10)
            }

            assertTrue(runner.cancel(status.id))
            // The status is marked terminal by cancel() itself; the terminal STEP
            // comes from the run's finally, so wait for the log, not the status.
            withTimeout(5_000) {
                while (runner.events(status.id).none { it.type == AgentTaskEvent.TYPE_CANCELLED }) {
                    delay(10)
                }
            }
            assertEquals(AgentTaskEvent.TYPE_CANCELLED, runner.events(status.id).last().type)
        } finally {
            runner.close()
        }
    }

    @Test
    @DisplayName("steering messages queue for a live task and drain exactly once")
    fun steeringQueueDrainsOnce() {
        val runner = StatefulAgentRunner(mockSession(mockAgent(AgentHistory(), null)))
        try {
            val status = runner.create()

            assertTrue(runner.enqueueInput(status.id, "  focus on the tests  "))
            assertTrue(runner.enqueueInput(status.id, "then stop"))
            assertEquals(
                listOf("focus on the tests", "then stop"),
                runner.drainInputs(status.id),
            )
            assertTrue(runner.drainInputs(status.id).isEmpty(), "draining is destructive")
        } finally {
            runner.close()
        }
    }

    @Test
    @DisplayName("steering is rejected for a finished, unknown or blank message")
    fun steeringRejectsDeadTasks() {
        val runner = StatefulAgentRunner(mockSession(mockAgent(AgentHistory(), null)))
        try {
            val status = runner.create()
            status.done()

            assertFalse(runner.enqueueInput(status.id, "too late"), "a finished task has no drainer")
            assertFalse(runner.enqueueInput("unknown-task", "hello"))
            assertFalse(runner.enqueueInput(status.id, "   "))
            assertTrue(runner.drainInputs(status.id).isEmpty())
        } finally {
            runner.close()
        }
    }

    @Test
    @DisplayName("agent-reported steps are sanitized on the way into the event log")
    fun runEventsAreSanitized() {
        val runner = StatefulAgentRunner(mockSession(mockAgent(AgentHistory(), null)))
        try {
            val status = runner.create()
            runner.recordRunEvent(
                status.id,
                AgentRunEvent(
                    AgentRunEvent.TOOL_CALL,
                    tool = "fill",
                    arguments = """{"selector":"input[type=password]","text":"hunter2"}""",
                    turn = 2,
                ),
            )
            runner.recordRunEvent(
                status.id,
                AgentRunEvent(
                    AgentRunEvent.TOOL_RESULT,
                    tool = "fill",
                    ok = false,
                    durationMs = 42,
                    text = "fill failed",
                    turn = 2,
                ),
            )

            val events = runner.events(status.id)
            val call = events.last { it.type == AgentRunEvent.TOOL_CALL }
            assertFalse(
                call.arguments!!.contains("hunter2"),
                "a typed credential must not reach the event log: ${call.arguments}",
            )
            assertEquals(2, call.turn)

            val result = events.last { it.type == AgentRunEvent.TOOL_RESULT }
            assertEquals(false, result.ok)
            assertEquals(42L, result.durationMs)
            assertEquals("fill failed", result.text)
        } finally {
            runner.close()
        }
    }

    @Test
    @DisplayName("a terminal task leaves no steering queue behind")
    fun terminalTaskDoesNotLeakSteeringQueue() = runBlocking {
        val agent = mockAgent(AgentHistory(), "run-1")
        coEvery { agent.run(any<String>()) } returns AgentHistory()
        val runner = StatefulAgentRunner(mockSession(agent))
        try {
            val status = runner.execute("finish quickly")
            // The queue is dropped when the run ends, so a message typed after the
            // completion can never be drained by a later task of the same session.
            assertFalse(runner.enqueueInput(status.id, "late"))
            assertTrue(runner.drainInputs(status.id).isEmpty())
        } finally {
            runner.close()
        }
    }
}
