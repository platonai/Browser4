package ai.platon.pulsar.boot.plugin

import ai.platon.pulsar.agentic.tools.CustomToolRegistry
import ai.platon.pulsar.agentic.tools.ToolMount
import ai.platon.pulsar.agentic.tools.advanced.crawl.SwarmFacadeMount
import ai.platon.pulsar.agentic.tools.advanced.crawl.SwarmFacadeRegistry
import ai.platon.pulsar.common.getLogger
import ai.platon.pulsar.protocol.browser.emulator.BrowserResponseHandler
import ai.platon.pulsar.protocol.browser.emulator.util.PageSnifferMount
import ai.platon.pulsar.skeleton.event.PulsarEventBus
import ai.platon.pulsar.skeleton.plugin.BrowseEventMount
import ai.platon.pulsar.skeleton.plugin.Browser4Plugin
import ai.platon.pulsar.skeleton.plugin.CrawlEventMount
import ai.platon.pulsar.skeleton.plugin.LoadEventMount
import ai.platon.pulsar.skeleton.plugin.PluginManifest
import ai.platon.pulsar.skeleton.plugin.PluginMount
import jakarta.annotation.PreDestroy
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.context.ApplicationContext
import java.nio.file.Path
import java.util.jar.JarFile

/**
 * Discovers and activates all plugins after the Spring context is fully initialized.
 *
 * Runs as an [ApplicationRunner] after the application context is refreshed.
 * It scans for beans that implement [PluginMount] sub-interfaces and wires them
 * into the appropriate integration points.
 *
 * ## Mount point wiring (28 event hooks across 3 phases)
 *
 * - [LoadEventMount] → `PulsarEventBus.pageEventHandlers.loadEventHandlers` (9 hooks)
 * - [BrowseEventMount] → `PulsarEventBus.pageEventHandlers.browseEventHandlers` (16 hooks)
 * - [CrawlEventMount] → `PulsarEventBus.pageEventHandlers.crawlEventHandlers` (2 hooks)
 * - [ToolMount] → [CustomToolRegistry]
 * - [SwarmFacadeMount] → [SwarmFacadeRegistry]
 * - [PageSnifferMount] → [BrowserResponseHandler.pageCategorySniffer]
 */
