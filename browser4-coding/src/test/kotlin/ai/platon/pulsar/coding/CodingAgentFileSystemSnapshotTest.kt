package ai.platon.pulsar.coding

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Tests for the durable snapshot store and the optimistic-concurrency guard
 * (plan P2.3 / P2.5):
 * - a snapshot survives a backend restart, so `revert` still works,
 * - `checksumOf` reports a stable content hash that changes with the content,
 * - `write(expectedChecksum=…)` refuses a stale write instead of clobbering a
 *   concurrent edit,
 * - persistence can be switched off for throw-away workspaces.
 */
class CodingAgentFileSystemSnapshotTest {

    @TempDir
    lateinit var tempDir: Path

    private fun fs(persist: Boolean = true) = CodingAgentFileSystem(tempDir, persistSnapshots = persist)

    private fun write(path: String, content: String) {
        val f = tempDir.resolve(path)
        Files.createDirectories(f.parent)
        Files.writeString(f, content)
    }

    private fun read(path: String): String = Files.readString(tempDir.resolve(path))

    @Test
    @DisplayName("a snapshot survives a new filesystem instance (restart)")
    fun snapshotSurvivesRestart() = runBlocking {
        write("src/a.txt", "original\n")
        // First instance snapshots the original and overwrites it.
        fs().writeFile("src/a.txt", "changed\n")
        assertEquals("changed\n", read("src/a.txt"))

        // Second instance = backend restart: the in-memory map is empty.
        val restarted = fs()
        val result = restarted.revert("src/a.txt")
        assertTrue(result.startsWith("✓"), "result: $result")
        assertEquals("original\n", read("src/a.txt"))
    }

    @Test
    @DisplayName("a created file is deleted by a revert after restart")
    fun createdFileRevertedAfterRestart() = runBlocking {
        fs().writeFile("new/file.txt", "created\n")
        assertTrue(Files.exists(tempDir.resolve("new/file.txt")))

        fs().revert("new/file.txt")
        assertFalse(Files.exists(tempDir.resolve("new/file.txt")), "revert must delete a created file")
    }

    @Test
    @DisplayName("persistence can be disabled for throw-away workspaces")
    fun persistenceCanBeDisabled() = runBlocking {
        write("a.txt", "original\n")
        fs(persist = false).writeFile("a.txt", "changed\n")

        val result = fs(persist = false).revert("a.txt")
        assertTrue(result.startsWith("Error:"), "result: $result")
        assertEquals("changed\n", read("a.txt"))
        assertFalse(Files.exists(tempDir.resolve(".browser4")), "no snapshot dir may be created")
    }

    @Test
    @DisplayName("checksumOf is stable for identical content and changes with edits")
    fun checksumIsStableAndMeaningful() = runBlocking {
        write("a.txt", "hello\n")
        val fs = fs()
        val first = fs.checksumOf("a.txt")
        assertNotNull(first)
        assertTrue(first!!.startsWith("sha256:"), "checksum: $first")
        assertEquals(first, fs.checksumOf("a.txt"), "same content must hash the same")

        fs.writeFile("a.txt", "hello world\n")
        val second = fs.checksumOf("a.txt")
        assertNotEquals(first, second, "changed content must hash differently")
    }

    @Test
    @DisplayName("checksumOf returns null for a missing file")
    fun checksumMissing() {
        assertNull(fs().checksumOf("nope.txt"))
    }

    @Test
    @DisplayName("write with a stale expectedChecksum is refused and the file is untouched")
    fun staleChecksumRefused() = runBlocking {
        write("a.txt", "original\n")
        val fs = fs()
        val staleChecksum = fs.checksumOf("a.txt")!!

        // Someone else edits the file between the read and the write.
        write("a.txt", "someone else\n")

        val result = fs.writeFile("a.txt", "my write\n", expectedChecksum = staleChecksum)
        assertTrue(result.startsWith("Error:"), "result: $result")
        assertTrue(result.contains("changed since it was read"), "result: $result")
        assertEquals("someone else\n", read("a.txt"), "the concurrent edit must survive")
    }

    @Test
    @DisplayName("write with the current expectedChecksum succeeds")
    fun matchingChecksumAccepted() = runBlocking {
        write("a.txt", "original\n")
        val fs = fs()
        val checksum = fs.checksumOf("a.txt")!!

        val result = fs.writeFile("a.txt", "my write\n", expectedChecksum = checksum)
        assertTrue(result.startsWith("✓"), "result: $result")
        assertEquals("my write\n", read("a.txt"))
    }

    @Test
    @DisplayName("write with expectedChecksum='absent' creates a file that did not exist")
    fun absentChecksumCreatesFile() = runBlocking {
        val result = fs().writeFile("fresh.txt", "new\n", expectedChecksum = "absent")
        assertTrue(result.startsWith("✓"), "result: $result")
        assertEquals("new\n", read("fresh.txt"))
    }
}
