package ai.platon.browser4.boot.plugin

import org.slf4j.LoggerFactory
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path

/**
 * Enhances the classpath with JARs from the [pluginsDir] directory before
 * Spring Boot starts.
 *
 * This must be called from `main()` before `runApplication`. It constructs a
 * URLClassLoader that includes all plugin JAR files, parented to the current
 * thread-context classloader, so that Spring Boot can discover
 * `AutoConfiguration.imports` files inside those JARs.
 *
 * Usage:
 * ```
 * PluginClasspathEnhancer.enhance(Path.of("plugins"))
 * runApplication<ApiApplication>(*args)
 * ```
 */
object PluginClasspathEnhancer {

    private val logger = LoggerFactory.getLogger(PluginClasspathEnhancer::class.java)

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

        val jarUrls = Files.list(pluginDir)
            .filter { it.toString().endsWith(".jar") }
            .sorted()
            .map { it.toUri().toURL() }
            .toList()

        if (jarUrls.isEmpty()) {
            logger.debug("No plugin JARs found in {}", pluginDir.toAbsolutePath())
            return
        }

        logger.info("Found {} plugin JAR(s) in {}", jarUrls.size, pluginDir.toAbsolutePath())
        jarUrls.forEach { url ->
            logger.info("  + {}", url.file.split("/").last())
        }

        val currentLoader = Thread.currentThread().contextClassLoader
        val enhancedLoader = URLClassLoader(jarUrls.toTypedArray(), currentLoader)
        Thread.currentThread().contextClassLoader = enhancedLoader

        logger.info("Plugin classpath enhanced ({} JARs)", jarUrls.size)
    }
}
