---
name: browser4-cli
description: Automates browser interactions for web testing, form filling, screenshots, and data extraction. Use when the user needs to navigate websites, interact with web pages, fill forms, take screenshots, test web applications, or extract information from web pages.
allowed-tools: Bash(browser4-cli:*)
---

# Browser Automation with browser4-cli

Browser automation CLI for AI agents.

- Chrome/Chromium via CDP with accessibility-tree snapshots, Playwright CLI compatible commands
- Built-in agent loop for autonomous agents with tool use and reasoning capabilities
- Data extraction and summarization tools for processing web content

## Installation

Installs browser4-cli globally using npm (Requires Node.js):

```shell
npm install -g browser4-cli
browser4-cli install
```

Bootstrap the native binary directly with a single command:

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

## Commands

### Core

```bash
browser4-cli open
# open and navigate right away in one step
browser4-cli open https://browser4.io
# navigate to a URL using the current active session
browser4-cli goto https://browser4.io
browser4-cli type "search query"
browser4-cli click e3
browser4-cli dblclick e7
browser4-cli fill e5 "user@example.com"
browser4-cli drag e2 e8
browser4-cli hover e4
browser4-cli select e9 "option-value"
browser4-cli check e12
browser4-cli uncheck e12
browser4-cli snapshot
browser4-cli snapshot --filename=after-click.yaml
browser4-cli eval "document.title"
browser4-cli eval --file=script.js
browser4-cli eval --file=script.js e5
browser4-cli get text e5
browser4-cli get html "#main"
browser4-cli get box e5
browser4-cli get styles e5
browser4-cli get property e5 value
browser4-cli get attr e5 href
browser4-cli scroll down 300
browser4-cli scroll up 200
browser4-cli wait 1000
browser4-cli wait e5
browser4-cli wait --text="Success"
browser4-cli wait --url="**/dashboard"
browser4-cli wait --load=networkidle
browser4-cli wait --fn="document.readyState === 'complete'"
browser4-cli resize 1920 1080
browser4-cli close
```

### Navigation

```bash
browser4-cli goto <url>         # Navigate to a URL, auto-opening or refreshing the session if needed
browser4-cli go-back
browser4-cli go-forward
browser4-cli reload
```

`goto` automatically reuses the current active session when possible, and auto-opens a fresh session when the saved session is missing or stale. If the backend had been stopped, `goto` starts or reconnects through the current slot before navigating.

### Keyboard

```bash
browser4-cli press Enter
browser4-cli press ArrowDown
browser4-cli keydown Shift
browser4-cli keyup Shift
```

### Mouse

```bash
browser4-cli mousemove 150 300
browser4-cli mousedown
browser4-cli mousedown right
browser4-cli mouseup
browser4-cli mouseup right
browser4-cli mousewheel 0 100
```

### Screenshots

```bash
browser4-cli screenshot
browser4-cli screenshot e5
browser4-cli screenshot --filename=page.png
```

### Element Data Extraction (get)

Extract data from a page element. The first argument is the mode, the second is a CSS selector or snapshot ref (`e5`).

```bash
browser4-cli get text e5            # visible text content
browser4-cli get html "#main"       # innerHTML of the element
browser4-cli get box e5             # bounding box (x, y, width, height)
browser4-cli get styles e5          # all computed CSS styles as JSON
browser4-cli get property e5 value  # JavaScript property value
browser4-cli get attr e5 href       # HTML attribute value
```

- Output distinguishes `null` (element/attribute missing), `""` (exists but empty), and normal values.
- `property` and `attr` modes require a third positional argument (the property/attribute name).
- Use `get attr <ref> id` and `get attr <ref> class` to discover identifying attributes from a snapshot ref, then use those values as CSS selectors with `domsnapshot get` (see [references/css-selector-bridge.md](references/css-selector-bridge.md)).

### Scroll

Scroll the page in a given direction by the specified number of pixels.

```bash
browser4-cli scroll down 300   # scroll down 300px
browser4-cli scroll up 200     # scroll up 200px
browser4-cli scroll right 150  # scroll right 150px (horizontal)
browser4-cli scroll left 100   # scroll left 100px (horizontal)
```

### Wait

Wait for a condition before proceeding. Without options, the positional argument is interpreted as a CSS selector to wait for, or as milliseconds if numeric.

```bash
browser4-cli wait 1000                   # wait 1 second (fixed delay)
browser4-cli wait e5                     # wait for element to appear
browser4-cli wait --text="Success"       # wait for text to appear on page
browser4-cli wait --url="**/dashboard"   # wait for URL to match glob
browser4-cli wait --load=networkidle     # wait for page load (networkidle or domcontentloaded)
browser4-cli wait --fn="document.querySelector('.loaded') !== null"  # wait for JS expression
```

### Tabs

Tab commands use **zero-based indices** (position in the tab list, starting at 0).
Run `tab-list` first — each tab shows its `index` (e.g., `index=0` for the first tab).

```bash
browser4-cli tab-list
browser4-cli tab-new
browser4-cli tab-new https://example.com/page
browser4-cli tab-close
browser4-cli tab-close 2
browser4-cli tab-select 0
```

