Reading prompt from stdin...
2026-08-14T07:24:09.183458Z ERROR codex_core::session::session: failed to load skill C:\Users\pereg\.agents\skills\prd-generator\SKILL.md: missing YAML frontmatter delimited by ---
2026-08-14T07:24:09.183496Z ERROR codex_core::session::session: failed to load skill C:\Users\pereg\.agents\skills\tech-blog-cn\SKILL.md: missing YAML frontmatter delimited by ---
2026-08-14T07:24:09.183499Z ERROR codex_core::session::session: failed to load skill C:\Users\pereg\.agents\skills\wechat-formatter\SKILL.md: missing YAML frontmatter delimited by ---
2026-08-14T07:24:09.183501Z ERROR codex_core::session::session: failed to load skill C:\Users\pereg\.agents\skills\xiaohongshu-writer\SKILL.md: missing YAML frontmatter delimited by ---
OpenAI Codex v0.147.0
--------
workdir: D:\workspace\Browser4\Browser4-4.13
model: deepseek-v4-flash
provider: deepseek
approval: never
sandbox: danger-full-access
reasoning effort: high
reasoning summaries: none
session id: 019fff28-2631-7231-9734-8feef3fdd0d7
--------
user
You are evaluating the usability, discoverability, and reliability of browser4-cli while completing a real-world task.

## Preparation

Before performing any browser interaction:

0. Verify your working directory is the repository root: `D:/workspace/Browser4/Browser4-4.13`. If `pwd` is anything other than this directory, navigate there immediately with `cd "D:/workspace/Browser4/Browser4-4.13"`. All browser4-cli commands use `browser4-cli` which works from the repo root — stay in this directory for all commands.
    **IMPORTANT — Temporary files:** Create ALL temporary, intermediate, and scratch files (scripts, data dumps, HTML snapshots, JSON exports, markdown drafts, log files, etc.) inside ./.test-sessions/ (not the repo root). Before creating any file, ensure the directory exists with mkdir -p .test-sessions. Do NOT pollute the repository root with temporary files — every generated file that is not a permanent project asset belongs under .test-sessions/.
1. Run `browser4-cli help`.
2. Read `https://browser4.io/SKILL.md` completely.
3. Learn the available commands, workflows, and conventions directly from the documentation.
4. Do not assume any prior knowledge of browser4-cli.

## Backend Server

Production mode: browser4-cli connects to a separately-managed backend server. Ensure the **latest runtime bundle release** is deployed and running before starting the task. The CLI does not auto-start a server in production mode — if no server is reachable, commands will fail with a connection error.

## Command Invocation

Every browser4-cli command in this session MUST be invoked as:

browser4-cli <command>

For example:
  browser4-cli goto "https://example.com"
  browser4-cli snapshot -i
  browser4-cli click e5

Always use \$cliInvocation\ exactly as shown. In production mode \rowser4-cli\ IS the released product under test — do not substitute \./b4w.ps1\, \4\, or \cargo run\, which would test the local source tree instead of the released binary and invalidate the evaluation.

## Tool Usage Rules

* Use the invocation method above for ALL browser interactions.
* Do NOT use Playwright, Puppeteer, Selenium, CDP libraries, external browser APIs, or any other browser automation tool.
* If a browser action is required, first identify the documented browser4-cli command that should perform it.
* Prefer documented workflows over assumptions.
* If documentation is ambiguous, incomplete, inaccurate, outdated, or difficult to discover, record it as an issue.

## Evaluation Objective

Your goal is not only to complete the task, but also to evaluate the usability of browser4-cli from the perspective of a first-time user. Actively look for issues in these categories:

* **Installation & Setup** — prerequisites, environment assumptions, setup complexity, platform-specific issues
* **Discoverability** — help output quality, command discoverability, missing examples, missing documentation
* **Documentation** — incomplete, incorrect, or ambiguous instructions; undocumented behavior; inconsistent terminology
* **CLI Experience** — naming consistency, workflow clarity, session/browser lifecycle, state management
* **Task Execution** — navigation, search, content extraction, form interaction, waiting/synchronization, error recovery
* **Reliability** — unexpected failures, flaky behavior, misleading outputs, poor error messages, silent failures
* **User Experience** — learnability, efficiency, cognitive load, friction points, missing shortcuts or quality-of-life features

## Investigation Guidelines

Whenever you encounter a problem:

1. Attempt to understand the root cause.
2. Determine whether it is:

   * Product issue
   * Documentation issue
   * UX issue
   * Reliability issue
   * Discoverability issue
3. Continue the task whenever reasonably possible.
4. Record all findings, even if a workaround exists.

## Deliverables

### A. Task Result

Provide the requested task outcome.

### B. Execution Trace

Summarize:

* Commands used
* Major steps performed
* Important decisions made
* Workarounds required

### C. Issues Found

For every issue discovered, provide a structured entry using the format below.
Each issue MUST begin with an ### Issue N: <title> header and use **Bold Label:**
lines for every field.

#### Required format for each issue:

### Issue N: <brief descriptive title>

**Severity:** Critical | High | Medium | Low

**Category:** Product | Documentation | UX | Reliability | Discoverability

**Reproduction:** Exact command(s) or steps to reproduce the issue.

**Expected:** What should have happened.

**Actual:** What actually happened.

**Root Cause:** Your best analysis of the technical cause. Infer from observed
behavior when possible; note what investigation is needed when uncertain. This
is essential for an AI coder to fix the issue later.

**Code Pointer:** File path and function name where a fix should likely be
applied (e.g. cli/browser4-cli/src/snapshot.rs:render_snapshot()). If unknown,
leave the value empty — a follow-up analysis will fill it in.

**AI Suggested Improvement:**
- First concrete suggestion (use a bullet list — each suggestion on its own line)
- Second concrete suggestion
- Additional suggestions as needed

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:** (free-text for refinement details, counter-arguments, or follow-up actions)

Leave the checkboxes empty — they are for the human reviewer to fill in.

Use --- (horizontal rule) to separate issues.

#### Alternative JSON format (preferred for machine processing):

As an alternative to the markdown format above, you may deliver Sections C (Issues Found) and D (Overall Assessment) as a **single JSON code block**. This format is **preferred** — it ensures reliable machine parsing, while the markdown format above is a backward-compatible fallback. Sections A (Task Result) and B (Execution Trace) must still be written as prose above the JSON block.

```json
{
  "issues": [
    {
      "title": "Brief descriptive title",
      "severity": "Critical",
      "category": "Product",
      "reproduction": "Exact command(s) or steps to reproduce the issue.",
      "expected": "What should have happened.",
      "actual": "What actually happened.",
      "rootCause": "Your best analysis of the technical cause. Infer from observed behavior when possible; note what investigation is needed when uncertain. This is essential for an AI coder to fix the issue later.",
      "codePointer": "File path and function name where a fix should likely be applied (e.g. cli/browser4-cli/src/snapshot.rs:render_snapshot()). Leave empty string if unknown.",
      "suggestion": "- First concrete suggestion\n- Second concrete suggestion\n- Additional suggestions as needed"
    }
  ],
  "assessment": {
    "completionStatus": "Successful / Partially Successful / Failed — describe the overall task outcome",
    "successRate": "e.g. 80% — estimated percentage of task steps that succeeded",
    "issuesFound": 8,
    "majorBlockers": "Description of any major blockers encountered, or empty string if none.",
    "mostConfusingAspects": "Most confusing aspects for a first-time user.",
    "mostValuableImprovements": "Most valuable suggested improvements.",
    "usabilityRating": 5
  }
}
```

**Rules for the JSON format:**

- Every field is a string except **issuesFound** (integer) and **usabilityRating** (integer 1–10).
- **severity** must be one of: Critical, High, Medium, Low.
- **category** must be one of: Product, Documentation, UX, Reliability, Discoverability.
- Empty/unavailable fields should be an empty string "", never omitted.
- Use \n for multi-line content within string values (e.g. bullet lists in **suggestion**).
- Place the JSON block after Sections A and B. It replaces Sections C and D entirely.

### D. Overall Assessment

Include:

* Task completion status
* Estimated task success rate
* Number of issues found
* Major blockers
* Most confusing aspects
* Most valuable improvements
* Overall usability rating (1–10)

## Important

* Think like a new user who has never used browser4-cli before.
* Do not assume undocumented functionality exists.
* Prefer evidence gathered from actual usage over assumptions.
* Record both major and minor usability issues.
* The task is considered successful only if both the task itself and the usability evaluation are completed.
* **ALL temporary files** (scripts, data files, HTML exports, JSON dumps, screenshots, logs, markdown drafts, etc.) **MUST** be created inside ./.test-sessions/. Never write temporary files to the repository root. Before creating any file, run mkdir -p .test-sessions if the directory does not already exist.

# Task
Before running this scenario, ensure MockSite is running on localhost:18080 (`./bin/test.ps1 mock-site`). For the natural-language extraction branch, also configure an LLM API key that enables `extract`.

This scenario covers every branch in **SKILL.md §4a — Choosing an Extraction Method**.

## Acceptance Criteria

1. **AC1 — Interact first, then extract:** After interacting with a MockSite form or widget, use a fresh snapshot/HTML snapshot to extract the resulting confirmation text or updated page state.
2. **AC2 — Static page, one field:** Use `htmlsnapshot get text` to extract a single product field from a MockSite product detail page.
3. **AC3 — Static page, one field, all matches:** Use `htmlsnapshot get all text` to extract every matching product title from a MockSite listing page.
4. **AC4 — Static page, correlated multi-field rows:** Use `htmlsnapshot query` with `DOM_LOAD_AND_SELECT` to extract title, price, and URL from each MockSite product card.
5. **AC5 — Dynamic or complex page logic:** Use `eval --json` to compute structured data from the live DOM instead of relying on CSS-only extraction.
6. **AC6 — Natural-language extraction:** Use `extract` on a product page to ask for structured product data in plain English.
7. **AC7 — High-volume extraction:** Use a seed file plus `crawl --sql` on multiple MockSite product URLs to demonstrate the bulk path for many pages.

## Steps

### 1. Interact first, then extract (AC1)

1. Go to `http://localhost:18080/generated/form-filling.html`.
2. Capture an interactive snapshot (`snapshot -i`) and use refs to fill several fields plus one checkbox or dropdown.
3. Submit the form, wait for the resulting page state, then capture a fresh HTML snapshot.
4. Extract the confirmation banner, summary block, or submitted-value container from the post-submit page with `htmlsnapshot get text ...`.
5. Verify the extracted text reflects the values you entered.

### 2. Static page, one field (AC2)

1. Go to `http://localhost:18080/ec/dp/B0E000001`.
2. Capture an HTML snapshot.
3. Run:

```
htmlsnapshot get text "#productTitle"
```

4. Verify the result is the product title for that page.

### 3. Static page, one field, all matches (AC3)

1. Go to `http://localhost:18080/ec/b?node=1292115012`.
2. Capture an HTML snapshot.
3. Use `htmlsnapshot get all text` with the product-title selector pattern already used elsewhere in the MockSite tasks (for example `[class*="product-title"]`).
4. Verify the command returns all product titles from the Electronics listing page, not just the first one.

### 4. Static page, correlated multi-field rows (AC4)

1. Stay on `http://localhost:18080/ec/b?node=1292115012`.
2. Write an X-SQL query to a file that selects:
   - `DOM_BASE_URI(DOM)` as the source URL
   - `DOM_FIRST_TEXT(...)` for the product title
   - `DOM_FIRST_TEXT(...)` or `DOM_FIRST_FLOAT(...)` for the price
   - `DOM_FIRST_HREF(...)` for the detail-page link
3. Scope the query to repeating product-card containers with `DOM_LOAD_AND_SELECT(@url, 'div[class*="product-card"]', ...)`.
4. Run `htmlsnapshot query --sql @<file>`.
5. Verify each output row keeps the title, price, and URL aligned for the same product card.

### 5. Dynamic or complex page logic (AC5)

1. Go to `http://localhost:18080/generated/interactive-1.html`.
2. Use `eval --json` to return a structured object that combines several live-DOM facts, such as:
   - `document.title`
   - the number of buttons, links, and forms
   - the visible text of each heading
3. Verify the JSON payload contains the requested computed fields and that the values match the page.

### 6. Natural-language extraction (AC6)

1. Go to `http://localhost:18080/ec/dp/B0E000002`.
2. If an LLM key is configured, run `extract` with a prompt such as:
   - "Return the product title, displayed price, rating, and the top three feature bullets as JSON."
3. Verify the extracted structure matches the visible product page.
4. If no LLM key is configured, record this branch as environment-blocked rather than changing the scenario.

### 7. High-volume extraction (AC7)

1. Create a seed file containing at least 4 MockSite product detail URLs from `http://localhost:18080/ec/dp/`.
2. Write an X-SQL query file that extracts the product URL, title, and price from each page.
3. Run:

```
crawl --seed-file <path-to-seed-file> --depth 0 --sql @<query-file> --format table --refresh
```

4. Verify the result aggregates one structured row per seed URL and demonstrates the recommended bulk-extraction path for many pages.

codex
I'll start by verifying the environment, checking the CLI, and reading the documentation before any browser interaction.
  → Verify environment, CLI help, and SKILL.md documentation
  • Ensure MockSite running and LLM key configured for extract
  • AC1: Interact-then-extract on form-filling.html
  • AC2: Static page single field via htmlsnapshot get text
  • AC3: Static page all matches via htmlsnapshot get all text
  • AC4: Correlated multi-field rows via htmlsnapshot query X-SQL
  • AC5: Dynamic page logic via eval --json
  • AC6: Natural-language extraction via extract
  • AC7: High-volume extraction via crawl --sql seed file
  • Compile task result, execution trace, and issue report
exec
"C:\\Program Files\\PowerShell\\7\\pwsh.exe" -Command 'Get-Location; Test-Path .test-sessions; if (-not (Test-Path .test-sessions)) { New-Item -ItemType Directory -Path .test-sessions | Out-Null; Write-Output "created .test-sessions" } else { Write-Output ".test-sessions exists" }' in D:\workspace\Browser4\Browser4-4.13
exec
"C:\\Program Files\\PowerShell\\7\\pwsh.exe" -Command 'browser4-cli help' in D:\workspace\Browser4\Browser4-4.13
 succeeded in 316ms:
browser4-cli 4.13.4 — Control a Browser4 server from the command line
Usage: browser4-cli [-s <session>] <command> [args] [options]

╔══ Quick Start ═══════════════════════════════════════════════════════
║  These are the commands you'll use most often:
║
║    goto <url>         Navigate to a page (auto-starts server & session)
║    snapshot [-v <N>]  Capture accessibility tree with element refs
║    click <ref>        Click an element by its ref (e5) or CSS selector
║    fill <ref> "<txt>"  Fill a form field (--submit to press Enter)
║    htmlsnapshot       Capture static HTML for content extraction
║    dialog-accept      Accept a native JavaScript dialog (alert/confirm/prompt)
║
║  Learn more: browser4-cli --help <command>  or  --help-json for AI/scripts
╚══════════════════════════════════════════════════════════════════════════

── Common workflows ─────────────────────────────────────────────────
  Navigate & inspect:
    goto <url>  →  snapshot -v 0  →  click <ref>  →  snapshot -v 0
  Extract data:
    htmlsnapshot get text "<css>"           # single field
    htmlsnapshot query --sql @query.sql       # structured extraction
  Form interaction:
    fill <ref> "<text>" --submit              # fill + press Enter
  Handle dialogs (two-step):
    click <ref> --auto-dismiss-dialogs        # auto-accept dialog in one step
    OR click <ref>  →  dialog-accept          # separate click + accept
  Run JavaScript:
    eval --file script.js                     # read JS from file (no quoting issues)
  Bulk crawl:
    crawl <url> --out-link-selector "..." --depth 1 --sql @query.sql
  Parallel extraction:
    swarm create  →  swarm query --sql @q.sql --seed-file urls.txt  →  swarm result <id>

── Commands ─────────────────────────────────────────────────────────

  [Core]
  batch [command...]            Execute multiple commands in one invocation
  loop [task]                   Execute a task repeatedly on an interval. Supports plain text commands, x-sql queries
                                (auto-detected by the server), browser4-cli subcommands (after --), and shell commands
                                (--shell). Progress is persisted to disk under a configurable --name and can be resumed
                                after interruption. Use --pause [--name] to pause a running loop (or combine --pause
                                with a task to start in paused state), --resume [--name] to resume,
                                --pause-all/--resume-all/--stop-all for bulk operations, --list to see all loops,
                                --status [--name] to inspect, --stop [--name] to clear.
  select <ref> <val>            Select an option in a dropdown
  check <ref>                   Check a checkbox or radio button
  uncheck <ref>                 Uncheck a checkbox or radio button
  wait [target]                 Wait for a condition: element, time, text, URL pattern, page load, or JS expression
  get <mode> <selector> [name]  Extract data from a page element: text, html, box, styles, property, or attr
★ snapshot                    Capture page snapshot to obtain element refs. See flags below for filtering, scoping,
                                and output options.
★ snapshot grep [pattern]     Search snapshot YAML content using regex patterns with grep-style output
  snapshot list                 List saved snapshot files with timestamps and sizes
  snapshot clean                Remove old snapshot files from the snapshot directory
★ eval [expression] [--ref <ref>]  Evaluate JavaScript expression on page or element. Prefer --file or --stdin on
                                Windows to avoid shell quoting issues. Use --await for async code (fetch, Promises). Use
                                --wait-selector for pages that render content asynchronously (React/SPA). Objects and
                                arrays are serialized as JSON; use --json to JSON-wrap scalar results.
  dialog-accept [prompt]        Accept a dialog
  dialog-dismiss                Dismiss a dialog
  resize <w> <h>                Resize the browser window
  delete-data                   Delete session data
  generate-locator <ref>        Generate the best CSS selector (id, class, or nth-of-type path) for a snapshot ref or
                                CSS selector

  [Navigation]
★ goto <url>                  Navigate to a URL, auto-opening or refreshing the session when needed. Use --new-tab to
                                open the URL in a new browser tab instead of the current tab.
  go-back                       Go back to the previous page
  go-forward                    Go forward to the next page
  reload                        Reload the current page
★ page-info                   Show current page identity — title, URL, and key metadata without the full
                                accessibility tree

  [HTML Snapshot (htmlsnapshot)]
  htmlsnapshot                  Capture: take a static HTML snapshot of the current page and store it for later
                                querying. Returns page metadata — URL, title, size, timestamps, and interactive
                                elements (tag, class, id, aria, bounding box). Follow with `htmlsnapshot get`,
                                `inspect`, or `summary` to read from the stored snapshot. Short form of `htmlsnapshot
                                capture`.
  htmlsnapshot capture          Capture: take a static HTML snapshot of the current page and store it for later
                                querying. Returns page metadata — URL, title, size, timestamps, and interactive
                                elements (tag, class, id, aria, bounding box). Follow with `htmlsnapshot get`,
                                `inspect`, or `summary` to read from the stored snapshot.
★ htmlsnapshot get <field> [selector] [name]  Extract elements from the HTML snapshot stored in Browser4's page
                                storage (text, textcontent, html, attr). Supports batch mode for multi-step workflows.
★ htmlsnapshot get all <field> [selector] [name]  Extract ALL matching elements from the HTML snapshot
                                (querySelectorAll semantics); supports --offset and --limit for pagination. Supports
                                batch mode.
★ htmlsnapshot query [url]    Run X-SQL. DOM_LOAD_AND_SELECT(@url, ...) re-fetches the page fresh via the scrape API
                                (independent of the stored snapshot). htmlsnapshot capture is only needed for
                                inspect/get/summary, not for query with @url. IMPORTANT: CSS selectors in X-SQL must use
                                single quotes (SQL syntax); double quotes mean SQL identifiers.
  htmlsnapshot export [file]    Export snapshot HTML from Browser4's page storage to a local file. The file path can be
                                passed as a positional argument or via --file.
  htmlsnapshot summary          Summarize: read the stored HTML snapshot and produce a compressed Web Page Summary Index
                                (WPSI) — preserves page structure, key nodes, and stats in <1% of original HTML size.
                                Use `htmlsnapshot` first to capture the page into storage.
  htmlsnapshot grep [pattern]   Search the HTML snapshot HTML using regex patterns with grep-style output
  htmlsnapshot inspect [selector]  Inspect: read the stored HTML snapshot and discover CSS selectors for recurring
                                patterns (product cards, prices, titles). Use `htmlsnapshot` first to capture the page
                                into storage.

  [Keyboard]
★ press <key> [ref]           Press a key on the focused element or an optional target ref, `a`, `ArrowLeft`
★ type <text> [ref]           Type text into the focused element or an optional target ref. Passing a ref is
                                recommended for reliable targeting; without a ref, text may go nowhere if no element is
                                currently focused.
  keydown <key>                 Press a key down on the keyboard
  keyup <key>                   Press a key up on the keyboard
★ fill <ref> <text>           Fill text into editable element

  [Mouse]
  mousemove <x> <y>             Move mouse to a given position
  mousedown [button]            Press mouse down
  mouseup [button]              Press mouse up
  mousewheel <dx> <dy>          Scroll mouse wheel
★ scroll <direction> <pixels> Scroll the page in a given direction by the specified number of pixels (relative to
                                current position). Output shows direction, amount, and new scroll position.
★ click <ref> [button]        Perform click on a web page
  dblclick <ref> [button]       Perform double click on a web page
  drag <startRef> <endRef>      Perform drag and drop between two elements
  hover <ref>                   Hover over element on page

  [Capture]
★ screenshot [ref]            Screenshot of the current page or element
  pdf                           Save page as PDF

  [Tabs]
★ tab-list                    List all tabs with live URLs and titles from the browser
★ tab-new [url]               Create a new tab
  tab-close [index] [--guid <guid>]  Close a browser tab
★ tab-select [index] [--guid <guid>]  Select a browser tab

  [Storage]
  state-save [filename]         Save cookies and localStorage to a JSON file
  state-load <filename>         Load cookies and localStorage from a JSON file
  cookie-list                   List browser cookies
  cookie-get <name>             Get a cookie by name
  cookie-set <name> <value>     Set a browser cookie
  cookie-delete <name>          Delete a browser cookie by name
  cookie-clear                  Clear all browser cookies
  localstorage-list             List localStorage entries
  localstorage-get <key>        Get a localStorage value by key
  localstorage-set <key> <value>  Set a localStorage value
  localstorage-delete <key>     Delete a localStorage entry
  localstorage-clear            Clear localStorage
  sessionstorage-list           List sessionStorage entries
  sessionstorage-get <key>      Get a sessionStorage value by key
  sessionstorage-set <key> <value>  Set a sessionStorage value
  sessionstorage-delete <key>   Delete a sessionStorage entry
  sessionstorage-clear          Clear sessionStorage
  webdb export <urls> <output-dir>  Export pages from the web database to a local directory
  webdb normalize <url>         Normalize a URL for use as a web database key

  [DevTools]
  console [min-level]           List console messages
  cdp <method>                  Send an arbitrary Chrome DevTools Protocol (CDP) command and print the JSON result. For
                                advanced browser interactions not covered by standard WebDriver commands. CDP method
                                names use dot notation (e.g. "Page.captureScreenshot", "Runtime.evaluate",
                                "DOM.getDocument"). Optional params can be passed as a JSON object via --json.

  [Agent]
★ extract <instruction>       Extract structured data from the current page
  summarize [instruction]       Summarize page content using AI
  chat <prompt>                 Chat with AI without any auto-appended context. Requires: OPENROUTER_API_KEY (or
                                equivalent LLM API key) configured in the environment.
  chat-result <id>              Get the result of an async chat task
  agent run <task>              Run an autonomous agent task (async, returns task ID)
  agent status <id>             Check the status of a running agent task
  agent result <id>             Get the result of a completed agent task
  agent list                    List all tracked agent tasks and their status

  [Swarm]
  swarm create                  Create a swarm scrape session with parallel browser contexts
  swarm submit [url] [--sql <query>] [--seed-file <file>] [--wait]  Submit URL(s) or X-SQL payloads as scrape jobs.
                                Without --sql, each URL is fetched but no data is extracted — the resultSet will be
                                empty. Use swarm query for structured extraction.
  swarm query [url] --sql <query> [--seed-file <file>] [--wait]  Submit an X-SQL query to extract structured data from a
                                loaded webpage
  swarm status <id>             Check the status of a scrape job
  swarm result <id>             Get the result of a completed scrape job
  swarm list                    List all tracked swarm tasks and their status
  swarm close                   Close the swarm session and release browser resources
  crawl [url]                   Crawl a website starting from a URL or seed file, following links up to a configurable
                                depth. Use --seed-file for bulk URL lists, --sql for X-SQL data extraction from crawled
                                pages.
  crawl status <id>             Check status of a crawl task by its ID
  crawl result <id>             Get the result of a completed crawl task by its ID
  crawl cancel <id>             Cancel a running crawl task by its ID
  crawl clear                   Remove all terminal-state crawl tasks from the task store
  crawl list                    List all tracked crawl tasks and their status

  [Browser sessions]
★ open [url]                  Open a browser session or reconnect to an existing one
  attach                        Attach to an existing browser via CDP endpoint, channel name, or Browser4 Extension
  close                         Close the browser
  list                          List browser sessions with their status and next-open behavior
  session-default <name>        Set a named session as the DEFAULT (unnamed) session so it can be targeted without -s
  close-all                     Close all browser sessions without stopping the Browser4 backend
  kill-all                      Forcefully stop the Browser4 backend and kill Browser4 browser processes
  stop                          Gracefully stop the Browser4 server
  status                        Show Browser4 server status (version, port, health)
  doctor                        Run system diagnostics: build info, LLM status, and auto-clean stale daemon files. Use
                                --verbose to include logs and metrics, --fix for destructive repairs.
  doctor log [name]             List, view, or search log files. Use 'doctor log' for list, 'doctor log <name>' to view,
                                'doctor log <name> --tail' for recent lines, 'doctor log <name> grep <pattern>' to
                                search.
  doctor metrics [filter]       List, view, or search backend metrics. Use 'doctor metrics' for overview, 'doctor
                                metrics <filter>' to filter by name, 'doctor metrics grep <pattern>' to search.

  [Config]
  config                        List all CLI configuration values
  config get <key>              Get a CLI configuration value
  config set <key> <value>      Set a CLI configuration value
  config delete <key>           Remove a CLI configuration value, resetting it to default

  [Install]
  install                       Install the self-contained Browser4 runtime bundle (dependency jars + bundled JRE +
                                launcher scripts)
  uninstall                     Remove all globally installed browser4-cli (npm, cargo) and its runtime data
  upgrade                       Upgrade browser4-cli and the Browser4 runtime to the latest version (or a specified
                                release tag). Uses npm when available, otherwise the platform install script

  [Skills]
  skills                        List bundled skill names (AI agent instructions for browser4-cli)
  experience save <url> <trace> Save a task execution trace to the progressive experience memory. Records the steps
                                taken, selectors used, and outcome so future tasks on the same domain can replay them.
  experience query <url>        Query the progressive experience memory for stored knowledge about a domain. Returns
                                selectors, blockers, interaction hints, and confidence tier. Called automatically before
                                every agent task.
  experience list               List stored knowledge entries from the progressive experience memory. Filter by domain
                                or intent to inspect what the system has learned.
  experience deep-learn <url> <intent>  Run deep learning analysis on stored experience traces. Builds or updates
                                verified knowledge facts (selectors, blockers, page structure). Promotes knowledge from
                                hypothesis to verified when confidence thresholds are met.

  [Plugins]
  plugin list                   List all installed Browser4 plugins in the server's plugins directory
  plugin info <name>            Show details for a single installed plugin (manifest name or JAR file name)
  plugin install <file>         Install a Browser4 plugin from a local JAR file (requires server restart to activate)
  plugin remove <name>          Remove a Browser4 plugin by name (beans remain in context until next restart)

── Plugin tools ─────────────────────────────────────────────────────
  Installed plugins expose tools via the plugin-<name> <method> pattern:
    plugin-<name>              invoke the default tool for a plugin domain
    plugin-<name> <method>     invoke a specific method (e.g. plugin-media download --url ...)
    plugin                     list all available plugin tool domains
  Use `plugin list` to see installed plugins and their status.

── Global options ───────────────────────────────────────────────────
  --help [cmd|category]       print help; try categories: nav, extract, session, kb, agent, swarm
  --help-json                 emit full command reference as machine-readable JSON (for AI / scripts)
  --version                   print version
  --json                      emit JSON to stdout only (suppresses tips, hints, and human-readable text)
  -q, --quiet                 suppress normal output, only show errors
  -tip, --show-tip            show a relevant tip on stderr after each command
  -s <name>                   named session label
  --timeout <seconds>         override the default HTTP timeout for tool calls (e.g. --timeout 300 for long-running
                              plugin tools)
  --server <url>              override Browser4 server URL

── Environment variables ─────────────────────────────────────────────
  BROWSER4_CLI_STATE_DIR=<dir>  override CLI session state directory (default: ~/.browser4); falls back to
                              ./.browser4-cli-state when unwritable
  BROWSER4_RUNTIME_DIR=<dir>  override Browser4 runtime data directory (JRE, JARs, launchers)

Run `browser4-cli help <command>` or `<command> --help` for detailed options and examples.
Dev mode: cargo run --manifest-path cli/browser4-cli/Cargo.toml -- <command>

 succeeded in 346ms:

Path
----
D:\workspace\Browser4\Browser4-4.13
True
.test-sessions exists


codex
Environment verified. Now let me read the SKILL.md documentation and check the backend/MockSite status.
web search: 
web search: https://browser4.io/SKILL.md#ws_call_id=call_00_a78Esoq97PW9zHIwSW1I8620
codex
The page didn't load through the browser tool. Let me try fetching it directly and also check the local copy in the repo.
exec
"C:\\Program Files\\PowerShell\\7\\pwsh.exe" -Command '$resp = Invoke-WebRequest -Uri "https://browser4.io/SKILL.md" -UseBasicParsing -TimeoutSec 30; Write-Output "Status: $($resp.StatusCode)"; Write-Output "Length: $($resp.Content.Length)"; $resp.Content.Substring(0, [Math]::Min(2000, $resp.Content.Length))' in D:\workspace\Browser4\Browser4-4.13
exec
"C:\\Program Files\\PowerShell\\7\\pwsh.exe" -Command 'Get-ChildItem -Path skills/browser4-cli -Force | Select-Object Name, Length; Write-Output "---"; Get-ChildItem -Path . -Filter "SKILL.md" -Recurse -Depth 3 -ErrorAction SilentlyContinue | Select-Object FullName' in D:\workspace\Browser4\Browser4-4.13
 succeeded in 375ms:

Name       Length
----       ------
references 
SKILL.md   45813
---
           1
           1
           1
           1
           1
           1
           1
           1
           1
           1
           1


 succeeded in 4730ms:
Status: 200
Length: 45376
---
name: browser4-cli
title: "Browser Automation with browser4-cli"
description: "Automates browser interactions for web testing, form filling, screenshots, and data extraction. Use when the user needs to navigate websites, interact with web pages, fill forms, take screenshots, test web applications, or extract information from web pages."
allowed-tools: Bash(browser4-cli:*)
tier: decision
---

# Browser Automation with browser4-cli

Browser automation CLI for AI agents — Chrome/Chromium via CDP with accessibility-tree snapshots.

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

> **⚡ First-run latency:** From a source tree, the first launch builds the runtime bundle via Maven (~1–3 min, before the spinner appears) and then starts the Browser4 backend (Spring Boot + JVM, ~10s). Subsequent commands are instant — the server stays alive between invocations. The spinner shows stage-level progress (JVM → Spring Boot → MCP tools) so you can see what's happening.

> **🖥️ Headless mode is the default for AI agents:** Always open browsers in **headless mode** (`--headless`) unless the user **explicitly** asks to see the browser window (e.g., "show me the browser", "open v

codex
The web doc downloaded successfully via HTTP. Let me save it into .test-sessions/ and read it completely, and also check the backend server status.
exec
"C:\\Program Files\\PowerShell\\7\\pwsh.exe" -Command '$resp = Invoke-WebRequest -Uri "https://browser4.io/SKILL.md" -UseBasicParsing -TimeoutSec 30; Set-Content -Path ".test-sessions/SKILL-web.md" -Value $resp.Content -Encoding UTF8; Write-Output "Saved. Length: $($resp.Content.Length)"' in D:\workspace\Browser4\Browser4-4.13
exec
"C:\\Program Files\\PowerShell\\7\\pwsh.exe" -Command 'browser4-cli status' in D:\workspace\Browser4\Browser4-4.13
 succeeded in 354ms:
Browser4 Status
===============
CLI version: 4.13.4
Server URL: http://localhost:18182
Installed bundle: v4.13.4 (at 2026-08-14T06:32:39.351436100+00:00)
Server health: UP
Server version: 4.13.4-SNAPSHOT

 succeeded in 2832ms:
Saved. Length: 45376

codex
Backend server is UP (v4.13.4 at localhost:18182). Now reading the SKILL.md documentation completely.
exec
"C:\\Program Files\\PowerShell\\7\\pwsh.exe" -Command 'Get-Content .test-sessions/SKILL-web.md -Raw' in D:\workspace\Browser4\Browser4-4.13
 succeeded in 320ms:
---
name: browser4-cli
title: "Browser Automation with browser4-cli"
description: "Automates browser interactions for web testing, form filling, screenshots, and data extraction. Use when the user needs to navigate websites, interact with web pages, fill forms, take screenshots, test web applications, or extract information from web pages."
allowed-tools: Bash(browser4-cli:*)
tier: decision
---

# Browser Automation with browser4-cli

Browser automation CLI for AI agents — Chrome/Chromium via CDP with accessibility-tree snapshots.

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

> **⚡ First-run latency:** From a source tree, the first launch builds the runtime bundle via Maven (~1–3 min, before the spinner appears) and then starts the Browser4 backend (Spring Boot + JVM, ~10s). Subsequent commands are instant — the server stays alive between invocations. The spinner shows stage-level progress (JVM → Spring Boot → MCP tools) so you can see what's happening.

> **🖥️ Headless mode is the default for AI agents:** Always open browsers in **headless mode** (`--headless`) unless the user **explicitly** asks to see the browser window (e.g., "show me the browser", "open visibly", "I want to watch", or "headed"). Headless mode is faster, uses fewer resources, and avoids unnecessary GUI windows. Use `--headed` **only** when the user specifically requests a visible browser. See the Display Mode section below (§2 Key Concepts) for details.

Every browser4-cli session follows this pattern.

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

For quick inline viewing without opening a file, add `--stdout`:
```bash
browser4-cli snapshot -v 0 --stdout   # print snapshot to stdout instead of file
```

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

Each interactive element has a **ref** (`e5`, `e12`) — the element's Chrome DevTools Protocol backend node ID, prefixed with `e` (so `e12345` refers to backend node 12345). Use them to target elements in `click`, `fill`, `type`, `get attr`, etc.

> **Note:** `/url` fields may be **relative** (e.g. `/url: news`, `/url: from?site=example.com`). The snapshot output includes the page URL at the top for resolution. For absolute URLs, use `htmlsnapshot get all attr "a[href]" href` which returns full URLs after redirect resolution.

### Ref Lifecycle

Refs are **ephemeral** — treat them as single-use handles. Any interaction can leave you with stale refs if the page re-renders or Chrome remaps backend nodes:

- **Always re-snapshot after interactions:** `click`, `fill`, `type`, `press`, `check`, `uncheck`, `select`, `hover`, `drag`, `dblclick`.
- **Definitely re-snapshot after page/context changes:** `goto`, `reload`, tab switches, or clicks that navigate/update the page.
- **If you are chaining form actions:** rely on the automatic post-action snapshot, then use refs from that fresh snapshot for the next step.

**In practice, the safest loop is interact → re-snapshot → use new refs.** This is the CLI's current guidance and avoids intermittent stale-ref failures on reactive pages.

Interaction commands capture an automatic snapshot after execution. Pass `--no-snapshot` to skip it when you plan to capture a fresh snapshot manually (saves a round-trip).

### Output Modes

- **Default** — human-readable output on stdout.
- **`--show-tip` / `-tip`** — show a relevant, rotating tip on stderr after each successful command. Tips are suppressed by default; use this flag to enable them.
- **`--json`** — single-line JSON envelope on stdout for commands that support structured output. This is the clean machine-readable mode for commands such as `tab-list`, `htmlsnapshot get`, `htmlsnapshot query`, and `eval`. **Exception:** `snapshot` remains YAML-focused and warns on stderr instead of returning JSON snapshot data.
- **`--quiet` / `-q`** — suppress all normal output; only errors appear on stderr.

### Display Mode (Headless vs Headed)

Browser4 can launch Chrome in two display modes:

| Mode | Flag | Window | Use case |
|------|------|--------|----------|
| **Headless** | `--headless` | No GUI window | **Default for AI agents** — scraping, automation, CI/CD, server environments |
| **Headed** | `--headed` | Visible browser window | Debugging, user demonstration, interactive development |

**Rule for AI agents: always use `--headless` by default.** Headless mode is faster, uses fewer resources, and avoids cluttering the user's desktop with browser windows. The only reason to use `--headed` is when the user **explicitly** requests a visible browser — look for phrases like "show me the browser", "I want to see", "open visibly", "headed", "watch what happens", or "debug visually".

Set the display mode with the `open` command when starting a **new** session. The `goto` command does **not** accept `--headless`/`--headed` directly — it inherits the session's existing display mode:

```bash
browser4-cli open --headless https://example.com     # headless (preferred default)
browser4-cli open --headed https://example.com       # headed (only when user asks)
```

Once a session is open, use `goto` for subsequent navigations (the display mode persists):

```bash
browser4-cli goto https://other-page.com             # stays headless (or headed) as set by open
```

> **Note — `goto` on first invocation:** When `goto` is the very first command (no prior `open`), it auto-opens a new session using the **CLI's default display mode, which is headless**. The display mode is still fixed at session creation, so if you want a visible window, start with `open --headed` before using `goto`.

> **Note — reconnecting to an existing session:** The `--headless`/`--headed` flags only take effect when creating a new session. When `open` reconnects to an already-running session, the display mode is already set and the flags are ignored — the CLI prints a warning on stderr and the reconnect message shows the tab count so inherited state is visible. To change the mode of an existing session, close it first (`close`), then `open --headless` to create a new one. To discard a stale session's tabs/cookies/location entirely and start clean, use `open --fresh` (closes the current session, then opens a new one).

### Sessions

Named sessions isolate browser state (cookies, localStorage, tabs). Use `-s <name>` to target a named session. `goto` auto-opens/reconnects — you rarely need to manage sessions manually.

The `list` command displays a "Next open" column showing what happens when `goto` or `open` targets a named session that already exists:
- **Reuse** — reconnects to the existing browser window (session is active on the backend).
- **Refresh** — opens a fresh window (session is stale or missing).

Session state is stored in `~/.browser4` by default. When that directory is not
writable (e.g. sandboxed shells), the CLI automatically falls back to
`./.browser4-cli-state` (workspace-relative) and prints a warning — set
`BROWSER4_CLI_STATE_DIR` to an explicit writable path to silence it.
`BROWSER4_RUNTIME_DIR` likewise overrides the runtime bundle location.

### Configuration

The `config` command manages persistent CLI defaults stored in
`~/.browser4/config.json` (honours `BROWSER4_CLI_STATE_DIR`). These are global
fallbacks — an explicit flag or environment variable always wins per invocation.

| Key | Purpose | Overridden by |
|-----|---------|---------------|
| `server` | Default Browser4 server URL | `--server` / `BROWSER4_CLI_SERVER` |
| `timeout` | Default HTTP timeout (seconds) | `--timeout` |
| `proxy` | Default download proxy URL | `--proxy` |
| `session` | Default session name | `-s` / `--session` / `BROWSER4_CLI_SESSION` |

```bash
browser4-cli config                              # List all values + config file path
browser4-cli config list                         # Same as above
browser4-cli config get server                   # Print one value ("(not set)" if unset)
browser4-cli config set server http://localhost:8182
browser4-cli config set timeout 45               # Positive integer seconds
browser4-cli config delete session               # Reset a key to its default
```

Notes:
- `config get` / `set` / `delete` use the spaced form (`config get server`), not `config-get server`.
- `timeout` must be a positive integer; `0` and unknown keys are rejected with a non-zero exit.
- `config set server` sets the persistent default; a later `--server` flag or `BROWSER4_CLI_SERVER` still overrides it for that invocation.

### Tab Management

Tab commands scope to a session — all operations affect the session targeted via `-s <session>` (or the DEFAULT session when `-s` is omitted).

#### Tab Lifecycle

```
1. LIST     browser4-cli tab-list                    # See all tabs: index, GUID, title, URL
2. CREATE   browser4-cli tab-new [url]               # Open a new tab (about:blank if URL omitted)
3. SWITCH   browser4-cli tab-select <index>          # Switch by index
           browser4-cli tab-select --guid <guid>    # Switch by stable GUID
4. CLOSE    browser4-cli tab-close <index>           # Close by index
           browser4-cli tab-close                   # Close current tab
           browser4-cli tab-close --guid <guid>     # Close by GUID
5. VERIFY   browser4-cli tab-list                    # Confirm state after changes
```

#### Key notes

- **GUIDs:** `tab-list` shows a `GUID` column. Use `--guid` for stable targeting across tab reordering. Extension sessions show a `chrome:` prefix on numeric GUIDs; regular sessions use 32-char hex GUIDs.
- **Machine-readable output:** Use `--json` either before or after the command: `browser4-cli --json tab-list` or `browser4-cli tab-list --json`. Output is a JSON envelope: `{"command":"tab-list","output":{"count":N,"tabs":[{"index":0,"guid":"...","url":"...","title":"..."}]},"status":"ok"}`. The `tabs` array and `count` are nested inside `output`.
- **Session scoping:** Prefix tab commands with `-s <session-id>` to target a non-default session. The `list` command shows all tracked sessions and their IDs.
- **Last-tab behavior:** Chrome requires at least one open tab. Closing the last tab silently creates a replacement `about:blank` — `tab-list` will still show 1 tab afterward.
- **Tab insert position:** New tabs are inserted by Chrome (not Browser4). The position depends on Chrome's native behavior which varies by platform, Chrome version, and mode — on Windows headless CDP, new tabs appear at index 0 (before the active tab); on macOS and some other configurations, they appear after the active tab. Always run `tab-list` after creating new tabs to confirm positions before switching by index.
- **No auto-snapshot:** `tab-list` and `tab-close` do NOT trigger automatic snapshots. After `tab-select`, run `snapshot` explicitly to get fresh element refs for the new active tab.
- **Re-snapshot after switches:** `tab-select` changes the active page context. Capture a fresh snapshot before interacting with page elements in the new tab.
- **Extension sessions:** When closing tabs on extension-attached sessions, the backend may report an error even though the tab was successfully closed (Chrome's `chrome.tabs.remove` callback can fire an error after the tab is already gone). The CLI verifies that the tab was actually removed and treats the operation as successful in this case. Extension sessions may also show "Stale" in `list` output after all tabs are closed — the session can be reconnected with `attach --extension`.
- **Extension re-attach creates a fresh tab scope:** Each `attach --extension` establishes a new WebSocket connection and creates its own tab tracking scope. After re-attaching (e.g., after navigating to `chrome://version/` which drops the connection), only tabs created through the *new* connection are visible in `tab-list`. Tabs from the previous connection are still open in Chrome but are not tracked by the new session. To work with those tabs, either re-open them via `tab-new` in the new session, or use `-s <name>` to preserve a named session that survives re-attach.

#### Examples

```bash
# List all tabs in the default session
browser4-cli tab-list

# Machine-readable tab data (both forms work)
browser4-cli --json tab-list
browser4-cli tab-list --json
# Output: {"command":"tab-list","output":{"count":1,"tabs":[{"index":0,"guid":"...","url":"about:blank","title":"(no title)"}]},"status":"ok"}

# Open a tab and switch to it
browser4-cli tab-new https://httpbin.org/get
# Output:
#   Created tab with GUID: 2AAA0C47... (https://httpbin.org/get)
#   Switched to tab 0 (https://httpbin.org/get)
# Note: tab index varies by platform (0 on Windows headless; may appear
# after the active tab on other platforms). Run `tab-list` to verify.

# Close by GUID (survives reordering)
browser4-cli tab-close --guid 2AAA0C47D288D3943BA85D31AA8D084C

# Cross-session tab operations
browser4-cli -s ext-session tab-list
browser4-cli -s ext-session tab-new https://example.com
browser4-cli -s ext-session tab-select 0
```

## 3. Command Map

| Command family | Purpose | When to use | Full reference |
|---------------|---------|-------------|----------------|
| `goto`, `open`, `close`, `reload` | Navigation & session management | Every session starts here | — |
| `snapshot` | Capture accessibility tree (AXTree) with element refs | **Page structure & interaction** — find elements to click, fill, etc. Use `snapshot` when you need refs (e5, e36) to interact with. | [snapshot.md](references/snapshot.md) |
| `snapshot grep` | Search snapshot content with regex | Find elements by text or pattern | — |
| `click`, `dblclick`, `drag`, `hover`, `fill`, `type`, `press`, `select`, `check`, `generate-locator` | Page interaction | Form filling, button clicks, mouse actions, navigation | — |
| `dialog-accept`, `dialog-dismiss` | Native JS dialog handling | After clicking buttons that trigger alert/confirm/prompt | — |
| `htmlsnapshot get`, `get all` | Extract text/html/attr via CSS selectors from stored HTML | **Page content & text extraction** — get article text, headings, attributes. Use `htmlsnapshot` when you need to read or extract page content. | [htmlsnapshot.md](references/htmlsnapshot.md) |
| `htmlsnapshot query` | X-SQL queries for structured extraction | Multi-field, filtered, sorted data | [x-sql.md](references/x-sql.md) |
| `eval` | Execute JavaScript in the page | Live DOM access, complex transforms | — |
| `eval --ref` | Execute JS scoped to a specific element | Element property extraction (text, attrs, styles) | **⚠️ Expression MUST be an arrow function: `element => element.textContent`** |
| `extract`, `summarize`, `agent run` | AI-powered extraction | Natural language extraction (needs LLM key) | [agent.md](references/agent.md) |
| `crawl` | Recursive crawling + bulk extraction | Multi-page traversal, seed-file processing | [crawl.md](references/crawl.md) |
| `swarm` | Parallel scraping across browser contexts | High-throughput extraction | [swarm.md](references/swarm.md) |
| `loop` | Repeated task execution with persistence | Monitoring, scheduled checks | [loop.md](references/loop.md) |
| `state-save`, `state-load`, `cookie-*`, `*-storage-*` | Browser storage management | Auth state reuse, cookie manipulation | [storage-state.md](references/storage-state.md) |
| `attach` | Connect to existing Chrome/Edge via CDP | Debug live browser, reuse auth | [attach.md](references/attach.md) |
| `webdb export`, `webdb normalize` | Export cached pages, normalize URLs to database keys | Post-crawl content extraction, URL key lookup | [webdb.md](references/webdb.md) |
| `skills`, `skills get`, `skills path`, `skills unpack` | Bundled AI agent skill files | Refresh agent instructions, unpack skill files | [skills.md](references/skills.md) |
| `skill-list`, `skill-info`, `skill-install`, `skill-uninstall`, `skill-reload` | Backend skill management | Install/manage server-side skills | [skills.md](references/skills.md) |
| `screenshot`, `scroll`, `wait`, `resize` | Visual capture & viewport control | Screenshots, viewport sizing, scroll control | — |
| `tab-list`, `tab-new`, `tab-select`, `tab-close` | Tab management | Multi-tab workflows, session-scoped tab operations. See §Tab Management below. | — |
| `config` | Persistent CLI defaults (server, timeout, proxy, session) | Set default server URL, timeout, proxy, or session name. See §Configuration. | — |

### Refreshing This Skill

The `skills` command retrieves bundled skill content that always matches the installed CLI version. Use it to get current instructions rather than relying on cached copies:

```bash
browser4-cli skills                         # List all bundled skills
browser4-cli skills get browser4-cli        # Print this SKILL.md
browser4-cli skills get browser4-cli --full # Include all reference files
browser4-cli skills path                    # Print skills directory path
browser4-cli skills unpack                  # Unpack bundled skill files to disk
```

Set `BROWSER4_SKILLS_DIR` to override the skills directory location. Skill files are unpacked automatically during `browser4-cli install`. Use `skills unpack` to refresh or relocate skill files without reinstalling.

## 4. Decision Trees

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
> | `htmlsnapshot query` | **No** — fetches independently | Uses `DOM_LOAD_AND_SELECT(@url, ...)` which re-fetches the page, bypassing the stored snapshot entirely |
>
> **If you get "No HTML snapshot found" or a timeout:** either run `htmlsnapshot` first to capture, or use `htmlsnapshot query` with `@url` for independent fetching.

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

```
Have HTML files and want structured data — without tokens?
├─ < 1,000 pages (small to medium)? → WebMiner Free (SMILE ML engine)
│  java -jar scent-miner.jar all ./html-pages/
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

> **Install:** `.\webminer.ps1 install` (PowerShell — the script ships with the [web-miner](https://github.com/platonai/web-miner) project, not this repo) or download from [web-miner releases](https://github.com/platonai/web-miner/releases). Requires JDK 17+.

See **[scent-miner/SKILL.md](../scent-miner/SKILL.md)** for the full reference.

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
```

**Critical syntax rules** (H2 SQL engine — violating these produces opaque errors):

| Rule | Correct | Wrong |
|------|---------|-------|
| CSS selectors use **single** quotes (SQL string literals) | `'h2'`, `'.price'` | `"h2"` (SQL identifier) |
| `@url` placeholder is **unquoted** | `@url` | `'@url'` (literal string) |
| FROM source is always `DOM_LOAD_AND_SELECT` | `DOM_LOAD_AND_SELECT(@url, '...')` | Any other table name |
| No CTEs (`WITH`), no `JOIN`, no subqueries | Simple `SELECT … FROM …` | `WITH t AS (…) SELECT …` |

**Discover selectors** before writing the query:
```bash
browser4-cli htmlsnapshot inspect                    # interactive: lists all elements with CSS classes/ids
browser4-cli htmlsnapshot summary                    # statistical summary of selectors on the page
browser4-cli htmlsnapshot get text ".price" --all    # quick test: does this selector match elements?
```

**Common mistakes and solutions:**

| Symptom | Likely cause | Fix |
|---------|-------------|-----|
| `Column "h2" not found` | Double quotes around CSS selector → treated as SQL column name | Use single quotes: `'h2'` |
| `Table "..." not found` | Wrong FROM source or quoted `@url` | Use `DOM_LOAD_AND_SELECT(@url, 'selector')` |
| Empty result set | Selector doesn't match any elements | Run `htmlsnapshot inspect` to find valid selectors |
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
> | `snapshot -i` | **Interactive elements only:** buttons, links, inputs, selects, textareas. Strips generic `<div>`, `<span>`, and other non-interactive containers | Simple forms, login pages, sparse pages with clear interactive controls. Reduces noise when you only need clickable/fillable elements |
> | `htmlsnapshot` | Static HTML (CSS selectors) | Content extraction (text, attributes), when you need CSS selectors instead of AX refs |
>
> **`-i` trade-off:** Interactive mode discards structural context. On e-commerce/search pages where product cards use generic `<div>` wrappers, `-i` may strip the containers you need. For these pages, prefer `--viewport 0` or use `htmlsnapshot` for CSS-based extraction.
>
> **Example — simple form page:**
> ```bash
> # Without -i: shows full page tree including header, footer, nav, etc.
> browser4-cli snapshot --stdout
> # ... 200+ lines ...
>
> # With -i: shows only form fields and buttons
> browser4-cli snapshot -i --stdout
> # e5  textbox  "Email"       /url: /login
> # e6  textbox  "Password"    /url: /login
> # e7  button   "Sign In"     /url: /login
> # 12 lines — just the interactive controls
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

### Multi-Session Workflow

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

browser4-cli htmlsnapshot query "https://example.com/products" --sql @query.sql
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

These are usable in any CSS selector via `:expr(...)`, in X-SQL `DOM_*` functions, and in `htmlsnapshot get` / `htmlsnapshot query` commands.

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

**Interact with pages (accessibility tree & element refs):**
[snapshot.md](references/snapshot.md) — `snapshot`, `snapshot grep`, `-v` viewport paging, `--auto-diff`, `-i` interactive mode, element refs

**Extract data from pages:**
[htmlsnapshot.md](references/htmlsnapshot.md) — `get`, `get all`, `query`, `grep`, `summary`, `inspect`, `export`
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
browser4-cli install
```

**Linux / macOS (bash):**
```bash
curl -fsSL https://browser4.oss-cn-beijing.aliyuncs.com/scripts/install-browser4-cli.sh | bash
browser4-cli install
```



codex
The doc output was truncated in the middle. Let me read the missing section (§4a and the X-SQL details) specifically.
exec
"C:\\Program Files\\PowerShell\\7\\pwsh.exe" -Command '$lines = Get-Content .test-sessions/SKILL-web.md; for ($i=0; $i -lt $lines.Count; $i++) { if ($lines[$i] -match '"'"'^## 4a|''^### 4a'"') { Write-Output \"Section 4a at line "'$i" } }; Write-Output "Total lines: $($lines.Count)"; $lines[90..200] -join "`n"' in D:\workspace\Browser4\Browser4-4.13
 succeeded in 343ms:
Section 4a at line 270
Total lines: 751

**In practice, the safest loop is interact → re-snapshot → use new refs.** This is the CLI's current guidance and avoids intermittent stale-ref failures on reactive pages.

Interaction commands capture an automatic snapshot after execution. Pass `--no-snapshot` to skip it when you plan to capture a fresh snapshot manually (saves a round-trip).

### Output Modes

- **Default** — human-readable output on stdout.
- **`--show-tip` / `-tip`** — show a relevant, rotating tip on stderr after each successful command. Tips are suppressed by default; use this flag to enable them.
- **`--json`** — single-line JSON envelope on stdout for commands that support structured output. This is the clean machine-readable mode for commands such as `tab-list`, `htmlsnapshot get`, `htmlsnapshot query`, and `eval`. **Exception:** `snapshot` remains YAML-focused and warns on stderr instead of returning JSON snapshot data.
- **`--quiet` / `-q`** — suppress all normal output; only errors appear on stderr.

### Display Mode (Headless vs Headed)

Browser4 can launch Chrome in two display modes:

| Mode | Flag | Window | Use case |
|------|------|--------|----------|
| **Headless** | `--headless` | No GUI window | **Default for AI agents** — scraping, automation, CI/CD, server environments |
| **Headed** | `--headed` | Visible browser window | Debugging, user demonstration, interactive development |

**Rule for AI agents: always use `--headless` by default.** Headless mode is faster, uses fewer resources, and avoids cluttering the user's desktop with browser windows. The only reason to use `--headed` is when the user **explicitly** requests a visible browser — look for phrases like "show me the browser", "I want to see", "open visibly", "headed", "watch what happens", or "debug visually".

Set the display mode with the `open` command when starting a **new** session. The `goto` command does **not** accept `--headless`/`--headed` directly — it inherits the session's existing display mode:

```bash
browser4-cli open --headless https://example.com     # headless (preferred default)
browser4-cli open --headed https://example.com       # headed (only when user asks)
```

Once a session is open, use `goto` for subsequent navigations (the display mode persists):

```bash
browser4-cli goto https://other-page.com             # stays headless (or headed) as set by open
```

> **Note — `goto` on first invocation:** When `goto` is the very first command (no prior `open`), it auto-opens a new session using the **CLI's default display mode, which is headless**. The display mode is still fixed at session creation, so if you want a visible window, start with `open --headed` before using `goto`.

> **Note — reconnecting to an existing session:** The `--headless`/`--headed` flags only take effect when creating a new session. When `open` reconnects to an already-running session, the display mode is already set and the flags are ignored — the CLI prints a warning on stderr and the reconnect message shows the tab count so inherited state is visible. To change the mode of an existing session, close it first (`close`), then `open --headless` to create a new one. To discard a stale session's tabs/cookies/location entirely and start clean, use `open --fresh` (closes the current session, then opens a new one).

### Sessions

Named sessions isolate browser state (cookies, localStorage, tabs). Use `-s <name>` to target a named session. `goto` auto-opens/reconnects — you rarely need to manage sessions manually.

The `list` command displays a "Next open" column showing what happens when `goto` or `open` targets a named session that already exists:
- **Reuse** — reconnects to the existing browser window (session is active on the backend).
- **Refresh** — opens a fresh window (session is stale or missing).

Session state is stored in `~/.browser4` by default. When that directory is not
writable (e.g. sandboxed shells), the CLI automatically falls back to
`./.browser4-cli-state` (workspace-relative) and prints a warning — set
`BROWSER4_CLI_STATE_DIR` to an explicit writable path to silence it.
`BROWSER4_RUNTIME_DIR` likewise overrides the runtime bundle location.

### Configuration

The `config` command manages persistent CLI defaults stored in
`~/.browser4/config.json` (honours `BROWSER4_CLI_STATE_DIR`). These are global
fallbacks — an explicit flag or environment variable always wins per invocation.

| Key | Purpose | Overridden by |
|-----|---------|---------------|
| `server` | Default Browser4 server URL | `--server` / `BROWSER4_CLI_SERVER` |
| `timeout` | Default HTTP timeout (seconds) | `--timeout` |
| `proxy` | Default download proxy URL | `--proxy` |
| `session` | Default session name | `-s` / `--session` / `BROWSER4_CLI_SESSION` |

```bash
browser4-cli config                              # List all values + config file path
browser4-cli config list                         # Same as above
browser4-cli config get server                   # Print one value ("(not set)" if unset)
browser4-cli config set server http://localhost:8182
browser4-cli config set timeout 45               # Positive integer seconds
browser4-cli config delete session               # Reset a key to its default
```

Notes:
- `config get` / `set` / `delete` use the spaced form (`config get server`), not `config-get server`.
- `timeout` must be a positive integer; `0` and unknown keys are rejected with a non-zero exit.
- `config set server` sets the persistent default; a later `--server` flag or `BROWSER4_CLI_SERVER` still overrides it for that invocation.

### Tab Management

Tab commands scope to a session — all operations affect the session targeted via `-s <session>` (or the DEFAULT session when `-s` is omitted).

#### Tab Lifecycle

```
1. LIST     browser4-cli tab-list                    # See all tabs: index, GUID, title, URL
2. CREATE   browser4-cli tab-new [url]               # Open a new tab (about:blank if URL omitted)
3. SWITCH   browser4-cli tab-select <index>          # Switch by index
           browser4-cli tab-select --guid <guid>    # Switch by stable GUID
4. CLOSE    browser4-cli tab-close <index>           # Close by index
           browser4-cli tab-close                   # Close current tab
           browser4-cli tab-close --guid <guid>     # Close by GUID
5. VERIFY   browser4-cli tab-list                    # Confirm state after changes
```

#### Key notes

- **GUIDs:** `tab-list` shows a `GUID` column. Use `--guid` for stable targeting across tab reordering. Extension sessions show a `chrome:` prefix on numeric GUIDs; regular sessions use 32-char hex GUIDs.
- **Machine-readable output:** Use `--json` either before or after the command: `browser4-cli --json tab-list` or `browser4-cli tab-list --json`. Output is a JSON envelope: `{"command":"tab-list","output":{"count":N,"tabs":[{"index":0,"guid":"...","url":"...","title":"..."}]},"status":"ok"}`. The `tabs` array and `count` are nested inside `output`.
- **Session scoping:** Prefix tab commands with `-s <session-id>` to target a non-default session. The `list` command shows all tracked sessions and their IDs.
- **Last-tab behavior:** Chrome requires at least one open tab. Closing the last tab silently creates a replacement `about:blank` — `tab-list` will still show 1 tab afterward.
- **Tab insert position:** New tabs are inserted by Chrome (not Browser4). The position depends on Chrome's native behavior which varies by platform, Chrome version, and mode — on Windows headless CDP, new tabs appear at index 0 (before the active tab); on macOS and some other configurations, they appear after the active tab. Always run `tab-list` after creating new tabs to confirm positions before switching by index.
- **No auto-snapshot:** `tab-list` and `tab-close` do NOT trigger automatic snapshots. After `tab-select`, run `snapshot` explicitly to get fresh element refs for the new active tab.
- **Re-snapshot after switches:** `tab-select` changes the active page context. Capture a fresh snapshot before interacting with page elements in the new tab.
- **Extension sessions:** When closing tabs on extension-attached sessions, the backend may report an error even though the tab was successfully closed (Chrome's `chrome.tabs.remove` callback can fire an error after the tab is already gone). The CLI verifies that the tab was actually removed and treats the operation as successful in this case. Extension sessions may also show "Stale" in `list` output after all tabs are closed — the session can be reconnected with `attach --extension`.
- **Extension re-attach creates a fresh tab scope:** Each `attach --extension` establishes a new WebSocket connection and creates its own tab tracking scope. After re-attaching (e.g., after navigating to `chrome://version/` which drops the connection), only tabs created through the *new* connection are visible in `tab-list`. Tabs from the previous connection are still open in Chrome but are not tracked by the new session. To work with those tabs, either re-open them via `tab-new` in the new session, or use `-s <name>` to preserve a named session that survives re-attach.

#### Examples

codex
Now let me read §4a in full, which is the core section this scenario covers.
exec
"C:\\Program Files\\PowerShell\\7\\pwsh.exe" -Command '$lines = Get-Content .test-sessions/SKILL-web.md; $lines[270..370] -join "`n"' in D:\workspace\Browser4\Browser4-4.13
 succeeded in 333ms:
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
> | `htmlsnapshot query` | **No** — fetches independently | Uses `DOM_LOAD_AND_SELECT(@url, ...)` which re-fetches the page, bypassing the stored snapshot entirely |
>
> **If you get "No HTML snapshot found" or a timeout:** either run `htmlsnapshot` first to capture, or use `htmlsnapshot query` with `@url` for independent fetching.

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

```
Have HTML files and want structured data — without tokens?
├─ < 1,000 pages (small to medium)? → WebMiner Free (SMILE ML engine)
│  java -jar scent-miner.jar all ./html-pages/
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

> **Install:** `.\webminer.ps1 install` (PowerShell — the script ships with the [web-miner](https://github.com/platonai/web-miner) project, not this repo) or download from [web-miner releases](https://github.com/platonai/web-miner/releases). Requires JDK 17+.

See **[scent-miner/SKILL.md](../scent-miner/SKILL.md)** for the full reference.

### 4e. X-SQL Quickstart Template


codex
Let me fill in the remaining gaps (lines 200–270 and 370–450) to ensure I've read the whole doc.
exec
"C:\\Program Files\\PowerShell\\7\\pwsh.exe" -Command '$lines = Get-Content .test-sessions/SKILL-web.md; Write-Output "=== LINES 200-270 ==="; $lines[200..269] -join "`n"; Write-Output "`n=== LINES 370-450 ==="; $lines[370..449] -join "`n"' in D:\workspace\Browser4\Browser4-4.13
 succeeded in 340ms:
=== LINES 200-270 ===
#### Examples

```bash
# List all tabs in the default session
browser4-cli tab-list

# Machine-readable tab data (both forms work)
browser4-cli --json tab-list
browser4-cli tab-list --json
# Output: {"command":"tab-list","output":{"count":1,"tabs":[{"index":0,"guid":"...","url":"about:blank","title":"(no title)"}]},"status":"ok"}

# Open a tab and switch to it
browser4-cli tab-new https://httpbin.org/get
# Output:
#   Created tab with GUID: 2AAA0C47... (https://httpbin.org/get)
#   Switched to tab 0 (https://httpbin.org/get)
# Note: tab index varies by platform (0 on Windows headless; may appear
# after the active tab on other platforms). Run `tab-list` to verify.

# Close by GUID (survives reordering)
browser4-cli tab-close --guid 2AAA0C47D288D3943BA85D31AA8D084C

# Cross-session tab operations
browser4-cli -s ext-session tab-list
browser4-cli -s ext-session tab-new https://example.com
browser4-cli -s ext-session tab-select 0
```

## 3. Command Map

| Command family | Purpose | When to use | Full reference |
|---------------|---------|-------------|----------------|
| `goto`, `open`, `close`, `reload` | Navigation & session management | Every session starts here | — |
| `snapshot` | Capture accessibility tree (AXTree) with element refs | **Page structure & interaction** — find elements to click, fill, etc. Use `snapshot` when you need refs (e5, e36) to interact with. | [snapshot.md](references/snapshot.md) |
| `snapshot grep` | Search snapshot content with regex | Find elements by text or pattern | — |
| `click`, `dblclick`, `drag`, `hover`, `fill`, `type`, `press`, `select`, `check`, `generate-locator` | Page interaction | Form filling, button clicks, mouse actions, navigation | — |
| `dialog-accept`, `dialog-dismiss` | Native JS dialog handling | After clicking buttons that trigger alert/confirm/prompt | — |
| `htmlsnapshot get`, `get all` | Extract text/html/attr via CSS selectors from stored HTML | **Page content & text extraction** — get article text, headings, attributes. Use `htmlsnapshot` when you need to read or extract page content. | [htmlsnapshot.md](references/htmlsnapshot.md) |
| `htmlsnapshot query` | X-SQL queries for structured extraction | Multi-field, filtered, sorted data | [x-sql.md](references/x-sql.md) |
| `eval` | Execute JavaScript in the page | Live DOM access, complex transforms | — |
| `eval --ref` | Execute JS scoped to a specific element | Element property extraction (text, attrs, styles) | **⚠️ Expression MUST be an arrow function: `element => element.textContent`** |
| `extract`, `summarize`, `agent run` | AI-powered extraction | Natural language extraction (needs LLM key) | [agent.md](references/agent.md) |
| `crawl` | Recursive crawling + bulk extraction | Multi-page traversal, seed-file processing | [crawl.md](references/crawl.md) |
| `swarm` | Parallel scraping across browser contexts | High-throughput extraction | [swarm.md](references/swarm.md) |
| `loop` | Repeated task execution with persistence | Monitoring, scheduled checks | [loop.md](references/loop.md) |
| `state-save`, `state-load`, `cookie-*`, `*-storage-*` | Browser storage management | Auth state reuse, cookie manipulation | [storage-state.md](references/storage-state.md) |
| `attach` | Connect to existing Chrome/Edge via CDP | Debug live browser, reuse auth | [attach.md](references/attach.md) |
| `webdb export`, `webdb normalize` | Export cached pages, normalize URLs to database keys | Post-crawl content extraction, URL key lookup | [webdb.md](references/webdb.md) |
| `skills`, `skills get`, `skills path`, `skills unpack` | Bundled AI agent skill files | Refresh agent instructions, unpack skill files | [skills.md](references/skills.md) |
| `skill-list`, `skill-info`, `skill-install`, `skill-uninstall`, `skill-reload` | Backend skill management | Install/manage server-side skills | [skills.md](references/skills.md) |
| `screenshot`, `scroll`, `wait`, `resize` | Visual capture & viewport control | Screenshots, viewport sizing, scroll control | — |
| `tab-list`, `tab-new`, `tab-select`, `tab-close` | Tab management | Multi-tab workflows, session-scoped tab operations. See §Tab Management below. | — |
| `config` | Persistent CLI defaults (server, timeout, proxy, session) | Set default server URL, timeout, proxy, or session name. See §Configuration. | — |

### Refreshing This Skill

The `skills` command retrieves bundled skill content that always matches the installed CLI version. Use it to get current instructions rather than relying on cached copies:

```bash
browser4-cli skills                         # List all bundled skills
browser4-cli skills get browser4-cli        # Print this SKILL.md
browser4-cli skills get browser4-cli --full # Include all reference files
browser4-cli skills path                    # Print skills directory path
browser4-cli skills unpack                  # Unpack bundled skill files to disk
```

Set `BROWSER4_SKILLS_DIR` to override the skills directory location. Skill files are unpacked automatically during `browser4-cli install`. Use `skills unpack` to refresh or relocate skill files without reinstalling.

## 4. Decision Trees


=== LINES 370-450 ===

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
```

**Critical syntax rules** (H2 SQL engine — violating these produces opaque errors):

| Rule | Correct | Wrong |
|------|---------|-------|
| CSS selectors use **single** quotes (SQL string literals) | `'h2'`, `'.price'` | `"h2"` (SQL identifier) |
| `@url` placeholder is **unquoted** | `@url` | `'@url'` (literal string) |
| FROM source is always `DOM_LOAD_AND_SELECT` | `DOM_LOAD_AND_SELECT(@url, '...')` | Any other table name |
| No CTEs (`WITH`), no `JOIN`, no subqueries | Simple `SELECT … FROM …` | `WITH t AS (…) SELECT …` |

**Discover selectors** before writing the query:
```bash
browser4-cli htmlsnapshot inspect                    # interactive: lists all elements with CSS classes/ids
browser4-cli htmlsnapshot summary                    # statistical summary of selectors on the page
browser4-cli htmlsnapshot get text ".price" --all    # quick test: does this selector match elements?
```

**Common mistakes and solutions:**

| Symptom | Likely cause | Fix |
|---------|-------------|-----|
| `Column "h2" not found` | Double quotes around CSS selector → treated as SQL column name | Use single quotes: `'h2'` |
| `Table "..." not found` | Wrong FROM source or quoted `@url` | Use `DOM_LOAD_AND_SELECT(@url, 'selector')` |
| Empty result set | Selector doesn't match any elements | Run `htmlsnapshot inspect` to find valid selectors |
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

codex
I've read the complete SKILL.md. Now let me check the MockSite status, LLM key availability, and the local reference docs for the commands I'll use.
exec
"C:\\Program Files\\PowerShell\\7\\pwsh.exe" -Command 'try { $r = Invoke-WebRequest -Uri "http://localhost:18080/" -UseBasicParsing -TimeoutSec 10; Write-Output "MockSite HTTP $($r.StatusCode), length $($r.Content.Length)" } catch { Write-Output "MockSite NOT reachable: $($_.Exception.Message)" }' in D:\workspace\Browser4\Browser4-4.13
 succeeded in 421ms:
MockSite HTTP 200, length 45

exec
"C:\\Program Files\\PowerShell\\7\\pwsh.exe" -Command 'Write-Output "DEEPSEEK_API_KEY set: $([bool]$env:DEEPSEEK_API_KEY)"; Write-Output "OPENROUTER_API_KEY set: $([bool]$env:OPENROUTER_API_KEY)"; Write-Output "VOLCENGINE_API_KEY set: $([bool]$env:VOLCENGINE_API_KEY)"; Write-Output "OPENAI_API_KEY set: $([bool]$env:OPENAI_API_KEY)"; browser4-cli doctor' in D:\workspace\Browser4\Browser4-4.13
 succeeded in 422ms:
DEEPSEEK_API_KEY set: False
OPENROUTER_API_KEY set: False
VOLCENGINE_API_KEY set: False
OPENAI_API_KEY set: False
Browser4 Doctor
================

-- Stale File Cleanup --
  No stale daemon files found

-- CLI Build Info --
  CLI version: 4.13.4
  Installed runtime: v4.13.4
  Installed at: 2026-08-14T06:32:39.351436100+00:00
  ✓ CLI and runtime versions match.

-- Backend Build Info --
  version: "4.13.4-SNAPSHOT"
  buildTime: "2026-08-14T07:24:47.303143200Z"
  ⚠  Backend is a development snapshot — it may be unstable.

-- LLM Status --
  ✓ LLM is configured.
  Source: configuration file (~/.browser4/config/)

💡 Tip: Use 'doctor --verbose' to include logs and metrics inline, or use 'doctor log' / 'doctor metrics' subcommands for more control.

💡 Tip: Run 'browser4-cli doctor --fix' to auto-repair common issues (reinstall Chrome, purge old state, clean temp files).

codex
MockSite is up and LLM is configured via config file. Let me verify which LLM provider is set and read the key reference docs.
exec
"C:\\Program Files\\PowerShell\\7\\pwsh.exe" -Command 'Get-ChildItem "$HOME/.browser4/config" -ErrorAction SilentlyContinue | Select-Object Name, Length; Write-Output "---"; Get-ChildItem "$HOME/.browser4" -Force -ErrorAction SilentlyContinue | Select-Object Name' in D:\workspace\Browser4\Browser4-4.13
exec
"C:\\Program Files\\PowerShell\\7\\pwsh.exe" -Command 'Get-ChildItem skills/browser4-cli/references | Select-Object Name, Length | Format-Table -AutoSize' in D:\workspace\Browser4\Browser4-4.13
 succeeded in 352ms:

Name               Length
----               ------
conf-available     
conf-enabled       
mcp                
---
agent              1
archive            1
browser            1
command            1
conf               1
config             1
coworker           1
data               1
loops              1
proxy              1
sessions           1
tmp                1
tta                1
async-tasks.json   1
config.json        1
loop-history.jsonl 1


 succeeded in 349ms:

Name                                 Length
----                                 ------
agent.md                               5116
attach.md                              7554
crawl.md                              14652
css-selector-bridge.md                10199
development.md                         2087
htmlsnapshot-scenarios-advanced.md    12626
htmlsnapshot-scenarios-amazon.md      26258
htmlsnapshot-scenarios-audit.md       16100
htmlsnapshot-scenarios-extraction.md  12315
htmlsnapshot-scenarios.md              5544
htmlsnapshot.md                       17373
load-options-guide.md                 14700
loop.md                               12706
power-dom.md                           5781
shell-quoting.md                       5783
skills.md                              7601
snapshot.md                           10321
storage-state.md                       4464
swarm.md                              11055
webdb.md                               5767
x-sql-array-functions.md               3062
x-sql-dom-functions.md                16835
x-sql-dom-load-select.md               3089
x-sql-dom-select-functions.md         10460
x-sql-string-functions.md             16259
x-sql.md                              20507


codex
Let me read the key reference docs: htmlsnapshot, X-SQL, crawl, and agent.
exec
"C:\\Program Files\\PowerShell\\7\\pwsh.exe" -Command 'Get-Content skills/browser4-cli/references/htmlsnapshot.md -Raw' in D:\workspace\Browser4\Browser4-4.13
 succeeded in 334ms:
---
title: "HTML Snapshot — Static DOM Extraction, Inspection & X-SQL Querying"
description: "Reference for htmlsnapshot commands (capture, get, query, summary, export, grep, inspect). Extract structured data from the raw HTML DOM via CSS selectors and X-SQL queries."
tier: catalog
---

# HTML Snapshot — Static DOM Extraction, Inspection & X-SQL Querying

The `htmlsnapshot` family operates on a **static HTML snapshot** — the raw HTML of the current page parsed into a queryable DOM. Unlike interactive `snapshot` (accessibility-tree refs for `click`/`type`/`fill`), `htmlsnapshot` extracts structured data via CSS selectors and X-SQL queries.

## Comparison: snapshot vs htmlsnapshot

| Feature | `snapshot` | `htmlsnapshot` |
|---|---|---|
| Data source | Accessibility tree | Raw HTML DOM |
| Element addressing | Refs (`e5`) | CSS selectors only |
| X-SQL support | No | Yes (`query`) |
| Interactive element list | No | Yes (`htmlsnapshot` capture returns interactiveElements) |
| Selector discovery | No | Yes (`inspect`) |
| Output | YAML accessibility tree | HTML (`export`), structured data (`get`/`query`/`inspect`) |

## Commands

```bash
browser4-cli htmlsnapshot                                # capture fresh static HTML snapshot + metadata
browser4-cli htmlsnapshot get <field> [selector] [name] [--page N] [--page-size N] [--all]  # extract text/html/attr via CSS; html paginated at 2K lines, text not paginated
browser4-cli htmlsnapshot query [url] --sql <query>      # X-SQL query against DOM (url defaults to current page)
browser4-cli htmlsnapshot summary                        # compressed page summary (WPSI)
browser4-cli htmlsnapshot export [--file <path>] [--clean]  # save snapshot HTML to file
browser4-cli htmlsnapshot get all <field> [selector] [name] [--offset N] [--limit N] [--page N] [--page-size N] [--all]  # extract ALL matches; html paginated at 2K lines, text not paginated
browser4-cli htmlsnapshot grep [OPTIONS] <pattern> [--page N] [--page-size N] [--all]  # search snapshot HTML with regex; paginated by default (2K lines)
browser4-cli htmlsnapshot inspect [selector] [--max N] [--depth D]  # analyze DOM structure, suggest CSS selectors
```

`htmlsnapshot` (capture) always fetches a fresh snapshot, caches it, and returns enriched metadata including image/link counts and a list of interactive elements (with tag, class, id, aria attributes, and bounding box). Subsequent `get`/`query`/`export`/`inspect` reuse the cache until the next capture or page navigation.

> **Note:** `htmlsnapshot get` looks up the page using the browser's current URL (after any redirects/navigations), so it works correctly on search-results pages and post-form-submission pages.

## Get — Extract data via CSS selectors

Only CSS selectors are accepted — element refs (`e5`) are rejected.

```bash
# First match only (querySelector semantics)
browser4-cli htmlsnapshot get <text|html|attr> <selector> [name]

# All matches (querySelectorAll semantics)
browser4-cli htmlsnapshot get all <text|html|attr> <selector> [name] [--offset N] [--limit N]
```

| Field | Description | Requires `name`? |
|---|---|---|
| `text` | Visible text of matched element(s) | No |
| `html` | Inner HTML of matched element(s) | No |
| `attr` | Value of a named attribute | **Yes** (3rd argument) |

**`get` returns only the first match.** For multiple results, use `htmlsnapshot get all` (returns a JSON array) or `htmlsnapshot query`.

> **Warning:** Correlating multiple fields: Each `get all` call scans the whole document independently — running `get all text ".title"` and `get all text ".price"` produces two unaligned arrays (different lengths, different order). To extract correlated fields (title + price + URL per item), use `htmlsnapshot query` with X-SQL's `DOM_LOAD_AND_SELECT` scoped to a parent container. See the [list-page scraping pattern](x-sql-dom-load-select.md#dom_load_and_select).

### `get` (single)

```bash
browser4-cli htmlsnapshot get text ".product-title"
browser4-cli htmlsnapshot get attr ".product-image" data-src
```

### `get all` (multiple)

Returns a JSON array of strings.  Supports `--offset` (skip first N) and `--limit` (max results).

```bash
browser4-cli htmlsnapshot get all text "h2 a"                  # all product titles
browser4-cli htmlsnapshot get all attr ".product-image" src    # all image URLs
browser4-cli htmlsnapshot get all text ".result" --limit 5     # first 5 results
browser4-cli htmlsnapshot get all text ".result" --offset 10   # skip first 10
```

### Troubleshooting empty results

If `htmlsnapshot get` returns an empty string when the page clearly has matching elements:

1. **Run `htmlsnapshot` first to capture a fresh snapshot:** `browser4-cli htmlsnapshot` then retry `get`
2. **Verify the CSS selector** with `htmlsnapshot grep <pattern>` to search the raw HTML
3. **Use `htmlsnapshot query` or `htmlsnapshot get all`** for multiple results or complex queries
4. **Check page load:** ensure the page finished loading (AJAX content may take time)

## Query — X-SQL against HTML snapshot

The `--sql` flag is **required**. Use `@url` as a placeholder for the target URL.

X-SQL uses the **H2 database** SQL dialect with DOM UDFs. Only simple `SELECT ... FROM DOM_LOAD_AND_SELECT(url, cssQuery)` queries are supported — no CTEs, subqueries, `EXPLODE`, or joins.

> **Important:** `@url` must appear **unquoted** in SQL. `SQLTemplate.createSQL(url)` handles escaping internally.
> - ✅ `FROM DOM_LOAD_AND_SELECT(@url, ':root')`
> - ❌ `FROM DOM_LOAD_AND_SELECT('@url', ':root')`
> - ❌ `FROM DOM_LOAD_AND_SELECT('.', ':root')` — the literal `'.'` is not a valid URL. Use the `@url` placeholder to reference the current page.

### Three ways to provide the SQL query

**1. File (recommended — no shell escaping issues):**
Prefix the `--sql` value with `@` to read from a `.sql` file:

```bash
# Write query to file (no escaping needed)
cat > query.sql << 'SQLEOF'
SELECT
  DOM_BASE_URI(dom) AS url,
  DOM_FIRST_TEXT(dom, '#productTitle') AS title
FROM DOM_LOAD_AND_SELECT(@url, 'body')
WHERE DOM_FIRST_TEXT(dom, '#productTitle') != 'Sponsored'
SQLEOF

# Run it
browser4-cli htmlsnapshot query "https://www.amazon.com/dp/B08PP5MSVB" --sql @query.sql
```

> **Note:** X-SQL function names are case-insensitive. `DOM_FIRST_TEXT` and `dom_first_text` are equivalent. This reference uses UPPERCASE for clarity.

**2. Stdin (for piped/scripted workflows — also avoids quoting):**

```bash
cat query.sql | browser4-cli htmlsnapshot query --sql-stdin
# or
browser4-cli htmlsnapshot query --sql-stdin < query.sql
# with a URL
browser4-cli htmlsnapshot query "https://example.com" --sql-stdin < query.sql
```

**3. Base64 (transport-safe — no quoting, works across all platforms):**

```bash
# Encode once, pass anywhere without escaping
base64 -w0 query.sql > query.b64
browser4-cli htmlsnapshot query "https://example.com" --sql @query.b64 --sql-base64

# Or inline the base64 value directly
browser4-cli htmlsnapshot query "https://example.com" --sql "$(base64 -w0 query.sql)" --sql-base64
```

**4. Inline (requires careful shell escaping on Windows):**

```bash
# Simple queries without quotes in selectors work inline:
browser4-cli htmlsnapshot query --sql "
  SELECT DOM_BASE_URI(dom) AS url, DOM_FIRST_TEXT(dom, 'h1') AS title
  FROM DOM_LOAD_AND_SELECT(@url, 'body');
"

# Queries with quoted selectors or != require escaping — prefer @file, --sql-stdin, or --sql-base64
```

To control caching or rendering, append load options to the URL (e.g. `https://example.com/page -i 1d -njr 3`).

## Summary — Web Page Summary Index (WPSI)

Generates a deterministic, AI-readable compressed page summary (typically <1% of original HTML) as a YAML file. Includes page metadata, structure landmarks, key content nodes with CSS selector hints, list/table detection, and stats. Requires a previously captured HTML snapshot.

```bash
browser4-cli htmlsnapshot summary
```

## Export

Save full snapshot HTML to a local file. The exported HTML is pretty-formatted for direct use with tools like `grep`. Use `--clean` to produce a minimal HTML file suitable for LLM consumption — strips `<script>`, `<style>`, `<noscript>`, comments, and non-standard attributes while preserving semantic structure.

```bash
browser4-cli htmlsnapshot export [--file page-snapshot.html] [--clean]
```

## Grep — Search snapshot HTML

Search the HTML snapshot HTML with regex patterns and grep-style output. Performs matching client-side (no backend changes) by fetching the HTML via `html_snapshot_export` then matching locally.

```bash
browser4-cli htmlsnapshot grep [OPTIONS] <pattern>
```

### Flags

| Flag | Description |
|---|---|
| `-i` | Case-insensitive matching |
| `-A N` | Show N lines after each match |
| `-B N` | Show N lines before each match |
| `-C N` | Show N lines before and after each match |
| `-v` | Invert match (select non-matching lines) |
| `-c` | Print only the count of matching lines |
| `-l` | Print only whether matches exist (grep-style "files-with-matches"; exits 0 if found) |
| `-F` | Treat pattern as a literal string, not regex |
| `-w` | Match only whole words (wraps pattern with `\b` word boundaries) |
| `--no-line-number` | Suppress line numbers in output (line numbers are shown by default) |
| `--selector <CSS>` | Scope search to a specific CSS element (fetches inner HTML via `html_snapshot_scrape`) |
| `--page N` | Show page N of paginated output (default: 1) |
| `--page-size N` | Characters per page (default: 1024) |
| `--all` | Show all output, disabling pagination |

Line numbers are **on by default** (unlike GNU grep where you opt in with `-n`). Use `--no-line-number` to suppress them.

For CI pass/fail checks, use `-l` (prints "htmlsnapshot" if matches exist) or `-c` (prints match count). Check the CLI exit code (`browser4-cli ... && echo PASS || echo FAIL`) — a non-zero exit means the backend call failed, not that matches were absent. `-l` always exits 0 when the backend call succeeds; the match/no-match result is in the output text.


### Examples

```bash
# Find all lines containing "error" (case-insensitive)
browser4-cli htmlsnapshot grep -i error

# Literal string match with 2 lines of context
browser4-cli htmlsnapshot grep -F -C 2 "404 Not Found"

# Count how many lines contain TODO, FIXME, or HACK
browser4-cli htmlsnapshot grep -c 'TODO|FIXME|HACK'

# Search only within <main> element
browser4-cli htmlsnapshot grep --selector main "Submit"

# Whole-word search for "password"
browser4-cli htmlsnapshot grep -w password

# Show non-empty lines (invert match on empty/whitespace-only)
browser4-cli htmlsnapshot grep -v '^\s*$'

# Search with pagination (page 2, custom page size)
browser4-cli htmlsnapshot grep -i error --page 2 --page-size 500

# Show all matches (disable pagination, useful for piping)
browser4-cli htmlsnapshot grep --all "pattern"
```

### Output format

Matches are printed with `N:` (line number + colon) followed by the line content. Context lines use `N:-` (line number, colon, dash) to distinguish them visually from match lines. Non-contiguous context groups are separated by `--`.

```
42:    <h1>Welcome to My Page</h1>
43:-    <nav>
44:      <a href="/login">Login</a>
45:-    </nav>
--
108:    <footer>Copyright 2026</footer>
```

When `--no-line-number` is passed, the line-number prefix is omitted entirely. Match and context lines are then distinguished only by the `-` prefix on context lines.

## Inspect — Discover CSS selectors for recurring patterns

Analyzes the HTML snapshot and suggests CSS selectors for recurring content patterns. Essential for complex pages where you don't know the right selectors ahead of time (e.g., e-commerce search results, news listings).

```bash
browser4-cli htmlsnapshot inspect [selector] [--max N] [--depth D]
```

| Parameter | Default | Description |
|---|---|---|
| `selector` | `:root` | CSS selector to scope inspection. When it matches multiple elements (e.g. `.product-card`), the command compares child structures across matches to find recurring patterns. |
| `--max N` | 10 | Max matching elements to analyze. |
| `--depth D` | 5 | Max descendant depth for selector suggestions. |

### How it works

When `selector` matches **multiple elements** (e.g. `.product-card`):
1. Finds all elements matching `selector`
2. For each match, walks descendants up to `--depth` and computes relative CSS selectors (tag + class + id)
3. Counts how many matches each selector appears in
4. Filters to selectors appearing in **≥50%** of matches (minimum 2)
5. Returns sample structures and ranked selector suggestions

When `selector` matches only **1 element** (e.g. default `:root`, or `body`), **auto-discovery** activates:
1. Walks the DOM to find groups of sibling elements sharing the same CSS signature
2. Scores each group by size × specificity × content-variance × structural-richness
3. Picks the best repeating pattern (e.g. `.product-card`) and re-runs the pipeline against it
4. Adds `autoDiscovered: true` and `originalSelector` to the response

### Output

```
### Inspect: ".product_pod" (20 matches, 10 analyzed)

  Sample structure (3 of 20):
  -- Element 1: article.product_pod
      img.thumbnail  "A Light in the Attic"
      h3              ""
       a              "A Light in the..."
      div.product_price
       p.price_color  "£51.77"
  ...

  Suggested selectors (recurring across matches):
   10/10 (100%)  h3 a                                         → "A Light in the..."
   10/10 (100%)  img.thumbnail                                → ""
   10/10 (100%)  p.price_color                                → "£51.77"
    8/10 ( 80%)  p.instock.availability                       → "In stock"
```

### Tips

- **Start without arguments:** `htmlsnapshot inspect` (no selector) triggers auto-discovery and finds the page's most prominent repeating content pattern. This is the quickest way to discover selectors on an unfamiliar page.
- **Start broad, then narrow:** First run without a selector to see page landmarks. Then target a repeating container (e.g. `.product_pod`, `.s-result-item`).
- **Always capture first:** `htmlsnapshot` must be run before `inspect` (it loads the cached document).
- **Use with `get`:** Take the suggested selectors and use them with `htmlsnapshot get all` or `htmlsnapshot query` for batch extraction.
- **Avoid quoting hell:** Use `--sql @file.sql` (file), `--sql-stdin` (piped), or `--sql-base64` (encoded) instead of inline `--sql "..."` on Windows — quoted CSS selectors and `!=` operators break inline SQL.
- **Base64 for portability:** `--sql "$(base64 -w0 query.sql)" --sql-base64` passes SQL safely through any shell, CI pipeline, or HTTP transport with zero quoting issues.
- **`@file` paths resolve relative to CWD first**, then fall back to the Browser4 repo root — so `cargo run` from `cli/browser4-cli` still finds `query.sql` at the workspace root.

## Error Handling

- `htmlsnapshot` capture fails if backend is unreachable or page cannot be loaded.
- `htmlsnapshot get` exits non-zero when the CSS selector matches nothing or an element ref (`e5`) is passed.
- `htmlsnapshot query` fails on invalid X-SQL syntax or missing `--sql`.
- `htmlsnapshot export` / `summary` / `inspect` fail if no snapshot has been captured yet.

## Notes

- `htmlsnapshot get` only accepts CSS selectors. For interactive element interaction, use the standard `snapshot` + ref-based commands.
- X-SQL queries through `htmlsnapshot query` follow the same constraints as `swarm query`. See [X-SQL reference](x-sql.md) for full function documentation.
- The captured snapshot is cached in the backend and invalidated by the next `htmlsnapshot` capture or a page navigation (`goto`, `reload`, etc.).
- `htmlsnapshot grep` performs matching **entirely client-side** in the CLI — the full HTML is fetched from the backend once, then all regex matching happens locally. No backend round-trips for the search itself.
- For CI pass/fail checks with grep, use `-l` (prints "htmlsnapshot" if matches found) or `-c` (prints match count). A `browser4-cli` non-zero exit code means the backend call itself failed, not that matches were absent.
- `htmlsnapshot` capture now returns enriched metadata: `imageCount`, `linkCount`, and `interactiveElements` (tag, class, id, aria attributes, bounding-box). The bounding box is extracted from the `vi` attribute injected by the browser's layout engine.
- `htmlsnapshot inspect` computes relative CSS selectors using tag + class + id. It does not use AI — the algorithm is fully deterministic and based on structural recurrence across matching elements. When run without a selector (or any single-match selector like `:root`), **auto-discovery** finds the page's most prominent repeating content pattern automatically — no prior knowledge of the page's markup is needed.
- **Output pagination:** `get html`, `get all html`, and `grep` paginate output by default at 2000 lines per page. `get text` and `get all text` are not paginated by default (text extraction rarely exceeds practical limits). Use `--page N` for subsequent pages, `--page-size N` to change the page size, or `--all` to disable pagination entirely. Pagination is automatically skipped in `--json` and `--quiet` modes. Use `--all` when piping output to external tools.


exec
"C:\\Program Files\\PowerShell\\7\\pwsh.exe" -Command 'Get-Content skills/browser4-cli/references/x-sql.md -Raw; Write-Output "`n===== DOM FUNCTIONS ====="; Get-Content skills/browser4-cli/references/x-sql-dom-functions.md -Raw' in D:\workspace\Browser4\Browser4-4.13
 succeeded in 323ms:
---
title: "X-SQL Reference: DOM & String Functions"
description: "Master index for the X-SQL function reference. Links to DOM_LOAD_AND_SELECT, DomFunctions, DomSelectFunctions, StringFunctions, and ArrayFunctions documentation."
tier: catalog
---

# X-SQL Reference: DOM & String Functions

This directory contains the X-SQL function reference, split by function group. Use the links below to read only the section you need.

**SQL constraint:** All queries extracting page data MUST use this pattern:

```sql
SELECT <expressions>
FROM DOM_LOAD_AND_SELECT(url, cssQuery [, offset, limit])
[WHERE <conditions>]
[ORDER BY <expression> [ASC|DESC]]
[LIMIT <n>]
```

No other SQL syntax is supported — no CTEs (`WITH`), no subqueries in `FROM`, no `EXPLODE`, no joins. The only valid table source is `DOM_LOAD_AND_SELECT`.

**URL parameter:** When used through `htmlsnapshot query` or `swarm query`, use the **unquoted** `@url` placeholder to reference the target page URL. Do NOT use `'.'` as a literal URL — it is not valid and will cause a 500 error. The `@url` placeholder is replaced with the actual page URL by `SQLTemplate.createSQL()`.

X-SQL uses the **H2 database** SQL dialect.

---

## Files

| File | Content | Lines |
|------|---------|-------|
| [x-sql-dom-load-select.md](x-sql-dom-load-select.md) | `DOM_LOAD_AND_SELECT` — Page loading with CSS selection | ~55 |
| [x-sql-dom-functions.md](x-sql-dom-functions.md) | `DomFunctions` — Core DOM operations (~65 functions) | ~500 |
| [x-sql-dom-select-functions.md](x-sql-dom-select-functions.md) | `DomSelectFunctions` — CSS selector-based extraction (~50 functions) | ~200 |
| [x-sql-string-functions.md](x-sql-string-functions.md) | `StringFunctions` — String manipulation (~90 functions) | ~430 |
| [x-sql-array-functions.md](x-sql-array-functions.md) | `ArrayFunctions` — Array operations (3 functions) | ~100 |

---

## Quick Reference: Common Patterns

### Scrape a list page (products, articles, etc.)

```sql
SELECT
    DOM_FIRST_TEXT(DOM, '.title') AS title,
    DOM_FIRST_FLOAT(DOM, '.price', 0.0) AS price,
    DOM_FIRST_HREF(DOM, 'a.title-link') AS link,
    DOM_FIRST_IMG(DOM, 'img.thumbnail') AS image,
    STR_DEFAULT_IF_BLANK(DOM_FIRST_TEXT(DOM, '.description'), 'N/A') AS description
FROM DOM_LOAD_AND_SELECT(
    'https://example.com/products -expires 1h',
    '.product-card',
    1, 20
)
WHERE DOM_IS_NOT_NIL(DOM)
  AND STR_IS_NOT_BLANK(DOM_FIRST_TEXT(DOM, '.title'));
```

### Extract page metadata

```sql
SELECT
    DOM_DOC_TITLE(DOM) AS page_title,
    DOM_FIRST_TEXT(DOM, 'meta[name="description"]') AS meta_description,
    DOM_FIRST_IMG(DOM, 'article img') AS hero_image,
    STR_FIRST_FLOAT(DOM_FIRST_TEXT(DOM, '.reading-time'), 0.0) AS reading_minutes
FROM DOM_LOAD_AND_SELECT('https://example.com/article/123', ':root');
```

### Clean and transform scraped text

```sql
SELECT
    DOM_TEXT(DOM) AS raw_text,
    STR_NORMALIZE_SPACE(STR_TRIM(DOM_TEXT(DOM))) AS cleaned,
    STR_DEFAULT_IF_BLANK(
        STR_ABBREVIATE(STR_NORMALIZE_SPACE(STR_TRIM(DOM_TEXT(DOM))), 200),
        '[empty]'
    ) AS display_text
FROM DOM_LOAD_AND_SELECT('https://example.com', 'p');
```

### Extract key-value specs with regex

```sql
-- Extract "Label: Value" pairs from a specs table
SELECT DOM_ALL_RE2(DOM, '.specs-table tr', '(.+?):\s*(.+)') AS specs
FROM DOM_LOAD_AND_SELECT('https://example.com/product/42', '.specs-table');

-- Extract all prices from a page
SELECT DOM_ALL_RE1(DOM, '.price', '\$([\d,]+\.?\d*)') AS prices
FROM DOM_LOAD_AND_SELECT('https://shop.example.com/sale', 'body');
```

### DOM tree analysis

```sql
-- Find the content-heavy containers on a page
SELECT
    DOM_CSS_SELECTOR(DOM) AS selector,
    DOM_TAG_NAME(DOM) AS tag,
    DOM_TEXT_LEN(DOM) AS text_chars,
    DOM_A(DOM) AS links,
    DOM_IMG(DOM) AS images,
    DOM_DEP(DOM) AS depth
FROM DOM_LOAD_AND_SELECT('https://example.com', 'div,section,article,main')
WHERE DOM_TEXT_LEN(DOM) > 200
ORDER BY DOM_TEXT_LEN(DOM) DESC;
```

### Array-based fallback chains

```sql
-- Try multiple selectors and use the first one that returns content
SELECT ARRAY_FIRST_NOT_BLANK(
    MAKE_ARRAY(
        DOM_FIRST_TEXT(DOM, 'h1.product-title'),
        DOM_FIRST_TEXT(DOM, '.product-name'),
        DOM_FIRST_TEXT(DOM, 'title'),
        'Unknown Product'
    )
) AS product_name
FROM DOM_LOAD_AND_SELECT('https://shop.example.com/product/42', 'body');
```

---

## Function Input/Output Types & Composability

X-SQL functions fall into **two type categories** based on how they interact with DOM elements:

| Type | Signature | Examples | Can compose with |
|------|-----------|----------|-----------------|
| **ValueDom functions** | Take `(DOM [, selector])` where `DOM` is a `ValueDom` node | `DOM_TEXT(DOM)`, `DOM_ABS_SRC(DOM)`, `DOM_HREF(DOM)`, `DOM_DOC_TITLE(DOM)` | Other ValueDom functions: `DOM_ABS_SRC(DOM_SELECT_FIRST(DOM, 'img'))` ✅ |
| **Scalar functions** | Take `(DOM, selector)` and return `String`/`Int`/`Float` | `DOM_FIRST_TEXT(DOM, 'h1')`, `DOM_FIRST_ATTR(DOM, 'img', 'src')`, `DOM_FIRST_IMG(DOM, 'img')` | String functions: `STR_DEFAULT_IF_BLANK(DOM_FIRST_TEXT(...), 'N/A')` ✅ |

**Critical rule:** You cannot pass a `String` (from a scalar function) where a `ValueDom` is expected, and vice versa.

### Common Composition Mistakes

```sql
-- ❌ WRONG: DOM_FIRST_IMG returns a String (the src attribute), not a ValueDom.
--    DOM_ABS_SRC expects a ValueDom, so it receives a String and fails with a
--    misleading 417 "scrape session closed" error.
SELECT DOM_ABS_SRC(DOM_FIRST_IMG(DOM, 'img')) AS image_url
FROM DOM_LOAD_AND_SELECT(@url, '#product');

-- ✅ CORRECT: Use DOM_FIRST_ATTR to get the src attribute directly.
--    No DOM_ABS_SRC needed — just pass the attribute name.
SELECT DOM_FIRST_ATTR(DOM, 'img', 'src') AS image_url
FROM DOM_LOAD_AND_SELECT(@url, '#product');

-- ✅ ALSO CORRECT: Select the DOM element first, then get the absolute src.
SELECT DOM_ABS_SRC(DOM_SELECT_FIRST(DOM, 'img')) AS image_url
FROM DOM_LOAD_AND_SELECT(@url, '#product');
```

### Visual Composition Graph

```
ValueDom functions (input: DOM element, output: varies)
┌─────────────────────────────────────────────┐
│ DOM_LOAD()     → ValueDom                   │
│ DOM_SELECT_FIRST(DOM, sel) → ValueDom       │
│ DOM_PARENT(DOM) → ValueDom                  │
│ DOM_ANCESTOR(DOM, tag) → ValueDom           │
│                                             │
│ DOM_TEXT(DOM) → String                      │
│ DOM_ABS_SRC(DOM) → String      ⚠ expects    │
│ DOM_ABS_HREF(DOM) → String      ValueDom!   │
│ DOM_HREF(DOM) → String                      │
│ DOM_SRC(DOM) → String                       │
│ DOM_DOC_TITLE(DOM) → String                 │
│ DOM_BASE_URI(DOM) → String                  │
└─────────────────────────────────────────────┘
        ▲
        │ CAN compose: DOM_ABS_SRC(DOM_SELECT_FIRST(DOM, 'img'))
        │
        │ CANNOT compose: DOM_ABS_SRC(DOM_FIRST_IMG(DOM, 'img'))
        │                 ↑ returns String, not ValueDom
        ▼
Scalar functions (input: DOM + selector string, output: scalar)
┌─────────────────────────────────────────────┐
│ DOM_FIRST_TEXT(DOM, sel) → String           │
│ DOM_FIRST_ATTR(DOM, sel, attr) → String     │
│ DOM_FIRST_IMG(DOM, sel) → String (src attr) │
│ DOM_FIRST_HREF(DOM, sel) → String           │
│ DOM_ALL_TEXTS(DOM, sel) → ValueArray        │
│ DOM_ALL_ATTRS(DOM, sel, attr) → ValueArray  │
└─────────────────────────────────────────────┘
```

---

## Function Index by SQL Alias

**Where to find detailed docs:** Functions in the "Element property", "Tree navigation", "Text", "Link/Image", "Regex", "HTML", "Feature", and "State check" categories are documented in [x-sql-dom-functions.md](x-sql-dom-functions.md). Functions in the "CSS select", "Attribute extraction", "Visual", and "DOM manipulation" categories are documented in [x-sql-dom-select-functions.md](x-sql-dom-select-functions.md). "Page loading" functions are in [x-sql-dom-load-select.md](x-sql-dom-load-select.md). String functions are in [x-sql-string-functions.md](x-sql-string-functions.md). Array functions are in [x-sql-array-functions.md](x-sql-array-functions.md).

### DOM Namespace

> **Legend:** `DOM` = `ValueDom` node (from `FROM DOM_LOAD_AND_SELECT`). `DOM, sel` = ValueDom + CSS selector string. `DOM, sel, attr` = ValueDom + selector + attribute name.

| SQL Alias | Input | Returns | Category |
|-----------|-------|---------|----------|
| `DOM_LOAD_AND_SELECT` | `(url, sel)` | `ResultSet` | Page loading + CSS selection |
| `DOM_LOAD` | `(url)` | `ValueDom` | Page loading |
| `DOM_FETCH` | `(url)` | `ValueDom` | Page loading |
| `DOM_IS_NIL` | `(DOM)` | `Boolean` | State check |
| `DOM_IS_NOT_NIL` | `(DOM)` | `Boolean` | State check |
| `DOM_ATTR` | `(DOM, attr)` | `String` | Element property → DomFunctions |
| `DOM_LABELS` | `(DOM)` | `String` | Element property |
| `DOM_FEATURE` | `(DOM, feat)` | `Double` | Element property |
| `DOM_HAS_ATTR` | `(DOM, attr)` | `Boolean` | Element property |
| `DOM_STYLE` | `(DOM, prop)` | `String` | Element property |
| `DOM_SEQUENCE` | `(DOM)` | `Int` | Element property |
| `DOM_DEPTH` | `(DOM)` | `Int` | Element property |
| `DOM_CSS_SELECTOR` | `(DOM)` | `String` | Element property |
| `DOM_CSS_PATH` | `(DOM)` | `String` | Element property |
| `DOM_SIBLING_SIZE` | `(DOM)` | `Int` | Tree navigation |
| `DOM_SIBLING_INDEX` | `(DOM)` | `Int` | Tree navigation |
| `DOM_ELEMENT_SIBLING_SIZE` | `(DOM)` | `Int` | Tree navigation |
| `DOM_ELEMENT_SIBLING_INDEX` | `(DOM)` | `Int` | Tree navigation |
| `DOM_URI` | `(DOM)` | `String` | URL/Location |
| `DOM_BASE_URI` | `(DOM)` | `String` | URL/Location |
| `DOM_ABS_URL` | `(DOM, url)` | `String` | URL/Location |
| `DOM_LOCATION` | `(DOM)` | `String` | URL/Location |
| `DOM_CHILD_NODE_SIZE` | `(DOM)` | `Int` | Tree navigation |
| `DOM_CHILD_ELEMENT_SIZE` | `(DOM)` | `Int` | Tree navigation |
| `DOM_TAG_NAME` | `(DOM)` | `String` | Element identity |
| `DOM_HREF` | `(DOM)` | `String` | Link/Image |
| `DOM_ABS_HREF` | `(DOM)` | `String` | Link/Image |
| `DOM_SRC` | `(DOM)` | `String` | Link/Image |
| `DOM_ABS_SRC` | `(DOM)` ⚠ | `String` | Link/Image |
| `DOM_TITLE` | `(DOM)` | `String` | Title |
| `DOM_DOC_TITLE` | `(DOM)` | `String` | Title |
| `DOM_HAS_TEXT` | `Boolean` | Text |
| `DOM_TEXT` | `String` | Text |
| `DOM_TEXT_LEN` | `Int` | Text |
| `DOM_TEXT_LENGTH` | `Int` | Text |
| `DOM_OWN_TEXT` | `String` | Text |
| `DOM_OWN_TEXTS` | `ValueArray` | Text |
| `DOM_OWN_TEXT_LEN` | `Int` | Text |
| `DOM_WHOLE_TEXT` | `String` | Text |
| `DOM_WHOLE_TEXT_LEN` | `Int` | Text |
| `DOM_RE1` | `String` | Regex |
| `DOM_RE2` | `ValueArray` | Regex |
| `DOM_DATA` | `String` | Element identity |
| `DOM_ID` | `String` | Element identity |
| `DOM_CLASS_NAME` | `String` | Element identity |
| `DOM_CLASS_NAMES` | `Set` | Element identity |
| `DOM_HAS_CLASS` | `Boolean` | Element identity |
| `DOM_VALUE` | `String` | Element identity |
| `DOM_OWNER_DOCUMENT` | `ValueDom` | Tree navigation |
| `DOM_OWNER_BODY` | `ValueDom` | Tree navigation |
| `DOM_DOCUMENT_VARIABLES` | `ValueDom` | Tree navigation |
| `DOM_PARENT` | `ValueDom` | Tree navigation |
| `DOM_ANCESTOR` | `ValueDom` | Tree navigation |
| `DOM_PARENT_NAME` | `String` | Tree navigation |
| `DOM_DOM` | `ValueDom` | HTML |
| `DOM_HTML` | `String` | HTML |
| `DOM_OUTER_HTML` | `String` | HTML |
| `DOM_SLIM_HTML` | `String` | HTML |
| `DOM_MINIMAL_HTML` | `String` | HTML |
| `DOM_UNIQUE_NAME` | `String` | Element identity |
| `DOM_LINKS` | `ValueArray` | Link/Image |
| `DOM_CH` | `Double` | Feature |
| `DOM_TN` | `Double` | Feature |
| `DOM_IMG` | `Double` | Feature |
| `DOM_A` | `Double` | Feature |
| `DOM_SIB` | `Double` | Feature |
| `DOM_C` | `Double` | Feature |
| `DOM_DEP` | `Double` | Feature |
| `DOM_SEQ` | `Double` | Feature |
| `DOM_TOP` | `Double` | Feature |
| `DOM_LEFT` | `Double` | Feature |
| `DOM_WIDTH` | `Double` | Feature |
| `DOM_HEIGHT` | `Double` | Feature |
| `DOM_AREA` | `Double` | Feature |
| `DOM_ASPECT_RATIO` | `Double` | Feature |
| `DOM_SELECT_ALL` | `(DOM, sel)` | `ValueArray` | CSS select |
| `DOM_SELECT_FIRST` | `(DOM, sel)` | `ValueDom` | CSS select |
| `DOM_SELECT_NTH` | `(DOM, sel, n)` | `ValueDom` | CSS select |
| `DOM_ALL_TEXTS` | `(DOM, sel)` | `ValueArray` | CSS select |
| `DOM_FIRST_TEXT` | `(DOM, sel)` | `String` | CSS select |
| `DOM_NTH_TEXT` | `(DOM, sel, n)` | `String` | CSS select |
| `DOM_ALL_OWN_TEXTS` | `(DOM, sel)` | `ValueArray` | CSS select |
| `DOM_FIRST_OWN_TEXT` | `(DOM, sel)` | `String` | CSS select |
| `DOM_NTH_OWN_TEXT` | `(DOM, sel, n)` | `String` | CSS select |
| `DOM_WHOLE_TEXTS` | `(DOM, sel)` | `ValueArray` | CSS select |
| `DOM_FIRST_WHOLE_TEXT` | `(DOM, sel)` | `String` | CSS select |
| `DOM_NTH_WHOLE_TEXT` | `(DOM, sel, n)` | `String` | CSS select |
| `DOM_ALL_SLIM_HTMLS` | `(DOM, sel)` | `ValueArray` | CSS select |
| `DOM_FIRST_SLIM_HTML` | `(DOM, sel)` | `String` | CSS select |
| `DOM_NTH_SLIM_HTML` | `(DOM, sel, n)` | `String` | CSS select |
| `DOM_ALL_MINIMAL_HTMLS` | `(DOM, sel)` | `ValueArray` | CSS select |
| `DOM_FIRST_MINIMAL_HTML` | `(DOM, sel)` | `String` | CSS select |
| `DOM_NTH_MINIMAL_HTML` | `(DOM, sel, n)` | `String` | CSS select |
| `DOM_ALL_INTEGERS` | `(DOM, sel)` | `ValueArray` | CSS select |
| `DOM_FIRST_INTEGER` | `(DOM, sel)` | `Int` | CSS select |
| `DOM_NTH_INTEGER` | `(DOM, sel, n)` | `Int` | CSS select |
| `DOM_ALL_FLOATS` | `(DOM, sel)` | `ValueArray` | CSS select |
| `DOM_FIRST_FLOAT` | `(DOM, sel)` | `ValueFloat` | CSS select |
| `DOM_NTH_FLOAT` | `(DOM, sel, n)` | `ValueFloat` | CSS select |
| `DOM_ALL_ATTRS` | `(DOM, sel, attr)` | `ValueArray` | CSS select |
| `DOM_FIRST_ATTR` | `(DOM, sel, attr)` | `String` | CSS select |
| `DOM_NTH_ATTR` | `(DOM, sel, attr, n)` | `String` | CSS select |
| `DOM_ALL_MULTI_ATTRS` | `(DOM, sel, attrs)` | `ValueArray` | CSS select |
| `DOM_FIRST_MULTI_ATTRS` | `(DOM, sel, attrs)` | `List` | CSS select |
| `DOM_NTH_MULTI_ATTRS` | `(DOM, sel, attrs, n)` | `List` | CSS select |
| `DOM_ALL_IMGS` | `(DOM, sel)` | `ValueArray` | CSS select |
| `DOM_FIRST_IMG` | `(DOM, sel)` ⚠ | `String` | CSS select |
| `DOM_NTH_IMG` | `(DOM, sel, n)` ⚠ | `String` | CSS select |
| `DOM_ALL_HREFS` | `(DOM, sel)` | `ValueArray` | CSS select |
| `DOM_FIRST_HREF` | `(DOM, sel)` ⚠ | `String` | CSS select |
| `DOM_NTH_HREF` | `(DOM, sel, n)` ⚠ | `String` | CSS select |
| `DOM_ALL_NODES_LABELS` | `ValueArray` | CSS select |
| `DOM_FIRST_NODE_LABELS` | `String` | CSS select |
| `DOM_NTH_NODE_LABELS` | `String` | CSS select |
| `DOM_ALL_RE1` | `ValueArray` | CSS select + regex |
| `DOM_FIRST_RE1` | `String` | CSS select + regex |
| `DOM_ALL_RE2` | `ValueArray` | CSS select + regex |
| `DOM_FIRST_RE2` | `ValueArray` | CSS select + regex |

> ⚠ **Warning:** Functions marked with ⚠ return a **scalar** (`String`), not a `ValueDom`. They select an element AND extract a property in one step. Their results **cannot** be passed to `ValueDom` functions like `DOM_ABS_SRC`, `DOM_ABS_HREF`, `DOM_TEXT`, etc. Use the `DOM_SELECT_*` + property-function pattern instead for composable DOM access (see [§Composability](#function-inputoutput-types--composability) above).

> **Note on `DOM_FIRST_HREF`:** For href extraction, `DOM_FIRST_HREF(DOM, sel)` can return an empty string for a class-only selector (e.g. `.product-link`) while the tag-qualified form (`a.product-link`) works. Prefer `DOM_FIRST_ATTR(DOM, sel, 'href')` — it accepts any selector and returns the href consistently (relative; use `DOM_ABS_HREF` or `abs:href` for the absolute URL).

### STR Namespace

| SQL Alias | Returns | Category |
|-----------|---------|----------|
| `STR_CAPITALIZE` | `String?` | Case |
| `STR_UNCAPITALIZE` | `String?` | Case |
| `STR_SWAP_CASE` | `String?` | Case |
| `STR_UPPER_CASE` | `String?` | Case |
| `STR_LOWER_CASE` | `String?` | Case |
| `STR_IS_EMPTY` | `Boolean` | Check |
| `STR_IS_NOT_EMPTY` | `Boolean` | Check |
| `STR_IS_BLANK` | `Boolean` | Check |
| `STR_IS_NOT_BLANK` | `Boolean` | Check |
| `STR_IS_ANY_EMPTY` | `Boolean` | Check |
| `STR_IS_NONE_EMPTY` | `Boolean` | Check |
| `STR_IS_ANY_BLANK` | `Boolean` | Check |
| `STR_IS_NONE_BLANK` | `Boolean` | Check |
| `STR_TRIM` | `String?` | Trim/Strip |
| `STR_TRIM_TO_NULL` | `String?` | Trim/Strip |
| `STR_TRIM_TO_EMPTY` | `String?` | Trim/Strip |
| `STR_STRIP` | `String?` | Trim/Strip |
| `STR_STRIP_TO_NULL` | `String?` | Trim/Strip |
| `STR_STRIP_TO_EMPTY` | `String?` | Trim/Strip |
| `STR_STRIP_START` | `String?` | Trim/Strip |
| `STR_STRIP_END` | `String?` | Trim/Strip |
| `STR_STRIP_ALL` | `Array` | Trim/Strip |
| `STR_STRIP_ACCENTS` | `String?` | Trim/Strip |
| `STR_SUBSTRING` | `String?` | Substring |
| `STR_LEFT` | `String?` | Substring |
| `STR_RIGHT` | `String?` | Substring |
| `STR_MID` | `String?` | Substring |
| `STR_SUBSTRING_BEFORE` | `String?` | Substring |
| `STR_SUBSTRING_AFTER` | `String?` | Substring |
| `STR_SUBSTRING_BEFORE_LAST` | `String?` | Substring |
| `STR_SUBSTRING_AFTER_LAST` | `String?` | Substring |
| `STR_SUBSTRING_BETWEEN` | `String?` | Substring |
| `STR_SUBSTRINGS_BETWEEN` | `Array` | Substring |
| `STR_CONTAINS_WHITESPACE` | `Boolean` | Search |
| `STR_CONTAINS_ANY` | `Boolean` | Search |
| `STR_CONTAINS_ONLY` | `Boolean` | Search |
| `STR_CONTAINS_NONE` | `Boolean` | Search |
| `STR_INDEX_OF_ANY` | `Int` | Search |
| `STR_INDEX_OF_ANY_BUT` | `Int` | Search |
| `STR_ORDINAL_INDEX_OF` | `Int` | Search |
| `STR_LAST_ORDINAL_INDEX_OF` | `Int` | Search |
| `STR_INDEX_OF_DIFFERENCE` | `Int` | Search |
| `STR_COUNT_MATCHES` | `Int` | Search |
| `STR_GET_COMMON_PREFIX` | `String?` | Search |
| `STR_SPLIT` | `Array` | Split/Join |
| `STR_SPLIT_BY_WHOLE_SEPARATOR` | `Array` | Split/Join |
| `STR_SPLIT_PRESERVE_ALL_TOKENS` | `Array` | Split/Join |
| `STR_SPLIT_BY_CHARACTER_TYPE` | `Array` | Split/Join |
| `STR_SPLIT_BY_CHARACTER_TYPE_CAMEL_CASE` | `Array` | Split/Join |
| `STR_JOIN` | `String?` | Split/Join |
| `STR_REPLACE_EACH` | `String?` | Replace |
| `STR_REPLACE_EACH_REPEATEDLY` | `String?` | Replace |
| `STR_REPLACE_CHARS` | `String?` | Replace |
| `STR_OVERLAY` | `String?` | Replace |
| `STR_DELETE_WHITESPACE` | `String?` | Replace |
| `STR_CHOMP` | `String?` | Replace |
| `STR_CHOP` | `String?` | Replace |
| `STR_NORMALIZE_SPACE` | `String?` | Replace |
| `STR_LEFT_PAD` | `String?` | Padding |
| `STR_RIGHT_PAD` | `String?` | Padding |
| `STR_CENTER` | `String?` | Padding |
| `STR_REPEAT` | `String?` | Utility |
| `STR_REVERSE` | `String?` | Utility |
| `STR_REVERSE_DELIMITED` | `String?` | Utility |
| `STR_DIFFERENCE` | `String?` | Utility |
| `STR_LENGTH` | `Int` | Utility |
| `STR_ABBREVIATE` | `String?` | Utility |
| `STR_ABBREVIATE_MIDDLE` | `String?` | Utility |
| `STR_DEFAULT_STRING` | `String?` | Utility |
| `STR_DEFAULT_IF_BLANK` | `String?` | Utility |
| `STR_DEFAULT_IF_EMPTY` | `String?` | Utility |
| `STR_TO_ENCODED_STRING` | `String?` | Utility |
| `STR_IS_ALPHA` | `Boolean` | Classification |
| `STR_IS_NUMERIC` | `Boolean` | Classification |
| `STR_IS_WHITESPACE` | `Boolean` | Classification |
| `STR_IS_ALPHA_SPACE` | `Boolean` | Classification |
| `STR_IS_ALPHANUMERIC` | `Boolean` | Classification |
| `STR_IS_ALPHANUMERIC_SPACE` | `Boolean` | Classification |
| `STR_IS_ASCII_PRINTABLE` | `Boolean` | Classification |
| `STR_IS_NUMERIC_SPACE` | `Boolean` | Classification |
| `STR_IS_ALL_LOWER_CASE` | `Boolean` | Classification |
| `STR_IS_ALL_UPPER_CASE` | `Boolean` | Classification |
| `STR_FIRST_INTEGER` | `Int` | Number extraction |
| `STR_FIRST_FLOAT` | `Float` | Number extraction |
| `STR_GET_FIRST_FLOAT_NUMBER` | `Float` | Number extraction |

### ARRAY Namespace

| SQL Alias | Returns | Description |
|-----------|---------|-------------|
| `ARRAY_JOIN_TO_STRING` | `String` | Join array elements with separator |
| `ARRAY_FIRST_NOT_BLANK` | `Value?` | First non-blank value |
| `ARRAY_FIRST_NOT_EMPTY` | `Value?` | First non-empty value |


===== DOM FUNCTIONS =====
---
title: "X-SQL: DomFunctions — Core DOM Operations"
description: "Reference for ~65 DOM functions: page loading, state checks, element properties, URL/location, tree navigation, element identity, link/image props, title, text, HTML serialization, regex, and computed features."
tier: catalog
---

# X-SQL: DomFunctions — Core DOM Operations

> **Parent:** [x-sql.md](x-sql.md) — full function index and quick-reference patterns
>
> **Related:** [DOM_LOAD_AND_SELECT](x-sql-dom-load-select.md) | [DomSelectFunctions](x-sql-dom-select-functions.md) | [StringFunctions](x-sql-string-functions.md)

**Source:** `DomFunctions.kt` | **Namespace:** `DOM` | **~65 functions**

> **SQL constraint:** All page-data queries must use `SELECT ... FROM DOM_LOAD_AND_SELECT(url, cssQuery)`. No CTEs, subqueries, `EXPLODE`, or `FROM DOM_LOAD(...)` are supported. `DOM_LOAD` and `DOM_FETCH` are expression functions (usable in SELECT, not FROM).

---

## Table of Contents

- [2.1 Page Loading](#21-page-loading)
- [2.2 DOM State Checks](#22-dom-state-checks)
- [2.3 Element Properties](#23-element-properties)
- [2.4 URL & Location](#24-url--location)
- [2.5 DOM Tree Navigation](#25-dom-tree-navigation)
- [2.6 Element Identity](#26-element-identity)
- [2.7 Link & Image Properties](#27-link--image-properties)
- [2.8 Title](#28-title)
- [2.9 Text Extraction](#29-text-extraction)
- [2.10 HTML Serialization](#210-html-serialization)
- [2.11 Regex Extraction on DOM Text](#211-regex-extraction-on-dom-text)
- [2.12 Computed Features](#212-computed-features)

---

## 2.1 Page Loading

### DOM_LOAD

```
DOM_LOAD(configuredUrl)
```

Loads a page from the database cache, or fetches it from the web if absent or expired. Returns a single `ValueDom` — usable as an expression, not a table source.

```sql
-- Expression usage: get page title
SELECT DOM_DOC_TITLE(DOM_LOAD('https://example.com'));

-- Equivalent via DOM_LOAD_AND_SELECT (preferred table-source pattern)
SELECT DOM_DOC_TITLE(DOM)
FROM DOM_LOAD_AND_SELECT('https://example.com', ':root');
```

### DOM_FETCH

```
DOM_FETCH(configuredUrl)
```

Forces an immediate web fetch, bypassing the cache entirely (sets expiry to zero). Returns a single `ValueDom` — usable as an expression, not a table source.

```sql
-- Expression usage: fetch latest prices
SELECT DOM_TEXT(DOM_FETCH('https://example.com/live-prices'));

-- Equivalent via DOM_LOAD_AND_SELECT with refresh option
SELECT DOM_TEXT(DOM)
FROM DOM_LOAD_AND_SELECT('https://example.com/live-prices -expires 0', ':root');
```

---

## 2.2 DOM State Checks

### DOM_IS_NIL

```
DOM_IS_NIL(dom)
```

Returns `true` if the DOM is nil (empty, invalid, or failed to load).

```sql
-- Filter out failed page loads
SELECT url, DOM_IS_NIL(DOM) AS failed
FROM DOM_LOAD_AND_SELECT('https://example.com', ':root');
```

### DOM_IS_NOT_NIL

```
DOM_IS_NOT_NIL(dom)
```

Returns `true` if the DOM is valid and contains content.

```sql
-- Only process successfully loaded pages
SELECT DOM_TEXT(DOM) AS text
FROM DOM_LOAD_AND_SELECT('https://example.com', 'p')
WHERE DOM_IS_NOT_NIL(DOM);
```

---

## 2.3 Element Properties

### DOM_ATTR

```
DOM_ATTR(dom, attrName)
```

Gets the value of any HTML attribute on the element.

```sql
-- Get the 'data-id' attribute from each product card
SELECT DOM_ATTR(DOM, 'data-id') AS product_id
FROM DOM_LOAD_AND_SELECT('https://shop.example.com', '.product');

-- Get href from all links
SELECT DOM_ATTR(DOM, 'href') AS link_url
FROM DOM_LOAD_AND_SELECT('https://example.com', 'a');
```

### DOM_LABELS

```
DOM_LABELS(dom)
```

Gets the Pulsar `A_LABELS` attribute — machine-learned node classification labels.

```sql
-- See what Pulsar thinks each element is
SELECT DOM_TAG_NAME(DOM) AS tag, DOM_LABELS(DOM) AS labels
FROM DOM_LOAD_AND_SELECT('https://example.com', 'div,p,ul,li');
```

### DOM_FEATURE

```
DOM_FEATURE(dom, featureName)
```

Gets any computed feature value by name. Returns `Double`.

```sql
-- Get a specific feature by name
SELECT DOM_FEATURE(DOM, 'CH') AS char_count
FROM DOM_LOAD_AND_SELECT('https://example.com', 'p');

-- Get the sibling count feature
SELECT DOM_FEATURE(DOM, 'SIB') AS siblings
FROM DOM_LOAD_AND_SELECT('https://example.com', 'div');
```

### DOM_HAS_ATTR

```
DOM_HAS_ATTR(dom, attrName)
```

Checks whether the element has a specific HTML attribute.

```sql
-- Find all elements that have a 'data-price' attribute
SELECT DOM_TAG_NAME(DOM) AS tag, DOM_ATTR(DOM, 'data-price') AS price
FROM DOM_LOAD_AND_SELECT('https://example.com', '*')
WHERE DOM_HAS_ATTR(DOM, 'data-price');
```

### DOM_STYLE

```
DOM_STYLE(dom, styleName)
```

Gets the computed CSS style value for the element.

```sql
-- Get the display and color styles
SELECT
    DOM_STYLE(DOM, 'display') AS display,
    DOM_STYLE(DOM, 'color') AS color
FROM DOM_LOAD_AND_SELECT('https://example.com', 'h1');
```

### DOM_SEQUENCE & DOM_DEPTH

```
DOM_SEQUENCE(dom)  -- sequence number in document order
DOM_DEPTH(dom)     -- depth in the DOM tree
```

```sql
-- Find deeply nested elements
SELECT DOM_CSS_SELECTOR(DOM) AS path, DOM_DEPTH(DOM) AS depth
FROM DOM_LOAD_AND_SELECT('https://example.com', '*')
WHERE DOM_DEPTH(DOM) > 10
ORDER BY DOM_DEPTH(DOM) DESC
LIMIT 10;
```

### DOM_CSS_SELECTOR & DOM_CSS_PATH

```
DOM_CSS_SELECTOR(dom)  -- unique CSS selector for this element
DOM_CSS_PATH(dom)      -- alias for cssSelector
```

```sql
-- Get the unique CSS path for every heading
SELECT DOM_TEXT(DOM) AS heading, DOM_CSS_SELECTOR(DOM) AS css_path
FROM DOM_LOAD_AND_SELECT('https://example.com', 'h1,h2,h3');
```

### DOM_SIBLING_SIZE & DOM_SIBLING_INDEX

```
DOM_SIBLING_SIZE(dom)          -- count of all sibling nodes (including text nodes)
DOM_SIBLING_INDEX(dom)         -- index among all sibling nodes
DOM_ELEMENT_SIBLING_SIZE(dom)  -- count of sibling elements only
DOM_ELEMENT_SIBLING_INDEX(dom) -- index among sibling elements
```

```sql
-- Find the first and last child elements of each container
SELECT
    DOM_TAG_NAME(DOM) AS container,
    DOM_CHILD_ELEMENT_SIZE(DOM) AS children_count
FROM DOM_LOAD_AND_SELECT('https://example.com', 'ul,ol,div.menu')
WHERE DOM_CHILD_ELEMENT_SIZE(DOM) > 0;
```

---

## 2.4 URL & Location

### DOM_URI

```
DOM_URI(dom)
```

Returns the page's normalized URI — the permanent internal address used as the database key.

```sql
-- See which actual URL was loaded (after normalization)
SELECT DOM_URI(DOM) AS normalized_url
FROM DOM_LOAD_AND_SELECT('https://example.com', ':root');
```

### DOM_BASE_URI

```
DOM_BASE_URI(dom)
```

Returns the element's base URI (the last working address of the page).

```sql
SELECT DOM_BASE_URI(DOM) AS base_url
FROM DOM_LOAD_AND_SELECT('https://example.com', ':root');
```

### DOM_ABS_URL

```
DOM_ABS_URL(dom, attributeKey)
```

Resolves a relative URL attribute to an absolute URL.

```sql
-- Resolve relative image paths to absolute URLs
SELECT DOM_ABS_URL(DOM, 'src') AS absolute_image_url
FROM DOM_LOAD_AND_SELECT('https://example.com', 'img');
```

### DOM_LOCATION

```
DOM_LOCATION(dom)
```

Returns the page's location — the last working address. May differ from `uri` if redirects occurred.

```sql
-- Detect if a redirect happened
SELECT
    DOM_URI(DOM) AS original,
    DOM_LOCATION(DOM) AS final_location
FROM DOM_LOAD_AND_SELECT('https://example.com', ':root')
WHERE DOM_URI(DOM) != DOM_LOCATION(DOM);
```

---

## 2.5 DOM Tree Navigation

### DOM_CHILD_NODE_SIZE & DOM_CHILD_ELEMENT_SIZE

```
DOM_CHILD_NODE_SIZE(dom)     -- includes text nodes
DOM_CHILD_ELEMENT_SIZE(dom)  -- element nodes only
```

```sql
-- Find containers with many direct child elements
SELECT
    DOM_TAG_NAME(DOM) AS tag,
    DOM_CHILD_ELEMENT_SIZE(DOM) AS child_count
FROM DOM_LOAD_AND_SELECT('https://example.com', '*')
WHERE DOM_CHILD_ELEMENT_SIZE(DOM) > 20
ORDER BY DOM_CHILD_ELEMENT_SIZE(DOM) DESC;
```

### DOM_PARENT

```
DOM_PARENT(dom)
```

Returns the parent element as a new DOM.

```sql
-- Get the parent of each <a> tag
SELECT
    DOM_TEXT(DOM) AS link_text,
    DOM_TAG_NAME(DOM_PARENT(DOM)) AS parent_tag,
    DOM_CLASS_NAME(DOM_PARENT(DOM)) AS parent_class
FROM DOM_LOAD_AND_SELECT('https://example.com', 'a');
```

### DOM_ANCESTOR

```
DOM_ANCESTOR(dom, n)
```

Returns the nth ancestor. `n=1` = parent, `n=2` = grandparent, etc.

```sql
-- Walk up to the 3rd ancestor
SELECT
    DOM_TAG_NAME(DOM) AS self,
    DOM_TAG_NAME(DOM_ANCESTOR(DOM, 3)) AS great_grandparent
FROM DOM_LOAD_AND_SELECT('https://example.com', 'a.nav-link');
```

### DOM_PARENT_NAME

```
DOM_PARENT_NAME(dom)
```

Returns the unique name of the parent element. Returns `"nil"` if the DOM is nil.

```sql
SELECT DOM_TEXT(DOM) AS text, DOM_PARENT_NAME(DOM) AS container
FROM DOM_LOAD_AND_SELECT('https://example.com', 'span');
```

### DOM_OWNER_DOCUMENT, DOM_OWNER_BODY, DOM_DOCUMENT_VARIABLES

```
DOM_OWNER_DOCUMENT(dom)     -- the full document containing this element
DOM_OWNER_BODY(dom)         -- the <body> containing this element
DOM_DOCUMENT_VARIABLES(dom) -- the Pulsar meta-information element from <head>
```

```sql
-- Get document metadata from any element
SELECT DOM_DOC_TITLE(DOM_OWNER_DOCUMENT(DOM)) AS page_title
FROM DOM_LOAD_AND_SELECT('https://example.com', 'p');

-- Access Pulsar meta information
SELECT DOM_TEXT(DOM_DOCUMENT_VARIABLES(DOM)) AS pulsar_meta
FROM DOM_LOAD_AND_SELECT('https://example.com', ':root');
```

---

## 2.6 Element Identity

```sql
-- DOM_TAG_NAME: Get the HTML tag name
SELECT DOM_TAG_NAME(DOM) AS tag FROM DOM_LOAD_AND_SELECT('...', '*') LIMIT 10;

-- DOM_ID: Get the element's id attribute
SELECT DOM_TEXT(DOM) AS text FROM DOM_LOAD_AND_SELECT('...', '*')
WHERE DOM_ID(DOM) IS NOT NULL;

-- DOM_CLASS_NAME: Get the element's class attribute (full string)
SELECT DOM_CLASS_NAME(DOM) AS classes FROM DOM_LOAD_AND_SELECT('...', 'div');

-- DOM_CLASS_NAMES: Get class names as a set
SELECT DOM_CLASS_NAMES(DOM) AS class_set FROM DOM_LOAD_AND_SELECT('...', 'div.active');

-- DOM_HAS_CLASS: Check for a specific class
SELECT DOM_TEXT(DOM) AS text FROM DOM_LOAD_AND_SELECT('...', 'div')
WHERE DOM_HAS_CLASS(DOM, 'featured');

-- DOM_UNIQUE_NAME: Get the element's unique name identifier
SELECT DOM_UNIQUE_NAME(DOM) AS name FROM DOM_LOAD_AND_SELECT('...', '*') LIMIT 10;

-- DOM_VALUE: Get form field value
SELECT DOM_VALUE(DOM) AS input_value FROM DOM_LOAD_AND_SELECT('...', 'input,select,textarea');

-- DOM_DATA: Get combined data-* attributes
SELECT DOM_DATA(DOM) AS dataset FROM DOM_LOAD_AND_SELECT('...', '[data-price]');
```

---

## 2.7 Link & Image Properties

```sql
-- DOM_HREF: Get raw href attribute
SELECT DOM_HREF(DOM) AS raw_link FROM DOM_LOAD_AND_SELECT('...', 'a');

-- DOM_ABS_HREF: Get resolved absolute href URL
SELECT DOM_ABS_HREF(DOM) AS absolute_link FROM DOM_LOAD_AND_SELECT('...', 'a');

-- DOM_SRC: Get raw src attribute
SELECT DOM_SRC(DOM) AS raw_src FROM DOM_LOAD_AND_SELECT('...', 'img');

-- DOM_ABS_SRC: Get resolved absolute src URL
SELECT DOM_ABS_SRC(DOM) AS absolute_src FROM DOM_LOAD_AND_SELECT('...', 'img');
```

**Practical pattern — extract all links with text:**

```sql
SELECT
    DOM_TEXT(DOM) AS link_text,
    DOM_ABS_HREF(DOM) AS url
FROM DOM_LOAD_AND_SELECT('https://example.com', 'a')
WHERE DOM_HAS_TEXT(DOM);
```

---

## 2.8 Title

```sql
-- DOM_TITLE: Get the element's title attribute (tooltip)
SELECT DOM_TITLE(DOM) AS tooltip FROM DOM_LOAD_AND_SELECT('...', 'abbr,img[title]');

-- DOM_DOC_TITLE: Get the document's <title> text
SELECT DOM_DOC_TITLE(DOM) AS page_title FROM DOM_LOAD_AND_SELECT('...', ':root');
```

---

## 2.9 Text Extraction

### DOM_HAS_TEXT

```
DOM_HAS_TEXT(dom)
```

```sql
-- Skip empty elements
SELECT DOM_TEXT(DOM) AS text
FROM DOM_LOAD_AND_SELECT('https://example.com', 'p')
WHERE DOM_HAS_TEXT(DOM);
```

### DOM_TEXT

```
DOM_TEXT(dom [, truncate])
```

Returns the element's full inner text. Optionally truncate to N characters.

```sql
-- Full text
SELECT DOM_TEXT(DOM) AS full_text FROM DOM_LOAD_AND_SELECT('...', 'article');

-- Truncated to 200 chars (for previews)
SELECT DOM_TEXT(DOM, 200) AS preview FROM DOM_LOAD_AND_SELECT('...', 'p');
```

### DOM_TEXT_LEN & DOM_TEXT_LENGTH

```
DOM_TEXT_LEN(dom)     -- text character count
DOM_TEXT_LENGTH(dom)  -- alias
```

```sql
-- Find the longest paragraphs
SELECT DOM_TEXT(DOM) AS text, DOM_TEXT_LEN(DOM) AS length
FROM DOM_LOAD_AND_SELECT('https://example.com', 'p')
ORDER BY DOM_TEXT_LEN(DOM) DESC
LIMIT 5;
```

### DOM_OWN_TEXT

```
DOM_OWN_TEXT(dom)
```

Returns only the element's direct text, excluding text from child elements.

```sql
-- Get the heading text without nested <span> content
SELECT DOM_OWN_TEXT(DOM) AS heading_text
FROM DOM_LOAD_AND_SELECT('https://example.com', 'h1,h2');
```

### DOM_OWN_TEXTS

```
DOM_OWN_TEXTS(dom)
```

Returns the own texts of the element and all its descendants as a `ValueArray`.

```sql
-- Get all text fragments from an article as an array
SELECT DOM_OWN_TEXTS(DOM) AS text_fragments
FROM DOM_LOAD_AND_SELECT('https://example.com', 'article');
```

### DOM_OWN_TEXT_LEN

```
DOM_OWN_TEXT_LEN(dom)
```

```sql
SELECT DOM_OWN_TEXT_LEN(DOM) AS own_text_length
FROM DOM_LOAD_AND_SELECT('...', 'p');
```

### DOM_WHOLE_TEXT & DOM_WHOLE_TEXT_LEN

```
DOM_WHOLE_TEXT(dom)     -- text including child text nodes
DOM_WHOLE_TEXT_LEN(dom)
```

```sql
-- Whole text is useful when you want text node content preserved
SELECT DOM_WHOLE_TEXT(DOM) AS whole_text
FROM DOM_LOAD_AND_SELECT('https://example.com', 'pre,code');
```

---

## 2.10 HTML Serialization

```sql
-- DOM_HTML: Inner HTML (slim copy — whitespace normalized)
SELECT DOM_HTML(DOM) AS inner_html FROM DOM_LOAD_AND_SELECT('...', 'div.content');

-- DOM_OUTER_HTML: Outer HTML including the element itself
SELECT DOM_OUTER_HTML(DOM) AS full_html FROM DOM_LOAD_AND_SELECT('...', 'div.card');

-- DOM_SLIM_HTML: Slimmed-down HTML (formatting removed)
SELECT DOM_SLIM_HTML(DOM) AS clean_html FROM DOM_LOAD_AND_SELECT('...', 'article');

-- DOM_MINIMAL_HTML: Most compact HTML representation
SELECT DOM_MINIMAL_HTML(DOM) AS compact_html FROM DOM_LOAD_AND_SELECT('...', 'section');

-- DOM_DOM: Identity — returns the DOM unchanged
SELECT DOM_DOC_TITLE(DOM_DOM(DOM)) AS title
FROM DOM_LOAD_AND_SELECT('https://example.com', ':root');
```

---

## 2.11 Regex Extraction on DOM Text

### DOM_RE1

```
DOM_RE1(dom, regex [, group])
```

Extracts a regex group from the element's text. Default is group 1.

```sql
-- Extract price numbers from text like "Price: $29.99"
SELECT DOM_RE1(DOM, '\$([\d.]+)') AS price
FROM DOM_LOAD_AND_SELECT('https://shop.example.com', '.price');

-- Extract the 2nd regex group
SELECT DOM_RE1(DOM, '(\d+) reviews.*(\d+) stars', 2) AS star_count
FROM DOM_LOAD_AND_SELECT('...', '.rating');
```

### DOM_RE2

```
DOM_RE2(dom, regex [, keyGroup, valueGroup])
```

Extracts key-value pairs from text. Returns `ValueArray` with `[key, value]`.

```sql
-- Extract "Color: Red" style text as key-value pairs
SELECT DOM_RE2(DOM, '(\w+):\s*(.+)') AS kv_pair
FROM DOM_LOAD_AND_SELECT('https://example.com', '.specs li');

-- Use custom group indices (group 2 as key, group 3 as value)
SELECT DOM_RE2(DOM, '(SKU:)\s*([A-Z0-9]+)', 2, 2) AS sku
FROM DOM_LOAD_AND_SELECT('...', '.product-code');
```

---

## 2.12 Computed Features

These are shorthand abbreviations for common DOM features. All return `Double`.

```sql
-- DOM_CH: Character count (text length)
SELECT DOM_TEXT(DOM) AS text, DOM_CH(DOM) AS chars
FROM DOM_LOAD_AND_SELECT('...', 'p')
ORDER BY DOM_CH(DOM) DESC LIMIT 5;

-- DOM_TN: Text node count
-- DOM_IMG: Image count
-- DOM_A: Anchor (link) count
SELECT DOM_TAG_NAME(DOM) AS tag, DOM_IMG(DOM) AS images, DOM_A(DOM) AS links
FROM DOM_LOAD_AND_SELECT('...', 'div');

-- DOM_SIB: Sibling count
-- DOM_C: Child count
-- DOM_DEP: Depth in tree
-- DOM_SEQ: Sequence number
SELECT
    DOM_DEP(DOM) AS tree_depth,
    DOM_SIB(DOM) AS siblings,
    DOM_C(DOM) AS children
FROM DOM_LOAD_AND_SELECT('...', 'div');

-- DOM_TOP, DOM_LEFT: Bounding box position
-- DOM_WIDTH, DOM_HEIGHT: Bounding box dimensions (minimum 1.0)
SELECT
    DOM_TOP(DOM) AS y,
    DOM_LEFT(DOM) AS x,
    DOM_WIDTH(DOM) AS w,
    DOM_HEIGHT(DOM) AS h
FROM DOM_LOAD_AND_SELECT('...', 'img');

-- DOM_AREA: width × height
SELECT DOM_AREA(DOM) AS pixel_area
FROM DOM_LOAD_AND_SELECT('...', 'img')
ORDER BY DOM_AREA(DOM) DESC;

-- DOM_ASPECT_RATIO: width / height
SELECT DOM_ASPECT_RATIO(DOM) AS ratio
FROM DOM_LOAD_AND_SELECT('...', 'img')
WHERE DOM_ASPECT_RATIO(DOM) > 1.5;  -- landscape images
```

**Practical pattern — find the largest visible images:**

```sql
SELECT
    DOM_ABS_SRC(DOM) AS image_url,
    DOM_WIDTH(DOM) AS width,
    DOM_HEIGHT(DOM) AS height,
    DOM_AREA(DOM) AS area
FROM DOM_LOAD_AND_SELECT('https://example.com', 'img')
WHERE DOM_WIDTH(DOM) > 100 AND DOM_HEIGHT(DOM) > 100
ORDER BY DOM_AREA(DOM) DESC
LIMIT 10;
```


codex
Now let me read the crawl and agent reference docs.
exec
"C:\\Program Files\\PowerShell\\7\\pwsh.exe" -Command 'Get-Content skills/browser4-cli/references/crawl.md -Raw' in D:\workspace\Browser4\Browser4-4.13
exec
"C:\\Program Files\\PowerShell\\7\\pwsh.exe" -Command 'Get-Content skills/browser4-cli/references/agent.md -Raw' in D:\workspace\Browser4\Browser4-4.13
 succeeded in 302ms:
---
title: "Crawl Command Reference"
description: "Reference for the crawl command. Recursive website crawling from a URL or seed file, with optional X-SQL data extraction and multi-format output."
tier: procedure
---

# Crawl Command Reference

Recursive website crawling — start from a URL or seed file, follow links up to
a configurable depth, and optionally extract structured data from each page
with X-SQL.

## Quick start

```bash
# Link discovery from a single URL
browser4-cli crawl "https://example.com" --out-link-selector "a[href]"

# Bulk fetch from a seed file (no link discovery)
browser4-cli crawl --seed-file urls.txt --depth 0

# Bulk fetch + X-SQL extraction to CSV
browser4-cli crawl --seed-file urls.txt --sql @extract.sql --format csv -o results.csv
```

> **Note:** `--out-link-selector` is required for link discovery.
> Without it, only seed URLs are processed regardless of depth.
> For depth 0 (bulk fetch), no selector is needed.

## When to Use

Use **crawl** for sequential multi-page workflows with built-in link discovery, seed-file bulk processing, and X-SQL extraction to structured output (CSV/JSON/table). Prefer **swarm** for parallel high-throughput extraction. Prefer **loop** for repeated monitoring at intervals. Prefer **htmlsnapshot query** for extracting from a single page.

## How It Works

Crawl loads seed URLs, optionally follows links up to a configurable depth, deduplicates visited pages, and optionally runs an X-SQL query against each page. Results are aggregated and formatted as table, CSV, or JSON. Use `--background` for async execution.

## Modes

### Link discovery mode (depth >= 1)

The classic crawl: start from a seed URL, extract links, load linked pages, and
optionally recurse deeper.

1. Loads each seed URL.
2. Extracts links matching `--out-link-selector` (a CSS selector).
3. Optionally filters links by `--out-link-pattern` (regex).
4. Deduplicates and limits to `--top-links` links.
5. Loads each linked page.
6. If `--depth` > 1, repeats steps 2–5 for loaded pages (skipping visited URLs).
7. Returns results as human-readable text (or JSON with `--json`).

### Bulk fetch mode (depth 0)

Load each URL directly without link discovery.  Ideal for:
- Processing a list of known detail pages (product pages, articles)
- Combining with `--sql` for structured data extraction
- When you have the URLs and just need the page content + extraction

```bash
browser4-cli crawl --seed-file product-urls.txt --depth 0 --refresh
```

### X-SQL extraction mode (with --sql)

When `--sql` is provided, the query is executed against each crawled page.
The `@url` placeholder is replaced with the page URL server-side.  Results are
aggregated across all pages and formatted according to `--format`.

```bash
browser4-cli crawl --seed-file urls.txt --depth 0 --sql "
  SELECT
    DOM_BASE_URI(dom) AS url,
    DOM_FIRST_TEXT(dom, 'h1') AS title,
    DOM_FIRST_TEXT(dom, '.price') AS price
  FROM DOM_LOAD_AND_SELECT(@url, 'body')
" --format table
```

> **Note:** X-SQL function names are case-insensitive.
> `DOM_FIRST_TEXT` and `dom_first_text` are equivalent.
> This reference uses UPPERCASE for clarity.

## Flags

### Core flags

| Flag | Short | Type | Default | Description |
|---|---|---|---|---|
| `url` (positional) | | string | — | Starting URL. Omit when using `--seed-file` |
| `--seed-file` | | string | — | File with URLs to crawl, one per line. Lines starting with `#` are comments |
| `--depth` | `-d` | int | `1` | 0 = fetch only (no links); 1+ = follow links to that depth |

### X-SQL extraction flags

| Flag | Type | Default | Description |
|---|---|---|---|
| `--sql` | string | — | X-SQL query. Use `@url` as page URL placeholder. Prefix with `@` to read from file |
| `--sql-stdin` | bool | — | Read query from stdin (avoids shell quoting issues) |
| `--sql-base64` | bool | — | Base64-decode the query value before execution |

### Output flags

| Flag | Short | Type | Default | Description |
|---|---|---|---|---|
| `--format` | | string | `table` | Output format: `json`, `csv`, or `table` |
| `--output` | `-o` | string | — | Write results to file instead of stdout |

### Link discovery flags

| Flag | Short | Type | Default | Description |
|---|---|---|---|---|
| `--out-link-selector` | `-ol` | string | — | CSS selector to extract links from each page |
| `--out-link-pattern` | `-olp` | regex | `.+` | Regex to filter extracted links |
| `--top-links` | `-tl` | int | `20` | Max links extracted per page |

### LoadOptions flags

| Flag | Short | Type | Description |
|---|---|---|---|
| `--args` | `-a` | string | Raw LoadOptions passthrough (see [LoadOptions Guide](load-options-guide.md)) |
| `--refresh` | | bool | Force fresh fetch (ignore cache) |
| `--parse` | | bool | Parse pages after fetch |
| `--expires` | | string | Cache TTL: `1d`, `1h`, `30m`, etc. |
| `--priority` | `-p` | int | Queue priority (lower = higher priority) |
| `--page-load-timeout` | | string | Max wait for each page load |
| `--ignore-url-query` | | bool | Strip query params from URLs |
| `--no-norm` | | bool | Disable URL normalization |
| `--readonly` | | bool | Non-destructive mode |

### Async flag

| Flag | Short | Type | Description |
|---|---|---|---|
| `--background` | `-bg` | bool | Submit crawl and return immediately; use `crawl list` to track |

## Output formats

### Table (default)

Aligned columns with header and separator:

```
  url                                    | title         | price
  ---------------------------------------+---------------+-------
  https://example.com/product/1          | Product One   | $19.99
  https://example.com/product/2          | Product Two   | $29.99
```

### CSV

Standard CSV with header row.  Fields containing commas, quotes, or newlines
are quoted.

```csv
url,title,price
https://example.com/product/1,Product One,$19.99
https://example.com/product/2,"Product Two, Special Edition",$29.99
```

### JSON

Pretty-printed JSON array of row objects:

```json
[
  {
    "url": "https://example.com/product/1",
    "title": "Product One",
    "price": "$19.99"
  }
]
```

### Page listing (no --sql)

When no X-SQL is provided, the default output lists crawled pages:

```
Crawl task submitted: 550e8400-e29b-41d4-a716-446655440000
  URLs: 3
Crawling... 1 pages found so far

Crawl completed. 3 pages found.
  depth=0 | https://example.com/page1 | Page 1 Title
  depth=0 | https://example.com/page2 | Page 2 Title
  depth=0 | https://example.com/page3 | Page 3 Title
```

## Common patterns

### Bulk product detail extraction

```bash
# Extract product URLs from search results (via eval or X-SQL), write to urls.txt
browser4-cli crawl --seed-file urls.txt --depth 0 --refresh \
  --sql @extract.sql --format csv -o products.csv
```

`extract.sql`:
```sql
SELECT
  DOM_BASE_URI(dom) AS url,
  DOM_FIRST_TEXT(dom, '#productTitle') AS title,
  DOM_FIRST_TEXT(dom, '.a-price .a-offscreen') AS price,
  DOM_FIRST_TEXT(dom, '#acrCustomerReviewText') AS rating,
  DOM_FIRST_TEXT(dom, '#feature-bullets') AS features
FROM DOM_LOAD_AND_SELECT(@url, 'body')
```

### Shallow crawl with extraction (list page + detail pages)

```bash
browser4-cli crawl "https://example.com/products" \
  --out-link-selector "a.product-link" \
  --top-links 50 \
  --depth 1 \
  --sql "SELECT DOM_FIRST_TEXT(dom, 'h1') AS title, DOM_FIRST_TEXT(dom, '.price') AS price FROM DOM_LOAD_AND_SELECT(@url, 'body')" \
  --format json
```

### Deep crawl (depth > 1) — recursive link following

```bash
browser4-cli crawl "https://example.com/docs" \
  --out-link-selector "a[href]" \
  --out-link-pattern ".*/docs/.*" \
  --depth 3 \
  --top-links 30
```

### Fresh crawl with quality requirements

```bash
browser4-cli crawl "https://example.com" \
  --out-link-selector "a[href]" \
  --refresh \
  --args "-requireSize 100000 -scrollCount 5"
```

### X-SQL from stdin (avoids shell quoting)

```bash
browser4-cli crawl --seed-file urls.txt --depth 0 --sql-stdin --format table < query.sql
```

### X-SQL from file (@ prefix)

```bash
browser4-cli crawl --seed-file urls.txt --depth 0 --sql @extract.sql --format csv -o out.csv
```

## Testing locally with MockSite

The mock e-commerce site (`./bin/test.ps1 mock-site`) provides predictable
product pages for testing crawl extraction without hitting live websites.

### MockSite selectors

MockSite's product pages use ID selectors (unlike the class selectors common on
Amazon).  Always inspect the actual page before writing queries:

```bash
# Discover selectors for a page
browser4-cli goto "http://localhost:18080/ec/dp/B0E000001"
browser4-cli htmlsnapshot
browser4-cli htmlsnapshot inspect

# The inspect output shows element patterns including singleton IDs
```

Typical MockSite selectors:

| Field | Selector |
|---|---|
| Product title | `#productTitle` |
| Price | `#product-price` |
| Description (feature list) | `#product-features` |
| Category (breadcrumb) | `.breadcrumbs` |

> **Note:** Detail pages (`/ec/dp/…`) use ID selectors (`#productTitle`, `#product-price`), while listing pages (`/ec/b?node=…`) use class selectors (`.product-card`, `.product-title`, `.product-price`).

### MockSite crawl example

```bash
# 1. Start MockSite
./bin/test.ps1 mock-site

# 2. Create a seed file
echo "http://localhost:18080/ec/dp/B0E000001" > seed-urls.txt
echo "http://localhost:18080/ec/dp/B0E000002" >> seed-urls.txt
echo "http://localhost:18080/ec/dp/B0E000003" >> seed-urls.txt

# 3. Create an X-SQL extract file
cat > extract.sql << 'SQLEOF'
SELECT
  DOM_BASE_URI(dom) AS url,
  DOM_FIRST_TEXT(dom, '#productTitle') AS title,
  DOM_FIRST_TEXT(dom, '#product-price') AS price
FROM DOM_LOAD_AND_SELECT(@url, 'body')
SQLEOF

# 4. Run the crawl
browser4-cli crawl --seed-file seed-urls.txt --depth 0 --refresh \
  --sql @extract.sql --format table
```

> **Tip:** When selectors don't match, use `htmlsnapshot grep` with `--selector`
> to verify elements exist, or `htmlsnapshot inspect` to discover available
> selectors.  MockSite uses IDs (`#productTitle`), not classes (`.title`).

## LoadOptions passthrough (`--args` / `-a`)

Any [LoadOptions](load-options-guide.md) field can be passed through `-a`:

```bash
browser4-cli crawl "https://example.com" -ol "a[href]" -a "-nMaxRetry 5 -lazyFlush -interactLevel FAST"
```

## URL deduplication

- Visited URLs are normalized: lowercase, trailing slash removed, query string
  always stripped for dedup purposes.
- The same URL is never visited twice within a crawl session.
- Use `--ignore-url-query` to additionally strip query parameters from extracted
  link hrefs before resolution.
- Use `--no-norm` to disable LoadOptions-level normalization (does not affect
  internal dedup normalization).

## Seed files

Plain text, one URL per line.  Blank lines and lines starting with `#` are
ignored.

```text
# Laser-Engraved Crystal products
https://www.amazon.com/dp/B0C17W3Q9B
https://www.amazon.com/dp/B0CXYZ1234
https://www.amazon.com/dp/B0DEXAMPLE
```

When both a positional `url` and `--seed-file` are provided, the URL is
prepended to the seed file list.

## Timeout

- CLI-side default: 600s. Override with `BROWSER4_CLI_CRAWL_TIMEOUT_SECS` env var.
- Backend timeout scales with depth: roughly 5 min per level, capped at 30 min.

## Error handling

| Situation | Behavior |
|---|---|
| No URLs provided | Exits with "No URLs provided. Specify a URL argument or --seed-file." |
| Empty seed file | Exits with "No URLs provided." after parsing |
| Timeout | Exits with message + task ID; increase `BROWSER4_CLI_CRAWL_TIMEOUT_SECS` |
| Server error | Exits with "Crawl failed: ..." and server error details |
| No links found (depth >= 1) | Completes with 0 pages; verify `--out-link-selector` |
| Invalid --format | Exits with "Invalid --format '...'. Expected: json, csv, or table" |
| X-SQL failure on one page | Page logged with error; other pages continue normally |

## Rate Limiting & Polite Scraping

Crawl includes built-in rate limiting between page loads. For manual batch operations,
follow these guidelines:

- Add `wait 1000-3000` (1-3 seconds) between rapid navigations on the same site
- Amazon and similar sites may show CAPTCHAs under aggressive automated access — longer delays reduce risk
- Use `eval` or `htmlsnapshot get all` to batch-extract from a single page load when possible, rather than navigating to each detail page individually
- Prefer `crawl` with conservative `--depth` and `--page-load-timeout` for automated multi-page traversal
- For `swarm`, control parallelism with `--max-browser-contexts` and `--max-open-tabs`

## Subcommands

When you submit a crawl with `--background`, the CLI returns immediately with a
task ID.  Use these subcommands to manage and monitor background crawl tasks.

### crawl status

Check the current status of a crawl task.

```bash
browser4-cli crawl status <task-id>
```

Shows whether the task is CREATED, PROCESSING, or completed (OK), along with
pages found so far and any error information.

### crawl result

Retrieve the full result of a completed crawl task.  Returns the same output
as a foreground crawl: page listing (without `--sql`) or formatted extraction
data (with `--sql`).

```bash
browser4-cli crawl result <task-id>
```

> **Note:** Only returns results for tasks in terminal state (OK, TIMEOUT,
> ERROR).  Use `crawl status` first to verify completion.

### crawl cancel

Cancel a running or queued crawl task.

```bash
browser4-cli crawl cancel <task-id>
```

The task transitions to TIMEOUT status.  Cancelled tasks remain visible in
`crawl list` until manually cleared or expired by TTL.

### crawl clear

Remove completed, cancelled, or failed crawl tasks from the task store.
Running tasks are not affected.

```bash
browser4-cli crawl clear
```

### crawl list

List all tracked crawl tasks across all sessions.

```bash
browser4-cli crawl list
browser4-cli crawl list --limit 20
browser4-cli crawl list --clear
```

| Flag | Type | Description |
|---|---|---|
| `--limit` | int | Show at most N tasks (latest first) |
| `--offset` | int | Skip the first N tasks |
| `--clear` | bool | Remove all tracked tasks from the list |

## See also

- [X-SQL: DOM_LOAD_AND_SELECT](x-sql-dom-load-select.md) — the table-source
  function for loading pages in X-SQL queries
- [Swarm reference](swarm.md) — parallel scraping and X-SQL extraction across
  multiple browser contexts
- [Multi-product extraction guide](../../docs/multi-product-extraction.md) —
  choosing between crawl, swarm, and other approaches for bulk data extraction
- [LoadOptions Guide](load-options-guide.md) — full LoadOptions reference


 succeeded in 431ms:
---
title: "Agent — Autonomous Browser Task Execution"
description: "Reference for agent commands (run, status, result), extract, and summarize. Submit natural-language tasks for autonomous browser execution driven by an LLM."
tier: procedure
---

# Agent — Autonomous Browser Task Execution

Submit natural-language tasks and let Browser4's AI agent plan and execute browser actions autonomously. Supports both asynchronous multi-step tasks (`agent run`) and synchronous single-page operations (`extract`, `summarize`).

## Prerequisites

Agent commands require an LLM API key, which must be visible to the **Browser4 backend server** process (the CLI only sends HTTP requests to the backend; the backend calls the LLM). When the CLI auto-starts the backend, set the key in the CLI's own environment before the first launch. Configure one provider via environment variables:

| Provider | Required Variables |
|---|---|
| DeepSeek | `DEEPSEEK_API_KEY` |
| OpenRouter | `OPENROUTER_API_KEY`, `OPENROUTER_MODEL_NAME`, `OPENROUTER_BASE_URL` |
| Volcengine (ByteDance) | `VOLCENGINE_API_KEY`, `VOLCENGINE_MODEL_NAME`, `VOLCENGINE_BASE_URL` |
| OpenAI-compatible | `OPENAI_API_KEY`, `OPENAI_MODEL_NAME`, `OPENAI_BASE_URL` |
| Aliyun Qwen (DashScope) | `OPENAI_API_KEY`, `OPENAI_MODEL_NAME`, `OPENAI_BASE_URL` |

```bash
# Set in the environment of the process that runs the backend server.
export DEEPSEEK_API_KEY=sk-xxxxxxxxxxxxxxxxxxxxxxxx
```

> The key must reach the backend server process, not just your CLI shell. Run `browser4-cli doctor` to confirm whether "✓ LLM is configured" on the backend.

If no valid LLM key is configured, `agent run` fails fast with a clear error.

## Quick Start

```bash
browser4-cli agent run "Open browser4.io and summarize the hero section"
# agent run prints a UUID task id (e.g. 3b27930e-48bc-4460-aae7-be521f0a9194)
browser4-cli agent status <task-id>   # poll until COMPLETED
browser4-cli agent result <task-id>
```

## When to Use

Use **agent** for natural-language tasks where you describe what you want without specifying click/fill/snapshot steps. Use **extract**/**summarize** for synchronous single-page LLM-powered extraction. For deterministic extraction with CSS selectors, prefer `htmlsnapshot get` or `htmlsnapshot query` (no LLM key needed).

## How It Works

`agent run` submits a natural-language task to the Browser4 backend, which uses an LLM to plan and execute browser actions autonomously. The agent reasons about the page, discovers elements, and adapts — it does not need refs or step-by-step instructions. Tasks run asynchronously; poll with `agent status` and fetch results with `agent result`. `extract` and `summarize` are synchronous variants that operate on the current page.

## Patterns

### 1. Asynchronous Multi-Step Task

```bash
browser4-cli agent run "Go to amazon.com, search for 'wireless headphones', extract the top 5 product titles and prices"
browser4-cli agent status <task-id>   # poll until COMPLETED
browser4-cli agent result <task-id>
```

**Writing tasks:** Describe **what** you want, not how. Good: "extract the top 5 product titles and prices." Avoid step-by-step ref-based instructions.

### 2. Poll Status

```bash
browser4-cli agent status <task-id>
```

Returns JSON:
```json
{"id":"3b27930e-48bc-4460-aae7-be521f0a9194","status":"RUNNING","statusCode":null,"processState":"processing","message":"Navigating..."}
```

| Status | Meaning |
|---|---|
| `RUNNING` | Agent is actively working |
| `COMPLETED` | Task finished — call `agent result` |
| `FAILED` | Task errored — inspect `message` and `statusCode` |
| `EXPECTATION_FAILED` | Precondition failed (e.g., missing LLM config, status 417) |

### 3. Fetch Results

```bash
browser4-cli agent result <task-id>
```

Always confirm completion via `agent status` first — incomplete tasks may return empty or partial results.

### 4. Composing with Standard Commands

```bash
browser4-cli open https://app.example.com
browser4-cli state-load auth.json
browser4-cli agent run "Navigate to the reports dashboard, extract all monthly metrics, and summarize trends"
```

### 5. Synchronous Extraction (extract)

```bash
browser4-cli extract "product name, price, ratings"
browser4-cli extract "all article headlines and authors" --schema '{"fields":[{"name":"title","type":"string"},{"name":"author","type":"string"}]}'
```

### 6. Synchronous Summarization (summarize)

```bash
browser4-cli summarize "summarize the product reviews"
browser4-cli summarize --selector "#content"
```

## Errors & Recovery

| Symptom | Recovery |
|----------|---------|
| `agent run` exits non-zero | Check backend is reachable and LLM key is configured |
| Task stuck in RUNNING | Poll `agent status` — some tasks take minutes |
| Status/result returns unexpected payload | Inspect `status`, `statusCode`, `message` fields |
| Task lost after backend restart | Task IDs may not persist; re-submit the task |
| `extract`/`summarize` blocking too long | Use `agent run` for async execution instead |
| Agent subcommands in batch mode | Not supported — use standalone commands |


codex
Documentation read. Let me verify which LLM provider is configured (without exposing keys), then start AC1.
exec
"C:\\Program Files\\PowerShell\\7\\pwsh.exe" -Command '$dirs = Get-ChildItem "$HOME/.browser4/config/conf-enabled" -Directory -ErrorAction SilentlyContinue; foreach ($d in $dirs) { $k = Join-Path $d.FullName "keys.json"; if (Test-Path $k) { $j = Get-Content $k -Raw | ConvertFrom-Json; Write-Output "$($d.Name): $($j.PSObject.Properties.Name -join '"', ')\" } }" in D:\workspace\Browser4\Browser4-4.13
 succeeded in 309ms:
exec
"C:\\Program Files\\PowerShell\\7\\pwsh.exe" -Command 'browser4-cli --help-json | Out-File -Encoding UTF8 .test-sessions/help-json.json; (Get-Content .test-sessions/help-json.json -Raw).Length; browser4-cli help extract' in D:\workspace\Browser4\Browser4-4.13
 succeeded in 436ms:
123936
browser4-cli extract <instruction>

Extract structured data from the current page

Arguments:
  <instruction>               What data to extract, e.g. 'product name, price, ratings'
Options:
  --schema                    JSON Schema or {fields:[...]} format to constrain extracted data.  Supports @file.json to
                              avoid shell quoting.
  --filename                  Save extracted content to a file instead of printing to stdout
  --raw                       Print extracted content directly to stdout (alias for --stdout)
  --stdout                    Print extracted content directly to stdout
Notes:
  - Uses AI to extract structured data from the current page based on a natural-language instruction.
  - Requires: an LLM API key configured on the Browser4 backend server (the CLI shell itself does not need the key).
  - --schema accepts a JSON schema to constrain the extracted data.  Both standard JSON Schema
    ({"type":"object","properties":{...}}) and the compact {fields:[{name,type,description,required}]} format are
    supported.  Use @file.json to avoid shell quoting issues.
  - Output is saved to a timestamped file by default. Use --stdout (or --raw) to print to stdout instead, or --filename
    to specify a custom path.

Examples:
  browser4-cli extract "product name, price, ratings"
  browser4-cli extract "all contact info" --schema @schema.json
  browser4-cli extract "article titles and dates" --stdout
  browser4-cli extract "page metadata" --filename meta.json

codex
The config directory check came up empty. Let me find the LLM configuration source without exposing key values.
exec
"C:\\Program Files\\PowerShell\\7\\pwsh.exe" -Command 'Get-ChildItem "$HOME/.browser4/config" -Recurse -File -ErrorAction SilentlyContinue | Select-Object FullName; Write-Output "---conf-enabled contents---"; Get-ChildItem "$HOME/.browser4/config/conf-enabled" -Recurse -Force -ErrorAction SilentlyContinue | Select-Object FullName' in D:\workspace\Browser4\Browser4-4.13
 succeeded in 374ms:

FullName
--------
C:\Users\pereg\.browser4\config\conf-available\a1-pulsar-volcengine-deepseek.xml
C:\Users\pereg\.browser4\config\conf-available\a2-pulsar-volcengine-deepseek-v3.xml
C:\Users\pereg\.browser4\config\conf-available\a3-pulsar-volcengine-deepseek-r1.xml
C:\Users\pereg\.browser4\config\conf-available\application-private.properties
C:\Users\pereg\.browser4\config\conf-available\b1-pulsar-deepseek.xml
C:\Users\pereg\.browser4\config\conf-available\c1-pulsar-qwen-plus-0806.xml
C:\Users\pereg\.browser4\config\conf-available\c2-pulsar-qwen-max.xml
C:\Users\pereg\.browser4\config\conf-available\c3-pulsar-qwen-baichuan2-turbo-192k.xml
C:\Users\pereg\.browser4\config\conf-enabled\application-private.properties
---conf-enabled contents---
C:\Users\pereg\.browser4\config\conf-enabled\application-private.properties


exec
"C:\\Program Files\\PowerShell\\7\\pwsh.exe" -Command '$p = Get-Content "$HOME/.browser4/config/conf-enabled/application-private.properties" | Where-Object { $_ -match '"'=' -and "'$_ -notmatch '"'"'^'"\\s*#' }; foreach ("'$line in $p) { $k = ($line -split '"'=', 2)[0].Trim(); "'$v = ($line -split '"'=', 2)[1].Trim(); if ("'$k -match '"'KEY|SECRET|TOKEN|PASSWORD') { Write-Output \""'$k = [REDACTED, length $($v.Length)]" } else { Write-Output $line } }' in D:\workspace\Browser4\Browser4-4.13
 succeeded in 349ms:
spring.main.allow-bean-definition-overriding=true
DEEPSEEK_API_KEY = [REDACTED, length 35]
DEEPSEEK_MODEL_NAME=deepseek-v4-flash
DEEPSEEK_BASE_URL=https://api.deepseek.com

codex
DeepSeek is configured on the backend — AC6 will be runnable. Starting AC1 now.
  ✓ Verify environment, CLI help, and SKILL.md documentation
  ✓ Ensure MockSite running and LLM key configured for extract
  → AC1: Interact-then-extract on form-filling.html
  • AC2: Static page single field via htmlsnapshot get text
  • AC3: Static page all matches via htmlsnapshot get all text
  • AC4: Correlated multi-field rows via htmlsnapshot query X-SQL
  • AC5: Dynamic page logic via eval --json
  • AC6: Natural-language extraction via extract
  • AC7: High-volume extraction via crawl --sql seed file
  • Compile task result, execution trace, and issue report
exec
"C:\\Program Files\\PowerShell\\7\\pwsh.exe" -Command 'browser4-cli goto "http://localhost:18080/generated/form-filling.html"' in D:\workspace\Browser4\Browser4-4.13
 succeeded in 7162ms:
Session opened: eac55b6f-7bbd-4d8d-801d-b74324fb9904
Navigated to http://localhost:18080/generated/form-filling.html
Page loaded. Use `wait --load networkidle` if content appears incomplete (e.g. async-rendered SPAs).
### Page
- Page URL: http://localhost:18080/generated/form-filling.html
- Page Title: Browser4 Form Filling Playground
### Snapshot
[Snapshot](D:\workspace\Browser4\Browser4-4.13\.browser4-cli\snapshot\snapshot-2026-08-14T07-25-18-190Z.yml)
No active session — creating a new one.
💡 Tip: Try `htmlsnapshot get text "h1"` to extract the page heading, or `htmlsnapshot inspect` to discover CSS selectors

codex
Page loaded. Now capturing an interactive snapshot to get element refs.
exec
"C:\\Program Files\\PowerShell\\7\\pwsh.exe" -Command 'browser4-cli snapshot -i --stdout' in D:\workspace\Browser4\Browser4-4.13
 succeeded in 493ms:
# Viewport State
# - processingViewport: 0
# - viewportHeight: 1080px
# - viewportsTotal: 3
# - hiddenTopHeight: 0px
# - hiddenBottomHeight: 1491px
#
- banner "pageHeader heroIntro Form Filling Playground Form Filling Playground A stable, test-friendly page for exercising Browser4 form controls. It keeps the familiar registration selectors from the CLI E2E fixture while adding radios, checkboxes, dynamic fields, realtime validation, and explicit DOM event logging. Testing capabilities Testing capabilities Text inputs Checkboxes and radios Select menus Dynamic fields Realtime validation Form status summary statsPanel Form status summary Live Summary Live Summary Completion completionValue 0 / 5 required Primary contact contactMethodValue email Last event lastEventValue page-ready" [ref=e30] [box=390,32,1140,354]:
  - generic "heroIntro Form Filling Playground Form Filling Playground A stable, test-friendly page for exercising Browser4 form controls. It keeps the familiar registration selectors from the CLI E2E fixture while adding radios, checkboxes, dynamic fields, realtime validation, and explicit DOM event logging. Testing capabilities Testing capabilities Text inputs Checkboxes and radios Select menus Dynamic fields Realtime validation" [ref=e31] [box=390,32,735,354]:
    - heading "Form Filling Playground" [level=1] [ref=e32] [box=418,60,677,47]
    - paragraph "A stable, test-friendly page for exercising Browser4 form controls. It keeps the familiar registration selectors from the CLI E2E fixture while adding radios, checkboxes, dynamic fields, realtime validation, and explicit DOM event logging." [ref=e33] [box=418,119,677,76]
    - generic "Testing capabilities" [ref=e34] [box=418,212,677,82]
  - complementary "Form status summary" [ref=e40] [box=1143,32,386,354]:
    - heading "Live Summary" [level=2] [ref=e41] [box=1167,56,337,24]
    - generic "completionValue 0 / 5 required" [ref=e45] [box=1184,133,133,28]: 0 / 5 required
    - generic "contactMethodValue email" [ref=e48] [box=1184,226,53,28]: email
    - generic "lastEventValue page-ready" [ref=e51] [box=1184,318,111,28]: page-ready
- main [ref=e52] [box=390,410,1140,1232]:
  - generic [ref=e53] [box=390,410,744,1232]:
    - heading "Registration Form" [level=2] [ref=e54] [box=414,434,694,32]
    - paragraph "Fill the form to exercise input, select, check, uncheck, click, and reset interactions. The submit and reset buttons update both the visual result panel and the serialized state log below." [ref=e55] [box=414,486,694,51]
    - form [ref=e1] [box=414,555,694,1062]:
      - generic "identitySection First Name First Name text first-name firstName Required. Used in the final submission payload. first-name-error Last Name Last Name text last-name lastName Required. Kept for parity with CLI form tests. last-name-error Email Email email email email Realtime validation checks the email format. email-error Country Country country country -- select -- -- select -- United States us United States United Kingdom uk United Kingdom Japan jp Japan Singapore sg Singapore Germany de Germany Dropdown for select and batch-select coverage. country-error" [ref=e56] [box=414,555,694,332]:
        - LabelText "First Name" [ref=e58] [box=433,574,183,21]
        - textbox "First Name" [ref=e2] [cursor=pointer] [box=433,603,183,46]:
          - /required: "true"
        - LabelText "Last Name" [ref=e64] [box=670,574,183,21]
        - textbox "Last Name" [ref=e3] [cursor=pointer] [box=670,603,183,46]:
          - /required: "true"
        - LabelText "Email" [ref=e70] [box=907,574,183,21]
        - textbox "Email" [ref=e4] [cursor=pointer] [box=907,603,183,46]:
          - /required: "true"
        - LabelText "Country" [ref=e76] [box=433,747,183,21]
        - combobox "Country" [ref=e5] [cursor=pointer] [box=433,777,183,48]:
          - /has_popup: menu
      - generic "preferencesSection Preferred contact method Preferred contact method Changing this group reveals a different dynamic field below. contact-method-group Email radio contact-email contact-method email Email Phone radio contact-phone contact-method phone Phone Business radio contact-business contact-method business Business Topics of interest Topics of interest Useful for testing multi-checkbox state tracking. topics-group Automation checkbox topic-automation topic automation Automation Testing checkbox topic-testing topic testing Testing AI agents checkbox topic-ai topic ai AI agents" [ref=e104] [box=414,907,694,212]:
        - group "Preferred contact method" [ref=e105] [box=416,907,335,212]:
          - Legend "Preferred contact method" [ref=e106] [box=435,907,210,21]
          - paragraph "Changing this group reveals a different dynamic field below." [ref=e107] [box=435,947,298,42]
          - generic "contact-method-group Email radio contact-email contact-method email Email Phone radio contact-phone contact-method phone Phone Business radio contact-business contact-method business Business" [ref=e108] [box=435,1001,298,100]:
            - radio "Email" [checked] [ref=e10] [cursor=pointer] [box=450,1017,13,13]
            - text: Email
            - radio "Phone" [ref=e11] [cursor=pointer] [box=551,1017,13,13]
            - text: Phone
            - radio "Business" [ref=e12] [cursor=pointer] [box=450,1073,13,13]
            - text: Business
        - group "Topics of interest" [ref=e112] [box=772,907,335,212]:
          - Legend "Topics of interest" [ref=e113] [box=790,907,144,21]
          - paragraph "Useful for testing multi-checkbox state tracking." [ref=e114] [box=790,947,298,42]
          - generic "topics-group Automation checkbox topic-automation topic automation Automation Testing checkbox topic-testing topic testing Testing AI agents checkbox topic-ai topic ai AI agents" [ref=e115] [box=790,1001,298,100]:
            - checkbox "Automation" [checked] [ref=e13] [cursor=pointer] [box=805,1017,13,13]
            - text: Automation
            - checkbox "Testing" [ref=e14] [cursor=pointer] [box=954,1017,13,13]
            - text: Testing
            - checkbox "AI agents" [ref=e15] [cursor=pointer] [box=805,1073,13,13]
            - text: AI agents
      - generic "dynamicFieldsSection phone-field-wrapper Phone Number tel phone-number phoneNumber +1 555 123 4567 Visible when the contact method is phone. phone-number-error company-field-wrapper Company text company company Browser4 Labs Visible when the contact method is business. company-error Experience level Experience level experience-level experienceLevel Beginner beginner Beginner Intermediate intermediate Intermediate Advanced advanced Advanced Extra select control for form-state assertions. experience-level-error" [ref=e119] [box=414,1140,694,140]:
        - generic "phone-field-wrapper Phone Number tel phone-number phoneNumber +1 555 123 4567 Visible when the contact method is phone. phone-number-error" [ref=e120]:
          - textbox "tel phone-number phoneNumber +1 555 123 4567" [ref=e6]:
            - /placeholder: +1 555 123 4567
        - generic "company-field-wrapper Company text company company Browser4 Labs Visible when the contact method is business. company-error" [ref=e127]:
          - textbox "text company company Browser4 Labs" [ref=e7]:
            - /placeholder: Browser4 Labs
        - LabelText "Experience level" [ref=e135] [box=433,1159,657,21]
        - combobox "Experience level" [ref=e8] [cursor=pointer] [box=433,1188,657,48]:
          - /has_popup: menu
      - generic "messageSection Comments Comments comments comments Tell us what workflow you want to automate next. comment-length 0 characters comments-error Consent and updates Consent and updates Send me product and release updates checkbox newsletter-opt-in Send me product and release updates I agree to the testing terms checkbox agree-terms I agree to the testing terms The agreement checkbox stays required for submit parity. agree-terms-error" [ref=e154] [box=414,1301,694,250]:
        - LabelText "Comments" [ref=e156] [box=433,1319,302,21]
        - textbox "Comments" [ref=e9] [cursor=pointer] [box=433,1349,302,132]:
          - /placeholder: Tell us what workflow you want to automate next.
          - /multiline: "true"
        - generic "comment-length 0 characters" [ref=e161] [box=433,1493,72,18]: 0 characters
        - group "Consent and updates" [ref=e163] [box=772,1301,335,250]:
          - Legend "Consent and updates" [ref=e164] [box=790,1301,174,21]
          - checkbox "Send me product and release updates" [ref=e16] [cursor=pointer] [box=805,1355,13,13]
          - text: Send me product and release updates
          - checkbox "I agree to the testing terms" [ref=e17] [cursor=pointer] [box=805,1439,13,13]:
            - /invalid: "true"
          - text: I agree to the testing terms
      - button "Submit form" [ref=e172] [cursor=pointer] [box=414,1571,131,46]
      - button "Reset form" [ref=e173] [cursor=pointer] [box=558,1571,119,46]
  - complementary "statusPanel Submission Status Submission Status error-panel alert result-panel Submission Result Your form data was captured successfully. Last submission payload Last submission payload result-data No submission yet. Validation guidance Validation guidance validation-summary First name is required. Last name is required. Email is required. Country is required. You must agree to the testing terms." [ref=e174] [box=1158,410,372,1232]:
    - heading "Submission Status" [level=2] [ref=e175] [box=1182,434,322,313]
    - heading "Last submission payload" [level=3] [ref=e181] [box=1199,798,289,24]
    - generic "result-data No submission yet." [ref=e182] [box=1199,833,289,20]: No submission yet.
    - heading "Validation guidance" [level=3] [ref=e184] [box=1199,1182,289,24]
    - generic "validation-summary First name is required. Last name is required. Email is required. Country is required. You must agree to the testing terms." [ref=e185] [box=1199,1217,289,102]: First name is required. Last name is required. Email is required. Country is required. You must agree to the testing terms.
- "complementary \"debugPanel Interaction Debug Interaction Debug Event log Event log event-log page-ready Serialized state Serialized state state-log { \\\"firstName\\\": \\\"\\\", \\\"lastName\\\": \\\"\\\", \\\"email\\\": \\\"\\\", \\\"country\\\": \\\"\\\", \\\"agreeTerms\\\": false, \\\"comments\\\": \\\"\\\", \\\"submitCount\\\": 0, \\\"resetCount\\\": 0, \\\"lastSubmission\\\": null, \\\"validationError\\\": \\\"First name is required.\\\", \\\"contactMethod\\\": \\\"email\\\", \\\"newsletterOptIn\\\": false, \\\"topics\\\": [ \\\"automation\\\" ], \\\"phoneNumber\\\": \\\"\\\", \\\"company\\\": \\\"\\\", \\\"experienceLevel\\\": \\\"intermediate\\\", \\\"commentLength\\\": 0, \\\"dynamicFieldVisible\\\": \\\"none\\\", \\\"eventLog\\\": [ \\\"page-ready\\\" ], \\\"requiredCompleted\\\": 0, \\\"formValid\\\": false } screen-reader-status\" [ref=e186] [box=390,1642,1140,880]":
  - heading "Interaction Debug" [level=2] [ref=e187] [box=410,1663,1098,32]
  - generic "Event log Event log event-log page-ready" [ref=e188] [box=410,1731,1098,143]:
    - heading "Event log" [level=3] [ref=e189] [box=410,1731,1098,21]
    - generic "event-log page-ready" [ref=e191] [box=425,1777,1069,20]: page-ready
  - "generic \"Serialized state Serialized state state-log { \\\"firstName\\\": \\\"\\\", \\\"lastName\\\": \\\"\\\", \\\"email\\\": \\\"\\\", \\\"country\\\": \\\"\\\", \\\"agreeTerms\\\": false, \\\"comments\\\": \\\"\\\", \\\"submitCount\\\": 0, \\\"resetCount\\\": 0, \\\"lastSubmission\\\": null, \\\"validationError\\\": \\\"First name is required.\\\", \\\"contactMethod\\\": \\\"email\\\", \\\"newsletterOptIn\\\": false, \\\"topics\\\": [ \\\"automation\\\" ], \\\"phoneNumber\\\": \\\"\\\", \\\"company\\\": \\\"\\\", \\\"experienceLevel\\\": \\\"intermediate\\\", \\\"commentLength\\\": 0, \\\"dynamicFieldVisible\\\": \\\"none\\\", \\\"eventLog\\\": [ \\\"page-ready\\\" ], \\\"requiredCompleted\\\": 0, \\\"formValid\\\": false }\" [ref=e192] [box=410,1890,1098,611]":
    - heading "Serialized state" [level=3] [ref=e193] [box=410,1890,1098,21]
    - "generic \"state-log { \\\"firstName\\\": \\\"\\\", \\\"lastName\\\": \\\"\\\", \\\"email\\\": \\\"\\\", \\\"country\\\": \\\"\\\", \\\"agreeTerms\\\": false, \\\"comments\\\": \\\"\\\", \\\"submitCount\\\": 0, \\\"resetCount\\\": 0, \\\"lastSubmission\\\": null, \\\"validationError\\\": \\\"First name is required.\\\", \\\"contactMethod\\\": \\\"email\\\", \\\"newsletterOptIn\\\": false, \\\"topics\\\": [ \\\"automation\\\" ], \\\"phoneNumber\\\": \\\"\\\", \\\"company\\\": \\\"\\\", \\\"experienceLevel\\\": \\\"intermediate\\\", \\\"commentLength\\\": 0, \\\"dynamicFieldVisible\\\": \\\"none\\\", \\\"eventLog\\\": [ \\\"page-ready\\\" ], \\\"requiredCompleted\\\": 0, \\\"formValid\\\": false }\" [ref=e195] [box=425,1936,1069,551]": "{ \"firstName\": \"\", \"lastName\": \"\", \"email\": \"\", \"country\": \"\", \"agreeTerms\": false, \"comments\": \"\", \"submitCount\": 0, \"resetCount\": 0, \"lastSubmission\": null, \"validationError\": \"First name is required.\", \"contactMethod\": \"email\", \"newsletterOptIn\": false, \"topics\": [ \"automation\" ], \"phoneNumber\": \"\", \"company\": \"\", \"experienceLevel\": \"intermediate\", \"commentLength\": 0, \"dynamicFieldVisible\": \"none\", \"eventLog\": [ \"page-ready\" ], \"requiredCompleted\": 0, \"formValid\": false }"
  - generic "screen-reader-status" [ref=e196] [box=409,1662,1,1]:
    - /live: polite
    - /atomic: "false"
    - /relevant: additions text# ---
# This page has 3 viewports (page chunks split by viewport height). You are currently viewing viewport 0 (absolute).
# To read the page viewport by viewport (like a human scrolling):
#   snapshot -v 0          # current visible area
#   snapshot -v 1          # scroll down one viewport
#   snapshot -v 0-2    # capture all viewports at once
#   snapshot -v all       # capture all viewports (same as above)
#

ℹ️  Element refs (e.g. e5, e36) are valid only until the next browser interaction. Re-run snapshot before reusing refs.

codex
Got the form refs. Now filling the form fields one by one, starting with First Name.
exec
"C:\\Program Files\\PowerShell\\7\\pwsh.exe" -Command 'browser4-cli fill e2 "Ada"' in D:\workspace\Browser4\Browser4-4.13
 succeeded in 530ms:
✓ Filled 'Ada' into e2
### Page
- Page URL: http://localhost:18080/generated/form-filling.html
- Page Title: Browser4 Form Filling Playground
### Snapshot
[Snapshot](D:\workspace\Browser4\Browser4-4.13\.browser4-cli\snapshot\snapshot-2026-08-14T07-25-28-400Z.yml)
💡 Tip: Try `htmlsnapshot get text "h1"` to extract the page heading, or `htmlsnapshot inspect` to discover CSS selectors

exec
"C:\\Program Files\\PowerShell\\7\\pwsh.exe" -Command 'browser4-cli fill e3 "Lovelace"' in D:\workspace\Browser4\Browser4-4.13
 succeeded in 528ms:
✓ Filled 'Lovelace' into e3
### Page
- Page URL: http://localhost:18080/generated/form-filling.html
- Page Title: Browser4 Form Filling Playground
### Snapshot
[Snapshot](D:\workspace\Browser4\Browser4-4.13\.browser4-cli\snapshot\snapshot-2026-08-14T07-25-31-290Z.yml)
💡 Tip: Try `htmlsnapshot get text "h1"` to extract the page heading, or `htmlsnapshot inspect` to discover CSS selectors

exec
"C:\\Program Files\\PowerShell\\7\\pwsh.exe" -Command 'browser4-cli fill e4 "ada@example.com"' in D:\workspace\Browser4\Browser4-4.13
 succeeded in 540ms:
✓ Filled 'ada@example.com' into e4
### Page
- Page URL: http://localhost:18080/generated/form-filling.html
- Page Title: Browser4 Form Filling Playground
### Snapshot
[Snapshot](D:\workspace\Browser4\Browser4-4.13\.browser4-cli\snapshot\snapshot-2026-08-14T07-25-33-121Z.yml)
💡 Tip: Try `htmlsnapshot get text "h1"` to extract the page heading, or `htmlsnapshot inspect` to discover CSS selectors

codex
Now selecting the Country dropdown and the Experience level, plus a checkbox.
exec
"C:\\Program Files\\PowerShell\\7\\pwsh.exe" -Command 'browser4-cli select e5 "jp"' in D:\workspace\Browser4\Browser4-4.13
 succeeded in 566ms:
["jp"]
✓ Selected 'jp' in e5
### Page
- Page URL: http://localhost:18080/generated/form-filling.html
- Page Title: Browser4 Form Filling Playground
### Snapshot
[Snapshot](D:\workspace\Browser4\Browser4-4.13\.browser4-cli\snapshot\snapshot-2026-08-14T07-25-35-808Z.yml)
💡 Tip: Try `htmlsnapshot get text "h1"` to extract the page heading, or `htmlsnapshot inspect` to discover CSS selectors

exec
"C:\\Program Files\\PowerShell\\7\\pwsh.exe" -Command 'browser4-cli select e8 "advanced"' in D:\workspace\Browser4\Browser4-4.13
 succeeded in 602ms:
["advanced"]
✓ Selected 'advanced' in e8
### Page
- Page URL: http://localhost:18080/generated/form-filling.html
- Page Title: Browser4 Form Filling Playground
### Snapshot
[Snapshot](D:\workspace\Browser4\Browser4-4.13\.browser4-cli\snapshot\snapshot-2026-08-14T07-25-37-939Z.yml)
💡 Tip: Try `htmlsnapshot get text "h1"` to extract the page heading, or `htmlsnapshot inspect` to discover CSS selectors

exec
"C:\\Program Files\\PowerShell\\7\\pwsh.exe" -Command 'browser4-cli check e14' in D:\workspace\Browser4\Browser4-4.13
 succeeded in 594ms:
✓ Checked e14
### Page
- Page URL: http://localhost:18080/generated/form-filling.html
- Page Title: Browser4 Form Filling Playground
### Snapshot
[Snapshot](D:\workspace\Browser4\Browser4-4.13\.browser4-cli\snapshot\snapshot-2026-08-14T07-25-40-107Z.yml)
💡 Tip: Try `htmlsnapshot get text "h1"` to extract the page heading, or `htmlsnapshot inspect` to discover CSS selectors

exec
"C:\\Program Files\\PowerShell\\7\\pwsh.exe" -Command 'browser4-cli check e16' in D:\workspace\Browser4\Browser4-4.13
 succeeded in 558ms:
✓ Checked e16
### Page
- Page URL: http://localhost:18080/generated/form-filling.html
- Page Title: Browser4 Form Filling Playground
### Snapshot
[Snapshot](D:\workspace\Browser4\Browser4-4.13\.browser4-cli\snapshot\snapshot-2026-08-14T07-25-42-139Z.yml)
💡 Tip: Try `htmlsnapshot get text "h1"` to extract the page heading, or `htmlsnapshot inspect` to discover CSS selectors

exec
"C:\\Program Files\\PowerShell\\7\\pwsh.exe" -Command 'browser4-cli fill e9 "Automating browser4-cli usability evaluation"' in D:\workspace\Browser4\Browser4-4.13
 succeeded in 534ms:
✓ Filled 'Automating browser4-cli usability evaluation' into e9
### Page
- Page URL: http://localhost:18080/generated/form-filling.html
- Page Title: Browser4 Form Filling Playground
### Snapshot
[Snapshot](D:\workspace\Browser4\Browser4-4.13\.browser4-cli\snapshot\snapshot-2026-08-14T07-25-44-169Z.yml)
💡 Tip: Try `htmlsnapshot get text "h1"` to extract the page heading, or `htmlsnapshot inspect` to discover CSS selectors

exec
"C:\\Program Files\\PowerShell\\7\\pwsh.exe" -Command 'browser4-cli check e17; browser4-cli click e172' in D:\workspace\Browser4\Browser4-4.13
 succeeded in 6140ms:
✓ Checked e17
### Page
- Page URL: http://localhost:18080/generated/form-filling.html
- Page Title: Browser4 Form Filling Playground
### Snapshot
[Snapshot](D:\workspace\Browser4\Browser4-4.13\.browser4-cli\snapshot\snapshot-2026-08-14T07-25-46-130Z.yml)
✓ Clicked e172
### Page
- Page URL: http://localhost:18080/generated/form-filling.html
- Page Title: Browser4 Form Filling Playground
### Snapshot
[Snapshot](D:\workspace\Browser4\Browser4-4.13\.browser4-cli\snapshot\snapshot-2026-08-14T07-25-51-675Z.yml)
💡 Tip: Try `htmlsnapshot get text "h1"` to extract the page heading, or `htmlsnapshot inspect` to discover CSS selectors
💡 Tip: Try `htmlsnapshot get text "h1"` to extract the page heading, or `htmlsnapshot inspect` to discover CSS selectors

codex
Form submitted. Now capturing a fresh HTML snapshot and extracting the confirmation data.
exec
"C:\\Program Files\\PowerShell\\7\\pwsh.exe" -Command 'browser4-cli htmlsnapshot' in D:\workspace\Browser4\Browser4-4.13
 succeeded in 660ms:
Snapshot: "Browser4 Form Filling Playground"
http://localhost:18080/generated/form-filling.html · 41 KB · text/html · captured 2026-08-14T07:25:54.464Z
16 interactive elements

### Interactive Elements
  Buttons (2):
    2. #registration-form button#submit-btn.primary-button "Submit form"  [box=415 1571 131 47; w=1006157]
    3. #registration-form button#reset-btn.secondary-button "Reset form"  [box=558 1571 119 47; w=1005593]

  Inputs (14):
    4. #messageSection textarea#comments             [box=433 1349 302 132; w=1039864]
    5. #dynamicFieldsSection select#experience-level "Beginner Intermediate Advanced"  [box=433 1188 657 48; w=1031536]
    6. #identitySection select#country              "-- select -- United States"  [box=433 777 184 48; w=1008832]
    7. #identitySection input#first-name             [box=433 604 184 47; w=1008648]
    8. #identitySection input#last-name              [box=670 604 184 47; w=1008648]
    9. #identitySection input#email                  [box=907 604 184 47; w=1008648]
   10. #contact-method-group input#contact-email     [box=450 1017 13 13; w=1000169]
   11. #contact-method-group input#contact-phone     [box=552 1017 13 13; w=1000169]
   12. #contact-method-group input#contact-business  [box=450 1073 13 13; w=1000169]
   13. #topics-group input#topic-automation          [box=805 1017 13 13; w=1000169]
   14. #topics-group input#topic-testing             [box=954 1017 13 13; w=1000169]
   15. #topics-group input#topic-ai                  [box=805 1073 13 13; w=1000169]
   16. #messageSection input#newsletter-opt-in       [box=805 1355 13 13; w=1000169]
   17. #messageSection input#agree-terms             [box=805 1439 13 13; w=1000169]

  ℹ️  The live page is still accessible — use `eval`, `snapshot`, or `click` to continue interacting.
  💡 Try these next:
    Use `get all text` to extract visible text, or `get all attr <name>` for attribute values.
    The SQL variant lets you query with full expressive power (joins, filters, aggregates).
     htmlsnapshot get text "h1" --limit 5   # page heading
     htmlsnapshot get all text "a" --limit 20  # link texts
     htmlsnapshot get attr "img[src]" src --limit 20  # image URLs
     htmlsnapshot get attr "a[href]" href --limit 20  # link URLs
     htmlsnapshot inspect  # discover recurring patterns
     htmlsnapshot query --sql "SELECT DOM_TEXT(DOM) AS text FROM DOM_LOAD_AND_SELECT(@url, 'a')"

codex
Fresh HTML snapshot captured. Extracting the confirmation banner and submitted-value container.
exec
"C:\\Program Files\\PowerShell\\7\\pwsh.exe" -Command 'browser4-cli htmlsnapshot get text ".result-data"; Write-Output "---ALERT---"; browser4-cli htmlsnapshot get text ".alert"; Write-Output "---SUMMARY---"; browser4-cli htmlsnapshot get text ".completionValue"; Write-Output "---PANEL---"; browser4-cli htmlsnapshot get text "#result-panel"' in D:\workspace\Browser4\Browser4-4.13
 succeeded in 569ms:

No elements matched ".result-data".
  The snapshot may be stale — it reflects the DOM at capture time. If the page has changed since the last `htmlsnapshot`, re-capture with `htmlsnapshot` first.
  Verify the selector with `htmlsnapshot grep ".result-data"`, or discover valid selectors with `htmlsnapshot inspect`.
---ALERT---

No elements matched ".alert".
  The snapshot may be stale — it reflects the DOM at capture time. If the page has changed since the last `htmlsnapshot`, re-capture with `htmlsnapshot` first.
  Verify the selector with `htmlsnapshot grep ".alert"`, or discover valid selectors with `htmlsnapshot inspect`.
---SUMMARY---

No elements matched ".completionValue".
  The snapshot may be stale — it reflects the DOM at capture time. If the page has changed since the last `htmlsnapshot`, re-capture with `htmlsnapshot` first.
  Verify the selector with `htmlsnapshot grep ".completionValue"`, or discover valid selectors with `htmlsnapshot inspect`.
---PANEL---
Submission Result Your form data was captured successfully.

codex
The `.result-data` selector didn't match. Let me grep the snapshot HTML to find the actual element structure.
exec
"C:\\Program Files\\PowerShell\\7\\pwsh.exe" -Command 'browser4-cli htmlsnapshot grep "result-data|result-panel|submission" -i --no-line-number' in D:\workspace\Browser4\Browser4-4.13
 succeeded in 357ms:
# htmlsnapshot snapshot captured at 2026-08-14T07:26:00Z
        <div class="helper-row" vi="433 658 184 36"> <span class="helper-text" vi="433 658 184 36">Required. Used in the final submission payload.</span> <span id="first-name-error" class="field-error" aria-live="polite" _h="1"></span> 
--
     <h2 vi="1183 435 323 147">Submission Status</h2> 
--
     <div id="result-panel" data-testid="result-panel" class="callout success visible" aria-live="polite" vi="1183 616 323 240"> <strong vi="1199 630 136 21">Submission Result</strong> 
--
      <h3 vi="1199 887 289 25">Last submission payload</h3> 
      <pre id="result-data" data-testid="result-data" vi="1199 922 289 347">{
--
  "lastSubmission": {

codex
Found it — the container is an ID (`#result-data`), not a class. Extracting with the correct selector.
exec
"C:\\Program Files\\PowerShell\\7\\pwsh.exe" -Command 'browser4-cli htmlsnapshot get text "#result-data"' in D:\workspace\Browser4\Browser4-4.13
 succeeded in 374ms:
{
  "firstName": "Ada",
  "lastName": "Lovelace",
  "email": "ada@example.com",
  "country": "jp",
  "comments": "Automating browser4-cli usability evaluation",
  "contactMethod": "email",
  "topics": [
    "automation",
    "testing"
  ],
  "newsletterOptIn": true,
  "phoneNumber": "",
  "company": "",
  "experienceLevel": "advanced"
}

codex
AC1 complete — extracted payload matches entered values. Moving to AC2 (static page, single field).
  ✓ Verify environment, CLI help, and SKILL.md documentation
  ✓ Ensure MockSite running and LLM key configured for extract
  ✓ AC1: Interact-then-extract on form-filling.html
  → AC2: Static page single field via htmlsnapshot get text
  • AC3: Static page all matches via htmlsnapshot get all text
  • AC4: Correlated multi-field rows via htmlsnapshot query X-SQL
  • AC5: Dynamic page logic via eval --json
  • AC6: Natural-language extraction via extract
  • AC7: High-volume extraction via crawl --sql seed file
  • Compile task result, execution trace, and issue report
exec
"C:\\Program Files\\PowerShell\\7\\pwsh.exe" -Command 'browser4-cli goto "http://localhost:18080/ec/dp/B0E000001"' in D:\workspace\Browser4\Browser4-4.13
 succeeded in 4645ms:
Using existing session DEFAULT (current page: http://localhost:18080/generated/form-filling.html).
Navigated to http://localhost:18080/ec/dp/B0E000001
Page loaded. Use `wait --load networkidle` if content appears incomplete (e.g. async-rendered SPAs).
### Page
- Page URL: http://localhost:18080/ec/dp/B0E000001
- Page Title: Product: 4K OLED TV 55
### Snapshot
[Snapshot](D:\workspace\Browser4\Browser4-4.13\.browser4-cli\snapshot\snapshot-2026-08-14T07-26-14-130Z.yml)
💡 Tip: Try `htmlsnapshot get text "h1"` to extract the page heading, or `htmlsnapshot inspect` to discover CSS selectors

exec
"C:\\Program Files\\PowerShell\\7\\pwsh.exe" -Command 'browser4-cli htmlsnapshot; Write-Output "---GET TITLE---"; browser4-cli htmlsnapshot get text "#productTitle"' in D:\workspace\Browser4\Browser4-4.13
 succeeded in 777ms:
Snapshot: "Product: 4K OLED TV 55"
http://localhost:18080/ec/dp/B0E000001 · 15 KB · text/html · captured 2026-08-14T07:26:16.325Z
1 images · 11 links · 13 interactive elements

### Interactive Elements
  Buttons (2):
    2. #product-page button.add-cart                "Add to cart"  [box=1325 336 282 41; w=1011562]
    3. #product-page button.buy-now                 "Buy now"  [box=1325 387 282 41; w=1011562]

  Links (11):
    4. a                                            "Wireless Noise-Cancelling Headphones"  [box=330 1106 200 37; w=7400]
    5. a                                            "Portable Bluetooth Speaker"  [box=657 1106 210 18; w=3780]
    6. a                                            "Smartphone 128GB"  [box=985 1106 148 18; w=2664]
    7. a                                            "USB-C Hub 7-in-1"  [box=1312 1106 133 18; w=2394]
    8. a                                            "Customer service"  [box=1765 48 115 17; w=1955]
    9. a                                            "Back to category"  [box=1526 48 110 17; w=1870]
   10. a                                            "Today's deals"  [box=1656 48 89 17; w=1513]
   11. #product-category-link a                     "Electronics"  [box=691 233 78 18; w=1404]
   12. a                                            "Electronics"  [box=346 136 72 17; w=1224]
   13. a                                            "Home"  [box=1466 48 39 17; w=663]
   14. a                                            "Home"  [box=294 136 39 17; w=663]

  ℹ️  The live page is still accessible — use `eval`, `snapshot`, or `click` to continue interacting.
  💡 Try these next:
    Use `get all text` to extract visible text, or `get all attr <name>` for attribute values.
    The SQL variant lets you query with full expressive power (joins, filters, aggregates).
     htmlsnapshot get text "h1" --limit 5   # page heading
     htmlsnapshot get all text "a" --limit 20  # link texts
     htmlsnapshot get attr "img[src]" src --limit 20  # image URLs
     htmlsnapshot get attr "a[href]" href --limit 20  # link URLs
     htmlsnapshot get attr "img[src]:expr(width > 200 && height > 200)" src --limit 20  # large images only
     htmlsnapshot inspect  # discover recurring patterns
     htmlsnapshot query --sql "SELECT DOM_TEXT(DOM) AS text FROM DOM_LOAD_AND_SELECT(@url, 'a')"
---GET TITLE---
4K OLED TV 55

codex
AC2 verified. Moving to AC3 (all matches on listing page).
  ✓ Verify environment, CLI help, and SKILL.md documentation
  ✓ Ensure MockSite running and LLM key configured for extract
  ✓ AC1: Interact-then-extract on form-filling.html
  ✓ AC2: Static page single field via htmlsnapshot get text
  → AC3: Static page all matches via htmlsnapshot get all text
  • AC4: Correlated multi-field rows via htmlsnapshot query X-SQL
  • AC5: Dynamic page logic via eval --json
  • AC6: Natural-language extraction via extract
  • AC7: High-volume extraction via crawl --sql seed file
  • Compile task result, execution trace, and issue report
exec
"C:\\Program Files\\PowerShell\\7\\pwsh.exe" -Command 'browser4-cli goto "http://localhost:18080/ec/b?node=1292115012"; browser4-cli htmlsnapshot' in D:\workspace\Browser4\Browser4-4.13
 succeeded in 4768ms:
Using existing session DEFAULT (current page: http://localhost:18080/ec/dp/B0E000001).
Navigated to http://localhost:18080/ec/b?node=1292115012
Page loaded. Use `wait --load networkidle` if content appears incomplete (e.g. async-rendered SPAs).
### Page
- Page URL: http://localhost:18080/ec/b?node=1292115012
- Page Title: Category: Electronics
### Snapshot
[Snapshot](D:\workspace\Browser4\Browser4-4.13\.browser4-cli\snapshot\snapshot-2026-08-14T07-26-26-060Z.yml)
Snapshot: "Category: Electronics"
http://localhost:18080/ec/b?node=1292115012 · 9 KB · text/html · captured 2026-08-14T07:26:26.166Z
7 images · 9 links · 22 interactive elements

### Interactive Elements
  Buttons (2):
    2. button.shop-btn                              "SHOP NOW →"  [box=286 185 123 35; w=1004305]
    3. button.search-btn                            "🔍"  [box=986 10 38 30; w=1001140]

  Inputs (11):
    4. input#search-box                              [box=719 10 262 27; w=1007074]
    5. select                                       "All"  [box=677 10 37 30; w=1001110]
    6. input                                         [box=19 262 13 13; w=1000169]
    7. input                                         [box=19 282 13 13; w=1000169]
    8. input                                         [box=19 357 13 13; w=1000169]
    9. input                                         [box=19 431 13 13; w=1000169]
   10. input                                         [box=19 451 13 13; w=1000169]
   11. input                                         [box=19 471 13 13; w=1000169]
   12. input                                         [box=19 491 13 13; w=1000169]
   13. input                                         [box=19 511 13 13; w=1000169]
   14. input                                         [box=19 531 13 13; w=1000169]

  Links (9):
   15. #product-B0E000002 a.product-link            "Wireless Noise-Cancelling Headphones"  [box=483 321 170 190; w=32300]
   16. #product-B0E000003 a.product-link            "Portable Bluetooth Speaker"  [box=690 321 170 190; w=32300]
   17. #product-B0E000001 a.product-link            "4K OLED TV 55"  [box=276 321 170 172; w=29240]
   18. #product-B0E000004 a.product-link            "Smartphone 128GB"  [box=897 321 170 172; w=29240]
   19. #product-B0E000005 a.product-link            "USB-C Hub 7-in-1"  [box=1104 321 170 172; w=29240]
   20. #product-B0E000006 a.product-link            "Wireless Mouse"  [box=1311 321 170 172; w=29240]
   21. a                                            "Returns & Orders"  [box=1751 17 112 17; w=1904]
   22. a                                            "Hello, sign in"  [box=1650 17 82 17; w=1394]
   23. a                                            "Cart"  [box=1883 17 27 17; w=459]

  ℹ️  The live page is still accessible — use `eval`, `snapshot`, or `click` to continue interacting.
  💡 Try these next:
    Use `get all text` to extract visible text, or `get all attr <name>` for attribute values.
    The SQL variant lets you query with full expressive power (joins, filters, aggregates).
     htmlsnapshot get text "h1" --limit 5   # page heading
     htmlsnapshot get all text "a" --limit 20  # link texts
     htmlsnapshot get attr "img[src]" src --limit 20  # image URLs
     htmlsnapshot get attr "a[href]" href --limit 20  # link URLs
     htmlsnapshot get attr "img[src]:expr(width > 200 && height > 200)" src --limit 20  # large images only
     htmlsnapshot inspect  # discover recurring patterns
     htmlsnapshot query --sql "SELECT DOM_TEXT(DOM) AS text FROM DOM_LOAD_AND_SELECT(@url, 'a')"
💡 Tip: Try `htmlsnapshot get text "h1"` to extract the page heading, or `htmlsnapshot inspect` to discover CSS selectors

exec
"C:\\Program Files\\PowerShell\\7\\pwsh.exe" -Command "browser4-cli htmlsnapshot get all text \"[class*='product-title']\"" in D:\workspace\Browser4\Browser4-4.13
 succeeded in 340ms:
["4K OLED TV 55","Wireless Noise-Cancelling Headphones","Portable Bluetooth Speaker","Smartphone 128GB","USB-C Hub 7-in-1","Wireless Mouse"]

codex
AC3 verified — all 6 titles returned. Now AC4: first let me confirm the product-card structure in the HTML.
exec
"C:\\Program Files\\PowerShell\\7\\pwsh.exe" -Command 'browser4-cli htmlsnapshot grep "product-card" -i --no-line-number' in D:\workspace\Browser4\Browser4-4.13
 succeeded in 345ms:
# htmlsnapshot snapshot captured at 2026-08-14T07:26:31Z
      <div class="product-card" id="product-B0E000001" data-category-id="1292115012" vi="266 310 191 277"> <a class="product-link" href="/ec/dp/B0E000001" style="text-decoration:none;color:inherit;display:block;" vi="276 321 170 172"> <img src="https://picsum.photos/seed/1250857624/200/140" alt="4K OLED TV 55" class="product-img" vi="276 321 170 140"> 
--
      <div class="product-card" id="product-B0E000002" data-category-id="1292115012" vi="473 310 191 277"> <a class="product-link" href="/ec/dp/B0E000002" style="text-decoration:none;color:inherit;display:block;" vi="483 321 170 190"> <img src="https://picsum.photos/seed/1250857625/200/140" alt="Wireless Noise-Cancelling Headphones" class="product-img" vi="483 321 170 140"> 
--
      <div class="product-card" id="product-B0E000003" data-category-id="1292115012" vi="680 310 191 277"> <a class="product-link" href="/ec/dp/B0E000003" style="text-decoration:none;color:inherit;display:block;" vi="690 321 170 190"> <img src="https://picsum.photos/seed/1250857626/200/140" alt="Portable Bluetooth Speaker" class="product-img" vi="690 321 170 140"> 
--
      <div class="product-card" id="product-B0E000004" data-category-id="1292115012" vi="886 310 191 277"> <a class="product-link" href="/ec/dp/B0E000004" style="text-decoration:none;color:inherit;display:block;" vi="897 321 170 172"> <img src="https://picsum.photos/seed/1250857627/200/140" alt="Smartphone 128GB" class="product-img" vi="897 321 170 140"> 
--
      <div class="product-card" id="product-B0E000005" data-category-id="1292115012" vi="1093 310 191 277"> <a class="product-link" href="/ec/dp/B0E000005" style="text-decoration:none;color:inherit;display:block;" vi="1104 321 170 172"> <img src="https://picsum.photos/seed/1250857628/200/140" alt="USB-C Hub 7-in-1" class="product-img" vi="1104 321 170 140"> 
--
      <div class="product-card" id="product-B0E000006" data-category-id="1292115012" vi="1300 310 191 277"> <a class="product-link" href="/ec/dp/B0E000006" style="text-decoration:none;color:inherit;display:block;" vi="1311 321 170 172"> <img src="https://picsum.photos/seed/1250857629/200/140" alt="Wireless Mouse" class="product-img" vi="1311 321 170 140"> 

codex
Cards confirmed. Let me check the price element structure before writing the query.
exec
"C:\\Program Files\\PowerShell\\7\\pwsh.exe" -Command 'browser4-cli htmlsnapshot grep "product-price|product-title" -i --no-line-number | Select-Object -First 20' in D:\workspace\Browser4\Browser4-4.13
 succeeded in 371ms:
# htmlsnapshot snapshot captured at 2026-08-14T07:26:33Z
        <div class="product-title" vi="276 475 170 18">
--
       <div class="product-price" id="product-price-B0E000001" lz="1" vi="276 503 170 19">
--
        <div class="product-title" vi="483 475 170 36">
--
       <div class="product-price" id="product-price-B0E000002" lz="1" vi="483 521 170 19">
--
        <div class="product-title" vi="690 475 170 36">
--
       <div class="product-price" id="product-price-B0E000003" lz="1" vi="690 521 170 19">
--
        <div class="product-title" vi="897 475 170 18">
--
       <div class="product-price" id="product-price-B0E000004" lz="1" vi="897 503 170 19">
--
        <div class="product-title" vi="1104 475 170 18">
--
       <div class="product-price" id="product-price-B0E000005" lz="1" vi="1104 503 170 19">

codex
Writing the X-SQL query file and running it.
exec
"C:\\Program Files\\PowerShell\\7\\pwsh.exe" -Command "@'
SELECT
  DOM_BASE_URI(DOM) AS url,
  DOM_FIRST_TEXT(DOM, '.product-title') AS title,
  DOM_FIRST_TEXT(DOM, '.product-price') AS price,
  DOM_FIRST_HREF(DOM, 'a.product-link') AS detail_url
FROM DOM_LOAD_AND_SELECT(@url, 'div[class*=\"product-card\"]')
'@ | Set-Content -Path .test-sessions/ac4-query.sql -Encoding UTF8; Get-Content .test-sessions/ac4-query.sql" in D:\workspace\Browser4\Browser4-4.13
 succeeded in 312ms:
SELECT
  DOM_BASE_URI(DOM) AS url,
  DOM_FIRST_TEXT(DOM, '.product-title') AS title,
  DOM_FIRST_TEXT(DOM, '.product-price') AS price,
  DOM_FIRST_HREF(DOM, 'a.product-link') AS detail_url
FROM DOM_LOAD_AND_SELECT(@url, 'div[class*="product-card"]')

exec
"C:\\Program Files\\PowerShell\\7\\pwsh.exe" -Command 'browser4-cli htmlsnapshot query "http://localhost:18080/ec/b?node=1292115012" --sql "@.test-sessions/ac4-query.sql"' in D:\workspace\Browser4\Browser4-4.13
 succeeded in 1905ms:
{"id":"a7c2c758-b582-4faf-a99f-398e03c31a11","statusCode":200,"pageStatusCode":200,"pageContentBytes":10148,"isDone":true,"resultSet":[{"url":"http://localhost:18080/ec/b?node=1292115012","title":"4K OLED TV 55","price":"$899.99","detail_url":"http://localhost:18080/ec/dp/B0E000001"},{"url":"http://localhost:18080/ec/b?node=1292115012","title":"Wireless Noise-Cancelling Headphones","price":"$199.99","detail_url":"http://localhost:18080/ec/dp/B0E000002"},{"url":"http://localhost:18080/ec/b?node=1292115012","title":"Portable Bluetooth Speaker","price":"$49.99","detail_url":"http://localhost:18080/ec/dp/B0E000003"},{"url":"http://localhost:18080/ec/b?node=1292115012","title":"Smartphone 128GB","price":"$599.00","detail_url":"http://localhost:18080/ec/dp/B0E000004"},{"url":"http://localhost:18080/ec/b?node=1292115012","title":"USB-C Hub 7-in-1","price":"$29.95","detail_url":"http://localhost:18080/ec/dp/B0E000005"},{"url":"http://localhost:18080/ec/b?node=1292115012","title":"Wireless Mouse","price":"$24.99","detail_url":"http://localhost:18080/ec/dp/B0E000006"}],"event":"onLoaded","message":null,"createdTime":"2026-08-14T07:26:41.119656400Z","startedTime":"2026-08-14T07:26:42.132040500Z","lastModifiedTime":"2026-08-14T07:26:42.132040500Z","finishTime":"2026-08-14T07:26:42.132040500Z","status":"OK"}

codex
AC4 complete — 6 aligned rows. Moving to AC5 (eval --json on interactive page).
  ✓ Verify environment, CLI help, and SKILL.md documentation
  ✓ Ensure MockSite running and LLM key configured for extract
  ✓ AC1: Interact-then-extract on form-filling.html
  ✓ AC2: Static page single field via htmlsnapshot get text
  ✓ AC3: Static page all matches via htmlsnapshot get all text
  ✓ AC4: Correlated multi-field rows via htmlsnapshot query X-SQL
  → AC5: Dynamic page logic via eval --json
  • AC6: Natural-language extraction via extract
  • AC7: High-volume extraction via crawl --sql seed file
  • Compile task result, execution trace, and issue report
exec
"C:\\Program Files\\PowerShell\\7\\pwsh.exe" -Command 'browser4-cli goto "http://localhost:18080/generated/interactive-1.html"' in D:\workspace\Browser4\Browser4-4.13
 succeeded in 4654ms:
Using existing session DEFAULT (current page: http://localhost:18080/ec/b?node=1292115012).
Navigated to http://localhost:18080/generated/interactive-1.html
Page loaded. Use `wait --load networkidle` if content appears incomplete (e.g. async-rendered SPAs).
### Page
- Page URL: http://localhost:18080/generated/interactive-1.html
- Page Title: Interactive Single Page
### Snapshot
[Snapshot](D:\workspace\Browser4\Browser4-4.13\.browser4-cli\snapshot\snapshot-2026-08-14T07-26-52-981Z.yml)
💡 Tip: Try `htmlsnapshot get text "h1"` to extract the page heading, or `htmlsnapshot inspect` to discover CSS selectors

exec
"C:\\Program Files\\PowerShell\\7\\pwsh.exe" -Command "@'
({
  pageTitle: document.title,
  buttonCount: document.querySelectorAll('button').length,
  linkCount: document.querySelectorAll('a').length,
  formCount: document.querySelectorAll('form').length,
  headings: Array.from(document.querySelectorAll('h1, h2, h3')).map(h => h.textContent.trim()).filter(t => t.length > 0)
})
'@ | Set-Content -Path .test-sessions/ac5-eval.js -Encoding UTF8; browser4-cli eval --file .test-sessions/ac5-eval.js --json" in D:\workspace\Browser4\Browser4-4.13
 succeeded in 425ms:
{"status":"ok","command":"eval","output":{"result":"{\"pageTitle\":\"Interactive Single Page\",\"buttonCount\":2,\"linkCount\":0,\"formCount\":0,\"headings\":[\"Welcome to the Interactive Page\",\"📋 User Information\",\"📊 Preferences\",\"🧮 Quick Calculator\",\"🎯 Dynamic Toggle\"]}","expression":"({\n  pageTitle: document.title,\n  buttonCount: document.querySelectorAll('button').length,\n  linkCount: document.querySelectorAll('a').length,\n  formCount: document.querySelectorAll('form').length,\n  headings: Array.from(document.querySelectorAll('h1, h2, h3')).map(h => h.textContent.trim()).filter(t => t.length > 0)\n})"}}

codex
AC5 returned structured data. Let me verify the computed values against the actual page.
exec
"C:\\Program Files\\PowerShell\\7\\pwsh.exe" -Command 'browser4-cli htmlsnapshot; Write-Output "---buttons---"; browser4-cli htmlsnapshot get all attr "button" class; Write-Output "---links---"; browser4-cli htmlsnapshot get all text "a"; Write-Output "---forms---"; browser4-cli htmlsnapshot grep "form" -i -c --no-line-number' in D:\workspace\Browser4\Browser4-4.13
 succeeded in 556ms:
Snapshot: "Interactive Single Page"
http://localhost:18080/generated/interactive-1.html · 7 KB · text/html · captured 2026-08-14T07:26:59.083Z
6 interactive elements

### Interactive Elements
  Buttons (2):
    2. #toggleSection button#toggleMessageButton.btn.btn-toggle "Toggle Message"  [box=560 1230 137 39; w=1005343]
    3. #calculatorSection button#addButton.btn.btn-add "Add"  [box=779 988 48 39; w=1001872]

  Inputs (4):
    4. #userInformation input#name.input.input-name  [box=560 381 215 39; w=1008385]
    5. #calculatorSection input#num1.input.input-number  [box=560 900 215 39; w=1008385]
    6. #calculatorSection input#num2.input.input-number  [box=560 988 215 39; w=1008385]
    7. #preferences select#colorSelect.select.select-color "-- Choose -- Light Blue"  [box=560 649 123 37; w=1004551]

  ℹ️  The live page is still accessible — use `eval`, `snapshot`, or `click` to continue interacting.
  💡 Try these next:
    Use `get all text` to extract visible text, or `get all attr <name>` for attribute values.
    The SQL variant lets you query with full expressive power (joins, filters, aggregates).
     htmlsnapshot get text "h1" --limit 5   # page heading
     htmlsnapshot get all text "a" --limit 20  # link texts
     htmlsnapshot get attr "img[src]" src --limit 20  # image URLs
     htmlsnapshot get attr "a[href]" href --limit 20  # link URLs
     htmlsnapshot inspect  # discover recurring patterns
     htmlsnapshot query --sql "SELECT DOM_TEXT(DOM) AS text FROM DOM_LOAD_AND_SELECT(@url, 'a')"
---buttons---
["btn btn-add","btn btn-toggle"]
---links---
[]
No elements matched "a".
  The snapshot may be stale — it reflects the DOM at capture time. If the page has changed since the last `htmlsnapshot`, re-capture with `htmlsnapshot` first.
  Verify the selector with `htmlsnapshot grep "a"`, or discover valid selectors with `htmlsnapshot inspect`.
---forms---
3

codex
Link and button counts verified. The grep count of 3 was line-based noise — let me confirm the actual `<form>` tag count.
exec
"C:\\Program Files\\PowerShell\\7\\pwsh.exe" -Command 'browser4-cli htmlsnapshot grep "<form" -i --no-line-number; Write-Output "---"; browser4-cli htmlsnapshot grep "form" -i --no-line-number | Select-Object -First 10' in D:\workspace\Browser4\Browser4-4.13
 succeeded in 398ms:
0 matches found
---
# htmlsnapshot snapshot captured at 2026-08-14T07:27:03Z
  <section id="userInformation" data-testid="userInformation" class="section section-user-info" data-role="section" data-component="user-info-section" vi="528 216 864 252"> 
   <h2 vi="560 248 800 38">📋 User Information</h2> 
--
  <input type="hidden" id="PulsarMetaInformation" domain="localhost" view-port="1920x1080" date-time="2026/8/14 15:26:59" timestamp="1786692419080" normalizeduri="http://localhost:18080/generated/interactive-1.html" href="http://localhost:18080/generated/interactive-1.html" normurl="http://localhost:18080/generated/interactive-1.html" normalizedurl="http://localhost:18080/generated/interactive-1.html" label="" taskid="" tasktime="1970-01-01T00:00:00Z">

codex
AC5 fully verified. Moving to AC6 (natural-language extraction).
  ✓ Verify environment, CLI help, and SKILL.md documentation
  ✓ Ensure MockSite running and LLM key configured for extract
  ✓ AC1: Interact-then-extract on form-filling.html
  ✓ AC2: Static page single field via htmlsnapshot get text
  ✓ AC3: Static page all matches via htmlsnapshot get all text
  ✓ AC4: Correlated multi-field rows via htmlsnapshot query X-SQL
  ✓ AC5: Dynamic page logic via eval --json
  → AC6: Natural-language extraction via extract
  • AC7: High-volume extraction via crawl --sql seed file
  • Compile task result, execution trace, and issue report
exec
"C:\\Program Files\\PowerShell\\7\\pwsh.exe" -Command 'browser4-cli goto "http://localhost:18080/ec/dp/B0E000002"' in D:\workspace\Browser4\Browser4-4.13
 succeeded in 4681ms:
Using existing session DEFAULT (current page: http://localhost:18080/generated/interactive-1.html).
Navigated to http://localhost:18080/ec/dp/B0E000002
Page loaded. Use `wait --load networkidle` if content appears incomplete (e.g. async-rendered SPAs).
### Page
- Page URL: http://localhost:18080/ec/dp/B0E000002
- Page Title: Product: Wireless Noise-Cancelling Headphones
### Snapshot
[Snapshot](D:\workspace\Browser4\Browser4-4.13\.browser4-cli\snapshot\snapshot-2026-08-14T07-27-12-740Z.yml)
💡 Tip: Try `htmlsnapshot get text "h1"` to extract the page heading, or `htmlsnapshot inspect` to discover CSS selectors

exec
"C:\\Program Files\\PowerShell\\7\\pwsh.exe" -Command 'browser4-cli extract "Return the product title, displayed price, rating, and the top three feature bullets as JSON." --stdout' in D:\workspace\Browser4\Browser4-4.13
 succeeded in 68781ms:
{"type":"ai.platon.pulsar.agentic.ExtractResult","description":"{\"title\":\"Wireless Noise-Cancelling Headphones\",\"price\":\"$199.99\",\"rating\":\"4.4\",\"feature_bullets\":[\"Bluetooth 5.2\",\"30h battery\"],\"metadata\":{\"progress\":\"\",\"completed\":false},\"inputToken\":1929,\"outputToken\":4020,\"totalToken\":5949,\"inferenceTimeMillis\":67876}"}

codex
Extraction returned structured data. Let me verify against the actual page content.
exec
"C:\\Program Files\\PowerShell\\7\\pwsh.exe" -Command 'browser4-cli htmlsnapshot; Write-Output "---TITLE---"; browser4-cli htmlsnapshot get text "#productTitle"; Write-Output "---PRICE---"; browser4-cli htmlsnapshot get text "#product-price"; Write-Output "---RATING---"; browser4-cli htmlsnapshot grep "rating" -i --no-line-number | Select-Object -First 5; Write-Output "---FEATURES---"; browser4-cli htmlsnapshot get text "#product-features"' in D:\workspace\Browser4\Browser4-4.13
 succeeded in 848ms:
Snapshot: "Product: Wireless Noise-Cancelling Headphones"
http://localhost:18080/ec/dp/B0E000002 · 15 KB · text/html · captured 2026-08-14T07:28:26.305Z
1 images · 11 links · 13 interactive elements

### Interactive Elements
  Buttons (2):
    2. #product-page button.add-cart                "Add to cart"  [box=1325 336 282 41; w=1011562]
    3. #product-page button.buy-now                 "Buy now"  [box=1325 387 282 41; w=1011562]

  Links (11):
    4. a                                            "Portable Bluetooth Speaker"  [box=657 1110 210 18; w=3780]
    5. a                                            "Smartphone 128GB"  [box=985 1110 148 18; w=2664]
    6. a                                            "USB-C Hub 7-in-1"  [box=1312 1110 133 18; w=2394]
    7. a                                            "4K OLED TV 55"  [box=330 1110 116 18; w=2088]
    8. a                                            "Customer service"  [box=1765 48 115 17; w=1955]
    9. a                                            "Back to category"  [box=1526 48 110 17; w=1870]
   10. a                                            "Today's deals"  [box=1656 48 89 17; w=1513]
   11. #product-category-link a                     "Electronics"  [box=691 269 78 18; w=1404]
   12. a                                            "Electronics"  [box=346 136 72 17; w=1224]
   13. a                                            "Home"  [box=1466 48 39 17; w=663]
   14. a                                            "Home"  [box=294 136 39 17; w=663]

  ℹ️  The live page is still accessible — use `eval`, `snapshot`, or `click` to continue interacting.
  💡 Try these next:
    Use `get all text` to extract visible text, or `get all attr <name>` for attribute values.
    The SQL variant lets you query with full expressive power (joins, filters, aggregates).
     htmlsnapshot get text "h1" --limit 5   # page heading
     htmlsnapshot get all text "a" --limit 20  # link texts
     htmlsnapshot get attr "img[src]" src --limit 20  # image URLs
     htmlsnapshot get attr "a[href]" href --limit 20  # link URLs
     htmlsnapshot get attr "img[src]:expr(width > 200 && height > 200)" src --limit 20  # large images only
     htmlsnapshot inspect  # discover recurring patterns
     htmlsnapshot query --sql "SELECT DOM_TEXT(DOM) AS text FROM DOM_LOAD_AND_SELECT(@url, 'a')"
---TITLE---
Wireless Noise-Cancelling Headphones
---PRICE---
$199.99
---RATING---
# htmlsnapshot snapshot captured at 2026-08-14T07:28:26Z
     <div class="rating-line" vi="691 304 576 19"> <span id="product-rating" vi="691 304 22 19">4.4</span> <span id="product-rating-count" vi="725 304 27 19">312</span> <span vi="764 304 129 19">2,274 recent visits</span> 
---FEATURES---
Bluetooth 5.2 30h battery

codex
AC6 verified — title, price, rating all match; the page only has two feature bullets, so the extraction is faithful. Now AC7 (high-volume crawl).
  ✓ Verify environment, CLI help, and SKILL.md documentation
  ✓ Ensure MockSite running and LLM key configured for extract
  ✓ AC1: Interact-then-extract on form-filling.html
  ✓ AC2: Static page single field via htmlsnapshot get text
  ✓ AC3: Static page all matches via htmlsnapshot get all text
  ✓ AC4: Correlated multi-field rows via htmlsnapshot query X-SQL
  ✓ AC5: Dynamic page logic via eval --json
  ✓ AC6: Natural-language extraction via extract
  → AC7: High-volume extraction via crawl --sql seed file
  • Compile task result, execution trace, and issue report
exec
"C:\\Program Files\\PowerShell\\7\\pwsh.exe" -Command "@'
http://localhost:18080/ec/dp/B0E000001
http://localhost:18080/ec/dp/B0E000002
http://localhost:18080/ec/dp/B0E000003
http://localhost:18080/ec/dp/B0E000004
http://localhost:18080/ec/dp/B0E000005
'@ | Set-Content -Path .test-sessions/ac7-seed.txt -Encoding UTF8; @'
SELECT
  DOM_BASE_URI(DOM) AS url,
  DOM_FIRST_TEXT(DOM, '#productTitle') AS title,
  DOM_FIRST_TEXT(DOM, '#product-price') AS price
FROM DOM_LOAD_AND_SELECT(@url, 'body')
'@ | Set-Content -Path .test-sessions/ac7-query.sql -Encoding UTF8; Write-Output \"seed:\"; Get-Content .test-sessions/ac7-seed.txt; Write-Output \"query:\"; Get-Content .test-sessions/ac7-query.sql" in D:\workspace\Browser4\Browser4-4.13
 succeeded in 369ms:
seed:
http://localhost:18080/ec/dp/B0E000001
http://localhost:18080/ec/dp/B0E000002
http://localhost:18080/ec/dp/B0E000003
http://localhost:18080/ec/dp/B0E000004
http://localhost:18080/ec/dp/B0E000005
query:
SELECT
  DOM_BASE_URI(DOM) AS url,
  DOM_FIRST_TEXT(DOM, '#productTitle') AS title,
  DOM_FIRST_TEXT(DOM, '#product-price') AS price
FROM DOM_LOAD_AND_SELECT(@url, 'body')

exec
"C:\\Program Files\\PowerShell\\7\\pwsh.exe" -Command 'browser4-cli crawl --seed-file ".test-sessions/ac7-seed.txt" --depth 0 --sql "@.test-sessions/ac7-query.sql" --format table --refresh' in D:\workspace\Browser4\Browser4-4.13
 succeeded in 32619ms:
Crawl task submitted: 37147ee8-373d-4d64-b052-64c8d2d108d4
  URLs: 5
  X-SQL extraction: enabled
Waiting for crawl to complete (task 37147ee8-373d-4d64-b052-64c8d2d108d4, 5 URLs). Use --background for long-running crawls.
Crawling... waiting for first page (6s elapsed, 5 URLs queued)
Crawling... 1 pages found so far
Crawling... 1 pages found so far
Crawling... 1 pages found so far
Crawling... 2 pages found so far
Crawling... 2/5 seeds done, 2 pages found, 2 rows extracted (http://localhost:18080/ec/d... / Wireless Noise-Cancelling H...) (16s elapsed)
Crawling... 2 pages found so far
Crawling... 2 pages found so far
Crawling... 3 pages found so far
Crawling... 3 pages found so far
Crawling... 3 pages found so far
Crawling... 4/5 seeds done, 4 pages found, 4 rows extracted (http://localhost:18080/ec/d... / Smartphone 128GB) (26s elapsed)
Crawling... 4 pages found so far
Crawling... 4 pages found so far
Crawling... 4 pages found so far
5 pages crawled, 5 rows extracted.

  url                                    | title                                | price  
  ---------------------------------------+--------------------------------------+--------
  http://localhost:18080/ec/dp/B0E000001 | 4K OLED TV 55                        | $899.99
  http://localhost:18080/ec/dp/B0E000002 | Wireless Noise-Cancelling Headphones | $199.99
  http://localhost:18080/ec/dp/B0E000003 | Portable Bluetooth Speaker           | $49.99 
  http://localhost:18080/ec/dp/B0E000004 | Smartphone 128GB                     | $599.00
  http://localhost:18080/ec/dp/B0E000005 | USB-C Hub 7-in-1                     | $29.95 


codex
All 7 ACs executed successfully. Before writing the report, let me verify a few suspected issues with evidence: the `--all` semantics in the SKILL template, the CLI state dir location, and where `extract` writes files by default.
exec
"C:\\Program Files\\PowerShell\\7\\pwsh.exe" -Command "browser4-cli goto \"http://localhost:18080/ec/b?node=1292115012\" | Out-Null; browser4-cli htmlsnapshot | Out-Null; Write-Output \"=== get text ... --all (as in SKILL copy-paste template) ===\"; browser4-cli htmlsnapshot get text \"[class*='product-title']\" --all; Write-Output \"=== get all text (documented all-matches form) ===\"; browser4-cli htmlsnapshot get all text \"[class*='product-title']\"" in D:\workspace\Browser4\Browser4-4.13
exec
"C:\\Program Files\\PowerShell\\7\\pwsh.exe" -Command 'Get-ChildItem -Force -Directory | Where-Object { $_.Name -like ".browser4*" -or $_.Name -like "*test-session*" } | Select-Object Name, FullName; Write-Output "---state dir contents---"; Get-ChildItem -Force ".browser4-cli" -ErrorAction SilentlyContinue | Select-Object Name; Write-Output "---docs claim fallback is ./.browser4-cli-state---"; Test-Path ".browser4-cli-state"' in D:\workspace\Browser4\Browser4-4.13
 succeeded in 378ms:

Name                FullName
----                --------
.browser4-cli       D:\workspace\Browser4\Browser4-4.13\.browser4-cli
.browser4-cli-state D:\workspace\Browser4\Browser4-4.13\.browser4-cli-state
.test-sessions      D:\workspace\Browser4\Browser4-4.13\.test-sessions
---state dir contents---
snapshot            
---docs claim fallback is ./.browser4-cli-state---
True


 succeeded in 5050ms:
=== get text ... --all (as in SKILL copy-paste template) ===
4K OLED TV 55
=== get all text (documented all-matches form) ===
["4K OLED TV 55","Wireless Noise-Cancelling Headphones","Portable Bluetooth Speaker","Smartphone 128GB","USB-C Hub 7-in-1","Wireless Mouse"]
💡 Tip: Try `htmlsnapshot get text "h1"` to extract the page heading, or `htmlsnapshot inspect` to discover CSS selectors

codex
Two issues confirmed so far. Let me dig into the state dir split and extract's default output location in the source.
exec
"C:\\Program Files\\PowerShell\\7\\pwsh.exe" -Command 'Write-Output "=== .browser4-cli ==="; Get-ChildItem -Force ".browser4-cli" -Recurse -Depth 2 -ErrorAction SilentlyContinue | Select-Object FullName; Write-Output "=== .browser4-cli-state ==="; Get-ChildItem -Force ".browser4-cli-state" -Recurse -Depth 2 -ErrorAction SilentlyContinue | Select-Object FullName' in D:\workspace\Browser4\Browser4-4.13
exec
"C:\\Program Files\\PowerShell\\7\\pwsh.exe" -Command "rg -n \"browser4-cli-state|\\.browser4-cli|snapshot_dir|snapshot-dir\" cli/browser4-cli/src --glob \"*.rs\" | Select-Object -First 40" in D:\workspace\Browser4\Browser4-4.13
 succeeded in 393ms:
cli/browser4-cli/src\tips.rs:355:        text: "Set BROWSER4_CLI_STATE_DIR to a writable path in sandboxed environments — the CLI falls back to ./.browser4-cli-state when ~/.browser4 is not writable",
cli/browser4-cli/src\snapshot.rs:9:pub const SNAPSHOT_DIR: [&str; 2] = [".browser4-cli", "snapshot"];
cli/browser4-cli/src\snapshot.rs:16:pub fn snapshot_dir() -> PathBuf {
cli/browser4-cli/src\snapshot.rs:22:    snapshot_dir().join("archive")
cli/browser4-cli/src\snapshot.rs:59:        snapshot_dir().join(&name)
cli/browser4-cli/src\snapshot.rs:89:    let snap_dir = snapshot_dir();
cli/browser4-cli/src\snapshot.rs:169:    fn test_snapshot_dir_uses_os_native_segments() {
cli/browser4-cli/src\snapshot.rs:170:        assert_eq!(snapshot_dir(), Path::new(".browser4-cli").join("snapshot"));
cli/browser4-cli/src\snapshot.rs:174:    fn test_resolve_output_path_uses_snapshot_dir() {
cli/browser4-cli/src\snapshot.rs:177:        assert!(path.ends_with(snapshot_dir().join("snap.yml")));
cli/browser4-cli/src\snapshot.rs:187:            !path.ends_with(snapshot_dir().join("subdir").join("my-shot.png")),
cli/browser4-cli/src\snapshot.rs:207:    fn test_resolve_output_path_bare_filename_still_uses_snapshot_dir() {
cli/browser4-cli/src\snapshot.rs:211:        assert!(path.ends_with(snapshot_dir().join("bare-file.png")));
cli/browser4-cli/src\state.rs:220:const FALLBACK_STATE_DIR_NAME: &str = ".browser4-cli-state";
cli/browser4-cli/src\state.rs:226:/// Workspace-relative fallback directory: `./.browser4-cli-state`.
cli/browser4-cli/src\state.rs:295:/// after a fallback write landed in `./.browser4-cli-state`), the fallback
cli/browser4-cli/src\state.rs:350:/// relative directory (`./.browser4-cli-state`) and prints a one-time warning
cli/browser4-cli/src\main.rs:4700:    let snap_dir = snapshot::snapshot_dir();
cli/browser4-cli/src\main.rs:4840:    let snap_dir = snapshot::snapshot_dir();
cli/browser4-cli/src\main.rs:6474:            snapshot::snapshot_dir().join(name)
cli/browser4-cli/src\help.rs:248:        "override CLI session state directory (default: ~/.browser4); falls back to ./.browser4-cli-state when unwritable",
cli/browser4-cli/src\help.rs:407:            "description": "Override CLI session state directory (default: ~/.browser4); falls back to ./.browser4-cli-state when unwritable"

 succeeded in 486ms:
=== .browser4-cli ===

FullName
--------
D:\workspace\Browser4\Browser4-4.13\.browser4-cli\snapshot
D:\workspace\Browser4\Browser4-4.13\.browser4-cli\snapshot\archive
D:\workspace\Browser4\Browser4-4.13\.browser4-cli\snapshot\extract-2026-08-04T18-42-46-164Z.txt
D:\workspace\Browser4\Browser4-4.13\.browser4-cli\snapshot\extract-2026-08-13T09-13-35-240Z.txt
D:\workspace\Browser4\Browser4-4.13\.browser4-cli\snapshot\extract-2026-08-13T09-14-12-144Z.txt
D:\workspace\Browser4\Browser4-4.13\.browser4-cli\snapshot\extract-2026-08-14T07-28-23-179Z.txt
D:\workspace\Browser4\Browser4-4.13\.browser4-cli\snapshot\htmlsnapshot-summary-2026-08-04T19-05-03-452Z.yml
D:\workspace\Browser4\Browser4-4.13\.browser4-cli\snapshot\screenshot-2026-08-04T17-31-15-162Z.png
D:\workspace\Browser4\Browser4-4.13\.browser4-cli\snapshot\screenshot-2026-08-04T17-31-28-483Z.png
D:\workspace\Browser4\Browser4-4.13\.browser4-cli\snapshot\screenshot-2026-08-04T18-00-21-473Z.png
D:\workspace\Browser4\Browser4-4.13\.browser4-cli\snapshot\snapshot-2026-08-04T19-04-12-036Z.yml
D:\workspace\Browser4\Browser4-4.13\.browser4-cli\snapshot\snapshot-2026-08-04T19-14-27-088Z.yml
D:\workspace\Browser4\Browser4-4.13\.browser4-cli\snapshot\snapshot-2026-08-04T19-18-54-820Z.yml
D:\workspace\Browser4\Browser4-4.13\.browser4-cli\snapshot\snapshot-2026-08-04T19-21-05-334Z.yml
D:\workspace\Browser4\Browser4-4.13\.browser4-cli\snapshot\snapshot-2026-08-04T19-22-48-849Z.yml
D:\workspace\Browser4\Browser4-4.13\.browser4-cli\snapshot\snapshot-2026-08-12T10-47-08-155Z.yml
D:\workspace\Browser4\Browser4-4.13\.browser4-cli\snapshot\snapshot-2026-08-12T10-47-26-057Z.yml
D:\workspace\Browser4\Browser4-4.13\.browser4-cli\snapshot\snapshot-2026-08-12T10-47-34-156Z.yml
D:\workspace\Browser4\Browser4-4.13\.browser4-cli\snapshot\snapshot-2026-08-12T10-47-43-433Z.yml
D:\workspace\Browser4\Browser4-4.13\.browser4-cli\snapshot\snapshot-2026-08-12T15-09-08-627Z.yml
D:\workspace\Browser4\Browser4-4.13\.browser4-cli\snapshot\snapshot-2026-08-12T15-09-20-172Z.yml
D:\workspace\Browser4\Browser4-4.13\.browser4-cli\snapshot\snapshot-2026-08-12T15-09-52-952Z.yml
D:\workspace\Browser4\Browser4-4.13\.browser4-cli\snapshot\snapshot-2026-08-12T19-35-34-158Z.yml
D:\workspace\Browser4\Browser4-4.13\.browser4-cli\snapshot\snapshot-2026-08-12T19-35-39-180Z.yml
D:\workspace\Browser4\Browser4-4.13\.browser4-cli\snapshot\snapshot-2026-08-12T19-37-10-781Z.yml
D:\workspace\Browser4\Browser4-4.13\.browser4-cli\snapshot\snapshot-2026-08-12T19-38-50-399Z.yml
D:\workspace\Browser4\Browser4-4.13\.browser4-cli\snapshot\snapshot-2026-08-12T19-39-00-866Z.yml
D:\workspace\Browser4\Browser4-4.13\.browser4-cli\snapshot\snapshot-2026-08-12T19-39-13-487Z.yml
D:\workspace\Browser4\Browser4-4.13\.browser4-cli\snapshot\snapshot-2026-08-12T19-39-26-689Z.yml
D:\workspace\Browser4\Browser4-4.13\.browser4-cli\snapshot\snapshot-2026-08-12T19-39-39-020Z.yml
D:\workspace\Browser4\Browser4-4.13\.browser4-cli\snapshot\snapshot-2026-08-12T19-39-50-573Z.yml
D:\workspace\Browser4\Browser4-4.13\.browser4-cli\snapshot\snapshot-2026-08-12T19-40-03-340Z.yml
D:\workspace\Browser4\Browser4-4.13\.browser4-cli\snapshot\snapshot-2026-08-12T19-40-15-593Z.yml
D:\workspace\Browser4\Browser4-4.13\.browser4-cli\snapshot\snapshot-2026-08-12T19-40-27-651Z.yml
D:\workspace\Browser4\Browser4-4.13\.browser4-cli\snapshot\snapshot-2026-08-12T19-40-37-993Z.yml
D:\workspace\Browser4\Browser4-4.13\.browser4-cli\snapshot\snapshot-2026-08-13T08-36-17-807Z.yml
D:\workspace\Browser4\Browser4-4.13\.browser4-cli\snapshot\snapshot-2026-08-13T08-39-11-520Z.yml
D:\workspace\Browser4\Browser4-4.13\.browser4-cli\snapshot\snapshot-2026-08-13T08-50-25-596Z.yml
D:\workspace\Browser4\Browser4-4.13\.browser4-cli\snapshot\snapshot-2026-08-13T08-51-33-863Z.yml
D:\workspace\Browser4\Browser4-4.13\.browser4-cli\snapshot\snapshot-2026-08-13T08-51-43-636Z.yml
D:\workspace\Browser4\Browser4-4.13\.browser4-cli\snapshot\snapshot-2026-08-13T08-55-03-930Z.yml
D:\workspace\Browser4\Browser4-4.13\.browser4-cli\snapshot\snapshot-2026-08-13T08-55-10-802Z.yml
D:\workspace\Browser4\Browser4-4.13\.browser4-cli\snapshot\snapshot-2026-08-13T08-55-17-558Z.yml
D:\workspace\Browser4\Browser4-4.13\.browser4-cli\snapshot\snapshot-2026-08-13T09-03-36-637Z.yml
D:\workspace\Browser4\Browser4-4.13\.browser4-cli\snapshot\snapshot-2026-08-13T09-03-43-527Z.yml
D:\workspace\Browser4\Browser4-4.13\.browser4-cli\snapshot\snapshot-2026-08-13T09-04-52-009Z.yml
D:\workspace\Browser4\Browser4-4.13\.browser4-cli\snapshot\snapshot-2026-08-13T09-05-04-196Z.yml
D:\workspace\Browser4\Browser4-4.13\.browser4-cli\snapshot\snapshot-2026-08-13T09-05-25-869Z.yml
D:\workspace\Browser4\Browser4-4.13\.browser4-cli\snapshot\snapshot-2026-08-13T09-05-55-301Z.yml
D:\workspace\Browser4\Browser4-4.13\.browser4-cli\snapshot\snapshot-2026-08-13T09-05-55-615Z.yml
D:\workspace\Browser4\Browser4-4.13\.browser4-cli\snapshot\snapshot-2026-08-13T09-05-55-921Z.yml
D:\workspace\Browser4\Browser4-4.13\.browser4-cli\snapshot\snapshot-2026-08-13T09-06-00-829Z.yml
D:\workspace\Browser4\Browser4-4.13\.browser4-cli\snapshot\snapshot-2026-08-13T09-06-01-119Z.yml
D:\workspace\Browser4\Browser4-4.13\.browser4-cli\snapshot\snapshot-2026-08-13T09-06-01-669Z.yml
D:\workspace\Browser4\Browser4-4.13\.browser4-cli\snapshot\snapshot-2026-08-13T09-06-01-962Z.yml
D:\workspace\Browser4\Browser4-4.13\.browser4-cli\snapshot\snapshot-2026-08-13T09-06-14-975Z.yml
D:\workspace\Browser4\Browser4-4.13\.browser4-cli\snapshot\snapshot-2026-08-13T09-07-08-599Z.yml
D:\workspace\Browser4\Browser4-4.13\.browser4-cli\snapshot\snapshot-2026-08-13T09-08-08-209Z.yml
D:\workspace\Browser4\Browser4-4.13\.browser4-cli\snapshot\snapshot-2026-08-13T09-11-46-523Z.yml
D:\workspace\Browser4\Browser4-4.13\.browser4-cli\snapshot\snapshot-2026-08-13T09-12-20-440Z.yml
D:\workspace\Browser4\Browser4-4.13\.browser4-cli\snapshot\snapshot-2026-08-13T09-21-48-266Z.yml
D:\workspace\Browser4\Browser4-4.13\.browser4-cli\snapshot\snapshot-2026-08-13T09-22-19-305Z.yml
D:\workspace\Browser4\Browser4-4.13\.browser4-cli\snapshot\snapshot-2026-08-13T09-22-23-906Z.yml
D:\workspace\Browser4\Browser4-4.13\.browser4-cli\snapshot\snapshot-2026-08-13T09-22-28-551Z.yml
D:\workspace\Browser4\Browser4-4.13\.browser4-cli\snapshot\snapshot-2026-08-13T09-29-47-299Z.yml
D:\workspace\Browser4\Browser4-4.13\.browser4-cli\snapshot\snapshot-2026-08-13T09-31-25-428Z.yml
D:\workspace\Browser4\Browser4-4.13\.browser4-cli\snapshot\snapshot-2026-08-14T06-52-59-259Z.yml
D:\workspace\Browser4\Browser4-4.13\.browser4-cli\snapshot\snapshot-2026-08-14T06-53-52-625Z.yml
D:\workspace\Browser4\Browser4-4.13\.browser4-cli\snapshot\snapshot-2026-08-14T06-57-19-272Z.yml
D:\workspace\Browser4\Browser4-4.13\.browser4-cli\snapshot\snapshot-2026-08-14T06-57-44-644Z.yml
D:\workspace\Browser4\Browser4-4.13\.browser4-cli\snapshot\snapshot-2026-08-14T07-00-14-275Z.yml
D:\workspace\Browser4\Browser4-4.13\.browser4-cli\snapshot\snapshot-2026-08-14T07-00-19-124Z.yml
D:\workspace\Browser4\Browser4-4.13\.browser4-cli\snapshot\snapshot-2026-08-14T07-00-40-384Z.yml
D:\workspace\Browser4\Browser4-4.13\.browser4-cli\snapshot\snapshot-2026-08-14T07-03-00-586Z.yml
D:\workspace\Browser4\Browser4-4.13\.browser4-cli\snapshot\snapshot-2026-08-14T07-13-01-853Z.yml
D:\workspace\Browser4\Browser4-4.13\.browser4-cli\snapshot\snapshot-2026-08-14T07-14-15-049Z.yml
D:\workspace\Browser4\Browser4-4.13\.browser4-cli\snapshot\snapshot-2026-08-14T07-17-32-219Z.yml
D:\workspace\Browser4\Browser4-4.13\.browser4-cli\snapshot\snapshot-2026-08-14T07-18-19-425Z.yml
D:\workspace\Browser4\Browser4-4.13\.browser4-cli\snapshot\snapshot-2026-08-14T07-18-26-229Z.yml
D:\workspace\Browser4\Browser4-4.13\.browser4-cli\snapshot\snapshot-2026-08-14T07-18-32-792Z.yml
D:\workspace\Browser4\Browser4-4.13\.browser4-cli\snapshot\snapshot-2026-08-14T07-18-56-543Z.yml
D:\workspace\Browser4\Browser4-4.13\.browser4-cli\snapshot\snapshot-2026-08-14T07-19-00-798Z.yml
D:\workspace\Browser4\Browser4-4.13\.browser4-cli\snapshot\snapshot-2026-08-14T07-19-25-542Z.yml
D:\workspace\Browser4\Browser4-4.13\.browser4-cli\snapshot\snapshot-2026-08-14T07-19-33-672Z.yml
D:\workspace\Browser4\Browser4-4.13\.browser4-cli\snapshot\snapshot-2026-08-14T07-19-38-273Z.yml
D:\workspace\Browser4\Browser4-4.13\.browser4-cli\snapshot\snapshot-2026-08-14T07-19-42-650Z.yml
D:\workspace\Browser4\Browser4-4.13\.browser4-cli\snapshot\snapshot-2026-08-14T07-19-55-019Z.yml
D:\workspace\Browser4\Browser4-4.13\.browser4-cli\snapshot\snapshot-2026-08-14T07-19-59-786Z.yml
D:\workspace\Browser4\Browser4-4.13\.browser4-cli\snapshot\snapshot-2026-08-14T07-20-04-066Z.yml
D:\workspace\Browser4\Browser4-4.13\.browser4-cli\snapshot\snapshot-2026-08-14T07-20-53-118Z.yml
D:\workspace\Browser4\Browser4-4.13\.browser4-cli\snapshot\snapshot-2026-08-14T07-20-57-774Z.yml
D:\workspace\Browser4\Browser4-4.13\.browser4-cli\snapshot\snapshot-2026-08-14T07-21-02-143Z.yml
D:\workspace\Browser4\Browser4-4.13\.browser4-cli\snapshot\snapshot-2026-08-14T07-25-18-190Z.yml
D:\workspace\Browser4\Browser4-4.13\.browser4-cli\snapshot\snapshot-2026-08-14T07-25-22-641Z.yml
D:\workspace\Browser4\Browser4-4.13\.browser4-cli\snapshot\snapshot-2026-08-14T07-25-28-400Z.yml
D:\workspace\Browser4\Browser4-4.13\.browser4-cli\snapshot\snapshot-2026-08-14T07-25-31-290Z.yml
D:\workspace\Browser4\Browser4-4.13\.browser4-cli\snapshot\snapshot-2026-08-14T07-25-33-121Z.yml
D:\workspace\Browser4\Browser4-4.13\.browser4-cli\snapshot\snapshot-2026-08-14T07-25-35-808Z.yml
D:\workspace\Browser4\Browser4-4.13\.browser4-cli\snapshot\snapshot-2026-08-14T07-25-37-939Z.yml
D:\workspace\Browser4\Browser4-4.13\.browser4-cli\snapshot\snapshot-2026-08-14T07-25-40-107Z.yml
D:\workspace\Browser4\Browser4-4.13\.browser4-cli\snapshot\snapshot-2026-08-14T07-25-42-139Z.yml
D:\workspace\Browser4\Browser4-4.13\.browser4-cli\snapshot\snapshot-2026-08-14T07-25-44-169Z.yml
D:\workspace\Browser4\Browser4-4.13\.browser4-cli\snapshot\snapshot-2026-08-14T07-25-46-130Z.yml
D:\workspace\Browser4\Browser4-4.13\.browser4-cli\snapshot\snapshot-2026-08-14T07-25-51-675Z.yml
D:\workspace\Browser4\Browser4-4.13\.browser4-cli\snapshot\snapshot-2026-08-14T07-26-14-130Z.yml
D:\workspace\Browser4\Browser4-4.13\.browser4-cli\snapshot\snapshot-2026-08-14T07-26-26-060Z.yml
D:\workspace\Browser4\Browser4-4.13\.browser4-cli\snapshot\snapshot-2026-08-14T07-26-52-981Z.yml
D:\workspace\Browser4\Browser4-4.13\.browser4-cli\snapshot\snapshot-2026-08-14T07-27-12-740Z.yml
D:\workspace\Browser4\Browser4-4.13\.browser4-cli\snapshot\snapshot-2026-08-14T07-29-35-078Z.yml
D:\workspace\Browser4\Browser4-4.13\.browser4-cli\snapshot\archive\2026-08-04
=== .browser4-cli-state ===
D:\workspace\Browser4\Browser4-4.13\.browser4-cli-state\loops
D:\workspace\Browser4\Browser4-4.13\.browser4-cli-state\sessions
D:\workspace\Browser4\Browser4-4.13\.browser4-cli-state\async-tasks.json
D:\workspace\Browser4\Browser4-4.13\.browser4-cli-state\cli-state.json
D:\workspace\Browser4\Browser4-4.13\.browser4-cli-state\loop-history.jsonl
D:\workspace\Browser4\Browser4-4.13\.browser4-cli-state\sessions\price-watch.json
D:\workspace\Browser4\Browser4-4.13\.browser4-cli-state\sessions\SWARM.json


codex
Found that `extract` wrote a file even with `--stdout`. Let me confirm.
exec
"C:\\Program Files\\PowerShell\\7\\pwsh.exe" -Command 'Write-Output "=== extract file created during AC6 ==="; Get-Content ".browser4-cli/snapshot/extract-2026-08-14T07-28-23-179Z.txt" -Raw; Write-Output "=== git status of untracked files ==="; git status --short | Select-Object -First 20' in D:\workspace\Browser4\Browser4-4.13
 succeeded in 427ms:
=== extract file created during AC6 ===
{"type":"ai.platon.pulsar.agentic.ExtractResult","description":"{\"title\":\"Wireless Noise-Cancelling Headphones\",\"price\":\"$199.99\",\"rating\":\"4.4\",\"feature_bullets\":[\"Bluetooth 5.2\",\"30h battery\"],\"metadata\":{\"progress\":\"\",\"completed\":false},\"inputToken\":1929,\"outputToken\":4020,\"totalToken\":5949,\"inferenceTimeMillis\":67876}"}
=== git status of untracked files ===
 M browser4-tests/real-world-scenarios/scripts/common.ps1
?? coworker/tasks/issues/draft/20260814-063329-bulk-scale-routing.full.md
?? coworker/tasks/issues/draft/20260814-063329-bulk-scale-routing.issues.json
?? coworker/tasks/issues/draft/20260814-063329-bulk-scale-routing.issues.md
?? coworker/tasks/issues/draft/20260814-063332-extraction-method-routing.full.md
?? coworker/tasks/issues/draft/20260814-063332-extraction-method-routing.issues.json
?? coworker/tasks/issues/draft/20260814-063332-extraction-method-routing.issues.md
?? coworker/tasks/issues/draft/20260814-063335-webminer-structuring-routing.full.md
?? coworker/tasks/issues/draft/20260814-063335-webminer-structuring-routing.issues.json
?? coworker/tasks/issues/draft/20260814-063335-webminer-structuring-routing.issues.md
?? coworker/tasks/issues/draft/20260814-063439-bulk-scale-routing.full.md
?? coworker/tasks/issues/draft/20260814-063439-bulk-scale-routing.issues.json
?? coworker/tasks/issues/draft/20260814-063439-bulk-scale-routing.issues.md
?? coworker/tasks/issues/draft/20260814-063440-extraction-method-routing.full.md
?? coworker/tasks/issues/draft/20260814-063440-extraction-method-routing.issues.json
?? coworker/tasks/issues/draft/20260814-063440-extraction-method-routing.issues.md
?? coworker/tasks/issues/draft/20260814-063442-webminer-structuring-routing.full.md
?? coworker/tasks/issues/draft/20260814-063442-webminer-structuring-routing.issues.json
?? coworker/tasks/issues/draft/20260814-063442-webminer-structuring-routing.issues.md
?? coworker/tasks/issues/draft/20260814-072407-bulk-scale-routing.full.md

codex
Two more verification checks: the "auto-discovery" claim for `get text article` from §4a, and query output options.
exec
"C:\\Program Files\\PowerShell\\7\\pwsh.exe" -Command 'Write-Output "=== get text article (claimed auto-discovery in SKILL 4a) ==="; browser4-cli htmlsnapshot get text "article"; Write-Output "exit=$LASTEXITCODE"; Write-Output "=== any <article> in page? ==="; browser4-cli htmlsnapshot grep "<article" -i -c --no-line-number' in D:\workspace\Browser4\Browser4-4.13
 succeeded in 439ms:
=== get text article (claimed auto-discovery in SKILL 4a) ===

No elements matched "article".
  The snapshot may be stale — it reflects the DOM at capture time. If the page has changed since the last `htmlsnapshot`, re-capture with `htmlsnapshot` first.
  Verify the selector with `htmlsnapshot grep "article"`, or discover valid selectors with `htmlsnapshot inspect`.
exit=0
=== any <article> in page? ===
0

exec
"C:\\Program Files\\PowerShell\\7\\pwsh.exe" -Command 'browser4-cli htmlsnapshot query --help' in D:\workspace\Browser4\Browser4-4.13
 succeeded in 328ms:
browser4-cli htmlsnapshot query [url]

Run X-SQL. DOM_LOAD_AND_SELECT(@url, ...) re-fetches the page fresh via the scrape API (independent of the stored
snapshot). htmlsnapshot capture is only needed for inspect/get/summary, not for query with @url. IMPORTANT: CSS
selectors in X-SQL must use single quotes (SQL syntax); double quotes mean SQL identifiers.

Arguments:
  [url]                       URL to run the query against. Defaults to the current session's page URL
Options:
  --sql                       X-SQL query. Use @url as placeholder (unquoted — SQLTemplate handles escaping). Prefix
                              with @ to read from file
  --sql-stdin                 Read X-SQL query from stdin (avoids shell quoting issues on Windows)
  --sql-base64                Base64-encoded X-SQL query (avoid shell quoting issues on Windows)
  --result-only               Extract and print only the resultSet object from the response JSON, omitting wrapper
                              metadata
  --output-file               Write output to a file instead of stdout
  --format                    Output format: json, csv, or table (default: json — the raw scrape response envelope;
                              use --format table for readable output, or --result-only for the bare resultSet)
Notes:
  - DOM_LOAD_AND_SELECT(@url, ...) re-fetches the page fresh via the scrape API.
    It does NOT use the stored snapshot from htmlsnapshot capture.
    htmlsnapshot capture is only needed for inspect/get/summary, not for query with @url.
  - Function names are case-insensitive (DOM_FIRST_TEXT = dom_first_text).
  - Use --format table for human-readable output (json, csv, or table).
  - Use --result-only to extract just the resultSet array, omitting wrapper metadata.
  - Use --sql @file.sql to avoid shell quoting issues on Windows.
  - Use --sql-stdin or --sql-base64 to avoid shell quoting issues with inline SQL.

Examples:
  browser4-cli htmlsnapshot query --sql "SELECT DOM_FIRST_TEXT(DOM, 'h1') AS title FROM DOM_LOAD_AND_SELECT(@url,
  ':root')"
  browser4-cli htmlsnapshot query --sql @query.sql
  browser4-cli htmlsnapshot query --sql-stdin < query.sql
  browser4-cli htmlsnapshot query --sql-base64 "$(base64 -w0 query.sql)"
  browser4-cli htmlsnapshot query --sql @query.sql --result-only
  browser4-cli htmlsnapshot query --sql @query.sql --format table

codex
Checking exit-code behavior on empty `get` results — the docs claim non-zero exit when nothing matches.
exec
"C:\\Program Files\\PowerShell\\7\\pwsh.exe" -Command 'browser4-cli htmlsnapshot get text "zzz-no-such-element-xyz" | Out-Null; Write-Output "exit_no_match=$LASTEXITCODE"; browser4-cli htmlsnapshot get text "#productTitle" | Out-Null; Write-Output "exit_match=$LASTEXITCODE"' in D:\workspace\Browser4\Browser4-4.13
 succeeded in 461ms:
exit_no_match=0
exit_match=0

ERROR: Reconnecting... 1/5
codex
Let me sanity-check that the wrapper propagates exit codes at all, using a known-failing command.
exec
"C:\\Program Files\\PowerShell\\7\\pwsh.exe" -Command 'browser4-cli config set timeout 0 | Out-Null; Write-Output "exit_bad_config=$LASTEXITCODE"; browser4-cli definitely-not-a-command | Out-Null; Write-Output "exit_bad_cmd=$LASTEXITCODE"' in D:\workspace\Browser4\Browser4-4.13
 succeeded in 417ms:
exit_bad_config=1
exit_bad_cmd=2
Error: Invalid timeout value '0': expected a positive integer (seconds)
Error: Unknown command: 'definitely-not-a-command'

codex
Exit codes work for real errors, confirming the empty-get behavior is a genuine silent-failure issue. Let me check whether the CLI output dir is git-ignored, then compile the report.
exec
"C:\\Program Files\\PowerShell\\7\\pwsh.exe" -Command "rg -n \"browser4-cli|test-sessions|\\.browser4\" .gitignore 2>"'$null; Write-Output "---"; git check-ignore -v .browser4-cli/snapshot/snapshot-2026-08-14T07-29-35-078Z.yml 2>$null; Write-Output "check-ignore exit: $LASTEXITCODE"' in D:\workspace\Browser4\Browser4-4.13
 succeeded in 397ms:
96:.browser4/
124:/.test-sessions/
131:.browser4-cli/
132:.browser4-cli-state/
133:*/**/.browser4-cli/
134:*/**/.browser4-cli-state/
137:browser4-cli-linux-arm64
138:browser4-cli-linux-x64
139:browser4-cli-win32-x64.exe
140:/cli/browser4-cli/coverage-html/
---
.gitignore:131:.browser4-cli/	.browser4-cli/snapshot/snapshot-2026-08-14T07-29-35-078Z.yml
check-ignore exit: 0

codex
One more check — confirming the extract file was written by my `--stdout` invocation.
exec
"C:\\Program Files\\PowerShell\\7\\pwsh.exe" -Command 'Get-Item ".browser4-cli/snapshot/extract-2026-08-14T07-28-23-179Z.txt" | Select-Object Name, CreationTime, LastWriteTime; Get-Item ".browser4-cli/snapshot/extract-2026-08-13*.txt" | Select-Object Name, CreationTime' in D:\workspace\Browser4\Browser4-4.13
 succeeded in 356ms:

Name                                 CreationTime       LastWriteTime
----                                 ------------       -------------
extract-2026-08-14T07-28-23-179Z.txt 2026/8/14 15:28:23 2026/8/14 15:28:23
extract-2026-08-13T09-13-35-240Z.txt 2026/8/13 17:13:35 
extract-2026-08-13T09-14-12-144Z.txt 2026/8/13 17:14:12 


codex
Let me locate the source for the extract file output to give accurate code pointers.
exec
"C:\\Program Files\\PowerShell\\7\\pwsh.exe" -Command 'rg -n "extract-|ExtractResult|timestamped file|--stdout" cli/browser4-cli/src --glob "*.rs" | Select-Object -First 30' in D:\workspace\Browser4\Browser4-4.13
exec
"C:\\Program Files\\PowerShell\\7\\pwsh.exe" -Command 'Get-Content cli/browser4-cli/src/snapshot.rs | Select-Object -First 40' in D:\workspace\Browser4\Browser4-4.13
 succeeded in 332ms:
cli/browser4-cli/src\tips.rs:39:        text: "Use `snapshot --stdout --page 1` to read snapshots directly in stdout without file access",
cli/browser4-cli/src\tips.rs:446:/// - `--raw` / `--stdout` output mode is active
cli/browser4-cli/src\tips.rs:461:    // Suppress in machine-readable output modes (--json, --raw, --stdout)
cli/browser4-cli/src\snapshot.rs:30:/// Generate a timestamped filename (e.g., `snapshot-2026-01-15T10-30-00-123Z.yml`).
cli/browser4-cli/src\help.rs:917:            "Output is saved to a timestamped file by default. Use --stdout (or --raw) to print to stdout instead, or --filename to specify a custom path.",
cli/browser4-cli/src\help.rs:929:        lines.push("  browser4-cli extract \"article titles and dates\" --stdout".to_string());
cli/browser4-cli/src\help.rs:953:            "Output is saved to a timestamped file by default. Use --stdout (or --raw) to print to stdout instead, or --filename to specify a custom path.",
cli/browser4-cli/src\help.rs:962:        lines.push("  browser4-cli summarize --stdout".to_string());
cli/browser4-cli/src\help.rs:1819:            "  - Use --stdout (or --raw) to print snapshot content directly to stdout for piping."
cli/browser4-cli/src\help.rs:1871:        lines.push("  browser4-cli snapshot --stdout | head -20".to_string());
cli/browser4-cli/src\help.rs:2216:            "    `snapshot --stdout | head` or running a full `tab-list`."
cli/browser4-cli/src\help.rs:2899:        assert!(help.contains("--stdout (or --raw) to print to stdout"));
cli/browser4-cli/src\commands.rs:1287:                OptionDef { name: "interactive", description: "Only show interactive elements (buttons, links, inputs). Combine with --stdout to see refs inline.", is_bool: true, short: Some("i") },
cli/browser4-cli/src\commands.rs:1293:                OptionDef { name: "raw", description: "Strip page info and return only snapshot content (alias for --stdout)", is_bool: true, short: None },
cli/browser4-cli/src\commands.rs:2418:                OptionDef { name: "raw", description: "Print extracted content directly to stdout (alias for --stdout)", is_bool: true, short: None },
cli/browser4-cli/src\commands.rs:2442:                OptionDef { name: "raw", description: "Print summary directly to stdout (alias for --stdout)", is_bool: true, short: None },
cli/browser4-cli/src\commands.rs:3125:                OptionDef { name: "raw", description: "Print summary content directly to stdout (alias for --stdout)", is_bool: true, short: None },
cli/browser4-cli/src\main.rs:178:// Raw / stdout output support (--raw / --stdout per-command flags)
cli/browser4-cli/src\main.rs:182:    /// When `--raw` or `--stdout` is active, the command emits machine-readable
cli/browser4-cli/src\main.rs:4454:    // Suppress tips when --raw/--stdout is active so stderr doesn't
cli/browser4-cli/src\main.rs:4566:        // Hint: suggest --stdout to print inline instead of opening the file
cli/browser4-cli/src\main.rs:4569:                "💡 Tip: Use `--stdout` to print element refs inline instead of opening the snapshot file.\n\
cli/browser4-cli/src\main.rs:4586:                    eprintln!("... (use --stdout for full output, --page N for more)");

 succeeded in 323ms:
//! Snapshot and screenshot file helpers for the Browser4 CLI.

use std::fs;
use std::path::{Path, PathBuf};

use chrono::{Datelike, Utc};

/// Default path segments for snapshot and screenshot outputs.
pub const SNAPSHOT_DIR: [&str; 2] = [".browser4-cli", "snapshot"];

/// Maximum number of snapshot files to keep in the main snapshot directory
/// before older ones are moved to dated archive subdirectories.
const MAX_SNAPSHOTS: usize = 100;

/// Build the default snapshot output directory using OS-native separators.
pub fn snapshot_dir() -> PathBuf {
    PathBuf::from(SNAPSHOT_DIR[0]).join(SNAPSHOT_DIR[1])
}

/// Build the archive root directory path.
pub fn archive_dir() -> PathBuf {
    snapshot_dir().join("archive")
}

/// Ensure a directory exists, creating it recursively if needed.
pub fn ensure_dir(dir: &Path) -> std::io::Result<()> {
    fs::create_dir_all(dir)
}

/// Generate a timestamped filename (e.g., `snapshot-2026-01-15T10-30-00-123Z.yml`).
pub fn timestamped_filename(prefix: &str, ext: &str) -> String {
    let now = Utc::now();
    let base = now.format("%Y-%m-%dT%H-%M-%S").to_string();
    let ms = now.timestamp_subsec_millis();
    format!("{}-{}-{:03}Z.{}", prefix, base, ms, ext)
}

/// Resolve the output path for a snapshot or screenshot, creating the directory
/// if necessary. Returns the absolute path as a string.
///

exec
"C:\\Program Files\\PowerShell\\7\\pwsh.exe" -Command 'rg -n "extract|ExtractResult" cli/browser4-cli/src/main.rs | Select-Object -First 30' in D:\workspace\Browser4\Browser4-4.13
 succeeded in 347ms:
369:        "extract",
449:        // extract / summarize / agent run that follow `swarm create` should
1059:            "💡 Tip: Try `htmlsnapshot get text \"h1\"` to extract the page heading, or `htmlsnapshot inspect` to discover CSS selectors"
1329:/// - WebSocket URL (`ws://` / `wss://`) — extracts host:port as HTTP
1340:    // WebSocket URL — extract host:port and rewrite as HTTP
2289:             Workaround: extract the link's href with `eval` and navigate with `goto`:\n  \
2290:             eval 'document.querySelector(\\'[data-ref=\"{}\"\\']')?.getAttribute(\\'href\\')'\n  goto <extracted-url>",
5334:            "  For CSS selector-based extraction, capture the DOM first with `htmlsnapshot`, then use `htmlsnapshot get text \"{}\"`.",
5358:// Agent extract / summarize handlers
5361:/// Handle the `extract` command: save AI-extracted content to a file by default,
5363:async fn handle_extract(
5383:    let mut extract_args = {
5396:    if let Some(schema_val) = extract_args.get("schema").and_then(|v| v.as_str()) {
5400:            extract_args["schema"] = json!(resolved);
5408:        let mut args = extract_args.clone();
5412:            let (url_res, title_res, extract_res) = tokio::join!(
5429:            let content = extract_res?;
5436:            format_missing_llm_error(&e, "extract")
5448:    let out_path = resolve_output_path(filename.as_deref(), "extract", "txt");
5451:    // Detect silent extraction failures: the server may return a metadata-only
5452:    // response with "completed": false and zero extracted data.  Warn the user
5454:    let extraction_empty = detect_empty_extraction(content);
5458:    json_field("extract_path", json!(out_path.display().to_string()));
5459:    json_field("extracted_content", json!(content));
5460:    if extraction_empty {
5461:        json_field("extraction_empty", json!(true));
5470:        if extraction_empty {
5472:            cli_println!("The extract command completed but returned no structured data. The AI model may not have been able to identify the requested information on this page. Try:");
5474:            cli_println!("  - Using `eval` with a JavaScript selector for precise data extraction");
5475:            cli_println!("  - Using `htmlsnapshot get` for CSS-based DOM extraction");

exec
"C:\\Program Files\\PowerShell\\7\\pwsh.exe" -Command '$lines = Get-Content cli/browser4-cli/src/main.rs; $lines[5358..5520] -join "`n"' in D:\workspace\Browser4\Browser4-4.13
 succeeded in 413ms:
// ---------------------------------------------------------------------------

/// Handle the `extract` command: save AI-extracted content to a file by default,
/// print to stdout with `--raw`.
async fn handle_extract(
    client: &Client,
    base_url: &str,
    tool_name: &str,
    tool_params: &Value,
    session_name: Option<&str>,
) -> Result<(), String> {
    let filename = tool_params
        .get("filename")
        .and_then(|v| v.as_str())
        .map(|s| s.to_string());
    let raw = tool_params
        .get("raw")
        .and_then(|v| v.as_bool())
        .unwrap_or(false)
        || tool_params
            .get("stdout")
            .and_then(|v| v.as_bool())
            .unwrap_or(false);
    raw_init(raw);
    let mut extract_args = {
        let mut a = tool_params.clone();
        if let Value::Object(ref mut m) = a {
            m.remove("filename");
            m.remove("raw");      // CLI-side flag, not a server parameter
            m.remove("stdout");   // CLI-side flag, not a server parameter
        }
        a
    };

    // Resolve @file references for --schema (mirrors --sql @file behavior).
    // Shell wrappers (b4w.sh, b4w.ps1) struggle with inline JSON containing
    // colons, commas, and quotes.  @file.json avoids shell quoting entirely.
    if let Some(schema_val) = extract_args.get("schema").and_then(|v| v.as_str()) {
        if let Some(file_path) = schema_val.strip_prefix('@') {
            let resolved = resolve_sql_file(file_path)
                .map_err(|e| format!("--schema @file: {e}"))?;
            extract_args["schema"] = json!(resolved);
        }
    }

    let combined = with_session(client, base_url, session_name, false, |session_id| {
        let client = client.clone();
        let base_url = base_url.to_string();
        let tool_name = tool_name.to_string();
        let mut args = extract_args.clone();
        args["sessionId"] = json!(session_id.clone());

        async move {
            let (url_res, title_res, extract_res) = tokio::join!(
                call_tool(
                    &client,
                    &base_url,
                    "page_url",
                    json!({ "sessionId": session_id })
                ),
                call_tool(
                    &client,
                    &base_url,
                    "page_title",
                    json!({ "sessionId": session_id })
                ),
                call_tool(&client, &base_url, &tool_name, args),
            );
            let url = url_res?;
            let title = title_res?;
            let content = extract_res?;
            Ok(format!("{}\n{}\n{}", url, title, content))
        }
    })
    .await
    .map_err(|e| {
        if is_missing_llm_configuration_message(&e) {
            format_missing_llm_error(&e, "extract")
        } else {
            e
        }
    })?;

    let parts: Vec<&str> = combined.splitn(3, '\n').collect();
    let (url, title, content) = match parts.as_slice() {
        [u, t, c] => (*u, *t, *c),
        _ => ("", "", combined.as_str()),
    };

    let out_path = resolve_output_path(filename.as_deref(), "extract", "txt");
    save_snapshot(&out_path, content).map_err(|e| e.to_string())?;

    // Detect silent extraction failures: the server may return a metadata-only
    // response with "completed": false and zero extracted data.  Warn the user
    // explicitly instead of silently saving an empty/ metadata-only file.
    let extraction_empty = detect_empty_extraction(content);

    json_field("page_url", json!(url));
    json_field("page_title", json!(title));
    json_field("extract_path", json!(out_path.display().to_string()));
    json_field("extracted_content", json!(content));
    if extraction_empty {
        json_field("extraction_empty", json!(true));
    }

    if raw {
        println!("{}", content);
    } else {
        cli_println!("### Page");
        cli_println!("- Page URL: {}", url);
        cli_println!("- Page Title: {}", title);
        if extraction_empty {
            cli_println!("### ⚠️  Extraction produced no data");
            cli_println!("The extract command completed but returned no structured data. The AI model may not have been able to identify the requested information on this page. Try:");
            cli_println!("  - Narrowing the scope with a more specific instruction");
            cli_println!("  - Using `eval` with a JavaScript selector for precise data extraction");
            cli_println!("  - Using `htmlsnapshot get` for CSS-based DOM extraction");
            cli_println!("[Raw response]({})", out_path.display());
        } else {
            cli_println!("### Extracted content");
            cli_println!("[Extracted content]({})", out_path.display());
        }
    }
    Ok(())
}

/// Check whether an extract response looks like a silent failure — the server
/// returned metadata (`"completed": false`) with no actual extracted data.
fn detect_empty_extraction(content: &str) -> bool {
    let trimmed = content.trim();
    if trimmed.is_empty() {
        return true;
    }
    // Try to parse as JSON and look for the "completed": false pattern that
    // signals the extraction pipeline produced no results.
    if let Ok(v) = serde_json::from_str::<serde_json::Value>(trimmed) {
        if let Some(completed) = v
            .pointer("/data/metadata/completed")
            .and_then(|c| c.as_bool())
        {
            if !completed {
                return true;
            }
        }
        // Also detect the top-level form: {"success":true,"completed":false,…}
        if let Some(completed) = v
            .pointer("/completed")
            .and_then(|c| c.as_bool())
        {
            if !completed {
                return true;
            }
        }
        // If the response is a small metadata-only object (no data array/string),
        // treat it as empty.
        if let Some(data) = v.get("data") {
            if data.is_object() && data.get("metadata").is_some() && data.as_object().map_or(true, |o| o.len() <= 2) {
                return true;
            }
        }
    }
    false
}

codex
Let me find the source of the confusing "No active session — creating a new one." message.
exec
"C:\\Program Files\\PowerShell\\7\\pwsh.exe" -Command 'rg -n "No active session" cli/browser4-cli/src --glob "*.rs"' in D:\workspace\Browser4\Browser4-4.13
 succeeded in 345ms:
cli/browser4-cli/src\main.rs:260:    /// No active session, session expired, session conflict.
cli/browser4-cli/src\main.rs:504:        "No active session is currently stored for this CLI context.",
cli/browser4-cli/src\main.rs:1248:        // No active session found. Create a new one explicitly so the user
cli/browser4-cli/src\main.rs:1252:            eprintln!("No active session — creating a new one.");
cli/browser4-cli/src\main.rs:18423:        assert!(message.contains("No active session is currently stored"));

exec
"C:\\Program Files\\PowerShell\\7\\pwsh.exe" -Command '$lines = Get-Content cli/browser4-cli/src/main.rs; $lines[1230..1270] -join "`n"' in D:\workspace\Browser4\Browser4-4.13
 succeeded in 441ms:
                };
                let conn_type = default_state
                    .attach_type
                    .as_deref()
                    .unwrap_or("cdp");
                return Err(format!(
                    "'{}' is an existing {conn_type}-attached session, not a named Browser4 session.\n\
                     Use '-s {}' directly with tab commands, e.g.:\n  \
                     browser4-cli -s {} tab-list\n  \
                     browser4-cli -s {} tab-new https://example.com\n\
                     To reconnect to this session: `{}`.\n\
                     To create a new Browser4 session: `open` (omit -s).",
                    name, name, name, name, attach_cmd
                ));
            }
        }

        // No active session found. Create a new one explicitly so the user
        // understands why a session is being opened (as opposed to reusing an
        // existing session silently).
        if !json_active() {
            eprintln!("No active session — creating a new one.");
        }
        let capabilities = build_open_session_capabilities(tool_params);
        let new_id =
            create_session(client, base_url, &state, session_name, Some(capabilities)).await?;
        cli_println!(
            "{}",
            format_session_opened_message(session_name, &new_id)
        );
        new_id
    };

    // When reconnecting to an existing session, inform the user what page is active
    if reused_existing_session {
        // A `headed` key in the params means the user passed --headless/--headed
        // explicitly, but the display mode was fixed when the session was
        // created — surface that the flag is being ignored instead of silently
        // reconnecting with different settings than requested.
        if let Some(headed) = tool_params.get("headed").and_then(Value::as_bool) {
            let flag = if headed { "--headed" } else { "--headless" };

codex
Let me find the htmlsnapshot get handler function for an accurate code pointer.
exec
"C:\\Program Files\\PowerShell\\7\\pwsh.exe" -Command 'rg -n "fn handle_html_snapshot|html_snapshot_get|htmlsnapshot get" cli/browser4-cli/src/main.rs | Select-Object -First 15' in D:\workspace\Browser4\Browser4-4.13
 succeeded in 350ms:
1059:            "💡 Tip: Try `htmlsnapshot get text \"h1\"` to extract the page heading, or `htmlsnapshot inspect` to discover CSS selectors"
5334:            "  For CSS selector-based extraction, capture the DOM first with `htmlsnapshot`, then use `htmlsnapshot get text \"{}\"`.",
5475:            cli_println!("  - Using `htmlsnapshot get` for CSS-based DOM extraction");
5671:async fn handle_html_snapshot_capture(
5897:        cli_println!("     htmlsnapshot get text \"h1\" --limit 5   # page heading");
5899:    cli_println!("     htmlsnapshot get all text \"a\" --limit 20  # link texts");
5900:    cli_println!("     htmlsnapshot get attr \"img[src]\" src --limit 20  # image URLs");
5901:    cli_println!("     htmlsnapshot get attr \"a[href]\" href --limit 20  # link URLs");
5903:        cli_println!("     htmlsnapshot get attr \"img[src]:expr(width > 200 && height > 200)\" src --limit 20  # large images only");
5913:async fn handle_html_snapshot_get(
5942:            "Element references ('{selector}') are not supported in htmlsnapshot get. Use a CSS selector instead."
5956:                 Usage:\n  htmlsnapshot get attr <css-selector> <attribute-name>\n  htmlsnapshot get all attr <css-selector> <attribute-name>\n\
5958:                 Examples:\n  htmlsnapshot get attr \"img\" src       → src of first matching <img>\n  htmlsnapshot get all attr \"a\" href    → href of every matching <a>\n  htmlsnapshot get attr \"#price\" data-value  → data-value attribute"
5973:        // Improve error messages for htmlsnapshot get failures.
5976:        if e.contains("htmlsnapshot get failed") || e.contains("html_snapshot_scrape") {

exec
"C:\\Program Files\\PowerShell\\7\\pwsh.exe" -Command '$lines = Get-Content cli/browser4-cli/src/main.rs; $lines[5913..6000] -join "`n"' in D:\workspace\Browser4\Browser4-4.13
 succeeded in 450ms:
    client: &Client,
    base_url: &str,
    tool_name: &str,
    tool_params: &Value,
    session_name: Option<&str>,
) -> Result<(), String> {
    // Validate field
    let field = tool_params
        .get("field")
        .and_then(|v| v.as_str())
        .unwrap_or("");
    if field.is_empty() {
        return Err("Field is required: text, html, or attr.".to_string());
    }
    if !["text", "html", "attr"].contains(&field) {
        return Err(format!(
            "Unknown field '{}'. Use text, html, or attr.",
            field
        ));
    }

    // Validate selector - reject element references
    let selector = tool_params
        .get("selector")
        .and_then(|v| v.as_str())
        .unwrap_or("");
    if is_element_reference(selector) {
        return Err(format!(
            "Element references ('{selector}') are not supported in htmlsnapshot get. Use a CSS selector instead."
        ));
    }

    // Validate attr field requires a name
    if field == "attr" {
        let attr_name = tool_params
            .get("attrName")
            .and_then(|v| v.as_str())
            .unwrap_or("");
        if attr_name.is_empty() {
            return Err(format!(
                "The 'attr' field requires an attribute name as the third argument.\n\
                 \n\
                 Usage:\n  htmlsnapshot get attr <css-selector> <attribute-name>\n  htmlsnapshot get all attr <css-selector> <attribute-name>\n\
                 \n\
                 Examples:\n  htmlsnapshot get attr \"img\" src       → src of first matching <img>\n  htmlsnapshot get all attr \"a\" href    → href of every matching <a>\n  htmlsnapshot get attr \"#price\" data-value  → data-value attribute"
            ));
        }
    }

    let result = with_session_paginated(client, base_url, session_name, false, |session_id| {
        let client = client.clone();
        let base_url = base_url.to_string();
        let tool_name = tool_name.to_string();
        let mut params = tool_params.clone();
        params["sessionId"] = json!(session_id);
        async move { call_tool_with_result(&client, &base_url, &tool_name, params).await }
    })
    .await
    .map_err(|e| {
        // Improve error messages for htmlsnapshot get failures.
        // The backend auto-captures when no snapshot exists, but if capture
        // itself fails, guide the user toward a fix.
        if e.contains("htmlsnapshot get failed") || e.contains("html_snapshot_scrape") {
            format!(
                "{}\n\nTip: Run `htmlsnapshot` first to explicitly capture the page, then try again.",
                e
            )
        } else {
            e
        }
    })?;

    let server_pagination = result.pagination;
    let text = result.text;

    // Output the result
    let empty_result = text == "null" || text.is_empty() || text.trim() == "[]";
    // Only HTML output is paginated by default; text extraction rarely exceeds
    // practical limits for single-field extraction so it defaults to --all.
    let paginate = (field == "html") && !empty_result;

    // Determine if this is a "get all" (querySelectorAll) call vs a "get" (single element)
    let is_get_all = tool_name.ends_with("_all");

    if empty_result {
        let display_selector = if selector.is_empty() { ":root" } else { selector };
        cli_println!("{}", text);
        cli_println!(

exec
"C:\\Program Files\\PowerShell\\7\\pwsh.exe" -Command '$lines = Get-Content cli/browser4-cli/src/main.rs; $lines[5990..6030] -join "`n"' in D:\workspace\Browser4\Browser4-4.13
 succeeded in 453ms:
    // Only HTML output is paginated by default; text extraction rarely exceeds
    // practical limits for single-field extraction so it defaults to --all.
    let paginate = (field == "html") && !empty_result;

    // Determine if this is a "get all" (querySelectorAll) call vs a "get" (single element)
    let is_get_all = tool_name.ends_with("_all");

    if empty_result {
        let display_selector = if selector.is_empty() { ":root" } else { selector };
        cli_println!("{}", text);
        cli_println!(
            "No elements matched \"{}\".",
            display_selector
        );
        cli_println!(
            "  The snapshot may be stale — it reflects the DOM at capture time. If the page has changed since the last `htmlsnapshot`, re-capture with `htmlsnapshot` first."
        );
        cli_println!(
            "  Verify the selector with `htmlsnapshot grep \"{}\"`, or discover valid selectors with `htmlsnapshot inspect`.",
            display_selector
        );
    } else if is_get_all && !json_active() {
        // For "get all" mode, warn when only 0–1 results are returned.
        // A low count often means the CSS selector doesn't match the page's
        // current structure (e.g. changed class names, different layout).
        cli_println!("{}", text);
        let result_count = if text.trim().starts_with('[') {
            // Parse the JSON array to count elements (server returns a JSON array for get_all)
            serde_json::from_str::<Vec<serde_json::Value>>(&text)
                .map(|arr| arr.len())
                .ok()
        } else {
            // String payload — count by line as a rough approximation
            Some(text.lines().count())
        };
        if let Some(count) = result_count {
            if count <= 1 {
                let display_selector = if selector.is_empty() { ":root" } else { selector };
                cli_println!(
                    "Only {} result(s) found for \"{}\". The page structure may have changed since the snapshot was captured. Try `htmlsnapshot inspect \"{}\"` to discover current selectors.",
                    count, display_selector, display_selector

codex
All evidence gathered. Compiling the final report now.
  ✓ Verify environment, CLI help, and SKILL.md documentation
  ✓ Ensure MockSite running and LLM key configured for extract
  ✓ AC1: Interact-then-extract on form-filling.html
  ✓ AC2: Static page single field via htmlsnapshot get text
  ✓ AC3: Static page all matches via htmlsnapshot get all text
  ✓ AC4: Correlated multi-field rows via htmlsnapshot query X-SQL
  ✓ AC5: Dynamic page logic via eval --json
  ✓ AC6: Natural-language extraction via extract
  ✓ AC7: High-volume extraction via crawl --sql seed file
  → Compile task result, execution trace, and issue report
codex
## A. Task Result

All 7 acceptance criteria passed end-to-end against the released `browser4-cli` 4.13.4 (backend v4.13.4-SNAPSHOT at localhost:18182, MockSite on localhost:18080, DeepSeek configured on the backend for `extract`):

- **AC1 — Interact first, then extract:** Filled First Name, Last Name, Email, Country (jp), Experience level (advanced), checked the Testing and newsletter checkboxes, filled Comments, agreed to terms, and submitted. A fresh `htmlsnapshot` after submit followed by `htmlsnapshot get text "#result-data"` returned the post-submit JSON payload containing every entered value (`firstName: Ada`, `lastName: Lovelace`, `email: ada@example.com`, `country: jp`, `topics: [automation, testing]`, `newsletterOptIn: true`, `experienceLevel: advanced`, comments). ✓
- **AC2 — Static page, one field:** `htmlsnapshot get text "#productTitle"` on `/ec/dp/B0E000001` returned `4K OLED TV 55`. ✓
- **AC3 — One field, all matches:** `htmlsnapshot get all text "[class*='product-title']"` on the Electronics listing returned all 6 product titles, not just the first. ✓
- **AC4 — Correlated multi-field rows:** An X-SQL query using `DOM_LOAD_AND_SELECT(@url, 'div[class*="product-card"]')` with `DOM_BASE_URI`, `DOM_FIRST_TEXT` (title/price), and `DOM_FIRST_HREF` (detail link) returned 6 rows with title, price, and detail URL correctly aligned per card. ✓
- **AC5 — Dynamic/complex page logic:** `eval --file ac5-eval.js --json` returned a structured object (`pageTitle`, `buttonCount: 2`, `linkCount: 0`, `formCount: 0`, 5 headings), each value verified against the live page. ✓
- **AC6 — Natural-language extraction:** `extract "Return the product title, displayed price, rating, and the top three feature bullets as JSON." --stdout` returned title/price/rating matching the page exactly; the page only contains 2 feature bullets, so the extraction was faithful. ✓
- **AC7 — High-volume extraction:** `crawl --seed-file` (5 product URLs) `--depth 0 --sql @ac7-query.sql --format table --refresh` produced 5 rows, one per seed URL, each with correct URL/title/price. ✓

All scratch files (query SQL, JS, seed list, help JSON, SKILL copy) were created under `./.test-sessions/` (git-ignored). No repo files were modified; the worktree changes shown by `git status` (a modified `common.ps1` and coworker draft files) pre-date this session.

## B. Execution Trace

**Preparation**
- Verified cwd = `D:\workspace\Browser4\Browser4-4.13`; created `./.test-sessions/`.
- Ran `browser4-cli help` (full command map) and saved `--help-json` to `.test-sessions/help-json.json`.
- Downloaded and read the complete `https://browser4.io/SKILL.md` (45 KB) plus the local reference docs that govern the scenario: `htmlsnapshot.md`, `x-sql.md`, `x-sql-dom-functions.md`, `crawl.md`, `agent.md`.
- `browser4-cli status` → backend UP; confirmed MockSite HTTP 200 on :18080; `browser4-cli doctor` → "LLM is configured" (DeepSeek via `~/.browser4/config/conf-enabled/application-private.properties`).

**AC1** — `goto form-filling.html` → `snapshot -i --stdout` (obtained refs e2–e9, e14, e16, e17, e172) → `fill e2 "Ada"`, `fill e3 "Lovelace"`, `fill e4 "ada@example.com"`, `select e5 "jp"`, `select e8 "advanced"`, `check e14`, `check e16`, `fill e9 "…"`, `check e17`, `click e172` → `htmlsnapshot` → `get text "#result-data"`. Workaround: my first selector `.result-data` (from the AX label) returned "No elements matched"; the element is an ID (`#result-data`), corrected via `htmlsnapshot grep`.

**AC2/AC3** — `goto` detail page → `htmlsnapshot` → `get text "#productTitle"`; `goto` listing page → `htmlsnapshot` → `get all text "[class*='product-title']"`.

**AC4** — Verified card markup with `htmlsnapshot grep "product-card"`; wrote `ac4-query.sql` under `.test-sessions/`; ran `htmlsnapshot query "http://localhost:18080/ec/b?node=1292115012" --sql "@.test-sessions/ac4-query.sql"` (quoting the `@` path per PowerShell guidance). Note: `DOM_FIRST_HREF(DOM, 'a.product-link')` returned absolute URLs.

**AC5** — `goto interactive-1.html`; wrote `ac5-eval.js`; ran `eval --file .test-sessions/ac5-eval.js --json`; cross-checked counts with `htmlsnapshot` capture + `get all attr button class` + `grep "<form"`.

**AC6** — `goto /ec/dp/B0E000002`; ran `extract "…as JSON." --stdout` (~69 s); verified title/price/rating/features via `htmlsnapshot get text`.

**AC7** — Wrote `ac7-seed.txt` (5 URLs) and `ac7-query.sql`; ran `crawl --seed-file ".test-sessions/ac7-seed.txt" --depth 0 --sql "@.test-sessions/ac7-query.sql" --format table --refresh`; verified the 5-row table.

**Key decisions:** used the documented `--sql @file` / `eval --file` patterns to avoid PowerShell quoting; followed the "interact → re-snapshot → extract" loop exactly as SKILL.md prescribes (refs stayed valid across sequential fills on this static form); treated `extract`'s output as valid once cross-checked against the live DOM.

**Workarounds required:** only the selector form correction in AC1 (`.result-data` → `#result-data`).

```json
{
  "issues": [
    {
      "title": "SKILL.md copy-paste template `htmlsnapshot get text <css> --all` returns only the first match, not all matches",
      "severity": "Medium",
      "category": "Documentation",
      "reproduction": "On a listing page with multiple matches run: browser4-cli htmlsnapshot; then browser4-cli htmlsnapshot get text \"[class*='product-title']\" --all. Compare with browser4-cli htmlsnapshot get all text \"[class*='product-title']\".",
      "expected": "Following the SKILL.md §1 Copy-Paste Template (`htmlsnapshot get text \"<css-selector>\" --all`) should return all matching product titles, as a first-time user would reasonably infer from the template.",
      "actual": "`get text ... --all` returns only the first match (e.g. \"4K OLED TV 55\"); the full list of 6 titles is only returned by `get all text ...`. `--all` merely disables output pagination; it does not switch to querySelectorAll semantics.",
      "rootCause": "The SKILL.md quick-start template conflates the `--all` pagination flag with the `get all` variant. The underlying command semantics are correct and correctly documented in references/htmlsnapshot.md; only the top-level SKILL.md template (and web copy) is wrong. A user copying the template gets silently incomplete data.",
      "codePointer": "skills/browser4-cli/SKILL.md (section 1 Copy-Paste Template); web SKILL.md at browser4.io/SKILL.md",
      "suggestion": "- Replace `htmlsnapshot get text \"<css-selector>\" --all` in the template with `htmlsnapshot get all text \"<css-selector>\"`\n- Add a one-line note under the template distinguishing `get` (first match) from `get all` (querySelectorAll)\n- Consider making the CLI print a hint when `--all` is used with `get` alone (e.g. 'Use get all <field> for all matches')"
    },
    {
      "title": "`htmlsnapshot get` exits 0 when the selector matches nothing, contradicting the documented non-zero exit",
      "severity": "Medium",
      "category": "Reliability",
      "reproduction": "browser4-cli htmlsnapshot get text \"zzz-no-such-element-xyz\"; echo $LASTEXITCODE  (also verifiable with `&& echo PASS` in bash)",
      "expected": "Non-zero exit code when the CSS selector matches nothing, as stated in references/htmlsnapshot.md Error Handling: \"htmlsnapshot get exits non-zero when the CSS selector matches nothing or an element ref (e5) is passed.\"",
      "actual": "Exit code is 0. The CLI prints a helpful \"No elements matched\" message but returns success, so scripts/CI pipelines treat empty extraction as success (silent failure). Control commands do propagate exit codes correctly (e.g. `config set timeout 0` exits 1, unknown command exits 2), so this is specific to the empty-result path.",
      "rootCause": "handle_html_snapshot_get computes `empty_result` and prints guidance but returns Ok(()) from the command handler; no error/exit code is produced for the no-match case. Only invalid selectors/refs and backend failures produce non-zero exits.",
      "codePointer": "cli/browser4-cli/src/main.rs:5913 (handle_html_snapshot_get, empty_result branch around line 5996)",
      "suggestion": "- Return a non-zero exit (e.g. 2) when `empty_result` is true for `get`/`get all`, while still printing the guidance text\n- Add a machine-readable `--json` field like `\"matched\": false` so scripts can distinguish no-match from success\n- Update references/htmlsnapshot.md only if the behavior is intentionally kept as exit 0, and document the discrepancy"
    },
    {
      "title": "`extract --stdout` prints the raw internal ExtractResult envelope instead of the extracted content",
      "severity": "Medium",
      "category": "Product",
      "reproduction": "browser4-cli extract \"Return the product title, displayed price, rating, and the top three feature bullets as JSON.\" --stdout",
      "expected": "Per `browser4-cli help extract`: \"--stdout Print extracted content directly to stdout.\" The output should be the extracted JSON (`{\"title\": ..., \"price\": ..., ...}`), directly consumable by scripts.",
      "actual": "Stdout contains the backend envelope: {\"type\":\"ai.platon.pulsar.agentic.ExtractResult\",\"description\":\"<escaped JSON>\",\"inputToken\":...,\"outputToken\":...,\"inferenceTimeMillis\":...}. The requested JSON is double-encoded inside `description`, and the embedded metadata reports `\"completed\":false` even though extraction succeeded — confusing and wrong for direct consumption.",
      "rootCause": "handle_extract passes the raw server response through as `content` and, in raw mode, `println!(\"{}\", content)` prints that envelope verbatim. No client-side unwrapping of the ExtractResult `description` field is performed, and the empty-extraction detector looks for `completed:false` at paths that this envelope shape does not match.",
      "codePointer": "cli/browser4-cli/src/main.rs:5363 (handle_extract, raw branch around line 5478)",
      "suggestion": "- In `--stdout`/`--raw` mode, unwrap `ExtractResult.description` and print the actual extracted payload\n- Optionally also print a one-line metadata summary to stderr instead of stdout so stdout stays clean for piping\n- Fix the empty-extraction detector to recognize the ExtractResult envelope shape (description containing metadata.completed=false)"
    },
    {
      "title": "`extract --stdout` still writes a timestamped file into the workspace `.browser4-cli/snapshot/` directory",
      "severity": "Low",
      "category": "UX",
      "reproduction": "Run browser4-cli extract \"...\" --stdout, then check Get-ChildItem .browser4-cli/snapshot/extract-*.txt (a new file with the same content is created).",
      "expected": "With `--stdout`, no file should be written — help text says \"Output is saved to a timestamped file by default. Use --stdout (or --raw) to print to stdout instead.\"",
      "actual": "A file `.browser4-cli/snapshot/extract-<timestamp>.txt` is always created regardless of `--stdout`/`--raw`, because `save_snapshot` is called unconditionally. This wrote to the repository working tree during the evaluation (git-ignored, but still workspace pollution).",
      "rootCause": "handle_extract always calls `save_snapshot(&out_path, content)` before checking the raw flag; the raw flag only controls whether content is also printed to stdout.",
      "codePointer": "cli/browser4-cli/src/main.rs:5448 (handle_extract, unconditional save_snapshot call)",
      "suggestion": "- Skip `save_snapshot` when `--stdout`/`--raw`/`--filename` is provided (or document that extraction always archives)\n- When `--filename` is provided, write only to that path instead of also writing to the snapshot dir\n- Surface the saved path in non-stdout mode so users know where archives land"
    },
    {
      "title": "Snapshot/screenshot/extract outputs go to a hardcoded workspace-relative `.browser4-cli/snapshot/` dir that is undocumented and not redirected by BROWSER4_CLI_STATE_DIR",
      "severity": "Medium",
      "category": "Documentation",
      "reproduction": "Run any `snapshot`, `screenshot`, or `extract` command from a repo checkout; observe files created under <cwd>/.browser4-cli/snapshot/ even though session state lives in ~/.browser4. Attempt to redirect with $env:BROWSER4_CLI_STATE_DIR — snapshot output location does not change.",
      "expected": "The documentation (SKILL.md, `browser4-cli help`) explains where command outputs are stored and provides a way to redirect them; the described fallback is `./.browser4-cli-state`, but outputs should follow the state/runtime dir or an env override.",
      "actual": "The snapshot/screenshot/extract output directory is hardcoded to `./.browser4-cli/snapshot/` relative to the current working directory (snapshot.rs SNAPSHOT_DIR), independent of BROWSER4_CLI_STATE_DIR. The docs only mention `.browser4-cli-state` as the workspace fallback for state, and never document `.browser4-cli/snapshot/`. In a source checkout this accumulates snapshot/extract files in the repo tree (mitigated only by .gitignore lines 131–134).",
      "rootCause": "snapshot_dir() builds the path from a hardcoded constant [\".browser4-cli\", \"snapshot\"] relative to CWD; no env var or config key overrides it, and the SKILL.md/help docs don't mention it.",
      "codePointer": "cli/browser4-cli/src/snapshot.rs:16 (snapshot_dir(), SNAPSHOT_DIR at line 9)",
      "suggestion": "- Document `.browser4-cli/snapshot/` in SKILL.md and `help` (alongside `snapshot clean`), and/or honor BROWSER4_CLI_STATE_DIR for snapshot outputs\n- Add an env var (e.g. BROWSER4_CLI_SNAPSHOT_DIR) or `config set snapshot-dir <path>` override\n- Consider defaulting outputs to the state dir (~/.browser4/snapshot) instead of CWD-relative paths, which is safer for source-tree usage"
    },
    {
      "title": "SKILL.md §4a claims `htmlsnapshot get text article` \"auto-discovers content\", but `article` is a plain tag selector",
      "severity": "Low",
      "category": "Documentation",
      "reproduction": "On a page without an <article> tag (e.g. MockSite product page): browser4-cli htmlsnapshot; browser4-cli htmlsnapshot get text \"article\" → \"No elements matched\".",
      "expected": "Either the command should auto-discover the main content as the decision tree implies, or the docs should not describe `article` as auto-discovery.",
      "actual": "The command simply runs querySelector('article'); on MockSite pages there are no <article> elements, so it returns an empty result with exit 0. Auto-discovery is a separate feature of `htmlsnapshot inspect`, not of `get text article`.",
      "rootCause": "The §4a decision tree line \"Don't know the right CSS selector? → htmlsnapshot get text article (auto-discovers content)\" is a misleading simplification; no auto-discovery exists in the get path.",
      "codePointer": "skills/browser4-cli/SKILL.md (section 4a decision tree); references/htmlsnapshot.md (get command docs)",
      "suggestion": "- Replace the decision-tree line with `htmlsnapshot inspect` (which genuinely auto-discovers recurring patterns) or clarify that `article` is just an example tag selector\n- Add a note that `get text` accepts any CSS selector and that `inspect`/`summary` are the discovery tools"
    },
    {
      "title": "`htmlsnapshot query` default output is a raw JSON envelope and the human-readable `--format table` / `--result-only` flags are absent from the main documentation",
      "severity": "Low",
      "category": "Discoverability",
      "reproduction": "browser4-cli htmlsnapshot query \"http://localhost:18080/ec/b?node=1292115012\" --sql \"@query.sql\" → full JSON envelope with id/statusCode/timestamps; only `browser4-cli htmlsnapshot query --help` reveals --format table and --result-only.",
      "expected": "The primary docs (SKILL.md §4e, references/htmlsnapshot.md Query section) should mention the output format options so a first-time user knows a readable table output exists; or the default should be human-readable.",
      "actual": "SKILL.md and htmlsnapshot.md describe `query` without any output-format flags; the user gets a noisy machine envelope by default and must discover `--help` to find `--format table`. The task instructions themselves only specified the envelope path, so the scenario still succeeded, but the readable option was effectively hidden.",
      "rootCause": "Documentation lag: the `--format`/`--result-only` options exist in help.rs/commands.rs but were not added to SKILL.md or the htmlsnapshot reference.",
      "codePointer": "skills/browser4-cli/references/htmlsnapshot.md (Query section); skills/browser4-cli/SKILL.md (4e X-SQL Quickstart)",
      "suggestion": "- Document `--format json|csv|table` and `--result-only` in the htmlsnapshot.md Query section and SKILL.md §4e\n- Add a table-format example to the X-SQL quickstart so the default envelope doesn't surprise first-time users\n- Consider making `--format table` the default when stdout is a TTY"
    },
    {
      "title": "First-run `goto` prints \"No active session — creating a new one.\" on stderr after the successful output, appearing contradictory",
      "severity": "Low",
      "category": "UX",
      "reproduction": "In a fresh CLI environment, run: browser4-cli goto \"http://localhost:18080/generated/form-filling.html\" and observe message ordering.",
      "expected": "Session creation status should appear before/with the \"Session opened: ...\" and navigation output so the messages are coherent.",
      "actual": "The stderr message \"No active session — creating a new one.\" is emitted before session creation in code, but appears after the \"Session opened\"/\"Navigated to\"/snapshot-path output in the captured console due to stdout/stderr stream buffering, making it look like the CLI first navigated and then created a session.",
## A. Task Result
      "rootCause": "eprintln! at main.rs:1252 writes to stderr (unbuffered) while session/navigation messages go through stdout buffering; in the captured output the stderr line surfaces last. The message itself is correct and intended.",

      "codePointer": "cli/browser4-cli/src/main.rs:1252 (eprintln! \"No active session — creating a new one.\")",
All 7 acceptance criteria passed end-to-end against the released `browser4-cli` 4.13.4 (backend v4.13.4-SNAPSHOT at localhost:18182, MockSite on localhost:18080, DeepSeek configured on the backend for `extract`):

- **AC1 — Interact first, then extract:** Filled First Name, Last Name, Email, Country (jp), Experience level (advanced), checked the Testing and newsletter checkboxes, filled Comments, agreed to terms, and submitted. A fresh `htmlsnapshot` after submit followed by `htmlsnapshot get text "#result-data"` returned the post-submit JSON payload containing every entered value (`firstName: Ada`, `lastName: Lovelace`, `email: ada@example.com`, `country: jp`, `topics: [automation, testing]`, `newsletterOptIn: true`, `experienceLevel: advanced`, comments). ✓
- **AC2 — Static page, one field:** `htmlsnapshot get text "#productTitle"` on `/ec/dp/B0E000001` returned `4K OLED TV 55`. ✓
- **AC3 — One field, all matches:** `htmlsnapshot get all text "[class*='product-title']"` on the Electronics listing returned all 6 product titles, not just the first. ✓
- **AC4 — Correlated multi-field rows:** An X-SQL query using `DOM_LOAD_AND_SELECT(@url, 'div[class*="product-card"]')` with `DOM_BASE_URI`, `DOM_FIRST_TEXT` (title/price), and `DOM_FIRST_HREF` (detail link) returned 6 rows with title, price, and detail URL correctly aligned per card. ✓
      "suggestion": "- Route the session-creation notice through the same stdout printing path used for \"Session opened\" so ordering is deterministic\n- Or merge the messages: \"No active session — creating a new one (id …)\" emitted as a single stdout line before navigation output"
- **AC5 — Dynamic/complex page logic:** `eval --file ac5-eval.js --json` returned a structured object (`pageTitle`, `buttonCount: 2`, `linkCount: 0`, `formCount: 0`, 5 headings), each value verified against the live page. ✓
    }
- **AC6 — Natural-language extraction:** `extract "Return the product title, displayed price, rating, and the top three feature bullets as JSON." --stdout` returned title/price/rating matching the page exactly; the page only contains 2 feature bullets, so the extraction was faithful. ✓
- **AC7 — High-volume extraction:** `crawl --seed-file` (5 product URLs) `--depth 0 --sql @ac7-query.sql --format table --refresh` produced 5 rows, one per seed URL, each with correct URL/title/price. ✓

All scratch files (query SQL, JS, seed list, help JSON, SKILL copy) were created under `./.test-sessions/` (git-ignored). No repo files were modified; the worktree changes shown by `git status` (a modified `common.ps1` and coworker draft files) pre-date this session.

## B. Execution Trace

**Preparation**
- Verified cwd = `D:\workspace\Browser4\Browser4-4.13`; created `./.test-sessions/`.
- Ran `browser4-cli help` (full command map) and saved `--help-json` to `.test-sessions/help-json.json`.
- Downloaded and read the complete `https://browser4.io/SKILL.md` (45 KB) plus the local reference docs that govern the scenario: `htmlsnapshot.md`, `x-sql.md`, `x-sql-dom-functions.md`, `crawl.md`, `agent.md`.
- `browser4-cli status` → backend UP; confirmed MockSite HTTP 200 on :18080; `browser4-cli doctor` → "LLM is configured" (DeepSeek via `~/.browser4/config/conf-enabled/application-private.properties`).

**AC1** — `goto form-filling.html` → `snapshot -i --stdout` (obtained refs e2–e9, e14, e16, e17, e172) → `fill e2 "Ada"`, `fill e3 "Lovelace"`, `fill e4 "ada@example.com"`, `select e5 "jp"`, `select e8 "advanced"`, `check e14`, `check e16`, `fill e9 "…"`, `check e17`, `click e172` → `htmlsnapshot` → `get text "#result-data"`. Workaround: my first selector `.result-data` (from the AX label) returned "No elements matched"; the element is an ID (`#result-data`), corrected via `htmlsnapshot grep`.

**AC2/AC3** — `goto` detail page → `htmlsnapshot` → `get text "#productTitle"`; `goto` listing page → `htmlsnapshot` → `get all text "[class*='product-title']"`.

**AC4** — Verified card markup with `htmlsnapshot grep "product-card"`; wrote `ac4-query.sql` under `.test-sessions/`; ran `htmlsnapshot query "http://localhost:18080/ec/b?node=1292115012" --sql "@.test-sessions/ac4-query.sql"` (quoting the `@` path per PowerShell guidance). Note: `DOM_FIRST_HREF(DOM, 'a.product-link')` returned absolute URLs.

**AC5** — `goto interactive-1.html`; wrote `ac5-eval.js`; ran `eval --file .test-sessions/ac5-eval.js --json`; cross-checked counts with `htmlsnapshot` capture + `get all attr button class` + `grep "<form"`.

**AC6** — `goto /ec/dp/B0E000002`; ran `extract "…as JSON." --stdout` (~69 s); verified title/price/rating/features via `htmlsnapshot get text`.

**AC7** — Wrote `ac7-seed.txt` (5 URLs) and `ac7-query.sql`; ran `crawl --seed-file ".test-sessions/ac7-seed.txt" --depth 0 --sql "@.test-sessions/ac7-query.sql" --format table --refresh`; verified the 5-row table.

**Key decisions:** used the documented `--sql @file` / `eval --file` patterns to avoid PowerShell quoting; followed the "interact → re-snapshot → extract" loop exactly as SKILL.md prescribes (refs stayed valid across sequential fills on this static form); treated `extract`'s output as valid once cross-checked against the live DOM.

**Workarounds required:** only the selector form correction in AC1 (`.result-data` → `#result-data`).

```json
{
  "issues": [
    {
      "title": "SKILL.md copy-paste template `htmlsnapshot get text <css> --all` returns only the first match, not all matches",
      "severity": "Medium",
      "category": "Documentation",
      "reproduction": "On a listing page with multiple matches run: browser4-cli htmlsnapshot; then browser4-cli htmlsnapshot get text \"[class*='product-title']\" --all. Compare with browser4-cli htmlsnapshot get all text \"[class*='product-title']\".",
      "expected": "Following the SKILL.md §1 Copy-Paste Template (`htmlsnapshot get text \"<css-selector>\" --all`) should return all matching product titles, as a first-time user would reasonably infer from the template.",
      "actual": "`get text ... --all` returns only the first match (e.g. \"4K OLED TV 55\"); the full list of 6 titles is only returned by `get all text ...`. `--all` merely disables output pagination; it does not switch to querySelectorAll semantics.",
      "rootCause": "The SKILL.md quick-start template conflates the `--all` pagination flag with the `get all` variant. The underlying command semantics are correct and correctly documented in references/htmlsnapshot.md; only the top-level SKILL.md template (and web copy) is wrong. A user copying the template gets silently incomplete data.",
      "codePointer": "skills/browser4-cli/SKILL.md (section 1 Copy-Paste Template); web SKILL.md at browser4.io/SKILL.md",
      "suggestion": "- Replace `htmlsnapshot get text \"<css-selector>\" --all` in the template with `htmlsnapshot get all text \"<css-selector>\"`\n- Add a one-line note under the template distinguishing `get` (first match) from `get all` (querySelectorAll)\n- Consider making the CLI print a hint when `--all` is used with `get` alone (e.g. 'Use get all <field> for all matches')"
    },
    {
      "title": "`htmlsnapshot get` exits 0 when the selector matches nothing, contradicting the documented non-zero exit",
      "severity": "Medium",
      "category": "Reliability",
      "reproduction": "browser4-cli htmlsnapshot get text \"zzz-no-such-element-xyz\"; echo $LASTEXITCODE  (also verifiable with `&& echo PASS` in bash)",
      "expected": "Non-zero exit code when the CSS selector matches nothing, as stated in references/htmlsnapshot.md Error Handling: \"htmlsnapshot get exits non-zero when the CSS selector matches nothing or an element ref (e5) is passed.\"",
      "actual": "Exit code is 0. The CLI prints a helpful \"No elements matched\" message but returns success, so scripts/CI pipelines treat empty extraction as success (silent failure). Control commands do propagate exit codes correctly (e.g. `config set timeout 0` exits 1, unknown command exits 2), so this is specific to the empty-result path.",
      "rootCause": "handle_html_snapshot_get computes `empty_result` and prints guidance but returns Ok(()) from the command handler; no error/exit code is produced for the no-match case. Only invalid selectors/refs and backend failures produce non-zero exits.",
      "codePointer": "cli/browser4-cli/src/main.rs:5913 (handle_html_snapshot_get, empty_result branch around line 5996)",
      "suggestion": "- Return a non-zero exit (e.g. 2) when `empty_result` is true for `get`/`get all`, while still printing the guidance text\n- Add a machine-readable `--json` field like `\"matched\": false` so scripts can distinguish no-match from success\n- Update references/htmlsnapshot.md only if the behavior is intentionally kept as exit 0, and document the discrepancy"
    },
    {
      "title": "`extract --stdout` prints the raw internal ExtractResult envelope instead of the extracted content",
      "severity": "Medium",
      "category": "Product",
      "reproduction": "browser4-cli extract \"Return the product title, displayed price, rating, and the top three feature bullets as JSON.\" --stdout",
      "expected": "Per `browser4-cli help extract`: \"--stdout Print extracted content directly to stdout.\" The output should be the extracted JSON (`{\"title\": ..., \"price\": ..., ...}`), directly consumable by scripts.",
      "actual": "Stdout contains the backend envelope: {\"type\":\"ai.platon.pulsar.agentic.ExtractResult\",\"description\":\"<escaped JSON>\",\"inputToken\":...,\"outputToken\":...,\"inferenceTimeMillis\":...}. The requested JSON is double-encoded inside `description`, and the embedded metadata reports `\"completed\":false` even though extraction succeeded — confusing and wrong for direct consumption.",
      "rootCause": "handle_extract passes the raw server response through as `content` and, in raw mode, `println!(\"{}\", content)` prints that envelope verbatim. No client-side unwrapping of the ExtractResult `description` field is performed, and the empty-extraction detector looks for `completed:false` at paths that this envelope shape does not match.",
      "codePointer": "cli/browser4-cli/src/main.rs:5363 (handle_extract, raw branch around line 5478)",
      "suggestion": "- In `--stdout`/`--raw` mode, unwrap `ExtractResult.description` and print the actual extracted payload\n- Optionally also print a one-line metadata summary to stderr instead of stdout so stdout stays clean for piping\n- Fix the empty-extraction detector to recognize the ExtractResult envelope shape (description containing metadata.completed=false)"
  ],
    },
    {
      "title": "`extract --stdout` still writes a timestamped file into the workspace `.browser4-cli/snapshot/` directory",
  "assessment": {
      "severity": "Low",
    "completionStatus": "Successful — all 7 acceptance criteria passed using only the released browser4-cli 4.13.4 binary against the production backend; the usability evaluation was completed alongside the task.",
      "category": "UX",
      "reproduction": "Run browser4-cli extract \"...\" --stdout, then check Get-ChildItem .browser4-cli/snapshot/extract-*.txt (a new file with the same content is created).",
    "successRate": "100% — all scenario steps (AC1–AC7) completed and verified; no environment blockers (LLM key was configured, MockSite and backend were up).",
      "expected": "With `--stdout`, no file should be written — help text says \"Output is saved to a timestamped file by default. Use --stdout (or --raw) to print to stdout instead.\"",
    "issuesFound": 8,
      "actual": "A file `.browser4-cli/snapshot/extract-<timestamp>.txt` is always created regardless of `--stdout`/`--raw`, because `save_snapshot` is called unconditionally. This wrote to the repository working tree during the evaluation (git-ignored, but still workspace pollution).",
      "rootCause": "handle_extract always calls `save_snapshot(&out_path, content)` before checking the raw flag; the raw flag only controls whether content is also printed to stdout.",
      "codePointer": "cli/browser4-cli/src/main.rs:5448 (handle_extract, unconditional save_snapshot call)",
      "suggestion": "- Skip `save_snapshot` when `--stdout`/`--raw`/`--filename` is provided (or document that extraction always archives)\n- When `--filename` is provided, write only to that path instead of also writing to the snapshot dir\n- Surface the saved path in non-stdout mode so users know where archives land"
    "majorBlockers": "None. The task was fully executable with documented commands; issues found are documentation inaccuracies, silent-failure/exit-code behavior, extract output envelope noise, and an undocumented workspace output directory — all workaroundable.",
    },
    {
      "title": "Snapshot/screenshot/extract outputs go to a hardcoded workspace-relative `.browser4-cli/snapshot/` dir that is undocumented and not redirected by BROWSER4_CLI_STATE_DIR",
      "severity": "Medium",
      "category": "Documentation",
      "reproduction": "Run any `snapshot`, `screenshot`, or `extract` command from a repo checkout; observe files created under <cwd>/.browser4-cli/snapshot/ even though session state lives in ~/.browser4. Attempt to redirect with $env:BROWSER4_CLI_STATE_DIR — snapshot output location does not change.",
    "mostConfusingAspects": "For a first-time user: (1) the SKILL.md template `get text ... --all` looks like it returns all matches but returns only the first; (2) `extract --stdout` prints an internal ExtractResult envelope instead of the requested JSON; (3) `htmlsnapshot query` dumps a large JSON envelope by default and the readable `--format table` is only discoverable via --help; (4) command outputs silently accumulate in an undocumented `./.browser4-cli/snapshot/` directory; (5) `htmlsnapshot get` returning exit 0 on no match makes empty results indistinguishable from success in scripts.",
      "expected": "The documentation (SKILL.md, `browser4-cli help`) explains where command outputs are stored and provides a way to redirect them; the described fallback is `./.browser4-cli-state`, but outputs should follow the state/runtime dir or an env override.",
    "mostValuableImprovements": "Fix the SKILL.md copy-paste template and document `--format`/`--result-only` for query; make `htmlsnapshot get` exit non-zero on no-match; make `extract --stdout` print clean unwrapped content and stop writing an archive file; document (or redirect via env var) the `.browser4-cli/snapshot/` output directory.",
      "actual": "The snapshot/screenshot/extract output directory is hardcoded to `./.browser4-cli/snapshot/` relative to the current working directory (snapshot.rs SNAPSHOT_DIR), independent of BROWSER4_CLI_STATE_DIR. The docs only mention `.browser4-cli-state` as the workspace fallback for state, and never document `.browser4-cli/snapshot/`. In a source checkout this accumulates snapshot/extract files in the repo tree (mitigated only by .gitignore lines 131–134).",
    "usabilityRating": 7
      "rootCause": "snapshot_dir() builds the path from a hardcoded constant [\".browser4-cli\", \"snapshot\"] relative to CWD; no env var or config key overrides it, and the SKILL.md/help docs don't mention it.",
  }
      "codePointer": "cli/browser4-cli/src/snapshot.rs:16 (snapshot_dir(), SNAPSHOT_DIR at line 9)",
      "suggestion": "- Document `.browser4-cli/snapshot/` in SKILL.md and `help` (alongside `snapshot clean`), and/or honor BROWSER4_CLI_STATE_DIR for snapshot outputs\n- Add an env var (e.g. BROWSER4_CLI_SNAPSHOT_DIR) or `config set snapshot-dir <path>` override\n- Consider defaulting outputs to the state dir (~/.browser4/snapshot) instead of CWD-relative paths, which is safer for source-tree usage"
    },
    {
      "title": "SKILL.md §4a claims `htmlsnapshot get text article` \"auto-discovers content\", but `article` is a plain tag selector",
      "severity": "Low",
      "category": "Documentation",
      "reproduction": "On a page without an <article> tag (e.g. MockSite product page): browser4-cli htmlsnapshot; browser4-cli htmlsnapshot get text \"article\" → \"No elements matched\".",
      "expected": "Either the command should auto-discover the main content as the decision tree implies, or the docs should not describe `article` as auto-discovery.",
      "actual": "The command simply runs querySelector('article'); on MockSite pages there are no <article> elements, so it returns an empty result with exit 0. Auto-discovery is a separate feature of `htmlsnapshot inspect`, not of `get text article`.",
      "rootCause": "The §4a decision tree line \"Don't know the right CSS selector? → htmlsnapshot get text article (auto-discovers content)\" is a misleading simplification; no auto-discovery exists in the get path.",
      "codePointer": "skills/browser4-cli/SKILL.md (section 4a decision tree); references/htmlsnapshot.md (get command docs)",
      "suggestion": "- Replace the decision-tree line with `htmlsnapshot inspect` (which genuinely auto-discovers recurring patterns) or clarify that `article` is just an example tag selector\n- Add a note that `get text` accepts any CSS selector and that `inspect`/`summary` are the discovery tools"
    },
    {
      "title": "`htmlsnapshot query` default output is a raw JSON envelope and the human-readable `--format table` / `--result-only` flags are absent from the main documentation",
      "severity": "Low",
      "category": "Discoverability",
      "reproduction": "browser4-cli htmlsnapshot query \"http://localhost:18080/ec/b?node=1292115012\" --sql \"@query.sql\" → full JSON envelope with id/statusCode/timestamps; only `browser4-cli htmlsnapshot query --help` reveals --format table and --result-only.",
      "expected": "The primary docs (SKILL.md §4e, references/htmlsnapshot.md Query section) should mention the output format options so a first-time user knows a readable table output exists; or the default should be human-readable.",
      "actual": "SKILL.md and htmlsnapshot.md describe `query` without any output-format flags; the user gets a noisy machine envelope by default and must discover `--help` to find `--format table`. The task instructions themselves only specified the envelope path, so the scenario still succeeded, but the readable option was effectively hidden.",
      "rootCause": "Documentation lag: the `--format`/`--result-only` options exist in help.rs/commands.rs but were not added to SKILL.md or the htmlsnapshot reference.",
      "codePointer": "skills/browser4-cli/references/htmlsnapshot.md (Query section); skills/browser4-cli/SKILL.md (4e X-SQL Quickstart)",
}
      "suggestion": "- Document `--format json|csv|table` and `--result-only` in the htmlsnapshot.md Query section and SKILL.md §4e\n- Add a table-format example to the X-SQL quickstart so the default envelope doesn't surprise first-time users\n- Consider making `--format table` the default when stdout is a TTY"
```
    },

**Overall assessment:** The task succeeded at 100% — every documented workflow in §4a executed correctly on the first or second try, and the core loop (snapshot → interact → re-snapshot → extract) held up well on the MockSite fixtures. Usability is good but not great: the biggest friction is documentation that misleads (the `--all` template, the "auto-discovery" claim, the exit-code contract, hidden query format flags) plus `extract` output that isn't what `--stdout` promises. Fixing the top four issues would make first-run experience markedly smoother; none of them block the documented extraction paths.
      "title": "First-run `goto` prints \"No active session — creating a new one.\" on stderr after the successful output, appearing contradictory",
tokens used
      "severity": "Low",
116,249
      "category": "UX",
      "reproduction": "In a fresh CLI environment, run: browser4-cli goto \"http://localhost:18080/generated/form-filling.html\" and observe message ordering.",
      "expected": "Session creation status should appear before/with the \"Session opened: ...\" and navigation output so the messages are coherent.",
      "actual": "The stderr message \"No active session — creating a new one.\" is emitted before session creation in code, but appears after the \"Session opened\"/\"Navigated to\"/snapshot-path output in the captured console due to stdout/stderr stream buffering, making it look like the CLI first navigated and then created a session.",
      "rootCause": "eprintln! at main.rs:1252 writes to stderr (unbuffered) while session/navigation messages go through stdout buffering; in the captured output the stderr line surfaces last. The message itself is correct and intended.",
      "codePointer": "cli/browser4-cli/src/main.rs:1252 (eprintln! \"No active session — creating a new one.\")",
      "suggestion": "- Route the session-creation notice through the same stdout printing path used for \"Session opened\" so ordering is deterministic\n- Or merge the messages: \"No active session — creating a new one (id …)\" emitted as a single stdout line before navigation output"
    }
  ],
  "assessment": {
    "completionStatus": "Successful — all 7 acceptance criteria passed using only the released browser4-cli 4.13.4 binary against the production backend; the usability evaluation was completed alongside the task.",
    "successRate": "100% — all scenario steps (AC1–AC7) completed and verified; no environment blockers (LLM key was configured, MockSite and backend were up).",
    "issuesFound": 8,
    "majorBlockers": "None. The task was fully executable with documented commands; issues found are documentation inaccuracies, silent-failure/exit-code behavior, extract output envelope noise, and an undocumented workspace output directory — all workaroundable.",
    "mostConfusingAspects": "For a first-time user: (1) the SKILL.md template `get text ... --all` looks like it returns all matches but returns only the first; (2) `extract --stdout` prints an internal ExtractResult envelope instead of the requested JSON; (3) `htmlsnapshot query` dumps a large JSON envelope by default and the readable `--format table` is only discoverable via --help; (4) command outputs silently accumulate in an undocumented `./.browser4-cli/snapshot/` directory; (5) `htmlsnapshot get` returning exit 0 on no match makes empty results indistinguishable from success in scripts.",
    "mostValuableImprovements": "Fix the SKILL.md copy-paste template and document `--format`/`--result-only` for query; make `htmlsnapshot get` exit non-zero on no-match; make `extract --stdout` print clean unwrapped content and stop writing an archive file; document (or redirect via env var) the `.browser4-cli/snapshot/` output directory.",
    "usabilityRating": 7
  }
}
```

**Overall assessment:** The task succeeded at 100% — every documented workflow in §4a executed correctly on the first or second try, and the core loop (snapshot → interact → re-snapshot → extract) held up well on the MockSite fixtures. Usability is good but not great: the biggest friction is documentation that misleads (the `--all` template, the "auto-discovery" claim, the exit-code contract, hidden query format flags) plus `extract` output that isn't what `--stdout` promises. Fixing the top four issues would make first-run experience markedly smoother; none of them block the documented extraction paths.
