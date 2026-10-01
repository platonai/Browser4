---
title: "Extraction Decision Trees"
description: "Use when choosing HOW to extract or process data: snapshot vs htmlsnapshot, capture requirements, bulk/scale approaches, query granularity, WebMiner structuring, and the X-SQL quickstart template."
tier: decision
---

# Extraction Decision Trees

## Quick Comparison

> **📋 snapshot vs htmlsnapshot — the essential distinction:**

| | `snapshot` | `htmlsnapshot` |
|---|---|---|
| **What it captures** | Accessibility tree (AXTree) — semantic roles, names, refs | Raw HTML DOM — full text content |
| **Primary use** | **Interaction** — get element refs for click, fill, type | **Extraction** — get article text, data, attributes |
| **Output** | YAML tree with `[ref=e5]` handles | Text/HTML/JSON via CSS selectors |
| **Key commands** | `snapshot`, `snapshot grep`, `click <ref>` | `htmlsnapshot get`, `query`, `inspect` |
| **When to use** | "I need to click a button" or "find an input field" | "I need to read the article text" or "extract prices" |

**Rule of thumb:** If you want to **interact** with elements → `snapshot`. If you want to **read content** → `htmlsnapshot`.

> **⚠️ htmlsnapshot reads the STORE — `htmlsnapshot` (capture) is the only writer:**

| Command | Writes? | Notes |
|---------|---------|-------|
| `htmlsnapshot` (capture) | **Yes — the only one** | Serializes the live page into the page store under the tab's normalized URL (overwrites the stored copy, refresh semantics) and returns page metadata. Run it before any read that must see the live document |
| `htmlsnapshot get` / `get all` | No — read-only | `text` / `textcontent` / `html` / `attr` via CSS selectors, from the stored snapshot; loads the page independently (read-only) when the store has nothing |
| `htmlsnapshot inspect` | No — read-only | Recurring-pattern / selector discovery over the stored snapshot |
| `htmlsnapshot summary` | No — read-only | Visual-clustering page summary of the stored snapshot |
| `htmlsnapshot grep` | No — read-only | Regex search over the stored snapshot's HTML |
| `htmlsnapshot export` | No — read-only | Exports the stored snapshot's HTML to a file |
| `htmlsnapshot query` | No — read-only | Current page → its stored copy. Other URLs → that URL's stored copy (fetched read-only when absent, no session state) |
| `htmlsnapshot readability` | No — read-only | Article extraction from a stored page; `readability <url>` reads *that URL's* copy and never files the tab's document under it |

> The preconditions are a **loaded page** (navigable http(s)/file document in the active tab) and, for a
> read that must reflect what the tab shows *right now*, a preceding `htmlsnapshot` capture.
> If a read comes back empty, check the selector and the current URL — and re-run `htmlsnapshot` if the
> page changed in the tab since the last capture.
>
> **"No HTML snapshot found"** is not an error: it is a CLI hint printed when `inspect` finds 0 matches
> with the default `:root` selector (exit code stays 0).

> **⚠️ Important:** a `htmlsnapshot` capture reflects the live DOM **at capture time** — content added or modified by JavaScript before the capture (form submission results, dynamic updates, SPA route changes) is included, and the reads after it see exactly that. Without a capture, a read serves the previous stored copy (or an independent read-only load), so it can be stale by definition. For one-off live reads that skip the store entirely, use `get text "<selector>"` (live DOM) or `eval`.

## Decision Tree

