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

**Usage:**
```bash
./bin/test.sh [test-types...] [additional-args...]
```

**Test Types:**
- `fast`: Run fast unit tests (default)
- `it`: Run integration tests
- `e2e`: Run end-to-end tests
- `mock-site`: Launch `browser4-rest-tests`' standalone mock site server via `spring-boot:run` (`mocksite` and `mocksiteboot` are accepted as legacy aliases)
- `rest`: Run REST module tests
- `skills`: Run skills module tests
- `mcp`: Run MCP module tests
- `browser4`: Run all Browser4 main tests (`fast`, `rest`, `it`, `e2e`)
- `cli`: Run Rust Browser4 CLI tests from `cli/browser4-cli`

**Examples:**
```bash
./bin/test.sh fast                       # Run unit tests
./bin/test.sh it                         # Run integration tests
./bin/test.sh browser4                   # Run all main tests
./bin/test.sh cli                        # Run Browser4 CLI tests
./bin/test.sh cli -- --nocapture         # Pass extra cargo test args
./bin/test.sh mock-site -Dmock.site.port=18080
```

### `test-production.ps1`

Acceptance test for the latest production release of `browser4-cli`.

Downloads, installs, exercises, uninstalls, and re-installs the global `browser4-cli`
from the public distribution channel. Shows help when called with no arguments
(safe default). The multi-scenario stress suite is opt-in via `-Stress`.

Tests the full lifecycle: install → smoke-test → uninstall → re-install → (with `-Stress`) multi-scenario stress.

| Parameter | Description |
|---|---|
| `-Stress` | Enable the multi-scenario stress suite (opt-in) |
| `-MultiScenariosIterations N` | Number of stress iterations (default: 1) |
| `-RemoveWorkingDir` | Delete the working directory on exit |
| `-WorkingDir <path>` | Override the working directory |
| `-Help` | Show help message |

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
- **`agent-utils.psm1`**: AI agent utilities for resolving and invoking AI assistants (Claude, Copilot, etc.). Provides `Get-AiAnalyzer`, `Test-AiAvailable`, `Invoke-AiAnalysis`, and backward-compatible wrappers.

### `git/`

Git maintenance and housekeeping scripts.

- **`cleanup-orphan-tags.ps1`**: Clean up orphaned git tags that no longer exist on the remote.
- **`cleanup-tags.ps1`**, **`cleanup-tags.sh`**: Remove local tags that no longer exist on the remote.
- **`delete-copilot-branches.ps1`**: Delete local and remote branches matching the GitHub Copilot naming pattern (`copilot/*`).
- **`git-config.ps1`**: Quick-set git HTTP/HTTPS proxy configuration.
- **`remove-tags-before.ps1`**: Remove stable-version git tags older than a specified threshold (default: before `v4.0.0`). Supports remote deletion.

### `maintenance/`

Automated maintenance system that runs health checks on a configurable schedule.
Modeled after a cron-style scheduler with CI, nightly, and dev execution modes.

**Orchestrator:**
- **`orchestrator.ps1`**: Master maintenance orchestrator. Loads task definitions from `config.psd1` and runs them on their configured intervals. Supports three modes via `$env:MAINTENANCE_MODE`: `ci` (single pass, strict, exit 1 on failure), `nightly` (single pass, relaxed), and `dev` (continuous loop, warn only). Use `-Once` for a single pass, `-Force` to bypass schedule state.

**Configuration:**
- **`config.psd1`**: Task definitions and scheduling configuration. Defines all maintenance tasks with their intervals, script paths, arguments, and dependencies. Tasks are organized into CI-level (per-commit, fast) and nightly-level (slower, comprehensive) tiers.
- **`thresholds/thresholds.psd1`**: Threshold values for checks that compare against numeric limits (e.g., max allowed log size, min coverage percentage).

**CI Entry Points:**
- **`ci/invoke-ci-checks.ps1`**: CI entry point — runs all level-1 (fast, per-commit) checks in sequence. Designed to be called from CI workflows as a single step. Checks include compilation, fast tests, Rust CLI, doc links, skill frontmatter, version consistency, PS1 syntax, and Dockerfile validation.
- **`ci/invoke-nightly-checks.ps1`**: Nightly entry point — runs the full check suite including slower checks like integration tests, e2e tests, coverage, dependency audits, dead code detection, and Qodana analysis.

