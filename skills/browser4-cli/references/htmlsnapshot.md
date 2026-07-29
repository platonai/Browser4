---
title: "HTML Snapshot — Static DOM Extraction, Inspection & X-SQL Querying"
description: "Reference for htmlsnapshot commands (capture, get, query, summary, export, grep, inspect). Extract structured data from the raw HTML DOM via CSS selectors and X-SQL queries."
tier: catalog
---

# HTML Snapshot — Static DOM Extraction, Inspection & X-SQL Querying

The `htmlsnapshot` family operates on a **static HTML snapshot** — the raw HTML of the current page parsed into a queryable DOM. Unlike interactive `snapshot` (accessibility-tree refs for `click`/`type`/`fill`), `htmlsnapshot` extracts structured data via CSS selectors and X-SQL queries.

## Comparison: snapshot vs htmlsnapshot

| Feature | `snapshot` | `htmlsnapshot` |
|---|---|---|
| Data source | Accessibility tree | Raw HTML DOM |
| Element addressing | Refs (`e5`) | CSS selectors only |
| X-SQL support | No | Yes (`query`) |
| Interactive element list | No | Yes (`htmlsnapshot` capture returns interactiveElements) |
| Selector discovery | No | Yes (`inspect`) |
| Output | YAML accessibility tree | HTML (`export`), structured data (`get`/`query`/`inspect`) |

## Commands

```bash
browser4-cli htmlsnapshot                                # capture fresh static HTML snapshot + metadata
browser4-cli htmlsnapshot get <field> [selector] [name] [--page N] [--page-size N] [--all]  # extract text/html/attr via CSS; html paginated at 2K lines, text not paginated
browser4-cli htmlsnapshot query [url] --sql <query>      # X-SQL query against DOM (url defaults to current page)
browser4-cli htmlsnapshot summary                        # compressed page summary (WPSI)
browser4-cli htmlsnapshot export [--file <path>] [--clean]  # save snapshot HTML to file
browser4-cli htmlsnapshot get all <field> [selector] [name] [--offset N] [--limit N] [--page N] [--page-size N] [--all]  # extract ALL matches; html paginated at 2K lines, text not paginated
browser4-cli htmlsnapshot grep [OPTIONS] <pattern> [--page N] [--page-size N] [--all]  # search snapshot HTML with regex; paginated by default (2K lines)
browser4-cli htmlsnapshot inspect [selector] [--max N] [--depth D]  # analyze DOM structure, suggest CSS selectors
```

`htmlsnapshot` (capture) always fetches a fresh snapshot, caches it, and returns enriched metadata including image/link counts and a list of interactive elements (with tag, class, id, aria attributes, and bounding box). Subsequent `get`/`query`/`export`/`inspect` reuse the cache until the next capture or page navigation.

> **Note:** `htmlsnapshot get` looks up the page using the browser's current URL (after any redirects/navigations), so it works correctly on search-results pages and post-form-submission pages.

## Get — Extract data via CSS selectors

Only CSS selectors are accepted — element refs (`e5`) are rejected.

```bash
# First match only (querySelector semantics)
browser4-cli htmlsnapshot get <text|html|attr> <selector> [name]

# All matches (querySelectorAll semantics)
browser4-cli htmlsnapshot get all <text|html|attr> <selector> [name] [--offset N] [--limit N]
```

| Field | Description | Requires `name`? |
|---|---|---|
| `text` | Visible text of matched element(s) | No |
| `html` | Inner HTML of matched element(s) | No |
| `attr` | Value of a named attribute | **Yes** (3rd argument) |

**`get` returns only the first match.** For multiple results, use `htmlsnapshot get all` (returns a JSON array) or `htmlsnapshot query`.

> **Warning:** Correlating multiple fields: Each `get all` call scans the whole document independently — running `get all text ".title"` and `get all text ".price"` produces two unaligned arrays (different lengths, different order). To extract correlated fields (title + price + URL per item), use `htmlsnapshot query` with X-SQL's `DOM_LOAD_AND_SELECT` scoped to a parent container. See the [list-page scraping pattern](x-sql-dom-load-select.md#dom_load_and_select).

### `get` (single)

```bash
browser4-cli htmlsnapshot get text ".product-title"
browser4-cli htmlsnapshot get attr ".product-image" data-src
```

### `get all` (multiple)

Returns a JSON array of strings.  Supports `--offset` (skip first N) and `--limit` (max results).

