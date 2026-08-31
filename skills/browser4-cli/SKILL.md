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
- **`--show-tip` / `-tip`** — show a relevant rotating tip on stderr after each successful command (suppressed by default).
- **`--json`** — single-line JSON envelope on stdout for structured commands (`tab-list`, `htmlsnapshot get/query`, `eval`). **Exception:** `snapshot` stays YAML-focused and warns on stderr instead of returning JSON.
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

Named sessions isolate browser state (cookies, localStorage, tabs). Use `-s <name>` to target a named session; `goto` auto-opens/reconnects — you rarely need to manage sessions manually. `list` shows a "Next open" column: **Reuse** (reconnects to the active window) or **Refresh** (opens fresh — session stale or missing). Session state lives in `~/.browser4` by default; when unwritable (sandboxed shells) the CLI falls back to `./.browser4-cli-state` with a warning — set `BROWSER4_CLI_STATE_DIR` / `BROWSER4_RUNTIME_DIR` to explicit writable paths to silence it.

### Configuration

CLI defaults (`config.json`) and server-side runtime overrides are managed by the `config` command family — see **[config.md](references/config.md)** for the full reference:

```bash
browser4-cli config                              # List all values + config file path
browser4-cli config set server http://localhost:8182
browser4-cli config set agent.llm.maxRequestTokens 800000   # server-side runtime override
```

### Tab Management

Tab commands (`tab-list`, `tab-new`, `tab-select`, `tab-close`, `window new`) scope to a session. **Re-snapshot after `tab-select`** — tab switches change the active page context. See **[tab-management.md](references/tab-management.md)** for the tab lifecycle, GUID-based targeting, cross-session operations, and extension-session quirks.

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

- **4a. Extraction method:** interact → `snapshot` + refs; read content → `htmlsnapshot`; one-step article (no selectors, no LLM) → `htmlsnapshot readability`; live DOM → `eval --json`; natural language → `extract`; many pages → `crawl`/`swarm`. `htmlsnapshot get`/`inspect`/`summary`/`grep`/`export`/`readability` need a prior capture; `query` and `readability <url>` fetch independently (`DOM_LOAD_AND_SELECT(@url, ...)`).
- **4b. Bulk/scale:** one list page → `query`; known URLs → `crawl --seed-file`; follow links → `crawl <url> --depth N`; parallel → `swarm`; scheduled → `loop`.
- **4c. Query granularity:** `get` = first match; `get all` = all matches (unaligned arrays — don't combine); `query` = correlated multi-field rows.
- **4d. Structuring pages (WebMiner):** `< 1,000 pages` → `webminer all` (free, local, zero tokens); `> 1,000 pages` → WebMiner Commercial (Spark). Acquire pages first with `crawl`/`swarm`, then feed the HTML directory in.
- **4e. X-SQL quickstart:** `SELECT DOM_FIRST_TEXT(DOM,'h2') AS title ... FROM DOM_LOAD_AND_SELECT(@url, '.product-card')` — single quotes for CSS, `@url` unquoted, no JOIN/CTE/subqueries; run via `--sql @file.sql`.

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
> | `snapshot -i` | **Interactive elements only:** buttons, links, inputs, selects, textareas. Strips generic `<div>`, `<span>`, and other non-interactive containers | Simple forms, login pages, sparse pages with clear interactive controls. Reduces noise when you only need clickable/fillable elements |
> | `htmlsnapshot` | Static HTML (CSS selectors) | Content extraction (text, attributes), when you need CSS selectors instead of AX refs |
>
> **`-i` trade-off:** Interactive mode discards structural context. On e-commerce/search pages where product cards use generic `<div>` wrappers, `-i` may strip the containers you need. For these pages, prefer `--viewport 0` or use `htmlsnapshot` for CSS-based extraction.
>
> **Example — simple form page (`snapshot -i --stdout`):** shows only form fields and buttons — `# e5  textbox  "Email"  /url: /login`, `# e6  textbox  "Password"  /url: /login`, `# e7  button  "Sign In"  /url: /login` — instead of the full 200+ line tree.

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

1. **Multi-Session Workflow** — `-s <name>` isolates state; `list`/`close`/`close-all` manage sessions
2. **Interactive Form Fill** — open → snapshot → fill refs → submit → `wait --load networkidle` → verify
3. **Find Elements by Text** — `snapshot grep [-i] [-A 3 -B 1] "pattern"`
4. **Mouse Interactions** — `hover`, `dblclick`, `drag`; verify with `snapshot grep`
5. **Dialog Handling** — `dialog-accept`/`dialog-dismiss` in a separate invocation (or `click --auto-dismiss-dialogs <ref>`)
6. **Verifying Results** — `snapshot -v 0 --auto-diff --stdout` after every interaction; `generate-locator` for resilient selectors
7. **Static Data Extraction** — capture `htmlsnapshot`, then `get text`/`get attr "<css>"`
8. **Bulk Extraction (X-SQL)** — correlated fields via `--sql @query.sql` with `DOM_LOAD_AND_SELECT(@url, '.product-card')`
9. **PowerCSS** — `:expr()` visual-feature selectors; full reference in [power-dom.md](references/power-dom.md)
10. **Agent Task Lifecycle** — `agent run` (async) → `status` → `result`; or `--wait [--wait-timeout]`
11. **Agent Memory** — run-start `## Memory` recall, `memory_note`, `memory_search`/`read`/`forget`, auto-deposit

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
