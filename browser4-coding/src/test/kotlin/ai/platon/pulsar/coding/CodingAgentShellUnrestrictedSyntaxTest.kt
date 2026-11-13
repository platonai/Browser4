package ai.platon.pulsar.coding

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
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
 */
class CodingAgentShellUnrestrictedSyntaxTest {

    private val isWindows = System.getProperty("os.name", "").lowercase().contains("win")

    private fun shell(allowUnsupportedSyntax: Boolean) = CodingAgentShell(
        baseDir = Path.of(System.getProperty("user.dir")).toAbsolutePath(),
        allowDevTools = true,
        allowExecution = true,
        allowDestructive = true,
        allowUnsupportedSyntax = allowUnsupportedSyntax,
    )

    @Test
    @DisplayName("without --unrestricted a control-flow shape stays a syntax refusal")
    fun defaultShellKeepsTheSyntaxGate() {
        val s = shell(allowUnsupportedSyntax = false)
        val command = "for %f in (*.csv) do echo %f"

        val explained = s.explain(command)
        assertTrue(explained.contains("NOT SUPPORTED"), explained)
        assertFalse(s.isAllowed(command), explained)
    }

    @Test
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
    @DisplayName("cmd.exe control flow actually runs under --unrestricted")
    fun unrestrictedRunsControlFlow(@TempDir tempDir: Path) = runBlocking {
        assumeTrue(isWindows, "cmd.exe control flow")

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
}
