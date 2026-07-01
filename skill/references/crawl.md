---
title: "Crawl Command Reference"
description: "Reference for the crawl command. Recursive website crawling from a URL or seed file, with optional X-SQL data extraction and multi-format output."
tier: procedure
---

# Crawl Command Reference

Recursive website crawling — start from a URL or seed file, follow links up to
a configurable depth, and optionally extract structured data from each page
with X-SQL.

## Quick start

```bash
# Link discovery from a single URL
browser4-cli crawl "https://example.com" --out-link-selector "a[href]"

# Bulk fetch from a seed file (no link discovery)
browser4-cli crawl --seed-file urls.txt --depth 0

# Bulk fetch + X-SQL extraction to CSV
browser4-cli crawl --seed-file urls.txt --sql @extract.sql --format csv -o results.csv
```

> **Note:** `--out-link-selector` is required for link discovery (depth >= 1).
> Without it, the crawl returns 0 pages.  For depth 0 (bulk fetch), no selector
> is needed.

## When to Use

Use **crawl** for sequential multi-page workflows with built-in link discovery, seed-file bulk processing, and X-SQL extraction to structured output (CSV/JSON/table). Prefer **swarm** for parallel high-throughput extraction. Prefer **loop** for repeated monitoring at intervals. Prefer **domsnapshot query** for extracting from a single page.

## How It Works

Crawl loads seed URLs, optionally follows links up to a configurable depth, deduplicates visited pages, and optionally runs an X-SQL query against each page. Results are aggregated and formatted as table, CSV, or JSON. Use `--background` for async execution.

## Modes

### Link discovery mode (depth >= 1)

The classic crawl: start from a seed URL, extract links, load linked pages, and
optionally recurse deeper.

1. Loads each seed URL.
2. Extracts links matching `--out-link-selector` (a CSS selector).
3. Optionally filters links by `--out-link-pattern` (regex).
4. Deduplicates and limits to `--top-links` links.
5. Loads each linked page.
6. If `--depth` > 1, repeats steps 2–5 for loaded pages (skipping visited URLs).
7. Returns results as human-readable text (or JSON with `--json`).

### Bulk fetch mode (depth 0)

Load each URL directly without link discovery.  Ideal for:
- Processing a list of known detail pages (product pages, articles)
- Combining with `--sql` for structured data extraction
- When you have the URLs and just need the page content + extraction

```bash
browser4-cli crawl --seed-file product-urls.txt --depth 0 --refresh
```

### X-SQL extraction mode (with --sql)

When `--sql` is provided, the query is executed against each crawled page.
The `@url` placeholder is replaced with the page URL server-side.  Results are
aggregated across all pages and formatted according to `--format`.

```bash
browser4-cli crawl --seed-file urls.txt --depth 0 --sql "
  SELECT
    dom_base_uri(dom) AS url,
    dom_first_text(dom, 'h1') AS title,
    dom_first_text(dom, '.price') AS price
  FROM load_and_select(@url, 'body')
" --format table
```

## Flags

### Core flags

| Flag | Short | Type | Default | Description |
|---|---|---|---|---|
| `url` (positional) | | string | — | Starting URL. Omit when using `--seed-file` |
| `--seed-file` | | string | — | File with URLs to crawl, one per line. Lines starting with `#` are comments |
| `--depth` | `-d` | int | `1` | 0 = fetch only (no links); 1+ = follow links to that depth |

### X-SQL extraction flags

| Flag | Type | Default | Description |
|---|---|---|---|
| `--sql` | string | — | X-SQL query. Use `@url` as page URL placeholder. Prefix with `@` to read from file |
| `--sql-stdin` | bool | — | Read query from stdin (avoids shell quoting issues) |
| `--sql-base64` | bool | — | Base64-decode the query value before execution |

### Output flags

| Flag | Short | Type | Default | Description |
|---|---|---|---|---|
| `--format` | | string | `table` | Output format: `json`, `csv`, or `table` |
| `--output` | `-o` | string | — | Write results to file instead of stdout |

### Link discovery flags

| Flag | Short | Type | Default | Description |
|---|---|---|---|---|
| `--out-link-selector` | `-ol` | string | — | CSS selector to extract links from each page |
| `--out-link-pattern` | `-olp` | regex | `.+` | Regex to filter extracted links |
| `--top-links` | `-tl` | int | `20` | Max links extracted per page |

### LoadOptions flags

| Flag | Short | Type | Description |
|---|---|---|---|
| `--args` | `-a` | string | Raw LoadOptions passthrough (see [LoadOptions Guide](load-options-guide.md)) |
| `--refresh` | | bool | Force fresh fetch (ignore cache) |
| `--parse` | | bool | Parse pages after fetch |
| `--expires` | | string | Cache TTL: `1d`, `1h`, `30m`, etc. |
| `--store-content` | | bool | Persist page content to storage |
| `--priority` | `-p` | int | Queue priority (lower = higher priority) |
| `--page-load-timeout` | | string | Max wait for each page load |
| `--ignore-url-query` | | bool | Strip query params from URLs |
| `--no-norm` | | bool | Disable URL normalization |
| `--readonly` | | bool | Non-destructive mode |

### Async flag

| Flag | Short | Type | Description |
|---|---|---|---|
| `--background` | `-bg` | bool | Submit crawl and return immediately; use `crawl list` to track |

## Output formats

### Table (default)

Aligned columns with header and separator:

```
  url                                    | title         | price
  ---------------------------------------+---------------+-------
  https://example.com/product/1          | Product One   | $19.99
  https://example.com/product/2          | Product Two   | $29.99
```

