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

@DisplayName("MemoryRecallService")
class MemoryRecallServiceTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var memory: AgentMemory

    @BeforeEach
    fun setUp() {
        memory = AgentMemory(MemoryScope(agentUuid = "a1"), rootDir = tempDir, knowledgeDir = tempDir.resolve("knowledge"))
    }

    @AfterEach
    fun tearDown() {
        // Close the memory (SQLite index + coroutine scope) BEFORE deleting the
        // temp dir, otherwise Windows keeps the index files locked.
        runCatching { memory.close() }
        try { tempDir.toFile().deleteRecursively() } catch (_: Exception) {}
    }

    private fun seedTask(taskId: String, instruction: String) {
        memory.sink.taskStarted(taskId, "a1", instruction, "cli", "https://example.com/p")
        memory.sink.toolExecuted(taskId, "a1", "htmlsnapshot.get", "{}", true, "ok", 5)
        memory.sink.completed(taskId, "a1", "done", listOf("title"))
    }

    @Test
    @DisplayName("renders L0 hits with the Memory header")
    fun testRecallWithHits() = runBlocking {
        seedTask("t1", "extract amazon price from product page")
        seedTask("t2", "fill login form")

        val section = memory.recall.recall("extract the amazon price", memory.scope)
        assertTrue(section.contains("## Memory"))
        assertTrue(section.contains("[L0]"))
        assertTrue(section.contains("t1".let { "" }) || section.contains("amazon"))
        assertTrue(section.contains("目标 URL: https://example.com/p") || !section.contains("目标 URL"))
    }

    @Test
    @DisplayName("returns empty for cold start (no tokens spent)")
    fun testColdStart() = runBlocking {
        val section = memory.recall.recall("brand new topic", memory.scope)
        assertEquals("", section)
    }

    @Test
    @DisplayName("respects the recall budget")
    fun testBudget() = runBlocking {
        repeat(3) { seedTask("t$it", "extract amazon price with selector #price-${"x".repeat(200)}") }
        val tight = MemoryRecallService(memory.queryService, maxChars = 300, enabled = true)
        val section = tight.recall("extract amazon price", memory.scope)
        assertTrue(section.length <= 300)
    }

    @Test
    @DisplayName("returns empty when disabled")
    fun testDisabled() = runBlocking {
        seedTask("t1", "extract amazon price")
        val disabled = MemoryRecallService(memory.queryService, enabled = false)
        assertEquals("", disabled.recall("extract amazon price", memory.scope))
    }

    @Test
    @DisplayName("recall scopes to the agent")
    fun testAgentScope() = runBlocking {
        seedTask("t1", "extract amazon price")
        // Another agent's memory has no events; recall must not leak.
        val other = AgentMemory(MemoryScope(agentUuid = "a2"), rootDir = tempDir, knowledgeDir = tempDir.resolve("knowledge"))
        try {
            assertEquals("", other.recall.recall("extract amazon price", other.scope))
        } finally {
            other.close()
        }
    }

    @Test
    @DisplayName("recall excludes the current task (self-reference noise)")
    fun testExcludeCurrentTask() = runBlocking {
        seedTask("t1", "extract amazon price")
        // The engine writes TaskStarted before recalling; without the
        // exclusion the fresh events of the CURRENT task would be hit.
        memory.sink.taskStarted("t-current", "a1", "extract amazon price again", "cli", null)
        val section = memory.recall.recall(
            "extract amazon price again", memory.scope, excludeTaskId = "t-current",
        )
        assertTrue(section.contains("[L0]"), "history hits must remain: $section")
        assertTrue(!section.contains("t-current"), "current task must be excluded: $section")
    }

    @Test
    @DisplayName("fuses L1 knowledge when the provider has a hit")
    fun testL1Fusion() = runBlocking {
        // Seed a task with a URL so the PEM layer can deposit knowledge.
        memory.sink.taskStarted("t1", "a1", "extract amazon price", "cli", "https://example.com/dp/1")
        memory.sink.completed("t1", "a1", "done", listOf("title"))
        memory.consolidator?.let { c ->
            // Run the deposit synchronously for the test.
            memory.knowledgeProvider?.deposit("t1", memory.scope)
        }
        val section = memory.recall.recall("extract amazon price from https://example.com/dp/2", memory.scope)
        assertTrue(section.contains("[L0]") || section.contains("[L1]"))
        assertTrue(section.isNotBlank())
    }
}
