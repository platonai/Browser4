---
name: browser4-cli
description: Automates browser interactions for web testing, form filling, screenshots, and data extraction. Use when the user needs to navigate websites, interact with web pages, fill forms, take screenshots, test web applications, or extract information from web pages.
allowed-tools: Bash(browser4-cli:*)
---

# Browser Automation with browser4-cli

Browser automation CLI for AI agents — Chrome/Chromium via CDP with accessibility-tree snapshots.

## Quick Start (Core Interaction Loop)

Every browser4-cli session follows this pattern. Commit it to memory:

```
1. NAVIGATE    browser4-cli goto <url>              # auto-opens/reconnects session
2. SNAPSHOT    browser4-cli snapshot -v 0            # capture accessibility tree (viewport 0 = top)
3. INTERACT    browser4-cli click <ref>              # use refs from the snapshot
              browser4-cli fill <ref> <value>
              browser4-cli press Enter
4. RE-SNAPSHOT browser4-cli snapshot -v 0 --auto-diff # ⚠️ REFS ARE SINGLE-USE — diff shows exactly what changed
5. EXTRACT     browser4-cli domsnapshot get ...      # or eval, or X-SQL (see Choosing an Extraction Method)
```

> **🔴 THE GOLDEN RULE: Refs are single-use.** Element refs (`e5`, `e12`) are Chrome DevTools Protocol backend node IDs — ephemeral integers that become invalid after ANY page-modifying command (click, type, fill, goto, reload, tab switch). Always re-snapshot before using refs. Never store refs across interactions.

### Minimal Session (Copy-Paste Template)

```bash
browser4-cli goto "https://example.com"
browser4-cli snapshot -v 0          # read the page; note refs for elements you need
browser4-cli fill <ref> "<value>"   # interact
browser4-cli press Enter
browser4-cli wait --load=networkidle
browser4-cli snapshot -v 0 --auto-diff  # verify what changed (diff vs previous snapshot)
browser4-cli domsnapshot get text "<css-selector>"   # extract data
```

## Concepts

### Snapshots & Element References

After commands that modify browser state (`open`, `click`, `type`, etc.), browser4-cli saves an **accessibility-tree snapshot** — a YAML file showing the page structure as nested elements with roles, accessible names, and refs:

```yaml
- generic [ref=e7]:
  - link "News" [ref=e191]:
    - /url: https://example.com/news
  - textbox "Search query" [ref=e35]:
    - /multiline: "true"
  - button "Search" [ref=e25]
  - list [ref=e374]:
    - listitem [level=1] [ref=e375]:
      - link "Headline Title" [ref=e376]:
        - /url: https://...
```

Each interactive element has a **ref** (`e5`, `e12`) used to target it in subsequent commands. Elements show roles (`button`, `link`, `textbox`, `generic`, `list`, `listitem`, `image`, `paragraph`) and accessible names. Properties (`/url`, `/multiline`) are nested with indentation. Extra attributes like `[level=1]` or `[box=x,y,width,height]` may appear alongside the ref.

### Ref Lifecycle

Element refs are Chrome DevTools Protocol **backend node IDs** — integers Chrome assigns to DOM nodes. They are **ephemeral**:

| Operation | Refs still valid? | Notes |
|---|---|---|
| Same-page interaction (`click`, `type`, `fill`) | **No** — re-snapshot after | Every page-modifying command regenerates the accessibility tree |
| `goto` (navigate to new URL) | **No** | New document → new backend node IDs |
| `go-back` / `go-forward` | **No** | Cached pages may coincidentally reuse IDs — not guaranteed |
| `reload` | **No** | Chrome may reassign backend node IDs on reload |
| Tab switch (`tab-select`) | **No** — re-snapshot | Different tab → different document |
| `snapshot` (re-capture) | **No** (old refs); **Yes** (new refs) | New snapshot → fresh refs; discard previous ones |

**Best practice:** Re-snapshot after **any** navigation or page-modifying interaction. Treat refs as single-use: capture → act → re-snapshot.

### Sessions

Named sessions isolate browser state (cookies, localStorage, tabs). Use `-s=<name>` to target a named session. `goto` auto-opens/reconnects a session; you rarely need to manage sessions manually.

