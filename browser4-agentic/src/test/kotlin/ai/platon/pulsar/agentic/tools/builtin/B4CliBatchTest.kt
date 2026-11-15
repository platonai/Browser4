package ai.platon.pulsar.agentic.tools.builtin

import ai.platon.pulsar.agentic.cli.CliProcessManager
import ai.platon.pulsar.agentic.cli.CliResult
import ai.platon.pulsar.agentic.cli.CliRunRequest
import ai.platon.pulsar.agentic.cli.CliTiming
import ai.platon.pulsar.coding.CodingAgentShell
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Paths

/**
 * `b4.batch` and the stdin payload path (evaluation E-1/E-2).
 *
 * Both exist for the same measured reason: a browser task makes 11–20 `b4.run`
 * calls and each costs a process start plus an MCP round trip, while a payload
 * inlined in the command line costs a PowerShell start on top of that (evaluation
 * §12.4). The tests below pin the wire shape (one `batch --json` process with the
 * command list on stdin), the guards that must survive it, and the payload path.
 */
@DisplayName("b4 batch and stdin payloads")
class B4CliBatchTest {

    private val shell = CodingAgentShell(Paths.get("."))

    @Test
    @DisplayName("commands split on newlines and survive a bulleted list")
    fun commandLinesSplitAndStripMarkers() {
        // Models routinely bullet a list they were asked to write as lines; without
        // this, "- goto https://x" reaches the CLI as an unknown command name.
        val raw = "goto https://example.com\n- snapshot -v 0\n\n  2. click e12  \n"
        assertEquals(
            listOf("goto https://example.com", "snapshot -v 0", "click e12"),
            batchCommandLines(raw),
        )
    }

    @Test
    @DisplayName("the payload is the CLI's own JSON command list")
    fun payloadIsJsonCommandList() {
        val payload = commandsAsBatchJson(listOf("goto https://x?a=1", "snapshot -v 0"))
        // The CLI parses this with `parse_batch_json_commands`; an array of strings
        // is the documented input, and quoting inside a step is preserved because
        // the value travels as JSON, not on a command line.
        assertEquals(listOf("goto https://x?a=1", "snapshot -v 0"), batchPayloadCommands(payload))
        assertTrue(payload.startsWith("[\""), payload)
    }

    @Test
    @DisplayName("the stdin payload parser accepts both documented entry shapes")
    fun payloadAcceptsBothEntryShapes() {
        assertEquals(
            listOf("goto https://example.com", "click e12"),
            batchPayloadCommands("""["goto https://example.com", ["click", "e12"]]"""),
        )
        assertNull(batchPayloadCommands("""{"commands":["goto x"]}"""), "an object is not a command list")
        assertNull(batchPayloadCommands("[]"), "an empty list is not a command list")
        assertNull(batchPayloadCommands("not json"))
    }

    // -- one process for the whole sequence (E-2) --------------------------

    @Test
    @DisplayName("b4.batch runs one `batch --json` process with the list on stdin")
    fun batchRunsOneProcess(@TempDir tmp: java.nio.file.Path) = runBlocking {
        val requests = mutableListOf<CliRunRequest>()
        val manager = mockk<CliProcessManager>(relaxed = true)
        coEvery { manager.run(any(), any(), any()) } coAnswers {
            requests += firstArg<CliRunRequest>()
            CliResult(exitCode = 0, stdout = "page title\n", stderr = "", durationMs = 5)
        }
        val executor = B4CliToolExecutor(cliProcessManager = manager)
        try {
            val text = executor.callFunctionOn(
                domain = "b4", functionName = "batch",
                args = mapOf(
                    "commands" to "goto https://example.com\nsnapshot -v 0\nclick e12",
                ),
                receiver = CodingAgentShell(tmp),
            ) as String

            assertEquals("page title\n", text)
            assertEquals(1, requests.size, "three commands must cost one process")
            val request = requests.single()
            assertEquals("batch --json", request.args, "the command line stays constant")
            assertEquals(
                listOf("goto https://example.com", "snapshot -v 0", "click e12"),
                batchPayloadCommands(request.stdin ?: error("no payload")),
                "the commands travel on stdin, so no step needs quoting",
            )
            assertEquals(tmp.toAbsolutePath().normalize(), request.workingDir?.toAbsolutePath()?.normalize())
        } finally {
            executor.closeJobs()
        }
    }

