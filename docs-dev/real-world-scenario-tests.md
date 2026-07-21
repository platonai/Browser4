# Real-World Scenario Tests

> Generated 2026-07-21 from the `4.12.x` branch.

## Overview

The Browser4 real-world scenario test system uses an **LLM agent** (Claude Code or Kimi Code) to simultaneously complete browser automation tasks AND evaluate browser4-cli's usability. Unlike traditional assertion-based E2E tests, each scenario produces a structured evaluation covering documentation gaps, discoverability problems, UX friction, and reliability issues — from a first-time user's perspective.

```
Task File (.md/.txt)  →  PowerShell Runner  →  LLM Agent  →  browser4-cli  →  Real Websites
                                                    │
                                                    ▼
                                            Structured Output
                                         (Task Result + Issues
                                          + Overall Assessment)
```

## File Layout

```
browser4-tests/real-world-scenarios/
├── README.md                          ← This documentation
├── tasks/
│   ├── real-world/
│   │   ├── generic/                   ← 7 scenarios (any browser agent)
│   │   └── browser4/                  ← 10 scenarios + 5 plugin scenarios
│   └── mock-site/                     ← 8 scenarios (needs localhost:18080)
└── scripts/
    ├── common.ps1                     ← $generalPrompt, Invoke-Agent, issue parsing
    ├── run-task.ps1                   ← Single-task runner
    ├── run-tests.ps1                  ← Batch runner (category filter, discovery)
    ├── run-task-production.ps1        ← Production-mode wrapper
    ├── run-tests-production.ps1       ← Production batch wrapper
    ├── run-all-scenarios.ps1          ← Full orchestration (~46 scenarios)
    ├── run-use-case.ps1               ← .txt use-case runner
    ├── watchdog.ps1                   ← Crash recovery + auto-restart
    ├── orchestration-common.ps1       ← Shared state, token parsing, reports
    ├── orchestration.tests.ps1        ← Tests for orchestration helpers
    ├── common.tests.ps1               ← Unit tests for common.ps1
    └── run-task.sh                    ← Bash wrapper (Git Bash / WSL)
```

Also participates in the scenario system:

```
browser4-tests/pulsar-tests-common/src/main/resources/e2e/scenarios/happy_path/use-cases/
├── 01-ecommerce-product-comparison.txt
├── 02-job-listing-extraction.txt
├── 03-saas-pricing-analysis.txt
├── 04-community-sentiment-scan.txt
├── 05-cloud-console-alert-check.txt
├── 06-deep-ecommerce-category-analysis.txt
├── 07-job-market-skill-demand-analysis.txt
├── 08-open-source-project-health.txt
├── 09-competitive-product-analysis.txt
├── 10-technology-trend-validation.txt
├── 11-hiring-market-cross-platform-comparison.txt
├── 12-company-due-diligence-automation.txt
├── 13-ongoing-competitive-monitoring.txt
├── 14-enterprise-operations-inspection.txt
├── 20-zh-baidu-baike-company-compare.txt
├── 21-zh-zhihu-topic-hot-questions.txt
├── 22-zh-weather-forecast-compare.txt
├── 23-zh-gov-policy-summary.txt
└── 24-zh-people-daily-headlines.txt
```

## How It Works

### 1. Task Files

Each task is a markdown file with a `# heading` (scenario name) followed by numbered instructions:

```markdown
# search-summary

1. Open https://www.baidu.com
2. Search for: 武汉龙虾节
3. Read multiple relevant results.
4. Summarize findings.
```

The heading becomes the scenario name; the body is the task prompt.

### 2. Shared Evaluation Prompt (`common.ps1`)

Every task is prefixed with `$generalPrompt` (~450 lines of instructions) that tells the agent to:

1. **Prepare**: run `./b4w.ps1 help`, read `SKILL.md`
2. **Evaluate across 7 dimensions**: Installation & Setup, Discoverability, Documentation, CLI Experience, Task Execution, Reliability, UX
3. **Produce structured output**:
   - **A. Task Result** — the requested outcome
   - **B. Execution Trace** — commands used, steps, workarounds
   - **C. Issues Found** — each with: Severity, Category, Reproduction, Expected/Actual, Root Cause, Code Pointer, AI Suggested Improvement, Human Review checkboxes
   - **D. Overall Assessment** — completion status, success rate, usability rating (1–10)

### 3. Agent Invocation

The runner invokes `claude` or `kimi` (auto-detected, `claude` preferred) with `--dangerously-skip-permissions` for unattended runs. Output streams to console in real-time AND is captured to `target/<timestamp>-<scenario>.raw.md`.

### 4. Post-Processing

