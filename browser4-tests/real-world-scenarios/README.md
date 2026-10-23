# Agent Scenario Tests

Markdown task files and PowerShell runners that evaluate browser4-cli usability
through an LLM agent. Each task defines a real-world scenario and asks the agent
to complete it while simultaneously evaluating the CLI's discoverability,
documentation, and reliability from a first-time user's perspective.

## Quick start (standalone)

```powershell
# From the repo root:
./browser4-tests/real-world-scenarios/scripts/run-task.ps1 -TaskFile tasks/real-world/generic/search-summary.md
```

Each task file describes the scenario in plain markdown. `run-task.ps1` reads the
file, combines it with the shared evaluation template (`common.ps1`), and invokes
the configured agent CLI (`dsh`, `opencode`, `claude`, `kimi`, or `codex`, auto-detected
in that order — `dsh` (DeepSeek Harness) is the preferred harness and `opencode`
the designated backup). Set
`$env:BROWSER4_AGENT` globally or `$script:scenarioAgentCli`
per-script to force a specific backend. The `-Agent` CLI parameter overrides both.

## Agent client diagnostics (Claude Code)

The runner puts the agent client's host environment right **before** it launches
the agent, because two Claude Code diagnostics otherwise open every captured
artifact (`target/<timestamp>-<scenario>.raw.md`) and dominate the heartbeat
tail. A workspace that has never been opened interactively produces exactly these
two lines on every run:

```
Ignoring 13 permissions.allow entries from .claude/settings.json: this workspace
has not been trusted. Run Claude Code interactively here once and accept the
trust dialog, or set projects["D:/…"].hasTrustDialogAccepted: true in
C:\Users\pereg\.claude.json.
[claude-code:unrecognized_model] {"model":"deepseek-v4-flash[1m]","query_source":"sdk"}
```

Both describe the host machine, not the scenario, and both silently degrade a run:

| Diagnostic | Why it matters | What the harness does |
|---|---|---|
| Workspace not trusted | Claude Code discards the permission allowlist in `.claude/settings.json`, so the agent and its sub-agents run under a different permission set than the project configures. Every fresh clone or git worktree starts in this state. | Writes `projects["<repo root>"].hasTrustDialogAccepted = true` into `~/.claude.json` (or `$CLAUDE_CONFIG_DIR/.claude.json`) before launching `claude` — exactly what the warning instructs. The write is atomic, preserves every other key, and is skipped entirely when the project declares no `.claude/settings.json` / `settings.local.json`, or is already trusted. |
| Model missing from Claude Code's catalog | Claude Code cannot find the model and falls back to a 200k context window, so auto-compact can fire in the middle of a 20–40 minute evaluation and quietly drop the context it depends on. | Exports `CLAUDE_CODE_MAX_CONTEXT_TOKENS` derived from the window declared in the model name (`…[1M]` → `1000000`, `…[200k]` → `200000`) unless the variable is already set. The harness reads the model name; it never chooses the model. |

Set `BROWSER4_SKIP_AGENT_ENV_SETUP=1` to keep the harness out of the Claude Code
configuration completely — CI, or a developer who manages trust by hand. The unit
suite sets it for the same reason.

`CLAUDE_CODE_MAX_CONTEXT_TOKENS` removes the 200k auto-compact window but **not**
the `[claude-code:unrecognized_model]` line itself; that one is only silenced by
mapping the model in your own Claude Code settings (a `modelOverrides` /
`behavesAs` entry, or appending `[1m]` to the model name). Whichever way it is
configured, the runner reports what the client complained about at the end of a
run — `N agent-client diagnostic line(s) captured — host environment, not agent
findings:` — so client noise can never pass for agent output or a Browser4
finding.

## Console encoding (UTF-8)

Every script here pins `[Console]::OutputEncoding` to UTF-8 as it loads
(`common.ps1` for the whole module, `bin/test.ps1` for its own process). A
redirected stream otherwise starts on the Windows OEM code page: a pwsh child on
a CP936 host writes `·` as `A1 A4` and `═` as `A8 54`, and a UTF-8 reader — the
relay's `StandardOutputEncoding`, a CI log, the DeepSeek harness capture —
renders those as `??` and `?T`. That is how a heartbeat/tail block degrades into

