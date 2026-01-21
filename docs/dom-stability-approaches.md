# DOM Stability Waiting Approaches

## Overview

This document analyzes and compares different approaches for waiting for DOM stability in the Browser4 framework. DOM stability detection is crucial for reliable web scraping and browser automation, as it ensures that the page has finished loading dynamic content before attempting to extract data or interact with elements.

## Existing Approaches

### Approach 1: `__pulsar_utils__.waitForReady` (JavaScript-based)

**Location**: `pulsar-core/pulsar-browser/src/main/resources/js/__pulsar_utils__.js`

**Type**: JavaScript in-page execution

**Core Mechanism**:
- Injected JavaScript that runs in the browser context
- Polls the DOM state repeatedly with configurable delays
- Tracks multiple metrics to determine readiness

**Detection Criteria**:
1. **Document State**: Checks if `document.readyState === "complete"`
2. **DOM Quality Metrics**:
   - Page height ≥ 4000px
   - Number of anchors ≥ 100
   - Number of images ≥ 20
3. **DOM Stability**: Monitors changes between checks
   - Height changes < 10px
   - No new anchors, images, short text, or number-like elements
4. **Idle Detection**: After 10+ consecutive stable checks (≈10 seconds)
5. **Progressive Scrolling**: Optionally scrolls down to trigger lazy-loaded content

**Usage Pattern**:
```kotlin
// From InteractiveBrowserEmulator.kt
val expression = "__pulsar_utils__.waitForReady(5)" // 5 scrolls
var message: Any? = null
while (message == null || message == false) {
    message = driver.evaluate(expression)
    if (message == false) {
        delay(500)
    }
}
```

**Advantages**:
- ✅ Runs in browser context - sees actual rendered state
- ✅ Comprehensive metrics (height, elements count, lazy-loading detection)
- ✅ Handles lazy-loaded content via scrolling
- ✅ Returns detailed metadata about page state
- ✅ Battle-tested in production for traditional web scraping

**Disadvantages**:
- ❌ JavaScript execution overhead on every check
- ❌ Polling-based - wastes CPU cycles
- ❌ Requires full script re-execution each check (slow for large pages)
- ❌ Hardcoded thresholds may not fit all page types
- ❌ No way to distinguish between intentional animations vs actual loading
- ❌ Maximum 60 rounds (≈60 seconds) timeout hardcoded

**Best For**:
- Traditional content-heavy websites (news, e-commerce)
- Pages with lazy-loaded images and content
- Scenarios where page quality matters (height, element count)
- When detailed page metadata is needed

### Approach 2: `PageStateTracker.waitForDOMSettle` (Kotlin/CDP-based)

**Location**: `pulsar-agentic/src/main/kotlin/ai/platon/pulsar/agentic/inference/detail/PageStateTracker.kt`

**Type**: Chrome DevTools Protocol (CDP) with lightweight JavaScript probe

**Core Mechanism**:
- Installs a single `MutationObserver` once per page
- Monitors DOM mutations via callbacks
- Uses a numeric signature combining readyState and mutation stamp
- Minimal JS ↔ JVM bridge overhead

**Detection Criteria**:
1. **Mutation Observer**: Tracks DOM changes (childList, characterData)
   - Excludes attribute changes to reduce noise
2. **ReadyState Awareness**: 
   - `complete` (code=2): requires only 2 stable checks
   - `interactive` (code=1): requires 3 stable checks
3. **Signature Stability**: Same signature for N consecutive checks
4. **Efficient Polling**: Configurable check interval (default 100ms)

**Implementation Details**:
```kotlin
// From PageStateTracker.kt
suspend fun waitForDOMSettle(timeoutMs: Long, checkIntervalMs: Long) {
    driver.waitForSelector("body", timeoutMs)
    ensureDomStabilityProbeInstalled() // Install once
    
    var stableCount = 0
    while (elapsed < timeoutMs) {
        val signature = driver.evaluateValue("window.__pulsar_GetDomSignature()")
        if (signature == lastSignature) {
            stableCount++
            val requiredStableChecks = if (readyStateCode == 2) 2 else 3
            if (stableCount >= requiredStableChecks) return // Done!
        } else {
            stableCount = 0
        }
        delay(checkIntervalMs)
    }
}
```

