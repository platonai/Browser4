package ai.platon.pulsar.agentic.tools.advanced.agent

import ai.platon.pulsar.agentic.model.AgentHistory
import ai.platon.pulsar.agentic.model.AgentState
import ai.platon.pulsar.agentic.tools.advanced.common.JsonlPersistence
import ai.platon.pulsar.api.model.BrowserUseState
import ai.platon.pulsar.common.serialize.json.pulsarObjectMapper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class AgentRunnerPersistenceTest {

    private val objectMapper = pulsarObjectMapper()

    // -----------------------------------------------------------------
    // JsonlPersistence for AgentTaskStatus
    // -----------------------------------------------------------------

    @Test
    fun `restore loads agent tasks from JSONL`(@TempDir tempDir: Path) {
        val persistence = createPersistence(tempDir)
        val task1 = AgentTaskStatus(id = "a1", statusCode = 201).apply {
            startedTime = null; lastModifiedTime = null; finishTime = null
        }
        val task2 = AgentTaskStatus(id = "a2", statusCode = 200, processState = "done").apply {
            startedTime = null; lastModifiedTime = null; finishTime = null
        }
        persistence.append(task1)
        persistence.append(task2)

        val restored = mutableListOf<AgentTaskStatus>()
        val count = persistence.restore { restored.add(it) }

        assertEquals(2, count)
        assertEquals(2, restored.size)
        assertEquals("a1", restored[0].id)
        assertEquals(201, restored[0].statusCode)
        assertNotNull(restored[0].createdTime, "createdTime should survive JSONL round-trip")
        assertEquals("a2", restored[1].id)
        assertEquals("done", restored[1].processState)
        assertNotNull(restored[1].createdTime, "createdTime should survive JSONL round-trip")
    }

    @Test
    fun `restore handles missing file gracefully`(@TempDir tempDir: Path) {
        val persistence = createPersistence(tempDir)
        val restored = mutableListOf<AgentTaskStatus>()
        val count = persistence.restore { restored.add(it) }
        assertEquals(0, count)
        assertTrue(restored.isEmpty())
    }

    @Test
    fun `restore skips corrupt lines`(@TempDir tempDir: Path) {
        val persistence = createPersistence(tempDir)
        val task = AgentTaskStatus(id = "good", statusCode = 200).apply {
            startedTime = null; lastModifiedTime = null; finishTime = null
        }
        persistence.append(task)

        // Manually inject a corrupt line
        val jsonlPath = persistence.javaClass.getDeclaredField("file").let {
            it.isAccessible = true
            it.get(persistence) as Path
        }
        Files.writeString(jsonlPath, "{not valid json}\n\n", java.nio.file.StandardOpenOption.APPEND)

        val restored = mutableListOf<AgentTaskStatus>()
        val count = persistence.restore { restored.add(it) }
        assertEquals(1, count)
        assertEquals(1, restored.size)
        assertEquals("good", restored[0].id)
    }

    @Test
    fun `restore empty file returns zero tasks`(@TempDir tempDir: Path) {
        val persistence = createPersistence(tempDir)
        // Write empty file
        val jsonlPath = persistence.javaClass.getDeclaredField("file").let {
            it.isAccessible = true
            it.get(persistence) as Path
        }
        Files.createDirectories(jsonlPath.parent)
        Files.writeString(jsonlPath, "")

        val restored = mutableListOf<AgentTaskStatus>()
        val count = persistence.restore { restored.add(it) }
        assertEquals(0, count)
    }

    // -----------------------------------------------------------------
    // agentHistory survives the JSONL round-trip (restart persistence)
    // -----------------------------------------------------------------

    @Test
    fun `agent task status round-trips agent history with state fields`(@TempDir tempDir: Path) {
        val persistence = createPersistence(tempDir)
        val state = AgentState(step = 2, instruction = "open the page", browserUseState = BrowserUseState.DUMMY).apply {
            sessionId = "run-1"
            domain = "tab"
            method = "click"
            description = "clicked the button"
            thinking = "button matches the goal"
            nextGoal = "verify result"
            evaluationPreviousGoal = "success"
            summary = "done"
            isComplete = true
        }
        val task = AgentTaskStatus(id = "t1", statusCode = 200, processState = "done").apply {
            startedTime = null; lastModifiedTime = null; finishTime = null
            agentHistory = AgentHistory(mutableListOf(state))
        }
        persistence.append(task)

        val restored = mutableListOf<AgentTaskStatus>()
        val count = persistence.restore { restored.add(it) }

        assertEquals(1, count)
        val history = restored[0].agentHistory
        assertNotNull(history, "agentHistory should survive the JSONL round-trip")
        assertEquals(1, history!!.states.size)
        val rs = history.states.single()
        assertEquals(2, rs.step)
        assertEquals("run-1", rs.sessionId)
        assertEquals("tab", rs.domain)
        assertEquals("click", rs.method)
        assertEquals("success", rs.evaluationPreviousGoal)
        assertEquals(true, rs.isComplete)
        assertEquals("done", rs.summary)
    }

    @Test
    fun `agent task status round-trips agent history with failed state`(@TempDir tempDir: Path) {
        val persistence = createPersistence(tempDir)
        val state = AgentState(step = 1, instruction = "click submit", browserUseState = BrowserUseState.DUMMY).apply {
            sessionId = "run-1"
            domain = "tab"
            method = "click"
            exception = IllegalStateException("element not found")
        }
        val task = AgentTaskStatus(id = "t2", statusCode = 500, processState = "done").apply {
            startedTime = null; lastModifiedTime = null; finishTime = null
            agentHistory = AgentHistory(mutableListOf(state))
        }
        persistence.append(task)

        val restored = mutableListOf<AgentTaskStatus>()
        val count = persistence.restore { restored.add(it) }

        assertEquals(1, count)
        val rs = restored[0].agentHistory!!.states.single()
        assertEquals("element not found", rs.exception?.message)
        assertFalse(rs.isSuccess)
    }

    // -----------------------------------------------------------------
    // Helper
    // -----------------------------------------------------------------

    private fun createPersistence(tempDir: Path): JsonlPersistence<AgentTaskStatus> {
        return JsonlPersistence(
            file = tempDir.resolve("agent-tasks.jsonl"),
            clazz = AgentTaskStatus::class,
            objectMapper = objectMapper
        )
    }
}