class PluginManager(
    private val applicationContext: ApplicationContext,
) : ApplicationRunner {

    private val logger = getLogger(PluginManager::class)
    private val plugins = mutableListOf<Browser4Plugin>()
    private val loadPolicy = PluginLoadPolicy.fromEnvironment(applicationContext.environment)

    override fun run(args: ApplicationArguments) {
        logger.info("--- PluginManager: scanning for plugins ---")

        // Discover all PluginMount beans (includes all sub-interfaces)
        val mountBeans = applicationContext.getBeansOfType(PluginMount::class.java)
        logger.info("Found {} PluginMount bean(s)", mountBeans.size)

        if (mountBeans.isNotEmpty()) {
            mountBeans.forEach { (name, mount) ->
                logger.info("  - {} : {}", name, mount.javaClass.simpleName)
            }
            wireAllMounts(mountBeans.values.toList())
        }

        // Discover and initialize Browser4Plugin beans
        val pluginBeans = applicationContext.getBeansOfType(Browser4Plugin::class.java)
        logger.info("Found {} Browser4Plugin bean(s)", pluginBeans.size)

        pluginBeans.values.forEach { plugin ->
            if (!loadPolicy.isEnabled(plugin.manifest)) {
                logger.info(
                    "  - Skipping disabled plugin '{}': {}",
                    plugin.manifest.name, loadPolicy.disabledReason(plugin.manifest)
                )
                return@forEach
            }

            // Defense in depth for plugins on the main classpath (bundled
            // plugins bypass PluginClasspathEnhancer): refuse beans whose
            // declared SDK is newer than the host.
            when (val verdict = PluginCompatibility.check(plugin.manifest)) {
                is PluginCompatibility.Blocked -> {
                    logger.error(
                        "  - Skipping incompatible plugin '{}': {}",
                        plugin.manifest.name, verdict.reason
                    )
                    return@forEach
                }
                is PluginCompatibility.Warn -> logger.warn(
                    "  - Plugin '{}' compatibility warning: {}",
                    plugin.manifest.name, verdict.reason
                )
                is PluginCompatibility.Compatible -> Unit
            }

            plugins.add(plugin)
            logger.info("  - {} v{} (sdk {})", plugin.manifest.name, plugin.manifest.version, plugin.manifest.sdkVersion)
            plugin.onStartup()
        }

        if (mountBeans.isEmpty() && pluginBeans.isEmpty()) {
            logger.info("No plugins detected.")
        }

        logger.info("--- PluginManager: scan complete ---")
    }

    @PreDestroy
    fun shutdown() {
        plugins.forEach { plugin ->
            try {
                plugin.onShutdown()
            } catch (e: Exception) {
                logger.warn("Error during plugin shutdown: {}", plugin.manifest.name, e)
            }
        }
    }

    // ---- Wiring ----

    private fun wireAllMounts(mounts: List<PluginMount>) {
        val pageHandlers = PulsarEventBus.pageEventHandlers

        for (mount in mounts) {
            val manifest = pluginManifestOf(mount)
            if (manifest != null && !loadPolicy.isEnabled(manifest)) {
                logger.info(
                    "  - Skipping mounts from disabled plugin '{}': {}",
                    manifest.name, loadPolicy.disabledReason(manifest)
                )
                continue
            }

            // Same defense in depth as the Browser4Plugin bean loop below:
            // bundled plugins bypass PluginClasspathEnhancer, so refuse mounts
            // whose declared SDK is newer than the host.
            if (manifest != null) {
                when (val verdict = PluginCompatibility.check(manifest)) {
                    is PluginCompatibility.Blocked -> {
                        logger.error(
                            "  - Skipping mounts from incompatible plugin '{}': {}",
                            manifest.name, verdict.reason
                        )
                        continue
                    }
                    is PluginCompatibility.Warn -> logger.warn(
                        "  - Mounts from plugin '{}' with compatibility warning: {}",
                        manifest.name, verdict.reason
                    )
                    is PluginCompatibility.Compatible -> Unit
                }
            }

            // Use independent `if` checks rather than a `when` expression so that
            // beans implementing multiple mount interfaces (e.g. BrowseEventMount
            // AND ToolMount) are wired into every matching integration point.
            // A `when { }` block short-circuits on the first match, which silently
            // skips downstream mounts like ToolMount for beans that also implement
            // BrowseEventMount (e.g. ImageAutoConfiguration, CaptchaAutoConfiguration).

            // --- Event-phase mounts ---
            if (mount is LoadEventMount) {
                if (pageHandlers != null) {
                    try {
                        mount.configureLoadHandlers(pageHandlers.loadEventHandlers)
                        logger.info("  + Configured load event handlers")
                    } catch (e: Exception) {
                        logger.warn("  ! Failed to configure load event handlers: {}", e.message)
                    }
                } else {
                    logger.debug("  - Skipping LoadEventMount: pageEventHandlers not yet available")
                }
            }

            if (mount is BrowseEventMount) {
                if (pageHandlers != null) {
                    try {
                        mount.configureBrowseHandlers(pageHandlers.browseEventHandlers)
                        logger.info("  + Configured browse event handlers")
                    } catch (e: Exception) {
                        logger.warn("  ! Failed to configure browse event handlers: {}", e.message)
                    }
                } else {
                    logger.debug("  - Skipping BrowseEventMount: pageEventHandlers not yet available")
                }
            }

            if (mount is CrawlEventMount) {
                if (pageHandlers != null) {
                    try {
                        mount.configureCrawlHandlers(pageHandlers.crawlEventHandlers)
                        logger.info("  + Configured crawl event handlers")
                    } catch (e: Exception) {
                        logger.warn("  ! Failed to configure crawl event handlers: {}", e.message)
                    }
                } else {
                    logger.debug("  - Skipping CrawlEventMount: pageEventHandlers not yet available")
                }
            }

            // --- Tool mount ---
            if (mount is ToolMount) {
                wireToolMount(mount)
            }

            // --- Swarm facade mount ---
            if (mount is SwarmFacadeMount) {
                val facade = mount.getSwarmFacade()
                if (facade != null) {
                    SwarmFacadeRegistry.instance.register(facade)
                    logger.info("  + Registered swarm facade: {}", facade.javaClass.simpleName)
                }
            }

            // --- Page sniffer mount ---
            if (mount is PageSnifferMount) {
                wirePageSnifferMount(mount)
            }
        }
    }

    private fun wireToolMount(mount: ToolMount) {
        mount.getToolExecutors().forEach { executor ->
            try {
                if (!CustomToolRegistry.instance.contains(executor.domain)) {
                    CustomToolRegistry.instance.register(executor)
                    logger.info("  + Registered tool executor for domain '{}'", executor.domain)
                } else {
                    logger.info("  - Tool executor already registered for domain '{}'", executor.domain)
                }
            } catch (e: Exception) {
                logger.warn("  ! Failed to register tool executor for domain '{}': {}",
                    executor.domain, e.message)
            }
        }
    }

    private fun wirePageSnifferMount(mount: PageSnifferMount) {
        try {
            val responseHandler = applicationContext.getBean(BrowserResponseHandler::class.java)
            mount.getPageSniffers().forEach { sniffer ->
                responseHandler.pageCategorySniffer.addLast(sniffer)
                logger.info("  + Registered page category sniffer: {}", sniffer.javaClass.simpleName)
            }
        } catch (e: Exception) {
            logger.warn("  ! Failed to register page sniffers: {}", e.message)
        }
    }

    /**
     * Resolves the plugin manifest that declared the given bean, or null when
     * the bean comes from core (no plugin JAR code source). Works for both
     * classpath modes: `plugins/` jars loaded via [PluginClasspathEnhancer]
     * (URLClassLoader) and jars on the JVM classpath (bundle wildcard).
     */
    private fun pluginManifestOf(bean: Any): PluginManifest? {
        var clazz = bean.javaClass
        // Unwrap Spring CGLIB proxies (e.g. SwarmAutoConfiguration$$SpringCGLIB$$0)
        while (clazz.name.contains("\$\$SpringCGLIB\$\$") || clazz.name.contains("\$\$EnhancerBySpringCGLIB\$\$")) {
            clazz = clazz.superclass ?: return null
        }
        val location = clazz.protectionDomain?.codeSource?.location ?: return null
        return manifestOfLocation(location)
    }
}

/**
 * Resolves a [PluginManifest] from a class code-source [location], or null
 * when the location does not point at a readable plugin JAR.
 *
 * Code sources are not always JARs: in tests they are `target/classes`
 * directories, inside a Spring Boot fat jar they are nested-jar entries
 * (BOOT-INF/...), and some class loaders expose root/empty locations. Any
 * of those must yield null (the bean is core, not a plugin) instead of
 * crashing startup — `Path.getFileName()` is null for root/empty paths.
 */
internal fun manifestOfLocation(location: java.net.URL): PluginManifest? {
    val jarPath = runCatching { Path.of(location.toURI()) }.getOrNull() ?: return null
    val fileName = jarPath.fileName?.toString() ?: return null
    if (!fileName.endsWith(".jar")) {
        return null
    }
    return runCatching {
        JarFile(jarPath.toFile()).use { PluginManifest.fromJar(it) }
    }.getOrNull()
}
