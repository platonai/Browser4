---
name: test-production
title: "Test Production — Acceptance-test the latest published browser4-cli release"
description: "Runs bin/test-production.ps1 to acceptance-test a production (or prerelease) release of browser4-cli. Use when asked to run production tests, acceptance-test a release, or verify the published CLI."
allowed-tools: Bash(pwsh:*), Bash(./bin/test-production.ps1:*)
---

# Test Production

Runs `bin/test-production.ps1` — an acceptance test that simulates a real end
user's journey with the **published** browser4-cli release.  It downloads,
installs, exercises, uninstalls, and re-installs the global CLI from the public
OSS distribution channel.

This is a separate entrypoint from `bin/test.ps1` (covered by the `run-tests`
skill).  `test-production.ps1` tests the **released artifact** — not the
in-repo source — and is designed to be run in CI or locally before tagging a
release.

## When to Use

- "run production tests"
- "run the production acceptance test"
- "acceptance-test the latest release"
- "test the published CLI"
- "verify the release before tagging"
- "test a prerelease candidate"
- "run production acceptance with stress"

## Safe Default

Running with no arguments shows help (safe default):

```bash
./bin/test-production.ps1
```

## Options

| Flag | Description |
|------|-------------|
| `-WorkingDir <path>` | Working directory for temporary artifacts (default: random subdir under system temp) |
| `-Stress` | Enable the multi-scenario stress suite (opt-in) |
| `-MultiScenariosIterations <n>` | Iterations for the multi-scenario suite (default: 1, only with `-Stress`) |
| `-RemoveWorkingDir` | Delete the working directory on exit (default: preserved for review) |
| `-Version <tag>` | Test a specific release version (e.g. `v4.12.0-rc.1`, `v4.11.0`). Default: latest stable |
| `-Help` | Show help message |

## What It Tests

1. **Release status report** — queries GitHub Releases, npm registry, Aliyun OSS
   CDN, and custom mirrors to verify all distribution channels are reachable.
2. **Pre-clean** — removes any existing global browser4-cli installation.
3. **Cycle 1 — Clean-room install** (no pre-existing config):
   - Installs the CLI via the remote bootstrap script (unmodified — no patches).
   - Verifies the CLI is on PATH after install.
   - Smoke-tests: `--help`, `--version`, `config --help`, `agent-run --help`, invalid command.
   - Cold-starts the browser server (`open`), verifies health endpoint responds.
   - Shuts down the server (`close-all`, `kill-all`), verifies it's unreachable.
   - Uninstalls and verifies runtime data is removed.
4. **Cycle 2 — Re-install with config + timing**:
   - Copies config (LLM keys, etc.) from the user's real `~/.browser4` into the test sandbox.
   - Repeats the install→exercise→uninstall cycle.
   - Measures warm-start latency vs cold-start.
5. **(With `-Stress`) Multi-scenarios stress test** — runs
   `browser4-tests/tests-production/multi-scenarios.ps1` against the global CLI.

## Key Principle

The script acts like a **real end user** — it does **not** patch install scripts,
create missing symlinks, or manually clean up after uninstall.  If any of those
are needed, the test **fails** because a real user would hit the same broken
behavior.

## Isolation

The test uses environment variables (`BROWSER4_CLI_STATE_DIR`, `BROWSER4_RUNTIME_DIR`)
to route all state and runtime data into an isolated working directory.  The
user's real `~/.browser4` is **never modified** — it is only read to copy config
into the test sandbox during Cycle 2.

## Usage

### Examples

```bash
# Show help (no arguments = safe default)
./bin/test-production.ps1

# Run the full acceptance test (no stress)
./bin/test-production.ps1

# Run with stress suite
./bin/test-production.ps1 -Stress

# Production CI: stress test with multiple iterations, clean up afterward
./bin/test-production.ps1 -Stress -MultiScenariosIterations 3 -RemoveWorkingDir

# Test a specific prerelease candidate
./bin/test-production.ps1 -Version v4.12.0-rc.1 -Stress

# Test a specific stable release
./bin/test-production.ps1 -Version v4.11.0

# Use a specific working directory (kept for review by default)
./bin/test-production.ps1 -WorkingDir /tmp/my-acceptance-test
```

### Typical CI invocation

```bash
# Full acceptance gate for a release
./bin/test-production.ps1 -Stress -MultiScenariosIterations 3 -RemoveWorkingDir
```

## Exit Codes

| Code | Meaning |
|------|---------|
| 0 | All steps passed |
| 1 | One or more steps failed |

## Working Directory

By default, a random subdirectory is created under the system temp directory
(e.g. `/tmp/.browser4-acceptance/20260720-143052-a3f2`).  The directory is
**preserved** after the test for review unless `-RemoveWorkingDir` is passed.

## Relationship to Other Skills

- **`run-tests`** — covers `bin/test.ps1` for in-repo unit, integration, E2E,
  CLI, and PowerShell tests.  Use that skill for development-time testing.
- **`test-production`** (this skill) — covers `bin/test-production.ps1` for
  release-time acceptance testing of the published artifact.
