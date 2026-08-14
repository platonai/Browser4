package ai.platon.pulsar.agentic.common

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Tests for the edit primitives (replaceRegex / editLines / insertAfter / revert)
 * and the upgraded diff (Myers/Patience unified output) in CodingAgentFileSystem.
 */
class CodingAgentFileSystemEditsTest {

    @TempDir
    lateinit var tempDir: Path

    private fun fs(): CodingAgentFileSystem = CodingAgentFileSystem(tempDir)

    private fun write(path: String, content: String) {
        val f = tempDir.resolve(path)
        Files.createDirectories(f.parent)
        Files.writeString(f, content)
    }

    private fun read(path: String): String = Files.readString(tempDir.resolve(path))

    // ==================== replaceRegex ====================

    @Test
    @DisplayName("replaceRegex replaces all matches with group references")
    fun replaceRegexBasic() = runBlocking {
        write("a.txt", "x=1\ny=2\nz=3\n")
        val result = fs().replaceRegexInFile("a.txt", "=(\\d+)", "=$1+1")
        assertTrue(result.contains("3 regex match"), "result: $result")
        assertEquals("x=1+1\ny=2+1\nz=3+1\n", read("a.txt"))
    }

    @Test
    @DisplayName("replaceRegex with count limits replacements")
    fun replaceRegexCount() = runBlocking {
        write("a.txt", "a1 b2 c3\n")
        val result = fs().replaceRegexInFile("a.txt", "\\d", "X", count = 2)
        assertTrue(result.contains("up to 2"))
        assertEquals("aX bX c3\n", read("a.txt"))
    }

    @Test
    @DisplayName("replaceRegex no-match reports no change")
    fun replaceRegexNoMatch() = runBlocking {
        write("a.txt", "hello\n")
        val result = fs().replaceRegexInFile("a.txt", "\\d+", "X")
        assertTrue(result.contains("not found"))
        assertEquals("hello\n", read("a.txt"))
    }

    @Test
    @DisplayName("replaceRegex invalid regex reports error")
    fun replaceRegexInvalid() = runBlocking {
        write("a.txt", "hello\n")
        val result = fs().replaceRegexInFile("a.txt", "(unclosed", "X")
        assertTrue(result.contains("Invalid regex"))
    }

    // ==================== editLines ====================

    @Test
    @DisplayName("editLines replaces a 1-based inclusive line range")
    fun editLinesBasic() = runBlocking {
        write("a.txt", "a\nb\nc\nd\ne\n")
        val result = fs().editLinesInFile("a.txt", 2, 4, "X\nY")
        assertTrue(result.contains("Replaced lines 2..4"))
        assertEquals("a\nX\nY\ne\n", read("a.txt"))
    }

    @Test
    @DisplayName("editLines rejects invalid ranges")
    fun editLinesInvalidRange() = runBlocking {
        write("a.txt", "a\nb\n")
        val result = fs().editLinesInFile("a.txt", 3, 2, "X")
        assertTrue(result.contains("Invalid line range"))
    }

    @Test
    @DisplayName("editLines beyond EOF reports error")
    fun editLinesBeyondEof() = runBlocking {
        write("a.txt", "a\n")
        val result = fs().editLinesInFile("a.txt", 5, 6, "X")
        assertTrue(result.contains("beyond file end"))
    }

    // ==================== insertAfter ====================

    @Test
    @DisplayName("insertAfter inserts after anchor line")
    fun insertAfterBasic() = runBlocking {
        write("a.txt", "a\nb\nc\n")
        val result = fs().insertAfterInFile("a.txt", "b", "B2")
        assertTrue(result.contains("Inserted after line 2"))
        assertEquals("a\nb\nB2\nc\n", read("a.txt"))
    }

    @Test
    @DisplayName("insertAfter missing anchor reports no change")
    fun insertAfterMissingAnchor() = runBlocking {
        write("a.txt", "a\n")
        val result = fs().insertAfterInFile("a.txt", "zzz", "X")
        assertTrue(result.contains("not found"))
        assertEquals("a\n", read("a.txt"))
    }

    // ==================== revert ====================

    @Test
    @DisplayName("revert restores a file to its first-write snapshot")
    fun revertRestoresContent() = runBlocking {
        write("a.txt", "original\n")
        val f = fs()
        f.writeFile("a.txt", "changed\n")
        val result = f.revert("a.txt")
        assertTrue(result.contains("Reverted"), "result: $result")
        assertEquals("original\n", read("a.txt"))
    }

    @Test
    @DisplayName("revert with no snapshot reports error")
    fun revertNoSnapshot() = runBlocking {
        write("a.txt", "never-tracked\n")
        val result = fs().revert("a.txt")
        assertTrue(result.contains("No snapshot"), "result: $result")
    }

