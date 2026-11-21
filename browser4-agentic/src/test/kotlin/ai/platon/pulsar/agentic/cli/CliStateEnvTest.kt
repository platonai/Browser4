package ai.platon.pulsar.agentic.cli

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * The `BROWSER4_CLI_STATE_DIR` a sandboxed child of the agent carries.
 *
 * Both sandboxed channels (`b4.run` and `coding.shell`, plus skill scripts) resolve it through
 * [CliStateEnv], because `~/.browser4` is readable but not writable inside the boundary: `attach`
 * used to persist its session to the CLI's CWD fallback while the next invocation read the empty
 * default and answered `Session required` (2026-11-20 live run).
 */
@DisplayName("agent sandbox CLI state directory")
class CliStateEnvTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    @DisplayName("a boundary relocates the state into the task workspace")
    fun boundaryRelocatesIntoTheWorkspace() {
        val stateDir = CliStateEnv.stateDir(null, "windows-low-il", tempDir)

        assertEquals(tempDir.resolve(".b4scratch").resolve("cli-state").toString(), stateDir)
        assertEquals(CliStateEnv.dirInWorkspace(tempDir), stateDir)
    }

    @Test
    @DisplayName("without a boundary the flat ~/.browser4 layout is kept")
    fun noBoundaryKeepsTheDefaultLayout() {
        // Sharing the state with the operator's terminal is the expected `agent run` behaviour.
        assertNull(CliStateEnv.stateDir(null, "off", tempDir))
        assertNull(CliStateEnv.stateDir(null, null, tempDir))
        assertNull(CliStateEnv.stateDir(null, "  ", tempDir))
    }

    @Test
    @DisplayName("an operator override wins over the workspace relocation")
    fun operatorOverrideWins() {
        assertEquals(
            "D:/granted/cli-state",
            CliStateEnv.stateDir("D:/granted/cli-state", "windows-low-il", tempDir),
        )
        // A blank override is a misconfiguration, not a request: fall back to the workspace.
        assertEquals(
            CliStateEnv.dirInWorkspace(tempDir),
            CliStateEnv.stateDir("   ", "windows-low-il", tempDir),
        )
    }

    @Test
    @DisplayName("no workspace means no relocation to invent")
    fun noWorkspaceMeansNoRelocation() {
        assertNull(CliStateEnv.stateDir(null, "windows-low-il", null))
    }
}
