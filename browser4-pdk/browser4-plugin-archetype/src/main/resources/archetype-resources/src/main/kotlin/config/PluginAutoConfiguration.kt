package ${package}.config

import ${package}.integration.MyBrowseEventHandler
import ${package}.integration.MyLoadEventHandler
import ${package}.MyPlugin
import ai.platon.pulsar.skeleton.plugin.BrowseEventMount
import ai.platon.pulsar.skeleton.plugin.LoadEventMount
import ai.platon.pulsar.skeleton.event.BrowseEventHandlers
import ai.platon.pulsar.skeleton.event.LoadEventHandlers
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Lazy

/**
 * Spring Boot auto-configuration for the plugin.
 *
 * This class is discovered via:
 *   META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
 *
 * It registers [PluginMount] beans that the PluginManager discovers and wires
 * into the global event bus.
 *
 * ## Mount points implemented in this example
 *
 * - [BrowseEventMount]: register handlers on any of 17 browse-phase event hooks
 * - [LoadEventMount]: register handlers on any of 9 load-phase event hooks
 *
 * ## Adding more mount points
 *
 * Simply add the interface to the class declaration and implement its method:
 *   class PluginAutoConfiguration : BrowseEventMount, LoadEventMount, CrawlEventMount {
 *       override fun configureCrawlHandlers(handlers: CrawlEventHandlers) { ... }
 *   }
 *
 * ## Important: use @Lazy
 *
 * Plugin beans should be lazy-initialized because they depend on services
 * that may not be available until the application context is fully ready.
 */
@AutoConfiguration
@Lazy
open class PluginAutoConfiguration : BrowseEventMount, LoadEventMount {

    // ========================================================================
    // BrowseEventMount: 17 event hooks for browser automation
    // ========================================================================

    /**
     * Called by PluginManager to wire handlers into the browse-phase event chain.
     *
     * The most commonly used hook is [onDocumentSteady] — it fires when the
     * page is fully loaded and stable, making it ideal for custom RPA actions.
     */
    override fun configureBrowseHandlers(handlers: BrowseEventHandlers) {
        // Fires when the page is steady — best for custom RPA actions
        handlers.onDocumentSteady.addLast { page, driver ->
            println("[${pluginName}] Page steady: ${"$"}{page.url}")
            // Add your custom interaction logic here:
            // - Click buttons, fill forms, extract data
            // - Take screenshots, inject scripts
        }

        // Fires before navigation — block unwanted resources
        handlers.onWillNavigate.addLast { page, driver ->
            driver.addBlockedURLs(listOf(
                "*.png", "*.jpg", "*.jpeg", "*.gif", "*.svg",
                "*.woff", "*.woff2", "*.ttf",
                "*google-analytics.com*"
            ))
        }
    }

    // ========================================================================
    // LoadEventMount: 9 event hooks for page loading/parsing
    // ========================================================================

    /**
     * Called by PluginManager to wire handlers into the load-phase event chain.
     */
    override fun configureLoadHandlers(handlers: LoadEventHandlers) {
        // Normalize URLs before loading (strip tracking params, etc.)
        handlers.onNormalize.addLast { url ->
            url.replace(Regex("\\?utm_.*"), "")
        }

        // Extract data after HTML document is parsed
        handlers.onHTMLDocumentParsed.addLast { page, doc ->
            println("[${pluginName}] HTML parsed: ${"$"}{page.url}")
        }
    }

    // ========================================================================
    // Bean definitions
    // ========================================================================

    @Bean
    open fun myPlugin(): MyPlugin = MyPlugin()

    @Bean
    open fun myBrowseEventHandler(): MyBrowseEventHandler = MyBrowseEventHandler()

    @Bean
    open fun myLoadEventHandler(): MyLoadEventHandler = MyLoadEventHandler()
}
