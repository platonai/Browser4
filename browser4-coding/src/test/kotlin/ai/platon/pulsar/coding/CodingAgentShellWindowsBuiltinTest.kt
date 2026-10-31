package ai.platon.pulsar.coding

import ai.platon.pulsar.coding.sandbox.ExecutionSandboxes
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * cmd.exe built-ins that a Windows agent legitimately needs, and the ones it must not
 * run as a one-liner.
 *
 * Origin (2026-10-28 run): `set` was refused as unknown, `echo.` (cmd's blank-line
 * idiom) was refused because the allow-list only knows `echo`, and `if`/`for` were
 * refused with no alternative — the model worked around all three with
 * `python -c …`, burning steps.
 *
 * `if`/`for` stay refused on purpose: `SAFE_COMMANDS` only checks the FIRST token, so a
 * control-flow keyword would let an arbitrary nested command through the workspace
 * boundary. They get an actionable hint instead (a `.cmd` file or `python -c`).
 *
 * Plan P1-4 adds the Windows execution coverage: `cmd /c`, `powershell -Command` and a
 * `runCode` interpreter subprocess all go through the same sandbox seam, so the suite stays
 * green with the default `off` sandbox and skips only when `windows-acl` cannot provision.
 */
@DisplayName("windows execution: cmd.exe built-ins, PowerShell and runCode")
class CodingAgentShellWindowsBuiltinTest {

    @TempDir
    lateinit var tempDir: Path

    private val isWindows = System.getProperty("os.name", "").lowercase().contains("win")

    private fun shell() = CodingAgentShell(
        baseDir = Path.of(System.getProperty("user.dir")).toAbsolutePath(),
        allowDevTools = true,
        allowNetwork = false,
        allowDestructive = false,
    )

    @Test
    @DisplayName("echo. is normalized to echo before validation and execution")
    fun echoDotIsNormalized() {
        assumeTrue(isWindows, "cmd.exe idiom")
        val s = shell()

        assertEquals("echo", s.normalizeShellCommand("echo."))
        assertEquals("echo done", s.normalizeShellCommand("echo. done"))
        assertEquals("echo a && echo b", s.normalizeShellCommand("echo. a && echo. b"))
        // A longer token that merely starts with `echo.` is not the idiom.
        assertEquals("echo.enabled", s.normalizeShellCommand("echo.enabled"))
        assertTrue(s.isAllowed("echo."), s.explain("echo."))
    }

    @Test
    @DisplayName("set is allowed bare or with NAME=value assignments only")
    fun setAllowedForAssignments() {
        assumeTrue(isWindows, "cmd.exe builtin")
        val s = shell()

        assertTrue(s.isAllowed("set"), s.explain("set"))
        assertTrue(s.isAllowed("set PYTHONIOENCODING=utf-8"), s.explain("set PYTHONIOENCODING=utf-8"))
        assertTrue(s.isAllowed("set \"VAR=some value\""), s.explain("set \"VAR=some value\""))
        assertFalse(s.isAllowed("set /p VAR=Enter: "), "set /p reads input: ${s.explain("set /p VAR=Enter: ")}")
    }

    @Test
    @DisplayName("a command that would kill the backend or its JVM is refused")
    fun processKillsOfTheRuntimeAreRefused() {
        // 2026-10-29 Gate: a live run ran `taskkill /PID <backend> /F /T` after a
        // navigation failure; the backend died and the task ended with no report.
        val s = shell()
        val ownPid = ProcessHandle.current().pid()

        val ownPidKill = "taskkill /PID $ownPid /F /T"
        assertFalse(s.isAllowed(ownPidKill), s.explain(ownPidKill))
        assertTrue(s.explain(ownPidKill).contains("runtime executing this task"), s.explain(ownPidKill))
        assertTrue(s.explain(ownPidKill).contains(ownPid.toString()), s.explain(ownPidKill))

        for (command in listOf(
            "taskkill /IM java.exe /F",
            "taskkill /IM browser4-cli.exe /F",
            "Stop-Process -Id $ownPid -Force",
            "kill -9 $ownPid",
        )) {
            assertFalse(s.isAllowed(command), "$command must be refused: ${s.explain(command)}")
        }

        // A different explicit pid is not the runtime: the refusal must not be this one
        // (whether the shell allows taskkill at all depends on the profile/PATH).
        val otherPid = "taskkill /PID 999999 /F"
        assertFalse(
            s.explain(otherPid).contains("runtime executing this task"),
            "a non-runtime pid must not be refused for this reason: ${s.explain(otherPid)}",
        )
    }

    @Test
    @DisplayName("if/for are refused with an actionable hint, never executed")
    fun controlFlowGetsAHint() {
        assumeTrue(isWindows, "cmd.exe control flow")
        val s = shell()

        for (command in listOf("if exist out.csv echo yes", "for %f in (*.csv) do echo %f")) {
            val explanation = s.explain(command)
            assertFalse(s.isAllowed(command), "must stay refused: $command")
            assertTrue(
                explanation.contains("control-flow keyword"),
                "the refusal must explain the keyword: $explanation",
            )
            assertTrue(
                explanation.contains("cmd /c") || explanation.contains("python -c"),
                "the refusal must name an alternative: $explanation",
            )
        }
    }