**JavaScript Probe** (`dom_settle.js`):
```javascript
// Installed once, reused for all checks
window.__pulsar_GetDomSignature = function() {
    const rsCode = document.readyState === 'complete' ? 2 : 
                   (document.readyState === 'interactive' ? 1 : 0);
    return (window.__pulsar_DomStamp * 4) + rsCode;
}

// MutationObserver increments __pulsar_DomStamp on changes
const obs = new MutationObserver(() => { window.__pulsar_DomStamp++; });
obs.observe(document, { subtree: true, childList: true, characterData: true });
```

**Advantages**:
- ✅ Fast - minimal JavaScript execution per check
- ✅ Efficient - single observer, no DOM traversal
- ✅ Low overhead - compact numeric signature
- ✅ Smart - adapts to readyState (fewer checks when complete)
- ✅ Modern - uses native MutationObserver API
- ✅ Configurable timeouts and intervals
- ✅ Ignores attribute changes (less noise from CSS/aria toggles)

**Disadvantages**:
- ❌ No content quality metrics (height, element count)
- ❌ May return too early for lazy-loaded content
- ❌ Doesn't handle scroll-triggered loading
- ❌ No metadata about page state returned
- ❌ Assumes mutations = loading (may be animation/interactive elements)

**Best For**:
- AI agent interactions requiring fast response
- Single-page applications (SPAs) with client-side rendering
- Modern web apps with minimal lazy-loading
- When speed is more important than content completeness

## Comparison Matrix

| Aspect | `waitForReady` (JS) | `waitForDOMSettle` (CDP) |
|--------|---------------------|--------------------------|
| **Speed** | Slow (full DOM traversal) | Fast (observer-based) |
| **Overhead** | High (re-parsing JS) | Low (one-time setup) |
| **Content Quality** | Yes (height, count metrics) | No |
| **Lazy Loading** | Yes (scroll support) | No |
| **Metadata** | Yes (detailed stats) | No |
| **Animations** | May wait unnecessarily | May return too early |
| **Configurability** | Limited (hardcoded) | Good (timeouts/intervals) |
| **Use Case** | Web scraping | AI agents, SPAs |

## Recommended New Approaches

### Approach 3: Network-Idle Detection

**Concept**: Wait for all network requests to finish, similar to Puppeteer's `networkidle0` / `networkidle2`.

**Implementation Strategy**:
```kotlin
suspend fun waitForNetworkIdle(
    idleTime: Long = 500,  // ms of no network activity
    maxInflightRequests: Int = 2,  // allow N concurrent requests
    timeout: Long = 30_000
) {
    val networkTracker = NetworkActivityTracker(driver)
    val startTime = System.currentTimeMillis()
    var lastActivityTime = startTime
    
    while (System.currentTimeMillis() - startTime < timeout) {
        val inflightCount = networkTracker.getInflightRequestCount()
        
        if (inflightCount <= maxInflightRequests) {
            val idleDuration = System.currentTimeMillis() - lastActivityTime
            if (idleDuration >= idleTime) {
                return // Network is idle!
            }
        } else {
            lastActivityTime = System.currentTimeMillis()
        }
        
        delay(100)
    }
}
```

**Advantages**:
- ✅ Accurate for AJAX-heavy sites
- ✅ Independent of DOM structure
- ✅ Works well with REST/GraphQL APIs
- ✅ Standard approach (Puppeteer, Playwright use this)

**Disadvantages**:
- ❌ Requires CDP network monitoring
- ❌ May wait too long for slow third-party resources
- ❌ Doesn't detect content rendered from cache

**Best For**:
- Single-page applications (React, Vue, Angular)
- Sites with heavy AJAX/fetch usage
- Modern web apps with REST/GraphQL APIs

### Approach 4: Hybrid Multi-Strategy Approach

**Concept**: Combine multiple signals for robust detection.

