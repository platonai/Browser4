---
title: "Crawl Command Reference"
description: "Reference for the crawl command. Recursive website crawling from a URL or seed file, with optional X-SQL data extraction and multi-format output."
tier: procedure
---

# Crawl Command Reference

Recursive website crawling — start from a URL or seed file, follow links up to
a configurable depth, and optionally extract structured data from each page
with X-SQL.

## Quick start

```bash
# Link discovery from a single URL
browser4-cli crawl "https://example.com" --out-link-selector "a[href]"

# Bulk fetch from a seed file (no link discovery)
browser4-cli crawl --seed-file urls.txt --depth 0

# Bulk fetch + X-SQL extraction to CSV
browser4-cli crawl --seed-file urls.txt --sql "@extract.sql" --format csv -o results.csv
```

> **Note:** `--out-link-selector` is required for link discovery; without it only seed URLs
> are processed regardless of depth.  For depth 0 (bulk fetch), no selector is needed.

## When to Use

Use **crawl** for sequential multi-page workflows with built-in link discovery, seed-file bulk processing, and X-SQL extraction to structured output (CSV/JSON/table). Prefer **swarm** for parallel high-throughput extraction. Prefer **loop** for repeated monitoring at intervals. Prefer **htmlsnapshot query** for extracting from a single page.

## How It Works

Crawl loads seed URLs, optionally follows links up to a configurable depth, deduplicates visited pages, and optionally runs an X-SQL query against each page. Results are aggregated and formatted as table, CSV, or JSON. Use `--background` for async execution.

## Common patterns

### Bulk product detail extraction

```bash
# Extract product URLs from search results (via eval or X-SQL), write to urls.txt
browser4-cli crawl --seed-file urls.txt --depth 0 --refresh \
  --sql "@extract.sql" --format csv -o products.csv
```

`extract.sql`:
```sql
SELECT DOM_BASE_URI(dom) AS url,
       DOM_FIRST_TEXT(dom, '#productTitle') AS title,
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
```

### LoadOptions passthrough and quality requirements (`--args` / `-a`)

Any [LoadOptions](load-options-guide.md) field can be passed through `-a`:

```bash
browser4-cli crawl "https://example.com" -ol "a[href]" --refresh -a "-requireSize 100000 -scrollCount 5"
browser4-cli crawl "https://example.com" -ol "a[href]" -a "-nMaxRetry 5 -lazyFlush -interactLevel FAST"
```

### X-SQL from stdin or a file

```bash
browser4-cli crawl --seed-file urls.txt --depth 0 --sql-stdin --format table < query.sql
browser4-cli crawl --seed-file urls.txt --depth 0 --sql "@extract.sql" --format csv -o out.csv
```

## Modes

### Link discovery mode (depth >= 1)

Loads each seed URL; extracts links matching `--out-link-selector` (a CSS selector);
optionally filters them with `--out-link-pattern` (a regex); deduplicates them and limits
them to `--top-links`; loads each linked page; and, if `--depth` > 1, repeats that for the
loaded pages (skipping visited URLs).  Returns human-readable text (or JSON with `--json`).

### Bulk fetch and X-SQL extraction (depth 0 / `--sql`)

Loads each URL directly without link discovery — for a list of known detail pages (product
pages, articles), and for `--sql` extraction when you already have the URLs.  The query runs
against each crawled page, with `@url` replaced by the page URL server-side; results are
aggregated across all pages and formatted by `--format`.  X-SQL function names are
case-insensitive (`DOM_FIRST_TEXT` = `dom_first_text`).

## Flags

### Core flags

| Flag | Short | Type | Default | Description |
|---|---|---|---|---|
| `url` (positional) | | string | — | Starting URL. Omit when using `--seed-file` |
| `--seed-file` | | string | — | File with URLs to crawl, one per line. Lines starting with `#` are comments |
| `--depth` | `-d` | int | `1` | 0 = fetch only (no links); 1+ = follow links to that depth |
| `--parallel` | | int | `4` | How many units (pages/tabs) to collect at the same time. `1` = strictly sequential |
| `--timeout` | | duration | `10m` | How long the crawl may run before the server cancels it: seconds (`900`) or `30s`, `10m`, `1h`. Max `1h` |
| `--background` | `-bg` | bool | — | Submit crawl and return immediately; use `crawl list` to track |