After the agent completes, `Write-IssuesToReadyQueue` parses the output:
- **Full output** → saved as `.full.md` in `coworker/tasks/issues/draft/`
- **Individual issues** → parsed from Section C via regex, written as `.issues.md` with background context and reproduction guide
- **JSON schema** → `ConvertTo-IssueJson` produces structured JSON matching `coworker/gui/frontend/issue-model.js`

### 5. Dev vs Production Mode

| | Dev (default) | Production |
|---|---|---|
| CLI invocation | `./b4w.ps1` | `browser4-cli` |
| SKILL.md source | `skills/browser4-cli/SKILL.md` (local) | `https://browser4.io/SKILL.md` |
| Backend | Auto-started from local JAR | Separately managed server |
| What it tests | Current local source | Installed release |

Set via `$browser4cliMode = 'production'` or `$env:BROWSER4CLI_MODE = 'production'`.

## Category Reference

### Real-World — Generic (7 scenarios)

Tests that any browser automation tool (Playwright, Puppeteer, Selenium) could run. No browser4-specific knowledge required.

| Task | Target | Scenario |
|------|--------|----------|
| `navigation-basics` | Wikipedia | Navigate pages, history (back/forward), reload, check status |
| `visual-screenshot-controls` | Wikipedia | Resize viewport, scroll, wait, screenshot (full-page), PDF export |
| `search-summary` | Baidu | Search for 武汉龙虾节, summarize findings |
| `amazon` | Amazon | Search for whiteboard pens, compare top 4 |
| `hacker-news` | Hacker News | Navigate HN, open and summarize top 3 posts |
| `amazon-calabi-yau` | Amazon | Search for Calabi-Yau gift, shortlist 10, pick best |
| `amazon-laser-engraved-crystal` | Amazon | Search for laser-engraved crystal gift, pick best |

### Real-World — Browser4 Specific (10 scenarios)

Exercises browser4-unique features: X-SQL, crawl, agent mode, loop, htmlsnapshot, attach, named sessions, auto-diff.

| Task | Target | Key Features |
|------|--------|-------------|
| `session-management` | Wikipedia, HN | Named sessions, switch/list/close all |
| `snapshot-mastery` | Wikipedia | Full/interactive/scoped/depth-limited snapshots, auto-diff, grep (all flags) |
| `htmlsnapshot-extraction` | books.toscrape.com | HTML snapshot get single/all values, export, summary, grep |
| `x-sql-query-methods` | books.toscrape.com | X-SQL via inline, @file, stdin, base64; inspect for selector discovery |
| `htmlsnapshot-inspect-discovery` | books.toscrape.com | HTML snapshot inspection + selector discovery workflow |
| `tab-management` | Wikipedia, HN | Open, list, switch, close multiple tabs |
| `agent-extraction` | Wikipedia | extract with schema, summarize with --selector, agent run/status/result |
| `crawl-link-discovery` | books.toscrape.com | crawl with link discovery, seed files, format/output options |
| `loop-monitoring` | httpbin.org | Loop in text/shell/subcommand modes, named loops, pause/resume/stop |
| `attach-remote-debug` | Chrome/Edge CDP | Attach to running Chrome/Edge via CDP, capture state |

### Plugins (5 scenarios)

Tests each of the 5 built-in Browser4 plugins against real websites.

| Task | Target | Plugin |
|------|--------|--------|
| `plugin-image-detection-download` | Wikipedia | browser4-images |
| `plugin-markdown-conversion` | Wikipedia, httpbin | browser4-markdown |
| `plugin-media-video-detection` | W3Schools | browser4-media |
| `plugin-pptx-generation` | Wikipedia, httpbin | browser4-pptx |
| `plugin-captcha-detection` | Google, hCaptcha, Cloudflare | browser4-captcha |

### Mock Site (8 scenarios)

Requires the local MockSite server on `localhost:18080`. Start with `./bin/test.ps1 mock-site`.

| Task | Scenario |
|------|----------|
| `form-filling` | Fill HTML form: text fields, dropdowns, checkboxes, radio buttons, submit, verify |
| `advanced-mouse-interaction` | Hover tooltips, drag-and-drop, double-click, generate-locator, browser dialogs |
| `x-sql-extraction-functions` | X-SQL DOM/STR/ARRAY/LLM functions, PowerCSS :expr(), WHERE/ORDER BY/LIMIT |
| `javascript-evaluation` | eval with --json, --file, --stdin, --ref on dynamic page |
| `crawl-advanced-extraction` | Crawl with X-SQL, background mode, caching, priority, timeouts |
| `swarm-parallel-scraping` | Swarm session, parallel X-SQL extraction, headless mode |
| `storage-state-management` | Cookies (set with all flags), localStorage, sessionStorage, state-save/load |
| `comprehensive-ecommerce-workflow` | 18-step end-to-end: 15+ commands combined |