**Implementation**:
```kotlin
suspend fun waitForPageStable(
    config: StabilityConfig = StabilityConfig.DEFAULT
): StabilityResult {
    val strategies = listOf(
        NetworkIdleStrategy(config),
        DOMStabilityStrategy(config),
        ContentQualityStrategy(config)
    )
    
    // Wait for all strategies OR first N strategies OR timeout
    return when (config.mode) {
        Mode.ALL -> waitForAll(strategies, config.timeout)
        Mode.ANY_N -> waitForAnyN(strategies, n = 2, config.timeout)
        Mode.RACE -> waitForFirst(strategies, config.timeout)
    }
}

data class StabilityConfig(
    val mode: Mode = Mode.ANY_N,
    val timeout: Long = 30_000,
    val networkIdleTime: Long = 500,
    val domStableChecks: Int = 3,
    val minHeight: Int = 1000,
    val minElements: Int = 10
)
```

**Strategy Interface**:
```kotlin
interface StabilityStrategy {
    suspend fun check(): Boolean
    val name: String
}

class NetworkIdleStrategy(config: StabilityConfig) : StabilityStrategy {
    override suspend fun check(): Boolean = 
        isNetworkIdle(config.networkIdleTime)
    override val name = "network-idle"
}

class DOMStabilityStrategy(config: StabilityConfig) : StabilityStrategy {
    override suspend fun check(): Boolean = 
        isDOMStable(config.domStableChecks)
    override val name = "dom-stable"
}

class ContentQualityStrategy(config: StabilityConfig) : StabilityStrategy {
    override suspend fun check(): Boolean = 
        hasMinimumContent(config.minHeight, config.minElements)
    override val name = "content-quality"
}
```

**Advantages**:
- ✅ Flexible - choose strategies per use case
- ✅ Robust - multiple signals reduce false positives
- ✅ Configurable - adjust per site/scenario
- ✅ Extensible - add new strategies easily

**Disadvantages**:
- ❌ More complex implementation
- ❌ May be slower (multiple checks)
- ❌ Requires careful tuning

**Best For**:
- Mission-critical scraping requiring high reliability
- Unknown/diverse site types
- Production systems needing robust fallbacks

### Approach 5: Custom Event-Based Approach

**Concept**: Let pages signal when they're ready via custom events.

**Implementation**:
```kotlin
suspend fun waitForCustomReadyEvent(
    eventName: String = "pulsarPageReady",
    timeout: Long = 30_000,
    fallbackStrategy: StabilityStrategy? = null
): Boolean {
    val eventFired = CompletableDeferred<Boolean>()
    
    // Install listener in page
    driver.evaluateValue("""
        document.addEventListener('$eventName', function() {
            window.__pulsarPageReady = true;
        }, { once: true });
    """)
    
    // Poll for event
    val startTime = System.currentTimeMillis()
    while (System.currentTimeMillis() - startTime < timeout) {
        val ready = driver.evaluateValue("window.__pulsarPageReady")
        if (ready == true) return true
        delay(100)
    }
    
    // Fallback if event never fires
    return fallbackStrategy?.check() ?: false
}
```

**Advantages**:
- ✅ Most accurate - page knows when it's ready
- ✅ Fast - no polling overhead
- ✅ Flexible - pages control their own readiness

**Disadvantages**:
- ❌ Requires page cooperation (custom event)
- ❌ Not applicable to third-party sites
- ❌ Needs fallback for non-cooperating pages

**Best For**:
- Internal/controlled sites
- Testing environments
- Sites with complex initialization logic

## Usage Guidelines

### When to Use Each Approach

1. **Use `waitForReady`** when:
   - Scraping traditional content websites (news, blogs, e-commerce)
   - Page quality matters (height, element count)
   - Dealing with lazy-loaded content
   - Need detailed page metadata
   - Speed is less critical than completeness

2. **Use `waitForDOMSettle`** when:
   - Building AI agents that need fast interactions
   - Working with modern SPAs
   - Speed is critical
   - Content completeness is less important
   - Interacting with known, well-behaved sites

3. **Use Network-Idle** when:
   - Target site is AJAX-heavy
   - Single-page application architecture
   - API-driven content loading
   - Need to wait for specific data fetches

4. **Use Hybrid Approach** when:
   - Site behavior is unknown or inconsistent
   - Reliability is paramount
   - Can afford slightly longer wait times
   - Need to handle diverse site types