### Task budget (`--timeout`)

A crawl runs under a **task budget**: one clock for the whole task, from which every round
derives its own timeout.  `--timeout` sets that budget for this crawl only — omit it and the
server default of 10 minutes applies.  The floor is `1s` (below it the CLI exits non-zero — a
crawl with no time at all cannot start a single round) and `1h` is the ceiling, because the
budget buys browser time.

The budget is what makes a large crawl *end* rather than be *killed*: a seed whose round
cannot fit in what is left is refused **before** it is submitted and reported as a lost page
(`reason = the crawl ran out of its time budget before this URL was submitted`, seed status
`skipped`), so `pagesFound + failedPages.size == pagesExpected` still holds on a truncated
crawl.  The record reports the budget it ran under (`taskTimeoutMillis`, including a
server-side clamp), never the raw request, and — the budget being a whole-task limit — a
very large seed list needs a larger `--timeout` or several crawls; the losses tell you
exactly which URLs never started.

```bash
# Beyond the 10m default; and a short pre-release check that reports what was left
browser4-cli crawl --seed-file urls.txt -d 0 --timeout 30m --refresh
browser4-cli crawl --seed-file smoke.txt -d 1 --timeout 2m
```

### Parallelism (`--parallel`)

A crawl is a set of **independent units** — one per seed URL — collected at the same time by
default, each on its own browser tab leased from the driver pool; `--parallel <n>` bounds how
many may be in flight at once (omitted = the server default of 4, `1` = strictly sequential,
`2`–`32` = up to `<n>` units concurrently).

* **Each unit needs its own tab.** The driver pool (`browser.context.number` ×
  `browser.max.active.tabs`, 2 × 8 by default) is the hard ceiling: asking for more than it
  can hand out yields a lower *observed* peak, which the completion report shows.  The peak
  is measured, not claimed — a peak of `1` on a multi-unit crawl means the collection was
  serial, and that is called out explicitly.
* **Values are validated before submitting.** `0` and non-numeric values exit non-zero with
  a hint (`--parallel 1` for the sequential form); values above 32 are rejected by the
  server (HTTP 400).  At `-d 1+` the budget bounds *seed rounds*, not pages: each round
  discovers its links and the pages themselves are fetched from the shared tab pool, so two
  rounds are free to overlap even when a single round has few links.

```bash
browser4-cli crawl --seed-file urls.txt -d 0 --parallel 8 --refresh   # 12 seed URLs, at most 8 at a time
browser4-cli crawl --seed-file urls.txt -d 0 --parallel 1 --refresh   # strictly sequential (a rate-limiting site)
```

### X-SQL and output flags

| Flag | Short | Type | Default | Description |
|---|---|---|---|---|
| `--sql` | | string | — | X-SQL query. Use `@url` as page URL placeholder. Prefix with `@` to read from file |
| `--sql-stdin` | | bool | — | Read query from stdin (avoids shell quoting issues) |
| `--sql-base64` | | bool | — | Base64-decode the query value before execution |
| `--format` | | string | `table` | Output format: `json`, `csv`, or `table` |
| `--output` | `-o` | string | — | Write results to file instead of stdout |
| `--verbose` | | bool | — | Show per-URL processing status in the crawl results (also surfaces per-page X-SQL diagnostics instead of only the aggregate error count) |

> **Note — what `--output` writes:** `-o` writes the **crawl result** — the
> aggregated X-SQL rows when `--sql` is given, or the crawled-page listing
> (`depth=N | <url> | <title>`) when it is not.  It does **not** write the raw
> HTML of each page.  To stage pages as HTML files on disk (e.g. for WebMiner),
> use `htmlsnapshot export`, or `webdb export "<url1,url2,…>" <dir>` to pull the
> already-fetched pages out of the page store.

### Link discovery flags

| Flag | Short | Type | Default | Description |
|---|---|---|---|---|
| `--out-link-selector` | `-ol` | string | — | CSS selector to extract links from each page |
| `--out-link-pattern` | `-olp` | regex | `.+` | Regex to filter extracted links |
| `--top-links` | `-tl` | int | `20` | Max **distinct** pages one page may contribute (repeats are removed first) |