## Commands

### Navigation & Session

```bash
browser4-cli open [--headed|--headless] [url]
browser4-cli goto <url>                         # auto-opens/reconnects session (prefer this)
browser4-cli go-back | go-forward | reload
browser4-cli close
browser4-cli -s=<name> open|goto <url>          # target a named session
```

### Attach — Connect to an Existing Browser

```bash
browser4-cli attach --cdp=<channel|url|port> [--endpoint=<server-url>] [-s=<name>]
```
Connect to an already-running Chrome/Edge via CDP. Supports channel names (`chrome`, `msedge`), CDP URLs, bare ports, and remote Browser4 servers. Full reference: **[references/attach.md](references/attach.md)**.

### Interaction

```bash
browser4-cli click <ref> | dblclick <ref> | hover <ref>
browser4-cli type "<text>"                      # type into focused element
browser4-cli fill <ref> <value>                 # clear + type into input/textarea
browser4-cli select <ref> "<val>"               # select dropdown option
browser4-cli check <ref> | uncheck <ref>
browser4-cli drag <from-ref> <to-ref>
```

### Keyboard & Mouse

```bash
browser4-cli press <key>                        # Enter, ArrowDown, Tab, Escape, etc.
browser4-cli keydown|keyup <key>
browser4-cli mousemove <x> <y>
browser4-cli mousedown|mouseup [right]
browser4-cli mousewheel <dx> <dy>
```

### Snapshots

```bash
browser4-cli snapshot                              # capture accessibility tree (compact + boxes by default)
browser4-cli snapshot --auto-diff                  # diff against previous snapshot — show only what changed
browser4-cli snapshot -v 0                         # capture specific viewport (single, list, range, or mixed)
browser4-cli snapshot -v 0-3                       # capture viewports 0 through 3
browser4-cli snapshot -i -d 5                      # interactive only, depth 5
browser4-cli snapshot -s "#content"                # scoped to CSS selector
browser4-cli snapshot --stdout --page 1            # paginated stdout (page-size 500 default)
browser4-cli snapshot --filename=result.yaml       # named output for workflow artifacts
```

Full reference: **[references/domsnapshot.md](references/domsnapshot.md)**. Key flags: `-v`/`--viewport`, `-i`/`--interactive`, `-c`/`--compact`, `-d`/`--depth`, `-s`/`--selector`, `-u`/`--urls`, `--auto-diff`, `--boxes`/`--no-boxes`, `--stdout`/`--raw`, `--page`, `--page-size`, `--all`.

> **🔴 Don't cat snapshot files.** Snapshots can exceed 256KB. Use viewport pagination (`snapshot -v 0`), `snapshot grep <pattern>`, or `snapshot --stdout --page 1` instead.
>
> **🔄 Verify interactions with `--auto-diff`:** After clicking or filling, re-snapshot with `--auto-diff` to see only what changed — the fastest way to confirm an action had the expected effect.
>
> **⚠️ Interactive mode on e-commerce:** Many product cards use generic `<div>` containers (not semantic `listitem`/`article`). Interactive mode strips these. Prefer `--viewport=0` or `domsnapshot` for shopping/search pages.

### Snapshot Grep

```bash
browser4-cli snapshot grep <pattern> [--page N] [--all]   # search snapshot YAML with regex
browser4-cli snapshot grep -i error                       # case-insensitive
browser4-cli snapshot grep -C 2 "timeout"                 # context lines
browser4-cli snapshot grep -F "literal"                   # fixed-string matching
browser4-cli snapshot grep --selector main "text"         # search within CSS selector subtree
```

Supports: `-i`, `-A N`, `-B N`, `-C N`, `-v`, `-c`, `-l`, `-F`, `-w`, `--no-line-number`, `--selector`. Requires a prior `snapshot` capture.

### Element Data Extraction (get)