### Use Cases (19 scenarios)

Structured `.txt` files with difficulty levels:

| Level | Count | Examples |
|-------|-------|----------|
| Simple | 5 | E-commerce product comparison, job listing extraction, SaaS pricing |
| Complex | 6 | Deep category analysis, skill demand analysis, open-source health |
| Enterprise | 3 | Company due diligence, competitive monitoring, operations inspection |
| Chinese | 5 | Baidu Baike company compare, Zhihu hot questions, weather forecast, gov policy, People's Daily headlines |

## Running Tests

### Quick Start

```powershell
# From the repo root:

# List all discovered tasks
./browser4-tests/real-world-scenarios/scripts/run-tests.ps1 -List

# Run a single task (dev mode — uses ./b4w.ps1)
./browser4-tests/real-world-scenarios/scripts/run-task.ps1 -TaskFile tasks/real-world/generic/search-summary.md

# Run a category
./browser4-tests/real-world-scenarios/scripts/run-tests.ps1 -Category generic
./browser4-tests/real-world-scenarios/scripts/run-tests.ps1 -Category browser4
./browser4-tests/real-world-scenarios/scripts/run-tests.ps1 -Category mock-site

# Run named tasks
./browser4-tests/real-world-scenarios/scripts/run-tests.ps1 search-summary amazon

# Stop on first failure
./browser4-tests/real-world-scenarios/scripts/run-tests.ps1 -FailFast

# Preview a category without running
./browser4-tests/real-world-scenarios/scripts/run-tests.ps1 -Category browser4 -List
```

### Production Mode

```powershell
# Single task
./browser4-tests/real-world-scenarios/scripts/run-task-production.ps1 -TaskFile tasks/real-world/generic/amazon.md

# Batch by category
./browser4-tests/real-world-scenarios/scripts/run-tests-production.ps1 -Category generic
```

### Full Orchestration (`run-all-scenarios.ps1`)

Runs all ~46 scenarios with resilience features:

```powershell
# Run everything (dev mode, with watchdog)
./browser4-tests/real-world-scenarios/scripts/run-all-scenarios.ps1

# Standalone (no watchdog)
./browser4-tests/real-world-scenarios/scripts/run-all-scenarios.ps1 -NoWatchdog

# Resume from previous run
./browser4-tests/real-world-scenarios/scripts/run-all-scenarios.ps1 -Resume

# Run a subset
./browser4-tests/real-world-scenarios/scripts/run-all-scenarios.ps1 -From "search-summary" -To "amazon"

# Production mode
./browser4-tests/real-world-scenarios/scripts/run-all-scenarios.ps1 -Production

# Force re-run all
./browser4-tests/real-world-scenarios/scripts/run-all-scenarios.ps1 -Force

# List all discovered scenarios
./browser4-tests/real-world-scenarios/scripts/run-all-scenarios.ps1 -List
```

**Orchestration features:**
- **State persistence** — JSON state file survives crashes; resume skips completed scenarios
- **Watchdog** — monitors orchestrator PID + heartbeat; restarts on crash (up to 3 times)
- **Credit exhaustion detection** — 18 regex patterns detect billing/auth failures; aborts immediately
- **Consecutive failure abort** — aborts after N consecutive failures (default 5, configurable)
- **Per-scenario token tracking** — parses "Breakdown by AI model:" from Claude output
- **Live progress** — progress bar with ETA, pass/fail counts, token totals
- **Final reports** — JSON + Markdown reports in `target/test-reports/reports/`

### Prerequisites

- **Agent CLI**: `claude` (Claude Code) or `kimi` (Kimi Code) on PATH
- **Java runtime**: for the backend in dev mode
- **Active LLM subscription**: scenarios consume API credits/tokens
- **MockSite**: `./bin/test.ps1 mock-site` for mock-site category tasks

## Output Structure

Each task run produces files in two locations:

### Agent Output (`target/`)
```
target/<timestamp>-<scenario>.raw.md    ← Full agent output (all sections A–D)
```

### Issue Queue (`coworker/tasks/issues/draft/`)
```
<timestamp>-<scenario>.full.md          ← Copy of full agent output
<timestamp>-<scenario>.issues.md        ← Parsed issues with reproduction guide
```

The `.issues.md` file includes:
- **Scenario Background** — what task was run, key commands, workarounds applied
- **Each issue** — severity, category, reproduction, expected/actual, root cause, code pointer, AI suggestion
- **Human Review checkboxes** — ACCEPT / ACCEPT with improvements / DEFER / WONTFIX / REJECT
- **Reproduction Guide** — common setup + per-issue steps

