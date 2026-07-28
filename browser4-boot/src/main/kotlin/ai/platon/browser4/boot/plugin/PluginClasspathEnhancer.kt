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
        originalLoader = currentLoader
        val loader = URLClassLoader(jarUrls.toTypedArray(), currentLoader)
        enhancedLoader = loader
        Thread.currentThread().contextClassLoader = loader

        logger.info("Plugin classpath enhanced ({} JARs)", jarUrls.size)
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
