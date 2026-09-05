---
name: browser4-cli
title: "Browser Automation with browser4-cli"
description: "Automates browser interactions for web testing, form filling, screenshots, and data extraction. Use when the user needs to navigate websites, interact with web pages, fill forms, take screenshots, test web applications, or extract information from web pages."
tags:
  - browser
  - automation
  - testing
  - cdp
  - snapshot
allowed-tools: Bash(browser4-cli:*)
tier: decision
---

# Browser Automation with browser4-cli

Browser automation CLI for AI agents — Chrome/Chromium via CDP with accessibility-tree snapshots.

### Invocation

After installing (`browser4-cli install`), invoke commands directly — the docs use `browser4-cli` as the generic command name:

```bash
browser4-cli open --headless <url>
```

## 1. Core Loop

> **🖥️ Headless mode is the default for AI agents:** Always open browsers with `--headless` unless the user **explicitly** asks to see the browser window ("show me the browser", "open visibly", "I want to watch", or "headed"). See Display Mode in §2.

```
1. OPEN        browser4-cli open --headless <url>   # headless by default for AI agents
              browser4-cli goto <url>               # or goto to navigate within existing session
2. SNAPSHOT    browser4-cli snapshot -v 0           # capture accessibility tree (viewport 0 = current visible screen)
3. INTERACT    browser4-cli click <ref>             # use refs from the snapshot
              browser4-cli fill <ref> <value>
              browser4-cli press Enter
4. RE-SNAPSHOT browser4-cli snapshot -v 0 --auto-diff # verify what changed (diff vs previous)
5. EXTRACT     browser4-cli htmlsnapshot get ...      # or eval, or X-SQL (see §4)
```

### Copy-Paste Template

```bash
browser4-cli open --headless "https://example.com"  # headless by default for AI agents
browser4-cli snapshot -v 0 --stdout       # read the page; note refs
browser4-cli fill <ref> "<value>"         # interact
browser4-cli press Enter
browser4-cli wait --load networkidle
browser4-cli snapshot -v 0 --auto-diff --stdout  # verify what changed
browser4-cli htmlsnapshot get text "<css-selector>" --all
```

For quick inline viewing without opening a file, add `--stdout` to any snapshot command.

## 2. Key Concepts

### Element Refs

After commands that modify browser state, browser4-cli saves an **accessibility-tree snapshot** — a YAML file showing the page structure:

```yaml
- generic [ref=e7]:
  - link "News" [ref=e191]: /url: https://example.com/news
  - textbox "Search query" [ref=e35]
  - button "Search" [ref=e25]
```

Each interactive element has a **ref** (`e5`, `e12`) — the element's Chrome DevTools Protocol backend node ID, prefixed with `e` (so `e12345` refers to backend node 12345). Use them to target elements in `click`, `fill`, `type`, `get attr`, etc.

> **Note:** `/url` fields may be **relative** (e.g. `/url: news`). The snapshot output includes the page URL at the top for resolution; for absolute URLs use `htmlsnapshot get all attr "a[href]" href` (resolves redirects).

### Ref Lifecycle

Refs are **ephemeral** — treat them as single-use handles. Any interaction can leave you with stale refs if the page re-renders or Chrome remaps backend nodes:

- **Always re-snapshot after interactions:** `click`, `fill`, `type`, `press`, `check`, `uncheck`, `select`, `hover`, `drag`, `dblclick`.
- **Definitely re-snapshot after page/context changes:** `goto`, `reload`, tab switches, or clicks that navigate/update the page. **If you are chaining form actions:** rely on the automatic post-action snapshot, then use refs from that fresh snapshot for the next step.

**In practice, the safest loop is interact → re-snapshot → use new refs.** Interaction commands capture an automatic snapshot after execution; pass `--no-snapshot` to skip it when you plan to capture a fresh snapshot manually (saves a round-trip).

### Output Modes

- **Default** — human-readable output on stdout.
- **`--show-tip` / `-tip`** — show a relevant, rotating tip on stderr after each successful command. Tips are suppressed by default; use this flag to enable them.
- **`--json`** — single-line JSON envelope on stdout for commands that support structured output. This is the clean machine-readable mode for commands such as `tab-list`, `htmlsnapshot get`, `htmlsnapshot query`, and `eval`. **Exception:** `snapshot` remains YAML-focused and warns on stderr instead of returning JSON snapshot data.
- **File output (default for AI commands)** — `extract` and `summarize` save their result to a timestamped file in `.browser4-cli/snapshot/` and print only a link; add `--stdout` (or `--raw`) to print the payload directly. When `extract --schema` is used, the requested schema fields are emitted as plain **top-level JSON** (in the file and on stdout) — no envelope to parse.
- **`--quiet` / `-q`** — suppress all normal output; only errors appear on stderr.

### Display Mode (Headless vs Headed)

| Mode | Flag | Window | Use case |
|------|------|--------|----------|
| **Headless** | `--headless` | No GUI window | **Default for AI agents** — scraping, automation, CI/CD, server environments |
| **Headed** | `--headed` | Visible browser window | Debugging, user demonstration, interactive development |

**Rule for AI agents: always use `--headless` by default.** Use `--headed` only when the user **explicitly** requests a visible browser ("show me the browser", "I want to see", "open visibly", "headed", "watch what happens").

