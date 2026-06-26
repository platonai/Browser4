---
name: browser4-cli
description: Automates browser interactions for web testing, form filling, screenshots, and data extraction. Use when the user needs to navigate websites, interact with web pages, fill forms, take screenshots, test web applications, or extract information from web pages.
allowed-tools: Bash(browser4-cli:*)
---

# Browser Automation with browser4-cli

Browser automation CLI for AI agents — Chrome/Chromium via CDP with accessibility-tree snapshots.

## Installation

Requires Node.js.

```bash
npm install -g browser4-cli
browser4-cli install              # install native binary (recommended)
browser4-cli install --tag=v4.9.3 # pin a specific version
```

Bootstrap scripts (alternative to npm):

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

## Concepts

### Snapshots & Element References

After commands that modify browser state (`open`, `click`, `type`, etc.), browser4-cli prints a header then saves an **accessibility-tree snapshot** — a YAML file showing the page structure as nested elements with roles, accessible names, and refs:

```
### Page
- Page URL: https://example.com/
- Page Title: Example Domain
### Snapshot
[Snapshot](.browser4-cli/snapshot/snapshot-2026-02-14T19-22-42-679Z.yml)
```

The YAML file itself contains the tree. Each interactive element has a **ref** (`e5`, `e12`) used to target it in subsequent commands. Roles include `button`, `link`, `textbox`, `generic`, `list`, `listitem`, `image`, `paragraph`, etc.:

```yaml
- generic [ref=e7]:
  - link "新闻" [ref=e191]:
    - /url: http://news.baidu.com
  - textbox "端午佳节" [ref=e35]:
    - /multiline: "true"
  - button "百度一下" [ref=e25]
  - list [ref=e374]:
    - listitem [level=1] [ref=e375]:
      - link "热搜标题" [ref=e376]:
        - /url: https://...
```

Element roles and accessible names come first, then `[ref=eN]` at the end of the line. Properties (`/url`, `/multiline`) and child elements are nested with indentation. Extra attributes like `[level=1]` may appear alongside the ref.

Take snapshots on demand with `browser4-cli snapshot` (see below).

### Ref Lifecycle

Element refs (`e5`, `e12`) are Chrome DevTools Protocol **backend node IDs** — integers Chrome assigns to DOM nodes in the current document. They are **ephemeral** and have a limited lifetime:

| Operation | Refs still valid? | Notes |
|---|---|---|
| Same-page interaction (`click`, `type`, `fill`) | **No** — re-snapshot after | Every command that modifies page state regenerates the accessibility tree; old refs may point to stale or removed nodes |
| `goto` (navigate to new URL) | **No** | New document → new backend node IDs |
| `go-back` / `go-forward` | **No** | Restoring a cached page may coincidentally reuse IDs, but this is **not guaranteed** by Chrome |
| `reload` | **No** | Chrome may reassign backend node IDs on reload |
| Tab switch (`tab-select`) | **No** — re-snapshot | Different tab → different document |
| `snapshot` (re-capture) | **No** (old refs); **Yes** (new refs) | A new snapshot produces fresh refs; discard previous ones |

**Best practice:** Re-snapshot after **any** navigation or page-modifying interaction before using refs. Treat refs as single-use: capture a snapshot, act on its refs immediately, then re-snapshot for the next interaction.

### Sessions

Named sessions isolate browser state (cookies, localStorage, tabs). Use `-s=<name>` to target a named session instead of the default slot. `goto` auto-opens/reconnects a session; you rarely need to manage sessions manually.

## Commands

### Navigation & Session

```bash
browser4-cli open [--headed|--headless] [url]  # start session, optionally with url
browser4-cli attach --cdp=<channel|url>         # connect to an existing browser via CDP
browser4-cli goto <url>                         # navigate (auto-opens/reconnects session)
browser4-cli go-back | go-forward | reload
browser4-cli close                              # close current session
browser4-cli -s=<name> open|goto <url>          # target a named session
```

`goto` auto-reuses the active session; auto-opens a fresh one if stale or missing. Prefer `goto` over manual session management.

### Attach — Connect to an Existing Browser

Connect to an already-running Chrome or Edge instance via CDP instead of launching a new browser. Supports channel names (`chrome`, `msedge`), CDP URLs, bare ports, and remote Browser4 servers.

```bash
browser4-cli attach --cdp=<channel|url|port> [--endpoint=<server-url>] [-s=<name>]
```

