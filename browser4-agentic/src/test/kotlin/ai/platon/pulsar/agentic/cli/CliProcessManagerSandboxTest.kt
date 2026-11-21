package ai.platon.pulsar.agentic.cli

import ai.platon.pulsar.coding.sandbox.ExecutionSandbox
import ai.platon.pulsar.coding.sandbox.ProcessSpec
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * `b4.run` on the task's OS boundary.
 *
 * The CLI used to spawn at the backend's own integrity level, which made it the widest remaining
 * agent channel: a sandboxed task could still write anywhere the CLI can. These tests pin the two
 * decisions that make the sandboxed launch work without breaking the CLI:
 *
 *  - its state goes to `BROWSER4_CLI_STATE_DIR` inside the workspace (the Medium `~/.browser4` is not
 *    writable for a sandboxed child), and
 *  - the working directory stays what the request asked for, falling back to the workspace.
 */
@DisplayName("b4.run sandboxed launch")
class CliProcessManagerSandboxTest {

    @TempDir
    lateinit var tempDir: Path

    private fun spec(workingDir: Path? = null, env: Map<String, String> = mapOf("PATH" to "/usr/bin")) =
        CliRunSpec(
            binaryPath = Path.of("/usr/local/bin/browser4-cli"),
            shell = "/usr/local/bin/browser4-cli",
            shellArgv = listOf("/usr/local/bin/browser4-cli", "goto", "https://example.com"),
            commandLine = "browser4-cli goto https://example.com",
            timeoutMs = 60_000,
            graceMs = 3_000,
            outputBufferBytes = 1 shl 20,
            returnMaxTokens = 10_000,
            workingDir = workingDir,
            sessionId = "s-1",
            taskId = "t-1",
            env = env,
            stdin = null,
        )

    private class NoopSandbox(override val id: String = "windows-low-il") : ExecutionSandbox {
        override fun prepare(
            spec: ProcessSpec,
            workspace: Path,
            allowNetwork: Boolean,
            extraWritableRoots: List<Path>,
        ): ProcessSpec = spec
    }

    @Test
    @DisplayName("the state directory moves into the workspace, so the child can write it")
    fun stateDirectoryIsInsideTheWorkspace() {
        val workspace = tempDir.resolve("ws")
        val spec = spec()

        val prepared = CliProcessManager().sandboxedSpec(spec, NoopSandbox(), workspace)

        val stateDir = prepared.environment["BROWSER4_CLI_STATE_DIR"]
        assertNotNull(stateDir, "the CLI needs a writable state directory: ${prepared.environment.keys}")
        assertEquals(workspace.resolve(".b4scratch").resolve("cli-state").toString(), stateDir)
        assertEquals(spec.shellArgv, prepared.command, "the argv is passed through unchanged")
        assertEquals(workspace, prepared.directory, "without a request directory the workspace is the cwd")
    }

    @Test
    @DisplayName("a requested working directory wins, and the whitelisted env is preserved")
    fun requestedDirectoryWins() {
        val workspace = tempDir.resolve("ws")
        val requested = tempDir.resolve("requested")
        val spec = spec(workingDir = requested, env = mapOf("PATH" to "/usr/bin", "BROWSER4_CLI_SERVER" to "http://localhost:18182"))

        val prepared = CliProcessManager().sandboxedSpec(spec, NoopSandbox(), workspace)

        assertEquals(requested, prepared.directory)
        assertEquals("http://localhost:18182", prepared.environment["BROWSER4_CLI_SERVER"], "session pinning survives")
        assertNull(prepared.sandboxAccount, "no account is involved in the low-integrity path")
        assertNull(prepared.sandboxLauncher, "the launcher is attached by prepare(), not by the spec builder")
    }

    @Test
    @DisplayName("a legacy ProcessBuilder's working directory is honoured when the request has none")
    fun legacyDirectoryIsHonoured() {
        val workspace = tempDir.resolve("ws")
        val legacyDir = tempDir.resolve("legacy")

        val prepared = CliProcessManager().sandboxedSpec(
            spec(),
            NoopSandbox(),
            workspace,
            ProcessBuilder("cmd.exe").directory(legacyDir.toFile()),
        )

        assertEquals(legacyDir, prepared.directory)
    }
}
