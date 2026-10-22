package ai.platon.pulsar.agentic.tools.advanced.agent

import ai.platon.pulsar.agentic.model.AgentHistory
import ai.platon.pulsar.agentic.model.AgentState
import ai.platon.pulsar.common.ResourceStatus
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Instant

class AgentTaskStatusTest {

    // -----------------------------------------------------------------
    // startedTime lifecycle
    // -----------------------------------------------------------------

    @Test
    fun `startedTime is null on construction`() {
        val status = AgentTaskStatus()
        assertNull(status.startedTime, "startedTime should be null before any refresh")
    }

    @Test
    fun `startedTime is set on first refresh`() {
        val status = AgentTaskStatus()
        status.refresh(ResourceStatus.SC_PROCESSING)
        assertNotNull(status.startedTime, "startedTime should be set after first refresh")
        assertEquals("in_progress", status.processState)
    }

    @Test
    fun `startedTime is preserved across multiple refreshes`() {
        val status = AgentTaskStatus()
        status.refresh(ResourceStatus.SC_PROCESSING)
        val first = status.startedTime

        Thread.sleep(5)
        status.refresh(ResourceStatus.SC_OK, isDone = true)

        assertEquals(first, status.startedTime, "startedTime should not change on subsequent refreshes")
    }

    @Test
    fun `startedTime is set by refresh with isDone`() {
        val status = AgentTaskStatus()
        assertNull(status.startedTime)
        status.refresh(isDone = true)
        assertNotNull(status.startedTime)
    }

    // -----------------------------------------------------------------
    // finishTime lifecycle
    // -----------------------------------------------------------------

    @Test
    fun `finishTime is null until done`() {
        val status = AgentTaskStatus()
        assertNull(status.finishTime)
    }

    @Test
    fun `done sets finishTime and startedTime`() {
        val status = AgentTaskStatus()
        status.done()
        assertNotNull(status.finishTime, "done() should set finishTime")
        assertNotNull(status.startedTime, "done() should set startedTime if not already set")
        assertEquals("done", status.processState)
    }

    @Test
    fun `done preserves existing startedTime`() {
        val status = AgentTaskStatus()
        status.refresh(ResourceStatus.SC_PROCESSING)
        val first = status.startedTime

        Thread.sleep(5)
        status.done()

        assertEquals(first, status.startedTime, "done() should not overwrite startedTime")
        assertNotNull(status.finishTime)
    }

    @Test
    fun `failed sets finishTime through refresh`() {
        val status = AgentTaskStatus()
        status.failed(ResourceStatus.SC_EXPECTATION_FAILED)
        assertTrue(status.isDone)
        assertEquals("done", status.processState)
    }

    // -----------------------------------------------------------------
    // lastModifiedTime lifecycle
    // -----------------------------------------------------------------

    @Test
    fun `lastModifiedTime is null before any refresh`() {
        val status = AgentTaskStatus()
        assertNull(status.lastModifiedTime)
    }

    @Test
    fun `lastModifiedTime advances on each refresh`() {
        val status = AgentTaskStatus()

        status.refresh(ResourceStatus.SC_PROCESSING)
        val first = status.lastModifiedTime
        assertNotNull(first)

        Thread.sleep(5)
        status.refresh(ResourceStatus.SC_OK)
        val second = status.lastModifiedTime
        assertNotNull(second)
        assertTrue(second!!.isAfter(first), "lastModifiedTime should advance")
    }

    @Test
    fun `emitEvent advances lastModifiedTime`() {
        val status = AgentTaskStatus()
        status.refresh(ResourceStatus.SC_PROCESSING)
        val before = status.lastModifiedTime

        Thread.sleep(5)
        status.emitEvent("onToolExecuted")

        assertEquals("Agent is working", status.message)
        assertNotNull(status.lastModifiedTime)
        assertTrue(
            status.lastModifiedTime!!.isAfter(before),
            "a progress event is a status change: without the bump the SSE stream " +
                "stayed silent for the whole run"
        )
    }

    // -----------------------------------------------------------------
    // processState transitions
    // -----------------------------------------------------------------

