# browser4-cli

Make websites accessible for AI agents. Automate tasks online with ease.

## What is browser4-cli?

`browser4-cli` is a command-line browser automation tool. It controls a real
Chrome browser — clicking, typing, scrolling, and extracting data — through a
local server that translates CLI commands into browser actions.

**What you can do with it:**

- Automate form filling, logins, and multistep web workflows
- Capture accessibility snapshots (ARIA tree) for interaction and HTML snapshots (HTML) for data analysis
- Extract structured data with AI, CSS selectors or X-SQL queries
- Swarm mode: Scrape at scale with parallel browser contexts
- Crawl websites recursively, following links to a configurable depth
- Run autonomous AI agent tasks from natural-language instructions
- Save screenshots, PDFs, and full HTML snapshots

**What makes it different:**

- **HTML snapshots** - static HTML analysis without losing information
- **X-SQL** — a SQL-like query language for extracting structured data from
  web pages in a single expression
- **Session persistence** — browser sessions survive across CLI invocations;
  come back to the same tabs and state days later
- **Swarm scraping** — coordinate multiple browser contexts in parallel for
  high-throughput data collection

## Installation

### npm (recommended)

```bash
npm install -g browser4-cli
browser4-cli install
```

### Standalone installers (no npm needed)

**Windows (PowerShell):**
```powershell
irm https://browser4.oss-cn-beijing.aliyuncs.com/scripts/install-browser4-cli.ps1 | iex
browser4-cli install
```

**Linux / macOS:**
```bash
curl -fsSL https://browser4.oss-cn-beijing.aliyuncs.com/scripts/install-browser4-cli.sh | bash
browser4-cli install
```

## Quick start

```bash
# Open a browser session
browser4-cli open --headed https://browser4.io

# Navigate to a page — auto-opens a session if none is active
browser4-cli goto https://browser4.io

# Inspect the page — note the eN labels on interactive nodes
browser4-cli snapshot --boxes

# Interact using refs from the snapshot
browser4-cli click e15
browser4-cli type e15 "Hello World"
browser4-cli press e15 Enter

# Extract data with CSS selectors
browser4-cli get text ".product-title"
browser4-cli get attr ".product-image" data-src

# HTML snapshot with X-SQL
browser4-cli htmlsnapshot capture
browser4-cli htmlsnapshot
browser4-cli htmlsnapshot get text "#main-content"
browser4-cli htmlsnapshot query --sql @query.sql
browser4-cli htmlsnapshot grep -i "error"

# AI-powered extraction and summarization (requires LLM key — see LLM Configuration below)
browser4-cli extract "product name, price, and rating as JSON"
browser4-cli summarize "key points in 3 bullets"

# Autonomous agent task
browser4-cli agent run "Search amazon for mechanical keyboards, compare the top 3, write a summary"

# Parallel scraping with swarm
browser4-cli swarm create --max-open-tabs 12 --display-mode HEADLESS
browser4-cli swarm submit --seed-file ./urls.txt --refresh --store-content
browser4-cli swarm result scrape-task-1

# Batch multiple commands
browser4-cli batch "goto https://browser4.io" "snapshot" "screenshot"

# Take a screenshot
browser4-cli screenshot --full-page

# Manage cookies and storage
browser4-cli cookie-list
browser4-cli state-save session.json

# Close the session when done
browser4-cli close
```

## Global options

| Flag | Description |
|---|---|
| `-h`, `--help [command]` | Print help (optionally for a command) |
| `-v`, `--version` | Print version |
| `-s <name>` | Named session (isolated state per name) |
| `--server <url>` | Override Browser4 server URL |
| `--proxy <url>` | HTTP proxy for runtime downloads only |
| `--json` | Emit machine-parseable JSON to stdout |
| `-q`, `--quiet` | Suppress normal output |

Sessions persist independently per name. Omit `-s` to use the default session
(`~/.browser4/cli-state.json`). With `-s <name>`, state is stored under
`~/.browser4/sessions/<name>.json`.

`--json` makes the CLI emit only the JSON envelope on stdout — all human-readable
text, tips, hints, and side information are suppressed (equivalent to `--quiet`
but with structured output). Every successful command writes a single-line JSON
envelope: `{"status":"ok","command":"<name>","output":{...}}`. Errors also
produce a JSON envelope with `"status":"error"` and an `"error"` object.

