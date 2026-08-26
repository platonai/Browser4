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

> **⚠️ htmlsnapshot capture requirements — which commands need a prior capture:**

| Command | Needs prior `htmlsnapshot` capture? | Notes |
|---------|-------------------------------------|-------|
| `htmlsnapshot` (capture) | — (this IS the capture) | Stores the page's initial HTML for later extraction |
| `htmlsnapshot get` / `get all` | **Yes** — requires stored snapshot | Extracts text/html/attr via CSS selectors from the stored HTML |
| `htmlsnapshot inspect` | **Yes** — requires stored snapshot | Iterates CSS selectors from the stored HTML; returns "No HTML snapshot found" if missing |
| `htmlsnapshot summary` | **Yes** — requires stored snapshot | Statistical summary of selectors on the stored page |
| `htmlsnapshot grep` | **Yes** — requires stored snapshot | Regex search over the stored HTML |
| `htmlsnapshot export` | **Yes** — requires stored snapshot | Exports the stored HTML to a file |
| `htmlsnapshot query` | **No** — fetches independently | Uses `DOM_LOAD_AND_SELECT(@url, ...)` which re-fetches the page, bypassing the stored snapshot entirely |

> **If you get "No HTML snapshot found" or a timeout:** either run `htmlsnapshot` first to capture, or use `htmlsnapshot query` with `@url` for independent fetching.

> **⚠️ Important:** `htmlsnapshot` captures the **current live DOM** at capture time. Content added or modified by JavaScript before the capture (form submission results, dynamic updates, SPA route changes) **is reflected** — but only if you run `htmlsnapshot` (capture) *after* the interaction. The stored snapshot becomes stale only if you do not re-capture after a navigation or interaction. For one-off live reads without a capture step, use `eval`.

## Decision Tree

```
Need to extract data from a page?
├─ Need to interact first (click, fill, scroll)?
│  → snapshot + refs, then re-capture htmlsnapshot after interacting, then extract
├─ Page has JS-updated content (after interaction, form submit, SPA)?
│  → eval --json for live DOM (use --stdin or --file on Windows)
├─ Static page, one field? → htmlsnapshot get text "<selector>"
├─ Static page, one field, ALL matches? → htmlsnapshot get all text "<selector>"
├─ Don't know the right CSS selector? → htmlsnapshot get text article  (auto-discovers content)
├─ Static page, multiple correlated fields (title+price+url per item)?
│  → htmlsnapshot query with X-SQL DOM_LOAD_AND_SELECT
├─ Dynamic/complex JS logic needed? → eval --json
├─ Natural language ("find the product price")? → extract (needs LLM key)
└─ High volume, many pages? → crawl or swarm with --sql
```

```
Need to process multiple pages?
├─ Single list page (products on one search results page)?
│  → htmlsnapshot query with DOM_LOAD_AND_SELECT
├─ Multiple known URLs (list in a file)? → crawl --seed-file urls.txt --depth 0 --sql @query.sql
├─ Crawl from a start URL (follow links)? → crawl <url> --out-link-selector "..." --depth N
├─ Need parallel execution (high throughput)? → swarm create → swarm query --seed-file ...
├─ Repeated monitoring (check every hour)? → loop -- eval "..." -i 3600
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

Requires JDK 17+ (auto-detected from `JAVA_HOME`, common paths, or `PATH`). Any other command is forwarded verbatim to `scent-miner.jar` (e.g. `webminer encode <dir>`).

> **Install:** `browser4-cli webminer install` (or the legacy launcher `.\webminer.ps1 install` from the [web-miner](https://github.com/platonai/web-miner) project). The JAR is also downloadable from [web-miner releases](https://github.com/platonai/web-miner/releases).

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

# 3. Run it
browser4-cli htmlsnapshot query "https://example.com/products" --sql @query.sql
```

**Discover selectors** before writing the query:

```bash
browser4-cli htmlsnapshot inspect                    # interactive: lists all elements with CSS classes/ids
browser4-cli htmlsnapshot summary                    # statistical summary of selectors on the page
browser4-cli htmlsnapshot get text ".price" --all    # quick test: does this selector match elements?
```

## Reference Map

- [SKILL.md §5](../SKILL.md#5-critical-warnings) — critical warnings (selectors go stale, shell quoting, stale snapshots)
- [htmlsnapshot.md](htmlsnapshot.md) — command reference for `get` / `query` / `grep` / `summary` / `inspect` / `export`
- [x-sql.md](x-sql.md) — X-SQL function reference
- [crawl.md](crawl.md) — bulk multi-page extraction
- [swarm.md](swarm.md) — parallel scraping
- [web-miner/SKILL.md](../../browser4-web-miner/SKILL.md) — WebMiner full reference
