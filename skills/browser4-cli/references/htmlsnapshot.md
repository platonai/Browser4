---
title: "HTML Snapshot — Static DOM Extraction, Inspection & X-SQL Querying"
description: "Reference for htmlsnapshot commands (capture, get, query, summary, export, grep, inspect). Extract structured data from the raw HTML DOM via CSS selectors and X-SQL queries."
tier: catalog
---

# HTML Snapshot — Static DOM Extraction, Inspection & X-SQL Querying

## Overview

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
browser4-cli htmlsnapshot                                # capture the active tab and return its metadata (the same capture every htmlsnapshot command runs)
browser4-cli htmlsnapshot get <field> [selector] [name] [--page N] [--page-size N] [--all] [--expires DUR]  # read text/html/attr via CSS from a fresh snapshot of the active page; html paginated at 2K lines, text not paginated
browser4-cli htmlsnapshot query [url] --sql <query> [--format json|csv|table] [--expires DUR]  # X-SQL over the active page's fresh snapshot, or over an explicit url's stored page (see Query below)
browser4-cli htmlsnapshot summary [--expires DUR]        # compressed page summary (WPSI) of the active page's fresh snapshot
browser4-cli htmlsnapshot export [--file <path>] [--clean] [--expires DUR]  # save a fresh snapshot of the active page to a file
browser4-cli htmlsnapshot get all <field> [selector] [name] [--offset N] [--limit N] [--page N] [--page-size N] [--all] [--expires DUR]  # read ALL matches; html paginated at 2K lines, text not paginated
browser4-cli htmlsnapshot grep [OPTIONS] <pattern> [--page N] [--page-size N] [--all] [--expires DUR]  # search a fresh snapshot of the active page with regex; paginated by default (2K lines)
browser4-cli htmlsnapshot inspect [selector] [--max N] [--depth D] [--expires DUR]  # analyze a fresh snapshot of the active page, suggest CSS selectors
browser4-cli htmlsnapshot readability [url] [--text-only] [--page N] [--page-size N] [--all] [--expires DUR]  # one-step article extraction from the active page (or an explicit url's stored page); no LLM, no selectors
```

`htmlsnapshot` (capture) serializes the document the active tab is showing, stores it under that tab's normalized URL, and returns enriched metadata including image/link counts and a list of interactive elements (with tag, class, id, aria attributes, and bounding box).

**Every command works on a fresh snapshot of the active page.** A command first *captures* the active tab — serializing the document the tab already shows, without navigating — and then operates on that snapshot:

- `htmlsnapshot` (capture) **returns** the snapshot's metadata;
- `get` / `get all` / `inspect` / `summary` / `grep` / `export` / `query` / `readability` **consume** it.

So a read already sees the page as it is right now — form results, SPA updates, `eval` mutations, login state — with no separate capture. Two consequences are worth knowing:

1. **A read of the active page also archives it**, under the tab's own normalized URL, overwriting the stored copy (the capture rides on `-expires 0s`, a *cache* flag, not a reload — capture never navigates).
2. **A read never files a document under a URL you passed.** A URL the tab does not show cannot be captured, so `readability <url>` and `query <url>` keep the read-only path: that URL's own stored copy, or an independent read-only load when the store has nothing.

> **Note:** a read resolves its target from the browser's current URL (after any redirects/navigations), so `get` / `get all` / `summary` / `inspect` / `export` always describe the page the tab is on now, and re-running a read is how you see a page that changed in the tab.

### `--expires` — read the tab, or read the store

Every read accepts `--expires <dur>` (alias `-expires`, default `0s`), with the same meaning as the load option: **how old the stored snapshot of the active page may be before the read takes a new one.**

| Value | What the read serves |
|---|---|
| `--expires 0s` (default) | The **live page**: the tab is captured first, exactly as described above. Nothing stored is reused. |
| `--expires 1d` | The **stored snapshot** while it is younger than a day — the read never touches the tab, so it works on the *previous* snapshot version. When the store has nothing, or its copy is older than the window, the read captures the live page as usual. |

```bash
# Capture once, then keep reading that exact stored version even while the tab changes
browser4-cli htmlsnapshot
browser4-cli htmlsnapshot get text ".price" --expires 1d
browser4-cli htmlsnapshot query --sql @query.sql --expires 30m
```

- Prefer `--expires 0s` over the older `-refresh` spelling in scripts: it says *do not reuse a stored copy* and never implies the page is re-fetched.
- Durations use the `LoadOptions` grammar: `0`, `0s`, `500ms`, `30s`, `10m`, `2h`, `1d`, or ISO-8601 (`PT30S`, `P1D`). A bare non-zero number (`--expires 5`) is **rejected** — `LoadOptions` reads `5` as its always-expired sentinel, so accepting it would silently mean `0s`.
- `--expires` governs the **active page** only. A read aimed at a URL the tab does not show (`readability <url>`, `query <url>`) is already store-only and is never captured, so the option does not change it.
- `htmlsnapshot` / `htmlsnapshot capture` has no `--expires`: a capture always writes the live document, which is its purpose. Use a read when you want the stored copy.


## Get — Extract data via CSS selectors

Only CSS selectors are accepted — element refs (`e5`) are rejected.

```bash
# First match only (querySelector semantics)
browser4-cli htmlsnapshot get <text|textcontent|html|attr> <selector> [name]

