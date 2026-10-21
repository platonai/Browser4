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
 * A link/junction must never be deleted by the coding file system.
 *
 * Observed in a real agent run: the agent ran `coding.delete("browser4-coding",
 * recursive=true)` inside its workspace, where that entry is an NTFS junction to
 * the real checkout. Deleting the link is confusing at best; on an API that
 * follows the reparse point it wipes the target tree.
 */
class CodingAgentFileSystemLinkGuardTest {

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
    @DisplayName("deleting a junction is refused and the target survives")
    fun refusesToDeleteJunction() = runBlocking {
        val real = Files.createDirectories(tempDir.resolve("real-module"))
        val keep = Files.writeString(real.resolve("keep.txt"), "keep")
        val workspace = Files.createDirectories(tempDir.resolve("workspace"))
        val junction = workspace.resolve("browser4-coding")
        link(junction, real)

        val fs = CodingAgentFileSystem(workspace)
        val result = fs.delete("browser4-coding", recursive = true)

        assertTrue(result.contains("Refusing to delete a link or junction"), "result: $result")
        assertTrue(Files.exists(keep), "the junction target must survive")
        assertTrue(Files.isDirectory(real), "the real module dir must survive")
    }

    @Test
    @DisplayName("a recursive delete refuses a directory that contains a junction")
    fun refusesRecursiveDeleteThroughJunction() = runBlocking {
        val real = Files.createDirectories(tempDir.resolve("real-module"))
        val keep = Files.writeString(real.resolve("keep.txt"), "keep")
        val workspace = Files.createDirectories(tempDir.resolve("workspace"))
        val parent = Files.createDirectories(workspace.resolve("bundle"))
        link(parent.resolve("linked-module"), real)

        val fs = CodingAgentFileSystem(workspace)
        val result = fs.delete("bundle", recursive = true)

        assertTrue(result.contains("it contains a link or junction"), "result: $result")
        assertTrue(Files.exists(keep), "the junction target must survive")
    }

    @Test
    @DisplayName("plain files and directories still delete")
    fun plainDeleteStillWorks() = runBlocking {
        val workspace = Files.createDirectories(tempDir.resolve("workspace"))
        val plain = Files.createDirectories(workspace.resolve("plain"))
        Files.writeString(plain.resolve("a.txt"), "a")

        val fs = CodingAgentFileSystem(workspace)
        val result = fs.delete("plain", recursive = true)

        assertTrue(result.startsWith("✓ Deleted"), "result: $result")
        assertFalse(Files.exists(plain))
    }
}
