package ai.platon.pulsar.common

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Unit tests for [B4LLMUtils] source-reading helpers. The jar extraction logic is tested
 * against a locally built sources-style jar so no network access is required.
 */
class B4LLMUtilsTest {

    private fun writeSourcesJar(jarPath: Path, entries: Map<String, String>) {
        Files.createDirectories(jarPath.parent)
        ZipOutputStream(Files.newOutputStream(jarPath)).use { zip ->
            for ((name, content) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
    }

    @Test
    @DisplayName("extractSourceFileFromJar finds a source file by simple name")
    fun extractSourceFileFromJarFindsFile(@TempDir dir: Path) {
        val jar = dir.resolve("pulsar-browser-4.11.6-sources.jar")
        val content = "package ai.platon.pulsar.api\n\ninterface WebDriver {\n    fun navigate(url: String)\n}\n"
        writeSourcesJar(jar, mapOf("ai/platon/pulsar/api/WebDriver.kt" to content))

        assertEquals(content, B4LLMUtils.extractSourceFileFromJar(jar, "WebDriver.kt"))
    }

    @Test
    @DisplayName("extractSourceFileFromJar prefers the deepest matching entry")
    fun extractSourceFileFromJarPrefersDeepestEntry(@TempDir dir: Path) {
        val jar = dir.resolve("sources.jar")
        writeSourcesJar(
            jar,
            mapOf(
                "WebDriver.kt" to "shallow",
                "ai/platon/pulsar/api/WebDriver.kt" to "deep",
            )
        )

        assertEquals("deep", B4LLMUtils.extractSourceFileFromJar(jar, "WebDriver.kt"))
    }

    @Test
    @DisplayName("extractSourceFileFromJar returns null for a missing file")
    fun extractSourceFileFromJarReturnsNullWhenMissing(@TempDir dir: Path) {
        val jar = dir.resolve("sources.jar")
        writeSourcesJar(jar, mapOf("ai/platon/pulsar/api/Browser.kt" to "content"))

        assertNull(B4LLMUtils.extractSourceFileFromJar(jar, "WebDriver.kt"))
    }

    @Test
    @DisplayName("extractSourceFileFromJar returns null when the jar does not exist")
    fun extractSourceFileFromJarReturnsNullForMissingJar(@TempDir dir: Path) {
        assertNull(B4LLMUtils.extractSourceFileFromJar(dir.resolve("no-such.jar"), "WebDriver.kt"))
    }
}
