# LoadOptions AI-Friendly Guide

## Overview

`LoadOptions` is the primary configuration class for controlling web page fetching, processing, and storage in Browser4. This guide helps AI agents understand and effectively use LoadOptions parameters.

**Location**: `ai.platon.pulsar.skeleton.common.options.LoadOptions`

**Key Characteristics**:
- Command-line style parameters (e.g., `-expires 1d -parse -storeContent`)
- Can be specified directly in URL strings or programmatically
- Supports both portal (index) pages and item (detail) pages
- Parameters are merged when multiple sources are provided

---

## Quick Reference

### Most Common Patterns

```kotlin
// Force refresh (like browser refresh button)
session.load(url, "-refresh")

// Set cache expiration
session.load(url, "-expires 1d")  // or "-i 1d"

// Parse and store content
session.load(url, "-parse -storeContent")

// Extract outlinks from portal page
session.loadOutPages(url, "-outLink a[href~=item] -topLinks 20")

// Complete portal + items pattern
session.loadOutPages(url, 
    "-expires 1d -outLink a.product -topLinks 10 " +
    "-itemExpires 7d -itemRequireImages 5"
)
```

### Time Format Support

Both ISO-8601 and Hadoop time duration formats are supported:
- **ISO-8601**: `PT1H30M`, `P1D`, `PT10S`
- **Hadoop**: `1s`, `10m`, `1h`, `1d`, `7d`
- **Units**: ns, us, ms, s (seconds), m (minutes), h (hours), d (days)

---

## Parameter Categories

### 1. Task Identification & Organization

#### entity
- **Names**: `-e`, `-entity`, `--entity`
- **Type**: String
- **Purpose**: Specifies the type of content (e.g., "product", "article", "hotel")
- **AI Note**: Use for content classification and specialized processing
- **Example**: `-entity product`

#### label
- **Names**: `-l`, `-label`, `--label`
- **Type**: String
- **Purpose**: Groups related tasks logically
- **AI Note**: Use for organizing and filtering tasks
- **Example**: `-label electronics-2024-Q1`

#### taskId
- **Names**: `-taskId`, `--task-id`
- **Type**: String
- **Purpose**: Unique identifier for individual tasks
- **AI Note**: Use for tracking specific crawl operations
- **Example**: `-taskId task-12345`

#### taskTime
- **Names**: `-taskTime`, `--task-time`
- **Type**: Instant
- **Default**: `Instant.EPOCH`
- **Purpose**: Timestamp for batch grouping
- **AI Note**: Use to group tasks in the same execution window
- **Example**: `-taskTime 2024-01-05T10:00:00Z`

#### deadline
- **Names**: `-deadline`, `--deadline`
- **Type**: Instant
- **Default**: `DateTimes.doomsday` (far future)
- **Purpose**: Absolute deadline for task completion
- **AI Note**: Tasks past deadline are immediately abandoned
- **Example**: `-deadline 2024-01-05T18:00:00Z`

---

### 2. Cache & Freshness Control

#### expires
- **Names**: `-i`, `-expire`, `-expires`, `--expire`
- **Type**: Duration
- **Default**: System default (check `LoadOptionDefaults.expires`)
- **Purpose**: Cache validity duration
- **AI Note**: Controls when cached pages are refetched
- **Example**: `-expires 1d` (refetch after 1 day)

#### expireAt
- **Names**: `-expireAt`, `--expire-at`
- **Type**: Instant
- **Default**: `DateTimes.doomsday`
- **Purpose**: Absolute cache expiration timestamp
- **AI Note**: Alternative to relative `expires`, provides fixed expiration point
- **Example**: `-expireAt 2024-01-06T00:00:00Z`

#### refresh
- **Names**: `-refresh`, `--refresh`
- **Type**: Boolean (flag)
- **Default**: false
- **Purpose**: Force immediate refetch (like browser refresh)
- **AI Note**: Equivalent to `-ignoreFailure -expires 0s` + resets retry counters
- **Example**: `-refresh`
- **Side Effects**: 
  - Sets `expires = Duration.ZERO`
  - Sets `ignoreFailure = true`
  - Resets `page.fetchRetries = 0`

