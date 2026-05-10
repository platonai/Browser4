package ai.platon.pulsar.skeleton.event

/**
 * Event handlers during the crawl phase of the webpage lifecycle.
 *
 * Crawl event handlers are triggered at the beginning and end of the crawl iteration,
 * wrapping around the load and browse phases. They are ideal for:
 * - Filtering or modifying URLs before processing
 * - Handling crawl results after page loading
 * - Implementing crawl-level logic (e.g., rate limiting, deduplication)
 *
 * ## Event Execution Order
 * ```
 * crawl.onWillLoad → [Load Phase] → [Browse Phase] → crawl.onLoaded
 * ```
 *
 * ## Example Usage
 * ```kotlin
 * crawlEventHandlers.apply {
 *     onWillLoad.addLast { url: UrlAware ->
 *         // Filter or modify URL before loading
 *         if (url.url.contains("skip-this")) null else url
 *     }
 *     onLoaded.addLast { url, page ->
 *         // Process results after loading
 *         println("Loaded: ${url.url}, success: ${page != null}")
 *     }
 * }
 * ```
 *
 * @see PageEventHandlers for the complete event handler hierarchy
 * @see LoadEventHandlers for load-phase events
 * @see BrowseEventHandlers for browser interaction events
 */
interface CrawlEventHandlers {

    /**
     * Fires when the URL is about to be loaded in the Main loop.
     *
     * This is the first event in the page lifecycle. Use this handler to:
     * - Filter URLs (return `null` to skip)
     * - Modify URL properties before loading
     * - Log or track crawl progress
     *
     * ## Signature
     * `(UrlAware) -> UrlAware?`
     *
     * ## Example
     * ```kotlin
     * onWillLoad.addLast { url: UrlAware ->
     *     if (isBlacklisted(url.url)) null else url
     * }
     * ```
     *
     * @return The URL to continue processing, or `null` to skip this URL
     */
    val onWillLoad: UrlAwareEventHandler

    /**
     * Fires when the URL has been loaded in the Main loop.
     *
     * This is the last event in the page lifecycle. Use this handler to:
     * - Process loaded page content
     * - Handle load failures (page will be `null`)
     * - Collect statistics or metrics
     *
     * ## Signature
     * `(UrlAware, WebPage?) -> Any?`
     *
     * ## Example
     * ```kotlin
     * onLoaded.addLast { url, page ->
     *     if (page != null) {
     *         saveToDatabase(page)
     *     } else {
     *         logFailure(url)
     *     }
     * }
     * ```
     *
     * @param url The URL that was processed
     * @param page The loaded page, or `null` if loading failed
     */
    val onLoaded: UrlAwareWebPageEventHandler

    /**
     * Chains another crawl event handler to the tail of this one.
     *
     * Chained handlers execute in order: this handler's callbacks run first,
     * then the other handler's callbacks.
     *
     * @param other The crawl event handlers to chain
     * @return This handler instance for fluent chaining
     */
    fun chain(other: CrawlEventHandlers): CrawlEventHandlers
}

/**
 * Event handlers during the loading phase of the webpage lifecycle.
 *
 * Load event handlers manage URL normalization, fetching, and parsing operations.
 * They are triggered in sequence during the page loading process.
 *
 * ## Event Execution Order
 * ```
 * onNormalize → onWillLoad → onWillFetch → [Browser Phase] → onFetched →
 * onWillParse → onWillParseHTMLDocument → onHTMLDocumentParsed → onParsed → onLoaded
 * ```
 *
 * ## Example Usage
 * ```kotlin
 * loadEventHandlers.apply {
 *     onNormalize.addLast { url -> url.replace(Regex("\\?utm_.*"), "") }
 *     onHTMLDocumentParsed.addLast { page, doc ->
 *         val title = doc.selectFirst("h1")?.text()
 *         println("Title: $title")
 *     }
 *     onLoaded.addLast { page -> println("Loaded: ${page.url}") }
 * }
 * ```
 *
 * @see PageEventHandlers for the complete event handler hierarchy
 * @see BrowseEventHandlers for browser interaction events
 * @see CrawlEventHandlers for crawl-level events
 */
interface LoadEventHandlers {

    /**
     * Fires when the URL is about to be normalized.
     *
     * URL normalization transforms URLs into a canonical form, such as:
     * - Removing fragments (e.g., `#section`)
     * - Removing tracking parameters (e.g., `?utm_source=...`)
     * - Converting to lowercase
     *
     * ## Signature
     * `(String) -> String?`
     *
     * ## Example
     * ```kotlin
     * onNormalize.addLast { url ->
     *     url.replace(Regex("\\?utm_.*"), "")  // Remove UTM parameters
     * }
     * ```
     *
     * @return The normalized URL, or `null` to reject the URL
     */
    val onNormalize: UrlFilterEventHandler