```bash
browser4-cli htmlsnapshot get all text "h2 a"                  # all product titles
browser4-cli htmlsnapshot get all attr ".product-image" src    # all image URLs
browser4-cli htmlsnapshot get all text ".result" --limit 5     # first 5 results
browser4-cli htmlsnapshot get all text ".result" --offset 10   # skip first 10
```

### Troubleshooting empty results

If `htmlsnapshot get` returns an empty string when the page clearly has matching elements:

1. **Run `htmlsnapshot` first to capture a fresh snapshot:** `browser4-cli htmlsnapshot` then retry `get`
2. **Verify the CSS selector** with `htmlsnapshot grep <pattern>` to search the raw HTML
3. **Use `htmlsnapshot query` or `htmlsnapshot get all`** for multiple results or complex queries
4. **Check page load:** ensure the page finished loading (AJAX content may take time)

## Query — X-SQL against HTML snapshot

The `--sql` flag is **required**. Use `@url` as a placeholder for the target URL.

X-SQL uses the **H2 database** SQL dialect with DOM UDFs. Only simple `SELECT ... FROM DOM_LOAD_AND_SELECT(url, cssQuery)` queries are supported — no CTEs, subqueries, `EXPLODE`, or joins.

> **Important:** `@url` must appear **unquoted** in SQL. `SQLTemplate.createSQL(url)` handles escaping internally.
> - ✅ `FROM DOM_LOAD_AND_SELECT(@url, ':root')`
> - ❌ `FROM DOM_LOAD_AND_SELECT('@url', ':root')`
> - ❌ `FROM DOM_LOAD_AND_SELECT('.', ':root')` — the literal `'.'` is not a valid URL. Use the `@url` placeholder to reference the current page.

### Three ways to provide the SQL query

**1. File (recommended — no shell escaping issues):**
Prefix the `--sql` value with `@` to read from a `.sql` file:

```bash
# Write query to file (no escaping needed)
cat > query.sql << 'SQLEOF'
SELECT
  DOM_BASE_URI(dom) AS url,
  DOM_FIRST_TEXT(dom, '#productTitle') AS title
FROM DOM_LOAD_AND_SELECT(@url, 'body')
WHERE DOM_FIRST_TEXT(dom, '#productTitle') != 'Sponsored'
SQLEOF

# Run it
browser4-cli htmlsnapshot query "https://www.amazon.com/dp/B08PP5MSVB" --sql @query.sql
```

> **Note:** X-SQL function names are case-insensitive. `DOM_FIRST_TEXT` and `dom_first_text` are equivalent. This reference uses UPPERCASE for clarity.

**2. Stdin (for piped/scripted workflows — also avoids quoting):**

```bash
cat query.sql | browser4-cli htmlsnapshot query --sql-stdin
# or
browser4-cli htmlsnapshot query --sql-stdin < query.sql
# with a URL
browser4-cli htmlsnapshot query "https://example.com" --sql-stdin < query.sql
```

**3. Base64 (transport-safe — no quoting, works across all platforms):**

```bash
# Encode once, pass anywhere without escaping
base64 -w0 query.sql > query.b64
browser4-cli htmlsnapshot query "https://example.com" --sql @query.b64 --sql-base64

# Or inline the base64 value directly
browser4-cli htmlsnapshot query "https://example.com" --sql "$(base64 -w0 query.sql)" --sql-base64
```

**4. Inline (requires careful shell escaping on Windows):**

```bash
# Simple queries without quotes in selectors work inline:
browser4-cli htmlsnapshot query --sql "
  SELECT DOM_BASE_URI(dom) AS url, DOM_FIRST_TEXT(dom, 'h1') AS title
  FROM DOM_LOAD_AND_SELECT(@url, 'body');
"

# Queries with quoted selectors or != require escaping — prefer @file, --sql-stdin, or --sql-base64
```

To control caching or rendering, append load options to the URL (e.g. `https://example.com/page -i 1d -njr 3`).

## Summary — Web Page Summary Index (WPSI)

Generates a deterministic, AI-readable compressed page summary (typically <1% of original HTML) as a YAML file. Includes page metadata, structure landmarks, key content nodes with CSS selector hints, list/table detection, and stats. Requires a previously captured HTML snapshot.

```bash
browser4-cli htmlsnapshot summary
```

## Export

Save full snapshot HTML to a local file. The exported HTML is pretty-formatted for direct use with tools like `grep`. Use `--clean` to produce a minimal HTML file suitable for LLM consumption — strips `<script>`, `<style>`, `<noscript>`, comments, and non-standard attributes while preserving semantic structure.

