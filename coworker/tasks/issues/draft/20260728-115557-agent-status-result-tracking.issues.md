# Issues: agent-status-result-tracking

> **Source:** `20260728-115557-agent-status-result-tracking.full.md` | **Date:** 20260728-115557 | **Mode:** dev

## Scenario Background

### Task

**Task:** Verified the full `agent run` → `agent status` → `agent result` → `agent list` lifecycle.

**Outcome:** All 11 steps passed successfully. The agent task completed (LLM key was configured), returned a substantive page summary about Lie groups, and the `--clear` flag correctly removed all tracked tasks.

| Step | Verification | Result |
|------|-------------|--------|
| 1 | `agent run` submits task, returns UUID | ✓ `c4f3aab0-...` |
| 2 | `agent list` shows task with agent command, standard status label | ✓ `processing` (not `done`) |
| 3 | `agent status` returns valid JSON with integer statusCode, processState, boolean isDone | ✓ `statusCode:102`, `processState:in_progress`, `isDone:false` |
| 4 | Poll until isDone or failed | ✓ Reached `isDone:true` at poll 3 (~15s) |
| 5 | `agent result` returns non-null content | ✓ Full page summary about Lie groups |
| 6 | `agent list` shows terminal status | ✓ `completed` (not `done`) |
| 7 | `agent list` third call — no auto-prune | ✓ All tasks persist |
| 8 | Submit 2 new tasks | ✓ Two new UUIDs returned |
| 9 | `agent list` shows all 5 tasks | ✓ 5 tasks listed |
| 10 | `agent list --clear` | ✓ "Cleared 5 tracked agent task(s)." |
| 11 | `agent list` final | ✓ "No tracked async tasks." |

### Execution Context

**Key Commands:**

1. `./b4w.sh agent run "Navigate to https://en.wikipedia.org/wiki/Li_group and describe what a Lie group is"`
2. `./b4w.sh agent list`
3. `./b4w.sh agent status c4f3aab0-8c6b-4326-a5f1-b12c932165bd`
4. `./b4w.sh agent status c4f3aab0-...` (polled 8 times, 3s intervals)
5. `./b4w.sh agent result c4f3aab0-8c6b-4326-a5f1-b12c932165bd`
6. `./b4w.sh agent list`
7. `./b4w.sh agent list` (third call)
8. `./b4w.sh agent run "summarize the key features of Rust"` and `./b4w.sh agent run "explain monads in functional programming"`
9. `./b4w.sh agent list`
10. `./b4w.sh agent list --clear`
11. `./b4w.sh agent list`

**Important decisions:**
- Switched from `./b4w.ps1` to `./b4w.sh` because `pwsh` wasn't resolvable from the CRLF shebang in `b4w.ps1` on Linux (though `pwsh` binary exists at `/opt/microsoft/powershell/7/pwsh`).
- Discovered 3 pre-existing tasks from prior sessions during the first `agent list` call — continued testing alongside them since the task spec focuses on lifecycle behavior.

**Workarounds:** Used `./b4w.sh` instead of `./b4w.ps1` as documented in SKILL.md for Linux environments.

---

## Issues Found (6 issues)

### Issue 1: b4w.ps1 has CRLF line endings — shebang fails on Linux

**Severity:** Medium
**Category:** Reliability

#### Reproduction

./b4w.ps1 help

#### Expected Behavior

The script executes via pwsh and displays help output.

#### Actual Behavior

Error: /usr/bin/env: 'pwsh\r': No such file or directory. The CR character in the shebang line causes env to look for 'pwsh\r' instead of 'pwsh'.

#### Root Cause Analysis

The b4w.ps1 file has Windows-style CRLF line endings. The shebang line `#!/usr/bin/env pwsh` is followed by `\r\n`, and on Linux the kernel passes the `\r` as part of the interpreter name to `env`. The file should have LF-only line endings for cross-platform compatibility.

#### Code Pointer

`b4w.ps1:1 — the shebang line with trailing CR`

#### AI Suggested Improvement

- Convert b4w.ps1 to LF line endings (git can handle this via .gitattributes with `*.ps1 text eol=lf` or `*.ps1 text=auto`)
- Add a pre-commit hook or CI check that validates no CRLF in shell scripts and shebang-bearing files
- Document in SKILL.md that Linux/macOS users should prefer b4w.sh (already partially documented, but the error message is cryptic)

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 2: processState value is 'completed' but documented as 'done'

**Severity:** Medium
**Category:** Documentation

#### Reproduction

