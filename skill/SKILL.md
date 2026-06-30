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
browser4-cli snapshot                              # capture accessibility tree (compact + boxes by default)
browser4-cli snapshot --filename=result.yaml       # named output (for workflow artifacts)
browser4-cli snapshot --no-boxes                   # omit bounding boxes to reduce output size
browser4-cli snapshot -i -d 5                      # interactive only, depth 5
browser4-cli snapshot -s "#content"                # scoped to CSS selector
browser4-cli snapshot --no-compact                 # include all structural nodes (disable compact)
browser4-cli snapshot --stdout                     # print raw snapshot content to stdout (for piping)
browser4-cli snapshot --stdout --page 1            # view first page of snapshot lines
browser4-cli snapshot --stdout --page-size 50 --page 2  # view 50 lines from page 2
browser4-cli snapshot --stdout --all               # disable pagination, show all content
browser4-cli snapshot --viewport=0,2,4             # capture specific viewports (single, list, range, or mixed)
browser4-cli snapshot --viewport=1-3               # capture viewports 1 through 3
```

Each snapshot now includes a `# Viewport State` header with metadata and each node shows `[box=x,y,width,height]` by default. The bounding box provides the positional data (`top`, `left`, `width`, `height`) that powers **PowerCSS `:expr()` selectors** — query nodes by visual features (size, position, content density) in X-SQL queries and `domsnapshot get` commands. See [references/power-dom.md](references/power-dom.md).

Pagination options for `--stdout`/`--raw` output:

| Option | Effect |
|---|---|
| `--page=<N>` | Page number (1-based, default: 1) |
| `--page-size=<N>` | Lines per page (default: 500) |
| `--all` | Disable pagination; show all content |

### Snapshot Grep

Search snapshot accessibility-tree YAML content with regex patterns and grep-style output:

```bash
browser4-cli snapshot grep <pattern> [--page N] [--page-size N] [--all]  # search snapshot YAML with regex; paginated by default (500 lines/page)
browser4-cli snapshot grep -i error                # case-insensitive search
browser4-cli snapshot grep -C 2 "timeout"          # show 2 lines of context
browser4-cli snapshot grep -F "literal"            # fixed-string (literal) matching — use for patterns with special chars
browser4-cli snapshot grep -c pattern              # print only match count
browser4-cli snapshot grep --selector main "text"  # search within a CSS selector's subtree
```

Supported grep options: `-i` (ignore-case), `-A N` (after-context), `-B N` (before-context), `-C N` (context), `-v` (invert-match), `-c` (count), `-l` (files-with-matches), `-F` (fixed-strings), `-w` (word-regexp), `--no-line-number`, `--selector`.

> **⚠️ Prerequisite:** `snapshot grep` searches the most recent accessibility-tree snapshot. Run `snapshot` first if you haven't captured one yet. Use `snapshot -v <N>` to narrow the capture to a specific viewport before grepping — the most relevant content is usually in viewport 0. `domsnapshot grep` similarly needs a prior `domsnapshot` capture.

| Flag | Effect |
|---|---|
| `-v, --viewport <spec>` | **First choice for large pages.** Capture specific viewports: single index (3), comma list (0,2,4), range (1-3), or mixed (0,2-4,7). Read the page viewport by viewport, like a human scrolling. |
| `-i, --interactive` | Only interactive elements (buttons, links, inputs) |
| `-c, --compact` | Remove empty structural elements (**enabled by default**) |
| `--no-compact` | Disable compact mode; include all structural nodes |
| `--boxes` | Include each element's bounding box as `[box=x,y,width,height]` (**enabled by default**) |
| `--no-boxes` | Disable bounding boxes to reduce output size |
| `-d, --depth <n>` | Limit tree depth |
| `-s, --selector <sel>` | Scope to CSS selector subtree |
| `-u, --urls` | Include href URLs for links |
| `--stdout` | Print snapshot content to stdout (for piping); alias: `--raw` |
| `--raw` | Alias for `--stdout` |

