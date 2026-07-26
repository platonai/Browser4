---
title: "WebDB Command Reference"
description: "Reference for webdb-export and webdb-normalize commands. Export cached pages from the web database to local files, and normalize URLs into consistent database keys."
tier: procedure
---

# WebDB Command Reference

Manage the web database (webdb) — Browser4's persistent page cache. Export cached
pages to local files and normalize URLs into consistent database keys.

## Quick start

```bash
# Export all cached pages to a directory
browser4-cli webdb-export "*" ./exported-pages

# Export specific pages by URL
browser4-cli webdb-export "https://example.com,https://example.com/about" ./out

# Normalize a URL to its webdb key form
browser4-cli webdb-normalize "https://example.com/page?utm=tracking"
```

## When to Use

Use **webdb-export** to extract cached page content from the web database after
a crawl or browsing session — the pages have already been fetched and stored;
export copies them to local files without re-fetching.  Use
**webdb-normalize** to discover the exact database key for a URL, which is
useful for scripting or debugging cache lookups.

Prefer **webdb-export** when you need raw page content (HTML) on disk after a
crawl.  Prefer **htmlsnapshot export** when you need structured DOM snapshots
rather than raw HTML.  Prefer **crawl --sql** or **htmlsnapshot query** when
you need extracted data fields, not full page content.

## How It Works

Browser4 maintains a persistent page cache (webdb) keyed by **normalized URL**.
When you `goto`, `crawl`, or otherwise load a page, the fetched content is
stored in webdb.  Subsequent visits to the same URL reuse the cached copy
(controlled by LoadOptions `-expires` / `-refresh`).

- **webdb-export** looks up each URL in the cache, normalizes it, retrieves
  the stored page, and writes it as an `.htm` file to the output directory.
  Wildcard `"*"` exports every page in the database.
- **webdb-normalize** runs URL normalization (lowercase, trailing-slash
  removal, redirect resolution) and returns the result — the exact key used
  for webdb lookups.

Normalization is **server-side**: the browser session must be active for
redirect resolution to work.  Both commands require an open session (use
`goto` or `open` first, or run after a crawl).

## Commands

### `webdb-export`

Export cached pages to a local directory.

| Argument | Required | Description |
|----------|----------|-------------|
| `urls` | Yes | Comma-separated URLs to export, or `"*"` for all pages |
| `output-dir` | Yes | Directory to save exported `.htm` files |

Filenames are derived from the normalized URL.  For example,
`https://example.com/page` becomes `example.com_page.htm`.

The output is a JSON summary:

```json
{
  "total": 3,
  "succeeded": 2,
  "failed": 1,
  "results": [
    {"url": "https://example.com", "status": "ok"},
    {"url": "https://example.com/about", "status": "ok"},
    {"url": "https://example.com/missing", "status": "error", "error": "Page not found in webdb"}
  ]
}
```

### `webdb-normalize`

Normalize a URL to its webdb key form.

| Argument | Required | Description |
|----------|----------|-------------|
| `url` | Yes | URL to normalize |

Returns the normalized URL string.  Normalization includes:
- Lowercasing the hostname
- Removing the default port (`:80` for HTTP, `:443` for HTTPS)
- Removing trailing slashes from the path
- Resolving known redirects (requires an active browser session)
- Validating URL syntax

## Common patterns

### Export all pages after a crawl

```bash
browser4-cli crawl --seed-file urls.txt --depth 0
browser4-cli webdb-export "*" ./crawl-output
```

### Export specific pages for offline analysis

```bash
browser4-cli webdb-export \
  "https://example.com/page1,https://example.com/page2,https://example.com/page3" \
  ./analysis-pages
```

### Check what key a URL will use before crawling

```bash
browser4-cli goto "https://example.com"
browser4-cli webdb-normalize "HTTPS://Example.COM/page?ref=ad#section"
# → https://example.com/page
```

### Script-friendly export with error handling

```bash
result=$(browser4-cli webdb-export "*" ./out --json)
succeeded=$(echo "$result" | jq '.succeeded')
failed=$(echo "$result" | jq '.failed')
echo "Exported $succeeded pages ($failed failed)"
```

## Error handling

| Symptom | Cause | Fix |
|---------|-------|-----|
| `Missing required parameter 'urls'` | No URLs argument provided | Pass comma-separated URLs or `"*"` |
| `Missing required parameter 'outputDir'` | No output directory provided | Pass an output directory path |
| `Session not found` | No active browser session | Run `goto <any-url>` or `open` first |
| `Page not found in webdb` | URL was never fetched or cache expired | Run `goto <url>` first, or use `--refresh` on the crawl |
| `No URLs provided` | Empty URL list or `*` matched nothing | Verify pages were cached (check crawl output) |

## How it compares to other export mechanisms

| Command | Exports | Format | Requires cache? |
|---------|---------|--------|-----------------|
| `webdb-export` | Raw page HTML | `.htm` files | Yes (webdb) |
| `htmlsnapshot export` | Formatted HTML DOM | HTML (use `--clean` for minimal LLM-ready output) | No (works from current page) |
| `screenshot` | Visual page image | PNG | No (renders live) |
| `pdf` | Print-formatted page | PDF | No (renders live) |
| `crawl --sql --format csv -o out.csv` | Extracted data fields | CSV/JSON/table | Yes (uses webdb internally) |

## See also

- [Crawl reference](crawl.md) — populating webdb via recursive crawling
- [LoadOptions Guide](load-options-guide.md) — cache control (`-expires`, `-refresh`, `-storeContent`)
- [Storage state reference](storage-state.md) — cookies, localStorage, sessionStorage management
