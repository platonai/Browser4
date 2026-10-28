package ai.platon.pulsar.agentic.tools.advanced.agent

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
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

    @Test
    @DisplayName("deliverables are top-level files only — scratch, inputs and dot-files are not artifacts")
    fun deliverablesExcludeWorkingState(@TempDir tmp: Path) {
        Files.writeString(tmp.resolve("amazon_microwaves.csv"), "id\n1\n")
        Files.writeString(tmp.resolve(".env"), "secret")
        Files.createDirectories(tmp.resolve(".b4scratch"))
        Files.writeString(tmp.resolve(".b4scratch").resolve("ev.js"), "x")
        Files.createDirectories(tmp.resolve(".b4inputs"))
        Files.writeString(tmp.resolve(".b4inputs").resolve("spec.xlsx"), "x")

        // A run that aborts on budget must offer the CSV, not its helper scripts.
        assertEquals(listOf("amazon_microwaves.csv"), deliverableFilesOf(tmp.toString()))
    }

    @Test
    @DisplayName("a missing, blank or non-directory workspace has no deliverables")
    fun deliverablesOfMissingWorkspaceAreEmpty(@TempDir tmp: Path) {
        assertTrue(deliverableFilesOf(null).isEmpty())
        assertTrue(deliverableFilesOf("").isEmpty())
        assertTrue(deliverableFilesOf(tmp.resolve("does-not-exist").toString()).isEmpty())
        assertTrue(deliverableFilesOf(tmp.resolve("file.txt").also { Files.writeString(it, "x") }.toString()).isEmpty())
    }

    @Test
    @DisplayName("the deliverables list is bounded")
    fun deliverablesAreBounded(@TempDir tmp: Path) {
        repeat(80) { index -> Files.writeString(tmp.resolve("f$index.csv"), "x") }

        assertEquals(MAX_ABORT_DELIVERABLES, deliverableFilesOf(tmp.toString()).size)
    }

    @Test
    @DisplayName("deliverables are attributed to THIS run: files older than the start are not claimed")
    fun deliverablesIgnoreOlderFiles(@TempDir tmp: Path) {
        // The 2026-10-28 baseline reported a CSV written 36 minutes before the run and
        // another agent's report as this run's deliverables; the finish gate compares a
        // completion report against this list, so a wrong list is worse than an empty one.
        val stale = tmp.resolve("old_report.csv")
        Files.writeString(stale, "old")
        Files.setLastModifiedTime(stale, FileTime.from(Instant.now().minusSeconds(3_600)))

        val startedAt = Instant.now()
        val fresh = tmp.resolve("top20.csv")
        Files.writeString(fresh, "id\n1\n")
        Files.setLastModifiedTime(fresh, FileTime.from(startedAt.plusMillis(500)))

        assertEquals(listOf("top20.csv"), deliverableFilesOf(tmp.toString(), startedAt))
    }

    @Test
    @DisplayName("the mutation log wins over the mtime scan and may name nested deliverables")
    fun deliverablesPreferMutations(@TempDir tmp: Path) {
        Files.createDirectories(tmp.resolve("out"))
        Files.writeString(tmp.resolve("out").resolve("rows.csv"), "x")
        Files.writeString(tmp.resolve("unrelated.csv"), "x")

        val files = deliverableFilesOf(
            tmp.toString(),
            mutations = listOf("out/rows.csv", ".b4scratch/ev.js", "../escape.csv"),
        )

        assertEquals(listOf("out/rows.csv"), files)
    }

    @Test
    @DisplayName("each task gets its own workspace; only --shared-workspace and --resume opt out")
    fun perTaskWorkspaceIsTheDefault(@TempDir tmp: Path) {
        val first = taskWorkspaceFor(tmp, "task-a", resumeWorkspace = null, sharedWorkspace = null)
        val second = taskWorkspaceFor(tmp, "task-b", resumeWorkspace = null, sharedWorkspace = null)

        assertEquals(tmp.resolve("workspace.task-a"), first)
        assertEquals(tmp.resolve("workspace.task-b"), second)
        assertNotEquals(first, second, "two tasks must never share a workspace")

        assertNull(
            taskWorkspaceFor(tmp, "task-c", resumeWorkspace = null, sharedWorkspace = true),
            "--shared-workspace keeps the old shared directory",
        )

        val checkpoint = tmp.resolve("workspace.interrupted")
        assertEquals(
            checkpoint,
            taskWorkspaceFor(tmp, "task-d", resumeWorkspace = checkpoint, sharedWorkspace = true),
            "a resume keeps the interrupted task's workspace even with --shared-workspace",
        )
    }
}
