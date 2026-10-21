package ai.platon.pulsar.agentic.tools.builtin

import ai.platon.pulsar.agentic.model.ToolCall
import ai.platon.pulsar.coding.CodingAgentFileSystem
import ai.platon.pulsar.coding.CodingAgentShell
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Integration test for the transactional workspace switch (plan P2.1/P2.2):
 * `coding.devTask(..., execute=true)` runs its steps inside a git worktree when
 * `browser4.agent.workspace.isolation=worktree`, and reports the isolation
 * outcome instead of silently writing into the main tree.
 */
class CodingToolExecutorIsolationTest {

    @TempDir
    lateinit var tempDir: Path

    private val property = "browser4.agent.workspace.isolation"
    private var previous: String? = null

    private fun git(vararg args: String) {
        val process = ProcessBuilder(listOf("git") + args)
            .directory(tempDir.toFile())
            .redirectErrorStream(true)
            .start()
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), "git ${args.joinToString(" ")} timed out")
        assertEquals(0, process.exitValue(), process.inputStream.bufferedReader().use { it.readText() })
    }

    @BeforeEach
    fun setUp() {
        assumeTrue(
            runCatching { ProcessBuilder("git", "--version").start().waitFor(10, TimeUnit.SECONDS) }
                .getOrDefault(false),
            "git is not available",
        )
        previous = System.getProperty(property)
        git("init")
        git("config", "user.email", "test@example.com")
        git("config", "user.name", "Isolation Test")
        git("config", "commit.gpgsign", "false")
        git("config", "core.autocrlf", "false")
        Files.writeString(tempDir.resolve(".gitignore"), ".browser4/\n")
        Files.writeString(tempDir.resolve("README.md"), "# scratch workspace\n")
        git("add", ".")
        git("commit", "-m", "initial")
    }

    @AfterEach
    fun tearDown() {
        if (previous == null) System.clearProperty(property) else System.setProperty(property, previous!!)
    }

    private fun target() = CodingToolExecutor.Target(
        CodingAgentShell(baseDir = tempDir),
        CodingAgentFileSystem(tempDir),
    )

    private fun runDevTaskExecute(): String {
        val executor = CodingToolExecutor()
        val tc = ToolCall(
            domain = "coding",
            method = "devTask",
            arguments = mutableMapOf(
                "task" to "please improve the overall performance",
                "execute" to "true",
            ),
        )
        val result = runBlocking { executor.callFunctionOn(tc, target()) }
        assertNull(result.exception, "executor failed: ${result.exception}")
        return result.value.toString()
    }

    @Test
    @DisplayName("with isolation on, execution reports an isolated run and leaves the tree untouched")
    fun isolationOnRunsInWorktree() {
        System.setProperty(property, "worktree")
        val text = runDevTaskExecute()

        assertTrue(text.contains("--- Isolation ---"), "text: $text")
        assertTrue(text.contains("No changes to merge"), "text: $text")
        // The main tree must be clean apart from the ignored management dir.
        val status = ProcessBuilder("git", "status", "--porcelain")
            .directory(tempDir.toFile()).redirectErrorStream(true).start()
            .inputStream.bufferedReader().use { it.readText() }.trim()
        assertEquals("", status, "main tree changed: $status")
        assertTrue(
            Files.exists(tempDir.resolve(".browser4").resolve("worktrees")),
            "the isolation management directory should have been used",
        )
    }

    @Test
    @DisplayName("with isolation off, execution runs in place and reports no isolation section")
    fun isolationOffRunsInPlace() {
        System.setProperty(property, "off")
        val text = runDevTaskExecute()
        assertFalse(text.contains("--- Isolation ---"), "text: $text")
        assertTrue(text.contains("--- Execution ---"), "text: $text")
    }
}