#### ignoreFailure
- **Names**: `-ignF`, `-ignoreFailure`, `--ignore-failure`
- **Type**: Boolean
- **Default**: System default
- **Purpose**: Retry pages even if previously failed
- **AI Note**: Use to retry "gone" pages without full refresh
- **Example**: `-ignoreFailure`

---

### 3. Page Quality Requirements

#### requireSize
- **Names**: `-rs`, `-requireSize`, `--require-size`
- **Type**: Int (bytes)
- **Default**: 0 (no requirement)
- **Purpose**: Minimum acceptable page size
- **AI Note**: Pages smaller than this are considered incomplete and refetched
- **Example**: `-requireSize 300000` (300KB minimum)

#### requireImages
- **Names**: `-ri`, `-requireImages`, `--require-images`
- **Type**: Int
- **Default**: 0 (no requirement)
- **Purpose**: Minimum required image count
- **AI Note**: Use for media-rich content validation
- **Example**: `-requireImages 10`

#### requireAnchors
- **Names**: `-ra`, `-requireAnchors`, `--require-anchors`
- **Type**: Int
- **Default**: 0 (no requirement)
- **Purpose**: Minimum required link count
- **AI Note**: Use to validate navigation elements loaded
- **Example**: `-requireAnchors 50`

#### requireNotBlank
- **Names**: `-rnb`, `-requireNotBlank`
- **Type**: String (CSS selector)
- **Default**: "" (empty)
- **Purpose**: CSS selector for element that must have non-blank text
- **AI Note**: Page is invalid if selected element is blank, triggers refetch
- **Example**: `-requireNotBlank .product-title`
- **Status**: Beta feature

#### waitNonBlank
- **Names**: `-wnb`, `-waitNonBlank`, `--wait-non-blank`
- **Type**: String (CSS selector)
- **Default**: "" (empty)
- **Purpose**: Wait for element to have text before proceeding
- **AI Note**: Different from requireNotBlank - this waits, not validates
- **Example**: `-waitNonBlank .dynamic-content`
- **Status**: Beta feature

---

### 4. Browser & Fetch Behavior

#### fetchMode
- **Names**: `-fm`, `-fetchMode`, `--fetch-mode`
- **Type**: FetchMode enum
- **Default**: `FetchMode.BROWSER`
- **Purpose**: Mechanism for fetching content
- **AI Note**: Currently only BROWSER mode is fully supported
- **Example**: `-fetchMode BROWSER`

#### browser
- **Names**: `-b`, `-browser`, `--browser`
- **Type**: BrowserType enum
- **Default**: System default (Chrome)
- **Purpose**: Browser engine selection
- **AI Note**: Chrome is default and recommended; session-scope selection not fully supported
- **Example**: `-browser CHROME`

#### isResource
- **Names**: `-resource`, `-isResource`
- **Type**: Boolean
- **Default**: false
- **Purpose**: Fetch as basic resource without browser rendering
- **AI Note**: Use for simple file downloads, APIs, or static content
- **Example**: `-resource`

#### readonly
- **Names**: `-readonly`
- **Type**: Boolean
- **Default**: false
- **Purpose**: Non-destructive mode, prevents page modifications
- **AI Note**: Ensures completely passive crawling without side effects
- **Example**: `-readonly`

---

### 5. Browser Interaction Settings

#### autoScrollCount
- **Names**: `-sc`, `-autoScrollCount`, `--auto-scroll-count`, `-scrollCount`, `--scroll-count`
- **Type**: Int
- **Default**: `InteractSettings.DEFAULT.autoScrollCount`
- **Purpose**: Number of scroll-down actions after page load
- **AI Note**: Controls lazy-loaded content discovery
- **Example**: `-scrollCount 5`

