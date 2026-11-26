package ai.platon.pulsar.rest.api.service

import ai.platon.pulsar.agent.tool.UserCommandExecutor
import ai.platon.pulsar.agentic.tools.advanced.agent.AgentSessionContext
import ai.platon.pulsar.agentic.tools.advanced.agent.AgentSessionTurn
import ai.platon.pulsar.agentic.tools.advanced.agent.AgentTaskStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.Mockito.`when`
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import java.time.Instant

/**
 * The service half of the session sidebar: collecting the two stores, the short reuse
 * cache, and the failure behaviour that must never take the console down.
 */
@DisplayName("agent sessions service")
class AgentSessionsServiceTest {

    private lateinit var commandExecutor: UserCommandExecutor
    private lateinit var sessionIndex: AgentSessionIndexProvider
    private lateinit var service: AgentSessionsService

    @BeforeEach
    fun setUp() {
        commandExecutor = Mockito.mock(UserCommandExecutor::class.java)
        sessionIndex = Mockito.mock(AgentSessionIndexProvider::class.java)
        service = AgentSessionsService(commandExecutor, sessionIndex)
    }

    private fun status(
        id: String,
        sessionId: String?,
        workspace: String? = null,
        explicit: Boolean = false,
        minutesAgo: Long = 0,
    ): AgentTaskStatus = AgentTaskStatus(id = id, statusCode = 200, processState = "done").apply {
        this.sessionId = sessionId
        this.workspace = workspace
        this.workspaceExplicit = explicit
        this.submittedTask = "task $id"
        val at = Instant.now().minusSeconds(minutesAgo * 60)
        this.createdTime = at
        this.startedTime = at
        this.lastModifiedTime = at
        this.finishTime = at
    }

    @Test
    @DisplayName("the tree is built from both stores and reused for the poll interval")
    fun sessionsAreGroupedAndReused() {
        `when`(commandExecutor.agentTaskStatuses()).thenReturn(
            listOf(status("t1", "s-1", workspace = "/work/corpus", explicit = true)),
        )
        `when`(commandExecutor.liveAgentSessionIds()).thenReturn(setOf("s-1"))
        `when`(sessionIndex.list()).thenReturn(
            listOf(
                AgentSessionContext(
                    sessionId = "s-1",
                    updatedAt = System.currentTimeMillis(),
                    taskCount = 1,
                    compressed = null,
                    turns = listOf(AgentSessionTurn("task", "collect")),
                ),
            ),
        )

        val first = service.sessions()
        val second = service.sessions()

        assertEquals(first, second, "a poll inside the reuse window sees the same tree")
        val group = first.workspaces.single()
        assertEquals("/work/corpus", group.path)
        assertTrue(group.sessions.single().live)
        assertEquals(1, group.sessions.single().turnCount)
        verify(commandExecutor, times(1)).agentTaskStatuses()

        // An explicit refresh re-reads the registry.
        service.sessions(refresh = true)
        verify(commandExecutor, times(2)).agentTaskStatuses()
    }

    @Test
    @DisplayName("an unreadable registry yields an empty tree instead of failing the console")
    fun unreadableRegistryYieldsAnEmptyTree() {
        `when`(commandExecutor.agentTaskStatuses()).thenThrow(RuntimeException("registry unreadable"))
        `when`(commandExecutor.liveAgentSessionIds()).thenReturn(emptySet())
        `when`(sessionIndex.list()).thenReturn(emptyList())

        val view = service.sessions()

        assertTrue(view.workspaces.isEmpty())
    }

    @Test
    @DisplayName("an unknown session is a real 404, a known one restores its turns and tasks")
    fun contextIsOnlyBuiltForKnownSessions() {
        `when`(commandExecutor.agentTaskStatuses()).thenReturn(
            listOf(status("t1", "s-1", workspace = "D:/corpus", explicit = true)),
        )
        `when`(commandExecutor.liveAgentSessionIds()).thenReturn(emptySet())
        `when`(sessionIndex.of("s-1")).thenReturn(
            AgentSessionContext(
                sessionId = "s-1",
                updatedAt = System.currentTimeMillis(),
                taskCount = 1,
                compressed = "earlier work",
                turns = listOf(AgentSessionTurn("task", "summarize"), AgentSessionTurn("outcome", "done")),
            ),
        )
        `when`(sessionIndex.of("nope")).thenReturn(null)

        val restored = service.context("s-1")
        assertEquals("D:/corpus", restored?.workspace)
        assertEquals(2, restored?.turns?.size)
        assertEquals("earlier work", restored?.compressed)
        assertEquals("t1", restored?.tasks?.single()?.id)

        assertNull(service.context("nope"))
        assertNull(service.context("   "), "a blank id is not a session")
    }
}
