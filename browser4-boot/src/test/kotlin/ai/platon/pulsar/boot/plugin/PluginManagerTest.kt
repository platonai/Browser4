package ai.platon.pulsar.boot.plugin

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.Attributes
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest

/**
 * Tests for [manifestOfLocation] — resolving a plugin manifest
 * from a class code-source URL. Guards against the regression where a
 * root/empty code source crashed application startup with a
 * NullPointerException (Path.getFileName() is null for root paths).
 */
class PluginManagerTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    @DisplayName("root code source (null file name) yields null instead of NPE")
    fun rootCodeSourceYieldsNull() {
        // "file:/" is the filesystem root: Path.getFileName() is null there.
        assertNull(manifestOfLocation(URI.create("file:/").toURL()))
    }

    @Test
    @DisplayName("directory code source (target/classes) yields null")
    fun directoryCodeSourceYieldsNull() {
        val classesDir = tempDir.resolve("target").resolve("classes")
        Files.createDirectories(classesDir)
        assertNull(manifestOfLocation(classesDir.toUri().toURL()))
    }

    @Test
    @DisplayName("nested fat-jar location yields null instead of NPE")
    fun nestedJarLocationYieldsNull() {
        // Spring Boot fat jars expose code sources like
        // file:/app/app.jar!/BOOT-INF/lib/plugin-1.0.0.jar!/ — not a readable file.
        val nested = URI.create("file:/app/app.jar!/BOOT-INF/lib/plugin-1.0.0.jar!/")
        assertNull(manifestOfLocation(nested.toURL()))
    }

    @Test
    @DisplayName("unreadable jar path yields null")
    fun unreadableJarYieldsNull() {
        val missing = tempDir.resolve("does-not-exist-1.0.0.jar")
        assertNull(manifestOfLocation(missing.toUri().toURL()))
    }

    @Test
    @DisplayName("plugin jar yields its manifest")
    fun pluginJarYieldsManifest() {
        val jarPath = createPluginJar("demo-plugin-1.0.0.jar", "demo-plugin")
        val manifest = manifestOfLocation(jarPath.toUri().toURL())
        assertNotNull(manifest)
        assertEquals("demo-plugin", manifest!!.name)
    }

    @Test
    @DisplayName("plain jar without plugin manifest yields null")
    fun plainJarYieldsNull() {
        val jarPath = tempDir.resolve("plain-lib-1.0.0.jar")
        val jdkManifest = Manifest().apply {
            mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
        }
        JarOutputStream(Files.newOutputStream(jarPath), jdkManifest).use { _ -> }
        assertNull(manifestOfLocation(jarPath.toUri().toURL()))
    }

    // ---- Helper ----

    private fun createPluginJar(fileName: String, name: String): Path {
        val manifestJson = """
            {
                "name": "$name",
                "version": "1.0.0",
                "defaultEnabled": true,
                "autoConfigurationClasses": ["java.lang.String"]
            }
        """.trimIndent()

        val jarPath = tempDir.resolve(fileName)
        val jdkManifest = Manifest().apply {
            mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
        }
        JarOutputStream(Files.newOutputStream(jarPath), jdkManifest).use { jos ->
            jos.putNextEntry(JarEntry("META-INF/browser4-plugin.json"))
            jos.write(manifestJson.toByteArray(Charsets.UTF_8))
            jos.closeEntry()
        }
        return jarPath
    }
}
