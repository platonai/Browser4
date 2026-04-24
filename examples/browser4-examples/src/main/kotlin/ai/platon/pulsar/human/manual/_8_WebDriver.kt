package ai.platon.pulsar.human.manual

import ai.platon.pulsar.agentic.AgenticSession
import ai.platon.pulsar.agentic.context.AgenticContexts
import ai.platon.pulsar.skeleton.common.options.LoadOptions
import ai.platon.pulsar.skeleton.workflow.fetch.driver.WebDriver
import org.slf4j.LoggerFactory

/**
 * # WebDriver API Reference - Low-Level Browser Control
 *
 * This example demonstrates direct use of the WebDriver API for fine-grained
 * browser automation. WebDriver is the low-level interface for browser control,
 * providing access to all browser automation capabilities.
 *
 * ## Key Concepts:
 * 1. **WebDriver** - Interface for browser automation (click, type, scroll, etc.)
 * 2. **AgenticSession** - Enhanced session with AI capabilities
 * 3. **Event Handlers** - Hook points for WebDriver interactions
 *
 * ## Main WebDriver Methods Demonstrated:
 *
 * ### Navigation & State
 * - `bringToFront()` - Focus the browser tab
 * - `currentUrl()` - Get current page URL
 *
 * ### Scrolling
 * - `scrollToTop()` - Scroll to page top
 * - `scrollToMiddle(ratio)` - Scroll to percentage of page height
 * - `scrollToBottom()` - Scroll to page bottom
 * - `mouseWheel(deltaX, deltaY)` - Simulate Playwright-style mouse wheel scrolling
 * - `mouseMove(x, y)` / `mouseDown()` / `mouseUp()` - Simulate low-level mouse input
 * - `keyDown(key)` / `keyUp(key)` - Simulate low-level keyboard input
 *
 * ### Element Interaction
 * - `exists(selector)` - Check if element exists
 * - `click(selector)` - Click an element
 * - `type(selector, text)` - Type text into input
 * - `boundingBox(selector)` - Get element dimensions
 *
 * ### Data Extraction
 * - `selectFirstTextOrNull(selector)` - Get element's text content
 * - `evaluate(expression)` - Execute JavaScript and return result
 *
 * ### Screenshots
 * - `screenshot(selector)` - Screenshot of specific element
 *
 * ### AI Integration (Agentic Features)
 * - `session.bindDriver(driver)` - Connect WebDriver to AI session
 * - `session.plainActs(instruction)` - Execute natural language commands
 *
 * ## AI Integration Notes:
 * - Use `plainActs()` to give natural language instructions to the browser
 * - Requires LLM configuration for AI-powered interactions
 * - Combine programmatic control with AI for robust automation
 *
 * @see WebDriver The main browser automation interface
 * @see AgenticSession Session with AI capabilities
 */
internal class WebDriverDemo(private val session: AgenticSession = AgenticContexts.createSession()) {
    private val logger = LoggerFactory.getLogger(this.javaClass)

    // =====================================================================
    // Selector Definitions
    // =====================================================================
    //
    // CSS selectors for target elements on the page.
    // AI Note: These selectors are Amazon-specific; adjust for other sites.

    // Search form selectors
    private val searchBoxSelector = ".form input[type=text]"
    private val searchBoxSubmit = ".form input[type=submit]"

    // Product page field selectors
    val fieldSelectors = mutableMapOf(
        "title" to "#productTitle",           // Product title
        "reviews" to "#acrCustomerReviewText",  // Review count
        "prodDetails" to "#prodDetails"        // Product details table
    )

    /**
     * Creates LoadOptions with WebDriver interaction handlers.
     *
     * AI Note: This method demonstrates attaching multiple handlers
     * to the same event (onDocumentFullyLoaded). They execute in order.
     *
     * @param args Load options string
     * @return Configured LoadOptions with interaction handlers
     */
    fun options(args: String): LoadOptions {
        val options = session.options(args)

        val be = options.eventHandlers.browseEventHandlers

        // =====================================================================
        // Handler 1: Basic Element Interactions
        // =====================================================================
        // Demonstrates click, text extraction, and typing
        be.onDocumentFullyLoaded.addLast { page, driver ->
            fieldSelectors.values.forEach { interact1(it, driver) }
        }

        // =====================================================================
        // Handler 2: Advanced WebDriver Showcase
        // =====================================================================
        // Demonstrates scrolling, screenshots, JS evaluation, AI commands
        be.onDocumentFullyLoaded.addLast { page, driver ->
            interact2(driver)
        }

        // Log completion
        be.onDidInteract.addLast { page, driver ->
            logger.info("Did the interaction")
        }

        return options
    }