#### scrollInterval
- **Names**: `-si`, `-scrollInterval`, `--scroll-interval`
- **Type**: Duration
- **Default**: `InteractSettings.DEFAULT.scrollInterval`
- **Purpose**: Time between successive scrolls
- **AI Note**: Longer intervals allow content to load, but increase crawl time
- **Example**: `-scrollInterval 1s`

#### scriptTimeout
- **Names**: `-stt`, `-scriptTimeout`, `--script-timeout`
- **Type**: Duration
- **Default**: `InteractSettings.DEFAULT.scriptTimeout`
- **Purpose**: Max time for injected JavaScript execution
- **AI Note**: Prevents hanging on problematic scripts
- **Example**: `-scriptTimeout 30s`

#### pageLoadTimeout
- **Names**: `-plt`, `-pageLoadTimeout`, `--page-load-timeout`
- **Type**: Duration
- **Default**: `InteractSettings.DEFAULT.pageLoadTimeout`
- **Purpose**: Max time to wait for page load completion
- **AI Note**: Balances completeness vs. performance
- **Example**: `-pageLoadTimeout 60s`

#### interactLevel
- **Names**: `-ilv`, `-interactLevel`, `--interact-level`
- **Type**: InteractLevel enum
- **Default**: `InteractLevel.DEFAULT`
- **Purpose**: Overall interaction aggressiveness
- **AI Note**: 
  - Higher levels = better content extraction, slower performance
  - Lower levels = faster crawling, may miss dynamic content
- **Example**: `-interactLevel HIGH`
- **Values**: See `InteractLevel` enum

#### incognito
- **Names**: `-ic`, `-incognito`, `--incognito`
- **Type**: Boolean
- **Default**: false
- **Purpose**: Enable browser incognito mode
- **AI Note**: Limited effect since browsers always run in temporary contexts
- **Status**: Beta feature
- **Example**: `-incognito`

---

### 6. Outlink Extraction (Portal Pages)

#### outLinkSelector
- **Names**: `-ol`, `-outLink`, `-outLinkSelector`, `--out-link-selector`, `-outlink`, `-outlinkSelector`, `--outlink-selector`
- **Type**: String (CSS selector)
- **Default**: "" (empty)
- **Purpose**: CSS selector to extract links from portal/index pages
- **AI Note**: System automatically appends "a" if missing
- **Example**: `-outLink div.product-list a[href~=item]`

#### outLinkPattern
- **Names**: `-olp`, `-outLinkPattern`, `--out-link-pattern`
- **Type**: String (regex)
- **Default**: ".+" (matches all)
- **Purpose**: Regex pattern to filter extracted links
- **AI Note**: Only links matching this pattern are followed
- **Example**: `-outLinkPattern .*/product/.*`

#### topLinks
- **Names**: `-tl`, `-topLinks`, `--top-links`
- **Type**: Int
- **Default**: 20
- **Purpose**: Maximum outlinks to extract and follow
- **AI Note**: Limits crawl breadth from portal pages
- **Example**: `-topLinks 50`

---

### 7. Item Page Specific Options

All item page options work identically to their main counterparts but apply only to detail pages extracted from portals.

#### itemExpires
- **Names**: `-ii`, `-itemExpire`, `-itemExpires`, `--item-expires`
- **Type**: Duration
- **Default**: DECADES (very long)
- **Purpose**: Cache expiration for item pages
- **Example**: `-itemExpires 7d`

#### itemExpireAt
- **Names**: `-itemExpireAt`, `--item-expire-at`
- **Type**: Instant
- **Default**: `DateTimes.doomsday`
- **Purpose**: Absolute expiration for item pages
- **Example**: `-itemExpireAt 2024-01-10T00:00:00Z`

#### itemBrowser
- **Names**: `-ib`, `-itemBrowser`, `--item-browser`
- **Type**: BrowserType enum
- **Default**: System default
- **Purpose**: Browser for item pages
- **AI Note**: Session-scope browser selection not fully supported
- **Example**: `-itemBrowser CHROME`

