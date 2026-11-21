package ai.platon.pulsar.coding.sandbox

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * `windows-low-il` — the coding-mode boundary, and the answer to "is a separate account required?".
 *
 * The backend must prove three things, and each one is a test: the workspace is label-provisioned,
 * the probe refuses an outside write (no silently-unconfined launch), and the token really is
 * inherited by a child process — which is what makes real programs, not just `coding.shell` calls,
 * subject to the boundary.
 */
@DisplayName("windows low-integrity sandbox")
class WindowsLowIntegritySandboxTest {

    @TempDir
    lateinit var tempDir: Path

    private val isWindows = System.getProperty("os.name", "").lowercase().contains("win")

    private class FakeRunner(private val handler: (List<String>) -> SandboxCommandResult) : SandboxCommandRunner {
        val calls = mutableListOf<List<String>>()

        override fun run(command: List<String>, timeoutSeconds: Long): SandboxCommandResult {
            calls += command
            return handler(command)
        }
    }

    private fun sandbox(
        runner: SandboxCommandRunner,
        probe: ((Path) -> String?)? = { null },
    ) = WindowsLowIntegritySandbox(
        runner = runner,
        probe = probe,
        caches = { emptyList() },
        protectCredentialStores = false,
        // Tests label their own throwaway trees; no marker files in the user data dir.
        markerDir = null,
        // The G9 self-check is exercised by its own tests; here it would only add `icacls` calls to
        // the fake runner that the label assertions count.
        aceRoots = { emptyList() },
        aceScanner = { emptyList() },
    )

    private fun spec(workspace: Path) = ProcessSpec(
        command = listOf("cmd.exe", "/c", "echo hi"),
        directory = workspace,
        environment = mapOf("PATH" to "C:\\Windows\\System32"),
    )

    @Test
    @DisplayName("release restores the task's label to Medium, and only once")
    fun releaseRestoresTheMediumLabel() {
        // `prepare()` refuses off Windows (SandboxUnavailableException) — that refusal is asserted by
        // `refusesOffWindows`; this case asserts the label work, which needs `icacls`.
        assumeTrue(isWindows, "icacls only exists on Windows")
        // Review S2: the Low token is shared by every task, so a workspace left Low is writable by
        // any later task. The restore is what bounds the grant to the task's own lifetime.
        val runner = FakeRunner { SandboxCommandResult(0) }
        val workspace = Files.createDirectories(tempDir.resolve("ws"))
        val sandbox = sandbox(runner)
        sandbox.prepare(spec(workspace), workspace, allowNetwork = false)

        runner.calls.clear()
        sandbox.release(workspace)

        val restore = runner.calls.firstOrNull { it.contains("(OI)(CI)Medium") }
        assertTrue(restore != null, "the task's label must be restored: ${runner.calls}")
        assertEquals(workspace.toAbsolutePath().normalize().toString(), restore!![1])

        runner.calls.clear()
        sandbox.release(workspace)
        assertTrue(
            runner.calls.none { it.contains("(OI)(CI)Medium") },
            "a second release must be a no-op: ${runner.calls}",
        )
    }

    /** `mklink /J` needs no elevation; `Files.createSymbolicLink` does. */
    private fun createJunction(link: Path, target: Path) {
        val process = ProcessBuilder("cmd.exe", "/c", "mklink", "/J", link.toString(), target.toString())
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.readBytes().toString(Charsets.UTF_8)
        assertEquals(0, process.waitFor(), "mklink /J failed: $output")
    }

    @Test
    @DisplayName("off Windows the backend refuses to prepare instead of degrading")
    fun refusesOffWindows() {
        val previous = System.getProperty("os.name")
        System.setProperty("os.name", "Linux")
        try {
            val runner = FakeRunner { SandboxCommandResult(0) }
            val error = assertThrows(SandboxUnavailableException::class.java) {
                sandbox(runner).prepare(spec(tempDir), tempDir, allowNetwork = false)
            }
            assertTrue(error.message!!.contains(WindowsLowIntegritySandbox.SANDBOX_ID), error.message!!)
            assertFalse(error.message!!.contains("falling back"), "must not advertise a fallback")
        } finally {
            if (previous == null) System.clearProperty("os.name") else System.setProperty("os.name", previous)
        }
    }

