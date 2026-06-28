package ai.platon.pulsar.skeleton.plugin

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
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
    @SerializedName("description")
    val description: String = "",
    @SerializedName("dependsOn")
    val dependsOn: List<String> = emptyList(),
    @SerializedName("autoConfigurationClasses")
    val autoConfigurationClasses: List<String> = emptyList(),
) {
    companion object {
        private val gson = Gson()
        private const val MANIFEST_PATH = "META-INF/browser4-plugin.json"

        /**
         * Attempts to read the plugin manifest from the given JAR file.
         *
         * @return the parsed manifest, or null if the JAR does not contain a manifest.
         */
        fun fromJar(jarFile: JarFile): PluginManifest? {
            val entry = jarFile.getJarEntry(MANIFEST_PATH) ?: return null
            return jarFile.getInputStream(entry).use { stream ->
                InputStreamReader(stream).use { reader ->
                    gson.fromJson(reader, PluginManifest::class.java)
                }
            }
        }
    }
}