#### itemScrollCount
- **Names**: `-isc`, `-itemScrollCount`, `--item-scroll-count`
- **Type**: Int
- **Default**: Inherits from `autoScrollCount`
- **Purpose**: Scroll count for item pages
- **Example**: `-itemScrollCount 10`

#### itemScrollInterval
- **Names**: `-isi`, `-itemScrollInterval`, `--item-scroll-interval`
- **Type**: Duration
- **Default**: Inherits from `scrollInterval`
- **Purpose**: Scroll interval for item pages
- **Example**: `-itemScrollInterval 2s`

#### itemScriptTimeout
- **Names**: `-ist`, `-itemScriptTimeout`, `--item-script-timeout`
- **Type**: Duration
- **Default**: Inherits from `scriptTimeout`
- **Purpose**: Script timeout for item pages
- **Example**: `-itemScriptTimeout 45s`

#### itemPageLoadTimeout
- **Names**: `-iplt`, `-itemPageLoadTimeout`, `--item-page-load-timeout`
- **Type**: Duration
- **Default**: Inherits from `pageLoadTimeout`
- **Purpose**: Page load timeout for item pages
- **Example**: `-itemPageLoadTimeout 90s`

#### itemWaitNonBlank
- **Names**: `-iwnb`, `-itemWaitNonBlank`, `--item-wait-non-blank`
- **Type**: String (CSS selector)
- **Default**: "" (empty)
- **Purpose**: Wait for element text on item pages
- **Example**: `-itemWaitNonBlank .product-description`

#### itemRequireNotBlank
- **Names**: `-irnb`, `-itemRequireNotBlank`, `--item-require-not-blank`
- **Type**: String (CSS selector)
- **Default**: "" (empty)
- **Purpose**: Validate element text on item pages
- **Status**: Beta feature
- **Example**: `-itemRequireNotBlank .product-price`

#### itemRequireSize
- **Names**: `-irs`, `-itemRequireSize`, `--item-require-size`
- **Type**: Int (bytes)
- **Default**: 0
- **Purpose**: Minimum size for item pages
- **Example**: `-itemRequireSize 500000`

#### itemRequireImages
- **Names**: `-iri`, `-itemRequireImages`, `--item-require-images`
- **Type**: Int
- **Default**: 0
- **Purpose**: Minimum images for item pages
- **Example**: `-itemRequireImages 8`

#### itemRequireAnchors
- **Names**: `-ira`, `-itemRequireAnchors`, `--item-require-anchors`
- **Type**: Int
- **Default**: 0
- **Purpose**: Minimum anchors for item pages
- **Example**: `-itemRequireAnchors 20`

---

### 8. Storage & Persistence

#### persist
- **Names**: `-persist`, `--persist`
- **Type**: Boolean (arity=1, requires value)
- **Default**: true
- **Purpose**: Whether to persist fetched pages immediately
- **AI Note**: Disable for performance when persistence not needed
- **Example**: `-persist false`

#### storeContent
- **Names**: `-sct`, `-storeContent`, `--store-content`
- **Type**: Boolean (arity=1, requires value)
- **Default**: System default
- **Purpose**: Whether to store HTML content in database
- **AI Note**: Content is typically the largest part; disable to save storage
- **Example**: `-storeContent true`

#### dropContent
- **Names**: `-dropContent`, `--drop-content`
- **Type**: Boolean (flag)
- **Default**: false
- **Purpose**: Explicitly do NOT store HTML content
- **AI Note**: Takes precedence over storeContent when both specified
- **Example**: `-dropContent`

#### lazyFlush
- **Names**: `-lazyFlush`, `--lazy-flush`
- **Type**: Boolean
- **Default**: System default
- **Purpose**: Batch page writes vs immediate writes
- **AI Note**: true = better performance, false = better data safety
- **Example**: `-lazyFlush true`

---

### 9. Parsing & Link Processing

