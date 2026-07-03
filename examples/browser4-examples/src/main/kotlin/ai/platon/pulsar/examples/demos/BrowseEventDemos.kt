package ai.platon.pulsar.examples.demos

import ai.platon.pulsar.common.getLogger
import ai.platon.pulsar.core.api.WebDriver
import ai.platon.pulsar.core.api.WebPage
import ai.platon.pulsar.skeleton.context.PulsarContexts
import ai.platon.pulsar.skeleton.event.BrowseEventHandlers
import ai.platon.pulsar.skeleton.event.impl.DefaultPageEventHandlers
import ai.platon.pulsar.skeleton.plugin.BrowseEventMount
import ai.platon.pulsar.test.TestUrls

/**
 * Demo plugins for [BrowseEventHandlers] events.
 *
 * Browse events control browser automation operations: navigation,
 * scrolling, waiting, and custom RPA actions. All handlers receive
 * both a [WebPage] and [WebDriver] for full browser control.
 *
 * ## Event Execution Order
 * ```
 * onWillLaunchBrowser → onBrowserLaunched → onWillFetch → onWillNavigate → onNavigated →
 * onWillInteract → onWillCheckDocumentState → onDocumentFullyLoaded → onWillScroll →
 * onDidScroll → onDocumentSteady → onWillComputeFeature → onFeatureComputed →
 * onDidInteract → onWillStopTab → onTabStopped → onFetched
 * ```
 *
 * ## Usage
 *
 * ### As a standalone demo (run directly)
 * ```kotlin
 * suspend fun main() = BrowseEventDemos.run()
 * ```
 *
 * ### As an installable plugin (via PluginController REST API)
 *
 * This class implements [BrowseEventMount], which is a [PluginMount] sub-interface.
 * When packaged as a plugin JAR and installed via `POST /api/plugins/install`,
 * the PluginManager automatically calls [configureBrowseHandlers] to wire these
 * handlers into the global event bus.
 *
 * **Plugin install steps:**
 * 1. Build the plugin JAR containing this class
 * 2. Ensure `META-INF/browser4-plugin.json` lists the auto-configuration class
 * 3. Upload via `POST /api/plugins/install` with `file=@plugin.jar`
 * 4. Restart the application
 *
 * @see CrawlEventDemos for crawl-phase event demos
 * @see LoadEventDemos for load-phase event demos
 */
class BrowseEventDemos : BrowseEventMount {
    private val logger = getLogger(this)

    private val session = PulsarContexts.createSession()
    private val eventHandlers = DefaultPageEventHandlers()
    private val url = TestUrls.PRODUCT_DETAIL_URL

