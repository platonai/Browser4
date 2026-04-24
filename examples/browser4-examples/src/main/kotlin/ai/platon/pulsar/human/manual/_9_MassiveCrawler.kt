package ai.platon.pulsar.human.manual

import ai.platon.pulsar.common.LinkExtractors
import ai.platon.pulsar.common.Priority13
import ai.platon.pulsar.common.collect.DelayUrl
import ai.platon.pulsar.dom.FeaturedDocument
import ai.platon.pulsar.persist.WebPage
import ai.platon.pulsar.skeleton.PulsarSettings
import ai.platon.pulsar.skeleton.context.PulsarContexts
import ai.platon.pulsar.skeleton.workflow.common.url.ParsableHyperlink
import java.time.Duration

/**
 * # Massive Scale Crawling - URL Pool Management and Priority Queues
 *
 * This example demonstrates Browser4's advanced URL pool management capabilities
 * for handling massive-scale crawling with different priority levels and scheduling.
 *
 * ## Key Concepts:
 * 1. **URL Pool** - Central queue management for crawl URLs
 * 2. **Priority Queues** - Different priority levels for URL processing
 * 3. **Reentrant vs Non-Reentrant** - URL revisitation policies
 * 4. **Delayed URLs** - Scheduling URLs for future crawling
 * 5. **Browser Contexts** - Multiple isolated browser instances
 *
 * ## URL Pool Architecture:
 * ```
 * URL Pool
 * ├── realTimeCache (highest priority) - Immediate processing
 * ├── highestCache - Very high priority
 * ├── higher2Cache - High priority
 * ├── normalCache - Default priority
 * ├── lower2Cache - Low priority
 * └── delayCache - Scheduled for future
 * ```
 *
 * ## Queue Types:
 * - **reentrantQueue** - URL can be added again (for periodic re-crawling)
 * - **nonReentrantQueue** - URL processed only once
 * - **nReentrantQueue** - URL can be added N times
 *
 * ## Browser Configuration:
 * - `maxBrowserContexts(4)` - Up to 4 isolated browser instances
 * - `maxOpenTabs(8)` - Up to 8 tabs per browser context
 * - `headless()` - Run without visible UI (production mode)
 *
 * ## AI Integration Notes:
 * - Use priority queues for AI-guided URL prioritization
 * - AI can score URLs and assign appropriate priorities
 * - Delay queue useful for rate-limited sites
 *
 * @see Priority13 Priority levels from HIGHEST to LOWEST
 * @see DelayUrl URL with delayed start time
 * @see GlobalCache The central cache containing URL pool
 */
fun main() {
    // =====================================================================
    // STEP 1: Configure Browser for Massive Crawling
    // =====================================================================
    //
    // Configure for parallel crawling with multiple browser contexts:
    // - withSequentialBrowsers(): Fresh contexts to prevent state accumulation
    // - maxBrowserContexts(4): Run 4 isolated browser instances
    // - maxOpenTabs(8): 8 concurrent tabs per context (32 total tabs)
    // - headless(): No visible browser window (faster, less resources)
    //
    // AI Note: Total concurrent pages = contexts × tabs = 4 × 8 = 32
    PulsarSettings.withSequentialBrowsers().maxBrowserContexts(4).maxOpenTabs(8).headless()

    // Create session to access URL pool
    val session = PulsarContexts.createSession()

    // Get the global URL pool for direct queue manipulation
    // AI Note: urlPool is the central hub for all URL scheduling
    val urlPool = session.context.globalCache.urlPool

    // =====================================================================
    // STEP 2: Define Parse Handler
    // =====================================================================
    //
    // Handler called for each page after loading and parsing.
    // AI Note: This is where you'd add data extraction, storage, and AI analysis.
    val parseHandler = { _: WebPage, document: FeaturedDocument ->
        // do something wonderful with the document
        // AI Note: In production, store to database, extract data, analyze content
        println(document.title + "\t|\t" + document.baseURI)
    }

    // Load URLs from resource file with parse handler
    val urls = LinkExtractors.fromResource("seeds100.txt").map { ParsableHyperlink(it, parseHandler) }

    // Get 5 sample URLs for demonstration
    // AI Note: Destructuring declaration for multiple variables
    val (url1, url2, url3, url4, url5) = urls.shuffled()

    // =====================================================================
    // STEP 3: Basic Submission Methods
    // =====================================================================
    //
    // Different ways to submit URLs to the crawl queue:

    // Submit single URL with default options
    session.submit(url1)

    // Submit with "-refresh" to force re-fetch ignoring cache
    session.submit(url2, "-refresh")

    // Submit with expiration: re-fetch if cached version is older than 30 seconds
    session.submit(url3, "-i 30s")

    // =====================================================================
    // STEP 4: Bulk Submission
    // =====================================================================
    //
    // Submit many URLs at once - efficient for large crawls.

    // Submit all URLs with default options
    session.submitAll(urls)

    // feel free to submit millions of urls here
    // AI Note: Browser4 can handle millions of URLs in the queue
    // They're processed based on priority and available resources
    session.submitAll(urls, "-i 7d")  // Re-fetch if older than 7 days

    // =====================================================================
    // STEP 5: Direct URL Pool Manipulation (Advanced)
    // =====================================================================
    //
    // For fine-grained control, add URLs directly to specific queues.

    // Add to default pool (normal priority)
    urlPool.add(url4)

    // Add with custom priority (HIGHER4 = 4th highest priority)
    // AI Note: Priority13 has 13 levels from HIGHEST to LOWEST
    urlPool.add(url5.apply { priority = Priority13.HIGHER4.value })

    // =====================================================================
    // STEP 6: Priority Queue Selection (Expert)
    // =====================================================================
    //
    // Add URLs directly to specific priority queues for precise control.

    // High priority, allow re-submission (reentrant)
    // AI Note: reentrantQueue allows the same URL to be added multiple times
    urlPool.highestCache.reentrantQueue.add(url1)

    // Higher priority, process only once (non-reentrant)
    // AI Note: nonReentrantQueue ignores duplicate URLs
    urlPool.higher2Cache.nonReentrantQueue.add(url2)

    // Lower priority, limited re-entry
    // AI Note: nReentrantQueue allows N re-entries (configurable)
    urlPool.lower2Cache.nReentrantQueue.add(url3)

    // =====================================================================
    // STEP 7: Real-Time and Delayed Processing
    // =====================================================================

    // Highest priority - immediate processing
    // AI Note: Use for time-sensitive URLs that need immediate attention
    urlPool.realTimeCache.reentrantQueue.add(url4)

    // Delayed processing - will start 2 hours later
    // AI Note: Useful for:
    // - Rate limiting compliance
    // - Scheduled re-crawls
    // - Off-peak processing
    urlPool.delayCache.add(DelayUrl(url5, Duration.ofHours(2)))

    // =====================================================================
    // STEP 8: Wait for Completion
    // =====================================================================

    // wait for all tasks to be finished.
    // AI Note: Blocks until all queues are empty and all pages processed
    PulsarContexts.await()
}
