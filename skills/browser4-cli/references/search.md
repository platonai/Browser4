---
title: "Search Command Reference"
description: "Reference for the search command family. Web search via Tavily (default) or Bocha, with optional result scraping via the browser session."
tier: procedure
---

# Search Command Reference

Web search via a pluggable provider (Tavily by default, Bocha when
`search.provider=bocha`) — submit a query, poll for the result, and
optionally scrape each hit's full-page content via the browser session.

The search family mirrors the crawl family: `search` submits and returns a
task id; `search-status` / `search-result` poll; `search-cancel` stops a
running task.

## Quick start

```bash
# Quick search, print results when done
browser4-cli search "Rust async runtime comparison"

# Restrict to two domains, return 5 results
browser4-cli search "browser automation Rust" \
  --max-results 5 \
  --include-domains "github.com,docs.rs"

# Search + scrape each result's full content (markdown)
browser4-cli search "GLM-5.2 benchmarks" --scrape

# Background submission — return immediately, poll later
browser4-cli search "long-tail query" --background
browser4-cli search-status <task-id>
browser4-cli search-result <task-id>
```

## When to Use

Use **search** when you need URLs that match a natural-language query —
discovery, not navigation. Once you have the URLs, follow up with **crawl**
(depth-0 bulk fetch + X-SQL) or **swarm** (parallel scraping) if you need
structured extraction from many pages at once.

Prefer **scrape** mode (`--scrape`) when you need the full content of a
small result set (≤ 10 hits): one browser tab per result, slower but
returns markdown/html alongside the snippet.

## How It Works

1. `search` POSTs to `POST /api/search` with the query and options. The
   server returns a task id immediately and runs the search asynchronously.
2. The active `SearchProvider` (Tavily or Bocha, selected by
   `search.provider`) calls the external search API and returns raw
   results — title, URL, snippet.
3. If `--scrape` is set, the server opens a browser tab per result URL and
   runs `select dom_markdown(dom) as content from load_and_select('@url', ':root')`
   to extract the full-page content. One bad URL never aborts the whole task.
4. `search-status` / `search-result` GET the task record; `search-cancel`
   POSTs to cancel a running task.

## Commands

### `search <query>`

Submit a web search. Returns a task id for polling.

| Option | Description | Default |
|---|---|---|
| `<query>` (positional) | The search query (required) | — |
| `--max-results <n>` / `-n` | Maximum number of results (1-50) | `10` |
| `--scrape` | Fetch and extract content from each result URL via the browser session | off |
| `--scrape-format <fmt>` | Output format for scraped content: `markdown` or `html` | `markdown` |
| `--topic <t>` | Search topic: `general` or `news` | `general` |
| `--time-range <r>` | Restrict to recent results: `day`, `week`, `month`, or `year` | no restriction |
| `--include-domains <list>` | Comma-separated domains to restrict results to | no restriction |
| `--exclude-domains <list>` | Comma-separated domains to exclude | no restriction |
| `--timeout <dur>` | Per-task budget before the server cancels it: seconds or `30s`, `1m`, `10m` | `60s` (max `10m`) |
| `--background` / `--bg` | Submit and return immediately; poll with `search-status` | off |

### `search-status <id>`

Get the current status (and partial results) of a search task. Same record
shape as `search-result`; semantically indicates polling.

### `search-result <id>`

Get the final result of a search task. Use `--verbose` to also print one
line per result (title + URL) to stderr.

### `search-cancel <id>`

Cancel a running search task. Returns `cancelled: true` if a worker was
found and stopped, `false` otherwise (unknown id, already finished).

## Provider configuration

The provider is selected by the `search.provider` property (defaults to
`tavily` when unset). Each provider needs its API key:

### Tavily (default)

```bash
# env var (recommended)
export TAVILY_API_KEY=tvly-xxxxxxxxxxxx
# or in application.properties
search.tavily.api.key=tvly-xxxxxxxxxxxx
```

Get a key at <https://tavily.com>.

### Bocha (博查)

```bash
# select the provider
search.provider=bocha
# env var
export BOCHA_API_KEY=sk-xxxxxxxxxxxx
# or in application.properties
search.bocha.api.key=sk-xxxxxxxxxxxx
```

## Common patterns

### Quick lookup, then deep scrape

```bash
# Find candidate URLs
browser4-cli search "X-SQL tutorial platon" --max-results 5 --background
# (poll, then read)
browser4-cli search-result <task-id> --verbose
# Pick one URL and crawl it for structured extraction
browser4-cli crawl "https://example.com/xsql-tutorial" --depth 0 \
  --sql "SELECT DOM_FIRST_TEXT(dom, 'h1') AS title, DOM_MARKDOWN(dom) AS body FROM DOM_LOAD_AND_SELECT(@url, ':root')" \
  --format json
```

### News search with time range

```bash
browser4-cli search "Rust 1.85 release" --topic news --time-range week
```

### Site-restricted search

```bash
# Only results from two domains, exclude a third
browser4-cli search "tokio runtime" \
  --include-domains "docs.rs,tokio.rs" \
  --exclude-domains "github.com"
```

### Scrape mode — full content per result

```bash
browser4-cli search "GLM-5.2 vs GPT-4o" --scrape --scrape-format markdown --max-results 5
# The `results[].scrapedContent` field of the response carries the full page content.
```

## Notes

- The search API runs as an async task like crawl: submit returns a task id,
  poll for the outcome. The task store is bounded at 100 entries (Caffeine
  LRU) and tasks self-expire after 1 day.
- A search without `--scrape` is fast (seconds): the CLI usually polls once
  and gets the result. With `--scrape`, expect ~1-2s per result URL.
- `--timeout` uses the same duration spelling as crawl: bare number = seconds,
  or `30s`, `1m`, `10m`. Values above 10 minutes are clamped.
- All four commands support `-s <session>` / `--session <name>` as a global
  flag — must appear before the command name, not after. See
  [sessions.md](sessions.md) for details.

## See also

- [crawl.md](crawl.md) — recursive crawling, seed-file bulk fetch, X-SQL extraction
- [swarm.md](swarm.md) — parallel scraping across browser contexts
- [sessions.md](sessions.md) — global session flag rules
