# DOM Snapshot — Static DOM Extraction & X-SQL Querying

The `domsnapshot` family of commands operates on a **static DOM snapshot** — the raw HTML of the current page parsed into a queryable document object model. Unlike the interactive `snapshot` command (which captures accessibility-tree refs for `click`/`type`/`fill`), `domsnapshot` extracts structured data from the DOM using CSS selectors and X-SQL queries without requiring an interactive browser session.

## Comparison: Snapshot vs DOM Snapshot

| Feature | `snapshot` | `domsnapshot` |
|---|---|---|
| Data source | Accessibility tree | Raw HTML DOM |
| Element addressing | Refs (`e5`, `e15`) | CSS selectors only |
| Interactive commands | `click`, `type`, `fill` | Not supported |
| Data extraction | Via `extract` | Via `get` and `query` |
| X-SQL support | No | Yes (`query`) |
| Export format | YAML (accessibility tree) | HTML (`export`) |

## Command Overview

Use the spaced `domsnapshot <subcommand>` form:

```bash
browser4-cli domsnapshot             # capture a fresh static DOM snapshot
browser4-cli domsnapshot get <field> [selector] [name]  # extract data from the snapshot
browser4-cli domsnapshot query [url] --sql <query>       # run X-SQL against a snapshot (url defaults to current page)
browser4-cli domsnapshot export [--file <path>]         # save snapshot HTML to a file
browser4-cli domsnapshot summary                       # generate a compressed page summary (WPSI)
```

| Command | Purpose |
|---|---|
| `domsnapshot` | Capture a static DOM snapshot of the current page |
| `domsnapshot get <field>` | Extract elements from the snapshot (text, html, attr) |
| `domsnapshot query [url]` | Run X-SQL against the DOM snapshot via the scrape API. URL defaults to the current session's page |
| `domsnapshot export` | Save full snapshot HTML content to a local file |
| `domsnapshot summary` | Generate a compressed Web Page Summary Index (WPSI) from the stored DOM snapshot |

## Capture

Capture a fresh static DOM snapshot of the current page and cache it in the backend for subsequent `get`/`query`/`export` calls.

```bash
# Capture a fresh static DOM snapshot of the current page
browser4-cli domsnapshot
```

Returns a JSON metadata object with the page URL, href, content size, capture time, content type, and title. The capture is always fresh — `domsnapshot` forces a new page capture regardless of any previously cached snapshot. The resulting snapshot is stored in the backend and reused by subsequent `get`, `query`, and `export` calls until the next `domsnapshot` capture or a page navigation.

## Get — Extract data from the snapshot

Extract text, HTML, or attribute values from the static DOM using CSS selectors. **Only CSS selectors** are supported — element references (`e5`, `backend:15`) are rejected.

```bash
# Extract visible text from an element
browser4-cli domsnapshot get text ".product-title"

# Extract the inner HTML of the entire page (default selector is :root)
browser4-cli domsnapshot get html

# Extract a specific attribute
browser4-cli domsnapshot get attr ".product-image" data-src

# Extract text from a specific element
browser4-cli domsnapshot get text "#description"
```

| Field | Description | Requires `name`? |
|---|---|---|
| `text` | Visible text of the matched element | No |
| `html` | Inner HTML of the matched element | No |
| `attr` | Value of a named attribute | **Yes** (3rd argument) |

## Query — X-SQL against DOM snapshot

Run X-SQL queries against a loaded page. The `--sql` flag is **required**. Use `@url` as a placeholder for the target URL. Prefix `--sql` value with `@` to read the query from a file.

The `url` argument is **optional** — when omitted, the query runs against the current active session's page URL. When provided explicitly, the query is stateless and can target any URL directly via the scrape backend.

X-SQL uses the **H2 database** SQL dialect with DOM UDFs. Only simple `SELECT ... FROM load_and_select(url, cssQuery)` queries are supported — no CTEs, subqueries, `EXPLODE`, or joins.

