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
    @DisplayName("readFile decodes a GBK console log instead of failing with 'Input length = 1'")
    fun readFileDecodesGbk() = runBlocking {
        // `拒绝访问。` in GBK — what cmd.exe leaves in stderr on a Chinese Windows. A strict UTF-8
        // decoder failed the whole read with "Input length = 1" (run 4125acaa, 2026-11-19).
        val f = tempDir.resolve("dump.err")
        Files.write(f, "拒绝访问。".toByteArray(charset("GBK")))
        val fileSystem = fs()

        val result = fileSystem.readFile("dump.err")
        assertTrue(result.contains("拒绝访问"), "the denial must be readable, was: $result")
        assertFalse(
            result.contains("[note]"),
            "the content stays clean — parsers read these files too: $result",
        )

        // The lenient decode is announced by the TOOL layer, which reads the flag instead.
        val textRead = fileSystem.readFileText("dump.err")
        assertTrue(textRead.recoded, "a GBK file must be reported as re-decoded")
        assertEquals(result, textRead.text, "the flag must not change the text itself")
    }

    @Test
    @DisplayName("a UTF-8 file is not marked as re-decoded")
    fun utf8FileIsNotRecoded() = runBlocking {
        Files.writeString(tempDir.resolve("ok.txt"), "plain ASCII\n")
        val fileSystem = fs()

        assertFalse(fileSystem.readFileText("ok.txt").recoded)
        assertFalse(fileSystem.readFileText("ok.txt").text.contains("[note]"))
    }

    @Test
    @DisplayName("an empty file reads as empty instead of 'cannot coerce to an empty range'")
    fun emptyFileReadsAsEmpty() = runBlocking {
        // A zero-byte `.b4scratch/posts.json` made readFileLines throw
        // "Cannot coerce value to an empty range: maximum -1 is less than minimum 0" and the tool
        // reported that internal message instead of "the file is empty" (run 4125acaa).
        Files.write(tempDir.resolve("posts.json"), ByteArray(0))
        val fileSystem = fs()

        assertEquals("", fileSystem.readFile("posts.json"))
        assertEquals("", fileSystem.readFileLines("posts.json"))
        assertEquals("", fileSystem.readFileLines("posts.json", startLine = 5, endLine = 10))
    }

    @Test
    @DisplayName("readFileLines on a one-line file returns that line")
    fun readFileLinesOneLine() = runBlocking {
        Files.writeString(tempDir.resolve("one.txt"), "only-line")
        val fileSystem = fs()

        assertEquals("only-line", fileSystem.readFileLines("one.txt"))
        assertEquals("only-line", fileSystem.readFileLines("one.txt", startLine = 3))
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
