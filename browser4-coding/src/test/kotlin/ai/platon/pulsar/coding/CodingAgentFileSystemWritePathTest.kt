package ai.platon.pulsar.coding

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Tests for the write-path hardening in [CodingAgentFileSystem]:
 * - governance protection on EVERY write path, not just replace/delete (plan P0.6)
 * - atomic writes that leave no temp files behind (plan P0.8)
 * - line-range edits preserving the file's newline style (plan P0.8, eval K7/G7)
 */
class CodingAgentFileSystemWritePathTest {

    @TempDir
    lateinit var tempDir: Path

    private fun fs(): CodingAgentFileSystem = CodingAgentFileSystem(tempDir)

    private fun write(path: String, content: String) {
        val f = tempDir.resolve(path)
        Files.createDirectories(f.parent)
        Files.writeString(f, content)
    }

    private fun read(path: String): String = Files.readString(tempDir.resolve(path))

    // ==================== protection coverage ====================

    @Test
    @DisplayName("writeFile is blocked on VERSION")
    fun writeVersionProtected() = runBlocking {
        write("VERSION", "4.14.2-SNAPSHOT\n")
        val result = fs().writeFile("VERSION", "9.9.9\n")
        assertTrue(result.startsWith("Error:"), "result: $result")
        assertTrue(result.contains("protected"), "result: $result")
        assertEquals("4.14.2-SNAPSHOT\n", read("VERSION"))
    }

    @Test
    @DisplayName("writeFile is blocked on AGENTS.md")
    fun writeAgentsProtected() = runBlocking {
        write("AGENTS.md", "# guidelines\n")
        val result = fs().writeFile("AGENTS.md", "hijacked\n")
        assertTrue(result.startsWith("Error:"), "result: $result")
        assertEquals("# guidelines\n", read("AGENTS.md"))
    }

    @Test
    @DisplayName("appendFile is blocked on a protected file")
    fun appendProtected() = runBlocking {
        write("VERSION", "4.14.2-SNAPSHOT\n")
        val result = fs().appendFile("VERSION", "sneaky\n")
        assertTrue(result.startsWith("Error:"), "result: $result")
        assertEquals("4.14.2-SNAPSHOT\n", read("VERSION"))
    }

    @Test
    @DisplayName("copy cannot overwrite a protected file")
    fun copyOverProtectedBlocked() = runBlocking {
        write("AGENTS.md", "# guidelines\n")
        write("payload.txt", "hijacked\n")
        val result = fs().copy("payload.txt", "AGENTS.md")
        assertTrue(result.startsWith("Error:"), "result: $result")
        assertEquals("# guidelines\n", read("AGENTS.md"))
    }

    @Test
    @DisplayName("move cannot delete or overwrite a protected file")
    fun moveProtectedBlocked() = runBlocking {
        write("VERSION", "4.14.2-SNAPSHOT\n")
        write("payload.txt", "hijacked\n")

        val fromProtected = fs().move("VERSION", "moved.txt")
        assertTrue(fromProtected.startsWith("Error:"), "result: $fromProtected")
        assertEquals("4.14.2-SNAPSHOT\n", read("VERSION"))

        val toProtected = fs().move("payload.txt", "AGENTS.md")
        assertTrue(toProtected.startsWith("Error:"), "result: $toProtected")
        assertFalse(Files.exists(tempDir.resolve("AGENTS.md")))
    }

    @Test
    @DisplayName("module poms stay writable (only the root aggregator is protected)")
    fun modulePomWritable() = runBlocking {
        write("browser4-plugins/browser4-seo/pom.xml", "<project/>\n")
        val result = fs().writeFile("browser4-plugins/browser4-seo/pom.xml", "<project><!-- x --></project>\n")
        assertTrue(result.startsWith("✓"), "result: $result")
    }

    // ==================== atomic writes ====================

    @Test
    @DisplayName("writeFile leaves no temp files behind")
    fun writeLeavesNoTempFiles() = runBlocking {
        val fs = fs()
        fs.writeFile("a.txt", "hello\n")
        fs.writeFile("nested/b.txt", "world\n")
        val leftovers = fs.workspaceRoot.toFile().walkTopDown()
            .filter { it.isFile && it.name.startsWith(".b4-write-") }
            .toList()
        assertTrue(leftovers.isEmpty(), "temp files left behind: $leftovers")
        assertEquals("hello\n", read("a.txt"))
        assertEquals("world\n", read("nested/b.txt"))
    }

