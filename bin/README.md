# Scripts

This directory contains scripts for building, running, testing, and maintaining Browser4.

## Root Scripts

### `build.ps1`, `build.sh`

Build the project using Maven and Cargo.
- Runs `./mvnw install` (Maven) + `cargo build --release` (CLI).
- Defaults to `-DskipTests`.
- Options:
    - `-clean`: Run `mvn clean` before building.
    - `-test`: Enable tests (skipped by default).
- Accepts standard Maven arguments (e.g., `-pl`, `-am`).

### `test.ps1`, `test.sh`

Comprehensive test runner for the current Maven reactors plus the Browser4 CLI package.
`test.sh` is a bash wrapper that auto-installs `pwsh` if missing, then delegates to `test.ps1`.

**Usage:**
```bash
./bin/test.sh [test-types...] [additional-args...]
```

**Test Types:**
- `fast`: Run fast unit tests (default)
- `it`: Run integration tests
- `e2e`: Run end-to-end tests
- `rest`: Run REST module tests
- `skills`: Run skills module tests
- `mcp`: Run MCP module tests
- `main`: Run all Browser4 main tests (`fast`, `rest`, `it`, `e2e`)
- `cli` / `browser4-cli`: Run Rust Browser4 CLI tests from `cli/browser4-cli`
- `server`: Launch the standalone mock site server from `browser4-tests/browser4-rest-tests` via `spring-boot:run` (`mock-site` and `mocksiteboot` are accepted as legacy aliases)
- `rws`: Run real-world-scenario unit tests (`common.tests.ps1`). With `--scenarios`, run all agent-scenario tasks via `run-tests.ps1`. With `--task <file>`, run a single task via `run-task.ps1`.
- `resume`: Resume from the last failed module (`-rf`)

**RWS flags** (accepted after `rws`):
- `--scenarios [names...]`: Run agent-scenario tasks (requires `claude`)
- `--task <file>`: Run a single task file directly
- `--production`: Use installed `browser4-cli` instead of `cargo run`
- `--fail-fast`: Stop after the first failing scenario
- `--list`: List discovered scenarios, don't run
- `--silent`: Suppress agent output
- `--skip-version-check`: Skip browser4-cli version check

**Examples:**
```bash
./bin/test.sh fast                       # Run unit tests
./bin/test.sh it                         # Run integration tests
./bin/test.sh main                       # Run all main tests
./bin/test.sh cli                        # Run Browser4 CLI tests
./bin/test.sh cli -- --nocapture         # Pass extra cargo test args
./bin/test.sh server -Dmock.site.port=18080
./bin/test.sh skills                     # Run skills-focused agentic tests
./bin/test.sh rws                        # Run real-world-scenario unit tests
./bin/test.sh rws --scenarios            # Run all agent-scenario tasks
./bin/test.sh rws --scenarios amazon     # Run a specific scenario task
./bin/test.sh rws --scenarios --list     # List discovered scenario tasks
./bin/test.sh rws --task tasks/real-world/amazon.md # Run a single task file
./bin/test.sh resume                     # Resume from last failed module
```

### `test-production.ps1`

Acceptance test for the latest production release of `browser4-cli`.

Downloads, installs, exercises, uninstalls, and re-installs the global `browser4-cli`
from the public distribution channel, then runs the multi-scenario stress suite against it.

Tests the full lifecycle: install → smoke-test → uninstall → re-install → multi-scenario stress.

| Parameter | Description |
|---|---|
| `-SkipMultiScenarios` | Skip the final `multi-scenarios.ps1` run |
| `-MultiScenariosIterations N` | Number of iterations (default: 1) |
| `-KeepWorkingDir` | Do not delete the working directory on exit |
| `-WorkingDir <path>` | Override the working directory |

### `version.mjs`

Unified version maintenance tool — single entry point for all version operations.
Browser4 has two independent version tracks.

**Backend version** (source: `VERSION` file → pom.xml, READMEs):
- `node bin/version.mjs show`: Print backend version.
- `node bin/version.mjs show -v`: Print version + git hash, branch, date.
- `node bin/version.mjs release`: Strip `-SNAPSHOT` for release deployment.
- `node bin/version.mjs bump <part>`: Bump version (major/minor/patch) with precheck.
- `node bin/version.mjs auto`: Bump backend to next patch; bump CLI if cli/ changed. Shows release info, change summary, and asks for confirmation.
- `node bin/version.mjs auto --dry-run`: Preview the bump plan without applying.
- `node bin/version.mjs auto --commit`: Apply the bump and commit+push.