```bash
browser4-cli htmlsnapshot export [--file page-snapshot.html] [--clean]
```

## Grep — Search snapshot HTML

Search the HTML snapshot HTML with regex patterns and grep-style output. Performs matching client-side (no backend changes) by fetching the HTML via `html_snapshot_export` then matching locally.

```bash
browser4-cli htmlsnapshot grep [OPTIONS] <pattern>
```

### Flags

| Flag | Description |
|---|---|
| `-i` | Case-insensitive matching |
| `-A N` | Show N lines after each match |
| `-B N` | Show N lines before each match |
| `-C N` | Show N lines before and after each match |
| `-v` | Invert match (select non-matching lines) |
| `-c` | Print only the count of matching lines |
| `-l` | Print only whether matches exist (grep-style "files-with-matches"; exits 0 if found) |
| `-F` | Treat pattern as a literal string, not regex |
| `-w` | Match only whole words (wraps pattern with `\b` word boundaries) |
| `--no-line-number` | Suppress line numbers in output (line numbers are shown by default) |
| `--selector <CSS>` | Scope search to a specific CSS element (fetches inner HTML via `html_snapshot_scrape`) |
| `--page N` | Show page N of paginated output (default: 1) |
| `--page-size N` | Characters per page (default: 1024) |
| `--all` | Show all output, disabling pagination |

Line numbers are **on by default** (unlike GNU grep where you opt in with `-n`). Use `--no-line-number` to suppress them.

For CI pass/fail checks, use `-l` (prints "htmlsnapshot" if matches exist) or `-c` (prints match count). Check the CLI exit code (`browser4-cli ... && echo PASS || echo FAIL`) — a non-zero exit means the backend call failed, not that matches were absent. `-l` always exits 0 when the backend call succeeds; the match/no-match result is in the output text.


### Examples

```bash
# Find all lines containing "error" (case-insensitive)
browser4-cli htmlsnapshot grep -i error

# Literal string match with 2 lines of context
browser4-cli htmlsnapshot grep -F -C 2 "404 Not Found"

# Count how many lines contain TODO, FIXME, or HACK
browser4-cli htmlsnapshot grep -c 'TODO|FIXME|HACK'

# Search only within <main> element
browser4-cli htmlsnapshot grep --selector main "Submit"

# Whole-word search for "password"
browser4-cli htmlsnapshot grep -w password

# Show non-empty lines (invert match on empty/whitespace-only)
browser4-cli htmlsnapshot grep -v '^\s*$'

# Search with pagination (page 2, custom page size)
browser4-cli htmlsnapshot grep -i error --page 2 --page-size 500

# Show all matches (disable pagination, useful for piping)
browser4-cli htmlsnapshot grep --all "pattern"
```

### Output format

Matches are printed with `N:` (line number + colon) followed by the line content. Context lines use `N:-` (line number, colon, dash) to distinguish them visually from match lines. Non-contiguous context groups are separated by `--`.

```
42:    <h1>Welcome to My Page</h1>
43:-    <nav>
44:      <a href="/login">Login</a>
45:-    </nav>
--
108:    <footer>Copyright 2026</footer>
```

When `--no-line-number` is passed, the line-number prefix is omitted entirely. Match and context lines are then distinguished only by the `-` prefix on context lines.

## Inspect — Discover CSS selectors for recurring patterns

Analyzes the HTML snapshot and suggests CSS selectors for recurring content patterns. Essential for complex pages where you don't know the right selectors ahead of time (e.g., e-commerce search results, news listings).

```bash
browser4-cli htmlsnapshot inspect [selector] [--max N] [--depth D]
```

| Parameter | Default | Description |
|---|---|---|
| `selector` | `:root` | CSS selector to scope inspection. When it matches multiple elements (e.g. `.product-card`), the command compares child structures across matches to find recurring patterns. |
| `--max N` | 10 | Max matching elements to analyze. |
| `--depth D` | 5 | Max descendant depth for selector suggestions. |

### How it works

When `selector` matches **multiple elements** (e.g. `.product-card`):
1. Finds all elements matching `selector`
2. For each match, walks descendants up to `--depth` and computes relative CSS selectors (tag + class + id)
3. Counts how many matches each selector appears in
4. Filters to selectors appearing in **≥50%** of matches (minimum 2)
5. Returns sample structures and ranked selector suggestions