Set the display mode with `open` when starting a **new** session; `goto` does not accept `--headless`/`--headed` — it inherits the session's mode:

```bash
browser4-cli open --headless https://example.com     # headless (preferred default)
browser4-cli open --headed https://example.com       # headed (only when user asks)
browser4-cli goto https://other-page.com             # stays headless (or headed) as set by open
```

> **Notes:** When `goto` is the very first command (no prior `open`), it auto-opens a new session using the CLI's default display mode, which is headless. `--headless`/`--headed` only take effect when **creating** a new session — when `open` reconnects to a running session they are ignored (the CLI warns on stderr). To change the mode, `close` first, then `open --headless`. Use `open --fresh` to discard a stale session's tabs/cookies/location entirely and start clean.

### Sessions

Named sessions isolate browser state (cookies, localStorage, tabs) in a **dedicated browser profile directory** keyed by the session id — reopening a named session always restores the same profile instead of rotating through a shared pool. Use `-s <name>` to target a named session. `goto` auto-opens/reconnects — you rarely need to manage sessions manually.

The `list` command displays a "Next open" column showing what happens when `goto` or `open` targets a named session that already exists:
- **Reuse** — reconnects to the existing browser window (session is active on the backend).
- **Refresh** — opens a fresh window (session is stale or missing).

Session state is stored in `~/.browser4` by default. When that directory is not
writable (e.g. sandboxed shells), the CLI automatically falls back to
`./.browser4-cli-state` (workspace-relative) and prints a warning — set
`BROWSER4_CLI_STATE_DIR` to an explicit writable path to silence it.
`BROWSER4_RUNTIME_DIR` likewise overrides the runtime bundle location.

### Configuration

CLI defaults (`config.json`) and server-side runtime overrides are managed by the `config` command family — see **[config.md](references/config.md)** for the full reference:

```bash
browser4-cli config                              # List all values + config file path
browser4-cli config set server http://localhost:8182
browser4-cli config set agent.llm.maxRequestTokens 800000   # server-side runtime override
```

### Tab Management

Tab commands (`tab-list`, `tab-new`, `tab-select`, `tab-close`, `window new`) scope to a session. **Re-snapshot after `tab-select`** — tab switches change the active page context. See **[tab-management.md](references/tab-management.md)** for the tab lifecycle, GUID-based targeting, cross-session operations, and extension-session quirks.

### Frame Switching (iframes)

Element commands (`click`, `fill`, `type`, `hover`, `focus`, `is visible`, `wait`, …) resolve CSS selectors against the **main document** by default. On pages that embed forms/content in `<iframe>`s, switch into the frame first:

```bash
browser4-cli frames                    # list the frame tree: names, urls, depth, active frame
browser4-cli frame "#pay-frame"        # switch into the iframe (CSS selector)
browser4-cli fill "#card-number" "4111 1111 1111 1111"   # resolves INSIDE the iframe
browser4-cli click "#pay-submit"       # resolves INSIDE the iframe
browser4-cli frame main                # back to the main document
```

- `frame <target>` accepts: a snapshot element ref of the iframe (`e12`, `backend:123`), an iframe CSS selector (`#pay-frame`, `iframe[src*="checkout"]`), the frame `name`, the frame `id` from `frames` output, or a URL fragment. Nested iframes: switch repeatedly (`frame "#outer"` then `frame "#inner"`).
- The scope resets automatically on navigation (`goto`/`open`/`reload`/back/forward) — re-run `frame` after navigating.
- **Same-origin iframes are fully supported.** Cross-origin iframes (out-of-process frames) are not supported in this version: `frames` cannot see them and `frame` on one fails with an actionable error (no per-frame CDP sessions are attached). Use `cdp` with `Target.attachToTarget` for those, or drive the frame's origin in its own session.
- `eval` always runs in the **main document** (matching agent-browser); reach same-origin iframe content from eval via `contentDocument` if needed.
- See **[frames.md](references/frames.md)** for details.

## 3. Command Map

