---
title: "Crawl Command Reference"
description: "Reference for the crawl command. Recursive website crawling — start from a seed URL and follow links up to a configurable depth."
---

# Crawl Command Reference

Recursive website crawling — start from a seed URL, extract links, load each linked page, and optionally recurse deeper.

## Quick start

```bash
browser4-cli crawl "https://example.com" --out-link-selector "a[href]"
```

> **Note:** `--out-link-selector` is effectively required. Without it, the crawl returns 0 pages — the seed URL is loaded but no out-links are extracted.

## Behavior

The `crawl` command:

1. Loads the seed URL.
2. Extracts links matching `--out-link-selector` (a CSS selector).
3. Optionally filters links by `--out-link-pattern` (regex).
4. Deduplicates and limits to `--top-links` links.
5. Loads each linked page.
6. If `--depth` > 1, repeats steps 2–5 for each loaded page (skipping already-visited URLs).
7. Returns results as human-readable text (or JSON with `--json`).

Crawl runs asynchronously — the command submits a task and polls until completion.

## Flags

| Flag | Short | Type | Default | Description |
|---|---|---|---|---|
| `--depth` | `-d` | int | `1` | Maximum crawl depth (1 = seed + linked pages only) |
| `--out-link-selector` | `-ol` | string | — | CSS selector to extract links from each page |
| `--out-link-pattern` | `-olp` | regex | `.+` | Regex to filter extracted links |
| `--top-links` | `-tl` | int | `20` | Max links extracted per page |
| `--args` | `-a` | string | — | Raw LoadOptions passthrough (see [LoadOptions Guide](load-options-guide.md)) |
| `--refresh` | | bool | — | Force fresh fetch (ignore cache) |
| `--parse` | | bool | — | Parse pages after fetch |
| `--expires` | | string | — | Cache TTL: `1d`, `1h`, `30m`, etc. |
| `--store-content` | | bool | — | Persist page content to storage |
| `--priority` | `-p` | int | — | Queue priority (lower = higher priority) |
| `--page-load-timeout` | | string | — | Max wait for each page load |
| `--ignore-url-query` | | bool | — | Strip query params from URLs (treat `?page=1` and `?page=2` as same URL) |
| `--no-norm` | | bool | — | Disable URL normalization |
| `--readonly` | | bool | — | Non-destructive mode (no side effects on target pages) |
| `--background` | `-bg` | bool | — | Submit crawl and return immediately; use `crawl-list` to track |

## LoadOptions passthrough (`--args` / `-a`)

Any [LoadOptions](load-options-guide.md) field can be passed through `-a`:

```bash
browser4-cli crawl "https://example.com" -ol "a[href]" -a "-nMaxRetry 5 -lazyFlush -interactLevel FAST"
```

This appends the raw string to the generated LoadOptions. Use for advanced options not covered by dedicated flags.

## URL deduplication

- Visited URLs are normalized: lowercase, trailing slash removed, query string always stripped for dedup purposes.
- The same URL is never visited twice within a crawl session, preventing infinite loops.
- Use `--ignore-url-query` to additionally strip query parameters from extracted link hrefs before resolution.
- Use `--no-norm` to disable LoadOptions-level normalization (does not affect internal dedup normalization).

## Timeout

- CLI-side default: 600s. Override with `BROWSER4_CLI_CRAWL_TIMEOUT_SECS` env var.
- Backend timeout scales with depth: roughly 5 min per level, capped at 30 min.

## Output format

### Human-readable (default)

```
Crawl task submitted: <uuid>
Crawling... 3 pages found so far
Crawl completed. 5 pages found.
  depth=1 | https://example.com/page1 | Page 1 Title
  depth=1 | https://example.com/page2 | Page 2 Title
  ...
```

### JSON (`--json`)

```json
{
  "task_id": "<uuid>",
  "pagesFound": 5,
  "pages": [
    {"url": "https://...", "title": "Page 1", "contentLength": 12345, "depth": 1},
    {"url": "https://...", "title": "Page 2", "contentLength": 67890, "depth": 1}
  ]
}
```

## Common patterns

### Shallow crawl (depth 1) — list page + detail pages

```bash
browser4-cli crawl "https://example.com/products" \
  --out-link-selector "a.product-link" \
  --top-links 50 \
  --parse \
  --expires 1d
```

### Deep crawl (depth > 1) — follow links recursively

```bash
browser4-cli crawl "https://example.com/docs" \
  --out-link-selector "a[href]" \
  --out-link-pattern ".*/docs/.*" \
  --depth 3 \
  --top-links 30
```

### Fresh crawl with quality requirements

```bash
browser4-cli crawl "https://example.com" \
  --out-link-selector "a[href]" \
  --refresh \
  --args "-requireSize 100000 -scrollCount 5"
```

## Error handling

| Situation | Behavior |
|---|---|
| Missing URL | Exits with "A URL is required for crawl." |
| Timeout | Exits with message + task ID after timeout expires |
| Server error | Exits with "Crawl failed: ..." and server error details |
| No links found | Completes with 0 pages (verify your `--out-link-selector` matches) |
