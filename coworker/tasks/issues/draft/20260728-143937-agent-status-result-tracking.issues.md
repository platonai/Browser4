# Issues: agent-status-result-tracking

> **Source:** `20260728-143937-agent-status-result-tracking.full.md` | **Date:** 20260728-143937 | **Mode:** dev

## Scenario Background

### Task

All 11 steps of the agent lifecycle evaluation completed successfully:

1. **`agent run`** → Task submitted: `24e92fbb-13f4-49e1-a01b-c489b006eb5d`
2. **`agent list`** → Task appeared with correct TASK ID, COMMAND "agent", STATUS "processing"
3. **`agent status`** → Valid JSON, `statusCode` integer (102), `processState` "in_progress", `isDone` false
4. **Polled** → Task transitioned to `isDone: true`, `statusCode: 200`, `processState: "completed"` after ~20 seconds
5. **`agent result`** → Non-null JSON with `pageSummary` field — agent navigated to the URL and correctly noted the "Li group" misspelling
6. **`agent list`** → STATUS column showed `completed` (not the deprecated `done` label)
7. **`agent list`** (third time) → Terminal task persisted — no auto-prune behavior
8. **Two new `agent run`** → Both submitted successfully (`f6a40d8f-...` and `d57dc646-...`)
9. **`agent list`** → All 5 tasks visible (3 completed + 2 processing)
10. **`agent list --clear`** → `Cleared 5 tracked agent task(s).` with exit code 0
11. **`agent list`** → `No tracked async tasks.` — all tasks removed

### Execution Context

| Step | Command | Key Observations |
|------|---------|-----------------|
| Prep | `./b4w.ps1 help` | Full help rendered; agent commands listed under "Agent:" section |
| Prep | Read `SKILL.md` | Agent lifecycle documented with status code table and polling pattern |
| 1 | `agent run "Navigate to Wikipedia..."` | Task ID printed with guidance to use `agent status` or `agent list` |
| 2 | `agent list` | 3 pre-existing tasks from prior runs visible; new task showed "processing" |
| 3 | `agent status <id>` | Raw JSON returned (not JSON-enveloped); valid structure |
| 4 | Poll loop (3s intervals) | Task completed in ~20s; statusCode changed 102→200 |
| 5 | `agent result <id>` | Returned JSON with pageSummary; not enveloped |
| 6 | `agent list` | Task showed "completed" (correct lifecycle labe...

(truncated — see full.md for complete trace)

---

## Issues Found (5 issues)

### Issue 1: --help agent omits --clear, --limit, --offset flags for agent list

**Severity:** Medium
**Category:** Discoverability

#### Reproduction

Run `./b4w.ps1 --help agent` and observe that `agent list` is listed with no flags.

#### Expected Behavior

The --help agent output should mention key flags like --clear, --limit, --offset for agent list, similar to how other commands surface their options.

#### Actual Behavior

`--help agent` shows: `agent list — List all tracked agent tasks and their status` with no flag hints. Users must run `agent list --help` (two levels deep) to discover --clear.

#### Root Cause Analysis

The category-level help filter (`--help agent`) only renders subcommand names and one-line descriptions. It does not enumerate per-subcommand flags. The implementation likely iterates over subcommands and prints their `about` strings without including their argument/flag definitions.

#### Code Pointer

`cli/browser4-cli/src/ — likely in the help rendering or clap configuration where category filters are applied`

#### AI Suggested Improvement

- Extend `--help agent` to show a brief flags summary per subcommand (e.g., `agent list [--clear] [--limit N] [--offset N]`)
- Alternatively, add a hint at the bottom: "Run `agent list --help` for detailed options."

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 2: SKILL.md agent lifecycle section missing --clear, --limit, --offset documentation

**Severity:** Medium
**Category:** Documentation

#### Reproduction

Read skills/browser4-cli/SKILL.md §Agent Task Lifecycle and note the absence of --clear, --limit, or --offset flags.

#### Expected Behavior

The SKILL.md should document the full `agent list` interface including --clear (for task pruning), --limit (for pagination), and --offset.

#### Actual Behavior

Only basic `agent list` usage is shown. The --clear flag (essential for task lifecycle cleanup) is undocumented in the skill file.

#### Root Cause Analysis

The SKILL.md agent section was written to cover the basic submit→poll→retrieve workflow. Task management flags (--clear, --limit, --offset) were added to the CLI but not backported to the skill documentation.

#### Code Pointer

`skills/browser4-cli/SKILL.md:400-446 (Agent Task Lifecycle section)`

#### AI Suggested Improvement

- Add a "Task Management" subsection documenting `agent list --clear`, `--limit`, and `--offset`
- Include an example showing the full lifecycle including cleanup: `agent list --clear`

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 3: Pre-existing tasks from prior sessions visible on first agent list

**Severity:** Low
**Category:** UX

#### Reproduction

Run `agent list` as a new user. If any prior CLI session submitted agent tasks, they appear in the list.

#### Expected Behavior

A new user's first `agent list` should either show no tasks or clearly distinguish between the current session's tasks and tasks from prior sessions.

#### Actual Behavior

When I ran `agent list`, 3 tasks from prior test runs appeared alongside my newly submitted task. A first-time user would be confused seeing tasks they didn't create.

#### Root Cause Analysis

Agent tasks are persisted to a shared task store on disk (likely in the Browser4 backend's data directory). There is no session-scoping or automatic expiry — tasks accumulate across CLI sessions until explicitly cleared.

#### AI Suggested Improvement

- Consider auto-clearing terminal tasks on CLI startup or session close, or scoping the task store to a session identifier
- Alternatively, add a note in the output when pre-existing tasks are shown: "Showing N tasks (M from prior sessions)"
- Document in SKILL.md that tasks persist across sessions and must be manually cleared

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 4: agent status outputs raw JSON by default instead of JSON-enveloped format

**Severity:** Low
**Category:** UX

#### Reproduction

Run `agent status <id>` and observe raw JSON output. Compare with `agent status <id> --json` which produces a JSON-enveloped format with command/status wrapper.

#### Expected Behavior

Either default output should be JSON-enveloped (consistent with other commands), or the documentation should clearly explain the difference between default and --json output.

#### Actual Behavior

Default `agent status` outputs bare JSON (`{"id":...,"statusCode":...}`). With `--json`, output is wrapped (`{"command":"agent-status","output":{"raw":{...}},"status":"ok"}`). This inconsistency means a script parsing `agent status` output gets different structures depending on the --json flag.

#### Root Cause Analysis

The agent status command's default output path bypasses the standard JSON envelope that other commands use. The --json flag re-enables the envelope. This appears to be intentional (raw JSON is more ergonomic for human reading) but creates an inconsistency.

#### AI Suggested Improvement

- Document this behavior explicitly in both --help output and SKILL.md
- Consider making the envelope the default for machine consistency, with a --raw flag for bare JSON

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 5: Command ordering inconsistent between main help and --help agent

**Severity:** Low
**Category:** UX

#### Reproduction

Compare `./b4w.ps1 help` agent section with `./b4w.ps1 --help agent` output.

#### Expected Behavior

Commands should appear in the same order across all help views.

#### Actual Behavior

Main help lists: run, status, result, list. `--help agent` lists: run, list, result, status. The lifecycle order (run→status→result→list) is more logical and should be consistent.

#### Root Cause Analysis

The main help and the category filter likely use different ordering logic — possibly alphabetical vs. declaration order in the clap configuration.

#### AI Suggested Improvement

- Unify command ordering across all help views to follow the logical lifecycle: agent run → agent status → agent result → agent list

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

## Overall Assessment

**Completion Status:** Successful — all 11 task steps completed without errors. The agent run → status → result → list → clear lifecycle works correctly end-to-end.

**Success Rate:** 100% — every command produced expected output with correct exit codes. No workarounds were needed.

**Issues Found:** 5

**Most Confusing Aspects:** 1) The --clear flag is hidden two levels deep in the help hierarchy — visible in `agent list --help` but not in `--help agent`. 2) The agent status command outputs different JSON structures with and without --json, which could trip up script authors. 3) Pre-existing tasks from prior sessions appear in agent list without any indication they're from a different session.

