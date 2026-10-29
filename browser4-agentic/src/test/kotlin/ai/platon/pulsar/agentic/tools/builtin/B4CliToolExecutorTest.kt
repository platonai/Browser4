package ai.platon.pulsar.agentic.tools.builtin

import ai.platon.pulsar.coding.CodingAgentShell
import ai.platon.pulsar.agentic.cli.CliProcessManager
import ai.platon.pulsar.agentic.cli.CliResult
import ai.platon.pulsar.agentic.cli.CliRunRequest
import ai.platon.pulsar.agentic.model.ToolCall
import io.mockk.coEvery
import io.mockk.mockk
import kotlin.test.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Paths

class B4CliToolExecutorTest {

    private val executor = B4CliToolExecutor()
    private val shell = CodingAgentShell(Paths.get("."))

    @Test
    fun `rejects agent run subcommand`() = runBlocking {
        val ex = assertThrows<IllegalArgumentException> {
            executor.callFunctionOn(
                domain = "b4", functionName = "run",
                args = mapOf("args" to "agent run 'do something'"),
                receiver = shell,
            )
        }
        assertTrue(ex.message!!.contains("Nested agent spawning"))
    }

    @Test
    fun `rejects agent-run subcommand`() = runBlocking {
        val ex = assertThrows<IllegalArgumentException> {
            executor.callFunctionOn(
                domain = "b4", functionName = "run",
                args = mapOf("args" to "agent-run 'do something'"),
                receiver = shell,
            )
        }
        assertTrue(ex.message!!.contains("Nested agent spawning"))
    }

    @Test
    fun `rejects act subcommand`() = runBlocking {
        val ex = assertThrows<IllegalArgumentException> {
            executor.callFunctionOn(
                domain = "b4", functionName = "run",
                args = mapOf("args" to "act 'do something'"),
                receiver = shell,
            )
        }
        assertTrue(ex.message!!.contains("Nested agent spawning"))
    }

    @Test
    fun `rejects leading whitespace agent run`() = runBlocking {
        val ex = assertThrows<IllegalArgumentException> {
            executor.callFunctionOn(
                domain = "b4", functionName = "run",
                args = mapOf("args" to "   agent run 'do something'"),
                receiver = shell,
            )
        }
        assertTrue(ex.message!!.contains("Nested agent spawning"))
    }

    // -- result classification (P0-2) -------------------------------------

    @Test
    fun `a json status error is a failure with its message preserved`() {
        // `browser4-cli --json` answers a failed command with an exit code of 0 for
        // some commands and a `status:error` envelope; b4.run used to report `[ok]`
        // and the model re-ran the same broken command four times.
        val result = CliResult(
            exitCode = 0,
            stdout = """{"status":"error","command":"eval","error":{"message":"Eval file '.b4scratch/missing.js' not found","code":"COMMAND_FAILED"}}""",
            stderr = "",
            durationMs = 12,
        )

        val text = classifyCliResult(result, "run", Paths.get("D:/ws/workspace.t1"))

        assertTrue(text.startsWith("b4.run [fail] "), text)
        assertTrue(text.contains("Eval file '.b4scratch/missing.js' not found"), text)
        assertNotNull(b4FailureReason(text))
    }

    @Test
    fun `a non-zero exit code is a failure`() {
        val result = CliResult(exitCode = 1, stdout = "", stderr = "Error: boom", durationMs = 20)

        val text = classifyCliResult(result, "run")

        assertTrue(text.startsWith("b4.run [fail] CLI exited with code 1"), text)
        assertTrue(text.contains("boom"), text)
    }

    @Test
    fun `a timeout names batching and the timeout knob`() {
        val result = CliResult(exitCode = null, stdout = "", stderr = "", timedOut = true, durationMs = 30_000)

        val text = classifyCliResult(result, "run")

        assertTrue(text.contains("[fail] timed out"), text)
        assertTrue(text.contains("timeoutSeconds"), "the hint must name the knob: $text")
        assertTrue(text.contains("batches"), "the hint must suggest batching: $text")
    }

    @Test
    fun `a missing relative path failure names the working directory`() {
        val result = CliResult(
            exitCode = 1,
            stdout = "",
            stderr = "Error: Eval file '.b4scratch/x.js' not found",
            durationMs = 8,
        )

        val text = classifyCliResult(result, "run", Paths.get("D:/ws/workspace.abc"))

        assertTrue(text.contains("working directory"), text)
        assertTrue(text.contains("workspace.abc"), text)
    }

