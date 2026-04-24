package ai.platon.pulsar.human.manual

import ai.platon.pulsar.skeleton.PulsarSettings
import ai.platon.pulsar.skeleton.common.options.LoadOptions
import ai.platon.pulsar.skeleton.context.PulsarContexts.createSession
import ai.platon.pulsar.skeleton.workflow.fetch.driver.WebDriver
import ai.platon.pulsar.skeleton.session.PulsarSession
import org.slf4j.LoggerFactory

/**
 * # RPA (Robotic Process Automation) with Browser4
 *
 * This example demonstrates how to use Browser4 for RPA tasks - automating
 * browser interactions like clicking, typing, and scrolling using the WebDriver API.
 *
 * ## Key Concepts:
 * 1. **WebDriver** - The interface for browser automation
 * 2. **Event Handlers** - Hook into the page load lifecycle for interactions
 * 3. **CSS Selectors** - Target elements for interaction
 * 4. **Suspend Functions** - All WebDriver operations are async (suspend)
 *
 * ## Common WebDriver Operations:
 * - `exists(selector)` - Check if an element exists
 * - `click(selector)` - Click an element
 * - `type(selector, text)` - Type text into an input field
 * - `selectFirstTextOrNull(selector)` - Get text content of an element
 * - `scrollToTop/Middle/Bottom()` - Scroll the page
 *
 * ## RPA + Scraping Pattern:
 * This example combines RPA interactions with data extraction:
 * 1. Load the page
 * 2. Interact with elements (click, type)
 * 3. Extract data using CSS selectors
 *
 * ## AI Integration Notes:
 * - Combine RPA with AI for intelligent automation
 * - Use AI to determine which elements to interact with
 * - AI can handle unexpected page states
 *
 * @see WebDriver The browser automation interface
 * @see BrowseEventHandlers Events for interaction hooks
 */
internal class RPACrawler(private val session: PulsarSession = createSession()) {
    private val logger = LoggerFactory.getLogger(this.javaClass)

    // =====================================================================
    // Selector Definitions
    // =====================================================================
    //
    // CSS selectors for target elements on the page.
    // AI Note: These selectors are specific to Amazon product pages.
    // For other sites, you'll need to inspect the HTML and adjust selectors.

    // Search form selectors (for demonstration of typing)
    private val searchBoxSelector = ".form input[type=text]"
    private val searchBoxSubmit = ".form input[type=submit]"

    // Product page field selectors (Amazon-specific)
    // AI Note: These are used both for interaction and data extraction
    val fieldSelectors = mutableMapOf(
        "title" to "#productTitle",           // Product title
        "reviews" to "#acrCustomerReviewText",  // Review count
        "prodDetails" to "#prodDetails"        // Product details table
    )

    /**
     * Creates LoadOptions with RPA event handlers attached.
     *
     * AI Note: This method demonstrates the pattern of attaching
     * suspend functions (interaction logic) to browse events.
     * The handlers will execute during the page loading process.
     *
     * @param args Load options string (e.g., "-refresh -parse")
     * @return Configured LoadOptions with interaction handlers
     */
    fun options(args: String): LoadOptions {
        val options = session.options(args)

        // Get reference to browse event handlers
        val be = options.eventHandlers.browseEventHandlers

        // =====================================================================
        // Register RPA Interaction Handler
        // =====================================================================
        //
        // onDocumentFullyLoaded is triggered when the page's DOM is ready.
        // This is the ideal time for most interactions.
        //
        // AI Note: The handler receives:
        // - page: The WebPage being loaded
        // - driver: The WebDriver for browser control
        be.onDocumentFullyLoaded.addLast { page, driver ->
            // Interact with each field selector
            fieldSelectors.values.forEach { interact(it, driver) }
        }

        // Optional: Log when interaction phase completes
        be.onDidInteract.addLast { page, driver ->
            logger.info("Did the interaction")
        }

        return options
    }

    /**
     * Performs interaction with a page element.
     *
     * This method demonstrates a typical RPA pattern:
     * 1. Check if element exists
     * 2. Click the element
     * 3. Extract text from the element
     * 4. Use extracted text for further actions (type into search box)
     *
     * AI Note: All WebDriver methods are suspend functions,
     * meaning they're non-blocking and coroutine-friendly.
     *
     * @param selector CSS selector for the target element
     * @param driver WebDriver instance for browser control
     */
    private suspend fun interact(selector: String, driver: WebDriver) {
        // Local reference to search box selector
        val searchBoxSelector = ".form input[type=text]"

        // Check if element exists before interacting
        // AI Note: Always check existence to avoid NoSuchElementException
        if (driver.exists(selector)) {
            // Step 1: Click the element
            driver.click(selector)

            // Step 2: Extract text from the element
            val text = driver.selectFirstTextOrNull(selector) ?: "no-text"

            // Step 3: Use extracted text in search box (first 3 chars)
            // AI Note: substring(1, 4) takes chars at index 1, 2, 3
            driver.type(searchBoxSelector, text.substring(1, 4))

            // Log the interaction
            logger.info("{} clicked", selector)
        }
    }
}

/**
 * Main entry point demonstrating RPA-style browser automation with scraping.
 *
 * AI Note: This example shows the powerful combination of:
 * 1. RPA interactions (clicking, typing) during page load
 * 2. Data extraction using CSS selectors after interactions
 */
fun main() {
    // Use the default browser which has an isolated profile.
    // You can also try other browsers, such as system default, prototype, sequential, temporary, etc.
    PulsarSettings.withDefaultBrowser()

    val url = PRODUCT_DETAIL_URL
    // "-refresh -parse" forces fresh page load and enables parsing
    val args = "-refresh -parse"

    // Create session and RPA crawler
    val session = createSession()
    val crawler = RPACrawler(session)

    // Load page with RPA interactions, then scrape fields
    // AI Note: scrape() combines:
    // 1. load() - with event handlers executing RPA logic
    // 2. parse() - converting to document
    // 3. extract() - getting text from selectors
    val fields = session.scrape(url, crawler.options(args), crawler.fieldSelectors)

    // Print extracted data
    println(fields)
}
