---
title: "Crawl Command Reference"
description: "Reference for the crawl command. Recursive website crawling from a URL or seed file, with optional X-SQL data extraction and multi-format output."
tier: procedure
---

# Crawl Command Reference

Recursive website crawling — start from a URL or seed file, follow links up to
a configurable depth, and optionally extract structured data from each page with X-SQL.

## Quick start

```bash
browser4-cli crawl "https://example.com" --out-link-selector "a[href]"   # link discovery
browser4-cli crawl --seed-file urls.txt --depth 0                        # bulk fetch, no links
browser4-cli crawl --seed-file urls.txt --sql "@extract.sql" --format csv -o results.csv
```

> **Notes:** `--out-link-selector` is required for link discovery; without it only seed URLs are
> processed whatever the depth (depth 0 needs none).  `--ignore-url-query` / `--no-norm` affect
> out-links *discovered* at `--depth 1+` only — a depth-0 seed fetch always requests the seed URL
> verbatim, so both flags change nothing there.  Git Bash path-converts a value starting with `/`
> (`-olp "/product/"`): use `./b4w.sh crawl …` or `MSYS2_ARG_CONV_EXCL='*' ./b4w.ps1 …`, or drop
> the slash (`-olp "product/"`).

## When to Use

Use **crawl** for sequential multi-page workflows with built-in link discovery, seed-file bulk processing, and X-SQL extraction to structured output (CSV/JSON/table). Prefer **swarm** for parallel high-throughput extraction. Prefer **loop** for repeated monitoring at intervals. Prefer **htmlsnapshot query** for extracting from a single page.

## How It Works

Crawl loads seed URLs, optionally follows links up to a configurable depth, deduplicates visited pages, and optionally runs an X-SQL query against each page. Results are aggregated and formatted as table, CSV, or JSON. Use `--background` for async execution.

## Common patterns

### Bulk product detail extraction

```bash
# Extract product URLs from search results (via eval or X-SQL), write to urls.txt
browser4-cli crawl --seed-file urls.txt --depth 0 --sql "@extract.sql" --format csv -o products.csv
```

`extract.sql`:
```sql
SELECT DOM_BASE_URI(dom) AS url, DOM_FIRST_TEXT(dom, '#productTitle') AS title,
       DOM_FIRST_TEXT(dom, '.a-price .a-offscreen') AS price
FROM DOM_LOAD_AND_SELECT(@url, 'body')
```

### Shallow crawl with extraction (list page + detail pages)

```bash
browser4-cli crawl "https://example.com/products" -ol "a.product-link" -tl 50 -d 1 \
  --sql "SELECT DOM_FIRST_TEXT(dom, 'h1') AS title, DOM_FIRST_TEXT(dom, '.price') AS price FROM DOM_LOAD_AND_SELECT(@url, 'body')" --format json
```

### Deep crawl (depth > 1) — recursive link following

```bash
browser4-cli crawl "https://example.com/docs" -ol "a[href]" -olp ".*/docs/.*" -d 3 -tl 30
browser4-cli crawl "https://example.com/shop" -ol "a.product" -olp "product/" -d 2 -tl 50
```

### LoadOptions passthrough and quality requirements (`--args` / `-a`)

`-a` / `--args` takes a **raw [LoadOptions](load-options-guide.md) string**, not JSON: split on
whitespace and forwarded verbatim.  A `-`-leading value is accepted, so all forms below bind:

```bash
browser4-cli crawl "https://example.com" -ol "a[href]" --args="-requireSize 100000 -scrollCount 5"
browser4-cli crawl "https://example.com" -ol "a[href]" -a "-nMaxRetry 5 -lazyFlush"
browser4-cli crawl --seed-file urls.txt -d 0 -a @loadopts.txt   # or: --args-stdin < loadopts.txt
```

> **Note:** the hyphen-value allowance is per option (`-a`/`--args`), not a general rule — other
> short options still refuse a `-`-leading value, and an unbound `-a` value is read as a positional
> seed URL (`Invalid crawl URL '-timeout 30m' (command argument)` means the option never bound).

### X-SQL from stdin or a file

```bash
browser4-cli crawl --seed-file urls.txt --depth 0 --sql-stdin --format table < query.sql
browser4-cli crawl --seed-file urls.txt --depth 0 --sql "@extract.sql" --format csv -o out.csv
```

