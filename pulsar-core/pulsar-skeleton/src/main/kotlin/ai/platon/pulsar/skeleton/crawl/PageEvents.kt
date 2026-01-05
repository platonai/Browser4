package ai.platon.pulsar.skeleton.crawl

import ai.platon.pulsar.skeleton.crawl.event.*

/**
 * Event handlers during the crawl phase of the webpage lifecycle.
 *
 * CrawlEventHandlers operate at the crawl iteration level, before and after loading individual pages.
 * These handlers coordinate between pages and manage crawl-wide concerns.
 *
 * **Key characteristics:**
 * - Operates at higher abstraction level than Load/Browse handlers
 * - Can filter or transform URLs before loading
 * - Coordinates multiple page loads
 * - Useful for crawl-wide logic like statistics, scheduling, and filtering
 *
 * **Typical use cases:**
 * - URL filtering and validation at crawl level
 * - Priority scheduling decisions
 * - Rate limiting enforcement
 * - Crawl scope validation
 * - Crawl statistics tracking
 * - Success/failure handling
 *
 * @see LoadEventHandlers for page loading phase events
 * @see BrowseEventHandlers for browser interaction phase events
 * */
interface CrawlEventHandlers {

    /**
     * Fire when the url is about to be loaded.
     *
     * This is the first event in the entire page lifecycle, occurring before any
     * URL normalization or loading begins.
     *
     * **Handler signature:** `(UrlAware) -> UrlAware?`
     *
     * **Return:**
     * - Return the (possibly modified) UrlAware to continue loading
     * - Return null to skip loading this URL
     *
     * **Common uses:**
     * - Filter URLs based on crawl scope or patterns
     * - Modify URL metadata before loading
     * - Implement priority scheduling
     * - Apply rate limiting decisions
     *
     * **Example:**
     * ```kotlin
     * onWillLoad.addLast { url ->
     *     if (url.spec.contains("/ads/")) null // Skip ads
     *     else url
     * }
     * ```
     * */
    val onWillLoad: UrlAwareEventHandler

    /**
     * Fire when the url is loaded.
     *
     * This is the last event in the entire page lifecycle, occurring after all
     * loading, browsing, and parsing operations complete.
     *
     * **Handler signature:** `(UrlAware, WebPage?) -> Any?`
     *
     * **Parameters:**
     * - `url` - The URL that was loaded
     * - `page` - The loaded WebPage, or null if loading failed
     *
     * **Common uses:**
     * - Track crawl statistics (success/failure counts)
     * - Update crawl state and progress
     * - Trigger downstream processing
     * - Queue management decisions
     * - Final cleanup and logging
     *
     * **Example:**
     * ```kotlin
     * onLoaded.addLast { url, page ->
     *     if (page != null) {
     *         logger.info("Successfully loaded: {}", url.spec)
     *         statistics.incrementSuccess()
     *     } else {
     *         logger.warn("Failed to load: {}", url.spec)
     *         statistics.incrementFailure()
     *     }
     * }
     * ```
     * */
    val onLoaded: UrlAwareWebPageEventHandler

    /**
     * Chain the other crawl event handler to the tail of this one.
     *
     * Allows composing multiple event handlers together. Handlers from `other`
     * will execute after handlers in this instance.
     *
     * @param other The crawl event handlers to append
     * @return This instance for method chaining
     * */
    fun chain(other: CrawlEventHandlers): CrawlEventHandlers
}

/**
 * Event handlers during the loading phase of the webpage lifecycle.
 *
 * LoadEventHandlers manage events related to fetching and parsing page content.
 * These handlers operate on the page content after it's retrieved, without direct browser access.
 *
 * **Key characteristics:**
 * - Operates on WebPage objects (internal page representation)
 * - No direct browser access (use BrowseEventHandlers for that)
 * - Suitable for content manipulation and data extraction
 * - Executes sequentially during page load
 *
 * **Typical use cases:**
 * - URL normalization and filtering
 * - HTTP header configuration
 * - Content preprocessing and validation
 * - HTML parsing and DOM manipulation
 * - Data extraction from parsed documents
 * - Link collection for crawling
 *
 * **Event flow:**
 * 1. onNormalize - Normalize URL format
 * 2. onWillLoad - Before initiating load
 * 3. onWillFetch - Before fetching content
 * 4. [Browse phase if using browser]
 * 5. onFetched - After content fetched
 * 6. onWillParse - Before parsing
 * 7. onWillParseHTMLDocument - Before HTML parsing
 * 8. onHTMLDocumentParsed - After HTML parsed (DOM available)
 * 9. onParsed - After all parsing done
 * 10. onLoaded - Page fully loaded
 *
 * @see BrowseEventHandlers for browser interaction phase events
 * @see CrawlEventHandlers for crawl iteration phase events
 * */