## Command reference

### Session management

| Command | Description |
|---|---|
| `open [url]` | Open or reuse a browser session. `--headed` / `--headless`, `--profile <path>`, `--profile-mode <mode>` (temporary / sequential / default), `--interact-level <level>` (FASTEST / FAST / DEFAULT). |
| `attach` | Attach to an existing browser via CDP endpoint or channel name. `--cdp <url\|channel>` (e.g. `http://localhost:9222` or `chrome`), `--endpoint <url>` for remote Browser4 servers. |
| `close` | Close the active session. |
| `list` | List sessions with status (Active / Stale / Unknown). `--all` to list across workspaces. |
| `close-all` | Close all sessions, keep the backend running. |
| `kill-all` | Forcefully stop the backend and all browser processes. |
| `stop` | Gracefully stop the Browser4 server. |
| `status` | Check whether the backend is reachable. `--server <url>` to check a specific server. |
| `doctor` | Run system diagnostics. `--server <url>`, `--file <log-name>`, `--lines <n>`. |

```bash
browser4-cli open --headed https://example.com
browser4-cli open --profile-mode temporary https://example.com
browser4-cli attach --cdp chrome
browser4-cli list
browser4-cli status
```

### Navigation

| Command | Description |
|---|---|
| `goto <url>` | Navigate to a URL. Auto-opens a session if none is active. |
| `go-back` | Go back to the previous page. |
| `go-forward` | Go forward to the next page. |
| `reload` | Reload the current page. |

```bash
browser4-cli goto https://example.com
browser4-cli -s mysession goto https://example.com
```

### Page interaction

| Command | Description |
|---|---|
| `click <ref> [button]` | Click an element. `--modifiers` for modifier keys, `--follow` to detect and switch to new tabs opened by the click. |
| `dblclick <ref> [button]` | Double-click an element. `--modifiers` for modifier keys, `--follow` to detect and switch to new tabs. |
| `hover <ref>` | Hover over an element. |
| `drag <startRef> <endRef>` | Drag and drop between two elements. |
| `fill <ref> <text>` | Fill text into an editable element. `--submit` to press Enter after. `--verify` to confirm. |
| `type <text> [ref]` | Type text. `--submit`, `--verify`, `--focus` (click first to focus). |
| `select <ref> <val>` | Select an option in a dropdown. `--verify` to confirm. |
| `check <ref>` | Check a checkbox or radio. |
| `uncheck <ref>` | Uncheck a checkbox or radio. |

All interaction commands accept element references from `snapshot` (e.g. `e15`)
or CSS selectors (e.g. `#submit-btn`, `.menu-item`).

```bash
browser4-cli click e15
browser4-cli fill e7 "John Doe" --submit
browser4-cli type "search term" e3
browser4-cli select e12 "option-value"
```

### Keyboard & mouse

| Command | Description |
|---|---|
| `press <key> [ref]` | Press a key (`a`, `Enter`, `ArrowLeft`, `Escape`). Supports `--verify`, `--follow` (detect new tabs). |
| `keydown <key>` | Press and hold a key. |
| `keyup <key>` | Release a key. |
| `mousemove <x> <y>` | Move mouse to coordinates. |
| `mousedown [button]` | Press mouse button (defaults to left). |
| `mouseup [button]` | Release mouse button. |
| `mousewheel <dx> <dy>` | Scroll the mouse wheel. |
| `scroll <direction> <pixels>` | Scroll the page (`up` / `down` / `left` / `right`). |

```bash
browser4-cli press Enter e7
browser4-cli mousemove 200 400
browser4-cli scroll down 300
```

### Waiting & dialogs

| Command | Description |
|---|---|
| `wait [target]` | Wait for a condition — six modes available. |
| `dialog-accept [prompt]` | Accept a browser dialog (alert, confirm, prompt). |
| `dialog-dismiss` | Dismiss a browser dialog. |

`wait` supports six modes, selected by which option you provide:

