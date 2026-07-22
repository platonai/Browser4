package ai.platon.pulsar.agentic.tools.advanced.agent

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
}
