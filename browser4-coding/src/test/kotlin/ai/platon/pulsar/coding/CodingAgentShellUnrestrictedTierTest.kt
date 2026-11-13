package ai.platon.pulsar.coding

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * `agent run --unrestricted` lifts the command **tier**, not only the syntax gate.
 *
 * Origin (2026-11-14, live supervision run): the task was started with
 * `./b4w -Rebuild agent run -i --coding --unrestricted`, the model-facing prompt promised that
 * inline scripts "run as written", and the first attempt to read a file —
 * `powershell -NoProfile -Command "Get-Content browser4-coding/..."` — came back
 * `PERMISSION_DENIED | code=COMMAND_NOT_ALLOWED ... inline script via 'powershell' rejected —
 * command 'get-content' is not allowed`. The syntax lift only ever covered the *syntax* family
 * ([CodingAgentShell.isSyntaxViolation]), so the inline body was still matched against the
 * SAFE/DEV tier — a list no PowerShell cmdlet is in, and `where Get-Content` finds nothing because a
 * cmdlet is not a PATH executable either.
 *
 * The tier is a spelling rule, not a boundary: the same body already ran unexamined through
 * `powershell -File`, and `python -c` is on the list. Under the flag the tier verdict is therefore
 * skipped — and *only* it: every veto checked ahead of that verdict (blocked patterns, explicit
 * deny, deny patterns, credential and protected paths, the runtime vetoes) still refuses, and the
 * build-only shell behind `coding.mvnBuild`/`verify` is not widened at all.
 */
class CodingAgentShellUnrestrictedTierTest {

    private fun shell(
        unrestricted: Boolean,
        baseDir: Path = Path.of(System.getProperty("user.dir")),
    ) = CodingAgentShell(
        baseDir = baseDir.toAbsolutePath(),
        allowDevTools = true,
        allowExecution = true,
        allowDestructive = true,
        allowExternalAccess = unrestricted,
        unrestricted = unrestricted,
        // An unrestricted policy clears these lists; the *always refused* credential stores are what
        // must keep refusing, so the tests below do not rely on the lists at all.
        protectedDirs = if (unrestricted) emptySet() else CodingAgentFileSystem.DEFAULT_PROTECTED_DIRS,
        sensitivePaths = if (unrestricted) emptySet() else CodingAgentFileSystem.DEFAULT_SENSITIVE_PATHS,
    )

