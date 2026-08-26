package ai.platon.pulsar.agentic.cli

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The resolved binary must ALWAYS be absolute: the shell invocation builds
 * `& '<binary>' ...` and the child's working directory is NOT the bundle root
 * (b4.run defaults it to the coding workspace). A relative `bin/...` binary
 * there fails with "The module 'bin' could not be loaded" on Windows
 * (observed in real-environment e2e).
 */
@DisplayName("CliBinaryResolver absolute-path guarantee")
class CliBinaryResolverTest {

    @TempDir
    lateinit var tempDir: Path

    @AfterEach
    fun tearDown() {
        try { tempDir.toFile().deleteRecursively() } catch (_: Exception) {}
    }

    @Test
    @DisplayName("bundled relative candidate resolves to an absolute path")
    fun testBundledCandidateIsAbsolute() {
        // Create `bin/browser4-cli.exe` next to the process cwd (surefire runs
        // with the module dir as cwd) so the bundled branch wins.
        val binDir = Path.of("bin")
        val exeName = if (System.getProperty("os.name").lowercase().contains("win")) "browser4-cli.exe" else "browser4-cli"
        val fakeExe = binDir.resolve(exeName)
        Files.createDirectories(binDir)
        Files.writeString(fakeExe, "")
        try {
            val resolved = CliBinaryResolver().resolve()
            assertTrue(resolved.isAbsolute, "resolved binary must be absolute, got: $resolved")
            assertEquals(exeName, resolved.fileName.toString())
            assertTrue(Files.exists(resolved))
        } finally {
            Files.deleteIfExists(fakeExe)
            Files.deleteIfExists(binDir)
        }
    }

    @Test
    @DisplayName("explicit path resolves to an absolute path")
    fun testExplicitPathIsAbsolute() {
        val fakeExe = tempDir.resolve("browser4-cli-custom.exe")
        Files.writeString(fakeExe, "")
        val resolved = CliBinaryResolver(explicitPath = fakeExe).resolve()
        assertTrue(resolved.isAbsolute)
        assertEquals(fakeExe, resolved)
    }

    @Test
    @DisplayName("missing explicit path never comes back as the resolved binary")
    fun testMissingExplicitPathNeverReturned() {
        val missing = tempDir.resolve("nope.exe")
        val resolver = CliBinaryResolver(explicitPath = missing)
        // resolve() either finds another candidate (bundled/PATH/dev wrapper on
        // dev machines) or throws; it must never return the missing path, and
        // whatever it returns must be absolute.
        val resolved = runCatching { resolver.resolve() }.getOrNull()
        if (resolved != null) {
            assertTrue(resolved.isAbsolute)
            assertTrue(resolved != missing, "missing explicit path must not be returned")
        }
    }
}