> **Git Bash / MSYS2 caveat — leading-`/` pattern values:** argument values that start with
> `/` (e.g. `-olp "/product/"`) are converted into Windows paths
> (`C:/Program Files/Git/product/`) before the CLI sees them — the pattern then filters out
> every link and the crawl silently reports only the seed page.  Use `./b4w.sh` from Git Bash
> (it exports `MSYS2_ARG_CONV_EXCL='*'`, disabling the conversion), run from PowerShell, or
> use a value that does not start with `/` (e.g. `-olp "product/"`).
> See [shell-quoting.md](shell-quoting.md).

### LoadOptions flags

| Flag | Short | Type | Description |
|---|---|---|---|
| `--args` | `-a` | string | Raw LoadOptions passthrough (see [LoadOptions Guide](load-options-guide.md)) |
| `--refresh` | | bool | Force fresh fetch (ignore cache) |
| `--parse` | | bool | Parse pages after fetch |
| `--expires` | | string | Cache TTL: `1d`, `1h`, `30m`, etc. Invalid values are rejected by the CLI (non-zero exit) |
| `--priority` | `-p` | int | Queue priority (non-negative integer; lower = higher priority) |
| `--page-load-timeout` | | string | Max wait per page load: seconds number (`30`) or duration (`30s`, `1m`) |
| `--ignore-url-query` | | bool | Strip query params from **discovered out-link** hrefs (no effect on seed URLs in depth-0 bulk fetch) |
| `--no-norm` | | bool | Disable URL normalization of **discovered out-link** hrefs (no effect on seed URLs in depth-0 bulk fetch) |
| `--readonly` | | bool | Non-destructive mode: loads may be served from the page store and are never written back. Wins over `--refresh` (see below) |

## Output formats

### X-SQL result formats (`--format`)

| `--format` | Output |
|---|---|
| `table` (default) | Aligned columns with a header and separator |
| `csv` | Header row; fields containing commas, quotes or newlines are quoted |
| `json` | Pretty-printed JSON array of row objects |

### Page listing (no --sql)

Without X-SQL, the default output lists the crawled pages:

```
Crawl task submitted: 550e8400-e29b-41d4-a716-446655440000
Crawling... 3 pages found (18s elapsed)

Crawl completed. 3 pages found.
  depth=0 | https://example.com/page1 | Page 1 Title
  depth=0 | https://example.com/page2 | Page 2 Title
```

> **Timing — slow progress is normal:** each page takes **several seconds** (roughly 5–7 s)
> through the backend parse/load pipeline, even for local pages, and progress lines update
> only when a page completes — a small local crawl of 3–10 pages can legitimately take
> 20–60 seconds, so repeated `Crawling...` lines are progress, not a hang.

## Testing locally with MockSite

The mock e-commerce site (`./bin/test.ps1 mock-site`) provides predictable product pages for
testing crawl extraction without hitting live websites.  Don't guess product IDs — MockSite
exposes a sitemap built exactly for URL discovery, and the bare path
`http://localhost:18080/ec/dp/` (trailing slash) 404s because product pages always require
an ID (`/ec/dp/<product-id>`):

```bash
curl -s http://localhost:18080/ec/sitemap.xml        # all 101 product URLs, 20 categories
curl -s http://localhost:18080/ec/sitemap.xml \      # build a seed file (first 8 products)
  | grep -o 'http://localhost:18080/ec/dp/[A-Z0-9]*' | head -8 > seed-urls.txt
browser4-cli crawl --seed-file seed-urls.txt --depth 0 --refresh \
  --sql "@extract.sql" --format table                # extract.sql: DOM_LOAD_AND_SELECT(@url, 'body')
browser4-cli goto "http://localhost:18080/ec/dp/B0E000001"
browser4-cli htmlsnapshot inspect                    # element patterns, incl. singleton IDs
```

Detail pages (`/ec/dp/…`) use ID selectors, listing pages (`/ec/b?node=…`) class selectors:
`#productTitle`, `#product-price`, `#product-features`, `.breadcrumbs` vs `.product-card`,
`.product-title`, `.product-price`.