    @Test
    @DisplayName("the tier verdict is exactly what the flag lifts, and nothing else")
    fun theTierVerdictIsWhatTheFlagLifts() {
        // No machine has this executable, so the PATH fallback cannot rescue it: the only question
        // left is the tier.
        val command = "b4-tier-probe --version"

        val restricted = shell(unrestricted = false)
        assertFalse(restricted.isAllowed(command), restricted.explain(command))
        assertTrue(
            restricted.explain(command).contains("is not allowed (safe=false"),
            "the refusal must be the tier verdict: ${restricted.explain(command)}",
        )

        val lifted = shell(unrestricted = true)
        assertTrue(lifted.isAllowed(command), lifted.explain(command))
        assertTrue(
            lifted.explain(command).contains("this task is unrestricted"),
            "an allowed-by-the-flag command must say why: ${lifted.explain(command)}",
        )
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    @DisplayName("the reported command form — powershell -Command Get-Content — reads the file")
    fun theReportedCommandRuns(@TempDir tempDir: Path) = runBlocking {
        Files.writeString(tempDir.resolve("note.txt"), "ALPHA-1")

        val result = shell(unrestricted = true, baseDir = tempDir).executeRaw(
            "powershell -NoProfile -Command \"Get-Content note.txt\"",
            workingDir = tempDir.toString(),
        )

        assertEquals(0, result.exitCode, result.stderr)
        assertTrue(result.stdout.contains("ALPHA-1"), result.stdout)
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    @DisplayName("without the flag the same inline body is still refused, with a useful hint")
    fun theReportedCommandIsRefusedWithoutTheFlag(@TempDir tempDir: Path) = runBlocking {
        Files.writeString(tempDir.resolve("note.txt"), "ALPHA-1")

        val refusal = shell(unrestricted = false, baseDir = tempDir).execute(
            "powershell -NoProfile -Command \"Get-Content note.txt\"",
            workingDir = tempDir.toString(),
        )

        assertTrue(refusal.contains(PermissionAudit.CODE_COMMAND_NOT_ALLOWED), refusal)
        assertTrue(refusal.contains("inline script via 'powershell' rejected"), refusal)
        assertTrue(
            refusal.contains("coding.read"),
            "the hint must name the cheap route: $refusal",
        )
        assertFalse(
            refusal.contains("coding.requestAccess"),
            "no approval widens the tier, so the hint must not offer one: $refusal",
        )
    }

    @Test
    @DisplayName("a liftable category veto still names the approval that does exist")
    fun categoryVetoNamesItsApproval() = runBlocking {
        val refusal = shell(unrestricted = false).execute("curl --version")

        assertTrue(refusal.contains("network access is off for this task"), refusal)
        assertTrue(refusal.contains("net.connect"), refusal)
    }

    @Test
    @DisplayName("unrestricted keeps the credential rail inside an inline script")
    fun unrestrictedKeepsTheCredentialRail() = runBlocking {
        val refusal = shell(unrestricted = true).execute(
            "powershell -NoProfile -Command \"Get-Content ~/.ssh/id_rsa\"",
        )

        assertTrue(refusal.contains(PermissionAudit.CODE_COMMAND_PATH_DENIED), refusal)
    }

    @Test
    @DisplayName("unrestricted still refuses to kill the runtime, and its hint offers no approval")
    fun unrestrictedKeepsTheRuntimeVetoes() = runBlocking {
        val ownPid = ProcessHandle.current().pid()
        val shell = shell(unrestricted = true)

        val kill = shell.execute("powershell -NoProfile -Command \"taskkill /PID $ownPid /F\"")
        assertTrue(kill.contains("would terminate the Browser4 backend"), kill)
        assertTrue(kill.contains("no approval lifts this"), kill)
        assertFalse(
            kill.contains("coding.requestAccess"),
            "a permanent veto must not offer an approval it can never grant: $kill",
        )

        val link = shell.execute("powershell -NoProfile -Command \"mklink /J a b\"")
        assertTrue(link.contains("workspace links are provisioned by the framework"), link)
        assertFalse(link.contains("coding.requestAccess"), link)

        val rebuild = shell.execute("powershell -NoProfile -Command \"b4w build\"")
        assertTrue(rebuild.contains("no approval lifts it"), rebuild)
        assertFalse(rebuild.contains("coding.requestAccess"), rebuild)
    }

    @Test
    @DisplayName("an operator's explicit deny keeps refusing under the flag, with no approval offered")
    fun explicitDenySurvives() = runBlocking {
        val shell = shell(unrestricted = true)
        shell.denyCommand("git")

        val refusal = shell.execute("git status")

        assertTrue(refusal.contains("is explicitly denied"), refusal)
        assertTrue(refusal.contains("no approval lifts it"), refusal)
        assertFalse(refusal.contains("coding.requestAccess"), refusal)
    }

    @Test
    @DisplayName("the policy switch is wired: an unrestricted policy builds a tier-lifted shell")
    fun unrestrictedPolicyBuildsATierLiftedShell(@TempDir tempDir: Path) {
        // `--unrestricted` has one source of truth (the policy). The shell builders must derive the
        // lift from it, otherwise the flag and the built shell drift apart — which is how the
        // 2026-11-14 refusal happened.
        val command = "get-content note.txt"

        val restricted = CodingComponents.shell(
            tempDir, AgentAccessPolicy.serverDefault.withTaskOverrides(AccessPreset.FULL),
        )
        assertFalse(restricted.isAllowed(command), restricted.explain(command))

        val lifted = CodingComponents.shell(tempDir, AgentAccessPolicy.serverDefault.withUnrestricted())
        assertTrue(lifted.isAllowed(command), lifted.explain(command))
    }

    @Test
    @DisplayName("the build-only shell behind coding.mvnBuild is never widened by the flag")
    fun buildOnlyShellIsNotWidened() {
        // That shell is not reachable by the model, and it must stay a build toolchain even when the
        // task is unrestricted — otherwise `read-build` would lose its only escape hatch.
        val buildOnly = CodingAgentShell(
            baseDir = Path.of(System.getProperty("user.dir")).toAbsolutePath(),
            allowDevTools = true,
            allowExecution = true,
            allowDestructive = true,
            buildOnly = true,
            unrestricted = true,
        )

        val command = "get-content README.md"
        assertFalse(buildOnly.isAllowed(command), buildOnly.explain(command))
    }
}
