package ai.platon.pulsar.rest.api.service

import ai.platon.pulsar.agentic.model.AgentHistory
import ai.platon.pulsar.agentic.model.AgentState
import ai.platon.pulsar.agentic.tools.advanced.agent.AgentSessionContext
import ai.platon.pulsar.agentic.tools.advanced.agent.AgentSessionTurn
import ai.platon.pulsar.agentic.tools.advanced.agent.AgentTaskStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * The grouping rules behind the console's session sidebar: which workspace a session is
 * filed under, what the temporary pile means, and what a restored context carries.
 */
@DisplayName("agent session grouping")
class AgentSessionGroupingTest {

    private fun status(
        taskId: String,
        sessionId: String?,
        workspace: String? = null,
        explicit: Boolean = false,
        processState: String = "done",
        submittedTask: String? = "task $taskId",
        minutesAgo: Long = 0,
        deliverables: List<String>? = null,
    ): AgentTaskStatus = AgentTaskStatus(
        id = taskId,
        statusCode = if (processState == "done") 200 else 201,
        processState = processState,
    ).apply {
        this.sessionId = sessionId
        this.workspace = workspace
        this.workspaceExplicit = explicit
        this.submittedTask = submittedTask
        this.deliverables = deliverables
        val at = Instant.now().minusSeconds(minutesAgo * 60)
        this.createdTime = at
        this.startedTime = at
        this.lastModifiedTime = at
        if (processState == "done") this.finishTime = at
    }

    private fun context(
        sessionId: String,
        minutesAgo: Long = 0,
        turns: List<AgentSessionTurn> = emptyList(),
        taskCount: Int = 0,
    ) = AgentSessionContext(
        sessionId = sessionId,
        updatedAt = Instant.now().minusSeconds(minutesAgo * 60).toEpochMilli(),
        taskCount = taskCount,
        compressed = null,
        turns = turns,
    )

    @Test
    @DisplayName("durable workspaces come first; the temporary pile is last and last-resort")
    fun durableWorkspacesComeFirstAndTemporaryLast() {
        val view = AgentSessionGrouping.group(
            statuses = listOf(
                status("t1", "s-temp", workspace = "/data/agent/workspace.t1"),
                status("t2", "s-work", workspace = "/work/corpus", explicit = true),
            ),
            contexts = emptyList(),
            liveSessions = emptySet(),
        )

        assertEquals(listOf("/work/corpus", null), view.workspaces.map { it.path })
        assertFalse(view.workspaces[0].temporary)
        assertTrue(view.workspaces[1].temporary)
        assertEquals(AgentSessionGrouping.TEMPORARY_GROUP_NAME, view.workspaces[1].name)
        assertEquals(listOf("s-work"), view.workspaces[0].sessions.map { it.sessionId })
        assertEquals(listOf("s-temp"), view.workspaces[1].sessions.map { it.sessionId })
    }

    @Test
    @DisplayName("a session is filed under the newest task that named a workspace, never scattered")
    fun sessionFiledUnderNewestExplicitWorkspace() {
        val view = AgentSessionGrouping.group(
            statuses = listOf(
                // The newest task of the session did NOT name a directory...
                status("t-new", "s-1", workspace = "/data/agent/workspace.t-new", minutesAgo = 0),
                // ...but an earlier one did, and the conversation worked there.
                status("t-old", "s-1", workspace = "/work/project", explicit = true, minutesAgo = 30),
                status("t-other", "s-2", workspace = "/work/project", explicit = true, minutesAgo = 10),
            ),
            contexts = emptyList(),
            liveSessions = emptySet(),
        )

        val durable = view.workspaces.first { it.path == "/work/project" }
        assertEquals(listOf("s-1", "s-2").sorted(), durable.sessions.map { it.sessionId }.sorted())
        assertTrue(view.workspaces.none { it.temporary && it.sessions.any { s -> s.sessionId == "s-1" } })
    }

    @Test
    @DisplayName("the completion report's file count wins over the mutation log's")
    fun reportedFilesWinOverTheMutationLog() {
        // `agent result.filesChanged` shows the completion report; the sidebar must not claim
        // "0 文件" for a task that plainly wrote files, nor a different count than the result.
        val reported = status("t1", "s-1", workspace = "/work/a", explicit = true, deliverables = listOf("a", "b", "c"))
        reported.agentHistory = AgentHistory(
            mutableListOf(
                AgentState(step = 1, instruction = "write two files", filesChanged = listOf("a", "b")),
            ),
        )

        val view = AgentSessionGrouping.group(
            statuses = listOf(reported),
            contexts = emptyList(),
            liveSessions = emptySet(),
        )

        assertEquals(2, view.workspaces.single().sessions.single().tasks.single().filesChanged)
    }

