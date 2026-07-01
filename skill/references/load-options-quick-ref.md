# LoadOptions Quick Reference

> Ultra-compact reference for AI agents. For detailed guidance see [load-options-guide.md](load-options-guide.md)

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
Hadoop:   1s, 10m, 1h, 1d, 7d
ISO-8601: PT1H30M, P1D, PT10S
```

## Common Patterns (Copy-Paste Ready)

### Simple Fetch
```
-expires 1d
-refresh
-expires 1d -requireSize 300000 -requireImages 5
```

### Portal Crawl
```
-expires 1d -outLink a.product -topLinks 20 -itemExpires 7d
-expires 1d -outLink a.product -topLinks 20 -itemExpires 7d -itemRequireImages 5 -itemRequireSize 500000
```

### Parse & Store
```
-parse -storeContent
-parse -dropContent
```

### Interaction
```
-scrollCount 10 -scrollInterval 2s -pageLoadTimeout 120s
-interactLevel BEST_DATA
```

### Retry Control
```
-ignoreFailure -nMaxRetry 5 -nJitRetry 2
```

## Parameter Categories

### Cache Control
`-expires`, `-expireAt`, `-refresh`, `-ignoreFailure`
Item: `-itemExpires`, `-itemExpireAt`

### Quality Requirements
`-requireSize`, `-requireImages`, `-requireAnchors`, `-requireNotBlank`
Item: `-itemRequireSize`, `-itemRequireImages`, `-itemRequireAnchors`

### Portal Link Extraction
`-outLink` (CSS selector), `-outLinkPattern` (regex), `-topLinks` (count)

### Browser Interaction
`-scrollCount`, `-scrollInterval`, `-scriptTimeout`, `-pageLoadTimeout`, `-interactLevel`
Item: `-itemScrollCount`, `-itemScrollInterval`, `-itemScriptTimeout`, `-itemPageLoadTimeout`

### Storage
`-persist`, `-storeContent`, `-dropContent`, `-lazyFlush`

### Parsing
`-parse`, `-ignoreUrlQuery`, `-noNorm`

### Retry & Priority
`-priority`, `-nMaxRetry`, `-nJitRetry`

### Task Management
`-entity`, `-label`, `-taskId`, `-taskTime`, `-deadline`

### Other
`-authToken`, `-readonly`, `-isResource`, `-test`, `-version`

## Decision Tree

```
Need to fetch a page?
├─ Use cache if valid → -expires 1d
├─ Force refresh → -refresh
└─ Ignore past failures → -ignoreFailure

Extracting links?
├─ Set selector → -outLink CSS_SELECTOR
├─ Limit count → -topLinks 20
└─ Filter pattern → -outLinkPattern REGEX

Quality concerns?
├─ Size check → -requireSize BYTES
├─ Image check → -requireImages COUNT
└─ Link check → -requireAnchors COUNT

Dynamic content?
├─ More scrolling → -scrollCount 10 -scrollInterval 2s
└─ Higher interaction preset → -interactLevel BEST_DATA

Two-tier crawl (portal + items)?
├─ Portal options → -expires 1d -outLink CSS
└─ Item options → -itemExpires 7d -itemRequireImages 5

Storage concerns?
├─ Parse only → -parse -dropContent
└─ Parse & store → -parse -storeContent
```

## Portal vs Item Pattern

Portal options apply to the list/index page. Item options (prefixed with `item`) apply to detail pages linked from the portal:

```
-expires 1d               # Portal: cache 1 day
-requireSize 200000        # Portal: min 200KB
-scrollCount 3             # Portal: scroll 3 times
-outLink a.product         # Portal: extract product links
-topLinks 20               # Portal: max 20 links

-itemExpires 7d            # Items: cache 7 days
-itemRequireSize 500000    # Items: min 500KB
-itemRequireImages 5       # Items: at least 5 images
-itemScrollCount 10        # Items: scroll 10 times
```

## Equivalent Options

```
-refresh  ==  -ignoreFailure -expires 0s  (plus resets retry counters)
-dropContent  overrides  -storeContent true
```

## Priority Values (Lower = Higher Priority)

```
High priority:  -2000, -1000
Normal:         0 (default)
Low priority:   1000, 2000
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

## Boolean Parameters

### Flags (no value needed)
`-refresh`, `-ignoreFailure`, `-readonly`, `-isResource`, `-parse`, `-ignoreUrlQuery`, `-noNorm`, `-incognito` (`-ic`), `-dropContent`, `-lazyFlush`

### Require true/false
`-persist`, `-storeContent`

## Time Duration Examples

```
10s      = 10 seconds
5m       = 5 minutes
2h       = 2 hours
1d       = 1 day
7d       = 7 days
PT30S    = 30 seconds (ISO-8601)
PT1H     = 1 hour (ISO-8601)
P1D      = 1 day (ISO-8601)
```

## Instant (Timestamp) Examples

```
2024-01-05T18:00:00Z           # UTC
2024-01-05T18:00:00+08:00      # With timezone
```

---

**See also**: [Full LoadOptions Guide](load-options-guide.md)
