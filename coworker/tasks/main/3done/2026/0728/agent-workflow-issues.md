# Issues: agent-workflow

> **Source:** `20260727-225907-agent-workflow.full.md` | **Date:** 20260727-225907 | **Mode:** dev

## Scenario Background

### Task

**Task completed successfully.** All 7 steps of the `agent` command sequence passed. The agent subsystem correctly tracked a task ("给出第100个素数" — "give the 100th prime number") through its full lifecycle: submission → processing → completion. The result correctly returned **541** as the 100th prime. No deprecated "done" status label appeared in the agent list output. All commands exited with code 0.

### Execution Context

**Key Commands:**

**Key workaround:** Had to poll `agent status` 4 times over ~15 seconds waiting for the LLM agent to complete. The task instructions anticipated this correctly.

**Important decision:** Verified that `--help agent` works as a category filter despite not being listed in the main help output's category list.

---

## Issues Found (7 issues)

### Issue 1: agent list returns "Backend unreachable" without auto-starting server

**Severity:** Medium
**Category:** Reliability

#### Reproduction

Run ./b4w.ps1 agent list when the backend is not running.

#### Expected Behavior

The daemon should auto-start the backend (per documentation: "the first ./b4w.ps1 command will start the daemon and backend automatically") or prompt the user to start it.

#### Actual Behavior

Output says "Note: Backend unreachable — showing cached statuses. Start the server for live status." No attempt to auto-start the server. Only write commands like `agent run` trigger auto-start.

#### Root Cause Analysis

Read-only commands (agent list, agent status) use a cached/local path that does not trigger the backend auto-start logic. Write commands (agent run) do trigger it. The auto-start guard is likely gated on whether the command requires a live backend connection.

#### AI Suggested Improvement

- Auto-start the backend on ANY command that needs it, not just write commands
- If auto-start is intentionally avoided for read commands (to keep them fast), at minimum display a hint like "Run any write command (e.g. agent run) to auto-start the backend"
- Document which commands trigger auto-start vs which don't

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Documented behavior ("the first command will start the daemon and backend automatically") conflicts with actual behavior (only write commands trigger auto-start). This is a reliability gap, not just UX polish — users following the docs will hit a dead end on read-only commands. At minimum, the hint should tell the user *how* to start the backend (e.g., "Run `agent run` or `session start` to auto-start the backend").

---

### Issue 2: No agent lifecycle documentation in SKILL.md — only command reference

**Severity:** Medium
**Category:** Documentation

#### Reproduction

Read skills/browser4-cli/SKILL.md. Search for guidance on the agent lifecycle (run → status → result workflow with polling).

#### Expected Behavior

A section explaining the agent task lifecycle: how to submit a task, how to poll for status, how to retrieve results, what the status codes mean, and when to check isDone.

#### Actual Behavior

The SKILL.md mentions agent commands only in the Command Map table (line 146: "agent.md" reference) and lists them under "Agent:" in the help output. There is no lifecycle workflow section, no polling guidance, no status code reference, and no explanation of the JSON fields returned by agent status.

#### Root Cause Analysis

The SKILL.md delegates all agent documentation to references/agent.md without providing even a minimal quick-start lifecycle pattern in the main skill file.

#### AI Suggested Improvement

- Add an "Agent Lifecycle" section to SKILL.md with a minimal copy-paste template: agent run → poll agent status → agent result
- Include a table of common statusCode values (102=processing, 200=OK, 417=expectation failed, etc.)
- Document the isDone polling pattern explicitly
- Link to references/agent.md for full details

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] SKILL.md is the primary entry point for the skill; delegating all agent documentation to a reference file without even a minimal lifecycle summary is a significant gap. A 5-line template (run → poll status with isDone → get result) plus a statusCode table would cover 80% of user needs without bloating the file. Cross-issue: this compounds Issue 1 — users who can't even list agents also have no docs to debug why.

---

### Issue 3: Hint message uses wrong CLI invocation name for local development

**Severity:** Low
**Category:** UX

#### Reproduction

Run ./b4w.ps1 agent run "<task>" and observe the hint message.

#### Expected Behavior

Hint should reference the same invocation method the user is currently using (./b4w.ps1) or use a generic variable like $(b4w).

#### Actual Behavior

