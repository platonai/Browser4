# Issues: agent-status-result-tracking

> **Source:** `20260727-231911-agent-status-result-tracking.full.md` | **Date:** 20260727-231911 | **Mode:** dev

## Scenario Background

### Task

**Task completed successfully.** The full `agent run` → `agent status` → `agent result` → `agent list` → `agent list --clear` lifecycle was verified end-to-end:

1. **Agent task submitted** and returned a UUID task ID (`58dd054f-e62f-439c-99df-d304dd21d9b2`)
2. **Agent task completed successfully** — the LLM API key was configured, so the task actually navigated to Wikipedia, retrieved the page, and produced a comprehensive summary of Lie groups (noting the "Li group" misspelling)
3. **Status polling** returned well-structured JSON with integer `statusCode`, boolean `isDone`, and string `processState`
4. **Result retrieval** returned the full page summary as JSON
5. **Task list operations** (`list`, `list --clear`) behaved as specified
6. All 11 verification steps passed

An LLM API key was configured, so the task actually succeeded (status code 200) with real page content — a richer test than the expected failure case.

### Execution Context

**Key Commands:**

| Step | Command | Purpose |
|------|---------|---------|
| 1 | `./b4w.ps1 agent run "Navigate to https://en.wikipedia.org/wiki/Li_group and describe what a Lie group is"` | Submit task |
| 2 | `./b4w.ps1 agent list` | Verify task in list with `processing` status |
| 3 | `./b4w.ps1 agent status 58dd054f-...` | Check JSON status (3 polls before completion) |
| 4 | `./b4w.ps1 agent status 58dd054f-...` (repeated) | Poll until `isDone: true` (~12s total) |
| 5 | `./b4w.ps1 agent result 58dd054f-...` | Fetch completed result |
| 6 | `./b4w.ps1 agent list` | Verify terminal `completed` status |
| 7 | `./b4w.ps1 agent list` | Verify no auto-pruning |
| 8 | `./b4w.ps1 agent run "summarize the key features of Rust"` + `agent run "explain monads in functional programming"` | Submit two more tasks |
| 9 | `./b4w.ps1 agent list` | Verify all tasks appear |
| 10 | `./b4w.ps1 agent list --clear` | Clear all tracked tasks |
| 11 | `./b4w.ps1 agent list` | Verify empty ("No tracked async tasks.") |

**Additional investigation commands:**
- `agent --help`, `agent list --help`, `agent run --help`, `agent status --help`, `agent result --help` — documentation checks
- `agent status` / `agent result` on cleared in-progress tasks — verified backend continuity

**Key decisions:**
- Polled `agent status` every ~4 seconds; task completed after 3 polls (~12 seconds)
- Used `grep -E '^\{'` to filter cargo build output from JSON results
- Noted pre-existing stale tasks from prior sessions (not part of the test)

**Workarounds required:**
- Cargo build output (warnings, `Finished` lines) mixed with command output; filtered with grep for JSON lines

---

