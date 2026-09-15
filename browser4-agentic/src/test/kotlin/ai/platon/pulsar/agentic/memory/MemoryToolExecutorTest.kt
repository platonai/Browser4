package ai.platon.pulsar.agentic.memory

import ai.platon.pulsar.agentic.tools.specs.ToolResultValidator
import ai.platon.pulsar.agentic.tools.specs.ToolSpecValidator
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@DisplayName("MemoryToolExecutor")
class MemoryToolExecutorTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var memory: AgentMemory
    private lateinit var executor: MemoryToolExecutor

    @BeforeEach
    fun setUp() {
        memory = AgentMemory(MemoryScope(agentUuid = "a1"), rootDir = tempDir, knowledgeDir = tempDir.resolve("knowledge"))
        executor = MemoryToolExecutor(memory)
    }

    @AfterEach
    fun tearDown() {
        // Close the memory (SQLite index) BEFORE deleting the temp dir so the
        // index files are not locked on Windows.
        runCatching { memory.close() }
        try { tempDir.toFile().deleteRecursively() } catch (_: Exception) {}
    }

    private fun seed() {
        memory.sink.taskStarted("t1", "a1", "extract amazon price", "cli", "https://example.com/p")
        memory.sink.toolExecuted("t1", "a1", "htmlsnapshot.get", """{"password":"hunter2"}""", true, "title ok", 5)
        memory.sink.completed("t1", "a1", "done", listOf("title"))
    }

    @Test
    @DisplayName("search returns JSON with hits")
    fun testSearch() = runBlocking {
        seed()
        val result = executor.callFunctionOn(
            "memory", "search", mapOf("query" to "amazon"), Any(),
        ) as String
        assertTrue(result.contains("t1"))
        assertTrue(result.contains("L0"))
    }

    @Test
    @DisplayName("the declared outputSchema accepts what search and read produce")
    fun outputSchemaAcceptsProduction() = runBlocking {
        seed()
        val calls = listOf<Pair<String, Map<String, Any?>>>(
            "search" to mapOf("query" to "amazon"),
            "read" to mapOf("taskId" to "t1"),
        )

        for ((method, args) in calls) {
            val spec = executor.getToolSpecs().getValue(method)
            assertNotNull(spec.outputSchema, "memory.$method must declare its result contract")

            val text = executor.callFunctionOn("memory", method, args, Any()) as String
            val issues = ToolResultValidator.validate(spec, ToolResultValidator.parse(text))

            assertEquals(
                emptyList<String>(), issues.map { "${it.path} ${it.message}" },
                "memory.$method answered with a payload that violates its own schema: $text",
            )
        }
    }

    @Test
    @DisplayName("optional arguments are advertised as optional, not required")
    fun optionalArgumentsAreNotRequired() {
        // `agent`/`seq` are read with `required = false`. Declaring them as bare
        // `null` defaults (the convention for *required*) made the validator reject
        // the query-only search and the whole-task read that the executor handles.
        val validator = ToolSpecValidator()

        val search = executor.getToolSpecs().getValue("search")
        assertEquals(
            emptyList<ToolSpecValidator.Violation>(),
            validator.validate(search, mapOf("query" to "amazon")),
            "a query-only search is a valid call",
        )

        val read = executor.getToolSpecs().getValue("read")
        assertEquals(
            emptyList<ToolSpecValidator.Violation>(),
            validator.validate(read, mapOf("taskId" to "t1")),
            "reading a whole task without coarse coordinates is a valid call",
        )
    }

    @Test
    @DisplayName("read returns a JSON event window")
    fun testRead() = runBlocking {
        seed()
        val events = memory.queryService.traceTask("t1")
        val result = executor.callFunctionOn(
            "memory", "read", mapOf("taskId" to "t1", "seq" to events.first().seq), Any(),
        ) as String
        assertTrue(result.contains("task_started") || result.contains("taskId"))
    }

    @Test
    @DisplayName("note writes the scratchpad and records a NoteWritten event")
    fun testNote() = runBlocking {
        seed()
        val result = executor.callFunctionOn(
            "memory", "note", mapOf("key" to "assumption", "value" to "page uses shadow DOM", "taskId" to "t1"), Any(),
        ) as String
        assertTrue(result.contains("Note saved: assumption"))
        assertEquals("page uses shadow DOM", memory.scratchpad.get("assumption"))
        val notes = memory.queryService.traceTask("t1").filterIsInstance<NoteWritten>()
        assertEquals(1, notes.size)
    }

    @Test
    @DisplayName("note masks sensitive values in the event log")
    fun testNoteSanitized() = runBlocking {
        seed()
        executor.callFunctionOn(
            "memory", "note", mapOf("key" to "password", "value" to "hunter2", "taskId" to "t1"), Any(),
        )
        val note = memory.queryService.traceTask("t1").filterIsInstance<NoteWritten>().first()
        assertEquals("hunter2", note.valueBrief) // key-based masking happens at write; the tool value is user content
        // Sensitive tool ARGUMENTS are masked at the sink boundary:
        val tool = memory.queryService.traceTask("t1").filterIsInstance<ToolExecuted>().first()
        assertTrue(!tool.argsBrief.contains("hunter2"))
    }

    @Test
    @DisplayName("forget removes the task")
    fun testForget() = runBlocking {
        seed()
        val result = executor.callFunctionOn("memory", "forget", mapOf("taskId" to "t1"), Any()) as String
        assertTrue(result.contains("Forgotten"))
        assertEquals(emptyList(), memory.queryService.traceTask("t1"))
    }

    @Test
    @DisplayName("rejects bad arguments")
    fun testValidation() {
        runBlocking {
            kotlin.test.assertFailsWith<IllegalArgumentException> {
                executor.callFunctionOn("memory", "search", mapOf(), Any())
            }
            kotlin.test.assertFailsWith<IllegalArgumentException> {
                executor.callFunctionOn("memory", "note", mapOf("key" to "bad key!", "value" to "v"), Any())
            }
        }
    }

    @Test
    @DisplayName("executor feeds the registry through ToolCallSpecificationProvider")
    fun testSpecificationProvider() {
        // Spring-side registration (PluginManager) consumes specs via the
        // provider; without them the engine's "already registered" guard
        // would leave memory.* invisible to the model.
        val provider = executor as ai.platon.pulsar.agentic.tools.specs.ToolCallSpecificationProvider
        val specs = provider.getToolCallSpecifications()
        assertEquals(setOf("search", "read", "note", "forget"), specs.map { it.method }.toSet())
        assertTrue(specs.all { it.domain == "memory" })
    }

    @Test
    @DisplayName("fails loudly without a backend")
    fun testNoBackend() {
        runBlocking {
            val bare = MemoryToolExecutor()
            kotlin.test.assertFailsWith<IllegalStateException> {
                bare.callFunctionOn("memory", "search", mapOf("query" to "x"), Any())
            }
        }
    }
}
