---
title: "LoadOptions Guide"
description: "Complete reference for LoadOptions — cache control, quality requirements, browser interaction, portal crawling, and storage. Use when configuring page fetching behavior."
tier: catalog
---

# LoadOptions Guide

`LoadOptions` controls web page fetching, processing, and storage in Browser4. Options are passed as command-line-style strings (e.g., `-expires 1d -parse -storeContent`).

## Quick Reference

### Most Used Options (Top 15)

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

### Time Formats

```
Hadoop:   1s, 10m, 1h, 1d, 7d
ISO-8601: PT1H30M, P1D, PT10S
```

### Common Patterns (Copy-Paste Ready)

**Simple Fetch:**
```
-expires 1d
-refresh
-expires 1d -requireSize 300000 -requireImages 5
```

**Portal Crawl:**
```
-expires 1d -outLink a.product -topLinks 20 -itemExpires 7d
-expires 1d -outLink a.product -topLinks 20 -itemExpires 7d -itemRequireImages 5 -itemRequireSize 500000
```

**Parse & Store:**
```
-parse -storeContent
-parse -dropContent
```

**Interaction:**
```
-scrollCount 10 -scrollInterval 2s -pageLoadTimeout 120s
-interactLevel BEST_DATA
```

**Retry Control:**
```
-ignoreFailure -nMaxRetry 5 -nJitRetry 2
```

---

## Parameter Categories

### 1. Task Identification & Organization

| Option | Short | Purpose | Example |
|--------|-------|---------|---------|
| `-entity` | `-e` | Content type label (e.g., "product", "article") | `-entity product` |
| `-label` | `-l` | Logical group for related tasks | `-label electronics-2024-Q1` |
| `-taskId` | | Unique identifier for a task | `-taskId task-12345` |
| `-taskTime` | | Timestamp for batch grouping | `-taskTime 2024-01-05T10:00:00Z` |
| `-deadline` | | Absolute deadline — tasks past this are abandoned | `-deadline 2024-01-05T18:00:00Z` |

### 2. Cache & Freshness Control

| Option | Short | Purpose | Example |
|--------|-------|---------|---------|
| `-expires` | `-i` | Cache validity duration | `-expires 1d` |
| `-expireAt` | | Absolute cache expiration timestamp | `-expireAt 2024-01-06T00:00:00Z` |
| `-refresh` | | Force immediate refetch, reset retry counters | `-refresh` |
| `-ignoreFailure` | `-ignF` | Retry pages even if they previously failed | `-ignoreFailure` |

**`-refresh` side effects:** Sets `expires=0s`, `itemExpires=0s`, and `ignoreFailure=true`. Use when you need a guaranteed fresh fetch.

**Choosing between `-expires` and `-expireAt`:** Use `-expires` for relative durations ("refetch after 1 day"). Use `-expireAt` for fixed points ("valid until midnight UTC").

### 3. Page Quality Requirements

Pages failing these checks are considered incomplete and will be refetched (up to `-nMaxRetry` times).

| Option | Short | Purpose | Example |
|--------|-------|---------|---------|
| `-requireSize` | `-rs` | Minimum page size in bytes | `-requireSize 300000` |
| `-requireImages` | `-ri` | Minimum image count | `-requireImages 10` |
| `-requireAnchors` | `-ra` | Minimum link count | `-requireAnchors 50` |
| `-requireNotBlank` | `-rnb` | CSS selector for element that must have non-blank text | `-requireNotBlank .product-title` |

### 4. Browser & Fetch Behavior

| Option | Short | Purpose | Example |
|--------|-------|---------|---------|
| `-readonly` | | Non-destructive mode, prevents page modifications | `-readonly` |
| `-isResource` | `-resource` | Fetch as raw resource (no browser rendering) — use for APIs, file downloads, static content | `-resource` |

### 5. Browser Interaction

Controls how the browser interacts with the page after load. Critical for lazy-loaded or JS-heavy content.