# All matches (querySelectorAll semantics)
browser4-cli htmlsnapshot get all <text|textcontent|html|attr> <selector> [name] [--offset N] [--limit N]
```

| Field | Description | Requires `name`? |
|---|---|---|
| `text` | Text content of the matched element(s), whitespace-normalized | No |
| `textcontent` | Same as `text` today — an alias kept for compatibility; neither is a rendered-text (`innerText`) read, so CSS overflow does not clip them | No |
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

1. **Check the page and the selector first** — the read captured the tab first, so the snapshot *is* the page as it is now; an empty result means the selector did not match it. If the content arrives asynchronously, wait for it (`wait "<css>"`) and read again
2. **Verify the CSS selector** with `htmlsnapshot grep <pattern>` to search the HTML
3. **Use `htmlsnapshot query` or `htmlsnapshot get all`** for multiple results or complex queries
4. **Check page load:** ensure the page finished loading before the capture (AJAX content may take time)

## Query — X-SQL over the stored page

The `--sql` flag is **required**. Use `@url` as a placeholder for the target URL.

X-SQL uses the **H2 database** SQL dialect with DOM UDFs. Only simple `SELECT ... FROM DOM_LOAD_AND_SELECT(url, cssQuery)` queries are supported — no CTEs, subqueries, `EXPLODE`, or joins.

> **`query` refreshes the active page first: it captures the tab, then queries that snapshot.**
> - **No URL argument, or a URL matching the page the session is showing:** the query
>   captures the active tab, then runs over the snapshot it just took — so login
>   state, SPA updates and `eval` mutations are visible without a separate
>   `htmlsnapshot` capture.
> - **An explicit URL of another page (with or without a session):** the SQL runs
>   over that URL's stored copy; when the store has nothing for it, the page is
>   fetched independently through the read-only scrape path (no session state).
>   This is the offline/corpus path — querying pages that are not open in any
>   session still works without a browser. A URL the tab does not show cannot be
>   captured, so the tab's document is never filed under it.
>
> The same contract holds for every other read (`get` / `get all` / `inspect` /
> `summary` / `grep` / `export` / `readability`): a read of the active page
> captures it first and then serves that snapshot; a read of another URL serves
> that URL's stored copy. A `query` run without an explicit URL argument always
> targets the current page URL.

> **Important:** `@url` must appear **unquoted** in SQL. `SQLTemplate.createSQL(url)` handles escaping internally.
> - ✅ `FROM DOM_LOAD_AND_SELECT(@url, ':root')`
> - ❌ `FROM DOM_LOAD_AND_SELECT('@url', ':root')`
> - ❌ `FROM DOM_LOAD_AND_SELECT('.', ':root')` — the literal `'.'` is not a valid URL. Use the `@url` placeholder to reference the current page.

### Four ways to provide the SQL query

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

# Run it (add --format table for human-readable output — the default is a raw JSON envelope)
browser4-cli htmlsnapshot query "https://www.amazon.com/dp/B08PP5MSVB" --sql @query.sql --format table
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

### Output format and exit codes

Default output is the **raw JSON response envelope** (statusCode, status,
resultSet, …) — machine-readable but noisy. Pick a friendlier format with
`--format`:

```bash
browser4-cli htmlsnapshot query "https://example.com" --sql @query.sql --format table  # aligned table + "N rows returned."
browser4-cli htmlsnapshot query "https://example.com" --sql @query.sql --format csv    # CSV rows
browser4-cli htmlsnapshot query "https://example.com" --sql @query.sql --result-only   # just the resultSet (JSON)
browser4-cli htmlsnapshot query "https://example.com" --sql @query.sql --format json   # explicit raw envelope (default)
```

| Flag | What prints to stdout |
|---|---|
| *(none)* / `--format json` | Raw JSON response envelope — `statusCode`, `status`, `message`, `resultSet` … (machine-readable default) |
| `--format table` | Aligned column table + a `N rows returned.` summary line (human-readable) |
| `--format csv` | CSV rows (pipe to a file: `browser4-cli htmlsnapshot query … --format csv > rows.csv`) |
| `--result-only` | Just the JSON `resultSet` array (drop the envelope; combine with `--format table` for table output) |

Exit codes (for scripts — don't parse stdout to detect errors):

- `0` — the response envelope reports success (`200`). An **empty** resultSet
  is still exit `0`: "no rows matched" is not an error.
- Nonzero — the server returned an **error envelope**: `417 Expectation
  Failed` (a query/SQL error or the scrape session closed before the query
  executed — re-run, or use `htmlsnapshot get` / `eval` for simple
  extractions) or a `5xx` with an empty resultSet (backend scrape engine
  error). The envelope still prints to stdout (or `--output-file`) so you can
  inspect it; key off the exit code.

To control caching or rendering, append load options to the URL (e.g. `https://example.com/page -i 1d -njr 3`).