1. Submit an agent task that completes successfully.
2. Run `agent status <task-id>` after the task finishes.
3. Observe `"processState": "completed"` in the JSON output.
4. Compare with SKILL.md status code table which says `processState` is `"done"` for statusCode 200.

#### Expected Behavior

SKILL.md documentation should match the actual output, or the output should match the documentation.

#### Actual Behavior

The JSON output contains `"processState": "completed"` for a successfully finished task, but the SKILL.md reference table says the value should be `"done"` for statusCode 200 and 417.

#### Root Cause Analysis

The backend likely changed the processState value from "done" to "completed" at some point, but the SKILL.md documentation was not updated to reflect this change. The value "completed" is actually more descriptive and user-friendly, so the code is probably correct and the docs are stale.

#### Code Pointer

`skills/browser4-cli/SKILL.md: the Agent Task Lifecycle status codes reference table`

#### AI Suggested Improvement

- Update the SKILL.md status codes table to show `"completed"` instead of `"done"` for statusCode 200 and 417
- Add a row showing the `"status"` field values ("Processing", "OK") alongside processState for clarity
- Consider adding API versioning or a changelog so documentation can stay synchronized with backend changes

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 3: agent status JSON is very verbose — includes full pageSummary on every poll

**Severity:** Medium
**Category:** UX

#### Reproduction

Run `agent status <task-id>` for a completed task. Each poll returns ~4-5KB of JSON containing the full `commandResult.pageSummary` text.

#### Expected Behavior

Polling for task completion should return lightweight status info (statusCode, processState, isDone) without the full result payload. The full result should only be returned by `agent result`.

#### Actual Behavior

Every `agent status` call after task completion returns the full pageSummary text inline in the JSON, making polling-heavy workflows expensive in terms of bandwidth and parsing overhead. For an 8-poll cycle like in this test, ~32KB+ of redundant data was transmitted.

#### Root Cause Analysis

The backend `agent status` endpoint appears to return the full task result object including `commandResult` once the task completes, rather than separating status metadata from result data. The `commandResult` field is duplicated in every status poll after completion.

#### AI Suggested Improvement

- Return only status metadata (id, statusCode, processState, isDone, lastModifiedTime) from `agent status` and reserve the full result payload for `agent result`
- Alternatively, add a `--lightweight` or `--no-result` flag to `agent status` to suppress the result payload
- At minimum, truncate or omit the `commandResult` field from status polls when `isDone` is true, since the user will fetch it via `agent result` anyway

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 4: agent --help does not show --clear flag for agent list

**Severity:** Low
**Category:** Discoverability

#### Reproduction

Run `agent --help` and observe the `agent list` entry. It shows only 'List all tracked agent tasks and their status' with no mention of --clear. Contrast with `agent list --help` which documents --clear, --limit, and --offset.

#### Expected Behavior

`agent --help` should at least hint at `agent list --clear` or indicate that additional flags are available (e.g., 'see agent list --help for more options').

#### Actual Behavior

`agent --help` shows a one-line summary for each subcommand with no indication that `agent list` has additional flags. A user who only reads `agent --help` would never discover `--clear`.

#### Root Cause Analysis

The top-level `agent --help` output is a condensed summary. It doesn't surface subcommand-specific flags. This is a design choice to keep help concise, but it hides discoverable functionality.

#### AI Suggested Improvement

- Add a note to the `agent list` line in `agent --help`: '... (supports --clear, --limit, --offset)'
- Or add a line at the bottom of `agent --help`: 'Run agent list --help for additional options.'
- Consider adding `agent clear` as a standalone subcommand for better discoverability — it's semantically odd to clear tasks via a flag on `list`

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 5: Task descriptions are truncated in agent list — similar tasks become indistinguishable

**Severity:** Low
**Category:** UX

#### Reproduction

Submit multiple agent tasks with long descriptions that share a common prefix (e.g., 'Navigate to https://en.wikipedia.org/wi…'). Run `agent list`.

#### Expected Behavior

There should be a way to see the full description, either via a `--verbose` flag, wider output, or a dedicated `agent info <id>` command.

#### Actual Behavior

Descriptions are truncated at a fixed width with an ellipsis character. When multiple tasks share the same long prefix (e.g., multiple Wikipedia navigation tasks), all appear identical in the list.

#### Root Cause Analysis

The `agent list` table format uses a fixed column width for DESCRIPTION. For very long descriptions, truncation with ellipsis is necessary for readability, but there's no mechanism to expand or differentiate similar entries.

