package ai.platon.browser4.boot.plugin

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
 */
data class PluginInfo(
    val fileName: String,
    val fileSize: Long,
    val path: String,
    val manifest: PluginManifest?,
    val loaded: Boolean,
)
