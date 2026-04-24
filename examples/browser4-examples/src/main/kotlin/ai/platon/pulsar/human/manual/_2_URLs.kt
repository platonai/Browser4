package ai.platon.pulsar.human.manual

import ai.platon.pulsar.common.urls.Hyperlink
import ai.platon.pulsar.common.urls.PlainUrl
import ai.platon.pulsar.persist.WebPage
import ai.platon.pulsar.skeleton.PulsarSettings
import ai.platon.pulsar.skeleton.context.PulsarContexts
import ai.platon.pulsar.skeleton.workflow.common.url.CompletableListenableHyperlink
import ai.platon.pulsar.skeleton.workflow.common.url.ListenableHyperlink
import ai.platon.pulsar.skeleton.workflow.common.url.ParsableHyperlink

/**
 * # URL Types Reference - Different URL Wrappers in Browser4
 *
 * Browser4 provides several URL wrapper classes that add different capabilities
 * to basic URL strings. This example demonstrates when and how to use each type.
 *
 * ## URL Type Hierarchy (from simple to complex):
 * 1. **PlainUrl** - Basic URL with load arguments (simplest)
 * 2. **Hyperlink** - URL with anchor text and portal-page options
 * 3. **ParsableHyperlink** - URL with automatic parsing callback
 * 4. **ListenableHyperlink** - URL with full event handler support
 * 5. **CompletableListenableHyperlink** - URL with CompletableFuture completion support
 *
 * ## When to Use Each Type:
 * - **PlainUrl**: Simple page loading with options
 * - **Hyperlink**: Portal page crawling with out-link selection
 * - **ParsableHyperlink**: When you need a callback after page parsing
 * - **ListenableHyperlink**: When you need fine-grained control over the crawl lifecycle
 * - **CompletableListenableHyperlink**: When you need to wait for completion with Future API
 *
 * ## AI Integration Notes:
 * - All URL types support load options as constructor arguments
 * - Event handlers can be used for AI-powered analysis during crawling
 * - Use ListenableHyperlink when you need to interact with WebDriver during fetch
 *
 * @see PlainUrl Basic URL wrapper with arguments
 * @see Hyperlink URL with anchor text and metadata
 * @see ParsableHyperlink URL with parse callback
 * @see ListenableHyperlink URL with event handlers
 * @see CompletableListenableHyperlink URL with CompletableFuture support
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
    // TYPE 1: PlainUrl - Simple URL with Arguments
    // =====================================================================
    //
    // PlainUrl is the simplest URL wrapper. It combines:
    // - A URL string
    // - Load arguments (CLI-style options)
    //
    // AI Note: Use PlainUrl when you just need to load a page with specific options.
    // It's lightweight and suitable for simple use cases.

    // Load a page from local storage, or fetch it from the Internet if it does not exist or has expired
    val plainUrl = PlainUrl(url, "-expires 10s")
    val page = session.load(plainUrl)
    println("PlainUrl loaded | " + page.url)

    // Submit a url to the URL pool, the submitted url will be processed in a crawl loop
    session.submit(plainUrl)

    // =====================================================================
    // TYPE 2: Hyperlink - URL with Anchor Text and Portal Options
    // =====================================================================
    //
    // Hyperlink extends PlainUrl with:
    // - Anchor text (the text that appears in <a> tags)
    // - Reference URL (the page where this link was found)
    // - Portal page options for out-link crawling
    //
    // AI Note: Use Hyperlink when crawling portal pages with multiple out-links.
    // The anchor text can be useful for understanding link context.

    // Load the portal page and then load all links specified by `-outLink`.
    // Option `-outLink` specifies the cssSelector to select links in the portal page to load.
    // Option `-topLinks` specifies the maximal number of links selected by `-outLink`.
    val hyperlink = Hyperlink(url, "", args = "-expires 10s -itemExpires 10s")
    val pages = session.loadOutPages(hyperlink, "-outLink a[href~=/dp/] -topLinks 5")
    println("Hyperlink's out pages are loaded | " + pages.size)

    // Load the portal page and submit the out links specified by `-outLink` to the URL pool.
    // Option `-outLink` specifies the cssSelector to select links in the portal page to submit.
    // Option `-topLinks` specifies the maximal number of links selected by `-outLink`.
    val hyperlink2 = Hyperlink(url, "", args = "-expires 1d -itemExpires 7d")
    session.submitForOutPages(hyperlink2, "-outLink a[href~=/dp/] -topLinks 5")

    // =====================================================================
    // TYPE 3: ParsableHyperlink - URL with Parse Callback
    // =====================================================================
    //
    // ParsableHyperlink automatically triggers parsing after fetch and
    // invokes a callback with the WebPage and FeaturedDocument.
    //
    // AI Note: Use ParsableHyperlink when you need to process parsed content
    // immediately after loading. Perfect for continuous crawling with data extraction.
    //
    // Callback signature: (WebPage, FeaturedDocument) -> Unit

    // Load a webpage and parse it into a document
    val parsableHyperlink = ParsableHyperlink(url) { p, doc ->
        // This callback is invoked after the page is loaded and parsed
        // AI Note: 'p' is the WebPage (raw page data), 'doc' is the parsed FeaturedDocument
        println("Parsed " + doc.baseURI)
    }
    val page2 = session.load(parsableHyperlink, "-expires 10s")
    println("ParsableHyperlink loaded | " + page2.url)

    // =====================================================================
    // TYPE 4: ListenableHyperlink - URL with Full Event Handler Support
    // =====================================================================
    //
    // ListenableHyperlink provides access to the complete event handler system:
    // - loadEventHandlers: Events during the load phase
    // - browseEventHandlers: Events during browser interaction
    // - crawlEventHandlers: Events during the crawl loop
    //
    // AI Note: Use ListenableHyperlink when you need fine-grained control,
    // such as custom JavaScript execution, waiting for specific elements,
    // or interacting with the page during the fetch process.

    // Load a ListenableHyperlink so we can register various event handlers
    val listenableLink = ListenableHyperlink(url, "")
    // Register a handler that runs after browser interaction is complete
    // AI Note: 'onDidInteract' is triggered after scrolling, clicking, and other interactions
    listenableLink.eventHandlers.browseEventHandlers.onDidInteract.addLast { pg, driver ->
        println("Interaction finished " + page.url)
    }
    val page3 = session.load(listenableLink, "-expires 10s")
    println("ListenableHyperlink loaded | " + page3.url)

    // =====================================================================
    // TYPE 5: CompletableListenableHyperlink - URL with Future Completion
    // =====================================================================
    //
    // CompletableListenableHyperlink combines ListenableHyperlink with
    // CompletableFuture support, allowing you to:
    // - Submit the URL to the crawl queue
    // - Wait for completion using .join() or .get()
    // - Chain operations using CompletableFuture methods
    //
    // AI Note: Use CompletableListenableHyperlink when you need to submit
    // a URL asynchronously but still want to wait for its completion.

    // Load a CompletableListenableHyperlink, so we can register various event handlers,
    // and we can wait for the execution to complete.
    val completableListenableHyperlink = CompletableListenableHyperlink<WebPage>(url).apply {
        // Complete the future when the page is loaded
        eventHandlers.loadEventHandlers.onLoaded.addLast { complete(it) }
    }
    // Submit to crawl queue (non-blocking)
    session.submit(completableListenableHyperlink, "-expires 10s")
    // Wait for completion (blocking) - returns the WebPage
    val page4 = completableListenableHyperlink.join()
    println("CompletableListenableHyperlink loaded | " + page4.url)

    // =====================================================================
    // CLEANUP: Wait for Background Tasks
    // =====================================================================

    // Wait until all tasks are done.
    PulsarContexts.await()
}