    @Test
    @DisplayName("a remembered conversation with no surviving task still lists, newest activity first")
    fun conversationWithoutTasksStillLists() {
        val view = AgentSessionGrouping.group(
            statuses = listOf(
                status("t1", "s-task", workspace = "/work/a", explicit = true, minutesAgo = 20),
            ),
            contexts = listOf(
                context("s-conversation", minutesAgo = 1, taskCount = 4),
            ),
            liveSessions = setOf("s-conversation"),
        )

        val temporary = view.workspaces.first { it.temporary }
        val summary = temporary.sessions.single { it.sessionId == "s-conversation" }
        assertTrue(summary.live)
        assertEquals(4, summary.taskCount)
        assertNull(summary.lastTask)
        // The session with a conversation 1 minute ago sorts before the 20-minute-old one.
        assertEquals("s-conversation", temporary.sessions.first().sessionId)
    }

    @Test
    @DisplayName("task rows are newest first and keep their own directory")
    fun tasksAreNewestFirstAndKeepTheirOwnWorkspace() {
        val view = AgentSessionGrouping.group(
            statuses = listOf(
                status(
                    "t-old", "s-1", workspace = "/work/a", explicit = true, minutesAgo = 20,
                    deliverables = listOf("out/a.csv"),
                ),
                status(
                    "t-running", "s-1", workspace = "/data/agent/workspace.t-running",
                    processState = "in_progress", minutesAgo = 0,
                ),
            ),
            contexts = emptyList(),
            liveSessions = setOf("s-1"),
        )

        val session = view.workspaces.first { it.path == "/work/a" }.sessions.single()
        assertEquals(listOf("t-running", "t-old"), session.tasks.map { it.id })
        assertEquals("t-running", session.lastTask?.id)
        assertTrue(session.running)
        assertEquals("/data/agent/workspace.t-running", session.tasks[0].workspace)
        assertFalse(session.tasks[0].workspaceExplicit)
        assertEquals(1, session.tasks[1].filesChanged)
    }

    @Test
    @DisplayName("a restored context carries the turns and every task, with a 404-shaped answer for unknown ids")
    fun contextRestoresTurnsAndTasks() {
        val conversation = context(
            "s-1",
            minutesAgo = 2,
            taskCount = 2,
            turns = listOf(
                AgentSessionTurn("task", "collect the prices"),
                AgentSessionTurn("tool", "browser_open(https://example.com)"),
                AgentSessionTurn("outcome", "done in 41s, 3 files"),
            ),
        )

        val restored = AgentSessionGrouping.contextOf(
            sessionId = "s-1",
            statuses = listOf(
                status("t1", "s-1", workspace = "/work/corpus", explicit = true, submittedTask = "collect the prices"),
            ),
            conversation = conversation,
            live = true,
        )

        assertEquals("s-1", restored.sessionId)
        assertEquals("/work/corpus", restored.workspace)
        assertFalse(restored.temporary)
        assertTrue(restored.live)
        assertEquals(3, restored.turns.size)
        assertEquals("task", restored.turns[0].role)
        assertEquals("collect the prices", restored.tasks.single().instruction)
        assertEquals(2, restored.taskCount, "the conversation remembers two tasks even with one row left")

        // Windows paths are one group whatever their separator/case spelling.
        val windows = AgentSessionGrouping.group(
            statuses = listOf(
                status("t1", "s-1", workspace = "D:\\Work\\Corpus", explicit = true),
                status("t2", "s-2", workspace = "d:/work/corpus/", explicit = true),
            ),
            contexts = emptyList(),
            liveSessions = emptySet(),
        )
        assertEquals(1, windows.workspaces.size)
        assertEquals(listOf("s-1", "s-2").sorted(), windows.workspaces.single().sessions.map { it.sessionId }.sorted())
    }

    @Test
    @DisplayName("a task row carries its model-written title and falls back to the instruction")
    fun taskRowsCarryTheTitle() {
        val titled = status(
            "t-titled", "s-1", workspace = "/work/a", explicit = true,
            submittedTask = "collect the prices of every microwave on the first page",
        )
        titled.title = "采集微波炉价格"
        val untitled = status("t-untitled", "s-1", workspace = "/work/a", explicit = true, minutesAgo = 10)

        val session = AgentSessionGrouping.group(listOf(titled, untitled), emptyList(), emptySet())
            .workspaces.single().sessions.single()

        assertEquals("采集微波炉价格", session.tasks.single { it.id == "t-titled" }.title)
        assertNull(session.tasks.single { it.id == "t-untitled" }.title, "an untitled task has no title, not a blank")
        assertEquals("task t-untitled", session.tasks.single { it.id == "t-untitled" }.instruction)
        assertEquals("采集微波炉价格", session.lastTask?.title, "the newest task names the session")
    }

    @Test
    @DisplayName("a session with a task but no conversation still has a task list to show")
    fun sessionWithoutConversationStillRestoresTasks() {
        val restored = AgentSessionGrouping.contextOf(
            sessionId = "s-plain",
            statuses = listOf(
                status("t1", "s-plain", submittedTask = "summarize this page"),
            ),
            conversation = null,
            live = false,
        )

        assertNotNull(restored)
        assertTrue(restored.turns.isEmpty())
        assertTrue(restored.temporary)
        assertEquals(1, restored.tasks.size)
        assertEquals("summarize this page", restored.tasks.single().instruction)
    }
}
