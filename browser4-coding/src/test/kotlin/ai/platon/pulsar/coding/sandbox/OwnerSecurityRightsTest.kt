package ai.platon.pulsar.coding.sandbox

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
 * The OWNER RIGHTS repair: the one ACE that turns "the owner cannot write a mandatory label"
 * into "the owner can", and the reason a worktree created under `%TEMP%`/`D:\` can still run a
 * `windows-low-il` task (see `docs/agent-permissions.md` §10).
 */
@DisplayName("owner security rights")
class OwnerSecurityRightsTest {

    @TempDir
    lateinit var tempDir: Path

    private class FakeRunner(private val result: SandboxCommandResult) : SandboxCommandRunner {
        val calls = mutableListOf<List<String>>()

        override fun run(command: List<String>, timeoutSeconds: Long): SandboxCommandResult {
            calls += command
            return result
        }
    }

    @Test
    @DisplayName("a directory is granted WDAC/WO to the owner, inheritable to later worktrees")
    fun directoryGrantIsInheritable() {
        val argv = OwnerSecurityRights.command(tempDir, isDirectory = true)

        assertEquals("icacls", argv[0])
        assertEquals(tempDir.toAbsolutePath().normalize().toString(), argv[1])
        assertEquals("/grant", argv[2])
        assertEquals("*S-1-3-4:(OI)(CI)(WDAC,WO)", argv[3], "owner-only, inheritable, no other right")
    }

    @Test
    @DisplayName("a file object is granted the same rights without inheritance flags")
    fun fileGrantHasNoInheritanceFlags() {
        val argv = OwnerSecurityRights.command(tempDir.resolve("a.txt"), isDirectory = false)

        assertEquals("*S-1-3-4:(WDAC,WO)", argv[3])
    }

    @Test
    @DisplayName("off Windows the repair is skipped instead of running icacls")
    fun skippedOffWindows() {
        val previous = System.getProperty("os.name")
        System.setProperty("os.name", "Linux")
        try {
            val runner = FakeRunner(SandboxCommandResult(0))
            assertFalse(OwnerSecurityRights.grant(tempDir, runner), "no labels exist off Windows")
            assertTrue(runner.calls.isEmpty(), "icacls must not run: ${runner.calls}")
        } finally {
            if (previous == null) System.clearProperty("os.name") else System.setProperty("os.name", previous)
        }
    }

    @Test
    @DisplayName("a real grant is what lets the same directory accept a Low label")
    fun realGrantMakesTheDirectoryLabelable() {
        assumeTrue(OwnerSecurityRights.isWindows(), "icacls only exists on Windows")
        val dir = Files.createDirectories(tempDir.resolve("ws"))
        assertTrue(OwnerSecurityRights.grant(dir), "the owner can write the ACL of its own directory")

        val label = SystemSandboxCommandRunner.run(
            listOf("icacls", dir.toString(), "/setintegritylevel", "(OI)(CI)Low"),
            30,
        )
        assertEquals(0, label.exitCode, "the label must be writable: ${label.output}")
        // Put the label back so the temp tree deletes cleanly on every host.
        SystemSandboxCommandRunner.run(
            listOf("icacls", dir.toString(), "/setintegritylevel", "(OI)(CI)Medium"),
            30,
        )
    }
}