| Option | Short | Purpose | Example |
|--------|-------|---------|---------|
| `-scrollCount` | `-sc` | Number of scroll-down actions after page load | `-scrollCount 5` |
| `-scrollInterval` | `-si` | Delay between successive scrolls | `-scrollInterval 1s` |
| `-scriptTimeout` | `-stt` | Max time for injected JavaScript to complete | `-scriptTimeout 30s` |
| `-pageLoadTimeout` | `-plt` | Max time to wait for page load | `-pageLoadTimeout 60s` |
| `-interactLevel` | `-ilv` | Overall interaction preset | `-interactLevel BEST_DATA` |

**`-interactLevel` values** (fastest → most thorough):

| Level | When to use |
|-------|-------------|
| `FASTEST` | Static pages, no JS needed |
| `FASTER` | Minimal JS, basic pages |
| `FAST` | Moderate JS, typical pages |
| `DEFAULT` | Standard web apps |
| `GOOD_DATA` | Content-rich pages with lazy loading |
| `BETTER_DATA` | Heavy JS, infinite scroll |
| `BEST_DATA` | Maximum extraction quality, SPAs |

Individual settings (`-scrollCount`, `-scrollInterval`, etc.) override the preset when both are specified.

### 6. Outlink Extraction (Portal Pages)

Controls how links are discovered and filtered on portal/list/index pages.

| Option | Short | Purpose | Example |
|--------|-------|---------|---------|
| `-outLink` | `-ol` | CSS selector to extract links | `-outLink div.product-list a[href~=item]` |
| `-outLinkPattern` | `-olp` | Regex to filter extracted links | `-outLinkPattern .*/product/.*` |
| `-topLinks` | `-tl` | Maximum outlinks to extract and follow | `-topLinks 50` |

### 7. Item Page Options

All item options mirror their main counterparts but apply only to detail pages extracted from portals. When not specified, item options inherit from their main equivalents.

| Option | Short | Purpose |
|--------|-------|---------|
| `-itemExpires` | `-ii` | Cache expiration for item pages |
| `-itemExpireAt` | | Absolute expiration for item pages |
| `-itemScrollCount` | `-isc` | Scroll count for item pages |
| `-itemScrollInterval` | `-isi` | Scroll interval for item pages |
| `-itemScriptTimeout` | `-ist` | Script timeout for item pages |
| `-itemPageLoadTimeout` | `-iplt` | Page load timeout for item pages |
| `-itemRequireSize` | `-irs` | Minimum size for item pages |
| `-itemRequireImages` | `-iri` | Minimum images for item pages |
| `-itemRequireAnchors` | `-ira` | Minimum anchors for item pages |

### 8. Storage & Persistence

| Option | Short | Purpose | Example |
|--------|-------|---------|---------|
| `-persist` | | Whether to persist fetched pages (requires value: `true`/`false`) | `-persist false` |
| `-storeContent` | `-sct` | Store HTML content (requires value: `true`/`false`) | `-storeContent true` |
| `-dropContent` | | Do NOT store HTML content (flag, takes precedence over `-storeContent`) | `-dropContent` |
| `-lazyFlush` | | Batch writes for better performance (flag) | `-lazyFlush` |

**Content storage precedence:** `-dropContent` overrides `-storeContent true`. Use `-dropContent` when you only need parsed data, not raw HTML.

### 9. Parsing & Link Processing

| Option | Short | Purpose | Example |
|--------|-------|---------|---------|
| `-parse` | `-ps` | Enable parsing after fetch (flag) | `-parse` |
| `-ignoreUrlQuery` | | Strip query parameters from URLs — treats `?page=1` and `?page=2` as same resource (flag) | `-ignoreUrlQuery` |
| `-noNorm` | | Disable URL normalization — may cause duplicate URLs (flag) | `-noNorm` |

### 10. Retry & Failure Handling

