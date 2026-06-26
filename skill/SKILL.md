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
browser4-cli goto <url>                         # navigate (auto-opens/reconnects session)
browser4-cli go-back | go-forward | reload
browser4-cli close                              # close current session
browser4-cli -s=<name> open|goto <url>          # target a named session
```

`goto` auto-reuses the active session; auto-opens a fresh one if stale or missing. Prefer `goto` over manual session management.

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
browser4-cli domsnapshot export [--file <path>]         # save snapshot HTML
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

## Error Handling

- Commands requiring the backend (`open`, `goto`, `snapshot`, `click`, etc.) exit non-zero if the backend is unreachable. Check with `browser4-cli list`.
- `eval` exits non-zero when the JS expression throws.
- `snapshot` exits non-zero when the page isn't ready or the accessibility tree can't be captured.
- Stale sessions: prefer `goto` to auto-reopen rather than manually managing session state.

## References

- **DOM Snapshot** — [references/domsnapshot.md](references/domsnapshot.md)
- **CSS Selector Bridge** — [references/css-selector-bridge.md](references/css-selector-bridge.md)
- **Swarm command** — [references/swarm.md](references/swarm.md)
- **Storage state** — [references/storage-state.md](references/storage-state.md)
- **X-SQL** — [references/x-sql.md](references/x-sql.md)
