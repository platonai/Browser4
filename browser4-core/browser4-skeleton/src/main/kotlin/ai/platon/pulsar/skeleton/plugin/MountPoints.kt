package ai.platon.pulsar.skeleton.plugin

import ai.platon.pulsar.skeleton.event.BrowseEventHandlers
import ai.platon.pulsar.skeleton.event.CrawlEventHandlers
import ai.platon.pulsar.skeleton.event.LoadEventHandlers

/**
 * Marker interface for plugin mount points.
 *
 * A [PluginMount] bean is automatically discovered by `PluginManager` and wired into the
 * appropriate integration point. Plugins implement specific sub-interfaces to declare what
 * they need to hook into.
 *
 * ## Available event-phase mount points
 *
 * - [LoadEventMount] — configure load-phase event handlers (normalize, fetch, parse)
 * - [BrowseEventMount] — configure browse-phase event handlers (navigate, scroll, RPA)
 * - [CrawlEventMount] — configure crawl-phase event handlers (URL filter, result handling)
 *
 * ## Other mount points
 *
 * - `ToolMount` (in browser4-agentic) — register custom tool executors
 * - `PageSnifferMount` (in browser4-protocol) — register page category sniffers
 */
interface PluginMount

/**
 * Mount point for **load-phase** event handlers.
 *
 * The `PluginManager` calls [configureLoadHandlers] with the active [LoadEventHandlers]
 * chain, allowing the plugin to register handlers on any load-phase hook:
 *
 * - `onNormalize` — URL normalization
 * - `onWillLoad` — before URL loading
 * - `onWillFetch` — before fetching
 * - `onFetched` — after fetch completes
 * - `onWillParse` — before parsing
 * - `onWillParseHTMLDocument` — before HTML document parsing
 * - `onHTMLDocumentParsed` — after HTML document parsing (data extraction)
 * - `onParsed` — parsing complete
 * - `onLoaded` — page fully loaded
 *
 * ## Example
 *
 * ```kotlin
 * class MyPlugin : LoadEventMount {
 *     override fun configureLoadHandlers(handlers: LoadEventHandlers) {
 *         handlers.onNormalize.addLast { url ->
 *             url.replace(Regex("\\?utm_.*"), "")
 *         }
 *         handlers.onHTMLDocumentParsed.addLast { page, doc ->
 *             extractData(page, doc)
 *         }
 *     }
 * }
 * ```
 */
interface LoadEventMount : PluginMount {
    /**
     * Called by `PluginManager` to let this plugin register handlers on any
     * load-phase event hook.
     *
     * @param handlers  the active load event handlers chain (never null at call time)
     */
    fun configureLoadHandlers(handlers: LoadEventHandlers)
}

/**
 * Mount point for **browse-phase** event handlers.
 *
 * The `PluginManager` calls [configureBrowseHandlers] with the active [BrowseEventHandlers]
 * chain, allowing the plugin to register handlers on any browse-phase hook:
 *
 * - `onWillLaunchBrowser` — before browser launch
 * - `onBrowserLaunched` — browser launched (first WebDriver access)
 * - `onWillFetch` — browse-phase fetch
 * - `onWillNavigate` — before navigation
 * - `onNavigated` — navigation complete
 * - `onWillInteract` — interaction starting
 * - `onWillCheckDocumentState` — document state check
 * - `onDocumentFullyLoaded` — document fully loaded
 * - `onWillScroll` — before scrolling
 * - `onDidScroll` — scrolling complete
 * - `onDocumentSteady` — **best for custom RPA actions** (page stable)
 * - `onWillComputeFeature` — before feature computation
 * - `onFeatureComputed` — features computed
 * - `onDidInteract` — interaction complete
 * - `onWillStopTab` — before tab close (last chance for screenshots, etc.)
 * - `onTabStopped` — tab stopped
 * - `onFetched` — browse-phase fetch complete
 *
 * ## Example
 *
 * ```kotlin
 * class CaptchaPlugin : BrowseEventMount {
 *     override fun configureBrowseHandlers(handlers: BrowseEventHandlers) {
 *         handlers.onDocumentSteady.addLast { page, driver ->
 *             detectAndSolveCaptcha(page, driver)
 *         }
 *     }
 * }
 * ```
 */
interface BrowseEventMount : PluginMount {
    /**
     * Called by `PluginManager` to let this plugin register handlers on any
     * browse-phase event hook.
     *
     * @param handlers  the active browse event handlers chain (never null at call time)
     */
    fun configureBrowseHandlers(handlers: BrowseEventHandlers)
}

/**
 * Mount point for **crawl-phase** event handlers.
 *
 * The `PluginManager` calls [configureCrawlHandlers] with the active [CrawlEventHandlers]
 * chain, allowing the plugin to register handlers on any crawl-phase hook:
 *
 * - `onWillLoad` — before URL enters the load/browse pipeline (can reject URLs)
 * - `onLoaded` — after pipeline completes (results available)
 *
 * ## Example
 *
 * ```kotlin
 * class UrlFilterPlugin : CrawlEventMount {
 *     override fun configureCrawlHandlers(handlers: CrawlEventHandlers) {
 *         handlers.onWillLoad.addLast { url ->
 *             if (isBlacklisted(url.url)) null else url  // reject blacklisted
 *         }
 *     }
 * }
 * ```
 */
interface CrawlEventMount : PluginMount {
    /**
     * Called by `PluginManager` to let this plugin register handlers on any
     * crawl-phase event hook.
     *
     * @param handlers  the active crawl event handlers chain (never null at call time)
     */
    fun configureCrawlHandlers(handlers: CrawlEventHandlers)
}