| Mode | Syntax | Example |
|---|---|---|
| **selector** | `wait <ref\|selector>` | `browser4-cli wait e1` |
| **time** | `wait <milliseconds>` | `browser4-cli wait 2000` |
| **text** | `wait --text <text>` | `browser4-cli wait --text "Success"` |
| **url** | `wait --url <glob>` | `browser4-cli wait --url "**/dashboard"` |
| **load** | `wait --load <state>` | `browser4-cli wait --load networkidle` |
| **fn** | `wait --fn <JS expr>` | `browser4-cli wait --fn "window.ready === true"` |

All modes accept `--timeout <ms>` (default: 30000). Load states: `networkidle`,
`domcontentloaded`, `load`.

### Screenshots & PDF

| Command | Description |
|---|---|
| `screenshot [ref]` | Take a screenshot. `--filename <path>`, `--full-page`, `--viewport <n>`. Optionally of a specific element. |
| `pdf` | Save page as PDF. `--filename <path>`. |

```bash
browser4-cli screenshot --full-page --filename page.png
browser4-cli screenshot --viewport 0 --filename top.png
browser4-cli pdf --filename page.pdf
```

### Tabs

| Command | Description |
|---|---|
| `tab-list` | List all tabs with their zero-based index. |
| `tab-new [url]` | Open a new tab. |
| `tab-close [index]` | Close a tab (current tab if no index). |
| `tab-select <index>` | Switch to a tab by index. |

```bash
browser4-cli tab-list
browser4-cli tab-select 1
browser4-cli tab-new https://example.com
browser4-cli tab-close 1
```

### Element inspection