```json
{
  "issues": [
    {
      "title": "--clear flag not discoverable from agent --help",
      "severity": "Medium",
      "category": "Discoverability",
      "reproduction": "./b4w.ps1 agent --help",
      "expected": "The --clear flag (or a clear subcommand) should be listed under agent subcommands or mentioned in the top-level agent help.",
      "actual": "agent --help lists only four subcommands: status, run, list, result. No mention of --clear. Users must know to run agent list --help specifically to discover the clear functionality.",
      "rootCause": "The agent --help output only enumerates subcommands, not their flags. The --clear flag is documented only in agent list --help. A user reading agent --help has no way to know that tasks can be cleared.",
      "codePointer": "cli/browser4-cli/src/ — likely in the clap/help configuration for the agent subcommand group",
      "suggestion": "- Add a note to agent --help: \"Use agent list --clear to remove all tracked tasks.\"\n- Or add agent clear as a dedicated subcommand for better discoverability (consistent with crawl clear)"
    },
    {
      "title": "--clear removes in-progress tasks without warning or cancellation",
      "severity": "Medium",
      "category": "UX",
      "reproduction": "1. agent run \"some task\"\n2. Immediately run agent list --clear\n3. The in-progress task is cleared from the list but continues running on the backend (still accessible by ID)",
      "expected": "Either: (a) --clear should only clear terminal-state tasks (completed/failed) with a warning about in-progress tasks, or (b) --clear should cancel running tasks and then clear them, or (c) a confirmation/warning should be shown about in-progress tasks being cleared.",
      "actual": "--clear silently removes all tasks including 2 in-progress ones. The tasks continue running on the backend (verified via agent status on cleared task IDs). No warning is shown. The user loses visibility of running tasks.",
      "rootCause": "The --clear flag likely just clears the local task tracking store without checking task state or interacting with the backend to cancel running tasks. This is a design decision rather than a bug, but it's surprising UX.",
      "codePointer": "",
      "suggestion": "- Only clear terminal-state tasks by default; require --force or --all to clear in-progress tasks\n- Display a warning when clearing in-progress tasks: \"2 in-progress task(s) will remain running on the backend but will no longer appear in the list.\"\n- Consider adding agent cancel <id> for explicit task cancellation"
    },
    {
      "title": "Documentation examples use fictional task IDs (agent-task-1) not real UUIDs",
      "severity": "Low",
      "category": "Documentation",
      "reproduction": "Read agent.md reference or run agent status --help / agent result --help",
      "expected": "Documentation examples should show realistic task ID formats that users will actually encounter.",
      "actual": "All agent documentation examples use 'agent-task-1' as the task ID. Real task IDs are UUIDs like '58dd054f-e62f-439c-99df-d304dd21d9b2'. This mismatch can confuse first-time users who may try to use 'agent-task-1' literally.",
      "rootCause": "Documentation was written before the task ID format was finalized or was never updated when the format changed from sequential names to UUIDs.",
      "codePointer": "skills/browser4-cli/references/agent.md:33, cli/browser4-cli/src/ (help text strings)",
      "suggestion": "- Update examples in agent.md to use UUID-style IDs\n- Update help text examples: \"browser4-cli agent status <task-id>\" should show a realistic example like \"browser4-cli agent status 58dd054f-e62f-439c-99df-d304dd21d9b2\"\n- Or show the format: \"agent status <uuid>\" with a note that the ID is printed by agent run"
    },
    {
      "title": "Stale task persisted for 8+ days in queued state",
      "severity": "Low",
      "category": "Reliability",
      "reproduction": "Run agent list after the backend has been running for days. A task from July 20 (8 days ago) appeared in queued state.",
      "expected": "Stale tasks should eventually time out or be auto-cleaned. An 8-day-old queued task that never started is likely stuck and should be detected and cleaned up.",
      "actual": "Task a8244106-4df4-4bd2-9192-2bfe38e1c631 was submitted on 2026-07-20 21:23:37 and remained queued 8 days later. No timeout, no auto-cleanup, no status change.",
      "rootCause": "No TTL/timeout mechanism for tasks stuck in queued state. The task was likely orphaned by a backend restart or was submitted against a different session/configuration and never picked up.",
      "codePointer": "",
      "suggestion": "- Implement a configurable TTL for queued tasks (e.g., 24 hours) after which they auto-transition to 'failed (timeout)'\n- Display a warning in agent list when tasks older than N hours are still queued\n- Consider persisting task state across backend restarts to prevent orphaned tasks"
    },
    {
      "title": "agent status JSON output is extremely verbose",
      "severity": "Low",
      "category": "UX",
      "reproduction": "Run agent status <id> on a running task and observe the message field.",
      "expected": "Status output should be concise and focused on actionable information: current state, progress, and any errors.",
      "actual": "The message field contains a very long string with internal state visitor event traces: 'StatefulPageVisitor.onCreated,onWillLoad,onWillFetch,onWillNavigate,...' and the commandResult.summary field duplicates this verbatim. This internal debug trace leaks into the user-facing JSON output.",
      "rootCause": "Internal event tracing from StatefulPageVisitor/StatefulAgentRunner is serialized into the user-facing message field. These are implementation details, not user-facing information.",
      "codePointer": "",
      "suggestion": "- Keep message concise: human-readable status descriptions like 'Navigating to page...', 'Extracting content...', 'Generating summary...'\n- Move the internal event trace to a debug-only field or a --verbose flag\n- The commandResult.summary should contain the actual task result, not a copy of the event trace"
    },
    {
      "title": "Status label casing inconsistent between agent list and agent status JSON",
      "severity": "Low",
      "category": "UX",
      "reproduction": "1. agent list (shows lowercase: completed, processing, queued)\n2. agent status (JSON shows: \"status\":\"OK\" or \"status\":\"Processing\")",
      "expected": "Consistent casing across all interfaces. If the list uses 'completed', the JSON should use 'completed' (not 'OK').",
      "actual": "agent list STATUS column uses lowercase labels (completed, processing, queued) while agent status JSON returns 'status':'OK' for completed tasks and 'status':'Processing' for running tasks. The 'OK' label is not a standard lifecycle term.",
      "rootCause": "The list display normalizes status to lowercase for the table view, while the JSON status field uses the raw internal enum name (OK, Processing). These come from different code paths.",
      "codePointer": "",
      "suggestion": "- Use consistent labels: completed (not OK), processing, queued, failed\n- Or document the mapping between list labels and JSON status values\n- Consider adding a statusLabel field to the JSON that matches the list display"
    },
    {
      "title": "No agent clear standalone command — inconsistent with crawl clear pattern",
      "severity": "Low",
      "category": "Discoverability",
      "reproduction": "Try running agent clear (does not exist). Must use agent list --clear instead.",
      "expected": "A separate agent clear command for better discoverability and consistency with the crawl clear command that already exists.",
      "actual": "Clearing tasks requires the --clear flag on agent list. The crawl command family has a standalone crawl clear command, creating an inconsistent pattern. agent --help does not mention --clear at all.",
      "rootCause": "The agent command family was designed with --clear as a flag on list, while the crawl command family uses a dedicated clear subcommand. Different design patterns for similar functionality.",
      "codePointer": "",
      "suggestion": "- Add agent clear as an alias or primary command for clearing tasks\n- Keep agent list --clear as a convenience flag but also support the standalone command\n- Align the pattern with crawl clear for consistency"
    },
    {
      "title": "agent status help example shows old status format (all-caps RUNNING)",
      "severity": "Low",
      "category": "Documentation",
      "reproduction": "Read skills/browser4-cli/references/agent.md which shows: {\"status\":\"RUNNING\"}",
      "expected": "Documentation should show the actual status format returned by the current code.",
      "actual": "The agent.md reference shows {\"status\":\"RUNNING\"} and lists statuses as RUNNING, COMPLETED, FAILED, EXPECTATION_FAILED (all-caps). The actual agent status output returns \"status\":\"Processing\" and \"status\":\"OK\" — different words and different casing. The document also uses \"processState\":\"processing\" but actual output uses \"processState\":\"in_progress\".",
      "rootCause": "The agent.md documentation was written for an earlier version of the agent system and was not updated when the status labels and processState values changed.",
      "codePointer": "skills/browser4-cli/references/agent.md:66",
      "suggestion": "- Update agent.md to reflect actual current output: status field values are OK/Processing, processState is in_progress/done\n- Add a table mapping status JSON values to list display labels\n- Ensure documentation is regenerated from code or kept in sync with code changes"
    }
  ],
  "assessment": {
    "completionStatus": "Successful — all 11 verification steps passed. The agent lifecycle (run → list → status → result → list → clear) worked correctly end-to-end with the additional benefit that an LLM API key was configured, so the task actually completed with real page content rather than just failing gracefully.",
    "successRate": "100% — every command executed successfully and produced the expected output format.",
    "issuesFound": 8,
    "majorBlockers": "No major blockers. The core agent lifecycle works reliably.",
    "mostConfusingAspects": "1. The --clear flag is only discoverable from agent list --help, not from agent --help — a first-time user would have no way to know tasks can be cleared.\n2. Documentation examples use fictional 'agent-task-1' IDs while real IDs are UUIDs.\n3. The agent status JSON format differs significantly from what the documentation describes (different status labels, different processState values, different casing).",
    "mostValuableImprovements": "1. Add agent clear as a standalone command (and list it in agent --help) for discoverability and consistency with crawl clear.\n2. Update agent.md documentation to match actual output format (status labels, processState values, task ID format).\n3. Only clear terminal-state tasks by default; warn about in-progress tasks being cleared.\n4. Add TTL/timeout for stale queued tasks to prevent 8-day-old orphans.",
    "usabilityRating": 6
  }
}
```