#### parse
- **Names**: `-ps`, `-parse`, `--parse`
- **Type**: Boolean
- **Default**: System default
- **Purpose**: Enable immediate parsing after fetch
- **AI Note**: Parses into FeaturedDocument, extracts links and data
- **Example**: `-parse`

#### reparseLinks
- **Names**: `-rpl`, `-reparseLinks`, `--reparse-links`
- **Type**: Boolean
- **Default**: false
- **Purpose**: Re-extract links even if already parsed
- **AI Note**: Use when link extraction rules have changed
- **Status**: Beta feature
- **Example**: `-reparseLinks`

#### ignoreUrlQuery
- **Names**: `-ignoreUrlQuery`, `--ignore-url-query`
- **Type**: Boolean
- **Default**: false
- **Purpose**: Strip query parameters from URLs
- **AI Note**: Treats URLs with different params as same resource
- **Status**: Beta feature
- **Example**: `-ignoreUrlQuery`

#### noNorm
- **Names**: `-noNorm`, `--no-link-normalizer`
- **Type**: Boolean
- **Default**: false
- **Purpose**: Disable URL normalization
- **AI Note**: Can lead to duplicate URLs in different formats
- **Status**: Beta feature
- **Example**: `-noNorm`

#### noFilter
- **Names**: `-noFilter`, `--no-link-filter`
- **Type**: Boolean
- **Default**: false
- **Purpose**: Disable URL filtering
- **AI Note**: Can follow links normally excluded (external domains, etc.)
- **Status**: Beta feature
- **Example**: `-noFilter`

---

### 10. Retry & Failure Handling

#### priority
- **Names**: `-p`, `-priority`
- **Type**: Int
- **Default**: 0
- **Purpose**: Task execution priority in queue
- **AI Note**: 
  - Lower values = higher priority (like PriorityBlockingQueue)
  - Can be specified in URL, args, LoadOptions, or UrlAware
  - Values adjusted to valid range (see Priority13)
- **Example**: `-priority -2000`

#### nMaxRetry
- **Names**: `-nmr`, `-nMaxRetry`, `--n-max-retry`
- **Type**: Int
- **Default**: 3
- **Purpose**: Max retries before marking page as "gone"
- **AI Note**: Once exceeded, page won't be fetched until refreshed
- **Example**: `-nMaxRetry 5`

#### nJitRetry
- **Names**: `-njr`, `-nJitRetry`, `--n-jit-retry`
- **Type**: Int
- **Default**: System default
- **Purpose**: Max immediate retries when RETRY(1601) status returned
- **AI Note**: Retries during single fetch operation, not across crawl loop
- **Example**: `-nJitRetry 2`

---

### 11. Authentication & Security

#### authToken
- **Names**: `-authToken`, `--auth-token`
- **Type**: String
- **Default**: "" (empty)
- **Purpose**: Authentication token for protected resources
- **AI Note**: Use for restricted content or API access
- **Example**: `-authToken Bearer_xyz123`

---

### 12. Advanced/Experimental

#### iframe
- **Names**: `-ifr`, `-iframe`, `--iframe`
- **Type**: Int
- **Default**: 0
- **Purpose**: Iframe index to focus on
- **AI Note**: Feature planned but not fully implemented
- **Status**: Beta feature
- **Example**: `-iframe 1`

#### netCondition (Deprecated)
- **Names**: `-netCond`, `-netCondition`, `--net-condition`
- **Type**: Condition enum
- **Default**: `Condition.GOOD`
- **Purpose**: Network condition indicator
- **AI Note**: DEPRECATED - use `interactLevel` instead
- **Example**: Use `-interactLevel` instead

#### test
- **Names**: `-test`, `--test`
- **Type**: Int
- **Default**: System default (typically 0)
- **Purpose**: Enable test mode with verbosity level
- **AI Note**: Higher values = more detailed logging; 0 = disabled
- **Example**: `-test 2`

#### version
- **Names**: `-v`, `-version`, `--version`
- **Type**: String
- **Default**: "20220918"
- **Purpose**: Load options format version
- **AI Note**: Tracks compatibility between parser versions
- **Example**: `-version 20220918`