Enable remote debugging in the target browser first: go to `chrome://inspect/#remote-debugging` and check "Allow remote debugging for this browser instance".

Full reference: **[references/attach.md](references/attach.md)**.

### Interaction

```bash
browser4-cli click <ref>           # left-click element
browser4-cli dblclick <ref>
browser4-cli hover <ref>
browser4-cli type "<text>"         # type into focused element
browser4-cli fill <ref> <value>  # clear + type into input/textarea
browser4-cli select <ref> "<val>"  # select dropdown option
browser4-cli check <ref>           # toggle checkbox on
browser4-cli uncheck <ref>         # toggle checkbox off
browser4-cli drag <from-ref> <to-ref>
```

### Keyboard & Mouse

```bash
browser4-cli press <key>           # e.g. Enter, ArrowDown, Tab, Escape
browser4-cli keydown|keyup <key>   # raw key events
browser4-cli mousemove <x> <y>
browser4-cli mousedown|mouseup [right]
browser4-cli mousewheel <dx> <dy>
```

### Snapshots

```bash
browser4-cli snapshot                              # capture accessibility tree
browser4-cli snapshot --filename=result.yaml       # named output (for workflow artifacts)
browser4-cli snapshot --boxes                      # include bounding boxes
browser4-cli snapshot -i -c -d 5                   # interactive only, compact, depth 5
browser4-cli snapshot -s "#content"                # scoped to CSS selector
```

| Flag | Effect |
|---|---|
| `-i, --interactive` | Only interactive elements (buttons, links, inputs) |
| `-c, --compact` | Remove empty structural elements |
| `-d, --depth <n>` | Limit tree depth |
| `-s, --selector <sel>` | Scope to CSS selector subtree |
| `-u, --urls` | Include href URLs for links |

### Element Data Extraction (get)

```bash
browser4-cli get <text|html|box|styles> <ref|selector>         # extract data
browser4-cli get <property|attr> <ref|selector> <name>          # property or attribute
```

- `text` → visible text content; `html` → innerHTML; `box` → `{x, y, width, height}`; `styles` → computed CSS (JSON)
- `property` / `attr` require a third argument (name)
- Use `browser4-cli generate-locator <ref>` to get a CSS selector from a snapshot ref

### Scroll & Wait

```bash
browser4-cli scroll <down|up|left|right> <px>
browser4-cli wait <ms>                                 # fixed delay
browser4-cli wait <ref>                                # wait for element to appear
browser4-cli wait --text="<text>"                      # wait for text on page
browser4-cli wait --url="<glob>"                       # wait for URL match
browser4-cli wait --load=<networkidle|domcontentloaded>
browser4-cli wait --fn="<js-expression>"
```

### Tabs (zero-based indices)

```bash
browser4-cli tab-list
browser4-cli tab-new [url]
browser4-cli tab-close [index]
browser4-cli tab-select <index>
```

Run `tab-list` first to discover indices.

### Screenshots & Evaluate

```bash
browser4-cli screenshot [ref] [--filename=page.png]
browser4-cli eval "<js>" [ref]              # evaluate JS, optionally scoped to element
browser4-cli eval --file=script.js [ref]
browser4-cli resize <width> <height>
```

### Storage

```bash
browser4-cli state-save [file.json]          # save cookies + localStorage
browser4-cli state-load <file.json>          # restore saved state

# Cookies
browser4-cli cookie-list [--domain=<domain>]
browser4-cli cookie-get|delete <name>
browser4-cli cookie-set <name> <value> [--path=/]
browser4-cli cookie-clear

# localStorage / sessionStorage
browser4-cli <localstorage|sessionstorage>-<list|get|set|delete|clear> [args...]
```

## DOM Snapshot

Static DOM queries (CSS selectors) for structured data extraction — unlike interactive `snapshot` which provides accessibility-tree refs.

```bash
browser4-cli domsnapshot                                # capture static DOM snapshot
browser4-cli domsnapshot get <field> [selector] [name]  # extract text/html/attr via CSS
browser4-cli domsnapshot query [url] --sql <query>      # X-SQL query against DOM
browser4-cli domsnapshot summary                        # compressed page summary (WPSI)
browser4-cli domsnapshot export [--file <path>]         # save snapshot HTML (might be huge, don't read it directly)
browser4-cli domsnapshot grep [OPTIONS] <pattern>       # search snapshot HTML with regex (grep-style output)
```