## Summary — Web Page Summary Index (WPSI)

Generates a deterministic, AI-readable compressed page summary (typically <1% of original HTML) as a YAML file. Includes page metadata, structure landmarks, key content nodes with CSS selector hints, list/table detection, and stats. Summarizes a **fresh snapshot of the active page** — the tab is captured first, so the summary is the page as it is now.

```bash
browser4-cli htmlsnapshot summary
browser4-cli htmlsnapshot summary --algorithm wpsi   # explicit built-in algorithm (default)
browser4-cli htmlsnapshot summary --expires 1d       # summarize the stored snapshot instead of the live page
browser4-cli htmlsnapshot summary --raw              # print the summary content, not the outline
browser4-cli htmlsnapshot summary -v                 # outline with internal scores and legend
```

The summarization algorithm is **pluggable**. The built-in algorithm is `wpsi`; installed Browser4 plugins can contribute additional algorithm ids. List what is installed with:

```bash
browser4-cli htmlsnapshot algorithms          # human-readable table, the default is marked
browser4-cli htmlsnapshot algorithms --json   # raw JSON array (id/displayName/description/version/builtin/default)
```

`htmlsnapshot algorithms` needs no open page or session. An unknown `--algorithm <id>` fails with the list of available ids. Output of non-`wpsi` algorithms is printed verbatim (the full content is still saved to a file).

