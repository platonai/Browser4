package ai.platon.pulsar.examples.demos

import ai.platon.pulsar.common.getLogger
import ai.platon.pulsar.common.urls.UrlAware
import ai.platon.pulsar.core.api.WebPage
import ai.platon.pulsar.skeleton.context.PulsarContexts
import ai.platon.pulsar.skeleton.event.CrawlEventHandlers
import ai.platon.pulsar.skeleton.event.impl.DefaultPageEventHandlers
import ai.platon.pulsar.skeleton.plugin.CrawlEventMount
import ai.platon.pulsar.test.TestUrls

/**
 * Demo plugins for [CrawlEventHandlers] events.
 *
 * Crawl events wrap around the load and browse phases of the page lifecycle:
 * ```
 * crawl.onWillLoad → [Load Phase] → [Browse Phase] → crawl.onLoaded
 * ```
 *
 * ## Usage
 *
 * ### As a standalone demo (run directly)
 * ```kotlin
 * suspend fun main() = CrawlEventDemos.run()
 * ```
 *
 * ### As an installable plugin (via PluginController REST API)
 *
 * This class implements [CrawlEventMount], which is a [PluginMount] sub-interface.
 * When packaged as a plugin JAR and installed via `POST /api/plugins/install`,
 * the PluginManager automatically calls [configureCrawlHandlers] to wire these
 * handlers into the global event bus.
 *
 * **Plugin install steps:**
 * 1. Build the plugin JAR containing this class
 * 2. Ensure `META-INF/browser4-plugin.json` lists the auto-configuration class
 * 3. Upload via `POST /api/plugins/install` with `file=@plugin.jar`
 * 4. Restart the application
 *
 * @see LoadEventDemos for load-phase event demos
 * @see BrowseEventDemos for browse-phase event demos
 */
class CrawlEventDemos : CrawlEventMount {
    private val logger = getLogger(this)

    private val session = PulsarContexts.createSession()
    private val eventHandlers = DefaultPageEventHandlers()
    private val url = TestUrls.PRODUCT_DETAIL_URL

    // ──────────────────────────────────────────────────────────────────────
    // CrawlEventMount implementation (called by PluginManager)
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Called by [PluginManager] to wire handlers into the global event bus.
     *
     * When this class is discovered as a [CrawlEventMount] bean, the PluginManager
     * passes the active [CrawlEventHandlers] chain so this plugin can register
     * its handlers on any crawl-phase hook.
     */
    override fun configureCrawlHandlers(handlers: CrawlEventHandlers) {
        handlers.onWillLoad.addLast { url: UrlAware ->
            logger.info("[Crawl.onWillLoad] About to load: ${url.url}")

            // Example: inspect URL before loading
            if (url.url.contains("skip-this")) {
                logger.info("[Crawl.onWillLoad] Detected skip-this URL: ${url.url}")
            }
            url
        }

        handlers.onLoaded.addLast { url, page ->
            if (page != null) {
                logger.info("[Crawl.onLoaded] Successfully loaded: ${url.url}")
                logger.info("[Crawl.onLoaded]   Status: ${page.protocolStatus}")
                logger.info("[Crawl.onLoaded]   Content length: ${page.contentLength}")
            } else {
                logger.warn("[Crawl.onLoaded] Failed to load: ${url.url}")
            }
        }

        logger.info("CrawlEventDemos: registered 2 crawl event handlers via CrawlEventMount")
    }

    // ──────────────────────────────────────────────────────────────────────
    // Standalone demo methods (for running directly, not via PluginManager)
    // ──────────────────────────────────────────────────────────────────────

    /**
     * ## onWillLoad — Fires when the URL is about to be loaded in the Main loop.
     *
     * Signature: `(UrlAware) -> UrlAware?`
     */
    fun demoOnWillLoad(): CrawlEventDemos {
        configureCrawlHandlers(eventHandlers.crawlEventHandlers)
        return this
    }

    /**
     * ## onLoaded — Fires when the URL has been loaded in the Main loop.
     *
     * Signature: `(UrlAware, WebPage?) -> Any?`
     */
    fun demoOnLoaded(): CrawlEventDemos {
        // Already registered via configureCrawlHandlers above
        return this
    }

    /**
     * Register all crawl event demos and run the session (standalone mode).
     */
    suspend fun run() {
        demoOnWillLoad()

        logger.info("=== CrawlEventDemos: All handlers registered ===")
        logger.info("Opening URL: $url")

        session.open(url, eventHandlers)

        logger.info("=== CrawlEventDemos: Complete ===")
    }

    companion object {
        @JvmStatic
        suspend fun main(vararg args: String) = CrawlEventDemos().run()
    }
}
