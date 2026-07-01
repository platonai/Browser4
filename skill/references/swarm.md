---
title: "Swarm — Parallel Scraping & Structured Data Extraction"
description: "Reference for swarm commands (create, submit, query, status, result). Orchestrate parallel scraping and structured data extraction across multiple browser contexts."
tier: procedure
---

# Swarm — Parallel Scraping & Structured Data Extraction

Orchestrate parallel scraping and structured data extraction across multiple browser contexts. All subcommands use spaced form (`swarm <subcommand>`) and are task-ID based.

## Quick Start

```bash
browser4-cli swarm create --display-mode=HEADLESS
browser4-cli swarm query --sql @query.sql --seed-file=./urls.txt --refresh
browser4-cli swarm status scrape-task-4     # poll until isDone: true
browser4-cli swarm result scrape-task-4
browser4-cli close                          # free resources
```

## When to Use

Use **swarm** when you need parallel execution across multiple browser contexts for high throughput. Prefer **crawl** for simpler sequential multi-page workflows with built-in link discovery. Prefer **loop** for repeated monitoring at intervals.

## How It Works

A swarm session manages multiple isolated browser contexts running in parallel. Each context has its own profile/cookie jar and can open tabs, load pages, and execute X-SQL queries independently. Jobs are enqueued and distributed across contexts automatically.

```
swarm create        →  opens a swarm session (fixed session ID: SWARM)
swarm submit        →  enqueues scrape jobs
swarm query         →  enqueues X-SQL jobs (preferred for structured extraction)
swarm status <id>   →  polls job progress
swarm result <id>   →  fetches the completed job payload
```

## Patterns

### 1. Create a Swarm Session

```bash
browser4-cli swarm create [--profile-mode=SEQUENTIAL|TEMPORARY] [--max-open-tabs=8] [--max-browser-contexts=2] [--display-mode=GUI|HEADLESS|SUPERVISED]
```

| Option | Default | Description |
|---|---|---|
| `--profile-mode` | `SEQUENTIAL` | `SEQUENTIAL` reuses profile across runs; `TEMPORARY` starts fresh |
| `--max-open-tabs` | `8` | Max open tabs per browser context |
| `--max-browser-contexts` | `2` | Number of isolated browser instances |
| `--display-mode` | `GUI` | `GUI`, `HEADLESS`, or `SUPERVISED` |

The session persists until `browser4-cli close` or `close-all`.

### 2. Submit Scrape Jobs

```bash
browser4-cli swarm submit <url> [--seed-file=./urls.txt] [--deadline=ISO] [--expires=1d] [--refresh] [--parse] [--store-content]
```

| Argument/Option | Description |
|---|---|
| `url` (positional) | Direct URL to scrape. Omit when using `--seed-file` alone |
| `--seed-file` | Plain-text file, one URL per line. Blank lines and `#` comments ignored |
| `--deadline` | ISO 8601 deadline (e.g. `2026-03-30T23:59:59Z`) |
| `--expires` | Cache expiration (e.g. `1d`, `1h`, `30m`) |
| `--refresh` | Force fresh fetch, ignore cache |
| `--parse` | Parse page immediately after fetching (required for later X-SQL queries) |
| `--store-content` | Persist page content to backend storage |

> **Note:** Prefer `swarm query` over `swarm submit --sql` for X-SQL extraction — it enforces `--sql` as required.

### 3. Submit X-SQL Extraction Jobs (Preferred)

```bash
browser4-cli swarm query [url] --sql "<query>" [--seed-file=./urls.txt] [--refresh]
```

| Argument/Option | Required | Description |
|---|---|---|
| `url` (positional) | No | Target page URL. Omit when using `--seed-file` alone |
| `--sql` | **Yes** | X-SQL query. Inline text or file path prefixed with `@` (e.g. `--sql @query.sql`). Use `@url` as placeholder |
| `--seed-file` | No | Run the same query against every URL in the file |

`--deadline` and `--expires` also supported.

```bash
# Query file against every URL in a seed file
browser4-cli swarm query --sql @query.sql --seed-file=./urls.txt --refresh
```

Core X-SQL pattern: `SELECT <fn>(dom, <selector>) FROM load_and_select(@url, '<scope>');`

Common extraction functions: `dom_base_uri`, `dom_first_text`, `dom_all_texts`, `dom_first_href`, `dom_all_hrefs`, `dom_first_src`, `dom_first_slim_html`, `dom_all_slim_html`, `dom_first_attr`. Full reference: [x-sql.md](x-sql.md).

### 4. Poll Status & Fetch Results

```bash
browser4-cli swarm status <task-id>   # returns JSON with isDone, status, message
browser4-cli swarm result <task-id>   # returns completed payload (resultSet array)
```

Wait for `isDone: true` before calling `swarm result`.

Example result:
```json
{"id":"scrape-task-4","isDone":true,"statusCode":200,"resultSet":[{"url":"...","title":"...","price":"$29.99"}]}
```

## Errors & Recovery

| Symptom | Recovery |
|----------|---------|
| All subcommands exit non-zero | Check stderr for details |
| Task not done yet | `swarm status` shows `isDone: false` — wait and retry |
| Missing LLM/API key | Surfaces as task-level error in `swarm status` / `swarm result` |
| Long-running tasks | Set `--deadline` to bound execution |
| Swarm subcommands in batch mode | Not supported — use standalone commands |

## Notes

- The swarm session uses fixed session ID `SWARM` — it doesn't share state with named (`-s=<name>`) or default sessions.
- Seed files support thousands of URLs; control parallelism with `--max-open-tabs` and `--max-browser-contexts`.
- Always close the swarm session when done (`browser4-cli close` or `close-all`) to free resources.
