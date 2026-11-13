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
 * The syntax gate is not a permission gate, and `agent run --unrestricted` relaxes both.
 *
 * Origin (2026-11-13): in an unrestricted run `for %f in (...) do ...` was still refused with
 * `UNSUPPORTED_SYNTAX | code=SYNTAX_NOT_SUPPORTED`. The refusal was correct by design — the
 * checker decomposes a command line to validate every program it can reach, and a `for` loop's
 * body is not the shell's to decompose — but under `--unrestricted` the task already runs with the
 * caller's own OS permissions, so a shape the checker cannot parse must be handed to the platform
 * shell instead of being refused. Policy refusals (blocked patterns, deny list, protected paths,
 * tier checks) keep applying: only the syntax family is lifted.
 *
 * The cmd.exe shapes below (`for`, `if`) are Windows-only — [CodingAgentShell] recognises them as
 * control flow only when `isWindowsHost()` — so those cases are `@EnabledOnOs(WINDOWS)` rather than
 * assertions that would fail on the Linux CI runners. The platform-neutral cases at the end keep
 * the lift itself and the vetoes that survive it covered everywhere: a suite that "passes" by being
 * skipped on every CI machine would protect nothing.
 */
class CodingAgentShellUnrestrictedSyntaxTest {

    private fun shell(allowUnsupportedSyntax: Boolean) = CodingAgentShell(
        baseDir = Path.of(System.getProperty("user.dir")).toAbsolutePath(),
        allowDevTools = true,
        allowExecution = true,
        allowDestructive = true,
        allowUnsupportedSyntax = allowUnsupportedSyntax,
    )