## Modes

### Link discovery mode (depth >= 1)

Loads each seed URL; extracts links matching `--out-link-selector` (a CSS selector), optionally
filters them with `--out-link-pattern` (a regex), deduplicates them and limits them to
`--top-links`; loads each linked page and, if `--depth` > 1, repeats that for the loaded pages
(skipping visited URLs).  Returns human-readable text (or JSON with `--json`).

### Bulk fetch and X-SQL extraction (depth 0 / `--sql`)

Loads each URL directly without link discovery — for a list of known detail pages (product pages,
articles), and for `--sql` extraction when you already have the URLs.  The query runs against each
crawled page with `@url` replaced server-side; results are aggregated across all pages and
formatted by `--format`.  X-SQL function names are case-insensitive (`DOM_FIRST_TEXT`).

## Flags

### Core flags

| Flag | Short | Type | Default | Description |
|---|---|---|---|---|
| `url` (positional) | | string | — | Starting URL. Must be absolute (`https://…`); omit when using `--seed-file` |
| `--seed-file` | | string | — | File with URLs to crawl, one per line, each an absolute URL. Lines starting with `#` are comments |
| `--depth` | `-d` | int | `1` | 0 = fetch only (no links); 1+ = follow links to that depth |
| `--parallel` | | int | `4` | How many units (pages/tabs) to collect at the same time. `1` = strictly sequential |
| `--timeout` | | duration | `10m` | Whole-crawl cap (max `1h`); bare number = seconds — use `30m` |
| `--background` | `-bg` | bool | — | Submit crawl and return immediately; use `crawl list` to track |

### Task budget (`--timeout`)

A crawl runs under a **task budget**: one clock for the whole task, from which every round derives
its own timeout.  `--timeout` sets it for this crawl only — default 10m, floor `1s` (below it the
CLI exits non-zero: a crawl with no time cannot start a single round), ceiling `1h` (the budget buys
browser time).  A **bare number counts seconds** — `--timeout 30` is 30 seconds — so write `30m`;
`--page-load-timeout` bounds one page load instead.

The budget is what makes a large crawl *end* rather than be *killed*: a seed whose round cannot fit
in what is left is refused **before** it is submitted and reported as a lost page (`reason = the
crawl ran out of its time budget before this URL was submitted`, seed status `skipped`), so
`pagesFound + failedPages.size == pagesExpected` still holds on a truncated crawl.  The record
reports the budget it ran under (`taskTimeoutMillis`, including a server-side clamp), so a very
large seed list needs a larger `--timeout` or several crawls — losses name the URLs never started.

Inside the budget, a round (one seed URL at depth >= 1) gets the **smaller** of `5 min × depth`
(capped at 30 min) and what the task has left minus a 30s reporting margin, so a round ends on its
own terms and its outstanding URLs are reported as lost instead of being killed by the task limit
(which used to turn a deep crawl into "fewer pages, no losses reported").  A seed with less than
~45s of budget left is refused with a `skipped` seed status, as above.

The CLI's own wait is 600s (`BROWSER4_CLI_CRAWL_TIMEOUT_SECS`): when it expires the crawl keeps
running server-side, so poll it with `crawl status` / `crawl result`.

```bash
browser4-cli crawl --seed-file urls.txt -d 0 --timeout 30m --refresh   # beyond the 10m default
browser4-cli crawl --seed-file smoke.txt -d 1 --timeout 2m
```

### Parallelism (`--parallel`)

A crawl is a set of **independent units** — one per seed URL — collected at the same time by
default, each on its own tab leased from the driver pool; `--parallel <n>` bounds how many may be
in flight (omitted = server default 4, `1` = sequential, `2`–`32` = up to `<n>` concurrently).

* **Each unit needs its own tab.** The driver pool (`browser.context.number` ×
  `browser.max.active.tabs`, 2 × 8 by default) is the hard ceiling: asking for more yields a lower
  *observed* peak, which the report shows — a peak of `1` on a multi-unit crawl means the
  collection was serial, and that is called out explicitly.
* **Values are validated before submitting.** `0`/non-numeric values exit non-zero with a hint
  (`--parallel 1`); above 32 the server rejects (HTTP 400).  At `-d 1+` the budget bounds *seed
  rounds*, not pages: each round discovers its links while the pages come from the shared tab pool,
  so rounds can overlap even when a single round has few links.

