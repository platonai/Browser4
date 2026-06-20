# Swarm — Parallel Scraping & Structured Data Extraction

Orchestrate parallel scraping and structured data extraction across multiple browser contexts. Designed for high-throughput jobs like refreshing a curated URL list, supervised fan-out browsing, and repeatable selector-based scraping with explicit output artifacts.

## Architecture

A swarm session manages multiple isolated browser contexts running in parallel under a single backend session. Each context can open tabs, load pages, and execute X-SQL queries independently. The CLI coordinates the full lifecycle:

```
swarm create        →  opens a swarm session (fixed session ID: SWARM)
swarm submit        →  enqueues scrape jobs
swarm query         →  enqueues X-SQL jobs (preferred for structured extraction)
swarm status <id>   →  polls job progress
swarm result <id>   →  fetches the completed job payload
```

All swarm subcommands use the spaced form (`swarm <subcommand>`, not `swarm-<subcommand>`). They are task-ID based and do not depend on the current saved CLI browser session slot.

## Comparison: Standard Commands vs Agent vs Swarm

| Interface | Model | Use when |
|---|---|---|
| Standard commands | Single action per invocation | You know the exact refs/selectors and want precise control |
| Agent CLI | Natural-language task → autonomous execution | You have a goal but don't know the page structure; multi-step exploration |
| Swarm CLI | Parallel contexts + X-SQL queries | High-throughput scraping, structured extraction across many pages |

## swarm create

Create a swarm scrape session that provisions parallel browser contexts.

```bash
# Minimal — defaults: SEQUENTIAL profile, 8 max tabs, 2 contexts, GUI display
browser4-cli swarm create

# Full configuration
browser4-cli swarm create \
  --profile-mode=TEMPORARY \
  --max-open-tabs=12 \
  --max-browser-contexts=3 \
  --display-mode=HEADLESS
```

### Options

| Option | Default | Description |
|---|---|---|
| `--profile-mode` | `SEQUENTIAL` | Browser profile mode. Supported: `SEQUENTIAL` or `TEMPORARY`. `SEQUENTIAL` reuses the profile across runs; `TEMPORARY` starts fresh each time. |
| `--max-open-tabs` | `8` | Maximum open tabs per browser context. Higher values increase parallelism at the cost of memory. |
| `--max-browser-contexts` | `2` | Number of isolated browser environments. Each context is a separate browser instance with its own profile and cookie jar. |
| `--display-mode` | `GUI` | Display mode. Supported: `GUI` (visible windows), `HEADLESS` (no UI), `SUPERVISED` (visible but unattended). |

### Notes

- Creates a swarm session using the fixed session ID `SWARM` and stores it in the current CLI slot.
- The session persists until explicitly closed with `browser4-cli close` (for the slot) or `browser4-cli close-all`.
- To verify the session is active, run `browser4-cli list`.

## swarm submit

Submit URLs or X-SQL payloads as asynchronous scrape jobs. Accepts a direct URL, a `--seed-file`, or both.

```bash
# Submit a single URL
browser4-cli swarm submit https://example.com/direct

# Submit a URL with load options
browser4-cli swarm submit https://example.com/direct \
  --deadline=2026-03-30T00:00:00Z \
  --expires=1d \
  --refresh \
  --store-content

# Submit from a seed file (one URL per line)
browser4-cli swarm submit --seed-file=./swarm-seeds.txt
```

### Arguments and Options

| Argument/Option | Required | Description |
|---|---|---|
| `url` (positional) | No | A direct URL to scrape. Omit when using `--seed-file` alone. |
| `--seed-file` | No | Path to a plain-text file with one URL per line. Blank lines and lines beginning with `#` are ignored. |
| `--sql` | No | X-SQL query to execute. Inline text or a file path prefixed with `@` (e.g. `--sql @query.sql`). When provided, the CLI sends a structured JSON body to `SwarmController.query(query)` instead of `SwarmController.submit(payload)`. |
| `--deadline` | No | ISO 8601 deadline for task completion (e.g. `2026-03-30T23:59:59Z`). Tasks that exceed the deadline may be cancelled by the backend. |
| `--expires` | No | Cache expiration duration (e.g. `1d`, `1h`, `30m`). Controls how long fetched content is considered fresh. |
| `--refresh` | No | Force a fresh fetch, ignoring any cached copy of the page. |
| `--parse` | No | Parse the page immediately after fetching (extract the DOM/accessibility tree). Required for subsequent X-SQL queries against the loaded page. |
| `--store-content` | No | Persist page content to backend storage. Useful for audit trails and later retrieval. |

