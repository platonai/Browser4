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
package ai.platon.pulsar.media

import ai.platon.pulsar.common.serialize.json.pulsarObjectMapper
import ai.platon.pulsar.skeleton.plugin.Browser4Version
import ai.platon.pulsar.skeleton.plugin.PluginManifest
import com.fasterxml.jackson.module.kotlin.readValue
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Guards the plugin manifest shipped in the JAR.
 *
 * `plugin list` renders `manifest.version`, so that field must be derived from the Maven
 * build like `sdkVersion` instead of carrying a stale hardcoded literal — otherwise the
 * reported plugin version does not identify the loaded JAR
 * (`browser4-media-4.14.1-SNAPSHOT.jar  v4.12.0-rc.1`).
 */
class MediaPluginTest {

    private companion object {
        const val MANIFEST_RESOURCE = "/META-INF/browser4-plugin.json"

        /** The stale hand-maintained literal this test locks out. */
        const val STALE_VERSION = "4.12.0-rc.1"

        /** A resolved (filtered) version, e.g. `4.14.1-SNAPSHOT`. */
        val VERSION_PATTERN = Regex("""\d+\.\d+.*""")
    }

    @Test
    @DisplayName("the manifest version comes from the build, not the stale 4.12.0-rc.1 literal")
    fun testManifestVersionMatchesTheBuild() {
        val manifest = classpathManifest()

        assertNotNull(manifest, "$MANIFEST_RESOURCE must be on the test classpath")
        assertTrue(manifest!!.version.isNotBlank(), "manifest version must not be blank")
        assertNotEquals(STALE_VERSION, manifest.version, "the manifest version must not be a stale literal")
        assertTrue(
            VERSION_PATTERN.matches(manifest.version),
            "manifest version must be a resolved build version, actual='${manifest.version}'"
        )
        // resources filtering injects ${project.version} into both fields
        assertEquals(manifest.sdkVersion, manifest.version)

        if (!manifest.version.startsWith("\${")) {
            // Unfiltered (IDE) resources keep the placeholder; a Maven build resolves it.
            assertEquals(Browser4Version.version, manifest.version)
            assertEquals(manifest.version, MediaPlugin().manifest.version)
        }
    }

    @Test
    @DisplayName("pluginVersion falls back to the host version when no JAR manifest is readable")
    fun testPluginVersionFallsBackToTheHostVersion() {
        // unit tests run from target/classes, where the package has no
        // Implementation-Version — the host version is the truthful answer
        assertEquals(Browser4Version.version, MediaPlugin.pluginVersion())
    }

    @Test
    @DisplayName("the programmatic manifest keeps the plugin identity and mount point")
    fun testManifestIdentity() {
        val manifest = MediaPlugin().manifest

        assertEquals("browser4-media", manifest.name)
        assertEquals(MediaPlugin.pluginVersion(), manifest.version)
        assertEquals(Browser4Version.version, manifest.sdkVersion)
        assertTrue(
            manifest.autoConfigurationClasses.contains("ai.platon.pulsar.media.config.MediaAutoConfiguration")
        )
        assertEquals(listOf("browser4-protocol", "browser4-agentic"), manifest.dependsOn)
    }

    private fun classpathManifest(): PluginManifest? =
        javaClass.getResourceAsStream(MANIFEST_RESOURCE)
            ?.use { pulsarObjectMapper().readValue<PluginManifest>(it) }
}
