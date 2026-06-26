---
title: "Swarm — Parallel Scraping & Structured Data Extraction"
description: "Reference for swarm commands (create, submit, query, status, result). Orchestrate parallel scraping and structured data extraction across multiple browser contexts."
---

# Swarm — Parallel Scraping & Structured Data Extraction

Orchestrate parallel scraping and structured data extraction across multiple browser contexts. All subcommands use spaced form (`swarm <subcommand>`) and are task-ID based — they do not depend on the current CLI browser session slot.

## Architecture

```
swarm create        →  opens a swarm session (fixed session ID: SWARM)
swarm submit        →  enqueues scrape jobs
swarm query         →  enqueues X-SQL jobs (preferred for structured extraction)
swarm status <id>   →  polls job progress
swarm result <id>   →  fetches the completed job payload
```

A swarm session manages multiple isolated browser contexts running in parallel. Each context can open tabs, load pages, and execute X-SQL queries independently.

## swarm create

Create a swarm scrape session with parallel browser contexts.

```bash
browser4-cli swarm create [--profile-mode=SEQUENTIAL|TEMPORARY] [--max-open-tabs=8] [--max-browser-contexts=2] [--display-mode=GUI|HEADLESS|SUPERVISED]
```

| Option | Default | Description |
|---|---|---|
| `--profile-mode` | `SEQUENTIAL` | `SEQUENTIAL` reuses profile across runs; `TEMPORARY` starts fresh each time |
| `--max-open-tabs` | `8` | Max open tabs per browser context |
| `--max-browser-contexts` | `2` | Number of isolated browser instances (each with own profile/cookie jar) |
| `--display-mode` | `GUI` | `GUI`, `HEADLESS`, or `SUPERVISED` |

Creates a swarm session with fixed session ID `SWARM` stored in the current CLI slot. The session persists until `browser4-cli close` or `close-all`.

## swarm submit

Submit URLs as asynchronous scrape jobs. Accepts a direct URL, a `--seed-file`, or both.

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

> Prefer `swarm query` over `swarm submit --sql` for X-SQL extraction — it enforces `--sql` as required.

## swarm query

Submit X-SQL queries to extract structured data from loaded pages. **Preferred** for structured extraction workflows.

```bash
browser4-cli swarm query [url] --sql "<query>" [--seed-file=./urls.txt] [--refresh]
```

| Argument/Option | Required | Description |
|---|---|---|
| `url` (positional) | No | Target page URL. Omit when using `--seed-file` alone |
| `--sql` | **Yes** | X-SQL query. Inline text or file path prefixed with `@` (e.g. `--sql @query.sql`). Use `@url` as placeholder |
| `--seed-file` | No | Run the same query against every URL in the file |

`--deadline` and `--expires` also supported (same as `swarm submit`).

```bash
# Inline query against a single URL
browser4-cli swarm query "https://www.amazon.com/dp/B08PP5MSVB" --sql "
  SELECT
    dom_base_uri(dom) AS url,
    dom_first_text(dom, '#productTitle') AS title,
    dom_first_slim_html(dom, 'img:expr(width > 400)') AS img
  FROM load_and_select(@url, 'body');
"

# Query file against every URL in a seed file
browser4-cli swarm query --sql @query.sql --seed-file=./urls.txt --refresh
```

### X-SQL Quick Reference

Core pattern: `SELECT <fn>(dom, <selector>) FROM load_and_select(@url, '<scope>');`

Common extraction functions: `dom_base_uri`, `dom_first_text`, `dom_all_texts`, `dom_first_href`, `dom_all_hrefs`, `dom_first_src`, `dom_first_slim_html`, `dom_all_slim_html`, `dom_first_attr`.

Selector extensions: `img:expr(width > 400)` for conditional matching, `div.product:expr(1)` for nth element (0-based).

Full reference: **[x-sql.md](x-sql.md)**.

## swarm status & result

```bash
browser4-cli swarm status <task-id>   # poll job progress (returns JSON with isDone, status, message)
browser4-cli swarm result <task-id>   # fetch completed job payload (results in resultSet array)
```

Wait for `isDone: true` from `swarm status` before calling `swarm result`.

Example result:

```json
{"id":"scrape-task-4","isDone":true,"statusCode":200,"resultSet":[{"url":"...","title":"...","price":"$29.99"}]}
```

## Complete Workflow

```bash
browser4-cli swarm create --display-mode=HEADLESS
browser4-cli swarm submit https://example.com/direct --seed-file=./urls.txt --refresh --store-content
browser4-cli swarm status scrape-task-4     # poll until isDone: true
browser4-cli swarm result scrape-task-4
browser4-cli close                          # free resources
```

## Error Handling

- All subcommands exit non-zero on failure. Check stderr.
- `swarm status` / `swarm result` print the backend payload as-is — inspect `statusCode` and `message` on failure.
- Missing LLM/API key for X-SQL processing surfaces as a task-level error visible via `swarm status` / `swarm result`.
- Use `--deadline` to bound long-running tasks.

## Notes

- Swarm subcommands are not supported in `batch` mode.
- The swarm session uses fixed session ID `SWARM` — it doesn't share state with named (`-s=<name>`) or default sessions.
- Seed files support thousands of URLs; control parallelism with `--max-open-tabs` and `--max-browser-contexts`.
- Always close the swarm session when done (`browser4-cli close` or `close-all`) to free resources.