**Most Valuable Improvements:** 1) Surface --clear, --limit, --offset flags in `--help agent` output. 2) Document task management flags in SKILL.md's agent lifecycle section. 3) Add session-scoping or staleness indicators to persisted tasks.

**Usability Rating:** 8/10

---

## How to Reproduce

### Common Setup

1. Clone the repository and `cd` to the repo root.
2. The CLI is invoked via `./b4w.ps1` which auto-builds from source when needed.
3. The backend server starts automatically in dev mode.
4. All commands from repo root: `./b4w.ps1 <command>`

### Per-Issue Reproduction Steps

#### Issue 1: --help agent omits --clear, --limit, --offset flags for agent list

Run `./b4w.ps1 --help agent` and observe that `agent list` is listed with no flags.

#### Issue 2: SKILL.md agent lifecycle section missing --clear, --limit, --offset documentation

Read skills/browser4-cli/SKILL.md §Agent Task Lifecycle and note the absence of --clear, --limit, or --offset flags.

#### Issue 3: Pre-existing tasks from prior sessions visible on first agent list

Run `agent list` as a new user. If any prior CLI session submitted agent tasks, they appear in the list.

#### Issue 4: agent status outputs raw JSON by default instead of JSON-enveloped format

Run `agent status <id>` and observe raw JSON output. Compare with `agent status <id> --json` which produces a JSON-enveloped format with command/status wrapper.

#### Issue 5: Command ordering inconsistent between main help and --help agent

Compare `./b4w.ps1 help` agent section with `./b4w.ps1 --help agent` output.

