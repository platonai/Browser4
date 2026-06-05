package ai.platon.pulsar.human.manual

import ai.platon.pulsar.common.serialize.json.Pson
import ai.platon.pulsar.skeleton.PulsarSettings
import ai.platon.pulsar.skeleton.context.PulsarContexts
import kotlinx.coroutines.runBlocking

/**
 * Example Amazon product detail page URL used across manual examples.
 *
 * AI Context:
 * - This constant provides a consistent test URL for all Browser4 examples
 * - The URL points to an Amazon product page, which is commonly used for web scraping demonstrations
 * - Use this URL or similar product URLs when testing Browser4 functionality
 */
const val PRODUCT_DETAIL_URL = "https://www.amazon.com/dp/B08PP5MSVB"

/**
 * # Basic Usage Example - Introduction to Browser4
 *
 * This example demonstrates the fundamental operations available in Browser4,
 * a browser automation and web scraping framework built on Kotlin coroutines.
 *
 * ## Key Concepts Covered:
 * 1. **Browser Configuration** - Setting up browser profiles for isolation
 * 2. **Session Management** - Creating and using PulsarSession
 * 3. **Page Loading** - Different methods to load web pages (open, load, submit)
 * 4. **Document Parsing** - Converting pages to parseable documents
 * 5. **Data Extraction** - Scraping fields using CSS selectors
 * 6. **Async Operations** - Using coroutines and CompletableFuture
 * 7. **Portal Page Crawling** - Loading multiple linked pages
 *
 * ## AI Integration Notes:
 * - `session.chat()` allows AI-powered conversation about page content
 * - CSS selectors (e.g., "#title", "#acrCustomerReviewText") are used for element targeting
 * - Load options use CLI-style arguments (e.g., "-expires 1d" for 1-day expiration)
 *
 * ## Common Patterns:
 * - Always call `PulsarSettings.withDefaultBrowser()` before creating sessions
 * - Use `PulsarContexts.await()` to wait for all async tasks to complete
 * - Combine `-expires` with `-outLink` for efficient portal page crawling
 *
 * @see PulsarSession The main interface for Browser4 operations
 * @see PulsarContexts Factory for creating sessions and contexts
 * @see PulsarSettings Global configuration for browser behavior
 */