**Overriding the default algorithm:**
The server-side default can be changed with the Spring property
`browser4.htmlsnapshot.summary.algorithm=<id>` (e.g. in
`application.properties` or as a `-D` system property). After plugins are
wired, `PluginManager` applies the configured value so that plain
`htmlsnapshot summary` (no `--algorithm`) runs your algorithm. If the id
is not registered, summary calls fail fast with the available list.

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
| `-n` / `--line-number` | GNU grep `-n` compatibility — line numbers are printed by default, so `-n` is accepted and does nothing extra |
| `--no-line-number` | Suppress line numbers in output (line numbers are shown by default) |
| `--selector <CSS>` | Scope search to a specific CSS element (fetches inner HTML via `html_snapshot_scrape`) |
| `--selector-all <CSS>` | Scope search to all elements matching the selector (querySelectorAll); each element's inner HTML is searched independently and results are annotated with the element index |
| `--raw-html` | Search the raw HTML including `<script>`/`<style>` content (by default script/style tags are stripped to avoid JS false positives) |
| `--page N` | Show page N of paginated output (default: 1) |
| `--page-size N` | Characters per page (default: 1024) |
| `--all` | Show all output, disabling pagination |

Line numbers are **on by default** (unlike GNU grep where you opt in with `-n`). `-n` is still accepted so GNU-grep muscle memory works — it is a no-op. Use `--no-line-number` to suppress the line-number prefix.

### Regex dialect

Patterns are **Rust regex** (`regex` crate) matched **per line** — not POSIX/PCRE, and not shell globs:

- **Alternation** is `|` — `price|rating` matches "price" or "rating".
- **Anchors:** `^` and `$` anchor to the **start/end of a line** of the snapshot, not the whole document — a bare `$` matches every line, so it looks like "everything matched".
- **A literal `$`:** write it as `[$]` (e.g. `'[$][0-9]+\.[0-9]{2}'` matches "$19.99"). The escaped form `\$` is *also* accepted by the Rust regex engine (a `$` is a metacharacter, so escaping it is valid), but `[$]` is the portable habit — it survives any number of shell-quoting layers without ambiguity.
- `\b` word boundaries and `\s`/`\d`/`\w` classes work as in most engines; other backslash escapes may be invalid.
- **Literal text:** pass `-F` to match a string exactly with no regex interpretation (no anchors, no alternation, no escaping needed).
- `-E` (extended regexp) is accepted for `grep -E` compatibility — ERE-like behavior is already the default.

`snapshot grep` shares the same Rust-regex dialect and flag set (it searches the AX-tree YAML instead of HTML); the HTML grep adds `--selector-all` and `--raw-html`, which only make sense over raw HTML.

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

- **List pages vs detail pages:** `inspect` finds **recurring** patterns — it shines on list/grid pages (search results, product cards, tables). A single product/article/detail page has no repeating block, so inspect may surface nothing (or an unrelated side rail). For detail pages use `htmlsnapshot summary` (visual clustering) to discover the main content selectors, then read them with explicit selectors (`htmlsnapshot get text "h1"`). When inspect finds nothing recurring it prints "No recurring pattern found" and points to `summary`.
- **Start without arguments:** `htmlsnapshot inspect` (no selector) triggers auto-discovery and finds the page's most prominent repeating content pattern. This is the quickest way to discover selectors on an unfamiliar page.
- **Start broad, then narrow:** First run without a selector to see page landmarks. Then target a repeating container (e.g. `.product_pod`, `.s-result-item`).
- **Inspect the live page:** `inspect` captures the active page itself and analyzes that snapshot, so it always inspects the page as the tab shows it now — no capture step to remember.
- **Use with `get`:** Take the suggested selectors and use them with `htmlsnapshot get all` or `htmlsnapshot query` for batch extraction.
- **Avoid quoting hell:** Use `--sql @file.sql` (file), `--sql-stdin` (piped), or `--sql-base64` (encoded) instead of inline `--sql "..."` on Windows — quoted CSS selectors and `!=` operators break inline SQL.
- **Base64 for portability:** `--sql "$(base64 -w0 query.sql)" --sql-base64` passes SQL safely through any shell, CI pipeline, or HTTP transport with zero quoting issues.
- **`@file` paths resolve against the Browser4 repo root first**, then fall back to the current working directory — so `cargo run` from `cli/browser4-cli` still finds `query.sql` at the workspace root, while a file that exists only relative to the CWD is picked up by the fallback.

## Readability — One-step article extraction