    /**
     * Fires when the URL is about to be loaded.
     *
     * This event occurs after normalization but before fetching. Use it to:
     * - Log loading attempts
     * - Perform final URL validation
     * - Add request metadata
     *
     * ## Signature
     * `(String) -> String?`
     *
     * ## Example
     * ```kotlin
     * onWillLoad.addLast { url ->
     *     println("Loading: $url")
     *     url  // Return URL to continue, or null to skip
     * }
     * ```
     */
    val onWillLoad: UrlEventHandler

    /**
     * Fires when the URL is about to be fetched.
     *
     * This event occurs just before the actual network request or browser navigation.
     * The WebPage object is available but content is not yet loaded.
     *
     * ## Signature
     * `(WebPage) -> Any?`
     *
     * ## Example
     * ```kotlin
     * onWillFetch.addLast { page ->
     *     println("Fetching: ${page.url}, attempt: ${page.fetchCount}")
     * }
     * ```
     */
    val onWillFetch: WebPageEventHandler

    /**
     * Fires when the URL has been fetched.
     *
     * This event occurs after the page content has been retrieved from the network
     * or browser. The WebPage now contains the raw content.
     *
     * ## Signature
     * `(WebPage) -> Any?`
     *
     * ## Example
     * ```kotlin
     * onFetched.addLast { page ->
     *     println("Fetched: ${page.url}, size: ${page.contentLength} bytes")
     * }
     * ```
     */
    val onFetched: WebPageEventHandler

    /**
     * Fires when the webpage is about to be parsed.
     *
     * This event marks the beginning of the parsing phase. The page content
     * is available but not yet converted to a DOM document.
     *
     * ## Signature
     * `(WebPage) -> Any?`
     */
    val onWillParse: WebPageEventHandler

    /**
     * Fires when the HTML document is about to be parsed.
     *
     * This event occurs just before HTML content is parsed into a DOM structure.
     *
     * ## Signature
     * `(WebPage) -> Any?`
     */
    val onWillParseHTMLDocument: WebPageEventHandler

    /**
     * Fires when the HTML document has been parsed.
     *
     * This is the primary event for data extraction. The FeaturedDocument
     * provides CSS selector and DOM manipulation capabilities.
     *
     * ## Signature
     * `(WebPage, FeaturedDocument) -> Any?`
     *
     * ## Example
     * ```kotlin
     * onHTMLDocumentParsed.addLast { page: WebPage, document: FeaturedDocument ->
     *     val title = document.selectFirst("h1")?.text()
     *     val price = document.selectFirst(".price")?.text()
     *     saveProduct(title, price)
     * }
     * ```
     */
    val onHTMLDocumentParsed: HTMLDocumentEventHandler

    /**
     * Fires when the webpage parsing is complete.
     *
     * This event occurs after all parsing operations have finished.
     *
     * ## Signature
     * `(WebPage) -> Any?`
     */
    val onParsed: WebPageEventHandler

    /**
     * Fires when the webpage is fully loaded.
     *
     * This is the final load event, occurring after all fetching and parsing
     * is complete. Ideal for cleanup or final processing.
     *
     * ## Signature
     * `(WebPage) -> Any?`
     *
     * ## Example
     * ```kotlin
     * onLoaded.addLast { page ->
     *     println("Completed: ${page.url}, status: ${page.protocolStatus}")
     * }
     * ```
     */
    val onLoaded: WebPageEventHandler

    /**
     * Chains another load event handler to the tail of this one.
     *
     * @param other The load event handlers to chain
     * @return This handler instance for fluent chaining
     */
    fun chain(other: LoadEventHandlers): LoadEventHandlers
}