interface LoadEventHandlers {

    /**
     * Fire when the url is about to be normalized.
     *
     * The event handlers normalize the url, for example, remove the fragment part of the url,
     * standardize query parameter ordering, or enforce specific URL formats.
     *
     * **Handler signature:** `(String) -> String?`
     *
     * **Return:**
     * - Return the normalized URL string to continue
     * - Return null to filter out this URL
     *
     * **Common uses:**
     * - Remove URL fragments (#anchors)
     * - Remove or normalize query parameters
     * - Enforce URL format standards
     * - Filter out specific URL patterns
     * - Canonicalize URLs for deduplication
     *
     * **Example:**
     * ```kotlin
     * onNormalize.addLast { url ->
     *     // Remove tracking parameters
     *     url.substringBefore('?')
     * }
     * ```
     * */
    val onNormalize: UrlFilterEventHandler

    /**
     * Fire when the url is about to be loaded.
     *
     * This is called immediately before loading begins, after normalization.
     * Last chance to modify or reject the URL.
     *
     * **Handler signature:** `(String) -> String?`
     *
     * **Return:**
     * - Return the (possibly modified) URL to continue loading
     * - Return null to skip loading this URL
     *
     * **Common uses:**
     * - Logging and monitoring
     * - Last-minute URL validation
     * - Rate limiting checks
     * - Load scheduling decisions
     *
     * **Example:**
     * ```kotlin
     * onWillLoad.addLast { url ->
     *     logger.info("About to load: {}", url)
     *     url // Continue with this URL
     * }
     * ```
     * */
    val onWillLoad: UrlEventHandler

    /**
     * Fire when the url is about to be fetched.
     *
     * Called before fetching the page content. Access to WebPage metadata.
     * Useful for configuring fetch options.
     *
     * **Handler signature:** `(WebPage) -> Any?`
     *
     * **Common uses:**
     * - Set custom HTTP headers
     * - Configure fetch options (timeout, retry, etc.)
     * - Pre-fetch validation
     * - Timing measurements
     *
     * **Example:**
     * ```kotlin
     * onWillFetch.addLast { page ->
     *     page.headers["User-Agent"] = "CustomBot/1.0"
     * }
     * ```
     * */
    val onWillFetch: WebPageEventHandler

    /**
     * Fire when the url is fetched.
     *
     * Called after page content is fetched, before any parsing.
     * The page object contains the raw content and HTTP metadata.
     *
     * **Handler signature:** `(WebPage) -> Any?`
     *
     * **Common uses:**
     * - Validate fetch success
     * - Log fetch metrics (size, time, status)
     * - Pre-process raw content
     * - Trigger dependent fetches
     *
     * **Example:**
     * ```kotlin
     * onFetched.addLast { page ->
     *     logger.info("Fetched {} bytes from {}", page.contentLength, page.url)
     * }
     * ```
     * */
    val onFetched: WebPageEventHandler

    /**
     * Fire when the webpage is about to be parsed.
     *
     * Called before any parsing begins. Use to set parsing options or
     * preprocess content.
     *
     * **Handler signature:** `(WebPage) -> Any?`
     *
     * **Common uses:**
     * - Set parsing options
     * - Content preprocessing
     * - Encoding detection and correction
     * - Parser selection
     *
     * **Example:**
     * ```kotlin
     * onWillParse.addLast { page ->
     *     page.encoding = "UTF-8"
     * }
     * ```
     * */
    val onWillParse: WebPageEventHandler