### CSV

Standard CSV with header row.  Fields containing commas, quotes, or newlines
are quoted.

```csv
url,title,price
https://example.com/product/1,Product One,$19.99
https://example.com/product/2,"Product Two, Special Edition",$29.99
```

### JSON

Pretty-printed JSON array of row objects:

```json
[
  {
    "url": "https://example.com/product/1",
    "title": "Product One",
    "price": "$19.99"
  }
]
```

### Page listing (no --sql)

When no X-SQL is provided, the default output lists crawled pages:

```
Crawl task submitted: 550e8400-e29b-41d4-a716-446655440000
  URLs: 3
Crawling... 1 pages found so far

Crawl completed. 3 pages found.
  depth=0 | https://example.com/page1 | Page 1 Title
  depth=0 | https://example.com/page2 | Page 2 Title
  depth=0 | https://example.com/page3 | Page 3 Title
```

## Common patterns

### Bulk product detail extraction

```bash
# Extract product URLs from search results (via eval or X-SQL), write to urls.txt
browser4-cli crawl --seed-file urls.txt --depth 0 --refresh \
  --sql @extract.sql --format csv -o products.csv
```

`extract.sql`:
```sql
SELECT
  dom_base_uri(dom) AS url,
  dom_first_text(dom, '#productTitle') AS title,
  dom_first_text(dom, '.a-price .a-offscreen') AS price,
  dom_first_text(dom, '#acrCustomerReviewText') AS rating,
  dom_first_text(dom, '#feature-bullets') AS features
FROM load_and_select(@url, 'body')
```

### Shallow crawl with extraction (list page + detail pages)

```bash
browser4-cli crawl "https://example.com/products" \
  --out-link-selector "a.product-link" \
  --top-links 50 \
  --depth 1 \
  --sql "SELECT dom_first_text(dom, 'h1') AS title, dom_first_text(dom, '.price') AS price FROM load_and_select(@url, 'body')" \
  --format json
```

### Deep crawl (depth > 1) — recursive link following

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

### X-SQL from stdin (avoids shell quoting)

```bash
browser4-cli crawl --seed-file urls.txt --depth 0 --sql-stdin --format table < query.sql
```

### X-SQL from file (@ prefix)

```bash
browser4-cli crawl --seed-file urls.txt --depth 0 --sql @extract.sql --format csv -o out.csv
```

## LoadOptions passthrough (`--args` / `-a`)

Any [LoadOptions](load-options-guide.md) field can be passed through `-a`:

```bash
browser4-cli crawl "https://example.com" -ol "a[href]" -a "-nMaxRetry 5 -lazyFlush -interactLevel FAST"
```

## URL deduplication

- Visited URLs are normalized: lowercase, trailing slash removed, query string
  always stripped for dedup purposes.
- The same URL is never visited twice within a crawl session.
- Use `--ignore-url-query` to additionally strip query parameters from extracted
  link hrefs before resolution.
- Use `--no-norm` to disable LoadOptions-level normalization (does not affect
  internal dedup normalization).

## Seed files

Plain text, one URL per line.  Blank lines and lines starting with `#` are
ignored.

```text
# Laser-Engraved Crystal products
https://www.amazon.com/dp/B0C17W3Q9B
https://www.amazon.com/dp/B0CXYZ1234
https://www.amazon.com/dp/B0DEXAMPLE
```

When both a positional `url` and `--seed-file` are provided, the URL is
prepended to the seed file list.

## Timeout

- CLI-side default: 600s. Override with `BROWSER4_CLI_CRAWL_TIMEOUT_SECS` env var.
- Backend timeout scales with depth: roughly 5 min per level, capped at 30 min.

## Error handling

| Situation | Behavior |
|---|---|
| No URLs provided | Exits with "No URLs provided. Specify a URL argument or --seed-file." |
| Empty seed file | Exits with "No URLs provided." after parsing |
| Timeout | Exits with message + task ID; increase `BROWSER4_CLI_CRAWL_TIMEOUT_SECS` |
| Server error | Exits with "Crawl failed: ..." and server error details |
| No links found (depth >= 1) | Completes with 0 pages; verify `--out-link-selector` |
| Invalid --format | Exits with "Invalid --format '...'. Expected: json, csv, or table" |
| X-SQL failure on one page | Page logged with error; other pages continue normally |

## Rate Limiting & Polite Scraping

Crawl includes built-in rate limiting between page loads. For manual batch operations,
follow these guidelines:

- Add `wait 1000-3000` (1-3 seconds) between rapid navigations on the same site
- Amazon and similar sites may show CAPTCHAs under aggressive automated access — longer delays reduce risk
- Use `eval` or `domsnapshot get all` to batch-extract from a single page load when possible, rather than navigating to each detail page individually
- Prefer `crawl` with conservative `--depth` and `--page-load-timeout` for automated multi-page traversal
- For `swarm`, control parallelism with `--max-browser-contexts` and `--max-open-tabs`

## See also

- [X-SQL: DOM_LOAD_AND_SELECT](x-sql-dom-load-select.md) — the table-source
  function for loading pages in X-SQL queries
- [Swarm reference](swarm.md) — parallel scraping and X-SQL extraction across
  multiple browser contexts
- [Multi-product extraction guide](../../docs/multi-product-extraction.md) —
  choosing between crawl, swarm, and other approaches for bulk data extraction
- [LoadOptions Guide](load-options-guide.md) — full LoadOptions reference