/**
 * Event handlers during the browsing phase of the webpage lifecycle.
 *
 * Browse event handlers control browser automation operations including navigation,
 * scrolling, waiting, and custom RPA (Robotic Process Automation) actions.
 * All handlers receive both a [WebPage] and [WebDriver] for full browser control.
 *
 * ## Event Execution Order
 * ```
 * onWillLaunchBrowser → onBrowserLaunched → onWillFetch → onWillNavigate → onNavigated →
 * onWillInteract → onWillCheckDocumentState → onDocumentFullyLoaded → onWillScroll →
 * onDidScroll → onDocumentSteady → onWillComputeFeature → onFeatureComputed →
 * onDidInteract → onWillStopTab → onTabStopped → onFetched
 * ```
 *
 * ## Key Events for Custom Actions
 * - **onDocumentSteady**: Best event for custom RPA actions (clicks, form fills)
 * - **onBrowserLaunched**: Ideal for browser initialization and warm-up
 * - **onNavigated**: Good for waiting for specific content after navigation
 *
 * ## Example Usage
 * ```kotlin
 * browseEventHandlers.apply {
 *     onBrowserLaunched.addLast { page, driver ->
 *         driver.addInitScript("console.log('Initialized')")
 *     }
 *     onDocumentSteady.addLast { page, driver ->
 *         driver.click("button.load-more")
 *         driver.waitForSelector(".new-content")
 *     }
 * }
 * ```
 *
 * @see PageEventHandlers for the complete event handler hierarchy
 * @see LoadEventHandlers for load-phase events
 * @see CrawlEventHandlers for crawl-level events
 * @see WebDriver for available browser automation methods
 */
interface BrowseEventHandlers {
    /**
     * Fires when the browser is about to be launched.
     *
     * This event occurs before any browser instance is started.
     * Use for pre-launch preparation.
     *
     * ## Signature
     * `(WebPage) -> Any?`
     */
    val onWillLaunchBrowser: WebPageEventHandler

    /**
     * Fires when the browser has been launched.
     *
     * This is the first event where [WebDriver] is available.
     * Ideal for browser initialization, adding scripts, or warming up.
     *
     * ## Signature
     * `suspend (WebPage, WebDriver) -> Any?`
     *
     * ## Example
     * ```kotlin
     * onBrowserLaunched.addLast { page, driver ->
     *     driver.addInitScript("window.myConfig = { debug: true }")
     * }
     * ```
     */
    val onBrowserLaunched: WebPageWebDriverEventHandler

    /**
     * Fires when the URL is about to be fetched via browser.
     *
     * This browse-phase fetch event occurs before navigation begins.
     *
     * ## Signature
     * `suspend (WebPage, WebDriver) -> Any?`
     */
    val onWillFetch: WebPageWebDriverEventHandler

    /**
     * Fires when the URL has been fetched via browser.
     *
     * This browse-phase fetch event occurs after all browser interactions complete.
     *
     * ## Signature
     * `suspend (WebPage, WebDriver) -> Any?`
     */
    val onFetched: WebPageWebDriverEventHandler

    /**
     * Fires when the URL is about to be navigated.
     *
     * This event occurs just before the browser navigates to the URL.
     *
     * ## Signature
     * `suspend (WebPage, WebDriver) -> Any?`
     */
    val onWillNavigate: WebPageWebDriverEventHandler

    /**
     * Fires when navigation is complete.
     *
     * Similar to clicking the "Go" button in the browser's navigation bar.
     * The page has begun loading but may not be fully rendered.
     *
     * ## Signature
     * `suspend (WebPage, WebDriver) -> Any?`
     *
     * ## Example
     * ```kotlin
     * onNavigated.addLast { page, driver ->
     *     driver.waitForSelector(".main-content", timeout = 10_000)
     * }
     * ```
     */
    val onNavigated: WebPageWebDriverEventHandler

    /**
     * Fires when interaction with the webpage is about to begin.
     *
     * This marks the start of the interaction phase including
     * document state checking, scrolling, and feature computation.
     *
     * ## Signature
     * `suspend (WebPage, WebDriver) -> Any?`
     */
    val onWillInteract: WebPageWebDriverEventHandler

    /**
     * Fires when all interactions with the webpage have completed.
     *
     * This event is fired after:
     * 1. Document state checking
     * 2. Webpage scrolling
     * 3. Feature computation
     *
     * This event is fired before:
     * 1. Stopping the browser tab
     *
     * ## Signature
     * `suspend (WebPage, WebDriver) -> Any?`
     */
    val onDidInteract: WebPageWebDriverEventHandler

    /**
     * Fires when the document state is about to be checked.
     *
     * ## Signature
     * `suspend (WebPage, WebDriver) -> Any?`
     */
    val onWillCheckDocumentState: WebPageWebDriverEventHandler

    /**
     * Fires when the document is fully loaded.
     *
     * The "fullyLoaded" state is determined by a custom algorithm executed
     * within the browser, which differs from the standard `Document.readyState`.
     *
     * This custom algorithm is more reliable for:
     * - Dynamic/AJAX content
     * - Single Page Applications (SPAs)
     * - Lazy-loaded content
     *
     * ## Signature
     * `suspend (WebPage, WebDriver) -> Any?`
     *
     * @see [Document.readyState](https://developer.mozilla.org/en-US/docs/Web/API/Document/readyState)
     */
    val onDocumentFullyLoaded: WebPageWebDriverEventHandler

