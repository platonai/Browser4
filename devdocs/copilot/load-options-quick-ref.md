# LoadOptions Quick Reference Card

> Ultra-compact reference for AI agents. For detailed guide see [load-options-guide.md](./load-options-guide.md)

## Most Used Options (Top 15)

| Option | Short | Purpose | Example |
|--------|-------|---------|---------|
| `-expires` | `-i` | Cache duration | `-expires 1d` |
| `-refresh` | - | Force refetch | `-refresh` |
| `-parse` | `-ps` | Enable parsing | `-parse` |
| `-storeContent` | `-sct` | Store HTML | `-storeContent true` |
| `-dropContent` | - | Don't store HTML | `-dropContent` |
| `-outLink` | `-ol` | Extract links (CSS) | `-outLink a.product` |
| `-topLinks` | `-tl` | Max outlinks | `-topLinks 20` |
| `-itemExpires` | `-ii` | Item cache duration | `-itemExpires 7d` |
| `-requireSize` | `-rs` | Min page size (bytes) | `-requireSize 300000` |
| `-requireImages` | `-ri` | Min image count | `-requireImages 5` |
| `-scrollCount` | `-sc` | Scroll times | `-scrollCount 10` |
| `-scrollInterval` | `-si` | Scroll delay | `-scrollInterval 2s` |
| `-ignoreFailure` | `-ignF` | Retry failed pages | `-ignoreFailure` |
| `-deadline` | - | Task deadline | `-deadline 2024-01-05T18:00:00Z` |
| `-priority` | `-p` | Task priority (lower=higher) | `-priority -2000` |

## Time Formats

```
Hadoop: 1s, 10m, 1h, 1d, 7d
ISO-8601: PT1H30M, P1D, PT10S
```

## Common Patterns (Copy-Paste Ready)

### Simple Fetch
```kotlin
// 1-day cache
"-expires 1d"

// Force refresh
"-refresh"

// With quality check
"-expires 1d -requireSize 300000 -requireImages 5"
```

### Portal Crawl
```kotlin
// Basic portal + items
"-expires 1d -outLink a.product -topLinks 20 -itemExpires 7d"

// With quality
"-expires 1d -outLink a.product -topLinks 20 -itemExpires 7d -itemRequireImages 5 -itemRequireSize 500000"
```

### Parse & Store
```kotlin
// Parse and store
"-parse -storeContent"

// Parse only (no storage)
"-parse -dropContent"
```

### Interaction
```kotlin
// Heavy scrolling
"-scrollCount 10 -scrollInterval 2s -pageLoadTimeout 120s"

// High interaction level (preset)
"-interactLevel HIGH"
```

### Retry Control
```kotlin
// Aggressive retry
"-ignoreFailure -nMaxRetry 5 -nJitRetry 2"
```

## Parameter Categories (Grouped)

### Cache Control
- `-expires`, `-expireAt`, `-refresh`, `-ignoreFailure`
- Item: `-itemExpires`, `-itemExpireAt`

### Quality Requirements
- `-requireSize`, `-requireImages`, `-requireAnchors`, `-requireNotBlank`, `-waitNonBlank`
- Item: `-itemRequireSize`, `-itemRequireImages`, `-itemRequireAnchors`, `-itemRequireNotBlank`, `-itemWaitNonBlank`

### Portal Link Extraction
- `-outLink` (CSS selector), `-outLinkPattern` (regex), `-topLinks` (count)

### Browser Interaction
- `-scrollCount`, `-scrollInterval`, `-scriptTimeout`, `-pageLoadTimeout`, `-interactLevel`
- Item: `-itemScrollCount`, `-itemScrollInterval`, `-itemScriptTimeout`, `-itemPageLoadTimeout`

### Storage
- `-persist`, `-storeContent`, `-dropContent`, `-lazyFlush`

### Parsing
- `-parse`, `-reparseLinks`, `-ignoreUrlQuery`, `-noNorm`, `-noFilter`

### Retry & Priority
- `-priority`, `-nMaxRetry`, `-nJitRetry`

### Task Management
- `-entity`, `-label`, `-taskId`, `-taskTime`, `-deadline`

### Other
- `-authToken`, `-readonly`, `-isResource`, `-browser`, `-fetchMode`

## Decision Tree

