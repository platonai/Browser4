Reading prompt from stdin...
2026-08-14T07:13:26.345407Z ERROR codex_core::session::session: failed to load skill C:\Users\pereg\.agents\skills\prd-generator\SKILL.md: missing YAML frontmatter delimited by ---
2026-08-14T07:13:26.345453Z ERROR codex_core::session::session: failed to load skill C:\Users\pereg\.agents\skills\tech-blog-cn\SKILL.md: missing YAML frontmatter delimited by ---
2026-08-14T07:13:26.345456Z ERROR codex_core::session::session: failed to load skill C:\Users\pereg\.agents\skills\wechat-formatter\SKILL.md: missing YAML frontmatter delimited by ---
2026-08-14T07:13:26.345458Z ERROR codex_core::session::session: failed to load skill C:\Users\pereg\.agents\skills\xiaohongshu-writer\SKILL.md: missing YAML frontmatter delimited by ---
OpenAI Codex v0.147.0
--------
workdir: D:\workspace\Browser4\Browser4-4.13
model: deepseek-v4-flash
provider: deepseek
approval: never
sandbox: danger-full-access
reasoning effort: high
reasoning summaries: none
session id: 019fff1e-5718-7bd1-b753-ffa48d3771b3
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
Before running this scenario, ensure MockSite is running on localhost:18080 (`./bin/test.ps1 mock-site`).

This scenario covers every branch in **SKILL.md §4b — Choosing Bulk/Scale Approach**.

## Acceptance Criteria

1. **AC1 — Single list page:** Use `htmlsnapshot query` with `DOM_LOAD_AND_SELECT` against one MockSite listing page.
2. **AC2 — Multiple known URLs:** Use `crawl --seed-file ... --depth 0 --sql @query.sql` against several specific product URLs.
3. **AC3 — Crawl from a start URL:** Use `crawl <url> --out-link-selector ... --depth N` on the generated crawl fixture.
4. **AC4 — Parallel execution:** Use `swarm create` and `swarm query --seed-file ...` for the same style of structured extraction at higher throughput.
5. **AC5 — Repeated monitoring:** Use `loop -- eval ... -i ...` against a named session that is already on a MockSite page.
6. **AC6 — Just a few URLs in a shell script:** Run the few-URL pattern with a simple shell loop (PowerShell loop on Windows is acceptable) and add a short wait between iterations.

## Steps

### 1. Single list page (AC1)

1. Go to `http://localhost:18080/ec/b?node=1292115012`.
2. Capture an HTML snapshot.
3. Write and run an X-SQL query that extracts one row per product card from the listing page.
4. Verify the result set contains correlated fields such as title + price for each product on that one page.

### 2. Multiple known URLs via seed file (AC2)

1. Create a seed file with at least 3 known MockSite product detail URLs, for example:
   - `http://localhost:18080/ec/dp/B0E000001`
   - `http://localhost:18080/ec/dp/B0E000002`
   - `http://localhost:18080/ec/dp/B0E000003`
2. Write an X-SQL query file that extracts `DOM_BASE_URI(DOM)`, `#productTitle`, and `#product-price`.
3. Run:

```
crawl --seed-file <path-to-seed-file> --depth 0 --sql @<query-file> --format table --refresh
```

4. Verify one structured result row is returned for each seed URL.

### 3. Crawl from a start URL with link discovery (AC3)

1. Go to the crawl fixture hub:

```
http://localhost:18080/generated/crawl/index.html
```

2. Run a crawl with link discovery, for example:

```
crawl http://localhost:18080/generated/crawl/index.html -d 2 -ol "a.product" -olp "/product/"
```

3. Verify product pages are discovered from the start URL and that unrelated category links are excluded by the selector/pattern filters.

### 4. Parallel execution with swarm (AC4)

1. Create a swarm session in headless mode with small but real parallelism, for example `--max-browser-contexts 2` and `--max-open-tabs 4`.
2. Reuse the seed file and query file from AC2.
3. Run `swarm query --sql @<query-file> --seed-file <path-to-seed-file> --refresh`.
4. Poll `swarm status`, then fetch the completed payload with `swarm result`.
5. Verify the extraction succeeds across multiple URLs and close the swarm session afterward.

### 5. Repeated monitoring with loop (AC5)

1. Open a named browser session on a stable MockSite page, for example:

```
-s price-watch goto http://localhost:18080/ec/dp/B0E000001
```

2. Start a short named loop in subcommand mode:

```
loop --name mock-price-watch --count 2 -i 10 -- -s price-watch eval "document.querySelector('#product-price').textContent.trim()"
```

3. Check `loop --list` or `loop --status --name mock-price-watch` while it runs.
4. Verify the loop executes repeated page checks without needing a multi-page crawl or swarm job.

### 6. Just a few URLs in a shell script (AC6)

1. Prepare a short list of 2-3 MockSite product URLs.
2. Exercise the lightweight few-URL pattern:
   - On bash: `for url in ...; do browser4-cli goto "$url"; ...; sleep 2; done`
   - On PowerShell: iterate the same list with `ForEach-Object`, calling `goto`, `htmlsnapshot`, and `htmlsnapshot get text "#productTitle"` with `Start-Sleep` between pages
3. Verify this ad-hoc loop is sufficient for a very small URL set and does not require crawl, swarm, or loop.

codex
I'll start by preparing the environment: verifying the working directory, creating the test-sessions folder, checking the CLI help, and confirming MockSite/backend availability.
exec
"C:\\Program Files\\PowerShell\\7\\pwsh.exe" -Command 'Get-Location; New-Item -ItemType Directory -Force -Path ".test-sessions" | Out-Null; Test-Path ".test-sessions"' in D:\workspace\Browser4\Browser4-4.13
exec
"C:\\Program Files\\PowerShell\\7\\pwsh.exe" -Command 'browser4-cli help' in D:\workspace\Browser4\Browser4-4.13
 succeeded in 330ms:
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

 succeeded in 350ms:

Path
----
D:\workspace\Browser4\Browser4-4.13
True


exec
"C:\\Program Files\\PowerShell\\7\\pwsh.exe" -Command 'try { $r = Invoke-WebRequest -Uri "http://localhost:18080/ec/b?node=1292115012" -UseBasicParsing -TimeoutSec 10; "MockSite HTTP $($r.StatusCode)" } catch { "MockSite UNREACHABLE: $($_.Exception.Message)" }' in D:\workspace\Browser4\Browser4-4.13
 succeeded in 506ms:
MockSite HTTP 200

codex
Working directory, `.test-sessions/`, CLI help, and MockSite are all confirmed. Now I'll read the official SKILL.md documentation and check the backend server status.
web search: 
web search: https://browser4.io/SKILL.md#ws_call_id=call_00_riOZ1vqsY6P364YE6n0P2142
codex
The page reader had trouble; I'll fetch the raw SKILL.md directly and also check the local skill doc for §4b.
exec
"C:\\Program Files\\PowerShell\\7\\pwsh.exe" -Command '$out = ".test-sessions'"\\browser4-io-SKILL.md\"; try { Invoke-WebRequest -Uri \"https://browser4.io/SKILL.md\" -UseBasicParsing -TimeoutSec 30 -OutFile "'$out; "Downloaded: $((Get-Item $out).Length) bytes" } catch { "FAILED: $($_.Exception.Message)" }' in D:\workspace\Browser4\Browser4-4.13
exec
"C:\\Program Files\\PowerShell\\7\\pwsh.exe" -Command 'browser4-cli status; browser4-cli --version' in D:\workspace\Browser4\Browser4-4.13
exec
"C:\\Program Files\\PowerShell\\7\\pwsh.exe" -Command "Get-ChildItem -Path \"skills\\browser4-cli\" -Recurse -File | Select-Object -ExpandProperty FullName" in D:\workspace\Browser4\Browser4-4.13
 succeeded in 482ms:
Browser4 Status
===============
CLI version: 4.13.4
Server URL: http://localhost:18182
Installed bundle: v4.13.4 (at 2026-08-14T06:32:39.351436100+00:00)
Server health: UP
Server version: 4.13.4-SNAPSHOT
browser4-cli 4.13.4

 succeeded in 372ms:
D:\workspace\Browser4\Browser4-4.13\skills\browser4-cli\SKILL.md
D:\workspace\Browser4\Browser4-4.13\skills\browser4-cli\references\agent.md
D:\workspace\Browser4\Browser4-4.13\skills\browser4-cli\references\attach.md
D:\workspace\Browser4\Browser4-4.13\skills\browser4-cli\references\crawl.md
D:\workspace\Browser4\Browser4-4.13\skills\browser4-cli\references\css-selector-bridge.md
D:\workspace\Browser4\Browser4-4.13\skills\browser4-cli\references\development.md
D:\workspace\Browser4\Browser4-4.13\skills\browser4-cli\references\htmlsnapshot-scenarios-advanced.md
D:\workspace\Browser4\Browser4-4.13\skills\browser4-cli\references\htmlsnapshot-scenarios-amazon.md
D:\workspace\Browser4\Browser4-4.13\skills\browser4-cli\references\htmlsnapshot-scenarios-audit.md
D:\workspace\Browser4\Browser4-4.13\skills\browser4-cli\references\htmlsnapshot-scenarios-extraction.md
D:\workspace\Browser4\Browser4-4.13\skills\browser4-cli\references\htmlsnapshot-scenarios.md
D:\workspace\Browser4\Browser4-4.13\skills\browser4-cli\references\htmlsnapshot.md
D:\workspace\Browser4\Browser4-4.13\skills\browser4-cli\references\load-options-guide.md
D:\workspace\Browser4\Browser4-4.13\skills\browser4-cli\references\loop.md
D:\workspace\Browser4\Browser4-4.13\skills\browser4-cli\references\power-dom.md
D:\workspace\Browser4\Browser4-4.13\skills\browser4-cli\references\shell-quoting.md
D:\workspace\Browser4\Browser4-4.13\skills\browser4-cli\references\skills.md
D:\workspace\Browser4\Browser4-4.13\skills\browser4-cli\references\snapshot.md
D:\workspace\Browser4\Browser4-4.13\skills\browser4-cli\references\storage-state.md
D:\workspace\Browser4\Browser4-4.13\skills\browser4-cli\references\swarm.md
D:\workspace\Browser4\Browser4-4.13\skills\browser4-cli\references\webdb.md
D:\workspace\Browser4\Browser4-4.13\skills\browser4-cli\references\x-sql-array-functions.md
D:\workspace\Browser4\Browser4-4.13\skills\browser4-cli\references\x-sql-dom-functions.md
D:\workspace\Browser4\Browser4-4.13\skills\browser4-cli\references\x-sql-dom-load-select.md
D:\workspace\Browser4\Browser4-4.13\skills\browser4-cli\references\x-sql-dom-select-functions.md
D:\workspace\Browser4\Browser4-4.13\skills\browser4-cli\references\x-sql-string-functions.md
D:\workspace\Browser4\Browser4-4.13\skills\browser4-cli\references\x-sql.md

 succeeded in 2439ms:
Downloaded: 45813 bytes

codex
Backend is healthy (v4.13.4 at localhost:18182). Now reading the official SKILL.md (downloaded copy) completely, in chunks.
exec
"C:\\Program Files\\PowerShell\\7\\pwsh.exe" -Command '$c = Get-Content ".test-sessions'"\\browser4-io-SKILL.md\" -Raw; \"Total lines: "'$(($c -split "`n").Count)"; $c.Substring(0, [Math]::Min(12000, $c.Length))' in D:\workspace\Browser4\Browser4-4.13
 succeeded in 354ms:
Total lines: 751
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
- **Machine-readable output:** Use `--json` either before or after the command: `browser4-cli --json tab-list` or `browser4-cli tab-list --json`. Output is a JSON envelope: `{"command":"tab-list","output":{"count":N,"tabs":[{"index":0,"guid":"...","url":"...","title":"..."}]},"status":"ok"}`. The `tabs` array and `count` are nested insi

exec
"C:\\Program Files\\PowerShell\\7\\pwsh.exe" -Command '$c = Get-Content ".test-sessions'"\\browser4-io-SKILL.md\" -Raw; "'$c.Substring(12000, [Math]::Min(12000, $c.Length - 12000))' in D:\workspace\Browser4\Browser4-4.13
 succeeded in 326ms:
de `output`.
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

**Warning:** Multiple `get all` calls produce un

exec
"C:\\Program Files\\PowerShell\\7\\pwsh.exe" -Command '$c = Get-Content ".test-sessions'"\\browser4-io-SKILL.md\" -Raw; "'$c.Substring(24000, [Math]::Min(12000, $c.Length - 24000))' in D:\workspace\Browser4\Browser4-4.13
 succeeded in 319ms:
aligned arrays (different lengths, different order). For correlated fields, use `query` with `DOM_LOAD_AND_SELECT` scoped to a parent container.

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


exec
"C:\\Program Files\\PowerShell\\7\\pwsh.exe" -Command '$c = Get-Content ".test-sessions'"\\browser4-io-SKILL.md\" -Raw; "'$c.Substring(36000, [Math]::Min(12000, $c.Length - 36000))' in D:\workspace\Browser4\Browser4-4.13
 succeeded in 351ms:
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
I've now read the full official SKILL.md. Next, I'll read the local skill doc's §4b and the relevant reference docs (crawl, swarm, loop, htmlsnapshot/x-sql) to learn exact command syntax.
exec
"C:\\Program Files\\PowerShell\\7\\pwsh.exe" -Command '$c = Get-Content "skills'"\\browser4-cli\\SKILL.md\" -Raw; \"Length: "'$($c.Length)"; $idx = $c.IndexOf("4b. Choosing Bulk"); if ($idx -lt 0) { $idx = $c.IndexOf("Bulk/Scale") }; if ($idx -ge 0) { $c.Substring($idx, [Math]::Min(4000, $c.Length - $idx)) } else { "SECTION NOT FOUND" }' in D:\workspace\Browser4\Browser4-4.13
exec
"C:\\Program Files\\PowerShell\\7\\pwsh.exe" -Command "Get-Content \"skills\\browser4-cli\\references\\crawl.md\" -Raw" in D:\workspace\Browser4\Browser4-4.13
 succeeded in 331ms:
Length: 45376
4b. Choosing Bulk/Scale Approach

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
browser4-cli htmlsnapshot inspect --selector-base6

 succeeded in 326ms:
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


codex
Now reading the swarm, loop, and X-SQL reference docs to learn the exact syntax for the remaining acceptance criteria.
exec
"C:\\Program Files\\PowerShell\\7\\pwsh.exe" -Command "Get-Content \"skills\\browser4-cli\\references\\swarm.md\" -Raw" in D:\workspace\Browser4\Browser4-4.13
exec
"C:\\Program Files\\PowerShell\\7\\pwsh.exe" -Command "Get-Content \"skills\\browser4-cli\\references\\loop.md\" -Raw" in D:\workspace\Browser4\Browser4-4.13
 succeeded in 333ms:
---
title: "Swarm — Parallel Scraping & Structured Data Extraction"
description: "Reference for swarm commands (create, submit, query, status, result, list, close). Orchestrate parallel scraping and structured data extraction across multiple browser contexts."
tier: procedure
---

# Swarm — Parallel Scraping & Structured Data Extraction

Orchestrate parallel scraping and structured data extraction across multiple browser contexts. All subcommands use spaced form (`swarm <subcommand>`) and are task-ID based.

## Quick Start

```bash
browser4-cli swarm create --display-mode HEADLESS
browser4-cli swarm query --sql @query.sql --seed-file ./urls.txt --refresh
browser4-cli swarm status <task-id>       # poll until isDone: true
browser4-cli swarm result <task-id>       # fetch the result payload
browser4-cli swarm close                  # release resources (or use close)
```

## When to Use

Use **swarm** when you need parallel execution across multiple browser contexts for high throughput. Prefer **crawl** for simpler sequential multi-page workflows with built-in link discovery. Prefer **loop** for repeated monitoring at intervals.

## How It Works

A swarm session manages multiple isolated browser contexts running in parallel. Each context has its own profile/cookie jar and can open tabs, load pages, and execute X-SQL queries independently. Jobs are enqueued and distributed across contexts automatically.

```
swarm create        →  opens a swarm session (fixed session ID: SWARM)
swarm submit        →  enqueues scrape jobs
swarm query         →  enqueues X-SQL jobs (preferred for structured extraction)
swarm status <id>   →  polls job progress (metadata only: isDone, statusCode, message)
swarm result <id>   →  fetches the completed job payload (resultSet, pageContentBytes)
swarm list          →  lists all tracked swarm tasks
swarm close         →  closes the swarm session and releases resources
```

> **Note:** Task IDs are UUIDs generated by the backend (e.g. `ca40ced0-2239-4209-9d81-34bcd50e50c1`). Use `swarm list` to rediscover task IDs, or save them from the submission output.

## Patterns

### 1. Create a Swarm Session

```bash
browser4-cli swarm create [--profile-mode SEQUENTIAL|TEMPORARY] [--max-open-tabs 8] [--max-browser-contexts 2] [--display-mode GUI|HEADLESS|SUPERVISED] [--clear-stale]
```

| Option | Default | Description |
|---|---|---|
| `--profile-mode` | `SEQUENTIAL` | `SEQUENTIAL` reuses profile across runs; `TEMPORARY` starts fresh |
| `--max-open-tabs` | `8` | Max open tabs per browser context |
| `--max-browser-contexts` | `2` | Number of isolated browser instances |
| `--display-mode` | `GUI` | `GUI`, `HEADLESS`, or `SUPERVISED` |
| `--clear-stale` | (flag) | Automatically clear stale tasks from prior sessions before creating |

The session persists until `browser4-cli swarm close` or `close`.

### 2. Submit Scrape Jobs

```bash
browser4-cli swarm submit <url> [--seed-file ./urls.txt] [--deadline ISO] [--expires 1d] [--refresh] [--parse] [--wait]
```

| Argument/Option | Description |
|---|---|
| `url` (positional) | Direct URL to scrape. Omit when using `--seed-file` alone |
| `--seed-file` | Plain-text file, one URL per line. Blank lines and `#` comments ignored |
| `--deadline` | ISO 8601 deadline (e.g. `2026-03-30T23:59:59Z`) |
| `--expires` | Cache expiration (e.g. `1d`, `1h`, `30m`) |
| `--refresh` | Force fresh fetch, ignore cache |
| `--parse` | Parse page immediately after fetching (required for later X-SQL queries) |
| `--wait` | Block until all submitted jobs complete (polls every 2s, 5-minute timeout) |

> **Important:** Without `--sql`, `swarm submit` only fetches and loads the page — no data is extracted. The `resultSet` will be empty. The `pageContentBytes` field in the result confirms the page was fetched. For structured data extraction, use `swarm query --sql @query.sql` instead.

> **Tip:** Use `--wait` to avoid manual polling for short-lived jobs. The CLI prints a progress summary when all jobs complete.

> **Note:** Prefer `swarm query` over `swarm submit --sql` for X-SQL extraction — it enforces `--sql` as required.

### 3. Submit X-SQL Extraction Jobs (Preferred)

```bash
browser4-cli swarm query [url] --sql "<query>" [--seed-file ./urls.txt] [--refresh] [--wait]
```

| Argument/Option | Required | Description |
|---|---|---|
| `url` (positional) | No | Target page URL. Omit when using `--seed-file` alone |
| `--sql` | **Yes** | X-SQL query. Inline text or file path prefixed with `@` (e.g. `--sql @query.sql`). Use `@url` as placeholder |
| `--seed-file` | No | Run the same query against every URL in the file |
| `--wait` | No | Block until all submitted jobs complete (polls every 2s, 5-minute timeout) |

`--deadline` and `--expires` also supported.

```bash
# Query file against every URL in a seed file
browser4-cli swarm query --sql @query.sql --seed-file ./urls.txt --refresh

# With --wait: blocks until all jobs finish
browser4-cli swarm query --sql @query.sql --seed-file ./urls.txt --refresh --wait
```

Core X-SQL pattern: `SELECT <fn>(dom, <selector>) FROM DOM_LOAD_AND_SELECT(@url, '<scope>');`

Common extraction functions: `DOM_BASE_URI`, `DOM_FIRST_TEXT`, `DOM_ALL_TEXTS`, `DOM_FIRST_HREF`, `DOM_ALL_HREFS`, `DOM_FIRST_SRC`, `DOM_ABS_SRC`, `DOM_FIRST_SLIM_HTML`, `DOM_ALL_SLIM_HTML`, `DOM_FIRST_ATTR`. Full reference: [x-sql.md](x-sql.md).

> **Tip:** `DOM_FIRST_SRC` and `DOM_FIRST_ATTR(..., 'src')` return **relative** URLs (e.g. `/images/photo.jpg`). Use `DOM_ABS_SRC(DOM)` to get the resolved **absolute** URL (e.g. `https://example.com/images/photo.jpg`). For image extraction, prefer `DOM_ABS_SRC` to avoid manual URL resolution.

> **Tip:** X-SQL result rows do **not** automatically include the source URL — they only contain the columns you explicitly select. To make results self-contained, always include the source URL as a column:
> ```sql
> SELECT DOM_BASE_URI(DOM) AS url, DOM_FIRST_TEXT(DOM, '#title') AS title
> FROM DOM_LOAD_AND_SELECT(@url, ':root')
> ```
> `DOM_BASE_URI(DOM)` returns the absolute URL of the page. `DOM_LOCATION(DOM)` can also be used (returns the same value).

### 4. Poll Status & Fetch Results

```bash
browser4-cli swarm status <task-id>   # returns metadata: id, isDone, statusCode, message
browser4-cli swarm result <task-id>   # returns result payload: resultSet, pageContentBytes
```

Wait for `isDone: true` before calling `swarm result`.

Example status output:
```json
{"id":"<task-id>","isDone":true,"statusCode":200,"message":"","lastModifiedTime":"2026-03-30T12:00:00Z"}
```

Example result output:
```json
{"id":"<task-id>","resultSet":[{"url":"...","title":"...","price":"$29.99"}],"pageContentBytes":null,"error":null}
```

### 5. List Tracked Tasks

```bash
browser4-cli swarm list           # list all tracked swarm tasks with live backend status
browser4-cli swarm list --clear   # remove all tracked swarm tasks
```

> **Note:** `swarm list` queries the backend for live status of each tracked task on every invocation. It shows a status summary (N total, X completed, Y queued, Z failed) followed by the task table. The STATUS column uses task-oriented labels: `queued` (waiting for worker), `processing`, `completed`, or `failed (<reason>)`. The COMMAND column distinguishes `swarm-submit` from `swarm-query`. Use `--clear` to clean up stale entries between sessions.

> **Tip:** `swarm create` warns if stale tasks from prior sessions are still tracked, since old completed tasks can interfere with the worker pool. In interactive mode (TTY), it prompts `Clear them now? [Y/n]` — press Enter or type `y` to clear. Use `swarm create --clear-stale` for non-interactive/scripted use, or `swarm list --clear` to remove stale entries manually.

### 6. Close the Swarm Session

```bash
browser4-cli swarm close   # equivalent to close when swarm session is active
```

Also accessible via: `close`, `close-all`, or `kill-all`. The swarm session is included in `close-all` enumeration — if `close-all` reports "0 session(s)" but swarm tasks are still tracked, the swarm session was already closed.

## Task Lifecycle States

Swarm tasks progress through these states:

| `lifecycleState` | `statusCode` | `status` (raw) | Meaning |
|---|---|---|---|
| `queued` | `201` | `Created` | Task submitted, waiting for an available worker |
| `processing` | `202` | `Accepted` | Task picked up by a worker, page is loading or X-SQL is running |
| `completed` | `200` | `OK` | Task finished successfully; use `swarm result <id>` to get data |
| `completed` | `200` | `OK` | `isDone: true` — the canonical completion indicator |
| `failed (<reason>)` | `4xx`/`5xx` | varies | Task failed with an error code; check `message` for details |

> **Tip:** `swarm status` outputs both the raw `status` (HTTP-derived) and a `lifecycleState` field with task-oriented labels. Use `lifecycleState` for programmatic checks; use the raw fields for debugging.

## Errors & Recovery

| Symptom | Recovery |
|----------|---------|
| All subcommands exit non-zero | Check stderr for details |
| Task not done yet | `swarm status` shows `isDone: false` — wait and retry, or use `--wait` on submission. A `statusCode` of `200` also indicates completion even if `isDone` lags. |
| Task stuck as "queued" (201) | Workers may be busy. Check with `swarm list`. If all tasks show `queued` for >30s, the worker pool may be stalled — try `swarm list --clear` to remove stale tasks, then `swarm close` and `swarm create` to restart the session. Add `--wait` to block until jobs complete. |
| Missing LLM/API key | Surfaces as task-level error in `swarm status` / `swarm result` |
| Long-running tasks | Set `--deadline` to bound execution |
| Swarm subcommands in batch mode | Not supported — use standalone commands |
| Lost task IDs | Use `swarm list` to rediscover all tracked tasks |
| Stale tasks in list | Use `swarm list --clear` to remove old entries, or `swarm create --clear-stale` to clear and recreate in one step |

## Notes

- The swarm session uses fixed session ID `SWARM` — it doesn't share state with named (`-s <name>`) or default sessions.
- Seed files support thousands of URLs; control parallelism with `--max-open-tabs` and `--max-browser-contexts`.
- Always close the swarm session when done (`browser4-cli swarm close` or `close`) to free resources.
- Task IDs are UUIDs (e.g. `ca40ced0-2239-4209-9d81-34bcd50e50c1`). Save them or use `swarm list` to rediscover.
- `swarm status` shows metadata only (isDone, statusCode, message). Use `swarm result` for the actual data payload (resultSet).
- Both `swarm submit` and `swarm query` tasks are tracked and appear in `swarm list`.
- **Windows Git Bash users:** Arguments with dashes (`--sql`, `--stdout`, `-v`) can be mangled by the bash→pwsh boundary. Quote them individually: `./b4w.ps1 "swarm" "query" "--sql" "@query.sql" "--seed-file" "./urls.txt"`. Or use `pwsh` directly and run commands inside PowerShell, or use the `b4w.sh` bash wrapper which handles the quoting automatically.


 succeeded in 348ms:
---
title: "Loop Command Reference"
description: "Reference for the loop command. Execute a task repeatedly on a configurable interval with persistence and resume support."
tier: procedure
---

# Loop Command Reference

Execute a task repeatedly on a configurable interval. Supports plain text commands
(auto-detected as X-SQL by the server), shell commands, and nested `browser4-cli`
subcommands. Progress is persisted to disk so loops survive process restarts.

## Quick start

```bash
# Plain text command every hour (default interval)
browser4-cli loop "load https://example.com and extract the page title"

# Shell command every 60 seconds, 10 iterations
browser4-cli loop --shell "curl -s https://api.example.com/health" -i 60 -n 10

# Run eval every 5 minutes
browser4-cli loop -- eval "document.title" -i 300
```

## When to Use

Use **loop** for repeated task execution at fixed intervals — monitoring, health checks, scheduled data collection. Prefer **crawl** for one-time bulk multi-page extraction. Prefer **swarm** for parallel high-throughput scraping. Use **cron** (system scheduler) for tasks that don't need browser automation.

## How It Works

Loop executes a task on a fixed interval, persists progress to `~/.browser4/loop-state.json` after each iteration, and resumes from the last completed iteration on restart. Interval is measured from start-to-start of iterations. Ctrl+C during execution saves progress before exit.

## Modes

### Plain text (default)

The task string is submitted to the Browser4 server via the `command_run` MCP tool.
The server auto-detects the command type:

- **Natural language**: e.g., `"load https://example.com and extract the page title"`
- **X-SQL**: e.g., `"select dom.title from DOM_LOAD_AND_SELECT('https://example.com')"`

```bash
browser4-cli loop "select dom.title, dom.url from DOM_LOAD_AND_SELECT('https://news.ycombinator.com')" --count 5
```

### Shell (`--shell`)

The task is executed via the OS shell. Uses `cmd /C` on Windows, `sh -c` on Unix.

```bash
browser4-cli loop --shell "curl -s https://api.example.com/health | jq .status" -i 60
```

Quote the entire shell command to avoid tokenization issues with pipes, redirects, and spaces.

### Subcommand (`--`)

**Argument grammar:** `loop [LOOP_FLAGS] [TASK | --shell SHELL_CMD | -- CLI_ARGS]`

Loop-level flags (`--name`, `--interval`, `--count`, `--timeout`, etc.) **must** appear before `--`. Everything after `--` is passed directly to the nested `browser4-cli` process.

```bash
# Correct: loop flags before --, subcommand args after
browser4-cli loop --name my-check --count 5 -- status

# Wrong: --name after -- is consumed as a subcommand argument, not a loop name
browser4-cli loop -- status --name my-check

# If the CLI detects a known loop flag after --, it emits a warning.
```

Everything after `--` is passed as arguments to a nested `browser4-cli` process.
Uses the current binary path so the same version is always invoked.

```bash
browser4-cli loop -- eval "document.title" -i 300
browser4-cli loop -- snapshot -i 600
browser4-cli loop -- screenshot --full-page -i 1800
```

## Flags

| Flag | Short | Type | Default | Description |
|---|---|---|---|---|
| `--interval` | `-i` | u64 | `3600` (1 hour) | Seconds between iterations |
| `--count` | `-n` | u64 | infinite | Maximum number of iterations |
| `--timeout` | `-t` | u64 | `604800` (1 week) | Maximum total duration in seconds |
| `--name` | | string | `"default"` | Loop name for persistence. Named loops are stored in `~/.browser4/loops/<name>.json`. Only letters, digits, dots, hyphens, and underscores are allowed. |
| `--shell` | | bool | — | Execute task as a shell command |
| `--pause` | | bool | — | Pause a running loop (control op), or start a new loop in paused state (when combined with a task) |
| `--resume` | | bool | — | Resume a paused loop AND spawn a background process to start execution. Cannot be combined with a task. Optionally specify `--name` to target a named loop. |
| `--stop` | | bool | — | Stop a running/paused loop and clear persisted state. Also writes a completion entry to the history log. |
| `--status` | | bool | — | Show loop state and progress |
| `--list` | | bool | — | List all persisted loops with name, iters (completed/max), interval, status, and task |
| `--history` | | bool | — | Show recently completed loops (up to 200 most recent) |
| `--keep-state` | | bool | — | Preserve the loop state file after normal completion (by default it is auto-cleaned) |
| `--pause-all` | | bool | — | Pause all running loops at once |
| `--resume-all` | | bool | — | Resume all paused loops at once and spawn background processes for each |
| `--stop-all` | | bool | — | Stop and clear all persisted loops at once |

### Named loops

Use `--name` to run multiple independent loops concurrently. Each named loop
has its own state file under `~/.browser4/loops/<name>.json`. The default
loop (no `--name`) uses `~/.browser4/loop-state.json`.

```bash
# Start a named health-check loop
browser4-cli loop --name health --shell "curl -s https://api.example.com/health" -i 300

# Start a second loop independently
browser4-cli loop --name monitor -- eval "document.title" -i 600

# List all loops
browser4-cli loop --list

# Pause/resume/stop a specific loop
browser4-cli loop --pause --name health
browser4-cli loop --resume --name health
browser4-cli loop --stop --name health
```

### Start paused

Combine `--pause` with a task to create a loop that is persisted but does not
start executing immediately. Use `--resume` to change state to running AND
spawn a background process that begins executing iterations.

```bash
# Create a loop in paused state
browser4-cli loop --pause --shell "echo hi" -i 60 --name demo

# Output:
# Loop: "echo hi" — every 60s
#   Mode: shell command
# ⏸  Created as paused. Use `browser4-cli loop --resume --name demo` to start.

# Resume the paused loop — this changes state to "running" AND spawns
# a background process that starts executing iterations immediately.
browser4-cli loop --resume --name demo
# ▶  Loop "demo" resumed (was paused).
#    Spawned background process (PID: 12345). Use --list to monitor, --pause to pause, --stop to clear.
#    State file: C:\Users\...\.browser4\loops\demo.json
```

### Normal completion

When a loop finishes normally (count reached or timeout expired), the
persisted state is automatically cleared.  Use `--stop` to clear state
before normal completion if needed.

```bash
browser4-cli loop --shell "echo hi" --count 2
# ... runs 2 iterations ...
# ✓  Loop finished — 2 iteration(s) completed.
# (state file is automatically removed)
```

## Persistence and resume

### State file

Loop progress is persisted to `~/.browser4/loop-state.json` (default loop) or
`~/.browser4/loops/<name>.json` (named loops) after each iteration:

```json
{
  "taskTokens": ["echo", "hello"],
  "mode": "shell",
  "intervalSecs": 3600,
  "count": 10,
  "timeoutSecs": 604800,
  "iterationsCompleted": 3,
  "startedAt": "2026-06-27T10:00:00+00:00",
  "updatedAt": "2026-06-27T13:00:05+00:00",
  "status": "running"
}
```

### Resume after interruption

If the process is interrupted (Ctrl+C, system shutdown, terminal closed):

1. Progress is saved automatically before exit.
2. Run the **same command** again to resume from the next iteration.
3. The original `startedAt` timestamp is preserved for timeout calculation.

```bash
# First run — interrupted after 3 iterations
browser4-cli loop --shell "echo hello" -n 10 -i 5
# ... Ctrl+C after 3 iterations ...

# Resume — starts at iteration 4
browser4-cli loop --shell "echo hello" -n 10 -i 5
# Resuming loop: "echo hello" from iteration 4
```

### Stop a loop

Use `--stop` to clear the persisted state and prevent auto-resume:

```bash
browser4-cli loop --stop
# Loop stopped. 3 iteration(s) were completed. State cleared.
```

### Inspect loop state

Use `--status` to view the current loop without executing:

```bash
browser4-cli loop --status
# ▶ Loop state: running
#   Task: echo hello
#   Mode: shell
#   Interval: 3600s
#   Count: 10
#   Timeout: 604800s
#   Iterations completed: 3
#   Started at: 2026-06-27T10:00:00+00:00
#   Updated at: 2026-06-27T13:00:05+00:00
```

### Loop history

When a loop completes normally or is stopped via `--stop`, a completion entry
is written to `~/.browser4/loop-history.jsonl`. Use `--history` to review
past loop runs:

```bash
browser4-cli loop --history
# 3 completed loop(s) in history (newest last):
#
#   ✓  (default)              2 iters  count reached     2026-06-27T...  echo hello
#   ✓  health                  5 iters  timeout           2026-06-27T...  curl -s https://...
#   ✓  demo                    1 iters  stopped by user   2026-06-27T...  eval document.title
#
# History keeps the most recent 200 completed loops.
```

### Keep state after completion

By default, completed loops auto-clean their state file. Use `--keep-state`
to preserve it for inspection:

```bash
browser4-cli loop --shell "echo done" --count 2 --keep-state --name my-loop
# ... runs 2 iterations ...
# ✓  Loop finished — 2 iteration(s) completed.
#    State preserved at: C:\Users\...\.browser4\loops\my-loop.json
```

## Output

### Human-readable output

Each iteration prints a header with the iteration number and UTC timestamp:

```
Loop: "echo hello" — every 3600s, up to 10 iterations or 604800s
  Mode: shell command

--- Iteration 1 [2026-06-27T10:00:00.000Z] ---
hello

--- Iteration 2 [2026-06-27T11:00:01.002Z] ---
hello

========================================
Loop finished. 2 iteration(s) completed.
```

### JSON output (`--json`)

When the global `--json` flag is used before the command:

```bash
browser4-cli --json loop --shell "echo hello" --count 2
```

```json
{
  "command": "loop",
  "status": "ok",
  "output": {
    "iterations": [
      {"iteration": 1, "timestamp": "2026-06-27T10:00:00.000Z", "ok": true, "output": "hello"},
      {"iteration": 2, "timestamp": "2026-06-27T11:00:01.002Z", "ok": true, "output": "hello"}
    ],
    "total_iterations": 2
  }
}
```

Failed iterations record the error and continue:

```json
{"iteration": 3, "timestamp": "...", "ok": false, "error": "Shell command exited with exit code: 1: ..."}
```

## How it works

### Execution loop

1. Parse arguments and determine mode (plain/shell/subcommand).
2. Check for existing persisted state (resume if matching task found).
3. Persist initial state.
4. For each iteration:
   - Check `--stop` signal in the persisted state file.
   - Check `--count` limit.
   - Check `--timeout` limit (capped from original `startedAt`).
   - Execute the task in the determined mode.
   - Persist updated progress.
   - Sleep for `interval - execution_time` (capped by remaining timeout budget).
   - Break on Ctrl+C (progress saved before exit).
5. On normal completion: clear persisted state, print summary.

### Interval semantics

The interval is measured from the **start** of one iteration to the start of the next.
If an iteration takes longer than the interval, the next iteration starts immediately
(no additional delay). This maintains a consistent pacing cadence.

### Timeout semantics

The timeout is checked at the **top** of each iteration. A long-running iteration may
exceed the configured timeout — the loop will not abort mid-execution. The sleep
between iterations is capped so the loop wakes up in time to honour the timeout.

### Ctrl+C handling

Ctrl+C is handled at every phase of the loop:

- **During execution**: A `tokio::select!` races the task against Ctrl+C. If
  Ctrl+C arrives mid-execution, progress is persisted from the last
  *completed* iteration so the loop can be resumed.
- **During sleep**: A polling loop checks for Ctrl+C every 2 seconds. Progress
  is persisted and the loop exits cleanly.
- **While paused**: Ctrl+C exits without changing state (the loop remains paused).

In all cases the state file is updated so running the same command again
resumes from the last completed iteration.

## Timeout

Set via `--timeout` / `-t`. Default: 604800 seconds (1 week).

```bash
browser4-cli loop --shell "echo hi" -t 3600  # Stop after 1 hour
```

## Error handling

- Errors during an iteration are logged to stderr and the loop **continues**.
- The JSON output records both successful and failed iterations.
- `--shell` and `--` are mutually exclusive — passing both produces a usage error.
- An empty task produces a usage error.
- Non-numeric values for `--interval`, `--count`, or `--timeout` produce a usage error.
- If a different task is persisted when starting a new loop, a warning is printed and a fresh loop starts.


exec
"C:\\Program Files\\PowerShell\\7\\pwsh.exe" -Command "Get-Content \"skills\\browser4-cli\\references\\htmlsnapshot.md\" -Raw" in D:\workspace\Browser4\Browser4-4.13
exec
"C:\\Program Files\\PowerShell\\7\\pwsh.exe" -Command "Get-Content \"skills\\browser4-cli\\references\\x-sql-dom-load-select.md\" -Raw" in D:\workspace\Browser4\Browser4-4.13
 succeeded in 350ms:
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


 succeeded in 330ms:
---
title: "X-SQL: DOM_LOAD_AND_SELECT — Page Loading with CSS Selection"
description: "Reference for DOM_LOAD_AND_SELECT. The required table-source function for all X-SQL page-data queries — loads a web page and selects elements matching a CSS query."
tier: catalog
---

# X-SQL: DOM_LOAD_AND_SELECT — Page Loading with CSS Selection

> **Parent:** [x-sql.md](x-sql.md) — full function index and quick-reference patterns
>
> **Related:** [DomFunctions](x-sql-dom-functions.md) | [DomSelectFunctions](x-sql-dom-select-functions.md) | [StringFunctions](x-sql-string-functions.md) | [ArrayFunctions](x-sql-array-functions.md)

**Source:** `DomFunctionTables.kt` | **Namespace:** `DOM`

---

## DOM_LOAD_AND_SELECT

```
DOM_LOAD_AND_SELECT(url, cssQuery [, offset, limit])
```

Loads a web page and immediately selects elements matching a CSS query. Returns a `ResultSet` of DOM objects — use this as a table source with `SELECT * FROM DOM_LOAD_AND_SELECT(...)`.

**Parameters:**
| Param | Type | Default | Description |
|-------|------|---------|-------------|
| `url` | `String` | required | The URL to load. Append space-separated load options (e.g. `https://example.com -expires 1h`) to control caching, rendering, etc. |
| `cssQuery` | `String` | required | CSS selector to match elements on the page |
| `offset` | `Int` | `1` | 1-based offset into the matched element set |
| `limit` | `Int` | `MAX_VALUE` | Maximum number of elements to return |

**Returns:** `ResultSet` with DOM column — each row is a `ValueDom` that can be passed to other DOM functions.

> **URL parameter:** When used through `htmlsnapshot query`, use the **unquoted** `@url` placeholder to reference the target page URL. The `SQLTemplate` engine replaces `@url` with the properly escaped URL before execution.
> - ✅ `DOM_LOAD_AND_SELECT(@url, 'h1')` — `@url` is replaced with the actual page URL
> - ❌ `DOM_LOAD_AND_SELECT('.', 'h1')` — the literal `'.'` string is not a valid URL and will cause a 500 error
>
> The `@url` placeholder must appear **unquoted**. Do not wrap it in quotes: `'@url'` will be treated as a literal string, not a placeholder.

**Examples:**

```sql
-- Load a page and select all <h1> elements
SELECT * FROM DOM_LOAD_AND_SELECT('https://example.com', 'h1');

-- Combine with DOM functions to extract text from each result
SELECT DOM_TEXT(DOM) AS title
FROM DOM_LOAD_AND_SELECT('https://example.com', 'article h2');

-- Load with expiration control (fetch fresh if older than 1 hour)
SELECT DOM_FIRST_TEXT(DOM, 'title')
FROM DOM_LOAD_AND_SELECT('https://example.com -expires 1h', 'h1');

-- Select only the first 5 product cards
SELECT DOM_TEXT(DOM) AS product_name
FROM DOM_LOAD_AND_SELECT('https://shop.example.com/products', '.product-card', 1, 5);
```

**Pattern: Scrape a list page with multiple fields per item:**

```sql
SELECT
    DOM_FIRST_TEXT(DOM, '.title') AS title,
    DOM_FIRST_TEXT(DOM, '.price') AS price,
    DOM_FIRST_HREF(DOM, 'a') AS link,
    DOM_FIRST_IMG(DOM, 'img') AS image
FROM DOM_LOAD_AND_SELECT('https://example.com/list', '.item');
```


codex
I've completed the documentation study (official SKILL.md, local SKILL.md §4b, and the crawl/swarm/loop/htmlsnapshot/X-SQL references). Now I'll set up a plan and start executing the acceptance criteria.
Execution plan for the six bulk/scale branches plus usability evaluation.
  → AC1 — Single list page: htmlsnapshot query with DOM_LOAD_AND_SELECT on MockSite listing
  • AC2 — crawl --seed-file --depth 0 --sql @query.sql over 3 product URLs
  • AC3 — crawl <url> --out-link-selector/-olp on generated crawl fixture
  • AC4 — swarm create/query/status/result/close with seed file
  • AC5 — loop -- eval on named session with status checks
  • AC6 — PowerShell few-URL loop with goto/htmlsnapshot/get
  • Compile execution trace, issues, and overall assessment
codex
Starting AC1. First, navigate to the MockSite listing page and capture an HTML snapshot.
exec
"C:\\Program Files\\PowerShell\\7\\pwsh.exe" -Command 'browser4-cli goto "http://localhost:18080/ec/b?node=1292115012"' in D:\workspace\Browser4\Browser4-4.13
 succeeded in 4925ms:
Using existing session DEFAULT (current page: http://localhost:18080/ec/b?node=1292115012).
Already at http://localhost:18080/ec/b?node=1292115012 — page unchanged.
### Page
- Page URL: http://localhost:18080/ec/b?node=1292115012
- Page Title: Category: Electronics
### Snapshot
[Snapshot](D:\workspace\Browser4\Browser4-4.13\.browser4-cli\snapshot\snapshot-2026-08-14T07-14-15-049Z.yml)
💡 Tip: Try `htmlsnapshot get text "h1"` to extract the page heading, or `htmlsnapshot inspect` to discover CSS selectors

exec
"C:\\Program Files\\PowerShell\\7\\pwsh.exe" -Command 'browser4-cli htmlsnapshot' in D:\workspace\Browser4\Browser4-4.13
 succeeded in 5719ms:
Snapshot: "Category: Electronics"
http://localhost:18080/ec/b?node=1292115012 · 9 KB · text/html · captured 2026-08-14T07:14:22.373Z
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

codex
Snapshot captured. Now discovering the product-card selectors via `htmlsnapshot inspect`.
exec
"C:\\Program Files\\PowerShell\\7\\pwsh.exe" -Command 'browser4-cli htmlsnapshot inspect' in D:\workspace\Browser4\Browser4-4.13
 succeeded in 418ms:
### Inspect: ".product-card" (6 matches, 6 analyzed)
  🔍 Auto-discovered repeating pattern from ":root"
    The tool found that ":root" repeats as a sibling group (e.g. a product grid, search result list).
    It analyzed 6 of the 6 occurrences to find selectors that work consistently.

  Sample structure (3 of 6):
    Showing 3 representative element(s) out of 6 total matches.
    Each element shows its CSS selector and bounding box (x y width height in px).
    Indented lines are child elements found inside it.
  -- Element 1: #product-list div#product-B0E000001.product-card
     box: 266 310 191 277
     text: "4K OLED TV 55 $899.99"
        #product-B0E000001 a.product-link  [276 321 170 172]  "4K OLED TV 55"
        #product-B0E000001 div#product-price-B0E000001.product-price  [276 503 170 19]  "$899.99"
        #product-B0E000001 div#product-rating-B0E000001.product-rating  [276 522 170 19]  "4.6 (521)"
        #product-B0E000001 div.product-badges  [276 540 170 19]  "Bestseller"
  -- Element 2: #product-list div#product-B0E000002.product-card
     box: 473 310 191 277
     text: "Wireless Noise-Cancelling Headphones $199.99 4.4"
        #product-B0E000002 a.product-link  [483 321 170 190]  "Wireless Noise-Cancelling Headphones"
        #product-B0E000002 div#product-price-B0E000002.product-price  [483 521 170 19]  "$199.99"
        #product-B0E000002 div#product-rating-B0E000002.product-rating  [483 540 170 19]  "4.4 (312)"
        #product-B0E000002 div.product-badges  [483 558 170 19]  "Bestseller"
  -- Element 3: #product-list div#product-B0E000003.product-card
     box: 680 310 191 277
     text: "Portable Bluetooth Speaker $49.99 4.3"
        #product-B0E000003 a.product-link  [690 321 170 190]  "Portable Bluetooth Speaker"
        #product-B0E000003 div#product-price-B0E000003.product-price  [690 521 170 19]  "$49.99"
        #product-B0E000003 div#product-rating-B0E000003.product-rating  [690 540 170 19]  "4.3 (901)"

  Suggested selectors (recurring across matches):
    Each row is a CSS selector that finds the same kind of element inside each match.
    ★ = high-quality (specific enough to use reliably).
    N/N (%) = how many of the analyzed matches contained this element / coverage.
    → "..." = sample values extracted by this selector.
    Use these selectors with `htmlsnapshot get` to extract data.
  ★   6/6 (100%)  div.product-title                        → "4K OLED TV 55" | "Wireless Noise-Cancelling Headphones" | "Portable Bluetooth Speaker"
  ★   6/6 (100%)  a.product-link                           
  ★   6/6 (100%)  img.product-img                          
  ★   6/6 (100%)  a:expr(img>0)                            
  ★   6/6 (100%)  a:expr(a>0)                              
      6/6 (100%)  img:expr(img>0)                          
      6/6 (100%)  div:expr(img>0)                          
      6/6 (100%)  div:expr(a>0)                            
      6/6 (100%)  [data-category-id="1292115012"]          
      3/6 (50%)  span.badge                               → "Bestseller" | "Hot"
      3/6 (50%)  div.product-badges                       

  Structural (bare tags, low specificity):
    These match too broadly — use only as a fallback or with :expr() filters.
      6/6 (100%)  a                                        
      6/6 (100%)  img                                      
      6/6 (100%)  div                                      
      3/6 (50%)  span                                     → "Bestseller" | "Hot"

  💡 Try these next:
    Use `get all text` to extract visible text, or `get all attr <name>` for attribute values.
    The SQL variant lets you query with full expressive power (joins, filters, aggregates).
     htmlsnapshot get all text "div.product-title" --limit 20
     htmlsnapshot get all text "a.product-link" --limit 20
     htmlsnapshot get all text "img.product-img" --limit 20
     htmlsnapshot get attr "img[src]" src --limit 20  # image URLs
     htmlsnapshot get attr "a[href]" href --limit 20  # link URLs
     htmlsnapshot get attr "img[src]:expr(width > 200 && height > 200)" src --limit 20  # large images only
     htmlsnapshot query --sql "SELECT DOM_TEXT(DOM) AS text FROM DOM_LOAD_AND_SELECT(@url, 'div.product-title')"

  📋 Tip: htmlsnapshot summary uses visual clustering (not DOM patterns)
    to group visible content. It can surface product info even when
    auto-discovery picks up navigation elements.
    Try: browser4-cli htmlsnapshot summary

codex
Selectors discovered: `.product-card`, `.product-title`, `.product-price`. Now I'll write the AC1 X-SQL query file and the shared AC2 artifacts (seed file + product query).
apply patch
patch: completed
D:\workspace\Browser4\Browser4-4.13\.test-sessions\ac1-listing-query.sql
D:\workspace\Browser4\Browser4-4.13\.test-sessions\ac2-product-query.sql
D:\workspace\Browser4\Browser4-4.13\.test-sessions\seed-urls.txt
diff --git a/.test-sessions/ac1-listing-query.sql b/.test-sessions/ac1-listing-query.sql
new file mode 100644
index 0000000000000000000000000000000000000000..d651eec929ece8af65e1fdb818fac08d6e7ef4ea
--- /dev/null
+++ b/.test-sessions/ac1-listing-query.sql
@@ -0,0 +1,6 @@
+SELECT
+  DOM_FIRST_TEXT(DOM, 'div.product-title') AS title,
+  DOM_FIRST_TEXT(DOM, 'div.product-price') AS price,
+  DOM_FIRST_HREF(DOM, 'a.product-link') AS link
+FROM
+  DOM_LOAD_AND_SELECT(@url, '.product-card')
diff --git a/.test-sessions/ac2-product-query.sql b/.test-sessions/ac2-product-query.sql
new file mode 100644
index 0000000000000000000000000000000000000000..1fa45c1ec3c6f272620e6313938e1565b2d4239c
--- /dev/null
+++ b/.test-sessions/ac2-product-query.sql
@@ -0,0 +1,6 @@
+SELECT
+  DOM_BASE_URI(DOM) AS url,
+  DOM_FIRST_TEXT(DOM, '#productTitle') AS title,
+  DOM_FIRST_TEXT(DOM, '#product-price') AS price
+FROM
+  DOM_LOAD_AND_SELECT(@url, 'body')

diff --git a/.test-sessions/ac1-listing-query.sql b/.test-sessions/ac1-listing-query.sql
new file mode 100644
index 0000000000000000000000000000000000000000..d651eec929ece8af65e1fdb818fac08d6e7ef4ea
--- /dev/null
+++ b/.test-sessions/ac1-listing-query.sql
@@ -0,0 +1,6 @@
+SELECT
+  DOM_FIRST_TEXT(DOM, 'div.product-title') AS title,
+  DOM_FIRST_TEXT(DOM, 'div.product-price') AS price,
+  DOM_FIRST_HREF(DOM, 'a.product-link') AS link
+FROM
+  DOM_LOAD_AND_SELECT(@url, '.product-card')
diff --git a/.test-sessions/ac2-product-query.sql b/.test-sessions/ac2-product-query.sql
new file mode 100644
index 0000000000000000000000000000000000000000..1fa45c1ec3c6f272620e6313938e1565b2d4239c
--- /dev/null
+++ b/.test-sessions/ac2-product-query.sql
@@ -0,0 +1,6 @@
+SELECT
+  DOM_BASE_URI(DOM) AS url,
+  DOM_FIRST_TEXT(DOM, '#productTitle') AS title,
+  DOM_FIRST_TEXT(DOM, '#product-price') AS price
+FROM
+  DOM_LOAD_AND_SELECT(@url, 'body')

exec
"C:\\Program Files\\PowerShell\\7\\pwsh.exe" -Command 'browser4-cli htmlsnapshot query "http://localhost:18080/ec/b?node=1292115012" --sql "@.test-sessions/ac1-listing-query.sql"' in D:\workspace\Browser4\Browser4-4.13
 succeeded in 2581ms:
{"id":"f63f62ca-c178-4188-b793-01f415cd37d6","statusCode":200,"pageStatusCode":200,"pageContentBytes":10148,"isDone":true,"resultSet":[{"title":"4K OLED TV 55","price":"$899.99","link":"http://localhost:18080/ec/dp/B0E000001"},{"title":"Wireless Noise-Cancelling Headphones","price":"$199.99","link":"http://localhost:18080/ec/dp/B0E000002"},{"title":"Portable Bluetooth Speaker","price":"$49.99","link":"http://localhost:18080/ec/dp/B0E000003"},{"title":"Smartphone 128GB","price":"$599.00","link":"http://localhost:18080/ec/dp/B0E000004"},{"title":"USB-C Hub 7-in-1","price":"$29.95","link":"http://localhost:18080/ec/dp/B0E000005"},{"title":"Wireless Mouse","price":"$24.99","link":"http://localhost:18080/ec/dp/B0E000006"}],"event":"onLoaded","message":null,"createdTime":"2026-08-14T07:14:32.606502400Z","startedTime":"2026-08-14T07:14:32.890497800Z","lastModifiedTime":"2026-08-14T07:14:32.894637100Z","finishTime":"2026-08-14T07:14:32.894637100Z","status":"OK"}

diff --git a/.test-sessions/ac1-listing-query.sql b/.test-sessions/ac1-listing-query.sql
new file mode 100644
index 0000000000000000000000000000000000000000..d651eec929ece8af65e1fdb818fac08d6e7ef4ea
--- /dev/null
+++ b/.test-sessions/ac1-listing-query.sql
@@ -0,0 +1,6 @@
+SELECT
+  DOM_FIRST_TEXT(DOM, 'div.product-title') AS title,
+  DOM_FIRST_TEXT(DOM, 'div.product-price') AS price,
+  DOM_FIRST_HREF(DOM, 'a.product-link') AS link
+FROM
+  DOM_LOAD_AND_SELECT(@url, '.product-card')
diff --git a/.test-sessions/ac2-product-query.sql b/.test-sessions/ac2-product-query.sql
new file mode 100644
index 0000000000000000000000000000000000000000..1fa45c1ec3c6f272620e6313938e1565b2d4239c
--- /dev/null
+++ b/.test-sessions/ac2-product-query.sql
@@ -0,0 +1,6 @@
+SELECT
+  DOM_BASE_URI(DOM) AS url,
+  DOM_FIRST_TEXT(DOM, '#productTitle') AS title,
+  DOM_FIRST_TEXT(DOM, '#product-price') AS price
+FROM
+  DOM_LOAD_AND_SELECT(@url, 'body')

codex
AC1 complete — 6 product rows with correlated title/price/link. Moving to AC2 (seed-file crawl).
AC1 succeeded: DOM_LOAD_AND_SELECT returned 6 correlated rows from the listing page.
  ✓ AC1 — Single list page: htmlsnapshot query with DOM_LOAD_AND_SELECT on MockSite listing
  → AC2 — crawl --seed-file --depth 0 --sql @query.sql over 3 product URLs
  • AC3 — crawl <url> --out-link-selector/-olp on generated crawl fixture
  • AC4 — swarm create/query/status/result/close with seed file
  • AC5 — loop -- eval on named session with status checks
  • AC6 — PowerShell few-URL loop with goto/htmlsnapshot/get
  • Compile execution trace, issues, and overall assessment
diff --git a/.test-sessions/ac1-listing-query.sql b/.test-sessions/ac1-listing-query.sql
new file mode 100644
index 0000000000000000000000000000000000000000..d651eec929ece8af65e1fdb818fac08d6e7ef4ea
--- /dev/null
+++ b/.test-sessions/ac1-listing-query.sql
@@ -0,0 +1,6 @@
+SELECT
+  DOM_FIRST_TEXT(DOM, 'div.product-title') AS title,
+  DOM_FIRST_TEXT(DOM, 'div.product-price') AS price,
+  DOM_FIRST_HREF(DOM, 'a.product-link') AS link
+FROM
+  DOM_LOAD_AND_SELECT(@url, '.product-card')
diff --git a/.test-sessions/ac2-product-query.sql b/.test-sessions/ac2-product-query.sql
new file mode 100644
index 0000000000000000000000000000000000000000..1fa45c1ec3c6f272620e6313938e1565b2d4239c
--- /dev/null
+++ b/.test-sessions/ac2-product-query.sql
@@ -0,0 +1,6 @@
+SELECT
+  DOM_BASE_URI(DOM) AS url,
+  DOM_FIRST_TEXT(DOM, '#productTitle') AS title,
+  DOM_FIRST_TEXT(DOM, '#product-price') AS price
+FROM
+  DOM_LOAD_AND_SELECT(@url, 'body')

exec
"C:\\Program Files\\PowerShell\\7\\pwsh.exe" -Command 'browser4-cli crawl --seed-file ".test-sessions/seed-urls.txt" --depth 0 --sql "@.test-sessions/ac2-product-query.sql" --format table --refresh' in D:\workspace\Browser4\Browser4-4.13
 succeeded in 26739ms:
Crawl task submitted: 5454bd00-bd24-4a2e-87ad-39ceed4db5cd
  URLs: 3
  X-SQL extraction: enabled
Waiting for crawl to complete (task 5454bd00-bd24-4a2e-87ad-39ceed4db5cd, 3 URLs). Use --background for long-running crawls.
Crawling... waiting for first page (6s elapsed, 3 URLs queued)
Crawling... 1 pages found so far
Crawling... 1 pages found so far
Crawling... 1 pages found so far
Crawling... 1/3 seeds done, 1 pages found, 1 rows extracted (http://localhost:18080/ec/d... / 4K OLED TV 55) (16s elapsed)
Crawling... 1 pages found so far
Crawling... 2 pages found so far
Crawling... 2 pages found so far
Crawling... 2 pages found so far
Crawling... 2 pages found so far
Crawling... 3/3 seeds done, 3 pages found, 3 rows extracted (http://localhost:18080/ec/d... / Portable Bluetooth Speaker) (26s elapsed)
3 pages crawled, 3 rows extracted.

  url                                    | title                                | price  
  ---------------------------------------+--------------------------------------+--------
  http://localhost:18080/ec/dp/B0E000001 | 4K OLED TV 55                        | $899.99
  http://localhost:18080/ec/dp/B0E000002 | Wireless Noise-Cancelling Headphones | $199.99
  http://localhost:18080/ec/dp/B0E000003 | Portable Bluetooth Speaker           | $49.99 


diff --git a/.test-sessions/ac1-listing-query.sql b/.test-sessions/ac1-listing-query.sql
new file mode 100644
index 0000000000000000000000000000000000000000..d651eec929ece8af65e1fdb818fac08d6e7ef4ea
--- /dev/null
+++ b/.test-sessions/ac1-listing-query.sql
@@ -0,0 +1,6 @@
+SELECT
+  DOM_FIRST_TEXT(DOM, 'div.product-title') AS title,
+  DOM_FIRST_TEXT(DOM, 'div.product-price') AS price,
+  DOM_FIRST_HREF(DOM, 'a.product-link') AS link
+FROM
+  DOM_LOAD_AND_SELECT(@url, '.product-card')
diff --git a/.test-sessions/ac2-product-query.sql b/.test-sessions/ac2-product-query.sql
new file mode 100644
index 0000000000000000000000000000000000000000..1fa45c1ec3c6f272620e6313938e1565b2d4239c
--- /dev/null
+++ b/.test-sessions/ac2-product-query.sql
@@ -0,0 +1,6 @@
+SELECT
+  DOM_BASE_URI(DOM) AS url,
+  DOM_FIRST_TEXT(DOM, '#productTitle') AS title,
+  DOM_FIRST_TEXT(DOM, '#product-price') AS price
+FROM
+  DOM_LOAD_AND_SELECT(@url, 'body')

codex
AC2 complete — 3 rows, one per seed URL. Now AC3: crawl from the fixture hub with link discovery and pattern filtering.
AC2 succeeded: 3/3 seed URLs produced one structured row each via crawl --seed-file --depth 0.
  ✓ AC1 — Single list page: htmlsnapshot query with DOM_LOAD_AND_SELECT on MockSite listing
  ✓ AC2 — crawl --seed-file --depth 0 --sql @query.sql over 3 product URLs
  → AC3 — crawl <url> --out-link-selector/-olp on generated crawl fixture
  • AC4 — swarm create/query/status/result/close with seed file
  • AC5 — loop -- eval on named session with status checks
  • AC6 — PowerShell few-URL loop with goto/htmlsnapshot/get
  • Compile execution trace, issues, and overall assessment
diff --git a/.test-sessions/ac1-listing-query.sql b/.test-sessions/ac1-listing-query.sql
new file mode 100644
index 0000000000000000000000000000000000000000..d651eec929ece8af65e1fdb818fac08d6e7ef4ea
--- /dev/null
+++ b/.test-sessions/ac1-listing-query.sql
@@ -0,0 +1,6 @@
+SELECT
+  DOM_FIRST_TEXT(DOM, 'div.product-title') AS title,
+  DOM_FIRST_TEXT(DOM, 'div.product-price') AS price,
+  DOM_FIRST_HREF(DOM, 'a.product-link') AS link
+FROM
+  DOM_LOAD_AND_SELECT(@url, '.product-card')
diff --git a/.test-sessions/ac2-product-query.sql b/.test-sessions/ac2-product-query.sql
new file mode 100644
index 0000000000000000000000000000000000000000..1fa45c1ec3c6f272620e6313938e1565b2d4239c
--- /dev/null
+++ b/.test-sessions/ac2-product-query.sql
@@ -0,0 +1,6 @@
+SELECT
+  DOM_BASE_URI(DOM) AS url,
+  DOM_FIRST_TEXT(DOM, '#productTitle') AS title,
+  DOM_FIRST_TEXT(DOM, '#product-price') AS price
+FROM
+  DOM_LOAD_AND_SELECT(@url, 'body')

exec
"C:\\Program Files\\PowerShell\\7\\pwsh.exe" -Command 'browser4-cli crawl "http://localhost:18080/generated/crawl/index.html" -d 2 -ol "a.product" -olp "/product/"' in D:\workspace\Browser4\Browser4-4.13
 succeeded in 60846ms:
Crawl task submitted: 08468cd8-3269-4c68-8233-6f95a90775f0
  URLs: 1
Waiting for crawl to complete (task 08468cd8-3269-4c68-8233-6f95a90775f0, 1 URLs). Use --background for long-running crawls.
Crawling... waiting for first page (6s elapsed, 1 URLs queued)
Crawling... waiting for first page (16s elapsed, 1 URLs queued)
Crawling... waiting for first page (26s elapsed, 1 URLs queued)
Crawling... waiting for first page (36s elapsed, 1 URLs queued)
Crawling... waiting for first page (46s elapsed, 1 URLs queued)
Crawling... waiting for first page (56s elapsed, 1 URLs queued)

Crawl completed. 10 pages found.
  depth=1 | http://localhost:18080/generated/crawl/index.html | Crawl Test Hub
  depth=1 | http://localhost:18080/generated/crawl/product/1.html | Widget Alpha — $10.00
  depth=1 | http://localhost:18080/generated/crawl/product/2.html | Widget Beta — $20.00
  depth=1 | http://localhost:18080/generated/crawl/product/3.html | Widget Gamma — $30.00
  depth=1 | http://localhost:18080/generated/crawl/product/4.html | Widget Delta — $40.00
  depth=1 | http://localhost:18080/generated/crawl/product/8.html | Widget Mu — $80.00
  depth=1 | http://localhost:18080/generated/crawl/product/5.html | Widget Epsilon — $50.00
  depth=1 | http://localhost:18080/generated/crawl/product/6.html | Widget Zeta — $60.00
  depth=1 | http://localhost:18080/generated/crawl/product/9.html | Widget Nu — $90.00
  depth=1 | http://localhost:18080/generated/crawl/product/7.html | Widget Lambda — $70.00

diff --git a/.test-sessions/ac1-listing-query.sql b/.test-sessions/ac1-listing-query.sql
new file mode 100644
index 0000000000000000000000000000000000000000..d651eec929ece8af65e1fdb818fac08d6e7ef4ea
--- /dev/null
+++ b/.test-sessions/ac1-listing-query.sql
@@ -0,0 +1,6 @@
+SELECT
+  DOM_FIRST_TEXT(DOM, 'div.product-title') AS title,
+  DOM_FIRST_TEXT(DOM, 'div.product-price') AS price,
+  DOM_FIRST_HREF(DOM, 'a.product-link') AS link
+FROM
+  DOM_LOAD_AND_SELECT(@url, '.product-card')
diff --git a/.test-sessions/ac2-product-query.sql b/.test-sessions/ac2-product-query.sql
new file mode 100644
index 0000000000000000000000000000000000000000..1fa45c1ec3c6f272620e6313938e1565b2d4239c
--- /dev/null
+++ b/.test-sessions/ac2-product-query.sql
@@ -0,0 +1,6 @@
+SELECT
+  DOM_BASE_URI(DOM) AS url,
+  DOM_FIRST_TEXT(DOM, '#productTitle') AS title,
+  DOM_FIRST_TEXT(DOM, '#product-price') AS price
+FROM
+  DOM_LOAD_AND_SELECT(@url, 'body')

codex
AC3 found 10 pages (hub + 9 product pages). Let me verify the fixture page actually contains unrelated category links that were correctly excluded.
exec
"C:\\Program Files\\PowerShell\\7\\pwsh.exe" -Command '$r = Invoke-WebRequest -Uri "http://localhost:18080/generated/crawl/index.html" -UseBasicParsing -TimeoutSec 10; $r.Content' in D:\workspace\Browser4\Browser4-4.13
 succeeded in 493ms:
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <title>Crawl Test Hub</title>
  <style>
    :root { --bg:#f6f7fb; --panel:#fff; --line:#d7deea; --ink:#1f2937; --muted:#667085; --shadow:0 12px 28px rgba(15,23,42,.08); }
    * { box-sizing: border-box; }
    body { font-family: Arial, sans-serif; margin: 0; background: var(--bg); color: var(--ink); }
    header { background: linear-gradient(135deg, #0f172a, #1f2937); color: #fff; padding: 1.2rem 1.5rem; }
    .toolbar { display:grid; grid-template-columns: 220px 1fr 280px; gap: 1rem; align-items: center; }
    .toolbar input { width: 100%; padding: .8rem 1rem; border: 0; border-radius: 999px; }
    .toolbar nav { text-align: right; font-size: .92rem; }
    .toolbar nav a { color: #c7d2fe; text-decoration: none; margin-left: .8rem; }
    main { max-width: 1280px; margin: 0 auto; padding: 1.5rem; }
    .hero, .rail, .products, .categories, .guides { background: var(--panel); border: 1px solid var(--line); border-radius: 18px; box-shadow: var(--shadow); padding: 1.1rem; margin-bottom: 1.2rem; }
    h1, h2, h3 { margin-top: 0; }
    .hero p, .guides li, .categories li { color: var(--muted); line-height: 1.55; }
    .product-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(240px, 1fr)); gap: .9rem; }
    .product { display: block; padding: .85rem; border: 1px solid #dce3ee; border-radius: 14px; text-decoration: none; color: inherit; background: #fbfcff; }
    .product-title { display: block; font-weight: 700; margin-bottom: .3rem; }
    .product-price { color: #b12704; font-weight: 700; }
    .product-copy { display: block; color: var(--muted); font-size: .92rem; margin-top: .45rem; }
    .category-list, .guide-list, .utility-list { margin: 0; padding-left: 1.1rem; display: grid; gap: .55rem; }
    .category-link { color: #0b4bb3; }
    .promo-link, .guide-link, .utility-link { color: #0b4bb3; }
    footer { margin-top: 2rem; padding: 1.5rem; font-size: .82rem; color: #666; text-align: center; }
    @media (max-width: 900px) { .toolbar { grid-template-columns: 1fr; } .toolbar nav { text-align: left; } }
  </style>
</head>
<body>
  <header>
    <div class="toolbar">
      <strong>Mock Crawl Center</strong>
      <input type="text" value="wireless gadget, deal alert, setup guide" aria-label="Search query" />
      <nav>
        <a class="utility-link" href="index.html#help">Help Center</a>
        <a class="utility-link" href="index.html#delivery">Delivery Info</a>
        <a class="utility-link" href="index.html#returns">Returns</a>
      </nav>
    </div>
  </header>
  <main>
    <section class="hero">
      <h1 id="pageTitle">Crawl Test Hub</h1>
      <p>This page intentionally mixes <strong>product links</strong>, <strong>category links</strong>, editorial copy, and navigation noise. Selector-based crawls should follow only the anchors you target, while broad crawls should encounter a more realistic storefront layout.</p>
    </section>

    <section class="products" id="product-list">
      <h2>Featured Products</h2>
      <div class="product-grid">
        <a class="product" id="prod-1" href="product/1.html">
          <span class="product-title">Widget Alpha</span>
          <span class="product-price">$10.00</span>
          <span class="product-copy">Budget-friendly starter model with setup tips, buying guidance, and support noise on the detail page.</span>
        </a>
        <a class="product" id="prod-2" href="product/2.html">
          <span class="product-title">Widget Beta</span>
          <span class="product-price">$20.00</span>
          <span class="product-copy">Mid-range option that leads to additional recommendation rails and related product links.</span>
        </a>
        <a class="product" id="prod-3" href="product/3.html">
          <span class="product-title">Widget Gamma</span>
          <span class="product-price">$30.00</span>
          <span class="product-copy">Popular seller with extra accessories and comparison-style noise sections.</span>
        </a>
      </div>
    </section>

    <section class="rail">
      <h2>Promotions and guides</h2>
      <ul class="guide-list">
        <li><a class="promo-link" href="index.html#bundle-deals">Bundle deals and trade-in banners</a></li>
        <li><a class="guide-link" href="index.html#buying-guide">How to compare specs before checkout</a></li>
        <li><a class="guide-link" href="index.html#faq">Warranty, sizing, and returns FAQ</a></li>
      </ul>
    </section>

    <section class="categories">
      <h2>Categories</h2>
      <ul class="category-list">
        <li><a class="category-link" id="cat-electronics" href="category/electronics.html">Electronics Category</a></li>
        <li><a class="category-link" id="cat-accessories" href="category/accessories.html">Accessories Category</a></li>
        <li><a class="category-link" id="cat-clearance" href="category/clearance.html">Clearance Category</a></li>
      </ul>
    </section>
  </main>

  <footer>Crawl Test Hub — Mock Site</footer>
</body>
</html>


diff --git a/.test-sessions/ac1-listing-query.sql b/.test-sessions/ac1-listing-query.sql
new file mode 100644
index 0000000000000000000000000000000000000000..d651eec929ece8af65e1fdb818fac08d6e7ef4ea
--- /dev/null
+++ b/.test-sessions/ac1-listing-query.sql
@@ -0,0 +1,6 @@
+SELECT
+  DOM_FIRST_TEXT(DOM, 'div.product-title') AS title,
+  DOM_FIRST_TEXT(DOM, 'div.product-price') AS price,
+  DOM_FIRST_HREF(DOM, 'a.product-link') AS link
+FROM
+  DOM_LOAD_AND_SELECT(@url, '.product-card')
diff --git a/.test-sessions/ac2-product-query.sql b/.test-sessions/ac2-product-query.sql
new file mode 100644
index 0000000000000000000000000000000000000000..1fa45c1ec3c6f272620e6313938e1565b2d4239c
--- /dev/null
+++ b/.test-sessions/ac2-product-query.sql
@@ -0,0 +1,6 @@
+SELECT
+  DOM_BASE_URI(DOM) AS url,
+  DOM_FIRST_TEXT(DOM, '#productTitle') AS title,
+  DOM_FIRST_TEXT(DOM, '#product-price') AS price
+FROM
+  DOM_LOAD_AND_SELECT(@url, 'body')

codex
The hub contains category/guide/utility links that were correctly excluded — only product pages were crawled. Let me quickly confirm product pages cross-link (depth-2 reach) and check crawl task list for completeness.
exec
"C:\\Program Files\\PowerShell\\7\\pwsh.exe" -Command '$r = Invoke-WebRequest -Uri "http://localhost:18080/generated/crawl/product/1.html" -UseBasicParsing -TimeoutSec 10; ($r.Content -split "`n" | Select-String -Pattern '"'href=\"["'^"]*"'"').Line | ForEach-Object { "'$_.Trim() }' in D:\workspace\Browser4\Browser4-4.13
exec
"C:\\Program Files\\PowerShell\\7\\pwsh.exe" -Command 'browser4-cli crawl list' in D:\workspace\Browser4\Browser4-4.13
 succeeded in 469ms:
<header><nav><a href="../index.html">← Back to Hub</a><a href="../category/electronics.html">Electronics</a><a href="../category/accessories.html">Accessories</a></nav></header>
<li><a class="help-link" href="../index.html#starter-guide">Starter guide for first-time buyers</a></li>
<li><a class="help-link" href="../index.html#returns">Returns and warranty overview</a></li>
<li><a class="help-link" href="../index.html#shipping">Shipping options and delivery timing</a></li>
<a class="product" href="4.html">Widget Delta — $40.00</a>
<a class="product" href="5.html">Widget Epsilon — $50.00</a>

 succeeded in 613ms:
Pruned 45 completed tasks from local tracking (routine cleanup).
Status: 13 total, 13 completed
13 tracked task(s) (showing 1-13):

  TASK ID       COMMAND  DESCRIPTION             STARTED              FINISHED             STATUS   
  ------------  -------  ----------------------  -------------------  -------------------  ---------
  08468cd8-3269-4c68-8233-6f95a90775f0  crawl    1 URLs, depth 2         2026-08-14 15:15:11  2026-08-14 15:16:12  completed
  5454bd00-bd24-4a2e-87ad-39ceed4db5cd  crawl    3 URLs, depth 0, X-SQL  2026-08-14 15:14:38  2026-08-14 15:15:02  completed
  53656e40-a29b-46b7-8a0c-694f0c11b618  crawl    1 URLs, depth 1         2026-08-14 15:06:31  2026-08-14 15:06:38  completed
  843fd009-8a7e-49cc-8aed-05417ab43b57  crawl    1 URLs, depth 1         2026-08-14 15:05:42  2026-08-14 15:05:47  completed
  c89fb4ed-5f82-4fe2-b476-954ae0feebd2  crawl    1 URLs, depth 1         2026-08-14 15:04:29  2026-08-14 15:04:34  completed
  e6c57a50-0440-4f2d-a45e-b108a35fa25c  crawl    1 URLs, depth 1         2026-08-14 15:04:01  2026-08-14 15:04:24  completed
  56b44bc5-0690-4d73-afc1-daaaa4fb1e79  crawl    1 URLs, depth 2         2026-08-14 15:03:42  2026-08-14 15:03:55  completed
  a6c71c45-1cb9-489d-81d6-af21ce09a51a  crawl    3 URLs, depth 0, X-SQL  2026-08-14 15:03:18  2026-08-14 15:03:37  completed
  d0482e26-4a7d-4e50-87e8-7ef55fdf4884  crawl    3 URLs, depth 0, X-SQL  2026-08-14 14:57:52  2026-08-14 14:58:11  completed
  974033b6-701e-40f9-a231-7bde66e68b51  crawl    1 URLs, depth 1         2026-08-14 14:55:36  2026-08-14 14:55:42  completed
  bed6d006-ba6f-400c-9466-51f0794c50a2  crawl    1 URLs, depth 1         2026-08-14 14:55:06  2026-08-14 14:55:32  completed
  7c3eb2fb-5e9a-44f4-ac9d-601c71b10644  crawl    1 URLs, depth 2         2026-08-14 14:54:41  2026-08-14 14:54:56  completed
  a9ef28c5-37d7-4d3c-a219-067fb8f7165e  crawl    3 URLs, depth 0, X-SQL  2026-08-14 14:54:15  2026-08-14 14:54:37  completed

Tip: use 'crawl cancel <id>' to cancel a stuck task, 'crawl clear' to remove terminal tasks.

diff --git a/.test-sessions/ac1-listing-query.sql b/.test-sessions/ac1-listing-query.sql
new file mode 100644
index 0000000000000000000000000000000000000000..d651eec929ece8af65e1fdb818fac08d6e7ef4ea
--- /dev/null
+++ b/.test-sessions/ac1-listing-query.sql
@@ -0,0 +1,6 @@
+SELECT
+  DOM_FIRST_TEXT(DOM, 'div.product-title') AS title,
+  DOM_FIRST_TEXT(DOM, 'div.product-price') AS price,
+  DOM_FIRST_HREF(DOM, 'a.product-link') AS link
+FROM
+  DOM_LOAD_AND_SELECT(@url, '.product-card')
diff --git a/.test-sessions/ac2-product-query.sql b/.test-sessions/ac2-product-query.sql
new file mode 100644
index 0000000000000000000000000000000000000000..1fa45c1ec3c6f272620e6313938e1565b2d4239c
--- /dev/null
+++ b/.test-sessions/ac2-product-query.sql
@@ -0,0 +1,6 @@
+SELECT
+  DOM_BASE_URI(DOM) AS url,
+  DOM_FIRST_TEXT(DOM, '#productTitle') AS title,
+  DOM_FIRST_TEXT(DOM, '#product-price') AS price
+FROM
+  DOM_LOAD_AND_SELECT(@url, 'body')

codex
AC3 verified: category/guide/utility links excluded, only product pages followed (including depth-2 links from product pages). Now AC4 — swarm session.
AC3 succeeded: 9 product pages discovered from hub with a.product/-olp filters excluding category links. Noted a depth-label quirk (all pages shown as depth=1).
  ✓ AC1 — Single list page: htmlsnapshot query with DOM_LOAD_AND_SELECT on MockSite listing
  ✓ AC2 — crawl --seed-file --depth 0 --sql @query.sql over 3 product URLs
  ✓ AC3 — crawl <url> --out-link-selector/-olp on generated crawl fixture
  → AC4 — swarm create/query/status/result/close with seed file
  • AC5 — loop -- eval on named session with status checks
  • AC6 — PowerShell few-URL loop with goto/htmlsnapshot/get
  • Compile execution trace, issues, and overall assessment
diff --git a/.test-sessions/ac1-listing-query.sql b/.test-sessions/ac1-listing-query.sql
new file mode 100644
index 0000000000000000000000000000000000000000..d651eec929ece8af65e1fdb818fac08d6e7ef4ea
--- /dev/null
+++ b/.test-sessions/ac1-listing-query.sql
@@ -0,0 +1,6 @@
+SELECT
+  DOM_FIRST_TEXT(DOM, 'div.product-title') AS title,
+  DOM_FIRST_TEXT(DOM, 'div.product-price') AS price,
+  DOM_FIRST_HREF(DOM, 'a.product-link') AS link
+FROM
+  DOM_LOAD_AND_SELECT(@url, '.product-card')
diff --git a/.test-sessions/ac2-product-query.sql b/.test-sessions/ac2-product-query.sql
new file mode 100644
index 0000000000000000000000000000000000000000..1fa45c1ec3c6f272620e6313938e1565b2d4239c
--- /dev/null
+++ b/.test-sessions/ac2-product-query.sql
@@ -0,0 +1,6 @@
+SELECT
+  DOM_BASE_URI(DOM) AS url,
+  DOM_FIRST_TEXT(DOM, '#productTitle') AS title,
+  DOM_FIRST_TEXT(DOM, '#product-price') AS price
+FROM
+  DOM_LOAD_AND_SELECT(@url, 'body')

exec
"C:\\Program Files\\PowerShell\\7\\pwsh.exe" -Command 'browser4-cli swarm create --display-mode HEADLESS --max-browser-contexts 2 --max-open-tabs 4' in D:\workspace\Browser4\Browser4-4.13
 succeeded in 405ms:
Swarm session created: SWARM

⚠ 2 swarm task(s) from prior sessions are still tracked.
  If new jobs get stuck in "Created" status, run `swarm list --clear` to remove stale entries,
  then recreate the swarm session before resubmitting.
  Or use `swarm create --clear-stale` to clear and recreate in one step.

diff --git a/.test-sessions/ac1-listing-query.sql b/.test-sessions/ac1-listing-query.sql
new file mode 100644
index 0000000000000000000000000000000000000000..d651eec929ece8af65e1fdb818fac08d6e7ef4ea
--- /dev/null
+++ b/.test-sessions/ac1-listing-query.sql
@@ -0,0 +1,6 @@
+SELECT
+  DOM_FIRST_TEXT(DOM, 'div.product-title') AS title,
+  DOM_FIRST_TEXT(DOM, 'div.product-price') AS price,
+  DOM_FIRST_HREF(DOM, 'a.product-link') AS link
+FROM
+  DOM_LOAD_AND_SELECT(@url, '.product-card')
diff --git a/.test-sessions/ac2-product-query.sql b/.test-sessions/ac2-product-query.sql
new file mode 100644
index 0000000000000000000000000000000000000000..1fa45c1ec3c6f272620e6313938e1565b2d4239c
--- /dev/null
+++ b/.test-sessions/ac2-product-query.sql
@@ -0,0 +1,6 @@
+SELECT
+  DOM_BASE_URI(DOM) AS url,
+  DOM_FIRST_TEXT(DOM, '#productTitle') AS title,
+  DOM_FIRST_TEXT(DOM, '#product-price') AS price
+FROM
+  DOM_LOAD_AND_SELECT(@url, 'body')

codex
Swarm session created, with a warning about 2 stale tasks from prior sessions. Per the docs, stale tasks can interfere with the worker pool, so I'll inspect and clear them before submitting.
exec
"C:\\Program Files\\PowerShell\\7\\pwsh.exe" -Command 'browser4-cli swarm list' in D:\workspace\Browser4\Browser4-4.13
 succeeded in 351ms:
Status: 2 total, 2 completed
2 tracked task(s) (showing 1-2):

  TASK ID       COMMAND      DESCRIPTION                             STARTED              FINISHED             STATUS   
  ------------  -----------  --------------------------------------  -------------------  -------------------  ---------
  d466a38c-d624-4cb3-9032-63e87a9c10c5  swarm-query  http://localhost:18080/ec/dp/B0E000001  2026-08-05 03:44:31  2026-08-05 03:44:31  completed
  48ad2a82-c435-402b-bfb8-448914771b5b  swarm-query  http://localhost:18080/ec/dp/B0H000001  2026-08-05 03:43:48  2026-08-05 03:43:48  completed

diff --git a/.test-sessions/ac1-listing-query.sql b/.test-sessions/ac1-listing-query.sql
new file mode 100644
index 0000000000000000000000000000000000000000..d651eec929ece8af65e1fdb818fac08d6e7ef4ea
--- /dev/null
+++ b/.test-sessions/ac1-listing-query.sql
@@ -0,0 +1,6 @@
+SELECT
+  DOM_FIRST_TEXT(DOM, 'div.product-title') AS title,
+  DOM_FIRST_TEXT(DOM, 'div.product-price') AS price,
+  DOM_FIRST_HREF(DOM, 'a.product-link') AS link
+FROM
+  DOM_LOAD_AND_SELECT(@url, '.product-card')
diff --git a/.test-sessions/ac2-product-query.sql b/.test-sessions/ac2-product-query.sql
new file mode 100644
index 0000000000000000000000000000000000000000..1fa45c1ec3c6f272620e6313938e1565b2d4239c
--- /dev/null
+++ b/.test-sessions/ac2-product-query.sql
@@ -0,0 +1,6 @@
+SELECT
+  DOM_BASE_URI(DOM) AS url,
+  DOM_FIRST_TEXT(DOM, '#productTitle') AS title,
+  DOM_FIRST_TEXT(DOM, '#product-price') AS price
+FROM
+  DOM_LOAD_AND_SELECT(@url, 'body')

exec
"C:\\Program Files\\PowerShell\\7\\pwsh.exe" -Command 'browser4-cli swarm list --clear' in D:\workspace\Browser4\Browser4-4.13
 succeeded in 346ms:
Cleared 2 tracked swarm task(s).

diff --git a/.test-sessions/ac1-listing-query.sql b/.test-sessions/ac1-listing-query.sql
new file mode 100644
index 0000000000000000000000000000000000000000..d651eec929ece8af65e1fdb818fac08d6e7ef4ea
--- /dev/null
+++ b/.test-sessions/ac1-listing-query.sql
@@ -0,0 +1,6 @@
+SELECT
+  DOM_FIRST_TEXT(DOM, 'div.product-title') AS title,
+  DOM_FIRST_TEXT(DOM, 'div.product-price') AS price,
+  DOM_FIRST_HREF(DOM, 'a.product-link') AS link
+FROM
+  DOM_LOAD_AND_SELECT(@url, '.product-card')
diff --git a/.test-sessions/ac2-product-query.sql b/.test-sessions/ac2-product-query.sql
new file mode 100644
index 0000000000000000000000000000000000000000..1fa45c1ec3c6f272620e6313938e1565b2d4239c
--- /dev/null
+++ b/.test-sessions/ac2-product-query.sql
@@ -0,0 +1,6 @@
+SELECT
+  DOM_BASE_URI(DOM) AS url,
+  DOM_FIRST_TEXT(DOM, '#productTitle') AS title,
+  DOM_FIRST_TEXT(DOM, '#product-price') AS price
+FROM
+  DOM_LOAD_AND_SELECT(@url, 'body')

exec
"C:\\Program Files\\PowerShell\\7\\pwsh.exe" -Command 'browser4-cli swarm query --sql "@.test-sessions/ac2-product-query.sql" --seed-file ".test-sessions/seed-urls.txt" --refresh' in D:\workspace\Browser4\Browser4-4.13
 succeeded in 465ms:
Query Submitted: http://localhost:18080/ec/dp/B0E000001 -> Task ID: 10f7cc39-ded2-4d8b-8a84-4f531aa9ec70
Query Submitted: http://localhost:18080/ec/dp/B0E000002 -> Task ID: 3cd299be-7d26-43f7-ba7a-ab71811f22b7
Query Submitted: http://localhost:18080/ec/dp/B0E000003 -> Task ID: e73c4f81-1316-4680-aed5-6cc87bed0f19
3 URL(s) queried. Use 'browser4-cli swarm list' to view all tracked tasks.

💡 Tip: Add --wait to track progress and see results automatically.
Without --wait, use these commands to monitor:
  swarm status <task-id>  — check if a job is done
  swarm result <task-id>  — get the result when complete
  swarm list             — see all tracked tasks
   If jobs appear stuck, run `swarm list` to check their status,
or `swarm list --clear` to remove stale entries from prior sessions.

diff --git a/.test-sessions/ac1-listing-query.sql b/.test-sessions/ac1-listing-query.sql
new file mode 100644
index 0000000000000000000000000000000000000000..d651eec929ece8af65e1fdb818fac08d6e7ef4ea
--- /dev/null
+++ b/.test-sessions/ac1-listing-query.sql
@@ -0,0 +1,6 @@
+SELECT
+  DOM_FIRST_TEXT(DOM, 'div.product-title') AS title,
+  DOM_FIRST_TEXT(DOM, 'div.product-price') AS price,
+  DOM_FIRST_HREF(DOM, 'a.product-link') AS link
+FROM
+  DOM_LOAD_AND_SELECT(@url, '.product-card')
diff --git a/.test-sessions/ac2-product-query.sql b/.test-sessions/ac2-product-query.sql
new file mode 100644
index 0000000000000000000000000000000000000000..1fa45c1ec3c6f272620e6313938e1565b2d4239c
--- /dev/null
+++ b/.test-sessions/ac2-product-query.sql
@@ -0,0 +1,6 @@
+SELECT
+  DOM_BASE_URI(DOM) AS url,
+  DOM_FIRST_TEXT(DOM, '#productTitle') AS title,
+  DOM_FIRST_TEXT(DOM, '#product-price') AS price
+FROM
+  DOM_LOAD_AND_SELECT(@url, 'body')

codex
3 swarm jobs submitted. Polling status now.
exec
"C:\\Program Files\\PowerShell\\7\\pwsh.exe" -Command 'browser4-cli swarm status "10f7cc39-ded2-4d8b-8a84-4f531aa9ec70"; browser4-cli swarm list' in D:\workspace\Browser4\Browser4-4.13
 succeeded in 369ms:
{
  "id": "10f7cc39-ded2-4d8b-8a84-4f531aa9ec70",
  "isDone": false,
  "statusCode": 201,
  "status": "Created",
  "lifecycleState": "queued",
  "message": "",
  "lastModifiedTime": "2026-08-14T07:16:39.057464400Z"
}
Note: Task is queued (statusCode=201 Created).  A worker will pick it up shortly.  Use `swarm list` to see all tracked tasks, or add `--wait` to submit/query to block until completion.
Status: 3 total, 3 queued
3 tracked task(s) (showing 1-3):

  TASK ID       COMMAND      DESCRIPTION                             STARTED              FINISHED             STATUS
  ------------  -----------  --------------------------------------  -------------------  -------------------  ------
  e73c4f81-1316-4680-aed5-6cc87bed0f19  swarm-query  http://localhost:18080/ec/dp/B0E000003  2026-08-14 15:16:38  -                    queued
  3cd299be-7d26-43f7-ba7a-ab71811f22b7  swarm-query  http://localhost:18080/ec/dp/B0E000002  2026-08-14 15:16:38  -                    queued
  10f7cc39-ded2-4d8b-8a84-4f531aa9ec70  swarm-query  http://localhost:18080/ec/dp/B0E000001  2026-08-14 15:16:38  -                    queued

diff --git a/.test-sessions/ac1-listing-query.sql b/.test-sessions/ac1-listing-query.sql
new file mode 100644
index 0000000000000000000000000000000000000000..d651eec929ece8af65e1fdb818fac08d6e7ef4ea
--- /dev/null
+++ b/.test-sessions/ac1-listing-query.sql
@@ -0,0 +1,6 @@
+SELECT
+  DOM_FIRST_TEXT(DOM, 'div.product-title') AS title,
+  DOM_FIRST_TEXT(DOM, 'div.product-price') AS price,
+  DOM_FIRST_HREF(DOM, 'a.product-link') AS link
+FROM
+  DOM_LOAD_AND_SELECT(@url, '.product-card')
diff --git a/.test-sessions/ac2-product-query.sql b/.test-sessions/ac2-product-query.sql
new file mode 100644
index 0000000000000000000000000000000000000000..1fa45c1ec3c6f272620e6313938e1565b2d4239c
--- /dev/null
+++ b/.test-sessions/ac2-product-query.sql
@@ -0,0 +1,6 @@
+SELECT
+  DOM_BASE_URI(DOM) AS url,
+  DOM_FIRST_TEXT(DOM, '#productTitle') AS title,
+  DOM_FIRST_TEXT(DOM, '#product-price') AS price
+FROM
+  DOM_LOAD_AND_SELECT(@url, 'body')

codex
Jobs queued. Waiting briefly, then polling again.
exec
"C:\\Program Files\\PowerShell\\7\\pwsh.exe" -Command 'Start-Sleep -Seconds 12; browser4-cli swarm status "10f7cc39-ded2-4d8b-8a84-4f531aa9ec70"; browser4-cli swarm list' in D:\workspace\Browser4\Browser4-4.13
 succeeded in 12493ms:
{
  "id": "10f7cc39-ded2-4d8b-8a84-4f531aa9ec70",
  "isDone": false,
  "statusCode": 201,
  "status": "Created",
  "lifecycleState": "queued",
  "message": "",
  "lastModifiedTime": "2026-08-14T07:16:39.057464400Z"
}
Note: Task is queued (statusCode=201 Created).  A worker will pick it up shortly.  Use `swarm list` to see all tracked tasks, or add `--wait` to submit/query to block until completion.
Status: 3 total, 2 completed, 1 queued
3 tracked task(s) (showing 1-3):

  TASK ID       COMMAND      DESCRIPTION                             STARTED              FINISHED             STATUS   
  ------------  -----------  --------------------------------------  -------------------  -------------------  ---------
  3cd299be-7d26-43f7-ba7a-ab71811f22b7  swarm-query  http://localhost:18080/ec/dp/B0E000002  2026-08-14 15:16:53  2026-08-14 15:16:53  completed
  e73c4f81-1316-4680-aed5-6cc87bed0f19  swarm-query  http://localhost:18080/ec/dp/B0E000003  2026-08-14 15:16:52  2026-08-14 15:16:52  completed
  10f7cc39-ded2-4d8b-8a84-4f531aa9ec70  swarm-query  http://localhost:18080/ec/dp/B0E000001  2026-08-14 15:16:38  -                    queued   

diff --git a/.test-sessions/ac1-listing-query.sql b/.test-sessions/ac1-listing-query.sql
new file mode 100644
index 0000000000000000000000000000000000000000..d651eec929ece8af65e1fdb818fac08d6e7ef4ea
--- /dev/null
+++ b/.test-sessions/ac1-listing-query.sql
@@ -0,0 +1,6 @@
+SELECT
+  DOM_FIRST_TEXT(DOM, 'div.product-title') AS title,
+  DOM_FIRST_TEXT(DOM, 'div.product-price') AS price,
+  DOM_FIRST_HREF(DOM, 'a.product-link') AS link
+FROM
+  DOM_LOAD_AND_SELECT(@url, '.product-card')
diff --git a/.test-sessions/ac2-product-query.sql b/.test-sessions/ac2-product-query.sql
new file mode 100644
index 0000000000000000000000000000000000000000..1fa45c1ec3c6f272620e6313938e1565b2d4239c
--- /dev/null
+++ b/.test-sessions/ac2-product-query.sql
@@ -0,0 +1,6 @@
+SELECT
+  DOM_BASE_URI(DOM) AS url,
+  DOM_FIRST_TEXT(DOM, '#productTitle') AS title,
+  DOM_FIRST_TEXT(DOM, '#product-price') AS price
+FROM
+  DOM_LOAD_AND_SELECT(@url, 'body')

codex
Two of three completed; the first-submitted job (B0E000001) still shows queued. Waiting a bit more to see if it gets picked up.
exec
"C:\\Program Files\\PowerShell\\7\\pwsh.exe" -Command 'Start-Sleep -Seconds 15; browser4-cli swarm status "10f7cc39-ded2-4d8b-8a84-4f531aa9ec70"; browser4-cli swarm list' in D:\workspace\Browser4\Browser4-4.13
 succeeded in 15426ms:
{
  "id": "10f7cc39-ded2-4d8b-8a84-4f531aa9ec70",
  "isDone": true,
  "statusCode": 200,
  "status": "OK",
  "lifecycleState": "completed",
  "message": "",
  "lastModifiedTime": "2026-08-14T07:17:03.622996900Z"
}
Status: 3 total, 3 completed
3 tracked task(s) (showing 1-3):

  TASK ID       COMMAND      DESCRIPTION                             STARTED              FINISHED             STATUS   
  ------------  -----------  --------------------------------------  -------------------  -------------------  ---------
  10f7cc39-ded2-4d8b-8a84-4f531aa9ec70  swarm-query  http://localhost:18080/ec/dp/B0E000001  2026-08-14 15:17:03  2026-08-14 15:17:03  completed
  3cd299be-7d26-43f7-ba7a-ab71811f22b7  swarm-query  http://localhost:18080/ec/dp/B0E000002  2026-08-14 15:16:53  2026-08-14 15:16:53  completed
  e73c4f81-1316-4680-aed5-6cc87bed0f19  swarm-query  http://localhost:18080/ec/dp/B0E000003  2026-08-14 15:16:52  2026-08-14 15:16:52  completed

diff --git a/.test-sessions/ac1-listing-query.sql b/.test-sessions/ac1-listing-query.sql
new file mode 100644
index 0000000000000000000000000000000000000000..d651eec929ece8af65e1fdb818fac08d6e7ef4ea
--- /dev/null
+++ b/.test-sessions/ac1-listing-query.sql
@@ -0,0 +1,6 @@
+SELECT
+  DOM_FIRST_TEXT(DOM, 'div.product-title') AS title,
+  DOM_FIRST_TEXT(DOM, 'div.product-price') AS price,
+  DOM_FIRST_HREF(DOM, 'a.product-link') AS link
+FROM
+  DOM_LOAD_AND_SELECT(@url, '.product-card')
diff --git a/.test-sessions/ac2-product-query.sql b/.test-sessions/ac2-product-query.sql
new file mode 100644
index 0000000000000000000000000000000000000000..1fa45c1ec3c6f272620e6313938e1565b2d4239c
--- /dev/null
+++ b/.test-sessions/ac2-product-query.sql
@@ -0,0 +1,6 @@
+SELECT
+  DOM_BASE_URI(DOM) AS url,
+  DOM_FIRST_TEXT(DOM, '#productTitle') AS title,
+  DOM_FIRST_TEXT(DOM, '#product-price') AS price
+FROM
+  DOM_LOAD_AND_SELECT(@url, 'body')

codex
All 3 swarm jobs completed. Fetching results.
exec
"C:\\Program Files\\PowerShell\\7\\pwsh.exe" -Command 'browser4-cli swarm result "10f7cc39-ded2-4d8b-8a84-4f531aa9ec70"; browser4-cli swarm result "3cd299be-7d26-43f7-ba7a-ab71811f22b7"; browser4-cli swarm result "e73c4f81-1316-4680-aed5-6cc87bed0f19"' in D:\workspace\Browser4\Browser4-4.13
 succeeded in 457ms:
{
  "id": "10f7cc39-ded2-4d8b-8a84-4f531aa9ec70",
  "resultSet": [
    {
      "url": "http://localhost:18080/ec/dp/B0E000001",
      "title": "4K OLED TV 55",
      "price": "$899.99"
    }
  ],
  "pageContentBytes": 15297,
  "error": null
}
{
  "id": "3cd299be-7d26-43f7-ba7a-ab71811f22b7",
  "resultSet": [
    {
      "url": "http://localhost:18080/ec/dp/B0E000002",
      "title": "Wireless Noise-Cancelling Headphones",
      "price": "$199.99"
    }
  ],
  "pageContentBytes": 15310,
  "error": null
}
{
  "id": "e73c4f81-1316-4680-aed5-6cc87bed0f19",
  "resultSet": [
    {
      "url": "http://localhost:18080/ec/dp/B0E000003",
      "title": "Portable Bluetooth Speaker",
      "price": "$49.99"
    }
  ],
  "pageContentBytes": 14353,
  "error": null
}

diff --git a/.test-sessions/ac1-listing-query.sql b/.test-sessions/ac1-listing-query.sql
new file mode 100644
index 0000000000000000000000000000000000000000..d651eec929ece8af65e1fdb818fac08d6e7ef4ea
--- /dev/null
+++ b/.test-sessions/ac1-listing-query.sql
@@ -0,0 +1,6 @@
+SELECT
+  DOM_FIRST_TEXT(DOM, 'div.product-title') AS title,
+  DOM_FIRST_TEXT(DOM, 'div.product-price') AS price,
+  DOM_FIRST_HREF(DOM, 'a.product-link') AS link
+FROM
+  DOM_LOAD_AND_SELECT(@url, '.product-card')
diff --git a/.test-sessions/ac2-product-query.sql b/.test-sessions/ac2-product-query.sql
new file mode 100644
index 0000000000000000000000000000000000000000..1fa45c1ec3c6f272620e6313938e1565b2d4239c
--- /dev/null
+++ b/.test-sessions/ac2-product-query.sql
@@ -0,0 +1,6 @@
+SELECT
+  DOM_BASE_URI(DOM) AS url,
+  DOM_FIRST_TEXT(DOM, '#productTitle') AS title,
+  DOM_FIRST_TEXT(DOM, '#product-price') AS price
+FROM
+  DOM_LOAD_AND_SELECT(@url, 'body')

codex
All 3 swarm results extracted correctly. Closing the swarm session.
exec
"C:\\Program Files\\PowerShell\\7\\pwsh.exe" -Command 'browser4-cli swarm close' in D:\workspace\Browser4\Browser4-4.13
 succeeded in 402ms:
Swarm session closed. Browser terminated. 3 tracked task(s) retained for history. Use `swarm list --clear` to remove.

diff --git a/.test-sessions/ac1-listing-query.sql b/.test-sessions/ac1-listing-query.sql
new file mode 100644
index 0000000000000000000000000000000000000000..d651eec929ece8af65e1fdb818fac08d6e7ef4ea
--- /dev/null
+++ b/.test-sessions/ac1-listing-query.sql
@@ -0,0 +1,6 @@
+SELECT
+  DOM_FIRST_TEXT(DOM, 'div.product-title') AS title,
+  DOM_FIRST_TEXT(DOM, 'div.product-price') AS price,
+  DOM_FIRST_HREF(DOM, 'a.product-link') AS link
+FROM
+  DOM_LOAD_AND_SELECT(@url, '.product-card')
diff --git a/.test-sessions/ac2-product-query.sql b/.test-sessions/ac2-product-query.sql
new file mode 100644
index 0000000000000000000000000000000000000000..1fa45c1ec3c6f272620e6313938e1565b2d4239c
--- /dev/null
+++ b/.test-sessions/ac2-product-query.sql
@@ -0,0 +1,6 @@
+SELECT
+  DOM_BASE_URI(DOM) AS url,
+  DOM_FIRST_TEXT(DOM, '#productTitle') AS title,
+  DOM_FIRST_TEXT(DOM, '#product-price') AS price
+FROM
+  DOM_LOAD_AND_SELECT(@url, 'body')

codex
AC4 complete. Now AC5 — first open the named `price-watch` session on the product page.
AC4 succeeded: swarm session created, 3 query jobs completed, results fetched, session closed.
  ✓ AC1 — Single list page: htmlsnapshot query with DOM_LOAD_AND_SELECT on MockSite listing
  ✓ AC2 — crawl --seed-file --depth 0 --sql @query.sql over 3 product URLs
  ✓ AC3 — crawl <url> --out-link-selector/-olp on generated crawl fixture
  ✓ AC4 — swarm create/query/status/result/close with seed file
  → AC5 — loop -- eval on named session with status checks
  • AC6 — PowerShell few-URL loop with goto/htmlsnapshot/get
  • Compile execution trace, issues, and overall assessment
diff --git a/.test-sessions/ac1-listing-query.sql b/.test-sessions/ac1-listing-query.sql
new file mode 100644
index 0000000000000000000000000000000000000000..d651eec929ece8af65e1fdb818fac08d6e7ef4ea
--- /dev/null
+++ b/.test-sessions/ac1-listing-query.sql
@@ -0,0 +1,6 @@
+SELECT
+  DOM_FIRST_TEXT(DOM, 'div.product-title') AS title,
+  DOM_FIRST_TEXT(DOM, 'div.product-price') AS price,
+  DOM_FIRST_HREF(DOM, 'a.product-link') AS link
+FROM
+  DOM_LOAD_AND_SELECT(@url, '.product-card')
diff --git a/.test-sessions/ac2-product-query.sql b/.test-sessions/ac2-product-query.sql
new file mode 100644
index 0000000000000000000000000000000000000000..1fa45c1ec3c6f272620e6313938e1565b2d4239c
--- /dev/null
+++ b/.test-sessions/ac2-product-query.sql
@@ -0,0 +1,6 @@
+SELECT
+  DOM_BASE_URI(DOM) AS url,
+  DOM_FIRST_TEXT(DOM, '#productTitle') AS title,
+  DOM_FIRST_TEXT(DOM, '#product-price') AS price
+FROM
+  DOM_LOAD_AND_SELECT(@url, 'body')

exec
"C:\\Program Files\\PowerShell\\7\\pwsh.exe" -Command 'browser4-cli -s price-watch goto "http://localhost:18080/ec/dp/B0E000001"' in D:\workspace\Browser4\Browser4-4.13
 succeeded in 7011ms:
Session opened: price-watch (8560b9b9-070c-417a-ba32-50319bc3211c)
Navigated to http://localhost:18080/ec/dp/B0E000001
Page loaded. Use `wait --load networkidle` if content appears incomplete (e.g. async-rendered SPAs).
### Page
- Page URL: http://localhost:18080/ec/dp/B0E000001
- Page Title: Product: 4K OLED TV 55
### Snapshot
[Snapshot](D:\workspace\Browser4\Browser4-4.13\.browser4-cli\snapshot\snapshot-2026-08-14T07-17-32-219Z.yml)
No active session — creating a new one.
💡 Tip: Try `htmlsnapshot get text "h1"` to extract the page heading, or `htmlsnapshot inspect` to discover CSS selectors

diff --git a/.test-sessions/ac1-listing-query.sql b/.test-sessions/ac1-listing-query.sql
new file mode 100644
index 0000000000000000000000000000000000000000..d651eec929ece8af65e1fdb818fac08d6e7ef4ea
--- /dev/null
+++ b/.test-sessions/ac1-listing-query.sql
@@ -0,0 +1,6 @@
+SELECT
+  DOM_FIRST_TEXT(DOM, 'div.product-title') AS title,
+  DOM_FIRST_TEXT(DOM, 'div.product-price') AS price,
+  DOM_FIRST_HREF(DOM, 'a.product-link') AS link
+FROM
+  DOM_LOAD_AND_SELECT(@url, '.product-card')
diff --git a/.test-sessions/ac2-product-query.sql b/.test-sessions/ac2-product-query.sql
new file mode 100644
index 0000000000000000000000000000000000000000..1fa45c1ec3c6f272620e6313938e1565b2d4239c
--- /dev/null
+++ b/.test-sessions/ac2-product-query.sql
@@ -0,0 +1,6 @@
+SELECT
+  DOM_BASE_URI(DOM) AS url,
+  DOM_FIRST_TEXT(DOM, '#productTitle') AS title,
+  DOM_FIRST_TEXT(DOM, '#product-price') AS price
+FROM
+  DOM_LOAD_AND_SELECT(@url, 'body')

codex
Session open. Let me verify the eval target works in this session, then start the named loop in the background so I can inspect its status while running.
exec
"C:\\Program Files\\PowerShell\\7\\pwsh.exe" -Command "browser4-cli -s price-watch eval \"document.querySelector('#product-price').textContent.trim()\"" in D:\workspace\Browser4\Browser4-4.13
 succeeded in 416ms:
$899.99

diff --git a/.test-sessions/ac1-listing-query.sql b/.test-sessions/ac1-listing-query.sql
new file mode 100644
index 0000000000000000000000000000000000000000..d651eec929ece8af65e1fdb818fac08d6e7ef4ea
--- /dev/null
+++ b/.test-sessions/ac1-listing-query.sql
@@ -0,0 +1,6 @@
+SELECT
+  DOM_FIRST_TEXT(DOM, 'div.product-title') AS title,
+  DOM_FIRST_TEXT(DOM, 'div.product-price') AS price,
+  DOM_FIRST_HREF(DOM, 'a.product-link') AS link
+FROM
+  DOM_LOAD_AND_SELECT(@url, '.product-card')
diff --git a/.test-sessions/ac2-product-query.sql b/.test-sessions/ac2-product-query.sql
new file mode 100644
index 0000000000000000000000000000000000000000..1fa45c1ec3c6f272620e6313938e1565b2d4239c
--- /dev/null
+++ b/.test-sessions/ac2-product-query.sql
@@ -0,0 +1,6 @@
+SELECT
+  DOM_BASE_URI(DOM) AS url,
+  DOM_FIRST_TEXT(DOM, '#productTitle') AS title,
+  DOM_FIRST_TEXT(DOM, '#product-price') AS price
+FROM
+  DOM_LOAD_AND_SELECT(@url, 'body')

apply patch
patch: completed
D:\workspace\Browser4\Browser4-4.13\.test-sessions\run-loop.ps1
diff --git a/.test-sessions/ac1-listing-query.sql b/.test-sessions/ac1-listing-query.sql
new file mode 100644
index 0000000000000000000000000000000000000000..d651eec929ece8af65e1fdb818fac08d6e7ef4ea
--- /dev/null
+++ b/.test-sessions/ac1-listing-query.sql
@@ -0,0 +1,6 @@
+SELECT
+  DOM_FIRST_TEXT(DOM, 'div.product-title') AS title,
+  DOM_FIRST_TEXT(DOM, 'div.product-price') AS price,
+  DOM_FIRST_HREF(DOM, 'a.product-link') AS link
+FROM
+  DOM_LOAD_AND_SELECT(@url, '.product-card')
diff --git a/.test-sessions/ac2-product-query.sql b/.test-sessions/ac2-product-query.sql
new file mode 100644
index 0000000000000000000000000000000000000000..1fa45c1ec3c6f272620e6313938e1565b2d4239c
--- /dev/null
+++ b/.test-sessions/ac2-product-query.sql
@@ -0,0 +1,6 @@
+SELECT
+  DOM_BASE_URI(DOM) AS url,
+  DOM_FIRST_TEXT(DOM, '#productTitle') AS title,
+  DOM_FIRST_TEXT(DOM, '#product-price') AS price
+FROM
+  DOM_LOAD_AND_SELECT(@url, 'body')
diff --git a/.test-sessions/run-loop.ps1 b/.test-sessions/run-loop.ps1
new file mode 100644
index 0000000000000000000000000000000000000000..a4da61b00ecf6843a91fe250ac4cb5afe8d6a8ec
--- /dev/null
+++ b/.test-sessions/run-loop.ps1
@@ -0,0 +1,4 @@
+#!/usr/bin/env pwsh
+$ErrorActionPreference = 'Continue'
+$out = Join-Path $PSScriptRoot 'loop-run.log'
+browser4-cli loop --name mock-price-watch --count 2 -i 10 -- -s price-watch eval "document.querySelector('#product-price').textContent.trim()" *> $out

diff --git a/.test-sessions/ac1-listing-query.sql b/.test-sessions/ac1-listing-query.sql
new file mode 100644
index 0000000000000000000000000000000000000000..d651eec929ece8af65e1fdb818fac08d6e7ef4ea
--- /dev/null
+++ b/.test-sessions/ac1-listing-query.sql
@@ -0,0 +1,6 @@
+SELECT
+  DOM_FIRST_TEXT(DOM, 'div.product-title') AS title,
+  DOM_FIRST_TEXT(DOM, 'div.product-price') AS price,
+  DOM_FIRST_HREF(DOM, 'a.product-link') AS link
+FROM
+  DOM_LOAD_AND_SELECT(@url, '.product-card')
diff --git a/.test-sessions/ac2-product-query.sql b/.test-sessions/ac2-product-query.sql
new file mode 100644
index 0000000000000000000000000000000000000000..1fa45c1ec3c6f272620e6313938e1565b2d4239c
--- /dev/null
+++ b/.test-sessions/ac2-product-query.sql
@@ -0,0 +1,6 @@
+SELECT
+  DOM_BASE_URI(DOM) AS url,
+  DOM_FIRST_TEXT(DOM, '#productTitle') AS title,
+  DOM_FIRST_TEXT(DOM, '#product-price') AS price
+FROM
+  DOM_LOAD_AND_SELECT(@url, 'body')
diff --git a/.test-sessions/run-loop.ps1 b/.test-sessions/run-loop.ps1
new file mode 100644
index 0000000000000000000000000000000000000000..a4da61b00ecf6843a91fe250ac4cb5afe8d6a8ec
--- /dev/null
+++ b/.test-sessions/run-loop.ps1
@@ -0,0 +1,4 @@
+#!/usr/bin/env pwsh
+$ErrorActionPreference = 'Continue'
+$out = Join-Path $PSScriptRoot 'loop-run.log'
+browser4-cli loop --name mock-price-watch --count 2 -i 10 -- -s price-watch eval "document.querySelector('#product-price').textContent.trim()" *> $out

exec
"C:\\Program Files\\PowerShell\\7\\pwsh.exe" -Command '$p = Start-Process pwsh -WindowStyle Hidden -ArgumentList @('"'-NoProfile','-File','D:\\workspace\\Browser4\\Browser4-4.13\\.test-sessions\\run-loop.ps1') -PassThru; \"Started loop PID "'$($p.Id)"; Start-Sleep -Seconds 4; browser4-cli loop --list' in D:\workspace\Browser4\Browser4-4.13
 succeeded in 4616ms:
Started loop PID 57916
1 persisted loop(s):

  ▶  mock-price-watch         1/2 iters    10s intv  running   -s price-watch eval document.querySelector('#product-price').textContent.trim()

Columns: name, iters (completed[/max]), interval, status, task.
Use --status [name] for full details, --pause/--resume [name] to control, --stop [name] to clear.

diff --git a/.test-sessions/ac1-listing-query.sql b/.test-sessions/ac1-listing-query.sql
new file mode 100644
index 0000000000000000000000000000000000000000..d651eec929ece8af65e1fdb818fac08d6e7ef4ea
--- /dev/null
+++ b/.test-sessions/ac1-listing-query.sql
@@ -0,0 +1,6 @@
+SELECT
+  DOM_FIRST_TEXT(DOM, 'div.product-title') AS title,
+  DOM_FIRST_TEXT(DOM, 'div.product-price') AS price,
+  DOM_FIRST_HREF(DOM, 'a.product-link') AS link
+FROM
+  DOM_LOAD_AND_SELECT(@url, '.product-card')
diff --git a/.test-sessions/ac2-product-query.sql b/.test-sessions/ac2-product-query.sql
new file mode 100644
index 0000000000000000000000000000000000000000..1fa45c1ec3c6f272620e6313938e1565b2d4239c
--- /dev/null
+++ b/.test-sessions/ac2-product-query.sql
@@ -0,0 +1,6 @@
+SELECT
+  DOM_BASE_URI(DOM) AS url,
+  DOM_FIRST_TEXT(DOM, '#productTitle') AS title,
+  DOM_FIRST_TEXT(DOM, '#product-price') AS price
+FROM
+  DOM_LOAD_AND_SELECT(@url, 'body')
diff --git a/.test-sessions/run-loop.ps1 b/.test-sessions/run-loop.ps1
new file mode 100644
index 0000000000000000000000000000000000000000..a4da61b00ecf6843a91fe250ac4cb5afe8d6a8ec
--- /dev/null
+++ b/.test-sessions/run-loop.ps1
@@ -0,0 +1,4 @@
+#!/usr/bin/env pwsh
+$ErrorActionPreference = 'Continue'
+$out = Join-Path $PSScriptRoot 'loop-run.log'
+browser4-cli loop --name mock-price-watch --count 2 -i 10 -- -s price-watch eval "document.querySelector('#product-price').textContent.trim()" *> $out

codex
Loop is running (1/2 iterations done). Checking `loop --status` while it runs.
exec
"C:\\Program Files\\PowerShell\\7\\pwsh.exe" -Command 'browser4-cli loop --status --name mock-price-watch; Start-Sleep -Seconds 14; browser4-cli loop --list; browser4-cli loop --status --name mock-price-watch' in D:\workspace\Browser4\Browser4-4.13
 succeeded in 14373ms:
▶  Loop "mock-price-watch" Running
   Task:       -s price-watch eval document.querySelector('#product-price').textContent.trim()
   Mode:       subcommand
   Interval:   10s
   Iterations: 1/2 (1 remaining)
   Timeout:    1w total, 6s elapsed, 6d 23h 59m 54s remaining
   Started:    2026-08-14 15:17:44
   Updated:    2026-08-14 15:17:44
   State file: C:\Users\pereg\.browser4\loops\mock-price-watch.json
No persisted loops. Start one with `browser4-cli loop <task>`.
No loop named "mock-price-watch".
State file: C:\Users\pereg\.browser4\loops\mock-price-watch.json

diff --git a/.test-sessions/ac1-listing-query.sql b/.test-sessions/ac1-listing-query.sql
new file mode 100644
index 0000000000000000000000000000000000000000..d651eec929ece8af65e1fdb818fac08d6e7ef4ea
--- /dev/null
+++ b/.test-sessions/ac1-listing-query.sql
@@ -0,0 +1,6 @@
+SELECT
+  DOM_FIRST_TEXT(DOM, 'div.product-title') AS title,
+  DOM_FIRST_TEXT(DOM, 'div.product-price') AS price,
+  DOM_FIRST_HREF(DOM, 'a.product-link') AS link
+FROM
+  DOM_LOAD_AND_SELECT(@url, '.product-card')
diff --git a/.test-sessions/ac2-product-query.sql b/.test-sessions/ac2-product-query.sql
new file mode 100644
index 0000000000000000000000000000000000000000..1fa45c1ec3c6f272620e6313938e1565b2d4239c
--- /dev/null
+++ b/.test-sessions/ac2-product-query.sql
@@ -0,0 +1,6 @@
+SELECT
+  DOM_BASE_URI(DOM) AS url,
+  DOM_FIRST_TEXT(DOM, '#productTitle') AS title,
+  DOM_FIRST_TEXT(DOM, '#product-price') AS price
+FROM
+  DOM_LOAD_AND_SELECT(@url, 'body')
diff --git a/.test-sessions/run-loop.ps1 b/.test-sessions/run-loop.ps1
new file mode 100644
index 0000000000000000000000000000000000000000..a4da61b00ecf6843a91fe250ac4cb5afe8d6a8ec
--- /dev/null
+++ b/.test-sessions/run-loop.ps1
@@ -0,0 +1,4 @@
+#!/usr/bin/env pwsh
+$ErrorActionPreference = 'Continue'
+$out = Join-Path $PSScriptRoot 'loop-run.log'
+browser4-cli loop --name mock-price-watch --count 2 -i 10 -- -s price-watch eval "document.querySelector('#product-price').textContent.trim()" *> $out

codex
Loop completed its 2 iterations (state auto-cleaned as documented). Checking the captured log for both iteration outputs.
exec
"C:\\Program Files\\PowerShell\\7\\pwsh.exe" -Command "Get-Content \".test-sessions\\loop-run.log\" -Raw" in D:\workspace\Browser4\Browser4-4.13
 succeeded in 321ms:
Loop: "-s price-watch eval document.querySelector('#product-price').textContent.trim()" 鈥?every 10s, up to 2 iterations or 604800s
  Mode: browser4-cli subcommand

--- Iteration 1 [2026-08-14 15:17:44.596] ---
$899.99

--- Iteration 2 [2026-08-14 15:17:54.647] ---
$899.99

========================================
鉁? Loop finished 鈥?2 iteration(s) completed.


diff --git a/.test-sessions/ac1-listing-query.sql b/.test-sessions/ac1-listing-query.sql
new file mode 100644
index 0000000000000000000000000000000000000000..d651eec929ece8af65e1fdb818fac08d6e7ef4ea
--- /dev/null
+++ b/.test-sessions/ac1-listing-query.sql
@@ -0,0 +1,6 @@
+SELECT
+  DOM_FIRST_TEXT(DOM, 'div.product-title') AS title,
+  DOM_FIRST_TEXT(DOM, 'div.product-price') AS price,
+  DOM_FIRST_HREF(DOM, 'a.product-link') AS link
+FROM
+  DOM_LOAD_AND_SELECT(@url, '.product-card')
diff --git a/.test-sessions/ac2-product-query.sql b/.test-sessions/ac2-product-query.sql
new file mode 100644
index 0000000000000000000000000000000000000000..1fa45c1ec3c6f272620e6313938e1565b2d4239c
--- /dev/null
+++ b/.test-sessions/ac2-product-query.sql
@@ -0,0 +1,6 @@
+SELECT
+  DOM_BASE_URI(DOM) AS url,
+  DOM_FIRST_TEXT(DOM, '#productTitle') AS title,
+  DOM_FIRST_TEXT(DOM, '#product-price') AS price
+FROM
+  DOM_LOAD_AND_SELECT(@url, 'body')
diff --git a/.test-sessions/run-loop.ps1 b/.test-sessions/run-loop.ps1
new file mode 100644
index 0000000000000000000000000000000000000000..a4da61b00ecf6843a91fe250ac4cb5afe8d6a8ec
--- /dev/null
+++ b/.test-sessions/run-loop.ps1
@@ -0,0 +1,4 @@
+#!/usr/bin/env pwsh
+$ErrorActionPreference = 'Continue'
+$out = Join-Path $PSScriptRoot 'loop-run.log'
+browser4-cli loop --name mock-price-watch --count 2 -i 10 -- -s price-watch eval "document.querySelector('#product-price').textContent.trim()" *> $out

codex
AC5 complete — loop ran 2 iterations, each returning `$899.99`, with status/list inspectable mid-run. Now AC6: a lightweight PowerShell few-URL loop.
apply patch
patch: completed
D:\workspace\Browser4\Browser4-4.13\.test-sessions\ac6-few-urls.ps1
diff --git a/.test-sessions/ac1-listing-query.sql b/.test-sessions/ac1-listing-query.sql
new file mode 100644
index 0000000000000000000000000000000000000000..d651eec929ece8af65e1fdb818fac08d6e7ef4ea
--- /dev/null
+++ b/.test-sessions/ac1-listing-query.sql
@@ -0,0 +1,6 @@
+SELECT
+  DOM_FIRST_TEXT(DOM, 'div.product-title') AS title,
+  DOM_FIRST_TEXT(DOM, 'div.product-price') AS price,
+  DOM_FIRST_HREF(DOM, 'a.product-link') AS link
+FROM
+  DOM_LOAD_AND_SELECT(@url, '.product-card')
diff --git a/.test-sessions/ac2-product-query.sql b/.test-sessions/ac2-product-query.sql
new file mode 100644
index 0000000000000000000000000000000000000000..1fa45c1ec3c6f272620e6313938e1565b2d4239c
--- /dev/null
+++ b/.test-sessions/ac2-product-query.sql
@@ -0,0 +1,6 @@
+SELECT
+  DOM_BASE_URI(DOM) AS url,
+  DOM_FIRST_TEXT(DOM, '#productTitle') AS title,
+  DOM_FIRST_TEXT(DOM, '#product-price') AS price
+FROM
+  DOM_LOAD_AND_SELECT(@url, 'body')
diff --git a/.test-sessions/ac6-few-urls.ps1 b/.test-sessions/ac6-few-urls.ps1
new file mode 100644
index 0000000000000000000000000000000000000000..07a5a6936b1fab6f1574eb1c9627014aa6157e04
--- /dev/null
+++ b/.test-sessions/ac6-few-urls.ps1
@@ -0,0 +1,18 @@
+#!/usr/bin/env pwsh
+$ErrorActionPreference = 'Continue'
+
+$urls = @(
+    "http://localhost:18080/ec/dp/B0E000001",
+    "http://localhost:18080/ec/dp/B0E000004",
+    "http://localhost:18080/ec/dp/B0E000005"
+)
+
+$urls | ForEach-Object {
+    $u = $_
+    Write-Host "=== Processing: $u ==="
+    browser4-cli goto $u
+    browser4-cli htmlsnapshot
+    $title = browser4-cli htmlsnapshot get text "#productTitle"
+    Write-Host "Title: $title"
+    Start-Sleep -Seconds 2
+}
diff --git a/.test-sessions/run-loop.ps1 b/.test-sessions/run-loop.ps1
new file mode 100644
index 0000000000000000000000000000000000000000..a4da61b00ecf6843a91fe250ac4cb5afe8d6a8ec
--- /dev/null
+++ b/.test-sessions/run-loop.ps1
@@ -0,0 +1,4 @@
+#!/usr/bin/env pwsh
+$ErrorActionPreference = 'Continue'
+$out = Join-Path $PSScriptRoot 'loop-run.log'
+browser4-cli loop --name mock-price-watch --count 2 -i 10 -- -s price-watch eval "document.querySelector('#product-price').textContent.trim()" *> $out

diff --git a/.test-sessions/ac1-listing-query.sql b/.test-sessions/ac1-listing-query.sql
new file mode 100644
index 0000000000000000000000000000000000000000..d651eec929ece8af65e1fdb818fac08d6e7ef4ea
--- /dev/null
+++ b/.test-sessions/ac1-listing-query.sql
@@ -0,0 +1,6 @@
+SELECT
+  DOM_FIRST_TEXT(DOM, 'div.product-title') AS title,
+  DOM_FIRST_TEXT(DOM, 'div.product-price') AS price,
+  DOM_FIRST_HREF(DOM, 'a.product-link') AS link
+FROM
+  DOM_LOAD_AND_SELECT(@url, '.product-card')
diff --git a/.test-sessions/ac2-product-query.sql b/.test-sessions/ac2-product-query.sql
new file mode 100644
index 0000000000000000000000000000000000000000..1fa45c1ec3c6f272620e6313938e1565b2d4239c
--- /dev/null
+++ b/.test-sessions/ac2-product-query.sql
@@ -0,0 +1,6 @@
+SELECT
+  DOM_BASE_URI(DOM) AS url,
+  DOM_FIRST_TEXT(DOM, '#productTitle') AS title,
+  DOM_FIRST_TEXT(DOM, '#product-price') AS price
+FROM
+  DOM_LOAD_AND_SELECT(@url, 'body')
diff --git a/.test-sessions/ac6-few-urls.ps1 b/.test-sessions/ac6-few-urls.ps1
new file mode 100644
index 0000000000000000000000000000000000000000..07a5a6936b1fab6f1574eb1c9627014aa6157e04
--- /dev/null
+++ b/.test-sessions/ac6-few-urls.ps1
@@ -0,0 +1,18 @@
+#!/usr/bin/env pwsh
+$ErrorActionPreference = 'Continue'
+
+$urls = @(
+    "http://localhost:18080/ec/dp/B0E000001",
+    "http://localhost:18080/ec/dp/B0E000004",
+    "http://localhost:18080/ec/dp/B0E000005"
+)
+
+$urls | ForEach-Object {
+    $u = $_
+    Write-Host "=== Processing: $u ==="
+    browser4-cli goto $u
+    browser4-cli htmlsnapshot
+    $title = browser4-cli htmlsnapshot get text "#productTitle"
+    Write-Host "Title: $title"
+    Start-Sleep -Seconds 2
+}
diff --git a/.test-sessions/run-loop.ps1 b/.test-sessions/run-loop.ps1
new file mode 100644
index 0000000000000000000000000000000000000000..a4da61b00ecf6843a91fe250ac4cb5afe8d6a8ec
--- /dev/null
+++ b/.test-sessions/run-loop.ps1
@@ -0,0 +1,4 @@
+#!/usr/bin/env pwsh
+$ErrorActionPreference = 'Continue'
+$out = Join-Path $PSScriptRoot 'loop-run.log'
+browser4-cli loop --name mock-price-watch --count 2 -i 10 -- -s price-watch eval "document.querySelector('#product-price').textContent.trim()" *> $out

exec
"C:\\Program Files\\PowerShell\\7\\pwsh.exe" -Command "pwsh -NoProfile -File \"D:\\workspace\\Browser4\\Browser4-4.13\\.test-sessions\\ac6-few-urls.ps1\"" in D:\workspace\Browser4\Browser4-4.13
 succeeded in 20619ms:
=== Processing: http://localhost:18080/ec/dp/B0E000001 ===
Using existing session DEFAULT (current page: http://localhost:18080/ec/b?node=1292115012).
Navigated to http://localhost:18080/ec/dp/B0E000001
Page loaded. Use `wait --load networkidle` if content appears incomplete (e.g. async-rendered SPAs).
### Page
- Page URL: http://localhost:18080/ec/dp/B0E000001
- Page Title: Product: 4K OLED TV 55
### Snapshot
[Snapshot](D:\workspace\Browser4\Browser4-4.13\.browser4-cli\snapshot\snapshot-2026-08-14T07-18-19-425Z.yml)
Snapshot: "Product: 4K OLED TV 55"
http://localhost:18080/ec/dp/B0E000001 · 15 KB · text/html · captured 2026-08-14T07:18:19.837Z
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
Title: 4K OLED TV 55
=== Processing: http://localhost:18080/ec/dp/B0E000004 ===
Using existing session DEFAULT (current page: http://localhost:18080/ec/dp/B0E000001).
Navigated to http://localhost:18080/ec/dp/B0E000004
Page loaded. Use `wait --load networkidle` if content appears incomplete (e.g. async-rendered SPAs).
### Page
- Page URL: http://localhost:18080/ec/dp/B0E000004
- Page Title: Product: Smartphone 128GB
### Snapshot
[Snapshot](D:\workspace\Browser4\Browser4-4.13\.browser4-cli\snapshot\snapshot-2026-08-14T07-18-26-229Z.yml)
Snapshot: "Product: Smartphone 128GB"
http://localhost:18080/ec/dp/B0E000004 · 14 KB · text/html · captured 2026-08-14T07:18:26.353Z
1 images · 11 links · 13 interactive elements

### Interactive Elements
  Buttons (2):
    2. #product-page button.add-cart                "Add to cart"  [box=1325 336 282 41; w=1011562]
    3. #product-page button.buy-now                 "Buy now"  [box=1325 387 282 41; w=1011562]

  Links (11):
    4. a                                            "Wireless Noise-Cancelling Headphones"  [box=657 1074 200 37; w=7400]
    5. a                                            "Portable Bluetooth Speaker"  [box=985 1074 210 18; w=3780]
    6. a                                            "USB-C Hub 7-in-1"  [box=1312 1074 133 18; w=2394]
    7. a                                            "4K OLED TV 55"  [box=330 1074 116 18; w=2088]
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
Title: Smartphone 128GB
=== Processing: http://localhost:18080/ec/dp/B0E000005 ===
Using existing session DEFAULT (current page: http://localhost:18080/ec/dp/B0E000004).
Navigated to http://localhost:18080/ec/dp/B0E000005
Page loaded. Use `wait --load networkidle` if content appears incomplete (e.g. async-rendered SPAs).
### Page
- Page URL: http://localhost:18080/ec/dp/B0E000005
- Page Title: Product: USB-C Hub 7-in-1
### Snapshot
[Snapshot](D:\workspace\Browser4\Browser4-4.13\.browser4-cli\snapshot\snapshot-2026-08-14T07-18-32-792Z.yml)
Snapshot: "Product: USB-C Hub 7-in-1"
http://localhost:18080/ec/dp/B0E000005 · 12 KB · text/html · captured 2026-08-14T07:18:32.969Z
1 images · 11 links · 13 interactive elements

### Interactive Elements
  Buttons (2):
    2. #product-page button.add-cart                "Add to cart"  [box=1325 253 282 41; w=1011562]
    3. #product-page button.buy-now                 "Buy now"  [box=1325 304 282 41; w=1011562]

  Links (11):
    4. a                                            "Wireless Noise-Cancelling Headphones"  [box=657 938 200 37; w=7400]
    5. a                                            "Portable Bluetooth Speaker"  [box=985 938 210 18; w=3780]
    6. a                                            "Smartphone 128GB"  [box=1312 938 148 18; w=2664]
    7. a                                            "4K OLED TV 55"  [box=330 938 116 18; w=2088]
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
Title: USB-C Hub 7-in-1
💡 Tip: Try `htmlsnapshot get text "h1"` to extract the page heading, or `htmlsnapshot inspect` to discover CSS selectors
💡 Tip: Try `htmlsnapshot get text "h1"` to extract the page heading, or `htmlsnapshot inspect` to discover CSS selectors
💡 Tip: Try `htmlsnapshot get text "h1"` to extract the page heading, or `htmlsnapshot inspect` to discover CSS selectors

diff --git a/.test-sessions/ac1-listing-query.sql b/.test-sessions/ac1-listing-query.sql
new file mode 100644
index 0000000000000000000000000000000000000000..d651eec929ece8af65e1fdb818fac08d6e7ef4ea
--- /dev/null
+++ b/.test-sessions/ac1-listing-query.sql
@@ -0,0 +1,6 @@
+SELECT
+  DOM_FIRST_TEXT(DOM, 'div.product-title') AS title,
+  DOM_FIRST_TEXT(DOM, 'div.product-price') AS price,
+  DOM_FIRST_HREF(DOM, 'a.product-link') AS link
+FROM
+  DOM_LOAD_AND_SELECT(@url, '.product-card')
diff --git a/.test-sessions/ac2-product-query.sql b/.test-sessions/ac2-product-query.sql
new file mode 100644
index 0000000000000000000000000000000000000000..1fa45c1ec3c6f272620e6313938e1565b2d4239c
--- /dev/null
+++ b/.test-sessions/ac2-product-query.sql
@@ -0,0 +1,6 @@
+SELECT
+  DOM_BASE_URI(DOM) AS url,
+  DOM_FIRST_TEXT(DOM, '#productTitle') AS title,
+  DOM_FIRST_TEXT(DOM, '#product-price') AS price
+FROM
+  DOM_LOAD_AND_SELECT(@url, 'body')
diff --git a/.test-sessions/ac6-few-urls.ps1 b/.test-sessions/ac6-few-urls.ps1
new file mode 100644
index 0000000000000000000000000000000000000000..07a5a6936b1fab6f1574eb1c9627014aa6157e04
--- /dev/null
+++ b/.test-sessions/ac6-few-urls.ps1
@@ -0,0 +1,18 @@
+#!/usr/bin/env pwsh
+$ErrorActionPreference = 'Continue'
+
+$urls = @(
+    "http://localhost:18080/ec/dp/B0E000001",
+    "http://localhost:18080/ec/dp/B0E000004",
+    "http://localhost:18080/ec/dp/B0E000005"
+)
+
+$urls | ForEach-Object {
+    $u = $_
+    Write-Host "=== Processing: $u ==="
+    browser4-cli goto $u
+    browser4-cli htmlsnapshot
+    $title = browser4-cli htmlsnapshot get text "#productTitle"
+    Write-Host "Title: $title"
+    Start-Sleep -Seconds 2
+}
diff --git a/.test-sessions/run-loop.ps1 b/.test-sessions/run-loop.ps1
new file mode 100644
index 0000000000000000000000000000000000000000..a4da61b00ecf6843a91fe250ac4cb5afe8d6a8ec
--- /dev/null
+++ b/.test-sessions/run-loop.ps1
@@ -0,0 +1,4 @@
+#!/usr/bin/env pwsh
+$ErrorActionPreference = 'Continue'
+$out = Join-Path $PSScriptRoot 'loop-run.log'
+browser4-cli loop --name mock-price-watch --count 2 -i 10 -- -s price-watch eval "document.querySelector('#product-price').textContent.trim()" *> $out

codex
All six ACs are complete. Let me run a couple of quick verification/reproduction checks for observations I made (the odd session-open message and swarm/crawl quirks) before writing up the evaluation.
exec
"C:\\Program Files\\PowerShell\\7\\pwsh.exe" -Command 'browser4-cli -s ac1-check goto "http://localhost:18080/ec/dp/B0E000001"; browser4-cli -s ac1-check goto "http://localhost:18080/ec/dp/B0E000002"' in D:\workspace\Browser4\Browser4-4.13
 succeeded in 11910ms:
Session opened: ac1-check (43e4f392-970e-4182-b8ba-63ba272e2ea4)
Navigated to http://localhost:18080/ec/dp/B0E000001
Page loaded. Use `wait --load networkidle` if content appears incomplete (e.g. async-rendered SPAs).
### Page
- Page URL: http://localhost:18080/ec/dp/B0E000001
- Page Title: Product: 4K OLED TV 55
### Snapshot
[Snapshot](D:\workspace\Browser4\Browser4-4.13\.browser4-cli\snapshot\snapshot-2026-08-14T07-18-56-543Z.yml)
Using existing session ac1-check (43e4f392-970e-4182-b8ba-63ba272e2ea4) (current page: http://localhost:18080/ec/dp/B0E000001).
Navigated to http://localhost:18080/ec/dp/B0E000002
Page loaded. Use `wait --load networkidle` if content appears incomplete (e.g. async-rendered SPAs).
### Page
- Page URL: http://localhost:18080/ec/dp/B0E000002
- Page Title: Product: Wireless Noise-Cancelling Headphones
### Snapshot
[Snapshot](D:\workspace\Browser4\Browser4-4.13\.browser4-cli\snapshot\snapshot-2026-08-14T07-19-00-798Z.yml)
No active session — creating a new one.
💡 Tip: Try `htmlsnapshot get text "h1"` to extract the page heading, or `htmlsnapshot inspect` to discover CSS selectors
💡 Tip: Try `htmlsnapshot get text "h1"` to extract the page heading, or `htmlsnapshot inspect` to discover CSS selectors

diff --git a/.test-sessions/ac1-listing-query.sql b/.test-sessions/ac1-listing-query.sql
new file mode 100644
index 0000000000000000000000000000000000000000..d651eec929ece8af65e1fdb818fac08d6e7ef4ea
--- /dev/null
+++ b/.test-sessions/ac1-listing-query.sql
@@ -0,0 +1,6 @@
+SELECT
+  DOM_FIRST_TEXT(DOM, 'div.product-title') AS title,
+  DOM_FIRST_TEXT(DOM, 'div.product-price') AS price,
+  DOM_FIRST_HREF(DOM, 'a.product-link') AS link
+FROM
+  DOM_LOAD_AND_SELECT(@url, '.product-card')
diff --git a/.test-sessions/ac2-product-query.sql b/.test-sessions/ac2-product-query.sql
new file mode 100644
index 0000000000000000000000000000000000000000..1fa45c1ec3c6f272620e6313938e1565b2d4239c
--- /dev/null
+++ b/.test-sessions/ac2-product-query.sql
@@ -0,0 +1,6 @@
+SELECT
+  DOM_BASE_URI(DOM) AS url,
+  DOM_FIRST_TEXT(DOM, '#productTitle') AS title,
+  DOM_FIRST_TEXT(DOM, '#product-price') AS price
+FROM
+  DOM_LOAD_AND_SELECT(@url, 'body')
diff --git a/.test-sessions/ac6-few-urls.ps1 b/.test-sessions/ac6-few-urls.ps1
new file mode 100644
index 0000000000000000000000000000000000000000..07a5a6936b1fab6f1574eb1c9627014aa6157e04
--- /dev/null
+++ b/.test-sessions/ac6-few-urls.ps1
@@ -0,0 +1,18 @@
+#!/usr/bin/env pwsh
+$ErrorActionPreference = 'Continue'
+
+$urls = @(
+    "http://localhost:18080/ec/dp/B0E000001",
+    "http://localhost:18080/ec/dp/B0E000004",
+    "http://localhost:18080/ec/dp/B0E000005"
+)
+
+$urls | ForEach-Object {
+    $u = $_
+    Write-Host "=== Processing: $u ==="
+    browser4-cli goto $u
+    browser4-cli htmlsnapshot
+    $title = browser4-cli htmlsnapshot get text "#productTitle"
+    Write-Host "Title: $title"
+    Start-Sleep -Seconds 2
+}
diff --git a/.test-sessions/run-loop.ps1 b/.test-sessions/run-loop.ps1
new file mode 100644
index 0000000000000000000000000000000000000000..a4da61b00ecf6843a91fe250ac4cb5afe8d6a8ec
--- /dev/null
+++ b/.test-sessions/run-loop.ps1
@@ -0,0 +1,4 @@
+#!/usr/bin/env pwsh
+$ErrorActionPreference = 'Continue'
+$out = Join-Path $PSScriptRoot 'loop-run.log'
+browser4-cli loop --name mock-price-watch --count 2 -i 10 -- -s price-watch eval "document.querySelector('#product-price').textContent.trim()" *> $out

codex
The stray "No active session — creating a new one." message reproduces reliably after successful `goto`. Let me find its source in the CLI code to identify the root cause.
exec
"C:\\Program Files\\PowerShell\\7\\pwsh.exe" -Command 'rg -n "No active session" cli/browser4-cli/src --glob "*.rs"' in D:\workspace\Browser4\Browser4-4.13
exec
"C:\\Program Files\\PowerShell\\7\\pwsh.exe" -Command 'rg -n "creating a new one" cli/browser4-cli/src --glob "*.rs"' in D:\workspace\Browser4\Browser4-4.13
 succeeded in 329ms:
cli/browser4-cli/src\main.rs:260:    /// No active session, session expired, session conflict.
cli/browser4-cli/src\main.rs:504:        "No active session is currently stored for this CLI context.",
cli/browser4-cli/src\main.rs:1248:        // No active session found. Create a new one explicitly so the user
cli/browser4-cli/src\main.rs:1252:            eprintln!("No active session — creating a new one.");
cli/browser4-cli/src\main.rs:18423:        assert!(message.contains("No active session is currently stored"));

 succeeded in 351ms:
cli/browser4-cli/src\main.rs:566:/// creating a new one would silently displace it.  This check returns an
cli/browser4-cli/src\main.rs:1252:            eprintln!("No active session — creating a new one.");
cli/browser4-cli/src\daemon.rs:1723:    // processes before creating a new one.

diff --git a/.test-sessions/ac1-listing-query.sql b/.test-sessions/ac1-listing-query.sql
new file mode 100644
index 0000000000000000000000000000000000000000..d651eec929ece8af65e1fdb818fac08d6e7ef4ea
--- /dev/null
+++ b/.test-sessions/ac1-listing-query.sql
@@ -0,0 +1,6 @@
+SELECT
+  DOM_FIRST_TEXT(DOM, 'div.product-title') AS title,
+  DOM_FIRST_TEXT(DOM, 'div.product-price') AS price,
+  DOM_FIRST_HREF(DOM, 'a.product-link') AS link
+FROM
+  DOM_LOAD_AND_SELECT(@url, '.product-card')
diff --git a/.test-sessions/ac2-product-query.sql b/.test-sessions/ac2-product-query.sql
new file mode 100644
index 0000000000000000000000000000000000000000..1fa45c1ec3c6f272620e6313938e1565b2d4239c
--- /dev/null
+++ b/.test-sessions/ac2-product-query.sql
@@ -0,0 +1,6 @@
+SELECT
+  DOM_BASE_URI(DOM) AS url,
+  DOM_FIRST_TEXT(DOM, '#productTitle') AS title,
+  DOM_FIRST_TEXT(DOM, '#product-price') AS price
+FROM
+  DOM_LOAD_AND_SELECT(@url, 'body')
diff --git a/.test-sessions/ac6-few-urls.ps1 b/.test-sessions/ac6-few-urls.ps1
new file mode 100644
index 0000000000000000000000000000000000000000..07a5a6936b1fab6f1574eb1c9627014aa6157e04
--- /dev/null
+++ b/.test-sessions/ac6-few-urls.ps1
@@ -0,0 +1,18 @@
+#!/usr/bin/env pwsh
+$ErrorActionPreference = 'Continue'
+
+$urls = @(
+    "http://localhost:18080/ec/dp/B0E000001",
+    "http://localhost:18080/ec/dp/B0E000004",
+    "http://localhost:18080/ec/dp/B0E000005"
+)
+
+$urls | ForEach-Object {
+    $u = $_
+    Write-Host "=== Processing: $u ==="
+    browser4-cli goto $u
+    browser4-cli htmlsnapshot
+    $title = browser4-cli htmlsnapshot get text "#productTitle"
+    Write-Host "Title: $title"
+    Start-Sleep -Seconds 2
+}
diff --git a/.test-sessions/run-loop.ps1 b/.test-sessions/run-loop.ps1
new file mode 100644
index 0000000000000000000000000000000000000000..a4da61b00ecf6843a91fe250ac4cb5afe8d6a8ec
--- /dev/null
+++ b/.test-sessions/run-loop.ps1
@@ -0,0 +1,4 @@
+#!/usr/bin/env pwsh
+$ErrorActionPreference = 'Continue'
+$out = Join-Path $PSScriptRoot 'loop-run.log'
+browser4-cli loop --name mock-price-watch --count 2 -i 10 -- -s price-watch eval "document.querySelector('#product-price').textContent.trim()" *> $out

exec
"C:\\Program Files\\PowerShell\\7\\pwsh.exe" -Command '$lines = Get-Content "cli'"\\browser4-cli\\src\\main.rs\"; "'$lines[1220..1270] -join "`n"' in D:\workspace\Browser4\Browser4-4.13
 succeeded in 447ms:
        // the correct command for targeting the attached session.
        if let Some(name) = session_name {
            let default_state = read_state(None, None);
            if default_state.session_id.as_deref() == Some(name)
                && default_state.is_attached
            {
                let attach_cmd = if default_state.attach_type.as_deref() == Some("extension") {
                    "attach --extension"
                } else {
                    "attach --cdp"
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

diff --git a/.test-sessions/ac1-listing-query.sql b/.test-sessions/ac1-listing-query.sql
new file mode 100644
index 0000000000000000000000000000000000000000..d651eec929ece8af65e1fdb818fac08d6e7ef4ea
--- /dev/null
+++ b/.test-sessions/ac1-listing-query.sql
@@ -0,0 +1,6 @@
+SELECT
+  DOM_FIRST_TEXT(DOM, 'div.product-title') AS title,
+  DOM_FIRST_TEXT(DOM, 'div.product-price') AS price,
+  DOM_FIRST_HREF(DOM, 'a.product-link') AS link
+FROM
+  DOM_LOAD_AND_SELECT(@url, '.product-card')
diff --git a/.test-sessions/ac2-product-query.sql b/.test-sessions/ac2-product-query.sql
new file mode 100644
index 0000000000000000000000000000000000000000..1fa45c1ec3c6f272620e6313938e1565b2d4239c
--- /dev/null
+++ b/.test-sessions/ac2-product-query.sql
@@ -0,0 +1,6 @@
+SELECT
+  DOM_BASE_URI(DOM) AS url,
+  DOM_FIRST_TEXT(DOM, '#productTitle') AS title,
+  DOM_FIRST_TEXT(DOM, '#product-price') AS price
+FROM
+  DOM_LOAD_AND_SELECT(@url, 'body')
diff --git a/.test-sessions/ac6-few-urls.ps1 b/.test-sessions/ac6-few-urls.ps1
new file mode 100644
index 0000000000000000000000000000000000000000..07a5a6936b1fab6f1574eb1c9627014aa6157e04
--- /dev/null
+++ b/.test-sessions/ac6-few-urls.ps1
@@ -0,0 +1,18 @@
+#!/usr/bin/env pwsh
+$ErrorActionPreference = 'Continue'
+
+$urls = @(
+    "http://localhost:18080/ec/dp/B0E000001",
+    "http://localhost:18080/ec/dp/B0E000004",
+    "http://localhost:18080/ec/dp/B0E000005"
+)
+
+$urls | ForEach-Object {
+    $u = $_
+    Write-Host "=== Processing: $u ==="
+    browser4-cli goto $u
+    browser4-cli htmlsnapshot
+    $title = browser4-cli htmlsnapshot get text "#productTitle"
+    Write-Host "Title: $title"
+    Start-Sleep -Seconds 2
+}
diff --git a/.test-sessions/run-loop.ps1 b/.test-sessions/run-loop.ps1
new file mode 100644
index 0000000000000000000000000000000000000000..a4da61b00ecf6843a91fe250ac4cb5afe8d6a8ec
--- /dev/null
+++ b/.test-sessions/run-loop.ps1
@@ -0,0 +1,4 @@
+#!/usr/bin/env pwsh
+$ErrorActionPreference = 'Continue'
+$out = Join-Path $PSScriptRoot 'loop-run.log'
+browser4-cli loop --name mock-price-watch --count 2 -i 10 -- -s price-watch eval "document.querySelector('#product-price').textContent.trim()" *> $out

exec
"C:\\Program Files\\PowerShell\\7\\pwsh.exe" -Command '$lines = Get-Content "cli'"\\browser4-cli\\src\\main.rs\"; "'$lines[1170..1210] -join "`n"; "---"; rg -n "ensure_or_create_session|ensure_session|async fn .*session" cli/browser4-cli/src/main.rs | Select-Object -First 20' in D:\workspace\Browser4\Browser4-4.13
 succeeded in 493ms:
                );
                cli_println!(
                    "Use 'attach --extension' or 'attach --cdp' to reconnect the attached browser later."
                );
                invalidate_session(&state, base_url, session_name);
                let capabilities = build_open_session_capabilities(tool_params);
                let new_id =
                    create_session(client, base_url, &state, session_name, Some(capabilities))
                        .await?;
                cli_println!(
                    "{}",
                    format_session_opened_message(session_name, &new_id)
                );
                new_id
            } else {
                let attach_cmd = if state.attach_type.as_deref() == Some("extension") {
                    "attach --extension"
                } else {
                    "attach --cdp"
                };
                let mut msg = format!(
                    "Attached session {} is no longer healthy. \
                     The browser or extension may have disconnected.\n\
                     Re-run `{}` to reconnect, or \
                     `close` / `close-all` to clear this session state.",
                    attached_id, attach_cmd
                );
                // Add chrome:// page hint for extension sessions
                if state.attach_type.as_deref() == Some("extension") {
                    msg.push_str(
                        "\n\nNote: Navigating to chrome:// internal pages \
                         (chrome://version, chrome://settings, etc.) may \
                         cause the extension connection to drop. After such \
                         navigation, re-attach with `attach --extension`.",
                    );
                }
                return Err(msg);
            }
        } else {
            return Err(
                "Attached session state has no session ID — re-run `attach` to create one."
---
763:async fn create_session(
955:async fn with_session<F, Fut>(
976:async fn with_session_paginated<F, Fut>(
1011:async fn post_command_snapshot(client: &Client, base_url: &str, session_id: &str) {
1068:async fn get_or_create_navigation_session(
3029:async fn handle_session_default(tool_params: &Value) -> Result<(), String> {
3195:async fn call_session_tool(
3216:async fn current_session_storage_state(
3232:async fn current_session_url(
3349:async fn close_all_sessions_across_servers(client: &Client, base_url: &str) -> CloseAllSummary {
3695:async fn find_reusable_persisted_session_id(
3819:async fn session_is_active_in_state(

diff --git a/.test-sessions/ac1-listing-query.sql b/.test-sessions/ac1-listing-query.sql
new file mode 100644
index 0000000000000000000000000000000000000000..d651eec929ece8af65e1fdb818fac08d6e7ef4ea
--- /dev/null
+++ b/.test-sessions/ac1-listing-query.sql
@@ -0,0 +1,6 @@
+SELECT
+  DOM_FIRST_TEXT(DOM, 'div.product-title') AS title,
+  DOM_FIRST_TEXT(DOM, 'div.product-price') AS price,
+  DOM_FIRST_HREF(DOM, 'a.product-link') AS link
+FROM
+  DOM_LOAD_AND_SELECT(@url, '.product-card')
diff --git a/.test-sessions/ac2-product-query.sql b/.test-sessions/ac2-product-query.sql
new file mode 100644
index 0000000000000000000000000000000000000000..1fa45c1ec3c6f272620e6313938e1565b2d4239c
--- /dev/null
+++ b/.test-sessions/ac2-product-query.sql
@@ -0,0 +1,6 @@
+SELECT
+  DOM_BASE_URI(DOM) AS url,
+  DOM_FIRST_TEXT(DOM, '#productTitle') AS title,
+  DOM_FIRST_TEXT(DOM, '#product-price') AS price
+FROM
+  DOM_LOAD_AND_SELECT(@url, 'body')
diff --git a/.test-sessions/ac6-few-urls.ps1 b/.test-sessions/ac6-few-urls.ps1
new file mode 100644
index 0000000000000000000000000000000000000000..07a5a6936b1fab6f1574eb1c9627014aa6157e04
--- /dev/null
+++ b/.test-sessions/ac6-few-urls.ps1
@@ -0,0 +1,18 @@
+#!/usr/bin/env pwsh
+$ErrorActionPreference = 'Continue'
+
+$urls = @(
+    "http://localhost:18080/ec/dp/B0E000001",
+    "http://localhost:18080/ec/dp/B0E000004",
+    "http://localhost:18080/ec/dp/B0E000005"
+)
+
+$urls | ForEach-Object {
+    $u = $_
+    Write-Host "=== Processing: $u ==="
+    browser4-cli goto $u
+    browser4-cli htmlsnapshot
+    $title = browser4-cli htmlsnapshot get text "#productTitle"
+    Write-Host "Title: $title"
+    Start-Sleep -Seconds 2
+}
diff --git a/.test-sessions/run-loop.ps1 b/.test-sessions/run-loop.ps1
new file mode 100644
index 0000000000000000000000000000000000000000..a4da61b00ecf6843a91fe250ac4cb5afe8d6a8ec
--- /dev/null
+++ b/.test-sessions/run-loop.ps1
@@ -0,0 +1,4 @@
+#!/usr/bin/env pwsh
+$ErrorActionPreference = 'Continue'
+$out = Join-Path $PSScriptRoot 'loop-run.log'
+browser4-cli loop --name mock-price-watch --count 2 -i 10 -- -s price-watch eval "document.querySelector('#product-price').textContent.trim()" *> $out

exec
"C:\\Program Files\\PowerShell\\7\\pwsh.exe" -Command '$lines = Get-Content "cli'"\\browser4-cli\\src\\main.rs\"; "'$lines[1068..1140] -join "`n"' in D:\workspace\Browser4\Browser4-4.13
 succeeded in 420ms:
    client: &Client,
    base_url: &str,
    tool_params: &Value,
    session_name: Option<&str>,
    force_new_session: bool,
) -> Result<(CliState, String, bool), String> {
    let mut state = read_state(None, session_name);
    state.session_name = session_name.map(|s| s.to_string());

    let reusable_session_id =
        find_reusable_persisted_session_id(client, base_url, &state, session_name).await?;
    let mut reused_existing_session = reusable_session_id.is_some();
    let session_id = if let Some(existing_id) = reusable_session_id {
        // When force_new_session is set (e.g., `open` command), if the
        // existing session is an attached session (CDP or extension),
        // don't reuse it — create a new regular Browser4 session instead.
        // Save the attached session's state under the session ID as a
        // named session so it can still be targeted with -s <sessionId>.
        if force_new_session && state.is_attached {
            // Persist the attached session under its own session ID
            // so it can be listed and targeted with -s <sessionId>.
            let mut attached_state = state.clone();
            attached_state.session_name = Some(existing_id.clone());
            write_state(&attached_state, None, Some(&existing_id))
                .map_err(|e| e.to_string())?;

            cli_println!(
                "Session '{}' is an attached/extension session. Creating a new Browser4 session.",
                existing_id
            );
            cli_println!(
                "Use '-s {}' to target the attached session.",
                existing_id
            );

            let capabilities = build_open_session_capabilities(tool_params);
            let new_id =
                create_session(client, base_url, &state, session_name, Some(capabilities)).await?;
            cli_println!(
                "{}",
                format_session_opened_message(session_name, &new_id)
            );
            reused_existing_session = false;
            new_id
        } else if tool_params.get("fresh").and_then(Value::as_bool) == Some(true) {
            // `--fresh` explicitly overrides session reuse: close the
            // existing session so its tabs, cookies, and location state
            // don't leak into the new one.  (Attached sessions are handled
            // by the branch above — closing them would kill the user's
            // real browser window.)
            cli_println!(
                "Closing existing session {} — starting fresh (--fresh).",
                existing_id
            );
            let _ = call_tool(
                client,
                base_url,
                "close_session",
                json!({ "sessionId": existing_id }),
            )
            .await;
            invalidate_session(&state, base_url, session_name);
            let capabilities = build_open_session_capabilities(tool_params);
            let new_id =
                create_session(client, base_url, &state, session_name, Some(capabilities)).await?;
            cli_println!(
                "{}",
                format_session_opened_message(session_name, &new_id)
            );
            reused_existing_session = false;
            new_id
        } else {
            existing_id

diff --git a/.test-sessions/ac1-listing-query.sql b/.test-sessions/ac1-listing-query.sql
new file mode 100644
index 0000000000000000000000000000000000000000..d651eec929ece8af65e1fdb818fac08d6e7ef4ea
--- /dev/null
+++ b/.test-sessions/ac1-listing-query.sql
@@ -0,0 +1,6 @@
+SELECT
+  DOM_FIRST_TEXT(DOM, 'div.product-title') AS title,
+  DOM_FIRST_TEXT(DOM, 'div.product-price') AS price,
+  DOM_FIRST_HREF(DOM, 'a.product-link') AS link
+FROM
+  DOM_LOAD_AND_SELECT(@url, '.product-card')
diff --git a/.test-sessions/ac2-product-query.sql b/.test-sessions/ac2-product-query.sql
new file mode 100644
index 0000000000000000000000000000000000000000..1fa45c1ec3c6f272620e6313938e1565b2d4239c
--- /dev/null
+++ b/.test-sessions/ac2-product-query.sql
@@ -0,0 +1,6 @@
+SELECT
+  DOM_BASE_URI(DOM) AS url,
+  DOM_FIRST_TEXT(DOM, '#productTitle') AS title,
+  DOM_FIRST_TEXT(DOM, '#product-price') AS price
+FROM
+  DOM_LOAD_AND_SELECT(@url, 'body')
diff --git a/.test-sessions/ac6-few-urls.ps1 b/.test-sessions/ac6-few-urls.ps1
new file mode 100644
index 0000000000000000000000000000000000000000..07a5a6936b1fab6f1574eb1c9627014aa6157e04
--- /dev/null
+++ b/.test-sessions/ac6-few-urls.ps1
@@ -0,0 +1,18 @@
+#!/usr/bin/env pwsh
+$ErrorActionPreference = 'Continue'
+
+$urls = @(
+    "http://localhost:18080/ec/dp/B0E000001",
+    "http://localhost:18080/ec/dp/B0E000004",
+    "http://localhost:18080/ec/dp/B0E000005"
+)
+
+$urls | ForEach-Object {
+    $u = $_
+    Write-Host "=== Processing: $u ==="
+    browser4-cli goto $u
+    browser4-cli htmlsnapshot
+    $title = browser4-cli htmlsnapshot get text "#productTitle"
+    Write-Host "Title: $title"
+    Start-Sleep -Seconds 2
+}
diff --git a/.test-sessions/run-loop.ps1 b/.test-sessions/run-loop.ps1
new file mode 100644
index 0000000000000000000000000000000000000000..a4da61b00ecf6843a91fe250ac4cb5afe8d6a8ec
--- /dev/null
+++ b/.test-sessions/run-loop.ps1
@@ -0,0 +1,4 @@
+#!/usr/bin/env pwsh
+$ErrorActionPreference = 'Continue'
+$out = Join-Path $PSScriptRoot 'loop-run.log'
+browser4-cli loop --name mock-price-watch --count 2 -i 10 -- -s price-watch eval "document.querySelector('#product-price').textContent.trim()" *> $out

codex
Let me capture stdout and stderr separately to pinpoint when that message is emitted.
exec
"C:\\Program Files\\PowerShell\\7\\pwsh.exe" -Command '$out1 = ".test-sessions'"\\goto-stdout.log\"; "'$err1 = ".test-sessions'"\\goto-stderr.log\"; browser4-cli -s ac1-check goto \"http://localhost:18080/ec/dp/B0E000003\" 1> "'$out1 2> $err1; "=== STDOUT ==="; Get-Content $out1; "=== STDERR ==="; Get-Content $err1' in D:\workspace\Browser4\Browser4-4.13
 succeeded in 4988ms:
=== STDOUT ===
Using existing session ac1-check (43e4f392-970e-4182-b8ba-63ba272e2ea4) (current page: http://localhost:18080/ec/dp/B0E000002).
Navigated to http://localhost:18080/ec/dp/B0E000003
Page loaded. Use `wait --load networkidle` if content appears incomplete (e.g. async-rendered SPAs).
### Page
- Page URL: http://localhost:18080/ec/dp/B0E000003
- Page Title: Product: Portable Bluetooth Speaker
### Snapshot
[Snapshot](D:\workspace\Browser4\Browser4-4.13\.browser4-cli\snapshot\snapshot-2026-08-14T07-19-25-542Z.yml)
=== STDERR ===
💡 Tip: Try `htmlsnapshot get text "h1"` to extract the page heading, or `htmlsnapshot inspect` to discover CSS selectors

diff --git a/.test-sessions/ac1-listing-query.sql b/.test-sessions/ac1-listing-query.sql
new file mode 100644
index 0000000000000000000000000000000000000000..d651eec929ece8af65e1fdb818fac08d6e7ef4ea
--- /dev/null
+++ b/.test-sessions/ac1-listing-query.sql
@@ -0,0 +1,6 @@
+SELECT
+  DOM_FIRST_TEXT(DOM, 'div.product-title') AS title,
+  DOM_FIRST_TEXT(DOM, 'div.product-price') AS price,
+  DOM_FIRST_HREF(DOM, 'a.product-link') AS link
+FROM
+  DOM_LOAD_AND_SELECT(@url, '.product-card')
diff --git a/.test-sessions/ac2-product-query.sql b/.test-sessions/ac2-product-query.sql
new file mode 100644
index 0000000000000000000000000000000000000000..1fa45c1ec3c6f272620e6313938e1565b2d4239c
--- /dev/null
+++ b/.test-sessions/ac2-product-query.sql
@@ -0,0 +1,6 @@
+SELECT
+  DOM_BASE_URI(DOM) AS url,
+  DOM_FIRST_TEXT(DOM, '#productTitle') AS title,
+  DOM_FIRST_TEXT(DOM, '#product-price') AS price
+FROM
+  DOM_LOAD_AND_SELECT(@url, 'body')
diff --git a/.test-sessions/ac6-few-urls.ps1 b/.test-sessions/ac6-few-urls.ps1
new file mode 100644
index 0000000000000000000000000000000000000000..07a5a6936b1fab6f1574eb1c9627014aa6157e04
--- /dev/null
+++ b/.test-sessions/ac6-few-urls.ps1
@@ -0,0 +1,18 @@
+#!/usr/bin/env pwsh
+$ErrorActionPreference = 'Continue'
+
+$urls = @(
+    "http://localhost:18080/ec/dp/B0E000001",
+    "http://localhost:18080/ec/dp/B0E000004",
+    "http://localhost:18080/ec/dp/B0E000005"
+)
+
+$urls | ForEach-Object {
+    $u = $_
+    Write-Host "=== Processing: $u ==="
+    browser4-cli goto $u
+    browser4-cli htmlsnapshot
+    $title = browser4-cli htmlsnapshot get text "#productTitle"
+    Write-Host "Title: $title"
+    Start-Sleep -Seconds 2
+}
diff --git a/.test-sessions/run-loop.ps1 b/.test-sessions/run-loop.ps1
new file mode 100644
index 0000000000000000000000000000000000000000..a4da61b00ecf6843a91fe250ac4cb5afe8d6a8ec
--- /dev/null
+++ b/.test-sessions/run-loop.ps1
@@ -0,0 +1,4 @@
+#!/usr/bin/env pwsh
+$ErrorActionPreference = 'Continue'
+$out = Join-Path $PSScriptRoot 'loop-run.log'
+browser4-cli loop --name mock-price-watch --count 2 -i 10 -- -s price-watch eval "document.querySelector('#product-price').textContent.trim()" *> $out

exec
"C:\\Program Files\\PowerShell\\7\\pwsh.exe" -Command '$out1 = ".test-sessions'"\\goto-stdout2.log\"; "'$err1 = ".test-sessions'"\\goto-stderr2.log\"; 1..3 | ForEach-Object { browser4-cli -s ac1-check goto \"http://localhost:18080/ec/dp/B0E00000"'$($_)" 1>> $out1 2>> $err1 }; "=== STDERR ==="; Get-Content $err1; "=== STDERR COUNT of '"'No active session' ===\"; (Select-String -Path "'$err1 -Pattern "No active session" -AllMatches).Count' in D:\workspace\Browser4\Browser4-4.13
 succeeded in 13821ms:
=== STDERR ===
💡 Tip: Try `htmlsnapshot get text "h1"` to extract the page heading, or `htmlsnapshot inspect` to discover CSS selectors
💡 Tip: Try `htmlsnapshot get text "h1"` to extract the page heading, or `htmlsnapshot inspect` to discover CSS selectors
💡 Tip: Try `htmlsnapshot get text "h1"` to extract the page heading, or `htmlsnapshot inspect` to discover CSS selectors
=== STDERR COUNT of 'No active session' ===
0

diff --git a/.test-sessions/ac1-listing-query.sql b/.test-sessions/ac1-listing-query.sql
new file mode 100644
index 0000000000000000000000000000000000000000..d651eec929ece8af65e1fdb818fac08d6e7ef4ea
--- /dev/null
+++ b/.test-sessions/ac1-listing-query.sql
@@ -0,0 +1,6 @@
+SELECT
+  DOM_FIRST_TEXT(DOM, 'div.product-title') AS title,
+  DOM_FIRST_TEXT(DOM, 'div.product-price') AS price,
+  DOM_FIRST_HREF(DOM, 'a.product-link') AS link
+FROM
+  DOM_LOAD_AND_SELECT(@url, '.product-card')
diff --git a/.test-sessions/ac2-product-query.sql b/.test-sessions/ac2-product-query.sql
new file mode 100644
index 0000000000000000000000000000000000000000..1fa45c1ec3c6f272620e6313938e1565b2d4239c
--- /dev/null
+++ b/.test-sessions/ac2-product-query.sql
@@ -0,0 +1,6 @@
+SELECT
+  DOM_BASE_URI(DOM) AS url,
+  DOM_FIRST_TEXT(DOM, '#productTitle') AS title,
+  DOM_FIRST_TEXT(DOM, '#product-price') AS price
+FROM
+  DOM_LOAD_AND_SELECT(@url, 'body')
diff --git a/.test-sessions/ac6-few-urls.ps1 b/.test-sessions/ac6-few-urls.ps1
new file mode 100644
index 0000000000000000000000000000000000000000..07a5a6936b1fab6f1574eb1c9627014aa6157e04
--- /dev/null
+++ b/.test-sessions/ac6-few-urls.ps1
@@ -0,0 +1,18 @@
+#!/usr/bin/env pwsh
+$ErrorActionPreference = 'Continue'
+
+$urls = @(
+    "http://localhost:18080/ec/dp/B0E000001",
+    "http://localhost:18080/ec/dp/B0E000004",
+    "http://localhost:18080/ec/dp/B0E000005"
+)
+
+$urls | ForEach-Object {
+    $u = $_
+    Write-Host "=== Processing: $u ==="
+    browser4-cli goto $u
+    browser4-cli htmlsnapshot
+    $title = browser4-cli htmlsnapshot get text "#productTitle"
+    Write-Host "Title: $title"
+    Start-Sleep -Seconds 2
+}
diff --git a/.test-sessions/run-loop.ps1 b/.test-sessions/run-loop.ps1
new file mode 100644
index 0000000000000000000000000000000000000000..a4da61b00ecf6843a91fe250ac4cb5afe8d6a8ec
--- /dev/null
+++ b/.test-sessions/run-loop.ps1
@@ -0,0 +1,4 @@
+#!/usr/bin/env pwsh
+$ErrorActionPreference = 'Continue'
+$out = Join-Path $PSScriptRoot 'loop-run.log'
+browser4-cli loop --name mock-price-watch --count 2 -i 10 -- -s price-watch eval "document.querySelector('#product-price').textContent.trim()" *> $out

codex
The message is intermittent. Let me try a fresh named session with back-to-back gotos to reproduce it again.
exec
"C:\\Program Files\\PowerShell\\7\\pwsh.exe" -Command 'browser4-cli -s ac2-check goto "http://localhost:18080/ec/dp/B0E000001"; browser4-cli -s ac2-check goto "http://localhost:18080/ec/dp/B0E000002"; browser4-cli -s ac2-check goto "http://localhost:18080/ec/dp/B0E000003"' in D:\workspace\Browser4\Browser4-4.13
 succeeded in 16256ms:
Session opened: ac2-check (26d84efe-2f31-4d1b-b612-7d32c64dcf94)
Navigated to http://localhost:18080/ec/dp/B0E000001
Page loaded. Use `wait --load networkidle` if content appears incomplete (e.g. async-rendered SPAs).
### Page
- Page URL: http://localhost:18080/ec/dp/B0E000001
- Page Title: Product: 4K OLED TV 55
### Snapshot
[Snapshot](D:\workspace\Browser4\Browser4-4.13\.browser4-cli\snapshot\snapshot-2026-08-14T07-19-55-019Z.yml)
Using existing session ac2-check (26d84efe-2f31-4d1b-b612-7d32c64dcf94) (current page: http://localhost:18080/ec/dp/B0E000001).
Navigated to http://localhost:18080/ec/dp/B0E000002
Page loaded. Use `wait --load networkidle` if content appears incomplete (e.g. async-rendered SPAs).
### Page
- Page URL: http://localhost:18080/ec/dp/B0E000002
- Page Title: Product: Wireless Noise-Cancelling Headphones
### Snapshot
[Snapshot](D:\workspace\Browser4\Browser4-4.13\.browser4-cli\snapshot\snapshot-2026-08-14T07-19-59-786Z.yml)
Using existing session ac2-check (26d84efe-2f31-4d1b-b612-7d32c64dcf94) (current page: http://localhost:18080/ec/dp/B0E000002).
Navigated to http://localhost:18080/ec/dp/B0E000003
Page loaded. Use `wait --load networkidle` if content appears incomplete (e.g. async-rendered SPAs).
### Page
- Page URL: http://localhost:18080/ec/dp/B0E000003
- Page Title: Product: Portable Bluetooth Speaker
### Snapshot
[Snapshot](D:\workspace\Browser4\Browser4-4.13\.browser4-cli\snapshot\snapshot-2026-08-14T07-20-04-066Z.yml)
No active session — creating a new one.
💡 Tip: Try `htmlsnapshot get text "h1"` to extract the page heading, or `htmlsnapshot inspect` to discover CSS selectors
💡 Tip: Try `htmlsnapshot get text "h1"` to extract the page heading, or `htmlsnapshot inspect` to discover CSS selectors
💡 Tip: Try `htmlsnapshot get text "h1"` to extract the page heading, or `htmlsnapshot inspect` to discover CSS selectors

diff --git a/.test-sessions/ac1-listing-query.sql b/.test-sessions/ac1-listing-query.sql
new file mode 100644
index 0000000000000000000000000000000000000000..d651eec929ece8af65e1fdb818fac08d6e7ef4ea
--- /dev/null
+++ b/.test-sessions/ac1-listing-query.sql
@@ -0,0 +1,6 @@
+SELECT
+  DOM_FIRST_TEXT(DOM, 'div.product-title') AS title,
+  DOM_FIRST_TEXT(DOM, 'div.product-price') AS price,
+  DOM_FIRST_HREF(DOM, 'a.product-link') AS link
+FROM
+  DOM_LOAD_AND_SELECT(@url, '.product-card')
diff --git a/.test-sessions/ac2-product-query.sql b/.test-sessions/ac2-product-query.sql
new file mode 100644
index 0000000000000000000000000000000000000000..1fa45c1ec3c6f272620e6313938e1565b2d4239c
--- /dev/null
+++ b/.test-sessions/ac2-product-query.sql
@@ -0,0 +1,6 @@
+SELECT
+  DOM_BASE_URI(DOM) AS url,
+  DOM_FIRST_TEXT(DOM, '#productTitle') AS title,
+  DOM_FIRST_TEXT(DOM, '#product-price') AS price
+FROM
+  DOM_LOAD_AND_SELECT(@url, 'body')
diff --git a/.test-sessions/ac6-few-urls.ps1 b/.test-sessions/ac6-few-urls.ps1
new file mode 100644
index 0000000000000000000000000000000000000000..07a5a6936b1fab6f1574eb1c9627014aa6157e04
--- /dev/null
+++ b/.test-sessions/ac6-few-urls.ps1
@@ -0,0 +1,18 @@
+#!/usr/bin/env pwsh
+$ErrorActionPreference = 'Continue'
+
+$urls = @(
+    "http://localhost:18080/ec/dp/B0E000001",
+    "http://localhost:18080/ec/dp/B0E000004",
+    "http://localhost:18080/ec/dp/B0E000005"
+)
+
+$urls | ForEach-Object {
+    $u = $_
+    Write-Host "=== Processing: $u ==="
+    browser4-cli goto $u
+    browser4-cli htmlsnapshot
+    $title = browser4-cli htmlsnapshot get text "#productTitle"
+    Write-Host "Title: $title"
+    Start-Sleep -Seconds 2
+}
diff --git a/.test-sessions/run-loop.ps1 b/.test-sessions/run-loop.ps1
new file mode 100644
index 0000000000000000000000000000000000000000..a4da61b00ecf6843a91fe250ac4cb5afe8d6a8ec
--- /dev/null
+++ b/.test-sessions/run-loop.ps1
@@ -0,0 +1,4 @@
+#!/usr/bin/env pwsh
+$ErrorActionPreference = 'Continue'
+$out = Join-Path $PSScriptRoot 'loop-run.log'
+browser4-cli loop --name mock-price-watch --count 2 -i 10 -- -s price-watch eval "document.querySelector('#product-price').textContent.trim()" *> $out

exec
"C:\\Program Files\\PowerShell\\7\\pwsh.exe" -Command "rg -n \"fn json_active\" cli/browser4-cli/src/main.rs; rg -n \"get_or_create_navigation_session\\(\" cli/browser4-cli/src/main.rs" in D:\workspace\Browser4\Browser4-4.13
 succeeded in 337ms:
132:fn json_active() -> bool {
1068:async fn get_or_create_navigation_session(
1842:        get_or_create_navigation_session(client, base_url, tool_params, session_name, true).await?;
1924:        get_or_create_navigation_session(client, base_url, tool_params, session_name, false).await?;
3965:        get_or_create_navigation_session(client, base_url, &json!({}), session_name, false).await?;

diff --git a/.test-sessions/ac1-listing-query.sql b/.test-sessions/ac1-listing-query.sql
new file mode 100644
index 0000000000000000000000000000000000000000..d651eec929ece8af65e1fdb818fac08d6e7ef4ea
--- /dev/null
+++ b/.test-sessions/ac1-listing-query.sql
@@ -0,0 +1,6 @@
+SELECT
+  DOM_FIRST_TEXT(DOM, 'div.product-title') AS title,
+  DOM_FIRST_TEXT(DOM, 'div.product-price') AS price,
+  DOM_FIRST_HREF(DOM, 'a.product-link') AS link
+FROM
+  DOM_LOAD_AND_SELECT(@url, '.product-card')
diff --git a/.test-sessions/ac2-product-query.sql b/.test-sessions/ac2-product-query.sql
new file mode 100644
index 0000000000000000000000000000000000000000..1fa45c1ec3c6f272620e6313938e1565b2d4239c
--- /dev/null
+++ b/.test-sessions/ac2-product-query.sql
@@ -0,0 +1,6 @@
+SELECT
+  DOM_BASE_URI(DOM) AS url,
+  DOM_FIRST_TEXT(DOM, '#productTitle') AS title,
+  DOM_FIRST_TEXT(DOM, '#product-price') AS price
+FROM
+  DOM_LOAD_AND_SELECT(@url, 'body')
diff --git a/.test-sessions/ac6-few-urls.ps1 b/.test-sessions/ac6-few-urls.ps1
new file mode 100644
index 0000000000000000000000000000000000000000..07a5a6936b1fab6f1574eb1c9627014aa6157e04
--- /dev/null
+++ b/.test-sessions/ac6-few-urls.ps1
@@ -0,0 +1,18 @@
+#!/usr/bin/env pwsh
+$ErrorActionPreference = 'Continue'
+
+$urls = @(
+    "http://localhost:18080/ec/dp/B0E000001",
+    "http://localhost:18080/ec/dp/B0E000004",
+    "http://localhost:18080/ec/dp/B0E000005"
+)
+
+$urls | ForEach-Object {
+    $u = $_
+    Write-Host "=== Processing: $u ==="
+    browser4-cli goto $u
+    browser4-cli htmlsnapshot
+    $title = browser4-cli htmlsnapshot get text "#productTitle"
+    Write-Host "Title: $title"
+    Start-Sleep -Seconds 2
+}
diff --git a/.test-sessions/run-loop.ps1 b/.test-sessions/run-loop.ps1
new file mode 100644
index 0000000000000000000000000000000000000000..a4da61b00ecf6843a91fe250ac4cb5afe8d6a8ec
--- /dev/null
+++ b/.test-sessions/run-loop.ps1
@@ -0,0 +1,4 @@
+#!/usr/bin/env pwsh
+$ErrorActionPreference = 'Continue'
+$out = Join-Path $PSScriptRoot 'loop-run.log'
+browser4-cli loop --name mock-price-watch --count 2 -i 10 -- -s price-watch eval "document.querySelector('#product-price').textContent.trim()" *> $out

exec
"C:\\Program Files\\PowerShell\\7\\pwsh.exe" -Command '$lines = Get-Content "cli'"\\browser4-cli\\src\\main.rs\"; "'$lines[125..175] -join "`n"; "--- around 1830-1930 ---"; $lines[1825..1935] -join "`n"' in D:\workspace\Browser4\Browser4-4.13
 succeeded in 426ms:
///
/// Uses the persistent `JSON_MODE` flag so the check remains reliable
/// even after `json_finish()` has taken the accumulator.  This is
/// important for suppressing tips and human-readable output that are
/// emitted after the JSON envelope.
#[allow(dead_code)]
fn json_active() -> bool {
    JSON_MODE.with(|cell| *cell.borrow())
}

/// Take the accumulated JSON fields and tear down the accumulator.
fn json_finish() -> Option<serde_json::Map<String, serde_json::Value>> {
    JSON_OUTPUT.with(|cell| cell.borrow_mut().take())
}

// ---------------------------------------------------------------------------
// Quiet output support (-q / --quiet global flag)
// ---------------------------------------------------------------------------

thread_local! {
    /// When `-q` / `--quiet` is active, normal output is suppressed.
    /// Errors still go to stderr.
    static QUIET: RefCell<bool> = RefCell::new(false);
}

fn quiet_init(quiet: bool) {
    QUIET.with(|cell| *cell.borrow_mut() = quiet);
}

fn quiet_active() -> bool {
    QUIET.with(|cell| *cell.borrow())
}

// ---------------------------------------------------------------------------
// Show-tip support (--show-tip / -tip global flag)
// ---------------------------------------------------------------------------

thread_local! {
    /// When `--show-tip` / `-tip` is active, tips are shown on stderr after
    /// each successful command.  Tips are suppressed by default.
    static SHOW_TIP: RefCell<bool> = RefCell::new(false);
}

fn show_tip_init(show_tip: bool) {
    SHOW_TIP.with(|cell| *cell.borrow_mut() = show_tip);
}

fn show_tip_active() -> bool {
    SHOW_TIP.with(|cell| *cell.borrow())
}

--- around 1830-1930 ---
    {
        std::process::Command::new("xdg-open")
            .arg(url)
            .spawn()
            .is_ok()
    }
}

async fn handle_open(
    client: &Client,
    base_url: &str,
    tool_name: &str,
    tool_params: &Value,
    session_name: Option<&str>,
) -> Result<(), String> {
    let (state, session_id, reused_existing_session) =
        get_or_create_navigation_session(client, base_url, tool_params, session_name, true).await?;

    json_field("session_id", json!(&session_id));
    json_field("reused", json!(reused_existing_session));

    let url = tool_params
        .get("url")
        .and_then(|u| u.as_str())
        .unwrap_or("about:blank");
    if should_navigate_after_open(url) {
        let mut params = json!({ "url": url });
        params["sessionId"] = json!(session_id.clone());
        let navigate_result = call_tool(client, base_url, tool_name, params.clone()).await;
        match navigate_result {
            Ok(result) => {
                if reused_existing_session {
                    cli_println!("Session already open: {}", session_id);
                }
                if !result.is_empty() {
                    cli_println!("{}", result);
                }
                post_command_snapshot(client, base_url, &session_id).await;
            }
            Err(err) => {
                if !should_retry_open_after_navigation_error(&err, reused_existing_session) {
                    return Err(format_navigation_failure_message(
                        url,
                        &session_id,
                        &err,
                        false,
                    ));
                }
                // The browser context was not ready yet (BrowserProtocol initialization race).
                // Or the reused saved session no longer has a usable browser tab.
                // Close the failed session, create a fresh one, and retry navigation.
                let _ = call_tool(
                    client,
                    base_url,
                    "close_session",
                    json!({ "sessionId": session_id }),
                )
                .await;
                invalidate_session(&state, base_url, session_name);
                let capabilities = build_open_session_capabilities(tool_params);
                let retry_id =
                    create_session(client, base_url, &state, session_name, Some(capabilities))
                        .await?;
                cli_println!(
                    "{}",
                    format_session_opened_message(session_name, &retry_id)
                );
                params["sessionId"] = json!(retry_id);
                let retry_result = call_tool(client, base_url, tool_name, params)
                    .await
                    .map_err(|err| {
                        format_navigation_failure_message(
                            url,
                            &retry_id,
                            &err,
                            should_retry_open_after_navigation_error(&err, true),
                        )
                    })?;
                if !retry_result.is_empty() {
                    cli_println!("{}", retry_result);
                }
                post_command_snapshot(client, base_url, &retry_id).await;
            }
        }
    } else if reused_existing_session {
        cli_println!("Session already open: {}", session_id);
    }
    Ok(())
}

async fn handle_goto(
    client: &Client,
    base_url: &str,
    tool_name: &str,
    tool_params: &Value,
    session_name: Option<&str>,
) -> Result<(), String> {
    let (state, session_id, reused_existing_session) =
        get_or_create_navigation_session(client, base_url, tool_params, session_name, false).await?;
    let target_url = tool_params
        .get("url")
        .and_then(|value| value.as_str())
        .unwrap_or("<unknown>");

    // Check whether the browser is already at the target URL before
    // navigating, so we can tell the user whether a navigation occurred.
    let was_already_at_url = if reused_existing_session {
        current_session_url(client, base_url, session_name)
            .await
            .map(|current| urls_match_for_display(&current, target_url))
            .unwrap_or(false)

diff --git a/.test-sessions/ac1-listing-query.sql b/.test-sessions/ac1-listing-query.sql
new file mode 100644
index 0000000000000000000000000000000000000000..d651eec929ece8af65e1fdb818fac08d6e7ef4ea
--- /dev/null
+++ b/.test-sessions/ac1-listing-query.sql
@@ -0,0 +1,6 @@
+SELECT
+  DOM_FIRST_TEXT(DOM, 'div.product-title') AS title,
+  DOM_FIRST_TEXT(DOM, 'div.product-price') AS price,
+  DOM_FIRST_HREF(DOM, 'a.product-link') AS link
+FROM
+  DOM_LOAD_AND_SELECT(@url, '.product-card')
diff --git a/.test-sessions/ac2-product-query.sql b/.test-sessions/ac2-product-query.sql
new file mode 100644
index 0000000000000000000000000000000000000000..1fa45c1ec3c6f272620e6313938e1565b2d4239c
--- /dev/null
+++ b/.test-sessions/ac2-product-query.sql
@@ -0,0 +1,6 @@
+SELECT
+  DOM_BASE_URI(DOM) AS url,
+  DOM_FIRST_TEXT(DOM, '#productTitle') AS title,
+  DOM_FIRST_TEXT(DOM, '#product-price') AS price
+FROM
+  DOM_LOAD_AND_SELECT(@url, 'body')
diff --git a/.test-sessions/ac6-few-urls.ps1 b/.test-sessions/ac6-few-urls.ps1
new file mode 100644
index 0000000000000000000000000000000000000000..07a5a6936b1fab6f1574eb1c9627014aa6157e04
--- /dev/null
+++ b/.test-sessions/ac6-few-urls.ps1
@@ -0,0 +1,18 @@
+#!/usr/bin/env pwsh
+$ErrorActionPreference = 'Continue'
+
+$urls = @(
+    "http://localhost:18080/ec/dp/B0E000001",
+    "http://localhost:18080/ec/dp/B0E000004",
+    "http://localhost:18080/ec/dp/B0E000005"
+)
+
+$urls | ForEach-Object {
+    $u = $_
+    Write-Host "=== Processing: $u ==="
+    browser4-cli goto $u
+    browser4-cli htmlsnapshot
+    $title = browser4-cli htmlsnapshot get text "#productTitle"
+    Write-Host "Title: $title"
+    Start-Sleep -Seconds 2
+}
diff --git a/.test-sessions/run-loop.ps1 b/.test-sessions/run-loop.ps1
new file mode 100644
index 0000000000000000000000000000000000000000..a4da61b00ecf6843a91fe250ac4cb5afe8d6a8ec
--- /dev/null
+++ b/.test-sessions/run-loop.ps1
@@ -0,0 +1,4 @@
+#!/usr/bin/env pwsh
+$ErrorActionPreference = 'Continue'
+$out = Join-Path $PSScriptRoot 'loop-run.log'
+browser4-cli loop --name mock-price-watch --count 2 -i 10 -- -s price-watch eval "document.querySelector('#product-price').textContent.trim()" *> $out

exec
"C:\\Program Files\\PowerShell\\7\\pwsh.exe" -Command '$lines = Get-Content "cli'"\\browser4-cli\\src\\main.rs\"; "'$lines[3930..4010] -join "`n"' in D:\workspace\Browser4\Browser4-4.13
 succeeded in 431ms:
        .map_err(|e| format!("Failed to format storage state JSON: {e}"))?;
    save_snapshot(&output_path, &formatted).map_err(|e| e.to_string())?;
    cli_println!("Storage state saved: {}", output_path.display());
    Ok(())
}

async fn handle_state_load(
    client: &Client,
    base_url: &str,
    tool_name: &str,
    tool_params: &Value,
    session_name: Option<&str>,
) -> Result<(), String> {
    let filename = tool_params
        .get("filename")
        .and_then(|value| value.as_str())
        .ok_or_else(|| "state-load requires a storage-state JSON file path".to_string())?;
    let input_path = resolve_storage_state_path(Some(filename))?;
    let state_json = std::fs::read_to_string(&input_path).map_err(|e| {
        format!(
            "Failed to read storage state file {}: {}",
            input_path.display(),
            e
        )
    })?;
    serde_json::from_str::<Value>(&state_json).map_err(|e| {
        format!(
            "Failed to parse storage state file {}: {}",
            input_path.display(),
            e
        )
    })?;

    let (_, session_id, _) =
        get_or_create_navigation_session(client, base_url, &json!({}), session_name, false).await?;
    let result = call_tool(
        client,
        base_url,
        tool_name,
        json!({
            "sessionId": session_id,
            "state": state_json,
        }),
    )
    .await?;
    let summary: StorageStateLoadSummary = serde_json::from_str(&result)
        .map_err(|e| format!("Browser4 returned an invalid storage-state load summary: {e}"))?;
    cli_println!(
        "Storage state loaded: {} (cookies: {}, origins: {}, localStorage entries: {})",
        input_path.display(),
        summary.cookies,
        summary.origins,
        summary.local_storage_entries
    );
    Ok(())
}

async fn handle_cookie_list(
    client: &Client,
    base_url: &str,
    tool_params: &Value,
    session_name: Option<&str>,
) -> Result<(), String> {
    let state = current_session_storage_state(client, base_url, session_name).await?;
    let domain_filter = tool_params.get("domain").and_then(|value| value.as_str());
    let path_filter = tool_params.get("path").and_then(|value| value.as_str());
    let cookies = state["cookies"]
        .as_array()
        .cloned()
        .unwrap_or_default()
        .into_iter()
        .filter(|cookie| {
            domain_filter
                .map(|domain| cookie.get("domain").and_then(|value| value.as_str()) == Some(domain))
                .unwrap_or(true)
                && path_filter
                    .map(|path| cookie.get("path").and_then(|value| value.as_str()) == Some(path))
                    .unwrap_or(true)
        })
        .collect::<Vec<_>>();
    cli_println!(

diff --git a/.test-sessions/ac1-listing-query.sql b/.test-sessions/ac1-listing-query.sql
new file mode 100644
index 0000000000000000000000000000000000000000..d651eec929ece8af65e1fdb818fac08d6e7ef4ea
--- /dev/null
+++ b/.test-sessions/ac1-listing-query.sql
@@ -0,0 +1,6 @@
+SELECT
+  DOM_FIRST_TEXT(DOM, 'div.product-title') AS title,
+  DOM_FIRST_TEXT(DOM, 'div.product-price') AS price,
+  DOM_FIRST_HREF(DOM, 'a.product-link') AS link
+FROM
+  DOM_LOAD_AND_SELECT(@url, '.product-card')
diff --git a/.test-sessions/ac2-product-query.sql b/.test-sessions/ac2-product-query.sql
new file mode 100644
index 0000000000000000000000000000000000000000..1fa45c1ec3c6f272620e6313938e1565b2d4239c
--- /dev/null
+++ b/.test-sessions/ac2-product-query.sql
@@ -0,0 +1,6 @@
+SELECT
+  DOM_BASE_URI(DOM) AS url,
+  DOM_FIRST_TEXT(DOM, '#productTitle') AS title,
+  DOM_FIRST_TEXT(DOM, '#product-price') AS price
+FROM
+  DOM_LOAD_AND_SELECT(@url, 'body')
diff --git a/.test-sessions/ac6-few-urls.ps1 b/.test-sessions/ac6-few-urls.ps1
new file mode 100644
index 0000000000000000000000000000000000000000..07a5a6936b1fab6f1574eb1c9627014aa6157e04
--- /dev/null
+++ b/.test-sessions/ac6-few-urls.ps1
@@ -0,0 +1,18 @@
+#!/usr/bin/env pwsh
+$ErrorActionPreference = 'Continue'
+
+$urls = @(
+    "http://localhost:18080/ec/dp/B0E000001",
+    "http://localhost:18080/ec/dp/B0E000004",
+    "http://localhost:18080/ec/dp/B0E000005"
+)
+
+$urls | ForEach-Object {
+    $u = $_
+    Write-Host "=== Processing: $u ==="
+    browser4-cli goto $u
+    browser4-cli htmlsnapshot
+    $title = browser4-cli htmlsnapshot get text "#productTitle"
+    Write-Host "Title: $title"
+    Start-Sleep -Seconds 2
+}
diff --git a/.test-sessions/run-loop.ps1 b/.test-sessions/run-loop.ps1
new file mode 100644
index 0000000000000000000000000000000000000000..a4da61b00ecf6843a91fe250ac4cb5afe8d6a8ec
--- /dev/null
+++ b/.test-sessions/run-loop.ps1
@@ -0,0 +1,4 @@
+#!/usr/bin/env pwsh
+$ErrorActionPreference = 'Continue'
+$out = Join-Path $PSScriptRoot 'loop-run.log'
+browser4-cli loop --name mock-price-watch --count 2 -i 10 -- -s price-watch eval "document.querySelector('#product-price').textContent.trim()" *> $out

exec
"C:\\Program Files\\PowerShell\\7\\pwsh.exe" -Command '$lines = Get-Content "cli'"\\browser4-cli\\src\\main.rs\"; "'$lines[1935..2070] -join "`n"' in D:\workspace\Browser4\Browser4-4.13
 succeeded in 403ms:
            .unwrap_or(false)
    } else {
        false
    };

    let mut params = tool_params.clone();
    params["sessionId"] = json!(session_id.clone());
    let navigate_result = call_tool(client, base_url, tool_name, params.clone()).await;
    match navigate_result {
        Ok(result) => {
            if !result.is_empty() {
                cli_println!("{}", result);
            }
            if was_already_at_url {
                cli_println!("Already at {} — page unchanged.", target_url);
            } else {
                // Detect redirects: compare the final URL with the requested URL
                let final_url = current_session_url(client, base_url, session_name)
                    .await
                    .unwrap_or_else(|_| target_url.to_string());
                if !urls_match_for_display(&final_url, target_url) {
                    cli_println!(
                        "Navigated to {} (redirected from {})",
                        final_url,
                        target_url
                    );
                } else {
                    cli_println!("Navigated to {}", target_url);
                }
                if !json_active() {
                    cli_println!("Page loaded. Use `wait --load networkidle` if content appears incomplete (e.g. async-rendered SPAs).");
                }
            }
            warn_if_url_has_encoded_quotes(target_url);
            post_command_snapshot(client, base_url, &session_id).await;
        }
        Err(err) => {
            if !should_retry_open_after_navigation_error(&err, reused_existing_session) {
                let should_suggest_refresh = should_retry_open_after_navigation_error(&err, true);
                return Err(format_navigation_failure_message(
                    target_url,
                    &session_id,
                    &err,
                    should_suggest_refresh,
                ));
            }

            let _ = call_tool(
                client,
                base_url,
                "close_session",
                json!({ "sessionId": session_id }),
            )
            .await;
            invalidate_session(&state, base_url, session_name);
            let capabilities = build_open_session_capabilities(tool_params);
            let retry_id =
                create_session(client, base_url, &state, session_name, Some(capabilities)).await?;
            cli_println!(
                "{}",
                format_session_opened_message(session_name, &retry_id)
            );
            params["sessionId"] = json!(retry_id.clone());

            match call_tool(client, base_url, tool_name, params).await {
                Ok(result) => {
                    if !result.is_empty() {
                        cli_println!("{}", result);
                    }
                    // Detect redirects: compare the final URL with the requested URL
                    let final_url = current_session_url(client, base_url, session_name)
                        .await
                        .unwrap_or_else(|_| target_url.to_string());
                    if !urls_match_for_display(&final_url, target_url) {
                        cli_println!(
                            "Navigated to {} (redirected from {})",
                            final_url,
                            target_url
                        );
                    } else {
                        cli_println!("Navigated to {}", target_url);
                    }
                    if !json_active() {
                        cli_println!("Page loaded. Use `wait --load networkidle` if content appears incomplete (e.g. async-rendered SPAs).");
                    }
                    warn_if_url_has_encoded_quotes(target_url);
                    post_command_snapshot(client, base_url, &retry_id).await;
                }
                Err(retry_err) => {
                    let should_suggest_refresh =
                        should_retry_open_after_navigation_error(&retry_err, true);
                    return Err(format_navigation_failure_message(
                        target_url,
                        &retry_id,
                        &retry_err,
                        should_suggest_refresh,
                    ));
                }
            }
        }
    }

    Ok(())
}

/// Compare two URLs for display purposes — treats URLs that differ only by a
/// trailing slash (or its absence) as equivalent.
fn urls_match_for_display(a: &str, b: &str) -> bool {
    a.trim_end_matches('/').trim().eq_ignore_ascii_case(b.trim_end_matches('/').trim())
}

/// Warn the user when a URL contains percent-encoded quotes (%22),
/// which typically indicates malformed HTML href attributes.
fn warn_if_url_has_encoded_quotes(target_url: &str) {
    if target_url.contains("%22") {
        let cleaned = target_url.replace("%22", "");
        eprintln!(
            "⚠️  URL contains encoded quotes (%22): {target_url}\n\
             💡 This usually means the link element has malformed escaped-quote attributes.\n\
             💡 Try the corrected URL instead: goto \"{cleaned}\""
        );
    }
}

fn is_timeout_error_message(error: &str) -> bool {
    let lower = error.to_ascii_lowercase();
    lower.contains("timed out") || lower.contains("deadline has elapsed")
}

fn is_not_focusable_error(error: &str) -> bool {
    let lower = error.to_ascii_lowercase();
    lower.contains("not focusable")
}

fn format_navigation_failure_message(
    target_url: &str,

diff --git a/.test-sessions/ac1-listing-query.sql b/.test-sessions/ac1-listing-query.sql
new file mode 100644
index 0000000000000000000000000000000000000000..d651eec929ece8af65e1fdb818fac08d6e7ef4ea
--- /dev/null
+++ b/.test-sessions/ac1-listing-query.sql
@@ -0,0 +1,6 @@
+SELECT
+  DOM_FIRST_TEXT(DOM, 'div.product-title') AS title,
+  DOM_FIRST_TEXT(DOM, 'div.product-price') AS price,
+  DOM_FIRST_HREF(DOM, 'a.product-link') AS link
+FROM
+  DOM_LOAD_AND_SELECT(@url, '.product-card')
diff --git a/.test-sessions/ac2-product-query.sql b/.test-sessions/ac2-product-query.sql
new file mode 100644
index 0000000000000000000000000000000000000000..1fa45c1ec3c6f272620e6313938e1565b2d4239c
--- /dev/null
+++ b/.test-sessions/ac2-product-query.sql
@@ -0,0 +1,6 @@
+SELECT
+  DOM_BASE_URI(DOM) AS url,
+  DOM_FIRST_TEXT(DOM, '#productTitle') AS title,
+  DOM_FIRST_TEXT(DOM, '#product-price') AS price
+FROM
+  DOM_LOAD_AND_SELECT(@url, 'body')
diff --git a/.test-sessions/ac6-few-urls.ps1 b/.test-sessions/ac6-few-urls.ps1
new file mode 100644
index 0000000000000000000000000000000000000000..07a5a6936b1fab6f1574eb1c9627014aa6157e04
--- /dev/null
+++ b/.test-sessions/ac6-few-urls.ps1
@@ -0,0 +1,18 @@
+#!/usr/bin/env pwsh
+$ErrorActionPreference = 'Continue'
+
+$urls = @(
+    "http://localhost:18080/ec/dp/B0E000001",
+    "http://localhost:18080/ec/dp/B0E000004",
+    "http://localhost:18080/ec/dp/B0E000005"
+)
+
+$urls | ForEach-Object {
+    $u = $_
+    Write-Host "=== Processing: $u ==="
+    browser4-cli goto $u
+    browser4-cli htmlsnapshot
+    $title = browser4-cli htmlsnapshot get text "#productTitle"
+    Write-Host "Title: $title"
+    Start-Sleep -Seconds 2
+}
diff --git a/.test-sessions/run-loop.ps1 b/.test-sessions/run-loop.ps1
new file mode 100644
index 0000000000000000000000000000000000000000..a4da61b00ecf6843a91fe250ac4cb5afe8d6a8ec
--- /dev/null
+++ b/.test-sessions/run-loop.ps1
@@ -0,0 +1,4 @@
+#!/usr/bin/env pwsh
+$ErrorActionPreference = 'Continue'
+$out = Join-Path $PSScriptRoot 'loop-run.log'
+browser4-cli loop --name mock-price-watch --count 2 -i 10 -- -s price-watch eval "document.querySelector('#product-price').textContent.trim()" *> $out

exec
"C:\\Program Files\\PowerShell\\7\\pwsh.exe" -Command '$lines = Get-Content "cli'"\\browser4-cli\\src\\main.rs\"; "'$lines[230..310] -join "`n"' in D:\workspace\Browser4\Browser4-4.13
 succeeded in 432ms:
/// human-oriented text is suppressed.
macro_rules! cli_println {
    () => {
        if !$crate::quiet_active() && !$crate::json_active() {
            ::std::println!();
        }
    };
    ($($arg:tt)*) => {
        if !$crate::quiet_active() && !$crate::json_active() {
            ::std::println!($($arg)*);
        }
    };
}

// ---------------------------------------------------------------------------
// Exit codes
// ---------------------------------------------------------------------------

/// Normalised exit codes reported by every command path.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(i32)]
enum ExitCode {
    #[allow(dead_code)]
    /// Command completed successfully.
    Success = 0,
    /// Catch-all for unexpected internal errors.
    General = 1,
    /// Invalid arguments, unknown command, bad URL, missing required args.
    Usage = 2,
    /// No active session, session expired, session conflict.
    Session = 3,
    /// Server unreachable, health-check timeout, daemon startup failure.
    Server = 4,
    /// One or more commands in a batch failed (processing itself succeeded).
    BatchPartial = 5,
}

/// Normalised error type that pairs a machine-readable exit code with a
/// human-readable message.
#[derive(Debug, Clone)]
struct CliError(ExitCode, String);

impl CliError {
    fn code(&self) -> ExitCode {
        self.0
    }
    fn message(&self) -> &str {
        &self.1
    }
}

impl std::fmt::Display for CliError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        self.1.fmt(f)
    }
}

impl From<String> for CliError {
    fn from(message: String) -> Self {
        CliError(ExitCode::General, message)
    }
}

impl From<&str> for CliError {
    fn from(message: &str) -> Self {
        CliError(ExitCode::General, message.to_string())
    }
}

/// Parsed arguments for the `loop` command.
#[derive(Debug, Default)]
struct LoopArgs {
    /// The raw tokens forming the task to execute.
    task_tokens: Vec<String>,
    /// True when `--shell` was specified.
    is_shell: bool,
    /// True when `--` was used to separate loop options from a browser4-cli subcommand.
    is_subcommand: bool,
    /// Seconds between iterations (default: 3600 = 1 hour).
    interval_secs: u64,
    /// Maximum number of iterations (None = infinite).

diff --git a/.test-sessions/ac1-listing-query.sql b/.test-sessions/ac1-listing-query.sql
new file mode 100644
index 0000000000000000000000000000000000000000..d651eec929ece8af65e1fdb818fac08d6e7ef4ea
--- /dev/null
+++ b/.test-sessions/ac1-listing-query.sql
@@ -0,0 +1,6 @@
+SELECT
+  DOM_FIRST_TEXT(DOM, 'div.product-title') AS title,
+  DOM_FIRST_TEXT(DOM, 'div.product-price') AS price,
+  DOM_FIRST_HREF(DOM, 'a.product-link') AS link
+FROM
+  DOM_LOAD_AND_SELECT(@url, '.product-card')
diff --git a/.test-sessions/ac2-product-query.sql b/.test-sessions/ac2-product-query.sql
new file mode 100644
index 0000000000000000000000000000000000000000..1fa45c1ec3c6f272620e6313938e1565b2d4239c
--- /dev/null
+++ b/.test-sessions/ac2-product-query.sql
@@ -0,0 +1,6 @@
+SELECT
+  DOM_BASE_URI(DOM) AS url,
+  DOM_FIRST_TEXT(DOM, '#productTitle') AS title,
+  DOM_FIRST_TEXT(DOM, '#product-price') AS price
+FROM
+  DOM_LOAD_AND_SELECT(@url, 'body')
diff --git a/.test-sessions/ac6-few-urls.ps1 b/.test-sessions/ac6-few-urls.ps1
new file mode 100644
index 0000000000000000000000000000000000000000..07a5a6936b1fab6f1574eb1c9627014aa6157e04
--- /dev/null
+++ b/.test-sessions/ac6-few-urls.ps1
@@ -0,0 +1,18 @@
+#!/usr/bin/env pwsh
+$ErrorActionPreference = 'Continue'
+
+$urls = @(
+    "http://localhost:18080/ec/dp/B0E000001",
+    "http://localhost:18080/ec/dp/B0E000004",
+    "http://localhost:18080/ec/dp/B0E000005"
+)
+
+$urls | ForEach-Object {
+    $u = $_
+    Write-Host "=== Processing: $u ==="
+    browser4-cli goto $u
+    browser4-cli htmlsnapshot
+    $title = browser4-cli htmlsnapshot get text "#productTitle"
+    Write-Host "Title: $title"
+    Start-Sleep -Seconds 2
+}
diff --git a/.test-sessions/run-loop.ps1 b/.test-sessions/run-loop.ps1
new file mode 100644
index 0000000000000000000000000000000000000000..a4da61b00ecf6843a91fe250ac4cb5afe8d6a8ec
--- /dev/null
+++ b/.test-sessions/run-loop.ps1
@@ -0,0 +1,4 @@
+#!/usr/bin/env pwsh
+$ErrorActionPreference = 'Continue'
+$out = Join-Path $PSScriptRoot 'loop-run.log'
+browser4-cli loop --name mock-price-watch --count 2 -i 10 -- -s price-watch eval "document.querySelector('#product-price').textContent.trim()" *> $out

exec
"C:\\Program Files\\PowerShell\\7\\pwsh.exe" -Command 'rg -n "Try .htmlsnapshot get text|generate_tip|show_tip|print_tip" cli/browser4-cli/src --glob "*.rs" | Select-Object -First 30' in D:\workspace\Browser4\Browser4-4.13
 succeeded in 377ms:
cli/browser4-cli/src\tips.rs:450:pub fn show_tip(command: &str) {
cli/browser4-cli/src\tips.rs:452:    if !crate::show_tip_active() {
cli/browser4-cli/src\args.rs:25:    pub show_tip: bool,
cli/browser4-cli/src\args.rs:109:            flags.show_tip = true;
cli/browser4-cli/src\main.rs:169:fn show_tip_init(show_tip: bool) {
cli/browser4-cli/src\main.rs:170:    SHOW_TIP.with(|cell| *cell.borrow_mut() = show_tip);
cli/browser4-cli/src\main.rs:173:fn show_tip_active() -> bool {
cli/browser4-cli/src\main.rs:1059:            "💡 Tip: Try `htmlsnapshot get text \"h1\"` to extract the page heading, or `htmlsnapshot inspect` to discover CSS selectors"
cli/browser4-cli/src\main.rs:15276:            show_tip: global.show_tip,
cli/browser4-cli/src\main.rs:16307:    show_tip_init(global.show_tip);
cli/browser4-cli/src\main.rs:17574:    tips::show_tip(command);
cli/browser4-cli/src\main.rs:18708:            show_tip: false,
cli/browser4-cli/src\main.rs:18735:            show_tip: false,

diff --git a/.test-sessions/ac1-listing-query.sql b/.test-sessions/ac1-listing-query.sql
new file mode 100644
index 0000000000000000000000000000000000000000..d651eec929ece8af65e1fdb818fac08d6e7ef4ea
--- /dev/null
+++ b/.test-sessions/ac1-listing-query.sql
@@ -0,0 +1,6 @@
+SELECT
+  DOM_FIRST_TEXT(DOM, 'div.product-title') AS title,
+  DOM_FIRST_TEXT(DOM, 'div.product-price') AS price,
+  DOM_FIRST_HREF(DOM, 'a.product-link') AS link
+FROM
+  DOM_LOAD_AND_SELECT(@url, '.product-card')
diff --git a/.test-sessions/ac2-product-query.sql b/.test-sessions/ac2-product-query.sql
new file mode 100644
index 0000000000000000000000000000000000000000..1fa45c1ec3c6f272620e6313938e1565b2d4239c
--- /dev/null
+++ b/.test-sessions/ac2-product-query.sql
@@ -0,0 +1,6 @@
+SELECT
+  DOM_BASE_URI(DOM) AS url,
+  DOM_FIRST_TEXT(DOM, '#productTitle') AS title,
+  DOM_FIRST_TEXT(DOM, '#product-price') AS price
+FROM
+  DOM_LOAD_AND_SELECT(@url, 'body')
diff --git a/.test-sessions/ac6-few-urls.ps1 b/.test-sessions/ac6-few-urls.ps1
new file mode 100644
index 0000000000000000000000000000000000000000..07a5a6936b1fab6f1574eb1c9627014aa6157e04
--- /dev/null
+++ b/.test-sessions/ac6-few-urls.ps1
@@ -0,0 +1,18 @@
+#!/usr/bin/env pwsh
+$ErrorActionPreference = 'Continue'
+
+$urls = @(
+    "http://localhost:18080/ec/dp/B0E000001",
+    "http://localhost:18080/ec/dp/B0E000004",
+    "http://localhost:18080/ec/dp/B0E000005"
+)
+
+$urls | ForEach-Object {
+    $u = $_
+    Write-Host "=== Processing: $u ==="
+    browser4-cli goto $u
+    browser4-cli htmlsnapshot
+    $title = browser4-cli htmlsnapshot get text "#productTitle"
+    Write-Host "Title: $title"
+    Start-Sleep -Seconds 2
+}
diff --git a/.test-sessions/run-loop.ps1 b/.test-sessions/run-loop.ps1
new file mode 100644
index 0000000000000000000000000000000000000000..a4da61b00ecf6843a91fe250ac4cb5afe8d6a8ec
--- /dev/null
+++ b/.test-sessions/run-loop.ps1
@@ -0,0 +1,4 @@
+#!/usr/bin/env pwsh
+$ErrorActionPreference = 'Continue'
+$out = Join-Path $PSScriptRoot 'loop-run.log'
+browser4-cli loop --name mock-price-watch --count 2 -i 10 -- -s price-watch eval "document.querySelector('#product-price').textContent.trim()" *> $out

exec
"C:\\Program Files\\PowerShell\\7\\pwsh.exe" -Command '$lines = Get-Content "cli'"\\browser4-cli\\src\\main.rs\"; "'$lines[1035..1068] -join "`n"; "--- 17560..17585 ---"; $lines[17560..17585] -join "`n"' in D:\workspace\Browser4\Browser4-4.13
 succeeded in 431ms:
    };

    let out_path = resolve_output_path(None, "snapshot", "yml");
    // Prepend a header comment documenting the snapshot.
    let header = "# Auto-snapshot after command — current viewport.\n\
                  # Use `browser4-cli snapshot grep <pattern>` to search the tree.\n";
    let snap_with_header = format!("{}\n{}", header, snap_result);
    if let Err(e) = save_snapshot(&out_path, &snap_with_header) {
        eprintln!("Warning: failed to save snapshot: {e}");
        return;
    }

    json_field("page_url", json!(&url_result));
    json_field("page_title", json!(&title_result));
    json_field("snapshot_path", json!(out_path.display().to_string()));

    cli_println!("### Page");
    cli_println!("- Page URL: {}", url_result);
    cli_println!("- Page Title: {}", title_result);
    cli_println!("### Snapshot");
    cli_println!("[Snapshot]({})", out_path.display());
    if !json_active() {
        eprintln!(
            "💡 Tip: Try `htmlsnapshot get text \"h1\"` to extract the page heading, or `htmlsnapshot inspect` to discover CSS selectors"
        );
    }
}

// ---------------------------------------------------------------------------
// Command handlers
// ---------------------------------------------------------------------------

async fn get_or_create_navigation_session(
    client: &Client,
--- 17560..17585 ---
    // Use println! directly — cli_println! checks json_active() which is
    // true for the entire command lifetime, and we MUST emit the JSON
    // envelope regardless.  (Same pattern as the error envelope below.)
    if json_enabled {
        if let Some(fields) = json_finish() {
            println!(
                "{}",
                json_envelope("ok", command, serde_json::Value::Object(fields), None)
            );
        }
    }

    // Show a relevant tip on stderr to help AI agents discover advanced features.
    tips::show_tip(command);

    Ok(())
}

/// Resolve a help target from command-line arguments.
///
/// Handles spaced prefixed forms (`swarm create`, `agent run`) by rewriting
/// them to internal flat names. Returns `None` when no target was given.
fn resolve_help_target(args: &[String]) -> Option<String> {
    if args.is_empty() {
        return None;
    }

diff --git a/.test-sessions/ac1-listing-query.sql b/.test-sessions/ac1-listing-query.sql
new file mode 100644
index 0000000000000000000000000000000000000000..d651eec929ece8af65e1fdb818fac08d6e7ef4ea
--- /dev/null
+++ b/.test-sessions/ac1-listing-query.sql
@@ -0,0 +1,6 @@
+SELECT
+  DOM_FIRST_TEXT(DOM, 'div.product-title') AS title,
+  DOM_FIRST_TEXT(DOM, 'div.product-price') AS price,
+  DOM_FIRST_HREF(DOM, 'a.product-link') AS link
+FROM
+  DOM_LOAD_AND_SELECT(@url, '.product-card')
diff --git a/.test-sessions/ac2-product-query.sql b/.test-sessions/ac2-product-query.sql
new file mode 100644
index 0000000000000000000000000000000000000000..1fa45c1ec3c6f272620e6313938e1565b2d4239c
--- /dev/null
+++ b/.test-sessions/ac2-product-query.sql
@@ -0,0 +1,6 @@
+SELECT
+  DOM_BASE_URI(DOM) AS url,
+  DOM_FIRST_TEXT(DOM, '#productTitle') AS title,
+  DOM_FIRST_TEXT(DOM, '#product-price') AS price
+FROM
+  DOM_LOAD_AND_SELECT(@url, 'body')
diff --git a/.test-sessions/ac6-few-urls.ps1 b/.test-sessions/ac6-few-urls.ps1
new file mode 100644
index 0000000000000000000000000000000000000000..07a5a6936b1fab6f1574eb1c9627014aa6157e04
--- /dev/null
+++ b/.test-sessions/ac6-few-urls.ps1
@@ -0,0 +1,18 @@
+#!/usr/bin/env pwsh
+$ErrorActionPreference = 'Continue'
+
+$urls = @(
+    "http://localhost:18080/ec/dp/B0E000001",
+    "http://localhost:18080/ec/dp/B0E000004",
+    "http://localhost:18080/ec/dp/B0E000005"
+)
+
+$urls | ForEach-Object {
+    $u = $_
+    Write-Host "=== Processing: $u ==="
+    browser4-cli goto $u
+    browser4-cli htmlsnapshot
+    $title = browser4-cli htmlsnapshot get text "#productTitle"
+    Write-Host "Title: $title"
+    Start-Sleep -Seconds 2
+}
diff --git a/.test-sessions/run-loop.ps1 b/.test-sessions/run-loop.ps1
new file mode 100644
index 0000000000000000000000000000000000000000..a4da61b00ecf6843a91fe250ac4cb5afe8d6a8ec
--- /dev/null
+++ b/.test-sessions/run-loop.ps1
@@ -0,0 +1,4 @@
+#!/usr/bin/env pwsh
+$ErrorActionPreference = 'Continue'
+$out = Join-Path $PSScriptRoot 'loop-run.log'
+browser4-cli loop --name mock-price-watch --count 2 -i 10 -- -s price-watch eval "document.querySelector('#product-price').textContent.trim()" *> $out

exec
"C:\\Program Files\\PowerShell\\7\\pwsh.exe" -Command '$lines = Get-Content "cli'"\\browser4-cli\\src\\main.rs\"; "'$lines[1140..1260] -join "`n"' in D:\workspace\Browser4\Browser4-4.13
 succeeded in 462ms:
            existing_id
        }
    } else if state.is_attached {
        // For attached sessions (CDP or extension), never fall through to
        // create_session — that would launch a NEW browser instance instead
        // of using the attached one.  Verify health directly and reuse the
        // attached session, or report a clear error if it is gone.
        if let Some(ref attached_id) = state.session_id {
            let ready_params = json!({ "sessionId": attached_id });
            let healthy = call_tool(client, base_url, "check_session_ready", ready_params)
                .await
                .ok()
                .and_then(|r| serde_json::from_str::<Value>(&r).ok())
                .map(|v| {
                    let ready = v.get("ready").and_then(|r| r.as_bool()).unwrap_or(false);
                    let h = v.get("healthy").and_then(|h| h.as_bool()).unwrap_or(false);
                    ready && h
                })
                .unwrap_or(false);

            if healthy {
                attached_id.clone()
            } else if force_new_session {
                // The attached session is stale, and the caller explicitly
                // wants a new session (e.g., `open` command).  Auto-evict
                // the stale attached session so the unnamed slot is freed
                // for the new Browser4 session.
                cli_println!(
                    "Attached session {} is no longer healthy — auto-evicting it to create a new Browser4 session.",
                    attached_id
                );
                cli_println!(
                    "Use 'attach --extension' or 'attach --cdp' to reconnect the attached browser later."
                );
                invalidate_session(&state, base_url, session_name);
                let capabilities = build_open_session_capabilities(tool_params);
                let new_id =
                    create_session(client, base_url, &state, session_name, Some(capabilities))
                        .await?;
                cli_println!(
                    "{}",
                    format_session_opened_message(session_name, &new_id)
                );
                new_id
            } else {
                let attach_cmd = if state.attach_type.as_deref() == Some("extension") {
                    "attach --extension"
                } else {
                    "attach --cdp"
                };
                let mut msg = format!(
                    "Attached session {} is no longer healthy. \
                     The browser or extension may have disconnected.\n\
                     Re-run `{}` to reconnect, or \
                     `close` / `close-all` to clear this session state.",
                    attached_id, attach_cmd
                );
                // Add chrome:// page hint for extension sessions
                if state.attach_type.as_deref() == Some("extension") {
                    msg.push_str(
                        "\n\nNote: Navigating to chrome:// internal pages \
                         (chrome://version, chrome://settings, etc.) may \
                         cause the extension connection to drop. After such \
                         navigation, re-attach with `attach --extension`.",
                    );
                }
                return Err(msg);
            }
        } else {
            return Err(
                "Attached session state has no session ID — re-run `attach` to create one."
                    .to_string(),
            );
        }
    } else {
        // When the user passes -s <id> and <id> happens to be the session_id
        // of an existing attached session (e.g., extension), refuse to create a
        // new Browser4 session under that name.  Creating a fresh session with
        // the same identifier as an attached session silently overwrites the
        // session metadata (connection type, etc.).  Direct the user toward
        // the correct command for targeting the attached session.
        if let Some(name) = session_name {
            let default_state = read_state(None, None);
            if default_state.session_id.as_deref() == Some(name)
                && default_state.is_attached
            {
                let attach_cmd = if default_state.attach_type.as_deref() == Some("extension") {
                    "attach --extension"
                } else {
                    "attach --cdp"
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

diff --git a/.test-sessions/ac1-listing-query.sql b/.test-sessions/ac1-listing-query.sql
new file mode 100644
index 0000000000000000000000000000000000000000..d651eec929ece8af65e1fdb818fac08d6e7ef4ea
--- /dev/null
+++ b/.test-sessions/ac1-listing-query.sql
@@ -0,0 +1,6 @@
+SELECT
+  DOM_FIRST_TEXT(DOM, 'div.product-title') AS title,
+  DOM_FIRST_TEXT(DOM, 'div.product-price') AS price,
+  DOM_FIRST_HREF(DOM, 'a.product-link') AS link
+FROM
+  DOM_LOAD_AND_SELECT(@url, '.product-card')
diff --git a/.test-sessions/ac2-product-query.sql b/.test-sessions/ac2-product-query.sql
new file mode 100644
index 0000000000000000000000000000000000000000..1fa45c1ec3c6f272620e6313938e1565b2d4239c
--- /dev/null
+++ b/.test-sessions/ac2-product-query.sql
@@ -0,0 +1,6 @@
+SELECT
+  DOM_BASE_URI(DOM) AS url,
+  DOM_FIRST_TEXT(DOM, '#productTitle') AS title,
+  DOM_FIRST_TEXT(DOM, '#product-price') AS price
+FROM
+  DOM_LOAD_AND_SELECT(@url, 'body')
diff --git a/.test-sessions/ac6-few-urls.ps1 b/.test-sessions/ac6-few-urls.ps1
new file mode 100644
index 0000000000000000000000000000000000000000..07a5a6936b1fab6f1574eb1c9627014aa6157e04
--- /dev/null
+++ b/.test-sessions/ac6-few-urls.ps1
@@ -0,0 +1,18 @@
+#!/usr/bin/env pwsh
+$ErrorActionPreference = 'Continue'
+
+$urls = @(
+    "http://localhost:18080/ec/dp/B0E000001",
+    "http://localhost:18080/ec/dp/B0E000004",
+    "http://localhost:18080/ec/dp/B0E000005"
+)
+
+$urls | ForEach-Object {
+    $u = $_
+    Write-Host "=== Processing: $u ==="
+    browser4-cli goto $u
+    browser4-cli htmlsnapshot
+    $title = browser4-cli htmlsnapshot get text "#productTitle"
+    Write-Host "Title: $title"
+    Start-Sleep -Seconds 2
+}
diff --git a/.test-sessions/run-loop.ps1 b/.test-sessions/run-loop.ps1
new file mode 100644
index 0000000000000000000000000000000000000000..a4da61b00ecf6843a91fe250ac4cb5afe8d6a8ec
--- /dev/null
+++ b/.test-sessions/run-loop.ps1
@@ -0,0 +1,4 @@
+#!/usr/bin/env pwsh
+$ErrorActionPreference = 'Continue'
+$out = Join-Path $PSScriptRoot 'loop-run.log'
+browser4-cli loop --name mock-price-watch --count 2 -i 10 -- -s price-watch eval "document.querySelector('#product-price').textContent.trim()" *> $out

exec
"C:\\Program Files\\PowerShell\\7\\pwsh.exe" -Command '$out1 = ".test-sessions'"\\goto-stdout3.log\"; "'$err1 = ".test-sessions'"\\goto-stderr3.log\"; Remove-Item "'$out1,$err1 -ErrorAction SilentlyContinue; browser4-cli -s ac3-check goto "http://localhost:18080/ec/dp/B0E000001" 1>> $out1 2>> $err1; browser4-cli -s ac3-check goto "http://localhost:18080/ec/dp/B0E000002" 1>> $out1 2>> $err1; browser4-cli -s ac3-check goto "http://localhost:18080/ec/dp/B0E000003" 1>> $out1 2>> $err1; "=== STDOUT ==="; Get-Content $out1; "=== STDERR ==="; Get-Content $err1' in D:\workspace\Browser4\Browser4-4.13
 succeeded in 16324ms:
=== STDOUT ===
Session opened: ac3-check (a8105432-77e5-44f8-9406-2edafb553b79)
Navigated to http://localhost:18080/ec/dp/B0E000001
Page loaded. Use `wait --load networkidle` if content appears incomplete (e.g. async-rendered SPAs).
### Page
- Page URL: http://localhost:18080/ec/dp/B0E000001
- Page Title: Product: 4K OLED TV 55
### Snapshot
[Snapshot](D:\workspace\Browser4\Browser4-4.13\.browser4-cli\snapshot\snapshot-2026-08-14T07-20-53-118Z.yml)
Using existing session ac3-check (a8105432-77e5-44f8-9406-2edafb553b79) (current page: http://localhost:18080/ec/dp/B0E000001).
Navigated to http://localhost:18080/ec/dp/B0E000002
Page loaded. Use `wait --load networkidle` if content appears incomplete (e.g. async-rendered SPAs).
### Page
- Page URL: http://localhost:18080/ec/dp/B0E000002
- Page Title: Product: Wireless Noise-Cancelling Headphones
### Snapshot
[Snapshot](D:\workspace\Browser4\Browser4-4.13\.browser4-cli\snapshot\snapshot-2026-08-14T07-20-57-774Z.yml)
Using existing session ac3-check (a8105432-77e5-44f8-9406-2edafb553b79) (current page: http://localhost:18080/ec/dp/B0E000002).
Navigated to http://localhost:18080/ec/dp/B0E000003
Page loaded. Use `wait --load networkidle` if content appears incomplete (e.g. async-rendered SPAs).
### Page
- Page URL: http://localhost:18080/ec/dp/B0E000003
- Page Title: Product: Portable Bluetooth Speaker
### Snapshot
[Snapshot](D:\workspace\Browser4\Browser4-4.13\.browser4-cli\snapshot\snapshot-2026-08-14T07-21-02-143Z.yml)
=== STDERR ===
No active session — creating a new one.
💡 Tip: Try `htmlsnapshot get text "h1"` to extract the page heading, or `htmlsnapshot inspect` to discover CSS selectors
💡 Tip: Try `htmlsnapshot get text "h1"` to extract the page heading, or `htmlsnapshot inspect` to discover CSS selectors
💡 Tip: Try `htmlsnapshot get text "h1"` to extract the page heading, or `htmlsnapshot inspect` to discover CSS selectors

diff --git a/.test-sessions/ac1-listing-query.sql b/.test-sessions/ac1-listing-query.sql
new file mode 100644
index 0000000000000000000000000000000000000000..d651eec929ece8af65e1fdb818fac08d6e7ef4ea
--- /dev/null
+++ b/.test-sessions/ac1-listing-query.sql
@@ -0,0 +1,6 @@
+SELECT
+  DOM_FIRST_TEXT(DOM, 'div.product-title') AS title,
+  DOM_FIRST_TEXT(DOM, 'div.product-price') AS price,
+  DOM_FIRST_HREF(DOM, 'a.product-link') AS link
+FROM
+  DOM_LOAD_AND_SELECT(@url, '.product-card')
diff --git a/.test-sessions/ac2-product-query.sql b/.test-sessions/ac2-product-query.sql
new file mode 100644
index 0000000000000000000000000000000000000000..1fa45c1ec3c6f272620e6313938e1565b2d4239c
--- /dev/null
+++ b/.test-sessions/ac2-product-query.sql
@@ -0,0 +1,6 @@
+SELECT
+  DOM_BASE_URI(DOM) AS url,
+  DOM_FIRST_TEXT(DOM, '#productTitle') AS title,
+  DOM_FIRST_TEXT(DOM, '#product-price') AS price
+FROM
+  DOM_LOAD_AND_SELECT(@url, 'body')
diff --git a/.test-sessions/ac6-few-urls.ps1 b/.test-sessions/ac6-few-urls.ps1
new file mode 100644
index 0000000000000000000000000000000000000000..07a5a6936b1fab6f1574eb1c9627014aa6157e04
--- /dev/null
+++ b/.test-sessions/ac6-few-urls.ps1
@@ -0,0 +1,18 @@
+#!/usr/bin/env pwsh
+$ErrorActionPreference = 'Continue'
+
+$urls = @(
+    "http://localhost:18080/ec/dp/B0E000001",
+    "http://localhost:18080/ec/dp/B0E000004",
+    "http://localhost:18080/ec/dp/B0E000005"
+)
+
+$urls | ForEach-Object {
+    $u = $_
+    Write-Host "=== Processing: $u ==="
+    browser4-cli goto $u
+    browser4-cli htmlsnapshot
+    $title = browser4-cli htmlsnapshot get text "#productTitle"
+    Write-Host "Title: $title"
+    Start-Sleep -Seconds 2
+}
diff --git a/.test-sessions/run-loop.ps1 b/.test-sessions/run-loop.ps1
new file mode 100644
index 0000000000000000000000000000000000000000..a4da61b00ecf6843a91fe250ac4cb5afe8d6a8ec
--- /dev/null
+++ b/.test-sessions/run-loop.ps1
@@ -0,0 +1,4 @@
+#!/usr/bin/env pwsh
+$ErrorActionPreference = 'Continue'
+$out = Join-Path $PSScriptRoot 'loop-run.log'
+browser4-cli loop --name mock-price-watch --count 2 -i 10 -- -s price-watch eval "document.querySelector('#product-price').textContent.trim()" *> $out

codex
The message fires on stderr during exactly one of the three gotos while stdout still shows session reuse. Let me trace where a second `get_or_create_navigation_session` call could occur during goto dispatch.
exec
"C:\\Program Files\\PowerShell\\7\\pwsh.exe" -Command '$lines = Get-Content "cli'"\\browser4-cli\\src\\main.rs\"; "'$lines[16260..16330] -join "`n"' in D:\workspace\Browser4\Browser4-4.13
 succeeded in 437ms:
        .filter(|(dist, _)| *dist <= max_distance)
        .collect();
    candidates.sort_by_key(|(dist, _)| *dist);
    candidates
        .into_iter()
        .take(max_suggestions)
        .map(|(_, name)| name)
        .collect()
}

fn json_envelope(
    status: &str,
    command: &str,
    output: serde_json::Value,
    error: Option<serde_json::Value>,
) -> String {
    let mut envelope = serde_json::Map::new();
    envelope.insert("status".to_string(), json!(status));
    envelope.insert("command".to_string(), json!(command));
    if let Some(err) = error {
        envelope.insert("error".to_string(), err);
    }
    envelope.insert("output".to_string(), output);
    let value = serde_json::Value::Object(envelope);
    if pretty_active() {
        serde_json::to_string_pretty(&value).unwrap_or_default()
    } else {
        value.to_string()
    }
}

async fn run(
    command: &str,
    global: &args::GlobalFlags,
    from_spaced_prefix: bool,
) -> Result<(), CliError> {
    // Initialise JSON output accumulator when --json is active.
    // Use a mutable local so subcommand-level --json (e.g. "tab-list --json")
    // can also enable it after the command-specific args are parsed.
    let mut json_enabled = global.json;
    if json_enabled {
        json_init();
    }
    // Initialise quiet mode when -q / --quiet is active.
    quiet_init(global.quiet);
    // Initialise show-tip mode when --show-tip / -tip is active.
    show_tip_init(global.show_tip);
    // Initialise pretty-print mode when --pretty is active.
    pretty_init(global.pretty);

    // ── Help dispatch ────────────────────────────────────────────────
    //
    // Progressive disclosure:
    //   no args        → quick reference (~35 lines, most-used commands)
    //   --help         → full command reference by category
    //   --help <topic> → category or per-command details
    //   --help-json    → machine-readable JSON (for AI agents / scripts)

    // --help-json as a standalone flag (before any command)
    if global.help_json {
        let help_args: Vec<String> = global.args.iter().cloned().collect();
        let sub = resolve_help_target(&help_args);
        println!("{}", generate_help_json(sub.as_deref()));
        return Ok(());
    }

    // --help-json as the command itself
    if command == "--help-json" {
        let help_args: Vec<String> = global.args.iter().skip(1).cloned().collect();
        let sub = resolve_help_target(&help_args);
        println!("{}", generate_help_json(sub.as_deref()));

diff --git a/.test-sessions/ac1-listing-query.sql b/.test-sessions/ac1-listing-query.sql
new file mode 100644
index 0000000000000000000000000000000000000000..d651eec929ece8af65e1fdb818fac08d6e7ef4ea
--- /dev/null
+++ b/.test-sessions/ac1-listing-query.sql
@@ -0,0 +1,6 @@
+SELECT
+  DOM_FIRST_TEXT(DOM, 'div.product-title') AS title,
+  DOM_FIRST_TEXT(DOM, 'div.product-price') AS price,
+  DOM_FIRST_HREF(DOM, 'a.product-link') AS link
+FROM
+  DOM_LOAD_AND_SELECT(@url, '.product-card')
diff --git a/.test-sessions/ac2-product-query.sql b/.test-sessions/ac2-product-query.sql
new file mode 100644
index 0000000000000000000000000000000000000000..1fa45c1ec3c6f272620e6313938e1565b2d4239c
--- /dev/null
+++ b/.test-sessions/ac2-product-query.sql
@@ -0,0 +1,6 @@
+SELECT
+  DOM_BASE_URI(DOM) AS url,
+  DOM_FIRST_TEXT(DOM, '#productTitle') AS title,
+  DOM_FIRST_TEXT(DOM, '#product-price') AS price
+FROM
+  DOM_LOAD_AND_SELECT(@url, 'body')
diff --git a/.test-sessions/ac6-few-urls.ps1 b/.test-sessions/ac6-few-urls.ps1
new file mode 100644
index 0000000000000000000000000000000000000000..07a5a6936b1fab6f1574eb1c9627014aa6157e04
--- /dev/null
+++ b/.test-sessions/ac6-few-urls.ps1
@@ -0,0 +1,18 @@
+#!/usr/bin/env pwsh
+$ErrorActionPreference = 'Continue'
+
+$urls = @(
+    "http://localhost:18080/ec/dp/B0E000001",
+    "http://localhost:18080/ec/dp/B0E000004",
+    "http://localhost:18080/ec/dp/B0E000005"
+)
+
+$urls | ForEach-Object {
+    $u = $_
+    Write-Host "=== Processing: $u ==="
+    browser4-cli goto $u
+    browser4-cli htmlsnapshot
+    $title = browser4-cli htmlsnapshot get text "#productTitle"
+    Write-Host "Title: $title"
+    Start-Sleep -Seconds 2
+}
diff --git a/.test-sessions/run-loop.ps1 b/.test-sessions/run-loop.ps1
new file mode 100644
index 0000000000000000000000000000000000000000..a4da61b00ecf6843a91fe250ac4cb5afe8d6a8ec
--- /dev/null
+++ b/.test-sessions/run-loop.ps1
@@ -0,0 +1,4 @@
+#!/usr/bin/env pwsh
+$ErrorActionPreference = 'Continue'
+$out = Join-Path $PSScriptRoot 'loop-run.log'
+browser4-cli loop --name mock-price-watch --count 2 -i 10 -- -s price-watch eval "document.querySelector('#product-price').textContent.trim()" *> $out

exec
"C:\\Program Files\\PowerShell\\7\\pwsh.exe" -Command 'rg -n "handle_goto|handle_open" cli/browser4-cli/src/main.rs | Select-Object -First 20; "---"; rg -n "retry|retry_" cli/browser4-cli/src/main.rs | Select-Object -First 40' in D:\workspace\Browser4\Browser4-4.13
 succeeded in 364ms:
1834:async fn handle_open(
1916:async fn handle_goto(
16555:            handle_open(
16575:            handle_goto(
---
519:        &[&format!("run `{bin} open <url>` to create a fresh session, then retry.")],
1866:                if !should_retry_open_after_navigation_error(&err, reused_existing_session) {
1876:                // Close the failed session, create a fresh one, and retry navigation.
1886:                let retry_id =
1891:                    format_session_opened_message(session_name, &retry_id)
1893:                params["sessionId"] = json!(retry_id);
1894:                let retry_result = call_tool(client, base_url, tool_name, params)
1899:                            &retry_id,
1901:                            should_retry_open_after_navigation_error(&err, true),
1904:                if !retry_result.is_empty() {
1905:                    cli_println!("{}", retry_result);
1907:                post_command_snapshot(client, base_url, &retry_id).await;
1973:            if !should_retry_open_after_navigation_error(&err, reused_existing_session) {
1974:                let should_suggest_refresh = should_retry_open_after_navigation_error(&err, true);
1992:            let retry_id =
1996:                format_session_opened_message(session_name, &retry_id)
1998:            params["sessionId"] = json!(retry_id.clone());
2022:                    post_command_snapshot(client, base_url, &retry_id).await;
2024:                Err(retry_err) => {
2026:                        should_retry_open_after_navigation_error(&retry_err, true);
2029:                        &retry_id,
2030:                        &retry_err,
2087:            .push(format!("run `{bin} open <url>` to refresh the session, then retry."));
2092:            "if the page eventually opens, increase `BROWSER4_CLI_NAVIGATION_TIMEOUT_SECS` and retry."
3858:fn should_retry_open_after_navigation_error(error: &str, reused_existing_session: bool) -> bool {
6365:                    cli_println!("  1. Re-run the query — the session may recover on retry.");
18348:    fn should_retry_open_after_navigation_error_for_stale_session_errors() {
18349:        assert!(should_retry_open_after_navigation_error(
18353:        assert!(should_retry_open_after_navigation_error(
18360:    fn should_retry_open_after_navigation_error_for_backend_disconnects_only_when_reusing() {
18362:        assert!(!should_retry_open_after_navigation_error(error, false));
18363:        assert!(should_retry_open_after_navigation_error(error, true));
18367:    fn format_navigation_failure_message_includes_refresh_guidance_for_retryable_errors() {
18398:    fn format_navigation_failure_message_omits_retry_guidance_for_non_retryable_errors() {
18433:            .contains("open <url>` to create a fresh session, then retry."));

diff --git a/.test-sessions/ac1-listing-query.sql b/.test-sessions/ac1-listing-query.sql
new file mode 100644
index 0000000000000000000000000000000000000000..d651eec929ece8af65e1fdb818fac08d6e7ef4ea
--- /dev/null
+++ b/.test-sessions/ac1-listing-query.sql
@@ -0,0 +1,6 @@
+SELECT
+  DOM_FIRST_TEXT(DOM, 'div.product-title') AS title,
+  DOM_FIRST_TEXT(DOM, 'div.product-price') AS price,
+  DOM_FIRST_HREF(DOM, 'a.product-link') AS link
+FROM
+  DOM_LOAD_AND_SELECT(@url, '.product-card')
diff --git a/.test-sessions/ac2-product-query.sql b/.test-sessions/ac2-product-query.sql
new file mode 100644
index 0000000000000000000000000000000000000000..1fa45c1ec3c6f272620e6313938e1565b2d4239c
--- /dev/null
+++ b/.test-sessions/ac2-product-query.sql
@@ -0,0 +1,6 @@
+SELECT
+  DOM_BASE_URI(DOM) AS url,
+  DOM_FIRST_TEXT(DOM, '#productTitle') AS title,
+  DOM_FIRST_TEXT(DOM, '#product-price') AS price
+FROM
+  DOM_LOAD_AND_SELECT(@url, 'body')
diff --git a/.test-sessions/ac6-few-urls.ps1 b/.test-sessions/ac6-few-urls.ps1
new file mode 100644
index 0000000000000000000000000000000000000000..07a5a6936b1fab6f1574eb1c9627014aa6157e04
--- /dev/null
+++ b/.test-sessions/ac6-few-urls.ps1
@@ -0,0 +1,18 @@
+#!/usr/bin/env pwsh
+$ErrorActionPreference = 'Continue'
+
+$urls = @(
+    "http://localhost:18080/ec/dp/B0E000001",
+    "http://localhost:18080/ec/dp/B0E000004",
+    "http://localhost:18080/ec/dp/B0E000005"
+)
+
+$urls | ForEach-Object {
+    $u = $_
+    Write-Host "=== Processing: $u ==="
+    browser4-cli goto $u
+    browser4-cli htmlsnapshot
+    $title = browser4-cli htmlsnapshot get text "#productTitle"
+    Write-Host "Title: $title"
+    Start-Sleep -Seconds 2
+}
diff --git a/.test-sessions/run-loop.ps1 b/.test-sessions/run-loop.ps1
new file mode 100644
index 0000000000000000000000000000000000000000..a4da61b00ecf6843a91fe250ac4cb5afe8d6a8ec
--- /dev/null
+++ b/.test-sessions/run-loop.ps1
@@ -0,0 +1,4 @@
+#!/usr/bin/env pwsh
+$ErrorActionPreference = 'Continue'
+$out = Join-Path $PSScriptRoot 'loop-run.log'
+browser4-cli loop --name mock-price-watch --count 2 -i 10 -- -s price-watch eval "document.querySelector('#product-price').textContent.trim()" *> $out

exec
"C:\\Program Files\\PowerShell\\7\\pwsh.exe" -Command '$lines = Get-Content "cli'"\\browser4-cli\\src\\main.rs\"; "'$lines[16520..16610] -join "`n"' in D:\workspace\Browser4\Browser4-4.13
 succeeded in 444ms:
            .unwrap_or("");
        let seed_file = tool_params.get("seedFile").and_then(|v| v.as_str());
        if url.is_empty() && seed_file.is_none() {
            return Err(CliError(
                ExitCode::Usage,
                "A URL or --seed-file is required.".to_string(),
            ));
        }
    }

    // Ensure the Browser4 server is running (for relevant commands).
    // Page-dependent commands targeting localhost do NOT auto-start the server
    // — the user should run `open <url>` or `goto <url>` first so a page is
    // already loaded.  Remote servers (--server <url>) always proceed normally.
    if should_ensure_server_running(command) {
        let is_local = base_url.contains("localhost") || base_url.contains("127.0.0.1");
        if is_page_dependent_command(command) && is_local && !is_local_port_open(&base_url) {
            let bin = cli_binary_name();
            return Err(CliError(
                ExitCode::Session,
                format!(
                    "No active browser session. Run '{bin} open <url>' or '{bin} goto <url>' first to load a page, then try '{}' again.",
                    command
                ),
            ));
        }
        ensure_server_running(&base_url).await?;
    }

    let client = make_client();

    // Dispatch the command
    match command {
        "open" => {
            handle_open(
                &client,
                &base_url,
                &tool_name,
                &tool_params,
                global.session_name.as_deref(),
            )
            .await?;
        }
        "attach" => {
            handle_attach(
                &client,
                &base_url,
                &tool_params,
                global.session_name.as_deref(),
                &parsed,
            )
            .await?;
        }
        "goto" => {
            handle_goto(
                &client,
                &base_url,
                &tool_name,
                &tool_params,
                global.session_name.as_deref(),
            )
            .await?;
        }
        "close" | "disconnect" => {
            handle_close(&client, &base_url, global.session_name.as_deref()).await?;
        }
        "close-all" => {
            handle_close_all(&client, &base_url).await?;
        }
        "kill-all" => {
            handle_kill_all().await?;
        }
        "session-default" => {
            handle_session_default(&tool_params).await?;
        }
        "list" => {
            let verbose = tool_params
                .get("verbose")
                .and_then(|v| v.as_bool())
                .unwrap_or(false);
            handle_list(&client, &base_url, verbose).await?;
        }
        "config" | "config-list" => {
            handle_config_list()?;
        }
        "config-get" => {
            handle_config_get(&tool_params)?;
        }
        "config-set" => {
            handle_config_set(&tool_params)?;
        }

diff --git a/.test-sessions/ac1-listing-query.sql b/.test-sessions/ac1-listing-query.sql
new file mode 100644
index 0000000000000000000000000000000000000000..d651eec929ece8af65e1fdb818fac08d6e7ef4ea
--- /dev/null
+++ b/.test-sessions/ac1-listing-query.sql
@@ -0,0 +1,6 @@
+SELECT
+  DOM_FIRST_TEXT(DOM, 'div.product-title') AS title,
+  DOM_FIRST_TEXT(DOM, 'div.product-price') AS price,
+  DOM_FIRST_HREF(DOM, 'a.product-link') AS link
+FROM
+  DOM_LOAD_AND_SELECT(@url, '.product-card')
diff --git a/.test-sessions/ac2-product-query.sql b/.test-sessions/ac2-product-query.sql
new file mode 100644
index 0000000000000000000000000000000000000000..1fa45c1ec3c6f272620e6313938e1565b2d4239c
--- /dev/null
+++ b/.test-sessions/ac2-product-query.sql
@@ -0,0 +1,6 @@
+SELECT
+  DOM_BASE_URI(DOM) AS url,
+  DOM_FIRST_TEXT(DOM, '#productTitle') AS title,
+  DOM_FIRST_TEXT(DOM, '#product-price') AS price
+FROM
+  DOM_LOAD_AND_SELECT(@url, 'body')
diff --git a/.test-sessions/ac6-few-urls.ps1 b/.test-sessions/ac6-few-urls.ps1
new file mode 100644
index 0000000000000000000000000000000000000000..07a5a6936b1fab6f1574eb1c9627014aa6157e04
--- /dev/null
+++ b/.test-sessions/ac6-few-urls.ps1
@@ -0,0 +1,18 @@
+#!/usr/bin/env pwsh
+$ErrorActionPreference = 'Continue'
+
+$urls = @(
+    "http://localhost:18080/ec/dp/B0E000001",
+    "http://localhost:18080/ec/dp/B0E000004",
+    "http://localhost:18080/ec/dp/B0E000005"
+)
+
+$urls | ForEach-Object {
+    $u = $_
+    Write-Host "=== Processing: $u ==="
+    browser4-cli goto $u
+    browser4-cli htmlsnapshot
+    $title = browser4-cli htmlsnapshot get text "#productTitle"
+    Write-Host "Title: $title"
+    Start-Sleep -Seconds 2
+}
diff --git a/.test-sessions/run-loop.ps1 b/.test-sessions/run-loop.ps1
new file mode 100644
index 0000000000000000000000000000000000000000..a4da61b00ecf6843a91fe250ac4cb5afe8d6a8ec
--- /dev/null
+++ b/.test-sessions/run-loop.ps1
@@ -0,0 +1,4 @@
+#!/usr/bin/env pwsh
+$ErrorActionPreference = 'Continue'
+$out = Join-Path $PSScriptRoot 'loop-run.log'
+browser4-cli loop --name mock-price-watch --count 2 -i 10 -- -s price-watch eval "document.querySelector('#product-price').textContent.trim()" *> $out

codex
Good — that message turns out to be the intentional "creating a new session" notice from the first `goto` (stderr just renders last in the merged stream). Not a bug. Now let me verify the crawl depth-label quirk and the swarm scheduling observation in the code.
exec
"C:\\Program Files\\PowerShell\\7\\pwsh.exe" -Command 'rg -n "depth=" cli/browser4-cli/src/crawl.rs cli/browser4-cli/src --glob "*.rs" | Select-Object -First 30' in D:\workspace\Browser4\Browser4-4.13
exec
"C:\\Program Files\\PowerShell\\7\\pwsh.exe" -Command 'rg -n "waiting for first page|pages found so far|poll" cli/browser4-cli/src --glob "*.rs" | Select-Object -First 30' in D:\workspace\Browser4\Browser4-4.13
2026-08-14T07:21:32.714770Z ERROR codex_core::tools::router: error=Exit code: 1
Wall time: 0.4 seconds
Output:
cli/browser4-cli/src\main.rs:11072:                    // the page listing shows a hollow "depth=0 | URL | " entry with
cli/browser4-cli/src\main.rs:11146:                                    "  depth={} | {} | ⚠ {}",
cli/browser4-cli/src\main.rs:11151:                                    "  depth={} | {} | ⚠ fetch returned 0 bytes",
cli/browser4-cli/src\main.rs:11156:                                    "  depth={} | {} | {}",
cli/browser4-cli/src\main.rs:13620:            .args(["list", "-g", "browser4-cli", "--depth=0"])
rg: cli/browser4-cli/src/crawl.rs: 系统找不到指定的文件。 (os error 2)

 exited 1 in 387ms:
cli/browser4-cli/src\main.rs:11072:                    // the page listing shows a hollow "depth=0 | URL | " entry with
cli/browser4-cli/src\main.rs:11146:                                    "  depth={} | {} | ⚠ {}",
cli/browser4-cli/src\main.rs:11151:                                    "  depth={} | {} | ⚠ fetch returned 0 bytes",
cli/browser4-cli/src\main.rs:11156:                                    "  depth={} | {} | {}",
cli/browser4-cli/src\main.rs:13620:            .args(["list", "-g", "browser4-cli", "--depth=0"])
rg: cli/browser4-cli/src/crawl.rs: 系统找不到指定的文件。 (os error 2)

 succeeded in 356ms:
cli/browser4-cli/src\tips.rs:240:        text: "Use `agent status` and `agent result` to poll async agent tasks",
cli/browser4-cli/src\http.rs:534:/// Returns a task ID that can be polled via the /api/conversations/{id} endpoint.
cli/browser4-cli/src\state.rs:913:    /// Last known status (empty until first poll).
cli/browser4-cli/src\state.rs:1009:            return true; // keep — not yet polled
cli/browser4-cli/src\managed_processes.rs:153:    poll_interval_ms: u64,
cli/browser4-cli/src\managed_processes.rs:179:            if wait_for_exit(pid, timeout_ms, poll_interval_ms) {
cli/browser4-cli/src\managed_processes.rs:188:        if wait_for_exit(pid, timeout_ms, poll_interval_ms) {
cli/browser4-cli/src\managed_processes.rs:195:        if wait_for_exit(pid, timeout_ms, poll_interval_ms) {
cli/browser4-cli/src\managed_processes.rs:1131:fn wait_for_exit(pid: u32, timeout_ms: u64, poll_interval_ms: u64) -> bool {
cli/browser4-cli/src\managed_processes.rs:1134:    let poll = std::time::Duration::from_millis(poll_interval_ms);
cli/browser4-cli/src\managed_processes.rs:1139:        std::thread::sleep(poll);
cli/browser4-cli/src\commands.rs:260:/// reused across polling iterations.
cli/browser4-cli/src\commands.rs:2467:                    description: "Submit asynchronously and return a task ID for later polling",
cli/browser4-cli/src\main.rs:1640:        let poll_start = std::time::Instant::now();
cli/browser4-cli/src\main.rs:1642:        let poll_interval = std::time::Duration::from_secs(1);
cli/browser4-cli/src\main.rs:1644:            let elapsed = poll_start.elapsed();
cli/browser4-cli/src\main.rs:1691:                    // connecting; keep polling silently.
cli/browser4-cli/src\main.rs:1697:            tokio::time::sleep(poll_interval).await;
cli/browser4-cli/src\main.rs:9097:                    // keep the first completion timestamp (don't overwrite on later polls).
cli/browser4-cli/src\main.rs:9478:    // Support --wait: poll until all submitted jobs complete
cli/browser4-cli/src\main.rs:9629:    // Support --wait: poll until all submitted jobs complete
cli/browser4-cli/src\main.rs:10027:    let poll_interval = std::time::Duration::from_secs(2);
cli/browser4-cli/src\main.rs:10113:        tokio::time::sleep(poll_interval).await;
cli/browser4-cli/src\main.rs:10164:                    // keep the first completion timestamp (don't overwrite on later polls).
cli/browser4-cli/src\main.rs:10592:/// Parse the crawl poll response and classify its status.
cli/browser4-cli/src\main.rs:10605:fn parse_crawl_poll_response(parsed: &Value) -> CrawlPollStatus {
cli/browser4-cli/src\main.rs:10846:    let poll_interval = std::time::Duration::from_secs(2);
cli/browser4-cli/src\main.rs:10875:        tokio::time::sleep(poll_interval).await;
cli/browser4-cli/src\main.rs:10970:                    "Crawling... waiting for first page ({}s elapsed, {} URLs queued)",
cli/browser4-cli/src\main.rs:11218:                    cli_println!("Crawling... {} pages found so far", pages_found);

diff --git a/.test-sessions/ac1-listing-query.sql b/.test-sessions/ac1-listing-query.sql
new file mode 100644
index 0000000000000000000000000000000000000000..d651eec929ece8af65e1fdb818fac08d6e7ef4ea
--- /dev/null
+++ b/.test-sessions/ac1-listing-query.sql
@@ -0,0 +1,6 @@
+SELECT
+  DOM_FIRST_TEXT(DOM, 'div.product-title') AS title,
+  DOM_FIRST_TEXT(DOM, 'div.product-price') AS price,
+  DOM_FIRST_HREF(DOM, 'a.product-link') AS link
+FROM
+  DOM_LOAD_AND_SELECT(@url, '.product-card')
diff --git a/.test-sessions/ac2-product-query.sql b/.test-sessions/ac2-product-query.sql
new file mode 100644
index 0000000000000000000000000000000000000000..1fa45c1ec3c6f272620e6313938e1565b2d4239c
--- /dev/null
+++ b/.test-sessions/ac2-product-query.sql
@@ -0,0 +1,6 @@
+SELECT
+  DOM_BASE_URI(DOM) AS url,
+  DOM_FIRST_TEXT(DOM, '#productTitle') AS title,
+  DOM_FIRST_TEXT(DOM, '#product-price') AS price
+FROM
+  DOM_LOAD_AND_SELECT(@url, 'body')
diff --git a/.test-sessions/ac6-few-urls.ps1 b/.test-sessions/ac6-few-urls.ps1
new file mode 100644
index 0000000000000000000000000000000000000000..07a5a6936b1fab6f1574eb1c9627014aa6157e04
--- /dev/null
+++ b/.test-sessions/ac6-few-urls.ps1
@@ -0,0 +1,18 @@
+#!/usr/bin/env pwsh
+$ErrorActionPreference = 'Continue'
+
+$urls = @(
+    "http://localhost:18080/ec/dp/B0E000001",
+    "http://localhost:18080/ec/dp/B0E000004",
+    "http://localhost:18080/ec/dp/B0E000005"
+)
+
+$urls | ForEach-Object {
+    $u = $_
+    Write-Host "=== Processing: $u ==="
+    browser4-cli goto $u
+    browser4-cli htmlsnapshot
+    $title = browser4-cli htmlsnapshot get text "#productTitle"
+    Write-Host "Title: $title"
+    Start-Sleep -Seconds 2
+}
diff --git a/.test-sessions/run-loop.ps1 b/.test-sessions/run-loop.ps1
new file mode 100644
index 0000000000000000000000000000000000000000..a4da61b00ecf6843a91fe250ac4cb5afe8d6a8ec
--- /dev/null
+++ b/.test-sessions/run-loop.ps1
@@ -0,0 +1,4 @@
+#!/usr/bin/env pwsh
+$ErrorActionPreference = 'Continue'
+$out = Join-Path $PSScriptRoot 'loop-run.log'
+browser4-cli loop --name mock-price-watch --count 2 -i 10 -- -s price-watch eval "document.querySelector('#product-price').textContent.trim()" *> $out

exec
"C:\\Program Files\\PowerShell\\7\\pwsh.exe" -Command '$lines = Get-Content "cli'"\\browser4-cli\\src\\main.rs\"; "'$lines[11050..11170] -join "`n"' in D:\workspace\Browser4\Browser4-4.13
 succeeded in 407ms:
                    }

                    let summary = format!("Results written");
                    let output = write_crawl_output(&extracted_output, output_file, &summary)?;
                    match output {
                        CrawlOutput::FileWritten { path, summary } => {
                            cli_println!("{} to {}", summary, path);
                        }
                        CrawlOutput::Stdout(content) => {
                            cli_println!("\n{}", content);
                        }
                    }

                    json_field("extracted", json!(all_extracted));
                } else {
                    let mut page_lines: Vec<String> = Vec::new();
                    page_lines.push(format!("Crawl completed. {} pages found.", page_count));

                    // Surface seed-level errors even in non-verbose mode.
                    // When a seed URL fails (e.g. link discovery crashes, protocol
                    // handler not ready), the error is stored in seedStatuses but
                    // the page listing shows a hollow "depth=0 | URL | " entry with
                    // no indication of failure.  Without this warning, users see
                    // "Crawl completed. N pages found." and assume all succeeded.
                    if let Some(seed_statuses) = parsed["seedStatuses"].as_array() {
                        let failed: Vec<&Value> = seed_statuses.iter()
                            .filter(|ss| ss["status"].as_str().unwrap_or("") == "error")
                            .collect();
                        if !failed.is_empty() {
                            page_lines.push(format!(
                                "\n⚠ {} of {} seed URL(s) failed:",
                                failed.len(), seed_statuses.len()
                            ));
                            for ss in &failed {
                                let s_url = ss["url"].as_str().unwrap_or("");
                                let s_error = ss["error"].as_str().unwrap_or("unknown error");
                                page_lines.push(format!("    ✗ {} — {}", s_url, s_error));
                            }
                            page_lines.push(
                                "  Use --verbose for full per-seed diagnostics.".to_string()
                            );
                        }
                    }

                    // Display per-seed-URL status when verbose
                    if verbose {
                        if let Some(seed_statuses) = parsed["seedStatuses"].as_array() {
                            if !seed_statuses.is_empty() {
                                page_lines.push(String::new());
                                page_lines.push("  Seed URL Status:".to_string());
                                for ss in seed_statuses {
                                    let s_url = ss["url"].as_str().unwrap_or("");
                                    let s_status = ss["status"].as_str().unwrap_or("");
                                    let s_pages = ss["pagesReturned"].as_i64().unwrap_or(0);
                                    let s_error = ss["error"].as_str().unwrap_or("");
                                    let icon = match s_status {
                                        "fetched" => "✓",
                                        "skipped" => "⊘",
                                        _ => "✗",
                                    };
                                    if s_error.is_empty() {
                                        page_lines.push(format!(
                                            "    {} {} → {} page(s)", icon, s_url, s_pages
                                        ));
                                    } else {
                                        page_lines.push(format!(
                                            "    {} {} → {} (error: {})", icon, s_url, s_pages, s_error
                                        ));
                                    }
                                }
                            }
                        }
                    }

                    // Display diagnostic info when 0 pages found (e.g. selector matched no elements)
                    if page_count == 0 {
                        if let Some(diag) = parsed["diagnostic"].as_str() {
                            page_lines.push(format!("\n  Diagnostic: {}", diag));
                        }
                        page_lines.push("\n  Tips:".to_string());
                        page_lines.push("    - Verify the --out-link-selector targets the correct elements".to_string());
                        page_lines.push("    - Use 'snapshot' or 'htmlsnapshot' to inspect the page structure first".to_string());
                    }

                    if let Some(pages) = pages {
                        for page in pages {
                            let page_url = page["url"].as_str().unwrap_or("");
                            let page_title = page["title"].as_str().unwrap_or("");
                            let page_depth = page["depth"].as_i64().unwrap_or(0);
                            let extraction_error = page["extractionError"].as_str();
                            let content_len = page["contentLength"].as_i64().unwrap_or(-1);
                            // Show fetch errors inline even in non-verbose mode so the
                            // user isn't misled by "Crawl completed" for failed pages.
                            if let Some(err) = extraction_error {
                                page_lines.push(format!(
                                    "  depth={} | {} | ⚠ {}",
                                    page_depth, page_url, err
                                ));
                            } else if content_len == 0 {
                                page_lines.push(format!(
                                    "  depth={} | {} | ⚠ fetch returned 0 bytes",
                                    page_depth, page_url
                                ));
                            } else {
                                page_lines.push(format!(
                                    "  depth={} | {} | {}",
                                    page_depth, page_url, page_title
                                ));
                            }
                            if verbose {
                                if extraction_error.is_none() && has_sql && page["extracted"].as_array().map_or(false, |a| a.is_empty()) {
                                    page_lines.push("    ⚠ X-SQL extraction returned 0 rows".to_string());
                                }
                            }
                        }
                    }
                    // Show a summary when pages had errors so the user doesn't see
                    // "Crawl completed. N pages found." and assume all succeeded.
                    let error_count = pages.map(|p| {
                        p.iter().filter(|pg| {
                            pg["extractionError"].as_str().is_some() ||

diff --git a/.test-sessions/ac1-listing-query.sql b/.test-sessions/ac1-listing-query.sql
new file mode 100644
index 0000000000000000000000000000000000000000..d651eec929ece8af65e1fdb818fac08d6e7ef4ea
--- /dev/null
+++ b/.test-sessions/ac1-listing-query.sql
@@ -0,0 +1,6 @@
+SELECT
+  DOM_FIRST_TEXT(DOM, 'div.product-title') AS title,
+  DOM_FIRST_TEXT(DOM, 'div.product-price') AS price,
+  DOM_FIRST_HREF(DOM, 'a.product-link') AS link
+FROM
+  DOM_LOAD_AND_SELECT(@url, '.product-card')
diff --git a/.test-sessions/ac2-product-query.sql b/.test-sessions/ac2-product-query.sql
new file mode 100644
index 0000000000000000000000000000000000000000..1fa45c1ec3c6f272620e6313938e1565b2d4239c
--- /dev/null
+++ b/.test-sessions/ac2-product-query.sql
@@ -0,0 +1,6 @@
+SELECT
+  DOM_BASE_URI(DOM) AS url,
+  DOM_FIRST_TEXT(DOM, '#productTitle') AS title,
+  DOM_FIRST_TEXT(DOM, '#product-price') AS price
+FROM
+  DOM_LOAD_AND_SELECT(@url, 'body')
diff --git a/.test-sessions/ac6-few-urls.ps1 b/.test-sessions/ac6-few-urls.ps1
new file mode 100644
index 0000000000000000000000000000000000000000..07a5a6936b1fab6f1574eb1c9627014aa6157e04
--- /dev/null
+++ b/.test-sessions/ac6-few-urls.ps1
@@ -0,0 +1,18 @@
+#!/usr/bin/env pwsh
+$ErrorActionPreference = 'Continue'
+
+$urls = @(
+    "http://localhost:18080/ec/dp/B0E000001",
+    "http://localhost:18080/ec/dp/B0E000004",
+    "http://localhost:18080/ec/dp/B0E000005"
+)
+
+$urls | ForEach-Object {
+    $u = $_
+    Write-Host "=== Processing: $u ==="
+    browser4-cli goto $u
+    browser4-cli htmlsnapshot
+    $title = browser4-cli htmlsnapshot get text "#productTitle"
+    Write-Host "Title: $title"
+    Start-Sleep -Seconds 2
+}
diff --git a/.test-sessions/run-loop.ps1 b/.test-sessions/run-loop.ps1
new file mode 100644
index 0000000000000000000000000000000000000000..a4da61b00ecf6843a91fe250ac4cb5afe8d6a8ec
--- /dev/null
+++ b/.test-sessions/run-loop.ps1
@@ -0,0 +1,4 @@
+#!/usr/bin/env pwsh
+$ErrorActionPreference = 'Continue'
+$out = Join-Path $PSScriptRoot 'loop-run.log'
+browser4-cli loop --name mock-price-watch --count 2 -i 10 -- -s price-watch eval "document.querySelector('#product-price').textContent.trim()" *> $out

codex
The CLI renders the backend's `depth` field directly. Let me check the backend crawl code to understand how depth is assigned.
exec
"C:\\Program Files\\PowerShell\\7\\pwsh.exe" -Command 'rg -n "depth" browser4-core browser4-rest --glob "*.kt" | Select-String -Pattern "crawl|Crawl|depth" | Select-Object -First 40' in D:\workspace\Browser4\Browser4-4.13
 succeeded in 436ms:

browser4-core\browser4-skeleton\src\test\kotlin\ai\platon\pulsar\skeleton\workflow\parse\html\FeaturedDocumentExprTest.
kt:21: * - `dep` — node depth in the document tree
browser4-core\browser4-skeleton\src\test\kotlin\ai\platon\pulsar\skeleton\workflow\parse\html\FeaturedDocumentExprTest.
kt:258:    @DisplayName(":expr(dep >= 0) selects all elements (depth is always >= 0)")
browser4-core\browser4-skeleton\src\test\kotlin\ai\platon\pulsar\skeleton\workflow\parse\html\FeaturedDocumentExprTest.
kt:259:    fun depthAlwaysNonNegative() {
browser4-core\browser4-skeleton\src\test\kotlin\ai\platon\pulsar\skeleton\workflow\parse\html\FeaturedDocumentExprTest.
kt:272:        assertEquals(3, all.size, "All 3 divs have depth >= 0")
browser4-rest\src\main\kotlin\ai\platon\pulsar\rest\mcp\controller\MCPToolController.kt:1668:            val depth = de
sc.parents().indexOfFirst { it === match } + 1
browser4-rest\src\main\kotlin\ai\platon\pulsar\rest\mcp\controller\MCPToolController.kt:1669:            if (depth < 0 
|| depth > maxDepth) continue
browser4-rest\src\main\kotlin\ai\platon\pulsar\rest\mcp\controller\MCPToolController.kt:2093: * or "Page" if no semanti
c ancestor is found within reasonable depth.
browser4-rest\src\main\kotlin\ai\platon\pulsar\rest\mcp\controller\MCPToolController.kt:2097:    var depth = 0
browser4-rest\src\main\kotlin\ai\platon\pulsar\rest\mcp\controller\MCPToolController.kt:2098:    while (current != null
 && depth < 10) {
browser4-rest\src\main\kotlin\ai\platon\pulsar\rest\mcp\controller\MCPToolController.kt:2113:        depth++
browser4-rest\src\test\kotlin\ai\platon\pulsar\rest\api\service\CrawlServiceTest.kt:66:        val request = CrawlReque
st(url = "", depth = 0)
browser4-rest\src\test\kotlin\ai\platon\pulsar\rest\api\service\CrawlServiceTest.kt:92:        val request = CrawlReque
st(url = "", depth = 0)
browser4-rest\src\test\kotlin\ai\platon\pulsar\rest\api\service\CrawlServiceTest.kt:131:        val request = CrawlRequ
est(url = "", depth = 0)
browser4-rest\src\test\kotlin\ai\platon\pulsar\rest\api\service\CrawlServiceTest.kt:163:        val request = CrawlRequ
est(url = "", depth = 0)
browser4-rest\src\main\kotlin\ai\platon\pulsar\rest\api\controller\CrawlController.kt:28:     * @param request The craw
l configuration: starting URL, LoadOptions args string, and depth
browser4-rest\src\main\kotlin\ai\platon\pulsar\rest\api\controller\CrawlController.kt:36:        if (request.depth < 0)
 {
browser4-rest\src\main\kotlin\ai\platon\pulsar\rest\api\controller\CrawlController.kt:37:            throw IllegalArgum
entException("depth must be >= 0, got ${request.depth}")
browser4-rest\src\main\kotlin\ai\platon\pulsar\rest\api\controller\CrawlController.kt:39:        logger.info("Crawl req
uest: url='{}' seeds={} depth={} args='{}' sql={}",
browser4-rest\src\main\kotlin\ai\platon\pulsar\rest\api\controller\CrawlController.kt:40:            request.url, reque
st.urls?.size ?: 0, request.depth, request.args, request.sql != null)
browser4-rest\src\main\kotlin\ai\platon\pulsar\rest\api\service\CrawlService.kt:36:    @param:JsonProperty("depth") val
 depth: Int = 1,
browser4-rest\src\main\kotlin\ai\platon\pulsar\rest\api\service\CrawlService.kt:69:    val depth: Int = 0,
browser4-rest\src\main\kotlin\ai\platon\pulsar\rest\api\service\CrawlService.kt:206:                    // For depth=0 
(bulk fetch mode), reuse a single session across
browser4-rest\src\main\kotlin\ai\platon\pulsar\rest\api\service\CrawlService.kt:213:                    val sharedDepth
0Session = if (request.depth == 0) {
browser4-rest\src\main\kotlin\ai\platon\pulsar\rest\api\service\CrawlService.kt:228:                                   
 seedRequest.depth == 0 -> crawlDepth0(taskId, seedRequest, sharedDepth0Session)
browser4-rest\src\main\kotlin\ai\platon\pulsar\rest\api\service\CrawlService.kt:229:                                   
 seedRequest.depth <= 1 -> crawlDepth1(taskId, seedRequest)
browser4-rest\src\main\kotlin\ai\platon\pulsar\rest\api\service\CrawlService.kt:258:                                   
     depth = 0
browser4-rest\src\main\kotlin\ai\platon\pulsar\rest\api\service\CrawlService.kt:348:        logger.info("Crawl task sub
mitted: {} seeds={} depth={}", taskId, seedUrls.size, request.depth)
browser4-rest\src\main\kotlin\ai\platon\pulsar\rest\api\service\CrawlService.kt:460:     * Fetch a single seed URL at d
epth=0 and optionally run X-SQL extraction.
browser4-rest\src\main\kotlin\ai\platon\pulsar\rest\api\service\CrawlService.kt:465:     *   multiple depth=0 seeds in 
sequence.
browser4-rest\src\main\kotlin\ai\platon\pulsar\rest\api\service\CrawlService.kt:503:                        url = reque
st.url, title = null, contentLength = 0, depth = 0,
browser4-rest\src\main\kotlin\ai\platon\pulsar\rest\api\service\CrawlService.kt:525:                    depth = 0,
browser4-rest\src\main\kotlin\ai\platon\pulsar\rest\api\service\CrawlService.kt:549:                        depth = 0
browser4-rest\src\main\kotlin\ai\platon\pulsar\rest\api\service\CrawlService.kt:560:            url = request.url, titl
e = null, contentLength = 0, depth = 0,
browser4-rest\src\main\kotlin\ai\platon\pulsar\rest\api\service\CrawlService.kt:580:                // auto-switch to d
epth=0 behavior (bulk fetch + extraction).  This prevents
browser4-rest\src\main\kotlin\ai\platon\pulsar\rest\api\service\CrawlService.kt:582:                // --depth 0 (which
 defaults to depth=1 link-discovery mode).
browser4-rest\src\main\kotlin\ai\platon\pulsar\rest\api\service\CrawlService.kt:586:                        "auto-switc
hing to depth=0 (bulk fetch + extraction mode)",
browser4-rest\src\main\kotlin\ai\platon\pulsar\rest\api\service\CrawlService.kt:673:                            depth =
 1,
browser4-rest\src\main\kotlin\ai\platon\pulsar\rest\api\service\CrawlService.kt:687:            withTimeout(300_000L) {
 // 5 minute timeout for depth=1
browser4-rest\src\main\kotlin\ai\platon\pulsar\rest\api\service\CrawlService.kt:693:            logger.warn("Crawl {}: 
depth=1 timed out after collecting {} pages; saving partial results", taskId, results.size)
browser4-rest\src\main\kotlin\ai\platon\pulsar\rest\api\service\CrawlService.kt:727:            val maxDepth = request.
depth


diff --git a/.test-sessions/ac1-listing-query.sql b/.test-sessions/ac1-listing-query.sql
new file mode 100644
index 0000000000000000000000000000000000000000..d651eec929ece8af65e1fdb818fac08d6e7ef4ea
--- /dev/null
+++ b/.test-sessions/ac1-listing-query.sql
@@ -0,0 +1,6 @@
+SELECT
+  DOM_FIRST_TEXT(DOM, 'div.product-title') AS title,
+  DOM_FIRST_TEXT(DOM, 'div.product-price') AS price,
+  DOM_FIRST_HREF(DOM, 'a.product-link') AS link
+FROM
+  DOM_LOAD_AND_SELECT(@url, '.product-card')
diff --git a/.test-sessions/ac2-product-query.sql b/.test-sessions/ac2-product-query.sql
new file mode 100644
index 0000000000000000000000000000000000000000..1fa45c1ec3c6f272620e6313938e1565b2d4239c
--- /dev/null
+++ b/.test-sessions/ac2-product-query.sql
@@ -0,0 +1,6 @@
+SELECT
+  DOM_BASE_URI(DOM) AS url,
+  DOM_FIRST_TEXT(DOM, '#productTitle') AS title,
+  DOM_FIRST_TEXT(DOM, '#product-price') AS price
+FROM
+  DOM_LOAD_AND_SELECT(@url, 'body')
diff --git a/.test-sessions/ac6-few-urls.ps1 b/.test-sessions/ac6-few-urls.ps1
new file mode 100644
index 0000000000000000000000000000000000000000..07a5a6936b1fab6f1574eb1c9627014aa6157e04
--- /dev/null
+++ b/.test-sessions/ac6-few-urls.ps1
@@ -0,0 +1,18 @@
+#!/usr/bin/env pwsh
+$ErrorActionPreference = 'Continue'
+
+$urls = @(
+    "http://localhost:18080/ec/dp/B0E000001",
+    "http://localhost:18080/ec/dp/B0E000004",
+    "http://localhost:18080/ec/dp/B0E000005"
+)
+
+$urls | ForEach-Object {
+    $u = $_
+    Write-Host "=== Processing: $u ==="
+    browser4-cli goto $u
+    browser4-cli htmlsnapshot
+    $title = browser4-cli htmlsnapshot get text "#productTitle"
+    Write-Host "Title: $title"
+    Start-Sleep -Seconds 2
+}
diff --git a/.test-sessions/run-loop.ps1 b/.test-sessions/run-loop.ps1
new file mode 100644
index 0000000000000000000000000000000000000000..a4da61b00ecf6843a91fe250ac4cb5afe8d6a8ec
--- /dev/null
+++ b/.test-sessions/run-loop.ps1
@@ -0,0 +1,4 @@
+#!/usr/bin/env pwsh
+$ErrorActionPreference = 'Continue'
+$out = Join-Path $PSScriptRoot 'loop-run.log'
+browser4-cli loop --name mock-price-watch --count 2 -i 10 -- -s price-watch eval "document.querySelector('#product-price').textContent.trim()" *> $out

exec
"C:\\Program Files\\PowerShell\\7\\pwsh.exe" -Command '$lines = Get-Content "browser4-rest'"\\src\\main\\kotlin\\ai\\platon\\pulsar\\rest\\api\\service\\CrawlService.kt\"; "'$lines[700..820] -join "`n"' in D:\workspace\Browser4\Browser4-4.13
 succeeded in 305ms:
                pages = results.toList(),
                error = "Crawl timed out after collecting ${results.size} pages (partial results saved)",
                startedTime = previous?.startedTime ?: java.time.Instant.now(),
                finishTime = java.time.Instant.now()
            )
            taskStore.put(taskId, timeout)
            onStatusChanged(timeout)
            throw e
        } finally {
            runCatching { session.close() }
        }
    }

    // ------------------------------------------------------------------
    // Depth>1: BFS continuous crawl using ParsableHyperlink parse handlers
    // ------------------------------------------------------------------

    private suspend fun crawlDepthN(taskId: String, request: CrawlRequest): List<CrawlPageResult> {
        // Use sequential browsers for continuous crawling (same as _5_ContinuousCrawler.kt)
        try { PulsarSettings.withSequentialBrowsers().maxOpenTabs(8) } catch (e: Exception) { /* optional config */ }

        val session = sessionManager.agenticContext.createSession()
        val results = Collections.synchronizedList(mutableListOf<CrawlPageResult>())
        try {
            val effectiveArgs = buildEffectiveArgs(request.args)
            val options = parseOptions(session, effectiveArgs)
            val maxDepth = request.depth
            val visited = ConcurrentHashMap.newKeySet<String>()

            // Per-crawl completion tracking: submitted count increments when new
            // links are submitted, completed count increments when parseHandler
            // finishes processing a page.  When they match the pool is drained.
            val submittedCount = AtomicInteger(1) // seed URL counts as submitted
            val completedCount = AtomicInteger(0)
            val allCompleted = CompletableDeferred<Unit>()

            // Use lateinit to allow recursive reference within the parse handler
            lateinit var parseHandler: (WebPage, FeaturedDocument) -> Any?

            parseHandler = { page: WebPage, document: FeaturedDocument ->
                val pageUrl = document.baseURI ?: page.url
                val currentDepth = extractDepth(page) ?: 1

                // Record this page
                val extractionResult = if (request.sql != null) {
                    executeSqlQuery(session, pageUrl, request.sql)
                } else Pair(null, null)
                results.add(
                    CrawlPageResult(
                        url = pageUrl,
                        title = document.title.takeIf { !it.isNullOrBlank() }
                            ?: extractTitleFromHtml(document.html),
                        contentLength = page.contentLength,
                        depth = currentDepth,
                        extracted = extractionResult.first,
                        extractionError = extractionResult.second
                    )
                )
                visited.add(normalizeForVisit(pageUrl))

                logger.debug("Crawl {}: depth={} page={}", taskId, currentDepth, pageUrl)

                // If we haven't reached max depth, extract and submit more links
                if (currentDepth < maxDepth) {
                    val selector = options.outLinkSelector
                    if (!selector.isNullOrBlank()) {
                        val allLinks = document.selectHyperlinks(selector)
                            .map { it.url }
                            .toList()
                        val (dupes, fresh) = allLinks.partition { link ->
                            normalizeForVisit(link) in visited
                        }
                        if (dupes.isNotEmpty()) {
                            logger.debug(
                                "Crawl {}: {} link(s) skipped — already visited (depth={})",
                                taskId, dupes.size, currentDepth
                            )
                        }
                        val newLinks = fresh
                            .filter { link -> matchesPattern(link, options.outLinkPattern) }
                            .take(options.topLinks)
                            .toList()

                        if (newLinks.isNotEmpty()) {
                            submittedCount.addAndGet(newLinks.size)
                            val args = buildArgsForDepth(options, currentDepth + 1)
                            newLinks.forEach { link ->
                                visited.add(normalizeForVisit(link))
                                val hyperlink = ParsableHyperlink("$link $args", parseHandler)
                                session.submit(hyperlink)
                            }
                            logger.debug(
                                "Crawl {}: submitted {} links at depth {}",
                                taskId,
                                newLinks.size,
                                currentDepth + 1
                            )
                        }
                    }
                }

                // Signal completion when all submitted pages have been processed
                if (completedCount.incrementAndGet() == submittedCount.get()) {
                    allCompleted.complete(Unit)
                }
            } // parseHandler defined

            // Submit the seed URL (depth 0 — it is the starting page).
            val seedArgs = buildArgsForDepth(options, 0)
            val seedHyperlink = ParsableHyperlink("${request.url} $seedArgs", parseHandler)
            session.submit(seedHyperlink)

            // Wait until the URL pool is drained (per-crawl completion, not global)
            val timeoutMs = (maxDepth * 300_000L).coerceAtMost(1_800_000L) // max 30 min
            withTimeout(timeoutMs) {
                allCompleted.await()
            }

            return results.toList()
        } catch (e: TimeoutCancellationException) {
            logger.warn("Crawl {}: depth>1 timed out after collecting {} pages; saving partial results", taskId, results.size)

diff --git a/.test-sessions/ac1-listing-query.sql b/.test-sessions/ac1-listing-query.sql
new file mode 100644
index 0000000000000000000000000000000000000000..d651eec929ece8af65e1fdb818fac08d6e7ef4ea
--- /dev/null
+++ b/.test-sessions/ac1-listing-query.sql
@@ -0,0 +1,6 @@
+SELECT
+  DOM_FIRST_TEXT(DOM, 'div.product-title') AS title,
+  DOM_FIRST_TEXT(DOM, 'div.product-price') AS price,
+  DOM_FIRST_HREF(DOM, 'a.product-link') AS link
+FROM
+  DOM_LOAD_AND_SELECT(@url, '.product-card')
diff --git a/.test-sessions/ac2-product-query.sql b/.test-sessions/ac2-product-query.sql
new file mode 100644
index 0000000000000000000000000000000000000000..1fa45c1ec3c6f272620e6313938e1565b2d4239c
--- /dev/null
+++ b/.test-sessions/ac2-product-query.sql
@@ -0,0 +1,6 @@
+SELECT
+  DOM_BASE_URI(DOM) AS url,
+  DOM_FIRST_TEXT(DOM, '#productTitle') AS title,
+  DOM_FIRST_TEXT(DOM, '#product-price') AS price
+FROM
+  DOM_LOAD_AND_SELECT(@url, 'body')
diff --git a/.test-sessions/ac6-few-urls.ps1 b/.test-sessions/ac6-few-urls.ps1
new file mode 100644
index 0000000000000000000000000000000000000000..07a5a6936b1fab6f1574eb1c9627014aa6157e04
--- /dev/null
+++ b/.test-sessions/ac6-few-urls.ps1
@@ -0,0 +1,18 @@
+#!/usr/bin/env pwsh
+$ErrorActionPreference = 'Continue'
+
+$urls = @(
+    "http://localhost:18080/ec/dp/B0E000001",
+    "http://localhost:18080/ec/dp/B0E000004",
+    "http://localhost:18080/ec/dp/B0E000005"
+)
+
+$urls | ForEach-Object {
+    $u = $_
+    Write-Host "=== Processing: $u ==="
+    browser4-cli goto $u
+    browser4-cli htmlsnapshot
+    $title = browser4-cli htmlsnapshot get text "#productTitle"
+    Write-Host "Title: $title"
+    Start-Sleep -Seconds 2
+}
diff --git a/.test-sessions/run-loop.ps1 b/.test-sessions/run-loop.ps1
new file mode 100644
index 0000000000000000000000000000000000000000..a4da61b00ecf6843a91fe250ac4cb5afe8d6a8ec
--- /dev/null
+++ b/.test-sessions/run-loop.ps1
@@ -0,0 +1,4 @@
+#!/usr/bin/env pwsh
+$ErrorActionPreference = 'Continue'
+$out = Join-Path $PSScriptRoot 'loop-run.log'
+browser4-cli loop --name mock-price-watch --count 2 -i 10 -- -s price-watch eval "document.querySelector('#product-price').textContent.trim()" *> $out

exec
"C:\\Program Files\\PowerShell\\7\\pwsh.exe" -Command "rg -n \"fun extractDepth|fun buildArgsForDepth|extractDepth\\(\" browser4-rest\\src\\main\\kotlin\\ai\\platon\\pulsar\\rest\\api\\service\\CrawlService.kt" in D:\workspace\Browser4\Browser4-4.13
 succeeded in 332ms:
742:                val currentDepth = extractDepth(page) ?: 1
1098:    private fun extractDepth(page: WebPage): Int? {
1107:    private fun buildArgsForDepth(options: LoadOptions, depth: Int): String {

diff --git a/.test-sessions/ac1-listing-query.sql b/.test-sessions/ac1-listing-query.sql
new file mode 100644
index 0000000000000000000000000000000000000000..d651eec929ece8af65e1fdb818fac08d6e7ef4ea
--- /dev/null
+++ b/.test-sessions/ac1-listing-query.sql
@@ -0,0 +1,6 @@
+SELECT
+  DOM_FIRST_TEXT(DOM, 'div.product-title') AS title,
+  DOM_FIRST_TEXT(DOM, 'div.product-price') AS price,
+  DOM_FIRST_HREF(DOM, 'a.product-link') AS link
+FROM
+  DOM_LOAD_AND_SELECT(@url, '.product-card')
diff --git a/.test-sessions/ac2-product-query.sql b/.test-sessions/ac2-product-query.sql
new file mode 100644
index 0000000000000000000000000000000000000000..1fa45c1ec3c6f272620e6313938e1565b2d4239c
--- /dev/null
+++ b/.test-sessions/ac2-product-query.sql
@@ -0,0 +1,6 @@
+SELECT
+  DOM_BASE_URI(DOM) AS url,
+  DOM_FIRST_TEXT(DOM, '#productTitle') AS title,
+  DOM_FIRST_TEXT(DOM, '#product-price') AS price
+FROM
+  DOM_LOAD_AND_SELECT(@url, 'body')
diff --git a/.test-sessions/ac6-few-urls.ps1 b/.test-sessions/ac6-few-urls.ps1
new file mode 100644
index 0000000000000000000000000000000000000000..07a5a6936b1fab6f1574eb1c9627014aa6157e04
--- /dev/null
+++ b/.test-sessions/ac6-few-urls.ps1
@@ -0,0 +1,18 @@
+#!/usr/bin/env pwsh
+$ErrorActionPreference = 'Continue'
+
+$urls = @(
+    "http://localhost:18080/ec/dp/B0E000001",
+    "http://localhost:18080/ec/dp/B0E000004",
+    "http://localhost:18080/ec/dp/B0E000005"
+)
+
+$urls | ForEach-Object {
+    $u = $_
+    Write-Host "=== Processing: $u ==="
+    browser4-cli goto $u
+    browser4-cli htmlsnapshot
+    $title = browser4-cli htmlsnapshot get text "#productTitle"
+    Write-Host "Title: $title"
+    Start-Sleep -Seconds 2
+}
diff --git a/.test-sessions/run-loop.ps1 b/.test-sessions/run-loop.ps1
new file mode 100644
index 0000000000000000000000000000000000000000..a4da61b00ecf6843a91fe250ac4cb5afe8d6a8ec
--- /dev/null
+++ b/.test-sessions/run-loop.ps1
@@ -0,0 +1,4 @@
+#!/usr/bin/env pwsh
+$ErrorActionPreference = 'Continue'
+$out = Join-Path $PSScriptRoot 'loop-run.log'
+browser4-cli loop --name mock-price-watch --count 2 -i 10 -- -s price-watch eval "document.querySelector('#product-price').textContent.trim()" *> $out

exec
"C:\\Program Files\\PowerShell\\7\\pwsh.exe" -Command '$lines = Get-Content "browser4-rest'"\\src\\main\\kotlin\\ai\\platon\\pulsar\\rest\\api\\service\\CrawlService.kt\"; "'$lines[1090..1130] -join "`n"' in D:\workspace\Browser4\Browser4-4.13
 succeeded in 319ms:

    private fun normalizeForVisit(url: String): String {
        return url.trim().lowercase()
            .removeSuffix("/")
            .substringBefore('?')  // strip query for dedup
    }

    private fun extractDepth(page: WebPage): Int? {
        // Depth is embedded as a synthetic option in the URL's args string.
        // `configuredUrl` carries the args (e.g. "https://... -depth 2 -parse"),
        // while `page.url` is the resolved URL without args.
        val url = page.configuredUrl
        val match = Regex("""-depth\s+(\d+)""").find(url)
        return match?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun buildArgsForDepth(options: LoadOptions, depth: Int): String {
        val parts = mutableListOf("-depth $depth -parse")
        if (options.outLinkSelector.isNotBlank()) {
            parts.add("-outLink \"${options.outLinkSelector}\"")
        }
        if (options.outLinkPattern.isNotBlank() && options.outLinkPattern != ".+") {
            parts.add("-outLinkPattern \"${options.outLinkPattern}\"")
        }
        if (options.refresh) parts.add("-refresh")
        return parts.joinToString(" ")
    }
}

diff --git a/.test-sessions/ac1-listing-query.sql b/.test-sessions/ac1-listing-query.sql
new file mode 100644
index 0000000000000000000000000000000000000000..d651eec929ece8af65e1fdb818fac08d6e7ef4ea
--- /dev/null
+++ b/.test-sessions/ac1-listing-query.sql
@@ -0,0 +1,6 @@
+SELECT
+  DOM_FIRST_TEXT(DOM, 'div.product-title') AS title,
+  DOM_FIRST_TEXT(DOM, 'div.product-price') AS price,
+  DOM_FIRST_HREF(DOM, 'a.product-link') AS link
+FROM
+  DOM_LOAD_AND_SELECT(@url, '.product-card')
diff --git a/.test-sessions/ac2-product-query.sql b/.test-sessions/ac2-product-query.sql
new file mode 100644
index 0000000000000000000000000000000000000000..1fa45c1ec3c6f272620e6313938e1565b2d4239c
--- /dev/null
+++ b/.test-sessions/ac2-product-query.sql
@@ -0,0 +1,6 @@
+SELECT
+  DOM_BASE_URI(DOM) AS url,
+  DOM_FIRST_TEXT(DOM, '#productTitle') AS title,
+  DOM_FIRST_TEXT(DOM, '#product-price') AS price
+FROM
+  DOM_LOAD_AND_SELECT(@url, 'body')
diff --git a/.test-sessions/ac6-few-urls.ps1 b/.test-sessions/ac6-few-urls.ps1
new file mode 100644
index 0000000000000000000000000000000000000000..07a5a6936b1fab6f1574eb1c9627014aa6157e04
--- /dev/null
+++ b/.test-sessions/ac6-few-urls.ps1
@@ -0,0 +1,18 @@
+#!/usr/bin/env pwsh
+$ErrorActionPreference = 'Continue'
+
+$urls = @(
+    "http://localhost:18080/ec/dp/B0E000001",
+    "http://localhost:18080/ec/dp/B0E000004",
+    "http://localhost:18080/ec/dp/B0E000005"
+)
+
+$urls | ForEach-Object {
+    $u = $_
+    Write-Host "=== Processing: $u ==="
+    browser4-cli goto $u
+    browser4-cli htmlsnapshot
+    $title = browser4-cli htmlsnapshot get text "#productTitle"
+    Write-Host "Title: $title"
+    Start-Sleep -Seconds 2
+}
diff --git a/.test-sessions/run-loop.ps1 b/.test-sessions/run-loop.ps1
new file mode 100644
index 0000000000000000000000000000000000000000..a4da61b00ecf6843a91fe250ac4cb5afe8d6a8ec
--- /dev/null
+++ b/.test-sessions/run-loop.ps1
@@ -0,0 +1,4 @@
+#!/usr/bin/env pwsh
+$ErrorActionPreference = 'Continue'
+$out = Join-Path $PSScriptRoot 'loop-run.log'
+browser4-cli loop --name mock-price-watch --count 2 -i 10 -- -s price-watch eval "document.querySelector('#product-price').textContent.trim()" *> $out

codex
The depth label is rendered straight from the backend's `page["depth"]`. Let me inspect the raw JSON of a crawl result to confirm what the backend actually reports.
exec
"C:\\Program Files\\PowerShell\\7\\pwsh.exe" -Command 'browser4-cli crawl result "08468cd8-3269-4c68-8233-6f95a90775f0" --json' in D:\workspace\Browser4\Browser4-4.13
 succeeded in 385ms:
{"status":"ok","command":"crawl-result","output":{"task_id":"08468cd8-3269-4c68-8233-6f95a90775f0","raw":{"createdAt":1786691772029,"finishTime":"2026-08-14T07:16:12.029636200Z","pages":[{"contentLength":7706,"depth":1,"title":"Crawl Test Hub","url":"http://localhost:18080/generated/crawl/index.html"},{"contentLength":6032,"depth":1,"title":"Widget Alpha — $10.00","url":"http://localhost:18080/generated/crawl/product/1.html"},{"contentLength":5982,"depth":1,"title":"Widget Beta — $20.00","url":"http://localhost:18080/generated/crawl/product/2.html"},{"contentLength":5933,"depth":1,"title":"Widget Gamma — $30.00","url":"http://localhost:18080/generated/crawl/product/3.html"},{"contentLength":3567,"depth":1,"title":"Widget Delta — $40.00","url":"http://localhost:18080/generated/crawl/product/4.html"},{"contentLength":4496,"depth":1,"title":"Widget Mu — $80.00","url":"http://localhost:18080/generated/crawl/product/8.html"},{"contentLength":3583,"depth":1,"title":"Widget Epsilon — $50.00","url":"http://localhost:18080/generated/crawl/product/5.html"},{"contentLength":3368,"depth":1,"title":"Widget Zeta — $60.00","url":"http://localhost:18080/generated/crawl/product/6.html"},{"contentLength":4467,"depth":1,"title":"Widget Nu — $90.00","url":"http://localhost:18080/generated/crawl/product/9.html"},{"contentLength":4541,"depth":1,"title":"Widget Lambda — $70.00","url":"http://localhost:18080/generated/crawl/product/7.html"}],"pagesFound":10,"seedStatuses":[{"pagesReturned":10,"status":"fetched","url":"http://localhost:18080/generated/crawl/index.html"}],"startedTime":"2026-08-14T07:15:11.836927600Z","status":"OK","taskId":"08468cd8-3269-4c68-8233-6f95a90775f0","taskTTLMinutes":60}}}

diff --git a/.test-sessions/ac1-listing-query.sql b/.test-sessions/ac1-listing-query.sql
new file mode 100644
index 0000000000000000000000000000000000000000..d651eec929ece8af65e1fdb818fac08d6e7ef4ea
--- /dev/null
+++ b/.test-sessions/ac1-listing-query.sql
@@ -0,0 +1,6 @@
+SELECT
+  DOM_FIRST_TEXT(DOM, 'div.product-title') AS title,
+  DOM_FIRST_TEXT(DOM, 'div.product-price') AS price,
+  DOM_FIRST_HREF(DOM, 'a.product-link') AS link
+FROM
+  DOM_LOAD_AND_SELECT(@url, '.product-card')
diff --git a/.test-sessions/ac2-product-query.sql b/.test-sessions/ac2-product-query.sql
new file mode 100644
index 0000000000000000000000000000000000000000..1fa45c1ec3c6f272620e6313938e1565b2d4239c
--- /dev/null
+++ b/.test-sessions/ac2-product-query.sql
@@ -0,0 +1,6 @@
+SELECT
+  DOM_BASE_URI(DOM) AS url,
+  DOM_FIRST_TEXT(DOM, '#productTitle') AS title,
+  DOM_FIRST_TEXT(DOM, '#product-price') AS price
+FROM
+  DOM_LOAD_AND_SELECT(@url, 'body')
diff --git a/.test-sessions/ac6-few-urls.ps1 b/.test-sessions/ac6-few-urls.ps1
new file mode 100644
index 0000000000000000000000000000000000000000..07a5a6936b1fab6f1574eb1c9627014aa6157e04
--- /dev/null
+++ b/.test-sessions/ac6-few-urls.ps1
@@ -0,0 +1,18 @@
+#!/usr/bin/env pwsh
+$ErrorActionPreference = 'Continue'
+
+$urls = @(
+    "http://localhost:18080/ec/dp/B0E000001",
+    "http://localhost:18080/ec/dp/B0E000004",
+    "http://localhost:18080/ec/dp/B0E000005"
+)
+
+$urls | ForEach-Object {
+    $u = $_
+    Write-Host "=== Processing: $u ==="
+    browser4-cli goto $u
+    browser4-cli htmlsnapshot
+    $title = browser4-cli htmlsnapshot get text "#productTitle"
+    Write-Host "Title: $title"
+    Start-Sleep -Seconds 2
+}
diff --git a/.test-sessions/run-loop.ps1 b/.test-sessions/run-loop.ps1
new file mode 100644
index 0000000000000000000000000000000000000000..a4da61b00ecf6843a91fe250ac4cb5afe8d6a8ec
--- /dev/null
+++ b/.test-sessions/run-loop.ps1
@@ -0,0 +1,4 @@
+#!/usr/bin/env pwsh
+$ErrorActionPreference = 'Continue'
+$out = Join-Path $PSScriptRoot 'loop-run.log'
+browser4-cli loop --name mock-price-watch --count 2 -i 10 -- -s price-watch eval "document.querySelector('#product-price').textContent.trim()" *> $out

codex
Confirmed: the backend reports `depth:1` for every page — including the seed and depth-2 pages — so the depth column is misreported. Let me finish with quick help-output checks for the bulk commands (discoverability assessment).
exec
"C:\\Program Files\\PowerShell\\7\\pwsh.exe" -Command 'browser4-cli help crawl' in D:\workspace\Browser4\Browser4-4.13
exec
"C:\\Program Files\\PowerShell\\7\\pwsh.exe" -Command 'browser4-cli help loop; browser4-cli help "swarm query"' in D:\workspace\Browser4\Browser4-4.13
 succeeded in 305ms:
browser4-cli crawl [url]

Crawl a website starting from a URL or seed file, following links up to a configurable depth. Use --seed-file for bulk
URL lists, --sql for X-SQL data extraction from crawled pages.

Arguments:
  [url]                       The starting URL for the crawl (omit if using --seed-file)
Options:
  -d, --depth                 Maximum crawl depth (default: 1). Use 0 to fetch pages without link discovery.
  --seed-file                 File containing URLs to crawl, one per line (lines starting with # are ignored)
  --sql                       X-SQL query to extract structured data from each crawled page. Use @url as the page URL
                              placeholder. Prefix with @ to read from file (e.g. --sql @query.sql)
  --sql-stdin                 Read X-SQL query from stdin (avoids shell quoting issues on Windows)
  --sql-base64                Base64-encoded X-SQL query (avoid shell quoting issues on Windows)
  --format                    Output format for X-SQL extracted data (requires --sql): json, csv, or table (default:
                              table). Has no effect without --sql.
  -o, --output                Write results to a file instead of stdout
  -ol, --out-link-selector    CSS selector to extract links from each page
  -olp, --out-link-pattern    Regex pattern to filter extracted links (default: .+)
  -tl, --top-links            Maximum links to extract per page (default: 20)
  -a, --args                  Additional LoadOptions passthrough. Prefix with @ to read from file (e.g. -a
                              @loadopts.txt). Use --args-stdin to pipe from stdin.
  --args-stdin                Read LoadOptions args from stdin (avoids shell quoting issues on Windows)
  --refresh                   Force a fresh fetch, ignoring cache
  --parse                     Parse each page immediately after fetching
  --expires                   Cache expiration duration (e.g. 1d, 1h, 30m)
  -p, --priority              Queue priority (lower = higher priority)
  --page-load-timeout         Maximum time to wait for page load
  --ignore-url-query          Remove query parameters from URLs during normalization
  --no-norm                   Disable URL normalization
  --readonly                  Non-destructive mode (no page modifications)
  -bg, --background           Submit crawl and return immediately; use 'crawl list' to track progress
  --verbose                   Show per-URL processing status in crawl results
Modes:
  Link discovery (depth >= 1): start from a seed URL, follow links up to N levels.
  Bulk fetch (depth 0): load each URL from --seed-file directly, no link discovery.
  X-SQL extraction (--sql): run a query against each crawled page and format results.

Notes:
  - Provide a positional URL or --seed-file (or both) — at least one is required.
  - --depth (-d) controls how many levels of links to follow (default: 1). Use 0 to skip link discovery.
  - --out-link-selector (-ol) specifies a CSS selector to extract links. Required for link discovery;
    without it, only seed URLs are processed regardless of depth.
  - --out-link-pattern (-olp) filters extracted links with a regex (default: .+).
  - --top-links (-tl) limits the number of links extracted per page (default: 20).
  - --sql accepts inline X-SQL, a file path with @ prefix (e.g. --sql @query.sql), or stdin via --sql-stdin.
  - --format controls output: 'table' (default, aligned columns), 'csv', or 'json'.
  - --output (-o) writes results to a file instead of stdout.
  - Boolean flags --refresh, --parse, --ignore-url-query, --no-norm, --readonly control fetch behavior.
  - --args (-a) passes additional LoadOptions through as a raw string (e.g. -a "-nMaxRetry 5").
  - --expires sets cache expiration (e.g. 1d, 1h, 30m), --priority sets queue priority (lower = higher priority).

Examples:
  browser4-cli crawl https://example.com
  browser4-cli crawl https://example.com -d 2 -ol "a.product" -olp "/product/"
  browser4-cli crawl https://example.com --depth 3 --refresh
  browser4-cli crawl --seed-file urls.txt --depth 0 --refresh
  browser4-cli crawl --seed-file urls.txt --sql @extract.sql --format csv -o results.csv
  browser4-cli crawl --seed-file urls.txt --sql-stdin --format table < query.sql

 succeeded in 376ms:
browser4-cli loop [task]

Execute a task repeatedly on an interval. Supports plain text commands, x-sql queries (auto-detected by the server),
browser4-cli subcommands (after --), and shell commands (--shell). Progress is persisted to disk under a configurable
--name and can be resumed after interruption. Use --pause [--name] to pause a running loop (or combine --pause with a
task to start in paused state), --resume [--name] to resume, --pause-all/--resume-all/--stop-all for bulk operations,
--list to see all loops, --status [--name] to inspect, --stop [--name] to clear.

Arguments:
  [task]                      The task to execute, use -- for a browser4-cli subcommand, --shell for a shell command, or
                              pass plain text/x-sql directly. Omit for control operations (--list, --stop, --status,
                              --resume, etc.)
Options:
  --name                      Loop name for persistence (default: default). Named loops are stored in
                              ~/.browser4/loops/<name>.json. Only letters, digits, dots, hyphens, and underscores
                              allowed.
  -i, --interval              Seconds between iterations (default: 3600 = 1 hour)
  -n, --count                 Number of iterations before stopping (default: infinite)
  -t, --timeout               Maximum total duration in seconds (default: 604800 = 1 week)
  --shell                     Execute the task as a shell command (cmd /C or sh -c)
  --list                      List all persisted loops
  --pause                     Without a task: pause a running loop at the next iteration boundary. With a task: create
                              the loop in paused state (use --resume then re-run to start execution). Optionally specify
                              --name to target a named loop.
  --resume                    Resume a paused loop (control op — cannot be combined with a task). Optionally specify
                              --name to target a named loop.
  --pause-all                 Pause all running loops at once
  --resume-all                Resume all paused loops at once
  --stop                      Stop a loop and clear its persisted state. Optionally specify --name to target a named
                              loop.
  --stop-all                  Stop and clear all persisted loops at once
  --status                    Show loop state and progress. Optionally specify --name to target a named loop.
  --history                   Show recently completed loops (up to 200 most recent)
  --keep-state                Preserve the loop state file after normal completion (by default it is auto-cleaned)
Notes:
  - Three task modes: plain text (requires a configured LLM provider), --shell
    (runs a shell command each iteration), and -- (browser4-cli subcommand, for
    multi-token subcommands like batch).
  - Progress is persisted to disk under a configurable --name (default: default).
    Loops survive interruption and can be resumed.
  - Use --history to see recently completed loops (up to 200 most recent).
  - Use --pause combined with a task to create a loop that starts in paused state.

Examples:
  # Plain-text task (requires LLM backend)
  browser4-cli loop "check if the login page loads" --interval 300
  # Shell command, run every 60 seconds
  browser4-cli loop --shell "curl -s https://api.example.com/health" --interval 60
  # Browser4 CLI subcommand (use -- separator for multi-token commands)
  browser4-cli loop --count 5 --interval 10 -- status
  # Multi-token subcommand: batch a sequence of browser operations
  browser4-cli loop --count 3 --interval 30 -- batch "goto https://example.com" snapshot status
  # Run 10 times then stop
  browser4-cli loop --count 10 --interval 3600 --shell "backup.sh"
  # Lifecycle: pause, resume, stop a named loop
  browser4-cli loop --pause --name my-loop
  browser4-cli loop --resume --name my-loop
  browser4-cli loop --stop --name my-loop
  # List all active loops and view history
  browser4-cli loop --list
  browser4-cli loop --history

Modes:
  - Plain text: the task string is submitted to the Browser4 server as-is.
    X-SQL queries are auto-detected by the server.
  - --shell: the task is executed via the OS shell (cmd /C or sh -c).
  - -- (double dash): everything after -- is executed as a browser4-cli subcommand.

Persistence & Control:
  - Progress is saved to ~/.browser4/loop-state.json (default) or
    ~/.browser4/loops/<name>.json (named loops) after each iteration.
  - If the process is interrupted (Ctrl+C), the loop can be resumed by running
    the same command again. State is auto-cleared on normal completion.
  - Use --stop to clear persisted state, --status to inspect it.
  - Use --name <n> to run multiple independent loops (name: only letters,
    digits, dots, hyphens, underscores). Use --list to see all loops.
  - Use --pause to suspend a running loop (control op) or combine --pause with
    a task to start in paused state. Use --resume to continue a paused loop.
  - Use --pause-all / --resume-all to control all loops at once.
  - Use --stop-all to stop and clear all persisted loops.

Notes:
  - The interval is measured from the start of each iteration.
  - If an iteration takes longer than the interval, the next starts immediately.
  - Errors during an iteration are logged but the loop continues.
  - The timeout is checked at the start of each iteration; a long-running
    iteration may exceed the timeout.
  - Defaults: 1 hour interval, 1 week timeout. Use --count, --interval,
    or --timeout to adjust.

Examples:
  browser4-cli loop "load https://example.com and extract the page title"
  browser4-cli loop --shell "curl -s https://api.example.com/health" -i 60 -n 10
  browser4-cli loop -- eval "document.title" -i 300
  browser4-cli loop "select dom.title from DOM_LOAD_AND_SELECT('https://example.com')" --count 5
  browser4-cli loop --list
  browser4-cli loop --status
  browser4-cli loop --status --name my-loop
  browser4-cli loop --pause --name my-loop
  browser4-cli loop --pause --shell "echo hi" -i 60   (start paused)
  browser4-cli loop --resume --name my-loop
  browser4-cli loop --stop --name my-loop
swarm query subcommands:

  swarm query [url] --sql <query> [--seed-file <file>] [--wait]  Submit an X-SQL query to extract structured data from a
                                loaded webpage

diff --git a/.test-sessions/ac1-listing-query.sql b/.test-sessions/ac1-listing-query.sql
new file mode 100644
index 0000000000000000000000000000000000000000..d651eec929ece8af65e1fdb818fac08d6e7ef4ea
--- /dev/null
+++ b/.test-sessions/ac1-listing-query.sql
@@ -0,0 +1,6 @@
+SELECT
+  DOM_FIRST_TEXT(DOM, 'div.product-title') AS title,
+  DOM_FIRST_TEXT(DOM, 'div.product-price') AS price,
+  DOM_FIRST_HREF(DOM, 'a.product-link') AS link
+FROM
+  DOM_LOAD_AND_SELECT(@url, '.product-card')
diff --git a/.test-sessions/ac2-product-query.sql b/.test-sessions/ac2-product-query.sql
new file mode 100644
index 0000000000000000000000000000000000000000..1fa45c1ec3c6f272620e6313938e1565b2d4239c
--- /dev/null
+++ b/.test-sessions/ac2-product-query.sql
@@ -0,0 +1,6 @@
+SELECT
+  DOM_BASE_URI(DOM) AS url,
+  DOM_FIRST_TEXT(DOM, '#productTitle') AS title,
+  DOM_FIRST_TEXT(DOM, '#product-price') AS price
+FROM
+  DOM_LOAD_AND_SELECT(@url, 'body')
diff --git a/.test-sessions/ac6-few-urls.ps1 b/.test-sessions/ac6-few-urls.ps1
new file mode 100644
index 0000000000000000000000000000000000000000..07a5a6936b1fab6f1574eb1c9627014aa6157e04
--- /dev/null
+++ b/.test-sessions/ac6-few-urls.ps1
@@ -0,0 +1,18 @@
+#!/usr/bin/env pwsh
+$ErrorActionPreference = 'Continue'
+
+$urls = @(
+    "http://localhost:18080/ec/dp/B0E000001",
+    "http://localhost:18080/ec/dp/B0E000004",
+    "http://localhost:18080/ec/dp/B0E000005"
+)
+
+$urls | ForEach-Object {
+    $u = $_
+    Write-Host "=== Processing: $u ==="
+    browser4-cli goto $u
+    browser4-cli htmlsnapshot
+    $title = browser4-cli htmlsnapshot get text "#productTitle"
+    Write-Host "Title: $title"
+    Start-Sleep -Seconds 2
+}
diff --git a/.test-sessions/run-loop.ps1 b/.test-sessions/run-loop.ps1
new file mode 100644
index 0000000000000000000000000000000000000000..a4da61b00ecf6843a91fe250ac4cb5afe8d6a8ec
--- /dev/null
+++ b/.test-sessions/run-loop.ps1
@@ -0,0 +1,4 @@
+#!/usr/bin/env pwsh
+$ErrorActionPreference = 'Continue'
+$out = Join-Path $PSScriptRoot 'loop-run.log'
+browser4-cli loop --name mock-price-watch --count 2 -i 10 -- -s price-watch eval "document.querySelector('#product-price').textContent.trim()" *> $out

codex
Help docs for all bulk commands are thorough. Before wrapping up, let me run one quick repeat of the swarm scenario to see whether the first-job-lags behavior is a pattern or a one-off, then clean up sessions.
exec
"C:\\Program Files\\PowerShell\\7\\pwsh.exe" -Command 'browser4-cli swarm create --display-mode HEADLESS --max-browser-contexts 2 --max-open-tabs 4 --clear-stale; browser4-cli swarm query --sql "@.test-sessions/ac2-product-query.sql" --seed-file ".test-sessions/seed-urls.txt" --refresh' in D:\workspace\Browser4\Browser4-4.13
 succeeded in 453ms:
Cleared 3 stale swarm task(s) from prior sessions.
Swarm session created: SWARM
Query Submitted: http://localhost:18080/ec/dp/B0E000001 -> Task ID: 8ba716a8-d28e-49d0-99df-ab3b339aa337
Query Submitted: http://localhost:18080/ec/dp/B0E000002 -> Task ID: a91fae7b-f30e-43b5-925d-e78468c170dc
Query Submitted: http://localhost:18080/ec/dp/B0E000003 -> Task ID: 47f7fb9b-8f77-4f64-b4fb-4dada5786091
3 URL(s) queried. Use 'browser4-cli swarm list' to view all tracked tasks.

💡 Tip: Add --wait to track progress and see results automatically.
Without --wait, use these commands to monitor:
  swarm status <task-id>  — check if a job is done
  swarm result <task-id>  — get the result when complete
  swarm list             — see all tracked tasks
   If jobs appear stuck, run `swarm list` to check their status,
or `swarm list --clear` to remove stale entries from prior sessions.

diff --git a/.test-sessions/ac1-listing-query.sql b/.test-sessions/ac1-listing-query.sql
new file mode 100644
index 0000000000000000000000000000000000000000..d651eec929ece8af65e1fdb818fac08d6e7ef4ea
--- /dev/null
+++ b/.test-sessions/ac1-listing-query.sql
@@ -0,0 +1,6 @@
+SELECT
+  DOM_FIRST_TEXT(DOM, 'div.product-title') AS title,
+  DOM_FIRST_TEXT(DOM, 'div.product-price') AS price,
+  DOM_FIRST_HREF(DOM, 'a.product-link') AS link
+FROM
+  DOM_LOAD_AND_SELECT(@url, '.product-card')
diff --git a/.test-sessions/ac2-product-query.sql b/.test-sessions/ac2-product-query.sql
new file mode 100644
index 0000000000000000000000000000000000000000..1fa45c1ec3c6f272620e6313938e1565b2d4239c
--- /dev/null
+++ b/.test-sessions/ac2-product-query.sql
@@ -0,0 +1,6 @@
+SELECT
+  DOM_BASE_URI(DOM) AS url,
+  DOM_FIRST_TEXT(DOM, '#productTitle') AS title,
+  DOM_FIRST_TEXT(DOM, '#product-price') AS price
+FROM
+  DOM_LOAD_AND_SELECT(@url, 'body')
diff --git a/.test-sessions/ac6-few-urls.ps1 b/.test-sessions/ac6-few-urls.ps1
new file mode 100644
index 0000000000000000000000000000000000000000..07a5a6936b1fab6f1574eb1c9627014aa6157e04
--- /dev/null
+++ b/.test-sessions/ac6-few-urls.ps1
@@ -0,0 +1,18 @@
+#!/usr/bin/env pwsh
+$ErrorActionPreference = 'Continue'
+
+$urls = @(
+    "http://localhost:18080/ec/dp/B0E000001",
+    "http://localhost:18080/ec/dp/B0E000004",
+    "http://localhost:18080/ec/dp/B0E000005"
+)
+
+$urls | ForEach-Object {
+    $u = $_
+    Write-Host "=== Processing: $u ==="
+    browser4-cli goto $u
+    browser4-cli htmlsnapshot
+    $title = browser4-cli htmlsnapshot get text "#productTitle"
+    Write-Host "Title: $title"
+    Start-Sleep -Seconds 2
+}
diff --git a/.test-sessions/run-loop.ps1 b/.test-sessions/run-loop.ps1
new file mode 100644
index 0000000000000000000000000000000000000000..a4da61b00ecf6843a91fe250ac4cb5afe8d6a8ec
--- /dev/null
+++ b/.test-sessions/run-loop.ps1
@@ -0,0 +1,4 @@
+#!/usr/bin/env pwsh
+$ErrorActionPreference = 'Continue'
+$out = Join-Path $PSScriptRoot 'loop-run.log'
+browser4-cli loop --name mock-price-watch --count 2 -i 10 -- -s price-watch eval "document.querySelector('#product-price').textContent.trim()" *> $out

exec
"C:\\Program Files\\PowerShell\\7\\pwsh.exe" -Command 'Start-Sleep -Seconds 15; browser4-cli swarm list; browser4-cli swarm status "8ba716a8-d28e-49d0-99df-ab3b339aa337"' in D:\workspace\Browser4\Browser4-4.13
 succeeded in 15424ms:
Status: 3 total, 3 completed
3 tracked task(s) (showing 1-3):

  TASK ID       COMMAND      DESCRIPTION                             STARTED              FINISHED             STATUS   
  ------------  -----------  --------------------------------------  -------------------  -------------------  ---------
  8ba716a8-d28e-49d0-99df-ab3b339aa337  swarm-query  http://localhost:18080/ec/dp/B0E000001  2026-08-14 15:22:27  2026-08-14 15:22:27  completed
  47f7fb9b-8f77-4f64-b4fb-4dada5786091  swarm-query  http://localhost:18080/ec/dp/B0E000003  2026-08-14 15:22:16  2026-08-14 15:22:34  completed
  a91fae7b-f30e-43b5-925d-e78468c170dc  swarm-query  http://localhost:18080/ec/dp/B0E000002  2026-08-14 15:22:16  2026-08-14 15:22:34  completed
{
  "id": "8ba716a8-d28e-49d0-99df-ab3b339aa337",
  "isDone": true,
  "statusCode": 200,
  "status": "OK",
  "lifecycleState": "completed",
  "message": "",
  "lastModifiedTime": "2026-08-14T07:22:27.829938100Z"
}

diff --git a/.test-sessions/ac1-listing-query.sql b/.test-sessions/ac1-listing-query.sql
new file mode 100644
index 0000000000000000000000000000000000000000..d651eec929ece8af65e1fdb818fac08d6e7ef4ea
--- /dev/null
+++ b/.test-sessions/ac1-listing-query.sql
@@ -0,0 +1,6 @@
+SELECT
+  DOM_FIRST_TEXT(DOM, 'div.product-title') AS title,
+  DOM_FIRST_TEXT(DOM, 'div.product-price') AS price,
+  DOM_FIRST_HREF(DOM, 'a.product-link') AS link
+FROM
+  DOM_LOAD_AND_SELECT(@url, '.product-card')
diff --git a/.test-sessions/ac2-product-query.sql b/.test-sessions/ac2-product-query.sql
new file mode 100644
index 0000000000000000000000000000000000000000..1fa45c1ec3c6f272620e6313938e1565b2d4239c
--- /dev/null
+++ b/.test-sessions/ac2-product-query.sql
@@ -0,0 +1,6 @@
+SELECT
+  DOM_BASE_URI(DOM) AS url,
+  DOM_FIRST_TEXT(DOM, '#productTitle') AS title,
+  DOM_FIRST_TEXT(DOM, '#product-price') AS price
+FROM
+  DOM_LOAD_AND_SELECT(@url, 'body')
diff --git a/.test-sessions/ac6-few-urls.ps1 b/.test-sessions/ac6-few-urls.ps1
new file mode 100644
index 0000000000000000000000000000000000000000..07a5a6936b1fab6f1574eb1c9627014aa6157e04
--- /dev/null
+++ b/.test-sessions/ac6-few-urls.ps1
@@ -0,0 +1,18 @@
+#!/usr/bin/env pwsh
+$ErrorActionPreference = 'Continue'
+
+$urls = @(
+    "http://localhost:18080/ec/dp/B0E000001",
+    "http://localhost:18080/ec/dp/B0E000004",
+    "http://localhost:18080/ec/dp/B0E000005"
+)
+
+$urls | ForEach-Object {
+    $u = $_
+    Write-Host "=== Processing: $u ==="
+    browser4-cli goto $u
+    browser4-cli htmlsnapshot
+    $title = browser4-cli htmlsnapshot get text "#productTitle"
+    Write-Host "Title: $title"
+    Start-Sleep -Seconds 2
+}
diff --git a/.test-sessions/run-loop.ps1 b/.test-sessions/run-loop.ps1
new file mode 100644
index 0000000000000000000000000000000000000000..a4da61b00ecf6843a91fe250ac4cb5afe8d6a8ec
--- /dev/null
+++ b/.test-sessions/run-loop.ps1
@@ -0,0 +1,4 @@
+#!/usr/bin/env pwsh
+$ErrorActionPreference = 'Continue'
+$out = Join-Path $PSScriptRoot 'loop-run.log'
+browser4-cli loop --name mock-price-watch --count 2 -i 10 -- -s price-watch eval "document.querySelector('#product-price').textContent.trim()" *> $out

codex
Second run shows different per-task timing (first task fast, others slower) — so the earlier lag was scheduling variability, not a stuck job. Fetching results to confirm, then cleaning up.
exec
"C:\\Program Files\\PowerShell\\7\\pwsh.exe" -Command 'browser4-cli swarm result "8ba716a8-d28e-49d0-99df-ab3b339aa337"; browser4-cli swarm close' in D:\workspace\Browser4\Browser4-4.13
 succeeded in 388ms:
{
  "id": "8ba716a8-d28e-49d0-99df-ab3b339aa337",
  "resultSet": [
    {
      "url": "http://localhost:18080/ec/dp/B0E000001",
      "title": "4K OLED TV 55",
      "price": "$899.99"
    }
  ],
  "pageContentBytes": 15297,
  "error": null
}
Swarm session closed. Browser terminated. 3 tracked task(s) retained for history. Use `swarm list --clear` to remove.

diff --git a/.test-sessions/ac1-listing-query.sql b/.test-sessions/ac1-listing-query.sql
new file mode 100644
index 0000000000000000000000000000000000000000..d651eec929ece8af65e1fdb818fac08d6e7ef4ea
--- /dev/null
+++ b/.test-sessions/ac1-listing-query.sql
@@ -0,0 +1,6 @@
+SELECT
+  DOM_FIRST_TEXT(DOM, 'div.product-title') AS title,
+  DOM_FIRST_TEXT(DOM, 'div.product-price') AS price,
+  DOM_FIRST_HREF(DOM, 'a.product-link') AS link
+FROM
+  DOM_LOAD_AND_SELECT(@url, '.product-card')
diff --git a/.test-sessions/ac2-product-query.sql b/.test-sessions/ac2-product-query.sql
new file mode 100644
index 0000000000000000000000000000000000000000..1fa45c1ec3c6f272620e6313938e1565b2d4239c
--- /dev/null
+++ b/.test-sessions/ac2-product-query.sql
@@ -0,0 +1,6 @@
+SELECT
+  DOM_BASE_URI(DOM) AS url,
+  DOM_FIRST_TEXT(DOM, '#productTitle') AS title,
+  DOM_FIRST_TEXT(DOM, '#product-price') AS price
+FROM
+  DOM_LOAD_AND_SELECT(@url, 'body')
diff --git a/.test-sessions/ac6-few-urls.ps1 b/.test-sessions/ac6-few-urls.ps1
new file mode 100644
index 0000000000000000000000000000000000000000..07a5a6936b1fab6f1574eb1c9627014aa6157e04
--- /dev/null
+++ b/.test-sessions/ac6-few-urls.ps1
@@ -0,0 +1,18 @@
+#!/usr/bin/env pwsh
+$ErrorActionPreference = 'Continue'
+
+$urls = @(
+    "http://localhost:18080/ec/dp/B0E000001",
+    "http://localhost:18080/ec/dp/B0E000004",
+    "http://localhost:18080/ec/dp/B0E000005"
+)
+
+$urls | ForEach-Object {
+    $u = $_
+    Write-Host "=== Processing: $u ==="
+    browser4-cli goto $u
+    browser4-cli htmlsnapshot
+    $title = browser4-cli htmlsnapshot get text "#productTitle"
+    Write-Host "Title: $title"
+    Start-Sleep -Seconds 2
+}
diff --git a/.test-sessions/run-loop.ps1 b/.test-sessions/run-loop.ps1
new file mode 100644
index 0000000000000000000000000000000000000000..a4da61b00ecf6843a91fe250ac4cb5afe8d6a8ec
--- /dev/null
+++ b/.test-sessions/run-loop.ps1
@@ -0,0 +1,4 @@
+#!/usr/bin/env pwsh
+$ErrorActionPreference = 'Continue'
+$out = Join-Path $PSScriptRoot 'loop-run.log'
+browser4-cli loop --name mock-price-watch --count 2 -i 10 -- -s price-watch eval "document.querySelector('#product-price').textContent.trim()" *> $out

codex
All six ACs verified. Cleaning up browser sessions and confirming the temp files are all under `.test-sessions/`.
exec
"C:\\Program Files\\PowerShell\\7\\pwsh.exe" -Command 'browser4-cli close-all; browser4-cli loop --list' in D:\workspace\Browser4\Browser4-4.13
exec
"C:\\Program Files\\PowerShell\\7\\pwsh.exe" -Command 'Get-ChildItem ".test-sessions" -Force | Select-Object Name, Length, LastWriteTime | Format-Table -AutoSize' in D:\workspace\Browser4\Browser4-4.13
 succeeded in 371ms:

Name                                           Length LastWriteTime
----                                           ------ -------------
20260803T0953591155278Z                               2026/8/3 17:53:59
20260804T1710366874656Z                               2026/8/5 1:10:38
20260805T0624304318405Z                               2026/8/5 14:24:36
20260805T0630532772339Z                               2026/8/5 14:30:53
20260805T0643347491550Z                               2026/8/5 14:43:35
20260805T0714314057976Z                               2026/8/5 15:14:33
20260805T0826244082413Z                               2026/8/5 16:26:26
20260805T1009588287496Z                               2026/8/5 18:09:59
20260805T1054229557379Z                               2026/8/5 18:54:23
20260805T1130227506979Z                               2026/8/5 19:30:23
20260805T1217475283626Z                               2026/8/5 20:17:48
20260805T1434455379916Z                               2026/8/5 22:34:52
20260805T1512279248047Z                               2026/8/5 23:12:28
20260805T1547407922419Z                               2026/8/5 23:47:41
20260805T1639102532261Z                               2026/8/6 0:39:16
20260805T1721020576872Z                               2026/8/6 1:21:08
20260805T1801540215081Z                               2026/8/6 2:01:54
20260805T1846291441118Z                               2026/8/6 2:46:29
20260805T1920197869123Z                               2026/8/6 3:20:20
20260805T1952396591576Z                               2026/8/6 3:52:40
20260805T2007209368139Z                               2026/8/6 4:07:21
20260806T0157282751503Z                               2026/8/6 9:57:34
20260806T0211275287376Z                               2026/8/6 10:11:27
20260806T0249562606879Z                               2026/8/6 10:49:56
20260806T0422090745368Z                               2026/8/6 12:22:09
20260807T0707300757870Z                               2026/8/7 15:07:37
20260812T1049552554327Z                               2026/8/12 18:49:56
20260812T1057020117461Z                               2026/8/12 18:57:02
20260812T1514027835762Z                               2026/8/12 23:14:02
20260812T2136584939719Z                               2026/8/13 5:36:58
20260813T0933457360058Z                               2026/8/13 17:33:46
20260814T0633359863870Z                               2026/8/14 14:33:41
20260814T0634433346978Z                               2026/8/14 14:34:43
html-corpus                                           2026/8/13 17:31:25
html-corpus-ml-output                                 2026/8/5 3:23:26
webminer-input                                        2026/8/13 17:29:47
ac1-list.sql                                   211    2026/8/13 16:38:54
ac1-listing-query.sql                          211    2026/8/14 15:14:28
ac1-listing.html                               10854  2026/8/14 14:57:24
ac1-query.sql                                  243    2026/8/14 15:03:05
ac2-detail.sql                                 172    2026/8/13 16:38:54
ac2-product-query.sql                          174    2026/8/14 15:14:28
ac2-production-scale-decision.md               1397   2026/8/13 17:30:12
ac2-query.sql                                  172    2026/8/14 15:03:15
ac2-seed.txt                                   117    2026/8/13 16:39:02
ac2-seeds.txt                                  117    2026/8/5 1:35:03
ac3-query.sql                                  172    2026/8/5 1:40:00
ac3-seeds.txt                                  162    2026/8/5 1:39:03
ac4-final.sql                                  244    2026/8/13 17:11:17
ac4-products.sql                               244    2026/8/5 2:40:41
ac4-query-article.sql                          149    2026/8/13 17:10:35
ac4-query-attr.sql                             300    2026/8/13 17:09:25
ac4-query-div.sql                              243    2026/8/13 17:08:43
ac4-query.sql                                  240    2026/8/13 17:08:43
ac4-seeds.txt                                  234    2026/8/13 17:25:30
ac5-eval.js                                    388    2026/8/13 17:11:59
ac5-query.sql                                  172    2026/8/13 17:29:06
ac6-few-urls.ps1                               464    2026/8/14 15:18:12
ac6-urls.txt                                   117    2026/8/5 1:54:33
ac7-detail.sql                                 181    2026/8/5 2:43:52
ac7-query.sql                                  172    2026/8/13 17:14:33
ac7-seed-urls.txt                              195    2026/8/5 2:43:03
ac7-seeds.txt                                  156    2026/8/13 17:14:33
attr-selector.sql                              186    2026/8/5 2:24:20
browser4-io-SKILL.md                           45813  2026/8/14 15:13:38
browser4-session-state.json                    118    2026/8/5 2:01:32
check-jar.ps1                                  1028   2026/8/14 15:06:58
check-json.py                                  763    2026/8/13 18:48:33
count-products.js                              50     2026/8/5 2:00:07
debug-card.js                                  525    2026/8/12 23:11:14
debug-query.sql                                221    2026/8/5 3:10:14
direct.sql                                     158    2026/8/5 2:24:10
electronics-listing-electronics-6-products.png 532041 2026/8/5 2:00:40
evaluation-report.md                           2644   2026/8/5 2:54:32
extract-amazon.js                              840    2026/8/12 23:10:36
extract-amazon2.js                             773    2026/8/12 23:10:59
extract-final.js                               773    2026/8/12 23:11:31
extract-fresh.sql                              172    2026/8/5 2:28:21
extract-query.sql                              231    2026/8/5 3:10:28
extract.sql                                    172    2026/8/5 2:22:21
extract2.sql                                   153    2026/8/5 2:23:10
extract3.sql                                   102    2026/8/5 2:23:28
extraction-summary-report.md                   2268   2026/8/5 2:01:52
final-screenshot-dialogs.png                   140149 2026/8/5 1:31:46
final-screenshot-interaction-log.png           155414 2026/8/5 1:31:46
findings.json                                  16856  2026/8/5 2:47:56
goto-stderr.log                                125    2026/8/14 15:19:25
goto-stderr2.log                               375    2026/8/14 15:19:42
goto-stderr3.log                               418    2026/8/14 15:21:02
goto-stdout.log                                513    2026/8/14 15:19:25
goto-stdout2.log                               1536   2026/8/14 15:19:42
goto-stdout3.log                               1473   2026/8/14 15:21:02
loop-run.log                                   377    2026/8/14 15:17:54
page_info.js                                   196    2026/8/5 2:56:09
price-eval.js                                  60     2026/8/5 1:53:05
product-extract.sql                            169    2026/8/5 1:58:58
product-query.sql                              174    2026/8/14 14:57:37
product-seeds.txt                              234    2026/8/5 3:23:49
query-page.sql                                 102    2026/8/5 2:25:52
query-test.sql                                 172    2026/8/5 2:23:47
r1.csv                                         90     2026/8/5 2:33:38
r2.csv                                         89     2026/8/5 2:33:42
result-snapshot.yml                            5204   2026/8/5 2:53:06
results-attr.csv                               128    2026/8/5 2:24:28
results-combined.csv                           89     2026/8/5 2:33:23
results-final.csv                              163    2026/8/5 2:33:42
results-fresh.csv                              146    2026/8/5 2:28:28
results.csv                                    128    2026/8/5 2:22:55
results2.csv                                   128    2026/8/5 2:23:18
run-loop.ps1                                   253    2026/8/14 15:17:42
seed-ac4.txt                                   108    2026/8/5 2:16:29
seed-single1.txt                               54     2026/8/5 2:33:12
seed-single2.txt                               54     2026/8/5 2:33:12
seed-urls-1-3.txt                              108    2026/8/5 2:31:57
seed-urls-1-4.txt                              108    2026/8/5 2:32:30
seed-urls-reversed.txt                         108    2026/8/5 2:29:24
seed-urls.txt                                  117    2026/8/14 15:14:28
simple-query.sql                               72     2026/8/5 3:09:04
simple-root.sql                                126    2026/8/5 2:27:32
simple.sql                                     71     2026/8/5 2:23:59
SKILL.md                                       45815  2026/8/14 14:59:30
swarm-query.sql                                172    2026/8/5 3:36:36
swarm-seeds-2.txt                              78     2026/8/5 3:36:55
swarm-simple.sql                               144    2026/8/5 1:51:34
token-stat.mjs                                 3243   2026/8/13 17:41:33
webminer-launcher.log                          81     2026/8/13 17:25:55


 succeeded in 1410ms:
Closed 5 session(s)
No persisted loops. Start one with `browser4-cli loop <task>`.

diff --git a/.test-sessions/ac1-listing-query.sql b/.test-sessions/ac1-listing-query.sql
new file mode 100644
index 0000000000000000000000000000000000000000..d651eec929ece8af65e1fdb818fac08d6e7ef4ea
--- /dev/null
+++ b/.test-sessions/ac1-listing-query.sql
@@ -0,0 +1,6 @@
+SELECT
+  DOM_FIRST_TEXT(DOM, 'div.product-title') AS title,
+  DOM_FIRST_TEXT(DOM, 'div.product-price') AS price,
+  DOM_FIRST_HREF(DOM, 'a.product-link') AS link
+FROM
+  DOM_LOAD_AND_SELECT(@url, '.product-card')
diff --git a/.test-sessions/ac2-product-query.sql b/.test-sessions/ac2-product-query.sql
new file mode 100644
index 0000000000000000000000000000000000000000..1fa45c1ec3c6f272620e6313938e1565b2d4239c
--- /dev/null
+++ b/.test-sessions/ac2-product-query.sql
@@ -0,0 +1,6 @@
+SELECT
+  DOM_BASE_URI(DOM) AS url,
+  DOM_FIRST_TEXT(DOM, '#productTitle') AS title,
+  DOM_FIRST_TEXT(DOM, '#product-price') AS price
+FROM
+  DOM_LOAD_AND_SELECT(@url, 'body')
diff --git a/.test-sessions/ac6-few-urls.ps1 b/.test-sessions/ac6-few-urls.ps1
new file mode 100644
index 0000000000000000000000000000000000000000..07a5a6936b1fab6f1574eb1c9627014aa6157e04
--- /dev/null
+++ b/.test-sessions/ac6-few-urls.ps1
@@ -0,0 +1,18 @@
+#!/usr/bin/env pwsh
+$ErrorActionPreference = 'Continue'
+
+$urls = @(
+    "http://localhost:18080/ec/dp/B0E000001",
+    "http://localhost:18080/ec/dp/B0E000004",
+    "http://localhost:18080/ec/dp/B0E000005"
+)
+
+$urls | ForEach-Object {
+    $u = $_
+    Write-Host "=== Processing: $u ==="
+    browser4-cli goto $u
+    browser4-cli htmlsnapshot
+    $title = browser4-cli htmlsnapshot get text "#productTitle"
+    Write-Host "Title: $title"
+    Start-Sleep -Seconds 2
+}
diff --git a/.test-sessions/run-loop.ps1 b/.test-sessions/run-loop.ps1
new file mode 100644
index 0000000000000000000000000000000000000000..a4da61b00ecf6843a91fe250ac4cb5afe8d6a8ec
--- /dev/null
+++ b/.test-sessions/run-loop.ps1
@@ -0,0 +1,4 @@
+#!/usr/bin/env pwsh
+$ErrorActionPreference = 'Continue'
+$out = Join-Path $PSScriptRoot 'loop-run.log'
+browser4-cli loop --name mock-price-watch --count 2 -i 10 -- -s price-watch eval "document.querySelector('#product-price').textContent.trim()" *> $out

codex
All tasks complete and sessions cleaned up. Let me locate the swarm worker dispatch code for accurate issue pointers, then finalize the report.
exec
"C:\\Program Files\\PowerShell\\7\\pwsh.exe" -Command 'rg -n "class .*Swarm|swarm.*worker|fun .*poll|poll.*swarm|SWARM" browser4-rest/src/main/kotlin --glob "*.kt" | Select-Object -First 25' in D:\workspace\Browser4\Browser4-4.13
 succeeded in 353ms:
browser4-rest/src/main/kotlin\ai\platon\pulsar\rest\session\SessionKind.kt:25:    SWARM;
browser4-rest/src/main/kotlin\ai\platon\pulsar\rest\session\SessionKind.kt:28:     *  Only [BROWSER4_LAUNCHED] and [SWARM] sessions own their browsers;
browser4-rest/src/main/kotlin\ai\platon\pulsar\rest\session\SessionKind.kt:31:        get() = this == BROWSER4_LAUNCHED || this == SWARM
browser4-rest/src/main/kotlin\ai\platon\pulsar\rest\session\PulsarSessionManager.kt:10:import ai.platon.pulsar.common.B4Constants.SWARM_SESSION_ID
browser4-rest/src/main/kotlin\ai\platon\pulsar\rest\session\PulsarSessionManager.kt:54:        val sessionId = SWARM_SESSION_ID
browser4-rest/src/main/kotlin\ai\platon\pulsar\rest\session\PulsarSessionManager.kt:82:        // Route SWARM sessions before computeIfAbsent — ensureSwarmSession
browser4-rest/src/main/kotlin\ai\platon\pulsar\rest\session\PulsarSessionManager.kt:85:        if (sessionId.equals(SWARM_SESSION_ID, ignoreCase = true)) {
browser4-rest/src/main/kotlin\ai\platon\pulsar\rest\session\PulsarSessionManager.kt:118:        // Route SWARM sessions before computeIfAbsent (see explanation above).
browser4-rest/src/main/kotlin\ai\platon\pulsar\rest\session\PulsarSessionManager.kt:119:        if (requestedId.equals(SWARM_SESSION_ID, ignoreCase = true)) {
browser4-rest/src/main/kotlin\ai\platon\pulsar\rest\session\PulsarSessionManager.kt:608:            explicitSessionId.equals(SWARM_SESSION_ID, ignoreCase = true) -> SWARM_SESSION_ID
browser4-rest/src/main/kotlin\ai\platon\pulsar\rest\session\PulsarSessionManager.kt:618:            requestedSessionId.equals(SWARM_SESSION_ID, ignoreCase = true) -> SWARM_SESSION_ID
browser4-rest/src/main/kotlin\ai\platon\pulsar\rest\session\PulsarSessionManager.kt:630:            sessionId.equals(SWARM_SESSION_ID, ignoreCase = true) -> when (requestedProfileMode) {
browser4-rest/src/main/kotlin\ai\platon\pulsar\rest\config\SwarmToolMountConfiguration.kt:16:class SwarmToolMountConfiguration(
browser4-rest/src/main/kotlin\ai\platon\pulsar\rest\config\StartupWarmer.kt:43:     * and stuck swarm worker pools.
browser4-rest/src/main/kotlin\ai\platon\pulsar\rest\config\StartupWarmer.kt:59:        // Swarm infrastructure — forces the swarm browser pool and worker
browser4-rest/src/main/kotlin\ai\platon\pulsar\agent\tool\SwarmToolExecutor.kt:23:class SwarmToolExecutor(
browser4-rest/src/main/kotlin\ai\platon\pulsar\rest\api\service\ConversationService.kt:3:import ai.platon.pulsar.common.B4Constants.SWARM_SESSION_ID
browser4-rest/src/main/kotlin\ai\platon\pulsar\rest\api\service\ConversationService.kt:24:    val session get() = sessionManager.getOrCreateSession(SWARM_SESSION_ID).agenticSession
browser4-rest/src/main/kotlin\ai\platon\pulsar\rest\api\service\LoadService.kt:3:import ai.platon.pulsar.common.B4Constants.SWARM_SESSION_ID
browser4-rest/src/main/kotlin\ai\platon\pulsar\rest\api\service\LoadService.kt:21:    val session get() = sessionManager.getOrCreateSession(SWARM_SESSION_ID).agenticSession
browser4-rest/src/main/kotlin\ai\platon\pulsar\rest\api\service\SwarmService.kt:28:class SwarmService(
browser4-rest/src/main/kotlin\ai\platon\pulsar\rest\api\service\ExtractService.kt:3:import ai.platon.pulsar.common.B4Constants.SWARM_SESSION_ID
browser4-rest/src/main/kotlin\ai\platon\pulsar\rest\api\service\ExtractService.kt:15:    val session get() = sessionManager.getOrCreateSession(SWARM_SESSION_ID).agenticSession
browser4-rest/src/main/kotlin\ai\platon\pulsar\rest\api\service\ScrapeService.kt:3:import ai.platon.pulsar.common.B4Constants.SWARM_SESSION_ID
browser4-rest/src/main/kotlin\ai\platon\pulsar\rest\api\controller\SwarmController.kt:24:class SwarmController(

diff --git a/.test-sessions/ac1-listing-query.sql b/.test-sessions/ac1-listing-query.sql
new file mode 100644
index 0000000000000000000000000000000000000000..d651eec929ece8af65e1fdb818fac08d6e7ef4ea
--- /dev/null
+++ b/.test-sessions/ac1-listing-query.sql
@@ -0,0 +1,6 @@
+SELECT
+  DOM_FIRST_TEXT(DOM, 'div.product-title') AS title,
+  DOM_FIRST_TEXT(DOM, 'div.product-price') AS price,
+  DOM_FIRST_HREF(DOM, 'a.product-link') AS link
+FROM
+  DOM_LOAD_AND_SELECT(@url, '.product-card')
diff --git a/.test-sessions/ac2-product-query.sql b/.test-sessions/ac2-product-query.sql
new file mode 100644
index 0000000000000000000000000000000000000000..1fa45c1ec3c6f272620e6313938e1565b2d4239c
--- /dev/null
+++ b/.test-sessions/ac2-product-query.sql
@@ -0,0 +1,6 @@
+SELECT
+  DOM_BASE_URI(DOM) AS url,
+  DOM_FIRST_TEXT(DOM, '#productTitle') AS title,
+  DOM_FIRST_TEXT(DOM, '#product-price') AS price
+FROM
+  DOM_LOAD_AND_SELECT(@url, 'body')
diff --git a/.test-sessions/ac6-few-urls.ps1 b/.test-sessions/ac6-few-urls.ps1
new file mode 100644
index 0000000000000000000000000000000000000000..07a5a6936b1fab6f1574eb1c9627014aa6157e04
--- /dev/null
+++ b/.test-sessions/ac6-few-urls.ps1
@@ -0,0 +1,18 @@
+#!/usr/bin/env pwsh
+$ErrorActionPreference = 'Continue'
+
+$urls = @(
+    "http://localhost:18080/ec/dp/B0E000001",
+    "http://localhost:18080/ec/dp/B0E000004",
+    "http://localhost:18080/ec/dp/B0E000005"
+)
+
+$urls | ForEach-Object {
+    $u = $_
+    Write-Host "=== Processing: $u ==="
+    browser4-cli goto $u
+    browser4-cli htmlsnapshot
+    $title = browser4-cli htmlsnapshot get text "#productTitle"
+    Write-Host "Title: $title"
+    Start-Sleep -Seconds 2
+}
diff --git a/.test-sessions/run-loop.ps1 b/.test-sessions/run-loop.ps1
new file mode 100644
index 0000000000000000000000000000000000000000..a4da61b00ecf6843a91fe250ac4cb5afe8d6a8ec
--- /dev/null
+++ b/.test-sessions/run-loop.ps1
@@ -0,0 +1,4 @@
+#!/usr/bin/env pwsh
+$ErrorActionPreference = 'Continue'
+$out = Join-Path $PSScriptRoot 'loop-run.log'
+browser4-cli loop --name mock-price-watch --count 2 -i 10 -- -s price-watch eval "document.querySelector('#product-price').textContent.trim()" *> $out

codex
Everything is verified and cleaned up. Here is the full evaluation report.

---

## A. Task Result

All six acceptance criteria were completed successfully, each reproducing the exact branch of SKILL.md §4b:

- **AC1 (single list page):** `htmlsnapshot query` with `DOM_LOAD_AND_SELECT(@url, '.product-card')` returned 6 rows (title + price + link) from `http://localhost:18080/ec/b?node=1292115012` — one row per product card.
- **AC2 (multiple known URLs):** `crawl --seed-file seed-urls.txt --depth 0 --sql @ac2-product-query.sql --format table --refresh` returned exactly 3 rows, one per seed URL, each with `DOM_BASE_URI` + `#productTitle` + `#product-price`.
- **AC3 (crawl from start URL):** `crawl http://localhost:18080/generated/crawl/index.html -d 2 -ol "a.product" -olp "/product/"` discovered 10 pages (hub + 9 product pages, including depth-2 pages reachable only via product→product links). Category/guide/utility links present in the fixture HTML were correctly excluded.
- **AC4 (parallel execution):** `swarm create --display-mode HEADLESS --max-browser-contexts 2 --max-open-tabs 4` → `swarm query --sql @ac2-product-query.sql --seed-file seed-urls.txt --refresh` → 3 tasks, polled via `swarm status`/`swarm list`, results fetched with `swarm result` (one row each), then `swarm close`.
- **AC5 (repeated monitoring):** `-s price-watch goto ...` then `loop --name mock-price-watch --count 2 -i 10 -- -s price-watch eval "..."` executed 2 iterations, both returning `$899.99`; `loop --list` and `loop --status` worked mid-run, and the state was auto-cleaned on completion as documented.
- **AC6 (few URLs in a shell script):** A PowerShell `ForEach-Object` loop over 3 URLs calling `goto` → `htmlsnapshot` → `htmlsnapshot get text "#productTitle"` with `Start-Sleep 2` extracted all 3 titles without needing crawl/swarm/loop.

The backend was the released runtime bundle (browser4-cli 4.13.4 ↔ server 4.13.4-SNAPSHOT at localhost:18182, health UP), and all temporary files were created under `.test-sessions/`.

## B. Execution Trace

**Preparation:** Verified cwd was the repo root; created `.test-sessions/`; ran `browser4-cli help`; downloaded and read `https://browser4.io/SKILL.md` in full (45.8 KB); read the local SKILL.md §4b and the crawl/swarm/loop/htmlsnapshot/x-sql reference docs; confirmed MockSite (HTTP 200 on :18080) and backend health (`browser4-cli status`).

**Commands and steps per AC:**

1. **AC1:** `browser4-cli goto "http://localhost:18080/ec/b?node=1292115012"` → `browser4-cli htmlsnapshot` → `browser4-cli htmlsnapshot inspect` (auto-discovered `.product-card` with `.product-title`/`.product-price`) → wrote `.test-sessions/ac1-listing-query.sql` → `browser4-cli htmlsnapshot query "<url>" --sql "@.test-sessions/ac1-listing-query.sql"` → 6 rows.
2. **AC2:** Wrote `.test-sessions/seed-urls.txt` (B0E000001/2/3) and `.test-sessions/ac2-product-query.sql`; ran `browser4-cli crawl --seed-file ".test-sessions/seed-urls.txt" --depth 0 --sql "@.test-sessions/ac2-product-query.sql" --format table --refresh` → 3 rows in ~26s.
3. **AC3:** `browser4-cli crawl "http://localhost:18080/generated/crawl/index.html" -d 2 -ol "a.product" -olp "/product/"` → 10 pages in ~60s. Verified fixture HTML contained `a.category-link`, `a.guide-link`, `a.promo-link`, `a.utility-link` that were correctly excluded; confirmed product pages cross-link (`a.product` → 4.html/5.html) so depth-2 discovery occurred. Checked raw result via `crawl result <id> --json`.
4. **AC4:** `browser4-cli swarm create --display-mode HEADLESS --max-browser-contexts 2 --max-open-tabs 4` (warned about 2 stale tasks) → `swarm list` → `swarm list --clear` → `browser4-cli swarm query --sql "@.test-sessions/ac2-product-query.sql" --seed-file ".test-sessions/seed-urls.txt" --refresh` → 3 task IDs → polled `swarm status`/`swarm list` until all `completed` → `swarm result <id>` ×3 → `swarm close`. Re-ran the full cycle once with `--clear-stale` to confirm extraction and observe scheduling variability.
5. **AC5:** `browser4-cli -s price-watch goto "http://localhost:18080/ec/dp/B0E000001"` → sanity-checked `-s price-watch eval "document.querySelector('#product-price').textContent.trim()"` → launched the loop in the background (hidden `Start-Process` running `.test-sessions/run-loop.ps1` so status could be inspected mid-run) → `loop --list` (1/2 iters, running) → `loop --status --name mock-price-watch` → loop finished, log showed 2 iterations of `$899.99`.
6. **AC6:** Wrote and ran `.test-sessions/ac6-few-urls.ps1` — `ForEach-Object` over 3 URLs with `goto`, `htmlsnapshot`, `htmlsnapshot get text "#productTitle"`, `Start-Sleep -Seconds 2` → titles extracted for all 3 pages.

**Key decisions/workarounds:** quoted PowerShell `@file` paths (`--sql "@..."`) per the shell-quoting warning; used `swarm list --clear` after the stale-task warning before submitting; used a hidden background process for the loop so `loop --status` could be observed while running; closed the swarm session and all 5 browser sessions at the end (`swarm close`, `close-all`).

**Investigation notes:** the stray "No active session — creating a new one." line turned out to be the intentional new-session notice from the first `goto`, written to stderr and thus appearing detached in merged output (not a functional bug). The crawl depth labels were confirmed wrong from raw backend JSON. Swarm queued-job timing was confirmed variable across two runs.

## C. Issues Found & D. Overall Assessment

```json
{
  "issues": [
    {
      "title": "Crawl reports every page at depth=1 regardless of actual discovery depth",
      "severity": "Medium",
      "category": "Reliability",
      "reproduction": "browser4-cli crawl \"http://localhost:18080/generated/crawl/index.html\" -d 2 -ol \"a.product\" -olp \"/product/\"\nthen: browser4-cli crawl result <task-id> --json\nResult: every page in the pages[] array has \"depth\":1, including the seed URL (http://localhost:18080/generated/crawl/index.html) and pages that are only reachable at depth 2 (product/4.html ... product/9.html, found via product->product links).",
      "expected": "The seed URL should be reported at depth=0, pages linked from the hub at depth=1, and pages found via product->product links at depth=2 (matching crawl.md's documented 'depth=0 | URL | Page 1 Title' output).",
      "actual": "All 10 pages are reported as \"depth\":1. The crawl itself recurses correctly (10 unique pages, no category pages, terminates), so only the reported depth is wrong, but users cannot verify link depth or filter results by depth.",
      "rootCause": "In CrawlService.crawlDepthN the recorded depth is `extractDepth(page) ?: 1`. extractDepth parses `page.configuredUrl` for a '-depth N' marker, which is apparently not preserved in configuredUrl for pages loaded through the parse handler, so the silent `?: 1` fallback applies to every page (seed and depth-2 pages alike). The internal recursion still knows the real depth (it stops at maxDepth), so only the reporting is affected. Needs backend verification of what configuredUrl contains for each page.",
      "codePointer": "browser4-rest/src/main/kotlin/ai/platon/pulsar/rest/api/service/CrawlService.kt:742 (crawlDepthN) and :1098 (extractDepth)",
      "suggestion": "- Track depth explicitly in the parse handler (e.g. a ConcurrentHashMap<normalizedUrl, depth> populated from buildArgsForDepth) and record it in CrawlPageResult instead of scraping the configured URL\n- Remove the silent `?: 1` fallback or apply it only to the seed so a missing depth fails loudly\n- Add a backend integration test asserting seed=0, direct links=1, second-level links=2 for a depth-2 crawl"
    },
    {
      "title": "Swarm jobs can sit in 'queued' for 20-25s while sibling jobs complete, looking stuck with no worker visibility",
      "severity": "Low",
      "category": "Reliability",
      "reproduction": "browser4-cli swarm create --display-mode HEADLESS --max-browser-contexts 2 --max-open-tabs 4\nbrowser4-cli swarm query --sql \"@q.sql\" --seed-file seeds.txt --refresh\nbrowser4-cli swarm status <id> (poll)\nRun 1: B0E000001 stayed statusCode=201 'queued' for ~25s while B0E000002/B0E000003 completed in ~1s each. Run 2 (same commands): B0E000001 completed instantly while the other two took ~18s each.",
      "expected": "With 2 contexts and 3 jobs, jobs should be picked up promptly in submission order and roughly evenly; `swarm status` should not look like the job is stuck while others succeed.",
      "actual": "Job pickup order and latency vary widely between runs (different task is 'slow' each time). One worker context appears warm (instant) while the other takes ~18s, and the queued job shows only statusCode 201 with no indication of worker warm-up.",
      "rootCause": "The swarm worker pool initializes browser contexts lazily and job->context assignment does not appear to be strict FIFO; the client has no visibility into per-worker state, so a job waiting for a cold context is indistinguishable from a stuck one. The CLI's own warning text ('If jobs appear stuck...') amplifies the impression. Backend scheduling in SwarmService needs investigation to confirm exact worker pick-up order.",
      "codePointer": "browser4-rest/src/main/kotlin/ai/platon/pulsar/rest/api/service/SwarmService.kt (task dispatch/worker pool); browser4-rest/src/main/kotlin/ai/platon/pulsar/rest/config/StartupWarmer.kt (swarm pool warming)",
      "suggestion": "- Warm the swarm browser pool during `swarm create` so no job hits a cold context\n- Include per-worker state (initializing/ready/busy) in `swarm status`/`swarm list` output\n- Assign jobs FIFO so the first submitted job starts first\n- Adjust the CLI 'stuck' guidance to mention expected worker warm-up latency"
    },
    {
      "title": "Crawl progress output is repetitive and slow-looking for small local jobs (26s for 3 pages; ~56s 'waiting for first page' for 10 pages)",
      "severity": "Low",
      "category": "UX",
      "reproduction": "browser4-cli crawl --seed-file .test-sessions/seed-urls.txt --depth 0 --sql \"@q.sql\" --format table --refresh   # 3 localhost pages, 26s\nbrowser4-cli crawl http://localhost:18080/generated/crawl/index.html -d 2 -ol \"a.product\" -olp \"/product/\"   # prints 'Crawling... waiting for first page (6s/16s/26s/36s/46s/56s elapsed, 1 URLs queued)' for ~56s",
      "expected": "Local pages should complete in a few seconds, and progress should show meaningful per-URL detail rather than near-identical lines that make the crawl look stalled.",
      "actual": "Each page takes ~5-7s (backend parse/load pipeline) and the CLI prints essentially the same 'waiting for first page' / 'N pages found so far' line every poll (~2s interval), which reads as a hang. The depth-0 mode did show '1/3 seeds done ... 1 rows extracted' per seed, which was much clearer.",
      "rootCause": "crawlDepthN processes pages sequentially through Pulsar's parse pipeline (engine-level latency dominates); the CLI polls every 2s but backend state only changes on page completion, so output lines repeat. Not a correctness failure, but a real UX/confidence problem for first-time users.",
      "codePointer": "cli/browser4-cli/src/main.rs:10846 (crawl polling loop) and :10970 (progress messages); browser4-rest/src/main/kotlin/ai/platon/pulsar/rest/api/service/CrawlService.kt (per-page processing)",
      "suggestion": "- Emit a per-URL completion line as soon as each page finishes (like depth-0 mode does), including in link-discovery mode\n- Replace repeated identical progress lines in place (e.g. carriage-return update) instead of appending duplicates\n- Document expected per-page latency in crawl.md so users don't interpret slow local crawls as failures"
    },
    {
      "title": "'No active session — creating a new one.' is written to stderr and can appear detached from the 'Session opened:' confirmation in merged/scripted output",
      "severity": "Low",
      "category": "UX",
      "reproduction": "browser4-cli -s demo goto http://localhost:18080/ec/dp/B0E000001; browser4-cli -s demo goto http://localhost:18080/ec/dp/B0E000002\nCapture combined stdout+stderr: the line 'No active session — creating a new one.' appears after the second command's output, far from the 'Session opened: demo (...)' line it belongs to.",
      "expected": "The new-session notice should appear adjacent to 'Session opened:' (same stream, same command), or be omitted in favor of the 'Session opened:' message.",
      "actual": "The informational notice goes to stderr via eprintln! while the success message goes to stdout; in merged/captured output (and command chains), the notice surfaces at the very end, making it look like a stray error from the last command. This confused the evaluator initially before source inspection showed it was intentional.",
      "rootCause": "cli/browser4-cli/src/main.rs:1252 uses eprintln! for an informational message while the paired 'Session opened:' message uses println! (cli_println!), so stream separation reorders them in merged output. stderr is conventionally reserved for warnings/errors, which makes the notice look alarming.",
      "codePointer": "cli/browser4-cli/src/main.rs:1248-1253 (get_or_create_navigation_session)",
      "suggestion": "- Emit 'No active session — creating a new one.' to stdout immediately before 'Session opened:' (or drop it and let 'Session opened:' carry the message)\n- Keep stderr strictly for warnings/errors so informational session lifecycle messages are consistently on stdout"
    },
    {
      "title": "Loop output contains non-ASCII glyphs (—, ✓) that are corrupted when redirected to a file on Windows PowerShell",
      "severity": "Low",
      "category": "UX",
      "reproduction": "Create a script that runs: browser4-cli loop --name mock-price-watch --count 2 -i 10 -- -s price-watch eval \"document.querySelector('#product-price').textContent.trim()\" *> loop-run.log\nThen inspect loop-run.log: lines contain '鈥?every 10s...' and '鉁? Loop finished' instead of '— every 10s' and '✓ Loop finished'.",
      "expected": "Redirected loop logs should preserve UTF-8 characters (— and ✓).",
      "actual": "The em-dash and checkmark are mangled into mojibake in the redirected file. Console display is fine; only scripted/logging workflows are affected.",
      "rootCause": "The CLI emits UTF-8, but Windows PowerShell 5.1's `*>` redirection decodes the byte stream using the system ANSI codepage (GBK in this environment), corrupting non-ASCII characters. Cosmetic, but it degrades log-based automation on a first-class platform.",
      "codePointer": "cli/browser4-cli/src/main.rs (loop command output formatting with —/✓ glyphs)",
      "suggestion": "- Use ASCII-safe framing characters (e.g. '-' and 'OK') in loop iteration/history output, or\n- Document in loop.md/shell-quoting.md that Windows users should set `[Console]::OutputEncoding = [Text.Encoding]::UTF8` before redirecting loop output"
    }
  ],
  "assessment": {
    "completionStatus": "Successful — all six acceptance criteria (AC1-AC6) were executed and verified against the released browser4-cli 4.13.4 / server 4.13.4, and the usability evaluation was completed.",
    "successRate": "100% — every task step succeeded on the first attempt; no workaround was required for correctness (only the documented PowerShell quoting practice and a hidden background process to observe loop status mid-run).",
Everything is verified and cleaned up. Here is the full evaluation report.
    "issuesFound": 5,

    "majorBlockers": "None. All bulk/scale workflows (htmlsnapshot query, crawl seed-file, crawl link discovery, swarm, loop, shell loop) worked end-to-end.",
---
    "mostConfusingAspects": "1) Crawl output labeling every page depth=1 (contradicts docs and hides real discovery depth). 2) Swarm jobs sitting at 'queued' for 20-25s while sibling jobs finish, looking stuck. 3) The 'No active session — creating a new one.' stderr line appearing detached from its 'Session opened:' confirmation in merged output. 4) Crawl progress lines repeating identically for ~56s on a 10-page local crawl.",

    "mostValuableImprovements": "1) Fix crawl depth reporting (track depth explicitly per page instead of parsing configuredUrl with a silent default). 2) Add worker warm-up/visibility to swarm so 'queued' states are explainable. 3) Make crawl progress output per-URL and non-repetitive. 4) Move the new-session notice to stdout adjacent to 'Session opened:'. 5) Document/avoid non-ASCII glyphs in redirected loop logs on Windows.",
## A. Task Result
    "usabilityRating": 7

  }
All six acceptance criteria were completed successfully, each reproducing the exact branch of SKILL.md §4b:
}

```
- **AC1 (single list page):** `htmlsnapshot query` with `DOM_LOAD_AND_SELECT(@url, '.product-card')` returned 6 rows (title + price + link) from `http://localhost:18080/ec/b?node=1292115012` — one row per product card.
- **AC2 (multiple known URLs):** `crawl --seed-file seed-urls.txt --depth 0 --sql @ac2-product-query.sql --format table --refresh` returned exactly 3 rows, one per seed URL, each with `DOM_BASE_URI` + `#productTitle` + `#product-price`.
diff --git a/.test-sessions/ac1-listing-query.sql b/.test-sessions/ac1-listing-query.sql
- **AC3 (crawl from start URL):** `crawl http://localhost:18080/generated/crawl/index.html -d 2 -ol "a.product" -olp "/product/"` discovered 10 pages (hub + 9 product pages, including depth-2 pages reachable only via product→product links). Category/guide/utility links present in the fixture HTML were correctly excluded.
new file mode 100644
- **AC4 (parallel execution):** `swarm create --display-mode HEADLESS --max-browser-contexts 2 --max-open-tabs 4` → `swarm query --sql @ac2-product-query.sql --seed-file seed-urls.txt --refresh` → 3 tasks, polled via `swarm status`/`swarm list`, results fetched with `swarm result` (one row each), then `swarm close`.
index 0000000000000000000000000000000000000000..d651eec929ece8af65e1fdb818fac08d6e7ef4ea
- **AC6 (few URLs in a shell script):** A PowerShell `ForEach-Object` loop over 3 URLs calling `goto` → `htmlsnapshot` → `htmlsnapshot get text "#productTitle"` with `Start-Sleep 2` extracted all 3 titles without needing crawl/swarm/loop.

--- /dev/null

+++ b/.test-sessions/ac1-listing-query.sql
@@ -0,0 +1,6 @@
+SELECT

+  DOM_FIRST_TEXT(DOM, 'div.product-title') AS title,
**Preparation:** Verified cwd was the repo root; created `.test-sessions/`; ran `browser4-cli help`; downloaded and read `https://browser4.io/SKILL.md` in full (45.8 KB); read the local SKILL.md §4b and the crawl/swarm/loop/htmlsnapshot/x-sql reference docs; confirmed MockSite (HTTP 200 on :18080) and backend health (`browser4-cli status`).
+  DOM_FIRST_TEXT(DOM, 'div.product-price') AS price,

+  DOM_FIRST_HREF(DOM, 'a.product-link') AS link
**Commands and steps per AC:**
+FROM

+  DOM_LOAD_AND_SELECT(@url, '.product-card')
1. **AC1:** `browser4-cli goto "http://localhost:18080/ec/b?node=1292115012"` → `browser4-cli htmlsnapshot` → `browser4-cli htmlsnapshot inspect` (auto-discovered `.product-card` with `.product-title`/`.product-price`) → wrote `.test-sessions/ac1-listing-query.sql` → `browser4-cli htmlsnapshot query "<url>" --sql "@.test-sessions/ac1-listing-query.sql"` → 6 rows.
diff --git a/.test-sessions/ac2-product-query.sql b/.test-sessions/ac2-product-query.sql
2. **AC2:** Wrote `.test-sessions/seed-urls.txt` (B0E000001/2/3) and `.test-sessions/ac2-product-query.sql`; ran `browser4-cli crawl --seed-file ".test-sessions/seed-urls.txt" --depth 0 --sql "@.test-sessions/ac2-product-query.sql" --format table --refresh` → 3 rows in ~26s.
new file mode 100644
3. **AC3:** `browser4-cli crawl "http://localhost:18080/generated/crawl/index.html" -d 2 -ol "a.product" -olp "/product/"` → 10 pages in ~60s. Verified fixture HTML contained `a.category-link`, `a.guide-link`, `a.promo-link`, `a.utility-link` that were correctly excluded; confirmed product pages cross-link (`a.product` → 4.html/5.html) so depth-2 discovery occurred. Checked raw result via `crawl result <id> --json`.
index 0000000000000000000000000000000000000000..1fa45c1ec3c6f272620e6313938e1565b2d4239c
4. **AC4:** `browser4-cli swarm create --display-mode HEADLESS --max-browser-contexts 2 --max-open-tabs 4` (warned about 2 stale tasks) → `swarm list` → `swarm list --clear` → `browser4-cli swarm query --sql "@.test-sessions/ac2-product-query.sql" --seed-file ".test-sessions/seed-urls.txt" --refresh` → 3 task IDs → polled `swarm status`/`swarm list` until all `completed` → `swarm result <id>` ×3 → `swarm close`. Re-ran the full cycle once with `--clear-stale` to confirm extraction and observe scheduling variability.
--- /dev/null
5. **AC5:** `browser4-cli -s price-watch goto "http://localhost:18080/ec/dp/B0E000001"` → sanity-checked `-s price-watch eval "document.querySelector('#product-price').textContent.trim()"` → launched the loop in the background (hidden `Start-Process` running `.test-sessions/run-loop.ps1` so status could be inspected mid-run) → `loop --list` (1/2 iters, running) → `loop --status --name mock-price-watch` → loop finished, log showed 2 iterations of `$899.99`.
+++ b/.test-sessions/ac2-product-query.sql
6. **AC6:** Wrote and ran `.test-sessions/ac6-few-urls.ps1` — `ForEach-Object` over 3 URLs with `goto`, `htmlsnapshot`, `htmlsnapshot get text "#productTitle"`, `Start-Sleep -Seconds 2` → titles extracted for all 3 pages.
@@ -0,0 +1,6 @@

+SELECT
**Key decisions/workarounds:** quoted PowerShell `@file` paths (`--sql "@..."`) per the shell-quoting warning; used `swarm list --clear` after the stale-task warning before submitting; used a hidden background process for the loop so `loop --status` could be observed while running; closed the swarm session and all 5 browser sessions at the end (`swarm close`, `close-all`).
+  DOM_BASE_URI(DOM) AS url,

+  DOM_FIRST_TEXT(DOM, '#productTitle') AS title,
**Investigation notes:** the stray "No active session — creating a new one." line turned out to be the intentional new-session notice from the first `goto`, written to stderr and thus appearing detached in merged output (not a functional bug). The crawl depth labels were confirmed wrong from raw backend JSON. Swarm queued-job timing was confirmed variable across two runs.
+  DOM_FIRST_TEXT(DOM, '#product-price') AS price

+FROM
## C. Issues Found & D. Overall Assessment
+  DOM_LOAD_AND_SELECT(@url, 'body')

diff --git a/.test-sessions/ac6-few-urls.ps1 b/.test-sessions/ac6-few-urls.ps1
```json
new file mode 100644
{
index 0000000000000000000000000000000000000000..07a5a6936b1fab6f1574eb1c9627014aa6157e04
  "issues": [
--- /dev/null
    {
+++ b/.test-sessions/ac6-few-urls.ps1
      "title": "Crawl reports every page at depth=1 regardless of actual discovery depth",
@@ -0,0 +1,18 @@
      "severity": "Medium",
+#!/usr/bin/env pwsh
      "category": "Reliability",
+$ErrorActionPreference = 'Continue'
      "reproduction": "browser4-cli crawl \"http://localhost:18080/generated/crawl/index.html\" -d 2 -ol \"a.product\" -olp \"/product/\"\nthen: browser4-cli crawl result <task-id> --json\nResult: every page in the pages[] array has \"depth\":1, including the seed URL (http://localhost:18080/generated/crawl/index.html) and pages that are only reachable at depth 2 (product/4.html ... product/9.html, found via product->product links).",
+
      "expected": "The seed URL should be reported at depth=0, pages linked from the hub at depth=1, and pages found via product->product links at depth=2 (matching crawl.md's documented 'depth=0 | URL | Page 1 Title' output).",
+$urls = @(
      "actual": "All 10 pages are reported as \"depth\":1. The crawl itself recurses correctly (10 unique pages, no category pages, terminates), so only the reported depth is wrong, but users cannot verify link depth or filter results by depth.",
+    "http://localhost:18080/ec/dp/B0E000001",
      "rootCause": "In CrawlService.crawlDepthN the recorded depth is `extractDepth(page) ?: 1`. extractDepth parses `page.configuredUrl` for a '-depth N' marker, which is apparently not preserved in configuredUrl for pages loaded through the parse handler, so the silent `?: 1` fallback applies to every page (seed and depth-2 pages alike). The internal recursion still knows the real depth (it stops at maxDepth), so only the reporting is affected. Needs backend verification of what configuredUrl contains for each page.",
+    "http://localhost:18080/ec/dp/B0E000004",
      "codePointer": "browser4-rest/src/main/kotlin/ai/platon/pulsar/rest/api/service/CrawlService.kt:742 (crawlDepthN) and :1098 (extractDepth)",
+    "http://localhost:18080/ec/dp/B0E000005"
      "suggestion": "- Track depth explicitly in the parse handler (e.g. a ConcurrentHashMap<normalizedUrl, depth> populated from buildArgsForDepth) and record it in CrawlPageResult instead of scraping the configured URL\n- Remove the silent `?: 1` fallback or apply it only to the seed so a missing depth fails loudly\n- Add a backend integration test asserting seed=0, direct links=1, second-level links=2 for a depth-2 crawl"
+)
    },
+
    {
+$urls | ForEach-Object {
      "title": "Swarm jobs can sit in 'queued' for 20-25s while sibling jobs complete, looking stuck with no worker visibility",
+    $u = $_
      "severity": "Low",
+    Write-Host "=== Processing: $u ==="
      "category": "Reliability",
+    browser4-cli goto $u
      "reproduction": "browser4-cli swarm create --display-mode HEADLESS --max-browser-contexts 2 --max-open-tabs 4\nbrowser4-cli swarm query --sql \"@q.sql\" --seed-file seeds.txt --refresh\nbrowser4-cli swarm status <id> (poll)\nRun 1: B0E000001 stayed statusCode=201 'queued' for ~25s while B0E000002/B0E000003 completed in ~1s each. Run 2 (same commands): B0E000001 completed instantly while the other two took ~18s each.",
+    browser4-cli htmlsnapshot
      "expected": "With 2 contexts and 3 jobs, jobs should be picked up promptly in submission order and roughly evenly; `swarm status` should not look like the job is stuck while others succeed.",
+    $title = browser4-cli htmlsnapshot get text "#productTitle"
      "actual": "Job pickup order and latency vary widely between runs (different task is 'slow' each time). One worker context appears warm (instant) while the other takes ~18s, and the queued job shows only statusCode 201 with no indication of worker warm-up.",
+    Write-Host "Title: $title"
      "rootCause": "The swarm worker pool initializes browser contexts lazily and job->context assignment does not appear to be strict FIFO; the client has no visibility into per-worker state, so a job waiting for a cold context is indistinguishable from a stuck one. The CLI's own warning text ('If jobs appear stuck...') amplifies the impression. Backend scheduling in SwarmService needs investigation to confirm exact worker pick-up order.",
+    Start-Sleep -Seconds 2
      "codePointer": "browser4-rest/src/main/kotlin/ai/platon/pulsar/rest/api/service/SwarmService.kt (task dispatch/worker pool); browser4-rest/src/main/kotlin/ai/platon/pulsar/rest/config/StartupWarmer.kt (swarm pool warming)",
+}
      "suggestion": "- Warm the swarm browser pool during `swarm create` so no job hits a cold context\n- Include per-worker state (initializing/ready/busy) in `swarm status`/`swarm list` output\n- Assign jobs FIFO so the first submitted job starts first\n- Adjust the CLI 'stuck' guidance to mention expected worker warm-up latency"
diff --git a/.test-sessions/run-loop.ps1 b/.test-sessions/run-loop.ps1
    },
new file mode 100644
    {
index 0000000000000000000000000000000000000000..a4da61b00ecf6843a91fe250ac4cb5afe8d6a8ec
      "title": "Crawl progress output is repetitive and slow-looking for small local jobs (26s for 3 pages; ~56s 'waiting for first page' for 10 pages)",
--- /dev/null
      "severity": "Low",
+++ b/.test-sessions/run-loop.ps1
      "category": "UX",
@@ -0,0 +1,4 @@
      "reproduction": "browser4-cli crawl --seed-file .test-sessions/seed-urls.txt --depth 0 --sql \"@q.sql\" --format table --refresh   # 3 localhost pages, 26s\nbrowser4-cli crawl http://localhost:18080/generated/crawl/index.html -d 2 -ol \"a.product\" -olp \"/product/\"   # prints 'Crawling... waiting for first page (6s/16s/26s/36s/46s/56s elapsed, 1 URLs queued)' for ~56s",
      "expected": "Local pages should complete in a few seconds, and progress should show meaningful per-URL detail rather than near-identical lines that make the crawl look stalled.",
+#!/usr/bin/env pwsh
      "actual": "Each page takes ~5-7s (backend parse/load pipeline) and the CLI prints essentially the same 'waiting for first page' / 'N pages found so far' line every poll (~2s interval), which reads as a hang. The depth-0 mode did show '1/3 seeds done ... 1 rows extracted' per seed, which was much clearer.",
+$ErrorActionPreference = 'Continue'
      "rootCause": "crawlDepthN processes pages sequentially through Pulsar's parse pipeline (engine-level latency dominates); the CLI polls every 2s but backend state only changes on page completion, so output lines repeat. Not a correctness failure, but a real UX/confidence problem for first-time users.",
+$out = Join-Path $PSScriptRoot 'loop-run.log'
      "codePointer": "cli/browser4-cli/src/main.rs:10846 (crawl polling loop) and :10970 (progress messages); browser4-rest/src/main/kotlin/ai/platon/pulsar/rest/api/service/CrawlService.kt (per-page processing)",
+browser4-cli loop --name mock-price-watch --count 2 -i 10 -- -s price-watch eval "document.querySelector('#product-price').textContent.trim()" *> $out
      "suggestion": "- Emit a per-URL completion line as soon as each page finishes (like depth-0 mode does), including in link-discovery mode\n- Replace repeated identical progress lines in place (e.g. carriage-return update) instead of appending duplicates\n- Document expected per-page latency in crawl.md so users don't interpret slow local crawls as failures"

    },
tokens used
    {
117,907
      "title": "'No active session — creating a new one.' is written to stderr and can appear detached from the 'Session opened:' confirmation in merged/scripted output",
      "severity": "Low",
      "category": "UX",
      "reproduction": "browser4-cli -s demo goto http://localhost:18080/ec/dp/B0E000001; browser4-cli -s demo goto http://localhost:18080/ec/dp/B0E000002\nCapture combined stdout+stderr: the line 'No active session — creating a new one.' appears after the second command's output, far from the 'Session opened: demo (...)' line it belongs to.",
      "expected": "The new-session notice should appear adjacent to 'Session opened:' (same stream, same command), or be omitted in favor of the 'Session opened:' message.",
      "actual": "The informational notice goes to stderr via eprintln! while the success message goes to stdout; in merged/captured output (and command chains), the notice surfaces at the very end, making it look like a stray error from the last command. This confused the evaluator initially before source inspection showed it was intentional.",
      "rootCause": "cli/browser4-cli/src/main.rs:1252 uses eprintln! for an informational message while the paired 'Session opened:' message uses println! (cli_println!), so stream separation reorders them in merged output. stderr is conventionally reserved for warnings/errors, which makes the notice look alarming.",
      "codePointer": "cli/browser4-cli/src/main.rs:1248-1253 (get_or_create_navigation_session)",
      "suggestion": "- Emit 'No active session — creating a new one.' to stdout immediately before 'Session opened:' (or drop it and let 'Session opened:' carry the message)\n- Keep stderr strictly for warnings/errors so informational session lifecycle messages are consistently on stdout"
    },
    {
      "title": "Loop output contains non-ASCII glyphs (—, ✓) that are corrupted when redirected to a file on Windows PowerShell",
      "severity": "Low",
      "category": "UX",
      "reproduction": "Create a script that runs: browser4-cli loop --name mock-price-watch --count 2 -i 10 -- -s price-watch eval \"document.querySelector('#product-price').textContent.trim()\" *> loop-run.log\nThen inspect loop-run.log: lines contain '鈥?every 10s...' and '鉁? Loop finished' instead of '— every 10s' and '✓ Loop finished'.",
      "expected": "Redirected loop logs should preserve UTF-8 characters (— and ✓).",
      "actual": "The em-dash and checkmark are mangled into mojibake in the redirected file. Console display is fine; only scripted/logging workflows are affected.",
      "rootCause": "The CLI emits UTF-8, but Windows PowerShell 5.1's `*>` redirection decodes the byte stream using the system ANSI codepage (GBK in this environment), corrupting non-ASCII characters. Cosmetic, but it degrades log-based automation on a first-class platform.",
      "codePointer": "cli/browser4-cli/src/main.rs (loop command output formatting with —/✓ glyphs)",
      "suggestion": "- Use ASCII-safe framing characters (e.g. '-' and 'OK') in loop iteration/history output, or\n- Document in loop.md/shell-quoting.md that Windows users should set `[Console]::OutputEncoding = [Text.Encoding]::UTF8` before redirecting loop output"
    }
  ],
  "assessment": {
    "completionStatus": "Successful — all six acceptance criteria (AC1-AC6) were executed and verified against the released browser4-cli 4.13.4 / server 4.13.4, and the usability evaluation was completed.",
    "successRate": "100% — every task step succeeded on the first attempt; no workaround was required for correctness (only the documented PowerShell quoting practice and a hidden background process to observe loop status mid-run).",
    "issuesFound": 5,
    "majorBlockers": "None. All bulk/scale workflows (htmlsnapshot query, crawl seed-file, crawl link discovery, swarm, loop, shell loop) worked end-to-end.",
    "mostConfusingAspects": "1) Crawl output labeling every page depth=1 (contradicts docs and hides real discovery depth). 2) Swarm jobs sitting at 'queued' for 20-25s while sibling jobs finish, looking stuck. 3) The 'No active session — creating a new one.' stderr line appearing detached from its 'Session opened:' confirmation in merged output. 4) Crawl progress lines repeating identically for ~56s on a 10-page local crawl.",
    "mostValuableImprovements": "1) Fix crawl depth reporting (track depth explicitly per page instead of parsing configuredUrl with a silent default). 2) Add worker warm-up/visibility to swarm so 'queued' states are explainable. 3) Make crawl progress output per-URL and non-repetitive. 4) Move the new-session notice to stdout adjacent to 'Session opened:'. 5) Document/avoid non-ASCII glyphs in redirected loop logs on Windows.",
    "usabilityRating": 7
  }
}
```
