package ai.platon.pulsar.agentic.tools

import ai.platon.pulsar.agentic.model.TcEvaluate
import ai.platon.pulsar.agentic.model.ToolCall
import ai.platon.pulsar.agentic.model.ToolCallResult
import ai.platon.pulsar.agentic.model.ToolSpec
import ai.platon.pulsar.common.serialize.json.pulsarObjectMapper
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * `batch_run` is how a client reaches the batch primitive over MCP, so it must
 * resolve tool names the way a single call does and answer in the shared envelope.
 */
@DisplayName("batch_run tool")
class BatchToolExecutorTest {

    private lateinit var manager: AgentToolManager
    private lateinit var executor: BatchToolExecutor
    private val mapper = pulsarObjectMapper()

    private val specs: Map<String, Map<String, ToolSpec>> = mapOf(
        "tab" to mapOf(
            "navigate" to spec("tab", "navigate"),
            "click" to spec("tab", "click"),
            "title" to spec("tab", "title"),
            "currentUrl" to spec("tab", "currentUrl"),
        ),
        "crawl" to mapOf("submit" to spec("crawl", "submit")),
    )

    @BeforeEach
    fun setUp() {
        manager = mockk(relaxed = true)
        // A private cache per test: the shared one is process-wide, so a test would
        // otherwise observe what a previous test cached.
        executor = BatchToolExecutor(
            ToolResultCache(
                enabledProvider = { true },
                ttlMultiplierProvider = { 1.0 },
                maxEntriesProvider = { 100 },
                clock = { 0L },
            )
        )
        every { manager.getAllToolSpecs() } returns specs
        every { manager.getToolSpec(any(), any()) } answers { specs[firstArg()]?.get(secondArg()) }
        coEvery { manager.execute(any()) } returns toolCallResult("done")
    }

    private fun spec(domain: String, method: String) = ToolSpec(
        domain = domain,
        method = method,
        description = "test",
        returnType = "String",
    )

    private fun toolCallResult(value: Any?): ToolCallResult = ToolCallResult(evaluate = TcEvaluate(value = value))

    private fun run(args: Map<String, Any?>): Map<*, *> = runBlocking {
        mapper.readValue(executor.callFunctionOn("batch", "run", args, manager) as String, Map::class.java)
    }

    private fun calls(): List<ToolCall> {
        val captured = mutableListOf<ToolCall>()
        coVerify { manager.execute(capture(captured)) }
        return captured
    }

    @Test
    @DisplayName("steps resolve to domain.method and run in order")
    fun stepsResolveAndRun() {
        val envelope = run(
            mapOf(
                "steps" to listOf(
                    mapOf("tool" to "navigate", "args" to mapOf("url" to "https://example.com")),
                    mapOf("tool" to "browser_click", "args" to mapOf("selector" to "#a")),
                    mapOf("tool" to "tab.title", "args" to emptyMap<String, Any?>()),
                )
            )
        )

        assertEquals(0, envelope["failureCount"])
        assertEquals(false, envelope["stoppedOnError"])
        val steps = envelope["steps"] as List<*>
        assertEquals(3, steps.size)

        val called = calls()
        assertEquals(
            listOf("tab" to "navigate", "tab" to "click", "tab" to "title"),
            called.map { it.domain to it.method },
        )
        assertEquals("https://example.com", called[0].arguments["url"])
        assertEquals("#a", called[1].arguments["selector"], "the frontend alias resolves to the same tool")
    }

    @Test
    @DisplayName("an unknown step tool fails that step without running anything else")
    fun unknownToolFailsTheStep() {
        val envelope = run(mapOf("steps" to listOf(mapOf("tool" to "does_not_exist", "args" to emptyMap<String, Any?>()))))

        assertEquals(1, envelope["failureCount"])
        val step = (envelope["steps"] as List<*>).first() as Map<*, *>
        assertEquals("UNKNOWN_TOOL", step["errorCode"])
        assertFalse(step["ok"] as Boolean)
        coVerify(exactly = 0) { manager.execute(any()) }
    }