Full reference: **[references/domsnapshot.md](references/domsnapshot.md)**.

### Bridging snapshot refs to CSS selectors

`domsnapshot` needs CSS selectors, not `e5` refs. Bridge with (pick one):

1. Construct from snapshot line: `- textbox "Email" [ref=e10]:` → `[placeholder="Email"]` or `input[type="email"]`
2. `browser4-cli get attr <ref> id` or `get attr <ref> class`
3. `browser4-cli generate-locator <ref>`

**Never** cat full snapshot files. Always use targeted `domsnapshot get` or `domsnapshot query`.

Full reference: **[references/css-selector-bridge.md](references/css-selector-bridge.md)**.

## AI-Powered Extraction & Summarization

Natural-language commands for extracting structured data or summarizing page content. These are synchronous (they block until complete) and require an LLM API key configured.

**Prerequisites:** Set an LLM API key — see [Agent reference](references/agent.md) for provider configuration (DeepSeek, OpenRouter, Volcengine, OpenAI-compatible, Aliyun Qwen).

### extract

Extract structured data from the current page. Uses an AI agent that reads the page content and returns the requested data.

```bash
# Simple extraction
browser4-cli extract "get all product titles on the page"

# Structured extraction with field descriptions
browser4-cli extract "get the first 5 search results with title, price, rating, and link as JSON"

# With an explicit JSON schema
browser4-cli extract "list all article headlines and authors" --schema='{"fields":[{"name":"title","type":"string"},{"name":"author","type":"string"}]}'
```

Options:

| Option | Effect |
|---|---|
| `--schema=<json>` | JSON schema to constrain the extracted data structure |

### summarize

Summarize page content using an AI agent.

```bash
browser4-cli summarize "summarize the main article"
browser4-cli summarize "summarize the product reviews"
browser4-cli summarize --selector="#content"
```

Options:

| Option | Effect |
|---|---|
| `--selector=<sel>` | CSS selector to limit summarization to a specific element |

Full reference: **[references/agent.md](references/agent.md)**.

## Browser Sessions

```bash
browser4-cli list              # show all sessions and their state
browser4-cli attach --cdp=<channel|url>  # attach to an existing browser instead of launching
browser4-cli close-all         # close all sessions, keep backend running
browser4-cli kill-all          # stop backend + kill all browser processes
```

## Swarm CLI

Parallel scraping and structured data extraction across multiple browser contexts.

```bash
browser4-cli swarm create [--profile-mode=TEMPORARY] [--max-open-tabs=12] [--max-browser-contexts=3] [--display-mode=HEADLESS]
browser4-cli swarm submit <url> [--seed-file=./urls.txt] [--refresh] [--store-content]
browser4-cli swarm query <url> --sql "<query>"
browser4-cli swarm status <id>
browser4-cli swarm result <id>
```

Full reference: **[references/swarm.md](references/swarm.md)**.

## Crawl CLI

Recursive website crawling — start from a seed URL and follow links up to a configurable depth.

```bash
browser4-cli crawl <url> [--depth=1] [--out-link-selector=<CSS>] [--out-link-pattern=<regex>] [--top-links=20]
```

### Command overview

| Command | Description |
|---|---|
| `crawl <url>` | Crawl a website starting from a URL, following links up to a configurable depth |

### Key flags

| Flag | Default | Description |
|---|---|---|
| `-d`, `--depth` | `1` | Maximum crawl depth |
| `-ol`, `--out-link-selector` | — | CSS selector to extract links from each page |
| `-olp`, `--out-link-pattern` | `.+` | Regex pattern to filter extracted links |
| `-tl`, `--top-links` | `20` | Maximum links to extract per page |
| `-a`, `--args` | — | Additional LoadOptions passthrough (e.g. `-a "-refresh -nMaxRetry 5"`) |
| `--refresh` | — | Force a fresh fetch, ignoring cache |
| `--parse` | — | Parse each page immediately after fetching |
| `--expires` | — | Cache expiration duration (e.g. `1d`, `1h`, `30m`) |
| `--store-content` | — | Persist page content to storage |
| `-p`, `--priority` | — | Queue priority (lower = higher priority) |
| `--page-load-timeout` | — | Maximum time to wait for page load |
| `--ignore-url-query` | — | Remove query parameters from URLs during normalization |
| `--no-norm` | — | Disable URL normalization |
| `--readonly` | — | Non-destructive mode (no page modifications) |