Hint says: "Use 'browser4-cli agent status <id>' to check progress, or 'browser4-cli agent list' to view all tracked tasks." — this references the globally-installed CLI name, not the local ./b4w.ps1 the user is running.

#### Root Cause Analysis

The hint text is hardcoded with the global CLI name 'browser4-cli' and does not detect or adapt to the local invocation path being used.

#### AI Suggested Improvement

- Detect the actual argv[0] or script path used and substitute it into hints
- Or use a neutral form like "Use 'agent status <id>' to check progress" since users are already within the CLI context

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Hardcoded "browser4-cli" in hint text is a legitimate UX inconsistency when the user invoked `./b4w.ps1`. The simplest fix is the suggested neutral form ("Use 'agent status <id>'…") — it avoids argv detection complexity and reads naturally since the user is already inside the CLI session. Cross-issue with Issue 1: both are about the CLI lacking self-awareness of its invocation context.

---

### Issue 4: --help agent category not listed in category filter help text

**Severity:** Low
**Category:** Discoverability

#### Reproduction

Run ./b4w.ps1 help and observe the category filter line: "Filter help by category: --help nav | --help extract | --help session | --help kb | --help swarm | --help crawl". Then run ./b4w.ps1 --help agent — it works but is not advertised.

#### Expected Behavior

"--help agent" should appear in the list of available category filters.

#### Actual Behavior

"--help agent" is functional but missing from the advertised category filter list. Similarly, "--help agent" is not mentioned in the SKILL.md reference.

#### Root Cause Analysis

The agent command family was likely added after the category filter list was authored, and the filter list string in the help output was not updated to include it.

#### AI Suggested Improvement

- Add "--help agent" to the category filter list in the help output
- Also add "--help agent" to the SKILL.md command map table
- Audit for other command families that may also be missing from the filter list

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] A functioning category filter that isn't advertised is a discoverability bug — users only find it by guessing. Trivial fix (add one string to the help output) with disproportionate payoff. Also audit for other unlisted categories (e.g., `--help config` if it exists) as suggested.

---

### Issue 5: FINISHED column always shows "-" in agent list, even for completed tasks

**Severity:** Low
**Category:** Product

#### Reproduction

Run ./b4w.ps1 agent list after a task completes (statusCode=200, finishTime present in JSON).

#### Expected Behavior

The FINISHED column should display the completion timestamp from the finishTime field in the agent status JSON.

#### Actual Behavior

The FINISHED column shows "-" for all tasks, even completed ones whose JSON status contains a valid finishTime (e.g. "2026-07-27T22:57:45.635194200Z").

#### Root Cause Analysis

The agent list table renderer either does not read the finishTime field from the task data, or the field name mismatch between the backend response and the list formatter causes it to always fall through to the "-" default.

#### AI Suggested Improvement

- Read finishTime from the task data and format it in the FINISHED column
- Ensure the column is populated from the same field that agent status JSON returns

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] This is a display bug — the `finishTime` field is present in the JSON response but the table formatter either doesn't read it or uses a mismatched field name. Cross-issue with Issues 6 and 7: all three are about the agent data pipeline having presentation gaps between the backend JSON and the CLI table/formatted output.

---

### Issue 6: processState JSON field uses "done" while agent list STATUS uses "completed"

**Severity:** Low
**Category:** UX

#### Reproduction

Run ./b4w.ps1 agent status <id> for a completed task. Compare processState (JSON) with agent list STATUS column.

#### Expected Behavior

Consistent terminology between the JSON API and the CLI table output. The task spec explicitly says the deprecated label is "done".

#### Actual Behavior

JSON: processState="done", status="OK". CLI table: STATUS="completed". The JSON field processState uses "done" which is the deprecated label the task spec warns against. While the CLI STATUS column correctly shows "completed", the internal JSON field name is confusing.

#### Root Cause Analysis

The backend uses "done" as an internal process state enum value, while the CLI presentation layer maps it to "completed". The term "done" appears in user-facing JSON output which contradicts the documented deprecation of that label.

#### AI Suggested Improvement

- Rename the internal processState enum from "done" to "completed" to match the CLI presentation
- Or add a mapping layer so JSON output also shows "completed" instead of "done"

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [x] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT with improvements] The terminology inconsistency is real, but the fix direction matters. The CLI's "completed" mapping is correct per the task spec (which deprecates "done"). Don't rename the internal enum — instead, add a JSON serialization mapping so the user-facing JSON also emits "completed". Cross-issue with Issue 5: both would be caught by a systematic audit of field name mappings between backend responses and CLI rendering.

