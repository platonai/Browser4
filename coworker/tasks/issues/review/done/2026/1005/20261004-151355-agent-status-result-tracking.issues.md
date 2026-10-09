# Issues: agent-status-result-tracking

> **Source:** `20261004-151355-agent-status-result-tracking.full.md` | **Date:** 20261004-151355 | **Mode:** dev

## Scenario Background

### Task

All 11 steps of the `agent run` → `agent status` → `agent result` → `agent list` lifecycle were verified successfully against the local dev backend (CLI 4.13.27, backend 4.13.27-SNAPSHOT, DeepSeek LLM key configured on the backend).

- **Step 1-2:** `agent run "Navigate to https://en.wikipedia.org/wiki/Li_group …"` returned task ID `779c38e7-8a1a-4d41-9e0e-47e18f958834` (exit 0). `agent list` immediately showed it with COMMAND=`agent` and STATUS=`processing` — a standard lifecycle label, not `done`.
- **Step 3-4:** `agent status <id>` returned valid JSON, exit 0, with `"statusCode":200` as an integer, `"processState":"completed"` present, and `"isDone":true` (boolean). The task finished in ~4.5 s, so no polling iterations were needed (note: `processState` is `completed`, matching the current SKILL.md — the older `done` value is only accepted for legacy payloads; `isDone:true` is the documented reliable check).
- **Step 5:** `agent result <id>` returned `{"pageSummary":"Based on the provided page content, there is no description of a Lie group…"}` — non-null, non-empty, exit 0.
- **Steps 6-7:** Two more `agent list` calls showed the task with terminal label `completed` (never `done`); terminal tasks persist — no auto-prune.
- **Steps 8-9:** Two additional tasks (`8fb05d29…` Rust, `56de2460…` monads) submitted; all three tasks appeared in `agent list` with correct labels.
- **Steps 10-11:** `agent list --clear` printed `Cleared 3 tracked agent task(s).` (exit 0); the final `agent list` printed `No tracked async tasks.`

**One major obstacle had to be cleared first:** the very first `agent list` invocation crashed with a Rust panic (exit 101) because the local task store already contained Chinese-language task descriptions. That is Issue 1 below.

### Execution Context

**Preparation**
1. `pwd` confirmed repo root; `./b4w.ps1 help` and `skills/browser4-cli/SKILL.md` (plus `references/agent.md`) read in full.
2. `./b4w.ps1 doctor` — dev bundle matches checkout; backend healthy; `✓ LLM is configured` (`DEEPSEEK_API_KEY`, model `deepseek-v4-flash`). First command also confirmed the daemon/backend were already running from a previous scenario in this run.

**Baseline failure and recovery (key decision point)**
3. `./b4w.ps1 agent list` → **exit 101, panic**: `src\state.rs:1209:40: end byte index 39 is not a char boundary; it is inside '到'` (stdout had only `Status: 66 total, 2 completed, 64 queued`).
4. Root-caused by source inspection: `format_async_task_list` truncates descriptions with `&desc[..desc_w - 1]`, a byte slice; `~/.browser4/async-tasks.json` hel...

(truncated — see full.md for complete trace)

---

## Issues Found (6 issues)

### Issue 1: agent list panics (exit 101) when a tracked task description contains multi-byte UTF-8 crossing the truncation boundary

**Severity:** Critical
**Category:** Reliability

#### Reproduction

1. ./b4w.ps1 agent run "简述: 请用中文介绍李群的数学定义与几何意义"  (any description >40 bytes whose byte 39 falls inside a multi-byte char)
2. ./b4w.ps1 agent list
Observed twice: first against pre-existing tracked tasks accumulated by earlier runs, then deterministically with the crafted task above. Backup of the poisoned store: .test-sessions/20261004T1433038523034Z/async-tasks.backup-231024.json.

#### Expected Behavior

agent list renders the table, truncating overly long descriptions at a character boundary (ellipsis, e.g. '…').

#### Actual Behavior

Exit code 101 and no table: "thread 'main' panicked at src\state.rs:1209:40: end byte index 39 is not a char boundary; it is inside '学' (bytes 38..41 of string)". The command stays broken on every invocation until `agent list --clear`, because the offending record persists in ~/.browser4/async-tasks.json.

#### Root Cause Analysis

