---
name: browser4-cli
title: "Browser4 CLI — AI Agent Skill"
description: "Automates browser interactions for web testing, form filling, screenshots, and data extraction. Use when the user needs to navigate websites, interact with web pages, fill forms, take screenshots, test web applications, or extract information from web pages."
allowed-tools: Bash(browser4-cli:*)
tier: decision
---

# Browser Automation with browser4-cli

Browser automation CLI for AI agents — Chrome/Chromium via CDP with accessibility-tree snapshots.

## 1. Core Loop

Every browser4-cli session follows this pattern:

```
1. NAVIGATE    browser4-cli goto <url>              # auto-opens/reconnects session
2. SNAPSHOT    browser4-cli snapshot -v 0            # capture accessibility tree (viewport 0 = top)
3. INTERACT    browser4-cli click <ref>              # use refs from the snapshot
              browser4-cli fill <ref> <value>
              browser4-cli press Enter
4. RE-SNAPSHOT browser4-cli snapshot -v 0 --auto-diff # verify what changed (diff vs previous)
5. EXTRACT     browser4-cli htmlsnapshot get ...      # or eval, or X-SQL (see §4)
```

### Copy-Paste Template

```bash
browser4-cli goto "https://example.com"
browser4-cli snapshot -v 0          # read the page; note refs
browser4-cli fill <ref> "<value>"   # interact
browser4-cli press Enter
browser4-cli wait --load networkidle
browser4-cli snapshot -v 0 --auto-diff  # verify what changed
browser4-cli htmlsnapshot get text "<css-selector>" --all
```

For quick inline viewing without opening a file, add `--stdout`:
```bash
browser4-cli snapshot -v 0 --stdout   # print snapshot to stdout instead of file
```

## 2. Key Concepts

### Element Refs

After commands that modify browser state, browser4-cli saves an **accessibility-tree snapshot** — a YAML file showing the page structure:

```yaml
- generic [ref=e7]:
  - link "News" [ref=e191]:
    - /url: https://example.com/news
  - textbox "Search query" [ref=e35]
  - button "Search" [ref=e25]
```

Each interactive element has a **ref** (`e5`, `e12`) — these are Chrome DevTools Protocol backend node IDs. Use them to target elements in `click`, `fill`, `type`, `get attr`, etc.

### Ref Lifecycle

Refs are **ephemeral** — they become invalid after commands that change the DOM tree structure:

- **Safe (refs survive):** `fill`, `type`, `press`, `check`, `uncheck`, `select` — these only modify element *properties* (value, checked, selectedIndex) without adding/removing DOM nodes.
- **Unsafe (re-snapshot after):** `click` on navigation links or buttons that trigger page updates, `goto`, `reload`, tab switches — these restructure the DOM or load new pages.
- **Gray area:** `click` on checkboxes/radio buttons and some dropdown toggles may or may not mutate the DOM. When in doubt, capture a new snapshot after clicking.

**In practice, you can fill an entire form from a single snapshot.** Only re-snapshot if a ref unexpectedly fails — the CLI will surface a clear error so you know when it's needed.

### Output Modes

- **Default** — human-readable output on stdout with tips on stderr.
- **`--json`** — single-line JSON envelope on stdout only. All tips, hints, warnings, and human-readable text are suppressed (clean machine output).
- **`--quiet` / `-q`** — suppress all normal output; only errors appear on stderr.

### Sessions

Named sessions isolate browser state (cookies, localStorage, tabs). Use `-s <name>` to target a named session. `goto` auto-opens/reconnects — you rarely need to manage sessions manually.

The `list` command displays a "Next open" column showing what happens when `goto` or `open` targets a named session that already exists:
- **Reuse** — reconnects to the existing browser window (session is active on the backend).
- **Refresh** — opens a fresh window (session is stale or missing).

## 3. Command Map

