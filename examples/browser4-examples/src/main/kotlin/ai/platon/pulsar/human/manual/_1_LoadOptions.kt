package ai.platon.pulsar.human.manual

import ai.platon.pulsar.skeleton.PulsarSettings
import ai.platon.pulsar.skeleton.context.PulsarContexts

/**
 * # Load Options Reference - Comprehensive Guide to Browser4 URL Parameters
 *
 * This example demonstrates the complete set of load options (CLI-style arguments)
 * that control how Browser4 fetches, caches, and processes web pages.
 *
 * ## Load Option Categories:
 * 1. **Expiration Control** - When to re-fetch cached pages
 * 2. **Failure Handling** - How to handle failed fetches
 * 3. **Quality Gates** - Minimum requirements for page content
 * 4. **Retry Control** - How many times to retry failed fetches
 * 5. **Portal Page Options** - Options for crawling linked pages
 *
 * ## Common Option Patterns:
 * - Short form options: `-i` (expires), `-ii` (itemExpires)
 * - Time duration formats: "10s" (seconds), "1m" (minutes), "1h" (hours), "1d" (days)
 * - ISO-8601 format: "PnDTnHnMn.nS" (e.g., "PT1H30M" for 1 hour 30 minutes)
 *
 * ## AI Integration Notes:
 * - Options can be combined: `-expires 1d -requireSize 300000`
 * - Use `-parse` when you need immediate document analysis
 * - Use `-refresh` to force a complete re-fetch ignoring all caches
 *
 * @see LoadOptions The class that parses these CLI-style arguments
 */
fun main() {
    // =====================================================================
    // SETUP: Browser and Session Configuration
    // =====================================================================
    // Use the default browser which has an isolated profile.
    // You can also try other browsers, such as system default, prototype, sequential, temporary, etc.
    PulsarSettings.withDefaultBrowser()

    // Create a pulsar session
    val session = PulsarContexts.createSession()
    // The main url we are playing with
    val url = PRODUCT_DETAIL_URL

    // =====================================================================
    // CATEGORY 1: Expiration Control Options
    // =====================================================================

    // Load a page, or fetch it if the expiry time exceeds.
    //
    // Option `-expires` specifies the expiry time and has a short form `-i`.
    //
    // The expiry time support both ISO-8601 standard and hadoop time duration format:
    // 1. ISO-8601 standard : PnDTnHnMn.nS
    // 2. Hadoop time duration format : Valid units are : ns, us, ms, s, m, h, d.
    //
    // AI Note: Expiration is calculated from the last fetch time stored in the database.
    // If (currentTime - lastFetchTime) > expirationDuration, the page is re-fetched.
    var page = session.load(url, "-expires 10s")
    page = session.load(url, "-i 10s")  // Short form: -i is equivalent to -expires

    // =====================================================================
    // CATEGORY 2: Failure Handling Options
    // =====================================================================

    // Add option `-ignoreFailure` to force re-fetch ignoring all failures even if `fetchRetries` exceeds the maximal.
    // AI Note: Use this when you want to retry a URL that previously failed permanently.
    page = session.load(url, "-ignoreFailure -expires 0s")

    // Add option `-refresh` to force re-fetch ignoring all failures and set `fetchRetries` to be 0,
    // `-refresh` = `-ignoreFailure -expires 0s` and `page.fetchRetires = 0`.
    // AI Note: This is the "nuclear option" for getting fresh content - ignores all caches and failures.
    page = session.load(url, "-refresh")

    // =====================================================================
    // CATEGORY 3: Quality Gate Options
    // =====================================================================

    // Option `-requireSize` to specifies the minimal page size, the page should be re-fetch if the
    // last page size is smaller than that.
    // AI Note: Useful for detecting blocked/captcha pages that return small responses.
    // A normal product page is typically 300KB-1MB, blocked pages are often < 10KB.
    page = session.load(url, "-requireSize 300000")

    // Option `-requireImages` specifies the minimal image count, the page should be re-fetch if the image count of the
    // last fetched page is smaller than that.
    // AI Note: Product pages typically have many images. Low image count may indicate lazy loading issues.
    page = session.load(url, "-requireImages 10")

    // Option `-requireAnchors` specifies the minimal anchor count, the page should be re-fetch if
    // the anchor count of the last fetched page is smaller than that.
    // AI Note: Useful for detecting partially loaded pages or blocked content.
    page = session.load(url, "-requireAnchors 100")

    // =====================================================================
    // CATEGORY 4: Deadline and Parsing Options
    // =====================================================================

    // If the deadline is exceeded, the task should be abandoned as soon as possible.
    // AI Note: Use for time-sensitive crawling jobs where stale data is useless.
    page = session.load(url, "-deadline 2022-04-15T18:36:54.941Z")

    // Add option `-parse` to activate the parsing subsystem.
    // AI Note: Enables HTML parsing, DOM analysis, and feature extraction during fetch.
    page = session.load(url, "-parse")

    // Option `-storeContent` tells the system to save the page content to the storage.
    // AI Note: By default, Browser4 may not store raw HTML to save space. Use this to persist content.
    page = session.load(url, "-storeContent")

    // =====================================================================
    // CATEGORY 5: Retry Control Options
    // =====================================================================

    // Option `-nMaxRetry` specifies the maximal number of retries in the Main loop, and if it's still failed
    // after this number, the page will be marked as `Gone`. A retry will be triggered when a RETRY(1601) status code
    // is returned.
    // AI Note: Main loop retries happen across multiple crawl cycles (not immediate).
    page = session.load(url, "-nMaxRetry 3")

    // Option `-nJitRetry` specifies the maximal number of retries for the load phase, which will be triggered
    // when a RETRY(1601) status is returned.
    // AI Note: JIT (Just-In-Time) retries happen immediately during the same load() call.
    page = session.load(url, "-nJitRetry 2")

    // =====================================================================
    // CATEGORY 6: Portal Page Options (Multi-Page Crawling)
    // =====================================================================

    // Load or fetch the portal page, and then load or fetch the out links selected by `-outLink`.
    //
    // Portal Page Options:
    // 1. `-expires` specifies the expiry time of the portal page and has a short form `-i`.
    // 2. `-outLink` specifies the cssSelector for links in the portal page to load.
    // 3. `-topLinks` specifies the maximal number of links selected by `-outLink`.
    //
    // Item Page (Out Link) Options:
    // 1. `-itemExpires` specifies the expiry time of item pages and has a short form `-ii`.
    // 2. `-itemRequireSize` specifies the minimal page size for item pages.
    // 3. `-itemRequireImages` specifies the minimal number of images in item pages.
    // 4. `-itemRequireAnchors` specifies the minimal number of anchors in item pages.
    //
    // AI Note: This is the primary pattern for e-commerce scraping:
    // - Portal page = Category listing, search results
    // - Out links = Individual product/item pages
    // - Quality gates ensure you get fully loaded product pages
    var pages = session.loadOutPages(url, "-expires 10s" +
            " -itemExpires 7d" +
            " -outLink a[href~=item]" +
            " -topLinks 10" +
            " -itemExpires 1d" +
            " -itemRequireSize 600000" +
            " -itemRequireImages 5" +
            " -itemRequireAnchors 50"
    )

    // =====================================================================
    // CLEANUP: Wait for Background Tasks
    // =====================================================================

    // Wait until all tasks are done.
    // AI Note: Important when using submit() or async operations to ensure clean shutdown.
    session.context.await()
}