> **Important:** The `@url` placeholder must appear **unquoted** in the SQL. `SQLTemplate.createSQL(url)` handles escaping and quoting internally.
> - ✅ `FROM load_and_select(@url, ':root')`
> - ❌ `FROM load_and_select('@url', ':root')`

To control caching or rendering, append load options to the URL string (e.g. `https://example.com/page -i 1d -njr 3`).

```bash
# Query the current page (no URL needed):
browser4-cli domsnapshot query --sql "
  SELECT
    dom_base_uri(dom) AS url,
    dom_first_text(dom, '#productTitle') AS title,
    dom_first_slim_html(dom, 'img:expr(width > 400)') AS img
  FROM load_and_select(@url, 'body');
"

# Query any URL explicitly:
browser4-cli domsnapshot query "https://www.amazon.com/dp/B08PP5MSVB" --sql "
  SELECT
    dom_base_uri(dom) AS url,
    dom_first_text(dom, '#productTitle') AS title
  FROM load_and_select(@url, 'body');
"

# Read query from a file:
browser4-cli domsnapshot query "https://www.example.com" --sql @query.sql
```

## Export

Save the full snapshot HTML content to a local file. If `--file` is not provided, a timestamped file is created in the snapshot directory.

```bash
# Export with auto-generated filename:
browser4-cli domsnapshot export

# Export to a specific file:
browser4-cli domsnapshot export --file=page-snapshot.html
```

> **Note:** The exported HTML is pretty-formatted, so tools like `grep` work directly on the output file.

## Summary — Web Page Summary Index (WPSI)

Generate a compressed page summary from the stored DOM snapshot. The summary is a deterministic, AI-readable index that preserves page structure and key content in typically <1% of the original HTML size.

```bash
# Generate a summary from the stored DOM snapshot:
browser4-cli domsnapshot summary
```

The summary is saved as a YAML file (`.yml`) in the snapshot directory, consistent with the `snapshot` command output format, and includes:

- **Page metadata** — title, URL
- **Page type** — inferred from DOM structure (product detail, article, search results, form, etc.)
- **Page structure** — landmark elements (header, nav, main, footer, etc.)
- **Main content** — key nodes with scores and CSS selector hints
- **List detection** — repeated structures (product lists, comment lists, etc.) with samples
- **Table summaries** — large tables summarized with row/col counts and headers
- **Stats** — node count, link count, button count, form count, table count, image count, input count

All summary nodes include a `box` field (the `vi` attribute — bounding box) that can be used to backtrack to the original DOM element. CSS selector hints (id, class) are provided for key nodes to enable automated data extraction.

> **Requirements:** A DOM snapshot must exist in the page storage (captured via `domsnapshot` or page navigation) before calling `dom snapshot summary`. The summary is generated deterministically — no AI model is involved.

## Error Handling

- `domsnapshot` capture fails if the backend is unreachable or the page cannot be loaded.
- `domsnapshot get` returns a non-zero exit code when the CSS selector matches no elements or when an element reference (`e5`, `backend:15`) is passed instead of a CSS selector.
- `domsnapshot query` fails on invalid X-SQL syntax or when `--sql` is missing.
- `domsnapshot export` fails if no snapshot has been captured yet.

## Notes

- `domsnapshot` (the capture command) always fetches a fresh page snapshot and caches it in the backend. Subsequent `get`, `query`, and `export` calls reuse this cached snapshot — they do not re-capture the page. The cache is invalidated by the next `domsnapshot` capture or a page navigation (`goto`, `reload`, etc.).
- `domsnapshot get` only accepts CSS selectors. For interactive element interaction (click, type, fill), use the standard `snapshot` + ref-based commands instead.
- X-SQL queries through `domsnapshot query` follow the same SQL constraints as `swarm query`. See the [X-SQL reference](x-sql.md) for full function documentation.