| Command family | Purpose | When to use | Full reference |
|---------------|---------|-------------|----------------|
| `goto`, `open`, `close`, `reload` | Navigation & session management | Every session starts here | — |
| `snapshot` | Capture accessibility tree (AXTree) with element refs | **Page structure & interaction** — find elements to click, fill, etc. Use `snapshot` when you need refs (e5, e36) to interact with. | [snapshot.md](references/snapshot.md) |
| `snapshot grep` | Search snapshot content with regex | Find elements by text or pattern | — |
| `click`, `dblclick`, `drag`, `hover`, `fill`, `type`, `press`, `select`, `check`, `generate-locator` | Page interaction | Form filling, button clicks, mouse actions, navigation | — |
| `focus`, `key`, `keyboard` | Focus an element / press a key (key & keyboard alias `press`) | Explicit focus before typing, agent-browser-style keypresses | — |
| `is visible\|enabled\|checked <sel>` | Element state assertions | Verify visibility, enabled-ness, or checked state before acting | — |
| `dialog-accept`, `dialog-dismiss`, `dialog-status` | Native JS dialog handling | After clicking buttons that trigger alert/confirm/prompt; `dialog-status` inspects the pending dialog | — |
| `htmlsnapshot get`, `get all` | Extract text/html/attr via CSS selectors from stored HTML | **Page content & text extraction** — get article text, headings, attributes. Use `htmlsnapshot` when you need to read or extract page content. | [htmlsnapshot.md](references/htmlsnapshot.md) |
| `htmlsnapshot readability` | One-step article extraction via a Readability-style heuristic (no LLM, no selectors) | Get the main article (title, byline, text) from the stored snapshot in one call; `htmlsnapshot readability <url>` fetches a page independently | [htmlsnapshot.md](references/htmlsnapshot.md) |
| `htmlsnapshot query` | X-SQL queries for structured extraction | Multi-field, filtered, sorted data | [x-sql.md](references/x-sql.md) |
| `eval` | Execute JavaScript in the page | Live DOM access, complex transforms | — |
| `eval --ref` | Execute JS scoped to a specific element | Element property extraction (text, attrs, styles) | **⚠️ Expression MUST be an arrow function: `element => element.textContent`** |
| `scrollintoview`, `pushstate`, `highlight` | Element scroll / history / visual highlight (eval-based shortcuts) | Scroll an element into view, push a history entry, outline an element | — |
| `vitals`, `web-vitals` | Core Web Vitals measurement (LCP, CLS, INP, FCP, TTFB) via injected web-vitals lib | Performance checks on a live page (needs network for CDN) | — |
| `set geo\|offline\|headers\|media\|device` | CDP emulation: geolocation, offline mode, extra headers, color scheme, device metrics | Emulate locations, networks, devices, and media features | — |
| `errors` | Console errors only (alias of `console --min-level error`) | Surface page JS errors fast | — |
| `extract`, `summarize`, `agent run` | AI-powered extraction | Natural language extraction (needs LLM key) | [agent.md](references/agent.md) |
| `crawl` | Recursive crawling + bulk extraction | Multi-page traversal, seed-file processing | [crawl.md](references/crawl.md) |
| `swarm` | Parallel scraping across browser contexts | High-throughput extraction | [swarm.md](references/swarm.md) |
| `loop` | Repeated task execution with persistence | Monitoring, scheduled checks | [loop.md](references/loop.md) |
| `state-save`, `state-load`, `cookie-*`, `*-storage-*` | Browser storage management | Auth state reuse, cookie manipulation | [storage-state.md](references/storage-state.md) |
| `attach` | Connect to existing Chrome/Edge via CDP | Debug live browser, reuse auth | [attach.md](references/attach.md) |
| `webdb export`, `webdb normalize` | Export cached pages, normalize URLs to database keys | Post-crawl content extraction, URL key lookup | [webdb.md](references/webdb.md) |
| `skills`, `skills get`, `skills path`, `skills unpack` | Bundled AI agent skill files | Refresh agent instructions, unpack skill files | [skills.md](references/skills.md) |
| `skill-list`, `skill-info`, `skill-install`, `skill-uninstall`, `skill-reload` | Backend skill management | Install/manage server-side skills | [skills.md](references/skills.md) |
| `screenshot`, `scroll`, `wait`, `resize` | Visual capture & viewport control | Screenshots, viewport sizing, scroll control; `wait --download` polls a download directory | — |
| `tab-list`, `tab-new`, `tab-select`, `tab-close`, `window new` | Tab & window management | Multi-tab workflows, session-scoped tab operations | [tab-management.md](references/tab-management.md) |
| `frames`, `frame <target>`, `frame main` | Iframe frame switching | Interact with content inside `<iframe>`s: `frame "#pay-frame"` then `fill`/`click`/`is visible` resolve inside that frame; `frames` lists the frame tree | [frames.md](references/frames.md) |
| `diff snapshot` | Unified diff between two saved accessibility snapshots | Verify what changed between interactions (`snapshot --auto-diff` equivalent on saved files) | — |
| `download`, `wait --download` | Download management | `download --dir <path>` configures the browser download folder; `wait --download` blocks until a download completes | — |
| `network requests`, `network request <id>`, `network har start`, `network har stop`, `network route`, `network unroute` | Network request inspection, HAR recording & request routing | Inspect what the page loaded (XHR/fetch/status/headers), debug API calls, record a `.har` file (Chrome DevTools importable), or mock/abort matching requests (Fetch interception). `network requests --filter api --status 2xx`; `network har start --content text` then `network har stop ./capture.har`; `network route "**/api/users" --body '{"users":[]}'` | [network.md](references/network.md) |
| `profiler start`, `profiler stop` | V8 CPU profiling via CDP | Profile page interactions and save `.cpuprofile` (Chrome DevTools / speedscope compatible) | — |
| `profiles list` | List browser profile directories | See what profiles exist under `~/.browser4/browser/chrome` before `open --profile` | — |
| `profile-import` | Import bookmarks/history/passwords/cookies/extensions from system Chrome/Edge/Safari (requires the browser4-profile-import plugin) | `profile-import --list-sources` to discover browsers; `profile-import --source chrome --data bookmarks,cookies` copies a whole profile snapshot to `~/.browser4/imports/`; `--into prototype|default` seeds a managed profile dir; then `open --profile <dir>` mounts it | [browser-state-import.md](references/browser-state-import.md) |
| `config` | Persistent CLI defaults (server, timeout, proxy, session) | Set default server URL, timeout, proxy, or session name | [config.md](references/config.md) |
| `status`, `doctor`, `doctor log`, `doctor metrics`, `doctor status` | Server health & diagnostics | `doctor status` prints the aggregated status report (health, build, runtime, LLM, sessions, browsers, swarm, plugins, skills, metrics, logs) in layers: summary by default, `--verbose` for full detail, `--section <name>` for one report, `--json` for machine-readable output. `status` prints the web status panel URL (`http://<server>:8182/status`) — a live dashboard of the same reports; `http://<server>:8182/pages.html` shows every open page | — |

