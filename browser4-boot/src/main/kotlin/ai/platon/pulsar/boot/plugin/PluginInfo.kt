package ai.platon.pulsar.boot.plugin

import ai.platon.pulsar.skeleton.plugin.PluginManifest

/**
 * Runtime information about an installed plugin JAR in the [plugins] directory.
 *
 * @param fileName  the JAR file name (e.g. "browser4-captcha-4.12.0.jar")
 * @param fileSize  size of the JAR in bytes
 * @param path      absolute path to the JAR on disk
 * @param manifest  parsed [PluginManifest], or null if the JAR does not contain
 *                  a valid `META-INF/browser4-plugin.json`
 * @param loaded    whether any of the plugin's auto-configuration classes are
 *                  currently registered in the Spring application context
 * @param defaultEnabled whether the plugin is in the default-loaded category
 *                       (`manifest.defaultEnabled`, true when manifest is absent)
 * @param enabled   effective activation state after applying [PluginLoadPolicy]
 *                  overrides (`browser4.plugins.enable|disable|enable-all`)
 */
data class PluginInfo(
    val fileName: String,
    val fileSize: Long,
    val path: String,
    val manifest: PluginManifest?,
    val loaded: Boolean,
    val defaultEnabled: Boolean = true,
    val enabled: Boolean = true,
)
