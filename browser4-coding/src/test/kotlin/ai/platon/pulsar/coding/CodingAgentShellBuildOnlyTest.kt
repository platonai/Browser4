package ai.platon.pulsar.coding

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * Tests for the `buildOnly` shell profile and the `allowExecution` switch (plan §4.1.1 / E2).
 *
 * The `write` and `read-build` presets get a shell that may only run the project build toolchain;
 * interpreters, shells, cloud CLIs, containers and VCS are removed because each of them is
 * arbitrary code execution. Nothing here spawns a process: [CodingAgentShell.isAllowed] runs the
 * same validation the executor does.
 */
class CodingAgentShellBuildOnlyTest {

    @TempDir
    lateinit var workspace: Path

    private fun buildShell(): CodingAgentShell =
        CodingAgentShell(baseDir = workspace, buildOnly = true, allowExecution = true)

    @Test
    @DisplayName("build tools are allowed in the build-only profile")
    fun buildToolsAllowed() {
        val shell = buildShell()
        listOf(
            "mvn -q -DskipTests compile",
            "mvnw validate",
            "gradle build",
            "cargo build",
            "npm run build",
            "tsc -p .",
            "go build ./...",
            "dotnet build",
            "make all",
            "javac Main.java",
        ).forEach { command ->
            assertTrue(shell.isAllowed(command), "$command should be allowed: ${shell.explain(command)}")
        }
    }

    @Test
    @DisplayName("interpreters are refused in the build-only profile")
    fun interpretersRefused() {
        val shell = buildShell()
        listOf(
            "python -c print(1)",
            "node script.js",
            "perl -e 1",
        ).forEach { command ->
            assertFalse(shell.isAllowed(command), "$command escaped the build-only profile")
            assertTrue(shell.explain(command).contains("not allowed"), shell.explain(command))
        }
    }

    @Test
    @DisplayName("inline scripts through an interpreter shell are refused in the build-only profile")
    fun inlineScriptsRefused() {
        val shell = buildShell()
        listOf(
            "sh -c \"mvn compile\"",
            "bash -c \"mvn compile\"",
            "pwsh -Command \"mvn compile\"",
            "cmd /c \"mvn compile\"",
        ).forEach { command ->
            assertFalse(shell.isAllowed(command), "wrapper inside the build-only profile: $command")
        }
    }

    @Test
    @DisplayName("an explicit path fallback is closed in the build-only profile")
    fun explicitPathFallbackClosed() {
        val shell = buildShell()
        assertFalse(shell.isAllowed("./anything.sh"), "./script.sh must not bypass BUILD_COMMANDS")
        assertFalse(shell.isAllowed("/usr/bin/anything"), "absolute paths must not bypass BUILD_COMMANDS")
    }

    @Test
    @DisplayName("network, container and VCS commands stay refused in the build-only profile")
    fun networkAndRemoteRefused() {
        val shell = buildShell()
        listOf("curl https://example.com", "docker run alpine", "ssh host", "scp a b").forEach { command ->
            assertFalse(shell.isAllowed(command), "$command must not be a build tool")
        }
    }

    @Test
    @DisplayName("the available-tool listing is narrowed to the build profile")
    fun detectedToolsAreNarrowed() {
        val detected = buildShell().detectAvailableTools()
        assertTrue(detected.all { it in CodingAgentShell.BUILD_COMMANDS }, "unexpected tools: $detected")
    }

    @Test
    @DisplayName("allowExecution=false refuses every command before validation")
    fun executionDisabledRefusesEverything() = kotlinx.coroutines.runBlocking {
        val shell = CodingAgentShell(baseDir = workspace, allowExecution = false)
        assertTrue(shell.execute("mvn -v").contains("disabled by the access policy"))
        assertTrue(shell.execute("echo hi").contains("disabled by the access policy"))
    }

    @Test
    @DisplayName("the default profile keeps the full development tool list")
    fun defaultProfileKeepsDevTools() {
        val shell = CodingAgentShell(baseDir = workspace)
        // Validation only: nothing is spawned.
        assertTrue(shell.isAllowed("python -c print(1)"), shell.explain("python -c print(1)"))
    }
}