### Storage

```bash
browser4-cli state-save
browser4-cli state-save auth-state.json
browser4-cli state-load auth-state.json
browser4-cli cookie-list
browser4-cli cookie-list --domain=example.com
browser4-cli cookie-get session_id
browser4-cli cookie-set session abc123 --path=/
browser4-cli cookie-delete session_id
browser4-cli cookie-clear
browser4-cli localstorage-list
browser4-cli localstorage-get theme
browser4-cli localstorage-set theme dark
browser4-cli localstorage-delete theme
browser4-cli localstorage-clear
browser4-cli sessionstorage-list
browser4-cli sessionstorage-get step
browser4-cli sessionstorage-set step 3
browser4-cli sessionstorage-delete step
browser4-cli sessionstorage-clear
```

### Notes

`state-save` writes a JSON file containing cookies plus the active origin's `localStorage`.
`state-load` restores that JSON into the current session and auto-opens a session first when needed.
`cookie-list` and `cookie-get` read from the current session's cookie jar.
`cookie-set` defaults to the current page URL when `--domain` is omitted.
The `localstorage-*` and `sessionstorage-*` commands operate on the active page origin in the current session.

## Open parameters

```bash
# Open with a URL (defaults to headed mode)
browser4-cli open https://browser4.io

# Force headed mode (visible browser window)
browser4-cli open --headed https://browser4.io

# Force headless mode (no visible window)
browser4-cli open --headless https://browser4.io

# Open a named session
browser4-cli -s=mysession open https://browser4.io

# Close the browser
browser4-cli close
```

- `--headed` forces a visible browser window (useful for debugging or when screenshots need rendering).
- `--headless` forces headless mode (no visible window). If both are passed, `--headless` takes priority.
- Use `-s=<name>` to target a named session instead of the default slot.

## Snapshots

After commands that modify browser state, browser4-cli usually provides a snapshot of the current browser state.

```bash
> browser4-cli goto https://example.com
### Page
- Page URL: https://example.com/
- Page Title: Example Domain
### Snapshot
[Snapshot](.browser4-cli/snapshot/page-2026-02-14T19-22-42-679Z.yml)
```

You can also take a snapshot on demand using `browser4-cli snapshot` command.

If `--filename` is not provided, a new snapshot file is created with a timestamp. Default to automatic file naming, use `--filename=` when artifact is a part of the workflow result.

## DOM Snapshot

The `domsnapshot` family of commands operates on a **static DOM snapshot** — the raw HTML of the current page parsed into a queryable document object model. Unlike the interactive `snapshot` command (which captures accessibility-tree refs for `click`/`type`/`fill`), `domsnapshot` extracts structured data from the DOM using CSS selectors and X-SQL queries.

```bash
browser4-cli domsnapshot                           # capture a fresh static DOM snapshot
browser4-cli domsnapshot get <field> [selector] [name]  # extract text/html/attr via CSS selectors
browser4-cli domsnapshot query [url] --sql <query>       # run X-SQL against the DOM
browser4-cli domsnapshot export [--file <path>]         # save snapshot HTML to a file
```

See **[references/domsnapshot.md](references/domsnapshot.md)** for the full command reference, field tables, X-SQL query examples, and the comparison with interactive `snapshot`.

### Bridging Snapshot Refs to CSS Selectors

`domsnapshot get` and `domsnapshot query` require CSS selectors — they reject interactive snapshot refs (`e5`). To bridge from a compact interactive snapshot to a `domsnapshot` query **without ever reading the full DOM snapshot**, use one of these approaches:

1. **Construct from snapshot info** — the interactive snapshot already shows tag, attributes, and text:
   `@e10 [input type="email"] placeholder="Email"` → use `[placeholder="Email"]`
2. **Extract attributes from the ref** — `browser4-cli get attr e5 id` or `get attr e5 class`
3. **Generate a unique selector** — `browser4-cli generate-locator e5`

```bash
# Tier 1 example: construct selector from snapshot output
browser4-cli snapshot
# @e13 [span class="price"] "$19.99"
browser4-cli domsnapshot get text ".price"

# Tier 2 example: discover class from ref, then query
CARD_CLASS=$(browser4-cli get attr e11 class)
browser4-cli domsnapshot query --sql "
  SELECT dom_first_text(dom, '.price') AS price
  FROM load_and_select(@url, '.${CARD_CLASS}')
"
```

> **Core rule:** Never `cat` the full snapshot file or use `domsnapshot export` just to read it. Always use targeted `domsnapshot get` or `domsnapshot query` to extract only the data you need.

Full reference: **[references/css-selector-bridge.md](references/css-selector-bridge.md)** — three-tier approach, `generate-locator` command, and anti-patterns to avoid.

## Browser Sessions

```bash
# create new browser session named "mysession"
browser4-cli -s=mysession open example.com
browser4-cli -s=mysession click e6
browser4-cli -s=mysession close  # stop a named browser
browser4-cli list
# Close all sessions, but keep Browser4.jar / the Browser4 backend running
browser4-cli close-all
# Explicitly stop Browser4.jar / the Browser4 backend and kill Browser4 browser processes
browser4-cli kill-all
```