> **Browser vs. raw HTML:** MockSite serves a JavaScript-hydrated page variant to browsers
> (the crawl fetch pipeline), which can differ from the static HTML a plain `curl` receives
> — e.g. category/navigation anchors arrive as `href="#"` and the rendered product list may
> be a subset.  When debugging link-discovery counts, verify the *browser* DOM with `eval` or
> `htmlsnapshot inspect` rather than assuming `curl` output matches what the crawler sees.

> **Tip:** When selectors don't match, use `htmlsnapshot grep` with `--selector` to verify
> elements exist, or `htmlsnapshot inspect` to discover available selectors.  MockSite uses
> IDs (`#productTitle`), not classes (`.title`).

## URL deduplication

- Visited URLs are normalized for dedup: lowercase, trailing slash removed, query string and
  URL fragment always stripped; the same URL is never visited twice in a crawl session.
- `--top-links` is a budget for **pages**, not anchors: the links a page offers are
  deduplicated *before* the budget is applied, so a product linked twice (image and title) or
  a page offered under two query strings costs one slot.
- A fragment is never part of a queued or reported URL: `product/1.html#specs` is queued —
  and reported — as `product/1.html`.  When one page is offered under several spellings, the
  crawl queues the first one it saw (document order) and reports that spelling, and
  fragment-only anchors (`href="#"`, `href="#section"`) are skipped during link extraction
  (they can never navigate to a new document) and are not counted as discovered out-links.
- Use `--ignore-url-query` to strip query parameters from discovered link hrefs before they
  are queued, so the URL a result row reports is the URL that was fetched; `--no-norm`
  disables LoadOptions-level normalization (not the internal dedup normalization).

> **Scope note:** `--ignore-url-query` and `--no-norm` only affect links *discovered* during
> depth ≥ 1 link discovery.  Seed URLs in a depth-0 bulk fetch are always fetched and
> reported verbatim, so these flags produce no observable change there.

## `--readonly` and the X-SQL second read

A crawl normally forces a fresh fetch (`-refresh`) on every page it loads.  With `--readonly`
it does not: **`--readonly` wins over `--refresh`** and the refresh is dropped rather than
added, because the X-SQL execution engine reads a page **twice**: first the crawl's own load
freezes the page into the local page cache under the URL the statement will resolve; then the
`load_and_select()` / `load()` UDF inside X-SQL resolves the URL in the statement's FROM
clause, and the statement is *sealed* with `-readonly` and with every fetch-forcing option
erased, so that read serves the frozen copy — no network round trip, no page-store write, no
cache write while the query runs.  The two flags cannot be combined, because `-refresh`
expands to `-ignoreFailure -i 0s`: it makes *every* local copy look expired, so the read-only
shortcut is missed and the UDF re-fetches the page while the query is still executing.

```bash
# Read the pages the store already holds; never write them back.
browser4-cli crawl --seed-file urls.txt --depth 0 --readonly
```

What to expect:

- A page **in the page store** is served from there: the result rows carry the store markers
  and the completion note reports how old the served content is
  (`readonly: N/M page(s) served from the page store (stored content up to … old)`).
- A page that is **not** local is fetched (read-only is a cache-hit *preference*, not a fetch
  prohibition) but not written back, and the note says `readonly: verified fresh — all N
  page(s) fetched from the live site`.
- Link discovery over a stale stored page can legitimately find no out-links; the
  empty-out-links diagnostic then explains it.  Add `--refresh` **instead of** `--readonly`
  for a crawl that must see the live site.

## Seed files

Plain text, one URL per line.  Blank lines and lines starting with `#` are ignored, and when
both a positional `url` and `--seed-file` are given, the URL is prepended to the list.

```text
# Laser-Engraved Crystal products
https://www.amazon.com/dp/B0C17W3Q9B
```

## Timeout

- CLI-side default: 600s (`BROWSER4_CLI_CRAWL_TIMEOUT_SECS`).  When that wait expires the
  crawl keeps running server-side — poll it with `crawl status` / `crawl result`.