Extracts the main article content using a deterministic, Readability-style heuristic (the same family of algorithms behind Firefox Reader View). **No LLM, no tokens, no CSS selectors required.** Without a URL it runs on a fresh snapshot of the active page (the tab is captured first); with a URL it reads that URL's own stored copy — or loads it read-only on the shared scrape session when the store has nothing, so **your tab is never navigated** by a read of another page.

```bash
# Extract the article from the active page's fresh snapshot
browser4-cli htmlsnapshot readability

# Plain text only (no metadata header), no pagination
browser4-cli htmlsnapshot readability --text-only --all

# Fetch and extract a specific article URL independently
browser4-cli htmlsnapshot readability "https://example.com/article"
```

### Output

Prints a metadata header (title, byline, site name, URL, character count, confidence) followed by the article text, paginated at 2000 lines by default. Use `--text-only` for just the text and `--json` for the full result (cleaned content HTML included under `content`).

| Field | Meaning |
|---|---|
| `title` | Page title (falls back to the article H1) |
| `byline` | Author, when discoverable (`meta[name=author]`, `rel=author`) |
| `siteName` | Site name, when discoverable (`og:site_name`) |
| `excerpt` | Meta description, when available |
| `length` | Article plain-text character count |
| `confidence` | Coverage ratio — fraction of the page's text captured by the article region |
| `content` | Cleaned article HTML (classes stripped by default) |
| `textContent` | Article plain text |

### How it works

1. Pre-clean: scripts, styles, forms, navigation landmarks, and hidden elements are dropped.
2. Candidate scoring: containers accumulate paragraph text density; link-heavy regions (nav, link farms) score near zero.
3. The best container is chosen (preferring a real article region over the whole `<body>`), then sanitized: noise widgets, empty elements, and class/id attributes are removed.
4. Metadata (title, byline, site name, excerpt) is extracted from the document head.

### Positioning vs other commands

- **`readability`** = heuristic article extraction (offline, deterministic, zero tokens) — best for long-form articles and news pages.
- **`get text "<selector>"`** = explicit CSS extraction — use when you know the structure.
- **`extract`** = LLM-based natural-language extraction — use for complex instructions (needs an LLM key).
- **`export --clean`** = whole-page minimal HTML for LLM consumption.

### Error handling

- Fails loudly when the page has no article-like content (text below the ~500-char threshold or no article structure). Try a page with substantial text, or fall back to `htmlsnapshot get text "<selector>"` / `htmlsnapshot inspect`.
- Reads the active page like `get`/`inspect`/`summary` — capturing the tab first and then extracting from that snapshot — when no URL is given; with a URL argument it reads **that URL's** stored copy, fetched read-only on the shared scrape session when the store has nothing (your tab is never navigated). It never files the tab's document under the URL you pass.

## Error Handling

- `htmlsnapshot` capture fails if backend is unreachable or page cannot be loaded.
- `htmlsnapshot get` / `get all` print a diagnostic ("No elements matched …") and exit `0` when the CSS selector matches nothing — consistent with `query`'s "no rows matched is not an error". A non-zero exit means the backend call failed (e.g. an invalid selector or an element ref like `e5`, which `get` does not accept).
- `htmlsnapshot query` exits nonzero on invalid X-SQL syntax, a missing `--sql`, or a server error envelope (`417 Expectation Failed` or a `5xx` with an empty resultSet). A `200` envelope with an empty resultSet ("no rows matched") is exit 0.
- `htmlsnapshot export` / `summary` / `inspect` / `readability` / `get` / `query` capture the active page first and then serve that snapshot (another URL's command serves that URL's stored copy, or an independent read-only load when the store has nothing). They fail only when the tab shows nothing archivable (about:blank, a browser error page, or a dead session) or when there is no loaded page to resolve an explicit URL against. An unparsable `--expires` value fails the read by name (`Invalid expires value '5' for scrape …`) rather than being ignored.

## Notes