    /**
     * Basic interaction pattern: click, read text, type.
     *
     * AI Note: This is the fundamental RPA pattern for form-like interactions.
     *
     * @param selector CSS selector for target element
     * @param driver WebDriver instance
     */
    private suspend fun interact1(selector: String, driver: WebDriver) {
        if (driver.exists(selector)) {
            // Click the element
            println("click $selector ...")
            driver.click(selector)

            // Extract text content
            println("select first text by $selector ...")
            var text = driver.selectFirstTextOrNull(selector) ?: "no-text"
            text = text.replace("\\s+", " ").trim().take(5)

            // Type extracted text into search box
            println("type `$text` in $searchBoxSelector ...")
            driver.type(searchBoxSelector, text)
        }
    }

    /**
     * Advanced WebDriver showcase demonstrating all major capabilities.
     *
     * AI Note: This method is a comprehensive reference for WebDriver operations.
     * In production, you'd use only the methods you need.
     *
     * @param driver WebDriver instance
     */
    private suspend fun interact2(driver: WebDriver) {
        val selector = "#productTitle"

        // =====================================================================
        // Focus Management
        // =====================================================================
        // Bring the browser tab to foreground
        // AI Note: Useful for multi-tab scenarios
        println("bring the page to front ...")
        driver.bringToFront()

        // =====================================================================
        // Scrolling Operations
        // =====================================================================

        // Scroll to bottom and get page dimensions
        println("scroll to the bottom of the page ...")
        driver.scrollToBottom()
        // boundingBox returns the element's position and size
        // AI Note: Useful for calculating click coordinates
        println("bounding box of body: " + driver.boundingBox("body"))

        // Scroll to middle (50% of page height)
        // AI Note: ratio 0.5 = 50%, 0.0 = top, 1.0 = bottom
        println("scroll to the middle of the page ...")
        driver.scrollToMiddle(0.5)

        // =====================================================================
        // Click and Type
        // =====================================================================

        // Click the product title element
        println("click $selector ...")
        driver.click(selector)

        // Extract text and use in another field
        println("query text of $selector ...")
        var text = driver.selectFirstTextOrNull(selector) ?: "no-text"
        text = text.substring(1, 4)
        println("type `$text` in $searchBoxSelector")
        driver.type(searchBoxSelector, text)

        // =====================================================================
        // Screenshot Capture
        // =====================================================================
        // Capture screenshot of specific element (not full page)
        // AI Note: Returns base64 encoded image data
        println("capture screenshot over $selector ...")
        driver.screenshot(selector)

        // =====================================================================
        // JavaScript Evaluation
        // =====================================================================
        // Execute JavaScript and get result
        // AI Note: Can run any JavaScript expression
        // Use for: DOM manipulation, data extraction, custom logic
        println("evaluate 1 + 1 ...")
        val result = driver.evaluate("1 + 1")
        require(result is Int)
        println("evaluate 1 + 1 returns $result")

        // =====================================================================
        // Mouse Wheel Scrolling
        // =====================================================================
        // Simulate smooth scrolling with mouse wheel
        // AI Note: More natural than instant scroll, useful for lazy-loading pages
        println("wheel down for 5 times ...")
        repeat(5) { driver.mouseWheel(0.0, 150.0) }

        println("hold Shift while moving the pointer ...")
        driver.keyDown("Shift")
        driver.mouseMove(100.0, 200.0)
        driver.mouseDown()
        driver.mouseUp()
        driver.keyUp("Shift")

        // Return to top
        println("scroll to top ...")
        repeat(5) { driver.mouseWheel(0.0, 150.0) }
        driver.scrollToTop()

        // =====================================================================
        // Form Submission (Search Example)
        // =====================================================================
        // Complete a search flow: type query → click submit → observe navigation
        println("search ...")
        text = "Vincent Willem van Gogh"
        println("type `$text` in $searchBoxSelector")
        driver.type(searchBoxSelector, text)
        driver.click(searchBoxSubmit)
        val url = driver.currentUrl()
        println("page navigated to $url")

        // =====================================================================
        // AI-Powered Actions (Requires LLM Configuration)
        // =====================================================================
        // Use natural language to control the browser
        // AI Note: This is the agentic feature - giving plain language instructions
        // Requires LLM provider configuration (OpenAI, Volcengine, etc.)
        println("Using plain language, tell the browser to click $selector ...")
        // Only works when LLM is configured
        session.bindDriver(driver)
        session.act("scroll to middle")
    }
}

/**
 * Main entry point demonstrating comprehensive WebDriver usage.
 *
 * AI Note: This example uses AgenticSession which provides:
 * - All standard PulsarSession features
 * - AI-powered browser control via natural language
 * - WebDriver binding for direct AI interaction
 */
fun main() {
    val url = PRODUCT_DETAIL_URL
    // "-refresh -parse" forces fresh load and enables parsing
    val args = "-refresh -parse"

    // Set the system to work with single page applications (SPA)
    // AI Note: AgenticSession is the AI-enhanced version of PulsarSession
    val session = AgenticContexts.createSession()

    // Create demo crawler with WebDriver interaction handlers
    val crawler = WebDriverDemo(session)

    // Load page - WebDriver interactions happen during load via event handlers
    session.load(url, crawler.options(args))
}
