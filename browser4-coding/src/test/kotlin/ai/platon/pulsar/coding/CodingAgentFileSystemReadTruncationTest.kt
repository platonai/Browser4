package ai.platon.pulsar.coding

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Tests for the context-budget truncation added to [CodingAgentFileSystem]
 * readFile / readFileLines / diff to prevent a single tool call from
 * injecting megabytes of text into the LLM context.
 */
class CodingAgentFileSystemReadTruncationTest {

    @TempDir
    lateinit var tempDir: Path

    private fun fs(): CodingAgentFileSystem = CodingAgentFileSystem(tempDir)

    private fun write(path: String, content: String) {
        val f = tempDir.resolve(path)
        Files.createDirectories(f.parent)
        Files.writeString(f, content)
    }

    @Test
    @DisplayName("readFile returns full content when under the cap")
    fun readFileUnderCap() = runBlocking {
        val content = "a".repeat(1000)
        write("small.txt", content)
        assertEquals(content, fs().readFile("small.txt"))
    }

    @Test
    @DisplayName("readFile folds large content to head+tail with omission marker")
    fun readFileFoldsLarge() = runBlocking {
        val content = "X".repeat(CodingAgentFileSystem.DEFAULT_MAX_OUTPUT_CHARS * 3)
        write("big.txt", content)
        val result = fs().readFile("big.txt")

        assertTrue(result.length < content.length, "must be truncated")
        assertTrue(result.length <= CodingAgentFileSystem.DEFAULT_MAX_OUTPUT_CHARS + 300,
            "result length ${result.length} should be near the cap")
        assertTrue(result.contains("omitted"), "should contain omission marker")
        assertTrue(result.contains("readFileLines"), "should hint at readFileLines")
        assertTrue(result.startsWith("X".repeat(100)), "should keep the head")
        assertTrue(result.endsWith("X".repeat(100)), "should keep the tail")
    }

    @Test
    @DisplayName("readFileLines respects maxChars and folds output")
    fun readFileLinesFoldsLarge() = runBlocking {
        // 5000 lines × 100 chars = 500K chars, well above the default cap
        val line = "L".repeat(100)
        val content = (1..5000).joinToString("\n") { line } + "\n"
        write("many.txt", content)

        val full = fs().readFileLines("many.txt", maxChars = Int.MAX_VALUE)
        // Files.readAllLines strips the trailing newline; joinToString("\n") does not re-add it,
        // so the round-tripped length is content.length - 1.
        assertEquals(content.length - 1, full.length, "maxChars=MAX should return everything (minus trailing newline)")

        val folded = fs().readFileLines("many.txt") // default cap
        assertTrue(folded.length < content.length, "default cap should truncate")
        assertTrue(folded.contains("omitted"))
    }

    @Test
    @DisplayName("readFileLines with explicit range under cap returns exact slice")
    fun readFileLinesRange() = runBlocking {
        val content = (1..100).joinToString("\n") { it.toString() } + "\n"
        write("nums.txt", content)
        val slice = fs().readFileLines("nums.txt", startLine = 10, endLine = 15)
        assertEquals("10\n11\n12\n13\n14\n15", slice)
    }
}