```
Need to fetch page?
├─ Use cache if valid → "-expires 1d"
├─ Force refresh → "-refresh"
└─ Ignore past failures → "-ignoreFailure"

Extracting links?
├─ Set selector → "-outLink CSS_SELECTOR"
├─ Limit count → "-topLinks 20"
└─ Filter pattern → "-outLinkPattern REGEX"

Quality concerns?
├─ Size check → "-requireSize BYTES"
├─ Image check → "-requireImages COUNT"
└─ Link check → "-requireAnchors COUNT"

Dynamic content?
├─ More scrolling → "-scrollCount 10 -scrollInterval 2s"
└─ Higher interaction → "-interactLevel HIGH"

Two-tier crawl?
├─ Portal options → "-expires 1d -outLink CSS"
└─ Item options → "-itemExpires 7d -itemRequireImages 5"

Storage concerns?
├─ Parse only → "-parse -dropContent"
└─ Parse & store → "-parse -storeContent"
```

## Portal vs Item Pattern

```kotlin
session.loadOutPages(portalUrl,
    // Portal page settings (prefix: none)
    "-expires 1d " +
    "-requireSize 200000 " +
    "-scrollCount 3 " +
    "-outLink a.product " +
    "-topLinks 20 " +
    
    // Item page settings (prefix: item)
    "-itemExpires 7d " +
    "-itemRequireSize 500000 " +
    "-itemRequireImages 5 " +
    "-itemScrollCount 10"
)
```

## Equivalent Options

```kotlin
// These are the same:
"-refresh" == "-ignoreFailure -expires 0s" + reset fetchRetries

// Content storage (dropContent takes precedence):
"-dropContent" overrides "-storeContent true"
```

## Priority Values (Lower = Higher Priority)

```
High priority: -2000, -1000
Normal: 0 (default)
Low priority: 1000, 2000
```

## Troubleshooting Quick Fixes

| Problem | Solution |
|---------|----------|
| Not refreshing | `-refresh` |
| Missing lazy content | `-scrollCount 10 -scrollInterval 2s` |
| Timeout | `-pageLoadTimeout 180s -scriptTimeout 60s` |
| Page too small | `-requireSize 300000 -nMaxRetry 5` |
| Too many links | `-topLinks 20 -outLinkPattern REGEX` |
| Storage growth | `-dropContent` |
| Past deadline | `-deadline ISO_TIMESTAMP` |

## API Usage Shortcuts

```kotlin
// Parse from string
val opts = LoadOptions.parse("-expires 1d -parse", conf)

// Merge
val merged = LoadOptions.merge(opts, "-refresh")

// Check expired
opts.isExpired(lastFetchTime)

// Check dead
opts.isDead()

// Create item options
val itemOpts = opts.createItemOptions()

// Get modified only
val modified = opts.modifiedParams
```

## Boolean Parameters

### Zero-arity (flags, no value needed)
`-refresh`, `-ignoreFailure`, `-readonly`, `-isResource`, `-parse`, `-reparseLinks`, `-ignoreUrlQuery`, `-noNorm`, `-noFilter`, `-incognito`, `-dropContent`

### Single-arity (require true/false)
`-persist`, `-storeContent`, `-lazyFlush`

## Time Duration Examples

```kotlin
"10s"     // 10 seconds
"5m"      // 5 minutes
"2h"      // 2 hours
"1d"      // 1 day
"7d"      // 7 days
"PT30S"   // 30 seconds (ISO-8601)
"PT1H"    // 1 hour (ISO-8601)
"P1D"     // 1 day (ISO-8601)
```

## Instant (Timestamp) Examples

```kotlin
"2024-01-05T18:00:00Z"           // UTC
"2024-01-05T18:00:00+08:00"      // With timezone
```

## URL-Style Usage

```kotlin
// Options can be in URL string
val url = "https://example.com -expires 1d -parse"
session.load(url)

// Or separate
session.load("https://example.com", "-expires 1d -parse")
```

---

**Quick Links**:
- [Full Guide](./load-options-guide.md)
- [Chinese Guide](./load-options-guide-zh.md)
- [Source Code](../../pulsar-core/pulsar-skeleton/src/main/kotlin/ai/platon/pulsar/skeleton/common/options/LoadOptions.kt)

**Version**: 2024-01-05
