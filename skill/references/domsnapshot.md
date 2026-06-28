---
title: "DOM Snapshot — Static DOM Extraction & X-SQL Querying"
description: "Reference for domsnapshot commands (get, query, summary, export, grep). Extract structured data from the raw HTML DOM via CSS selectors and X-SQL queries."
---

# DOM Snapshot — Static DOM Extraction & X-SQL Querying

The `domsnapshot` family operates on a **static DOM snapshot** — the raw HTML of the current page parsed into a queryable DOM. Unlike interactive `snapshot` (accessibility-tree refs for `click`/`type`/`fill`), `domsnapshot` extracts structured data via CSS selectors and X-SQL queries.

## Comparison: snapshot vs domsnapshot

| Feature | `snapshot` | `domsnapshot` |
|---|---|---|
| Data source | Accessibility tree | Raw HTML DOM |
| Element addressing | Refs (`e5`) | CSS selectors only |
| X-SQL support | No | Yes (`query`) |
| Output | YAML accessibility tree | HTML (`export`), structured data (`get`/`query`) |

## Commands

```bash
browser4-cli domsnapshot                                # capture fresh static DOM snapshot
browser4-cli domsnapshot get <field> [selector] [name]  # extract text/html/attr via CSS selectors
browser4-cli domsnapshot query [url] --sql <query>      # X-SQL query against DOM (url defaults to current page)
browser4-cli domsnapshot summary                        # compressed page summary (WPSI)
browser4-cli domsnapshot export [--file <path>]         # save snapshot HTML to file
browser4-cli domsnapshot grep [OPTIONS] <pattern>       # search snapshot HTML with regex (grep-style)
```

`domsnapshot` (capture) always fetches a fresh snapshot and caches it. Subsequent `get`/`query`/`export` reuse the cache until the next capture or page navigation.

> **Note:** `domsnapshot get` looks up the page using the browser's current URL (after any redirects/navigations), so it works correctly on search-results pages and post-form-submission pages.

## Get — Extract data via CSS selectors

Only CSS selectors are accepted — element refs (`e5`) are rejected.

```bash
# First match only (querySelector semantics)
browser4-cli domsnapshot get <text|html|attr> <selector> [name]

# All matches (querySelectorAll semantics)
browser4-cli domsnapshot get all <text|html|attr> <selector> [name] [--offset N] [--limit N]
```

| Field | Description | Requires `name`? |
|---|---|---|
| `text` | Visible text of matched element(s) | No |
| `html` | Inner HTML of matched element(s) | No |
| `attr` | Value of a named attribute | **Yes** (3rd argument) |

**`get` returns only the first match.** For multiple results, use `domsnapshot get all` (returns a JSON array) or `domsnapshot query`.

### `get` (single)

```bash
browser4-cli domsnapshot get text ".product-title"
browser4-cli domsnapshot get attr ".product-image" data-src
```

### `get all` (multiple)

Returns a JSON array of strings.  Supports `--offset` (skip first N) and `--limit` (max results).

```bash
browser4-cli domsnapshot get all text "h2 a"                  # all product titles
browser4-cli domsnapshot get all attr ".product-image" src    # all image URLs
browser4-cli domsnapshot get all text ".result" --limit 5     # first 5 results
browser4-cli domsnapshot get all text ".result" --offset 10   # skip first 10
```

### Troubleshooting empty results

If `domsnapshot get` returns an empty string when the page clearly has matching elements:

1. **Run `domsnapshot` first to capture a fresh snapshot:** `browser4-cli domsnapshot` then retry `get`
2. **Verify the CSS selector** with `domsnapshot grep <pattern>` to search the raw HTML
3. **Use `domsnapshot query` or `domsnapshot get all`** for multiple results or complex queries
4. **Check page load:** ensure the page finished loading (AJAX content may take time)

## Query — X-SQL against DOM snapshot

The `--sql` flag is **required**. Use `@url` as a placeholder for the target URL. Prefix `--sql` value with `@` to read the query from a file.

X-SQL uses the **H2 database** SQL dialect with DOM UDFs. Only simple `SELECT ... FROM load_and_select(url, cssQuery)` queries are supported — no CTEs, subqueries, `EXPLODE`, or joins.

