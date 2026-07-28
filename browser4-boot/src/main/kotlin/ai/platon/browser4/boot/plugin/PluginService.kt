package ai.platon.browser4.boot.plugin

import ai.platon.pulsar.common.getLogger
import ai.platon.pulsar.skeleton.plugin.PluginManifest
import org.springframework.context.ApplicationContext
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.jar.JarFile
import kotlin.io.path.*

/**
 * Service for managing runtime plugins in the [pluginDir].
 *
 * Provides operations to list, inspect, install, and remove plugin JARs.
 * All file-system mutations are safe to call at runtime, but newly installed
 * plugins only take effect after an application restart.
 *
 * @param applicationContext  the Spring application context, used to check
 *                            whether a plugin's beans are currently loaded
 * @param pluginDir           the directory where plugin JARs are stored
 *                            (default: `plugins/` relative to the working directory)
 */
class PluginService(
    private val applicationContext: ApplicationContext,
    private val pluginDir: Path = Path.of("plugins"),
) {

    private val logger = getLogger(PluginService::class)

    // ---- Query ----

    /**
     * Lists all plugin JARs found in [pluginDir].
     *
     * Each JAR is inspected for a [PluginManifest]. The [PluginInfo.loaded]
     * flag indicates whether the plugin's auto-configuration beans are currently
     * present in the application context.
     */
    fun listPlugins(): List<PluginInfo> {
        if (!Files.isDirectory(pluginDir)) {
            logger.debug("Plugin directory does not exist: {}", pluginDir.toAbsolutePath())
            return emptyList()
        }

        return Files.list(pluginDir)
            .filter { it.name.endsWith(".jar") }
            .sorted { a, b -> a.name.compareTo(b.name) }
            .map { jarPath -> toPluginInfo(jarPath) }
            .toList()
    }

    /**
     * Finds a single plugin by name.
     *
     * Matching is attempted in order:
     * 1. Exact match against the manifest [PluginManifest.name]
     * 2. Exact match against the JAR file name (with or without `.jar` extension)
     *
     * @return the matching [PluginInfo], or null if no plugin matches
     */
    fun getPlugin(name: String): PluginInfo? {
        return listPlugins().firstOrNull { info ->
            info.manifest?.name == name
                || info.fileName == name
                || info.fileName.removeSuffix(".jar") == name
        }
    }

    // ---- Install ----

    /**
     * Installs a plugin JAR from [source] into [pluginDir].
     *
     * Validates that the source JAR contains a valid `META-INF/browser4-plugin.json`
     * manifest. The JAR is copied into the plugin directory under its original file name.
     *
     * @param source   path to the plugin JAR file
     * @param replace  if true, overwrite an existing JAR with the same file name
     * @return [PluginInfo] describing the installed plugin
     * @throws IllegalArgumentException  if the source is not a valid plugin JAR
     * @throws IllegalStateException     if a plugin with the same name already exists
     *                                   and [replace] is false
     */
    fun installPlugin(source: Path, replace: Boolean = false): PluginInfo {
        require(Files.isRegularFile(source)) {
            "Plugin source is not a regular file: ${source.toAbsolutePath()}"
        }
        require(source.name.endsWith(".jar")) {
            "Plugin source must be a .jar file: ${source.fileName}"
        }

        // Read the manifest to validate this is a real plugin JAR
        val manifest = readManifest(source)
            ?: throw IllegalArgumentException(
                "Not a valid Browser4 plugin: ${source.fileName} — " +
                    "missing META-INF/browser4-plugin.json"
            )

        // Ensure the target directory exists
        Files.createDirectories(pluginDir)

        val target = pluginDir.resolve(source.name)

        if (Files.exists(target) && !replace) {
            throw IllegalStateException(
                "Plugin '${manifest.name}' is already installed (${target.fileName}). " +
                    "Use replace=true to overwrite, or remove it first."
            )
        }

        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)
        logger.info("Plugin installed: {} v{} → {}", manifest.name, manifest.version, target.fileName)

        val installed = toPluginInfo(target)
        logger.info(
            "  {} ({} bytes) — restart the application to activate",
            installed.fileName,
            installed.fileSize
        )

        return installed
    }

    // ---- Remove ----

    /**
     * Removes a plugin JAR from [pluginDir].
     *
     * Matching works the same way as [getPlugin]: by manifest name or JAR file name.
     * The JAR is deleted from disk. Any beans already loaded from this plugin
     * remain in the application context until the next restart.
     *
     * @param name  the plugin name (manifest name or file name)
     * @return [PluginInfo] describing the removed plugin
     * @throws IllegalArgumentException  if no plugin matches [name]
     */
    fun removePlugin(name: String): PluginInfo {
        val info = getPlugin(name)
            ?: throw IllegalArgumentException("No plugin found matching '$name'")

        val jarPath = Path.of(info.path)
        Files.delete(jarPath)
        logger.info("Plugin removed: {} (was {})", info.fileName, info.path)

        if (info.loaded) {
            logger.info(
                "  Plugin '{}' was loaded at startup. Beans will be cleaned up on next restart.",
                info.manifest?.name ?: info.fileName
            )
        }

        return info
    }

    // ---- Internal ----

    private fun toPluginInfo(jarPath: Path): PluginInfo {
        val manifest = readManifest(jarPath)
        val loaded = manifest?.autoConfigurationClasses?.any { className ->
            try {
                val clazz = Class.forName(className, false, javaClass.classLoader)
                applicationContext.getBeanNamesForType(clazz).isNotEmpty()
            } catch (_: Exception) {
                false
            }
        } ?: false

        return PluginInfo(
            fileName = jarPath.name,
            fileSize = Files.size(jarPath),
            path = jarPath.toAbsolutePath().toString(),
            manifest = manifest,
            loaded = loaded,
        )
    }

    private fun readManifest(jarPath: Path): PluginManifest? {
        return try {
            JarFile(jarPath.toFile()).use { jar ->
                PluginManifest.fromJar(jar)
            }
        } catch (e: Exception) {
            logger.debug("Failed to read manifest from {}: {}", jarPath.fileName, e.message)
            null
        }
    }
}
