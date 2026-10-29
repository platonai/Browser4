package ai.platon.pulsar.coding

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
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
 */
@DisplayName("cmd.exe built-ins")
class CodingAgentShellWindowsBuiltinTest {

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
}