| Command | Description |
|---|---|
| `snapshot` | Capture an accessibility snapshot. See [Snapshot](#snapshot) below. |
| `get <mode> <selector> [name]` | Extract data from a page element in one of six modes (see below). |
| `eval <expression> [ref]` | Evaluate JavaScript on the page or a target element. `--file <path>`, `--base64`, or `--stdin` to provide the expression. `--json` to wrap scalar results. |
| `generate-locator <ref>` | Generate a stable CSS selector path for an element. |
| `htmlsnapshot` | Short form of `htmlsnapshot capture`. Capture a static HTML snapshot. See [HTML Snapshot](#dom-snapshot) below. |
| `htmlsnapshot capture` | Capture a static HTML snapshot. See [HTML Snapshot](#dom-snapshot) below. |
| `extract <instruction>` | Extract structured data with AI. `--schema <json>` for typed output. `--filename <path>`, `--raw`. |
| `summarize [instruction]` | Summarize page content with AI. `--selector <css>`, `--filename <path>`, `--raw`. |

`get` modes:

| Mode | Returns | Example |
|---|---|---|
| `text` | Visible inner text | `browser4-cli get text ".price"` |
| `html` | Inner HTML | `browser4-cli get html e3` |
| `box` | Bounding box `{x,y,w,h}` | `browser4-cli get box "#header"` |
| `styles` | Computed CSS styles | `browser4-cli get styles e9` |
| `property` | DOM property value | `browser4-cli get property "input" value` |
| `attr` | HTML attribute value | `browser4-cli get attr "a.link" href` |

```bash
browser4-cli eval "document.title"
browser4-cli eval "el => el.textContent" e15
browser4-cli eval --file script.js
browser4-cli eval --base64 ZG9jdW1lbnQudGl0bGU=
browser4-cli generate-locator e5
browser4-cli extract "product name, price, and rating"
browser4-cli extract "contacts" --schema '{"type":"object","properties":{"name":{"type":"string"},"email":{"type":"string"}}}'
browser4-cli summarize --selector "#reviews"
```

### Browser storage

| Command | Description |
|---|---|
| `state-save [path]` | Save cookies & localStorage to a JSON file. |
| `state-load <path>` | Restore cookies & localStorage from a saved state. |
| `cookie-list` | List cookies. `--domain`, `--path` filters. |
| `cookie-get <name>` | Get a cookie by name. |
| `cookie-set <name> <value>` | Set a cookie. `--domain`, `--path`, `--expires`, `--httpOnly`, `--secure`, `--sameSite` (Strict / Lax / None). |
| `cookie-delete <name>` | Delete a cookie. `--domain`, `--path` overrides. |
| `cookie-clear` | Clear all cookies. |
| `localstorage-list` | List localStorage entries. |
| `localstorage-get <key>` | Get a localStorage value. |
| `localstorage-set <key> <value>` | Set a localStorage value. |
| `localstorage-delete <key>` | Delete a localStorage key. |
| `localstorage-clear` | Clear all localStorage. |
| `sessionstorage-list` | List sessionStorage entries. |
| `sessionstorage-get <key>` | Get a sessionStorage value. |
| `sessionstorage-set <key> <value>` | Set a sessionStorage value. |
| `sessionstorage-delete <key>` | Delete a sessionStorage key. |
| `sessionstorage-clear` | Clear all sessionStorage. |

```bash
browser4-cli state-save auth.json
browser4-cli state-load auth.json
browser4-cli cookie-set token abc123 --httpOnly --secure --sameSite Lax
browser4-cli cookie-list --domain example.com
```

### Batch & loop

| Command | Description |
|---|---|
| `batch [command...]` | Execute multiple commands in one invocation. `--bail` to stop on first failure. `--json` to read commands from stdin JSON. |
| `loop <task>` | Execute a task repeatedly on an interval. Supports plain text tasks, X-SQL, shell commands (`--shell`), and CLI subcommands (after `--`). |

`batch` only supports DOM operations (navigation, keyboard, mouse, core
interactions, screenshots, tabs). Session lifecycle commands (`open`, `close`)
must run separately.

```bash
# Batch form fill
browser4-cli batch "fill e1 'John'" "fill e2 'john@example.com'" "click e3"

# Stop on first failure
browser4-cli batch --bail "goto https://example.com" "click e1" "screenshot"

# Pipe commands as JSON
echo '[
  ["goto", "https://example.com"],
  ["fill", "#name", "Bob"],
  ["click", "#submit"]
]' | browser4-cli batch --json

# Loop: run every 5 minutes, 10 iterations
browser4-cli loop "load https://example.com and extract the page title" -i 300 -n 10

# Loop a shell command
browser4-cli loop --shell "curl -s https://api.example.com/health" -i 60 -n 10
```

### Server management

| Command | Description |
|---|---|
| `install` | Download the Browser4 runtime bundle. `--tag <version>`, `--force`. |
| `upgrade` | Upgrade the runtime. `--tag <version>`, `--force`. |
| `uninstall` | Remove browser4-cli and its runtime data. `-y` / `--yes` to skip confirmation, `--dry-run` to preview. |

```bash
browser4-cli install
browser4-cli install --tag v4.11.0
browser4-cli upgrade --force
browser4-cli uninstall --dry-run
browser4-cli uninstall -y
```

### Skills

Manage bundled skill files embedded in the browser4-cli binary. Skill files are AI agent
instructions that always match the installed CLI version.

| Command | Description |
|---|---|
| `skills` | List all bundled skill names. Same as `skills list`. |
| `skills list` | List available bundled skills with file counts. |
| `skills get <name>` | Output a skill's SKILL.md content. `--full` includes references and templates. `--all` outputs every skill. |
| `skills path [name]` | Print the skills directory path. With a name, prints the path to that skill's subdirectory. |
| `skills unpack [dest]` | Unpack bundled skill files to a directory (defaults to the skills directory). |

Skill files are unpacked to the versioned installation directory during `browser4-cli install`.
Use `skills unpack` to refresh or relocate skill files without reinstalling.
Set `BROWSER4_SKILLS_DIR` to override the skills directory path.

```bash
browser4-cli skills                         # List bundled skills
browser4-cli skills get browser4-cli        # Get the CLI skill's main content
browser4-cli skills get browser4-cli --full # Include references and templates
browser4-cli skills get --all               # Output every skill
browser4-cli skills path                    # Print skills root directory
browser4-cli skills path browser4-cli       # Print path to a specific skill
browser4-cli skills unpack                  # Unpack to default skills directory
browser4-cli skills unpack /custom/path     # Unpack to a custom directory
```

### Other

| Command | Description |
|---|---|
| `resize <w> <h>` | Resize the browser window. |
| `delete-data` | Delete session data (cookies, storage, cache). |
| `console [min-level]` | List console messages. `--clear` to clear. |
| `upload <ref> <file>` | Upload files to a file input. |

---

## Commands with subcommands

### Snapshot

`snapshot` captures an accessibility tree of the current page. Every interactive
element is labeled with a ref (`e15`, `e42`) that you can pass directly to
`click`, `type`, `fill`, and other interaction commands.

```
browser4-cli snapshot [options]
browser4-cli snapshot grep [OPTIONS] <pattern>
```

#### snapshot

Capture the accessibility snapshot.

| Option | Description |
|---|---|
| `--filename <path>` | Save snapshot to file instead of stdout |
| `--boxes` | Include bounding boxes `[box=x,y,w,h]` per element |
| `-i`, `--interactive` | Show only interactive elements |
| `-u`, `--urls` | Include href URLs for link elements |
| `-c`, `--compact` | Remove empty structural nodes (default) |
| `--no-compact` | Include all structural nodes |
| `-d`, `--depth <n>` | Limit tree depth |
| `-l`, `--limit <n>` | Cap total rendered nodes |
| `-s`, `--selector <css>` | Scope snapshot to a CSS selector |
| `--raw`, `--stdout` | Print directly to stdout (for piping) |
| `-vp`, `--viewport <spec>` | Capture specific viewports (e.g. `0,2,4` or `1-3`) |

```bash
# Full snapshot with bounding boxes
browser4-cli snapshot --boxes

# Interactive elements only, depth-limited
browser4-cli snapshot -i -c -d 5

# Scope to a specific section, pipe to grep
browser4-cli snapshot -s "#main-content" --raw | grep "button"

# Capture specific viewports
browser4-cli snapshot --viewport 0,2,4
```

#### snapshot grep

Search the accessibility-tree YAML with regex patterns and grep-style output.
Line numbers are shown by default.

| Flag | Description |
|---|---|
| `-i` | Case-insensitive matching |
| `-A N`, `-B N`, `-C N` | Context lines after / before / around each match |
| `-v` | Invert match (non-matching lines) |
| `-c` | Print only count of matching lines |
| `-l` | Print only whether matches exist |
| `-F` | Treat pattern as a literal string |
| `-w` | Match whole words only |
| `--no-line-number` | Suppress line numbers |
| `--selector <css>` | Scope search to a CSS selector |
| `--page N`, `--page-size N`, `--all` | Output pagination (2000 lines per page default) |

```bash
browser4-cli snapshot grep -i error
browser4-cli snapshot grep -C 2 "timeout"
browser4-cli snapshot grep -F -w "Error" --selector main
```

---

### HTML Snapshot

`htmlsnapshot` captures a **static HTML snapshot** of the current page — a full
HTML capture stored in the backend that can be queried repeatedly without
re-fetching. Unlike the accessibility `snapshot`, this works against the raw DOM.

```
browser4-cli htmlsnapshot capture
browser4-cli htmlsnapshot
browser4-cli htmlsnapshot get <field> [selector] [name]
browser4-cli htmlsnapshot get all <field> [selector] [name]
browser4-cli htmlsnapshot query [url] --sql <query>
browser4-cli htmlsnapshot export [--file <path>]
browser4-cli htmlsnapshot summary
browser4-cli htmlsnapshot grep [OPTIONS] <pattern>
browser4-cli htmlsnapshot inspect [selector] [--max N] [--depth D]
```

#### htmlsnapshot / htmlsnapshot capture

Capture the DOM and display metadata (URL, title, timestamps, image/link counts,
interactive elements with tag, class, id, aria, and bounding boxes).
`htmlsnapshot` is a short form of `htmlsnapshot capture`.

```bash
browser4-cli htmlsnapshot capture
browser4-cli htmlsnapshot
```

#### htmlsnapshot get

Extract elements from the stored snapshot by CSS selector.

| Field | Returns | Example |
|---|---|---|
| `text` | Inner text of the first match | `htmlsnapshot get text "#title"` |
| `html` | Inner HTML of the first match | `htmlsnapshot get html "body"` |
| `attr` | Attribute value (requires `name`) | `htmlsnapshot get attr "a" href` |

Selector defaults to `:root`. `get html` output is paginated (2000 lines per page);
`get text` is not paginated by default. Use `--page N`, `--page-size N`, or `--all`.

```bash
browser4-cli htmlsnapshot get text "#productTitle"
browser4-cli htmlsnapshot get html "#main-content"
browser4-cli htmlsnapshot get attr "a.product-link" href
browser4-cli htmlsnapshot get html "body" --page 2
browser4-cli htmlsnapshot get html --all
```

#### htmlsnapshot get all

Like `get`, but extracts ALL matching elements (querySelectorAll semantics).
Supports `--offset N` and `--limit N` for element-level pagination, plus
`--page N`, `--page-size N`, `--all` for output pagination. `get all html` is
paginated at 2000 lines by default; `get all text` is not paginated by default.

> **Note:** Each `get all` call scans the whole document independently. For
> **correlated multi-field extraction** (title + price + URL per item), use
> `htmlsnapshot query` with X-SQL's `DOM_LOAD_AND_SELECT` — it scopes each row
> to a parent container so fields stay aligned. See the
> [list-page scraping pattern](skills/browser4-cli/references/x-sql-dom-load-select.md).

```bash
browser4-cli htmlsnapshot get all text "h2 a"
browser4-cli htmlsnapshot get all text ".result" --offset 10 --limit 5
browser4-cli htmlsnapshot get all text "p" --page-size 500
```

#### htmlsnapshot query

Run an X-SQL query against the stored HTML snapshot. `--sql` is required. Use
`@url` as a placeholder for the target page URL (unquoted — the server handles
escaping).

**Recommended:** Write queries to a `.sql` file to avoid shell escaping issues.
Prefix the `--sql` value with `@` to read from a file. Use `--sql-stdin` for
piped/scripted workflows, or `--sql-base64` for transport-safe encoded queries.

```bash
# From a file (recommended — no shell escaping)
cat > query.sql << 'SQLEOF'
SELECT
  dom_base_uri(dom) AS url,
  dom_first_text(dom, 'h1') AS title
FROM load_and_select(@url, ':root')
SQLEOF
browser4-cli htmlsnapshot query --sql @query.sql

# From stdin
cat query.sql | browser4-cli htmlsnapshot query --sql-stdin

# From base64 (transport-safe, no quoting issues)
browser4-cli htmlsnapshot query --sql "$(base64 -w0 query.sql)" --sql-base64

# Inline (simple queries only — quoted selectors require escaping on Windows)
browser4-cli htmlsnapshot query --sql "
  SELECT dom_base_uri(dom) AS url, dom_first_text(dom, 'h1') AS title
  FROM load_and_select(@url, ':root')
"
```

#### htmlsnapshot export

Save the full snapshot HTML to a local file.

```bash
browser4-cli htmlsnapshot export --file snapshot.html
```

#### htmlsnapshot summary

Generate a compressed Web Page Summary Index (WPSI) — page type, structure, key
content nodes, repeated lists, tables, and stats — typically <1% of the original
HTML size.

```bash
browser4-cli htmlsnapshot summary
```

#### htmlsnapshot grep

Search the HTML snapshot HTML with regex patterns. Same grep flags as
`snapshot grep`: `-i`, `-A N`, `-B N`, `-C N`, `-v`, `-c`, `-l`, `-F`, `-w`,
`--no-line-number`, `--selector <css>`, `--page N`, `--page-size N`, `--all`.

```bash
browser4-cli htmlsnapshot grep -i error
browser4-cli htmlsnapshot grep -F -C 2 "404 Not Found"
browser4-cli htmlsnapshot grep --selector main "Submit"
browser4-cli htmlsnapshot grep --all "TODOs"
```

#### htmlsnapshot inspect

Analyze DOM structure and discover CSS selectors for recurring patterns (product
cards, prices, titles). Run without arguments to auto-discover the page's most
prominent repeating content. When the selector matches multiple similar elements,
it compares their child structures and ranks selectors by recurrence.

```bash
browser4-cli htmlsnapshot inspect                        # auto-discover repeating patterns
browser4-cli htmlsnapshot inspect ".product_pod"         # inspect a specific container
browser4-cli htmlsnapshot inspect ".s-result-item" --depth 6 --max 20
```

---

### LLM Configuration

AI-powered commands (`agent`, `extract`, `summarize`) and X-SQL `llm_*` functions
require an LLM API key. Configure one provider via environment variables:

| Provider | Environment Variables |
|---|---|
| DeepSeek | `DEEPSEEK_API_KEY` |
| OpenRouter | `OPENROUTER_API_KEY`, `OPENROUTER_MODEL_NAME`, `OPENROUTER_BASE_URL` |
| Volcengine (ByteDance) | `VOLCENGINE_API_KEY`, `VOLCENGINE_MODEL_NAME`, `VOLCENGINE_BASE_URL` |
| OpenAI-compatible | `OPENAI_API_KEY`, `OPENAI_MODEL_NAME`, `OPENAI_BASE_URL` |
| Aliyun Qwen (DashScope) | `OPENAI_API_KEY`, `OPENAI_MODEL_NAME`, `OPENAI_BASE_URL` |

Example:

```bash
export DEEPSEEK_API_KEY=sk-xxxxxxxxxxxxxxxxxxxxxxxx
```

If no valid LLM key is configured, AI commands fail fast with a clear error at startup.

---

### Agent

The `agent` subcommands submit natural-language tasks that the Browser4 backend
executes autonomously — it plans, browses, and returns a result.

```
browser4-cli agent run <task>
browser4-cli agent status <id>
browser4-cli agent result <id>
```

#### agent run

Submit an asynchronous natural-language task. Returns immediately with a task ID.

```bash
browser4-cli agent run "Open browser4.io and summarize the hero section"
# → Task submitted: agent-task-1
# → Use 'browser4-cli agent status agent-task-1' to check progress.
```

#### agent status

Poll the status of a running task.

```bash
browser4-cli agent status agent-task-1
```

#### agent result

Fetch the completed task result.

```bash
browser4-cli agent result agent-task-1
```

Agent commands are task-ID based and do not require an active browser session.
They are not supported inside `batch` mode.

---

### Swarm

Swarm mode coordinates multiple browser contexts in parallel for high-throughput
scraping and data extraction.

```
browser4-cli swarm create [options]
browser4-cli swarm submit [url] [options]
browser4-cli swarm query <url> --sql <query>
browser4-cli swarm status <id>
browser4-cli swarm result <id>
```

#### swarm create

Create a swarm scrape session.

| Option | Default | Description |
|---|---|---|
| `--profile-mode` | `SEQUENTIAL` | Profile mode: `SEQUENTIAL` or `TEMPORARY` |
| `--max-open-tabs` | `8` | Max open tabs per browser context |
| `--max-browser-contexts` | `2` | Number of isolated browser environments |
| `--display-mode` | — | Display mode: `GUI`, `HEADLESS`, `SUPERVISED` |

```bash
browser4-cli swarm create \
  --profile-mode TEMPORARY \
  --max-open-tabs 12 \
  --max-browser-contexts 3 \
  --display-mode HEADLESS
```

#### swarm submit

Submit URLs as scrape jobs. Accepts a direct URL, a `--seed-file` (one URL per
line; `#` comments and blank lines ignored), or both.

| Option | Description |
|---|---|
| `--seed-file <path>` | File of URLs to submit |
| `--sql <query>` | X-SQL query (inline or `@file.sql`). Sends to query API instead of submit. |
| `--deadline <ISO 8601>` | Task completion deadline |
| `--expires <duration>` | Cache expiration (e.g. `1d`, `1h`) |
| `--refresh` | Force fresh fetch, ignore cache |
| `--parse` | Parse page immediately after fetching |
| `--store-content` | Persist page content to storage |

```bash
# Submit URLs from a file
browser4-cli swarm submit --seed-file ./urls.txt \
  --deadline 2026-03-30T00:00:00Z \
  --expires 1d --refresh --store-content

# Submit with inline X-SQL
browser4-cli swarm submit "https://www.amazon.com/dp/B08PP5MSVB" --sql "
  SELECT
    dom_base_uri(dom) AS url,
    dom_first_text(dom, '#productTitle') AS title
  FROM load_and_select(@url, 'body')
"

# Submit with X-SQL from a file
browser4-cli swarm submit "https://www.amazon.com/dp/B08PP5MSVB" --sql @query.sql
```

#### swarm query

Run an X-SQL query against loaded webpages. `--sql` is required. Supports a
direct URL, `--seed-file`, or both — runs the same query against each. Also
accepts `--deadline`, `--expires`, `--refresh`.

```bash
# Inline X-SQL
browser4-cli swarm query "https://www.amazon.com/dp/B08PP5MSVB" --sql "
  SELECT dom_first_text(dom, '#productTitle') AS title
  FROM load_and_select(@url, 'body')
"

# From a file, with seed URLs
browser4-cli swarm query --sql @query.sql --seed-file ./urls.txt --refresh
```

#### swarm status / swarm result

Poll and fetch results — same pattern as `agent status` / `agent result`.

```bash
browser4-cli swarm status scrape-task-4
browser4-cli swarm result scrape-task-4
```

---

### Crawl

Recursive website crawling from a seed URL. Reuses the swarm infrastructure.

```
browser4-cli crawl <url> [options]
```

| Flag | Default | Description |
|---|---|---|
| `-d`, `--depth` | `1` | Maximum crawl depth |
| `-ol`, `--out-link-selector` | — | CSS selector to extract links from each page |
| `-olp`, `--out-link-pattern` | `.+` | Regex filter for extracted links |
| `-tl`, `--top-links` | `20` | Max links extracted per page |
| `-a`, `--args` | — | Additional LoadOptions passthrough (e.g. `-a "-nMaxRetry 5"`) |
| `--refresh` | — | Force fresh fetch |
| `--parse` | — | Parse pages immediately after fetching |
| `--store-content` | — | Persist page content |
| `--expires` | — | Cache expiration (e.g. `1d`, `1h`, `30m`) |
| `-p`, `--priority` | — | Queue priority (lower = higher) |
| `--ignore-url-query` | — | Strip query params during URL normalization |
| `--no-norm` | — | Disable URL normalization |
| `--readonly` | — | Non-destructive mode |
| `--page-load-timeout` | — | Max wait for each page load |
| `-bg`, `--background` | — | Submit crawl and return immediately; use `crawl list` to track |

```bash
# Depth 1: extract all links from homepage, load each linked page
browser4-cli crawl "https://example.com" --out-link-selector "a[href]"

# Depth 2: follow product links, filter by pattern
browser4-cli crawl "https://shop.example.com" \
  --depth 2 \
  --out-link-selector "a.product-link" \
  --out-link-pattern "/product/" \
  --top-links 10

# Deep crawl with refresh and content storage
browser4-cli crawl "https://example.com" --depth 3 --refresh --store-content

# Background crawl — submit and return immediately
browser4-cli crawl "https://example.com" -ol "a[href]" --background
browser4-cli crawl list
```

---

## Element references

The `snapshot` command returns an accessibility tree where every interactive
element is labeled with a ref like `e15`. Pass this ref to any interaction
command:

```bash
browser4-cli snapshot       # see e15 is the search input
browser4-cli click e15      # click it
browser4-cli type "query" e15  # type into it
```

You can also use CSS selectors (e.g. `#search`, `.btn-primary`,
`input[name=email]`) anywhere a ref is accepted.

## State persistence

CLI state is stored under `~/.browser4` (override with `BROWSER4_CLI_STATE_DIR`):

- Default session: `~/.browser4/cli-state.json`
- Named sessions (`-s <name>`): `~/.browser4/sessions/<name>.json`

Each state file stores the session ID, server URL, and cursor position — so your
next `open` or `goto` picks up right where you left off.

The Browser4 runtime bundle (~200 MB) is stored separately in a
platform-conventional data directory so clearing CLI session state does not
require re-downloading:

| Platform | Path |
|---|---|
| Linux | `~/.local/share/browser4/runtime/<version>/` |
| macOS | `~/Library/Application Support/browser4/runtime/<version>/` |
| Windows | `%APPDATA%/browser4/runtime/<version>/` |

Override the runtime data root with `BROWSER4_RUNTIME_DIR`.

## License

Apache-2.0
