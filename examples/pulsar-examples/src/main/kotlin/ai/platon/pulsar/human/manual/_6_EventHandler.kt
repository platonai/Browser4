package ai.platon.pulsar.human.manual

import ai.platon.pulsar.common.urls.UrlAware
import ai.platon.pulsar.dom.FeaturedDocument
import ai.platon.pulsar.persist.WebPage
import ai.platon.pulsar.skeleton.PulsarSettings
import ai.platon.pulsar.skeleton.context.PulsarContexts
import ai.platon.pulsar.skeleton.event.impl.DefaultPageEventHandlers
import ai.platon.pulsar.skeleton.workflow.common.url.ListenableHyperlink
import ai.platon.pulsar.browser.driver.WebDriver
import java.util.concurrent.atomic.AtomicInteger

/**
 * # Event Handler System - Complete Lifecycle Control
 *
 * This example demonstrates Browser4's comprehensive event handler system,
 * which provides hooks into every stage of the page loading and crawling lifecycle.
 *
 * ## Event Handler Categories:
 * 1. **LoadEventHandlers** - Events during the load phase
 * 2. **BrowseEventHandlers** - Events during browser interaction
 * 3. **CrawlEventHandlers** - Events during the crawl loop
 *
 * ## Event Flow (in order of execution):
 * ```
 * LOAD PHASE:
 * onNormalize → onWillLoad → onWillFetch → onFetched →
 * onWillParse → onWillParseHTMLDocument → onHTMLDocumentParsed → onParsed → onLoaded
 *
 * BROWSE PHASE (during fetch):
 * onWillLaunchBrowser → onBrowserLaunched → onWillNavigate → onNavigated →
 * onWillInteract → onWillCheckDocumentState → onDocumentFullyLoaded →
 * onWillScroll → onDidScroll → onDocumentSteady →
 * onWillComputeFeature → onFeatureComputed → onDidInteract →
 * onWillStopTab → onTabStopped
 *
 * CRAWL PHASE:
 * onWillLoad → onLoaded
 * ```
 *
 * ## AI Integration Notes:
 * - Use `onHTMLDocumentParsed` for AI analysis of page content
 * - Use `onDocumentFullyLoaded` for AI-driven page interaction
 * - Use `onFeatureComputed` for post-processing with AI extraction
 * - Each handler receives context (WebPage, WebDriver, FeaturedDocument)
 *
 * @see DefaultPageEventHandlers Base class for custom event handlers
 * @see ListenableHyperlink URL type that supports event handlers
 */

/**
 * Custom event handlers that print the call sequence for demonstration.
 *
 * AI Note: This class shows all available event hooks. In production,
 * you only need to override the events you care about.
 */
class PrintFlowEventHandlers : DefaultPageEventHandlers() {
    // Atomic counter for tracking event sequence
    private val sequencer = AtomicInteger()
    private val seq get() = sequencer.incrementAndGet()