    @Test
    fun `the evaluate deadline hints at server-side waits, not a bigger timeout`() {
        // 2026-10-29 Gate: a 35s page-side sleep inside `eval --await` failed with
        // `[INTERNAL] browser_evaluate failed: No response | Runtime.evaluate | #14, (PT30S)`,
        // and no timeout knob could widen the driver's 30s deadline.
        val result = CliResult(
            exitCode = 1,
            stdout = "",
            stderr = "Error: [INTERNAL] browser_evaluate failed: No response | Runtime.evaluate | #14, (PT30S)",
            durationMs = 31_000,
        )

        val text = classifyCliResult(result, "run")

        assertTrue(text.contains("[hint]"), text)
        assertTrue(text.contains("30s driver deadline"), "the hint must name the deadline: $text")
        assertTrue(text.contains("wait --selector"), "the hint must point at wait: $text")
        assertTrue(text.contains("wait --fn"), text)
        assertTrue(text.contains("cannot widen"), "the hint must say a timeout will not help: $text")
    }

    @Test
    fun `per-item status errors do not fail a successful command`() {
        // A crawl reports a failed page as `"status":"error"` inside its item list;
        // only a TOP-LEVEL status is the command's own verdict.
        val result = CliResult(
            exitCode = 0,
            stdout = """{"pageUrl":"https://example.com","items":[{"url":"a","status":"error","error":"boom"}]}""",
            stderr = "",
            durationMs = 30,
        )

        assertNull(cliFailureReason(result))
        assertEquals(result.toModelText(), classifyCliResult(result, "run"))
    }

    @Test
    fun `a failed or cancelled job is a failure but a completed one is not`() {
        val completed = jobStateFailure("wait", "j1", "COMPLETED", "Job j1 state=COMPLETED\n42")
        assertEquals("Job j1 state=COMPLETED\n42", completed)

        val running = jobStateFailure("status", "j1", "RUNNING", "Job j1 state=RUNNING")
        assertEquals("Job j1 state=RUNNING", running)

        val failed = jobStateFailure("wait", "j1", "FAILED", "Job j1 state=FAILED\nfragment")
        assertTrue(failed.startsWith("b4.wait [fail] job j1 ended FAILED"), failed)

        val cancelled = jobStateFailure("status", "j1", "CANCELLED", "Job j1 state=CANCELLED")
        assertTrue(cancelled.startsWith("b4.status [fail] job j1 ended CANCELLED"), cancelled)
    }

    @Test
    fun `an infrastructure failure is a failure`() {
        val result = CliResult(
            exitCode = null, stdout = "", stderr = "",
            infraFailure = "Backend unreachable at http://localhost:18182", durationMs = 0,
        )

        val text = classifyCliResult(result, "run")

        assertTrue(text.startsWith("b4.run [fail] infrastructure failure"), text)
    }

    // -- cwd follows the live workspace (P0-3) ----------------------------

    @Test
    fun `the command runs in the current coding workspace, before and after a rebind`(@TempDir tmp: java.nio.file.Path) = runBlocking {
        // A stale executor (captured defaultWorkingDir) made a relative
        // `eval --file .b4scratch/x.js` resolve into the PREVIOUS task's workspace while
        // coding.* wrote into the new one (2026-10-28: "not found" four times).
        val requests = mutableListOf<CliRunRequest>()
        val manager = mockk<CliProcessManager>(relaxed = true)
        coEvery { manager.run(any(), any(), any()) } coAnswers {
            requests += firstArg<CliRunRequest>()
            CliResult(exitCode = 0, stdout = "version 1.0.0", stderr = "", durationMs = 1)
        }
        val executor = B4CliToolExecutor(cliProcessManager = manager)
        try {
            val first = java.nio.file.Files.createDirectories(tmp.resolve("workspace.task-a"))
            executor.callFunctionOn(
                ToolCall(domain = "b4", method = "run", arguments = mutableMapOf("args" to "version")),
                CodingAgentShell(first),
            )

            val second = java.nio.file.Files.createDirectories(tmp.resolve("workspace.task-b"))
            executor.callFunctionOn(
                ToolCall(domain = "b4", method = "run", arguments = mutableMapOf("args" to "version")),
                CodingAgentShell(second),
            )

            assertEquals(
                listOf(first.toAbsolutePath().normalize(), second.toAbsolutePath().normalize()),
                requests.map { it.workingDir?.toAbsolutePath()?.normalize() },
                "each call must run in the workspace of the shell it is dispatched with",
            )
        } finally {
            executor.closeJobs()
        }
    }
}
