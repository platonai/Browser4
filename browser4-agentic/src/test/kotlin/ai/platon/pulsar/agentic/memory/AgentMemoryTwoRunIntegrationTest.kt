package ai.platon.pulsar.agentic.memory

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Engine-level two-run closed-loop verification: what an agent run leaves in
 * memory (L0 events + PEM deposit) must make the NEXT run's recall section
 * contain both fact hits (L0) and knowledge (L1) — the design's §11 e2e
 * acceptance at the engine level, without a live LLM/backend.
 */
@DisplayName("AgentMemory two-run closed loop")
class AgentMemoryTwoRunIntegrationTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var memory: AgentMemory
    private lateinit var background: CoroutineScope

    @BeforeEach
    fun setUp() {
        memory = AgentMemory(MemoryScope(agentUuid = "a1"), rootDir = tempDir, knowledgeDir = tempDir.resolve("knowledge"))
        background = CoroutineScope(Dispatchers.Default + SupervisorJob())
    }

    @AfterEach
    fun tearDown() {
        runCatching { memory.close() }
        runCatching { background.cancel() }
        try { tempDir.toFile().deleteRecursively() } catch (_: Exception) {}
    }

    /** Consolidator wired exactly like AgentMemory does, but with a short delay. */
    private fun quickConsolidator() = MemoryConsolidator(
        memory.knowledgeProvider!!, memory.scope, background, delayMs = 50,
    )

    @Test
    @DisplayName("run1 deposits knowledge; run2 recall fuses L0 facts + L1 knowledge")
    fun testTwoRunClosedLoop() = runBlocking {
        // ── Run 1: the engine's observation points of a successful task ──
        val run1 = "run1"
        memory.currentTaskId = run1
        memory.sink.taskStarted(run1, "a1", "extract the product title from https://example.com/dp/12345", "cli", "https://example.com/dp/12345")
        memory.sink.toolExecuted(run1, "a1", "htmlsnapshot.get", """{"url":"https://example.com/dp/12345"}""", true, "title=Awesome Product", 42)
        memory.sink.toolExecuted(run1, "a1", "tab.extract", """{"instruction":"title"}""", true, "Awesome Product", 15)
        memory.sink.completed(run1, "a1", "extracted product title", listOf("title"), durationMs = 1200)
        // The engine's completion hook schedules the consolidation (short delay).
        quickConsolidator().schedule(run1)
        delay(300)

        // 4 events on disk (started / 2 tools / completed) + task list visible.
        assertEquals(4, memory.eventLog.readTask("a1", run1).size)
        assertEquals(1, memory.queryService.listTasks(memory.scope).size)

        // ── Run 2: a NEW task on the same site — recall must fuse L0 + L1 ──
        val section = memory.recall.recall(
            "extract the product title from https://example.com/dp/67890", memory.scope,
        )
        assertTrue(section.contains("## Memory"), "recall section must be injected on run 2")
        assertTrue(section.contains("[L0]"), "L0 fact hits must be present: $section")
        assertTrue(section.contains("[L1]"), "L1 PEM knowledge must be present: $section")
        assertTrue(section.contains("置信度"), "confidence must be rendered: $section")
        assertTrue(section.contains("example.com"), "domain context must be visible: $section")
    }

    @Test
    @DisplayName("failed runs deposit failure traces with categories (blocker awareness)")
    fun testFailureRunDeposits() = runBlocking {
        val runF = "runF"
        memory.sink.taskStarted(runF, "a1", "extract the product title from https://example.com/dp/77777", "cli", "https://example.com/dp/77777")
        memory.sink.toolExecuted(runF, "a1", "htmlsnapshot.get", """{"url":"https://example.com/dp/77777"}""", false, "[fail] selector not found", 30)
        memory.sink.failed(runF, "a1", "selector #title not found", "SELECTOR_DRIFT", step = 1)
        // The engine's completion hook schedules the consolidation (short delay).
        quickConsolidator().schedule(runF)
        delay(300)

        val tasks = memory.queryService.listTasks(memory.scope)
        assertEquals("failure", tasks.first { it.taskId == runF }.outcome)
        // The failure category lands in the PEM trace (visible to later queries).
        val hits = memory.knowledgeProvider!!.query(
            "extract the product title", "https://example.com/dp/88888", memory.scope,
        )
        assertTrue(hits.hits.isNotEmpty(), "later queries must surface the failure knowledge")
    }

    @Test
    @DisplayName("scratchpad notes survive into the tail message and the note event log")
    fun testScratchpadThroughEngine() = runBlocking {
        memory.currentTaskId = "runN"
        memory.sink.taskStarted("runN", "a1", "fill login form on https://example.com/login", "cli", "https://example.com/login")

        // The model calls memory.note through the tool; the engine re-injects
        // the render as the tail message every round.
        memory.note("assumption", "form uses shadow DOM", "runN")
        val tail = memory.scratchpad.render()
        assertTrue(tail!!.contains("assumption: form uses shadow DOM"))

        // The note is also an L0 event (searchable across sessions).
        val page = memory.queryService.searchEvents("shadow DOM", SearchFilters(agentUuid = "a1"))
        assertTrue(page.hits.isNotEmpty(), "notes must be searchable")
    }
}
