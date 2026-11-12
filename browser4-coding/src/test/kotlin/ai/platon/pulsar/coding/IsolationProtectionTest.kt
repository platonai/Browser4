package ai.platon.pulsar.coding

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * The launch path is operator work (self-development isolation plan §D8): a task
 * must not be able to rewrite the scripts that start/repair the backend, and
 * `coding.shell` must refuse the same commands the file tools refuse to touch.
 */
class IsolationProtectionTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    @DisplayName("launch scripts are protected files and .mvn is a protected directory")
    fun launchPathIsProtected() {
        val files = CodingAgentFileSystem.DEFAULT_PROTECTED_FILES
        listOf(
            "b4w.ps1", "b4w.sh", "b4w.bat",
            "bin/build.ps1", "bin/build/build.ps1",
            "bin/recover.ps1",
            "browser4-apps/browser4-bundle/build-runtime-bundle.ps1",
            "mvnw", "mvnw.cmd",
        ).forEach { assertTrue(it in files, "$it must be protected") }

        val dirs = CodingAgentFileSystem.DEFAULT_PROTECTED_DIRS
        assertTrue(".mvn" in dirs, ".mvn must be a protected directory")
        assertTrue(".browser4" in dirs)
    }

    @Test
    @DisplayName("writing a launch script through the file tools is refused")
    fun writeLaunchScriptRefused() = runBlocking {
        val fs = CodingComponents.fs(tempDir, AgentAccessPolicy())
        val b4w = fs.writeFile("b4w.ps1", "Write-Host pwned\n")
        assertTrue(b4w.contains("protected"), "result: $b4w")

        val build = fs.writeFile("bin/build.ps1", "# replaced\n")
        assertTrue(build.contains("protected"), "result: $build")
    }

    @Test
    @DisplayName("coding.shell refuses to rebuild or stop the backend it runs inside")
    fun shellRefusesBootstrapCommands() {
        val shell = CodingComponents.shell(tempDir, AgentAccessPolicy())

        listOf(
            "b4w build",
            "b4w stop",
            "b4w install",
            "b4w upgrade",
            "./bin/build.ps1 -Rebuild",
            "pwsh bin/recover.ps1 --status",
            "pwsh browser4-apps/browser4-bundle/build-runtime-bundle.ps1",
        ).forEach { command ->
            assertFalse(shell.isAllowed(command), "must be refused: $command")
            val why = shell.explain(command)
            assertTrue(
                why.contains("rebuilds or restarts") || why.contains("bootstrap/recovery"),
                "refusal must name the reason: $command -> $why",
            )
        }
    }

    @Test
    @DisplayName("ordinary build and test commands stay allowed")
    fun ordinaryCommandsStillAllowed() {
        val shell = CodingComponents.shell(tempDir, AgentAccessPolicy())
        assertTrue(shell.isAllowed("mvn -q -pl browser4-coding -am -DskipTests compile"))
        assertTrue(shell.isAllowed("git status --porcelain"))
    }
}
