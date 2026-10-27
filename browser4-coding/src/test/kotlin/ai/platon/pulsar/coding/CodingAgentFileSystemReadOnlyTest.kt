package ai.platon.pulsar.coding

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Tests for the read-only file system (plan E1): every mutator must refuse up front with a
 * structured message, before path resolution, so a caller that reached the file system directly
 * cannot widen a `read` / `read-build` preset.
 */
class CodingAgentFileSystemReadOnlyTest {

    @TempDir
    lateinit var workspace: Path

    private fun readOnlyFs() = CodingAgentFileSystem(workspaceRoot = workspace, readOnly = true)

    private fun assertRefused(result: String) {
        assertTrue(
            result.contains("disabled by the access policy"),
            "expected a policy refusal, got: $result",
        )
    }

    @Test
    @DisplayName("write and append are refused on a read-only file system")
    fun writeAndAppendRefused() = runBlocking {
        val fs = readOnlyFs()
        assertRefused(fs.writeFile("a.txt", "x"))
        assertRefused(fs.appendFile("a.txt", "y"))
        assertEquals(false, Files.exists(workspace.resolve("a.txt")))
    }

    @Test
    @DisplayName("line-oriented edits are refused on a read-only file system")
    fun lineEditsRefused() = runBlocking {
        Files.writeString(workspace.resolve("a.txt"), "hello")
        val fs = readOnlyFs()
        assertRefused(fs.insertAfterInFile("a.txt", "hello", "world"))
        assertRefused(fs.editLinesInFile("a.txt", 1, 1, "changed"))
        assertRefused(fs.replaceInFile("a.txt", "hello", "bye"))
        assertRefused(fs.replaceRegexInFile("a.txt", "hel+o", "bye"))
        assertEquals("hello", Files.readString(workspace.resolve("a.txt")))
    }

    @Test
    @DisplayName("delete, mkdir, copy, move and revert are refused on a read-only file system")
    fun destructiveOpsRefused() = runBlocking {
        Files.writeString(workspace.resolve("a.txt"), "hello")
        val fs = readOnlyFs()
        assertRefused(fs.mkdir("newdir"))
        assertRefused(fs.copy("a.txt", "b.txt"))
        assertRefused(fs.move("a.txt", "c.txt"))
        assertRefused(fs.revert("a.txt"))
        assertRefused(fs.delete("a.txt"))
        assertTrue(Files.exists(workspace.resolve("a.txt")))
    }

    @Test
    @DisplayName("protect is refused on a read-only file system")
    fun protectRefused() {
        Files.writeString(workspace.resolve("a.txt"), "hello")
        assertRefused(readOnlyFs().protect("a.txt"))
    }

    @Test
    @DisplayName("reads still work on a read-only file system")
    fun readsStillWork() = runBlocking {
        Files.writeString(workspace.resolve("a.txt"), "hello")
        assertEquals("hello", readOnlyFs().readFile("a.txt"))
    }
}
