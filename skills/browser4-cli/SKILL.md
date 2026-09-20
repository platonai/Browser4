---
name: browser4-cli
title: "Browser Automation with browser4-cli"
description: "Drive Chrome via CDP: navigate, click, fill forms, snapshot pages, extract data and run X-SQL. Use when a task needs web testing, screenshots, scraping, or browser interaction."
allowed-tools: Bash(browser4-cli:*)
tier: decision
---

# Browser Automation with browser4-cli

Browser automation CLI for AI agents — Chrome/Chromium via CDP with accessibility-tree snapshots.

This is the **decision layer**: the core loop, the command map, and which reference to open. Every
topic links to a deep dive — load only what the task needs ([§7 Reference Map](#7-reference-map)).

### Invocation

The docs use `browser4-cli` as the generic command name. From within the Browser4
source tree use one of the following:

| Shell | Command | Notes |
|-------|---------|-------|
| PowerShell (Windows) | `./b4w.ps1 <command>` | Primary dev wrapper; builds from source if needed |
| Git Bash (Windows) | `./b4w.sh <command>` | Quotes args automatically for pwsh safety |
| Git Bash (alt) | `pwsh ./b4w.ps1 <command>` | Direct PowerShell invocation |
| Linux / macOS | `./b4w.sh <command>` | Same script works cross-platform |
| Any (installed) | `browser4-cli <command>` | After `browser4-cli install` |

> **Important:** The `$(./b4w.ps1) <command>` syntax shown in some task
> instructions does **not** work in bash — `$(…)` is command substitution, not
> invocation.  Use `pwsh ./b4w.ps1 <command>` or `./b4w.sh <command>` instead.

## 1. Core Loop

> **⚡ First-run latency:** From a source tree, the first launch builds the runtime bundle via Maven (~1–3 min, before the spinner appears) and then starts the Browser4 backend (Spring Boot + JVM, ~10s). Subsequent commands are instant — the server stays alive between invocations.

> **🖥️ Headless is the default for AI agents:** always `open --headless` unless the user explicitly asks to see the window, a human must act on the page, or the site blocked the headless browser — in that last case retry `--headed` **once** (§2 Display Mode).

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
browser4-cli htmlsnapshot get all text "<css-selector>"   # extract from the live page
```

`--stdout` prints to stdout instead of writing a file. Interactions capture an automatic
post-command snapshot; `--no-snapshot` skips that round-trip.

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

Each interactive element has a **ref** (`e5`, `e12`) — the element's Chrome DevTools Protocol backend node ID prefixed with `e` (so `e12345` refers to backend node 12345). Use refs to target elements in `click`, `fill`, `type`, `get attr`, etc.

> **Note:** `/url` fields may be **relative** — the snapshot header carries the page URL for resolution. Add `-u` / `--urls` to include link hrefs, and `-b` / `--brief` when you only need the page URL and title. For absolute URLs after redirect resolution use `htmlsnapshot get all attr "a[href]" href`.

### Ref Lifecycle

Refs are **ephemeral** — treat them as single-use handles:

- **Always re-snapshot after interactions:** `click`, `fill`, `type`, `press`, `check`, `uncheck`, `select`, `hover`, `drag`, `dblclick`.
- **Definitely re-snapshot after page/context changes:** `goto`, `reload`, tab switches, or clicks that navigate.
- **Interactions auto-scroll** the target into view, so a later `snapshot -v 0` can start mid-page (nonzero `hiddenTopHeight`) — `eval "window.scrollTo(0,0)"` or capture `-v all` when documenting the top of a page.

**Safe loop: interact → re-snapshot → use new refs.** Details: [snapshot.md](references/snapshot.md).

### Output Modes

- **Default** — human-readable output on stdout.
- **`--json`** — single-line envelope `{status, command, output[, error]}` for structured commands (`tab-list`, `htmlsnapshot get`, `htmlsnapshot query`, `eval`). **Exception:** `snapshot` stays YAML and warns on stderr instead.
- **File output** — `extract` and `summarize` save to a timestamped file in `.browser4-cli/snapshot/` and print a link; add `--stdout` (or `--raw`) to print the payload. With `extract --schema` the fields are plain top-level JSON — no envelope to parse.
- **`--show-tip` / `-tip`** — rotating tips on stderr (suppressed by default). **`--quiet` / `-q`** — suppress normal output.

### Display Mode (Headless vs Headed)

| Mode | Flag | Window | Use case |
|------|------|--------|----------|
| **Headless** | `--headless` | No GUI window | **Default for AI agents** — scraping, automation, CI/CD, servers |
| **Headed** | `--headed` | Visible window | Debugging, demonstration, a human must act, or the one-shot bot-detection retry |

Use `--headed` for exactly three reasons: (1) the user explicitly asks for a visible browser; (2) a human must act on the page (login, CAPTCHA, QR code, 2FA); (3) **anti-bot escalation**.

**Anti-bot escalation — retry once:** when a page looks blocked (CAPTCHA/verification widget, Cloudflare/DataDome/Akamai/PerimeterX challenge, Google `/sorry/`, "unusual traffic" / "access denied", or an implausibly empty body), `close` then `open --headed` **with the same `-s <name>`** so profile and cookies survive, retry the same step **once**, and **tell the user the mode was switched**. If the headed retry is blocked too, stop — the block is fingerprint/IP-level: prefer `attach --cdp` / `attach --extension` (a real logged-in profile), raise `--interact-level`, or report the site as unreachable. GUI-less environments (CI, Docker) degrade `--headed` to headless — say the retry ran headless instead of claiming a visible window.

The display mode is **fixed at session creation**: `goto` cannot change it and `open --headed` on a live session only warns on stderr. To change modes, `close` first (`open --fresh` closes and reopens). Full decision guide: [browser-modes.md](references/browser-modes.md).

### Sessions

Named sessions isolate browser state (cookies, localStorage, tabs) in a **dedicated browser profile directory** keyed by the session id, so reopening restores that profile. (Exception: `--profile-mode TEMPORARY` has no pinned directory.) `goto` auto-opens/reconnects, so session management is rarely manual.

> **Concurrent runs — always pass `-s <name>`:** the unnamed DEFAULT session is a singleton shared by every invocation that omits `-s` (last writer wins), so parallel agents navigate each other's pages. Give each run its own `-s job-42`.

`list` shows a **"Next open"** column: **Reuse** (reconnects to the live session) or **Refresh** (stale/missing → fresh window). State lives in `~/.browser4`, falling back to `./.browser4-cli-state` when unwritable; override with `BROWSER4_CLI_STATE_DIR` / `BROWSER4_RUNTIME_DIR`.

### Configuration

Persistent CLI defaults live in `~/.browser4/config.json` (honours `BROWSER4_CLI_STATE_DIR`). They are global fallbacks — an explicit flag or environment variable always wins per invocation.

| Key | Purpose | Overridden by |
|-----|---------|---------------|
| `server` | Default Browser4 server URL | `--server` / `BROWSER4_CLI_SERVER` |
| `timeout` | Default HTTP timeout (seconds) | `--timeout` |
| `proxy` | Default download proxy URL | `--proxy` |
| `session` | Default session name | `-s` / `--session` / `BROWSER4_CLI_SESSION` |

```bash
browser4-cli config                       # list values + config file path
browser4-cli config set server http://localhost:8182
browser4-cli config set timeout 45        # positive integer; 0 and unknown keys are rejected
browser4-cli config delete session        # reset a key to its default
```

Use the spaced form (`config get server`), not `config-get server`.

### Tabs

```
browser4-cli tab-list                            # index, GUID, title, URL (no auto-snapshot)
browser4-cli tab-new [url]                       # new tab; the index is chosen by Chrome
browser4-cli tab-select <index> | --guid <guid>
browser4-cli tab-close [index] | --guid <guid>   # no argument = current tab
```

- `tab-select` / `tab-new` change the active page context — run `snapshot` before using refs.
- Closing the last tab makes Chrome create a replacement (the CLI prints a stderr note); `tab-list` still shows 1 tab.
- `--json tab-list` (or `tab-list --json`) returns `{status, command, output:{tabs:[…, "active"], count}}`.
- Extension-attached sessions have their own quirks — delayed close confirmation, a fresh tab scope per re-attach, `chrome:`-prefixed GUIDs. See [tabs.md](references/tabs.md).

## 3. Command Map

| Command family | Purpose | When to use | Full reference |
|---------------|---------|-------------|----------------|
| `goto`, `open`, `close`, `close-all`, `reload` | Navigation & session management | Every session starts here; `close-all` cleans up every session | — |
| `snapshot` | Capture accessibility tree (AXTree) with element refs | **Page structure & interaction** — find elements to click, fill, etc. Use `snapshot` when you need refs (e5, e36) to interact with. | [snapshot.md](references/snapshot.md) |
| `snapshot grep` | Search the page's AX tree with regex (live, no prior capture) | Find elements by text or pattern. Patterns are **Rust regex** — `\|` is alternation, and a literal `$` is safest written `[$]` (`-i`, `-A/-B/-C`, `-F`, `-v`, `-c`, `-l` supported) | — |
| `click`, `dblclick`, `drag`, `hover`, `mousemove`, `fill`, `type`, `press`, `select`, `check`, `generate-locator` | Page interaction | Form filling, button clicks, mouse actions, navigation. Clicks/hovers move the pointer onto the element first; `mousemove 5 5` clears a lingering `:hover`. `type --method auto\|chars\|exec` (needs a target ref) bulk-inserts long (>150 chars) or multi-line text in one `execCommand('insertText')` instead of typing per character | — |
| `upload <ref> <file> [file...]` | Upload local files to a page file input | Send attachments/photos/documents to an `<input type="file">`; the target must be a file input, paths must be readable on the machine running the browser | [upload.md](references/upload.md) |
| `dialog-accept`, `dialog-dismiss` | Native JS dialog handling | After clicking buttons that trigger alert/confirm/prompt — the triggering click parks server-side until the dialog is handled, so run the dialog command in a **separate** invocation. `dialog-accept "text"` fills a prompt; `click --auto-dismiss-dialogs <ref>` auto-accepts in one step | — |
| `htmlsnapshot get`, `get all` | Extract `text` / `textcontent` / `html` / `attr` via CSS selectors from the **live page** (no prior capture) | **Page content & text extraction** — get article text, headings, attributes. Prefer `textcontent` when `text` looks truncated (CSS overflow clips `text`) | [htmlsnapshot.md](references/htmlsnapshot.md) |
| `get <mode> <selector> [name]` | **Live-DOM single-element read** (`text`, `html`, `box`, `styles`, `property`, `attr`) — capture-free, works on the current live page with refs or CSS selectors (`--raw` keeps text verbatim) | **Post-interaction verification & quick reads** — "did the submit work?" without a capture round-trip. Value contract: a matched element returns its value — or `""` when the attribute/property is absent; `null` means the selector matched nothing; an unresolvable `eN` ref errors explicitly | — |
| `htmlsnapshot query` | X-SQL queries for structured extraction | Multi-field, filtered, sorted data | [x-sql.md](references/x-sql.md) |
| `eval` | Execute JavaScript in the page (`--await` for Promises/fetch, `--wait-selector` for late-rendered content, `--file`/`--stdin`/`--base64` to dodge shell quoting) | Live DOM access, complex transforms | [eval.md](references/eval.md) |
| `eval --ref` | Execute JS scoped to a specific element | Element property extraction (text, attrs, styles) | **⚠️ Expression MUST be an arrow function: `element => element.textContent`** |
| `extract`, `summarize`, `agent run` | AI-powered extraction | Natural language extraction (needs LLM key) | [agent.md](references/agent.md) |
| `crawl` | Recursive crawling + bulk extraction | Multi-page traversal, seed-file processing | [crawl.md](references/crawl.md) |
| `swarm` | Parallel scraping across browser contexts | High-throughput extraction | [swarm.md](references/swarm.md) |
| `loop` | Repeated task execution with persistence | Monitoring, scheduled checks | [loop.md](references/loop.md) |
| `state-save`, `state-load`, `cookie-*`, `*-storage-*` | Browser storage management | Auth state reuse, cookie manipulation | [storage-state.md](references/storage-state.md) |
| `attach` | Connect to existing Chrome/Edge via CDP | Debug live browser, reuse auth — after attaching, check the printed actual browser; a ⚠ warning flags a channel mismatch | [attach.md](references/attach.md) |
| `webdb export`, `webdb normalize` | Export cached pages, normalize URLs to database keys | Post-crawl content extraction, URL key lookup | [webdb.md](references/webdb.md) |
| `skills`, `skills get`, `skills path`, `skills unpack` | Bundled AI agent skill files | Refresh agent instructions, unpack skill files | [skills.md](references/skills.md) |
| `skill-list`, `skill-info`, `skill-install`, `skill-uninstall`, `skill-reload` | Backend skill management | Install/manage server-side skills | [skills.md](references/skills.md) |
| `screenshot`, `scroll`, `wait`, `resize` | Visual capture & viewport control | Screenshots, viewport sizing, scroll control | — |
| `tab-list`, `tab-new`, `tab-select`, `tab-close` | Tab management | Multi-tab workflows, session-scoped tab operations | [tabs.md](references/tabs.md) |
| `config` | Persistent CLI defaults (server, timeout, proxy, session) | Set default server URL, timeout, proxy, or session name | — |
| `batch` | Run several commands in one invocation | Scripted multi-step flows, fewer round-trips | [quickstart.md](references/quickstart.md) |
| `status`, `doctor`, `doctor log`, `doctor metrics` | Backend/process diagnostics and logs | Server not ready, startup failures, log/metric inspection | [quickstart.md](references/quickstart.md) |
| `console`, `cdp`, `pdf`, `page-info`, `go-back`, `go-forward`, `keydown`, `keyup`, `mousedown`, `mouseup`, `mousewheel`, `snapshot list`, `snapshot clean`, `crawl status\|result\|cancel\|clear\|list`, `swarm submit\|status\|result\|list\|close`, `chat`, `session-default`, `delete-data`, `kill-all`, `stop`, `uninstall`, `plugin-*` | Remaining command families (not covered here) | Discover with `browser4-cli help` / `browser4-cli help <command>` | — |
| `experience save`, `experience query`, `experience list`, `experience deep learn` | Progressive experience memory | Reuse selectors, extraction patterns and blocker awareness across sessions — see the sibling skill | [browser4-experience](../browser4-experience/SKILL.md) |

### Refreshing This Skill

The `skills` command retrieves bundled skill content that always matches the installed CLI version. Use it to get current instructions rather than relying on cached copies:

```bash
browser4-cli skills                         # List all bundled skills
browser4-cli skills get browser4-cli        # Print this SKILL.md
browser4-cli skills get browser4-cli --full # Include all reference files
browser4-cli skills path                    # Print skills directory path
browser4-cli skills unpack                  # Unpack bundled skill files to disk
```

Skill files are unpacked automatically during `browser4-cli install` (and refreshed by `upgrade`);
unchanged files are skipped, so re-running is cheap. `install` / `upgrade` also copy the bundled
skills into `~/.agents/skills` so agents (e.g. Codex) load them automatically. Overrides:
`BROWSER4_SKILLS_DIR`, `BROWSER4_AGENTS_SKILLS_DIR`.

## 4. Choosing an Approach

### 4a. snapshot vs htmlsnapshot

| | `snapshot` | `htmlsnapshot` |
|---|---|---|
| **What it captures** | Accessibility tree (AXTree) — semantic roles, names, refs | Raw HTML DOM — full text content |
| **Primary use** | **Interaction** — get element refs for click, fill, type | **Extraction** — get article text, data, attributes |
| **Output** | YAML tree with `[ref=e5]` handles | Text/HTML/JSON via CSS selectors |
| **Key commands** | `snapshot`, `snapshot grep`, `click <ref>` | `htmlsnapshot get`, `query`, `inspect` |

**Rule of thumb:** to **interact** with elements → `snapshot`. To **read content** → `htmlsnapshot`.

> **⚠️ htmlsnapshot reads the LIVE page — no prior capture required.** `get` / `get all` /
> `inspect` / `summary` / `grep` / `export` and `query` all serialize the **current live DOM of the
> active tab**, falling back to the page store / a fresh capture only when there is no usable live
> document (about:blank, non-http(s), evaluation failure). `htmlsnapshot` (capture) is optional — it
> returns page metadata and stores an archived copy. The real precondition is a **loaded page**: if
> a read comes back empty, check the selector and the URL. "No HTML snapshot found" is a CLI hint
> printed when `inspect` finds 0 matches with the default `:root` selector (exit code stays 0) — not
> an error. Per-command matrix: [htmlsnapshot.md](references/htmlsnapshot.md).

### 4b. Decision Tree

```
Need to extract data from a page?
├─ Need to interact first (click, fill, scroll)?
│  → snapshot + refs, then extract from the live DOM (reads need no capture)
├─ Live-DOM single-field read without a capture (post-interaction verify, quick read)?
│  → get text "<selector>" / get attr "<selector>" href
├─ Page has JS-updated content (form submit, SPA route change)?
│  → reads already see the live DOM; use eval --json (--await, --wait-selector) for arbitrary JS
├─ Static page, one field? → htmlsnapshot get text "<selector>"
├─ Static page, one field, ALL matches? → htmlsnapshot get all text "<selector>"
├─ Don't know the right CSS selector? → htmlsnapshot inspect  (list/grid pages)
│                                      → htmlsnapshot summary (detail/single-block pages)
├─ Static page, multiple correlated fields (title+price+url per item)?
│  → htmlsnapshot query with X-SQL DOM_LOAD_AND_SELECT
├─ Natural language ("find the product price")? → extract (needs LLM key)
└─ High volume, many pages? → crawl or swarm with --sql
```

**Warning:** several `get all` calls produce unaligned arrays (different lengths, different order).
For correlated fields use `query` with `DOM_LOAD_AND_SELECT` scoped to a parent container.

### 4c. X-SQL Essentials

```sql
SELECT
  DOM_FIRST_TEXT(DOM, 'h2')     AS title,
  DOM_FIRST_ATTR(DOM, 'a[href]', 'href') AS url
FROM DOM_LOAD_AND_SELECT(@url, '.product-card')
```

| Rule | Correct | Wrong |
|------|---------|-------|
| CSS selectors use **single** quotes (SQL string literals) | `'h2'`, `'.price'` | `"h2"` (SQL identifier) |
| `@url` placeholder is **unquoted** | `@url` | `'@url'` (literal string) |
| FROM source is always `DOM_LOAD_AND_SELECT` | `DOM_LOAD_AND_SELECT(@url, '…')` | Any other table name |
| No CTEs (`WITH`), no `JOIN`, no subqueries | simple `SELECT … FROM …` | `WITH t AS (…) SELECT …` |

Save the query to a file (`--sql @query.sql`) to avoid shell quoting; the default output is the raw
JSON envelope (`--format table` for humans, `--result-only` for just the resultSet). Exit code is
nonzero on a server error envelope (`417`, `5xx`) but **0** on a `200` with an empty resultSet.
Discover selectors with `htmlsnapshot inspect` / `summary` first. Pitfalls — H2 type errors needing
`CAST(... AS DOUBLE)`, `DOM_*_IMG` helpers ignoring `:expr(...)`, `DOM_FIRST_HREF` needing a
tag-qualified selector — and the full function catalog: **[x-sql.md](references/x-sql.md)**.
Copy-paste template and expanded trees: [decision-trees.md](references/decision-trees.md).

### 4d. Bulk & Scale

- Single list page → `htmlsnapshot query` with `DOM_LOAD_AND_SELECT`.
- Known URL list → `crawl --seed-file urls.txt --depth 0 --sql @query.sql` (add `--parallel 8`; each unit gets its own tab).
- Crawl from a start URL → `crawl <url> --out-link-selector "…" --depth N`.
- High throughput → `swarm create` → `swarm query --seed-file …`; `swarm query` returns rows, so stage the fetched corpus with `webdb export "url1,url2" <dir>` (URLs **comma-separated**).
- Repeated monitoring → `loop -i 3600 -- eval "…"`.

Details: [crawl.md](references/crawl.md) · [swarm.md](references/swarm.md) · [loop.md](references/loop.md) · [webdb.md](references/webdb.md)

### 4e. Structuring Downloaded Pages (WebMiner)

`browser4-cli webminer install`, then `webminer all ./html-pages/` — local ML clustering (no LLM
tokens) that produces an interactive HTML report plus spreadsheets. The SMILE free tier covers
small-to-medium corpora (< 1,000 pages); the Spark tier scales to production. Requires JDK 17+.
Full reference: **[web-miner/SKILL.md](../browser4-web-miner/SKILL.md)**.

## 5. Critical Warnings

> **Refs are single-use.** Re-snapshot after any interaction, and always after `goto`, `reload`, and tab switches. Never reuse a ref across navigations.

> **Selectors break when sites change their HTML.** Discover them with `htmlsnapshot inspect` / `summary` before extracting. Treat scenario examples as patterns, not copy-paste recipes.

> **Shell quoting on Windows.** Complex JS/SQL with nested quotes breaks inline: prefer `--sql @file.sql`, `--sql-stdin`, `--sql-base64`, `eval --file` / `--stdin` / `--base64`; for `htmlsnapshot inspect` use `@file`, `--stdin`, or `--selector-base64`. **On PowerShell always quote `@file` paths (`--sql "@query.sql"`)** — an unquoted `@` is the splatting operator. Never inline `--sql "…"` with double-quoted CSS selectors. See [shell-quoting.md](references/shell-quoting.md).

> **Don't cat snapshot files** — they can exceed 256KB, and `--stdout` can dump 63KB+ trees. Bound the capture with `-v 0`, `--depth`, `--selector`, `--no-boxes`, or use `snapshot grep` / `htmlsnapshot`. `snapshot --stdout`, `htmlsnapshot get html` and `grep` paginate at 2000 lines by default (`--all` / `--page-size 0` disables); `get all …` and `text`/`textcontent` reads print in full.

> **`eval --ref` requires an arrow function:** `element => element.textContent`. Writing `element.textContent` (or `this.textContent`) returns `null` — the #1 mistake with element-scoped eval.

> **Sandboxed environments:** the backend writes its logs inside the runtime bundle, so a workspace-only sandbox makes `open`/`goto` hang until the startup timeout with `Access denied` (see the startup log path under `🧾 Details`). Fix before the first launch: point `BROWSER4_RUNTIME_DIR` and `BROWSER4_CLI_STATE_DIR` at writable directories.

> **`get` value contract:** a matched element returns its value — or `""` when the attribute/property is absent; `null` means the selector matched nothing; an unresolvable `eN` ref fails with an explicit error.

## 6. Recipes & Deep Dives

Copy-paste pairs for the common flows — form fill, `snapshot grep`, mouse/drag, dialog handling,
verify-after-interaction, single-field and bulk extraction, PowerCSS, agent tasks:

- [quick-patterns.md](references/quick-patterns.md) — the recipe catalog.
- [quickstart.md](references/quickstart.md) — the compressed resident quick reference.
- [decision-trees.md](references/decision-trees.md) — extraction decision trees, expanded.
- [htmlsnapshot-scenarios.md](references/htmlsnapshot-scenarios.md) — end-to-end walkthroughs; focused variants: [advanced](references/htmlsnapshot-scenarios-advanced.md), [amazon](references/htmlsnapshot-scenarios-amazon.md), [audit](references/htmlsnapshot-scenarios-audit.md), [extraction](references/htmlsnapshot-scenarios-extraction.md).

**Resilient selectors (PowerCSS):** `div:expr(width > 400 && height > 400)` filters by computed
geometry/position/content density instead of class names, so it survives markup changes — usable in
any CSS selector and in `htmlsnapshot get` / X-SQL selectors. Feature list and operators:
[power-dom.md](references/power-dom.md); bridging refs to selectors: [css-selector-bridge.md](references/css-selector-bridge.md).

**AI-powered extraction:** `agent run "<task>"` submits an async task — poll `agent status <id>` and
read `agent result <id>`. A finished task reports `"processState": "completed"` (older payloads used
`"done"`), so **`isDone: true` is the reliable completion check**; `agent list` shows all tracked
tasks. `extract` / `summarize` are the synchronous variants. See [agent.md](references/agent.md).

## 7. Reference Map

Organized by task — follow the link that matches what you're trying to do:

**Start here (distilled / recipe references):**
[quickstart.md](references/quickstart.md) — compressed resident quick reference: core loop, key commands, snapshot vs htmlsnapshot, critical warnings
[quick-patterns.md](references/quick-patterns.md) — copy-paste recipes: sessions, form fill, mouse & dialogs, verify-after-interaction, extraction
[decision-trees.md](references/decision-trees.md) — the extraction decision trees in expanded form, plus the X-SQL quickstart template

**Interact with pages (accessibility tree & element refs):**
[snapshot.md](references/snapshot.md) — `snapshot`, `snapshot grep`, `-v` viewport paging, `--auto-diff`, `-i` interactive mode, element refs
[tabs.md](references/tabs.md) — tab lifecycle, GUID targeting, last-tab behavior, extension-session quirks
[upload.md](references/upload.md) — upload local files to a page `<input type="file">` (multi-file, `--no-snapshot`, browser-host path rules)

**Extract data from pages:**
[htmlsnapshot.md](references/htmlsnapshot.md) — `get`, `get all`, `query`, `grep`, `summary`, `inspect`, `export`
[eval.md](references/eval.md) — `eval`, `eval --ref`: live-DOM JavaScript evaluation (inline/`--file`/`--stdin`/`--base64`, `--await`, `--wait-selector`, `--json`, arrow-function rule)
[x-sql.md](references/x-sql.md) — X-SQL function reference (DOM, STR, ARRAY namespaces)
[x-sql-dom-functions.md](references/x-sql-dom-functions.md), [x-sql-dom-load-select.md](references/x-sql-dom-load-select.md), [x-sql-dom-select-functions.md](references/x-sql-dom-select-functions.md), [x-sql-string-functions.md](references/x-sql-string-functions.md), [x-sql-array-functions.md](references/x-sql-array-functions.md) — X-SQL namespace sub-references
[htmlsnapshot-scenarios.md](references/htmlsnapshot-scenarios.md) — end-to-end recipes; focused variants: [advanced](references/htmlsnapshot-scenarios-advanced.md), [amazon](references/htmlsnapshot-scenarios-amazon.md), [audit](references/htmlsnapshot-scenarios-audit.md), [extraction](references/htmlsnapshot-scenarios-extraction.md)

**Run at scale (multiple pages/URLs):**
[crawl.md](references/crawl.md) — recursive crawling, seed-file bulk fetch, X-SQL extraction
[swarm.md](references/swarm.md) — parallel scraping across multiple browser contexts
[loop.md](references/loop.md) — repeated task execution with persistence/resume

**Manage browser state:**
[storage-state.md](references/storage-state.md) — cookies, localStorage, sessionStorage, state save/load
[webdb.md](references/webdb.md) — export cached pages, normalize URLs for database lookups
[attach.md](references/attach.md) — connect to existing Chrome/Edge via CDP

**Choose how the browser runs:**
[browser-modes.md](references/browser-modes.md) — session (default / named / swarm) × display (headless / headed / SUPERVISED) × browser source (managed / `attach --cdp` / `attach --extension`), plus the headless→headed escalation when a site blocks the bot, profile mode, interact level, contexts, and their failure modes

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

**Developers:**
[development.md](references/development.md) — build the CLI from source (Rust, Java 17+)

## Installation

**Cross-platform (Node.js):**
```bash
npm install -g browser4-cli
browser4-cli install
```

**Windows (PowerShell):**
```powershell
irm https://browser4.oss-cn-beijing.aliyuncs.com/scripts/install-browser4-cli.ps1 | iex
```

**Linux / macOS (bash):**
```bash
curl -fsSL https://browser4.oss-cn-beijing.aliyuncs.com/scripts/install-browser4-cli.sh | bash
```

The bootstrap scripts also install the Browser4 backend (runtime bundle) automatically:
`browser4-cli install` on a fresh machine, or `browser4-cli upgrade` when a backend already exists.
Those two subcommands accept only `--tag` and `--force`; to install the CLI **only**, pass
`--skip-backend` (`-SkipBackend` in PowerShell) to the install script — the CLI forwards that flag
to the script internally.