    /**
     * Fire when the html document is about to be parsed.
     *
     * Called specifically before HTML document parsing begins.
     * More specific than onWillParse.
     *
     * **Handler signature:** `(WebPage) -> Any?`
     *
     * **Common uses:**
     * - HTML-specific preprocessing
     * - Configure HTML parser options
     * - Validate HTML content
     * - Set up DOM processing
     *
     * **Example:**
     * ```kotlin
     * onWillParseHTMLDocument.addLast { page ->
     *     logger.info("Parsing HTML document: {}", page.url)
     * }
     * ```
     * */
    val onWillParseHTMLDocument: WebPageEventHandler

    /**
     * Fire when the html document is parsed.
     *
     * **This is the primary event for data extraction from parsed HTML.**
     *
     * Called after HTML document is parsed, providing access to the DOM.
     * This is where you extract data, collect links, analyze structure, etc.
     *
     * **Handler signature:** `(WebPage, FeaturedDocument) -> Any?`
     *
     * **Parameters:**
     * - `page` - The WebPage containing metadata
     * - `document` - The parsed FeaturedDocument (DOM) with selection methods
     *
     * **Common uses:**
     * - **Extract data** using CSS selectors or XPath
     * - **Collect links** for further crawling
     * - **Analyze page structure** and content
     * - **Store extracted data** to database or files
     * - **Validate** page content and structure
     *
     * **Example:**
     * ```kotlin
     * onHTMLDocumentParsed.addLast { page, document ->
     *     val products = document.select(".product").map { element ->
     *         Product(
     *             name = element.selectFirst(".name")?.text(),
     *             price = element.selectFirst(".price")?.text()
     *         )
     *     }
     *     // Store products...
     * }
     * ```
     *
     * @see FeaturedDocument for DOM manipulation and selection methods
     * */
    val onHTMLDocumentParsed: HTMLDocumentEventHandler

    /**
     * Fire when the webpage is parsed.
     *
     * Called after all parsing is complete (HTML, CSS, JavaScript references, etc.).
     * Final opportunity to process parsed content.
     *
     * **Handler signature:** `(WebPage) -> Any?`
     *
     * **Common uses:**
     * - Post-parsing validation
     * - Finalize extracted data
     * - Trigger downstream processing
     * - Update crawl statistics
     *
     * **Example:**
     * ```kotlin
     * onParsed.addLast { page ->
     *     logger.info("Parsing complete for: {}", page.url)
     * }
     * ```
     * */
    val onParsed: WebPageEventHandler

    /**
     * Fire when the webpage is loaded.
     *
     * Called when page loading is completely finished (fetch + parse complete).
     * This is the final event in the load phase.
     *
     * **Handler signature:** `(WebPage) -> Any?`
     *
     * **Common uses:**
     * - Final processing
     * - Store results to database
     * - Update crawl state
     * - Trigger callbacks
     * - Cleanup resources
     *
     * **Example:**
     * ```kotlin
     * onLoaded.addLast { page ->
     *     logger.info("Page fully loaded: {}", page.url)
     *     database.store(page)
     * }
     * ```
     * */
    val onLoaded: WebPageEventHandler

    /**
     * Chain the other load event handler to the tail of this one.
     *
     * Allows composing multiple event handlers together. Handlers from `other`
     * will execute after handlers in this instance.
     *
     * @param other The load event handlers to append
     * @return This instance for method chaining
     * */
    fun chain(other: LoadEventHandlers): LoadEventHandlers
}

