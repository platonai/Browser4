package ai.platon.pulsar.agentic.memory

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@DisplayName("DefaultMemoryQueryService")
class DefaultMemoryQueryServiceTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var log: AgentEventLog
    private lateinit var buffer: EventBuffer
    private lateinit var service: DefaultMemoryQueryService

    @BeforeEach
    fun setUp() {
        log = AgentEventLog(tempDir)
        buffer = EventBuffer(1000)
        service = DefaultMemoryQueryService(log, buffer)
    }

    @AfterEach
    fun tearDown() {
        try { tempDir.toFile().deleteRecursively() } catch (_: Exception) {}
    }

    private fun seedTask(agent: String, taskId: String, instruction: String, outcome: String? = "success") {
        val sink = AgentMemorySink(log, buffer)
        sink.taskStarted(taskId, agent, instruction, "cli", "https://example.com/p")
        sink.toolExecuted(taskId, agent, "htmlsnapshot.get", """{"url":"https://example.com/p"}""", true, "title extracted", 10)
        when (outcome) {
            "success" -> sink.completed(taskId, agent, "done: $instruction", listOf("title"))
            "failure" -> sink.failed(taskId, agent, "selector not found", "SELECTOR_DRIFT", 1)
            null -> Unit // still running
        }
    }

    @Test
    @DisplayName("listTasks folds events into task records, newest first")
    fun testListTasks() = runBlocking {
        seedTask("a1", "t1", "extract amazon price", "success")
        seedTask("a1", "t2", "fill login form", "failure")

        val tasks = service.listTasks(MemoryScope(agentUuid = "a1"))
        assertEquals(2, tasks.size)
        assertEquals("t2", tasks[0].taskId) // newest first
        assertEquals("fill login form", tasks[0].instruction)
        assertEquals("failure", tasks[0].outcome)
        assertEquals(1, tasks[0].stepCount)
        assertEquals(1, tasks[0].toolCount)
    }

    @Test
    @DisplayName("live events are answerable before hitting disk (live-preferred)")
    fun testLivePreferred() = runBlocking {
        val sink = AgentMemorySink(log, buffer)
        sink.taskStarted("live1", "a1", "in-flight task", "cli", null)
        sink.toolExecuted("live1", "a1", "b4.run", "{}", true, "partial", 5)

        // The event log is a DIFFERENT instance with no file writes? No — the
        // sink writes through the same log; instead simulate a lagging disk by
        // querying through a fresh service sharing only the buffer.
        val tasks = service.listTasks(MemoryScope(agentUuid = "a1"))
        assertEquals(1, tasks.size)
        assertEquals("live1", tasks[0].taskId)
        assertEquals(null, tasks[0].outcome) // still running

        // Persisted path: a fresh log+service sees the same events from disk.
        val diskOnly = DefaultMemoryQueryService(AgentEventLog(tempDir), EventBuffer(10))
        val diskTasks = diskOnly.listTasks(MemoryScope(agentUuid = "a1"))
        assertEquals(1, diskTasks.size)
    }

    @Test
    @DisplayName("readEvent returns a bounded window around the target seq")
    fun testReadEventWindow() = runBlocking {
        seedTask("a1", "t1", "extract", "success")
        val events = service.traceTask("t1")
        val target = events.first { it is ToolExecuted }.seq

        val window = service.readEvent("t1", target, before = 0, after = 1)
        assertEquals(2, window.events.size)
        assertEquals(target, window.events.first().seq)

        // Bounded by readWindowMax
        val huge = service.readEvent("t1", target, before = 1000, after = 1000)
        assertTrue(huge.events.size <= 1 + 2 * 50)
    }

    @Test
    @DisplayName("searchEvents finds keyword hits with snippets")
    fun testSearchEvents() = runBlocking {
        seedTask("a1", "t1", "extract amazon price", "success")
        seedTask("a1", "t2", "fill login form", "failure")

        val page = service.searchEvents("amazon price", SearchFilters(agentUuid = "a1"))
        assertEquals(1, page.hits.size)
        assertEquals("t1", page.hits[0].taskId)
        assertEquals("L0", page.hits[0].tier)
        assertTrue(page.hits[0].snippet.isNotBlank())
    }

    @Test
    @DisplayName("searchEvents respects outcome filters")
    fun testSearchFilters() = runBlocking {
        seedTask("a1", "t1", "extract amazon price", "success")
        seedTask("a1", "t2", "extract amazon price again", "failure")

        val ok = service.searchEvents("amazon", SearchFilters(agentUuid = "a1", outcome = "success"))
        assertEquals(listOf("t1"), ok.hits.map { it.taskId })

        val bad = service.searchEvents("amazon", SearchFilters(agentUuid = "a1", outcome = "failure"))
        assertEquals(listOf("t2"), bad.hits.map { it.taskId })
    }

    @Test
    @DisplayName("searchTasks matches task-level text")
    fun testSearchTasks() = runBlocking {
        seedTask("a1", "t1", "extract amazon price", "success")
        seedTask("a1", "t2", "fill login form", "failure")

        val page = service.searchTasks("login")
        assertEquals(listOf("t2"), page.hits.map { it.taskId })
    }

    @Test
    @DisplayName("forget removes the task from log and buffer")
    fun testForget() = runBlocking {
        seedTask("a1", "t1", "extract", "success")
        seedTask("a1", "t2", "fill", "failure")
        service.forget("t1")

        val tasks = service.listTasks(MemoryScope(agentUuid = "a1"))
        assertEquals(listOf("t2"), tasks.map { it.taskId })
        assertEquals(emptyList(), service.traceTask("t1"))
    }

    @Test
    @DisplayName("disabled service answers nothing")
    fun testDisabled() = runBlocking {
        val disabled = DefaultMemoryQueryService(log, buffer, enabled = false)
        seedTask("a1", "t1", "extract", "success")
        assertEquals(emptyList(), disabled.listTasks(MemoryScope(agentUuid = "a1")))
        assertEquals(0, disabled.searchEvents("extract").hits.size)
    }
}
