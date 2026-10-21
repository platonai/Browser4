package ai.platon.pulsar.agentic.tools.builtin

import ai.platon.pulsar.coding.CodingAgentFileSystem
import ai.platon.pulsar.coding.CodingAgentShell
import ai.platon.pulsar.agentic.model.ToolCall
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Tests for `coding.checksum` + `coding.write(expectedChecksum=…)` — the
 * optimistic-concurrency pair (plan P2.3/P2.5), exercised through the tool
 * boundary an agent actually calls.
 */
class CodingToolExecutorConcurrencyTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var executor: CodingToolExecutor
    private lateinit var target: CodingToolExecutor.Target

    @BeforeEach
    fun setUp() {
        executor = CodingToolExecutor()
        target = CodingToolExecutor.Target(
            CodingAgentShell(baseDir = tempDir),
            CodingAgentFileSystem(tempDir),
        )
    }

    private fun call(method: String, vararg args: Pair<String, Any?>): String {
        val tc = ToolCall(domain = "coding", method = method, arguments = linkedMapOf(*args))
        val result = runBlocking { executor.callFunctionOn(tc, target) }
        assertNull(result.exception, "executor failed: ${result.exception}")
        return result.value.toString()
    }

    private fun writeFile(path: String, content: String) {
        val f = tempDir.resolve(path)
        Files.createDirectories(f.parent)
        Files.writeString(f, content)
    }

    @Test
    @DisplayName("coding.checksum returns a stable sha256 for the file")
    fun checksumTool() {
        writeFile("a.txt", "hello\n")
        val first = call("checksum", "path" to "a.txt")
        assertTrue(first.startsWith("sha256:"), "checksum: $first")
        assertEquals(first, call("checksum", "path" to "a.txt"))

        writeFile("a.txt", "changed\n")
        assertNotEquals(first, call("checksum", "path" to "a.txt"))
    }

    @Test
    @DisplayName("coding.checksum reports a missing file without failing the call")
    fun checksumMissingFile() {
        val result = call("checksum", "path" to "nope.txt")
        assertTrue(result.startsWith("No checksum available"), "result: $result")
    }

    @Test
    @DisplayName("coding.write refuses a stale expectedChecksum")
    fun writeRefusesStaleChecksum() {
        writeFile("a.txt", "original\n")
        val checksum = call("checksum", "path" to "a.txt")

        // A concurrent edit lands before the model's write.
        writeFile("a.txt", "concurrent edit\n")

        val result = call(
            "write",
            "path" to "a.txt",
            "content" to "model write\n",
            "expectedChecksum" to checksum,
        )
        assertTrue(result.startsWith("Error:"), "result: $result")
        assertEquals("concurrent edit\n", Files.readString(tempDir.resolve("a.txt")))
    }

    @Test
    @DisplayName("coding.write succeeds with the current checksum")
    fun writeAcceptsCurrentChecksum() {
        writeFile("a.txt", "original\n")
        val checksum = call("checksum", "path" to "a.txt")

        val result = call(
            "write",
            "path" to "a.txt",
            "content" to "model write\n",
            "expectedChecksum" to checksum,
        )
        assertTrue(result.startsWith("✓"), "result: $result")
        assertEquals("model write\n", Files.readString(tempDir.resolve("a.txt")))
    }

    @Test
    @DisplayName("coding.write without expectedChecksum keeps the historical behaviour")
    fun writeWithoutChecksum() {
        val result = call("write", "path" to "a.txt", "content" to "plain\n")
        assertTrue(result.startsWith("✓"), "result: $result")
        assertEquals("plain\n", Files.readString(tempDir.resolve("a.txt")))
    }
}
