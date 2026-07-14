---
name: run-tests
title: "Run Tests — Execute Browser4 test suites via bin/test.ps1"
description: "Discovers and runs Browser4 test suites (unit, integration, E2E, CLI, PowerShell, real-world scenarios). Use when asked to run, check, or verify tests."
allowed-tools: Bash(pwsh:*), Bash(./bin/test.ps1:*)
---

# Run Tests

Invokes `bin/test.ps1`, the unified test orchestrator for the Browser4 monorepo.
Supports Maven tests, Rust CLI tests, PowerShell `*.tests.ps1` files, real-world
scenario agent evaluations, and mock-server launch.

## When to Use

- "run the tests"
- "run fast / unit tests"
- "run integration / it tests"
- "run e2e tests"
- "run cli tests"
- "run all PowerShell tests"
- "run real-world scenarios"
- "check what tests would run" (-DryRun / -Show)
- "resume failed tests"
- "launch mock server"

## How It Works

`bin/test.ps1` buckets test-type arguments into dispatch categories (Maven, CLI,
PowerShell, RWS, server) and runs each in sequence.  The script must be invoked
from the repository root (it `Set-Location`s there automatically).

Test results are persisted per invocation to `.test-sessions/<session-id>/test-session.json`
(see `bin/common/test-session.psm1`).  Pass `-NoSession` to skip persistence or `-SessionPath`
to write to a custom location.

## Usage

```bash
./bin/test.ps1 [OPTIONS] [TEST-TYPES...] [EXTRA-ARGS...]
```

### Options

| Flag | Description |
|------|-------------|
| `-DryRun` | Compile only (test-compile), do not run tests |
| `-Show` | Print the final command, do not execute anything |
| `-NoSession` | Skip persisting test results to `.test-sessions/` |
| `-SessionPath <path>` | Custom path for the test-session JSON file |

### Test Types

| Type | Category | Description |
|------|----------|-------------|
| `fast` | Maven | Fast unit tests |
| `it` | Maven | Integration tests (`-DrunITs=true`) |
| `e2e` | Maven | End-to-end tests (`-DrunE2ETests=true`) |
| `rest` | Maven | REST module tests (`-DrunRestTests=true`) |
| `skills` | Maven | Skills-focused agentic tests (browser4-agentic) |
| `mcp` | Maven | MCP-focused agentic tests (browser4-agentic) |
| `main` | Maven | All Browser4 main tests: fast + it + e2e + rest |
| `cli` | CLI | Rust Browser4 CLI tests (`cargo test --test e2e`). Alias: `browser4-cli` |
| `ps` | PowerShell | All `*.tests.ps1` files in the project |
| `resume` | Maven | Resume from the last failed module (`-rf`) |
| `mock-site` | Server | Launch MockSiteBoot (aliases: `server`, `mocksite`) |
| `rws` | RWS | Real-world scenario agent evaluations. Bare `rws` shows help; pass `--scenarios` or `--task` to run. |

### RWS Flags (accepted after `rws`)

| Flag | Description |
|------|-------------|
| `--scenarios [names...]` | Run agent-scenario tasks (requires claude) |
| `--task <file>` | Run a single task file |
| `--production` | Use installed browser4-cli instead of cargo run |
| `--fail-fast` | Stop after the first failing scenario |
| `--list` | List discovered scenarios, don't run |
| `--silent` | Suppress agent output |
| `--skip-version-check` | Skip browser4-cli version check |
| `--timeout <minutes>` | Per-task timeout (default: no timeout) |

### Examples

```bash
# Run fast unit tests
./bin/test.ps1 fast

# Show the Maven command without executing
./bin/test.ps1 -DryRun fast

# Run integration tests with extra Maven args
./bin/test.ps1 it -pl browser4-core

# Run end-to-end tests
./bin/test.ps1 e2e

# Run CLI tests (Rust cargo test)
./bin/test.ps1 cli

# Pass extra cargo test args to CLI tests
./bin/test.ps1 cli -- --help

# Run all PowerShell test files
./bin/test.ps1 ps

# Run all PS tests quietly
./bin/test.ps1 ps -Quiet

# Run all main tests together
./bin/test.ps1 main

# Run fast tests and PS tests together
./bin/test.ps1 fast ps

# Run fast tests without persisting session
./bin/test.ps1 -NoSession fast

# Write session to a custom path
./bin/test.ps1 -SessionPath out/session.json ps

# Preview what tests would be run
./bin/test.ps1 -Show main

# Resume from the last failed Maven module
./bin/test.ps1 resume

# Launch the mock server
./bin/test.ps1 mock-site -Dmock.site.port=18080

# Run all real-world scenarios
./bin/test.ps1 rws --scenarios

# Run a specific scenario
./bin/test.ps1 rws --scenarios amazon

# Run scenarios against installed production CLI
./bin/test.ps1 rws --scenarios --production

# List discovered scenarios
./bin/test.ps1 rws --scenarios --list

# Run a single task file directly
./bin/test.ps1 rws --task tasks/real-world/generic/amazon.md

# Run scenarios with 30-minute per-task timeout
./bin/test.ps1 rws --scenarios --timeout 30
```

## Test Session

After each invocation, results are persisted to `.test-sessions/<session-id>/test-session.json`.
To inspect the latest session:

```bash
ls -t .test-sessions/*/test-session.json | head -1 | xargs cat
```

The session records the last status, log paths, per-file results (for `ps`),
system environment, and a rolling 5-entry history per test type.
Pass `-NoSession` to skip persistence, or `-SessionPath <path>` to write to a custom location.