**CLI version** (source: `cli/VERSION-CLI` → package.json, Cargo.toml):
- `node bin/version.mjs cli show`: Print CLI version.
- `node bin/version.mjs cli sync`: Sync to dependent files.
- `node bin/version.mjs cli sync --check`: Check-only mode (CI lint).
- `node bin/version.mjs cli auto`: Bump CLI to next patch if changes detected in cli/.
- `node bin/version.mjs cli auto --dry-run`: Preview the CLI bump plan.

**Cross-cutting:**
- `node bin/version.mjs check`: Full consistency check across all version files.

Replaces the previous `version.sh`, `version.ps1`, `bump-version.ps1`,
`bump-version-patch.ps1`, `update-versions.sh`, and `sync-version.js` scripts.

### `seeds.txt`

A text file containing seed URLs for testing or crawling.

---

## Subdirectories

### `build/`

Build scripts with extended functionality.

- **`build.ps1`**: Full build pipeline — Maven + Spring Boot fat JAR + Cargo. Builds the entire project including the CLI, then copies `Browser4.jar` to `target/`.
- **`spring-boot.ps1`**: Build then launch Browser4 via `mvnw spring-boot:run`. Convenient for development hot-reload workflows.

### `ci/`

CI/CD helper scripts for triggering and managing CI workflows.

- **`trigger-ci-action.ps1`**: Create and push a CI pre-release tag (`vX.Y.Z-ci.N`) to trigger the CI workflow. Auto-increments the pre-release number.
- **`ci-tags-rm.ps1`**: Remove CI release tags.

### `common/`

Shared PowerShell utility modules imported by other scripts.

- **`Util.ps1`**: Common utilities including `Fix-Encoding-UTF8` — sets the console code page and output encoding to UTF-8 to prevent mojibake in Windows PowerShell.
- **`agent-utils.psm1`**: AI agent utilities — resolve and invoke AI assistants (`claude`, `copilot`, etc.) on PATH. Provides `Get-AiAnalyzer`, `Test-AiAvailable`, and `Invoke-AiAnalysis` for AI-powered log analysis in test runners.

### `git/`

Git maintenance and housekeeping scripts.

- **`cleanup-orphan-tags.ps1`**: Clean up orphaned git tags that no longer exist on the remote.
- **`cleanup-tags.ps1`**, **`cleanup-tags.sh`**: Remove local tags that no longer exist on the remote.
- **`delete-copilot-branches.ps1`**: Delete local and remote branches matching the GitHub Copilot naming pattern (`copilot/*`).
- **`git-config.ps1`**: Quick-set git HTTP/HTTPS proxy configuration.
- **`remove-tags-before.ps1`**: Remove stable-version git tags older than a specified threshold (default: before `v4.0.0`). Supports remote deletion.

### `maintenance/`

Config-driven, cross-platform maintenance system that periodically verifies code quality,
document correctness, and SKILL documentation AI-friendliness. See also [maintenance/README.md](maintenance/README.md).

**Core:**
- **`orchestrator.ps1`**: Master scheduler/orchestrator. Continuously cycles through configured checks, skipping tasks that ran recently (state tracked in `state/maintenance-state.json`). Supports `-Once` (single pass), `-Force` (ignore last-run state), and CI mode (`$env:MAINTENANCE_MODE=ci`).
- **`config.psd1`**: Scheduler task configuration — which checks run and at what intervals.

**Checks** (`checks/` — 28 scripts across 9 categories):

| Category | Scope | Example Scripts |
|---|---|---|
| Code Quality | CI + Nightly + Weekly | `check-compilation.ps1`, `check-dead-code.ps1`, `check-deprecated-apis.ps1` |
| Test Health | CI + Nightly + Weekly | `check-fast-tests.ps1`, `check-e2e-tests.ps1`, `check-test-tags.ps1` |
| Documentation | CI + Nightly + Hourly | `check-doc-links-internal.ps1`, `check-doc-links-external.ps1`, `check-bilingual-readme.ps1` |
| SKILL Docs | CI + Nightly + Weekly | `check-skill-structure.ps1`, `check-skill-frontmatter.ps1`, `check-skill-ai-quality.ps1` |
| Version & Release | CI + Nightly + Release | `check-version-consistency.ps1`, `check-changelog-staleness.ps1` |
| Dependencies | Nightly + Weekly | `check-dependency-vulns.ps1`, `check-maven-deps.ps1`, `check-cargo-audit.ps1` |
| Infrastructure | CI + Nightly | `check-dockerfile.ps1`, `check-ps1-syntax.ps1`, `check-ci-workflows.ps1` |
| Operational | Nightly + Weekly | `check-log-sizes.ps1`, `check-coverage.ps1` |
| AI-Assisted | On-demand + Scheduled | `check-skill-ai-quality.ps1` |
| Cleanup | On-demand | `clean-build-artifacts.ps1`, `clean-temp-files.ps1` |