```
  ?T?T last 10 lines ?T?T
  ?? claude --dangerously-skip-permissions ?? running (0m 30s elapsed, heartbeat #1)
```

The relay decodes children as UTF-8, so the child is the side that has to
agree — `common.ps1` pins it for every script that dot-sources the module, and
all of them do so before their first write. `common.tests.ps1` group 29 asserts
both halves: that loading the module forces code page 65001, and that the exact
decoration bytes survive a real capture (no `U+FFFD`, no `U+FFFD + T`).

## Anatomy of a task file

Task files live in `tasks/` and follow this format:

```markdown
# scenario-name

1. Go to https://example.com
2. Search for: something
3. Summarize the results.
```

The first `# Heading` becomes the scenario name; the body is the task prompt.

## Running every task at once

`run-tests.ps1` auto-discovers every `.md` in `tasks/` recursively and runs them
sequentially:

```powershell
# Run everything:
./browser4-tests/real-world-scenarios/scripts/run-tests.ps1

# List discovered tasks (shows category tags):
./browser4-tests/real-world-scenarios/scripts/run-tests.ps1 -List

# Run a subset by name:
./browser4-tests/real-world-scenarios/scripts/run-tests.ps1 search-summary amazon

# Stop on first failure:
./browser4-tests/real-world-scenarios/scripts/run-tests.ps1 -FailFast
```

### Running by category

Filter tasks by category with `-Category`:

```powershell
# Universal browser tasks (any agent — Playwright, Puppeteer, Selenium):
./browser4-tests/real-world-scenarios/scripts/run-tests.ps1 -Category generic

# Browser4-specific feature tests (X-SQL, crawl, agent, htmlsnapshot, loop):
./browser4-tests/real-world-scenarios/scripts/run-tests.ps1 -Category browser4

# All real-world tasks (generic + browser4):
./browser4-tests/real-world-scenarios/scripts/run-tests.ps1 -Category real-world

# MockSite-dependent tasks only:
./browser4-tests/real-world-scenarios/scripts/run-tests.ps1 -Category mock-site

# Combine with -List to preview:
./browser4-tests/real-world-scenarios/scripts/run-tests.ps1 -Category browser4 -List

# Combine with task name filter:
./browser4-tests/real-world-scenarios/scripts/run-tests.ps1 -Category generic amazon
```

Valid categories: `generic`, `browser4`, `real-world`, `mock-site`, `all` (default).

## Adding a new task

1. Create a new `.md` file in the appropriate category subdirectory:
   - `tasks/real-world/generic/` — universal scenarios (any browser agent can run)
   - `tasks/real-world/browser4/` — scenarios requiring browser4-specific features
   - `tasks/mock-site/` — scenarios requiring the local MockSite server
2. Add a `# scenario-name` heading and the task instructions
3. Save and run — `run-tests.ps1` discovers it automatically (recursive)

## Available tasks

47 task files covering all browser4-cli commands documented in the SKILL reference plus the 5 built-in Browser4 plugins.

### Real-World — Generic (`tasks/real-world/generic/`)

10 scenarios requiring no browser4-specific knowledge. These can also be used to
evaluate other browser automation agents (Playwright, Puppeteer, Selenium, etc.).

| Task file | Target | Scenario |
|-----------|--------|----------|
| `navigation-basics.md` | Wikipedia | Navigate pages, use history (back/forward), reload, check status |
| `visual-screenshot-controls.md` | Wikipedia | Resize viewport, scroll, wait, screenshot (full-page), PDF export |
| `search-summary.md` | Baidu | Search for 武汉龙虾节, summarize findings |
| `amazon.md` | Amazon | Search for whiteboard pens, compare top 4 |
| `amazon-calabi-yau.md` | Amazon | Search for Calabi-Yau gift, shortlist 10, pick best |
| `amazon-laser-engraved-crystal.md` | Amazon | Search for laser-engraved crystal gift, pick best |
| `amazon-cell-phone-60-pages.md` | Amazon | Collect 60 pages of search results in parallel, export the HTML |
| `hacker-news.md` | Hacker News | Navigate HN, open and summarize top 3 posts |
| `bot-stealth-check-md.md` | Bot-detection services | Run 5 online detection services, report whether Browser4 looks human |
| `qunar.md` | Qunar | 查询后天北京到上海的机票，找出最便宜的 3 个航班（无 `# heading`，中文提示词） |

