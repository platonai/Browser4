---
title: "WebDB Command Reference"
description: "Reference for the `webdb export` and `webdb normalize` commands. Export cached pages from the web database to local files, and normalize URLs into consistent database keys."
tier: procedure
---

# WebDB Command Reference

Manage the web database (webdb) — Browser4's persistent page cache. Export cached
pages to local files and normalize URLs into consistent database keys.

## Quick start

```bash
# Export specific pages by URL
browser4-cli webdb export "https://example.com,https://example.com/about" ./out

# Normalize a URL to its webdb key form
browser4-cli webdb normalize "https://example.com/page?utm=tracking"
```

> **Command form:** `webdb` is a command group — use the spaced form
> (`webdb export`, `webdb normalize`). The flat aliases `webdb-export` /
> `webdb-normalize` are rejected with a pointer to the spaced form.

## When to Use

Use **webdb export** to extract cached page content from the web database after
a crawl or browsing session — the pages have already been fetched and stored;
export copies them to local files without re-fetching.  Use
**webdb normalize** to discover the exact database key for a URL, which is
useful for scripting or debugging cache lookups.

Prefer **webdb export** when you need raw page content (HTML) on disk after a
crawl.  Prefer **htmlsnapshot export** when you need structured DOM snapshots
rather than raw HTML.  Prefer **crawl --sql** or **htmlsnapshot query** when
you need extracted data fields, not full page content.

## How It Works

Browser4 maintains a persistent page cache (webdb) keyed by **normalized URL**.
When you `goto`, `crawl`, or otherwise load a page, the fetched content is
stored in webdb.  Subsequent visits to the same URL reuse the cached copy
(controlled by LoadOptions `-expires` / `-refresh`).

- **webdb export** looks up each URL in the cache, normalizes it, retrieves
  the stored page, and writes it as an `.htm` file to the output directory.
- **webdb normalize** runs URL normalization (lowercase, trailing-slash
  removal, redirect resolution) and returns JSON: the URL you asked for, the
  canonical `normalizedUrl` used for webdb lookups, and the `storageKey` — the
  exact file name `webdb export` writes that page to.

Normalization is **server-side**: the browser session must be active for
redirect resolution to work.  Both commands require an open session (use
`goto` or `open` first, or run after a crawl).

## Commands

### `webdb export`

Export cached pages to a local directory.

| Argument | Required | Description |
|----------|----------|-------------|
| `urls` | Yes | Comma-separated URLs to export |
| `output-dir` | Yes | Directory to save exported `.htm` files |

Filenames are derived from the normalized URL.  For example,
`https://example.com/page` becomes `example.com_page.htm` — the same name
`webdb normalize` reports as `storageKey`.

The output is a JSON summary.  Every entry carries a `status`, and the tallies
keep the three outcomes apart so a 0-byte export is never counted as a success:

```json
{
  "total": 4,
  "succeeded": 2,
  "empty": 1,
  "failed": 1,
  "results": [
    {"url": "https://example.com", "status": "ok", "contentLength": 52134},
    {"url": "https://example.com/about", "status": "ok", "contentLength": 18760},
    {"url": "https://example.com/blank", "status": "empty", "contentLength": 0,
     "error": "Stored page content is empty (0 bytes); re-fetch the URL with -refresh"},
    {"url": "https://example.com/missing", "status": "error", "error": "Page not found in webdb"}
  ]
}
```

- `ok` — the page was exported and the written file has bytes (`contentLength`).
- `empty` — the page was found and written, but the stored record held no bytes
  (`contentLength: 0`); re-fetch the URL with `-refresh`, then export again.
- `error` — the URL is not in webdb (or the write failed); `error` carries the
  reason.

`succeeded` counts only `ok` entries, never a 0-byte file; `empty` and `failed`
account for the rest.

### `webdb normalize`

Normalize a URL to its webdb key form.

| Argument | Required | Description |
|----------|----------|-------------|
| `url` | Yes | URL to normalize |

Returns a JSON object with the request URL and the two forms derived from it:

