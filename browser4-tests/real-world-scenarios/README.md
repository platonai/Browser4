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
the configured agent CLI (`claude` or `kimi`, auto-detected in that order; force
one by setting `$script:scenarioAgentCli` before calling `Invoke-Agent`).

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

30 task files covering all browser4-cli commands documented in the SKILL reference plus the 5 built-in Browser4 plugins.

### Real-World — Generic (`tasks/real-world/generic/`)

7 scenarios requiring no browser4-specific knowledge. These can also be used to
evaluate other browser automation agents (Playwright, Puppeteer, Selenium, etc.).

| Task file | Target | Scenario |
|-----------|--------|----------|
| `navigation-basics.md` | Wikipedia | Navigate pages, use history (back/forward), reload, check status |
| `visual-screenshot-controls.md` | Wikipedia | Resize viewport, scroll, wait, screenshot (full-page), PDF export |
| `search-summary.md` | Baidu | Search for 武汉龙虾节, summarize findings |
| `amazon.md` | Amazon | Search for whiteboard pens, compare top 4 |
| `hacker-news.md` | Hacker News | Navigate HN, open and summarize top 3 posts |
| `amazon-calabi-yau.md` | Amazon | Search for Calabi-Yau gift, shortlist 10, pick best |
| `amazon-laser-engraved-crystal.md` | Amazon | Search for laser-engraved crystal gift, pick best |

### Real-World — Browser4 Specific (`tasks/real-world/browser4/`)

10 scenarios exercising browser4-unique features (X-SQL, crawl, agent, loop,
htmlsnapshot, attach, named sessions, auto-diff).

| Task file | Target | Scenario |
|-----------|--------|----------|
| `session-management.md` | Wikipedia, HN | Open named sessions, switch between them, list/close all |
| `snapshot-mastery.md` | Wikipedia | Full/interactive/scoped/depth-limited snapshots, auto-diff, grep with all flags |
| `html-snapshot-extraction.md` | books.toscrape.com | Capture HTML snapshot, get single/all values, export, summary, grep |
| `x-sql-query-methods.md` | books.toscrape.com | X-SQL via inline, @file, stdin, and base64; inspect for selector discovery |
| `htmlsnapshot-inspect-discovery.md` | books.toscrape.com | HTML snapshot inspection and selector discovery workflow |
| `tab-management.md` | Wikipedia, HN | Open, list, switch, and close multiple tabs across different sites |
| `agent-extraction.md` | Wikipedia | extract with schema, summarize with --selector, agent run/status/result |
| `crawl-link-discovery.md` | books.toscrape.com | Crawl with link discovery, seed files, format/output options |
| `loop-monitoring.md` | httpbin.org | Loop in plain-text/shell/subcommand modes, named loops, pause/resume/stop |
| `attach-remote-debug.md` | Chrome/Edge CDP | Attach to running Chrome/Edge via CDP, capture state |

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

8 scenarios requiring the local MockSite server (`./bin/test.ps1 mock-site`).

| Task file | Scenario |
|-----------|----------|
| `form-filling.md` | Fill HTML form: text fields, dropdowns, checkboxes, radio buttons, submit, verify |
| `advanced-mouse-interaction.md` | Hover tooltips, drag-and-drop, double-click, generate-locator, browser dialogs |
| `x-sql-extraction-functions.md` | X-SQL DOM/STR/ARRAY/LLM functions, PowerCSS :expr(), WHERE/ORDER BY/LIMIT |
| `javascript-evaluation.md` | eval with --json, --file, --stdin, --ref on dynamic page |
| `crawl-advanced-extraction.md` | Crawl with X-SQL, background mode, caching, priority, timeouts |
| `swarm-parallel-scraping.md` | Swarm session, parallel X-SQL extraction, headless mode |
| `storage-state-management.md` | Cookies (set with all flags), localStorage, sessionStorage, state-save/load |
| `comprehensive-ecommerce-workflow.md` | End-to-end e-commerce research: 18 steps combining 15+ commands |

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
