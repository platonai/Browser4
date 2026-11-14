package ai.platon.pulsar.coding

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * There is no syntax gate: a shape the decomposer cannot parse is handed to the platform shell.
 *
 * Origin: the shell used to refuse `for %f in (...) do ...` with
 * `UNSUPPORTED_SYNTAX | code=SYNTAX_NOT_SUPPORTED` — first always, and later only outside
 * `--unrestricted`. The refusal answered a question the model never asked: the decomposer must
 * name every program a command line can reach, but a `for` body is not its to decompose, and no
 * preset or approval could ever make the shape runnable. The family is gone.
 *
 * The line is now handed over verbatim and the platform shell reports its own parse error. What
 * survives is [CodingAgentShell.handOffVetoViolation]: the vetoes that do not need the grammar —
 * forbidden link/mount tools, killing or rebuilding the runtime the task runs inside, and a
 * disabled network/destructive tier — are re-checked at every program position. Those are policy
 * refusals and stay policy refusals.
 *
 * The cmd.exe shapes (`for`, `if`) are Windows-only — [CodingAgentShell] recognises them as
 * control flow only when `isWindowsHost()` — so those cases are `@EnabledOnOs(WINDOWS)` rather than
 * assertions that would fail on the Linux CI runners. The platform-neutral cases keep the hand-off
 * and its vetoes covered everywhere.
 */
class CodingAgentShellUnparsedHandOffTest {

    private fun shell(
        unrestricted: Boolean = false,
        baseDir: Path = Path.of(System.getProperty("user.dir")).toAbsolutePath(),
    ) = CodingAgentShell(
        baseDir = baseDir,
        allowDevTools = true,
        allowExecution = true,
        allowDestructive = true,
        unrestricted = unrestricted,
    )

