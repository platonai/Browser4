package ai.platon.browser4.boot.plugin

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito
import org.springframework.context.ApplicationContext
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.Attributes
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import kotlin.io.path.*

class PluginServiceTest {

    // ---- Helpers ----

    private fun createTempPluginDir(): Path {
        return Files.createTempDirectory("browser4-test-plugins-")
    }

    /**
     * Creates a minimal plugin JAR at [targetDir]/[fileName] containing a valid
     * `META-INF/browser4-plugin.json` with the given [name] and [version].
     *
     * Uses [JarOutputStream] with a proper [Manifest] so the output is a
     * well-formed JAR readable by [java.util.jar.JarFile].
     */
    private fun createPluginJar(
        targetDir: Path,
        fileName: String = "test-plugin-1.0.0.jar",
        name: String = "test-plugin",
        version: String = "1.0.0",
        autoConfigurationClasses: List<String> = listOf("java.lang.String"),
    ): Path {
        val manifestJson = """
            {
                "name": "$name",
                "version": "$version",
                "description": "Test plugin for unit tests",
                "dependsOn": ["browser4-skeleton"],
                "autoConfigurationClasses": [${autoConfigurationClasses.joinToString(", ") { "\"$it\"" }}]
            }
        """.trimIndent()

        // Build a proper java.util.jar.Manifest so JarOutputStream writes a valid JAR
        val jdkManifest = Manifest().apply {
            mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
            mainAttributes.putValue("Created-By", "PluginServiceTest")
        }

        val jarPath = targetDir.resolve(fileName)
        JarOutputStream(Files.newOutputStream(jarPath), jdkManifest).use { jos ->
            jos.putNextEntry(JarEntry("META-INF/browser4-plugin.json"))
            jos.write(manifestJson.toByteArray(Charsets.UTF_8))
            jos.closeEntry()
        }
        return jarPath
    }

    /**
     * Creates a non-plugin JAR (no browser4-plugin.json manifest, only MANIFEST.MF).
     */
    private fun createNonPluginJar(targetDir: Path, fileName: String = "plain-lib.jar"): Path {
        val jdkManifest = Manifest().apply {
            mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
        }
        val jarPath = targetDir.resolve(fileName)
        JarOutputStream(Files.newOutputStream(jarPath), jdkManifest).use { _ -> }
        return jarPath
    }

    private fun mockAppContext(): ApplicationContext {
        val ctx = Mockito.mock(ApplicationContext::class.java)
        // By default, return empty array -- plugin is not loaded
        Mockito.`when`(ctx.getBeanNamesForType(Mockito.any<Class<*>>())).thenReturn(emptyArray())
        return ctx
    }

    // ---- listPlugins() ----

    @Test
    fun `listPlugins returns empty list when directory does not exist`() {
        val service = PluginService(mockAppContext(), Path.of("/no/such/plugins/dir"))
        val result = service.listPlugins()
        assertTrue(result.isEmpty())
    }