### Real-World — Browser4 Specific (`tasks/real-world/browser4/`)

18 scenarios (13 feature scenarios plus the 5 plugin scenarios listed below)
exercising browser4-unique features (X-SQL, crawl, agent, loop, htmlsnapshot,
attach, named sessions, auto-diff).

| Task file | Target | Scenario |
|-----------|--------|----------|
| `session-management.md` | Wikipedia, HN | Open named sessions, switch between them, list/close all |
| `snapshot-mastery.md` | Wikipedia | Full/interactive/scoped/depth-limited snapshots, auto-diff, grep with all flags |
| `headed-window-visibility.md` | example.com | Headed session: the visible window must show the requested page (CDP target != about:blank), never a separate headless instance; headless must stay windowless; CLI warns only when a headed process lacks a window |
| `dom-snapshot-extraction.md` | books.toscrape.com | Capture HTML snapshot, get single/all values, export, summary, grep |
| `x-sql-query-methods.md` | books.toscrape.com | X-SQL via inline, @file, stdin, and base64; inspect for selector discovery |
| `htmlsnapshot-inspect-discovery.md` | books.toscrape.com | HTML snapshot inspection and selector discovery workflow |
| `tab-management.md` | Wikipedia, HN | Open, list, switch, and close multiple tabs across different sites |
| `agent-extraction.md` | Wikipedia | extract with schema, summarize with --selector, agent run/status/result |
| `agent-status-result-tracking.md` | Wikipedia | Full `agent run` → `status` → `result` → `list` lifecycle, terminal labels, `list --clear` |
| `crawl-link-discovery.md` | books.toscrape.com | Crawl with link discovery, seed files, format/output options |
| `loop-monitoring.md` | httpbin.org | Loop in plain-text/shell/subcommand modes, named loops, pause/resume/stop |
| `attach-remote-debug.md` | Chrome/Edge CDP | Attach to running Chrome/Edge via CDP, capture state |
| `attach-failure-boundaries.md` | Chrome CDP | Attach failure modes: unreachable endpoint fails loudly, page-less endpoint is an error, real current page reported, random-port discovery via channel |

### Real-World — Plugin Scenarios (`tasks/real-world/browser4/plugin-*.md`)

5 scenarios exercising each of the 5 built-in Browser4 plugins against real websites.

| Task file | Target | Plugin | Scenario |
|-----------|--------|--------|----------|
| `plugin-image-detection-download.md` | Wikipedia | browser4-images | Detect flag images, download single images, bulk download all |
| `plugin-markdown-conversion.md` | Wikipedia, httpbin | browser4-markdown | Convert pages to Markdown, discover links, crawl, fetch via HTTP |
| `plugin-media-video-detection.md` | W3Schools | browser4-media | Detect HTML5 video elements, download video, probe metadata |
| `plugin-pptx-generation.md` | Wikipedia, httpbin | browser4-pptx | Generate PowerPoint from rich/dense/simple pages, verify structure |
| `plugin-captcha-detection.md` | Google, hCaptcha, Cloudflare | browser4-captcha | Detect reCAPTCHA v2, hCaptcha, Turnstile; false-positive check |

### Mock Site (`tasks/mock-site/`)

19 scenarios requiring the local MockSite server (`./bin/test.ps1 mock-site`).

