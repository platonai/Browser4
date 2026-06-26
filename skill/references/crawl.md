---
title: "Crawl Command Reference"
description: "Reference for the crawl command. Recursive website crawling — start from a seed URL and follow links up to a configurable depth."
---

# Crawl Command Reference

Recursive website crawling — start from a seed URL and follow links up to a configurable depth.

## Quick start

```bash
browser4-cli crawl "https://example.com" --out-link-selector "a[href]"
```

## Overview

The `crawl` command loads a seed URL, extracts links matching a CSS selector,
optionally filters them with a regex pattern, loads each linked page, and
optionally recurses to the configured depth. Results are returned as a JSON
array of crawled pages.

**Backend:** `POST /api/crawl` returns a task UUID; poll via `GET /api/crawl/{id}/result`.

## Flags

| Flag | Short | Type | Default | Description |
|---|---|---|---|---|
| `--depth` | `-d` | int | `1` | Maximum crawl depth |
| `--out-link-selector` | `-ol` | string | — | CSS selector to extract links |
| `--out-link-pattern` | `-olp` | regex | `.+` | Regex to filter extracted links |
| `--top-links` | `-tl` | int | `20` | Max links per page |
| `--args` | `-a` | string | — | Raw LoadOptions passthrough |
| `--refresh` | | bool | — | Force fresh fetch |
| `--parse` | | bool | — | Parse pages after fetch |
| `--expires` | | string | — | Cache TTL (`1d`, `1h`, `30m`) |
| `--store-content` | | bool | — | Persist page content |
| `--priority` | `-p` | int | — | Queue priority |
| `--page-load-timeout` | | string | — | Page load timeout |
| `--ignore-url-query` | | bool | — | Strip URL query params |
| `--no-norm` | | bool | — | Disable URL normalization |
| `--readonly` | | bool | — | Non-destructive mode |

## How it works

### Depth = 1 (default)

1. Parse `--args` into `LoadOptions` (JCommander).
2. Load the portal (seed) page via `PulsarSession.loadDocument`.
3. Extract links using `outLinkSelector` CSS selector, normalize to absolute
   URLs, filter by `outLinkPattern`, deduplicate, limit to `topLinks`.
4. Wrap each out-link in a `ParsableHyperlink` with `-parse` and a result-tracking
   parse handler.
5. Submit all to the session's URL pool and await completion.
6. Collect page titles, URLs, content lengths, and depths.

### Depth > 1

1. Create a session with `PulsarSettings.withSequentialBrowsers()` (same as
   `_5_ContinuousCrawler.kt`).
2. Submit the seed URL as a `ParsableHyperlink` with depth=1.
3. The parse handler:
   - Records the page result (URL, title, content length, depth).
   - If `currentDepth < maxDepth`: extracts links matching the selector/pattern,
     filters out already-visited URLs, wraps new links in `ParsableHyperlink`
     with `depth = currentDepth + 1`, submits them.
4. `AgenticContexts.await()` blocks until the URL pool is empty.
5. Timeout: `min(maxDepth × 5 min, 30 min)`.

## LoadOptions passthrough (`--args` / `-a`)

Any `LoadOptions` field can be passed through `-a`:

```bash
browser4-cli crawl "https://example.com" -ol "a[href]" -a "-nMaxRetry 5 -lazyFlush -interactLevel FAST"
```

This appends the raw string to the generated `LoadOptions` args sent to the backend.
Use this for advanced options not covered by dedicated flags.

## Error handling

- **Missing URL**: exits with "A URL is required for crawl."
- **Timeout**: exits with a message and the task ID after the timeout (default 600s,
  configurable via `BROWSER4_CLI_CRAWL_TIMEOUT_SECS`).
- **Server errors**: reported as "Crawl failed: ..." with the server error message.

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
  "pages_found": 5,
  "pages": [
    {"url": "https://...", "title": "Page 1", "contentLength": 12345, "depth": 1},
    ...
  ]
}
```

## URL normalization and dedup

- Visited URLs are normalized: lowercase, trailing slash removed, query string
  stripped.
- The same URL won't be visited twice within a crawl session, preventing cycles.

## Timeout

- CLI-side: 600s default, controlled by `BROWSER4_CLI_CRAWL_TIMEOUT_SECS`.
- Backend depth=1: 5 min.
- Backend depth>1: `min(depth × 5 min, 30 min)`.
