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
`claude`.

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

`run-tests.ps1` auto-discovers every `.md` in `tasks/` and runs them
sequentially:

```powershell
# Run everything:
./browser4-tests/real-world-scenarios/scripts/run-tests.ps1

# List discovered tasks:
./browser4-tests/real-world-scenarios/scripts/run-tests.ps1 -List

# Run a subset:
./browser4-tests/real-world-scenarios/scripts/run-tests.ps1 search-summary amazon

# Stop on first failure:
./browser4-tests/real-world-scenarios/scripts/run-tests.ps1 -FailFast
```

New task files placed in `tasks/` are picked up automatically — no
registration step is needed.

## Adding a new task

1. Create a new `.md` file in the appropriate category subdirectory:
   - `tasks/real-world/generic/` — universal scenarios (any browser agent can run)
   - `tasks/real-world/browser4/` — scenarios requiring browser4-specific features
   - `tasks/mock-site/` — scenarios requiring the local MockSite server
2. Add a `# scenario-name` heading and the task instructions
3. Save and run — `run-tests.ps1` discovers it automatically (recursive)

## Available tasks

25 task files covering all browser4-cli commands documented in the SKILL reference.

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
domsnapshot, attach, named sessions, auto-diff).

| Task file | Target | Scenario |
|-----------|--------|----------|
| `session-management.md` | Wikipedia, HN | Open named sessions, switch between them, list/close all |
| `snapshot-mastery.md` | Wikipedia | Full/interactive/scoped/depth-limited snapshots, auto-diff, grep with all flags |
| `dom-snapshot-extraction.md` | books.toscrape.com | Capture DOM snapshot, get single/all values, export, summary, grep |
| `x-sql-query-methods.md` | books.toscrape.com | X-SQL via inline, @file, stdin, and base64; inspect for selector discovery |
| `domsnapshot-inspect-discovery.md` | books.toscrape.com | DOM inspection and selector discovery workflow |
| `tab-management.md` | Wikipedia, HN | Open, list, switch, and close multiple tabs across different sites |
| `agent-extraction.md` | Wikipedia | extract with schema, summarize with --selector, agent run/status/result |
| `crawl-link-discovery.md` | books.toscrape.com | Crawl with link discovery, seed files, format/output options |
| `loop-monitoring.md` | httpbin.org | Loop in plain-text/shell/subcommand modes, named loops, pause/resume/stop |
| `attach-remote-debug.md` | Chrome/Edge CDP | Attach to running Chrome/Edge via CDP, capture state |

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
`run-task-production.ps1`:

```powershell
# Single task in production mode:
./browser4-tests/real-world-scenarios/scripts/run-task-production.ps1 -TaskFile tasks/real-world/generic/amazon.md

# With silent output:
./browser4-tests/real-world-scenarios/scripts/run-task-production.ps1 -TaskFile tasks/real-world/generic/search-summary.md -Silent
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
| `run-tests.ps1` | Batch runner — discovers and runs all tasks in `tasks/` |
| `common.tests.ps1` | Unit tests for `common.ps1` |
