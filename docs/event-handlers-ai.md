# EventHandlers: Comprehensive AI-Friendly Guide

## Table of Contents
1. [Overview](#overview)
2. [Architecture](#architecture)
3. [Event Handler Groups](#event-handler-groups)
4. [Complete API Reference](#complete-api-reference)
5. [Usage Patterns](#usage-patterns)
6. [Examples](#examples)
7. [Best Practices](#best-practices)
8. [Troubleshooting](#troubleshooting)

## Overview

EventHandlers in Browser4 provide a comprehensive mechanism to intercept and process events throughout the entire lifecycle of webpage loading, browsing, and crawling. They allow you to:

- **Monitor** page lifecycle events at granular stages
- **Intercept** and modify behavior before/after critical operations
- **Inject** custom logic into the browser automation workflow
- **Coordinate** complex multi-step interactions
- **Debug** and trace execution flow

### Key Concepts

- **Event Chains**: Multiple handlers can be chained together, executing in sequence
- **Event Groups**: Events are organized into three logical groups (Load, Browse, Crawl)
- **WebDriver Integration**: Browse events have access to the WebDriver for browser control
- **Non-blocking**: Handlers are designed to be non-blocking and can be async

## Architecture

### Hierarchy

```
PageEventHandlers (Top-level interface)
├── LoadEventHandlers (Page loading & parsing phase)
├── BrowseEventHandlers (Browser interaction phase)  
└── CrawlEventHandlers (Crawl iteration phase)
```

### Event Lifecycle Flow

```
┌─────────────────────────────────────────────────────────────────┐
│                     CRAWL PHASE (Start)                          │
├─────────────────────────────────────────────────────────────────┤
│ 1. crawl.onWillLoad           - Before loading URL              │
└─────────────────────────────────────────────────────────────────┘
                            ↓
┌─────────────────────────────────────────────────────────────────┐
│                      LOAD PHASE (Begin)                          │
├─────────────────────────────────────────────────────────────────┤
│ 2. load.onNormalize           - Normalize URL format            │
│ 3. load.onWillLoad            - Before initiating load          │
│ 4. load.onWillFetch           - Before fetching content         │
└─────────────────────────────────────────────────────────────────┘
                            ↓
┌─────────────────────────────────────────────────────────────────┐
│                    BROWSE PHASE (Browser)                        │
├─────────────────────────────────────────────────────────────────┤
│ 5. browse.onWillLaunchBrowser - Before browser starts           │
│ 6. browse.onBrowserLaunched   - After browser is ready          │
│ 7. browse.onWillNavigate      - Before navigation               │
│ 8. browse.onNavigated         - After URL loaded                │
│ 9. browse.onWillInteract      - Before interaction starts       │
│10. browse.onWillCheckDocumentState - Before checking DOM        │
│11. browse.onDocumentFullyLoaded - DOM fully loaded              │
│12. browse.onWillScroll        - Before scrolling                │
│13. browse.onDidScroll         - After scrolling                 │
│14. browse.onDocumentSteady    - Page stable, ready for action   │
│15. browse.onWillComputeFeature - Before feature extraction      │
│16. browse.onFeatureComputed   - After features extracted        │
│17. browse.onDidInteract       - After interaction complete      │
│18. browse.onWillStopTab       - Before closing tab              │
│19. browse.onTabStopped        - After tab closed                │
└─────────────────────────────────────────────────────────────────┘
                            ↓
┌─────────────────────────────────────────────────────────────────┐
│                   LOAD PHASE (Complete)                          │
├─────────────────────────────────────────────────────────────────┤
│20. load.onFetched             - After content fetched           │
│21. load.onWillParse           - Before parsing                  │
│22. load.onWillParseHTMLDocument - Before HTML parsing           │
│23. load.onHTMLDocumentParsed  - After HTML parsed               │
│24. load.onParsed              - After all parsing done          │
│25. load.onLoaded              - Page fully loaded               │
└─────────────────────────────────────────────────────────────────┘
                            ↓
┌─────────────────────────────────────────────────────────────────┐
│                     CRAWL PHASE (End)                            │
├─────────────────────────────────────────────────────────────────┤
│26. crawl.onLoaded             - After page loaded               │
└─────────────────────────────────────────────────────────────────┘
```

## Event Handler Groups

### 1. LoadEventHandlers

Handles events during the page loading and parsing phase. These events operate on the page content after it's retrieved.

**When to use**: When you need to process or transform page content, modify parsing behavior, or collect data from the parsed document.

**Key characteristics**:
- Operates on `WebPage` objects (the internal page representation)
- No direct browser access
- Suitable for content manipulation and data extraction
- Executes sequentially during page load

### 2. BrowseEventHandlers

Handles events during the browser interaction phase. These events have access to the WebDriver for direct browser control.

**When to use**: When you need to interact with the live browser, execute JavaScript, click elements, scroll pages, or perform dynamic actions.

**Key characteristics**:
- Has access to `WebDriver` for browser automation
- Can execute JavaScript in the browser context
- Supports async operations (coroutines)
- Ideal for dynamic interactions and RPA tasks

### 3. CrawlEventHandlers

Handles events at the crawl iteration level, before and after loading individual pages.

**When to use**: When you need to filter URLs, coordinate between pages, or perform pre/post-load operations at the crawl level.

**Key characteristics**:
- Operates at higher abstraction level
- Can filter or transform URLs before loading
- Coordinates multiple page loads
- Useful for crawl-wide logic

## Complete API Reference

### LoadEventHandlers

#### onNormalize
```kotlin
val onNormalize: UrlFilterEventHandler
```
**Signature**: `(String) -> String?`

**Purpose**: Normalize URL before processing. Can filter out unwanted URLs by returning null.

**Parameters**:
- `url: String` - The raw URL to normalize

**Returns**: Normalized URL string, or null to skip this URL

**Example**:
```kotlin
loadEventHandlers.onNormalize.addLast { url ->
    // Remove tracking parameters
    url.substringBefore('?')
}
```

**Use cases**:
- Remove URL fragments or query parameters
- Enforce URL format standards
- Filter out specific URL patterns
- Canonicalize URLs for deduplication

---

#### onWillLoad
```kotlin
val onWillLoad: UrlEventHandler
```
**Signature**: `(String) -> String?`

**Purpose**: Called immediately before loading begins. Last chance to modify or reject the URL.

**Parameters**:
- `url: String` - The normalized URL about to be loaded

**Returns**: Modified URL or null to skip loading

**Example**:
```kotlin
loadEventHandlers.onWillLoad.addLast { url ->
    logger.info("About to load: {}", url)
    url // Continue with this URL
}
```

**Use cases**:
- Logging and monitoring
- Last-minute URL validation
- Rate limiting checks
- Load scheduling decisions

---

#### onWillFetch
```kotlin
val onWillFetch: WebPageEventHandler
```
**Signature**: `(WebPage) -> Any?`

**Purpose**: Called before fetching the page content. Access to WebPage metadata.

**Parameters**:
- `page: WebPage` - The page object with metadata

**Returns**: Any value (typically ignored)

**Example**:
```kotlin
loadEventHandlers.onWillFetch.addLast { page ->
    page.headers["User-Agent"] = "CustomBot/1.0"
}
```

**Use cases**:
- Set custom HTTP headers
- Configure fetch options
- Pre-fetch validation
- Timing measurements

---

#### onFetched
```kotlin
val onFetched: WebPageEventHandler
```
**Signature**: `(WebPage) -> Any?`

**Purpose**: Called after page content is fetched, before parsing.

**Parameters**:
- `page: WebPage` - The page with fetched content

**Returns**: Any value (typically ignored)

**Example**:
```kotlin
loadEventHandlers.onFetched.addLast { page ->
    logger.info("Fetched {} bytes from {}", page.contentLength, page.url)
}
```

**Use cases**:
- Validate fetch success
- Log fetch metrics
- Pre-process raw content
- Trigger dependent fetches

---

#### onWillParse
```kotlin
val onWillParse: WebPageEventHandler
```
**Signature**: `(WebPage) -> Any?`

**Purpose**: Called before parsing the page content.

**Parameters**:
- `page: WebPage` - The page about to be parsed

**Returns**: Any value (typically ignored)

**Example**:
```kotlin
loadEventHandlers.onWillParse.addLast { page ->
    // Prepare for parsing
    page.encoding = "UTF-8"
}
```

**Use cases**:
- Set parsing options
- Content preprocessing
- Encoding detection
- Parser selection

---

#### onWillParseHTMLDocument
```kotlin
val onWillParseHTMLDocument: WebPageEventHandler
```
**Signature**: `(WebPage) -> Any?`

**Purpose**: Called specifically before HTML document parsing.

**Parameters**:
- `page: WebPage` - The page with HTML content

**Returns**: Any value (typically ignored)

**Example**:
```kotlin
loadEventHandlers.onWillParseHTMLDocument.addLast { page ->
    logger.info("Parsing HTML document: {}", page.url)
}
```

**Use cases**:
- HTML-specific preprocessing
- Configure HTML parser options
- Validate HTML content
- Set up DOM processing

---

#### onHTMLDocumentParsed
```kotlin
val onHTMLDocumentParsed: HTMLDocumentEventHandler
```
**Signature**: `(WebPage, FeaturedDocument) -> Any?`

**Purpose**: Called after HTML document is parsed. Access to parsed DOM.

**Parameters**:
- `page: WebPage` - The page object
- `document: FeaturedDocument` - The parsed DOM document

**Returns**: Any value (can return extracted data)

**Example**:
```kotlin
loadEventHandlers.onHTMLDocumentParsed.addLast { page, document ->
    val title = document.selectFirstOrNull("h1")?.text()
    logger.info("Page title: {}", title)
}
```

**Use cases**:
- **Data extraction** from parsed DOM
- **Link collection** for crawling
- **Content analysis** and classification
- **DOM manipulation** before storage

---

#### onParsed
```kotlin
val onParsed: WebPageEventHandler
```
**Signature**: `(WebPage) -> Any?`

**Purpose**: Called after all parsing is complete.

**Parameters**:
- `page: WebPage` - The fully parsed page

**Returns**: Any value (typically ignored)

**Example**:
```kotlin
loadEventHandlers.onParsed.addLast { page ->
    logger.info("Parsing complete for: {}", page.url)
}
```

**Use cases**:
- Post-parsing validation
- Finalize extracted data
- Trigger downstream processing
- Update crawl statistics

---

#### onLoaded
```kotlin
val onLoaded: WebPageEventHandler
```
**Signature**: `(WebPage) -> Any?`

**Purpose**: Called when page loading is completely finished.

**Parameters**:
- `page: WebPage` - The fully loaded page

**Returns**: Any value (typically ignored)

**Example**:
```kotlin
loadEventHandlers.onLoaded.addLast { page ->
    logger.info("Page fully loaded: {}", page.url)
}
```

**Use cases**:
- Final processing
- Store results to database
- Update crawl state
- Trigger callbacks

---

### BrowseEventHandlers

#### onWillLaunchBrowser
```kotlin
val onWillLaunchBrowser: WebPageEventHandler
```
**Signature**: `(WebPage) -> Any?`

**Purpose**: Called before launching browser for this page.

**Parameters**:
- `page: WebPage` - The page that will be browsed

**Returns**: Any value (typically ignored)

**Example**:
```kotlin
browseEventHandlers.onWillLaunchBrowser.addLast { page ->
    logger.info("Launching browser for: {}", page.url)
}
```

**Use cases**:
- Browser launch logging
- Pre-launch configuration
- Resource allocation
- Cost tracking

---

#### onBrowserLaunched
```kotlin
val onBrowserLaunched: WebPageWebDriverEventHandler
```
**Signature**: `suspend (WebPage, WebDriver) -> Any?`

**Purpose**: Called after browser is launched and ready. First point with WebDriver access.

**Parameters**:
- `page: WebPage` - The page to browse
- `driver: WebDriver` - The WebDriver instance

**Returns**: Any value (typically ignored)

**Example**:
```kotlin
browseEventHandlers.onBrowserLaunched.addLast { page, driver ->
    // Warm up browser or set initial state
    driver.evaluate("console.log('Browser ready')")
}
```

**Use cases**:
- **Browser warm-up** operations
- **Set cookies** or local storage
- **Inject scripts** for monitoring
- **Configure viewport** size

---

#### onWillFetch (Browse)
```kotlin
val onWillFetch: WebPageWebDriverEventHandler
```
**Signature**: `suspend (WebPage, WebDriver) -> Any?`

**Purpose**: Called before fetching via browser (before navigation).

**Parameters**:
- `page: WebPage` - The page to fetch
- `driver: WebDriver` - The WebDriver instance

**Returns**: Any value (typically ignored)

**Example**:
```kotlin
browseEventHandlers.onWillFetch.addLast { page, driver ->
    // Wait for referrer to complete
    delay(1000)
}
```

**Use cases**:
- Wait for previous page
- Set up network interception
- Configure browser behavior
- Implement rate limiting

---

#### onFetched (Browse)
```kotlin
val onFetched: WebPageWebDriverEventHandler
```
**Signature**: `suspend (WebPage, WebDriver) -> Any?`

**Purpose**: Called after browser fetch completes.

**Parameters**:
- `page: WebPage` - The fetched page
- `driver: WebDriver` - The WebDriver instance

**Returns**: Any value (typically ignored)

**Example**:
```kotlin
browseEventHandlers.onFetched.addLast { page, driver ->
    logger.info("Browser fetch complete: {}", driver.currentUrl())
}
```

**Use cases**:
- Validate fetch success
- Check for errors
- Capture network logs
- Measure timing

---

#### onWillNavigate
```kotlin
val onWillNavigate: WebPageWebDriverEventHandler
```
**Signature**: `suspend (WebPage, WebDriver) -> Any?`

**Purpose**: Called before navigating to the URL in browser.

**Parameters**:
- `page: WebPage` - The page to navigate to
- `driver: WebDriver` - The WebDriver instance

**Returns**: Any value (typically ignored)

**Example**:
```kotlin
browseEventHandlers.onWillNavigate.addLast { page, driver ->
    logger.info("Navigating to: {}", page.url)
}
```

**Use cases**:
- Log navigation
- Pre-navigation setup
- Cancel navigation conditionally
- Measure navigation timing

---

#### onNavigated
```kotlin
val onNavigated: WebPageWebDriverEventHandler
```
**Signature**: `suspend (WebPage, WebDriver) -> Any?`

**Purpose**: Called after navigation completes (URL loaded in browser).

**Parameters**:
- `page: WebPage` - The navigated page
- `driver: WebDriver` - The WebDriver instance

**Returns**: Any value (typically ignored)

**Example**:
```kotlin
browseEventHandlers.onNavigated.addLast { page, driver ->
    logger.info("Navigation complete. Current URL: {}", driver.currentUrl())
}
```

**Use cases**:
- Verify navigation success
- Check for redirects
- Capture initial page state
- Handle navigation errors

---

#### onWillInteract
```kotlin
val onWillInteract: WebPageWebDriverEventHandler
```
**Signature**: `suspend (WebPage, WebDriver) -> Any?`

**Purpose**: Called before beginning page interactions (scrolling, clicking, etc.).

**Parameters**:
- `page: WebPage` - The page to interact with
- `driver: WebDriver` - The WebDriver instance

**Returns**: Any value (typically ignored)

**Example**:
```kotlin
browseEventHandlers.onWillInteract.addLast { page, driver ->
    logger.info("Starting interactions with: {}", page.url)
}
```

**Use cases**:
- Pre-interaction setup
- Load custom scripts
- Initialize interaction state
- Set interaction parameters

---

#### onWillCheckDocumentState
```kotlin
val onWillCheckDocumentState: WebPageWebDriverEventHandler
```
**Signature**: `suspend (WebPage, WebDriver) -> Any?`

**Purpose**: Called before checking if document is fully loaded.

**Parameters**:
- `page: WebPage` - The page being checked
- `driver: WebDriver` - The WebDriver instance

**Returns**: Any value (typically ignored)

**Example**:
```kotlin
browseEventHandlers.onWillCheckDocumentState.addLast { page, driver ->
    // Wait a bit before checking
    delay(500)
}
```

**Use cases**:
- Pre-check delays
- Custom ready state logic
- Performance monitoring
- Debug logging

---

#### onDocumentFullyLoaded
```kotlin
val onDocumentFullyLoaded: WebPageWebDriverEventHandler
```
**Signature**: `suspend (WebPage, WebDriver) -> Any?`

**Purpose**: Called when document is determined to be fully loaded (custom algorithm, not standard readyState).

**Parameters**:
- `page: WebPage` - The fully loaded page
- `driver: WebDriver` - The WebDriver instance

**Returns**: Any value (typically ignored)

**Example**:
```kotlin
browseEventHandlers.onDocumentFullyLoaded.addLast { page, driver ->
    logger.info("Document fully loaded")
    // Take initial screenshot
}
```

**Use cases**:
- Capture fully loaded state
- Start dependent operations
- Measure load performance
- Take screenshots

---

#### onWillScroll
```kotlin
val onWillScroll: WebPageWebDriverEventHandler
```
**Signature**: `suspend (WebPage, WebDriver) -> Any?`

**Purpose**: Called before scrolling the page.

**Parameters**:
- `page: WebPage` - The page to scroll
- `driver: WebDriver` - The WebDriver instance

**Returns**: Any value (typically ignored)

**Example**:
```kotlin
browseEventHandlers.onWillScroll.addLast { page, driver ->
    logger.info("About to scroll page")
}
```

**Use cases**:
- Pre-scroll setup
- Configure scroll behavior
- Measure scroll timing
- Handle fixed elements

---

#### onDidScroll
```kotlin
val onDidScroll: WebPageWebDriverEventHandler
```
**Signature**: `suspend (WebPage, WebDriver) -> Any?`

**Purpose**: Called after scrolling the page.

**Parameters**:
- `page: WebPage` - The scrolled page
- `driver: WebDriver` - The WebDriver instance

**Returns**: Any value (typically ignored)

**Example**:
```kotlin
browseEventHandlers.onDidScroll.addLast { page, driver ->
    val scrollHeight = driver.evaluate("document.body.scrollHeight")
    logger.info("Scrolled page, height: {}", scrollHeight)
}
```

**Use cases**:
- Verify scroll completed
- Check for lazy-loaded content
- Capture scroll metrics
- Trigger scroll-dependent actions

---

#### onDocumentSteady
```kotlin
val onDocumentSteady: WebPageWebDriverEventHandler
```
**Signature**: `suspend (WebPage, WebDriver) -> Any?`

**Purpose**: Called when document is steady (no more changes expected). **Ideal point for custom actions.**

**Parameters**:
- `page: WebPage` - The steady page
- `driver: WebDriver` - The WebDriver instance

**Returns**: Any value (typically ignored)

**Example**:
```kotlin
browseEventHandlers.onDocumentSteady.addLast { page, driver ->
    // Perfect time for custom interactions
    driver.click(".load-more-button")
    driver.waitForSelector(".product-item")
}
```

**Use cases**:
- **Custom click actions** (load more, expand sections)
- **Form filling** and submission
- **Modal handling** (close popups)
- **Data extraction** from stable DOM
- **RPA tasks** requiring stable page

**⭐ Most commonly used handler for custom automation tasks**

---

#### onWillComputeFeature
```kotlin
val onWillComputeFeature: WebPageWebDriverEventHandler
```
**Signature**: `suspend (WebPage, WebDriver) -> Any?`

**Purpose**: Called before computing page features (metadata, structure analysis).

**Parameters**:
- `page: WebPage` - The page to analyze
- `driver: WebDriver` - The WebDriver instance

**Returns**: Any value (typically ignored)

**Example**:
```kotlin
browseEventHandlers.onWillComputeFeature.addLast { page, driver ->
    logger.info("Computing page features")
}
```

**Use cases**:
- Pre-computation setup
- Custom feature selection
- Performance monitoring
- Debug logging

---

#### onFeatureComputed
```kotlin
val onFeatureComputed: WebPageWebDriverEventHandler
```
**Signature**: `suspend (WebPage, WebDriver) -> Any?`

**Purpose**: Called after page features are computed.

**Parameters**:
- `page: WebPage` - The analyzed page (features available)
- `driver: WebDriver` - The WebDriver instance

**Returns**: Any value (typically ignored)

**Example**:
```kotlin
browseEventHandlers.onFeatureComputed.addLast { page, driver ->
    logger.info("Features computed, page ready for extraction")
}
```

**Use cases**:
- Access computed features
- Feature-based decisions
- Quality assessment
- Store feature data

---

#### onDidInteract
```kotlin
val onDidInteract: WebPageWebDriverEventHandler
```
**Signature**: `suspend (WebPage, WebDriver) -> Any?`

**Purpose**: Called after all interactions are complete (scrolling, features, custom actions).

**Parameters**:
- `page: WebPage` - The interacted page
- `driver: WebDriver` - The WebDriver instance

**Returns**: Any value (typically ignored)

**Example**:
```kotlin
browseEventHandlers.onDidInteract.addLast { page, driver ->
    logger.info("All interactions complete")
    // Final screenshot or validation
}
```

**Use cases**:
- Final validation
- Capture final state
- Measure total interaction time
- Cleanup before closing

---

#### onWillStopTab
```kotlin
val onWillStopTab: WebPageWebDriverEventHandler
```
**Signature**: `suspend (WebPage, WebDriver) -> Any?`

**Purpose**: Called before closing the browser tab.

**Parameters**:
- `page: WebPage` - The page being closed
- `driver: WebDriver` - The WebDriver instance

**Returns**: Any value (typically ignored)

**Example**:
```kotlin
browseEventHandlers.onWillStopTab.addLast { page, driver ->
    // Last chance to extract data
    val finalUrl = driver.currentUrl()
    logger.info("Closing tab: {}", finalUrl)
}
```

**Use cases**:
- Final data capture
- Cleanup operations
- Resource release
- Performance metrics

---

#### onTabStopped
```kotlin
val onTabStopped: WebPageWebDriverEventHandler
```
**Signature**: `suspend (WebPage, WebDriver) -> Any?`

**Purpose**: Called after browser tab is stopped/closed.

**Parameters**:
- `page: WebPage` - The closed page
- `driver: WebDriver` - The WebDriver instance (may be invalid)

**Returns**: Any value (typically ignored)

**Example**:
```kotlin
browseEventHandlers.onTabStopped.addLast { page, driver ->
    logger.info("Tab closed for: {}", page.url)
}
```

**Use cases**:
- Post-close cleanup
- Statistics updates
- Resource tracking
- Error handling

---

### CrawlEventHandlers

#### onWillLoad
```kotlin
val onWillLoad: UrlAwareEventHandler
```
**Signature**: `(UrlAware) -> UrlAware?`

**Purpose**: Called before loading a URL at the crawl level. Can filter or transform URLs.

**Parameters**:
- `url: UrlAware` - The URL-aware object to load

**Returns**: Modified UrlAware or null to skip

**Example**:
```kotlin
crawlEventHandlers.onWillLoad.addLast { url ->
    if (url.spec.contains("ads")) null // Skip ads
    else url
}
```

**Use cases**:
- **URL filtering** at crawl level
- **Priority scheduling** decisions
- **Rate limiting** enforcement
- **Crawl scope** validation

---

#### onLoaded
```kotlin
val onLoaded: UrlAwareWebPageEventHandler
```
**Signature**: `(UrlAware, WebPage?) -> Any?`

**Purpose**: Called after URL is loaded at the crawl level.

**Parameters**:
- `url: UrlAware` - The URL that was loaded
- `page: WebPage?` - The loaded page (may be null if load failed)

**Returns**: Any value (typically ignored)

**Example**:
```kotlin
crawlEventHandlers.onLoaded.addLast { url, page ->
    if (page != null) {
        logger.info("Successfully loaded: {}", url.spec)
    } else {
        logger.warn("Failed to load: {}", url.spec)
    }
}
```

**Use cases**:
- **Crawl statistics** tracking
- **Success/failure** handling
- **Queue management** decisions
- **Downstream** task triggering

---

## Usage Patterns

### Pattern 1: Simple Event Logging

```kotlin
class LoggingEventHandlers : DefaultPageEventHandlers() {
    init {
        loadEventHandlers.onLoaded.addLast { page ->
            logger.info("Loaded: {}", page.url)
        }
        
        browseEventHandlers.onDocumentSteady.addLast { page, driver ->
            logger.info("Page steady: {}", page.url)
        }
    }
}
```

### Pattern 2: Custom Browser Interactions

```kotlin
class InteractiveEventHandlers : DefaultPageEventHandlers() {
    init {
        browseEventHandlers.onDocumentSteady.addLast { page, driver ->
            // Click "Load More" buttons until none found
            while (driver.exists(".load-more")) {
                driver.click(".load-more")
                driver.waitForSelector(".new-items")
                delay(1000)
            }
        }
    }
}
```

### Pattern 3: Data Extraction

```kotlin
class ExtractionEventHandlers : DefaultPageEventHandlers() {
    val products = mutableListOf<Product>()
    
    init {
        loadEventHandlers.onHTMLDocumentParsed.addLast { page, document ->
            document.select(".product").forEach { element ->
                products.add(
                    Product(
                        name = element.selectFirst(".name")?.text(),
                        price = element.selectFirst(".price")?.text()
                    )
                )
            }
        }
    }
}
```

### Pattern 4: Login Handling

```kotlin
class LoginEventHandlers : DefaultPageEventHandlers() {
    init {
        browseEventHandlers.onBrowserLaunched.addLast { page, driver ->
            if (requiresLogin(page.url)) {
                performLogin(driver)
            }
        }
    }
    
    private suspend fun performLogin(driver: WebDriver) {
        driver.navigateTo("https://example.com/login")
        driver.type("#username", "myuser")
        driver.type("#password", "mypass")
        driver.click("#login-button")
        driver.waitForNavigation()
    }
}
```

### Pattern 5: Error Handling

```kotlin
class RobustEventHandlers : DefaultPageEventHandlers() {
    init {
        browseEventHandlers.onNavigated.addLast { page, driver ->
            val currentUrl = driver.currentUrl()
            if (currentUrl.contains("/error") || currentUrl.contains("/404")) {
                logger.error("Navigation error: {}", currentUrl)
                // Could throw exception or handle gracefully
            }
        }
    }
}
```

### Pattern 6: Performance Monitoring

```kotlin
class PerformanceEventHandlers : DefaultPageEventHandlers() {
    private val timings = mutableMapOf<String, Long>()
    
    init {
        browseEventHandlers.onWillNavigate.addLast { page, driver ->
            timings["navigate-start"] = System.currentTimeMillis()
        }
        
        browseEventHandlers.onDocumentFullyLoaded.addLast { page, driver ->
            val loadTime = System.currentTimeMillis() - timings["navigate-start"]!!
            logger.info("Page load time: {} ms", loadTime)
        }
    }
}
```

### Pattern 7: Modal/Popup Handling

```kotlin
class ModalHandlerEventHandlers : DefaultPageEventHandlers() {
    init {
        browseEventHandlers.onDocumentSteady.addLast { page, driver ->
            // Close cookie consent
            if (driver.exists("#cookie-consent")) {
                driver.click("#cookie-consent .accept")
                delay(500)
            }
            
            // Close newsletter popup
            if (driver.exists(".newsletter-popup")) {
                driver.click(".newsletter-popup .close")
                delay(500)
            }
        }
    }
}
```

### Pattern 8: Chaining Multiple Handlers

```kotlin
val session = PulsarContexts.createSession()

// Create base handlers
val loggingHandlers = LoggingEventHandlers()
val modalHandlers = ModalHandlerEventHandlers()

// Chain them together
val combinedHandlers = loggingHandlers.chain(modalHandlers)

// Use combined handlers
val link = ListenableHyperlink(
    url = "https://example.com",
    eventHandlers = combinedHandlers
)
```

## Examples

### Complete Example: E-commerce Product Scraper

```kotlin
package ai.platon.pulsar.examples

import ai.platon.pulsar.skeleton.context.PulsarContexts
import ai.platon.pulsar.skeleton.crawl.common.url.ListenableHyperlink
import ai.platon.pulsar.skeleton.crawl.event.impl.DefaultPageEventHandlers
import org.slf4j.LoggerFactory

data class Product(
    val name: String?,
    val price: String?,
    val imageUrl: String?,
    val rating: String?
)

/**
 * Comprehensive event handler for scraping e-commerce product pages.
 * Demonstrates:
 * - Modal handling
 * - Infinite scroll
 * - Data extraction
 * - Performance tracking
 */
class EcommerceScraperHandlers : DefaultPageEventHandlers() {
    private val logger = LoggerFactory.getLogger(javaClass)
    val products = mutableListOf<Product>()
    private var startTime = 0L
    
    init {
        // Track timing
        browseEventHandlers.onWillNavigate.addLast { page, driver ->
            startTime = System.currentTimeMillis()
            logger.info("Starting scrape: {}", page.url)
        }
        
        // Close modals/popups when page is stable
        browseEventHandlers.onDocumentSteady.addLast { page, driver ->
            // Close cookie consent
            if (driver.exists("#cookie-banner")) {
                driver.click("#cookie-banner .accept")
                delay(500)
            }
            
            // Scroll to load all products (infinite scroll)
            var previousHeight = 0
            var currentHeight = driver.evaluate("document.body.scrollHeight", 0)
            var attempts = 0
            
            while (previousHeight < currentHeight && attempts < 10) {
                driver.scrollToBottom()
                delay(2000) // Wait for new items to load
                previousHeight = currentHeight
                currentHeight = driver.evaluate("document.body.scrollHeight", 0)
                attempts++
                logger.info("Scroll attempt {}, height: {}", attempts, currentHeight)
            }
            
            logger.info("Finished scrolling after {} attempts", attempts)
        }
        
        // Extract product data from parsed DOM
        loadEventHandlers.onHTMLDocumentParsed.addLast { page, document ->
            val productElements = document.select(".product-card")
            logger.info("Found {} products", productElements.size)
            
            productElements.forEach { element ->
                val product = Product(
                    name = element.selectFirstOrNull(".product-name")?.text(),
                    price = element.selectFirstOrNull(".product-price")?.text(),
                    imageUrl = element.selectFirstOrNull(".product-image")?.attr("src"),
                    rating = element.selectFirstOrNull(".product-rating")?.text()
                )
                products.add(product)
            }
            
            logger.info("Extracted {} products", products.size)
        }
        
        // Log completion stats
        crawlEventHandlers.onLoaded.addLast { url, page ->
            val elapsed = System.currentTimeMillis() - startTime
            logger.info("Scrape complete: {} products in {} ms", products.size, elapsed)
        }
    }
}

fun main() {
    val session = PulsarContexts.createSession()
    val handlers = EcommerceScraperHandlers()
    
    val link = ListenableHyperlink(
        url = "https://example.com/products",
        args = "-refresh -parse",
        eventHandlers = handlers
    )
    
    session.submit(link)
    PulsarContexts.await()
    
    // Access extracted data
    handlers.products.forEach { product ->
        println("${product.name}: ${product.price}")
    }
}
```

### Example: Login and Navigate

```kotlin
class LoginAndScrapeHandlers(
    private val username: String,
    private val password: String
) : DefaultPageEventHandlers() {
    
    private var isLoggedIn = false
    
    init {
        // Perform login once when browser is ready
        browseEventHandlers.onBrowserLaunched.addLast { page, driver ->
            if (!isLoggedIn) {
                logger.info("Performing login...")
                driver.navigateTo("https://example.com/login")
                driver.waitForNavigation()
                
                driver.type("#username", username)
                driver.type("#password", password)
                driver.click("#login-button")
                
                driver.waitForSelector(".user-menu", Duration.ofSeconds(10))
                isLoggedIn = true
                logger.info("Login successful")
            }
        }
        
        // Navigate to target page after login
        browseEventHandlers.onWillNavigate.addLast { page, driver ->
            if (!isLoggedIn) {
                logger.warn("Not logged in yet, waiting...")
                // Could wait or throw exception
            }
        }
    }
}
```

## Best Practices

### 1. Handler Ordering
- Handlers execute in the order they're added (FIFO)
- Use `addFirst()` for high-priority handlers
- Use `addLast()` for normal handlers (default)
- Chain handlers rather than creating complex single handlers

### 2. Performance
- Keep handlers lightweight and fast
- Avoid blocking operations in handlers
- Use async/await for I/O operations in browse handlers
- Don't perform heavy computation in event handlers

### 3. Error Handling
- Always handle exceptions in your handlers
- Use try-catch blocks for risky operations
- Log errors appropriately
- Don't let one handler's error break the chain

```kotlin
loadEventHandlers.onLoaded.addLast { page ->
    try {
        // Your logic here
    } catch (e: Exception) {
        logger.error("Handler error for {}: {}", page.url, e.message)
        // Don't rethrow unless you want to stop processing
    }
}
```

### 4. State Management
- Store state in handler instance variables
- Use thread-safe collections for concurrent access
- Clean up state in onLoaded/onTabStopped
- Consider using correlation IDs for tracking

### 5. Logging
- Use structured logging with placeholders
- Log at appropriate levels (debug, info, warn, error)
- Include relevant context (URL, timing, etc.)
- Don't log sensitive data

```kotlin
// Good
logger.info("Loaded page in {} ms | url={}", elapsed, url)

// Bad - string concatenation, sensitive data
logger.info("Loaded " + url + " with password " + password)
```

### 6. WebDriver Usage
- Check element existence before interacting: `driver.exists(selector)`
- Use appropriate waits: `driver.waitForSelector()`, `driver.waitForNavigation()`
- Handle timeouts gracefully
- Don't forget delays for dynamic content: `delay(milliseconds)`

### 7. Return Values
- Most handlers can return `null` or `Unit`
- URL handlers should return the URL to continue, null to skip
- Document handlers can return extracted data
- Return values can be used for handler chaining logic

### 8. Testing
- Test handlers in isolation when possible
- Use mock pages and drivers for unit testing
- Test handler chains to verify execution order
- Monitor handler performance in production

## Troubleshooting

### Issue: Handler not being called

**Possible causes:**
1. Handler registered on wrong event
2. Page type doesn't trigger that event (e.g., no browse events if not using browser)
3. Handler throws exception silently
4. Handler chain broken by null return

**Solutions:**
- Add logging to verify handler registration
- Check that page is loaded with appropriate options (e.g., `-parse` for HTML parsing)
- Wrap handler logic in try-catch
- Use `PrintFlowEventHandlers` to trace event sequence

### Issue: Handlers execute in wrong order

**Cause:** Using `addFirst()` vs `addLast()` incorrectly

**Solution:** Review handler registration order and use `addLast()` for normal priority

### Issue: WebDriver operations fail

**Possible causes:**
1. Element not loaded yet
2. Wrong selector
3. Element not visible/interactive
4. Browser context lost

**Solutions:**
```kotlin
// Add waits
driver.waitForSelector(".element", Duration.ofSeconds(10))

// Check existence first
if (driver.exists(".element")) {
    driver.click(".element")
}

// Verify page state
val currentUrl = driver.currentUrl()
logger.info("Current URL: {}", currentUrl)
```

### Issue: Handler timeout

**Cause:** Long-running operations blocking event loop

**Solutions:**
- Use `delay()` instead of `Thread.sleep()`
- Break long operations into smaller chunks
- Increase timeout in LoadOptions
- Use async operations properly

### Issue: Memory leaks from handlers

**Cause:** Handlers holding references to large objects

**Solutions:**
- Clear collections in onLoaded/onTabStopped
- Use weak references for caches
- Avoid storing driver/page references
- Profile memory usage

### Issue: Events seem to fire multiple times

**Cause:** Handler chaining with multiple instances

**Solutions:**
- Check if handlers are being chained unintentionally
- Use unique handler instances
- Add logging with correlation IDs

## Advanced Topics

### Custom Event Handler Types

You can create custom handler types by extending the base classes:

```kotlin
abstract class CustomWebPageHandler : (WebPage, MyCustomObject) -> Any?, AbstractPHandler() {
    abstract override operator fun invoke(page: WebPage, customObj: MyCustomObject): Any?
}

open class CustomEventHandler : AbstractChainedFunction2<WebPage, MyCustomObject, Any?>()
```

### Handler Factories

For dynamic handler creation:

```kotlin
object MyHandlerFactory {
    fun createHandlers(config: Config): PageEventHandlers {
        return DefaultPageEventHandlers().apply {
            if (config.enableLogging) {
                // Add logging handlers
            }
            if (config.handleModals) {
                // Add modal handlers
            }
        }
    }
}
```

### Global Event Handlers

Set handlers for all pages:

```kotlin
GlobalEventHandlers.pageEventHandlers = MyCustomHandlers()
```

### Conditional Handler Execution

```kotlin
browseEventHandlers.onDocumentSteady.addLast { page, driver ->
    // Only execute for specific domains
    if (page.url.contains("example.com")) {
        // Custom logic
    }
}
```

## Related Documentation

- [Event Handling Guide](get-started/9event-handling.md) - Basic event handling tutorial
- [WebDriver API](advanced-guides.md#webdriver) - WebDriver usage and capabilities
- [Load Options](get-started/3load-options.md) - Page loading configuration
- [RPA Guide](get-started/10RPA.md) - Robotic Process Automation with Browser4

## Appendix: Quick Reference

### Event Handler Execution Order

1. `crawl.onWillLoad`
2. `load.onNormalize`
3. `load.onWillLoad`
4. `load.onWillFetch`
5. `browse.onWillLaunchBrowser` (if using browser)
6. `browse.onBrowserLaunched`
7. `browse.onWillFetch`
8. `browse.onWillNavigate`
9. `browse.onNavigated`
10. `browse.onWillInteract`
11. `browse.onWillCheckDocumentState`
12. `browse.onDocumentFullyLoaded`
13. `browse.onWillScroll`
14. `browse.onDidScroll`
15. `browse.onDocumentSteady` ⭐ **Best for custom actions**
16. `browse.onWillComputeFeature`
17. `browse.onFeatureComputed`
18. `browse.onDidInteract`
19. `browse.onWillStopTab`
20. `browse.onTabStopped`
21. `browse.onFetched`
22. `load.onFetched`
23. `load.onWillParse`
24. `load.onWillParseHTMLDocument`
25. `load.onHTMLDocumentParsed` ⭐ **Best for data extraction**
26. `load.onParsed`
27. `load.onLoaded`
28. `crawl.onLoaded`

### Handler Signature Quick Reference

```kotlin
// Basic handlers
(String) -> String?                      // URL handlers
(UrlAware) -> UrlAware?                  // UrlAware handlers
(WebPage) -> Any?                        // WebPage handlers
(WebPage, FeaturedDocument) -> Any?      // Document handlers
suspend (WebPage, WebDriver) -> Any?     // WebDriver handlers (async)
(UrlAware, WebPage?) -> Any?             // Crawl completion handler
```

### Common Imports

```kotlin
import ai.platon.pulsar.skeleton.context.PulsarContexts
import ai.platon.pulsar.skeleton.crawl.common.url.ListenableHyperlink
import ai.platon.pulsar.skeleton.crawl.event.impl.DefaultPageEventHandlers
import ai.platon.pulsar.skeleton.crawl.fetch.driver.WebDriver
import ai.platon.pulsar.dom.FeaturedDocument
import ai.platon.pulsar.persist.WebPage
import kotlinx.coroutines.delay
import java.time.Duration
```
