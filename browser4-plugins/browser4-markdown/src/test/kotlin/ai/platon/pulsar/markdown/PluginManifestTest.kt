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
package ai.platon.pulsar.markdown

import ai.platon.pulsar.common.serialize.json.pulsarObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Guards the plugin manifest shipped in the JAR.
 *
 * `plugin list` renders `manifest.version`, so that field must be derived from
 * the Maven build like `sdkVersion` instead of carrying a stale hardcoded
 * literal, otherwise the reported plugin version does not identify the loaded
 * JAR (`browser4-markdown-4.14.1-SNAPSHOT.jar  v4.12.0-rc.1`).
 */
class PluginManifestTest {

    private companion object {
        const val MANIFEST_RESOURCE = "/META-INF/browser4-plugin.json"

        /** The stale hand-maintained literal this test locks out. */
        const val STALE_VERSION = "4.12.0-rc.1"

        val VERSION_PATTERN = Regex("""\d+\.\d+.*""")
    }

    @Test
    @DisplayName("plugin manifest version is filtered from the Maven build version")
    fun manifestVersionMatchesBuildVersion() {
        val text = requireNotNull(javaClass.getResourceAsStream(MANIFEST_RESOURCE)) {
            "$MANIFEST_RESOURCE must be on the test classpath"
        }.use { it.readBytes().decodeToString() }

        val manifest: Map<String, Any?> = pulsarObjectMapper().readValue(text)
        val version = manifest["version"]?.toString().orEmpty()
        val sdkVersion = manifest["sdkVersion"]?.toString().orEmpty()

        assertTrue(version.isNotBlank(), "manifest version must not be blank")
        assertNotEquals(STALE_VERSION, version, "the manifest version must not be a stale literal")
        assertTrue(
            VERSION_PATTERN.matches(version),
            "manifest version must be a resolved build version, actual='$version'"
        )
        assertTrue(
            version == sdkVersion,
            "manifest version ('$version') must be filtered from \${project.version} like " +
                "sdkVersion ('$sdkVersion')"
        )
    }
}
