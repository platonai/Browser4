# Browser4 CLI Production Test Suite

Production acceptance and stress tests for the globally-installed `browser4-cli`.
These scripts exercise the full lifecycle — install, uninstall, server start/stop,
session management, agent tasks, and swarm workflows — against the **published**
CLI binary, not the local source tree.

## Quick Start

```powershell
# Run the full production acceptance test (install → exercise → uninstall → re-install)
pwsh bin/test-production.ps1

# Run all test categories
pwsh bin/tests/run-tests.ps1 all

# Run smoke tests only
pwsh bin/tests/run-tests.ps1 smoke

# Run a single test
pwsh bin/tests/run-tests.ps1 cli-basics

# Run multi-scenario stress suite (60 iterations, global CLI)
pwsh bin/tests/multi-scenarios.ps1 -Iterations 10
```

```bash
# Linux/macOS: use the bash wrapper
bin/tests/run-tests.sh smoke
```

## Available Tests

| Script | Category | Description |
|---|---|---|
| `cli-basics.ps1` | smoke | --version, --help, open, list, close |
| `agent-run-free-command.ps1` | agent | Agent free-command task lifecycle |
| `agent-run-page-visit.ps1` | agent | Agent page-visit task lifecycle |
| `agent-run-page-visit-interact.ps1` | agent | Agent page-visit + interaction lifecycle |
| `swarm-agents.ps1` | swarm | Swarm create / submit / status lifecycle |
| `stress-swarm-agents.ps1` | stress | Swarm stress test with seed URLs |
| `stress-session.ps1` | stress | Session lifecycle stress test |
| `stress-install.ps1` | stress | Install / server lifecycle stress test |
| `multi-scenarios.ps1` | orchestrator | Multi-iteration loop over core scenarios |

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
- Globally-installed tools: `browser4-cli`, `pwsh`, `bash`
- Optional AI CLIs (`claude`, `copilot`) on PATH for failure analysis
- Remote URLs (OSS install scripts, release downloads)

## Logs

Each run creates a timestamped log directory under `bin/tests/logs/`.
On failure, log paths are printed and AI analysis is invoked automatically
when `claude` or `copilot` is on PATH.

## See Also

- [AGENTS.md](../AGENTS.md) — repository guidelines, including the test portability section
- [test-utils.psm1](test-utils.psm1) — shared test utilities module (self-contained)