    @Test
    @EnabledOnOs(OS.WINDOWS)
    @DisplayName("without --unrestricted a control-flow shape stays a syntax refusal")
    fun defaultShellKeepsTheSyntaxGate() {
        val s = shell(allowUnsupportedSyntax = false)
        val command = "for %f in (*.csv) do echo %f"

        val explained = s.explain(command)
        assertTrue(explained.contains("NOT SUPPORTED"), explained)
        assertFalse(s.isAllowed(command), explained)
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    @DisplayName("--unrestricted lifts the syntax gate instead of answering NOT SUPPORTED")
    fun unrestrictedLiftsTheSyntaxGate() {
        val s = shell(allowUnsupportedSyntax = true)
        val command = "for %f in (*.csv) do echo %f"

        val explained = s.explain(command)
        assertFalse(explained.contains("NOT SUPPORTED"), explained)
        assertTrue(s.isAllowed(command), explained)
    }

    @Test
    @DisplayName("the syntax relaxation does not widen the policy: blocked patterns still refuse")
    fun policyRefusalsSurviveTheSyntaxRelaxation() {
        val s = shell(allowUnsupportedSyntax = true)

        val explained = s.explain("format c:")
        assertTrue(explained.contains("DENIED"), explained)
        assertFalse(s.isAllowed("format c:"), explained)
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    @DisplayName("a refused shape is counted as a syntax refusal only while the gate is on")
    fun liftedGateDoesNotCountSyntaxRefusals() = runBlocking {
        val command = "for %f in (*.csv) do echo %f"
        val before = UnsupportedSyntax.snapshot()["coding.shell"] ?: 0

        shell(allowUnsupportedSyntax = false).executeRaw(command)
        assertEquals(
            before + 1,
            UnsupportedSyntax.snapshot()["coding.shell"] ?: 0,
            "a syntax refusal is counted once by the restricted shell",
        )

        // The lifted gate never builds a refusal, so the syntax counter must not move — whatever
        // the platform shell makes of the line (it reports its own parse error, if any).
        shell(allowUnsupportedSyntax = true).executeRaw(command)
        assertEquals(
            before + 1,
            UnsupportedSyntax.snapshot()["coding.shell"] ?: 0,
            "the lifted gate never produces a refusal, so it counts nothing",
        )
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    @DisplayName("cmd.exe control flow actually runs under --unrestricted")
    fun unrestrictedRunsControlFlow(@TempDir tempDir: Path) = runBlocking {
        Files.writeString(tempDir.resolve("a.csv"), "x")
        val s = CodingAgentShell(
            baseDir = tempDir,
            allowDevTools = true,
            allowExecution = true,
            allowDestructive = true,
            allowUnsupportedSyntax = true,
        )

        val result = s.executeRaw("for %f in (*.csv) do echo b4-loop-%f", workingDir = tempDir.toString())
        assertEquals(0, result.exitCode, result.stderr)
        assertTrue(result.stdout.contains("b4-loop-a.csv"), result.stdout)
        assertFalse(result.stdout.contains(UnsupportedSyntax.PREFIX), result.stdout)
    }

    @DisplayName("the policy switch is wired: an unrestricted policy builds a lifted shell")
    @EnabledOnOs(OS.WINDOWS)
    @Test
    fun unrestrictedPolicyBuildsALiftedShell(@TempDir tempDir: Path) {
        // `--unrestricted` has exactly one source of truth (the policy), and the shell builders must
        // derive the syntax switch from it — otherwise the documented behaviour and the built shell
        // drift apart, which is how the 2026-11-13 refusal happened.
        val command = "for %f in (*.csv) do echo %f"

        val restricted = CodingComponents.shell(
            tempDir, AgentAccessPolicy.serverDefault.withTaskOverrides(AccessPreset.FULL),
        )
        assertFalse(restricted.isAllowed(command), restricted.explain(command))
        assertTrue(restricted.explain(command).contains("NOT SUPPORTED"), restricted.explain(command))

        val lifted = CodingComponents.shell(tempDir, AgentAccessPolicy.serverDefault.withUnrestricted())
        assertTrue(lifted.isAllowed(command), lifted.explain(command))
        assertFalse(lifted.explain(command).contains("NOT SUPPORTED"), lifted.explain(command))
    }

    // ------------------------------------------------------------------
    // Platform-neutral coverage: these run on the Linux CI runners too.
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the lift vacates the syntax family on every platform, not just on cmd.exe")
    fun liftedGateVacatesTheSyntaxFamilyOnEveryPlatform() {
        // `empty or invalid command` belongs to the same family as the cmd.exe shapes and is
        // produced on every platform, so it exercises the lift itself without depending on
        // `isWindowsHost()`.
        val restricted = shell(allowUnsupportedSyntax = false)
        val lifted = shell(allowUnsupportedSyntax = true)
        val blank = "   "

        assertFalse(restricted.isAllowed(blank), restricted.explain(blank))
        assertTrue(lifted.isAllowed(blank), lifted.explain(blank))
    }

    @Test
    @DisplayName("the runtime guard still sees a program hidden in an undecomposable body")
    fun runtimeGuardSeesProgramsInsideUndecomposableBodies() {
        // The guard is the part of the lift that protects the run's own runtime; it is
        // platform-independent, so it is asserted directly rather than through `isAllowed` (which
        // only reaches it through a syntax refusal, and the cmd.exe refusals are Windows-only).
        val s = shell(allowUnsupportedSyntax = true)
        val ownPid = ProcessHandle.current().pid()

        assertNotNull(
            s.liftedSyntaxGuardViolation("for %f in (1) do mklink link target"),
            "FORBIDDEN_COMMANDS inside a loop body",
        )
        assertNotNull(
            s.liftedSyntaxGuardViolation("for %f in (1) do taskkill /PID $ownPid /F"),
            "killing the JVM that runs the task",
        )
        assertNotNull(
            s.liftedSyntaxGuardViolation("for %f in (1) do bin/build.ps1 -SkipTests"),
            "rebuilding the runtime from a loop body",
        )
        assertNotNull(
            s.liftedSyntaxGuardViolation("for %f in (1) do (mklink link target)"),
            "a veto must not hide behind block parentheses",
        )
        assertNotNull(
            s.liftedSyntaxGuardViolation("for %f in (1) do cmd /c \"taskkill /PID $ownPid /F\""),
            "the inline-script body is scanned recursively",
        )
        assertNull(
            s.liftedSyntaxGuardViolation("for %f in (*.csv) do echo %f"),
            "an ordinary loop must not be refused",
        )
        assertNull(
            s.liftedSyntaxGuardViolation("for %f in (*.csv) do (echo %f)"),
            "a benign parenthesised body must not be refused",
        )
        assertNull(
            s.liftedSyntaxGuardViolation("echo b4w build failed"),
            "a word in argument position is not a program",
        )
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
            allowUnsupportedSyntax = true,
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
