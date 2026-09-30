package ai.platon.pulsar.agentic.tools

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * A batch is one request with many steps, so the two things that must not drift
 * are the **order** (browser work is stateful) and the **per-step envelope** (both
 * channels and the CLI parse it).
 */
@DisplayName("Batch execution")
class BatchExecutorTest {

    private fun step(index: Int, tool: String, args: Map<String, Any?> = emptyMap()) =
        BatchExecutor.BatchStep(index = index, id = tool, tool = tool, args = args)

    private fun executor(
        readOnly: (String) -> Boolean = { false },
        runner: suspend (BatchExecutor.BatchStep) -> BatchExecutor.BatchStepResult,
    ) = BatchExecutor(stepRunner = { step -> runner(step) }, readOnly = readOnly)

    // ---------------------------------------------------------------------
    // Ordering and failure policy
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("steps run in order and each carries the shared envelope")
    fun stepsRunInOrder() = runBlocking {
        val seen = mutableListOf<String>()
        val outcome = executor { step ->
            seen += step.tool
            BatchExecutor.BatchStepResult(ok = true, text = "text of ${step.tool}")
        }.run(listOf(step(0, "navigate"), step(1, "click"), step(2, "title")))

        assertEquals(listOf("navigate", "click", "title"), seen)
        assertEquals(0, outcome.failureCount)
        assertFalse(outcome.stoppedOnError)
        assertEquals(3, outcome.steps.size)

        val first = outcome.steps.first().toMap()
        assertEquals(0, first["index"])
        assertEquals("navigate", first["id"])
        assertEquals("navigate", first["tool"])
        assertEquals(true, first["ok"])
        assertNotNull(first["durationMs"])
        assertEquals("text of navigate", first["text"])
        assertFalse(first.containsKey("cached"), "an uncached step stays quiet rather than saying cached=false")
    }

    @Test
    @DisplayName("bail stops at the first failure, without bail every step still runs")
    fun bailStopsTheBatch() = runBlocking {
        var executed = 0
        val runner: suspend (BatchExecutor.BatchStep) -> BatchExecutor.BatchStepResult = { step ->
            executed++
            if (step.tool == "click") {
                BatchExecutor.BatchStepResult.failed("click failed: no node", "CDP_ERROR")
            } else {
                BatchExecutor.BatchStepResult(ok = true, text = "ok")
            }
        }
        val steps = listOf(step(0, "navigate"), step(1, "click"), step(2, "title"))

        val bailed = executor(runner = runner).run(steps, bail = true)
        assertEquals(2, executed, "execution stops after the failing step")
        assertTrue(bailed.stoppedOnError)
        assertEquals(1, bailed.failureCount)
        assertEquals(2, bailed.steps.size, "the steps that did not run are absent, not reported as failures")
        assertEquals("CDP_ERROR", bailed.steps[1].errorCode)

        executed = 0
        val full = executor(runner = runner).run(steps, bail = false)
        assertEquals(3, executed)
        assertFalse(full.stoppedOnError)
        assertEquals(1, full.failureCount)
    }

    @Test
    @DisplayName("a throwing step becomes a failed step with a mapped error code")
    fun throwingStepBecomesAFailure() = runBlocking {
        val outcome = executor { step ->
            if (step.tool == "click") throw IllegalStateException("missing required parameter 'selector'")
            BatchExecutor.BatchStepResult(ok = true)
        }.run(listOf(step(0, "click"), step(1, "title")))

        assertEquals(1, outcome.failureCount)
        val failed = outcome.steps.first()
        assertFalse(failed.ok)
        assertEquals("MISSING_REQUIRED_ARG", failed.errorCode, "the same classification a single call would get")
        assertTrue(failed.error!!.contains("selector"))
    }