| Field | Meaning |
|-------|---------|
| `url` | The URL you passed in, echoed back unchanged |
| `normalizedUrl` | The canonical URL — the key used for webdb lookups |
| `storageKey` | The file name `webdb export` writes this page to (e.g. `localhost_18080_ec_dp_B0HLT0001.htm`) |

`storageKey` is the field scripts care about: it is exactly the name the
exported `.htm` file gets, so you can predict or locate the file without listing
the output directory.

Normalization includes:
- Lowercasing the hostname
- Removing the default port (`:80` for HTTP, `:443` for HTTPS)
- Removing trailing slashes from the path
- Resolving known redirects (requires an active browser session)
- Validating URL syntax

## Common patterns

### Export crawled pages after a crawl

```bash
browser4-cli crawl --seed-file urls.txt --depth 0
browser4-cli webdb export "https://example.com/page1,https://example.com/page2" ./crawl-output
```

### Export specific pages for offline analysis

```bash
browser4-cli webdb export \
  "https://example.com/page1,https://example.com/page2,https://example.com/page3" \
  ./analysis-pages
```

### Check what key a URL will use before crawling

```bash
browser4-cli goto "http://localhost:18080/ec/dp/B0HLT0001"
browser4-cli webdb normalize "http://localhost:18080/ec/dp/B0HLT0001"
# {"url":"http://localhost:18080/ec/dp/B0HLT0001",
#  "normalizedUrl":"http://localhost:18080/ec/dp/B0HLT0001",
#  "storageKey":"localhost_18080_ec_dp_B0HLT0001.htm"}
```

### Script-friendly export with error handling

```bash
result=$(browser4-cli webdb export "https://example.com/page1,https://example.com/page2" ./out --json)
payload=$(echo "$result" | jq '.output.result | fromjson')
succeeded=$(echo "$payload" | jq '.succeeded')
empty=$(echo "$payload" | jq '.empty')
failed=$(echo "$payload" | jq '.failed')
echo "Exported $succeeded pages ($empty empty, $failed failed)"
```

Under `--json` the summary is nested at `.output.result` (as a JSON string), hence
the `fromjson` step.

## Flags

The `webdb` commands define no flags of their own — positional arguments only.  The global `-q` / `--quiet` and `--timeout <secs>` are also accepted after the command, since the CLI hoists them into the global flags: `browser4-cli webdb export "https://example.com,https://example.org" ./out --timeout 30`.

## Error handling

| Symptom | Cause | Fix |
|---------|-------|-----|
| `Missing required parameter 'urls'` | No URLs argument provided | Pass comma-separated URLs |
| `Missing required parameter 'outputDir'` | No output directory provided | Pass an output directory path |
| `Session not found` | No active browser session | Run `goto <any-url>` or `open` first |
| `Page not found in webdb` | URL was never fetched or cache expired | Run `goto <url>` first, or use `--refresh` on the crawl |
| `"status": "empty"` (with `contentLength: 0`) | The page is cached but the stored record holds no bytes | Re-fetch the URL with `-refresh`, then export again |
| `No URLs provided` | Empty URL list | Verify pages were cached (check crawl output) |

## How it compares to other export mechanisms

| Command | Exports | Format | Requires cache? |
|---------|---------|--------|-----------------|
| `webdb export` | Raw page HTML | `.htm` files | Yes (webdb) |
| `htmlsnapshot export` | Formatted HTML DOM | HTML (use `--clean` for minimal LLM-ready output) | No (works from current page) |
| `screenshot` | Visual page image | PNG | No (renders live) |
| `pdf` | Print-formatted page | PDF | No (renders live) |
| `crawl --sql --format csv -o out.csv` | Extracted data fields | CSV/JSON/table | Yes (uses webdb internally) |

## See also

- [Crawl reference](crawl.md) — populating webdb via recursive crawling
- [LoadOptions Guide](load-options-guide.md) — cache control (`-expires`, `-refresh`, `-storeContent`)
- [Storage state reference](storage-state.md) — cookies, localStorage, sessionStorage management
