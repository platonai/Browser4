package ai.platon.pulsar.agentic.memory

import ai.platon.pulsar.agentic.tools.experience.KnowledgeStore
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@DisplayName("PemKnowledgeProvider (L1 fusion)")
class PemKnowledgeProviderTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var log: AgentEventLog
    private lateinit var buffer: EventBuffer
    private lateinit var service: DefaultMemoryQueryService
    private lateinit var knowledgeStore: KnowledgeStore
    private lateinit var provider: PemKnowledgeProvider

    @BeforeEach
    fun setUp() {
        log = AgentEventLog(tempDir.resolve("events"))
        buffer = EventBuffer(1000)
        service = DefaultMemoryQueryService(log, buffer)
        knowledgeStore = KnowledgeStore(tempDir.resolve("knowledge"))
        knowledgeStore.initializeStore()
        provider = PemKnowledgeProvider(knowledgeStore, service, minPromoteIntervalMinutes = 0)
    }

    @AfterEach
    fun tearDown() {
        try { tempDir.toFile().deleteRecursively() } catch (_: Exception) {}
    }

    private fun seedTask(taskId: String, instruction: String, outcome: String = "success") {
        val sink = AgentMemorySink(log, buffer)
        sink.taskStarted(taskId, "a1", instruction, "cli", "https://example.com/dp/12345")
        sink.toolExecuted(taskId, "a1", "htmlsnapshot.get", """{"url":"https://example.com/dp/12345"}""", true, "ok", 5)
        sink.toolExecuted(taskId, "a1", "tab.extract", "{}", true, "title=Product", 8)
        when (outcome) {
            "success" -> sink.completed(taskId, "a1", "extracted product title", listOf("title"))
            "failure" -> sink.failed(taskId, "a1", "selector #title not found", "SELECTOR_DRIFT", 2)
        }
    }

    @Test
    @DisplayName("deposit folds L0 events into a PEM trace (success)")
    fun testDepositSuccess() = runBlocking {
        seedTask("t1", "extract product title", "success")
        assertTrue(provider.deposit("t1", MemoryScope(agentUuid = "a1")))

        val domain = "example.com"
        val stats = knowledgeStore.loadStats(domain, "extract")
        assertEquals(1, stats.successes)
        // Trace file exists
        val traces = knowledgeStore.listTraces(domain, page = 1, pageSize = 5)
        assertEquals(1, traces.size)
    }

    @Test
    @DisplayName("deposit is idempotent per task")
    fun testDepositIdempotent() = runBlocking {
        seedTask("t1", "extract product title", "success")
        assertTrue(provider.deposit("t1", MemoryScope(agentUuid = "a1")))
        assertFalse(provider.deposit("t1", MemoryScope(agentUuid = "a1"))) // second call no-op

        val stats = knowledgeStore.loadStats("example.com", "extract")
        assertEquals(1, stats.successes)
    }

    @Test
    @DisplayName("deposit records failure traces with a failure category")
    fun testDepositFailure() = runBlocking {
        seedTask("t1", "extract product title", "failure")
        assertTrue(provider.deposit("t1", MemoryScope(agentUuid = "a1")))

        val stats = knowledgeStore.loadStats("example.com", "extract")
        assertEquals(1, stats.failures)
    }

    @Test
    @DisplayName("deposit skips tasks without a usable URL")
    fun testDepositNoUrl() = runBlocking {
        val sink = AgentMemorySink(log, buffer)
        sink.taskStarted("t1", "a1", "do a local thing", "cli", null)
        sink.completed("t1", "a1", "done", null)
        assertFalse(provider.deposit("t1", MemoryScope(agentUuid = "a1")))
    }

    @Test
    @DisplayName("query returns knowledge hits and a rendered section")
    fun testQuery() = runBlocking {
        seedTask("t1", "extract product title", "success")
        provider.deposit("t1", MemoryScope(agentUuid = "a1"))

        val hits = provider.query("extract the product title", "https://example.com/dp/99999", MemoryScope(agentUuid = "a1"))
        assertTrue(hits.hits.isNotEmpty())
        assertTrue(hits.rendered.contains("[L1]"))
        assertTrue(hits.rendered.contains("置信度"))
    }

    @Test
    @DisplayName("query returns empty without a URL (cold start stays cheap)")
    fun testQueryNoUrl() = runBlocking {
        seedTask("t1", "extract product title", "success")
        provider.deposit("t1", MemoryScope(agentUuid = "a1"))
        val hits = provider.query("extract", null, MemoryScope(agentUuid = "a1"))
        assertEquals(0, hits.hits.size)
    }
}