- `htmlsnapshot get` only accepts CSS selectors. For interactive element interaction, use the standard `snapshot` + ref-based commands.
- X-SQL queries through `htmlsnapshot query` follow the same constraints as `swarm query`. See [X-SQL reference](x-sql.md) for full function documentation.
- Every `htmlsnapshot` command **captures the active page first and then operates on that snapshot** — the family is about the page the tab is showing, so by default no command serves an older copy of it. `htmlsnapshot` (capture) *returns* the snapshot: it serializes the document the active tab is showing and stores it under the tab's **normalized** URL (the store identity), overwriting whatever the store held for that URL. That overwrite relies on the load option `-expires 0s` inside the capture call: it bypasses the process-wide page cache, because `persist` deliberately drops the content of a page whose shell came from that cache (a capture that skipped the cache bypass would report the new document while the store kept the old one). **`-expires 0s` here is a cache flag, not a reload** — capture never navigates, scrolls or re-fetches: the document always comes from the tab, so interactions, SPA state and `eval` mutations survive it, and every read command (`get`, `get all`, `inspect`, `summary`, `grep`, `export`, `query`, `readability`) sees them without a capture of its own. (It replaced the older `-refresh` spelling, which says the same thing — `-refresh` == `-expires 0s -ignoreFailure` — but reads like a page reload, which a capture never does.)
- The one deliberate exception to "always the live page" is the caller asking for the stored one: `--expires <dur>` on a read serves the stored snapshot of the active page while it is younger than the window, leaving the tab untouched — the way to operate on the *previous* snapshot version. The default `0s` reuses nothing, so the live-page behavior above is what a read does unless it is asked otherwise.
- The capture is always keyed by the **active tab's own** URL, never by a URL the caller passed. A URL the tab does not show cannot be captured, so those commands (`readability <url>`, `query --url <url>`) keep the read-only path: that URL's stored copy, or an independent read-only load when the store has nothing. This is the invariant that keeps a read from filing the tab's document under a URL it only asked to read.
- The metadata `url` is the normalized identity; `href` is the raw address the tab reported (query and fragment included). The href is what the browser should be given when something has to be *opened*, the url is what everything is *looked up* by.
- `htmlsnapshot grep` performs matching **entirely client-side** in the CLI — the full HTML is fetched from the backend once, then all regex matching happens locally. No backend round-trips for the search itself.
- For CI pass/fail checks with grep, use `-l` (prints "htmlsnapshot" if matches found) or `-c` (prints match count). A `browser4-cli` non-zero exit code means the backend call itself failed, not that matches were absent.
- `htmlsnapshot` capture now returns enriched metadata: `imageCount`, `linkCount`, and `interactiveElements` (tag, class, id, aria attributes, bounding-box). The bounding box comes from the `vi` (visual-information) data the Browser4 runtime computes from the live layout and injects **while serializing** the HTML — `vi` is deliberately not a DOM attribute (the live page stays untouched), so it only exists in HTML the driver serialized. The capture produces that data on demand, so a session that only navigated (`goto`, tab switch, form submission) still gets boxes, and `htmlsnapshot export` writes `vi` attributes for offline consumers.
- Exported HTML also carries `<link rel="normalizedURI" href="…">` in `<head>`: the page URL after `PulsarSession.normalize()`, injected during serialization the same way. Together with `vi`, it makes the exported artifact self-describing — an offline consumer can tell both *where* each element sits and *which page* the document is. (A subtree read through the driver, e.g. `outerHTML(selector)`, intentionally has no such link: the URL describes the document, not a fragment.)
- `htmlsnapshot inspect` computes relative CSS selectors using tag + class + id. It does not use AI — the algorithm is fully deterministic and based on structural recurrence across matching elements. When run without a selector (or any single-match selector like `:root`), **auto-discovery** finds the page's most prominent repeating content pattern automatically — no prior knowledge of the page's markup is needed.
- **Output pagination:** `get html` and `grep` paginate output by default at 2000 lines per page. `get all …` (any field) and the `text` / `textcontent` fields print in full, unpaginated. Use `--page N` for subsequent pages, `--page-size N` to change the page size, or `--all` to disable pagination entirely. Pagination is automatically skipped in `--json` and `--quiet` modes. Use `--all` when piping output to external tools.
