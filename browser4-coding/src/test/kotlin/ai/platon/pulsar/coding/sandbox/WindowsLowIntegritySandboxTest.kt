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
    )

    private fun spec(workspace: Path) = ProcessSpec(
        command = listOf("cmd.exe", "/c", "echo hi"),
        directory = workspace,
        environment = mapOf("PATH" to "C:\\Windows\\System32"),
    )

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
}