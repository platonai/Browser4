---
name: maintenance
title: "Maintenance — Config-driven quality checks and health monitoring"
description: "Orchestrates 30 check scripts across 9 categories (code, tests, docs, SKILLs, deps, infra). Use when asked to run maintenance, check quality, verify docs, audit SKILLs, or clean up artifacts."
allowed-tools: Bash(pwsh:*), Bash(./bin/maintenance/*)
---

# Maintenance

A config-driven, cross-platform maintenance system that periodically verifies
code quality, document correctness, and SKILL documentation AI-friendliness.
The orchestrator reads `bin/maintenance/config.psd1`, runs check scripts on a
schedule, persists results to shared state, and reports through multiple
formatters (console, JSON, markdown, GitHub annotations).

## When to Use

- "run maintenance"
- "run CI checks" / "run nightly checks"
- "check code quality"
- "verify SKILL frontmatter"
- "audit documentation links"
- "check for dead code"
- "run coverage check"
- "clean up build artifacts"
- "add a new maintenance check"
- "force-run all maintenance tasks"
- "check the maintenance state"

## How It Works

The **orchestrator** (`bin/maintenance/orchestrator.ps1`) is the central entry
point. It reads task definitions from `config.psd1`, checks the shared state
file to avoid redundant re-runs (unless `-Force`), executes each task with the
configured interval, collects result objects, and routes them through reporters.

**Result object contract** — every check script emits a `PSCustomObject`:
```powershell
@{
    CheckId    = "A1"
    Name       = "Compilation Check"
    Status     = "passed"  # passed | failed | skipped | error
    DurationMs = 1234
    ExitCode   = 0
    Details    = "Maven + Cargo compile OK"
    Results    = @( @{ Item="..."; Status="passed"; Message="..." } )
    Artifacts  = @( "bin/maintenance/logs/A1-20260714.json" )
    Timestamp  = "2026-07-14T14:30:22Z"
}
```

## Usage

All commands are run from the repository root.

### Orchestrator (primary entry point)

```bash
# One pass (run pending tasks, skip if still within interval)
pwsh bin/maintenance/orchestrator.ps1 -Once

# Continuous loop (dev mode — warn only, never fails)
pwsh bin/maintenance/orchestrator.ps1

# Force all tasks regardless of last-run state
pwsh bin/maintenance/orchestrator.ps1 -Force -Once

# CI mode (strict — first failure exits 1)
MAINTENANCE_MODE=ci pwsh bin/maintenance/orchestrator.ps1 -Once

# Nightly mode (collects all failures, reports at end)
MAINTENANCE_MODE=nightly pwsh bin/maintenance/orchestrator.ps1 -Once
```

### CI entry points

```bash
# Per-commit safety checks (fast)
pwsh bin/maintenance/ci/invoke-ci-checks.ps1

# Full nightly suite (thorough)
pwsh bin/maintenance/ci/invoke-nightly-checks.ps1
```

### Running individual checks

```bash
# Syntax-check all PS1 scripts
pwsh bin/maintenance/checks/check-ps1-syntax.ps1

# Validate SKILL.md YAML frontmatter
pwsh bin/maintenance/checks/check-skill-frontmatter.ps1

# Validate internal doc links
pwsh bin/maintenance/checks/check-doc-links-internal.ps1

# Check version consistency across files
pwsh bin/maintenance/checks/check-version-consistency.ps1

# Run code coverage check
pwsh bin/maintenance/checks/check-coverage.ps1

# Detect dead code
pwsh bin/maintenance/checks/check-dead-code.ps1

# Clean stale build artifacts (dry-run first)
pwsh bin/maintenance/checks/clean-build-artifacts.ps1
```

## Execution Modes

| Mode | Behavior | Trigger |
|------|----------|---------|
| `ci` | Strict: any failure exits 1 immediately. Implies `-Force`. | `MAINTENANCE_MODE=ci` |
| `nightly` | Relaxed: collects all failures, reports at end. | `MAINTENANCE_MODE=nightly` |
| `dev` | Warn only: never fails; all issues are warnings. | Default |

## Check Categories

| ID | Category | Count | Frequency |
|----|----------|-------|-----------|
| A | Code Quality & Correctness | 7 | CI + Nightly + Weekly |
| B | Test Health | 4 | CI + Nightly + Weekly |
| C | Documentation | 4 | CI + Nightly + Hourly |
| D | SKILL Documentation | 3 | CI + Nightly + Weekly |
| E | Version & Release | 3 | CI + Nightly + Release |
| F | Dependency Management | 3 | Nightly + Weekly |
| G | Infrastructure Health | 3 | CI + Nightly |
| H | Operational Health | 3 | Nightly + Weekly |
| I | AI-Assisted Quality | 2 | On-demand + Scheduled |

## Complete Check Inventory

| Script | Category | Interval | Description |
|--------|----------|----------|-------------|
| `check-compilation.ps1` | A | 5 min | Maven + Cargo compilation |
| `check-fast-tests.ps1` | B | 10 min | Fast JUnit unit tests |
| `check-rust-cli.ps1` | A | 1 hr | cargo test + cargo clippy |
| `check-doc-links-internal.ps1` | C | 1 hr | Internal documentation links |
| `check-skill-frontmatter.ps1` | D | 1 hr | SKILL.md YAML frontmatter |
| `check-version-consistency.ps1` | E | 1 hr | Version alignment across files |
| `check-ps1-syntax.ps1` | A | 1 hr | Parse all PS1 for syntax errors |
| `check-dockerfile.ps1` | G | 1 hr | Docker image builds |
| `check-coverage.ps1` | B | 24 hr | Code coverage vs thresholds |
| `check-test-tags.ps1` | B | 24 hr | JUnit test tag taxonomy |
| `check-skill-structure.ps1` | D | 24 hr | SKILL.md section structure |
| `check-dependency-vulns.ps1` | F | 24 hr | CVE vulnerability scan |
| `check-maven-deps.ps1` | F | 24 hr | Maven dependency convergence |
| `check-cargo-audit.ps1` | F | 24 hr | Rust cargo audit |
| `check-doc-links-external.ps1` | C | 24 hr | External URL validation |
| `check-bilingual-readme.ps1` | C | 24 hr | README.md ↔ README.zh.md |
| `check-log-sizes.ps1` | H | 24 hr | Log directory size audit |
| `check-deprecated-apis.ps1` | A | 7 days | Deprecated API usage |
| `check-dead-code.ps1` | A | 7 days | Dead code and unused imports |
| `check-skill-ai-quality.ps1` | D | 7 days | SKILL.md AI quality assessment |
| `check-license-compliance.ps1` | E | 7 days | Dependency license compatibility |
| `clean-build-artifacts.ps1` | H | 7 days | Remove stale build artifacts |
| `clean-temp-files.ps1` | H | 7 days | Remove stale temp/lock files |

Additional on-demand checks not in the default config:
`check-changelog-staleness.ps1`, `check-ci-workflows.ps1`, `check-e2e-tests.ps1`,
`check-integration-tests.ps1`, `check-qodana.ps1`, `check-readme-staleness.ps1`,
`check-release-assets.ps1`.

## Shared State

The orchestrator persists run history to `bin/maintenance/state/maintenance-state.json`,
which is tracked in git so the whole team shares one view.

**How skip logic works:** A task is skipped if it ran within its configured
`IntervalSeconds`. This means CI nightly running `check-coverage` prevents a
developer's orchestrator from re-running it within 24 hours. Force mode
(`-Force`) or CI mode bypass this entirely.

**File locking** prevents corruption when two processes write simultaneously.
Stale locks (older than 60 seconds) are automatically broken.

## Thresholds

All numeric thresholds live in `bin/maintenance/thresholds/thresholds.psd1`.
Override any value via environment variable:

```bash
MAINTENANCE_Coverage_Global=0.75 pwsh bin/maintenance/orchestrator.ps1 -Once
MAINTENANCE_LogHealth_MaxTotalMB=200 pwsh bin/maintenance/orchestrator.ps1 -Once
```

## Adding a New Check

1. Create `bin/maintenance/checks/check-my-thing.ps1` following the result object contract
2. Add a task entry to `bin/maintenance/config.psd1` with `Name`, `Description`, `Enabled`, `IntervalSeconds`, `ScriptPath`
3. If it's a CI-level check, add it to `bin/maintenance/ci/invoke-ci-checks.ps1`
4. If it's a nightly check, add it to `bin/maintenance/ci/invoke-nightly-checks.ps1`

## Reporters

| Script | Output |
|--------|--------|
| `reporters/report-console.ps1` | Colorized terminal output |
| `reporters/report-json.ps1` | JSON files under `logs/` |
| `reporters/report-github-annotations.ps1` | CI workflow commands |
| `reporters/report-summary.ps1` | Markdown summary |

## Directory Layout

```
bin/maintenance/
├── config.psd1              # Task definitions and intervals
├── orchestrator.ps1         # Master scheduler
├── common/
│   ├── MaintenanceUtil.ps1  # Logging, results, threshold helpers
│   └── MaintenanceState.ps1 # State I/O with file locking
├── checks/                  # 30 check/clean scripts
├── reporters/               # Output formatters
├── ci/                      # CI entry points
├── state/
│   └── maintenance-state.json  # Shared run history (git-tracked)
└── thresholds/
    └── thresholds.psd1      # Numeric thresholds
```

## Dependencies

- PowerShell Core 6+ (`pwsh`)
- Git (repository root resolution)
- Maven Wrapper (`mvnw` / `mvnw.cmd`)
- Cargo (Rust CLI checks)
- Docker (Qodana, integration tests, Dockerfile checks)
- Python 3 (`bin/quality/fix-links.py`)
- `ripgrep` (`rg`) recommended for fast content search