    @Test
    @DisplayName("bail maps to `batch --json --bail`")
    fun bailIsForwarded() = runBlocking {
        val requests = mutableListOf<CliRunRequest>()
        val manager = mockk<CliProcessManager>(relaxed = true)
        coEvery { manager.run(any(), any(), any()) } coAnswers {
            requests += firstArg<CliRunRequest>()
            CliResult(exitCode = 5, stdout = "", stderr = "Batch command 1 failed (click e99): not found", durationMs = 5)
        }
        val executor = B4CliToolExecutor(cliProcessManager = manager)
        try {
            val text = executor.callFunctionOn(
                domain = "b4", functionName = "batch",
                args = mapOf("commands" to "click e99\nsnapshot", "bail" to true),
                receiver = shell,
            ) as String

            assertEquals("batch --json --bail", requests.single().args)
            // A failing step is a failure for the whole call: exit code 5 means at
            // least one step failed, and the CLI's own line names which one.
            assertTrue(text.startsWith("b4.batch [fail]"), text)
            assertTrue(text.contains("Batch command 1 failed (click e99)"), text)
        } finally {
            executor.closeJobs()
        }
    }

    @Test
    @DisplayName("a batch with no command line is rejected before anything runs")
    fun emptyBatchIsRejected() {
        val ex = assertThrows<IllegalArgumentException> {
            runBlocking {
                B4CliToolExecutor().callFunctionOn(
                    domain = "b4", functionName = "batch",
                    args = mapOf("commands" to " \n\n "),
                    receiver = shell,
                )
            }
        }
        assertTrue(ex.message!!.contains("at least one command"), ex.message!!)
    }

    // -- the guards survive the payload (E-1) ------------------------------

    @Test
    @DisplayName("a batch step that would stop the backend is refused, naming the step")
    fun batchRefusesLifecycleSteps() {
        val text = runBlocking {
            B4CliToolExecutor().callFunctionOn(
                domain = "b4", functionName = "batch",
                args = mapOf("commands" to "snapshot -v 0\nstop"),
                receiver = shell,
            )
        } as String

        // Without per-step guards the payload would be a blind spot: the command
        // line is always `batch --json`, so nothing else could see `stop`.
        assertTrue(text.startsWith("PERMISSION_DENIED | code="), "a refusal must render as [fail]: $text")
        assertTrue(text.contains("command 2 (stop) was refused"), text)
        assertTrue(text.contains("nothing ran"), "the model must know no step executed: $text")
    }

    @Test
    @DisplayName("the same command list on b4.run's stdin is refused the same way")
    fun runWithBatchPayloadIsGuarded() {
        val text = runBlocking {
            B4CliToolExecutor().callFunctionOn(
                domain = "b4", functionName = "run",
                args = mapOf(
                    "args" to "batch --json",
                    "stdin" to """["goto https://example.com","stop"]""",
                ),
                receiver = shell,
            )
        } as String

        // E-1 makes stdin a first-class payload channel; moving a command list out
        // of `args` must not hide it from the guards.
        assertTrue(text.startsWith("PERMISSION_DENIED | code="), "a refusal must render as [fail]: $text")
        assertTrue(text.contains("command 2 (stop) was refused"), text)
    }

