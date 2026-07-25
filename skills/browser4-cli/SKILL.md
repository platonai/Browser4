---
name: browser4-cli
title: "Browser Automation with browser4-cli"
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

Each interactive element has a **ref** (`e5`, `e12`) — the element's Chrome DevTools Protocol backend node ID, prefixed with `e` (so `e12345` refers to backend node 12345). Use them to target elements in `click`, `fill`, `type`, `get attr`, etc.

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

### Tab Management

Tab commands scope to a session — all operations affect the session targeted via `-s <session>` (or the DEFAULT session when `-s` is omitted).

#### Tab Lifecycle

```
1. LIST     browser4-cli tab-list                    # See all tabs: index, GUID, title, URL
2. CREATE   browser4-cli tab-new [url]               # Open a new tab (about:blank if URL omitted)
3. SWITCH   browser4-cli tab-select <index>          # Switch by index
           browser4-cli tab-select --guid <guid>    # Switch by stable GUID
4. CLOSE    browser4-cli tab-close <index>           # Close by index
           browser4-cli tab-close                   # Close current tab
           browser4-cli tab-close --guid <guid>     # Close by GUID
5. VERIFY   browser4-cli tab-list                    # Confirm state after changes
```

#### Key notes

- **GUIDs:** `tab-list` shows a `GUID` column. Use `--guid` for stable targeting across tab reordering. Extension sessions show a `chrome:` prefix on numeric GUIDs; regular sessions use 32-char hex GUIDs.
- **Machine-readable output:** Use the global `--json` flag *before* the command: `browser4-cli --json tab-list`. The output includes a `"tabs"` array with `index`, `guid`, `url`, `title` for each tab, plus a `"count"` field.
- **Session scoping:** Prefix tab commands with `-s <session-id>` to target a non-default session. The `list` command shows all tracked sessions and their IDs.
- **Last-tab behavior:** Chrome requires at least one open tab. Closing the last tab silently creates a replacement `about:blank` — `tab-list` will still show 1 tab afterward.
- **Tab insert position:** New tabs are inserted by Chrome (not Browser4). The position depends on Chrome's native behavior — typically after the active tab. Use `tab-list` to verify.
- **No auto-snapshot:** `tab-list` and `tab-close` do NOT trigger automatic snapshots. After `tab-select`, run `snapshot` explicitly to get fresh element refs for the new active tab.
- **Re-snapshot after switches:** `tab-select` changes the active page context. Capture a fresh snapshot before interacting with page elements in the new tab.

#### Examples

```bash
# List all tabs in the default session
browser4-cli tab-list

# Machine-readable tab data
browser4-cli --json tab-list

# Open a tab and switch to it
browser4-cli tab-new https://httpbin.org/get
# Output: Switched to tab 1 (https://httpbin.org/get)

# Close by GUID (survives reordering)
browser4-cli tab-close --guid 2AAA0C47D288D3943BA85D31AA8D084C

# Cross-session tab operations
browser4-cli -s ext-session tab-list
browser4-cli -s ext-session tab-new https://example.com
browser4-cli -s ext-session tab-select 0
```

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
| `webdb export`, `webdb normalize` | Export cached pages, normalize URLs to database keys | Post-crawl content extraction, URL key lookup | [webdb.md](references/webdb.md) |
| `skills`, `skills get`, `skills path`, `skills unpack` | Bundled AI agent skill files | Refresh agent instructions, unpack skill files | [skills.md](references/skills.md) |
| `skill-list`, `skill-info`, `skill-install`, `skill-uninstall`, `skill-reload` | Backend skill management | Install/manage server-side skills | [skills.md](references/skills.md) |
| `screenshot`, `scroll`, `wait`, `resize` | Visual capture & viewport control | Screenshots, viewport sizing, scroll control | — |
| `tab-list`, `tab-new`, `tab-select`, `tab-close` | Tab management | Multi-tab workflows, session-scoped tab operations. See §Tab Management below. | — |

### Refreshing This Skill

The `skills` command retrieves bundled skill content that always matches the installed CLI version. Use it to get current instructions rather than relying on cached copies:

```bash
browser4-cli skills                         # List all bundled skills
browser4-cli skills get browser4-cli        # Print this SKILL.md
browser4-cli skills get browser4-cli --full # Include all reference files
browser4-cli skills path                    # Print skills directory path
browser4-cli skills unpack                  # Unpack bundled skill files to disk
```

