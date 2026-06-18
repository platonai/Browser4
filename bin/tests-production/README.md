# Browser4 CLI Production Test Suite

Production acceptance and stress tests for the globally-installed `browser4-cli`.
These scripts exercise the full lifecycle — install, uninstall, server start/stop,
session management, agent tasks, and swarm workflows — against the **published**
CLI binary, not the local source tree.

All test scripts are **cross-platform** (Windows, Linux, macOS) and use
**soft-failure** semantics — every test runs to completion regardless of
individual step failures, and a full summary is printed at the end.

## Quick Start

```powershell
# Run the full production acceptance test (install → exercise → uninstall → re-install)
pwsh bin/test-production.ps1

# Run all test categories
pwsh bin/tests-production/run-tests.ps1 all

# Run smoke tests only
pwsh bin/tests-production/run-tests.ps1 smoke

# Run a single test
pwsh bin/tests-production/run-tests.ps1 cli-basics

# Run multi-scenario stress suite (60 iterations, global CLI)
pwsh bin/tests-production/multi-scenarios.ps1 -Iterations 10
```

```bash
# Linux/macOS: use the bash wrapper
bin/tests-production/run-tests.sh smoke
```

## Available Tests

### PowerShell tests (primary)

| Script | Category | Description |
|---|---|---|
| `cli-basics.ps1` | smoke | Basic CLI functionality (--version, --help, open, list, close) |
| `agent-run-free-command.ps1` | agent | Agent free-command task lifecycle |
| `agent-run-page-visit.ps1` | agent | Agent page-visit task lifecycle |
| `agent-run-page-visit-interact.ps1` | agent | Agent page-visit + interaction task lifecycle |
| `swarm-agents.ps1` | swarm | Swarm create / submit / status / result lifecycle |
| `stress-swarm-agents.ps1` | stress | Swarm stress test with seed URLs |
| `stress-session.ps1` | stress | Session lifecycle stress (open / goto / close / kill-all cycles) |
| `stress-install.ps1` | stress | Install / server lifecycle stress (install, auto-start, stop, kill-all, state integrity) |
| `multi-scenarios.ps1` | orchestrator | Multi-iteration loop over core scenarios |

### Bash tests

| Script | Description |
|---|---|
| `swarm-agents.sh` | Swarm create / submit / status lifecycle (bash, identical coverage to `swarm-agents.ps1`) |

### Utilities & runners

| File | Purpose |
|---|---|
| `run-tests.ps1` | PowerShell test runner (discover, run, summarise, AI failure analysis) |
| `run-tests.sh` | Bash wrapper that invokes `run-tests.ps1` with locale detection |
| `test-utils.psm1` | Shared PowerShell test utilities module (self-contained) |
| `seeds.txt` | Seed URLs for swarm tests |
| `seeds-stress.txt` | Extended seed list for stress-swarm-agents |

## Locale

Tests adapt their behaviour to the detected locale.  The locale is resolved
in this order:

1. `$env:BROWSER4_TEST_LOCALE` — explicit override
2. `$env:LANG` (Unix) or system locale (Windows)
3. Falls back to `en`

You can also pass `-Locale zh` (or any two-letter code) to `run-tests.ps1`
to force a locale for the entire suite.

## Portability

These scripts are **self-contained** and must remain so.  They run from any
location with only a globally-installed `browser4-cli` on PATH.

**They must never depend on:**
- `git` or any git command
- The repository root (`pom.xml`, source modules)
- Local Maven/Cargo build outputs (`target/` directories)

**What they use:**
- Sibling files resolved via `$PSScriptRoot` (PowerShell) or `$BASH_SOURCE` (bash)
- `$env:BROWSER4_CLI_BIN` to override the CLI binary
- `$env:BROWSER4_TEST_LOCALE` to override the test locale
- Globally-installed tools: `browser4-cli`, `pwsh`, `bash`
- Optional AI CLIs (`claude`, `copilot`) on PATH for failure analysis
- Remote URLs (OSS install scripts, release downloads)

## Logs

Each run creates a timestamped log directory under `bin/tests-production/logs/`.
On failure, log paths are printed and AI analysis is invoked automatically
when `claude` or `copilot` is on PATH (priority: `claude`, then `copilot`).

## See Also

- [AGENTS.md](../../AGENTS.md) — repository guidelines and testing conventions
- [test-utils.psm1](test-utils.psm1) — shared test utilities module (self-contained)