    @Test
    @DisplayName("prepare labels the workspace Low, redirects TEMP into it, and wires the token launcher")
    fun prepareProvisionsAndWiresTheLauncher() {
        assumeTrue(isWindows, "icacls only exists on Windows")
        val workspace = Files.createDirectories(tempDir.resolve("ws"))
        val runner = FakeRunner { SandboxCommandResult(0) }

        val prepared = sandbox(runner).prepare(spec(workspace), workspace, allowNetwork = false)

        val label = runner.calls.firstOrNull { call ->
            call.firstOrNull() == "icacls" && call[1] == workspace.toString()
        }
        assertNotNull(label, "the workspace must be labelled: ${runner.calls}")
        assertEquals("(OI)(CI)Low", label!![3], "the workspace is labelled deeply, inheritance included")
        assertEquals(workspace.resolve(".b4scratch").resolve("tmp").toString(), prepared.environment["TEMP"])
        assertEquals(prepared.environment["TEMP"], prepared.environment["TMP"])
        assertNotNull(prepared.sandboxLauncher, "a token backend must own the launch")
        assertNull(prepared.sandboxAccount, "no account is involved")
    }

    @Test
    @DisplayName("a failed probe is a hard failure, never an unconfined launch")
    fun failedProbeIsHardFailure() {
        assumeTrue(isWindows, "icacls only exists on Windows")
        val workspace = Files.createDirectories(tempDir.resolve("ws-fail"))
        val runner = FakeRunner { SandboxCommandResult(0) }

        val error = assertThrows(SandboxUnavailableException::class.java) {
            sandbox(runner) { "a write OUTSIDE the workspace succeeded" }
                .prepare(spec(workspace), workspace, allowNetwork = false)
        }

        assertTrue(error.message!!.contains("low-integrity probe failed"), error.message!!)
        assertTrue(error.message!!.contains("OUTSIDE"), error.message!!)
    }

    @Test
    @DisplayName("the real token confines a child process: write inside succeeds, write outside is denied")
    fun realChildIsConfined() {
        assumeTrue(isWindows, "Windows tokens only")
        // The workspace must live where the user holds WRITE_OWNER: an integrity label can only be
        // lowered there, and `%TEMP%` on this machine inherits Modify-only from a shared directory.
        // That is also exactly where the product puts a task workspace (`<app data>/agent/...`).
        val root = Files.createDirectories(
            Path.of(System.getProperty("user.home"), ".browser4", "tmp", "low-il-test-${System.nanoTime()}"),
        )
        val workspace = Files.createDirectories(root.resolve("ws"))
        // Real token, real icacls labels, real probe; only the cache/credential roots are stubbed
        // so the test does not touch the developer's ~/.m2 and ~/.ssh.
        val sandbox = WindowsLowIntegritySandbox(
            caches = { emptyList() },
            protectCredentialStores = false,
            markerDir = null,
        )
        val runner = SystemSandboxCommandRunner
        try {
            // prepare() runs the three-part probe and throws when the boundary does not hold.
            val prepared = sandbox.prepare(spec(workspace), workspace, allowNetwork = false)
            assertNotNull(prepared.sandboxLauncher)

            val inside = workspace.resolve("inside.txt")
            val outside = root.resolve("outside.txt")
            val insideResult = sandbox.writeFileAsLow(workspace, inside)
            val outsideResult = sandbox.writeFileAsLow(workspace, outside)

            assertEquals(0, insideResult.first, "a workspace write must work: ${insideResult.second}")
            assertTrue(Files.exists(inside), "the inside file must exist")
            assertFalse(Files.exists(outside), "a write outside the workspace must not happen")
            assertTrue(outsideResult.first != 0, "the outside write must be refused: ${outsideResult.second}")

            // The task temp is the one this backend labelled Low; the token's `%TEMP%` is Medium and
            // would be unusable for the child (compilers and scripts write there).
            val temp = sandbox.runAsLowWithOutput(workspace, "echo %TEMP%")
            assertEquals(0, temp.first, temp.second)
            assertTrue(
                temp.second.trim().equals(
                    workspace.resolve(".b4scratch").resolve("tmp").toString(),
                    ignoreCase = true,
                ),
                "the child must get the task's own temp: ${temp.second}",
            )
        } finally {
            // Put the labels back so the temporary tree can be deleted.
            runner.run(listOf("icacls", root.toString(), "/setintegritylevel", "(OI)(CI)Medium"), 30)
            runCatching { root.toFile().deleteRecursively() }
        }
    }