Set `BROWSER4_SKILLS_DIR` to override the skills directory location. Skill files are unpacked automatically during `browser4-cli install`. Use `skills unpack` to refresh or relocate skill files without reinstalling.

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

> **Warning:** Shell quoting on Windows — complex JS/SQL with nested quotes causes escaping issues. Prefer `--sql @file.sql` (read from file), `--sql-stdin` (piped), `--sql-base64` (encoded), or `eval --file`/`eval --stdin`/`eval --base64` (JS from file or base64). For `htmlsnapshot inspect`, use `@file`, `--stdin`, or `--selector-base64`. Never inline `--sql "..."` with double-quoted CSS selectors on Windows. See [shell-quoting.md](references/shell-quoting.md) for the full workaround workflow.

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
    DOM_FIRST_TEXT(DOM, '.price') AS price,
    DOM_FIRST_ATTR(DOM, 'a[href]', 'href') AS url,
    DOM_FIRST_ATTR(DOM, 'img:expr(width > 250 && height > 250)', 'src') AS img
FROM DOM_LOAD_AND_SELECT(@url, '.product-card')
SQLEOF

browser4-cli htmlsnapshot query "https://example.com/products" --sql @query.sql
```

### PowerCSS

Modern web pages change their HTML structure frequently, but their **visual layout** stays stable. PowerCSS extends standard CSS selectors with a `:expr()` pseudo-selector that queries elements by their **computed numerical features** — size, position, and content density. This makes selectors resilient to markup changes.

#### Numerical Features

Browser4 computes these features for every DOM node:

| Feature | Description |
|---------|-------------|
| `top` | Top Y-coordinate of the element (pixels) |
| `left` | Left X-coordinate of the element (pixels) |
| `width` | Width of the element (pixels) |
| `height` | Height of the element (pixels) |
| `char` | Number of characters inside the node |
| `txt_nd` | Number of descendant text nodes |
| `img` | Number of descendant `<img>` elements |
| `a` | Number of descendant `<a>` elements |
| `sibling` | Number of sibling nodes |
| `child` | Number of child nodes |
| `dep` | Node depth in the document tree |
| `seq` | Node sequence in document order |
| `txt_dns` | Text node density |

These are usable in any CSS selector via `:expr(...)`, in X-SQL `DOM_*` functions, and in `htmlsnapshot get` / `htmlsnapshot query` commands.

---

#### `:expr()` Pseudo-Selector

```
element:expr(expression)
```

Operators in expressions include `+`, `-`, `*`, `/`, `^`, `%`, `==`, `!=`, `<`, `>`, `<=`, `>=`, `&&`, `||`. Use parentheses for grouping.

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
[webdb.md](references/webdb.md) — export cached pages, normalize URLs for database lookups
[attach.md](references/attach.md) — connect to existing Chrome/Edge via CDP

**Manage skills and agent instructions:**
[skills.md](references/skills.md) — bundled skill files, backend skill management

**AI-powered extraction:**
[agent.md](references/agent.md) — `extract`, `summarize`, `agent run|status|result`, LLM provider config

**Resilient selectors:**
[power-dom.md](references/power-dom.md) — PowerCSS `:expr()` visual-feature selectors
[css-selector-bridge.md](references/css-selector-bridge.md) — bridging snapshot refs to CSS selectors

**Configure fetching:**
[load-options-guide.md](references/load-options-guide.md) — cache control, quality requirements, interaction, portal crawling

**Troubleshoot:**
[shell-quoting.md](references/shell-quoting.md) — avoid shell-quoting breakage for complex JS/X-SQL on Windows / Git Bash

## Installation

**Cross-platform (Node.js):**
```bash
npm install -g browser4-cli
browser4-cli install
```

**Windows (PowerShell):**
```powershell
irm https://browser4.oss-cn-beijing.aliyuncs.com/scripts/install-browser4-cli.ps1 | iex
browser4-cli install
```

**Linux / macOS (bash):**
```bash
curl -fsSL https://browser4.oss-cn-beijing.aliyuncs.com/scripts/install-browser4-cli.sh | bash
browser4-cli install
```

## Development

See [development.md](references/development.md) — prerequisites, building from source, and `cargo run` patterns.
