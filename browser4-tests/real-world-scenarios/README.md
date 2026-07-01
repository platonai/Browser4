# Agent Scenario Tests

Markdown task files and PowerShell runners that evaluate browser4-cli usability
through an LLM agent. Each task defines a real-world scenario and asks the agent
to complete it while simultaneously evaluating the CLI's discoverability,
documentation, and reliability from a first-time user's perspective.

## Quick start (standalone)

```powershell
# From the repo root:
./browser4-tests/real-world-scenarios/scripts/run-task.ps1 -TaskFile tasks/search-summary.md
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

1. Create a new `.md` file in `tasks/`, e.g. `tasks/my-scenario.md`
2. Add a `# scenario-name` heading and the task instructions
3. Save and run — `run-tests.ps1` discovers it automatically

## Available tasks

25 task files covering all browser4-cli commands documented in the SKILL reference.

### Navigation & Session

| Task file | Scenario |
|-----------|----------|
| `tasks/navigation-basics.md` | Navigate Wikipedia, use history (back/forward), reload, check status |
| `tasks/session-management.md` | Open named sessions, switch between them, list/close all |

### Page Interaction

| Task file | Scenario |
|-----------|----------|
| `tasks/form-filling.md` | Fill a MockSite HTML form: text fields, dropdowns, checkboxes, submit, verify |
| `tasks/advanced-mouse-interaction.md` | Hover, drag, double-click, generate-locator, handle dialogs on MockSite |

### Snapshot

| Task file | Scenario |
|-----------|----------|
| `tasks/snapshot-mastery.md` | Full/interactive/scoped/depth-limited snapshots, auto-diff, grep with all flags |

### DOM Snapshot & X-SQL

| Task file | Scenario |
|-----------|----------|
| `tasks/dom-snapshot-extraction.md` | Capture DOM snapshot, get single/all values, export, summary, grep |
| `tasks/x-sql-query-methods.md` | X-SQL via inline, @file, stdin, and base64; inspect for selector discovery |
| `tasks/x-sql-extraction-functions.md` | X-SQL DOM/STR/ARRAY/LLM functions, PowerCSS :expr(), WHERE/ORDER BY/LIMIT |
| `tasks/domsnapshot-inspect-discovery.md` | DOM inspection and selector discovery workflow |

### JavaScript, Visual, Tabs, Storage

| Task file | Scenario |
|-----------|----------|
| `tasks/javascript-evaluation.md` | eval with --json, --file, --stdin, --ref on MockSite |
| `tasks/visual-screenshot-controls.md` | Resize viewport, scroll, wait, screenshot (full-page), PDF export |
| `tasks/tab-management.md` | Open, list, switch, and close multiple tabs across different sites |
| `tasks/storage-state-management.md` | Cookies (set with all flags), localStorage, sessionStorage, state-save/load |

### Agent & Extraction

| Task file | Scenario |
|-----------|----------|
| `tasks/agent-extraction.md` | extract with schema, summarize with --selector, agent run/status/result |

### Crawl, Swarm, Loop

| Task file | Scenario |
|-----------|----------|
| `tasks/crawl-link-discovery.md` | Crawl with link discovery, seed files, format/output options |
| `tasks/crawl-advanced-extraction.md` | Crawl with X-SQL, background mode, caching, priority, timeouts |
| `tasks/swarm-parallel-scraping.md` | Swarm session, parallel X-SQL extraction, headless mode |
| `tasks/loop-monitoring.md` | Loop in plain-text/shell/subcommand modes, named loops, pause/resume/stop |

### Specialized & Capstone

| Task file | Scenario |
|-----------|----------|
| `tasks/attach-remote-debug.md` | Attach to running Chrome/Edge via CDP, capture state |
| `tasks/comprehensive-ecommerce-workflow.md` | End-to-end e-commerce research: 18 steps combining 15+ commands |

### Web Search & Shopping

| Task file | Scenario |
|-----------|----------|
| `tasks/search-summary.md` | Search Baidu for 武汉龙虾节, summarize findings |
| `tasks/amazon.md` | Search Amazon for whiteboard pens, compare top 4 |
| `tasks/hacker-news.md` | Navigate HN, open and summarize top 3 posts |
| `tasks/amazon-calabi-yau.md` | Search Amazon for Calabi-Yau gift, shortlist 10, pick best |
| `tasks/amazon-laser-engraved-crystal.md` | Search Amazon for laser-engraved crystal gift, pick best |

> **Note:** Tasks marked with a MockSite dependency require the mock server running on `localhost:18080`. Start it with `./bin/test.ps1 mock-site`.

## Running in production mode

To test against the globally installed `browser4-cli` (not `cargo run`), use
`run-task-production.ps1`:

```powershell
# Single task in production mode:
./browser4-tests/real-world-scenarios/scripts/run-task-production.ps1 -TaskFile tasks/amazon.md

# With silent output:
./browser4-tests/real-world-scenarios/scripts/run-task-production.ps1 -TaskFile tasks/search-summary.md -Silent
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