    @Test
    @DisplayName("bail is honoured, and a thrown failure is classified like a single call")
    fun bailIsHonoured() {
        coEvery { manager.execute(any()) } throws IllegalStateException("missing required parameter 'selector'")

        val envelope = run(
            mapOf(
                "steps" to listOf(
                    mapOf("tool" to "click", "args" to mapOf("selector" to "#a")),
                    mapOf("tool" to "title", "args" to emptyMap<String, Any?>()),
                ),
                "bail" to true,
            )
        )

        assertEquals(1, envelope["failureCount"])
        assertEquals(true, envelope["stoppedOnError"])
        assertEquals(1, (envelope["steps"] as List<*>).size)
        val step = (envelope["steps"] as List<*>).first() as Map<*, *>
        assertEquals("MISSING_REQUIRED_ARG", step["errorCode"])
        coVerify(exactly = 1) { manager.execute(any()) }
    }

    @Test
    @DisplayName("concurrency is refused when a step can change page state")
    fun concurrencyIsGuarded() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                executor.callFunctionOn(
                    "batch", "run",
                    mapOf(
                        "steps" to listOf(
                            mapOf("tool" to "title", "args" to emptyMap<String, Any?>()),
                            mapOf("tool" to "click", "args" to mapOf("selector" to "#a")),
                        ),
                        "concurrency" to 2,
                    ),
                    manager,
                )
            }
        }

        assertTrue(error.message!!.contains("read-only"), error.message)
    }

    @Test
    @DisplayName("a read-only batch is replayed from the cache on the second call")
    fun readOnlyBatchIsCached() {
        val cache = ToolResultCache(
            enabledProvider = { true },
            ttlMultiplierProvider = { 1.0 },
            maxEntriesProvider = { 100 },
            clock = { 0L },
        )
        val caching = BatchToolExecutor(cache)
        val steps = listOf(
            mapOf("tool" to "title", "args" to emptyMap<String, Any?>()),
            mapOf("tool" to "current_url", "args" to emptyMap<String, Any?>()),
        )

        fun runOnce(): Map<*, *> = runBlocking {
            mapper.readValue(
                caching.callFunctionOn("batch", "run", mapOf("steps" to steps, "sessionId" to "s1"), manager) as String,
                Map::class.java,
            )
        }

        val first = runOnce()
        assertEquals(false, first["cached"], "nothing cached yet")
        val second = runOnce()

        assertEquals(true, second["cached"], "a batch of reads is replayed")
        assertEquals(2, second["cachedSteps"])
        coVerify(exactly = 2) { manager.execute(any()) }
    }

    @Test
    @DisplayName("a batch with a state-changing step is never replayed")
    fun statefulBatchIsNotCached() {
        val cache = ToolResultCache(
            enabledProvider = { true },
            ttlMultiplierProvider = { 1.0 },
            maxEntriesProvider = { 100 },
            clock = { 0L },
        )
        val caching = BatchToolExecutor(cache)
        val steps = listOf(
            mapOf("tool" to "title", "args" to emptyMap<String, Any?>()),
            mapOf("tool" to "click", "args" to mapOf("selector" to "#a")),
        )

        fun runOnce(): Map<*, *> = runBlocking {
            mapper.readValue(
                caching.callFunctionOn("batch", "run", mapOf("steps" to steps, "sessionId" to "s1"), manager) as String,
                Map::class.java,
            )
        }

        assertEquals(false, runOnce()["cached"])
        assertEquals(false, runOnce()["cached"], "a click invalidated the read, so nothing can be replayed")
        coVerify(exactly = 4) { manager.execute(any()) }
    }

    @Test
    @DisplayName("steps also parse from the JSON text the standard server passes through")
    fun stepsAsJsonText() {
        val envelope = run(
            mapOf(
                "steps" to """[{"tool":"title","args":{}},{"tool":"current_url","args":{}}]""",
            )
        )

        assertEquals(0, envelope["failureCount"])
        assertEquals(2, (envelope["steps"] as List<*>).size)
        coVerify(exactly = 2) { manager.execute(any()) }
    }

    @Test
    @DisplayName("the tool documents the unified step shape")
    fun specDocumentsTheContract() {
        val spec = executor.getToolSpecs().getValue("run")

        assertNotNull(spec.help)
        assertTrue(spec.help!!.contains("serially"), "the help must state the ordering guarantee")
        assertTrue(spec.arguments.any { it.name == "steps" })
        assertTrue(spec.examples.any { it.executable }, "the examples must be runnable inputs")
    }
}