```bash
browser4-cli crawl --seed-file urls.txt -d 0 --parallel 8 --refresh   # 12 seed URLs, at most 8 at a time
browser4-cli crawl --seed-file urls.txt -d 0 --parallel 1 --refresh   # strictly sequential (a rate-limiting site)
```

### X-SQL and output flags

| Flag | Short | Type | Default | Description |
|---|---|---|---|---|
| `--sql` | | string | — | X-SQL query. Use `@url` as page URL placeholder. Prefix with `@` to read from file |
| `--sql-stdin` | | bool | — | Read query from stdin (avoids shell quoting issues) |
| `--sql-base64` | | string/bool | — | Base64 query — value form, or bare flag over `--sql` |
| `--format` | | string | `table` | Output format for X-SQL rows: `json`, `csv`, or `table`.  Requires `--sql`: without it the CLI **warns on stdout** but **refuses** (exit 1, writes nothing) when `--output` is also given, because the file would hold the plain-text listing under a `csv`/`json` name.  For a JSON *page listing* of a plain crawl use the global `--json` flag before the command — no `--sql` needed |
| `--output` | `-o` | string | — | Write results to file instead of stdout |
| `--verbose` | | bool | — | Show per-URL processing status in the crawl results (also surfaces per-page X-SQL diagnostics instead of only the aggregate error count) |

> **Note — `--sql-base64` accepts two shapes:** `--sql "<base64>" --sql-base64` (bare flag) decodes
> the `--sql`/`--sql-stdin` text; `--sql-base64 "<base64>"` (value form) supplies the encoded query
> itself, but for `crawl` only when `--sql`/`--sql-stdin` is present — alone it is accepted and does
> no extraction at all and no error, unlike `swarm submit`/`htmlsnapshot query`.  Prefer the flag.

> **Note — what `--output` writes:** `-o` writes the **crawl result** — the aggregated X-SQL rows
> with `--sql`, else the page listing (`depth=N | <url> | <title>`).  It does **not** write page
> HTML: to stage pages as files (e.g. for WebMiner) use `htmlsnapshot export`, or
> `webdb export "<url1,url2,…>" <dir>` for pages already in the page store.

### Link discovery flags

| Flag | Short | Type | Default | Description |
|---|---|---|---|---|
| `--out-link-selector` | `-ol` | string | — | CSS selector to extract links from each page |
| `--out-link-pattern` | `-olp` | regex | `.+` | Regex to filter links (Git Bash: no leading `/`) |
| `--top-links` | `-tl` | int | `20` | Max **distinct** pages one page may contribute (repeats are removed first) |

> **Git Bash / MSYS2 — leading-`/` values:** `/`-leading values (e.g. `-olp "/product/"`) are
> converted into Windows paths before the CLI sees them, and the pattern then filters out every
> link.  Use `./b4w.sh` (it exports `MSYS2_ARG_CONV_EXCL='*'`), run from PowerShell, or drop the
> slash (`-olp "product/"`).  See [shell-quoting.md](shell-quoting.md).

### LoadOptions flags

| Flag | Short | Type | Description |
|---|---|---|---|
| `--args` | `-a` | string | Raw LoadOptions string; bind it as `--args="…"` or `-a @file` |
| `--refresh` | | bool | Force fresh fetch (ignore cache) |
| `--parse` | | bool | Parse pages after fetch |
| `--expires` | | string | Cache TTL: `1d`, `1h`, `30m`, etc. Invalid values are rejected by the CLI (non-zero exit) |
| `--priority` | `-p` | int | Queue priority (non-negative integer; lower = higher priority) |
| `--page-load-timeout` | | string | Per-page load cap (`30`, `30s`, `1m`) — not the crawl budget |
| `--ignore-url-query` | | bool | Out-links only (depth 1+); seeds are fetched verbatim |
| `--no-norm` | | bool | Out-links only (depth 1+); no URL normalization of discovered hrefs |
| `--readonly` | | bool | Non-destructive mode: loads may be served from the page store and are never written back. Wins over `--refresh` (see below) |

> **Note — seed-only crawls:** a `--depth 0` fetch discovers nothing, so `--ignore-url-query` and
> `--no-norm` are accepted and change nothing; a stderr advisory for that case is not emitted yet.

## Output formats

### X-SQL result formats (`--format`)