    @Test
    @DisplayName("writeFile creates parent directories")
    fun writeCreatesParents() = runBlocking {
        val result = fs().writeFile("a/b/c/d.txt", "deep\n")
        assertTrue(result.startsWith("✓"), "result: $result")
        assertEquals("deep\n", read("a/b/c/d.txt"))
    }

    // ==================== newline preservation ====================

    @Test
    @DisplayName("editLines preserves CRLF line endings")
    fun editLinesPreservesCrlf() = runBlocking {
        write("win.kt", "line1\r\nline2\r\nline3\r\n")
        val result = fs().editLinesInFile("win.kt", 2, 2, "REPLACED")
        assertTrue(result.startsWith("✓"), "result: $result")
        assertEquals("line1\r\nREPLACED\r\nline3\r\n", read("win.kt"))
    }

    @Test
    @DisplayName("insertAfter preserves CRLF line endings")
    fun insertAfterPreservesCrlf() = runBlocking {
        write("win.kt", "a\r\nb\r\n")
        val result = fs().insertAfterInFile("win.kt", "a", "inserted")
        assertTrue(result.startsWith("✓"), "result: $result")
        assertEquals("a\r\ninserted\r\nb\r\n", read("win.kt"))
    }

    @Test
    @DisplayName("a file without a trailing newline keeps that shape after insertAfter")
    fun insertAfterKeepsMissingTrailingNewline() = runBlocking {
        write("noeol.kt", "a\nb")
        val result = fs().insertAfterInFile("noeol.kt", "a", "inserted")
        assertTrue(result.startsWith("✓"), "result: $result")
        assertEquals("a\ninserted\nb", read("noeol.kt"))
    }

    @Test
    @DisplayName("appendFile keeps the existing content and appends")
    fun appendKeepsExisting() = runBlocking {
        write("log.txt", "line1\n")
        val result = fs().appendFile("log.txt", "line2\n")
        assertTrue(result.startsWith("✓"), "result: $result")
        assertEquals("line1\nline2\n", read("log.txt"))
    }

    @Test
    @DisplayName("appendFile creates the file when absent")
    fun appendCreatesNew() = runBlocking {
        val result = fs().appendFile("new/log.txt", "line1\n")
        assertTrue(result.startsWith("✓"), "result: $result")
        assertEquals("line1\n", read("new/log.txt"))
    }

    // --- mutation log (finish-gate trace) ------------------------------------

    @Test
    @DisplayName("writes, edits and deletes are recorded in the mutation log")
    fun mutationsRecorded() = runBlocking {
        val fs = CodingAgentFileSystem(tempDir)
        fs.writeFile("src/keep.txt", "a")
        fs.appendFile("src/keep.txt", "b")
        fs.writeFile("src/gone.txt", "x")
        fs.delete("src/gone.txt")

        val ops = fs.mutationsSnapshot().map { it.op to it.path }
        assertEquals(
            listOf(
                "write" to "src/keep.txt",
                "append" to "src/keep.txt",
                "write" to "src/gone.txt",
                "delete" to "src/gone.txt",
            ),
            ops,
        )
        assertTrue(fs.mutationsSnapshot().all { it.ok }, "every recorded mutation must be ok")
    }

    @Test
    @DisplayName("the mutation log is bounded and resettable")
    fun mutationLogBoundedAndResettable() = runBlocking {
        val fs = CodingAgentFileSystem(tempDir)
        repeat(CodingAgentFileSystem.MAX_MUTATION_LOG + 5) { i ->
            fs.writeFile("f$i.txt", "$i")
        }
        assertEquals(CodingAgentFileSystem.MAX_MUTATION_LOG, fs.mutationsSnapshot().size)
        // Oldest entries dropped, newest kept.
        assertEquals("f${CodingAgentFileSystem.MAX_MUTATION_LOG + 4}.txt", fs.mutationsSnapshot().last().path)

        fs.clearMutations()
        assertTrue(fs.mutationsSnapshot().isEmpty())
    }
}