**CI entry points** (`ci/`):
- **`invoke-ci-checks.ps1`**: Per-commit CI checks (fast, strict — fails on first issue).
- **`invoke-nightly-checks.ps1`**: Nightly full suite (relaxed — collects all failures, reports at end).

**Reporters** (`reporters/`): `report-console.ps1` (colorized terminal), `report-json.ps1`, `report-github-annotations.ps1`, `report-summary.ps1` (markdown).

**Shared modules** (`common/`): `MaintenanceUtil.ps1` (logging, results, thresholds), `MaintenanceState.ps1` (persistent state I/O with file locking).

**Configuration**: `thresholds/thresholds.psd1` (all numeric thresholds, overridable via env vars), `state/maintenance-state.json` (team-shared run history in git).

**Quick Start:**
```powershell
pwsh bin/maintenance/checks/check-ps1-syntax.ps1    # Run a single check
pwsh bin/maintenance/ci/invoke-ci-checks.ps1          # All CI-level checks
pwsh bin/maintenance/ci/invoke-nightly-checks.ps1     # All nightly checks
pwsh bin/maintenance/orchestrator.ps1 -Once           # One full pass
pwsh bin/maintenance/orchestrator.ps1 -Force -Once    # Force all tasks
$env:MAINTENANCE_MODE = "ci"
pwsh bin/maintenance/orchestrator.ps1 -Once           # CI strict mode
```

### `quality/`

Code quality check scripts.

- **`fix-links.py`**: Automatically fix broken documentation links.

### `release/`

Release management scripts. See also [release/README.md](release/README.md) for the full release workflow.

- **`trigger-release-action.ps1`**: Interactive script to create and push a release tag (`vX.Y.Z`). Validates the version in `VERSION`, shows changelog since the previous tag, and pushes to the specified remote.
- **`trigger-cli-release-action.ps1`**: Trigger the `browser4-cli` release workflow. Supports tag mode (creates `v{version}-cli` tag) and dispatch mode (`gh workflow run`), plus dry-run tagging.
- **`check-publish-status.ps1`**: Check whether the current project version and CLI version have been fully published to GitHub and npm.
- **`download-release-assets.ps1`**: Download all assets from a GitHub release (defaults to latest, supports specific tags via `-Tag`).