```bash
browser4-cli get <text|html|box|styles> <ref|selector>
browser4-cli get <property|attr> <ref|selector> <name>
browser4-cli generate-locator <ref>             # get a CSS selector from a snapshot ref
```

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
browser4-cli tab-list | tab-new [url] | tab-close [index] | tab-select <index>
```

### Screenshots & Evaluate

```bash
browser4-cli screenshot [ref] [--filename=page.png]
browser4-cli eval "<js>" [ref]              # evaluate JS, optionally scoped to element
browser4-cli eval --json "<js>" [ref]       # output result as valid JSON
browser4-cli eval --file=script.js [ref]    # read JS from file (avoids shell quoting)
browser4-cli eval --stdin [ref]             # read JS from stdin (avoids shell quoting)
browser4-cli resize <width> <height>
```

> **Windows/bash quoting:** Complex JS expressions with nested quotes require painful escaping. **Always prefer `--file` or `--stdin` on Windows.** For structured data extraction, X-SQL eliminates quoting problems entirely — queries are plain SQL.

### Storage

```bash
browser4-cli state-save [file.json] | state-load <file.json>
browser4-cli cookie-list | cookie-get|set|delete | cookie-clear
browser4-cli <localstorage|sessionstorage>-<list|get|set|delete|clear> [args...]
```
Full reference: **[references/storage-state.md](references/storage-state.md)**.

### Browser Sessions

```bash
browser4-cli list              # show all sessions and their state
browser4-cli attach --cdp=<channel|url>  # attach to an existing browser
browser4-cli close-all         # close all sessions, keep backend running
browser4-cli kill-all          # stop backend + kill all browser processes
```

## DOM Snapshot

Static DOM queries (CSS selectors) for structured data extraction — unlike interactive `snapshot` which provides accessibility-tree refs.

```bash
browser4-cli domsnapshot                                # capture static DOM snapshot
browser4-cli domsnapshot get <field> [selector] [--page N] [--all]  # extract first match (text/html/attr via CSS)
browser4-cli domsnapshot get all <field> [selector] [--page N] [--all]  # extract ALL matches
browser4-cli domsnapshot query [url] --sql <query>      # X-SQL query against DOM
browser4-cli domsnapshot summary                        # compressed page summary
browser4-cli domsnapshot export [--file <path>]         # save snapshot HTML
browser4-cli domsnapshot grep [OPTIONS] <pattern>       # search snapshot HTML with regex
browser4-cli domsnapshot inspect [selector] [--max N]   # analyze DOM structure, suggest CSS selectors
```

Full reference: **[references/domsnapshot.md](references/domsnapshot.md)**.

> **PowerCSS `:expr()` selectors** query elements by visual features (size, position, content density) — resilient to HTML structure changes:
> ```bash
> # Select images larger than 400x400 (skip thumbnails/icons)
> browser4-cli domsnapshot get all attr "img:expr(width>400 && height>400)" src
>
> # Find wide content blocks with substantial text
> browser4-cli domsnapshot inspect "div:expr(width>400 && left>100 && char>500)"
> ```
> Full reference: **[references/power-dom.md](references/power-dom.md)**.
>
> **Bridging snapshot refs to CSS selectors:** Use `get attr <ref> id`/`class`, `generate-locator <ref>`, or construct from snapshot info. Full reference: **[references/css-selector-bridge.md](references/css-selector-bridge.md)**.
>
> **E-commerce & real-world recipes:** See **[references/domsnapshot-scenarios.md](references/domsnapshot-scenarios.md)** for 16 end-to-end recipes covering e-commerce, news, SEO, pricing, job boards, and more.

## Choosing an Extraction Method

| Method | Best for | Avoid for |
|--------|----------|-----------|
| **`snapshot` + refs** | Interactive workflows (click, fill, navigate) | Static data extraction |
| **`domsnapshot get`** | Extracting specific fields via CSS selectors | Deeply nested selectors (may return `[]`); fall back to `eval` or X-SQL |
| **`eval --json`** | Live DOM access, complex JS transformations | Windows/bash quoting (use `--stdin` or `--file`) |
| **X-SQL** | **Bulk data extraction** — structured extraction with filtering, sorting, pagination. No quoting pain | Interactive workflows, live page manipulation |
| **`extract` / `summarize`** | Natural language extraction, AI-powered | High-volume extraction (cost/latency); requires LLM API key |

## X-SQL

X-SQL is a SQL-based query language for extracting structured data from web pages. It eliminates shell quoting pain entirely — queries are plain SQL with no nested JavaScript.

**Use X-SQL when:** you need structured data (titles, prices, links, images), want to avoid JavaScript quoting complexity, or need server-side filtering/sorting/transformation.

**Use `eval` when:** you need to interact with the live page (click, scroll, read dynamic state), call JavaScript APIs, or the page requires prior interaction.

### Pattern

```sql
SELECT <expressions>
FROM DOM_LOAD_AND_SELECT(url, cssQuery [, offset, limit])
[WHERE <conditions>]
[ORDER BY <expression> [ASC|DESC]]
[LIMIT <n>]
```

`DOM_LOAD_AND_SELECT` loads the page (or fetches from cache), selects elements matching the CSS query, and returns them as a virtual table — one row per matched element. Each row has a `DOM` column passed to DOM functions like `DOM_FIRST_TEXT`, `DOM_FIRST_HREF`, `DOM_ATTR`, etc.

### Key Advantages Over eval

| Concern | `eval` | X-SQL |
|---------|--------|------|
| **Shell quoting** | Nested JS strings → painful escaping | Plain SQL string → minimal escaping |
| **Pagination** | Manual `.slice()` in JS | Built-in `offset, limit` in `DOM_LOAD_AND_SELECT` |
| **Filtering** | Manual `.filter()` in JS | `WHERE` clause with DOM/string predicates |
| **Sorting** | Manual `.sort()` in JS | `ORDER BY` with any expression |
| **Caching** | None (re-executes every time) | Built-in via `-expires 1h` load option |
| **Null handling** | Manual null checks in JS | `DOM_IS_NOT_NIL`, `STR_DEFAULT_IF_BLANK`, etc. |
| **Visual selection** | Manual `getBoundingClientRect()` | `:expr()` pseudo-selector — query by size, position, content density |

### Example: E-commerce Product Extraction

```bash
browser4-cli domsnapshot query . --sql "
SELECT
    DOM_FIRST_TEXT(DOM, 'h2 a span') AS title,
    DOM_FIRST_FLOAT(DOM, '.a-price .a-offscreen', 0.0) AS price,
    DOM_FIRST_TEXT(DOM, '.a-icon-alt') AS rating,
    DOM_FIRST_HREF(DOM, 'h2 a') AS url,
    DOM_ATTR(DOM, 'data-asin') AS asin,
    DOM_FIRST_ATTR(DOM, 'img:expr(width>200 && height>200)', 'src') AS image