format_async_task_list computes desc_w as a byte length capped at 40 and then truncates with `&desc[..desc_w - 1]` — a raw byte slice. Rust panics when the slice index is not a UTF-8 char boundary; CJK text makes this near-certain once the description exceeds 40 bytes. The identical unsafe pattern exists at state.rs:1523 in Table::render (used by `list` for browser sessions), and format_async_task_list is also shared by `crawl list` (main.rs:13178) and `swarm list` (main.rs:12443), so those commands carry the same defect.

#### Code Pointer

`cli/browser4-cli/src/state.rs:1209 (format_async_task_list) and cli/browser4-cli/src/state.rs:1523 (Table::render)`

#### AI Suggested Improvement

- Replace byte slicing with a char-boundary-safe truncator (walk char_indices and cut at the last boundary <= max bytes, or chars().take(n) if a char count is acceptable).
- Extract the helper and use it in both format_async_task_list (state.rs:1209) and Table::render (state.rs:1523).
- Add unit tests with mixed ASCII+CJK descriptions (e.g. "AI: 请用中文介绍李群的数学定义") asserting no panic and a correct ellipsis.
- Consider computing column widths in characters rather than bytes so CJK columns also align.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 2: agent status/result for an unknown task ID print the literal 'null' and exit 0

**Severity:** Medium
**Category:** Reliability

#### Reproduction

./b4w.ps1 agent status 00000000-0000-0000-0000-000000000000
./b4w.ps1 agent result 00000000-0000-0000-0000-000000000000
./b4w.ps1 agent status 00000000-0000-0000-0000-000000000000 --json

#### Expected Behavior

A clear error naming the unknown task id (e.g. 'No agent task found with id …') and a non-zero exit code (and an error envelope in --json mode).

#### Actual Behavior

Plain mode prints the literal string null with exit code 0. JSON mode reports success for a nonexistent task: {"status":"ok","command":"agent-status","output":{"task_id":"…","raw":null}}. Scripts cannot distinguish 'task not found' from a legitimate null payload.

#### Root Cause Analysis

handle_agent_status (main.rs:10978) and handle_agent_result (main.rs:11018) treat a null/absent backend response as a successful result and print it verbatim; there is no not-found classification (the backend returns null rather than a 404-style error for unknown ids). Note also an inconsistency: agent status --json emits raw:null (JSON null) while agent result --json emits raw:"null" (the string).

#### Code Pointer

`cli/browser4-cli/src/main.rs:10978 (handle_agent_status), cli/browser4-cli/src/main.rs:11018 (handle_agent_result)`

#### AI Suggested Improvement

- Detect a null/absent result for a syntactically valid task id and emit a human-readable 'task not found' error with a non-zero exit code.
- In --json mode set status:'error' with an error field instead of status:'ok' with raw:null.
- Normalize the raw:null vs raw:"null" inconsistency between the two commands.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 3: agent status <id> returns agent history from unrelated tasks; agentState.instruction can name a different task

**Severity:** Medium
**Category:** UX

#### Reproduction

./b4w.ps1 agent run "简述: 请用中文介绍李群的数学定义"   # note id A
./b4w.ps1 agent status <A>   # run immediately, inspect agentHistory.states and agentState

#### Expected Behavior

The status payload for task A describes task A's own progress (its instruction and steps), or any session-scoped history is explicitly labeled as such.

#### Actual Behavior

The fresh task's payload contained complete histories of earlier unrelated tasks — e.g. 'What is the birth date of Guido van Rossum?' (a task from a previous session) and 'summarize the key features of Rust' — and agentState.instruction was 'summarize the key features of Rust' while the queried id was the new task. An automation consumer reading agentState/agentHistory gets another task's data.

#### Root Cause Analysis

Inferred: the backend reuses one stateful agent per browser session and serializes its accumulated history into every CommandStatus. CommandStatus.toCommandStatus copies agentHistory verbatim (browser4-rest CommandStatus.kt:212) and agentState is derived as agentHistory.lastOrNull() (CommandStatus.kt:80). Where exactly agentHistory is attached per command (StatefulAgentRunner / UserCommandExecutor) needs backend investigation.

#### Code Pointer

`browser4-rest/src/main/kotlin/ai/platon/pulsar/rest/api/entities/CommandStatus.kt:212 (toCommandStatus) and the agent-runner code populating agentHistory`

#### AI Suggested Improvement