---

## Issues Found (8 issues)

### Issue 1: --clear flag not discoverable from agent --help

**Severity:** Medium
**Category:** Discoverability

#### Reproduction

./b4w.ps1 agent --help

#### Expected Behavior

The --clear flag (or a clear subcommand) should be listed under agent subcommands or mentioned in the top-level agent help.

#### Actual Behavior

agent --help lists only four subcommands: status, run, list, result. No mention of --clear. Users must know to run agent list --help specifically to discover the clear functionality.

#### Root Cause Analysis

The agent --help output only enumerates subcommands, not their flags. The --clear flag is documented only in agent list --help. A user reading agent --help has no way to know that tasks can be cleared.

#### Code Pointer

`cli/browser4-cli/src/ — likely in the clap/help configuration for the agent subcommand group`

#### AI Suggested Improvement

- Add a note to agent --help: "Use agent list --clear to remove all tracked tasks."
- Or add agent clear as a dedicated subcommand for better discoverability (consistent with crawl clear)

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] `agent --help` should surface the existence of `--clear` even if it lives on a subcommand. Strongly related to Issue 7 — both should be addressed together (add a note to top-level help AND consider an `agent clear` alias).

---

### Issue 2: --clear removes in-progress tasks without warning or cancellation

**Severity:** Medium
**Category:** UX