### Orchestration Reports (`target/test-reports/`)
```
target/test-reports/
├── state/orchestration-state.json      ← Resume state (scenario statuses, token totals)
├── reports/<ts>-run-full.json          ← Final JSON report
├── reports/<ts>-run-full.md            ← Final Markdown report
└── scenarios/<ts>-<scenario>.raw.md    ← Per-scenario captured output
```

## Adding a New Scenario

1. Create a `.md` file in the appropriate directory:
   - `tasks/real-world/generic/` — universal scenarios
   - `tasks/real-world/browser4/` — browser4-specific features
   - `tasks/mock-site/` — needs MockSite server

2. Follow the format:
   ```markdown
   # my-scenario-name
   
   1. Go to https://example.com
   2. Perform some action
   3. Verify the result
   ```

3. Run it — discovery is recursive, so `run-tests.ps1` picks it up automatically:
   ```powershell
   ./browser4-tests/real-world-scenarios/scripts/run-tests.ps1 -List
   ```

4. For MockSite-dependent tasks, include a note at the top:
   ```markdown
   Before running this scenario, ensure MockSite is running on localhost:18080 (`./bin/test.ps1 mock-site`).
   ```

## Script Reference

| Script | Purpose |
|--------|---------|
| `common.ps1` | `$generalPrompt` (~450 lines), `Invoke-Agent`, `Start-NativeCommand`, `Write-IssuesToReadyQueue`, `ConvertFrom-IssuesSection`, token parsing, Windows command-line escaping, stdin redirect for large prompts |
| `run-task.ps1` | Reads a `.md` task file, combines with `$generalPrompt`, invokes agent |
| `run-tests.ps1` | Recursive discovery, `-Category` filter, `-List`, task name filter, `-FailFast`, `-TimeoutMinutes` |
| `run-task-production.ps1` | Sets `$browser4cliMode = 'production'`, delegates to `run-task.ps1` |
| `run-tests-production.ps1` | Sets production mode, delegates to `run-tests.ps1` |
| `run-all-scenarios.ps1` | Full orchestration: state persistence, watchdog, credit detection, consecutive-failure abort, JSON+MD reports |
| `run-use-case.ps1` | Parses `.txt` use-case files, runs via agent |
| `watchdog.ps1` | Monitors orchestrator PID + heartbeat; restarts on crash (max 3); detects hung processes via stale heartbeat |
| `orchestration-common.ps1` | State management (init/read/write with file locking), scenario discovery, token parsing, credit exhaustion detection, use-case parsing, progress display, final report generation |
| `orchestration.tests.ps1` | Unit tests for orchestration-common.ps1 |
| `common.tests.ps1` | Unit tests for common.ps1 |

## Design Decisions

### Why LLM-driven?

Traditional E2E tests verify that specific inputs produce specific outputs. They can't answer:
- "Can a new user figure out how to do X?"
- "Is the documentation clear enough?"
- "What's the most confusing part of the workflow?"
- "Does the CLI surface helpful error messages?"

The LLM agent acts as a **perpetual new user** — it reads the docs fresh each time, follows instructions literally, and reports every point of confusion.

### Prompt is the Test Harness

The `$generalPrompt` in `common.ps1` is the most important "code" in this system. It defines:
- How the agent discovers commands (help + SKILL.md)
- What counts as an issue (7 categories)
- The structured output format (sections A–D)
- The issue template (severity, reproduction, root cause, code pointer)

Changing the prompt changes what the tests measure.

### Resilience by Default

The orchestration layer (`run-all-scenarios.ps1` + `watchdog.ps1`) assumes things will go wrong:
- LLM APIs have outages and rate limits
- Processes crash
- Credits run out mid-run
- Networks are flaky

State is persisted after every scenario so a 45-scenario run that crashes on #43 can resume from #43, not from #1.

### Token Tracking

`ConvertFrom-TokenUsage` parses the "Breakdown by AI model:" section from Claude's session summary using the same regex as `coworker/scripts/workers/count-total-token-usage.py`. This gives per-scenario and per-model token accounting.

## Relationship to Other Test Suites

| Test suite | What it tests | Runner |
|-----------|---------------|--------|
| **Rust unit tests** | CLI arg parsing, command compilation, batch logic | `cargo test --bin browser4-cli` |
| **Rust E2E tests** | CLI ↔ backend integration, mock server scenarios | `cargo test --test e2e` |
| **Kotlin tests** | Backend controllers, argument normalization, MCP dispatch | `mvn test -pl browser4-rest` |
| **Real-world scenarios** (this) | End-to-end usability, discoverability, documentation, reliability | PowerShell + LLM agent |

The real-world scenarios complement the others: they don't replace assertion-based tests, but they catch problems the other suites structurally cannot — documentation blind spots, confusing UX, undiscoverable features, and brittle error handling.