---

## Usage Patterns

### Pattern 1: Simple Page Fetch

```kotlin
// Fetch page with 1-day cache
val page = session.load(url, "-expires 1d")

// Force refresh
val page = session.load(url, "-refresh")

// Fetch with quality requirements
val page = session.load(url, 
    "-expires 1d -requireSize 300000 -requireImages 5"
)
```

### Pattern 2: Portal + Items Crawling

```kotlin
// Portal page expires in 1 day, item pages expire in 7 days
// Extract top 20 product links, require 5+ images on each
val pages = session.loadOutPages(url,
    "-expires 1d " +
    "-outLink a.product-link " +
    "-topLinks 20 " +
    "-itemExpires 7d " +
    "-itemRequireImages 5 " +
    "-itemRequireSize 500000"
)
```

### Pattern 3: Parse and Store

```kotlin
// Parse page immediately and store content
val page = session.load(url, "-parse -storeContent")

// Parse without storing heavy content
val page = session.load(url, "-parse -dropContent")
```

### Pattern 4: With Retry Control

```kotlin
// Allow up to 5 retries, ignore previous failures
val page = session.load(url, 
    "-ignoreFailure -nMaxRetry 5 -nJitRetry 2"
)
```

### Pattern 5: Custom Interaction

```kotlin
// Scroll 10 times with 2s interval, high interaction level
val page = session.load(url,
    "-scrollCount 10 " +
    "-scrollInterval 2s " +
    "-interactLevel HIGH " +
    "-pageLoadTimeout 120s"
)
```

### Pattern 6: Task Management

```kotlin
// Organized task with deadline
val page = session.load(url,
    "-label Q1-electronics " +
    "-taskId task-${UUID.randomUUID()} " +
    "-deadline 2024-01-05T23:59:59Z " +
    "-expires 1d"
)
```

---

## Parameter Relationships

### Mutual Exclusivity
- `storeContent` vs `dropContent`: Both control same behavior; `dropContent` takes precedence

### Dependent Parameters
- `outLinkSelector`, `outLinkPattern`, `topLinks`: All work together for link extraction
- `expires` + `expireAt`: Both control expiration; use one or the other
- `refresh`: Automatically sets `expires=0s` and `ignoreFailure=true`

### Portal vs Item Options
- Main options (e.g., `expires`) apply to portal pages
- Item options (e.g., `itemExpires`) apply to extracted detail pages
- When transitioning to item pages, item options become main options

### Interaction Settings Hierarchy
- `interactLevel`: Overall preset
- Individual settings (`scrollCount`, `scrollInterval`, etc.): Override preset
- Item-specific settings: Apply to item pages only

---

## Common Pitfalls & Solutions

### Problem: Pages Not Refreshing
**Solution**: Use `-refresh` or explicitly set `-expires 0s -ignoreFailure`

### Problem: Missing Lazy-Loaded Content
**Solution**: Increase `-scrollCount` and adjust `-scrollInterval`
```kotlin
"-scrollCount 10 -scrollInterval 2s"
```

### Problem: Timeouts on Slow Pages
**Solution**: Increase timeout values
```kotlin
"-pageLoadTimeout 180s -scriptTimeout 60s"
```

### Problem: Incomplete Pages (Too Small)
**Solution**: Set quality requirements and allow retries
```kotlin
"-requireSize 300000 -requireImages 5 -nMaxRetry 5"
```

### Problem: Too Many Outlinks Extracted
**Solution**: Limit with `topLinks` and filter with `outLinkPattern`
```kotlin
"-topLinks 20 -outLinkPattern .*/product/.*"
```

### Problem: Storage Growing Too Fast
**Solution**: Don't store content for temporary analysis
```kotlin
"-dropContent" or "-storeContent false"
```

### Problem: Task Running Past Deadline
**Solution**: Set explicit deadline
```kotlin
"-deadline 2024-01-05T18:00:00Z"
```

---