#### Reproduction

1. agent run "some task"
2. Immediately run agent list --clear
3. The in-progress task is cleared from the list but continues running on the backend (still accessible by ID)

#### Expected Behavior

Either: (a) --clear should only clear terminal-state tasks (completed/failed) with a warning about in-progress tasks, or (b) --clear should cancel running tasks and then clear them, or (c) a confirmation/warning should be shown about in-progress tasks being cleared.

#### Actual Behavior

--clear silently removes all tasks including 2 in-progress ones. The tasks continue running on the backend (verified via agent status on cleared task IDs). No warning is shown. The user loses visibility of running tasks.

#### Root Cause Analysis

The --clear flag likely just clears the local task tracking store without checking task state or interacting with the backend to cancel running tasks. This is a design decision rather than a bug, but it's surprising UX.

#### AI Suggested Improvement

- Only clear terminal-state tasks by default; require --force or --all to clear in-progress tasks
- Display a warning when clearing in-progress tasks: "2 in-progress task(s) will remain running on the backend but will no longer appear in the list."
- Consider adding agent cancel <id> for explicit task cancellation

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Silently dropping visibility of in-progress tasks that continue running backend-side is a real UX hazard. The fix should be coordinated with Issue 1/7: if clear becomes more discoverable, this surprising behavior is exposed to more users. At minimum, warn about in-progress tasks before clearing; ideally, only clear terminal-state tasks by default and require `--force`/`--all` for active ones.

---

### Issue 3: Documentation examples use fictional task IDs (agent-task-1) not real UUIDs

**Severity:** Low
**Category:** Documentation

#### Reproduction

Read agent.md reference or run agent status --help / agent result --help

#### Expected Behavior

Documentation examples should show realistic task ID formats that users will actually encounter.

#### Actual Behavior

All agent documentation examples use 'agent-task-1' as the task ID. Real task IDs are UUIDs like '58dd054f-e62f-439c-99df-d304dd21d9b2'. This mismatch can confuse first-time users who may try to use 'agent-task-1' literally.

#### Root Cause Analysis

Documentation was written before the task ID format was finalized or was never updated when the format changed from sequential names to UUIDs.

#### Code Pointer

`skills/browser4-cli/references/agent.md:33, cli/browser4-cli/src/ (help text strings)`

#### AI Suggested Improvement

- Update examples in agent.md to use UUID-style IDs
- Update help text examples: "browser4-cli agent status <task-id>" should show a realistic example like "browser4-cli agent status 58dd054f-e62f-439c-99df-d304dd21d9b2"
- Or show the format: "agent status <uuid>" with a note that the ID is printed by agent run

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Trivial documentation fix. Update all examples in agent.md and help strings to use realistic UUID-format IDs. Related to Issue 8 — both fix stale docs in the same file; a single editing pass should address both.

---

### Issue 4: Stale task persisted for 8+ days in queued state

**Severity:** Low
**Category:** Reliability

#### Reproduction

Run agent list after the backend has been running for days. A task from July 20 (8 days ago) appeared in queued state.

#### Expected Behavior

Stale tasks should eventually time out or be auto-cleaned. An 8-day-old queued task that never started is likely stuck and should be detected and cleaned up.

#### Actual Behavior

Task a8244106-4df4-4bd2-9192-2bfe38e1c631 was submitted on 2026-07-20 21:23:37 and remained queued 8 days later. No timeout, no auto-cleanup, no status change.