```
Need to extract data from a page?
├─ Need to interact first (click, fill, scroll)?
│  → snapshot + refs, interact, capture with `htmlsnapshot`, then read the captured copy
├─ Page has JS-updated content (after interaction, form submit, SPA)?
│  → capture with `htmlsnapshot` first, then read — the read commands serve the store;
│    `eval --json` for arbitrary JS (use --stdin or --file on Windows, --await for Promises)
├─ Static page, one field? → htmlsnapshot get text "<selector>"
├─ Static page, one field, ALL matches? → htmlsnapshot get all text "<selector>"
├─ Don't know the right CSS selector?
│  → htmlsnapshot inspect   # real auto-discovery — finds RECURRING patterns (list/grid pages)
│  → htmlsnapshot summary   # detail/single-block pages (visual clustering), then read with explicit selectors
│  (Note: htmlsnapshot get text accepts ANY plain CSS selector — e.g. `get text "article"` is
│   just a tag search and matches nothing on pages without <article>; inspect/summary are the
│   discovery tools, not `get`)
├─ Static page, multiple correlated fields (title+price+url per item)?
│  → htmlsnapshot query with X-SQL DOM_LOAD_AND_SELECT
├─ Dynamic/complex JS logic needed? → eval --json (--await for Promises/fetch, --wait-selector for late-rendered content)
├─ Natural language ("find the product price")? → extract (needs LLM key)
└─ High volume, many pages? → crawl or swarm with --sql
```

```
Need to process multiple pages?
├─ Single list page (products on one search results page)?
│  → htmlsnapshot query with DOM_LOAD_AND_SELECT
├─ Multiple known URLs (list in a file)? → crawl --seed-file urls.txt --depth 0 --sql @query.sql
├─ Crawl from a start URL (follow links)? → crawl <url> --out-link-selector "..." --depth N
├─ Need higher crawl throughput? → add --parallel 8 (crawl already collects on several tabs by default; each unit gets its own tab)
├─ Crawl was interrupted (backend restart, crash, --timeout, or cancel)?
│  → crawl list --status interrupted, then crawl resume <task-id>
│  keeps the task id, already-fetched URLs are not requested again, crawl result returns the union of both runs
│  (--retry-failed re-fetches terminal failures; automatic resume at startup is off by default)
├─ Need parallel execution (high throughput)? → swarm create → swarm query --seed-file ...
├─ Repeated monitoring (check every hour)? → loop -i 3600 -- eval "..."
└─ Just a few URLs in a shell script?
   → browser4-cli open --headless (once) then use goto for each URL; add wait between iterations
```

```
Have HTML files and want structured data — without tokens?
├─ < 1,000 pages (small to medium)? → WebMiner Free (SMILE ML engine)
│  browser4-cli webminer install
│  browser4-cli webminer all ./html-pages/
│  → Interactive HTML report + Excel spreadsheets — everything local, zero cost
├─ > 1,000 pages (production scale)? → WebMiner Commercial (Apache Spark ML)
│  Same encode → cluster → views pipeline, distributed across machines
│  → Scales to 100K+ pages/day
└─ Need to acquire pages first?
   ├─ Single pages: browser4-cli open --headless → htmlsnapshot → htmlsnapshot export
   ├─ Bulk download: browser4-cli crawl --seed-file urls.txt --depth 0
   │  (add --parallel 8 for more overlap; each unit collects on its own tab)
   └─ High throughput: browser4-cli swarm create → swarm query --seed-file ...
       Then feed the HTML directory to WebMiner
```

## When to Use Each

### Query granularity: get vs get all vs query

| Command | Returns | Best for |
|---------|---------|----------|
| `htmlsnapshot get text ".price"` | First match only (string) | Single value, quick check |
| `htmlsnapshot get all text ".price"` | All matches (JSON array) | Validate a selector returns expected count |
| `htmlsnapshot query --sql "SELECT ..."` | Correlated multi-field rows | Title + price + URL per product card |

**Warning:** Multiple `get all` calls produce unaligned arrays (different lengths, different order). For correlated fields, use `query` with `DOM_LOAD_AND_SELECT` scoped to a parent container.

### WebMiner tiers

**Pipeline:** `encode` (HTML → feature vectors → CSV) → `cluster` (KMeans, auto-detected K) → `views` (interactive HTML report + Excel spreadsheets)