| Option | Short | Purpose | Example |
|--------|-------|---------|---------|
| `-priority` | `-p` | Task priority — lower = higher priority (like Unix nice) | `-priority -2000` |
| `-nMaxRetry` | `-nmr` | Max retries before marking page as "gone" | `-nMaxRetry 5` |
| `-nJitRetry` | `-njr` | Max immediate retries within a single fetch operation (`-1` = disabled) | `-nJitRetry 2` |

### 11. Authentication

| Option | Short | Purpose | Example |
|--------|-------|---------|---------|
| `-authToken` | | Authentication token for protected resources | `-authToken Bearer_xyz123` |

---

## Usage Patterns

### Pattern 1: Simple Page Fetch

```
-expires 1d                                           # 1-day cache
-refresh                                               # Force refresh
-expires 1d -requireSize 300000 -requireImages 5       # With quality checks
```

### Pattern 2: Portal + Items Crawling

```
-expires 1d -outLink a.product-link -topLinks 20 -itemExpires 7d -itemRequireImages 5 -itemRequireSize 500000
```

Portal page caches for 1 day. Extracts up to 20 product links. Each item page caches for 7 days and requires 5+ images and 500KB+ size.

### Pattern 3: Parse and Store

```
-parse -storeContent     # Parse and keep HTML
-parse -dropContent      # Parse only, discard HTML
```

### Pattern 4: With Retry Control

```
-ignoreFailure -nMaxRetry 5 -nJitRetry 2
```

Retries previously failed pages, allows up to 5 total retries, with 2 immediate retries per fetch attempt.

### Pattern 5: Custom Interaction for Heavy Pages

```
-scrollCount 10 -scrollInterval 2s -interactLevel BEST_DATA -pageLoadTimeout 120s
```

Scrolls 10 times with 2s pauses, uses maximum interaction level, waits up to 120s for page load.

### Pattern 6: Task Management

```
-label Q1-electronics -taskId task-abc123 -deadline 2024-01-05T23:59:59Z -expires 1d
```

---

## Parameter Relationships

### Mutual Exclusivity
- **`storeContent` vs `dropContent`**: Both control HTML storage. `-dropContent` wins if both are present.
- **`expires` vs `expireAt`**: Both control cache expiration. Use one or the other; last one set wins.

### Dependencies
- **`-outLink` + `-outLinkPattern` + `-topLinks`**: Work together for link extraction. `-outLink` is required to enable extraction.
- **`-refresh`**: When used, automatically sets `expires=0s`, `itemExpires=0s`, and `ignoreFailure=true`.

### Portal vs Item Options
- Main options (`-expires`, `-scrollCount`, etc.) apply to portal/list pages.
- Item options (`-itemExpires`, `-itemScrollCount`, etc.) apply to detail pages extracted from portals.
- Item options inherit from main options when not explicitly set.
- Portal-specific options (`-outLink`, `-outLinkPattern`, `-topLinks`) have no item equivalents — they only make sense on list pages.

### Interaction Settings Hierarchy
1. `-interactLevel` selects a preset profile.
2. Individual settings (`-scrollCount`, `-scrollInterval`, etc.) override the preset.
3. Item-specific settings (`-itemScrollCount`, etc.) override for item pages only.

---

## Common Pitfalls & Solutions

### Pages not refreshing
Use `-refresh` or `-expires 0s -ignoreFailure`.

### Missing lazy-loaded content
Increase scroll count and interval:
```
-scrollCount 10 -scrollInterval 2s
```

### Timeouts on slow pages
Increase timeouts:
```
-pageLoadTimeout 180s -scriptTimeout 60s
```

### Incomplete pages (too small / missing content)
Set quality requirements and allow more retries:
```
-requireSize 300000 -requireImages 5 -nMaxRetry 5
```

### Too many outlinks extracted
Limit count and filter with regex:
```
-topLinks 20 -outLinkPattern .*/product/.*
```

### Storage growing too fast
Don't store raw HTML for analysis-only tasks:
```
-dropContent
```

### Task running past deadline
Set an explicit deadline:
```
-deadline 2024-01-05T18:00:00Z
```

---

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

---

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
