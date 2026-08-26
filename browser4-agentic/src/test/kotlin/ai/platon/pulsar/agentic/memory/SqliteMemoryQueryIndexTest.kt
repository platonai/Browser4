package ai.platon.pulsar.agentic.memory

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@DisplayName("SqliteMemoryQueryIndex (FTS5)")
class SqliteMemoryQueryIndexTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var log: AgentEventLog
    private lateinit var buffer: EventBuffer
    private lateinit var index: SqliteMemoryQueryIndex
    private lateinit var service: DefaultMemoryQueryService

    @BeforeEach
    fun setUp() {
        log = AgentEventLog(tempDir.resolve("events"))
        buffer = EventBuffer(1000)
        index = SqliteMemoryQueryIndex(tempDir.resolve("memory-index.sqlite"), log)
        service = DefaultMemoryQueryService(log, buffer, index = index)
    }

    @AfterEach
    fun tearDown() {
        index.close()
        try { tempDir.toFile().deleteRecursively() } catch (_: Exception) {}
    }

    private fun seedTask(agent: String, taskId: String, instruction: String, outcome: String = "success") {
        val sink = AgentMemorySink(log, buffer)
        sink.taskStarted(taskId, agent, instruction, "cli", "https://example.com/p")
        sink.toolExecuted(taskId, agent, "htmlsnapshot.get", """{"url":"https://example.com/p"}""", true, "ok", 5)
        if (outcome == "success") sink.completed(taskId, agent, "done: $instruction")
        else sink.failed(taskId, agent, "selector not found", "SELECTOR_DRIFT", 1)
    }

    @Test
    @DisplayName("sync then FTS search finds keyword hits")
    fun testSearchAfterSync() = runBlocking {
        seedTask("a1", "t1", "extract amazon price", "success")
        seedTask("a1", "t2", "fill login form", "failure")

        val page = service.searchEvents("amazon price")
        assertEquals(1, page.hits.size)
        assertEquals("t1", page.hits[0].taskId)
        assertTrue(page.hits[0].snippet.isNotBlank())
    }

    @Test
    @DisplayName("incremental sync aligns only new events (watermark)")
    fun testIncrementalSync() {
        seedTask("a1", "t1", "extract amazon price", "success")
        // The first sync returns the global watermark (JVM-wide event seq —
        // other test classes consume seq numbers too), so only relative
        // progress is asserted.
        val w1 = index.sync(log.readAll(null))
        assertTrue(w1 > 0)

        seedTask("a1", "t2", "fill login form", "failure")
        val w2 = index.sync(log.readSince(w1))
        assertTrue(w2 > w1)
        assertEquals(3, w2 - w1) // only the new task's 3 events
    }

    @Test
    @DisplayName("per-agent isolation: filter by agent uuid")
    fun testAgentIsolation() = runBlocking {
        seedTask("a1", "t1", "extract amazon price", "success")
        seedTask("a2", "t2", "extract amazon price too", "success")

        val a1 = service.searchEvents("amazon", SearchFilters(agentUuid = "a1"))
        assertEquals(listOf("t1"), a1.hits.map { it.taskId })
        val a2 = service.searchEvents("amazon", SearchFilters(agentUuid = "a2"))
        assertEquals(listOf("t2"), a2.hits.map { it.taskId })
    }

    @Test
    @DisplayName("outcome filter narrows hits")
    fun testOutcomeFilter() = runBlocking {
        seedTask("a1", "t1", "extract amazon price", "success")
        seedTask("a1", "t2", "extract amazon price again", "failure")

        val ok = service.searchEvents("amazon", SearchFilters(outcome = "success"))
        assertEquals(listOf("t1"), ok.hits.map { it.taskId })
        val bad = service.searchEvents("amazon", SearchFilters(outcome = "failure"))
        assertEquals(listOf("t2"), bad.hits.map { it.taskId })
    }

    @Test
    @DisplayName("searchTasks matches task-level text")
    fun testSearchTasks() = runBlocking {
        seedTask("a1", "t1", "extract amazon price", "success")
        seedTask("a1", "t2", "fill login form", "failure")

        val page = service.searchTasks("login form")
        assertEquals(listOf("t2"), page.hits.map { it.taskId })
    }

    @Test
    @DisplayName("pagination via cursor offset")
    fun testPagination() = runBlocking {
        repeat(5) { seedTask("a1", "t$it", "extract item number $it", "success") }
        val page1 = service.searchEvents("extract item", limit = 2)
        assertEquals(2, page1.hits.size)
        val page2 = service.searchEvents("extract item", cursor = page1.nextCursor, limit = 2)
        assertEquals(2, page2.hits.size)
        assertTrue(page2.hits.none { it.taskId in page1.hits.map { h -> h.taskId } })
    }

    @Test
    @DisplayName("forget removes rows from the index")
    fun testForget() = runBlocking {
        seedTask("a1", "t1", "extract amazon price", "success")
        seedTask("a1", "t2", "fill login form", "failure")
        service.forget("t1")

        val page = service.searchEvents("amazon")
        assertEquals(0, page.hits.size)
    }

    @Test
    @DisplayName("rebuild regenerates the index from the log")
    fun testRebuild() = runBlocking {
        seedTask("a1", "t1", "extract amazon price", "success")
        val page1 = service.searchEvents("amazon")
        assertEquals(1, page1.hits.size)

        index.rebuild()
        val page2 = service.searchEvents("amazon")
        assertEquals(1, page2.hits.size)
        assertEquals(page1.hits[0].taskId, page2.hits[0].taskId)
    }

    @Test
    @DisplayName("index file is disposable — delete and search still works (log is authoritative)")
    fun testDisposableIndex() = runBlocking {
        seedTask("a1", "t1", "extract amazon price", "success")
        assertEquals(1, service.searchEvents("amazon").hits.size)

        // Simulate index loss: new index instance over the same log.
        index.close()
        Files.deleteIfExists(tempDir.resolve("memory-index.sqlite"))
        Files.deleteIfExists(tempDir.resolve("memory-index.sqlite-wal"))
        Files.deleteIfExists(tempDir.resolve("memory-index.sqlite-shm"))

        val fresh = SqliteMemoryQueryIndex(tempDir.resolve("memory-index.sqlite"), log)
        try {
            val freshService = DefaultMemoryQueryService(log, buffer, index = fresh)
            val page = freshService.searchEvents("amazon")
            assertEquals(1, page.hits.size)
        } finally {
            fresh.close()
        }
    }
}
