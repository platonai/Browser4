# Issues: loop-monitoring

> **Source:** `20260708-185531-loop-monitoring.full.md` | **Date:** 20260708-185531 | **Mode:** dev

## Scenario Background

### Task

**Task:** Evaluate the `loop` command across 12 sub-tasks covering plain-text mode, shell mode, subcommand mode, listing, status checking, pause/resume, stop, timeout, and bulk operations.

**Outcome:** 11 of 12 sub-tasks completed successfully. The plain-text mode loop (health-check) ran but both iterations failed with HTTP timeout errors on the `command_run` MCP tool — the loop executor itself worked correctly (count-based completion), but the task payload failed.

### Summary of execution:

| Step | Description | Result |
|------|-------------|--------|
| 1 | Plain-text loop `--name health-check` | Loop ran 2 iters, both errored (LLM timeout) |
| 2 | `--list` while loop runs | Showed health-check running + stale default loop |
| 3 | `--status --name health-check` | Correctly showed running state, 0/2 iters |
| 4 | `--shell "date" --name shell-test` | ✅ 2 iters completed successfully |
| 5 | `-- status --name cli-test` | ✅ 2 iters completed successfully |
| 6 | `--list` after #4-5 | Only showed running loops (completed ones auto-cleaned) |
| 7 | `--pause --shell ... --name paused-test` | ✅ Created paused, verified via `--list` and `--status` |
| 8 | Resume then pause | ✅ Resume spawned background process, pause worked |
| 9 | `--stop --name paused-test` | ✅ Stopped, state cleared |
| 10 | `--timeout 30 --name timeout-test` | ✅ Ran 6 iters, stopped on timeout exactly at 30s |
| 11 | `--stop-all` | ✅ Cleaned 1 stale loop |
| 12 | `--list` (final) | ✅ "No persisted loops" confirmed |

---

### Execution Context

**Key Commands:**

```
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- loop "navigate to..." --count 2 --interval 10 --name health-check
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- loop --list
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- loop --status --name health-check
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- loop --shell "date" --count 2 --interval 5 --name shell-test
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- loop --name cli-test --count 2 --interval 5 -- status
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- loop --pause --shell "..." --count 5 --interval 5 --name paused-test
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- loop --resume --name paused-test
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- loop --pause --name paused-test
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- loop --stop --name paused-test
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- loop --shell "echo timeout-test" --count 100 --interval 5 --timeout 30 --name timeout-test
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- loop --stop-all
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- loop --history
```

---

## Issues Found (9 issues)

### Issue 1: Plain-text loop mode fails without a working LLM backend

**Severity:** High
**Category:** Reliability

#### Reproduction

```
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- loop "navigate to https://httpbin.org/get, take a snapshot, and report the page status" --count 2 --interval 10 --name health-check
```

#### Expected Behavior

The loop executes the natural language task by navigating to httpbin.org, taking a snapshot, and reporting status, completing 2 iterations successfully.

#### Actual Behavior

Both iterations failed with: `[ERROR] Iteration N: HTTP request timed out [tool=command_run, endpoint=http://localhost:8182/mcp/call-tool, timeout=30s]: error sending request for url (http://localhost:8182/mcp/call-tool)`. The loop itself reported "Loop finished — 2 iteration(s) completed" despite 100% failure rate.

#### Root Cause Analysis

Plain-text mode submits tasks via the `command_run` MCP tool, which requires an LLM backend to parse and execute natural language commands. When the LLM is unavailable or unconfigured (no API key, rate-limited, etc.), the MCP call times out after 30s. The server was UP during testing, so this is a backend LLM dependency issue. The loop executor doesn't distinguish between "ran with errors" and "ran successfully" in its completion message.

#### Code Pointer

`The loop completion summary logic — it prints "✓ Loop finished" regardless of per-iteration error counts.`

#### AI Suggested Improvement

- Report the number of failed vs. successful iterations in the completion summary (e.g., "Loop finished — 2/2 iterations completed, 2 with errors")
- Exit with a non-zero code when any iteration fails
- Document the LLM backend requirement for plain-text mode in both the loop reference and the SKILL.md
- Consider adding a pre-flight check: if `command_run` is unavailable, warn the user before the loop starts

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Core issue: loop reports success even with 100% iteration failure rate. Agents parsing the completion message get misleading signals. Fix should include error counting in summary, non-zero exit codes, and a pre-flight LLM availability check for plain-text mode.