---

### Issue 7: agent status JSON returns verbose internal event chain in message field

**Severity:** Low
**Category:** UX

#### Reproduction

Run ./b4w.ps1 agent status <id> while a task is in progress.

#### Expected Behavior

The message field should contain a concise, human-readable status description.

#### Actual Behavior

The message field contains a long dot-separated chain of internal Java class/method names: "StatefulAgentRunner.created,PerceptiveAgent.onWillRun,PerceptiveAgent.onWillAct,ContextToAction.onWillGenerate,ContextToAction.onDidGenerate,PerceptiveAgent.onDidAct,PerceptiveAgent.onWillAct,ContextToAction.onWillGenerate". This is an internal event trace, not a user-facing message.

#### Root Cause Analysis

The backend populates the message field by concatenating internal lifecycle event handler names rather than generating a user-facing status description.

#### AI Suggested Improvement

- Keep the internal event chain in a separate field (e.g. eventTrace) and generate a concise human-readable message
- Or provide a summary-only mode: agent status --summary that omits the verbose message field

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Internal Java class/method trace chains have no place in a user-facing `message` field. The suggested fix — keep the event trace in a separate `eventTrace` field and generate a concise human-readable summary for `message` — is the right approach. Cross-issue with Issues 5 and 6: all three point to a need for a presentation-layer pass over agent command output to ensure fields are user-appropriate, consistently named, and correctly populated.

---

## Overall Assessment

**Completion Status:** Successful — all 7 agent command sequence steps passed. The agent task was submitted, tracked, polled, and its result (541) correctly retrieved.

**Success Rate:** 100% — every command in the sequence exited with code 0 and produced the expected output. The LLM API key was configured correctly and the agent computed the correct answer.

**Issues Found:** 7

**Most Confusing Aspects:** 1) The "Backend unreachable" message on agent list (Steps 0-1) made it seem like something was broken, but it was just that read-only commands don't auto-start the server. 2) The hint message referencing 'browser4-cli' instead of the actual invocation './b4w.ps1' could lead a new user to type the wrong command. 3) The verbose internal event chain in agent status JSON (message field) is unintelligible for a first-time user trying to understand what the agent is doing.

**Most Valuable Improvements:** 1) Auto-start the backend on ALL commands that need it, or document clearly which commands trigger auto-start. 2) Add an Agent Lifecycle section to SKILL.md with a copy-paste workflow template and status code reference. 3) Add --help agent to the advertised category filter list. 4) Clean up the message field in agent status JSON to be human-readable.

**Usability Rating:** 7/10

---

## How to Reproduce

### Common Setup

1. Clone the repository and `cd` to the repo root.
2. The CLI is invoked via `./b4w.ps1` which auto-builds from source when needed.
3. The backend server starts automatically in dev mode.
4. All commands from repo root: `./b4w.ps1 <command>`

### Per-Issue Reproduction Steps

#### Issue 1: agent list returns "Backend unreachable" without auto-starting server

Run ./b4w.ps1 agent list when the backend is not running.

#### Issue 2: No agent lifecycle documentation in SKILL.md — only command reference

Read skills/browser4-cli/SKILL.md. Search for guidance on the agent lifecycle (run → status → result workflow with polling).

#### Issue 3: Hint message uses wrong CLI invocation name for local development

Run ./b4w.ps1 agent run "<task>" and observe the hint message.

#### Issue 4: --help agent category not listed in category filter help text

Run ./b4w.ps1 help and observe the category filter line: "Filter help by category: --help nav | --help extract | --help session | --help kb | --help swarm | --help crawl". Then run ./b4w.ps1 --help agent — it works but is not advertised.

#### Issue 5: FINISHED column always shows "-" in agent list, even for completed tasks

Run ./b4w.ps1 agent list after a task completes (statusCode=200, finishTime present in JSON).

#### Issue 6: processState JSON field uses "done" while agent list STATUS uses "completed"

Run ./b4w.ps1 agent status <id> for a completed task. Compare processState (JSON) with agent list STATUS column.

#### Issue 7: agent status JSON returns verbose internal event chain in message field

Run ./b4w.ps1 agent status <id> while a task is in progress.