### Usage examples

```bash
# Depth=1: extract all links from the homepage and load each linked page
browser4-cli crawl "https://platon.ai" --out-link-selector "a[href]"

# Depth=2: follow links two levels deep, only matching product pages
browser4-cli crawl "https://shop.example.com" \
  --depth 2 \
  --out-link-selector "a.product-link" \
  --out-link-pattern "/product/" \
  --top-links 10

# With LoadOptions passthrough for advanced control
browser4-cli crawl "https://example.com" \
  -ol "a[href]" \
  -a "-refresh -nMaxRetry 5 -interactLevel FAST"
```

Behind the scenes: depth=1 reuses `PulsarSession.submitForOutPages`; depth>1 uses a BFS continuous crawl with visited-URL dedup and recursive link submission.

## Loop CLI

Execute a task repeatedly on a configurable interval. Progress is persisted to disk and can be resumed after interruption.

```bash
browser4-cli loop <task> [--interval=3600] [--count=<N>] [--timeout=604800]
browser4-cli loop --shell <shell-command>
browser4-cli loop -- <browser4-cli-subcommand...>
browser4-cli loop --status
browser4-cli loop --stop
```

### Modes

| Mode | Syntax | Description |
|---|---|---|
| Plain text | `loop <task>` | Task sent to the Browser4 server. X-SQL is auto-detected. |
| Shell | `loop --shell <cmd>` | Task executed via OS shell (`cmd /C` or `sh -c`). |
| Subcommand | `loop -- <tokens...>` | Tokens passed to a nested `browser4-cli` process. |

### Key flags

| Flag | Short | Default | Description |
|---|---|---|---|
| `--interval` | `-i` | `3600` (1 hour) | Seconds between iterations |
| `--count` | `-n` | infinite | Maximum number of iterations |
| `--timeout` | `-t` | `604800` (1 week) | Maximum total duration in seconds |
| `--shell` | — | — | Execute task as a shell command |
| `--stop` | — | — | Stop a running loop and clear persisted state |
| `--status` | — | — | Show current loop state and progress |

### Persistence and resume

- After each iteration, progress is saved to `~/.browser4/loop-state.json`.
- If the process is interrupted (Ctrl+C, shutdown), running the same command again resumes from the last completed iteration.
- Use `--stop` to clear the persisted state and start fresh.
- Use `--status` to inspect the current loop without executing.

### Usage examples

```bash
# Plain text command every hour (default interval)
browser4-cli loop "load https://example.com and extract the page title"

# Shell command every 60 seconds, 10 iterations max
browser4-cli loop --shell "curl -s https://api.example.com/health" -i 60 -n 10

# Run a browser4-cli eval every 5 minutes
browser4-cli loop -- eval "document.title" -i 300

# X-SQL query, 5 iterations
browser4-cli loop "select dom.title from load_and_select('https://example.com')" --count 5

# Inspect current loop state
browser4-cli loop --status

# Stop a running/persisted loop
browser4-cli loop --stop
```

Full reference: **[references/loop.md](references/loop.md)**.

## Error Handling

- Commands requiring the backend (`open`, `attach`, `goto`, `snapshot`, `click`, etc.) exit non-zero if the backend is unreachable. Check with `browser4-cli list`.
- `attach` exits non-zero when it cannot find the target browser (no matching channel, no CDP endpoint listening on the given port).
- `attach` exits non-zero when `--cdp` is a channel name and no running browser with remote debugging enabled is found for that channel.
- `eval` exits non-zero when the JS expression throws.
- `snapshot` exits non-zero when the page isn't ready or the accessibility tree can't be captured.
- Stale sessions: prefer `goto` to auto-reopen rather than manually managing session state.

## References

- **Attach** — [references/attach.md](references/attach.md)
- **DOM Snapshot** — [references/domsnapshot.md](references/domsnapshot.md)
- **CSS Selector Bridge** — [references/css-selector-bridge.md](references/css-selector-bridge.md)
- **Crawl command** — [references/crawl.md](references/crawl.md)
- **Loop command** — [references/loop.md](references/loop.md)
- **Swarm command** — [references/swarm.md](references/swarm.md)
- **Storage state** — [references/storage-state.md](references/storage-state.md)
- **X-SQL** — [references/x-sql.md](references/x-sql.md)
