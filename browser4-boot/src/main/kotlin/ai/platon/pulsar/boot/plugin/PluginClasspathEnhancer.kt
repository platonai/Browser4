package ai.platon.pulsar.boot.plugin

import ai.platon.pulsar.skeleton.plugin.Browser4Version
import ai.platon.pulsar.skeleton.plugin.PluginManifest
import org.slf4j.LoggerFactory
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarFile

/**
 * Enhances the classpath with JARs from the [pluginsDir] directory before
 * Spring Boot starts.
 *
 * This must be called from `main()` before `runApplication`. It constructs a
 * URLClassLoader that includes all plugin JAR files, parented to the current
 * thread-context classloader, so that Spring Boot can discover
 * `AutoConfiguration.imports` files inside those JARs.
 *
 * Only plugins that are enabled by the [PluginLoadPolicy] are added —
 * `defaultEnabled: false` (opt-in) plugins are skipped unless explicitly
 * enabled via `browser4.plugins.enable` / `browser4.plugins.enable-all`.
 *
 * Usage:
 * ```
 * PluginClasspathEnhancer.enhance(Path.of("plugins"))
 * runApplication<ApiApplication>(*args)
 * ```
 */
object PluginClasspathEnhancer {

    private val logger = LoggerFactory.getLogger(PluginClasspathEnhancer::class.java)

    /** The enhanced URLClassLoader, saved so it can be closed to release file handles. */
    @Volatile
    private var enhancedLoader: URLClassLoader? = null

    /** The original thread-context classloader before enhancement. */
    @Volatile
    private var originalLoader: ClassLoader? = null

    /**
     * Scans [pluginDir] for .jar files, wraps the current thread-context
     * classloader in a new URLClassLoader that includes them, and installs
     * it as the thread-context classloader.
     *
     * If the directory does not exist or contains no JARs, this is a no-op.
     */
    fun enhance(pluginDir: Path = Path.of("plugins")) {
        if (!Files.isDirectory(pluginDir)) {
            logger.debug("Plugin directory does not exist: {}", pluginDir.toAbsolutePath())
            return
        }

        val jars = Files.list(pluginDir)
            .filter { it.toString().endsWith(".jar") }
            .sorted()
            .toList()

        val policy = PluginLoadPolicy.fromSystem()
        val selected = selectJars(jars, policy)

        if (selected.isEmpty()) {
            logger.debug("No loadable plugin JARs found in {}", pluginDir.toAbsolutePath())
            return
        }

        val jarUrls = selected.map { it.toUri().toURL() }
        logger.info(
            "Found {} plugin JAR(s) in {} ({} total, {} skipped by load policy)",
            jarUrls.size, pluginDir.toAbsolutePath(), jars.size, jars.size - selected.size
        )
        jarUrls.forEach { url ->
            logger.info("  + {}", url.file.split("/").last())
        }

        val currentLoader = Thread.currentThread().contextClassLoader
        originalLoader = currentLoader
        val loader = URLClassLoader(jarUrls.toTypedArray(), currentLoader)
        enhancedLoader = loader
        Thread.currentThread().contextClassLoader = loader

        logger.info("Plugin classpath enhanced ({} JARs)", jarUrls.size)
    }

    /**
     * Applies the [PluginLoadPolicy]: default-disabled (opt-in) plugins are
     * excluded unless explicitly enabled. JARs without a plugin manifest are
     * loaded as-is for backward compatibility.
     *
     * Plugins whose SDK major version is newer than the host's are excluded
     * with an error log (they cannot work against older hosts); older-SDK
     * plugins are kept with a warning.
     */
    internal fun selectJars(
        jars: List<Path>,
        policy: PluginLoadPolicy,
        hostVersion: String = Browser4Version.version,
    ): List<Path> {
        return jars.filter { jar ->
            val manifest = runCatching {
                JarFile(jar.toFile()).use { PluginManifest.fromJar(it) }
            }.getOrNull()

            when {
                manifest == null -> {
                    logger.debug("JAR without plugin manifest: {} (loaded as-is)", jar.fileName)
                    true
                }
                !policy.isEnabled(manifest) -> {
                    logger.info(
                        "Skipping plugin '{}' ({}): {}",
                        manifest.name, jar.fileName, policy.disabledReason(manifest)
                    )
                    false
                }
                else -> when (val verdict = PluginCompatibility.check(manifest, hostVersion)) {
                    is PluginCompatibility.Blocked -> {
                        logger.error(
                            "Skipping incompatible plugin '{}' ({}): {}",
                            manifest.name, jar.fileName, verdict.reason
                        )
                        false
                    }
                    is PluginCompatibility.Warn -> {
                        logger.warn(
                            "Loading plugin '{}' ({}) with compatibility warning: {}",
                            manifest.name, jar.fileName, verdict.reason
                        )
                        true
                    }
                    is PluginCompatibility.Compatible -> true
                }
            }
        }
    }

    /**
     * Closes the enhanced URLClassLoader and restores the original
     * thread-context classloader.
     *
     * This releases file handles to all plugin JARs, which is necessary
     * on Windows where the JVM locks files that are on the classpath.
     * Call [enhance] afterwards to re-create the classloader with the
     * remaining JARs.
     *
     * Safe to call multiple times — subsequent calls are no-ops if the
     * loader is already closed.
     *
     * @return true if a classloader was actually closed, false if this
     *         was a no-op (no prior enhancement)
     */
    fun close(): Boolean {
        val loader = enhancedLoader
        if (loader != null) {
            try {
                loader.close()
                logger.info("Plugin classloader closed; file handles released")
            } catch (e: Exception) {
                logger.warn("Failed to close plugin classloader: {}", e.message)
            }
            enhancedLoader = null
        }

        val orig = originalLoader
        if (orig != null) {
            Thread.currentThread().contextClassLoader = orig
            logger.debug("TCCL restored to original classloader")
        }

        return loader != null
    }
}