    // ---------------------------------------------------------------------
    // Concurrency policy
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("parallel execution is refused for a batch that can change state")
    fun concurrencyIsRefusedForStatefulBatches() {
        val executor = executor(readOnly = { it == "title" }) { BatchExecutor.BatchStepResult(ok = true) }
        val steps = listOf(step(0, "title"), step(1, "click"))

        val error = assertThrows(IllegalArgumentException::class.java) {
            runBlocking { executor.run(steps, concurrency = 4) }
        }

        assertTrue(error.message!!.contains("read-only"), error.message)
        assertTrue(error.message!!.contains("click"), "the message must name the offending step: ${error.message}")
    }

    @Test
    @DisplayName("read-only batches may run in parallel, and stay in step order")
    fun readOnlyBatchesMayRunInParallel() = runBlocking {
        val started = java.util.concurrent.atomic.AtomicInteger()
        val outcome = executor(readOnly = { true }) { step ->
            started.incrementAndGet()
            delay(20)
            BatchExecutor.BatchStepResult(ok = true, text = step.tool)
        }.run(listOf(step(0, "title"), step(1, "current_url"), step(2, "get_text")), concurrency = 3)

        assertEquals(3, started.get())
        assertEquals(listOf("title", "current_url", "get_text"), outcome.steps.map { it.tool }, "results keep step order")
        assertEquals(0, outcome.failureCount)
    }

    // ---------------------------------------------------------------------
    // Batch-level caching (requirement 13)
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("a batch counts as cached only when every step was cached")
    fun cachedIsAllOrNothing() = runBlocking {
        val allCached = executor { BatchExecutor.BatchStepResult(ok = true, text = "x", cached = true) }
            .run(listOf(step(0, "title"), step(1, "current_url")))
        assertTrue(allCached.cached)
        assertEquals(2, allCached.cachedSteps)
        assertEquals(true, allCached.toMap()["cached"])

        val partly = executor { step ->
            BatchExecutor.BatchStepResult(ok = true, text = "x", cached = step.tool == "title")
        }.run(listOf(step(0, "title"), step(1, "navigate")))
        assertFalse(partly.cached, "a state-changing step can never be replayed")
        assertEquals(1, partly.cachedSteps)
    }

    // ---------------------------------------------------------------------
    // Parsing (the unified step shape)
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("both the canonical and the CLI step shape parse")
    fun stepShapesAreAccepted() {
        val steps = BatchExecutor.parseSteps(
            listOf(
                mapOf("tool" to "navigate", "args" to mapOf("url" to "https://example.com")),
                mapOf("tool" to "click", "arguments" to mapOf("selector" to "#a")),
                mapOf("op" to "tool", "id" to "read-title", "tool" to "title", "arguments" to emptyMap<String, Any?>()),
            )
        )

        assertEquals(3, steps.size)
        assertEquals(mapOf("url" to "https://example.com"), steps[0].args)
        assertEquals("#a", steps[1].args["selector"])
        assertEquals("read-title", steps[2].id, "an explicit id wins over the tool name")
        assertEquals("title", steps[2].tool)
        assertEquals("navigate", steps[0].id, "the id defaults to the tool name")
        assertEquals(listOf(0, 1, 2), steps.map { it.index })
    }

    @Test
    @DisplayName("a malformed step fails the request with a message naming the index")
    fun malformedStepsAreRejected() {
        val missingTool = assertThrows(IllegalArgumentException::class.java) {
            BatchExecutor.parseSteps(listOf(mapOf("args" to emptyMap<String, Any?>())))
        }
        assertTrue(missingTool.message!!.contains("index 0"), missingTool.message)

        val notAnObject = assertThrows(IllegalArgumentException::class.java) {
            BatchExecutor.parseSteps(listOf("navigate"))
        }
        assertTrue(notAnObject.message!!.contains("index 0"), notAnObject.message)

        val noSteps = assertThrows(IllegalArgumentException::class.java) {
            BatchExecutor.parseSteps(null)
        }
        assertTrue(noSteps.message!!.contains("steps"), noSteps.message)
    }

    @Test
    @DisplayName("an empty batch is refused rather than reported as successful")
    fun emptyBatchIsRefused() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            runBlocking { executor { BatchExecutor.BatchStepResult(ok = true) }.run(emptyList()) }
        }
        assertTrue(error.message!!.contains("at least one step"), error.message)
    }
}