5. **Use Custom Events** when:
   - You control the target site
   - Testing internal applications
   - Complex initialization sequences
   - Need precise ready signals

### Combining Approaches

For maximum reliability in production:

```kotlin
suspend fun smartWaitForPage(url: String, context: PageContext): StabilityResult {
    // 1. Basic: Wait for body element
    driver.waitForSelector("body", timeout = 5000)
    
    // 2. Strategy selection based on site type
    val strategy = when (detectSiteType(url)) {
        SiteType.SPA -> waitForDOMSettle()
        SiteType.TRADITIONAL -> waitForReady()
        SiteType.AJAX_HEAVY -> waitForNetworkIdle()
        SiteType.UNKNOWN -> waitForHybrid()
    }
    
    // 3. Validation: Check if page has minimum content
    val hasContent = validateMinimumContent()
    
    // 4. Fallback: If strategy failed, try alternate
    if (!hasContent && !strategy.success) {
        return waitForHybrid(timeout = 10_000)
    }
    
    return StabilityResult.success(strategy)
}
```

## Implementation Recommendations

### Short-term (Immediate)

1. **Document existing approaches** ✅ (this document)
2. **Add configuration options** to `PageStateTracker`:
   ```kotlin
   data class DOMSettleConfig(
       val timeoutMs: Long = 5000,
       val checkIntervalMs: Long = 100,
       val requiredStableChecksComplete: Int = 2,
       val requiredStableChecksOther: Int = 3,
       val observeAttributes: Boolean = false  // Currently false, make configurable
   )
   ```

3. **Add network-idle detection** as optional strategy in `PageStateTracker`

### Mid-term (Next Sprint)

1. **Implement hybrid approach** with pluggable strategies
2. **Add site-type detection** heuristics
3. **Create comprehensive tests** for each approach
4. **Performance benchmarking** across different site types

### Long-term (Roadmap)

1. **ML-based readiness prediction** - learn patterns from successful/failed waits
2. **Adaptive timeout tuning** - automatically adjust based on site behavior
3. **Visual stability detection** - use screenshots to detect visual changes
4. **User telemetry** - collect data on which strategies work best for which sites

## Testing Strategy

### Test Matrix

Each approach should be tested against:

1. **Static HTML sites** (simple, fast-loading)
2. **Dynamic SPAs** (React, Vue, Angular)
3. **AJAX-heavy sites** (infinite scroll, lazy-loading)
4. **Slow-loading sites** (large images, third-party resources)
5. **Animation-heavy sites** (CSS animations, canvas, video)

### Test Scenarios

```kotlin
@Test
fun `waitForReady should handle lazy-loaded images`() = runTest {
    val url = "https://example.com/lazy-images"
    driver.navigate(url)
    
    val result = driver.evaluate("__pulsar_utils__.waitForReady(5)")
    assertNotNull(result)
    assertTrue(result.contains("\"ni\":20")) // At least 20 images
}

@Test
fun `waitForDOMSettle should be fast for simple pages`() = runTest {
    val url = "https://example.com/simple"
    driver.navigate(url)
    
    val startTime = System.currentTimeMillis()
    pageStateTracker.waitForDOMSettle()
    val elapsed = System.currentTimeMillis() - startTime
    
    assertTrue(elapsed < 2000) // Should be fast
}

@Test
fun `network-idle should wait for AJAX requests`() = runTest {
    val url = "https://example.com/spa"
    driver.navigate(url)
    
    waitForNetworkIdle(idleTime = 500, maxInflightRequests = 0)
    
    val content = driver.evaluate("document.body.innerText")
    assertTrue(content.contains("Expected AJAX Content"))
}
```

## References

- **Puppeteer Network Idle**: https://pptr.dev/api/puppeteer.page.waitfornavigation
- **Playwright Wait Strategies**: https://playwright.dev/docs/api/class-page#page-wait-for-load-state
- **MutationObserver API**: https://developer.mozilla.org/en-US/docs/Web/API/MutationObserver

---

**Document Version**: 1.0  
**Last Updated**: 2026-01-21  
**Authors**: Browser4 Team  
**Status**: Design Document