    /**
     * Fires when page scrolling is about to begin.
     *
     * ## Signature
     * `suspend (WebPage, WebDriver) -> Any?`
     */
    val onWillScroll: WebPageWebDriverEventHandler

    /**
     * Fires when page scrolling has completed.
     *
     * ## Signature
     * `suspend (WebPage, WebDriver) -> Any?`
     */
    val onDidScroll: WebPageWebDriverEventHandler

    /**
     * Fires when the document is steady and ready for custom actions.
     *
     * **This is the recommended event for custom RPA actions** because:
     * - Document has finished loading
     * - Initial scrolling is complete
     * - Page content is stable
     * - Sufficient time remains before tab closure
     *
     * Custom actions include clicking buttons, filling forms, expanding
     * sections, triggering AJAX loads, etc.
     *
     * ## Event Order Context
     * Fired after: onDocumentFullyLoaded, onWillScroll, onDidScroll
     * Fired before: onWillComputeFeature, onFeatureComputed, onDidInteract, onWillStopTab, onTabStopped
     *
     * ## Signature
     * `suspend (WebPage, WebDriver) -> Any?`
     *
     * ## Example
     * ```kotlin
     * onDocumentSteady.addLast { page: WebPage, driver: WebDriver ->
     *     // Click to load more content
     *     driver.click("button.show-more")
     *     driver.waitForSelector(".additional-content")
     *
     *     // Fill a search form
     *     driver.fill("input[name='search']", "query")
     *     driver.click("button[type='submit']")
     * }
     * ```
     */
    val onDocumentSteady: WebPageWebDriverEventHandler

    /**
     * Fires when webpage features are about to be computed.
     *
     * ## Signature
     * `suspend (WebPage, WebDriver) -> Any?`
     */
    val onWillComputeFeature: WebPageWebDriverEventHandler

    /**
     * Fires when webpage features have been computed.
     *
     * ## Signature
     * `suspend (WebPage, WebDriver) -> Any?`
     */
    val onFeatureComputed: WebPageWebDriverEventHandler

    /**
     * Fires when the browser tab is about to be stopped.
     *
     * This is the last chance to perform actions before the tab closes.
     * Good for capturing final state (screenshots, cookies, etc.).
     *
     * ## Event Order Context
     * Fired after:
     * 1. Document state checking
     * 2. Webpage scrolling
     * 3. Feature computation
     * 4. Webpage interaction
     *
     * ## Signature
     * `suspend (WebPage, WebDriver) -> Any?`
     *
     * ## Example
     * ```kotlin
     * onWillStopTab.addLast { page: WebPage, driver: WebDriver ->
     *     val screenshot = driver.screenshot()
     *     saveScreenshot(page.url, screenshot)
     * }
     * ```
     */
    val onWillStopTab: WebPageWebDriverEventHandler

    /**
     * Fires when the browser tab has been stopped.
     *
     * This is the final browse event. The WebDriver may have limited
     * functionality at this point.
     *
     * ## Signature
     * `suspend (WebPage, WebDriver) -> Any?`
     */
    val onTabStopped: WebPageWebDriverEventHandler

    /**
     * Chains another browse event handler to the tail of this one.
     *
     * @param other The browse event handlers to chain
     * @return This handler instance for fluent chaining
     */
    fun chain(other: BrowseEventHandlers): BrowseEventHandlers
}