---

### Issue 2: Loop state persists across sessions; stale loops from prior sessions cause confusion

**Severity:** Medium
**Category:** UX

#### Reproduction

1. Run a named loop in a previous session (or have a leftover default loop)
2. In a new session, run `loop --list`
3. Observe the stale loop still showing as "running"

#### Expected Behavior

Either the loop detects the prior process is dead and marks itself as "stale"/"orphaned", or it auto-cleans on daemon restart, or the `--list` output distinguishes live vs. orphaned loops.

#### Actual Behavior

A "default" loop from a prior eval session showed as "▶ running" with 6 iterations. The process that spawned it was long dead. It remained in the list until explicitly stopped with `--stop-all`.

#### Root Cause Analysis

Loop state is persisted to disk (`~/.browser4/loop-state.json` / `~/.browser4/loops/<name>.json`) and the state machine only transitions via explicit CLI commands (pause/stop/resume/completion). There's no liveness check — if the background process dies, the state file remains with `status: "running"`. On daemon restart, no reconciliation occurs.

#### Code Pointer

`The loop state file loader and `--list` formatter — they read `status` from the JSON file without verifying the background process is actually alive.`

#### AI Suggested Improvement

- When loading loop state, check if a PID is recorded and verify the process is still alive; if not, mark the loop as "orphaned" or auto-clean
- Add a `Stale` or `Orphaned` status distinct from `Running`
- Print a warning on `--list` when orphaned loops are detected: "⚠ 1 loop appears orphaned (background process not found). Use --stop to clear."
- Consider auto-cleaning orphaned loops on daemon start

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Stale loop state from dead processes misleads agents about what's actually running. Fix should verify PID liveness on state load and surface orphaned loops with a distinct status in --list output rather than silently auto-cleaning.

---

### Issue 3: Error handling in loop completion message is misleading

**Severity:** Medium
**Category:** UX

#### Reproduction

Run a loop where every iteration fails (e.g., plain-text mode without LLM):
```
cargo run -- ... loop "bad command" --count 2 --name test
```

#### Expected Behavior

The completion message indicates failures: "Loop finished — 2 iterations completed, 2 errors."

#### Actual Behavior

The completion message reads: "✓ Loop finished — 2 iteration(s) completed." with no indication of errors. Users must inspect the full output to discover that every iteration failed.

#### Root Cause Analysis

The loop's completion summary only counts iterations completed vs. max, not the success/failure ratio. While errors *are* logged per-iteration, the final summary is silent about them.

#### Code Pointer

`The loop completion path in the loop executor.`

#### AI Suggested Improvement

- Track an error counter alongside the iteration counter
- Include error count in the completion summary: "Loop finished — 2/2 iterations completed (2 errors)"
- Exit with non-zero exit code when errors occurred

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [x] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: DUPLICATE] Same root cause as Issue 1: the loop completion summary only counts iterations, not success/failure. Issue 1's fix (error counting + non-zero exit codes) fully subsumes this one.

---

### Issue 4: `--list` and `--history` are separate commands but users expect unified view

**Severity:** Low
**Category:** Discoverability

#### Reproduction

1. Run several loops to completion
2. Run `loop --list`
3. See "No persisted loops" — but loops just ran

#### Expected Behavior

Either `--list` shows recently completed loops with a "(completed)" status, or the `--list` output includes a hint pointing to `--history`.

#### Actual Behavior

`--list` only shows running/paused loops. Completed loops auto-clean and disappear. Users must know about the separate `--history` command. A new user runs loops, then runs `--list` and sees nothing — confusing.

#### Root Cause Analysis

Design choice to separate active loops (`--list`) from completed loops (`--history`). The separation is logical but not discoverable.

#### Code Pointer

`The `--list` handler — could add a footer hint.`

#### AI Suggested Improvement

