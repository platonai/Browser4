package ai.platon.pulsar.human.manual

import ai.platon.pulsar.common.LinkExtractors
import ai.platon.pulsar.dom.FeaturedDocument
import ai.platon.pulsar.persist.WebPage
import ai.platon.pulsar.skeleton.PulsarSettings
import ai.platon.pulsar.skeleton.context.PulsarContexts
import ai.platon.pulsar.skeleton.workflow.common.url.ParsableHyperlink

/**
 * # Continuous Crawling - Self-Expanding Crawl Queue
 *
 * This example demonstrates how to build a continuous crawler that discovers
 * and queues new URLs from crawled pages, creating an expanding crawl frontier.
 *
 * ## Key Concepts:
 * 1. **Crawl Frontier** - The queue of URLs waiting to be crawled
 * 2. **Link Discovery** - Extracting new links from parsed pages
 * 3. **Recursive Submission** - Adding discovered links back to the queue
 * 4. **Browser Mode Selection** - Choosing appropriate browser isolation
 *
 * ## Continuous Crawling Pattern:
 * ```
 * seed URLs → fetch → parse → extract links → submit to queue → repeat
 *                                    ↑                              │
 *                                    └──────────────────────────────┘
 * ```
 *
 * ## Browser Modes for Continuous Crawling:
 * - **Sequential Browsers**: Create new browser context for each batch
 *   - Better isolation between crawl batches
 *   - Prevents state accumulation (cookies, cache)
 *   - Recommended for large-scale crawling
 * - **Temporary Browsers**: Disposable browsers for maximum isolation
 *   - Best for crawling sites that detect bot behavior
 *
 * ## AI Integration Notes:
 * - The parse handler is the ideal place for AI analysis
 * - Use AI to classify page types and filter irrelevant links
 * - AI can help detect and skip duplicate content
 *
 * @see ParsableHyperlink URL with automatic parsing callback
 * @see PulsarSettings.withSequentialBrowsers() Recommended for continuous crawling
 */
fun main() {
    // =====================================================================
    // STEP 1: Browser Configuration for Continuous Crawling
    // =====================================================================
    // For continuous crawls, you'd better use sequential browsers or temporary browsers
    //
    // AI Note: Sequential browsers create fresh browser contexts, preventing:
    // - Cookie accumulation that might trigger anti-bot measures
    // - Memory leaks from long-running browser sessions
    // - State pollution between different crawl batches
    //
    // maxOpenTabs(8) limits concurrent browser tabs to prevent resource exhaustion
    PulsarSettings.withSequentialBrowsers().maxOpenTabs(8)

    // Create a context (not just a session) to access advanced crawl features
    // AI Note: PulsarContext provides access to submitAll() and crawl queue management
    val context = PulsarContexts.create()

    // =====================================================================
    // STEP 2: Define the Parse Handler (Core Crawl Logic)
    // =====================================================================
    //
    // This handler is called for every page after it's loaded and parsed.
    // It's the heart of the continuous crawler where you:
    // - Extract data from the current page
    // - Discover and submit new links for crawling
    //
    // AI Note: This is where you would add AI-powered analysis:
    // - Content classification
    // - Entity extraction
    // - Relevance scoring
    val parseHandler = { _: WebPage, document: FeaturedDocument ->
        // do something wonderful with the document
        // AI Note: document.title gets the <title> tag, baseURI gets the page URL
        println(document.title + "\t|\t" + document.baseURI)

        // extract more links from the document
        // AI Note: selectHyperlinks() finds all matching <a> tags and returns Hyperlink objects
        // The CSS selector `a[href~=/dp/]` matches links containing "/dp/" (Amazon product pages)
        context.submitAll(document.selectHyperlinks("a[href~=/dp/]"))
    }

    // =====================================================================
    // STEP 3: Create Seed URLs with Parse Handlers
    // =====================================================================
    //
    // Load seed URLs from a resource file and wrap them with ParsableHyperlink
    //
    // AI Note: Each URL gets the same parseHandler, but you could customize
    // handlers based on URL patterns for different page types
    //
    // change to seeds100.txt to crawl more
    val urls = LinkExtractors.fromResource("seeds100.txt")
        .map { ParsableHyperlink("$it -refresh", parseHandler) }

    // =====================================================================
    // STEP 4: Submit and Wait
    // =====================================================================
    //
    // Submit all seed URLs and wait for the crawl to complete
    //
    // AI Note: The crawler will:
    // 1. Process seed URLs
    // 2. Call parseHandler for each page
    // 3. parseHandler submits discovered links
    // 4. Those links are processed, leading to more discoveries
    // 5. Continues until no more URLs to process
    context.submitAll(urls).await()
}