| `--format` | Output |
|---|---|
| `table` (default) | Aligned columns with a header and separator |
| `csv` | Header row; fields containing commas, quotes or newlines are quoted |
| `json` | Pretty-printed JSON array of row objects |

### Page listing (no --sql)

Without X-SQL, the default output lists the crawled pages.  `--format csv|json` cannot format that listing: on stdout it only warns, and with `--output` the CLI refuses (exit 1, no file) rather than writing prose into a `.csv`/`.json` path.  For a JSON page listing use the global flag — `browser4-cli --json crawl "<url>" -d 1` prints the envelope, and `--json` with `-o` is refused for the same reason.

```
Crawl task submitted: 550e8400-e29b-41d4-a716-446655440000
Crawling... 3 pages found (18s elapsed)

Crawl completed. 3 pages found.
  depth=0 | https://example.com/page1 | Page 1 Title
```

> **Timing — slow progress is normal:** each page takes **several seconds** (roughly 5–7 s) through
> the backend parse/load pipeline, even locally, and progress updates only when a page completes —
> a 3–10 page local crawl can legitimately take 20–60 s, so repeated `Crawling...` lines are
> progress, not a hang.

## Testing locally with MockSite

The mock e-commerce site (`./bin/test.ps1 mock-site`) provides predictable product pages for
testing crawl extraction without hitting live websites.  A cold start runs a Maven preflight
(browser4-dependencies, then browser4-rest and ~25 modules) that serves nothing on `:18080` for
about 10–15 minutes — it looks stalled; with cached artifacts the next start is fast.  Don't guess
product IDs: MockSite exposes a sitemap built for URL discovery, and the bare path
`http://localhost:18080/ec/dp/` (trailing slash) 404s — product pages need an ID (`/ec/dp/<id>`).

```bash
curl -s http://localhost:18080/ec/sitemap.xml > map.xml   # all 101 product URLs, 20 categories
grep -o 'http://localhost:18080/ec/dp/[A-Z0-9]*' map.xml | head -8 > seed-urls.txt
browser4-cli crawl --seed-file seed-urls.txt --depth 0 --refresh --sql "@extract.sql" --format table
browser4-cli goto "http://localhost:18080/ec/dp/B0E000001"
browser4-cli htmlsnapshot inspect                    # element patterns, incl. singleton IDs
```

Detail pages (`/ec/dp/…`) use IDs (`#productTitle`, `#product-price`, `#product-features`);
listing pages (`/ec/b?node=…`) use classes (`.breadcrumbs`, `.product-card`, `.product-title`,
`.product-price`).

> **Browser vs. raw HTML:** MockSite serves a JavaScript-hydrated variant to browsers (the crawl
> fetch pipeline) that can differ from the static HTML `curl` receives — e.g. category anchors
> arrive as `href="#"` and the rendered product list may be a subset.  When debugging link-discovery
> counts, verify the *browser* DOM with `eval` or `htmlsnapshot inspect`.

> **Tip:** When selectors don't match, use `htmlsnapshot grep --selector` to verify elements exist,
> or `htmlsnapshot inspect` to discover selectors.  MockSite uses IDs, not classes.

## URL deduplication

- Visited URLs are normalized for dedup: scheme and host lowercased, fragment removed, a trailing
  path slash dropped (`…/p/` and `…/p/?q=1` fold onto `…/p` and `…/p?q=1`).  The **query string is
  kept** (`?page=1` ≠ `?page=2`) and the path is not lowercased — both are case sensitive.
- Use `--ignore-url-query` to treat query variants of one path as the **same** page: it strips the
  query from discovered hrefs *before they are queued*, so a grid/list toggle (`?src=grid` /
  `?src=list`) collapses into one page and costs one `--top-links` slot.  Without it each spelling
  is its own page and spends budget — the safe default, since a query usually selects the document.
- `--top-links` is a budget for **pages**, not anchors: links are deduplicated *before* the budget
  applies, so a product linked twice (image and title) costs one slot.
- A fragment is never part of a queued or reported URL: `product/1.html#specs` is queued (and
  reported) as `product/1.html`, and one page offered under several spellings queues the first in
  document order.  Fragment-only anchors (`href="#"`, `href="#section"`) are skipped — they can
  never navigate to a new document — and are not counted as discovered out-links.