> **Tip:** When you only need structured extraction via X-SQL, prefer `swarm query` over `swarm submit --sql`. The `swarm query` command is purpose-built for this workflow and enforces `--sql` as required.

### Seed File Format

```text
# urls for the swarm crawler
https://example.com/seed-1
https://example.com/seed-2
```

- One URL per line.
- Blank lines are skipped.
- Lines beginning with `#` are treated as comments and ignored.

### Output

On success, prints `Submitted:` followed by the task ID(s). Capture this ID for use with `swarm status` and `swarm result`.

## swarm query

Submit an X-SQL query to extract structured data from a loaded webpage. This is the preferred command for structured extraction workflows.

```bash
# Inline X-SQL query
browser4-cli swarm query "https://www.amazon.com/dp/B08PP5MSVB" --sql "
  SELECT
    dom_base_uri(dom) AS url,
    dom_first_text(dom, '#productTitle') AS title,
    dom_first_slim_html(dom, 'img:expr(width > 400)') AS img
  FROM load_and_select(@url, 'body');
"

# Read query from a file
browser4-cli swarm query "https://www.amazon.com/dp/B08PP5MSVB" --sql @query.sql

# Run the same query against every URL in a seed file
browser4-cli swarm query --sql @query.sql --seed-file=./urls.txt --refresh
```

### Arguments and Options

| Argument/Option | Required | Description |
|---|---|---|
| `url` (positional) | No | Target page URL to load and run the query against. Omit when using `--seed-file` alone. |
| `--sql` | **Yes** | X-SQL query. Inline text or a file path prefixed with `@` (e.g. `--sql @query.sql`). Use `@url` as a placeholder for the target page URL. |
| `--seed-file` | No | Path to a plain-text file with one URL per line. The same query runs against each URL. |
| `--deadline` | No | ISO 8601 deadline for task completion. |
| `--expires` | No | Cache expiration duration (e.g. `1d`, `1h`). |
| `--refresh` | No | Force a fresh fetch, ignoring cache. |

> **Important:** `--seed-file` takes a direct file path (no `@` prefix). Only `--sql` uses `@` to disambiguate inline X-SQL text from a file path.

### X-SQL Reference

X-SQL is a SQL-like query language for extracting structured data from web pages. Queries run against the DOM tree loaded in a browser context.

**Core pattern:**

```sql
SELECT
  <extraction_function>(dom, <selector>) AS <alias>,
  ...
FROM load_and_select(@url, '<scope-selector>');
```

- `@url` — Placeholder for the target URL (substituted server-side).
- `dom` — Reference to the DOM root within the scope selector.
- `load_and_select(@url, '<selector>')` — Loads the page at `@url`, then scopes the DOM to the given CSS selector. Use `'body'` to scope to the full page body.

**Common extraction functions:**

| Function | Returns |
|---|---|
| `dom_base_uri(dom)` | The base URI of the document |
| `dom_first_text(dom, '<selector>')` | Text content of the first matching element |
| `dom_all_texts(dom, '<selector>')` | Text content of all matching elements (array) |
| `dom_first_href(dom, '<selector>')` | `href` attribute of the first matching link |
| `dom_all_hrefs(dom, '<selector>')` | `href` attributes of all matching links (array) |
| `dom_first_src(dom, '<selector>')` | `src` attribute of the first matching element |
| `dom_first_slim_html(dom, '<selector>')` | Outer HTML of the first matching element |
| `dom_all_slim_html(dom, '<selector>')` | Outer HTML of all matching elements (array) |
| `dom_first_attr(dom, '<selector>', '<attr>')` | Named attribute of the first matching element |

**Selector extensions:**

X-SQL selectors support expression filters via `:expr(<condition>)`:

```sql
-- Select images wider than 400px
dom_first_slim_html(dom, 'img:expr(width > 400)') AS large_img

-- Select the second matching element (0-based)
dom_first_text(dom, 'div.product:expr(1)') AS second_product
```

**Example query file (`query.sql`):**

```sql
SELECT
  dom_base_uri(dom) AS url,
  dom_first_text(dom, '#productTitle') AS title,
  dom_first_text(dom, '.a-price .a-offscreen') AS price,
  dom_first_slim_html(dom, 'img:expr(width > 400)') AS main_image,
  dom_all_texts(dom, '.a-size-base.review-text') AS reviews
FROM load_and_select(@url, 'body');
```

### Routing

`swarm query` sends a structured JSON body to `SwarmController.query(query)`. The `@url` placeholder is substituted with the target URL (and any load options) server-side.

> **Tip:** `swarm submit --sql` also works as a convenience alias, but `swarm query` is the preferred command for X-SQL queries.

## swarm status

Poll the progress of an asynchronous scrape job.

