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
```

`domsnapshot` (capture) always fetches a fresh snapshot and caches it. Subsequent `get`/`query`/`export` reuse the cache until the next capture or page navigation.

## Get — Extract data via CSS selectors

Only CSS selectors are accepted — element refs (`e5`) are rejected.

```bash
browser4-cli domsnapshot get <text|html|attr> <selector> [name]
```

| Field | Description | Requires `name`? |
|---|---|---|
| `text` | Visible text of matched element | No |
| `html` | Inner HTML of matched element | No |
| `attr` | Value of a named attribute | **Yes** (3rd argument) |

Examples:

```bash
browser4-cli domsnapshot get text ".product-title"
browser4-cli domsnapshot get attr ".product-image" data-src
```

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

## Error Handling

- `domsnapshot` capture fails if backend is unreachable or page cannot be loaded.
- `domsnapshot get` exits non-zero when the CSS selector matches nothing or an element ref (`e5`) is passed.
- `domsnapshot query` fails on invalid X-SQL syntax or missing `--sql`.
- `domsnapshot export` / `summary` fail if no snapshot has been captured yet.

## Notes

- `domsnapshot get` only accepts CSS selectors. For interactive element interaction, use the standard `snapshot` + ref-based commands.
- X-SQL queries through `domsnapshot query` follow the same constraints as `swarm query`. See [X-SQL reference](x-sql.md) for full function documentation.
- The captured snapshot is cached in the backend and invalidated by the next `domsnapshot` capture or a page navigation (`goto`, `reload`, etc.).