#### Root Cause Analysis

No TTL/timeout mechanism for tasks stuck in queued state. The task was likely orphaned by a backend restart or was submitted against a different session/configuration and never picked up.

#### AI Suggested Improvement

- Implement a configurable TTL for queued tasks (e.g., 24 hours) after which they auto-transition to 'failed (timeout)'
- Display a warning in agent list when tasks older than N hours are still queued
- Consider persisting task state across backend restarts to prevent orphaned tasks

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Legitimate reliability concern. An 8-day-old queued task that never started is almost certainly orphaned. Implement a configurable TTL (e.g., 24h) after which queued tasks auto-transition to `failed (timeout)`, and surface a warning in `agent list` for tasks older than N hours.

---

### Issue 5: agent status JSON output is extremely verbose

**Severity:** Low
**Category:** UX

#### Reproduction

Run agent status <id> on a running task and observe the message field.

#### Expected Behavior

Status output should be concise and focused on actionable information: current state, progress, and any errors.

#### Actual Behavior

The message field contains a very long string with internal state visitor event traces: 'StatefulPageVisitor.onCreated,onWillLoad,onWillFetch,onWillNavigate,...' and the commandResult.summary field duplicates this verbatim. This internal debug trace leaks into the user-facing JSON output.

#### Root Cause Analysis

Internal event tracing from StatefulPageVisitor/StatefulAgentRunner is serialized into the user-facing message field. These are implementation details, not user-facing information.

#### AI Suggested Improvement

- Keep message concise: human-readable status descriptions like 'Navigating to page...', 'Extracting content...', 'Generating summary...'
- Move the internal event trace to a debug-only field or a --verbose flag
- The commandResult.summary should contain the actual task result, not a copy of the event trace

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Internal `StatefulPageVisitor`/`StatefulAgentRunner` event traces should not leak into the user-facing `message` field. Keep `message` concise and human-readable; move the event trace to a debug-only field gated behind `--verbose`. Related to Issue 6 — both are problems in the same JSON response payload.

---

### Issue 6: Status label casing inconsistent between agent list and agent status JSON

**Severity:** Low
**Category:** UX

#### Reproduction

1. agent list (shows lowercase: completed, processing, queued)
2. agent status (JSON shows: "status":"OK" or "status":"Processing")

#### Expected Behavior

Consistent casing across all interfaces. If the list uses 'completed', the JSON should use 'completed' (not 'OK').

#### Actual Behavior

agent list STATUS column uses lowercase labels (completed, processing, queued) while agent status JSON returns 'status':'OK' for completed tasks and 'status':'Processing' for running tasks. The 'OK' label is not a standard lifecycle term.

#### Root Cause Analysis

The list display normalizes status to lowercase for the table view, while the JSON status field uses the raw internal enum name (OK, Processing). These come from different code paths.

#### AI Suggested Improvement

- Use consistent labels: completed (not OK), processing, queued, failed
- Or document the mapping between list labels and JSON status values
- Consider adding a statusLabel field to the JSON that matches the list display

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] `agent list` shows lowercase labels (completed, processing, queued) while `agent status` JSON returns `"status":"OK"` and `"status":"Processing"`. The `OK` label in particular is not a standard lifecycle term. Normalize to a single consistent set (completed, processing, queued, failed) across all interfaces. Related to Issue 5 — fix both in the same serialization pass.

---

### Issue 7: No agent clear standalone command — inconsistent with crawl clear pattern

**Severity:** Low
**Category:** Discoverability

#### Reproduction

Try running agent clear (does not exist). Must use agent list --clear instead.

#### Expected Behavior

A separate agent clear command for better discoverability and consistency with the crawl clear command that already exists.

#### Actual Behavior

Clearing tasks requires the --clear flag on agent list. The crawl command family has a standalone crawl clear command, creating an inconsistent pattern. agent --help does not mention --clear at all.

#### Root Cause Analysis

The agent command family was designed with --clear as a flag on list, while the crawl command family uses a dedicated clear subcommand. Different design patterns for similar functionality.

#### AI Suggested Improvement

- Add agent clear as an alias or primary command for clearing tasks
- Keep agent list --clear as a convenience flag but also support the standalone command
- Align the pattern with crawl clear for consistency

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Adding `agent clear` as a standalone command (or alias) aligns the agent UX with the existing `crawl clear` pattern. This is a companion fix to Issue 1 — the top-level help should list `clear` as a subcommand, and `agent list --clear` can be retained as a convenience alias. Implement together with Issue 1.