- Add a footer to `--list` when it returns empty: "No active loops. Use `--history` to see recently completed loops."
- Consider an `--all` flag that combines `--list` and `--history` output

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Low-cost, high-value discoverability fix. An empty --list output with no hint about --history is confusing for both humans and agents. A one-line footer is trivial to add.

---

### Issue 5: Pause command has inherent race condition with fast-executing loops

**Severity:** Low
**Category:** Reliability

#### Reproduction

1. Create a paused loop with a fast command (e.g., `echo hi`)
2. Resume it
3. Immediately try to pause it

#### Expected Behavior

The loop pauses within a reasonable time.

#### Actual Behavior

The loop may complete all iterations before the pause takes effect. The first attempt with `echo paused-test-running` (2 iters, 5s interval) completed before pause could be issued. Second attempt with `sleep 3 && echo` worked.

#### Root Cause Analysis

The pause mechanism sets a flag in the state file that is checked at the top of each iteration. If the loop finishes all iterations before the next iteration boundary, pause never takes effect. This is documented behavior but the UX is poor for the user who just wants to pause what they started.

#### Code Pointer

`The pause flag check in the loop execution loop.`

#### AI Suggested Improvement

- Document this behavior more prominently in the reference docs
- Consider a `--signal` or `--kill` option for immediate termination (sends SIGTERM to the background process rather than waiting for the flag check)

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [x] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: WONTFIX] The pause mechanism is intentionally flag-based and checked at iteration boundaries — this is correct design for graceful pause/resume between iterations. For immediate termination, --stop already exists. The race is inherent to any cooperative (non-preemptive) pause model and cannot be eliminated without a fundamentally different architecture. Documenting the iteration-boundary behavior more prominently in docs is reasonable but not a code change.

---

### Issue 6: `--name` validation silently allows reusing names of completed loops

**Severity:** Low
**Category:** UX

#### Reproduction

1. Run `loop --shell "echo first" --count 2 --name demo`
2. Wait for it to complete (auto-cleans)
3. Run `loop --shell "echo second" --count 2 --name demo`

#### Expected Behavior

Either a warning that the name was previously used, or seamless reuse.

#### Actual Behavior

Seamless reuse with no indication of prior use. The history records both, but the user sees no connection. Not harmful but could mask mistakes (e.g., thinking you're resuming a loop when you're starting a new one with different parameters).

#### Root Cause Analysis

Name uniqueness is not enforced, and previously-used names are not checked against history.

#### AI Suggested Improvement

- When creating a named loop, check history and warn if the name was used recently: "Note: a loop named 'demo' completed 5 minutes ago. Starting a fresh loop."

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [x] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: DEFER] A warning on name reuse is a nice-to-have but adds minimal value for AI agents (which track their own loop state). The cost of querying history on every named loop creation is not justified by the benefit. Would reconsider if users report actual bugs caused by name collision confusion.

---

### Issue 7: `--interval` short flag `-i` conflicts with other CLI conventions

**Severity:** Low
**Category:** Discoverability

#### Reproduction

A user familiar with Unix conventions might expect `-i` to mean "interactive" or "case-insensitive" (common in `grep`, `sed`). The `loop` command uses `-i` for `--interval`.

#### Expected Behavior

`-i` is either not used (if ambiguous) or documented in a way that's easy to discover.

#### Actual Behavior

`-i` maps to `--interval`. The help output lists it clearly, but muscle memory from other tools might cause confusion. Low impact but worth noting for completeness.

#### Root Cause Analysis

Short flag assignment. `-n` for `--count` is standard. `-t` for `--timeout` is standard. `-i` for `--interval` is reasonable but worth documenting clearly.

#### AI Suggested Improvement

- This is a minor convention issue — no change needed, just noting it for awareness

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [x] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: REJECT] -i for --interval is a conventional and well-documented choice. The help output clearly lists all short flags. Unix conventions vary widely per-tool (install, interactive, include, input, etc.) — there is no universal standard. No evidence of actual user confusion; this is a theoretical concern only. The issue's own suggested improvement concedes 'no change needed.'

---

### Issue 8: No `--json` output example in loop reference docs

**Severity:** Low
**Category:** Documentation

#### Reproduction

