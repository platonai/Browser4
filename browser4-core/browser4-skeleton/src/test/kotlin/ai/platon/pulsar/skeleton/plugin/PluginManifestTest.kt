package ai.platon.pulsar.skeleton.plugin

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.Attributes
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import java.util.jar.Manifest

class PluginManifestTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun fromJarParsesSdkVersionFromJson() {
        val jar = createJar(
            json = """
                {
                    "name": "plugin-a",
                    "version": "1.0.0",
                    "sdkVersion": "4.14.0-SNAPSHOT",
                    "autoConfigurationClasses": ["java.lang.String"]
                }
            """.trimIndent()
        )
        JarFile(jar.toFile()).use { jf ->
            val manifest = PluginManifest.fromJar(jf)
            assertEquals("plugin-a", manifest!!.name)
            assertEquals("4.14.0-SNAPSHOT", manifest.sdkVersion)
        }
    }

    @Test
    fun fromJarFallsBackToManifestAttributeWhenJsonHasNoSdkVersion() {
        val jar = createJar(
            json = """
                {
                    "name": "legacy-plugin",
                    "version": "4.12.0-rc.1",
                    "autoConfigurationClasses": ["java.lang.String"]
                }
            """.trimIndent(),
            manifestAttributes = mapOf("Browser4-Plugin-Version" to "4.12.0-rc.1"),
        )
        JarFile(jar.toFile()).use { jf ->
            val manifest = PluginManifest.fromJar(jf)
            assertEquals("legacy-plugin", manifest!!.name)
            assertEquals("4.12.0-rc.1", manifest.sdkVersion)
        }
    }

    @Test
    fun fromJarPrefersJsonSdkVersionOverManifestAttribute() {
        val jar = createJar(
            json = """
                {
                    "name": "plugin-b",
                    "version": "1.0.0",
                    "sdkVersion": "4.14.0",
                    "autoConfigurationClasses": ["java.lang.String"]
                }
            """.trimIndent(),
            manifestAttributes = mapOf("Browser4-Plugin-Version" to "4.10.0"),
        )
        JarFile(jar.toFile()).use { jf ->
            assertEquals("4.14.0", PluginManifest.fromJar(jf)!!.sdkVersion)
        }
    }

    @Test
    fun fromJarReturnsNullForNonPluginJar() {
        val jar = tempDir.resolve("plain-lib.jar")
        val jdkManifest = Manifest().apply {
            mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
        }
        JarOutputStream(Files.newOutputStream(jar), jdkManifest).use { _ -> }
        JarFile(jar.toFile()).use { jf ->
            assertNull(PluginManifest.fromJar(jf))
        }
    }

    // ---- Helpers ----

    private fun createJar(
        json: String,
        manifestAttributes: Map<String, String> = emptyMap(),
    ): Path {
        val jarPath = tempDir.resolve("plugin-${System.nanoTime()}.jar")
        val jdkManifest = Manifest().apply {
            mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
            manifestAttributes.forEach { (k, v) -> mainAttributes.putValue(k, v) }
        }
        JarOutputStream(Files.newOutputStream(jarPath), jdkManifest).use { jos ->
            jos.putNextEntry(JarEntry("META-INF/browser4-plugin.json"))
            jos.write(json.toByteArray(Charsets.UTF_8))
            jos.closeEntry()
        }
        return jarPath
    }
}