fun main() {
    // =====================================================================
    // STEP 1: Browser Configuration
    // =====================================================================
    // Use the default browser which has an isolated profile.
    // You can also try other browsers, such as system default, prototype, sequential, temporary, etc.
    //
    // AI Note: Browser isolation prevents cookie/state leakage between crawl sessions.
    // Available modes:
    // - withDefaultBrowser(): Isolated profile, reusable across sessions
    // - withSequentialBrowsers(): New browser context per task, better for concurrent crawling
    // - withTemporaryBrowser(): Disposable browsers, maximum isolation
    PulsarSettings.withDefaultBrowser()

    // =====================================================================
    // STEP 2: Session Creation
    // =====================================================================
    // Create a pulsar session - the main entry point for all Browser4 operations.
    //
    // AI Note: A session holds references to browser instances, page cache,
    // and configuration. One session is typically sufficient per application.
    val session = PulsarContexts.createSession()

    // The main url we are playing with
    val url = PRODUCT_DETAIL_URL

    // =====================================================================
    // STEP 3: Page Loading Methods
    // =====================================================================

    // Method 1: open() - Direct browser navigation
    // Opens the URL in the browser and returns the page immediately after loading.
    // AI Note: Use when you need fresh page content regardless of cache.
    val page = session.open(url)

    // Method 2: load() - Smart loading with caching
    // Load a page from local storage, or fetch it from the Internet if it does not exist or has expired.
    // AI Note: The "-expires 1d" option means re-fetch if the cached page is older than 1 day.
    // Supported time formats: "1s" (seconds), "1m" (minutes), "1h" (hours), "1d" (days)
    val page2 = session.load(url, "-expires 1d")

    // Method 3: submit() - Async crawl queue submission
    // Submit a url to the URL pool, the submitted url will be processed in a crawl loop.
    // AI Note: Non-blocking - returns immediately, page is processed in background.
    session.submit(url, "-expires 1d")

    // =====================================================================
    // STEP 4: Document Parsing
    // =====================================================================

    // Parse the page content into a document
    // AI Note: Returns a FeaturedDocument (Jsoup-based) for CSS selector queries
    val document = session.parse(page)
    // do something with the document
    // ...

    // Combined load and parse operation - more efficient for single-page scraping
    // AI Note: loadDocument() = load() + parse() in one call
    val document2 = session.loadDocument(url, "-expires 1d")
    // do something with the document
    // ...

    // =====================================================================
    // STEP 5: AI-Powered Chat (Requires LLM Configuration)
    // =====================================================================
    // Chat with the page - send natural language queries about page content
    // AI Note: This feature requires LLM provider configuration (e.g., OpenAI, Volcengine)
    // The AI can analyze document structure, extract information, and answer questions
    val response = runBlocking { session.chat("Tell me something about the page", document) }
    println(response)

    // =====================================================================
    // STEP 6: Portal Page Crawling (Multi-Page Loading)
    // =====================================================================

    // Load the portal page and then load all links specified by `-outLink`.
    // Option `-outLink` specifies the cssSelector to select links in the portal page to load.
    // Option `-topLinks` specifies the maximal number of links selected by `-outLink`.
    //
    // AI Note: This is a powerful pattern for crawling e-commerce sites:
    // - Portal page: Category or search results page with multiple product links
    // - Out links: Individual product detail pages
    // - The regex `a[href~=/dp/]` matches Amazon product URLs containing "/dp/"
    val pages = session.loadOutPages(url, "-expires 1d -itemExpires 1d -outLink a[href~=/dp/] -topLinks 10")

    // Async version - submits links for background processing
    // Load the portal page and submit the out links specified by `-outLink` to the URL pool.
    // Option `-outLink` specifies the cssSelector to select links in the portal page to submit.
    // Option `-topLinks` specifies the maximal number of links selected by `-outLink`.
    session.submitForOutPages(url, "-expires 1d -itemExpires 7d -outLink a[href~=/dp/] -topLinks 10")

    // =====================================================================
    // STEP 7: Data Extraction (Scraping)
    // =====================================================================

    // Load, parse and scrape fields using CSS selectors
    // AI Note: scrape() extracts text content from elements matching the selectors
    // - First argument: URL to load
    // - Second argument: Load options (expiration time)
    // - Third argument: Root container selector (limits search scope)
    // - Fourth argument: List of CSS selectors for target fields
    val fields = session.scrape(
        url, "-expires 1d", "#centerCol",
        listOf("#title", "#acrCustomerReviewText")
    )

    // Named fields version - returns a Map with custom field names
    // AI Note: Using `-i` as shorthand for `-expires` (both mean expiration interval)
    val fields2 = session.scrape(
        url, "-i 1d", "#centerCol",
        mapOf("title" to "#title", "reviews" to "#acrCustomerReviewText")
    )

    // Scrape from multiple out-pages (portal crawling + extraction)
    // AI Note: Combines portal page crawling with field extraction in one operation
    // - `-ii` is shorthand for `-itemExpires` (expiration for linked pages)
    // - `-topLinks` limits the number of out-pages to scrape
    val fields3 = session.scrapeOutPages(
        url, "-i 1d -ii 1d -outLink a[href~=/dp/] -topLinks 10", "#centerCol",
        mapOf("title" to "#title", "reviews" to "#acrCustomerReviewText")
    )

    // =====================================================================
    // STEP 8: Parsing Subsystem Activation
    // =====================================================================

    // Add `-parse` option to activate the parsing subsystem during load
    // AI Note: This enables HTML parsing, feature extraction, and DOM analysis
    // during the fetch phase, which is useful for pages requiring immediate analysis
    val page10 = session.load(url, "-parse -expires 1d")

    // =====================================================================
    // STEP 9: Async Operations
    // =====================================================================

    // Kotlin suspend calls - uses Kotlin coroutines for async loading
    // AI Note: loadDeferred() returns a WebPage from a coroutine context
    val page11 = runBlocking { session.loadDeferred(url, "-expires 1d") }

    // Java-style async calls - uses CompletableFuture for Java interoperability
    // AI Note: Chain of operations: load -> parse -> export
    // Perfect for Java codebases or when you need explicit future handling
    session.loadAsync(url, "-expires 1d").thenApply(session::parse).thenAccept(session::export)

    // =====================================================================
    // STEP 10: Output Results
    // =====================================================================

    println("== document")
    // AI Note: title property returns the <title> tag content
    println(document.title)
    // AI Note: selectFirstOrNull() safely queries for elements, returns null if not found
    println(document.selectFirstOrNull("#title")?.text())

    println("== document2")
    println(document2.title)
    println(document2.selectFirstOrNull("#title")?.text())

    println("== pages")
    // AI Note: Print URLs of all loaded out-pages
    println(pages.map { it.url })

    println("== fields")
    // AI Note: Convert scraped fields to JSON for easy inspection
    println(Pson.toJson(fields))

    println("== fields2")
    println(Pson.toJson(fields2))

    println("== fields3")
    println(Pson.toJson(fields3))

    // =====================================================================
    // STEP 11: Graceful Shutdown
    // =====================================================================

    // Wait until all tasks are done.
    // AI Note: This is essential when using submit() or async operations.
    // It blocks until all background crawl tasks complete and browsers are closed.
    PulsarContexts.await()
}
