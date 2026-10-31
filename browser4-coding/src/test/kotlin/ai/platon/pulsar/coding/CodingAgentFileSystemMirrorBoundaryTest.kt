package ai.platon.pulsar.coding

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Verifies that the file-system boundary, now decided on real paths through [Authorizer], still
 * treats mirrored module junctions as in-scope while refusing genuinely external paths.
 *
 * This is the A3 behavior-equivalence contract: the previous lexical `startsWith(workspaceRoot)`
 * check let module junctions through because their *lexical* path lived in the workspace. The new
 * real-path check must let them through because their *real* target is an explicitly derived mirror
 * root.
 */
class CodingAgentFileSystemMirrorBoundaryTest {

    @TempDir
    lateinit var tempDir: Path

    private fun link(linkPath: Path, target: Path) {
        val isWindows = System.getProperty("os.name").lowercase().contains("win")
        if (isWindows) {
            val process = ProcessBuilder("cmd", "/c", "mklink", "/J", linkPath.toString(), target.toString())
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            val exit = process.waitFor()
            assumeTrue(exit == 0, "mklink /J unavailable: $output")
        } else {
            runCatching { Files.createSymbolicLink(linkPath, target) }
                .onFailure { assumeTrue(false, "symlinks unsupported here: ${it.message}") }
        }
    }

    @Test
    @DisplayName("MirrorRoots.derive finds the real target of a workspace junction")
    fun derivesMirrorRoot() {
        val real = Files.createDirectories(tempDir.resolve("real-module"))
        val workspace = Files.createDirectories(tempDir.resolve("workspace"))
        link(workspace.resolve("browser4-coding"), real)

        val roots = MirrorRoots.derive(workspace)

        val realNormalized = real.toRealPath().normalize()
        assertTrue(roots.any { it == realNormalized }, "expected $realNormalized in $roots")
    }

    @Test
    @DisplayName("a write through a mirrored module junction is allowed")
    fun writeThroughJunctionAllowed() = runBlocking {
        val real = Files.createDirectories(tempDir.resolve("real-module"))
        val workspace = Files.createDirectories(tempDir.resolve("workspace"))
        link(workspace.resolve("browser4-coding"), real)

        val fs = CodingAgentFileSystem(workspace)
        val result = fs.writeFile("browser4-coding/hello.txt", "hello")

        assertFalse(result.startsWith("Error:"), "write should succeed: $result")
        val written = Files.readString(real.resolve("hello.txt"))
        assertEquals("hello", written)
    }

    @Test
    @DisplayName("a read through a mirrored module junction is allowed")
    fun readThroughJunctionAllowed() = runBlocking {
        val real = Files.createDirectories(tempDir.resolve("real-module"))
        Files.writeString(real.resolve("note.txt"), "mirror-content")
        val workspace = Files.createDirectories(tempDir.resolve("workspace"))
        link(workspace.resolve("browser4-coding"), real)

        val fs = CodingAgentFileSystem(workspace)
        val result = fs.readFile("browser4-coding/note.txt")

        assertFalse(result.startsWith("Error:"), "read should succeed: $result")
        assertTrue(result.contains("mirror-content"))
    }

    @Test
    @DisplayName("a genuinely external absolute path is still refused")
    fun externalPathRefused() = runBlocking {
        val workspace = Files.createDirectories(tempDir.resolve("workspace"))
        val outside = Files.writeString(tempDir.resolve("outside.txt"), "no")

        val fs = CodingAgentFileSystem(workspace)
        val result = fs.readFile(outside.toString())

        assertTrue(result.startsWith("Error:"), "external read must be refused: $result")
    }

    @Test
    @DisplayName("a traversal escaping the workspace is refused")
    fun traversalRefused() = runBlocking {
        val workspace = Files.createDirectories(tempDir.resolve("workspace"))

        val fs = CodingAgentFileSystem(workspace)
        val result = fs.readFile("../escape.txt")

        assertTrue(result.startsWith("Error:"), "traversal must be refused: $result")
    }
}