    // ==================== diff (upgraded) ====================

    @Test
    @DisplayName("diff produces unified output with hunk header")
    fun diffUnified() = runBlocking {
        write("a.txt", "line1\nline2\nline3\nline4\n")
        val f = fs()
        f.writeFile("a.txt", "line1\nINSERTED\nline2\nline3\nline4\n")
        val result = f.diff("a.txt")
        assertTrue(result.contains("diff a.txt (myers)"), "result: $result")
        assertTrue(result.contains("+INSERTED"))
        // Myers keeps the following lines aligned (no spurious -line2/-line3).
        assertFalse(result.contains("-line2"), "naive misalignment leaked: $result")
        assertFalse(result.contains("-line3"), "naive misalignment leaked: $result")
    }

    @Test
    @DisplayName("diff accepts patience algorithm")
    fun diffPatience() = runBlocking {
        write("a.txt", "a\nb\nc\n")
        val f = fs()
        f.writeFile("a.txt", "a\nB\nc\n")
        val result = f.diff("a.txt", algorithm = "patience")
        assertTrue(result.contains("diff a.txt (patience)"))
        assertTrue(result.contains("-b"))
        assertTrue(result.contains("+B"))
    }

    @Test
    @DisplayName("diff reports no changes when identical")
    fun diffNoChanges() = runBlocking {
        write("a.txt", "same\n")
        val f = fs()
        val result = f.diff("a.txt")
        assertTrue(result.contains("No snapshot") || result.contains("No changes"), "result: $result")
    }

    // ==================== grep/glob excluded dirs ====================

    @Test
    @DisplayName("grep skips excluded directories by default")
    fun grepSkipsExcludedDirs() = runBlocking {
        write("src/a.txt", "needle here\n")
        write("node_modules/pkg/index.js", "needle in node_modules\n")
        write("target/generated.js", "needle in target\n")
        val f = fs()
        val result = f.grep("needle", path = ".")
        assertTrue(result.contains("a.txt"), "result: $result")
        assertFalse(result.contains("node_modules"), "excluded dir leaked: $result")
        assertFalse(result.contains("target/"), "excluded dir leaked: $result")
    }

    @Test
    @DisplayName("glob skips excluded directories by default")
    fun globSkipsExcludedDirs() = runBlocking {
        write("src/a.txt", "x\n")
        write("node_modules/deep/file.txt", "x\n")
        val f = fs()
        val result = f.glob("**/*.txt")
        assertTrue(result.contains("a.txt"), "result: $result")
        assertFalse(result.contains("node_modules"), "excluded dir leaked: $result")
    }

    @Test
    @DisplayName("custom exclusion set is honored")
    fun customExclusionSet() = runBlocking {
        write("keep/ok.txt", "x\n")
        write("vendor/skip.txt", "x\n")
        val f = CodingAgentFileSystem(tempDir, searchExcludedDirs = setOf("vendor"))
        val result = f.glob("**/*.txt")
        assertTrue(result.contains("ok.txt"), "result: $result")
        assertFalse(result.contains("vendor"), "custom exclusion leaked: $result")
    }

    // ==================== delete hard protections ====================

    @Test
    @DisplayName("delete refuses to remove the workspace root")
    fun deleteWorkspaceRootProtected() = runBlocking {
        val f = fs()
        val result = f.delete(".", recursive = true)
        assertTrue(result.contains("workspace root"), "result: $result")
    }

    @Test
    @DisplayName("delete refuses to remove .git recursively")
    fun deleteVcsProtected() = runBlocking {
        write(".git/config", "dummy\n")
        val f = fs()
        val result = f.delete(".git", recursive = true)
        assertTrue(result.contains("version-control"), "result: $result")
        assertTrue(tempDir.resolve(".git/config").toFile().exists(), ".git must survive")
    }

    @Test
    @DisplayName("delete refuses recursive delete of a dir containing .git")
    fun deleteParentContainingVcsProtected() = runBlocking {
        write("repo/.git/config", "dummy\n")
        write("repo/src/main.kt", "x\n")
        val f = fs()
        val result = f.delete("repo", recursive = true)
        assertTrue(result.contains("version-control"), "result: $result")
        assertTrue(tempDir.resolve("repo/.git/config").toFile().exists(), "nested .git must survive")
    }

    @Test
    @DisplayName("delete of ordinary file still works")
    fun deleteOrdinaryFileWorks() = runBlocking {
        write("junk.txt", "bye\n")
        val f = fs()
        val result = f.delete("junk.txt")
        assertTrue(result.contains("Deleted"), "result: $result")
        assertFalse(tempDir.resolve("junk.txt").toFile().exists())
    }
}