    @Test
    fun `listPlugins returns empty list when directory is empty`() {
        val dir = createTempPluginDir()
        try {
            val service = PluginService(mockAppContext(), dir)
            val result = service.listPlugins()
            assertTrue(result.isEmpty())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `listPlugins returns info for each JAR with manifest`() {
        val dir = createTempPluginDir()
        try {
            createPluginJar(dir, "plugin-a-1.0.0.jar", name = "plugin-a")
            createPluginJar(dir, "plugin-b-2.0.0.jar", name = "plugin-b")
            createNonPluginJar(dir, "not-a-plugin.jar")

            val service = PluginService(mockAppContext(), dir)
            val result = service.listPlugins()

            assertEquals(3, result.size, "Should find all 3 JARs in directory")

            val a = result.find { it.fileName == "plugin-a-1.0.0.jar" }
            assertNotNull(a, "plugin-a-1.0.0.jar should be in results")
            assertEquals("plugin-a", a!!.manifest?.name)
            assertEquals("1.0.0", a.manifest?.version)
            assertTrue(a.fileSize > 0, "File size should be positive")
            assertTrue(a.path.endsWith("plugin-a-1.0.0.jar"))
            assertFalse(a.loaded)

            val non = result.find { it.fileName == "not-a-plugin.jar" }
            assertNotNull(non, "not-a-plugin.jar should be in results")
            assertNull(non!!.manifest, "Non-plugin JAR should have null manifest")

        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `listPlugins returns loaded=true when auto-config beans exist`() {
        val dir = createTempPluginDir()
        try {
            // Uses "java.lang.String" so Class.forName succeeds
            createPluginJar(
                dir, "loaded-plugin-1.0.0.jar",
                name = "loaded-plugin",
                autoConfigurationClasses = listOf("java.lang.String")
            )

            val ctx = Mockito.mock(ApplicationContext::class.java)
            Mockito.`when`(ctx.getBeanNamesForType(Mockito.any<Class<*>>()))
                .thenReturn(arrayOf("stringBean"))

            val service = PluginService(ctx, dir)
            val result = service.listPlugins()

            assertEquals(1, result.size)
            assertTrue(
                result[0].loaded,
                "Plugin should be reported as loaded when its auto-config beans exist in context"
            )
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    // ---- getPlugin() ----

    @Test
    fun `getPlugin matches by manifest name`() {
        val dir = createTempPluginDir()
        try {
            createPluginJar(dir, "myplugin-3.0.0.jar", name = "my-unique-plugin")

            val service = PluginService(mockAppContext(), dir)
            val result = service.getPlugin("my-unique-plugin")

            assertNotNull(result, "Should find plugin by manifest name")
            assertEquals("my-unique-plugin", result!!.manifest?.name)
            assertEquals("myplugin-3.0.0.jar", result.fileName)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `getPlugin matches by file name with and without extension`() {
        val dir = createTempPluginDir()
        try {
            createPluginJar(dir, "cool-plugin.jar", name = "cool-plugin")

            val service = PluginService(mockAppContext(), dir)

            assertNotNull(service.getPlugin("cool-plugin.jar"), "Should match by full file name")
            assertNotNull(service.getPlugin("cool-plugin"), "Should match by file name without extension")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `getPlugin returns null when no match`() {
        val dir = createTempPluginDir()
        try {
            val service = PluginService(mockAppContext(), dir)
            assertNull(service.getPlugin("nonexistent"))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    // ---- installPlugin() ----

    @Test
    fun `installPlugin copies JAR to plugin dir and returns info`() {
        val pluginDir = createTempPluginDir()
        val stagingDir = createTempPluginDir()
        try {
            val sourceJar = createPluginJar(stagingDir, "my-plugin-1.0.0.jar", name = "my-plugin")
            val service = PluginService(mockAppContext(), pluginDir)

            val info = service.installPlugin(sourceJar)

            assertEquals("my-plugin-1.0.0.jar", info.fileName)
            assertEquals("my-plugin", info.manifest?.name)
            assertTrue(
                info.path.startsWith(pluginDir.toAbsolutePath().toString()),
                "Installed path should be inside pluginDir"
            )
            assertTrue(
                Files.exists(pluginDir.resolve("my-plugin-1.0.0.jar")),
                "JAR should exist in plugin dir after install"
            )
        } finally {
            pluginDir.toFile().deleteRecursively()
            stagingDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `installPlugin throws on non-JAR file extension`() {
        val pluginDir = createTempPluginDir()
        try {
            val textFile = pluginDir.resolve("readme.txt")
            Files.writeString(textFile, "hello")
            val service = PluginService(mockAppContext(), pluginDir)

            val ex = assertThrows<IllegalArgumentException> {
                service.installPlugin(textFile)
            }
            assertTrue(
                ex.message!!.contains(".jar"),
                "Error message should mention .jar requirement"
            )
        } finally {
            pluginDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `installPlugin throws on JAR without browser4-plugin manifest`() {
        val pluginDir = createTempPluginDir()
        val stagingDir = createTempPluginDir()
        try {
            val plainJar = createNonPluginJar(stagingDir)
            val service = PluginService(mockAppContext(), pluginDir)

            val ex = assertThrows<IllegalArgumentException> {
                service.installPlugin(plainJar)
            }
            assertTrue(
                ex.message!!.contains("browser4-plugin.json"),
                "Error message should mention missing manifest"
            )
        } finally {
            pluginDir.toFile().deleteRecursively()
            stagingDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `installPlugin throws on duplicate without replace flag`() {
        val pluginDir = createTempPluginDir()
        val stagingDir = createTempPluginDir()
        try {
            // Pre-install a JAR with the same file name
            createPluginJar(pluginDir, "dup-plugin-1.0.0.jar", name = "dup-plugin")
            val sourceJar = createPluginJar(stagingDir, "dup-plugin-1.0.0.jar", name = "dup-plugin")
            val service = PluginService(mockAppContext(), pluginDir)

            val ex = assertThrows<IllegalStateException> {
                service.installPlugin(sourceJar, replace = false)
            }
            assertTrue(
                ex.message!!.contains("already installed"),
                "Should report plugin already installed"
            )
        } finally {
            pluginDir.toFile().deleteRecursively()
            stagingDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `installPlugin with replace=true overwrites existing`() {
        val pluginDir = createTempPluginDir()
        val stagingDir = createTempPluginDir()
        try {
            // Pre-install an older version
            createPluginJar(pluginDir, "plugin-1.0.0.jar", name = "plugin", version = "1.0.0")
            // New version
            val sourceJar = createPluginJar(stagingDir, "plugin-1.0.0.jar", name = "plugin", version = "2.0.0")
            val service = PluginService(mockAppContext(), pluginDir)

            val info = service.installPlugin(sourceJar, replace = true)

            assertEquals("2.0.0", info.manifest?.version, "Should install the new version")
            assertTrue(
                Files.exists(pluginDir.resolve("plugin-1.0.0.jar")),
                "JAR should exist after replace install"
            )
        } finally {
            pluginDir.toFile().deleteRecursively()
            stagingDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `installPlugin creates plugin directory if it does not exist`() {
        val parentDir = createTempPluginDir()
        val pluginDir = parentDir.resolve("nested/plugins")
        val stagingDir = createTempPluginDir()
        try {
            val sourceJar = createPluginJar(stagingDir, "nested-test-1.0.0.jar", name = "nested-test")
            val service = PluginService(mockAppContext(), pluginDir)

            service.installPlugin(sourceJar)

            assertTrue(Files.isDirectory(pluginDir), "Plugin dir should be auto-created")
            assertTrue(
                Files.exists(pluginDir.resolve("nested-test-1.0.0.jar")),
                "JAR should exist in auto-created dir"
            )
        } finally {
            parentDir.toFile().deleteRecursively()
            stagingDir.toFile().deleteRecursively()
        }
    }

    // ---- removePlugin() ----

    @Test
    fun `removePlugin deletes JAR by manifest name`() {
        val pluginDir = createTempPluginDir()
        try {
            createPluginJar(pluginDir, "to-remove-1.0.0.jar", name = "to-remove")
            val service = PluginService(mockAppContext(), pluginDir)

            val info = service.removePlugin("to-remove")

            assertEquals("to-remove-1.0.0.jar", info.fileName)
            assertEquals("to-remove", info.manifest?.name)
            assertFalse(
                Files.exists(pluginDir.resolve("to-remove-1.0.0.jar")),
                "JAR should be deleted after removal"
            )
        } finally {
            pluginDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `removePlugin deletes JAR by file name`() {
        val pluginDir = createTempPluginDir()
        try {
            createPluginJar(pluginDir, "delete-me.jar", name = "delete-me")
            val service = PluginService(mockAppContext(), pluginDir)

            val info = service.removePlugin("delete-me.jar")

            assertEquals("delete-me.jar", info.fileName)
            assertFalse(
                Files.exists(pluginDir.resolve("delete-me.jar")),
                "JAR should be deleted after removal"
            )
        } finally {
            pluginDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `removePlugin throws when no match`() {
        val pluginDir = createTempPluginDir()
        try {
            val service = PluginService(mockAppContext(), pluginDir)
            val ex = assertThrows<IllegalArgumentException> {
                service.removePlugin("nonexistent")
            }
            assertTrue(ex.message!!.contains("No plugin found"))
        } finally {
            pluginDir.toFile().deleteRecursively()
        }
    }

    // ---- PluginManifest integration ----

    @Test
    fun `PluginManifest fromJar reads manifest from JAR`() {
        val dir = createTempPluginDir()
        try {
            val jarPath = createPluginJar(dir, "test.jar", name = "test-name", version = "2.0")
            val manifest = ai.platon.pulsar.skeleton.plugin.PluginManifest.fromJar(
                java.util.jar.JarFile(jarPath.toFile())
            )
            assertNotNull(manifest, "fromJar should return non-null for valid JAR")
            assertEquals("test-name", manifest!!.name)
            assertEquals("2.0", manifest.version)
            assertEquals(listOf("java.lang.String"), manifest.autoConfigurationClasses)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    // ---- Edge cases ----

    @Test
    fun `listPlugins skips corrupted JARs gracefully`() {
        val dir = createTempPluginDir()
        try {
            // Create a file named .jar but it's not actually a valid ZIP
            Files.writeString(dir.resolve("corrupt.jar"), "not a real jar file")

            val service = PluginService(mockAppContext(), dir)
            val result = service.listPlugins()

            assertEquals(1, result.size, "Should still list the file even if unreadable")
            assertNull(result[0].manifest, "Corrupt JAR should have null manifest")
            assertEquals("corrupt.jar", result[0].fileName)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `listPlugins ignores non-JAR files`() {
        val dir = createTempPluginDir()
        try {
            Files.writeString(dir.resolve("config.json"), "{}")
            Files.writeString(dir.resolve("README.md"), "# Docs")
            createPluginJar(dir, "real-plugin-1.0.0.jar", name = "real-plugin")

            val service = PluginService(mockAppContext(), dir)
            val result = service.listPlugins()

            assertEquals(1, result.size, "Only .jar files should be listed")
            assertEquals("real-plugin-1.0.0.jar", result[0].fileName)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