### Refreshing This Skill

```bash
browser4-cli skills | skills get browser4-cli [--full] | skills unpack
```

Skill files are unpacked during `browser4-cli install` (and refreshed by `upgrade`); `BROWSER4_SKILLS_DIR` overrides the location, and install/upgrade also copy skills to `~/.agents/skills` for AI agents.

## 4. Decision Trees

Choosing how to extract or process data? The full decision trees, comparisons, and the X-SQL quickstart template live in **[decision-trees.md](references/decision-trees.md)**. The essentials:

### 4a. Choosing an Extraction Method

> **📋 snapshot vs htmlsnapshot — the essential distinction:**
>
> | | `snapshot` | `htmlsnapshot` |
> |---|---|---|
> | **What it captures** | Accessibility tree (AXTree) — semantic roles, names, refs | Raw HTML DOM — full text content |
> | **Primary use** | **Interaction** — get element refs for click, fill, type | **Extraction** — get article text, data, attributes |
> | **Output** | YAML tree with `[ref=e5]` handles | Text/HTML/JSON via CSS selectors |
> | **Key commands** | `snapshot`, `snapshot grep`, `click <ref>` | `htmlsnapshot get`, `query`, `inspect` |
> | **When to use** | "I need to click a button" or "find an input field" | "I need to read the article text" or "extract prices" |
>
> **Rule of thumb:** If you want to **interact** with elements → `snapshot`. If you want to **read content** → `htmlsnapshot`.

> **⚠️ htmlsnapshot capture requirements — which commands need a prior capture:**
>
> | Command | Needs prior `htmlsnapshot` capture? | Notes |
> |---------|-------------------------------------|-------|
> | `htmlsnapshot` (capture) | — (this IS the capture) | Stores the page's initial HTML for later extraction |
> | `htmlsnapshot get` / `get all` | **Yes** — requires stored snapshot | Extracts text/html/attr via CSS selectors from the stored HTML |
> | `htmlsnapshot inspect` | **Yes** — requires stored snapshot | Iterates CSS selectors from the stored HTML; returns "No HTML snapshot found" if missing |
> | `htmlsnapshot summary` | **Yes** — requires stored snapshot | Statistical summary of selectors on the stored page |
> | `htmlsnapshot grep` | **Yes** — requires stored snapshot | Regex search over the stored HTML |
> | `htmlsnapshot export` | **Yes** — requires stored snapshot | Exports the stored HTML to a file |
> | `htmlsnapshot query` | **No** — no capture needed | Current page → queries the session's **live DOM** (seeded before the SQL runs; login/SPA state visible). Other URLs → independent webdb load, no session state |
>
> **If you get "No HTML snapshot found" or a timeout:** either run `htmlsnapshot` first to capture, or use `htmlsnapshot query` — it needs no prior capture (current page reads the live DOM; an explicit URL is fetched independently).