    // ──────────────────────────────────────────────────────────────────────
    // BrowseEventMount implementation (called by PluginManager)
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Called by [PluginManager] to wire handlers into the global event bus.
     *
     * When this class is discovered as a [BrowseEventMount] bean, the PluginManager
     * passes the active [BrowseEventHandlers] chain so this plugin can register
     * its handlers on any browse-phase hook (17 hooks available).
     */
    override fun configureBrowseHandlers(handlers: BrowseEventHandlers) {
        // 1. onWillLaunchBrowser — Before browser launch (no WebDriver yet)
        handlers.onWillLaunchBrowser.addLast { page: WebPage ->
            logger.info("[Browse.onWillLaunchBrowser] Preparing browser for: ${page.url}")
        }

        // 2. onBrowserLaunched — Browser launched (first WebDriver access)
        handlers.onBrowserLaunched.addLast { page: WebPage, driver: WebDriver ->
            logger.info("[Browse.onBrowserLaunched] Browser launched for: ${page.url}")
            driver.addInitScript(
                """
                window.__browser4Demo = {
                    startTime: Date.now(),
                    config: { debug: true }
                };
                console.log('[Browser4 Demo] Initialized');
                """.trimIndent()
            )
            logger.info("[Browse.onBrowserLaunched] Init script injected")
        }

        // 3. onWillFetch — Browse-phase fetch (before navigation)
        handlers.onWillFetch.addLast { page: WebPage, _: WebDriver ->
            logger.info("[Browse.onWillFetch] Preparing fetch: ${page.url}")
        }

        // 4. onWillNavigate — Before navigation (block resources here)
        handlers.onWillNavigate.addLast { page: WebPage, driver: WebDriver ->
            logger.info("[Browse.onWillNavigate] Navigating to: ${page.url}")
            val blockedPatterns = listOf(
                "*.png", "*.jpg", "*.jpeg", "*.gif", "*.svg",
                "*.woff", "*.woff2", "*.ttf",
                "*google-analytics.com*", "*googletagmanager.com*"
            )
            driver.addBlockedURLs(blockedPatterns)
            logger.info("[Browse.onWillNavigate] Blocked ${blockedPatterns.size} resource patterns")
        }

        // 5. onNavigated — Navigation complete
        handlers.onNavigated.addLast { page: WebPage, driver: WebDriver ->
            logger.info("[Browse.onNavigated] Navigation complete: ${page.url}")
            try {
                driver.waitForSelector(".main-content", 10_000L)
                logger.info("[Browse.onNavigated] Main content detected")
            } catch (e: Exception) {
                logger.warn("[Browse.onNavigated] Main content not found (continuing anyway)")
            }
        }

        // 6. onWillInteract — Interaction phase starting
        handlers.onWillInteract.addLast { page: WebPage, driver: WebDriver ->
            logger.info("[Browse.onWillInteract] Beginning interaction: ${page.url}")
            logger.info("[Browse.onWillInteract]   Current URL: ${driver.currentUrl()}")
        }

        // 7. onWillCheckDocumentState — Document state check
        handlers.onWillCheckDocumentState.addLast { page: WebPage, driver: WebDriver ->
            logger.info("[Browse.onWillCheckDocumentState] Checking state: ${page.url}")
            val readyState = driver.evaluate("document.readyState")
            logger.info("[Browse.onWillCheckDocumentState]   document.readyState = $readyState")
        }

        // 8. onDocumentFullyLoaded — Document fully loaded
        handlers.onDocumentFullyLoaded.addLast { page: WebPage, driver: WebDriver ->
            logger.info("[Browse.onDocumentFullyLoaded] Document ready: ${page.url}")
            val pageHeight = driver.evaluate("document.body.scrollHeight")
            val viewport = driver.evaluate("window.innerHeight")
            logger.info("[Browse.onDocumentFullyLoaded]   Page: $pageHeight px, Viewport: $viewport px")
        }

        // 9. onWillScroll — Before scrolling
        handlers.onWillScroll.addLast { page: WebPage, driver: WebDriver ->
            logger.info("[Browse.onWillScroll] About to scroll: ${page.url}")
            val scrollY = driver.evaluate("window.scrollY")
            logger.info("[Browse.onWillScroll]   Scroll position: $scrollY")
        }

        // 10. onDidScroll — Scrolling complete
        handlers.onDidScroll.addLast { page: WebPage, driver: WebDriver ->
            logger.info("[Browse.onDidScroll] Scrolling complete: ${page.url}")
            val scrollY = driver.evaluate("window.scrollY")
            val maxScroll = driver.evaluate("document.body.scrollHeight - window.innerHeight")
            logger.info("[Browse.onDidScroll]   Scrolled: $scrollY / $maxScroll")
        }

        // 11. onDocumentSteady — ★ Best for custom RPA actions
        handlers.onDocumentSteady.addLast { page: WebPage, driver: WebDriver ->
            logger.info("[Browse.onDocumentSteady] Page steady — RPA ready: ${page.url}")

            // Example: click "Show More" if present
            val showMoreExists = driver.evaluate(
                """!!document.querySelector('button[data-action="show-more"], .load-more, .show-more')"""
            )
            if (showMoreExists == true) {
                logger.info("[Browse.onDocumentSteady]   Clicking 'Show More'")
                driver.click("button.show-more, .load-more")
                driver.waitForSelector(".additional-content", 5_000L)
            }

            // Example: dismiss cookie banners
            val cookieExists = driver.evaluate(
                """!!document.querySelector('[aria-label="cookie-consent"], .cookie-banner, #cookie-notice')"""
            )
            if (cookieExists == true) {
                logger.info("[Browse.onDocumentSteady]   Dismissing cookie banner")
                driver.click("[aria-label='cookie-consent'] button, .cookie-banner .accept, #cookie-notice .agree")
            }

            logger.info("[Browse.onDocumentSteady]   RPA actions complete")
        }

        // 12. onWillComputeFeature — Before feature computation
        handlers.onWillComputeFeature.addLast { page: WebPage, _: WebDriver ->
            logger.info("[Browse.onWillComputeFeature] Computing features: ${page.url}")
        }

        // 13. onFeatureComputed — Features computed
        handlers.onFeatureComputed.addLast { page: WebPage, driver: WebDriver ->
            logger.info("[Browse.onFeatureComputed] Features computed: ${page.url}")
            val imgCount = driver.evaluate("document.images.length")
            val linkCount = driver.evaluate("document.links.length")
            logger.info("[Browse.onFeatureComputed]   Images: $imgCount, Links: $linkCount")
        }

        // 14. onDidInteract — All interactions complete
        handlers.onDidInteract.addLast { page: WebPage, _: WebDriver ->
            logger.info("[Browse.onDidInteract] Interactions complete: ${page.url}")
        }

        // 15. onWillStopTab — Last chance before tab closes
        handlers.onWillStopTab.addLast { page: WebPage, driver: WebDriver ->
            logger.info("[Browse.onWillStopTab] Tab about to close: ${page.url}")
            try {
                val cookies = driver.evaluate("document.cookie")
                logger.info("[Browse.onWillStopTab]   Cookies: $cookies")
            } catch (e: Exception) {
                logger.warn("[Browse.onWillStopTab]   Cookie capture failed: ${e.message}")
            }
        }

        // 16. onTabStopped — Tab stopped (final browse event)
        handlers.onTabStopped.addLast { page: WebPage, _: WebDriver ->
            logger.info("[Browse.onTabStopped] Tab stopped: ${page.url}")
        }

        // 17. onFetched — Browse-phase fetch complete
        handlers.onFetched.addLast { page: WebPage, driver: WebDriver ->
            logger.info("[Browse.onFetched] Browse fetch complete: ${page.url}")
            logger.info("[Browse.onFetched]   Final URL: ${driver.currentUrl()}")
        }

        logger.info("BrowseEventDemos: registered 17 browse event handlers via BrowseEventMount")
    }

    // ──────────────────────────────────────────────────────────────────────
    // Standalone demo runner (for running directly, not via PluginManager)
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Register all browse event demos and run the session (standalone mode).
     */
    suspend fun run() {
        configureBrowseHandlers(eventHandlers.browseEventHandlers)

        logger.info("=== BrowseEventDemos: All 17 handlers registered ===")
        logger.info("Opening URL: $url")

        session.open(url, eventHandlers)

        logger.info("=== BrowseEventDemos: Complete ===")
    }

    companion object {
        @JvmStatic
        suspend fun main(vararg args: String) = BrowseEventDemos().run()
    }
}
