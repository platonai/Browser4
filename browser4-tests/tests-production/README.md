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
pwsh bin/test-production.ps1 -Run

# Show the help page (options, examples, exit codes) without running anything
pwsh bin/test-production.ps1 -Help

# Show only the copy-paste-ready examples
pwsh bin/test-production.ps1 -Examples

# Run all test categories
pwsh browser4-tests/tests-production/run-tests.ps1 all

# Run smoke tests only
pwsh browser4-tests/tests-production/run-tests.ps1 smoke

# Run the server-free local command surface (no browser, no backend — ~1 min)
pwsh browser4-tests/tests-production/run-tests.ps1 local

# Run a single test
pwsh browser4-tests/tests-production/run-tests.ps1 cli-basics

# Run multi-scenario stress suite (60 iterations, global CLI)
pwsh browser4-tests/tests-production/multi-scenarios.ps1 -Iterations 10
```

```bash
# Linux/macOS: use the bash wrapper
browser4-tests/tests-production/run-tests.sh smoke
```

## Available Tests

### PowerShell tests (primary)

| Script | Category | Description |
|---|---|---|
| `cli-basics.ps1` | smoke | Basic CLI functionality (--version, --help, open, list, close) |
| `local-commands.ps1` | local | Server-free local command surface (`config`, `skills`, `snapshot`, spaced-form contract) — no browser, no backend |
| `agent-run-free-command.ps1` | agent | Agent free-command task lifecycle |
| `agent-run-page-visit.ps1` | agent | Agent page-visit task lifecycle |
| `agent-run-page-visit-interact.ps1` | agent | Agent page-visit + interaction task lifecycle |
| `swarm-agents.ps1` | swarm | Swarm create / submit / status / result lifecycle |
| `stress-swarm-agents.ps1` | stress | Swarm stress test with seed URLs |
| `stress-session.ps1` | stress | Session lifecycle stress (open / goto / close / kill-all cycles) |
| `stress-install.ps1` | stress | Install / server lifecycle stress (install, auto-start, stop, kill-all, state integrity) |
| `multi-scenarios.ps1` | orchestrator | Multi-iteration loop over core scenarios |
| `test-and-fix.ps1` | orchestrator | Two-phase workflow: run acceptance tests, then auto-fix failures via AI analysis |
| `bundle-download-speed.ps1` | infra | Measure bundle download speed from each configured mirror |
| `verify-named-session-profile.ps1` | verify | **Measure** whether a named session keeps its dedicated profile across a backend restart (destructive restart phase is opt-in via `-RestartBackend`) |
| `verify-attach-close-tabs.ps1` | verify | **Measure** what `close` does to tabs on a CDP-attached session (needs a browser started with `--remote-debugging-port`) |

> **Verification (`verify`) scripts measure, they do not assert.** Each prints an
> explicit verdict (`STABLE` / `REBOUND`, `PROCESS SURVIVED` / `TABS CLOSED` /
> `TABS UNTOUCHED`) and exits 0 for any measured outcome; only precondition
> failures (no CLI, no CDP endpoint, server cannot start) fail the run. They exist
> because the corresponding behaviour is documented but not covered by an
> assertion — see
> [browser-modes.md §7](../../skills/browser4-cli/references/browser-modes.md).

### Bash tests

| Script | Description |
|---|---|
| `swarm-agents.sh` | Swarm create / submit / status lifecycle (bash, identical coverage to `swarm-agents.ps1`) |

### Helper unit tests

These scripts validate the test infrastructure itself — no browser4-cli installation
or network access required. They run in pure PowerShell via AST parsing and
argument-escaping validation.

| Script | Description |
|---|---|
| `test-production-helpers.ps1` | Unit tests for helper functions in `test-production.ps1` (AST-extracted, no side effects) |
| `test-utils-helpers.ps1` | Unit tests for `test-utils.psm1`: `ConvertTo-WindowsCmdArg` against the MSDN CommandLineToArgvW spec, plus the AI-analysis console policy (`Test-AiAnalysisVisible`, `Format-AiAnalysisNotice`) |

### Utilities & runners

| File | Purpose |
|---|---|
| `run-tests.ps1` | PowerShell test runner (discover, run, summarise, AI failure analysis) |
| `run-tests.sh` | Bash wrapper that invokes `run-tests.ps1` with locale detection |
| `test-utils.psm1` | Shared PowerShell test utilities module (self-contained). Importing it also pins the process to UTF-8 output: the suite's stdout is captured to files that are read back as UTF-8, and a redirected stream otherwise starts on the Windows OEM code page, where `·` / `—` become `??` and `═` becomes `?T` (CP936 `A8 54` — the `0x54` is a literal `T`). |
| `seeds.txt` | Seed URLs for swarm tests |
| `seeds-stress.txt` | Extended seed list for stress-swarm-agents |

## Production acceptance test (`bin/test-production.ps1`)

`bin/test-production.ps1` is the end-to-end release gate: install → exercise →
uninstall → re-install, against the **published** CLI. Running it with no
arguments prints help and exits 0 — a safe default, because an accidental
invocation must not install, uninstall, or start anything. Pass `-Run` (or any
other option) to actually run it.

| Option | Description |
|---|---|
| `-Run` | Run with all defaults (latest stable release, random temp working directory, no stress suite) |
| `-WorkingDir <path>` | Working directory for temporary artifacts (default: random subdir under the system temp dir; preserved for review) |
| `-Version <tag>` | Test a specific release, including prereleases (e.g. `v4.15.0-rc.1`); the release status report then queries the tag-specific endpoints |
| `-Stress` | Also run the opt-in multi-scenario stress suite (`multi-scenarios.ps1`) |
| `-MultiScenariosIterations <n>` | Iterations for the stress suite (default: 1, only with `-Stress`) |
| `-RemoveWorkingDir` | Delete the working directory on exit (default: kept for review) |
| `-Help` | Print the help page and exit 0 |
| `-Examples` | Print only the copy-paste-ready examples and exit 0 |

The help page is generated from the comment-based help block at the end of the
script, so it always matches the parameter list. It also reports parameters that
lack a `.PARAMETER` description or an `.EXAMPLE`, and
`test-production-helpers.ps1` fails while that coverage is incomplete — a new
switch needs a description and at least one example before the helper suite is
green again.

Exit codes: `0` — every step passed (or help/examples were printed); `1` — at
least one step failed (the failing steps are listed in the results table).

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

Each run creates a timestamped log directory under `browser4-tests/tests-production/logs/`.
On failure, log paths are printed and AI analysis is invoked automatically when
an AI CLI (`dsh`, `opencode`, `claude`, `copilot`) is on PATH.

The analysis body is **not printed to the console** by default: it is free-form
AI output (often the analyzer's own reasoning plus raw error dumps) that buries
the test report. Only a one-line pointer to `ai-analysis.txt` is printed. To get
the full text on the console, pass `-ShowAnalysis` to `Invoke-CopilotAnalysis` or
set `BROWSER4_SHOW_AI_ANALYSIS=1` for the whole session (e.g. when capturing CI
logs).

## See Also

- [AGENTS.md](../../AGENTS.md) — repository guidelines and testing conventions
- [test-utils.psm1](test-utils.psm1) — shared test utilities module (self-contained)
