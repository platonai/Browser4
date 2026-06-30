package ai.platon.pulsar.examples.demos

import ai.platon.pulsar.common.getLogger
import ai.platon.pulsar.core.api.WebPage
import ai.platon.pulsar.dom.FeaturedDocument
import ai.platon.pulsar.skeleton.context.PulsarContexts
import ai.platon.pulsar.skeleton.event.LoadEventHandlers
import ai.platon.pulsar.skeleton.event.impl.DefaultPageEventHandlers
import ai.platon.pulsar.skeleton.plugin.LoadEventMount
import ai.platon.pulsar.test.TestUrls

/**
 * Demo plugins for [LoadEventHandlers] events.
 *
 * Load events manage URL normalization, fetching, and parsing operations.
 * They are triggered in sequence during the page loading process:
 * ```
 * onNormalize → onWillLoad → onWillFetch → [Browse Phase] → onFetched →
 * onWillParse → onWillParseHTMLDocument → onHTMLDocumentParsed → onParsed → onLoaded
 * ```
 *
 * ## Usage
 *
 * ### As a standalone demo (run directly)
 * ```kotlin
 * suspend fun main() = LoadEventDemos.run()
 * ```
 *
 * ### As an installable plugin (via PluginController REST API)
 *
 * This class implements [LoadEventMount], which is a [PluginMount] sub-interface.
 * When packaged as a plugin JAR and installed via `POST /api/plugins/install`,
 * the PluginManager automatically calls [configureLoadHandlers] to wire these
 * handlers into the global event bus.
 *
 * **Plugin install steps:**
 * 1. Build the plugin JAR containing this class
 * 2. Ensure `META-INF/browser4-plugin.json` lists the auto-configuration class
 * 3. Upload via `POST /api/plugins/install` with `file=@plugin.jar`
 * 4. Restart the application
 *
 * @see CrawlEventDemos for crawl-phase event demos
 * @see BrowseEventDemos for browse-phase event demos
 */
class LoadEventDemos : LoadEventMount {
    private val logger = getLogger(this)

    private val session = PulsarContexts.createSession()
    private val eventHandlers = DefaultPageEventHandlers()
    private val url = TestUrls.PRODUCT_DETAIL_URL

    // ──────────────────────────────────────────────────────────────────────
    // LoadEventMount implementation (called by PluginManager)
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Called by [PluginManager] to wire handlers into the global event bus.
     *
     * When this class is discovered as a [LoadEventMount] bean, the PluginManager
     * passes the active [LoadEventHandlers] chain so this plugin can register
     * its handlers on any load-phase hook (9 hooks available).
     */
    override fun configureLoadHandlers(handlers: LoadEventHandlers) {
        // 1. onNormalize — URL normalization
        handlers.onNormalize.addLast { url: String ->
            logger.info("[Load.onNormalize] Original URL: $url")

            val normalized = url.replace(Regex("[?&]utm_[^&]+"), "")
                .replace(Regex("#.*"), "")
                .trimEnd('?', '&')

            if (normalized != url) {
                logger.info("[Load.onNormalize] Normalized to: $normalized")
            }
            normalized
        }

        // 2. onWillLoad — Pre-load validation
        handlers.onWillLoad.addLast { url: String ->
            logger.info("[Load.onWillLoad] Loading: $url")
            if (url.isBlank()) { logger.warn("[Load.onWillLoad] Blank URL — rejecting"); null } else url
        }

        // 3. onWillFetch — Before fetch
        handlers.onWillFetch.addLast { page: WebPage ->
            logger.info("[Load.onWillFetch] About to fetch: ${page.url} (attempt ${page.fetchCount})")
        }

        // 4. onFetched — After fetch
        handlers.onFetched.addLast { page: WebPage ->
            logger.info("[Load.onFetched] Fetched: ${page.url} | Status: ${page.protocolStatus} | ${page.contentLength} bytes")
        }

        // 5. onWillParse — Before parsing
        handlers.onWillParse.addLast { page: WebPage ->
            logger.info("[Load.onWillParse] Beginning to parse: ${page.url}")
        }

        // 6. onWillParseHTMLDocument — Before HTML parsing
        handlers.onWillParseHTMLDocument.addLast { page: WebPage ->
            logger.info("[Load.onWillParseHTMLDocument] Parsing HTML: ${page.url}")
        }

        // 7. onHTMLDocumentParsed — Data extraction (primary extraction event)
        handlers.onHTMLDocumentParsed.addLast { page: WebPage, document: FeaturedDocument ->
            logger.info("[Load.onHTMLDocumentParsed] Document parsed: ${page.url}")

            val title = document.selectFirst("h1")?.text() ?: document.title
            val metaDescription = document.selectFirst("meta[name=description]")?.attr("content")
            val bodyText = document.selectFirst("body")?.text()?.take(200)
            val links = document.select("a[href]")

            logger.info("[Load.onHTMLDocumentParsed]   Title: ${title?.take(100)}")
            metaDescription?.let { logger.info("[Load.onHTMLDocumentParsed]   Meta: ${it.take(100)}") }
            bodyText?.let { logger.info("[Load.onHTMLDocumentParsed]   Body preview: $it") }
            logger.info("[Load.onHTMLDocumentParsed]   Links found: ${links.size}")
        }

        // 8. onParsed — Parsing complete
        handlers.onParsed.addLast { page: WebPage ->
            logger.info("[Load.onParsed] Parsing complete: ${page.url}")
        }

        // 9. onLoaded — Fully loaded
        handlers.onLoaded.addLast { page: WebPage ->
            logger.info("[Load.onLoaded] Fully loaded: ${page.url} | Final status: ${page.protocolStatus}")
        }

        logger.info("LoadEventDemos: registered 9 load event handlers via LoadEventMount")
    }

    // ──────────────────────────────────────────────────────────────────────
    // Standalone demo runner (for running directly, not via PluginManager)
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Register all load event demos and run the session (standalone mode).
     */
    suspend fun run() {
        configureLoadHandlers(eventHandlers.loadEventHandlers)

        logger.info("=== LoadEventDemos: All 9 handlers registered ===")
        logger.info("Opening URL: $url")

        session.open(url, eventHandlers)

        logger.info("=== LoadEventDemos: Complete ===")
    }

    companion object {
        @JvmStatic
        suspend fun main(vararg args: String) = LoadEventDemos().run()
    }
}

suspend fun main() = LoadEventDemos.run()