/**
 * Event handlers during the browsing phase of the webpage lifecycle.
 *
 * BrowseEventHandlers manage events during the browser interaction phase. These handlers have access
 * to the WebDriver for direct browser control and automation.
 *
 * **Key characteristics:**
 * - Has access to WebDriver for browser automation
 * - Can execute JavaScript in the browser context
 * - Supports async operations (coroutines)
 * - Ideal for dynamic interactions and RPA tasks
 * - All handlers are suspend functions
 *
 * **Typical use cases:**
 * - Browser automation (clicking, typing, scrolling)
 * - JavaScript execution
 * - Waiting for dynamic content
 * - Modal and popup handling
 * - Form filling and submission
 * - Screenshot capture
 * - Login flows
 * - Infinite scroll handling
 * - RPA (Robotic Process Automation) tasks
 *
 * **Event flow:**
 * 1. onWillLaunchBrowser - Before browser starts
 * 2. onBrowserLaunched - Browser ready (first WebDriver access)
 * 3. onWillFetch - Before browser fetch
 * 4. onWillNavigate - Before navigation
 * 5. onNavigated - After URL loaded in browser
 * 6. onWillInteract - Before interaction phase begins
 * 7. onWillCheckDocumentState - Before checking DOM state
 * 8. onDocumentFullyLoaded - DOM fully loaded
 * 9. onWillScroll - Before scrolling
 * 10. onDidScroll - After scrolling
 * 11. onDocumentSteady - Page stable ⭐ **BEST FOR CUSTOM ACTIONS**
 * 12. onWillComputeFeature - Before feature extraction
 * 13. onFeatureComputed - After features extracted
 * 14. onDidInteract - After all interactions complete
 * 15. onWillStopTab - Before closing tab
 * 16. onTabStopped - After tab closed
 * 17. onFetched - After browser fetch complete
 *
 * @see LoadEventHandlers for page loading phase events
 * @see CrawlEventHandlers for crawl iteration phase events
 * @see WebDriver for available automation methods
 * */
interface BrowseEventHandlers {
    /**
     * Fire when the browser is about to be launched.
     *
     * Called before launching browser for this page. This is the last event before browser startup.
     *
     * **Handler signature:** `(WebPage) -> Any?`
     *
     * **Common uses:**
     * - Browser launch logging
     * - Pre-launch configuration
     * - Resource allocation tracking
     * - Cost/quota tracking
     *
     * **Example:**
     * ```kotlin
     * onWillLaunchBrowser.addLast { page ->
     *     logger.info("Launching browser for: {}", page.url)
     * }
     * ```
     * */
    val onWillLaunchBrowser: WebPageEventHandler
    
    /**
     * Fire when the browser is launched.
     *
     * **This is the first event with WebDriver access.**
     *
     * Called after browser is launched and ready. Use this for browser warm-up operations.
     *
     * **Handler signature:** `suspend (WebPage, WebDriver) -> Any?`
     *
     * **Common uses:**
     * - Browser warm-up operations
     * - Set cookies or local storage
     * - Inject monitoring scripts
     * - Configure viewport size
     * - Perform login flows (for session reuse)
     *
     * **Example:**
     * ```kotlin
     * onBrowserLaunched.addLast { page, driver ->
     *     // Set viewport size
     *     driver.evaluate("window.resizeTo(1920, 1080)")
     *     // Inject monitoring script
     *     driver.evaluate("console.log('Browser ready')")
     * }
     * ```
     * */
    val onBrowserLaunched: WebPageWebDriverEventHandler

    /**
     * Fire when the url is about to be fetched via browser.
     *
     * Called before fetching via browser (before navigation). Use for pre-fetch coordination.
     *
     * **Handler signature:** `suspend (WebPage, WebDriver) -> Any?`
     *
     * **Common uses:**
     * - Wait for previous page to complete
     * - Set up network interception
     * - Configure browser behavior
     * - Implement rate limiting
     * - Coordinate with referrer pages
     *
     * **Example:**
     * ```kotlin
     * onWillFetch.addLast { page, driver ->
     *     // Wait a bit before fetching next page
     *     delay(1000)
     * }
     * ```
     * */
    val onWillFetch: WebPageWebDriverEventHandler
    
    /**
     * Fire when the url is fetched via browser.
     *
     * Called after browser fetch completes (after page content loaded).
     *
     * **Handler signature:** `suspend (WebPage, WebDriver) -> Any?`
     *
     * **Common uses:**
     * - Validate fetch success
     * - Check for error pages
     * - Capture network logs
     * - Measure timing
     *
     * **Example:**
     * ```kotlin
     * onFetched.addLast { page, driver ->
     *     val currentUrl = driver.currentUrl()
     *     logger.info("Browser fetch complete: {}", currentUrl)
     * }
     * ```
     * */
    val onFetched: WebPageWebDriverEventHandler