    @Test
    @DisplayName("a non-batch stdin payload is not parsed as commands")
    fun nonBatchPayloadIsNotParsedAsCommands() = runBlocking {
        val requests = mutableListOf<CliRunRequest>()
        val manager = mockk<CliProcessManager>(relaxed = true)
        coEvery { manager.run(any(), any(), any()) } coAnswers {
            requests += firstArg<CliRunRequest>()
            CliResult(exitCode = 0, stdout = "ok", stderr = "", durationMs = 1)
        }
        val executor = B4CliToolExecutor(cliProcessManager = manager)
        try {
            // JavaScript that merely *mentions* `stop` is not a CLI invocation: the
            // guard reads a batch's command list, it does not grep the payload. A
            // text-based guard would refuse perfectly ordinary scripts.
            executor.callFunctionOn(
                domain = "b4", functionName = "run",
                args = mapOf("args" to "eval --stdin", "stdin" to "document.title; // stop --help"),
                receiver = shell,
            )
            assertEquals("document.title; // stop --help", requests.single().stdin)
        } finally {
            executor.closeJobs()
        }
    }

    // -- payload travels beside the command line (E-1) ---------------------

    @Test
    @DisplayName("b4.run carries stdin to the child instead of inlining it")
    fun runCarriesStdinPayload() = runBlocking {
        val requests = mutableListOf<CliRunRequest>()
        val manager = mockk<CliProcessManager>(relaxed = true)
        coEvery { manager.run(any(), any(), any()) } coAnswers {
            requests += firstArg<CliRunRequest>()
            CliResult(exitCode = 0, stdout = "Example Domain", stderr = "", durationMs = 3)
        }
        val executor = B4CliToolExecutor(cliProcessManager = manager)
        try {
            val text = executor.callFunctionOn(
                domain = "b4", functionName = "run",
                args = mapOf("args" to "eval --stdin --json", "stdin" to "document.title"),
                receiver = shell,
            )

            assertEquals("Example Domain", text)
            val request = requests.single()
            // The command line has no quoting and no payload, so it stays in the
            // shell-free subset (evaluation §12.7) — that is the whole point.
            assertEquals("eval --stdin --json", request.args)
            assertEquals("document.title", request.stdin)
        } finally {
            executor.closeJobs()
        }
    }

    @Test
    @DisplayName("without a payload the request still means 'stdin is EOF'")
    fun absentPayloadStaysNull() = runBlocking {
        val requests = mutableListOf<CliRunRequest>()
        val manager = mockk<CliProcessManager>(relaxed = true)
        coEvery { manager.run(any(), any(), any()) } coAnswers {
            requests += firstArg<CliRunRequest>()
            CliResult(exitCode = 0, stdout = "ok", stderr = "", durationMs = 1)
        }
        val executor = B4CliToolExecutor(cliProcessManager = manager)
        try {
            executor.callFunctionOn(
                domain = "b4", functionName = "run",
                args = mapOf("args" to "snapshot -v 0"),
                receiver = shell,
            )
            assertNull(requests.single().stdin)
        } finally {
            executor.closeJobs()
        }
    }

    // -- timing is opt-in (E-4) --------------------------------------------

    @Test
    @DisplayName("the timing line appears only when the property asks for it")
    fun timingLineIsOptIn() {
        val result = CliResult(
            exitCode = 0,
            stdout = "42",
            stderr = "",
            durationMs = 2600,
            timing = CliTiming(startupMs = 282, httpMs = 2310, serverMs = 2240, netMs = 70, calls = 2, totalMs = 2600),
        )

        val quiet = classifyCliResult(result, "run")
        assertFalse(quiet.contains("[timing]"), "off by default: $quiet")

        val verbose = classifyCliResult(result, "run", timingVisible = true)
        assertTrue(verbose.contains("[timing] startup=282ms http=2310ms server=2240ms"), verbose)
        // The segments are additive: startup + http is the invocation, and the page
        // is `server` inside `http`.
        assertTrue(verbose.contains("net=70ms calls=2"), verbose)
    }
}
