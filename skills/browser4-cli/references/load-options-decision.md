---
title: "LoadOptions — Choosing Options"
description: "Use when deciding which LoadOptions to pass for a page fetch: cache freshness, quality requirements, link extraction, interaction, portal crawling, and storage."
tier: decision
---

# LoadOptions — Choosing Options

## Quick Comparison

| Concern | Key options | Full reference |
|---------|-------------|----------------|
| Cache freshness | `-expires`, `-refresh`, `-ignoreFailure` | [load-options-guide.md](load-options-guide.md#parameter-categories) |
| Page quality | `-requireSize`, `-requireImages`, `-requireAnchors` | [load-options-guide.md](load-options-guide.md#parameter-categories) |
| Dynamic content | `-scrollCount`, `-scrollInterval`, `-interactLevel` | [load-options-guide.md](load-options-guide.md#parameter-categories) |
| Portal + items crawl | `-outLink`, `-topLinks`, `-item*` options | [Portal vs Item Pattern](load-options-guide.md#portal-vs-item-pattern) |
| Storage | `-parse`, `-storeContent`, `-dropContent` | [load-options-guide.md](load-options-guide.md#parameter-categories) |

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

## When to Use Each

- **Cache freshness** — the default fetch reuses the cache; set `-expires 1d` for a daily-fresh policy, `-refresh` to force a re-fetch, `-ignoreFailure` to retry pages that previously failed.
- **Quality requirements** — pages smaller than expected are usually error pages or incomplete renders; `-requireSize BYTES`, `-requireImages COUNT`, and `-requireAnchors COUNT` gate acceptance so bad pages are retried instead of stored.
- **Dynamic content** — lazy-loaded pages need scrolling (`-scrollCount 10 -scrollInterval 2s`); complex SPAs may need `-interactLevel BEST_DATA` for full interaction.
- **Portal + items** — two-tier crawls separate list-page options from detail-page options: `-outLink CSS`/`-topLinks N` on the portal, `-item*` prefixed options on the items.
- **Storage** — analysis-only tasks should `-parse -dropContent` (don't store raw HTML); keep `-storeContent` only when the raw HTML must be persisted.

## Quick Patterns

```bash
# Fresh daily snapshot of a page
browser4-cli crawl --url "https://example.com" --depth 0 -expires 1d -requireSize 200000

# Portal crawl with items (quality-gated)
browser4-cli crawl --url "https://example.com/catalog" -outLink "a.product" -topLinks 20 `
  -itemExpires 7d -itemRequireImages 5

# Heavy dynamic page, analysis only
browser4-cli crawl --url "https://example.com/app" --depth 0 -scrollCount 10 -interactLevel BEST_DATA -parse -dropContent
```

### Passing options through crawl

`crawl` accepts LoadOptions via `--args` / `-a` — either inline or from a file:

```bash
browser4-cli crawl --url "https://example.com" --args "-expires 1d -requireSize 200000"
browser4-cli crawl --url "https://example.com" -a @options.txt
```

### LoadOptions in X-SQL queries

Append space-separated load options to the URL inside `DOM_LOAD_AND_SELECT`:

```sql
SELECT DOM_FIRST_TEXT(DOM, 'h1')
FROM DOM_LOAD_AND_SELECT('https://example.com -expires 1h', 'body')
```

### Combining freshness with quality gates

The common production pattern pairs a freshness policy with quality gates so
stale or broken pages are retried instead of silently stored:

```bash
browser4-cli crawl --url "https://example.com" --depth 0 -expires 1d -requireSize 200000 -requireImages 3 -nMaxRetry 5
```

## Reference Map

- [load-options-guide.md](load-options-guide.md) — complete parameter reference (all flags, defaults, relationships)
- [crawl.md](crawl.md) — how `--args` / `-a` passes LoadOptions to crawl
- [htmlsnapshot-scenarios-amazon.md](htmlsnapshot-scenarios-amazon.md) — quota-friendly load-option usage in real workflows