> **Tip:** On content-heavy pages (e-commerce, search results), snapshots can exceed 256KB.
> **Read the page viewport by viewport — just like a human scrolls.** Important content usually
> appears at the top of the page first; `--viewport=0` captures the most relevant viewport.
> Use `--viewport=1`, `--viewport=2`, etc. to continue down the page as needed:
> ```bash
> browser4-cli snapshot -v 0        # top of page — usually the most important content
> browser4-cli snapshot -v 1        # next scroll down
> browser4-cli snapshot -v 0-2      # first three viewports at once
> ```
> Other options for keeping output manageable: `-d 5`, `-s "<selector>"`, `-i`.
> Compact mode (on by default) strips empty structural wrappers.
> Never cat full snapshot files — use `domsnapshot get` for structured extraction.
> Use `snapshot grep` to search the YAML accessibility tree directly without loading it into an editor.
> For `--stdout` output, use `--page 1` for the first page or `--page-size` to control pagination.
>
> **⚠️ Interactive mode (`-i`) on e-commerce sites:** Many e-commerce product cards use generic `<div>`
> containers (not semantic `listitem`/`article` roles). Interactive mode strips these, hiding product
> listings from the snapshot. For shopping/search pages, use `--viewport=0` to read the top results
> first, or `-d 4` (shallow depth) over `-i`,
> or use `domsnapshot inspect` + `domsnapshot get` for structured data extraction.

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
browser4-cli eval --file=script.js [ref]    # read JS expression from file
browser4-cli eval --stdin [ref]             # read JS expression from stdin (avoids shell quoting)
browser4-cli eval --json "<js>" [ref]       # output result as valid JSON
browser4-cli resize <width> <height>
```

**Evaluate (`eval`):** Run JavaScript in the browser page.

| Option | Effect |
|---|---|
| `<expression>` | JavaScript expression or function to evaluate (positional argument) |
| `[ref]` | Optional CSS selector or element reference to scope the evaluation (e.g. `e5`) |
| `--file=<path>` | Read the JavaScript expression from a file instead of the command line |
| `--stdin` | Read the JavaScript expression from stdin — useful for piping multi-line scripts and avoiding shell quoting complexity |
| `--json` | Serialize the result as JSON: quotes strings, wraps scalar values. Without `--json`, strings are printed raw (unquoted), which can make empty strings and `null` indistinguishable. Use `--json` when consuming eval output programmatically. |

**Examples:**

```bash
# Simple evaluation
browser4-cli eval "document.title"

# With JSON output for programmatic use
browser4-cli eval --json "document.querySelectorAll('.price')"

# Read script from file (avoids shell escaping)
browser4-cli eval --file=extract-products.js

# Pipe a script via stdin (no quoting needed)
echo 'Array.from(document.querySelectorAll(".product")).map(el => el.textContent)' | browser4-cli eval --stdin --json

