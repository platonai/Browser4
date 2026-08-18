package ai.platon.pulsar.skeleton.plugin

import ai.platon.pulsar.common.serialize.json.pulsarObjectMapper
import com.fasterxml.jackson.annotation.JsonProperty
import java.io.InputStreamReader
import java.util.jar.JarFile

/**
 * Plugin manifest metadata, deserialized from `META-INF/browser4-plugin.json` inside a plugin JAR.
 *
 * Every plugin JAR must contain this file so that the plugin loader can identify it.
 */
data class PluginManifest(
    val name: String,
    val version: String,
    @field:JsonProperty("description")
    val description: String = "",
    /**
     * The Browser4 SDK version this plugin was compiled against
     * (e.g. `4.14.0`), used by the host to decide compatibility.
     *
     * Plugins built before 4.14 (which did not declare this field) fall back
     * to the JAR's `Browser4-Plugin-Version` manifest attribute; when neither
     * is present the host treats the plugin as "unknown SDK" and loads it on
     * a best-effort basis.
     */
    @field:JsonProperty("sdkVersion")
    val sdkVersion: String = "",
    @field:JsonProperty("dependsOn")
    val dependsOn: List<String> = emptyList(),
    /**
     * Whether the plugin is loaded automatically at startup.
     *
     * - `true` (default): default-loaded — activated unless explicitly disabled.
     * - `false`: opt-in — NOT activated unless explicitly enabled
     *   (`browser4.plugins.enable=<name>` or `browser4.plugins.enable-all=true`).
     */
    @field:JsonProperty("defaultEnabled")
    val defaultEnabled: Boolean = true,
    @field:JsonProperty("autoConfigurationClasses")
    val autoConfigurationClasses: List<String> = emptyList(),
) {
    companion object {
        private const val MANIFEST_PATH = "META-INF/browser4-plugin.json"

        /**
         * JAR manifest attribute written by the plugin archetype/PDK with the
         * Browser4 SDK version the plugin was built against.
         */
        private const val MANIFEST_SDK_VERSION_ATTR = "Browser4-Plugin-Version"

        /**
         * Attempts to read the plugin manifest from the given JAR file.
         *
         * When the JSON manifest does not declare [sdkVersion], falls back to
         * the JAR's `Browser4-Plugin-Version` MANIFEST.MF attribute so plugins
         * built before the sdkVersion field existed still carry their SDK
         * version.
         *
         * @return the parsed manifest, or null if the JAR does not contain a manifest.
         */
        fun fromJar(jarFile: JarFile): PluginManifest? {
            val entry = jarFile.getJarEntry(MANIFEST_PATH) ?: return null
            val manifest = jarFile.getInputStream(entry).use { stream ->
                InputStreamReader(stream).use { reader ->
                    pulsarObjectMapper().readValue(reader, PluginManifest::class.java)
                }
            }
            if (manifest.sdkVersion.isBlank()) {
                val declared = jarFile.manifest?.mainAttributes?.getValue(MANIFEST_SDK_VERSION_ATTR)
                    ?.takeIf { it.isNotBlank() }
                if (declared != null) {
                    return manifest.copy(sdkVersion = declared)
                }
            }
            return manifest
        }
    }
}