## Programmatic Usage

### Creating LoadOptions

```kotlin
// From string
val options = LoadOptions.parse("-expires 1d -parse", conf)

// From session
val options = session.options("-expires 1d -parse")

// Merge options
val merged = LoadOptions.merge(options1, options2)
val merged = LoadOptions.merge(options, "-refresh")

// Clone and modify
val newOptions = options.clone()
newOptions.expires = Duration.ofDays(7)
```

### Checking Values

```kotlin
// Check if expired
if (options.isExpired(lastFetchTime)) {
    // Refetch needed
}

// Check if past deadline
if (options.isDead()) {
    // Abandon task
}

// Check if parser engaged
if (options.parserEngaged()) {
    // Parser will run
}
```

### Getting Modified Parameters

```kotlin
// Get only non-default parameters
val modifiedParams = options.modifiedParams
val modifiedOptions = options.modifiedOptions

// Convert to string (normalized)
val argsString = options.toString()
```

### Item Options

```kotlin
// Create item-specific options from portal options
val itemOptions = options.createItemOptions()
```

---

## API Public Options

These options are exposed through REST APIs and marked with `@ApiPublic`:

- `entity`, `label`, `taskId`, `taskTime`, `deadline`
- `authToken`, `readonly`, `isResource`, `priority`
- `expires`, `expireAt`, `refresh`, `ignoreFailure`
- `outLinkSelector`, `outLinkPattern`, `topLinks`
- `requireSize`, `requireImages`, `requireAnchors`, `requireNotBlank`, `waitNonBlank`
- `itemExpires`, `itemExpireAt`, `itemScrollCount`, `itemWaitNonBlank`, `itemRequireNotBlank`
- `itemRequireSize`, `itemRequireImages`, `itemRequireAnchors`

Full list available via: `LoadOptions.apiPublicOptionNames`

---

## Best Practices for AI Agents

1. **Start Simple**: Begin with basic options like `-expires` and `-refresh`
2. **Layer Complexity**: Add quality requirements only when needed
3. **Test Incrementally**: Add one option at a time to understand effects
4. **Use Presets**: Let `interactLevel` handle interaction settings when possible
5. **Monitor Performance**: Higher quality = slower performance; balance accordingly
6. **Leverage Defaults**: Most defaults are reasonable; override only when necessary
7. **Portal/Item Pattern**: Use item-specific options for two-tier crawling
8. **Error Handling**: Use retry options (`nMaxRetry`, `nJitRetry`) appropriately
9. **Storage Awareness**: Consider using `-dropContent` for analysis-only tasks
10. **Time Formats**: Use Hadoop format (e.g., `1d`, `2h`) for better readability

---

## Related Documentation

- User Guide: `/docs/get-started/3load-options.md`
- REST API Examples: `/docs/rest-api-examples.md`
- Concepts: `/docs/concepts.md`
- Source Code: `/pulsar-core/pulsar-skeleton/src/main/kotlin/ai/platon/pulsar/skeleton/common/options/LoadOptions.kt`

---

## Quick Command Builder

```kotlin
// Template for most common scenarios
val basicFetch = "-expires 1d"
val forceFetch = "-refresh"
val qualityFetch = "-expires 1d -requireSize 300000 -requireImages 5"
val portalCrawl = "-expires 1d -outLink CSS_SELECTOR -topLinks 20"
val fullCrawl = "-expires 1d -outLink CSS_SELECTOR -topLinks 20 -itemExpires 7d -itemRequireImages 5"
val parseAndStore = "-parse -storeContent"
val parseOnly = "-parse -dropContent"
val withRetry = "-ignoreFailure -nMaxRetry 5"
val withDeadline = "-deadline ISO_TIMESTAMP -expires 1d"
val customInteract = "-scrollCount 10 -scrollInterval 2s -pageLoadTimeout 120s"
```

---

**Version**: 2024-01-05  
**Maintainer**: Browser4 Team  
**Status**: Living Document - Update as LoadOptions evolves
