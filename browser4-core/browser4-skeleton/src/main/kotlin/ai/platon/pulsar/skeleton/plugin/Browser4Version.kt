package ai.platon.pulsar.skeleton.plugin

import java.util.Properties

/**
 * The version of the running Browser4 host.
 *
 * The build injects `META-INF/browser4-version.properties` (filtered from the
 * module's `${project.version}`, which is governed to equal the repo VERSION
 * file) into every packaged module. The plugin compatibility policy
 * ([ai.platon.pulsar.boot.plugin.PluginCompatibility]) compares plugin
 * `sdkVersion` declarations against this value.
 *
 * Fallback chain when the resource is missing (e.g. running from an IDE with
 * unfiltered resources): the JAR manifest `Implementation-Version`, then
 * `0.0.0-unknown` — an unknown host version never blocks plugin loading.
 */
object Browser4Version {

    private const val RESOURCE_PATH = "META-INF/browser4-version.properties"

    /**
     * Sentinel for "host version unknown". Deliberately NOT parseable as a
     * version (unlike `0.0.0-unknown`), so [ai.platon.pulsar.skeleton.plugin.SdkVersions]
     * yields no major and the compatibility policy never blocks on it.
     */
    const val UNKNOWN = "unknown"

    /** The host Browser4 version, e.g. `4.14.0-SNAPSHOT`. Never null/blank. */
    val version: String by lazy { readVersion() }

    private fun readVersion(): String {
        val resource = Browser4Version::class.java.classLoader.getResourceAsStream(RESOURCE_PATH)
        if (resource != null) {
            resource.use { stream ->
                val props = Properties()
                props.load(stream)
                val version = props.getProperty("version")?.trim()
                if (!version.isNullOrBlank()) return version
            }
        }
        val implementationVersion = Browser4Version::class.java.`package`?.implementationVersion
        return implementationVersion?.takeIf { it.isNotBlank() } ?: UNKNOWN
    }
}
