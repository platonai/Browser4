package ai.platon.pulsar.boot.plugin

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.Attributes
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest

class PluginClasspathEnhancerTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `selectJars keeps default-enabled plugins and filters opt-in plugins`() {
        val defaultJar = createPluginJar("default-1.0.0.jar", "default-plugin", defaultEnabled = true)
        val optInJar = createPluginJar("optin-1.0.0.jar", "optin-plugin", defaultEnabled = false)
        val plainJar = createNonPluginJar("lib.jar")

        val selected = PluginClasspathEnhancer.selectJars(
            listOf(defaultJar, optInJar, plainJar),
            PluginLoadPolicy(enableAll = false, enabledNames = emptySet(), disabledNames = emptySet())
        )

        assertEquals(listOf(defaultJar, plainJar), selected)
    }

    @Test
    fun `selectJars honors explicit enable of opt-in plugins`() {
        val defaultJar = createPluginJar("default-1.0.0.jar", "default-plugin")
        val optInJar = createPluginJar("optin-1.0.0.jar", "optin-plugin", defaultEnabled = false)

        val selected = PluginClasspathEnhancer.selectJars(
            listOf(defaultJar, optInJar),
            PluginLoadPolicy(enableAll = false, enabledNames = setOf("optin-plugin"), disabledNames = emptySet())
        )

        assertEquals(listOf(defaultJar, optInJar), selected)
    }

    @Test
    fun `selectJars honors enable-all and explicit disable`() {
        val defaultJar = createPluginJar("default-1.0.0.jar", "default-plugin")
        val optInJar = createPluginJar("optin-1.0.0.jar", "optin-plugin", defaultEnabled = false)

        val selected = PluginClasspathEnhancer.selectJars(
            listOf(defaultJar, optInJar),
            PluginLoadPolicy(
                enableAll = true,
                enabledNames = emptySet(),
                disabledNames = setOf("default-plugin"),
            )
        )

        assertEquals(listOf(optInJar), selected)
    }

    // ---- Helpers ----

    private fun createPluginJar(
        fileName: String,
        name: String,
        defaultEnabled: Boolean = true,
    ): Path {
        val manifestJson = """
            {
                "name": "$name",
                "version": "1.0.0",
                "defaultEnabled": $defaultEnabled,
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

    private fun createNonPluginJar(fileName: String): Path {
        val jarPath = tempDir.resolve(fileName)
        val jdkManifest = Manifest().apply {
            mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
        }
        JarOutputStream(Files.newOutputStream(jarPath), jdkManifest).use { _ -> }
        return jarPath
    }
}