- The URL the crawl was *asked* to fetch is never rewritten: a seed keeps its query string, so
  `crawl "https://h/search?q=x&page=3"` fetches exactly that URL and the row reports it.  Use
  `--no-norm` to also disable LoadOptions-level normalization of discovered hrefs (not the internal
  dedup normalization).

> **Scope note:** `--ignore-url-query` and `--no-norm` only affect links *discovered* at depth ≥ 1;
> seed URLs in a depth-0 bulk fetch are fetched and reported verbatim, so they change nothing there.
> When in scope, `--ignore-url-query` changes *which* pages a crawl finds: a paginated listing
> linked as `?page=1…N` yields **one** page with the flag and N without it.

## `--readonly` and the X-SQL second read

A crawl normally forces a fresh fetch (`-refresh`) on every page it loads.  With `--readonly` it
does not: **`--readonly` wins over `--refresh`**, because the X-SQL engine reads a page **twice** —
first the crawl's own load freezes the page into the local page cache under the URL the statement
will resolve; then the `load_and_select()` / `load()` UDF resolves that URL in the statement's FROM
clause, and the statement is *sealed* with `-readonly` and every fetch-forcing option erased, so
that read serves the frozen copy (no network round trip, no page-store or cache write).  The flags
cannot be combined: `-refresh` expands to `-ignoreFailure -i 0s`, making every local copy look
expired, so the read-only shortcut is missed and the UDF re-fetches the page mid-query.

```bash
browser4-cli crawl --seed-file urls.txt --depth 0 --readonly   # never write the pages back
```

What to expect:

- A page **in the store** is served from there: the rows carry the store markers and the completion
  note reports the served content's age (`readonly: N/M page(s) served from the page store
  (stored content up to … old)`).
- A page that is **not** local is fetched (read-only is a cache-hit *preference*, not a fetch
  prohibition) but not written back, and the note says `readonly: verified fresh — all N page(s)
  fetched from the live site`.
- Link discovery over a stale stored page can legitimately find no out-links; the empty-out-links
  diagnostic explains it.  Use `--refresh` **instead of** `--readonly` to see the live site.

## Seed files

Plain text, one URL per line.  Blank lines and lines starting with `#` are ignored, and when
both a positional `url` and `--seed-file` are given, the URL is prepended to the list.

```text
https://www.amazon.com/dp/B0C17W3Q9B
```

Every seed — the positional URL and each seed-file line — must be an **absolute URL**.  A value the
crawl could never fetch is refused **before** the task is submitted; the message names the seed and,
for a seed file, the line it came from:

```text
Invalid crawl URL 'formats' (command argument): expected an absolute http(s) URL such as https://example.com/page.
Invalid crawl URL 'formats' (urls.txt line 4): expected an absolute http(s) URL such as https://example.com/page.
```

