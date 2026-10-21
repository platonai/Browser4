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
package ai.platon.pulsar.pptx

import ai.platon.pulsar.common.serialize.json.pulsarObjectMapper
import ai.platon.pulsar.skeleton.plugin.Browser4Version
import ai.platon.pulsar.skeleton.plugin.PluginManifest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Tests for `META-INF/browser4-plugin.json`: the version rendered by `plugin list`
 * and checked by `bin/verify-plugin.ps1 -Strict` must come from the Maven build,
 * never from a hardcoded literal.
 */
class PptxManifestTest {

    @Test
    @DisplayName("the manifest version comes from the build, not the stale 4.12.1-SNAPSHOT literal")
    fun testManifestVersionMatchesTheBuild() {
        val manifest = classpathManifest()

        assertNotNull(manifest, "the plugin manifest must be on the classpath")
        assertNotEquals("4.12.1-SNAPSHOT", manifest!!.version)
        // Resources filtering injects ${project.version} into both fields, so the
        // version always equals the SDK the plugin was built against.
        assertEquals(manifest.sdkVersion, manifest.version)

        if (!manifest.version.startsWith("\${")) {
            // Unfiltered (IDE) resources keep the placeholder; a Maven build resolves it.
            assertEquals(Browser4Version.version, manifest.version)
        }
    }

    @Test
    @DisplayName("the manifest keeps the plugin identity, dependencies and mount point")
    fun testManifestIdentity() {
        val manifest = classpathManifest()
        assertNotNull(manifest, "the plugin manifest must be on the classpath")

        assertEquals("browser4-pptx", manifest!!.name)
        assertEquals("Convert web pages to PowerPoint (PPTX) files", manifest.description)
        assertEquals(listOf("browser4-protocol", "browser4-agentic"), manifest.dependsOn)
        assertEquals(
            listOf("ai.platon.pulsar.pptx.config.PptxAutoConfiguration"),
            manifest.autoConfigurationClasses
        )
    }

    private fun classpathManifest(): PluginManifest? =
        PptxManifestTest::class.java.classLoader.getResourceAsStream("META-INF/browser4-plugin.json")
            ?.use { pulsarObjectMapper().readValue(it, PluginManifest::class.java) }
}