`browser4-cli list` shows both the saved session state (`Active`, `Stale`, or `Unknown`) and what the
next `browser4-cli open` will do for each slot (`Reuse` or `Refresh`).

## Advanced commands

Some advanced commands are intentionally omitted from the global `browser4-cli help` summary.
Query them explicitly when needed:

```bash
browser4-cli help batch
browser4-cli help extract
browser4-cli help swarm create
```

## Swarm CLI

Browser4 CLI offers a high-level interface for complex, multi-step browser tasks beyond the standard single-action commands:

**Swarm CLI** (`swarm <subcommand>`) — Orchestrate parallel scraping and structured data extraction across multiple browser contexts. Designed for high-throughput jobs like refreshing a curated URL list, supervised fan-out browsing, or repeatable selector-based scraping with explicit output artifacts. Supports X-SQL for structured queries against loaded webpages.

| Interface | Model | Use when |
|---|---|---|
| Standard commands | Single action per invocation | You know the exact refs/selectors and want precise control |
| Swarm CLI | Parallel contexts + X-SQL queries | High-throughput scraping, structured extraction across many pages |

## Swarm workflows

The `swarm` subcommands are intended for a swarm scrape workflow where one CLI
session coordinates multiple backend browser contexts.

Use the spaced `swarm <subcommand>` form:

```bash
browser4-cli swarm create
browser4-cli swarm submit https://example.com
browser4-cli swarm query "https://..." --sql @query.sql
```

### Command overview

| Command | Purpose |
|---|---|
| `swarm create` | Create a swarm scrape session |
| `swarm submit <url>` | Submit URLs or raw X-SQL for scraping |
| `swarm query <url>` | Run an X-SQL query against a loaded webpage |
| `swarm status <id>` | Poll a job by task ID |
| `swarm result <id>` | Fetch a completed job's result |

### URL scraping

Recommended lifecycle:

```bash
# 1) create a swarm scrape session with backend capability hints
browser4-cli swarm create \
  --profile-mode=TEMPORARY \
  --max-open-tabs=12 \
  --max-browser-contexts=3 \
  --display-mode=HEADLESS

# 2) submit one direct URL plus a seed file as scrape jobs
browser4-cli swarm submit https://example.com/direct \
  --seed-file=./swarm-seeds.txt \
  --deadline=2026-03-30T00:00:00Z \
  --expires=1d \
  --refresh \
  --store-content

# 3) poll and fetch the result
browser4-cli swarm status scrape-task-4
browser4-cli swarm result scrape-task-4
```

### X-SQL query submissions

Use `swarm query` to run X-SQL queries that extract structured data from loaded webpages. The `--sql` flag is **required**, and `@url` serves as a placeholder for the target URL. Only simple `SELECT ... FROM load_and_select(@url, cssQuery)` queries are supported (no CTEs, subqueries, `EXPLODE`, or joins).

See **[references/swarm.md](references/swarm.md#swarm-query)** for inline/file-based query examples, the arguments table, extraction functions reference, and seed file usage.

## Installation

### Global Installation (recommended)

Installs the native Rust binary:

```bash
npm install -g browser4-cli

# optional but recommended for standalone backend startup
browser4-cli install

# install a specific version
browser4-cli install --tag=v4.9.3

# force reinstall even if already installed
browser4-cli install --tag=4.9.3 --force
```

After installation, use `browser4-cli`.

## Error handling

- Commands that require a connection to the Browser4 backend (such as `open`, `goto`, `snapshot`, `click`) will fail with a non-zero exit code if the backend is unreachable. Check that the backend is running with `browser4-cli list`.
- `eval` returns a non-zero exit code when the JavaScript expression throws or cannot be evaluated.
- `snapshot` returns a non-zero exit code when the page is not ready or the accessibility tree cannot be captured.
- When a session goes stale (browser closed externally or backend restarted), `open` and `goto` automatically refresh it. Running commands against a stale session before refreshing will fail — prefer letting `goto` auto-open rather than manually managing session state.

## Example: Form submission

```bash
browser4-cli open https://example.com/form
browser4-cli snapshot

browser4-cli fill e1 "user@example.com"
browser4-cli fill e2 "password123"
browser4-cli click e3
browser4-cli snapshot
browser4-cli close
```

## Example: Multi-tab workflow

```bash
browser4-cli open https://example.com
browser4-cli tab-new https://example.com/other
browser4-cli tab-list
browser4-cli tab-select 0
browser4-cli snapshot
browser4-cli close
```

## Specific tasks

* **DOM Snapshot** [references/domsnapshot.md](references/domsnapshot.md)
* **CSS Selector Bridge** [references/css-selector-bridge.md](references/css-selector-bridge.md)
* **Smarm command** [references/swarm.md](references/swarm.md)
* **Storage state (cookies, localStorage)** [references/storage-state.md](references/storage-state.md)
* **X-SQL** [references/x-sql.md](references/x-sql.md)