FROM DOM_LOAD_AND_SELECT('https://www.amazon.com/s?k=...', '[data-component-type=\"s-search-result\"]', 1, 48)
WHERE DOM_IS_NOT_NIL(DOM)
  AND STR_IS_NOT_BLANK(DOM_FIRST_TEXT(DOM, 'h2'))
ORDER BY DOM_FIRST_FLOAT(DOM, '.a-price .a-offscreen', 999999.0) ASC
"
```

### Function Reference

X-SQL provides ~200 functions across four namespaces:

| Namespace | Functions | Purpose |
|-----------|-----------|---------|
| `DOM_*` | ~110 | Element properties, text extraction, CSS selection, regex, tree navigation |
| `DOM_FIRST_*` / `DOM_ALL_*` | ~50 | Batch extraction: `DOM_FIRST_TEXT(DOM, '.price')`, `DOM_ALL_HREFS(DOM, 'a')` |
| `STR_*` | ~90 | String manipulation: trim, split, regex, case conversion, padding |
| `ARRAY_*` | 3 | Array operations: join, first-not-blank, first-not-empty |

**Full reference:** **[references/x-sql.md](references/x-sql.md)** — master index with all functions, plus leaf files:
- [DOM_LOAD_AND_SELECT](references/x-sql-dom-load-select.md)
- [DomFunctions](references/x-sql-dom-functions.md)
- [DomSelectFunctions](references/x-sql-dom-select-functions.md)
- [StringFunctions](references/x-sql-string-functions.md)
- [ArrayFunctions](references/x-sql-array-functions.md)
- [PowerCSS :expr()](references/power-dom.md) — visual feature selectors

## AI-Powered Extraction & Summarization

```bash
browser4-cli extract "<prompt>" [--schema=...]   # structured data via AI
browser4-cli summarize "<prompt>" [--selector=...] # page summarization
```
Requires an LLM API key. Full reference: **[references/agent.md](references/agent.md)** (covers `extract`, `summarize`, `agent run|status|result`, and provider configuration).

## Swarm CLI

Parallel scraping across multiple browser contexts. Full reference: **[references/swarm.md](references/swarm.md)**.

```bash
browser4-cli swarm create [--max-browser-contexts=3] [--display-mode=HEADLESS]
browser4-cli swarm submit <url> [--seed-file=./urls.txt]
browser4-cli swarm query <url> --sql "<query>"
browser4-cli swarm status|result <id> | swarm list
```

## Crawl CLI

Recursive website crawling — start from a seed URL and follow links. Full reference: **[references/crawl.md](references/crawl.md)**.

```bash
browser4-cli crawl <url> [--depth=1] [--out-link-selector=<CSS>] [--out-link-pattern=<regex>] [--background]
browser4-cli crawl list
```

## Loop CLI

Repeated task execution with persistence/resume. Full reference: **[references/loop.md](references/loop.md)**.

```bash
browser4-cli loop <task> [--interval=3600] [--count=<N>] [--timeout=604800]
browser4-cli loop --shell <shell-command>
browser4-cli loop -- <browser4-cli-subcommand...>
browser4-cli loop --status | --stop
```

Three modes: plain text (X-SQL auto-detected), shell command, and browser4-cli subcommand. Progress persists to `~/.browser4/loop-state.json` — interrupted loops resume from the last completed iteration.

## Paginating Through Results

Two complementary strategies:

1. **Viewport pagination** (recommended first): a single page spans multiple viewport-heights. Read viewport by viewport:
   ```bash
   browser4-cli snapshot -v 0        # top of page — most important content
   browser4-cli snapshot -v 1        # next scroll down
   browser4-cli snapshot -v 0-3      # first four viewports at once
   browser4-cli snapshot -v all      # entire page
   ```

2. **Multi-page pagination**: navigate between separate pages of results. Snapshot → `snapshot grep -i "next"` → click the "Next" ref → `wait --load=networkidle` → re-snapshot.

> **Prefer viewport pagination first.** Important content usually appears at the top; viewport 0 captures the most relevant information without loading new URLs.

For bulk data extraction, prefer **X-SQL** — it handles pagination, filtering, sorting, and caching in a single query. For interactive multi-page traversal, use `crawl` with `--depth 1` and `--out-link-selector`.

## Polite Scraping

Add `wait 1000-3000` between rapid navigations to avoid rate limiting and CAPTCHAs. Batch-extract from single page loads when possible; use `crawl` for automated traversal (built-in rate limiting). Full reference: **[references/polite-scraping.md](references/polite-scraping.md)**.

## Error Handling & Recovery

Commands exit non-zero on failure. Common recoveries: re-snapshot for stale refs, `wait --load=networkidle` for un-ready pages, `--stdin`/`--file` for eval quoting errors, and `goto` to auto-restart stale sessions. Full reference: **[references/error-handling.md](references/error-handling.md)**.

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

For Linux/macOS and other install methods, see the [full installation guide](docs/cli-install-upgrade.md).

## References

- **Attach** — [references/attach.md](references/attach.md)
- **DOM Snapshot** — [references/domsnapshot.md](references/domsnapshot.md)
- **DOM Snapshot Scenarios** — [references/domsnapshot-scenarios.md](references/domsnapshot-scenarios.md) (16 real-world recipes)
- **CSS Selector Bridge** — [references/css-selector-bridge.md](references/css-selector-bridge.md)
- **Agent (extract/summarize)** — [references/agent.md](references/agent.md)
- **Crawl command** — [references/crawl.md](references/crawl.md)
- **Loop command** — [references/loop.md](references/loop.md)
- **Swarm command** — [references/swarm.md](references/swarm.md)
- **Storage state** — [references/storage-state.md](references/storage-state.md)
- **Polite Scraping** — [references/polite-scraping.md](references/polite-scraping.md)
- **Error Handling & Recovery** — [references/error-handling.md](references/error-handling.md)
- **PowerCSS :expr()** — [references/power-dom.md](references/power-dom.md)
- **X-SQL** — [references/x-sql.md](references/x-sql.md)