```bash
browser4-cli swarm status scrape-task-4
```

- Accepts the task ID returned by `swarm submit` or `swarm query`.
- Reads job status from `SwarmController.getStatus(id)` and prints the returned JSON payload.
- Typical status fields include `id`, `status`, `isDone`, `statusCode`, `processState`, and `message`.

### Interpreting Status

```json
{
  "id": "scrape-task-4",
  "isDone": false,
  "status": "PROCESSING",
  "message": "Loading page..."
}
```

Wait for `isDone: true` before calling `swarm result`.

## swarm result

Fetch the completed result of a scrape job.

```bash
browser4-cli swarm result scrape-task-4
```

- Accepts the task ID returned by `swarm submit` or `swarm query`.
- Reads the job result from `SwarmController.getResult(id)` and prints the returned payload.
- For successful extractions, results appear in the `resultSet` array.
- Results may be plain text or structured JSON depending on the job type.

### Example Result

```json
{
  "id": "scrape-task-4",
  "isDone": true,
  "statusCode": 200,
  "resultSet": [
    {
      "url": "https://www.amazon.com/dp/B08PP5MSVB",
      "title": "Example Product Title",
      "price": "$29.99",
      "main_image": "<img src=\"...\" width=\"500\">"
    }
  ]
}
```

## Complete Workflows

### URL Scraping Workflow

```bash
# 1) Create a swarm session
browser4-cli swarm create \
  --profile-mode=TEMPORARY \
  --max-open-tabs=12 \
  --max-browser-contexts=3 \
  --display-mode=HEADLESS

# 2) Submit jobs (direct URL + seed file)
browser4-cli swarm submit https://example.com/direct \
  --seed-file=./swarm-seeds.txt \
  --deadline=2026-03-30T00:00:00Z \
  --expires=1d \
  --refresh \
  --store-content

# 3) Poll until complete
browser4-cli swarm status scrape-task-4

# 4) Fetch results
browser4-cli swarm result scrape-task-4

# 5) Clean up
browser4-cli close
```

### X-SQL Extraction Workflow

```bash
# 1) Create a swarm session
browser4-cli swarm create --display-mode=HEADLESS

# 2) Submit an X-SQL query
browser4-cli swarm query "https://example.com/products" \
  --sql @extract-products.sql

# 3) Poll and fetch
browser4-cli swarm status scrape-task-5
browser4-cli swarm result scrape-task-5

# 4) Clean up
browser4-cli close
```

### Multi-URL Structured Extraction

```bash
# Run the same X-SQL query against every URL in a seed file
browser4-cli swarm create --display-mode=HEADLESS

browser4-cli swarm query \
  --sql @extract-products.sql \
  --seed-file=./product-urls.txt \
  --refresh

# Poll all submitted tasks
browser4-cli swarm status scrape-task-6
browser4-cli swarm result scrape-task-6
```

## Typical Use Cases

- **Parallel refresh of a curated URL list** — load dozens of pages concurrently across multiple browser contexts, extracting updated content with `--refresh`.
- **Supervised fan-out browsing** — start from a seed page, extract links, then crawl each link in parallel.
- **Repeatable selector-based scraping** — define X-SQL queries with explicit CSS selectors and run them repeatedly as pages change.
- **Structured data extraction** — use X-SQL to extract product details, prices, reviews, and images into structured JSON.
- **Content archival** — combine `--parse` and `--store-content` to persist full page snapshots for audit or later analysis.

## Error Handling

- All swarm subcommands return a non-zero exit code on failure. Check stderr for details.
- `swarm create` fails if the backend is unreachable or if the session cannot be provisioned.
- `swarm submit` and `swarm query` fail fast on invalid arguments (missing `--sql`, malformed URL).
- `swarm status` and `swarm result` print the backend payload as-is. If the task failed server-side, inspect the `statusCode` and `message` fields in the returned JSON.
- Missing LLM/API key configuration for X-SQL query processing will surface as a task-level error visible via `swarm status` / `swarm result`.
- Use `--deadline` to bound long-running tasks and prevent hung jobs from blocking your workflow.

## Notes

- Swarm subcommands are advanced commands and are not supported in `batch` mode.
- The swarm session uses a fixed session ID (`SWARM`) — it does not interfere with or share state with named sessions (`-s=<name>`) or the default session.
- Seed files support up to thousands of URLs, but consider the `--max-open-tabs` and `--max-browser-contexts` settings to control parallelism and resource consumption.
- Load-option flags (`--deadline`, `--expires`, `--refresh`, `--parse`, `--store-content`) work with both `swarm submit` and `swarm query`.
- Always close the swarm session when done to free resources: `browser4-cli close` or `browser4-cli close-all`.