| Command family | Purpose | When to use | Full reference |
|---------------|---------|-------------|----------------|
| `goto`, `open`, `close`, `reload` | Navigation & session management | Every session starts here | — |
| `snapshot` | Capture accessibility tree with refs | Before/after interactions | [htmlsnapshot.md](references/htmlsnapshot.md) |
| `snapshot grep` | Search snapshot content with regex | Find elements by text or pattern | — |
| `click`, `fill`, `type`, `press`, `select`, `check`, `drag` | Page interaction | Form filling, button clicks, navigation | — |
| `htmlsnapshot get`, `get all` | Extract text/html/attr via CSS selectors | Single-field data extraction | [htmlsnapshot.md](references/htmlsnapshot.md) |
| `htmlsnapshot query` | X-SQL queries for structured extraction | Multi-field, filtered, sorted data | [x-sql.md](references/x-sql.md) |
| `eval` | Execute JavaScript in the page | Live DOM access, complex transforms | — |
| `extract`, `summarize`, `agent run` | AI-powered extraction | Natural language extraction (needs LLM key) | [agent.md](references/agent.md) |
| `crawl` | Recursive crawling + bulk extraction | Multi-page traversal, seed-file processing | [crawl.md](references/crawl.md) |
| `swarm` | Parallel scraping across browser contexts | High-throughput extraction | [swarm.md](references/swarm.md) |
| `loop` | Repeated task execution with persistence | Monitoring, scheduled checks | [loop.md](references/loop.md) |
| `state-save`, `state-load`, `cookie-*`, `*-storage-*` | Browser storage management | Auth state reuse, cookie manipulation | [storage-state.md](references/storage-state.md) |
| `attach` | Connect to existing Chrome/Edge via CDP | Debug live browser, reuse auth | [attach.md](references/attach.md) |
| `screenshot`, `scroll`, `wait`, `resize`, `tab-*` | Visual capture & viewport control | Screenshots, tab management | — |

## 4. Decision Trees

### 4a. Choosing an Extraction Method

```
Need to extract data from a page?
├─ Need to interact first (click, fill, scroll)? → snapshot + refs, then extract
├─ Static page, one field? → htmlsnapshot get text "<selector>"
├─ Static page, one field, ALL matches? → htmlsnapshot get all text "<selector>"
├─ Static page, multiple correlated fields (title+price+url per item)?
│  → htmlsnapshot query with X-SQL DOM_LOAD_AND_SELECT
├─ Dynamic/complex JS logic needed? → eval --json (use --stdin or --file on Windows)
├─ Natural language ("find the product price")? → extract (needs LLM key)
└─ High volume, many pages? → crawl or swarm with --sql
```

### 4b. Choosing Bulk/Scale Approach

```
Need to process multiple pages?
├─ Single list page (products on one search results page)?
│  → htmlsnapshot query with DOM_LOAD_AND_SELECT
├─ Multiple known URLs (list in a file)? → crawl --seed-file urls.txt --depth 0 --sql @query.sql
├─ Crawl from a start URL (follow links)? → crawl <url> --out-link-selector "..." --depth N
├─ Need parallel execution (high throughput)? → swarm create → swarm query --seed-file ...
├─ Repeated monitoring (check every hour)? → loop -- eval "..." -i 3600
└─ Just a few URLs in a shell script?
   → for url in ...; do browser4-cli goto "$url"; ... done  (add wait between iterations)
```

### 4c. Query Granularity: get vs get all vs query

| Command | Returns | Best for |
|---------|---------|----------|
| `htmlsnapshot get text ".price"` | First match only (string) | Single value, quick check |
| `htmlsnapshot get all text ".price"` | All matches (JSON array) | Validate a selector returns expected count |
| `htmlsnapshot query --sql "SELECT ..."` | Correlated multi-field rows | Title + price + URL per product card |

**Warning:** Multiple `get all` calls produce unaligned arrays (different lengths, different order). For correlated fields, use `query` with `DOM_LOAD_AND_SELECT` scoped to a parent container.

## 5. Critical Warnings