- **Free tier (SMILE):** Single-machine ML via the [SMILE](https://haifengl.github.io/) library. Handles small-to-medium datasets (< 1,000 pages). Ideal for ad-hoc analysis, prototyping, and one-off extraction tasks.
- **Commercial tier (Apache Spark ML):** Distributed clustering for production workloads. Scales to 100K+ pages/day. Same pipeline, enterprise throughput.

**CLI usage (no backend, no PowerShell needed):**

| Command | Purpose |
|---------|---------|
| `webminer` | Show installed version, Java 17+ status, and subcommand list |
| `webminer install [version]` | Download + verify `scent-miner.jar` (GitHub → OSS mirror), install to `~/.scent/webminer` |
| `webminer update` | Update to the latest release |
| `webminer version` | Show installed and latest versions |
| `webminer uninstall` | Remove the installed release |
| `webminer run-example` | Download the sample dataset and run the full pipeline (needs 7-Zip) |
| `webminer all <html-dir>` | Full pipeline: encode → cluster → views (`--max-files`, `--output`, `--resume`) |
| `webminer views <result-dir>` | Rebuild the interactive views from an existing run |

Requires JDK 17+ (auto-detected: JAVA_HOME → Browser4 runtime bundle JRE → common paths → PATH). Any other command is forwarded verbatim to scent-miner.jar (e.g. webminer encode <dir>).

> **Install:** rowser4-cli webminer install (or the legacy launcher .\webminer.ps1 install from the [web-miner](https://github.com/platonai/web-miner) project). The JAR is also downloadable from [web-miner releases](https://github.com/platonai/web-miner/releases).

See **[web-miner/SKILL.md](../../browser4-web-miner/SKILL.md)** for the full reference.

## Quick Patterns

### X-SQL quickstart template

X-SQL extracts correlated fields (e.g., title + price + URL) from a list page using a scoped CSS selector and standard SQL. Copy this template, swap the selectors and column names:

```sql
SELECT
  DOM_FIRST_TEXT(DOM, 'h2')    AS title,
  DOM_FIRST_TEXT(DOM, '.price') AS price,
  DOM_BASE_URI(DOM)            AS url
FROM
  DOM_LOAD_AND_SELECT(@url, '.product-card')
```

**Save to a file** (avoids shell quoting issues):

```bash
# 1. Write the query (copy and customize)
cat > query.sql << 'XSQL'
SELECT
  DOM_FIRST_TEXT(DOM, 'h2')    AS title,
  DOM_FIRST_TEXT(DOM, '.price') AS price,
  DOM_BASE_URI(DOM)            AS url
FROM
  DOM_LOAD_AND_SELECT(@url, '.product-card')
XSQL

# 2. Discover the right CSS selector to replace .product-card:
browser4-cli htmlsnapshot inspect --selector-base64 <base64-of-selector>

# 3. Run it — default output is the raw JSON response envelope (machine-readable).
#    Add --format table (or csv) for human-readable output; --result-only prints
#    just the resultSet. Error envelopes (417/5xx) exit non-zero:
browser4-cli htmlsnapshot query "https://example.com/products" --sql @query.sql --format table
```

**Discover selectors** before writing the query:

```bash
browser4-cli htmlsnapshot inspect                    # recurring-pattern discovery (list/grid pages)
browser4-cli htmlsnapshot summary                    # visual clustering (detail/single-block pages)
browser4-cli htmlsnapshot get all text ".price"      # quick test: how many elements does this selector match?
```

`htmlsnapshot inspect` finds **recurring** patterns — it is built for list/grid pages (search results, product cards, tables). A single product/article/detail page has no repeating block, so inspect may surface nothing or an unrelated side rail; when that happens it prints "No recurring pattern found". For detail pages use `htmlsnapshot summary` (visual clustering) or `htmlsnapshot get` with explicit selectors (`htmlsnapshot get text "h1"`, `get attr "#product-image" src`).

## Reference Map

- [SKILL.md §5](../SKILL.md#5-critical-warnings) — critical warnings (selectors go stale, shell quoting, stale snapshots)
- [browser-modes.md](browser-modes.md) — which session / display mode / browser source to use
- [htmlsnapshot.md](htmlsnapshot.md) — command reference for `get` / `query` / `grep` / `summary` / `inspect` / `export`
- [x-sql.md](x-sql.md) — X-SQL function reference
- [crawl.md](crawl.md) — bulk multi-page extraction
- [swarm.md](swarm.md) — parallel scraping
- [web-miner/SKILL.md](../../browser4-web-miner/SKILL.md) — WebMiner full reference