- Scope agentHistory to the queried command/task (filter states by the task's own run/instruction), or expose cross-task history under an explicitly named session-level field.
- If retention is intentional as LLM context, at least ensure agentState points at the queried task's latest state so agentState.instruction is never another task's.
- Add a regression test asserting a fresh task's agent status contains no other task's instruction.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 4: agent list --clear silently removes in-flight (non-terminal) tasks from tracking

**Severity:** Low
**Category:** UX

#### Reproduction

./b4w.ps1 agent run "summarize the key features of Rust"
./b4w.ps1 agent run "explain monads in functional programming"
./b4w.ps1 agent list          # both show processing
./b4w.ps1 agent list --clear  # 'Cleared 3 tracked agent task(s).'
./b4w.ps1 agent list          # 'No tracked async tasks.'

#### Expected Behavior

Either only terminal tasks are cleared, or the output warns that N tasks are still running and will continue server-side.

#### Actual Behavior

All tracked agent tasks are removed regardless of state. The Rust task was still at step 5 minutes later (its history appeared in a subsequent agent status payload) while agent list reported 'No tracked async tasks.' The user loses list-based monitoring of running work; agent status <id> still works only if the id was saved.

#### Root Cause Analysis

handle_agent_list's --clear branch (main.rs:11062-11076) retains only `t.command != "agent"` — unconditional removal with no status inspection and no warning. Compare the terminal-only prune semantics available elsewhere (state.rs:1080 prune_async_tasks).

#### Code Pointer

`cli/browser4-cli/src/main.rs:11062 (handle_agent_list --clear branch); compare cli/browser4-cli/src/state.rs:1080 (prune_async_tasks)`

#### AI Suggested Improvement

- Skip non-terminal tasks by default and report counts by state; add --force/--all to remove running ones.
- If removing running tasks stays the default, print a warning listing the in-flight ids that continue running server-side.
- Add a --status filter so users can clear only completed/failed entries.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 5: agent list table columns misalign because the TASK ID width is capped at 12 while ids are 36-char UUIDs

**Severity:** Low
**Category:** UX

#### Reproduction

./b4w.ps1 agent run "summarize the key features of Rust"
./b4w.ps1 agent list

#### Expected Behavior

Data columns line up under their headers, as in a conventional table.

#### Actual Behavior

Every data row is shifted right relative to the header and separator rows: the 36-char UUID overflows the 12-char TASK ID column, so STARTED/FINISHED/DURATION/STATUS values no longer sit under their headers. (The header itself is also narrower than the separator dashes for some columns.)

#### Root Cause Analysis

format_async_task_list caps the id column width with .min(12) (state.rs, id_w computation around line 1158) but task ids are 36-char UUIDs; the {:<id_w$} format pads to 12 and the longer value shifts all subsequent columns.

#### Code Pointer

`cli/browser4-cli/src/state.rs:1126 (format_async_task_list, id_w = ….max(8).min(12))`

#### AI Suggested Improvement

- Drop the .min(12) cap or raise it to 36 so the column fits UUIDs.
- Alternatively display a short id prefix (first 8 chars) with a note that the full id is available from agent status.
- Add a rendered-table snapshot test using a real UUID.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 6: Duplicate task-count lines with inconsistent vocabulary between the summary and the table ('queued' vs 'pending')

**Severity:** Low
**Category:** UX

#### Reproduction

./b4w.ps1 agent list

#### Expected Behavior

One status summary using the documented lifecycle vocabulary (queued / processing / completed / failed (<code>)).

#### Actual Behavior

Two counts are printed for the same data set: 'Status: N total, …' followed by 'N tracked task(s) (showing a-b):'. Additionally, a task not yet polled is counted as 'queued' by the summary but displayed as 'pending' in the STATUS column — a fifth label outside the documented lifecycle set.

#### Root Cause Analysis

handle_agent_list prints summarize_async_tasks(&filtered) (main.rs:11118) and then format_async_task_list(&display, …) (main.rs:11124), each rendering its own count line; summarize_async_tasks maps an empty last_status to 'queued' (state.rs:1281) while format_async_task_list maps it to 'pending' (state.rs, around line 1201).

#### Code Pointer

`cli/browser4-cli/src/main.rs:11118-11124; cli/browser4-cli/src/state.rs:1268 (summarize_async_tasks) and cli/browser4-cli/src/state.rs:1126 (format_async_task_list)`

#### AI Suggested Improvement

- Print only one summary line (let the second line show pagination only, without re-counting).
- Use one vocabulary everywhere: map an empty status to 'queued' in the table, or document 'pending' as an alias.
- Add a test asserting the STATUS label set stays within {queued, processing, completed, failed (<code>)}.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

## Overall Assessment

**Completion Status:** Successful — all 11 scenario steps verified end-to-end after recovering from a pre-existing crash in the local task store

**Success Rate:** 100% of the 11 scenario steps after the workaround; the very first agent list invocation failed with a panic (exit 101) until the poisoned store was cleared

**Issues Found:** 6

**Major Blockers:** The initial `agent list` crashed with a Rust panic (exit 101) because the local store already contained non-ASCII (Chinese) agent-task descriptions from earlier runs — the same failure occurs deterministically for any user who submits a >40-byte non-ASCII task. Recovery required the documented `agent list --clear` (which works because it never renders the table); after that the full lifecycle passed.

**Most Confusing Aspects:** Two different count lines for the same task list ('Status: 1 total, 1 processing' vs '1 tracked task(s)'); unknown task IDs yielding the literal null with exit 0 and JSON status 'ok'; a new task's status payload embedding other tasks' instructions and steps, with agentState.instruction naming a different task; and --clear removing tasks that are still running while the list then reports 'No tracked async tasks.'

**Most Valuable Improvements:** Fix the byte-boundary truncation crash (the same unsafe slice is latent in Table::render and shared with crawl/swarm list); return a proper not-found error with a non-zero exit for unknown agent task ids; scope or clearly label agent history per task; warn when --clear removes in-flight tasks; fix the UUID column width so the table aligns.

**Usability Rating:** 6/10

---

## How to Reproduce

### Common Setup

1. Clone the repository and `cd` to the repo root.
2. The CLI is invoked via `./b4w.ps1` (PowerShell) or `./b4w.sh` (Bash / Git Bash), which auto-build from source when needed.
3. The backend server starts automatically in dev mode.
4. All commands from repo root:

   - **PowerShell:** `./b4w.ps1 <command>`
   - **Bash / Git Bash:** `./b4w.sh <command>`
   - **Direct:** `browser4-cli <command>` (if installed globally)

   > **Note:** `$(./b4w.ps1)` is command substitution in bash — do NOT use it.

### Per-Issue Reproduction Steps

#### Issue 1: agent list panics (exit 101) when a tracked task description contains multi-byte UTF-8 crossing the truncation boundary

1. ./b4w.ps1 agent run "简述: 请用中文介绍李群的数学定义与几何意义"  (any description >40 bytes whose byte 39 falls inside a multi-byte char)
2. ./b4w.ps1 agent list
Observed twice: first against pre-existing tracked tasks accumulated by earlier runs, then deterministically with the crafted task above. Backup of the poisoned store: .test-sessions/20261004T1433038523034Z/async-tasks.backup-231024.json.

#### Issue 2: agent status/result for an unknown task ID print the literal 'null' and exit 0

./b4w.ps1 agent status 00000000-0000-0000-0000-000000000000
./b4w.ps1 agent result 00000000-0000-0000-0000-000000000000
./b4w.ps1 agent status 00000000-0000-0000-0000-000000000000 --json

#### Issue 3: agent status <id> returns agent history from unrelated tasks; agentState.instruction can name a different task

./b4w.ps1 agent run "简述: 请用中文介绍李群的数学定义"   # note id A
./b4w.ps1 agent status <A>   # run immediately, inspect agentHistory.states and agentState

#### Issue 4: agent list --clear silently removes in-flight (non-terminal) tasks from tracking

./b4w.ps1 agent run "summarize the key features of Rust"
./b4w.ps1 agent run "explain monads in functional programming"
./b4w.ps1 agent list          # both show processing
./b4w.ps1 agent list --clear  # 'Cleared 3 tracked agent task(s).'
./b4w.ps1 agent list          # 'No tracked async tasks.'

#### Issue 5: agent list table columns misalign because the TASK ID width is capped at 12 while ids are 36-char UUIDs

./b4w.ps1 agent run "summarize the key features of Rust"
./b4w.ps1 agent list

#### Issue 6: Duplicate task-count lines with inconsistent vocabulary between the summary and the table ('queued' vs 'pending')

./b4w.ps1 agent list