- Backend task limit: **10 minutes per crawl task** by default (raise it per crawl with
  `--timeout`, up to 1h), however many seeds or levels it has.  A task that reaches it ends
  `TIMEOUT` and still reports the pages it collected plus every seed it never settled.
- A round (one seed URL at depth >= 1) gets the **smaller** of `5 min × depth` (capped at
  30 min) and what the task has left minus a 30s reporting margin — so it always times out on
  its own terms, with its outstanding URLs reported as lost, instead of being killed by the
  task limit (which used to turn a deep crawl into "fewer pages, no losses reported").
- A seed the remaining budget cannot carry (less than ~45s left) is **not submitted at all**:
  it is reported as a lost page and its `seedStatuses` entry is `skipped`, rather than being
  started and killed with no accounting.

## Error handling

| Situation | Behavior |
|---|---|
| No URLs provided | Exits with "No URLs provided. Specify a URL argument or --seed-file." |
| Empty seed file | Exits with "No URLs provided." after parsing |
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

**Partial failures exit 6.** A polled crawl that lost pages (`failedPages`) or delivered pages
with no usable content (an `extractionError`, or a 0-byte `contentLength`) is reported as
completed *with errors*: it prints `Summary: ok: <n>, failed: <m>`, records `pages_failed` in
its JSON, stores the local task as `partial failure (<ok> of <pages> pages ok)` and exits
**6** (`PartialFailure`).  Exit 0 stays reserved for a crawl where nothing failed; in `--json`
mode the failure path emits the error envelope, which carries no accumulated fields, so the
counts arrive only inside `error.message` — `pages_failed` is present on the success path,
where it is `0`.

## Rate Limiting & Polite Scraping

Crawl includes built-in rate limiting between page loads. For manual batch operations: add
`wait 1000-3000` (1-3 seconds) between rapid navigations on the same site; use `eval` or
`htmlsnapshot get all` to batch-extract from a single page load instead of navigating to each
detail page; prefer conservative `--depth` and `--page-load-timeout` for automated multi-page
traversal; for `swarm`, control parallelism with `--max-browser-contexts` and
`--max-open-tabs`.  Amazon and similar sites may show CAPTCHAs under aggressive automated
access — longer delays reduce risk.

## Subcommands

A crawl submitted with `--background` returns a task ID immediately.  These subcommands
manage and monitor the task afterwards.