> **Warning:** Refs are single-use for navigation and DOM-mutating commands. Re-snapshot after `click` (on links/buttons), `goto`, `reload`, and tab switches. Form interactions (`fill`, `type`, `press`, `check`, `uncheck`, `select`) are safe — you can fill an entire form from a single snapshot. Never store refs across navigations.

> **Warning:** CSS selectors are tied to live websites — they break when sites change their HTML. Always discover selectors with `htmlsnapshot inspect` or `htmlsnapshot summary` before extraction. Treat scenario examples as patterns, not copy-paste recipes.

> **Warning:** Shell quoting on Windows — complex JS/SQL with nested quotes causes escaping issues. Prefer `--sql @file.sql` (read from file), `--sql-stdin` (piped), `--sql-base64` (encoded), or `eval --file`/`eval --stdin`/`eval --base64` (JS from file or base64). For `htmlsnapshot inspect`, use `@file`, `--stdin`, or `--selector-base64`. Never inline `--sql "..."` with double-quoted CSS selectors on Windows.

> **Warning:** Don't cat snapshot files — they can exceed 256KB. Use viewport pagination (`snapshot -v 0`), `snapshot grep <pattern>`, or `snapshot --stdout --page 1` instead.

> **Note:** Output pagination defaults — `get html`, `get all html`, and `grep` paginate at 2K lines. `get text` and `get all text` are not paginated by default. Use `--all` to disable pagination, or `--page N` for subsequent pages.

> **Note:** Interactive mode (`snapshot -i`) strips generic `<div>` containers. Many e-commerce product cards use generic divs, not semantic elements. Prefer `--viewport 0` or `htmlsnapshot` for shopping/search pages.

## 6. Quick Patterns

### Interactive Form Fill

```bash
browser4-cli goto "https://example.com/login"
browser4-cli snapshot -v 0
browser4-cli fill <email-ref> "user@example.com"
browser4-cli fill <password-ref> "password"
browser4-cli click <submit-ref>
browser4-cli wait --load networkidle
browser4-cli snapshot -v 0 --auto-diff
```

### Find Elements by Text (snapshot grep)

```bash
browser4-cli goto "https://example.com"
browser4-cli snapshot -v 0                        # capture snapshot first
browser4-cli snapshot grep "See also"             # search for text in the full AX tree
browser4-cli snapshot grep -i "price|rating"      # case-insensitive regex alternation
browser4-cli snapshot grep -A 3 -B 1 "Checkout"   # show surrounding context lines
```

### Static Data Extraction (Single Field)

```bash
browser4-cli goto "https://example.com/product/42"
browser4-cli htmlsnapshot                           # capture static HTML snapshot
browser4-cli htmlsnapshot get text ".product-title"
browser4-cli htmlsnapshot get attr ".product-image" src
```

### Bulk Extraction (X-SQL — Correlated Fields)

```bash
# Write query to file (no shell escaping)
cat > query.sql << 'SQLEOF'
SELECT
    DOM_FIRST_TEXT(DOM, '.title') AS title,
    DOM_FIRST_FLOAT(DOM, '.price', 0.0) AS price,
    DOM_FIRST_HREF(DOM, 'a') AS url
FROM DOM_LOAD_AND_SELECT(@url, '.product-card', 1, 48)
WHERE DOM_IS_NOT_NIL(DOM)
ORDER BY DOM_FIRST_FLOAT(DOM, '.price', 999999.0) ASC
SQLEOF

browser4-cli htmlsnapshot query "https://example.com/products" --sql @query.sql
```

## 7. Reference Map

Organized by task — follow the link that matches what you're trying to do:

**Extract data from pages:**
[htmlsnapshot.md](references/htmlsnapshot.md) — `get`, `get all`, `query`, `grep`, `summary`, `inspect`, `export`
[x-sql.md](references/x-sql.md) — X-SQL function reference (DOM, STR, ARRAY namespaces)
[htmlsnapshot-scenarios.md](references/htmlsnapshot-scenarios.md) — 16 end-to-end recipes (e-commerce, Amazon, SEO, CI, jobs, real estate)

