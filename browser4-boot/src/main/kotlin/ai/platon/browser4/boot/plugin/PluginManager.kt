package ai.platon.browser4.boot.plugin

import ai.platon.pulsar.agentic.tools.CustomToolRegistry
import ai.platon.pulsar.agentic.tools.ToolMount
import ai.platon.pulsar.common.getLogger
import ai.platon.pulsar.protocol.browser.emulator.BrowserResponseHandler
import ai.platon.pulsar.protocol.browser.emulator.util.PageSnifferMount
import ai.platon.pulsar.skeleton.event.PulsarEventBus
import ai.platon.pulsar.skeleton.plugin.BrowseEventMount
import ai.platon.pulsar.skeleton.plugin.Browser4Plugin
import ai.platon.pulsar.skeleton.plugin.CrawlEventMount
import ai.platon.pulsar.skeleton.plugin.LoadEventMount
import ai.platon.pulsar.skeleton.plugin.PluginMount
import jakarta.annotation.PreDestroy
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.context.ApplicationContext

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
 * - [PageSnifferMount] → [BrowserResponseHandler.pageCategorySniffer]
 */
class PluginManager(
    private val applicationContext: ApplicationContext,
) : ApplicationRunner {

    private val logger = getLogger(PluginManager::class)
    private val plugins = mutableListOf<Browser4Plugin>()

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
            plugins.add(plugin)
            logger.info("  - {} v{}", plugin.manifest.name, plugin.manifest.version)
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
                        logger.info("  ✓ Configured load event handlers")
                    } catch (e: Exception) {
                        logger.warn("  ⚠ Failed to configure load event handlers: {}", e.message)
                    }
                } else {
                    logger.debug("  - Skipping LoadEventMount: pageEventHandlers not yet available")
                }
            }

            if (mount is BrowseEventMount) {
                if (pageHandlers != null) {
                    try {
                        mount.configureBrowseHandlers(pageHandlers.browseEventHandlers)
                        logger.info("  ✓ Configured browse event handlers")
                    } catch (e: Exception) {
                        logger.warn("  ⚠ Failed to configure browse event handlers: {}", e.message)
                    }
                } else {
                    logger.debug("  - Skipping BrowseEventMount: pageEventHandlers not yet available")
                }
            }

            if (mount is CrawlEventMount) {
                if (pageHandlers != null) {
                    try {
                        mount.configureCrawlHandlers(pageHandlers.crawlEventHandlers)
                        logger.info("  ✓ Configured crawl event handlers")
                    } catch (e: Exception) {
                        logger.warn("  ⚠ Failed to configure crawl event handlers: {}", e.message)
                    }
                } else {
                    logger.debug("  - Skipping CrawlEventMount: pageEventHandlers not yet available")
                }
            }

            // --- Tool mount ---
            if (mount is ToolMount) {
                wireToolMount(mount)
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
                    logger.info("  ✓ Registered tool executor for domain '{}'", executor.domain)
                } else {
                    logger.info("  - Tool executor already registered for domain '{}'", executor.domain)
                }
            } catch (e: Exception) {
                logger.warn("  ⚠ Failed to register tool executor for domain '{}': {}",
                    executor.domain, e.message)
            }
        }
    }

    private fun wirePageSnifferMount(mount: PageSnifferMount) {
        try {
            val responseHandler = applicationContext.getBean(BrowserResponseHandler::class.java)
            mount.getPageSniffers().forEach { sniffer ->
                responseHandler.pageCategorySniffer.addLast(sniffer)
                logger.info("  ✓ Registered page category sniffer: {}", sniffer.javaClass.simpleName)
            }
        } catch (e: Exception) {
            logger.warn("  ⚠ Failed to register page sniffers: {}", e.message)
        }
    }
}