| Subcommand | What it does |
|---|---|
| `crawl status <task-id>` | One-line summary plus the raw record: `Created` / `Processing` / `OK`, pages found so far, and any error information |
| `crawl result <task-id>` | The task's current record — page listing (without `--sql`) or extracted data (with `--sql`).  A task still `Processing` returns its partial record with `status: Processing` and the CLI hints that it is not yet terminal, so `result` and `status` both work as a poll |
| `crawl cancel <task-id>` | Cancel a running or queued task; it transitions to TIMEOUT and stays visible in `crawl list` until cleared or expired by TTL.  `{"cancelled": false}` means no running worker was found — the record is still queryable.  A cancellation does **not** throw the work away: the checkpoint is kept, the record reports what is left, and `crawl resume <task-id>` continues it |
| `crawl resume <task-id>` | Continue an interrupted task from its checkpoint — see [`crawl resume`](#crawl-resume) below |
| `crawl clear` | Remove completed, cancelled and failed tasks from the store; running tasks are not affected.  A checkpoint that still has work left is **kept**, so a cleared timed-out task can still be resumed by id, and interrupted tasks are not touched at all (they are not finished tasks); `crawl clear --all` is the explicit way to discard resumable state — after it, an interrupted task can only be submitted again |
| `crawl list` | List all tracked crawl tasks across all sessions |

```bash
browser4-cli crawl status <task-id>
browser4-cli crawl result <task-id>
browser4-cli crawl cancel <task-id>
browser4-cli crawl resume <task-id> --bg
browser4-cli crawl clear --all
browser4-cli crawl list --status interrupted
```

The wire values are `ResourceStatus` display text — `Created`, `Processing`, `OK`, `Request
Timeout`, `Internal Server Error`, `Not Found`, plus `Interrupted` (the worker died with the
backend — see [Resume after an interruption](#resume-after-an-interruption)); one vocabulary,
defined by `CrawlStatus` on the backend.  Lifecycle labels: `queued`, `processing`,
`completed`, `failed (timeout)`, `failed (error)`, `failed (not found)`, `interrupted`.

A record also carries the resume bookkeeping, whether or not the task was ever interrupted:
`resumable` (is there a checkpoint with work left), `remaining` (URLs still to fetch),
`skippedAlreadyFetched` (URLs restored instead of re-requested), `resumeCount`, and
`resumedFrom` (the interruption a resumed run continued from).  `crawl status` prints them and
emits them as JSON fields; `Interrupted` is **terminal** — `crawl status` never waits on it.

> **While a task runs, the progress counts only grow.**  `pagesFound` counts every page
> collected so far across *all* seeds and `pagesExpected` / `failedPages` never fall back
> once reported (a poller can treat a decrease as a bug); `pagesExpected` covers the seeds
> that have already finished, so on a multi-seed crawl it climbs as seeds settle.

`crawl list` flags:

| Flag | Type | Description |
|---|---|---|
| `--limit` | int | Show at most N tasks (latest first) |
| `--offset` | int | Skip the first N tasks |
| `--status` | string | Filter by status: `completed`, `running`, `failed`, `queued`, `interrupted`, `not found` |
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
requested again (counted in `skippedAlreadyFetched` and restored into the result), in-flight
URLs are re-submitted with a fresh delivery-attempt budget, terminally failed URLs stay failed
unless `--retry-failed`, and the discovered frontier is followed so a `--depth >= 1` crawl
continues where it stopped instead of restarting at the seeds.  The result is the union of the
runs — `pagesFound + failedPages.size == pagesExpected` still holds, each row carries `run` /
`fetchedAt` / `restoredFromCheckpoint` — and a rejected resume (already completed, nothing
left, no checkpoint on disk) prints the reason and exits non-zero; a live worker is refused.

## Resume after an interruption

A crawl interrupted by a restart, a crash or the server-side `--timeout` budget is reported
as `Interrupted` (never as a phantom `Processing`) and keeps a **checkpoint** on disk: its
input contract, the URLs that succeeded, the URLs that failed terminally, the URLs that were
in flight and the links it had discovered but never queued.  `crawl resume <task-id>`
continues it:

```bash
browser4-cli crawl list --status interrupted
browser4-cli crawl resume 3f1c…             # continues and polls to completion
browser4-cli crawl resume 3f1c… --bg        # fire and forget
browser4-cli crawl result 3f1c…             # union of both runs, per-row provenance
```

What a resume promises:

- **No repeat request for a URL that already succeeded.** It is restored from the
  checkpoint and reported in `skippedAlreadyFetched`.
- **Terminal failures stay failed** unless `--retry-failed`.
- **The frontier is followed**, so `--depth >= 1` continues its breadth-first walk.
- **The accounting survives the merge**: `pagesFound + failedPages.size == pagesExpected`.
- **A rejected resume says why** (already completed → use `--force`; nothing left; no
  checkpoint on disk) and exits non-zero.

Automatic resume at backend startup is **off by default** (`crawl.autoResume=false`): a
restart is not consent to keep hitting sites.  Checkpoints live under
`~/.browser4/data/crawl/checkpoints/`, survive the task store's LRU and TTL, and are
discarded by `crawl clear --all`.  The full contract — file layout, write cadence, crash
safety, and the end-to-end SIGKILL test — is in
[docs/crawl-checkpoint-resume.md](../../../docs/crawl-checkpoint-resume.md).

## See also

- [Crawl checkpoint & resume](../../../docs/crawl-checkpoint-resume.md) — the backend
  contract: checkpoint file layout, write cadence, resume semantics, `crawl.autoResume`
- [X-SQL: DOM_LOAD_AND_SELECT](x-sql-dom-load-select.md) — the table-source
  function for loading pages in X-SQL queries
- [Swarm reference](swarm.md) — parallel scraping and X-SQL extraction across
  multiple browser contexts
- [Multi-product extraction guide](../../../docs/multi-product-extraction.md) —
  choosing between crawl, swarm, and other approaches for bulk data extraction
- [LoadOptions Guide](load-options-guide.md) — full LoadOptions reference