    @Test
    @EnabledOnOs(OS.WINDOWS)
    @DisplayName("a control-flow shape is handed to the platform shell, in both modes")
    fun controlFlowIsHandedToThePlatformShell() {
        val command = "for %f in (*.csv) do echo %f"

        for (unrestricted in listOf(false, true)) {
            val s = shell(unrestricted)
            val explained = s.explain(command)

            assertTrue(s.isAllowed(command), explained)
            assertTrue(explained.contains("handed to the platform shell"), explained)
            assertFalse(explained.contains("NOT SUPPORTED"), explained)
        }
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    @DisplayName("unsupported if shapes are handed over; there is no separate refusal family")
    fun unsupportedConditionalsAreHandedOver() {
        val s = shell()

        for (command in listOf(
            "if exist out.csv",                                        // no payload
            "if exist out.csv (echo yes) else if exist a (echo no)",   // `else if`
            "if exist out.csv (echo a & echo b)",                      // chain inside a block
            "if exist out.csv goto :eof",                              // label jump
            "if exist out.csv call :run",
            "if exist out.csv (echo yes) trailing",                    // tokens after the block
        )) {
            assertTrue(s.isAllowed(command), "must be handed over: $command — ${s.explain(command)}")
            assertTrue(
                s.explain(command).contains("handed to the platform shell"),
                s.explain(command),
            )
        }
    }

    @Test
    @DisplayName("the hand-off does not widen the policy: blocked patterns still refuse")
    fun policyRefusalsSurviveTheHandOff() {
        val s = shell(unrestricted = true)

        val explained = s.explain("format c:")
        assertTrue(explained.contains("DENIED"), explained)
        assertFalse(s.isAllowed("format c:"), explained)
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    @DisplayName("cmd.exe control flow actually runs through the platform shell")
    fun controlFlowRuns(@TempDir tempDir: Path) = runBlocking {
        Files.writeString(tempDir.resolve("a.csv"), "x")

        for (unrestricted in listOf(false, true)) {
            val s = shell(unrestricted, tempDir)
            val result = s.executeRaw("for %f in (*.csv) do echo b4-loop-%f", workingDir = tempDir.toString())

            assertEquals(0, result.exitCode, result.stderr)
            assertTrue(result.stdout.contains("b4-loop-a.csv"), result.stdout)
        }
    }

    @Test
    @DisplayName("the hand-off vetoes still see a program hidden in an undecomposable body")
    fun handOffVetoesSeeProgramsInsideUndecomposableBodies() {
        val s = shell(unrestricted = true)
        val ownPid = ProcessHandle.current().pid()

        assertNotNull(
            s.handOffVetoViolation("for %f in (1) do mklink link target"),
            "FORBIDDEN_COMMANDS inside a loop body",
        )
        assertNotNull(
            s.handOffVetoViolation("for %f in (1) do taskkill /PID $ownPid /F"),
            "killing the JVM that runs the task",
        )
        assertNotNull(
            s.handOffVetoViolation("for %f in (1) do bin/build.ps1 -SkipTests"),
            "rebuilding the runtime from a loop body",
        )
        assertNotNull(
            s.handOffVetoViolation("for %f in (1) do (mklink link target)"),
            "a veto must not hide behind block parentheses",
        )
        assertNotNull(
            s.handOffVetoViolation("for %f in (1) do cmd /c \"taskkill /PID $ownPid /F\""),
            "the inline-script body is scanned recursively",
        )
        assertNotNull(
            s.handOffVetoViolation("for %f in (1) do cat ~/.ssh/id_rsa"),
            "the credential guard applies to every segment, including an undecomposable body",
        )
        assertNull(
            s.handOffVetoViolation("for %f in (*.csv) do echo %f"),
            "an ordinary loop must not be refused",
        )
        assertNull(
            s.handOffVetoViolation("for %f in (*.csv) do (echo %f)"),
            "a benign parenthesised body must not be refused",
        )
        assertNull(
            s.handOffVetoViolation("echo b4w build failed"),
            "a word in argument position is not a program",
        )
    }

    @Test
    @DisplayName("a disabled tier still refuses a program the decomposer could not reach")
    fun disabledTierStillRefusesInsideUndecomposableBodies() {
        val s = CodingAgentShell(
            baseDir = Path.of(System.getProperty("user.dir")).toAbsolutePath(),
            allowDevTools = true,
            allowExecution = true,
            allowNetwork = false,
            allowDestructive = false,
        )

        assertNotNull(
            s.handOffVetoViolation("for %f in (1) do curl http://example.com"),
            "network commands are vetoes while the network tier is disabled",
        )
        assertNotNull(
            s.handOffVetoViolation("for %f in (1) do del out.csv"),
            "destructive commands are vetoes while that tier is disabled",
        )
        assertNull(
            s.handOffVetoViolation("for %f in (1) do echo ok"),
            "a safe program inside an undecomposable body is allowed",
        )
    }

    @Test
    @DisplayName("a blank command stays an ordinary refusal — there is nothing to hand over")
    fun blankCommandStaysRefused() {
        for (unrestricted in listOf(false, true)) {
            val s = shell(unrestricted)
            assertFalse(s.isAllowed("   "), s.explain("   "))
        }
    }

    @Test
    @DisplayName("the credential rail survives --unrestricted, and its hint does not invent a boundary")
    fun credentialRailSurvivesUnrestricted() = runBlocking {
        // `ALWAYS_REFUSED` is checked ahead of the policy's (now empty) sensitive list, so an
        // unrestricted task still cannot read a key into its context — `coding.permissions` prints
        // that list as `always refused`. Only the *policy* rails are lifted.
        val unrestricted = CodingAgentShell(
            baseDir = Path.of(System.getProperty("user.dir")).toAbsolutePath(),
            allowDevTools = true,
            allowExecution = true,
            allowDestructive = true,
            unrestricted = true,
            allowExternalAccess = true,
            sensitivePaths = emptySet(),
            protectedDirs = emptySet(),
        )

        val refused = unrestricted.executeRaw("echo ~/.ssh/id_rsa")

        assertTrue(refused.stderr.contains("credential location"), refused.stderr)
        assertTrue(
            refused.stderr.contains("hard rail even where the workspace boundary is off"),
            "the hint must describe the credential rail, not a boundary that is gone: ${refused.stderr}",
        )
        assertFalse(
            refused.stderr.contains("pass a path inside the workspace"),
            "an unrestricted task has no workspace boundary to pass: ${refused.stderr}",
        )
    }
}