    /**
     * Fire when the url is about to be navigated in the browser.
     *
     * Called before navigating to the URL in browser (before driver.navigateTo).
     *
     * **Handler signature:** `suspend (WebPage, WebDriver) -> Any?`
     *
     * **Common uses:**
     * - Log navigation
     * - Pre-navigation setup
     * - Cancel navigation conditionally
     * - Measure navigation timing
     *
     * **Example:**
     * ```kotlin
     * onWillNavigate.addLast { page, driver ->
     *     logger.info("Navigating to: {}", page.url)
     * }
     * ```
     * */
    val onWillNavigate: WebPageWebDriverEventHandler
    
    /**
     * Fire when the url is navigated, just like we clicked the `Go` button on the browser's navigation bar.
     *
     * Called after navigation completes (URL loaded in browser).
     *
     * **Handler signature:** `suspend (WebPage, WebDriver) -> Any?`
     *
     * **Common uses:**
     * - Verify navigation success
     * - Check for redirects
     * - Capture initial page state
     * - Handle navigation errors
     *
     * **Example:**
     * ```kotlin
     * onNavigated.addLast { page, driver ->
     *     val currentUrl = driver.currentUrl()
     *     if (currentUrl.contains("/error")) {
     *         logger.error("Navigation error: {}", currentUrl)
     *     }
     * }
     * ```
     * */
    val onNavigated: WebPageWebDriverEventHandler

    /**
     * Fire when the interaction with the webpage is about to begin.
     *
     * Called before beginning page interactions (scrolling, clicking, etc.).
     * Marks the start of the interaction phase.
     *
     * **Handler signature:** `suspend (WebPage, WebDriver) -> Any?`
     *
     * **Common uses:**
     * - Pre-interaction setup
     * - Load custom scripts
     * - Initialize interaction state
     * - Set interaction parameters
     *
     * **Example:**
     * ```kotlin
     * onWillInteract.addLast { page, driver ->
     *     logger.info("Starting interactions with: {}", page.url)
     * }
     * ```
     * */
    val onWillInteract: WebPageWebDriverEventHandler
    
    /**
     * Fire when the interactions with the webpage have been completed.
     *
     * Called after all interactions are complete (scrolling, features, custom actions).
     * This is the final event in the interaction phase.
     *
     * **Handler signature:** `suspend (WebPage, WebDriver) -> Any?`
     *
     * This event is fired after the completion of the following actions:
     * 1. Checking the document state
     * 2. Completing webpage scrolling
     * 3. Computing webpage features
     *
     * The event is fired before the following actions:
     * 1. Stopping the browser tab
     *
     * **Common uses:**
     * - Final validation
     * - Capture final page state
     * - Measure total interaction time
     * - Cleanup before closing
     *
     * **Example:**
     * ```kotlin
     * onDidInteract.addLast { page, driver ->
     *     logger.info("All interactions complete")
     *     // Take final screenshot
     * }
     * ```
     * */
    val onDidInteract: WebPageWebDriverEventHandler

    /**
     * Fire when the document state is about to be checked.
     *
     * Called before checking if document is fully loaded.
     *
     * **Handler signature:** `suspend (WebPage, WebDriver) -> Any?`
     *
     * **Common uses:**
     * - Pre-check delays
     * - Custom ready state logic
     * - Performance monitoring
     * - Debug logging
     *
     * **Example:**
     * ```kotlin
     * onWillCheckDocumentState.addLast { page, driver ->
     *     delay(500) // Wait before checking
     * }
     * ```
     * */
    val onWillCheckDocumentState: WebPageWebDriverEventHandler

    /**
     * Fire when the document is fully loaded.
     *
     * The `fullyLoaded` state is determined using a custom algorithm executed within the browser.
     * This differs from the standard Document.readyState.
     *
     * **Handler signature:** `suspend (WebPage, WebDriver) -> Any?`
     *
     * This `fullyLoaded` state differs from the standard Document.readyState, which describes the loading state of the
     * document. When Document.readyState changes, a readystatechange event fires on the document object.
     *
     * **Common uses:**
     * - Capture fully loaded state
     * - Start dependent operations
     * - Measure load performance
     * - Take screenshots
     *
     * **Example:**
     * ```kotlin
     * onDocumentFullyLoaded.addLast { page, driver ->
     *     logger.info("Document fully loaded")
     * }
     * ```
     *
     * @see [Document.readyState](https://developer.mozilla.org/en-US/docs/Web/API/Document/readyState)
     * */
    val onDocumentFullyLoaded: WebPageWebDriverEventHandler

