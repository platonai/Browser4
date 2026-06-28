package ai.platon.browser4.boot.plugin

import ai.platon.pulsar.skeleton.plugin.PluginManifest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PluginInfoTest {

    @Test
    fun `all fields are accessible`() {
        val manifest = PluginManifest(
            name = "test-plugin",
            version = "2.0.0",
            description = "A test plugin",
            dependsOn = listOf("browser4-skeleton"),
            autoConfigurationClasses = listOf("com.example.TestAutoConfiguration"),
        )
        val info = PluginInfo(
            fileName = "test-plugin-2.0.0.jar",
            fileSize = 42_000,
            path = "/tmp/plugins/test-plugin-2.0.0.jar",
            manifest = manifest,
            loaded = true,
        )

        assertEquals("test-plugin-2.0.0.jar", info.fileName)
        assertEquals(42_000, info.fileSize)
        assertEquals("/tmp/plugins/test-plugin-2.0.0.jar", info.path)
        assertEquals("test-plugin", info.manifest?.name)
        assertEquals("2.0.0", info.manifest?.version)
        assertEquals(listOf("browser4-skeleton"), info.manifest?.dependsOn)
        assertEquals(listOf("com.example.TestAutoConfiguration"), info.manifest?.autoConfigurationClasses)
        assertTrue(info.loaded)
    }

    @Test
    fun `manifest can be null for non-plugin JARs`() {
        val info = PluginInfo(
            fileName = "random-library.jar",
            fileSize = 100,
            path = "/tmp/plugins/random-library.jar",
            manifest = null,
            loaded = false,
        )

        assertNull(info.manifest)
        assertFalse(info.loaded)
    }

    @Test
    fun `copy preserves all fields`() {
        val original = PluginInfo(
            fileName = "test.jar",
            fileSize = 1024,
            path = "/path/to/test.jar",
            manifest = PluginManifest("test", "1.0"),
            loaded = false,
        )
        val copy = original.copy(loaded = true)

        assertEquals(original.fileName, copy.fileName)
        assertEquals(original.fileSize, copy.fileSize)
        assertEquals(original.path, copy.path)
        assertEquals(original.manifest, copy.manifest)
        assertTrue(copy.loaded)
        assertFalse(original.loaded)
    }
}
