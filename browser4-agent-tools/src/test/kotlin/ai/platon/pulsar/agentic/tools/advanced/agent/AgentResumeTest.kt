package ai.platon.pulsar.agentic.tools.advanced.agent

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/**
 * Resume support: a crashed task leaves a checkpoint (instruction, workspace,
 * files); `agent run --resume <id>` starts a NEW run seeded from it.
 *
 * Origin: the 2026-10-28 supervision run lost 29 minutes of work and 7.9M tokens
 * when the process died, with no way to continue (`agent run` had no resume).
 */
@DisplayName("Agent resume")
class AgentResumeTest {

    private fun status(
        id: String = "old-task",
        instruction: String? = "Collect 20 microwave ovens",
        workspace: Path? = null,
    ) = AgentTaskStatus(id = id).apply {
        submittedTask = instruction
        this.workspace = workspace?.toString()
        toolExecutions = 42
        codingMode = false
    }

    @Test
    @DisplayName("the resume instruction keeps the task text and names the workspace contents")
    fun resumeInstructionNamesCheckpoint(@TempDir tmp: Path) {
        Files.writeString(tmp.resolve("rows.csv"), "a,b\n")
        Files.createDirectories(tmp.resolve(".b4scratch"))
        Files.writeString(tmp.resolve(".b4scratch").resolve("progress.txt"), "B001\n")

        val checkpoint = resumeCheckpointOf(status(workspace = tmp))

        val text = resumeInstruction("", checkpoint)

        assertTrue(text.contains("Collect 20 microwave ovens"), text)
        assertTrue(text.contains("## Resume context"), text)
        assertTrue(text.contains("old-task"), text)
        assertTrue(text.contains("42 tool call"), text)
        assertTrue(text.contains(tmp.toString()), text)
        assertTrue(text.contains("rows.csv"), text)
        assertTrue(text.contains(".b4scratch/progress.txt"), text)
        assertTrue(text.contains("Do not start over"), text)
    }

    @Test
    @DisplayName("an explicit task text wins over the checkpoint instruction")
    fun explicitInstructionWins(@TempDir tmp: Path) {
        val checkpoint = resumeCheckpointOf(status(workspace = tmp))

        val text = resumeInstruction("Also check the price column", checkpoint)

        assertTrue(text.startsWith("Also check the price column"), text)
        assertFalse(
            text.contains("Collect 20 microwave ovens"),
            "the checkpoint's own text must not be duplicated: $text",
        )
    }

    @Test
    @DisplayName("a stale resume block is stripped before it is re-applied")
    fun staleResumeBlockIsStripped() {
        val previous = """
            Collect 20 microwave ovens

            ## Resume context

            This run resumes task older, ...
        """.trimIndent()

        assertEquals("Collect 20 microwave ovens", stripResumeBlock(previous))
        assertEquals("plain task", stripResumeBlock("plain task"))
    }

    @Test
    @DisplayName("a checkpoint whose workspace is gone still resumes from the instruction")
    fun missingWorkspaceStillResumes(@TempDir tmp: Path) {
        val checkpoint = resumeCheckpointOf(status(workspace = tmp.resolve("does-not-exist")))

        assertNull(checkpoint.workspace, "a missing directory is not a workspace")
        assertTrue(checkpoint.files.isEmpty(), checkpoint.files.toString())

        val text = resumeInstruction("", checkpoint)
        assertTrue(text.contains("previous workspace is no longer available"), text)
    }

    @Test
    @DisplayName("the file listing is bounded and shows .b4scratch entries")
    fun fileListingIsBounded(@TempDir tmp: Path) {
        repeat(30) { index -> Files.writeString(tmp.resolve("f$index.csv"), "x") }
        Files.createDirectories(tmp.resolve(".b4scratch"))
        Files.writeString(tmp.resolve(".b4scratch").resolve("notes.txt"), "n")

        val files = listWorkspaceFiles(tmp, maxFiles = 5)

        assertEquals(5, files.size, files.toString())
        assertFalse(files.any { it == "f29.csv" }, "sorted, so only the first entries fit: $files")
        assertTrue(files.all { it.startsWith("f") || it.startsWith(".b4scratch/") }, files.toString())
    }
}
