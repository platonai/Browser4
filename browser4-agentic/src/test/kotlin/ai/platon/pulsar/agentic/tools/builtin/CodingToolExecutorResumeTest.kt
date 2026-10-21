package ai.platon.pulsar.agentic.tools.builtin

import ai.platon.pulsar.agentic.model.ToolCall
import ai.platon.pulsar.coding.CodingAgentFileSystem
import ai.platon.pulsar.coding.CodingAgentShell
import ai.platon.pulsar.coding.DevTaskCheckpoint
import ai.platon.pulsar.coding.DevTaskCheckpointStore
import ai.platon.pulsar.coding.DevTaskStepResult
import ai.platon.pulsar.coding.DevTaskStepSnapshot
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * Tests for `coding.resumeDevTask` (plan P2.4): a failed/crashed dev-task run is
 * recorded as a checkpoint, and resuming runs ONLY the remaining steps, updating
 * the checkpoint as it goes.
 */
class CodingToolExecutorResumeTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var executor: CodingToolExecutor
    private lateinit var target: CodingToolExecutor.Target

    @BeforeEach
    fun setUp() {
        executor = CodingToolExecutor()
        target = CodingToolExecutor.Target(
            CodingAgentShell(baseDir = tempDir),
            CodingAgentFileSystem(tempDir),
        )
    }

    private fun validateStep(order: Int) = DevTaskStepSnapshot(
        order = order,
        tool = "coding.validate",
        purpose = "validate repo governance $order",
        command = "coding.validate(type=\"repo-consistency\")",
        args = mapOf("type" to "repo-consistency"),
    )

    private fun seedCheckpoint(
        id: String = "dt-test-1",
        completed: List<DevTaskStepResult> = listOf(DevTaskStepResult(1, "coding.validate", true, "ok")),
        state: String = DevTaskCheckpoint.STATE_FAILED,
    ): DevTaskCheckpoint {
        val cp = DevTaskCheckpoint(
            id = id,
            task = "add a pure function to browser4-coding",
            state = state,
            steps = listOf(validateStep(1), validateStep(2)),
            completed = completed,
            failedStep = 2,
            failureMessage = "boom",
            createdAt = "2026-10-21T18:00:00Z",
            updatedAt = "2026-10-21T18:01:00Z",
        )
        assertTrue(DevTaskCheckpointStore(tempDir).save(cp), "could not seed the checkpoint")
        return cp
    }

    private fun call(method: String, vararg args: Pair<String, Any?>): String {
        val tc = ToolCall(domain = "coding", method = method, arguments = linkedMapOf(*args))
        val result = runBlocking { executor.callFunctionOn(tc, target) }
        assertNull(result.exception, "executor failed: ${result.exception}")
        return result.value.toString()
    }

    @Test
    @DisplayName("resume skips completed steps and runs the rest")
    fun resumeSkipsCompletedSteps() {
        seedCheckpoint()
        val text = call("resumeDevTask", "taskId" to "dt-test-1")

        assertTrue(text.contains("Resuming dev task dt-test-1 (1/2 steps done)"), "text: $text")
        assertTrue(text.contains("skipped (already completed — resume)"), "text: $text")
        assertTrue(text.contains("Step 2. [coding.validate]"), "text: $text")
        assertTrue(text.contains("--- Checkpoint ---"), "text: $text")
        assertTrue(text.contains("state: ${DevTaskCheckpoint.STATE_DONE}"), "text: $text")
        assertTrue(text.contains("Resumed run."), "text: $text")

        val reloaded = DevTaskCheckpointStore(tempDir).load("dt-test-1")!!
        assertEquals(DevTaskCheckpoint.STATE_DONE, reloaded.state)
        assertEquals(listOf(1, 2), reloaded.completed.map { it.order })
    }

    @Test
    @DisplayName("resuming a completed task is refused with a clear message")
    fun resumeCompletedTaskRefused() {
        seedCheckpoint(
            completed = listOf(
                DevTaskStepResult(1, "coding.validate", true, "ok"),
                DevTaskStepResult(2, "coding.validate", true, "ok"),
            ),
            state = DevTaskCheckpoint.STATE_DONE,
        )
        val text = call("resumeDevTask", "taskId" to "dt-test-1")
        assertTrue(text.contains("already completed"), "text: $text")
    }

    @Test
    @DisplayName("an unknown checkpoint id is reported, not silently ignored")
    fun unknownCheckpoint() {
        val text = call("resumeDevTask", "taskId" to "dt-does-not-exist")
        assertTrue(text.contains("No dev-task checkpoint found"), "text: $text")
        assertTrue(text.contains("dt-does-not-exist"), "text: $text")
        // The message points at the checkpoint directory (path separators differ
        // between platforms, so compare on the normalized form).
        assertTrue(
            text.replace('\\', '/').contains(DevTaskCheckpointStore.DIR),
            "text: $text",
        )
    }

    @Test
    @DisplayName("a fresh execute run records a resumable checkpoint")
    fun executeRecordsCheckpoint() {
        val tc = ToolCall(
            domain = "coding",
            method = "devTask",
            arguments = mutableMapOf(
                "task" to "please improve the overall performance",
                "execute" to "true",
            ),
        )
        val result = runBlocking { executor.callFunctionOn(tc, target) }
        assertNull(result.exception, "executor failed: ${result.exception}")
        val text = result.value.toString()
        assertTrue(text.contains("--- Checkpoint ---"), "text: $text")
        assertTrue(text.contains("id: dt-"), "text: $text")
        assertTrue(text.contains("state: ${DevTaskCheckpoint.STATE_DONE}"), "text: $text")

        val ids = DevTaskCheckpointStore(tempDir).list().map { it.id }
        assertEquals(1, ids.size, "expected one checkpoint, got $ids")
    }
}