**Run at scale (multiple pages/URLs):**
[crawl.md](references/crawl.md) — recursive crawling, seed-file bulk fetch, X-SQL extraction
[swarm.md](references/swarm.md) — parallel scraping across multiple browser contexts
[loop.md](references/loop.md) — repeated task execution with persistence/resume

**Manage browser state:**
[storage-state.md](references/storage-state.md) — cookies, localStorage, sessionStorage, state save/load
[attach.md](references/attach.md) — connect to existing Chrome/Edge via CDP

**AI-powered extraction:**
[agent.md](references/agent.md) — `extract`, `summarize`, `agent run|status|result`, LLM provider config

**Resilient selectors:**
[power-dom.md](references/power-dom.md) — PowerCSS `:expr()` visual-feature selectors
[css-selector-bridge.md](references/css-selector-bridge.md) — bridging snapshot refs to CSS selectors

**Configure fetching:**
[load-options-guide.md](references/load-options-guide.md) — cache control, quality requirements, interaction, portal crawling

## Installation

Requires Node.js.

```bash
npm install -g browser4-cli
browser4-cli install
```

**Windows (PowerShell):**
```powershell
irm https://browser4.oss-cn-beijing.aliyuncs.com/scripts/install-browser4-cli.ps1 | iex
browser4-cli install
```

## Development

See [development.md](references/development.md) — prerequisites, building from source, and `cargo run` patterns.

## Troubleshooting: Shell Quoting on Windows

When running `browser4-cli` under Git Bash (or any POSIX shell on Windows), inline expressions pass through **four layers of quote interpretation**: Bash → `cargo run` → CLI argument parser → browser's JS engine. Each layer strips or reinterprets quotes, making correct escaping nearly impossible for complex JavaScript or X-SQL.

### The Problem

```
# This FAILS on Windows because double quotes inside double quotes get mangled:
eval "JSON.stringify({searchVal: document.querySelector('input[placeholder*=\"Search\"]')?.value})"
```

**Why it fails:** Bash sees `\"` and strips one layer of escaping. Then the remaining quotes interact with `cargo run` argument parsing. Single quotes, double quotes, backticks, and `$` signs all interact in unpredictable ways.

### The Solution: Never Inline Complex JS/SQL on Windows

| Instead of | Use | Example |
|---|---|---|
| `eval "complex JS"` | `eval --file` | `eval --file script.js` |
| | `eval --stdin` | `echo 'document.title' \| browser4-cli eval --stdin` |
| | `eval --base64` | `eval --base64 ZG9jdW1lbnQudGl0bGU=` |
| `htmlsnapshot query --sql "..."` | `--sql @file` | `--sql @query.sql` |
| | `--sql-stdin` | `--sql-stdin < query.sql` |
| | `--sql-base64` | `--sql-base64 <base64>` |
| `htmlsnapshot inspect --selector "..."` | `@file` | `htmlsnapshot inspect @selectors.txt` |
| | `--stdin` | `echo 'a[href]' \| htmlsnapshot inspect --stdin` |

### eval: Recommended Workflow on Windows

```bash
# 1. Write your JavaScript to a file (any text editor)
echo "document.querySelector('input.search-filter-input').value" > get-value.js

# 2. Evaluate it via --file — no quoting issues
browser4-cli eval --file get-value.js

# Or use heredoc for inline scripts (Bash only):
browser4-cli eval --stdin << 'EOF'
document.querySelector('input[placeholder*="Search"]')?.value
EOF

# Or pipe from echo:
echo 'JSON.stringify(document.querySelectorAll("a.job-link"))' | browser4-cli eval --stdin --json
```

### Why This Works

- **`--file`** — File content is read directly; the shell never interprets it.
- **`--stdin`** — Stdin content is passed as raw bytes; no shell interpolation.
- **`--base64`** — Base64 strings contain only alphanumeric characters and `+/=` — safe in any shell.
- **`@file`** — The `@` prefix tells the CLI to read from a file instead of interpreting as a CSS selector / SQL.