**Check Scripts** (`checks/`):

*Build & Compilation:*
- **`check-compilation.ps1`**: Verify Maven + Cargo compilation succeeds.
- **`check-fast-tests.ps1`**: Run fast unit tests and verify they pass.
- **`check-integration-tests.ps1`**: Run integration tests.
- **`check-e2e-tests.ps1`**: Run end-to-end tests.
- **`check-rust-cli.ps1`**: Verify Rust CLI compiles and passes tests.
- **`check-qodana.ps1`**: Run Qodana static analysis.

*Dependencies & Security:*
- **`check-dependency-vulns.ps1`**: Check for known vulnerabilities in dependencies.
- **`check-cargo-audit.ps1`**: Run `cargo audit` for Rust dependency vulnerabilities.
- **`check-maven-deps.ps1`**: Validate Maven dependency tree.
- **`check-license-compliance.ps1`**: Check that all dependencies have compliant licenses.

*Code Quality:*
- **`check-dead-code.ps1`**: Detect potentially dead/unused code.
- **`check-deprecated-apis.ps1`**: Flag usage of deprecated APIs.
- **`check-coverage.ps1`**: Verify code coverage meets thresholds.
- **`check-ps1-syntax.ps1`**: Validate PowerShell script syntax across the repo.
- **`check-dockerfile.ps1`**: Validate Dockerfile correctness and best practices.

*Documentation & Links:*
- **`check-doc-links-internal.ps1`**: Check internal documentation links for broken references.
- **`check-doc-links-external.ps1`**: Check external documentation links for availability.
- **`check-readme-staleness.ps1`**: Detect if READMEs are stale relative to the files they document.
- **`check-bilingual-readme.ps1`**: Verify bilingual READMEs are in sync.

*Release & Versioning:*
- **`check-version-consistency.ps1`**: Verify version numbers are consistent across all files.
- **`check-release-assets.ps1`**: Validate that release assets are present and valid.
- **`check-changelog-staleness.ps1`**: Check if the changelog is up to date.
- **`check-ci-workflows.ps1`**: Validate CI workflow definitions.

*Skills:*
- **`check-skill-frontmatter.ps1`**: Validate frontmatter in skill definition files.
- **`check-skill-structure.ps1`**: Validate skill file/directory structure.
- **`check-skill-ai-quality.ps1`**: Assess AI quality of skill definitions.

*Housekeeping:*
- **`check-log-sizes.ps1`**: Monitor log file sizes against thresholds.
- **`check-test-tags.ps1`**: Validate test tag annotations.
- **`clean-build-artifacts.ps1`**: Clean up stale build artifacts.
- **`clean-temp-files.ps1`**: Remove orphaned temporary files.

**Common Modules:**
- **`common/MaintenanceUtil.ps1`**: Shared utilities for all maintenance scripts. Provides `New-MaintenanceResult`, `Add-MaintenanceResult`, `Get-RepositoryRoot`, `Resolve-MaintenancePath`, `Write-MaintenanceLog`, `Invoke-MaintenanceStep`, `Test-Platform`, `Get-MaintenanceThreshold`, and `Test-IsMaintenanceMode`.
- **`common/MaintenanceState.ps1`**: State management for the scheduler — tracks last-run times, results, and status per task.

**Reporters** (`reporters/`):
- **`report-console.ps1`**: Colorized, human-readable console output.
- **`report-github-annotations.ps1`**: GitHub Actions annotation output (errors/warnings surface in PR diffs).
- **`report-json.ps1`**: Machine-readable JSON output for downstream processing.
- **`report-summary.ps1`**: Summary-only reporter for CI step summaries.

**Tests:**
- **`tests/maintenance.tests.ps1`**: Unit tests for the maintenance system itself.
- **`state/maintenance-state.json`**: Runtime state file tracking execution history.

### `quality/`

Code quality check scripts.

- **`fix-links.py`**: Automatically fix broken documentation links.

### `release/`

Release management scripts. See also [release/README.md](release/README.md) for the release workflow.