---

### Issue 8: agent status help example shows old status format (all-caps RUNNING)

**Severity:** Low
**Category:** Documentation

#### Reproduction

Read skills/browser4-cli/references/agent.md which shows: {"status":"RUNNING"}

#### Expected Behavior

Documentation should show the actual status format returned by the current code.

#### Actual Behavior

The agent.md reference shows {"status":"RUNNING"} and lists statuses as RUNNING, COMPLETED, FAILED, EXPECTATION_FAILED (all-caps). The actual agent status output returns "status":"Processing" and "status":"OK" — different words and different casing. The document also uses "processState":"processing" but actual output uses "processState":"in_progress".

#### Root Cause Analysis

The agent.md documentation was written for an earlier version of the agent system and was not updated when the status labels and processState values changed.

#### Code Pointer

`skills/browser4-cli/references/agent.md:66`

#### AI Suggested Improvement

- Update agent.md to reflect actual current output: status field values are OK/Processing, processState is in_progress/done
- Add a table mapping status JSON values to list display labels
- Ensure documentation is regenerated from code or kept in sync with code changes

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] agent.md documents `RUNNING`/`COMPLETED`/`FAILED` and `processState: "processing"` but actual output uses `OK`/`Processing` and `processState: "in_progress"`. Related to Issues 3, 5, and 6 — once the code-side status labels are normalized (Issue 6), update agent.md to match the actual output. Address in the same docs pass as Issue 3.

---

## Overall Assessment

**Completion Status:** Successful — all 11 verification steps passed. The agent lifecycle (run → list → status → result → list → clear) worked correctly end-to-end with the additional benefit that an LLM API key was configured, so the task actually completed with real page content rather than just failing gracefully.

**Success Rate:** 100% — every command executed successfully and produced the expected output format.

**Issues Found:** 8

**Major Blockers:** No major blockers. The core agent lifecycle works reliably.

**Most Confusing Aspects:** 1. The --clear flag is only discoverable from agent list --help, not from agent --help — a first-time user would have no way to know tasks can be cleared.
2. Documentation examples use fictional 'agent-task-1' IDs while real IDs are UUIDs.
3. The agent status JSON format differs significantly from what the documentation describes (different status labels, different processState values, different casing).

**Most Valuable Improvements:** 1. Add agent clear as a standalone command (and list it in agent --help) for discoverability and consistency with crawl clear.
2. Update agent.md documentation to match actual output format (status labels, processState values, task ID format).
3. Only clear terminal-state tasks by default; warn about in-progress tasks being cleared.
4. Add TTL/timeout for stale queued tasks to prevent 8-day-old orphans.

**Usability Rating:** 6/10

---

## How to Reproduce

### Common Setup

1. Clone the repository and `cd` to the repo root.
2. The CLI is invoked via `./b4w.ps1` which auto-builds from source when needed.
3. The backend server starts automatically in dev mode.
4. All commands from repo root: `./b4w.ps1 <command>`

### Per-Issue Reproduction Steps

#### Issue 1: --clear flag not discoverable from agent --help

./b4w.ps1 agent --help

#### Issue 2: --clear removes in-progress tasks without warning or cancellation

1. agent run "some task"
2. Immediately run agent list --clear
3. The in-progress task is cleared from the list but continues running on the backend (still accessible by ID)

#### Issue 3: Documentation examples use fictional task IDs (agent-task-1) not real UUIDs

Read agent.md reference or run agent status --help / agent result --help

#### Issue 4: Stale task persisted for 8+ days in queued state

Run agent list after the backend has been running for days. A task from July 20 (8 days ago) appeared in queued state.

#### Issue 5: agent status JSON output is extremely verbose

Run agent status <id> on a running task and observe the message field.

#### Issue 6: Status label casing inconsistent between agent list and agent status JSON

1. agent list (shows lowercase: completed, processing, queued)
2. agent status (JSON shows: "status":"OK" or "status":"Processing")

#### Issue 7: No agent clear standalone command — inconsistent with crawl clear pattern

Try running agent clear (does not exist). Must use agent list --clear instead.

#### Issue 8: agent status help example shows old status format (all-caps RUNNING)

Read skills/browser4-cli/references/agent.md which shows: {"status":"RUNNING"}