Read `skills/browser4-cli/references/loop.md`. The Output section shows JSON schema but the command example uses the `--json` global flag before `loop`, which might not be obvious to users reading the loop docs in isolation.

#### Expected Behavior

The reference docs clearly show how to get machine-readable output.

#### Actual Behavior

The example is present but embedded in the "JSON output" subsection. It could be more prominent for scripted/automated use cases.

#### Root Cause Analysis

Documentation structure — the JSON example exists but is in a subsection rather than being highlighted as a primary use case.

#### AI Suggested Improvement

- Add a `--json` example to the Quick Start section
- Consider adding a note: "For scripting/automation, always use `--json` before the `loop` command"

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Adding a --json example improves documentation for scripting/automation. AI agents reading docs to construct loop commands benefit from clear programmatic-output examples.

---

### Issue 9: `--resume` spawns background process — control returns immediately with no `--wait` option

**Severity:** Low
**Category:** UX

#### Reproduction

```
loop --resume --name my-loop
```

#### Expected Behavior

Option to wait (block) until the resumed loop completes, similar to `--wait` on `swarm query`.

#### Actual Behavior

`--resume` spawns a background process and returns immediately. The user must manually monitor with `--list` or `--status`. For short loops, this is inconvenient — you resume a loop with 1 remaining iteration and 5s interval, but you have to poll to know it's done.

#### Root Cause Analysis

The resume operation always backgrounds. There's no `--wait` or `--foreground` flag.

#### AI Suggested Improvement

- Add a `--wait` flag to `--resume` that blocks until the resumed loop completes
- Or add a `--foreground` flag that runs the resumed loop synchronously

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [x] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: DEFER] Feature request for a --wait flag on --resume. While useful for scripting, the current polling approach (--status/--list) works adequately. Implementation requires blocking CLI while monitoring daemon state, which is non-trivial. Postpone until there's demonstrated demand from automated workflow use cases.

---

## How to Reproduce

### Common Setup

1. Clone the repository and `cd` to the repo root.
2. Build the CLI: `cd cli/browser4-cli && cargo build`
3. The backend server starts automatically in dev mode.
4. All commands from repo root: `cd cli/browser4-cli && cargo run -- <command>`

### Per-Issue Reproduction Steps

#### Issue 1: Plain-text loop mode fails without a working LLM backend

```
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- loop "navigate to https://httpbin.org/get, take a snapshot, and report the page status" --count 2 --interval 10 --name health-check
```

#### Issue 2: Loop state persists across sessions; stale loops from prior sessions cause confusion

1. Run a named loop in a previous session (or have a leftover default loop)
2. In a new session, run `loop --list`
3. Observe the stale loop still showing as "running"

#### Issue 3: Error handling in loop completion message is misleading

Run a loop where every iteration fails (e.g., plain-text mode without LLM):
```
cargo run -- ... loop "bad command" --count 2 --name test
```

#### Issue 4: `--list` and `--history` are separate commands but users expect unified view

1. Run several loops to completion
2. Run `loop --list`
3. See "No persisted loops" — but loops just ran

#### Issue 5: Pause command has inherent race condition with fast-executing loops

1. Create a paused loop with a fast command (e.g., `echo hi`)
2. Resume it
3. Immediately try to pause it

#### Issue 6: `--name` validation silently allows reusing names of completed loops

1. Run `loop --shell "echo first" --count 2 --name demo`
2. Wait for it to complete (auto-cleans)
3. Run `loop --shell "echo second" --count 2 --name demo`

#### Issue 7: `--interval` short flag `-i` conflicts with other CLI conventions

A user familiar with Unix conventions might expect `-i` to mean "interactive" or "case-insensitive" (common in `grep`, `sed`). The `loop` command uses `-i` for `--interval`.

#### Issue 8: No `--json` output example in loop reference docs

Read `skills/browser4-cli/references/loop.md`. The Output section shows JSON schema but the command example uses the `--json` global flag before `loop`, which might not be obvious to users reading the loop docs in isolation.

#### Issue 9: `--resume` spawns background process — control returns immediately with no `--wait` option

```
loop --resume --name my-loop
```