- **`trigger-release-action.ps1`**: Interactive script to create and push a release tag (`vX.Y.Z`). Validates the version in `VERSION`, shows changelog since the previous tag, and pushes to the specified remote.
- **`trigger-cli-release-action.ps1`**: Trigger the `browser4-cli` release workflow. Supports tag mode (creates `v{version}-cli` tag) and dispatch mode (`gh workflow run`), plus dry-run tagging.
- **`check-publish-status.ps1`**: Check whether the current project version and CLI version have been fully published to GitHub and npm.
- **`download-release-assets.ps1`**: Download all assets from a GitHub release (defaults to latest, supports specific tags via `-Tag`).

### `tests/`

Test infrastructure and Docker verification scripts.

- **`test-create-runtime-bundle.ps1`**: Build the `browser4-bundle` Maven module with `-Passet-bundle` to create a runtime distribution bundle.
- **`test-docker-local.ps1`**: Build and smoke-test the Browser4 Docker image locally, mirroring the CI `build-core-and-docker` job. Runs Maven build, Docker build, health check, and JAR inspection.
- **`test.ps1.tests.ps1`**: Unit tests for helper functions and dispatch logic in `bin/test.ps1`. Extracts pure functions via PowerShell's AST parser and tests them in isolation — no Maven or Cargo execution needed.

### `browser4-tests/tests-production/`

Integration test suite for `browser4-cli` production releases. All tests use the globally-installed CLI by default (override with `$env:BROWSER4_CLI_BIN`).

**Test Runner:**
- **`run-tests.ps1`**, **`run-tests.sh`**: Discover and run test scripts. Supports categories (`smoke`, `agent`, `swarm`, `stress`, `all`) or individual tests. `run-tests.sh` is a bash wrapper that auto-detects locale and invokes `run-tests.ps1` via `pwsh`. On failure, attempts AI-powered log analysis via `claude` or `copilot` if available.

**Acceptance Tests:**
- **`cli-basics.ps1`**: Smoke test — verifies `--version`, `--help`, and basic session operations (open, list, close).
- **`agent-run-page-visit.ps1`**: Agent page-visit task lifecycle test.
- **`agent-run-page-visit-interact.ps1`**: Agent page-visit with interaction task test.
- **`agent-run-free-command.ps1`**: Agent free-command task lifecycle test (goto → extract).
- **`swarm-agents.ps1`**, **`swarm-agents.sh`**: Swarm create / submit / status lifecycle test.
- **`bundle-download-speed.ps1`**: Measure runtime bundle download speed from each available source (GitHub Releases, Alibaba Cloud OSS, proxy on/off). Downloads the first 10 MB via HTTP Range request to keep probes small.

**Stress Tests:**
- **`multi-scenarios.ps1`**: Multi-scenario stress-test orchestrator. Runs the scenario suite in a loop with isolated sub-processes.
- **`stress-install.ps1`**: Stress-test the install/uninstall lifecycle.
- **`stress-session.ps1`**: Stress-test session open/close lifecycle.
- **`stress-swarm-agents.ps1`**: Stress-test swarm agent operations at scale.

**Test & Fix:**
- **`test-and-fix.ps1`**: Two-phase workflow: (1) run the full acceptance test suite via `test-production.ps1`, then (2) collect AI analysis files from failing tests and invoke the best available AI CLI to produce a fix plan. Optionally applies fixes automatically. Designed for CI and local "test → fix → retest" loops.

**Unit Tests (for test infrastructure):**
- **`test-production-helpers.ps1`**: Unit tests for helper functions defined in `test-production.ps1` (assertions, result writing, path resolution). Extracts functions via AST parser and tests in isolation.
- **`test-utils-helpers.ps1`**: Unit tests for `ConvertTo-WindowsCmdArg` in `test-utils.psm1`. Validates Windows command-line argument escaping against the MSDN CommandLineToArgvW specification.

**Support Files:**
- **`test-utils.psm1`**: Shared PowerShell module providing CLI invocation tracking, logging, failure reporting, and AI analysis.
- **`seeds.txt`**, **`seeds-stress.txt`**: Seed URL lists for test scenarios.
- **`logs/`**: Per-run log directories with full command output.
- **`.browser4-cli/snapshot/`**: CLI snapshot files for history verification.

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
