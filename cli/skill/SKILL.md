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
```

Bootstrap the native binary directly with a single command:

**Windows (PowerShell):**
```powershell
irm https://browser4.oss-cn-beijing.aliyuncs.com/scripts/install-browser4-cli.ps1 | iex
```

**Linux / macOS (bash):**
```bash
curl -fsSL https://browser4.oss-cn-beijing.aliyuncs.com/scripts/install-browser4-cli.sh | bash
```

Optional backend runtime install: `browser4-cli install`

## Quick start

```bash
# install the self-contained Browser4 backend runtime (Browser4.jar + bundled JRE)
browser4-cli install
# open new browser
browser4-cli open
# navigate to a page with the current active session
browser4-cli goto https://browser4.io/
# take a snapshot
browser4-cli snapshot
# interact with the page using refs from the snapshot
browser4-cli click e15
browser4-cli type "page.click"
browser4-cli press Enter
# take a screenshot
browser4-cli screenshot
# close the browser
browser4-cli close
```

`browser4-cli open` reuses the saved session for the current slot only when the backend still reports it
as active. If the saved session is stale or missing, `open` refreshes it by creating a new session.

`browser4-cli goto` first tries to reuse the current active session. If no active session is available,
or the saved session is no longer active, it automatically starts or refreshes the session before
navigating.

When `browser4-cli install` has been run, `browser4-cli open` uses the bundled `jlink` JRE and
installed `Browser4.jar` from the CLI state directory instead of requiring a separately installed Java runtime.

## Commands

The sections below cover the standard browser workflow commands that are surfaced in the global `browser4-cli help` overview.

### Core

```bash
browser4-cli open
# open and navigate right away in one step
browser4-cli open https://browser4.io/
# navigate to a URL using the current active session
browser4-cli goto https://playwright.dev
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

### Tabs

```bash
browser4-cli tab-list
browser4-cli tab-new
browser4-cli tab-new https://example.com/page
browser4-cli tab-close
browser4-cli tab-close 2
browser4-cli tab-select 0
```

Use `browser4-cli tab-list` to obtain the current zero-based tab index before calling `tab-select` or `tab-close` with a specific target.

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
browser4-cli help console
browser4-cli help extract
browser4-cli help summarize
browser4-cli help agent run
browser4-cli help swarm create
```

## Agent and Swarm CLI

Browser4 CLI offers two high-level interfaces for complex, multi-step browser tasks beyond the standard single-action commands:

**Agent CLI** (`agent <subcommand>`) — Submit a natural-language task and let Browser4's backend AI agent plan and execute it autonomously. The agent reasons about the page, decides which actions to take, and completes the task asynchronously. Best for exploratory tasks, multi-step workflows where you don't know the exact page structure ahead of time, or delegating an entire goal to the backend.

**Swarm CLI** (`swarm <subcommand>`) — Orchestrate parallel scraping and structured data extraction across multiple browser contexts. Designed for high-throughput jobs like refreshing a curated URL list, supervised fan-out browsing, or repeatable selector-based scraping with explicit output artifacts. Supports X-SQL for structured queries against loaded webpages.

| Interface | Model | Use when |
|---|---|---|
| Standard commands | Single action per invocation | You know the exact refs/selectors and want precise control |
| Agent CLI | Natural-language task → autonomous execution | You have a goal but don't know the page structure; multi-step exploration |
| Swarm CLI | Parallel contexts + X-SQL queries | High-throughput scraping, structured extraction across many pages |

See the sections below for detailed usage of each.

## Agent task commands

Use the `agent` subcommands when you want Browser4's backend agent to execute a
natural-language task asynchronously.

Use the spaced `agent <subcommand>` form:

```bash
browser4-cli agent run "Open browser4.io and summarize the hero section"
browser4-cli agent status agent-task-1
browser4-cli agent result agent-task-1
```

Recommended lifecycle:

```bash
# 1) submit an autonomous task
browser4-cli agent run "Open browser4.io and summarize the hero section"

# 2) poll progress with the returned task id
browser4-cli agent status agent-task-1

# 3) read the final result
browser4-cli agent result agent-task-1
```

Notes:

- `agent run` returns immediately after the backend accepts the task and prints
  the generated task ID plus a ready-to-copy `agent status` follow-up command.
- `agent status` prints the backend status payload as-is. This is typically JSON
  and may include fields like `id`, `status`, `statusCode`, `processState`,
  `message`, `agentState`, `agentHistory`, and `commandResult`.
- `agent result` prints the backend result payload as-is. Depending on the
  task, that payload may be plain text or structured JSON.
- The commands are task-ID based, so they do not depend on the current saved
  CLI browser session slot.
- `agent` subcommands are advanced commands and are not supported in `batch`
  mode.
- `agent run` performs a short status probe after submission so missing LLM/API
  key configuration errors can fail fast with a clearer message.

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

Use `swarm query` to run an X-SQL query that extracts structured data from the
loaded webpage. The `--sql` flag is **required**. The query uses `@url` as a
placeholder for the target URL.

```bash
# Inline X-SQL query:
browser4-cli swarm query "https://www.amazon.com/dp/B08PP5MSVB" --sql "
  SELECT
    dom_base_uri(dom) AS url,
    dom_first_text(dom, '#productTitle') AS title,
    dom_first_slim_html(dom, 'img:expr(width > 400)') AS img
  FROM load_and_select(@url, 'body');
"

# Read query from a file:
browser4-cli swarm query "https://www.amazon.com/dp/B08PP5MSVB" --sql @query.sql

# With load options and a seed file:
browser4-cli swarm query --sql @query.sql --seed-file=./urls.txt --refresh --parse
```

Example `query.sql`:

```sql
SELECT
  dom_base_uri(dom) AS url,
  dom_first_text(dom, '#productTitle') AS title,
  dom_first_slim_html(dom, 'img:expr(width > 400)') AS img
FROM load_and_select(@url, 'body');
```

`swarm query` sends a structured JSON body to `SwarmController.query(query)`.
The `@url` placeholder is substituted with the target URL (and any load options)
server-side.

> **Tip:** `swarm submit --sql` also works as a convenience alias, but
> `swarm query` is the preferred command for X-SQL queries.

Notes:

- `swarm submit` accepts a positional URL, `--seed-file`, or both.
- `swarm query` accepts `--sql` (required), plus a URL, `--seed-file`, or both.
- Seed files are plain text, one URL per line. Empty lines and lines beginning
  with `#` are ignored.
- Load-option style flags (`--deadline`, `--expires`, `--refresh`, `--parse`,
  `--store-content`) work with both `swarm submit` and `swarm query`.
- Capture the job ID printed by `swarm submit` or `swarm query`, then use
  `swarm status` and `swarm result` to follow the async job via
  `SwarmController.getStatus(id)` and `SwarmController.getResult(id)`.

Example seed file:

```text
# urls for the swarm crawler
https://example.com/seed-1
https://example.com/seed-2
```

Typical use cases:

- parallel refresh of a curated URL list
- supervised fan-out browsing across multiple contexts
- repeatable selector-based scraping jobs with explicit output artifacts
- structured data extraction from web pages using X-SQL queries

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
- `agent run` performs a short status probe after submission — missing LLM/API key configuration errors fail fast with a clear message rather than silently queuing a doomed task.
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

* **Agent command** [references/agent.md](references/agent.md)
* **Smarm command** [references/swarm.md](references/swarm.md)
* **Storage state (cookies, localStorage)** [references/storage-state.md](references/storage-state.md)