#### AI Suggested Improvement

- Add a `--verbose` flag to `agent list` that shows full descriptions (possibly wrapped)
- Consider displaying a short hash/ID suffix in the DESCRIPTION column when truncation occurs
- Add `agent info <id>` as a command that shows full details for a single task (description, full status, timing)

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 6: Pre-existing tasks from prior sessions appear in fresh agent list

**Severity:** Low
**Category:** UX

#### Reproduction

Start a fresh testing session. Run `agent list` before submitting any tasks yourself.

#### Expected Behavior

A first-time user would expect an empty task list. Seeing 3 pre-existing tasks (some 'completed', one 'queued') is confusing and makes the user wonder if they did something wrong or if the tool is shared.

#### Actual Behavior

The first `agent list` call showed 3 tasks from prior sessions (submitted at 19:32, 19:46). One was still 'queued' and later transitioned to 'failed (417)' during our testing.

#### Root Cause Analysis

Agent tasks are persisted to disk and survive across CLI sessions. This is by design for long-running or async tasks that may span multiple CLI invocations. However, there's no session-scoped isolation or indication that tasks belong to different sessions.

#### AI Suggested Improvement

- Add a session identifier or timestamp grouping to `agent list` output so users can distinguish their current session's tasks from prior sessions
- Consider adding `agent list --since <time>` or `agent list --session` to filter by recency
- Display a notice on first `agent list` when pre-existing tasks are found: 'Showing N tasks from prior sessions. Use --clear to remove them.'
- Alternatively, namespace tasks by session (-s flag) so different workflows don't interfere

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

## Overall Assessment

**Completion Status:** Successful — all 11 lifecycle steps passed. The agent task ran to completion, returned meaningful results, and --clear removed all tracked tasks cleanly.

**Success Rate:** 100% — every command in the lifecycle produced correct, non-null output with the expected vocabulary and JSON structure.

**Issues Found:** 6

**Major Blockers:** None. The b4w.ps1 CRLF issue required switching to b4w.sh, but this is a documented alternative and worked flawlessly.

**Most Confusing Aspects:** 1. The processState documentation discrepancy ('done' vs 'completed') would cause confusion for anyone writing scripts that parse the JSON. 2. Pre-existing tasks appearing in a 'fresh' session makes a new user question whether the tool is working correctly. 3. The verbose JSON from agent status makes polling feel heavyweight and wastes bandwidth — it's counterintuitive that 'checking status' returns the full result.

**Most Valuable Improvements:** 1. Fix the processState documentation to match actual behavior (one-line change). 2. Make agent status return lightweight status-only JSON, reserving the full payload for agent result. 3. Add agent-specific lifecycle examples to b4w.sh --help output (currently agent commands are listed but without the lifecycle workflow pattern). 4. Fix b4w.ps1 line endings for cross-platform reliability.

**Usability Rating:** 7/10

---

## How to Reproduce

### Common Setup

1. Clone the repository and `cd` to the repo root.
2. The CLI is invoked via `./b4w.ps1` which auto-builds from source when needed.
3. The backend server starts automatically in dev mode.
4. All commands from repo root: `./b4w.ps1 <command>`

### Per-Issue Reproduction Steps

#### Issue 1: b4w.ps1 has CRLF line endings — shebang fails on Linux

./b4w.ps1 help

#### Issue 2: processState value is 'completed' but documented as 'done'

1. Submit an agent task that completes successfully.
2. Run `agent status <task-id>` after the task finishes.
3. Observe `"processState": "completed"` in the JSON output.
4. Compare with SKILL.md status code table which says `processState` is `"done"` for statusCode 200.

#### Issue 3: agent status JSON is very verbose — includes full pageSummary on every poll

Run `agent status <task-id>` for a completed task. Each poll returns ~4-5KB of JSON containing the full `commandResult.pageSummary` text.

#### Issue 4: agent --help does not show --clear flag for agent list

Run `agent --help` and observe the `agent list` entry. It shows only 'List all tracked agent tasks and their status' with no mention of --clear. Contrast with `agent list --help` which documents --clear, --limit, and --offset.

#### Issue 5: Task descriptions are truncated in agent list — similar tasks become indistinguishable

Submit multiple agent tasks with long descriptions that share a common prefix (e.g., 'Navigate to https://en.wikipedia.org/wi…'). Run `agent list`.

#### Issue 6: Pre-existing tasks from prior sessions appear in fresh agent list

Start a fresh testing session. Run `agent list` before submitting any tasks yourself.

