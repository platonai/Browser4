/**
 * Copyright (c) Vincent Zhang, ivincent.zhang@gmail.com, Platon.AI.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.platon.pulsar.images

import ai.platon.pulsar.common.serialize.json.pulsarObjectMapper
import ai.platon.pulsar.skeleton.plugin.Browser4Version
import ai.platon.pulsar.skeleton.plugin.PluginManifest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Tests for the plugin manifest: the version reported by the plugin and by the
 * packaged `META-INF/browser4-plugin.json` must come from the Maven build.
 */
class ImagesPluginTest {

    @Test
    @DisplayName("the manifest version comes from the build, not the stale 4.12.0-rc.1 literal")
    fun testManifestVersionMatchesTheBuild() {
        val manifest = classpathManifest()

        assertNotNull(manifest, "the plugin manifest must be on the classpath")
        assertNotEquals("4.12.0-rc.1", manifest!!.version)
        // resources filtering injects ${project.version} into both fields
        assertEquals(manifest.sdkVersion, manifest.version)

        if (!manifest.version.startsWith("\${")) {
            // Unfiltered (IDE) resources keep the placeholder; a Maven build resolves it.
            assertEquals(Browser4Version.version, manifest.version)
            assertEquals(manifest.version, ImagesPlugin().manifest.version)
        }
    }

    @Test
    @DisplayName("pluginVersion falls back to the host version when no JAR manifest is readable")
    fun testPluginVersionFallsBackToTheHostVersion() {
        // unit tests run from target/classes, where the package has no
        // Implementation-Version — the host version is the truthful answer
        assertEquals(Browser4Version.version, ImagesPlugin.pluginVersion())
    }

    @Test
    @DisplayName("the programmatic manifest keeps the plugin identity and mount point")
    fun testManifestIdentity() {
        val manifest = ImagesPlugin().manifest

        assertEquals("browser4-images", manifest.name)
        assertEquals(Browser4Version.version, manifest.sdkVersion)
        assertTrue(manifest.autoConfigurationClasses.contains("ai.platon.pulsar.images.config.ImageAutoConfiguration"))
        assertEquals(listOf("browser4-protocol", "browser4-agentic"), manifest.dependsOn)
    }

    private fun classpathManifest(): PluginManifest? =
        ImagesPlugin::class.java.classLoader.getResourceAsStream("META-INF/browser4-plugin.json")
            ?.use { pulsarObjectMapper().readValue(it, PluginManifest::class.java) }
}
