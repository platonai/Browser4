# Browser4 Maintenance System

A config-driven, cross-platform maintenance system that periodically verifies
code quality, document correctness, and SKILL documentation AI-friendliness.

## Quick Start

```powershell
# Run a single check
pwsh bin/maintenance/checks/check-ps1-syntax.ps1

# Run all CI-level checks
pwsh bin/maintenance/ci/invoke-ci-checks.ps1

# Run all nightly checks
pwsh bin/maintenance/ci/invoke-nightly-checks.ps1

# Run the orchestrator (one pass)
pwsh bin/maintenance/orchestrator.ps1 -Once

# Run the orchestrator continuously (dev mode)
pwsh bin/maintenance/orchestrator.ps1

# Run in CI mode (strict, fails on any issue)
$env:MAINTENANCE_MODE = "ci"
pwsh bin/maintenance/orchestrator.ps1 -Once
```

## Directory Structure

```
bin/maintenance/
├── README.md                          # This file
├── config.psd1                        # Scheduler task configuration
├── orchestrator.ps1                   # Master scheduler/orchestrator
│
├── common/
│   └── MaintenanceUtil.ps1            # Shared utilities
│
├── checks/                            # 28 individual check scripts
│   ├── check-*.ps1                    # Read-only checks
│   └── clean-*.ps1                    # Cleanup operations
│
├── reporters/                         # Output formatters
│   ├── report-console.ps1             # Colorized terminal
│   ├── report-json.ps1                # JSON files
│   ├── report-github-annotations.ps1  # CI workflow commands
│   └── report-summary.ps1             # Markdown summary
│
├── ci/                                # CI entry points
│   ├── invoke-ci-checks.ps1           # Per-commit checks
│   └── invoke-nightly-checks.ps1      # Nightly full suite
│
└── thresholds/
    └── thresholds.psd1                # All numeric thresholds
```

## Check Categories

| ID | Category | Tasks | Frequency |
|----|----------|-------|-----------|
| A  | Code Quality & Correctness | 7 | CI + Nightly + Weekly |
| B  | Test Health | 4 | CI + Nightly + Weekly |
| C  | Documentation | 4 | CI + Nightly + Hourly |
| D  | SKILL Documentation | 3 | CI + Nightly + Weekly |
| E  | Version & Release | 3 | CI + Nightly + Release |
| F  | Dependency Management | 3 | Nightly + Weekly |
| G  | Infrastructure Health | 3 | CI + Nightly |
| H  | Operational Health | 3 | Nightly + Weekly |
| I  | AI-Assisted Quality | 2 | On-demand + Scheduled |

## Execution Modes

| Mode | Behavior | Trigger |
|------|----------|---------|
| `ci` | Strict: any failure exits 1 immediately | `$env:MAINTENANCE_MODE=ci` |
| `nightly` | Relaxed: collects all failures, reports at end | `$env:MAINTENANCE_MODE=nightly` |
| `dev` | Warn only: never fails; all issues are warnings | Default |

## Thresholds

All numeric thresholds live in `thresholds/thresholds.psd1`.
Override any value via environment variable:

```powershell
$env:MAINTENANCE_Coverage_Global = "0.75"
$env:MAINTENANCE_LogHealth_MaxTotalMB = "200"
```

## Adding a New Check

1. Create `checks/check-my-thing.ps1` following the result object contract
2. Add a task entry to `config.psd1`
3. If it's a CI-level check, add it to `ci/invoke-ci-checks.ps1`
4. If it's a nightly check, add it to `ci/invoke-nightly-checks.ps1`

## Result Object Contract

Every check script outputs a `PSCustomObject`:

```powershell
@{
    CheckId    = "D1"
    Name       = "SKILL Frontmatter Validation"
    Status     = "passed"  # passed | failed | skipped | error
    DurationMs = 1234
    ExitCode   = 0
    Details    = "10/10 SKILL.md files valid"
    Results    = @( @{ Item="..."; Status="passed"; Message="..." } )
    Artifacts  = @( "maintenance/logs/D1-20260620.json" )
    Timestamp  = "2026-06-20T14:30:22Z"
}
```

## Dependencies

- PowerShell Core 6+ (`pwsh`)
- Git (for repository root resolution)
- Maven Wrapper (`mvnw` / `mvnw.cmd`)
- Cargo (for Rust CLI checks)
- Docker (for Qodana, integration tests, Dockerfile checks)
- Python 3 (for `bin/quality/fix-links.py`)
- `ripgrep` (`rg`) recommended for fast content search

## See Also

- [AGENTS.md](../../AGENTS.md) — Project conventions and guidance
- [docs/TESTING.md](../../docs/TESTING.md) — Test taxonomy
- [coworker/](../../coworker/) — Task automation system (architectural inspiration)