    /**
     * Fire when we are about to perform scrolling on the page.
     *
     * Called before scrolling the page.
     *
     * **Handler signature:** `suspend (WebPage, WebDriver) -> Any?`
     *
     * **Common uses:**
     * - Pre-scroll setup
     * - Configure scroll behavior
     * - Measure scroll timing
     * - Handle fixed elements
     *
     * **Example:**
     * ```kotlin
     * onWillScroll.addLast { page, driver ->
     *     logger.info("About to scroll page")
     * }
     * ```
     * */
    val onWillScroll: WebPageWebDriverEventHandler
    
    /**
     * Fire when we have performed scrolling on the page.
     *
     * Called after scrolling the page.
     *
     * **Handler signature:** `suspend (WebPage, WebDriver) -> Any?`
     *
     * **Common uses:**
     * - Verify scroll completed
     * - Check for lazy-loaded content
     * - Capture scroll metrics
     * - Trigger scroll-dependent actions
     *
     * **Example:**
     * ```kotlin
     * onDidScroll.addLast { page, driver ->
     *     val scrollHeight = driver.evaluate("document.body.scrollHeight", 0)
     *     logger.info("Scrolled page, height: {}", scrollHeight)
     * }
     * ```
     * */
    val onDidScroll: WebPageWebDriverEventHandler

    /**
     * Fire when we have performed scrolling on the page, at which point the document is considered not to change
     * unless other interactive actions occur.
     *
     * **⭐ This is the IDEAL event for custom browser automation actions. ⭐**
     *
     * At this point, the page is stable and ready for custom interactions like clicking buttons,
     * filling forms, or extracting dynamic content.
     *
     * **Handler signature:** `suspend (WebPage, WebDriver) -> Any?`
     *
     * Custom actions are defined by the user using code snippets that are written for a specific purpose, such as:
     * - Clicking "Load More" buttons
     * - Expanding collapsed sections
     * - Filling and submitting forms
     * - Closing modals and popups
     * - Handling infinite scroll
     * - Taking screenshots
     * - Any RPA task requiring a stable page
     *
     * The event is fired after the completion of the following actions:
     * - onDocumentFullyLoaded
     * - onWillScroll
     * - onDidScroll
     *
     * The event is fired before the following actions:
     * - onWillComputeFeature
     * - onFeatureComputed
     * - onDidInteract
     * - onWillStopTab
     * - onTabStopped
     *
     * **Common uses (MOST IMPORTANT HANDLER):**
     * - **Click "Load More" buttons** and wait for new content
     * - **Fill and submit forms**
     * - **Close cookie consents and popups**
     * - **Handle infinite scroll** by detecting when to stop
     * - **Expand collapsed content**
     * - **Execute custom JavaScript**
     * - **Wait for AJAX/dynamic content**
     * - **Perform any RPA automation task**
     *
     * **Example:**
     * ```kotlin
     * onDocumentSteady.addLast { page, driver ->
     *     // Close cookie consent
     *     if (driver.exists("#cookie-consent")) {
     *         driver.click("#cookie-consent .accept")
     *         delay(500)
     *     }
     *     
     *     // Click "Load More" until no more items
     *     while (driver.exists(".load-more-button")) {
     *         driver.click(".load-more-button")
     *         driver.waitForSelector(".new-items")
     *         delay(2000)
     *     }
     * }
     * ```
     * */
    val onDocumentSteady: WebPageWebDriverEventHandler

    /**
     * Fire when the webpage features are about to be computed.
     *
     * Called before computing page features (metadata, structure analysis).
     *
     * **Handler signature:** `suspend (WebPage, WebDriver) -> Any?`
     *
     * **Common uses:**
     * - Pre-computation setup
     * - Custom feature selection
     * - Performance monitoring
     * - Debug logging
     *
     * **Example:**
     * ```kotlin
     * onWillComputeFeature.addLast { page, driver ->
     *     logger.info("Computing page features")
     * }
     * ```
     * */
    val onWillComputeFeature: WebPageWebDriverEventHandler
    