> **⚠️ Important:** `htmlsnapshot` captures the **current live DOM** at capture time. Content added or modified by JavaScript before the capture (form submission results, dynamic updates, SPA route changes) **is reflected** — but only if you run `htmlsnapshot` (capture) *after* the interaction. The stored snapshot becomes stale only if you do not re-capture after a navigation or interaction. For one-off live reads without a capture step, use `eval`. See [§5 Critical Warnings](#5-critical-warnings) for more.

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
   → browser4-cli open --headless (once) then use goto for each URL; add wait between iterations
```

### 4c. Query Granularity: get vs get all vs query

| Command | Returns | Best for |
|---------|---------|----------|
| `htmlsnapshot get text ".price"` | First match only (string) | Single value, quick check |
| `htmlsnapshot get all text ".price"` | All matches (JSON array) | Validate a selector returns expected count |
| `htmlsnapshot query --sql "SELECT ..."` | Correlated multi-field rows | Title + price + URL per product card |

**Warning:** Multiple `get all` calls produce unaligned arrays (different lengths, different order). For correlated fields, use `query` with `DOM_LOAD_AND_SELECT` scoped to a parent container.

### 4d. Structuring Extracted Pages (WebMiner)

WebMiner runs ML clustering on downloaded HTML files to produce structured spreadsheets and interactive reports — **no LLM tokens, everything runs locally.**

webminer is a first-class Browser4 CLI citizen — `browser4-cli webminer <command>` installs and runs the tool without PowerShell:

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

**Pipeline:** `encode` (HTML → feature vectors → CSV) → `cluster` (KMeans, auto-detected K) → `views` (interactive HTML report + Excel spreadsheets)

**Free tier (SMILE):** Single-machine ML via the [SMILE](https://haifengl.github.io/) library. Handles small-to-medium datasets (< 1,000 pages). Ideal for ad-hoc analysis, prototyping, and one-off extraction tasks.

**Commercial tier (Apache Spark ML):** Distributed clustering for production workloads. Scales to 100K+ pages/day. Same pipeline, enterprise throughput.

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

See **[web-miner/SKILL.md](../browser4-web-miner/SKILL.md)** for the full reference.

### 4e. X-SQL Quickstart Template

X-SQL lets you extract correlated fields (e.g., title + price + URL) from a
list page using a scoped CSS selector and standard SQL.  Copy this template,
swap the selectors and column names, and you have a working query:

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
#    Default output is the raw JSON response envelope (machine-readable).
#    For human-readable output add --format table (csv also available;
#    --result-only prints just the resultSet):
browser4-cli htmlsnapshot query "https://example.com/products" --sql @query.sql --format table
```

**Exit codes:** `htmlsnapshot query` exits `0` when the response envelope
reports success — a `200` with an *empty* resultSet counts as success
("no rows matched", not an error). It exits nonzero when the server returns
an error envelope (`417 Expectation Failed` — the scrape session closed
before the query ran — or a `5xx` with an empty resultSet), so scripts can
detect failure without parsing the JSON.

**Critical syntax rules** (H2 SQL engine — violating these produces opaque errors):

| Rule | Correct | Wrong |
|------|---------|-------|
| CSS selectors use **single** quotes (SQL string literals) | `'h2'`, `'.price'` | `"h2"` (SQL identifier) |
| `@url` placeholder is **unquoted** | `@url` | `'@url'` (literal string) |
| FROM source is always `DOM_LOAD_AND_SELECT` | `DOM_LOAD_AND_SELECT(@url, '...')` | Any other table name |
| No CTEs (`WITH`), no `JOIN`, no subqueries | Simple `SELECT … FROM …` | `WITH t AS (…) SELECT …` |

**Discover selectors** before writing the query:
```bash
browser4-cli htmlsnapshot inspect                    # recurring-pattern discovery (list/grid pages)
browser4-cli htmlsnapshot summary                    # visual clustering (detail/single-block pages)
browser4-cli htmlsnapshot get text ".price" --all    # quick test: does this selector match elements?
```

`htmlsnapshot inspect` finds **recurring** patterns — it is built for list/grid pages (search results, product cards, tables). A single product/article/detail page has no repeating block, so inspect may surface nothing or an unrelated side rail; when that happens it prints "No recurring pattern found". For detail pages use `htmlsnapshot summary` (visual clustering) or `htmlsnapshot get` with explicit selectors (`htmlsnapshot get text "h1"`, `get attr "#product-image" src`).

**Common mistakes and solutions:**

| Symptom | Likely cause | Fix |
|---------|-------------|-----|
| `Column "h2" not found` | Double quotes around CSS selector → treated as SQL column name | Use single quotes: `'h2'` |
| `Table "..." not found` | Wrong FROM source or quoted `@url` | Use `DOM_LOAD_AND_SELECT(@url, 'selector')` |
| `Hexadecimal string contains non-hex character` (417) | `DOM_FIRST_FLOAT`/`DOM_FIRST_INTEGER` compared to a numeric literal in WHERE — the function returns a custom H2 value type that predicates cannot compare (works in SELECT/ORDER BY) | Wrap in a numeric cast: `WHERE CAST(DOM_FIRST_FLOAT(DOM, '.price', 0.0) AS DOUBLE) >= 25.0` (or `STR_FIRST_FLOAT(DOM_FIRST_TEXT(DOM, '.price'), 0.0)`) — always pass the default argument explicitly (the registered form is `DOM_FIRST_FLOAT(DOM, sel, default)`; the 2-argument shorthand is not portable across engine builds), see [x-sql.md](references/x-sql.md) |
| Empty result set | Selector doesn't match any elements | Run `htmlsnapshot inspect` to find valid selectors |
| Empty result set (no error) | Filtering images with a PowerCSS `:expr(...)` selector passed to a `DOM_*_IMG` function — the img-scanning path ignores `:expr` and matches nothing | Use an attribute path that honors `:expr`: `DOM_FIRST_ATTR(DOM, 'img:expr(src^=https://cdn)', 'src')` or `DOM_SELECT_FIRST(DOM, sel)` + `DOM_ABS_SRC` — see [x-sql-dom-select-functions.md](references/x-sql-dom-select-functions.md) |
| `Syntax error in SQL statement` | `--sql` value contains shell-escaped characters | Use `--sql @query.sql` instead of inline SQL |

## 5. Critical Warnings

> **Warning:** Refs are effectively single-use. Re-snapshot after any interaction before using refs again, and always do so after `goto`, `reload`, and tab switches. On reactive pages, even form commands can leave earlier refs stale. Never store refs across navigations or assume a pre-interaction ref is still valid.

> **Warning:** CSS selectors are tied to live websites — they break when sites change their HTML. Always discover selectors with `htmlsnapshot inspect` or `htmlsnapshot summary` before extraction. Treat scenario examples as patterns, not copy-paste recipes.

> **Warning:** Shell quoting on Windows — complex JS/SQL with nested quotes causes escaping issues. Prefer `--sql @file.sql` (read from file), `--sql-stdin` (piped), `--sql-base64` (encoded), or `eval --file`/`eval --stdin`/`eval --base64` (JS from file or base64). For `htmlsnapshot inspect`, use `@file`, `--stdin`, or `--selector-base64`. Never inline `--sql "..."` with double-quoted CSS selectors on Windows. **On PowerShell, always quote `@file` paths (`--sql "@query.sql"`) — an unquoted `@` is read as the splatting operator.** See [shell-quoting.md](references/shell-quoting.md) for the full workaround workflow.
>
> **Tip:** To generate base64 for `eval --base64`: `echo -n 'document.title' | base64` (Linux/macOS) or `[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes('document.title'))` (PowerShell).
>
> **⚠️ Important — eval with `--ref`:** When scoping evaluation to an element with `--ref` (or positional `[ref]`), the expression **MUST be an arrow function**: `element => element.textContent`. The DOM element is passed as the first argument. Writing `element.textContent` or `this.textContent` will return `null` — this is the #1 user mistake with element-scoped eval.

> **Warning:** Don't cat snapshot files — they can exceed 256KB. The same applies to `--stdout`, which may dump large accessibility trees (63KB+ for content-rich pages). Use viewport pagination (`snapshot -v 0`), `snapshot grep <pattern>`, or `snapshot --stdout --page 1` instead. For targeted extraction, prefer `snapshot grep` or `htmlsnapshot` commands over full-tree dumps.

> **Note:** Output pagination defaults — `get html`, `get all html`, and `grep` paginate at 2K lines. `get text` and `get all text` are not paginated by default. Use `--all` to disable pagination, or `--page N` for subsequent pages.

> **Snapshot modes — when to use `-v 0` vs `-i` vs default:**
>
> | Mode | What it shows | Best for |
> |------|--------------|----------|
> | `snapshot` (default) | Full AX tree with all element refs | General exploration, first look at a page |
> | `snapshot -v 0` | Current visible screen (a single screen-height viewport chunk) | Long pages — read one chunk at a time to keep output small. Use `-v all` for the entire page |
> | `snapshot -i` | **Interactive-oriented layout** — inner text is aggregated into the enclosing element's name so each ref line reads as a self-contained target. **Not** a strict interactive-only filter: addressable headings, paragraphs and generic containers remain | Quick orientation before acting via refs; form-heavy pages where most lines are controls anyway. To bound size use `-v 0` viewport pagination, `--selector`, or `htmlsnapshot` |
> | `htmlsnapshot` | Static HTML (CSS selectors) | Content extraction (text, attributes), when you need CSS selectors instead of AX refs |
>
> **`-i` does not shrink the tree:** the interactive pass aggregates text into element names; it does **not** strip non-interactive containers (addressable headings, `<div>` wrappers etc. remain). Pair `-i` with `-v 0` (`snapshot -i -v 0`) for one focused screenful, and use `htmlsnapshot` when you need CSS-selector extraction instead of refs.
>
> **Example — reading one screenful:**
> ```bash
> # Default rendering: text sits under its own element lines in the tree.
> browser4-cli snapshot -v 0 --stdout
>
> # Interactive-oriented rendering: each ref line carries its inner text in
> # the name. Controls, headings and containers that have refs all remain —
> # `-i` changes the layout, it does not reduce the tree to buttons/links.
> browser4-cli snapshot -i -v 0 --stdout
> ```

> **Warning:** `htmlsnapshot` captures the **current live DOM** at capture time. Re-capture (run `htmlsnapshot`) after any interaction or navigation to reflect JS updates — a previously captured snapshot is stale only if you do not re-capture. The auto-captured snapshot after `goto` is an earlier capture and does not include later interactions. For one-off live reads without a capture step, use `eval`. The `htmlsnapshot inspect` command reads the stored snapshot — re-capture first to inspect the updated DOM.

> **Warning — backend startup fails in sandboxed/restricted environments:** The Browser4 backend (Spring Boot/JVM) writes its log files to a `logs/` directory inside the runtime bundle — `BROWSER4_RUNTIME_DIR` (default `%APPDATA%/browser4` on Windows, `~/.local/share/browser4` on Linux). In sandboxes that only allow writes to the workspace, this write is denied and the server never becomes ready: `goto`/`open` hang until the startup timeout with `FileNotFoundException … Access denied` (or `拒绝访问`) in the startup log.
>
> **Diagnose:** the failed command prints a startup-log path under `🧾 Details` — look for a `logs\*.log` (or `logs/*.log`) write failure there.
>
> **Fix:** point the runtime and state at writable locations before the first launch:
> ```bash
> # PowerShell
> $env:BROWSER4_RUNTIME_DIR  = "D:\workspace\browser4-runtime"  # JRE/JARs + logs (~200 MB)
> $env:BROWSER4_CLI_STATE_DIR = "D:\workspace\.browser4-state"  # session state
> ```
> `BROWSER4_RUNTIME_DIR` relocates the runtime (re-downloads the bundle if not already present); `BROWSER4_CLI_STATE_DIR` already auto-falls back to `./.browser4-cli-state` when `~/.browser4` is unwritable.

## 6. Quick Patterns

Proven copy-paste recipes — full walkthroughs in **[quick-patterns.md](references/quick-patterns.md)**:

Named sessions isolate browser state. Create and switch with `-s <name>`, list with `list`, close one with `close`, and clean up with `close-all`:

```bash
browser4-cli -s research goto "https://en.wikipedia.org"   # opens "research"
browser4-cli -s news     goto "https://news.ycombinator.com" # opens "news"
browser4-cli -s news     snapshot -i --stdout              # act inside "news"
browser4-cli list                                          # show all sessions
browser4-cli -s news     close                             # close only "news"
browser4-cli close-all                                     # close every session
```

### Interactive Form Fill

```bash
browser4-cli open --headless "https://example.com/login"
browser4-cli snapshot -v 0
browser4-cli fill <email-ref> "user@example.com"
browser4-cli fill <password-ref> "password"
browser4-cli click <submit-ref>
browser4-cli wait --load networkidle
browser4-cli snapshot -v 0 --auto-diff
```

### Find Elements by Text (snapshot grep)

```bash
browser4-cli open --headless "https://example.com"
browser4-cli snapshot -v 0                        # capture snapshot first
browser4-cli snapshot grep "See also"             # search for text in the full AX tree
browser4-cli snapshot grep -i "price|rating"      # case-insensitive regex alternation
browser4-cli snapshot grep -A 3 -B 1 "Checkout"   # show surrounding context lines
```

**Regex dialect (same for `htmlsnapshot grep`):** patterns are Rust regex. `|` is alternation (not `\|`); `^`/`$` anchor the start/end of a line, so a literal dollar must be written `[$]` — e.g. `'[$][0-9]+'` matches "$12", but `\$[0-9]+` is an invalid-escape **error**. `-n` is accepted (GNU-grep habit) but line numbers already print by default — use `--no-line-number` to hide them, or `-F` to match literal text.

### Mouse Interactions

```bash
# Hover — reveal tooltips, expand menus, trigger hover effects
browser4-cli hover <ref>                          # hover over an element
browser4-cli snapshot grep "tooltip"              # verify tooltip appeared

# Double-click — trigger dblclick handlers
browser4-cli dblclick <ref>                       # double-click an element

# Drag-and-drop — move elements between containers
browser4-cli drag <source-ref> <target-ref>       # drag source onto target
browser4-cli snapshot grep "new position"         # verify element was moved
```

### Dialog Handling

Native browser dialogs (`alert()`, `confirm()`, `prompt()`) block the page's main thread. When a dialog appears (e.g., after clicking a button), `click` will time out. Handle the dialog with a separate command:

```bash
browser4-cli click "#alertBtn"                    # triggers alert — click will time out
browser4-cli dialog-accept                        # dismiss the alert ("OK")

browser4-cli click "#confirmBtn"                  # triggers confirm
browser4-cli dialog-accept                        # click "OK" (returns true to page)

browser4-cli click "#promptBtn"                   # triggers prompt
browser4-cli dialog-accept "Hello from Browser4"  # fill prompt and accept

browser4-cli dialog-dismiss                       # cancel/dismiss any dialog
```

**Note:** `dialog-accept` and `dialog-dismiss` must be run in a separate invocation — they cannot be part of the same command as the triggering `click`. Alternatively, use `click --auto-dismiss-dialogs <ref>` to auto-accept any dialog triggered by the click in a single invocation.

### Verifying Results (verify-after-interaction)

Every interaction should be followed by verification. These patterns show how to confirm your actions had the expected effect:

```bash
# After click — diff vs previous snapshot
browser4-cli click <submit-ref>
browser4-cli snapshot -v 0 --auto-diff --stdout   # shows only what changed

# After hover — search for expected content
browser4-cli hover <ref>
browser4-cli snapshot grep "expected-tooltip-text"

# After drag — confirm reordering
browser4-cli drag <source> <target>
browser4-cli snapshot grep "new order|reordered|moved"

# After dialog — verify the interaction log
browser4-cli click "#alertBtn" && browser4-cli dialog-accept
browser4-cli snapshot grep "\[alert\]|\[confirm\]|\[prompt\]"

# Generate resilient CSS selectors from snapshot refs
browser4-cli generate-locator <ref>               # produces e.g. "#contactForm > button.primary"
browser4-cli get text "#contactForm > button.primary"  # verify with the generated selector
```

### Static Data Extraction (Single Field)

```bash
browser4-cli open --headless "https://example.com/product/42"
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

# Add --format table for human-readable output (default is the raw JSON
# response envelope; --result-only prints just the resultSet):
browser4-cli htmlsnapshot query "https://example.com/products" --sql @query.sql --format table
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

These are usable in any CSS selector via `:expr(...)`, in X-SQL `DOM_*` attribute/text/select functions, and in `htmlsnapshot get` / `htmlsnapshot query` commands. **Exception:** the X-SQL `DOM_*_IMG` image helpers (`DOM_FIRST_IMG`/`DOM_NTH_IMG`/`DOM_ALL_IMGS`) ignore `:expr(...)` and silently match nothing — filter images with `DOM_FIRST_ATTR(DOM, 'img:expr(...)', 'src')` or `DOM_SELECT_FIRST` instead (see [x-sql-dom-select-functions.md](references/x-sql-dom-select-functions.md)).

---

#### `:expr()` Pseudo-Selector

```
element:expr(expression)
```

Operators in expressions include `+`, `-`, `*`, `/`, `^`, `%`, `==`, `!=`, `<`, `>`, `<=`, `>=`, `&&`, `||`. Use parentheses for grouping.

### Agent Task Lifecycle (Async)

Agent tasks run asynchronously — submit a task, poll for completion, then fetch results:

```bash
# 1. Submit a natural-language task (returns <task-id>)
browser4-cli agent run "Find the top 5 products and their prices on this page"

# 2. Poll until complete
browser4-cli agent status <task-id>
# Look for: "processState": "done" or "isDone": true

# 3. Get the result
browser4-cli agent result <task-id>
```

**Note:** `agent run` is asynchronous. Submit with `agent run`, then use `agent status` and `agent result` to track completion and fetch output.

**Polling with `isDone`:** The JSON from `agent status` includes `isDone: true` when finished. Shell scripts can parse this:
```bash
while true; do
  done=$(browser4-cli agent status <task-id> | grep -o '"isDone" *: *true')
  [ -n "$done" ] && break
  sleep 2
done
browser4-cli agent result <task-id>
```

**Status codes reference:**

| statusCode | processState | Meaning |
|-----------|-------------|---------|
| (null) | `"created"` | Queued, not yet picked up |
| 102 | `"in_progress"` | Agent is actively working |
| 200 | `"done"` | Task completed successfully |
| 417 | `"done"` | Expectation failed (e.g., missing LLM key) |
| 4xx/5xx | `"done"` | Task failed — inspect `message` for details |

**CLI status labels:**
- `queued` — task submitted, waiting to start
- `processing` — agent is working on the task
- `completed` — task finished successfully (call `agent result`)
- `failed (NNN)` — task failed with HTTP status NNN

**Listing tasks:** `browser4-cli agent list` shows all tracked tasks with ID, description, started/finished times, and status.

See **[agent.md](references/agent.md)** for full details including LLM key configuration, error recovery, and `extract`/`summarize` synchronous variants.

## 7. Reference Map

Organized by task — follow the link that matches what you're trying to do:

**Start here (distilled core):** [quickstart.md](references/quickstart.md) — distilled resident quick reference (core loop, copy-paste template, key commands, snapshot vs htmlsnapshot, critical warnings); embedded in the CLI engine's system prompt — full details live in this SKILL.md.

**Interact with pages (accessibility tree & element refs):** [snapshot.md](references/snapshot.md) — `snapshot`, `snapshot grep`, `-v` viewport paging, `--auto-diff`, `-i` interactive mode, element refs

**Extract data from pages:**
[htmlsnapshot.md](references/htmlsnapshot.md) — `get`, `get all`, `query`, `grep`, `summary`, `inspect`, `export`
[x-sql.md](references/x-sql.md) — X-SQL function reference (DOM, STR, ARRAY namespaces); sub-references: [x-sql-dom-functions.md](references/x-sql-dom-functions.md), [x-sql-dom-load-select.md](references/x-sql-dom-load-select.md), [x-sql-dom-select-functions.md](references/x-sql-dom-select-functions.md), [x-sql-string-functions.md](references/x-sql-string-functions.md), [x-sql-array-functions.md](references/x-sql-array-functions.md)
[htmlsnapshot-scenarios.md](references/htmlsnapshot-scenarios.md) — end-to-end recipes; focused variants: [advanced](references/htmlsnapshot-scenarios-advanced.md), [amazon](references/htmlsnapshot-scenarios-amazon.md), [audit](references/htmlsnapshot-scenarios-audit.md), [extraction](references/htmlsnapshot-scenarios-extraction.md)
[decision-trees.md](references/decision-trees.md) — choosing extraction method, bulk/scale approach, query granularity, WebMiner, X-SQL quickstart
[quick-patterns.md](references/quick-patterns.md) — proven interact/verify/extract copy-paste recipes

**Run at scale (multiple pages/URLs):**
[crawl.md](references/crawl.md) — recursive crawling, seed-file bulk fetch, X-SQL extraction
[swarm.md](references/swarm.md) — parallel scraping across multiple browser contexts
[loop.md](references/loop.md) — repeated task execution with persistence/resume

**Manage browser state:**
[storage-state.md](references/storage-state.md) — cookies, localStorage, sessionStorage, state save/load
[browser-state-import.md](references/browser-state-import.md) — copy system browser state into Browser4-managed sessions
[webdb.md](references/webdb.md) — export cached pages, normalize URLs for database lookups
[attach.md](references/attach.md) — connect to existing Chrome/Edge via CDP
[tab-management.md](references/tab-management.md) — multi-tab workflows: tab lifecycle, GUID targeting, cross-session operations

**Manage skills and agent instructions:**
[skills.md](references/skills.md) — bundled skill files, backend skill management

**AI-powered extraction:** [agent.md](references/agent.md) — `extract`, `summarize`, `agent run|status|result`, LLM provider config

**Resilient selectors:**
[power-dom.md](references/power-dom.md) — PowerCSS `:expr()` visual-feature selectors
[css-selector-bridge.md](references/css-selector-bridge.md) — bridging snapshot refs to CSS selectors

**Configure fetching:**
[load-options-guide.md](references/load-options-guide.md) — cache control, quality requirements, interaction, portal crawling
[load-options-decision.md](references/load-options-decision.md) — choosing LoadOptions (decision tree)

**Troubleshoot:** [shell-quoting.md](references/shell-quoting.md) — avoid shell-quoting breakage for complex JS/X-SQL on Windows / Git Bash

**Manage configuration:** [config.md](references/config.md) — `config` command family: CLI defaults and server-side runtime overrides

## Installation

```
https://browser4.oss-cn-beijing.aliyuncs.com/scripts/install-browser4-cli.ps1
and install-browser4-cli.sh
```