    @Test
    @DisplayName("mkdir/md are ordinary workspace work, but the path boundary still applies")
    fun mkdirIsAllowedInsideTheWorkspace() {
        assumeTrue(isWindows, "cmd.exe builtin without a PATH entry")
        val s = shell()

        // A live run was refused with "command 'mkdir' is not allowed" while the CLI's own
        // docs recommend `mkdir -p ./downloads`; creating a directory inside the workspace is
        // what coding.mkdir already does, so the refusal bought nothing (2026-10-29).
        assertTrue(s.isAllowed("mkdir out"), s.explain("mkdir out"))
        assertTrue(s.isAllowed("md out"), s.explain("md out"))
        assertTrue(s.isAllowed("mkdir .b4scratch\\data"), s.explain("mkdir .b4scratch\\data"))
        // The path boundary is still enforced on the argument. (C:\Windows and the temp
        // directory are toolchain roots by design, so the probe path is a plain drive root.)
        val outside = "mkdir D:\\b4-outside-probe"
        assertFalse(s.isAllowed(outside), "outside the workspace: ${s.explain(outside)}")
    }

    /**
     * Plan P1-3 — the acceptance test for the ACL matrix.
     *
     * Opt-in because it needs the out-of-band provisioning (a local account plus its credential)
     * and the privilege to assign a token, neither of which a test may create itself. When it runs,
     * both guards are switched off (`allowExternalAccess`, `workspaceBoundaryEnabled`): the question
     * is whether *the OS* refuses the write, not whether the shell's lexical boundary does.
     */
    @Test
    @DisplayName("the sandbox account writes inside the workspace and is refused outside by the OS")
    fun sandboxDeniesOutsideWrites() = runBlocking {
        assumeTrue(isWindows, "the windows-acl sandbox is Windows only")
        assumeTrue(
            System.getProperty("browser4.agent.sandbox.test", "false").toBoolean(),
            "opt-in: provision the sandbox account, then run with -Dbrowser4.agent.sandbox.test=true",
        )

        val workspace = Files.createDirectories(tempDir.resolve("ws"))
        val inside = workspace.resolve("inside.txt")
        val outside = Path.of(System.getProperty("user.home"))
            .resolve("b4-sandbox-probe-${System.nanoTime()}.txt")
        val previous = System.getProperty(ExecutionSandboxes.PROP_SANDBOX)
        System.setProperty(ExecutionSandboxes.PROP_SANDBOX, "windows-acl")
        try {
            val s = CodingAgentShell(
                baseDir = workspace,
                allowExternalAccess = true,
                workspaceBoundaryEnabled = false,
            )

            val inResult = s.executeRaw("echo inside > \"$inside\"")
            if (inResult.stderr.contains("Windows sandbox unavailable")) {
                assumeTrue(false, "the sandbox is not provisioned: ${inResult.stderr}")
            }
            assertEquals(0, inResult.exitCode, "the workspace must stay writable: ${inResult.stderr}")
            assertTrue(Files.exists(inside), "the workspace must stay writable")

            val outResult = s.executeRaw("echo outside > \"$outside\"")
            assertNotEquals(
                0, outResult.exitCode,
                "the OS must refuse the write: ${outResult.stdout} ${outResult.stderr}",
            )
            assertFalse(Files.exists(outside), "no file may appear outside the workspace")
        } finally {
            if (previous == null) System.clearProperty(ExecutionSandboxes.PROP_SANDBOX)
            else System.setProperty(ExecutionSandboxes.PROP_SANDBOX, previous)
            Files.deleteIfExists(inside)
            Files.deleteIfExists(outside)
        }
    }

    @Test
    @DisplayName("cmd /c runs end to end through the shell")
    fun cmdEndToEnd() = runBlocking {
        assumeTrue(isWindows, "cmd.exe only exists on Windows")
        val s = shellIn(tempDir)
        val result = runInShell(s, "cmd /c echo b4-cmd-e2e")

        assertEquals(0, result.exitCode, result.stderr)
        assertTrue(result.stdout.contains("b4-cmd-e2e"), result.stdout)
    }

    @Test
    @DisplayName("powershell -Command runs end to end through the shell")
    fun powerShellEndToEnd() = runBlocking {
        assumeTrue(isWindows, "the Windows PowerShell only exists on Windows")
        assumeTrue(hasCommand("powershell"), "powershell.exe is not on PATH")
        val s = shellIn(tempDir)
        // `echo` (not `Write-Output`): the nested-script check only knows the allow-list names.
        val result = runInShell(s, "powershell.exe -NoProfile -NonInteractive -Command \"echo b4-pwsh-e2e\"")

        assertEquals(0, result.exitCode, result.stderr)
        assertTrue(result.stdout.contains("b4-pwsh-e2e"), result.stdout)
    }

    @Test
    @DisplayName("runCode runs an interpreter subprocess end to end")
    fun runCodeSubprocess() = runBlocking {
        val language = when {
            hasCommand("python") -> "python"
            hasCommand("node") -> "js"
            else -> null
        }
        assumeTrue(language != null, "neither python nor node is available")
        val code = if (language == "python") "print('b4-runcode-e2e')" else "console.log('b4-runcode-e2e')"

        val result = CodeRunner(defaultTimeoutSeconds = 20).run(language!!, code)
        assumeSandboxReady(result.stdout + result.stderr)

        assertEquals(0, result.exitCode, result.stderr)
        assertTrue(result.stdout.contains("b4-runcode-e2e"), result.stdout)
    }

    private fun shellIn(dir: Path) = CodingAgentShell(baseDir = dir, allowDevTools = true)

    /**
     * Runs [command] through the shell and aborts the test when `windows-acl` is selected but the
     * account is not provisioned — that is the documented hard failure, not a bug in this test.
     */
    private suspend fun runInShell(s: CodingAgentShell, command: String): ShellResult {
        val result = s.executeRaw(command)
        assumeSandboxReady(result.stdout + result.stderr)
        return result
    }

    private fun assumeSandboxReady(output: String) {
        assumeTrue(!output.contains("Windows sandbox unavailable"), "the sandbox is not provisioned: $output")
    }

    private fun hasCommand(command: String): Boolean =
        runCatching { ProcessBuilder(command, "--version").start().destroy() }.isSuccess
}