    /**
     * Fire when the webpage features have been computed.
     *
     * Called after page features are computed (metadata, structure available).
     *
     * **Handler signature:** `suspend (WebPage, WebDriver) -> Any?`
     *
     * **Common uses:**
     * - Access computed features
     * - Feature-based decisions
     * - Quality assessment
     * - Store feature data
     *
     * **Example:**
     * ```kotlin
     * onFeatureComputed.addLast { page, driver ->
     *     logger.info("Features computed, page ready for extraction")
     * }
     * ```
     * */
    val onFeatureComputed: WebPageWebDriverEventHandler

    /**
     * Fire when the browser tab is about to be stopped.
     *
     * Called before closing the browser tab. Last chance to extract data or capture state.
     *
     * **Handler signature:** `suspend (WebPage, WebDriver) -> Any?`
     *
     * This event is fired after the completion of the following actions:
     * 1. Checking the document state
     * 2. Completing webpage scrolling
     * 3. Computing webpage features
     * 4. Interacting with the webpage
     *
     * **Common uses:**
     * - Final data capture
     * - Cleanup operations
     * - Resource release
     * - Performance metrics
     *
     * **Example:**
     * ```kotlin
     * onWillStopTab.addLast { page, driver ->
     *     val finalUrl = driver.currentUrl()
     *     logger.info("Closing tab: {}", finalUrl)
     * }
     * ```
     * */
    val onWillStopTab: WebPageWebDriverEventHandler
    
    /**
     * Fire when the browser tab is stopped.
     *
     * Called after browser tab is stopped/closed.
     *
     * **Handler signature:** `suspend (WebPage, WebDriver) -> Any?`
     *
     * **Note:** The WebDriver may be invalid at this point.
     *
     * **Common uses:**
     * - Post-close cleanup
     * - Statistics updates
     * - Resource tracking
     * - Error handling
     *
     * **Example:**
     * ```kotlin
     * onTabStopped.addLast { page, driver ->
     *     logger.info("Tab closed for: {}", page.url)
     * }
     * ```
     * */
    val onTabStopped: WebPageWebDriverEventHandler

    /**
     * Chain the other browse event handler to the tail of this one.
     *
     * Allows composing multiple event handlers together. Handlers from `other`
     * will execute after handlers in this instance.
     *
     * @param other The browse event handlers to append
     * @return This instance for method chaining
     * */
    fun chain(other: BrowseEventHandlers): BrowseEventHandlers
}

/**
 * The `PageEventHandlers` class specifies all event handlers that are triggered at various stages of a webpage’s lifecycle.
 *
 * The events are fall into three groups:
 *
 * 1. [LoadEventHandlers] triggers in loading stage.
 * 2. [BrowseEventHandlers] triggers in browsing stage.
 * 3. [CrawlEventHandlers] triggers in crawl stage, which is before and after loading the page.
 * */
interface PageEventHandlers {
    /**
     * Event handlers during the loading stage.
     * */
    var loadEventHandlers: LoadEventHandlers
    /**
     * Event handlers during the browsing stage.
     * */
    var browseEventHandlers: BrowseEventHandlers
    /**
     * Event handlers during the crawl stage.
     * */
    var crawlEventHandlers: CrawlEventHandlers
    /**
     * Alias of [loadEventHandlers]
     * */
    var le get() = loadEventHandlers
        set(value) {
            loadEventHandlers = value
        }
    /**
     * Alias of [browseEventHandlers]
     * */
    var be get() = browseEventHandlers
        set(value) {
            browseEventHandlers = value
        }
    /**
     * Alias of [crawlEventHandlers]
     * */
    var ce get() = crawlEventHandlers
        set(value) {
            crawlEventHandlers = value
        }

    /**
     * Chain the other page event handlers to the tail of this one.
     * */
    fun chain(other: PageEventHandlers): PageEventHandlers
}