> **Important:** `@url` must appear **unquoted** in SQL. `SQLTemplate.createSQL(url)` handles escaping internally.
> - ✅ `FROM load_and_select(@url, ':root')`
> - ❌ `FROM load_and_select('@url', ':root')`

```bash
# Inline query against current page:
browser4-cli domsnapshot query --sql "
  SELECT
    dom_base_uri(dom) AS url,
    dom_first_text(dom, '#productTitle') AS title,
    dom_first_slim_html(dom, 'img:expr(width > 400)') AS img
  FROM load_and_select(@url, 'body');
"

# Read query from file, target a specific URL:
browser4-cli domsnapshot query "https://www.amazon.com/dp/B08PP5MSVB" --sql @query.sql
```

To control caching or rendering, append load options to the URL (e.g. `https://example.com/page -i 1d -njr 3`).

## Summary — Web Page Summary Index (WPSI)

Generates a deterministic, AI-readable compressed page summary (typically <1% of original HTML) as a YAML file. Includes page metadata, structure landmarks, key content nodes with CSS selector hints, list/table detection, and stats. Requires a previously captured DOM snapshot.

```bash
browser4-cli domsnapshot summary
```

## Export

Save full snapshot HTML to a local file. The exported HTML is pretty-formatted for direct use with tools like `grep`.

```bash
browser4-cli domsnapshot export [--file=page-snapshot.html]
```

## Grep — Search snapshot HTML

Search the DOM snapshot HTML with regex patterns and grep-style output. Performs matching client-side (no backend changes) by fetching the HTML via `dom_snapshot_export` then matching locally.

```bash
browser4-cli domsnapshot grep [OPTIONS] <pattern>
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
| `--selector <CSS>` | Scope search to a specific CSS element (fetches inner HTML via `dom_snapshot_scrape`) |

Line numbers are **on by default** (unlike GNU grep where you opt in with `-n`). Use `--no-line-number` to suppress them.

For CI pass/fail checks, use `-l` (prints "domsnapshot" if matches exist) or `-c` (prints match count). Check the CLI exit code (`browser4-cli ... && echo PASS || echo FAIL`) — a non-zero exit means the backend call failed, not that matches were absent. `-l` always exits 0 when the backend call succeeds; the match/no-match result is in the output text.


### Examples

```bash
# Find all lines containing "error" (case-insensitive)
browser4-cli domsnapshot grep -i error

# Literal string match with 2 lines of context
browser4-cli domsnapshot grep -F -C 2 "404 Not Found"

# Count how many lines contain TODO, FIXME, or HACK
browser4-cli domsnapshot grep -c 'TODO|FIXME|HACK'

# Search only within <main> element
browser4-cli domsnapshot grep --selector main "Submit"

# Whole-word search for "password"
browser4-cli domsnapshot grep -w password

# Show non-empty lines (invert match on empty/whitespace-only)
browser4-cli domsnapshot grep -v '^\s*$'
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

## Error Handling

- `domsnapshot` capture fails if backend is unreachable or page cannot be loaded.
- `domsnapshot get` exits non-zero when the CSS selector matches nothing or an element ref (`e5`) is passed.
- `domsnapshot query` fails on invalid X-SQL syntax or missing `--sql`.
- `domsnapshot export` / `summary` fail if no snapshot has been captured yet.

## Notes

- `domsnapshot get` only accepts CSS selectors. For interactive element interaction, use the standard `snapshot` + ref-based commands.
- X-SQL queries through `domsnapshot query` follow the same constraints as `swarm query`. See [X-SQL reference](x-sql.md) for full function documentation.
- The captured snapshot is cached in the backend and invalidated by the next `domsnapshot` capture or a page navigation (`goto`, `reload`, etc.).
- `domsnapshot grep` performs matching **entirely client-side** in the CLI — the full HTML is fetched from the backend once, then all regex matching happens locally. No backend round-trips for the search itself.
- For CI pass/fail checks with grep, use `-l` (prints "domsnapshot" if matches found) or `-c` (prints match count). A `browser4-cli` non-zero exit code means the backend call itself failed, not that matches were absent.