    /**
     * The structural veto ("do not kill the runtime") used to be a command-name whitelist. With a Low
     * child and a Medium backend, the kernel enforces it: terminating a higher-integrity process is a
     * write-up, so `taskkill` from the sandbox fails even though the account owns both processes.
     */
    @Test
    @DisplayName("a sandboxed child cannot terminate a Medium process (the backend is one)")
    fun childCannotTerminateAMediumProcess() {
        assumeTrue(isWindows, "process integrity only exists on Windows")
        val root = Files.createDirectories(
            Path.of(System.getProperty("user.home"), ".browser4", "tmp", "low-il-kill-${System.nanoTime()}"),
        )
        val workspace = Files.createDirectories(root.resolve("ws"))
        val sandbox = WindowsLowIntegritySandbox(
            caches = { emptyList() },
            protectCredentialStores = false,
            markerDir = null,
        )
        // A Medium-IL stand-in for the backend: the sandbox child must not be able to end it.
        val victim = ProcessBuilder("cmd.exe", "/c", "ping -n 30 127.0.0.1 > nul")
            .redirectErrorStream(true)
            .start()
        try {
            sandbox.prepare(spec(workspace), workspace, allowNetwork = false)

            val result = sandbox.runAsLowWithOutput(workspace, "taskkill /PID ${victim.pid()} /F")

            assertTrue(result.first != 0, "taskkill must be refused: ${result.second}")
            assertTrue(victim.isAlive, "the Medium process must survive the attempt")
        } finally {
            victim.destroyForcibly()
            runCatching { root.toFile().deleteRecursively() }
        }
    }

    /**
     * The backend creates and labels `.b4scratch/tmp` with its own (Medium) rights, so a junction
     * there would lower the label of a directory outside the workspace and hand the Low child a write
     * channel into it (measured before this guard existed).
     */
    @Test
    @DisplayName("a scratch path that is a junction is refused before anything is created outside")
    fun scratchJunctionIsRefused() {
        assumeTrue(isWindows, "junctions only exist on Windows")
        val root = Files.createDirectories(
            Path.of(System.getProperty("user.home"), ".browser4", "tmp", "low-il-link-${System.nanoTime()}"),
        )
        val workspace = Files.createDirectories(root.resolve("ws"))
        val outside = Files.createDirectories(root.resolve("outside"))
        createJunction(workspace.resolve(".b4scratch"), outside)
        val sandbox = WindowsLowIntegritySandbox(
            caches = { emptyList() },
            protectCredentialStores = false,
            markerDir = null,
        )
        try {
            val error = assertThrows(SandboxUnavailableException::class.java) {
                sandbox.prepare(spec(workspace), workspace, allowNetwork = false)
            }
            assertTrue(error.message!!.contains("scratch"), error.message!!)
            assertFalse(Files.exists(outside.resolve("tmp")), "nothing may be created through the junction")
        } finally {
            val link = workspace.resolve(".b4scratch")
            if (Files.exists(link, java.nio.file.LinkOption.NOFOLLOW_LINKS)) (link.toFile()).delete()
            runCatching { root.toFile().deleteRecursively() }
        }
    }

    @Test
    @DisplayName("D1: a credential store is unreadable to the child and still readable to the developer")
    fun credentialStoreStaysUnreadable() {
        assumeTrue(isWindows, "mandatory labels only exist on Windows")
        val root = Files.createDirectories(
            Path.of(System.getProperty("user.home"), ".browser4", "tmp", "low-il-cred-${System.nanoTime()}"),
        )
        val secrets = Files.createDirectories(root.resolve("secrets"))
        Files.writeString(secrets.resolve("id_rsa"), "PRIVATE-KEY-MARKER")
        val workspace = Files.createDirectories(root.resolve("ws"))
        val sandbox = WindowsLowIntegritySandbox(
            caches = { emptyList() },
            credentialStores = listOf(secrets.toString()),
            markerDir = null,
        )
        try {
            sandbox.prepare(spec(workspace), workspace, allowNetwork = false)

            val asLow = sandbox.listDirAsLow(workspace, secrets)
            assertTrue(asLow.first != 0, "the child must not list a credential store: ${asLow.second}")
            // The label is Medium + NO_READ_UP: same-level processes (the developer's own shell) keep
            // their access, so protecting the secret does not break the machine.
            assertEquals("PRIVATE-KEY-MARKER", Files.readString(secrets.resolve("id_rsa")))
        } finally {
            SystemSandboxCommandRunner.run(
                listOf("icacls", secrets.toString(), "/setintegritylevel", "(OI)(CI)Medium"),
                30,
            )
            SystemSandboxCommandRunner.run(
                listOf("icacls", root.toString(), "/setintegritylevel", "(OI)(CI)Medium"),
                30,
            )
            runCatching { root.toFile().deleteRecursively() }
        }
    }