# Extract product data with JSON output
browser4-cli eval --json "Array.from(document.querySelectorAll('[data-component-type=\"s-search-result\"]')).map(el => ({title: el.querySelector('h2')?.textContent,price: el.querySelector('.a-price')?.textContent}))"
```

> **Windows/bash quoting guide:** Git Bash on Windows processes quotes differently than Linux shells.
> Complex JavaScript expressions with nested quotes require careful escaping (e.g. `'\\''` patterns),
> making expressions nearly unreadable. **Always prefer `--file` or `--stdin` on Windows:**
>
> ```bash
> # Option 1: --file (best for reusable scripts)
> browser4-cli eval --json --file=extract-products.js
>
> # Option 2: --stdin with echo + pipe (best for one-liners)
> echo 'Array.from(document.querySelectorAll("[data-component-type=\"s-search-result\"]")).map(el => ({title: el.querySelector("h2")?.textContent, price: el.querySelector(".a-price .a-offscreen")?.textContent}))' | browser4-cli eval --stdin --json
>
> # Option 3: --stdin with heredoc (best for multi-line scripts)
> browser4-cli eval --stdin --json << 'JS'
> Array.from(document.querySelectorAll('[data-component-type="s-search-result"]'))
>   .slice(0, 25)
>   .map(el => ({
>     title: el.querySelector('h2')?.textContent?.trim(),
>     price: el.querySelector('.a-price .a-offscreen')?.textContent,
>   }))
> JS
> ```
>
> These approaches avoid all shell quoting complexity — the JavaScript stays readable and error-free.
>
> **Alternative: X-SQL.** For structured data extraction (titles, prices, links, etc.), [X-SQL](#x-sql) eliminates quoting problems entirely — queries are plain SQL with no nested JavaScript strings. See the [X-SQL](#x-sql) section for e-commerce extraction examples that replace the heredoc above with a single SQL query.

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
browser4-cli domsnapshot get <field> [selector] [name] [--page N] [--page-size N] [--all]  # extract first match (text/html/attr via CSS); paginated by default (500 lines/page)
browser4-cli domsnapshot get all <field> [selector] [name] [--page N] [--page-size N] [--all]  # extract ALL matches (querySelectorAll); paginated by default
browser4-cli domsnapshot query [url] --sql <query>      # X-SQL query against DOM
browser4-cli domsnapshot summary                        # compressed page summary (WPSI)
browser4-cli domsnapshot export [--file <path>]         # save snapshot HTML (might be huge, don't read it directly)
browser4-cli domsnapshot grep [OPTIONS] <pattern> [--page N] [--page-size N] [--all]  # search snapshot HTML with regex; paginated by default (500 lines/page)
browser4-cli domsnapshot inspect [selector] [--max N]   # analyze DOM structure, suggest CSS selectors
```

Full reference: **[references/domsnapshot.md](references/domsnapshot.md)**.

> **PowerCSS `:expr()` selectors** work in `domsnapshot get` and `domsnapshot inspect` — query elements by size, position, and content:
> ```bash
> browser4-cli domsnapshot get all attr "img:expr(width>400)" src
> browser4-cli domsnapshot inspect "div:expr(width>400 && width<500 && char>100)"
> ```
> `:expr()` is intended for **static web page analysis** (DOM snapshot, X-SQL). It does **not** work with `eval` or real-time browser DOM inspection. See [PowerCSS reference](references/power-dom.md) for the full feature set.

> **Troubleshooting `domsnapshot get` empty results:** If `domsnapshot get all text/attr` returns `[]` for a CSS selector that clearly matches elements, the DOM snapshot's serialized representation may differ from the live DOM (e.g., deeply nested selectors like `h2 a` may not match due to structural flattening). Two reliable fallbacks:
> 1. **`eval` with live DOM:** `browser4-cli eval --json "Array.from(document.querySelectorAll('h2 a')).map(a => a.href)"`
> 2. **[X-SQL](#x-sql):** `browser4-cli domsnapshot query . --sql "SELECT DOM_ALL_HREFS(DOM, 'h2 a') FROM DOM_LOAD_AND_SELECT('...', ':root')"`
>
> Both execute against the live DOM and always return correct results. X-SQL also adds filtering, sorting, and caching.

### Shopping / E-commerce Workflow

A common use case is extracting structured product data from search results. The recommended approach:

```bash
# 1. Navigate and search
browser4-cli goto "https://www.amazon.com"
browser4-cli fill <searchbox-ref> "Laser-Engraved Crystal"
browser4-cli press Enter
browser4-cli wait --load=networkidle

# 2. Discover CSS selectors for product cards
browser4-cli domsnapshot inspect "[data-component-type=\"s-search-result\"]" --max 2

# 3. Extract structured product data in one eval call
browser4-cli eval --stdin --json << 'JS'
Array.from(document.querySelectorAll('[data-component-type="s-search-result"]'))
  .slice(0, 48)
  .map(el => ({
    title: el.querySelector('h2')?.textContent?.trim(),
    price: el.querySelector('.a-price .a-offscreen')?.textContent,
    rating: el.querySelector('.a-icon-alt')?.textContent,
    url: el.querySelector('h2 a')?.href,
    asin: el.getAttribute('data-asin'),
  }))
JS

# 4. Navigate to detail pages with polite delays
browser4-cli goto "https://www.amazon.com/dp/B0CXJ1NT4B"
browser4-cli wait 1500
browser4-cli eval --json "JSON.stringify({price: document.querySelector('.a-price .a-offscreen')?.textContent, rating: document.querySelector('#acrPopover')?.title, reviews: document.querySelector('#acrCustomerReviewText')?.textContent})"
```

> **Why eval over domsnapshot for e-commerce?** Amazon product cards use deeply nested generic `<div>` structures.
> `domsnapshot get all text` works well for titles and prices, but `domsnapshot get all attr ... href` may return
> empty for nested link selectors. JavaScript `querySelectorAll` against the live DOM is the most reliable fallback.
> For a cleaner, quoting-free alternative, see the [X-SQL](#x-sql) section below — it can express the same
> extraction declaratively without nested JavaScript strings.

### Bridging snapshot refs to CSS selectors

`domsnapshot` needs CSS selectors, not `e5` refs. Bridge with (pick one):

1. Construct from snapshot line: `- textbox "Email" [ref=e10]:` → `[placeholder="Email"]` or `input[type="email"]`
2. `browser4-cli get attr <ref> id` or `get attr <ref> class`
3. `browser4-cli generate-locator <ref>`

**Never** cat full snapshot files. Always use targeted `domsnapshot get` or `domsnapshot query`.

Full reference: **[references/css-selector-bridge.md](references/css-selector-bridge.md)**.

## X-SQL

X-SQL is a SQL-based query language for extracting structured data from web pages. It eliminates shell quoting pain entirely — queries are plain SQL strings with no nested JavaScript, making it ideal for complex data extraction on Windows/bash.

**Use X-SQL when:** you need structured data from a page (titles, prices, links, images), you want to avoid JavaScript quoting complexity, or you need server-side filtering/sorting/transformation.

**Use `eval` when:** you need to interact with the live page (click, scroll, read dynamic state), call JavaScript APIs, or the page requires prior interaction before data is available.

### Quick Start

```bash
# Basic: load a page and extract text from all elements matching a CSS selector
browser4-cli domsnapshot query "https://example.com" --sql "SELECT DOM_TEXT(DOM) FROM DOM_LOAD_AND_SELECT('https://example.com', 'h1')"

# Structured extraction: multiple fields per result row
browser4-cli domsnapshot query "https://example.com" --sql "
SELECT
    DOM_FIRST_TEXT(DOM, 'h2 a span') AS title,
    DOM_FIRST_FLOAT(DOM, '.a-price .a-offscreen', 0.0) AS price,
    DOM_FIRST_TEXT(DOM, '.a-icon-alt') AS rating,
    DOM_FIRST_HREF(DOM, 'h2 a') AS url,
    DOM_ATTR(DOM, 'data-asin') AS asin
FROM DOM_LOAD_AND_SELECT('https://www.amazon.com/s?k=Laser-Engraved+Crystal', '[data-component-type=\"s-search-result\"]', 1, 48)
WHERE DOM_IS_NOT_NIL(DOM)
  AND STR_IS_NOT_BLANK(DOM_FIRST_TEXT(DOM, 'h2'))
ORDER BY DOM_FIRST_FLOAT(DOM, '.a-price .a-offscreen', 999999.0) ASC
"

# Visual feature selection with :expr() — pick images larger than 400x400
browser4-cli domsnapshot query "https://www.amazon.com/dp/B0CXJ1NT4B" --sql "
SELECT DOM_FIRST_IMG(DOM, 'img:expr(width > 400 && height > 400)') AS main_image
FROM DOM_LOAD_AND_SELECT('https://www.amazon.com/dp/B0CXJ1NT4B', ':root')
"
```

### How It Works

X-SQL queries always follow this pattern:

```sql
SELECT <expressions>
FROM DOM_LOAD_AND_SELECT(url, cssQuery [, offset, limit])
[WHERE <conditions>]
[ORDER BY <expression> [ASC|DESC]]
[LIMIT <n>]
```

`DOM_LOAD_AND_SELECT` loads the page (or fetches from cache), selects elements matching the CSS query, and returns them as a virtual table — one row per matched element. Each row has a `DOM` column (a `ValueDom` object) that you pass to DOM functions like `DOM_FIRST_TEXT`, `DOM_FIRST_HREF`, `DOM_ATTR`, etc.

### Key Advantages Over eval

| Concern | `eval` | X-SQL |
|---------|--------|------|
| **Shell quoting** | Nested JS strings → painful escaping on Windows/bash | Plain SQL string → minimal escaping |
| **Pagination** | Manual `.slice()` in JS | Built-in `offset, limit` in `DOM_LOAD_AND_SELECT` |
| **Filtering** | Manual `.filter()` in JS | `WHERE` clause with DOM/string predicates |
| **Sorting** | Manual `.sort()` in JS | `ORDER BY` with any expression |
| **Caching** | None (re-executes every time) | Built-in via `-expires 1h` load option |
| **Null handling** | Manual null checks in JS | `DOM_IS_NOT_NIL`, `STR_DEFAULT_IF_BLANK`, etc. |
| **Visual selection** | Manual `getBoundingClientRect()` checks in JS | `:expr()` pseudo-selector — query by size, position, content density |

### Common Patterns

**E-commerce product extraction** (equivalent to the eval-based workflow above):

```bash
browser4-cli goto "https://www.amazon.com"
browser4-cli fill <searchbox-ref> "Laser-Engraved Crystal"
browser4-cli press Enter
browser4-cli wait --load=networkidle

# One query replaces the entire eval heredoc — no quoting pain:
browser4-cli domsnapshot query . --sql "
SELECT
    DOM_FIRST_TEXT(DOM, 'h2 a span') AS title,
    DOM_FIRST_FLOAT(DOM, '.a-price .a-offscreen', 0.0) AS price,
    DOM_FIRST_TEXT(DOM, '.a-icon-alt') AS rating,
    DOM_FIRST_HREF(DOM, 'h2 a') AS url,
    DOM_ATTR(DOM, 'data-asin') AS asin
FROM DOM_LOAD_AND_SELECT('https://www.amazon.com/s?k=Laser-Engraved+Crystal', '[data-component-type=\"s-search-result\"]', 1, 48)
WHERE DOM_IS_NOT_NIL(DOM)
  AND STR_IS_NOT_BLANK(DOM_FIRST_TEXT(DOM, 'h2'))
ORDER BY DOM_FIRST_FLOAT(DOM, '.a-price .a-offscreen', 999999.0) ASC
"
```

**Scrape with caching** (fetch fresh if older than 1 hour):

```sql
SELECT DOM_FIRST_TEXT(DOM, 'title') AS page_title,
       DOM_FIRST_TEXT(DOM, 'h1') AS heading
FROM DOM_LOAD_AND_SELECT('https://example.com -expires 1h', 'head, h1')
```

**Extract key-value specs with regex:**

```sql
SELECT DOM_ALL_RE2(DOM, '.specs-table tr', '(.+?):\s*(.+)') AS specs
FROM DOM_LOAD_AND_SELECT('https://example.com/product/42', '.specs-table')
```

**Fallback chains** (try multiple selectors, use the first that returns content):

```sql
SELECT ARRAY_FIRST_NOT_BLANK(
    MAKE_ARRAY(
        DOM_FIRST_TEXT(DOM, 'h1.product-title'),
        DOM_FIRST_TEXT(DOM, '.product-name'),
        DOM_FIRST_TEXT(DOM, 'title'),
        'Unknown Product'
    )
) AS product_name
FROM DOM_LOAD_AND_SELECT('https://shop.example.com/product/42', 'body')
```

### PowerCSS `:expr()` — Visual Feature Selectors

The `:expr()` pseudo-selector queries elements by computed visual features — size, position, content density — directly in CSS selectors. This makes selectors resilient to HTML structure changes; the visual layout of a page changes far less often than its markup.

Available features: `top`, `left`, `width`, `height`, `char`, `img`, `a`, `child`, `sibling`, `dep`, `seq`, `txt_nd`, `txt_dns`. See [references/power-dom.md](references/power-dom.md) for the full reference.

**E-commerce: select only the main product image, skip thumbnails and icons:**

```sql
-- Main product photo is consistently large — thumbnails and logos are small
SELECT DOM_FIRST_ATTR(DOM, 'img:expr(width > 400 && height > 400)', 'src') AS main_image,
       DOM_FIRST_ATTR(DOM, 'img:expr(width < 150 && height < 150)', 'src') AS thumbnail,
       DOM_FIRST_FLOAT(DOM, '.a-price .a-offscreen:expr(width > 100)', 0.0) AS price
FROM DOM_LOAD_AND_SELECT('https://www.amazon.com/dp/B0CXJ1NT4B', ':root')
```

**Find the main content column by position and text density:**

```sql
-- Main content: wide, positioned after sidebar, with substantial text
SELECT DOM_CSS_SELECTOR(DOM) AS selector,
       DOM_TEXT_LEN(DOM) AS chars
FROM DOM_LOAD_AND_SELECT('https://example.com', 'div:expr(width > 400 && left > 100 && char > 500)')
ORDER BY DOM_TEXT_LEN(DOM) DESC
LIMIT 3
```

**Filter out noisy/tracking elements by size:**

```sql
-- Only select meaningful content blocks — skip 1px tracking pixels and empty divs
SELECT DOM_TEXT(DOM)
FROM DOM_LOAD_AND_SELECT('https://example.com', 'div:expr(width > 200 && height > 50 && char > 100)')
ORDER BY DOM_TEXT_LEN(DOM) DESC
```

**Fallback chain with `:expr()` — try specific selectors first, then generic visual rules:**

```sql
SELECT DOM_FIRST_ATTR(DOM,
    '#landingImage, #imgTagWrapperId img, #imageBlock img:expr(width > 400)',
    'data-old-hires'
) AS product_image
FROM DOM_LOAD_AND_SELECT('https://www.amazon.com/dp/B0CXJ1NT4B', ':root')
```

> **Scope:** `:expr()` is intended solely for **static web page analysis** (X-SQL, `domsnapshot get`, `domsnapshot inspect`). It does **not** work in `eval` or for real-time browser DOM inspection — use standard JavaScript `querySelector` with `getBoundingClientRect()` in `eval` instead. Positional features (`top`, `left`, `width`, `height`) come from the DOM snapshot's bounding box metadata.

**Content analysis** (find the content-heavy containers on a page):

```sql
SELECT DOM_CSS_SELECTOR(DOM) AS selector,
       DOM_TAG_NAME(DOM) AS tag,
       DOM_TEXT_LEN(DOM) AS text_chars,
       DOM_A(DOM) AS links,
       DOM_IMG(DOM) AS images
FROM DOM_LOAD_AND_SELECT('https://example.com', 'div,section,article,main')
WHERE DOM_TEXT_LEN(DOM) > 200
ORDER BY DOM_TEXT_LEN(DOM) DESC
```

### Function Reference

X-SQL provides ~200 functions across four namespaces:

| Namespace | Functions | Purpose |
|-----------|-----------|---------|
| `DOM_*` | ~110 | Element properties, text extraction, CSS selection, regex, tree navigation, bounding boxes |
| `DOM_FIRST_*` / `DOM_ALL_*` | ~50 | Batch extraction: `DOM_FIRST_TEXT(DOM, '.price')`, `DOM_ALL_HREFS(DOM, 'a')`, etc. |
| `STR_*` | ~90 | String manipulation: trim, split, regex, case conversion, padding, blank-checking |
| `ARRAY_*` | 3 | Array operations: join, first-not-blank, first-not-empty |

**Full reference:** [references/x-sql.md](references/x-sql.md) — master index with all functions, or dive directly into:
- [DOM_LOAD_AND_SELECT](references/x-sql-dom-load-select.md) — page loading
- [DomFunctions](references/x-sql-dom-functions.md) — core DOM operations
- [DomSelectFunctions](references/x-sql-dom-select-functions.md) — CSS selector-based extraction
- [StringFunctions](references/x-sql-string-functions.md) — string manipulation
- [ArrayFunctions](references/x-sql-array-functions.md) — array utilities

> **Tip:** X-SQL queries are also auto-detected in `loop` tasks — just pass the SQL as the task string:
> ```bash
> browser4-cli loop "select dom.title from load_and_select('https://example.com')" --count 5
> ```

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
browser4-cli swarm list                                # list all tracked swarm tasks
```

Full reference: **[references/swarm.md](references/swarm.md)**.

## Crawl CLI

Recursive website crawling — start from a seed URL and follow links up to a configurable depth.

```bash
browser4-cli crawl <url> [--depth=1] [--out-link-selector=<CSS>] [--out-link-pattern=<regex>] [--top-links=20] [--background]
browser4-cli crawl list      # list all tracked crawl tasks
```

### Command overview

| Command | Description |
|---|---|
| `crawl <url>` | Crawl a website starting from a URL, following links up to a configurable depth |
| `crawl list` | List all tracked crawl tasks and their status |

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
| `-bg`, `--background` | — | Submit crawl and return immediately; track with `crawl list` |

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

# Background crawl (returns immediately)
browser4-cli crawl "https://example.com" --background -d 2

# List tracked crawl tasks
browser4-cli crawl list
```

Behind the scenes: depth=1 reuses `PulsarSession.submitForOutPages`; depth>1 uses a BFS continuous crawl with visited-URL dedup and recursive link submission. Use `--background` for long crawls — the task ID is persisted locally and can be checked with `crawl list` or `crawl result <id>`.

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

## Paginating Through Results

Browser4 supports two complementary pagination strategies:

1. **Viewport pagination** (recommended first approach) — a single page may span multiple viewport-heights of content. Read the page viewport by viewport, just like a human scrolls:
   ```bash
   browser4-cli snapshot -v 0        # top of page — most important content
   browser4-cli snapshot -v 1        # next scroll down
   browser4-cli snapshot -v 0-3      # first four viewports at once
   browser4-cli snapshot -v all      # entire page (all viewports)
   ```

2. **Multi-page pagination** — navigate between separate pages of results:
   ```bash
   # 1. Capture the current page
   browser4-cli snapshot
   # 2. Search for the "Next" button/link
   browser4-cli snapshot grep -i "next"
   # 3. Click the Next link (use the ref from grep output)
   browser4-cli click <ref>
   # 4. Wait for the new page to load
   browser4-cli wait --load=networkidle
   # 5. Re-snapshot for the next page
   browser4-cli snapshot
   ```

> **Prefer viewport pagination first.** Important content usually appears at the top of a page; viewport 0 captures the most relevant information without loading new URLs. Multi-page navigation should be used when content genuinely spans separate URLs.

For bulk data extraction, consider using JavaScript `eval` to collect data from all visible results first (many sites load results beyond page 1 via infinite scroll or larger initial batches), or use `crawl` with `--depth 1` and an appropriate `--out-link-selector` for automated multi-page traversal.

## Polite Scraping

When navigating multiple pages rapidly, be respectful of the target website to avoid triggering rate limiting, CAPTCHAs, or IP blocks:

```bash
# Add waits between rapid navigations
browser4-cli goto "https://www.amazon.com/dp/B0CXJ1NT4B"
browser4-cli wait 2000      # 2-second polite delay
browser4-cli eval "document.title"

# For batch operations, insert sleep between items
for asin in B0CXJ1NT4B B0BGH5L5FX B0GF6N1NWM; do
  browser4-cli goto "https://www.amazon.com/dp/$asin"
  browser4-cli wait 1500    # 1.5s between product pages
  browser4-cli eval --json "JSON.stringify({title: document.title.split(':')[0]?.trim(), price: document.querySelector('.a-price .a-offscreen')?.textContent})"
done
```

**Guidelines:**
- Add `wait 1000-3000` (1–3 seconds) between rapid navigations on the same site
- Amazon and similar sites may show CAPTCHAs under aggressive automated access — longer delays reduce risk
- Use `eval` or `domsnapshot get all` to batch-extract data from a single page load when possible, rather than navigating to each detail page individually
- Prefer `crawl` with conservative `--depth` and `--page-load-timeout` for automated multi-page traversal — it includes built-in rate limiting

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
- **PowerCSS** — [references/power-dom.md](references/power-dom.md)
- **X-SQL** — [references/x-sql.md](references/x-sql.md)