    init {
        // =====================================================================
        // LOAD EVENT HANDLERS
        // =====================================================================
        // These events track the page loading lifecycle
        loadEventHandlers.apply {
            // Called when URL normalization occurs
            // AI Note: Use to transform/clean URLs before loading
            onNormalize.addLast { url ->
                println("$seq. load - onNormalize")
                url
            }

            // Called before attempting to load
            // AI Note: Return a WebPage to skip loading, or null to proceed
            onWillLoad.addLast { url ->
                println("$seq. load - onWillLoad")
                null
            }

            // Called before fetching from network
            onWillFetch.addLast { page ->
                println("$seq. load - onWillFetch")
            }

            // Called after fetching completes
            onFetched.addLast { page ->
                println("$seq. load - onFetched")
            }

            // Called before parsing begins
            onWillParse.addLast { page ->
                println("$seq. load - onWillParse")
            }

            // Called before HTML document parsing
            onWillParseHTMLDocument.addLast { page ->
                println("$seq. load - onWillParseHTMLDocument")
            }

            // Called after HTML document is parsed
            // AI Note: Ideal for AI content analysis - you have both page and document
            onHTMLDocumentParsed.addLast { page: WebPage, document: FeaturedDocument ->
                println("$seq. load - onHTMLDocumentParsed")
            }

            // Called after all parsing completes
            onParsed.addLast { page ->
                println("$seq. load - onParsed")
            }

            // Called when load operation completes
            onLoaded.addLast { page ->
                println("$seq. load - onLoaded")
            }
        }

        // =====================================================================
        // BROWSE EVENT HANDLERS
        // =====================================================================
        // These events track browser interaction during the fetch phase
        browseEventHandlers.apply {
            // Called before browser launch
            onWillLaunchBrowser.addLast { page ->
                println("$seq. browse - onWillLaunchBrowser")
            }

            // Called after browser is launched
            // AI Note: Good place for browser configuration
            onBrowserLaunched.addLast { page, driver ->
                println("$seq. browse - onBrowserLaunched")
            }

            // Called before navigating to URL
            // AI Note: Can modify navigation behavior, add headers, etc.
            onWillNavigate.addLast { page, driver ->
                println("$seq. browse - onWillNavigate")
            }

            // Called after navigation completes
            onNavigated.addLast { page, driver ->
                println("$seq. browse - onNavigated")
            }

            // Called before interaction phase begins
            onWillInteract.addLast { page, driver ->
                println("$seq. browse - onWillInteract")
            }

            // Called when checking if document is ready
            onWillCheckDocumentState.addLast { page: WebPage, driver: WebDriver ->
                println("$seq. browse - onWillCheckDocumentState")
            }

            // Called when document is fully loaded (DOMContentLoaded + load)
            // AI Note: Ideal for custom JavaScript execution or element waiting
            onDocumentFullyLoaded.addLast { page: WebPage, driver: WebDriver ->
                println("$seq. browse - onDocumentFullyLoaded")
            }

            // Called before scrolling begins
            onWillScroll.addLast { page: WebPage, driver: WebDriver ->
                println("$seq. browse - onWillScroll")
            }

            // Called after scrolling completes
            onDidScroll.addLast { page: WebPage, driver: WebDriver ->
                println("$seq. browse - onDidScroll")
            }

            // Called when document reaches steady state (no more network activity)
            // AI Note: Good for pages with lazy loading
            onDocumentSteady.addLast { page: WebPage, driver: WebDriver ->
                println("$seq. browse - onDocumentSteady")
            }

            // Called before computing page features
            onWillComputeFeature.addLast { page: WebPage, driver: WebDriver ->
                println("$seq. browse - onWillComputeFeature")
            }

            // Called after features are computed
            // AI Note: Good for final JavaScript evaluation or screenshot capture
            onFeatureComputed.addLast { page: WebPage, driver: WebDriver ->
                println("$seq. browse - onFeatureComputed")
            }

            // Called after all interactions complete
            onDidInteract.addLast { page, driver ->
                println("$seq. browse - onDidInteract")
            }

            // Called before stopping the browser tab
            onWillStopTab.addLast { page: WebPage, driver: WebDriver ->
                println("$seq. browse - onWillStopTab")
            }

            // Called after tab is stopped
            onTabStopped.addLast { page: WebPage, driver: WebDriver ->
                println("$seq. browse - onTabStopped")
            }
        }

        // =====================================================================
        // CRAWL EVENT HANDLERS
        // =====================================================================
        // These events track the crawl loop (when using submit())
        crawlEventHandlers.apply {
            // Called before loading in crawl loop
            // AI Note: Return modified URL or null to skip
            onWillLoad.addLast { url: UrlAware ->
                println("$seq. crawl - onWillLoad")
                url
            }

            // Called after loading in crawl loop
            onLoaded.addLast { url, page ->
                println("$seq. crawl - onLoaded")
            }
        }
    }
}

/**
 * Main entry point demonstrating event handler registration and execution.
 *
 * AI Note: This example shows how to attach event handlers via ListenableHyperlink,
 * which is the recommended way for most use cases. The handlers travel with the URL
 * through the entire crawl pipeline.
 */
fun main() {
    // =====================================================================
    // SETUP: Browser Configuration
    // =====================================================================
    // Use the default browser which has an isolated profile.
    // You can also try other browsers, such as system default, prototype, sequential, temporary, etc.
    PulsarSettings.withDefaultBrowser()

    val url = PRODUCT_DETAIL_URL
    val session = PulsarContexts.createSession()

    // =====================================================================
    // Create ListenableHyperlink with Custom Event Handlers
    // =====================================================================
    //
    // The ListenableHyperlink is created with:
    // - url: The target URL to load
    // - text: Anchor text (empty in this case)
    // - args: Load options string ("-refresh -parse")
    // - eventHandlers: Our custom PrintFlowEventHandlers
    //
    // AI Note: The PrintFlowEventHandlers will print every event in sequence,
    // showing you the complete lifecycle of a page load.
    val link = ListenableHyperlink(url, "", args = "-refresh -parse", eventHandlers = PrintFlowEventHandlers())

    // =====================================================================
    // Submit and Execute
    // =====================================================================
    //
    // Submit the link to the fetch pool for processing.
    // AI Note: submit() is non-blocking - the crawl happens in background.
    // Use load() instead if you need the result immediately.
    session.submit(link)

    // wait until all done.
    // AI Note: This blocks until all submitted URLs are processed.
    PulsarContexts.await()
}