A host without its scheme gets the fix suggested (`did you mean 'https://example.com'?`), and a
misspelled or unfetchable scheme (`htps://…`, `ftp://…`) names that scheme.  Several refused seeds
are reported together (the first five, then a count).  Without this check the seed was submitted
anyway: the fetcher substituted its default search-engine URL, the task reported `0 pages found`,
and the run exited `0` — a typo that looked exactly like an empty site (issue #616).  A seed may
still carry trailing LoadOptions (`https://example.com -expires 1d`), split off before the check.

## Error handling

| Situation | Behavior |
|---|---|
| No URLs provided | Exits with "No URLs provided. Specify a URL argument or --seed-file." |
| Empty seed file | Exits with "No URLs provided." after parsing |
| Malformed seed | Exits with "Invalid crawl URL '<value>' (<origin>): expected an absolute http(s) URL such as https://example.com/page." before the server is started — `<origin>` is `command argument` or `<seed file> line <n>`. A bare host is answered with `did you mean 'https://<host>'?` |
| Timeout | Exits with message + task ID; increase `BROWSER4_CLI_CRAWL_TIMEOUT_SECS` |
| Server error | Exits with "Crawl failed: ..." and server error details |
| No links found (depth >= 1) | Exit 0 with a `⚠ Link discovery found no out-links` warning plus the backend diagnostic (it distinguishes "selector matched nothing" from "pattern filtered them all") and the effective `--out-link-pattern`. The seed page is always counted in depth ≥ 2 crawls, so an all-filtered crawl reports `Crawl completed. 1 pages found.` (depth-1 crawls list only discovered pages and report `0 pages found`). Inspect the warning text and verify `--out-link-selector` / `--out-link-pattern` — a shell-mangled pattern (Git Bash `/`-prefix conversion) is the usual cause |
| Pages lost (any depth) | Exits **6** after printing a `Summary: ok: <n>, failed: <m>` line and a `⚠ N of M submitted page(s) were never delivered` warning naming each lost URL, its depth, its protocol status and the reason. The crawl is **incomplete**, not merely small: `pagesFound + failedPages.size == pagesExpected` always holds. Check `failedPages` in the JSON output. A page is lost when its fetch failed after the retry budget was exhausted, when the task was dropped/evicted, when the crawl ran out of its time budget before the URL was submitted, or when the load returned no document of its own — a zero-byte fetch, or the page store substituted for a failed fetch (`reason = the load returned no document …`; such a URL is **withheld from the listing** rather than shown as a row with an empty title). Re-run, or lower `--depth` / reduce concurrency if it repeats — a repeated loss on a many-core host usually means the target site is refusing the parallel load, so try `--parallel 2` (or `--parallel 1` to rule parallelism out entirely) |
| Crawl hit the task limit | The task ends `TIMEOUT` and the CLI exits non-zero ("Crawl failed: Crawl timed out while processing seeds …"). `crawl result <taskId>` still carries the accounting: the losses of the seeds that settled, plus **one lost-page row per seed whose round never returned**, reason `the server-side task limit fired while this URL was still being fetched`. The pages such a round had already published are deliberately *not* claimed — its submitted count is unknown, and claiming them would break the `pagesFound + failedPages.size == pagesExpected` invariant — so re-run those URLs. Lower `--depth`, raise `--timeout` (up to `1h`), or split the seeds across several crawls, to stay inside the limit. A seed that is refused *before* it starts reports `reason = the crawl ran out of its time budget before this URL was submitted` and a `skipped` seed status. **Such a task is resumable**: the record sets `resumable: true` with the URLs left in `remaining`, and the CLI prints a `crawl resume <task-id>` hint — continuing it fetches exactly those URLs instead of re-crawling from the seeds |
| Task interrupted (backend restart, crash, SIGKILL) | `crawl status` reports `Interrupted` — never a phantom `Processing` — with `remaining` and `skippedAlreadyFetched`, and the CLI tells you to run `crawl resume <task-id>`. The task is terminal (nothing waits on it), exempt from the task store's LRU and TTL, and not removed by `crawl clear`; `crawl clear --all` discards it and its checkpoint. With no checkpoint on disk the record says it **cannot** be resumed and has to be submitted again |
| Page listed with `depth=-1` (depth >= 2) | The page was fetched and recorded, but neither the URL it was queued under nor the URL it was served from is a URL this crawl submitted (a redirect combined with a `<base href>`). It is listed with `depth=-1`, counted in `pagesFound`, **not** reported as lost, and **not** expanded (`-1` is never read as depth 0). A single such row is a labelling gap; if every row has it, the site rewrites its document base URI and the listing depths are not meaningful — use `--depth 1`, or report it |
| Invalid --format | Exits with "Invalid --format '...'. Expected: json, csv, or table" |
| Invalid --parallel | Exits with "Invalid --parallel value '...'" — accepts a positive integer up to 32; `0` is rejected with the `--parallel 1` hint, and anything above 32 is refused by the server (HTTP 400) |
| Invalid --timeout | Exits with "Invalid --timeout value '...'" — accepts seconds (`900`) or a duration (`30s`, `10m`, `1h`); below `1s` and above `1h` are both refused before the crawl is submitted |
| X-SQL failure on one page | Page logged with error; other pages continue normally — the crawl still completes, but the run exits 6 (see below) |

**Partial failures exit 6.** A polled crawl that lost pages (`failedPages`) or delivered pages with
no usable content (an `extractionError`, or a 0-byte `contentLength`) is reported as completed
*with errors*: it prints `Summary: ok: <n>, failed: <m>`, records `pages_failed` in its JSON, stores
the local task as `partial failure (<ok> of <pages> pages ok)` and exits **6** (`PartialFailure`).
Exit 0 stays reserved for a crawl where nothing failed; in `--json` mode the failure path emits the
error envelope, which carries no accumulated fields, so the counts arrive only inside
`error.message` (on the success path `pages_failed` is present and `0`).

## Rate Limiting & Polite Scraping

Crawl includes built-in rate limiting between page loads. For manual batch operations: add
`wait 1000-3000` between rapid navigations on the same site; use `eval` or `htmlsnapshot get all`
to batch-extract from one page load instead of navigating to each detail page; prefer conservative
`--depth` and `--page-load-timeout` for automated multi-page traversal; for `swarm`, control
parallelism with `--max-browser-contexts` and `--max-open-tabs`.  Amazon and similar sites may show
CAPTCHAs under aggressive automated access — longer delays reduce risk.

## Subcommands

A crawl submitted with `--background` returns a task ID immediately.  These subcommands
manage and monitor the task afterwards.

| Subcommand | What it does |
|---|---|
| `crawl status <task-id>` | Raw record on **stdout**; the one-line summary (`Created` / `Processing` / `OK`, pages found, errors) and the interrupted / resume notes on **stderr** |
| `crawl result <task-id>` | Same split: raw record on **stdout**, summary and the "still processing" / resume notes on **stderr**.  A task still `Processing` returns its partial record with `status: Processing`, so `result` and `status` both work as a poll.  `--verbose` adds the per-page provenance rows on stderr |
| `crawl cancel <task-id>` | Cancel a running or queued task; it transitions to TIMEOUT and stays visible in `crawl list` until cleared or expired by TTL.  `{"cancelled": false}` means no running worker was found — the record is still queryable.  A cancellation does **not** throw the work away: the checkpoint is kept, the record reports what is left, and `crawl resume <task-id>` continues it |
| `crawl resume <task-id>` | Continue an interrupted task from its checkpoint — see [`crawl resume`](#crawl-resume) below |
| `crawl clear` | Remove completed, cancelled and failed tasks from the store; running tasks are not affected.  A checkpoint that still has work left is **kept**, so a cleared timed-out task can still be resumed by id, and interrupted tasks are not touched at all (they are not finished tasks); `crawl clear --all` is the explicit way to discard resumable state — after it, an interrupted task can only be submitted again |
| `crawl list` | List all tracked crawl tasks across all sessions.  This is **housekeeping state, not an audit log**: listing prunes entries the backend no longer knows and entries older than 24 h, and prints what it removed.  Backend records and crawl checkpoints are never affected; `crawl result <task-id>` still works for a pruned id |

```bash
browser4-cli crawl status <task-id>
browser4-cli crawl result <task-id>
browser4-cli crawl cancel <task-id>
browser4-cli crawl clear --all
browser4-cli crawl list --status interrupted
```

`crawl status` and `crawl result` follow one rule: **stdout carries only the backend's raw JSON
record**; the human summary and the interrupted / resume / still-processing notes go to **stderr**
(`-q`/`--quiet` and JSON mode suppress that summary — with `--quiet` the record goes too, so the
command prints nothing).  A script reads **stdout only**.

`--json` is **global**: write it **before** the command (`browser4-cli --json crawl status <id>`).
stdout is then a single-line envelope (`{"status":"ok","command":"crawl-status","output":{…}}`)
instead of the raw record, carrying `output.task_id`, `resumable`, `remaining`,
`skipped_already_fetched`, `resume_count`, `resumed_from` and `raw` for `status` (`task_id` and
`raw` for `result`).  Do not write `crawl status --json <id>`: the flag then swallows the id.

```bash
# Poll a background crawl: loop while the record still says Created/Processing.
id=550e8400-e29b-41d4-a716-446655440000
while :; do
  rec=$(browser4-cli crawl status "$id" 2>/dev/null)   # drop the stderr summary
  grep -qE '"status" *: *"(Created|Processing)"' <<<"$rec" || break
  sleep 10
done
printf '%s\n' "$rec"    # terminal record: OK, Request Timeout, Interrupted, …
```

The wire values are `ResourceStatus` display text — `Created`, `Processing`, `OK`,
`Request Timeout`, `Internal Server Error`, `Not Found`, plus `Interrupted` (the worker died with
the backend — see [`crawl resume`](#crawl-resume)); one vocabulary, defined by `CrawlStatus` on the
backend.  Lifecycle labels: `queued`, `processing`, `completed`, `failed (timeout)`,
`failed (error)`, `failed (not found)`, `interrupted`.

A record also carries the resume bookkeeping, whether or not the task was ever interrupted:
`resumable` (a checkpoint with work left), `remaining` (URLs still to fetch),
`skippedAlreadyFetched` (URLs restored instead of re-requested), `resumeCount`, and `resumedFrom`
(the interruption a resumed run continued from).  `crawl status` prints them and emits them as
`--json` fields; `Interrupted` is **terminal** — `crawl status` never waits on it.

> **While a task runs, the progress counts only grow.**  `pagesFound` counts every page collected
> so far across *all* seeds and `pagesExpected` / `failedPages` never fall back once reported (a
> poller can treat a decrease as a bug); `pagesExpected` covers the seeds already finished, so on a
> multi-seed crawl it climbs as seeds settle.

`crawl list` flags:

| Flag | Type | Description |
|---|---|---|
| `--limit` | int | Show at most N tasks (latest first) |
| `--offset` | int | Skip the first N tasks |
| `--status` | string | Filter by status: `completed`, `running`, `failed`, `queued`, `interrupted`, `not found`.  `failed` also matches a run whose label is `partial failure (<ok> of <expected> pages ok)` — a crawl that finished with pages missing keeps `status = OK` on the backend (so a poller stops) while the CLI records the partial failure |
| `--since` | time | Show only tasks submitted since a relative time (e.g. `1h`, `30m`, `1d`) |
| `--clear` | bool | Remove all tracked tasks from the list |

### crawl resume

Continue an interrupted crawl from its checkpoint — after a backend restart, a crash, a `SIGKILL`,
a `crawl cancel`, or a task the server-side `--timeout` budget cut off.

```bash
browser4-cli crawl resume <task-id>
browser4-cli crawl resume <task-id> --bg
browser4-cli crawl resume <task-id> --retry-failed
browser4-cli crawl resume <task-id> --force
```

| Flag | Type | Description |
|---|---|---|
| `--force` / `-f` | bool | Resume even when the task's record says it completed successfully (a no-op when nothing is left; combine with `--retry-failed` to fetch the URLs it lost) |
| `--retry-failed` | bool | Re-submit the URLs that failed terminally before, instead of keeping them failed |
| `--bg` / `--background` | bool | Start the resume and return immediately; poll with `crawl status` / `crawl result` |
| `--verbose` | bool | Mark each row as fetched-in-this-run or restored-from-checkpoint |

The task keeps its id — `crawl resume` continues *that* task.  Already-fetched URLs are not
requested again (counted in `skippedAlreadyFetched` and restored into the result), in-flight URLs
are re-submitted with a fresh delivery-attempt budget, terminally failed URLs stay failed unless
`--retry-failed`, and the discovered frontier is followed so a `--depth >= 1` crawl continues where
it stopped instead of restarting at the seeds.  The result is the union of the runs —
`pagesFound + failedPages.size == pagesExpected` still holds and each row carries `run` /
`fetchedAt` / `restoredFromCheckpoint` — and a rejected resume (already completed, nothing left, no
checkpoint on disk) prints the reason and exits non-zero; a live worker is refused.

The **checkpoint** on disk holds the input contract, the URLs that succeeded, those that failed
terminally, those still in flight, and the links discovered but never queued.

Automatic resume at backend startup is **off by default** (`crawl.autoResume=false`): a restart is
not consent to keep hitting sites.  Checkpoints live under `~/.browser4/data/crawl/checkpoints/`,
survive the task store's LRU and TTL, and are discarded by `crawl clear --all`.  The full contract
— file layout, write cadence, crash safety, the end-to-end SIGKILL test — is in
[docs/crawl-checkpoint-resume.md](../../../docs/crawl-checkpoint-resume.md).

## See also

- [Crawl checkpoint & resume](../../../docs/crawl-checkpoint-resume.md) — checkpoint layout,
  write cadence, resume semantics, `crawl.autoResume`
- [X-SQL: DOM_LOAD_AND_SELECT](x-sql-dom-load-select.md) — the table-source function for X-SQL
- [Swarm reference](swarm.md) — parallel scraping and X-SQL across browser contexts
- [Multi-product extraction guide](../../../docs/multi-product-extraction.md) — choosing between
  crawl, swarm, and other approaches for bulk extraction
- [LoadOptions Guide](load-options-guide.md) — full LoadOptions reference
