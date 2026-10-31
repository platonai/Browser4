package ai.platon.pulsar.coding

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * The shell obeys the workspace boundary (D4).
 *
 * `coding.*` file tools refuse paths outside the workspace when
 * `allowExternalAccess=false`, but the shell only contained its *working directory*: a
 * live run used `dir /s /b "D:\workspace\…"` and `git -C "D:\repo" show …` to read a
 * fixture the file tools refused (2026-10-28). Arguments are checked now, with the
 * toolchain/launcher exceptions a build genuinely needs.
 *
 * Nothing here spawns a process — [CodingAgentShell.isAllowed] runs the same validation
 * the executor does.
 */
class CodingAgentShellWorkspaceBoundaryTest {

    @TempDir
    lateinit var workspace: Path

    /** An absolute file outside the workspace and outside the toolchain roots. */
    private fun outsideFile(): Path = workspace.root.resolve("b4-outside-data.csv")

    private fun shell(
        allowExternalAccess: Boolean = false,
        boundaryEnabled: Boolean = true,
    ) = CodingAgentShell(
        baseDir = workspace,
        allowExternalAccess = allowExternalAccess,
        workspaceBoundaryEnabled = boundaryEnabled,
    )

    @Test
    @DisplayName("an absolute path outside the workspace is refused")
    fun externalAbsolutePathIsRefused() {
        val shell = shell()
        val command = "type \"${outsideFile()}\""

        assertFalse(shell.isAllowed(command), shell.explain(command))
        assertTrue(shell.explain(command).contains("outside the workspace"), shell.explain(command))
    }

    @Test
    @DisplayName("a relative path escaping the workspace is refused")
    fun relativeEscapeIsRefused() {
        val shell = shell()
        val relative = workspace.relativize(outsideFile()).toString()
        // The guard judges one command line for both shells, so a `\`-spelled escape is refused
        // wherever it runs: resolving both separator styles must not turn an escape into an allow.
        val windowsStyle = relative.replace('/', '\\')

        for (command in listOf("type \"$relative\"", "type \"$windowsStyle\"")) {
            assertFalse(shell.isAllowed(command), shell.explain(command))
        }
    }

    @Test
    @DisplayName("a tool pointed at a directory outside the workspace is refused")
    fun externalToolTargetIsRefused() {
        val shell = shell()
        val command = "git -C \"${workspace.root}\" show HEAD"

        assertFalse(shell.isAllowed(command), shell.explain(command))
    }

    @Test
    @DisplayName("relative paths, launchers and the JDK stay allowed")
    fun ordinaryToolchainStaysAllowed() {
        val shell = shell()

        for (command in listOf(
            "type .b4inputs/spec.csv",
            "mvnw.cmd -pl browser4-coding -am compile",
            "\"\$JAVA_HOME/bin/java\" -version",
            // Windows spelling on a POSIX host: the guard expands `%VAR%` and `$env:` forms on
            // every platform, so `\` must resolve as a separator too — it refused the JDK itself
            // as "outside the workspace" on the Ubuntu nightly runner (2026-10-31).
            "\"%JAVA_HOME%\\bin\\java\" -version",
        )) {
            assertTrue(shell.isAllowed(command), "$command must stay allowed: ${shell.explain(command)}")
        }
    }

    @Test
    @DisplayName("allowExternalAccess and the boundary switch both relax the guard")
    fun switchesRelaxTheGuard() {
        val command = "type \"${outsideFile()}\""

        val external = shell(allowExternalAccess = true)
        assertTrue(external.isAllowed(command), external.explain(command))

        val boundaryOff = shell(boundaryEnabled = false)
        assertTrue(boundaryOff.isAllowed(command), boundaryOff.explain(command))
    }
}