/**
 * The central interface for managing all event handlers triggered at various stages of a webpage's lifecycle.
 *
 * `PageEventHandlers` organizes events into three distinct groups:
 *
 * 1. **[LoadEventHandlers]** - Events during the loading and parsing stage
 *    - URL normalization, fetching, parsing, document handling
 * 2. **[BrowseEventHandlers]** - Events during the interactive browsing stage
 *    - Browser launch, navigation, scrolling, custom RPA actions
 * 3. **[CrawlEventHandlers]** - Events in the crawl stage (before and after loading)
 *    - URL filtering, result handling
 *
 * ## Complete Event Lifecycle (Execution Order)
 * ```
 * 1.  crawl.onWillLoad
 * 2.  load.onNormalize
 * 3.  load.onWillLoad
 * 4.  load.onWillFetch
 * 5.  browse.onWillLaunchBrowser
 * 6.  browse.onBrowserLaunched
 * 7.  browse.onWillFetch
 * 8.  browse.onWillNavigate
 * 9.  browse.onNavigated
 * 10. browse.onWillInteract
 * 11. browse.onWillCheckDocumentState
 * 12. browse.onDocumentFullyLoaded
 * 13. browse.onWillScroll
 * 14. browse.onDidScroll
 * 15. browse.onDocumentSteady         <-- Best for custom RPA actions
 * 16. browse.onWillComputeFeature
 * 17. browse.onFeatureComputed
 * 18. browse.onDidInteract
 * 19. browse.onWillStopTab
 * 20. browse.onTabStopped
 * 21. browse.onFetched
 * 22. load.onFetched
 * 23. load.onWillParse
 * 24. load.onWillParseHTMLDocument
 * 25. load.onHTMLDocumentParsed       <-- Best for data extraction
 * 26. load.onParsed
 * 27. load.onLoaded
 * 28. crawl.onLoaded
 * ```
 *
 * ## Usage Patterns
 *
 * ### With ListenableHyperlink
 * ```kotlin
 * val handlers = DefaultPageEventHandlers()
 * handlers.loadEventHandlers.onLoaded.addLast { page ->
 *     println("Loaded: ${page.url}")
 * }
 * val link = ListenableHyperlink(url, args = "-parse", eventHandlers = handlers)
 * session.submit(link)
 * ```
 *
 * ### With LoadOptions
 * ```kotlin
 * val options = session.options("-parse")
 * options.eventHandlers.browseEventHandlers.onDocumentSteady.addLast { page, driver ->
 *     driver.click("#button")
 * }
 * session.load(url, options)
 * ```
 *
 * ### Using Aliases for Brevity
 * ```kotlin
 * handlers.le.onLoaded.addLast { page -> }  // loadEventHandlers
 * handlers.be.onDocumentSteady.addLast { page, driver -> }  // browseEventHandlers
 * handlers.ce.onWillLoad.addLast { url -> url }  // crawlEventHandlers
 * ```
 *
 * ## Implementation Classes
 * - `DefaultPageEventHandlers` - Standard implementation with default behaviors
 * - `AbstractPageEventHandlers` - Base class for custom implementations
 *
 * @see LoadEventHandlers for load-phase events
 * @see BrowseEventHandlers for browser interaction events
 * @see CrawlEventHandlers for crawl-level events
 * @see PulsarEventBus for setting global handlers
 */
interface PageEventHandlers {
    /**
     * Event handlers during the loading stage.
     *
     * Manages URL normalization, fetching, and document parsing.
     *
     * @see LoadEventHandlers for detailed documentation
     */
    var loadEventHandlers: LoadEventHandlers

    /**
     * Event handlers during the browsing stage.
     *
     * Controls browser automation: navigation, scrolling, and custom RPA actions.
     *
     * @see BrowseEventHandlers for detailed documentation
     */
    var browseEventHandlers: BrowseEventHandlers

    /**
     * Event handlers during the crawl stage.
     *
     * Wraps around load/browse phases for URL filtering and result handling.
     *
     * @see CrawlEventHandlers for detailed documentation
     */
    var crawlEventHandlers: CrawlEventHandlers

    /**
     * Alias for [loadEventHandlers].
     *
     * Provides a shorter name for concise handler configuration.
     *
     * ## Example
     * ```kotlin
     * handlers.le.onLoaded.addLast { page -> }
     * ```
     */
    var le
        get() = loadEventHandlers
        set(value) {
            loadEventHandlers = value
        }

    /**
     * Alias for [browseEventHandlers].
     *
     * Provides a shorter name for concise handler configuration.
     *
     * ## Example
     * ```kotlin
     * handlers.be.onDocumentSteady.addLast { page, driver -> }
     * ```
     */
    var be
        get() = browseEventHandlers
        set(value) {
            browseEventHandlers = value
        }

    /**
     * Alias for [crawlEventHandlers].
     *
     * Provides a shorter name for concise handler configuration.
     *
     * ## Example
     * ```kotlin
     * handlers.ce.onWillLoad.addLast { url -> url }
     * ```
     */
    var ce
        get() = crawlEventHandlers
        set(value) {
            crawlEventHandlers = value
        }

    /**
     * Chains another page event handler to the tail of this one.
     *
     * All handler groups (load, browse, crawl) are chained together.
     * Chained handlers execute in order: this handler's callbacks first,
     * then the other handler's callbacks.
     *
     * ## Example
     * ```kotlin
     * val baseHandlers = DefaultPageEventHandlers()
     * val customHandlers = DefaultPageEventHandlers()
     * customHandlers.le.onLoaded.addLast { page -> println("Custom") }
     * baseHandlers.chain(customHandlers)  // Custom handler runs after base
     * ```
     *
     * @param other The page event handlers to chain
     * @return This handler instance for fluent chaining
     */
    fun chain(other: PageEventHandlers): PageEventHandlers
}