    @Test
    @DisplayName("a refused label is repaired by granting OWNER RIGHTS and retried exactly once")
    fun refusedLabelIsRepairedByGrantingOwnerRights() {
        assumeTrue(isWindows, "icacls only exists on Windows")
        val workspace = Files.createDirectories(tempDir.resolve("ws-heal"))
        var labels = 0
        val runner = FakeRunner { command ->
            when {
                command.contains("/setintegritylevel") && command[1] == workspace.toString() -> {
                    labels++
                    if (labels == 1) SandboxCommandResult(5, "Access is denied.") else SandboxCommandResult(0)
                }
                else -> SandboxCommandResult(0)
            }
        }

        sandbox(runner).prepare(spec(workspace), workspace, allowNetwork = false)

        val grant = runner.calls.single { it.contains("/grant") && it[1] == workspace.toString() }
        assertEquals("*S-1-3-4:(OI)(CI)(WDAC,WO)", grant.last())
        assertEquals(2, labels, "the label is retried once, not in a loop: ${runner.calls}")
    }

    @Test
    @DisplayName("when the owner grant is refused too, prepare still fails hard on the label")
    fun refusedLabelWithoutGrantStaysFatal() {
        assumeTrue(isWindows, "icacls only exists on Windows")
        val workspace = Files.createDirectories(tempDir.resolve("ws-nogrant"))
        val runner = FakeRunner { command ->
            when {
                command.contains("/grant") -> SandboxCommandResult(1, "Access is denied.")
                command.contains("/setintegritylevel") && command[1] == workspace.toString() ->
                    SandboxCommandResult(5, "Access is denied.")
                else -> SandboxCommandResult(0)
            }
        }

        val error = assertThrows(SandboxUnavailableException::class.java) {
            sandbox(runner).prepare(spec(workspace), workspace, allowNetwork = false)
        }

        assertTrue(error.message!!.contains("cannot lower the mandatory label"), error.message!!)
    }

    // ------------------------------------------------------------------
    // G9: the self-check that names a stale AppContainer grant
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a stale container ACE on a toolchain tree becomes an advisory note")
    fun staleContainerAceIsReported() {
        val stale = Files.createDirectories(tempDir.resolve("stale-toolchain"))
        val sid = "S-1-15-2-1-2-3-4-5-6-7-8"
        val sandbox = WindowsLowIntegritySandbox(
            runner = FakeRunner { SandboxCommandResult(0) },
            probe = { null },
            caches = { emptyList() },
            protectCredentialStores = false,
            markerDir = null,
            aceRoots = { listOf(stale) },
            aceScanner = { roots ->
                ContainerAceScan
                    .scan(roots, FakeRunner { SandboxCommandResult(0, "$sid:(OI)(CI)(RX)") })
                    .map { ContainerAceScan.warning(it) }
            },
        )

        val notes = sandbox.advisoryNotes()

        assertEquals(1, notes.size, "one finding, one line: $notes")
        assertTrue(notes.single().contains(stale.toString()), notes.single())
        assertTrue(notes.single().contains("/remove"), "the note carries the fix: ${notes.single()}")
    }

    @Test
    @DisplayName("a clean installation, and the switch off, report nothing")
    fun cleanInstallationReportsNothing() {
        val clean = Files.createDirectories(tempDir.resolve("clean-toolchain"))
        val quiet = WindowsLowIntegritySandbox(
            runner = FakeRunner { SandboxCommandResult(0) },
            probe = { null },
            caches = { emptyList() },
            protectCredentialStores = false,
            markerDir = null,
            aceRoots = { listOf(clean) },
            aceScanner = { emptyList() },
        )
        assertTrue(quiet.advisoryNotes().isEmpty(), "a clean tree has nothing to report")

        val previous = System.getProperty(WindowsLowIntegritySandbox.PROP_CHECK_CONTAINER_ACES)
        try {
            System.setProperty(WindowsLowIntegritySandbox.PROP_CHECK_CONTAINER_ACES, "off")
            val disabled = WindowsLowIntegritySandbox(
                runner = FakeRunner { SandboxCommandResult(0) },
                probe = { null },
                caches = { emptyList() },
                protectCredentialStores = false,
                markerDir = null,
                aceRoots = { listOf(clean) },
                aceScanner = { error("the scan must not run when the switch is off") },
            )
            assertTrue(disabled.advisoryNotes().isEmpty(), "off disables the scan entirely")
        } finally {
            if (previous == null) System.clearProperty(WindowsLowIntegritySandbox.PROP_CHECK_CONTAINER_ACES)
            else System.setProperty(WindowsLowIntegritySandbox.PROP_CHECK_CONTAINER_ACES, previous)
        }
    }
}