| Task file | Scenario |
|-----------|----------|
| `form-filling.md` | Fill HTML form: text fields, dropdowns, checkboxes, radio buttons, submit, verify |
| `advanced-mouse-interaction.md` | Hover tooltips, drag-and-drop, double-click, generate-locator, browser dialogs |
| `x-sql-extraction-functions.md` | X-SQL DOM/STR/ARRAY/LLM functions, PowerCSS :expr(), WHERE/ORDER BY/LIMIT |
| `javascript-evaluation.md` | eval with --json, --file, --stdin, --ref on dynamic page |
| `crawl-advanced-extraction.md` | Crawl with X-SQL, background mode, caching, priority, timeouts |
| `crawl-link-options.md` | Crawl with depth control, out-link CSS selectors (`-ol`), URL patterns (`-olp`), and seed files |
| `crawl-sql-formats.md` | Crawl with X-SQL via `--sql @file` and `--sql-stdin`, CSV and table output formats |
| `swarm-parallel-scraping.md` | Swarm session, parallel X-SQL extraction, headless mode |
| `storage-state-management.md` | Cookies (set with all flags), localStorage, sessionStorage, state-save/load |
| `comprehensive-ecommerce-workflow.md` | End-to-end e-commerce research: 18 steps combining 15+ commands |
| `network-observability.md` | `network requests/request/route/unroute`, HAR recording (`network har start/stop`) against the network fixture |
| `console-errors-and-cdp.md` | `console`, `errors`, `dialog status`, raw `cdp` calls, and generic `tool call` passthrough |
| `iframes-and-popups.md` | `frames` / `frame` scoping (including nested frames) and `window new` targets |
| `file-upload-and-download.md` | `upload` (single + multi-file, error paths), `download --dir`, `wait --download` |
| `device-and-network-emulation.md` | `set geo/offline/headers/media/device`, `delete data`, `resize` |
| `element-assertions-and-focus.md` | `is visible/enabled/checked`, `check`/`uncheck`, `focus`, `scrollintoview`, `highlight`, `pushstate` |
| `decision-tree/extraction-method-routing.md` | Decision-tree coverage for extraction choices: interact-first, get, get all, query, eval, extract, crawl |
| `decision-tree/bulk-scale-routing.md` | Decision-tree coverage for bulk-routing choices: single page, crawl, swarm, loop, and few-URL shell loops |
| `decision-tree/webminer-structuring-routing.md` | Decision-tree coverage for WebMiner routing: single-page export, crawl/swarm acquisition, free vs commercial tiers |

> **Note:** MockSite tasks require the mock server running on `localhost:18080`. Start it with `./bin/test.ps1 mock-site`.

## Running in production mode

To test against the globally installed `browser4-cli` (not `cargo run`), use
the production wrappers:

```powershell
# Single task in production mode:
./browser4-tests/real-world-scenarios/scripts/run-task-production.ps1 -TaskFile tasks/real-world/generic/amazon.md

# With silent output:
./browser4-tests/real-world-scenarios/scripts/run-task-production.ps1 -TaskFile tasks/real-world/generic/search-summary.md -Silent

# Batch run by category in production mode:
./browser4-tests/real-world-scenarios/scripts/run-tests-production.ps1 -Category generic
./browser4-tests/real-world-scenarios/scripts/run-tests-production.ps1 -Category browser4 -List
```

This replaces the per-task wrapper scripts previously in
`browser4-tests/real-world-scenarios/`. The production wrapper sets
`$browser4cliMode = 'production'` so `common.ps1` resolves the CLI as
`browser4-cli help` and loads the skill reference from
`https://browser4.io/SKILL.md`.

## Script reference

| Script | Purpose |
|--------|---------|
| `common.ps1` | Shared evaluation prompt (`$generalPrompt`) and agent invocation (`Invoke-Agent`) |
| `run-task.ps1` | Single-task runner — reads a `.md` task file and invokes the agent |
| `run-task-production.ps1` | Production wrapper — sets `$browser4cliMode = 'production'` and delegates to `run-task.ps1` |
| `run-tests.ps1` | Batch runner — recursive discovery, `-Category` filter, `-List`, task name filter, `-FailFast` |
| `run-tests-production.ps1` | Production batch runner — delegates to `run-tests.ps1` with `-Production`, supports `-Category` |
| `common.tests.ps1` | Unit tests for `common.ps1` |
