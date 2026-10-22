package ai.platon.pulsar.agentic.tools.advanced.agent

import ai.platon.pulsar.agentic.model.AgentHistory
import ai.platon.pulsar.agentic.model.AgentState
import ai.platon.pulsar.common.ResourceStatus
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Instant

@DisplayName("agent task status lifecycle")
class AgentTaskStatusTest {

    // -----------------------------------------------------------------
    // startedTime lifecycle
    // -----------------------------------------------------------------

    @Test
    @DisplayName("startedTime is null on construction")
    fun startedTimeIsNullOnConstruction() {
        val status = AgentTaskStatus()
        assertNull(status.startedTime, "startedTime should be null before any refresh")
    }

    @Test
    @DisplayName("startedTime is set on first refresh")
    fun startedTimeIsSetOnFirstRefresh() {
        val status = AgentTaskStatus()
        status.refresh(ResourceStatus.SC_PROCESSING)
        assertNotNull(status.startedTime, "startedTime should be set after first refresh")
        assertEquals("in_progress", status.processState)
    }

    @Test
    @DisplayName("startedTime is preserved across multiple refreshes")
    fun startedTimeIsPreservedAcrossMultipleRefreshes() {
        val status = AgentTaskStatus()
        status.refresh(ResourceStatus.SC_PROCESSING)
        val first = status.startedTime

        Thread.sleep(5)
        status.refresh(ResourceStatus.SC_OK, isDone = true)

        assertEquals(first, status.startedTime, "startedTime should not change on subsequent refreshes")
    }

    @Test
    @DisplayName("startedTime is set by refresh with isDone")
    fun startedTimeIsSetByRefreshWithIsDone() {
        val status = AgentTaskStatus()
        assertNull(status.startedTime)
        status.refresh(isDone = true)
        assertNotNull(status.startedTime)
    }

    // -----------------------------------------------------------------
    // finishTime lifecycle
    // -----------------------------------------------------------------

    @Test
    @DisplayName("finishTime is null until done")
    fun finishTimeIsNullUntilDone() {
        val status = AgentTaskStatus()
        assertNull(status.finishTime)
    }

    @Test
    @DisplayName("done sets finishTime and startedTime")
    fun doneSetsFinishTimeAndStartedTime() {
        val status = AgentTaskStatus()
        status.done()
        assertNotNull(status.finishTime, "done() should set finishTime")
        assertNotNull(status.startedTime, "done() should set startedTime if not already set")
        assertEquals("done", status.processState)
    }

    @Test
    @DisplayName("done preserves existing startedTime")
    fun donePreservesExistingStartedTime() {
        val status = AgentTaskStatus()
        status.refresh(ResourceStatus.SC_PROCESSING)
        val first = status.startedTime

        Thread.sleep(5)
        status.done()

        assertEquals(first, status.startedTime, "done() should not overwrite startedTime")
        assertNotNull(status.finishTime)
    }

    @Test
    @DisplayName("failed sets finishTime through refresh")
    fun failedSetsFinishTimeThroughRefresh() {
        val status = AgentTaskStatus()
        status.failed(ResourceStatus.SC_EXPECTATION_FAILED)
        assertTrue(status.isDone)
        assertEquals("done", status.processState)
    }

    // -----------------------------------------------------------------
    // lastModifiedTime lifecycle
    // -----------------------------------------------------------------

    @Test
    @DisplayName("lastModifiedTime is null before any refresh")
    fun lastModifiedTimeIsNullBeforeAnyRefresh() {
        val status = AgentTaskStatus()
        assertNull(status.lastModifiedTime)
    }

    @Test
    @DisplayName("lastModifiedTime advances on each refresh")
    fun lastModifiedTimeAdvancesOnEachRefresh() {
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
    @DisplayName("emitEvent advances lastModifiedTime")
    fun emitEventAdvancesLastModifiedTime() {
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
    @DisplayName("processState starts as created")
    fun processStateStartsAsCreated() {
        val status = AgentTaskStatus()
        assertEquals("created", status.processState)
    }

    @Test
    @DisplayName("refresh changes processState to in_progress")
    fun refreshChangesProcessStateToInProgress() {
        val status = AgentTaskStatus()
        status.refresh(ResourceStatus.SC_PROCESSING)
        assertEquals("in_progress", status.processState)
    }

    @Test
    @DisplayName("refresh with isDone changes processState to done")
    fun refreshWithIsDoneChangesProcessStateToDone() {
        val status = AgentTaskStatus()
        status.refresh(isDone = true)
        assertEquals("done", status.processState)
    }

    @Test
    @DisplayName("failed sets processState to done")
    fun failedSetsProcessStateToDone() {
        val status = AgentTaskStatus()
        status.failed(ResourceStatus.SC_EXPECTATION_FAILED)
        assertEquals("done", status.processState)
    }

    // -----------------------------------------------------------------
    // isDone computed property
    // -----------------------------------------------------------------

    @Test
    @DisplayName("isDone is true when processState is done")
    fun isDoneIsTrueWhenProcessStateIsDone() {
        val status = AgentTaskStatus(processState = "done")
        assertTrue(status.isDone)
    }

    @Test
    @DisplayName("isDone is false when processState is created")
    fun isDoneIsFalseWhenProcessStateIsCreated() {
        val status = AgentTaskStatus(processState = "created")
        assertFalse(status.isDone)
    }

    @Test
    @DisplayName("isDone is false when processState is in_progress")
    fun isDoneIsFalseWhenProcessStateIsInProgress() {
        val status = AgentTaskStatus(processState = "in_progress")
        assertFalse(status.isDone)
    }

    // -----------------------------------------------------------------
    // lastActivity: TTL is judged by the last write, not creation
    // -----------------------------------------------------------------

    @Test
    @DisplayName("lastActivity falls back to createdTime before any refresh")
    fun lastActivityFallsBackToCreatedTimeBeforeAnyRefresh() {
        val status = AgentTaskStatus()
        assertEquals(status.createdTime, status.lastActivity())
    }

    @Test
    @DisplayName("lastActivity follows lastModifiedTime after a refresh")
    fun lastActivityFollowsLastModifiedTimeAfterARefresh() {
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
    @DisplayName("detachedSnapshot keeps timestamps, task fields and the live tool counter")
    fun detachedSnapshotKeepsTimestampsTaskFieldsAndTheLiveToolCounter() {
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
    @DisplayName("detachedSnapshot without history keeps the progress fields")
    fun detachedSnapshotWithoutHistoryKeepsTheProgressFields() {
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