When `selector` matches only **1 element** (e.g. default `:root`, or `body`), **auto-discovery** activates:
1. Walks the DOM to find groups of sibling elements sharing the same CSS signature
2. Scores each group by size × specificity × content-variance × structural-richness
3. Picks the best repeating pattern (e.g. `.product-card`) and re-runs the pipeline against it
4. Adds `autoDiscovered: true` and `originalSelector` to the response

### Output

```
### Inspect: ".product_pod" (20 matches, 10 analyzed)

  Sample structure (3 of 20):
  -- Element 1: article.product_pod
      img.thumbnail  "A Light in the Attic"
      h3              ""
       a              "A Light in the..."
      div.product_price
       p.price_color  "£51.77"
  ...

  Suggested selectors (recurring across matches):
   10/10 (100%)  h3 a                                         → "A Light in the..."
   10/10 (100%)  img.thumbnail                                → ""
   10/10 (100%)  p.price_color                                → "£51.77"
    8/10 ( 80%)  p.instock.availability                       → "In stock"
```

### Tips

- **Start without arguments:** `htmlsnapshot inspect` (no selector) triggers auto-discovery and finds the page's most prominent repeating content pattern. This is the quickest way to discover selectors on an unfamiliar page.
- **Start broad, then narrow:** First run without a selector to see page landmarks. Then target a repeating container (e.g. `.product_pod`, `.s-result-item`).
- **Always capture first:** `htmlsnapshot` must be run before `inspect` (it loads the cached document).
- **Use with `get`:** Take the suggested selectors and use them with `htmlsnapshot get all` or `htmlsnapshot query` for batch extraction.
- **Avoid quoting hell:** Use `--sql @file.sql` (file), `--sql-stdin` (piped), or `--sql-base64` (encoded) instead of inline `--sql "..."` on Windows — quoted CSS selectors and `!=` operators break inline SQL.
- **Base64 for portability:** `--sql "$(base64 -w0 query.sql)" --sql-base64` passes SQL safely through any shell, CI pipeline, or HTTP transport with zero quoting issues.
- **`@file` paths resolve relative to CWD first**, then fall back to the Browser4 repo root — so `cargo run` from `cli/browser4-cli` still finds `query.sql` at the workspace root.

## Error Handling

- `htmlsnapshot` capture fails if backend is unreachable or page cannot be loaded.
- `htmlsnapshot get` exits non-zero when the CSS selector matches nothing or an element ref (`e5`) is passed.
- `htmlsnapshot query` fails on invalid X-SQL syntax or missing `--sql`.
- `htmlsnapshot export` / `summary` / `inspect` fail if no snapshot has been captured yet.

## Notes

- `htmlsnapshot get` only accepts CSS selectors. For interactive element interaction, use the standard `snapshot` + ref-based commands.
- X-SQL queries through `htmlsnapshot query` follow the same constraints as `swarm query`. See [X-SQL reference](x-sql.md) for full function documentation.
- The captured snapshot is cached in the backend and invalidated by the next `htmlsnapshot` capture or a page navigation (`goto`, `reload`, etc.).
- `htmlsnapshot grep` performs matching **entirely client-side** in the CLI — the full HTML is fetched from the backend once, then all regex matching happens locally. No backend round-trips for the search itself.
- For CI pass/fail checks with grep, use `-l` (prints "htmlsnapshot" if matches found) or `-c` (prints match count). A `browser4-cli` non-zero exit code means the backend call itself failed, not that matches were absent.
- `htmlsnapshot` capture now returns enriched metadata: `imageCount`, `linkCount`, and `interactiveElements` (tag, class, id, aria attributes, bounding-box). The bounding box is extracted from the `vi` attribute injected by the browser's layout engine.
- `htmlsnapshot inspect` computes relative CSS selectors using tag + class + id. It does not use AI — the algorithm is fully deterministic and based on structural recurrence across matching elements. When run without a selector (or any single-match selector like `:root`), **auto-discovery** finds the page's most prominent repeating content pattern automatically — no prior knowledge of the page's markup is needed.
- **Output pagination:** `get html`, `get all html`, and `grep` paginate output by default at 2000 lines per page. `get text` and `get all text` are not paginated by default (text extraction rarely exceeds practical limits). Use `--page N` for subsequent pages, `--page-size N` to change the page size, or `--all` to disable pagination entirely. Pagination is automatically skipped in `--json` and `--quiet` modes. Use `--all` when piping output to external tools.