> **Note:** Version bumping is handled by the root-level [`version.mjs`](#versionmjs). Deprecated scripts (`bump-version.ps1`, `bump-version-patch.ps1`, `update-versions.sh`) have been consolidated into `version.mjs`.

### `tests/`

Test infrastructure and Docker verification scripts.

- **`test-create-runtime-bundle.ps1`**: Build the `browser4-bundle` Maven module with `-Passet-bundle` to create a runtime distribution bundle.
- **`test-docker-local.ps1`**: Build and smoke-test the Browser4 Docker image locally, mirroring the CI `build-core-and-docker` job. Runs Maven build, Docker build, health check, and JAR inspection.
- **`test.ps1.tests.ps1`**: Unit tests for the root `test.ps1` test runner (Pester-based).

### `tests-production/`

Production acceptance and stress tests for the globally-installed `browser4-cli`.
All tests use the globally-installed CLI by default (override with `$env:BROWSER4_CLI_BIN`).
These scripts are self-contained and portable — they never depend on git, the repo root, or local build outputs. See also [tests-production/README.md](tests-production/README.md).

**Test Runner:**
- **`run-tests.ps1`**, **`run-tests.sh`**: Discover and run test scripts. Supports categories (`smoke`, `agent`, `swarm`, `stress`, `all`) or individual tests. `run-tests.sh` is a bash wrapper that auto-detects locale and invokes `run-tests.ps1` via `pwsh`. On failure, attempts AI-powered log analysis via `claude` or `copilot` if available.

**Test Scripts:**
- **`cli-basics.ps1`**: Smoke test — verifies `--version`, `--help`, and basic session operations (open, list, close).
- **`agent-run-page-visit.ps1`**: Agent page-visit task lifecycle test.
- **`agent-run-page-visit-interact.ps1`**: Agent page-visit with interaction task test.
- **`agent-run-free-command.ps1`**: Agent free-command task lifecycle test (goto → extract).
- **`swarm-agents.ps1`**, **`swarm-agents.sh`**: Swarm create / submit / status lifecycle test.
- **`multi-scenarios.ps1`**: Multi-scenario stress-test orchestrator. Runs the scenario suite in a loop with isolated sub-processes.
- **`stress-install.ps1`**: Stress-test the install/uninstall lifecycle.
- **`stress-session.ps1`**: Stress-test session open/close lifecycle.
- **`stress-swarm-agents.ps1`**: Stress-test swarm agent operations at scale.
- **`bundle-download-speed.ps1`**: Measure browser bundle download speed.
- **`test-and-fix.ps1`**: Run tests and attempt automatic fixes on failure.

**Support Files:**
- **`test-utils.psm1`**: Shared PowerShell module providing CLI invocation tracking, logging, failure reporting, and AI analysis.
- **`test-production-helpers.ps1`**, **`test-utils-helpers.ps1`**: Helper functions for production test workflows.
- **`seeds.txt`**, **`seeds-stress.txt`**: Seed URL lists for test scenarios.
- **`logs/`**: Per-run log directories with full command output.

### `tools/`

Utility scripts for development and system maintenance.

**Process Management:**
- **`browser4-process-common.ps1`**: Shared module for detecting Browser4 Java and Chrome processes. Used by other process-management scripts.
- **`kill-browsers.ps1` / `kill-browsers.sh`**: Kill browser processes (Chrome, Chromium, Edge) that were launched by Browser4.
- **`kill-browsers-short.ps1`**: Minimal version — kills Chrome processes with `PULSAR_CHROME` in the command line.
- **`kill-browser4-processes.ps1`**: Kill Browser4 Java server processes. Supports `-ListOnly` for inspection without termination.
- **`list-browser4-processes.ps1`**: List all running Browser4 Java processes.
- **`list-browsers.ps1`**: List all browser processes launched by Browser4.

**Dependencies & Setup:**
- **`install-depends.ps1` / `install-depends.sh`**: Install system dependencies (Chrome, Maven Wrapper, etc.).
- **`install-powershell.sh`**: Install PowerShell (`pwsh`) via the official install script if not already present.
- **`check-dependencies.ps1` / `check-dependencies.sh`**: Check for dependency updates or issues.
- **`fix-kotlin-daemon.ps1`**: Diagnose and fix stale Kotlin daemon processes. Kills orphaned daemon JVMs, cleans lock files in `%LOCALAPPDATA%\kotlin\daemon`, and optionally runs a Maven install check to verify the fix.

**Housekeeping:**
- **`clear-temp-dir.ps1`**: Targeted cleanup of temporary build artifacts (tomcat, chrome, test, `.jar`, koltin, playwright, VS installer residue). Supports `-MinAgeHours` (default 24h) and `-WhatIf` for preview.
- **`remove-global-browser4-cli.ps1`**: Uninstall globally installed `browser4-cli` packages from npm, pnpm, Yarn, and cargo. Supports `-DryRun` and `-FailIfRemaining` for CI validation.
- **`dos2unix.sh`**: Convert Windows line endings to Unix.

**Proxy:**
- **`proxy.sh`**: Set/unset/status HTTP(S) and SOCKS5 proxy environment variables, git config, and VS Code settings. Must be `source`d for environment variables to persist:
  ```bash
  source bin/tools/proxy.sh on
  source bin/tools/proxy.sh off
  source bin/tools/proxy.sh status
  ```

**Code Metrics:**
- **`cloc.ps1`**: Count lines of code for a given git ref (defaults to HEAD).

**Maven Configuration:**
- **`maven/cn/settings.xml`**: Pre-configured Maven settings with Huawei Cloud mirror for faster builds in China.
- **`maven/maven-settings.md`**: Instructions for using the Chinese Maven mirror.

**Cron:**
- **`cron/update_and_build.sh`**: Cron-compatible script for periodic git-pull + rebuild. Intended to be scheduled via crontab.