    @Test
    fun `processState starts as created`() {
        val status = AgentTaskStatus()
        assertEquals("created", status.processState)
    }

    @Test
    fun `refresh changes processState to in_progress`() {
        val status = AgentTaskStatus()
        status.refresh(ResourceStatus.SC_PROCESSING)
        assertEquals("in_progress", status.processState)
    }

    @Test
    fun `refresh with isDone changes processState to done`() {
        val status = AgentTaskStatus()
        status.refresh(isDone = true)
        assertEquals("done", status.processState)
    }

    @Test
    fun `failed sets processState to done`() {
        val status = AgentTaskStatus()
        status.failed(ResourceStatus.SC_EXPECTATION_FAILED)
        assertEquals("done", status.processState)
    }

    // -----------------------------------------------------------------
    // isDone computed property
    // -----------------------------------------------------------------

    @Test
    fun `isDone is true when processState is done`() {
        val status = AgentTaskStatus(processState = "done")
        assertTrue(status.isDone)
    }

    @Test
    fun `isDone is false when processState is created`() {
        val status = AgentTaskStatus(processState = "created")
        assertFalse(status.isDone)
    }

    @Test
    fun `isDone is false when processState is in_progress`() {
        val status = AgentTaskStatus(processState = "in_progress")
        assertFalse(status.isDone)
    }

    // -----------------------------------------------------------------
    // lastActivity: TTL is judged by the last write, not creation
    // -----------------------------------------------------------------

    @Test
    fun `lastActivity falls back to createdTime before any refresh`() {
        val status = AgentTaskStatus()
        assertEquals(status.createdTime, status.lastActivity())
    }

    @Test
    fun `lastActivity follows lastModifiedTime after a refresh`() {
        val status = AgentTaskStatus()
        val created = status.createdTime

        Thread.sleep(5)
        status.refresh(ResourceStatus.SC_PROCESSING)

        assertTrue(status.lastActivity().isAfter(created), "lastActivity must move with the latest write")
        assertEquals(status.lastModifiedTime, status.lastActivity())
    }

    // -----------------------------------------------------------------
    // detachedSnapshot: body-declared state must not be reset by copy()
    // -----------------------------------------------------------------

    @Test
    fun `detachedSnapshot keeps timestamps, task fields and the live tool counter`() {
        val status = AgentTaskStatus(processState = "in_progress", statusCode = ResourceStatus.SC_PROCESSING)
        status.refresh(ResourceStatus.SC_PROCESSING)
        status.submittedTask = "refactor Browser4WebDriver"
        status.workspace = "/workspace"
        status.toolExecutions = 42
        status.agentHistory = AgentHistory(
            mutableListOf(AgentState(step = 1, instruction = "refactor", sessionId = "run-1"))
        )

        val snapshot = status.detachedSnapshot()

        // data class copy() only carries constructor properties: createdTime,
        // startedTime, lastModifiedTime, submittedTask, workspace and
        // toolExecutions must be copied explicitly.
        assertEquals(status.createdTime, snapshot.createdTime)
        assertEquals(status.startedTime, snapshot.startedTime)
        assertEquals(status.lastModifiedTime, snapshot.lastModifiedTime)
        assertEquals("refactor Browser4WebDriver", snapshot.submittedTask)
        assertEquals("/workspace", snapshot.workspace)
        assertEquals(42, snapshot.toolExecutions)
        assertEquals(1, snapshot.agentHistory?.states?.size)
        assertNotSame(status.agentHistory, snapshot.agentHistory, "the history must be detached")
    }

    @Test
    fun `detachedSnapshot without history keeps the progress fields`() {
        val status = AgentTaskStatus(processState = "in_progress")
        status.refresh(ResourceStatus.SC_PROCESSING)
        status.toolExecutions = 7
        status.agentHistory = AgentHistory(mutableListOf(AgentState(step = 1, instruction = "x")))

        val snapshot = status.detachedSnapshot(includeHistory = false)

        assertNull(snapshot.agentHistory, "periodic snapshots stay small")
        assertEquals(7, snapshot.toolExecutions)
        assertEquals(status.startedTime, snapshot.startedTime)
    }
}
