# Issues: agent-status-result-tracking

> **Source:** `20261015-112415-agent-status-result-tracking.issues.json` | **Date:** 20261015-112415 | **Mode:** dev

## Scenario Background

### Task

The `agent run` → `agent status` → `agent result` → `agent list` lifecycle was exercised end-to-end on the local dev stack (CLI 4.14.1, backend 4.14.1-SNAPSHOT, LLM configured via `DEEPSEEK_API_KEY` — so agent tasks genuinely executed; the "failed (417) because no LLM key" path was **not** exercised).

| Step | Check | Result |
|---|---|---|
| 1 | `agent run` prints "Task submitted: 1a6da78e-…", exit 0 | ✅ |
| 2 | Task appears in `agent list`; ID/COMMAND match; STATUS = `queued` (not "done") | ✅ |
| 3 | `agent status`: exit 0, valid JSON, **`statusCode` integer / `processState` / `isDone`** | ❌ fields absent entirely |
| 4 | Poll until `"isDone": true` | ❌ `isDone` never exists; completion only visible as `"status":"done"` |
| 5 | `agent result`: exit 0, not `null`, contains a real summary ("a Lie group is a group that is also a differentiable (smooth) manifold…") | ✅ |
| 6 | `agent list` shows terminal label (`completed` / `failed (…)`) | ❌ still `queued` after server said `done` |
| 7 | Task persists across list calls, no auto-prune | ✅ (persistence works; label still wrong) |
| 8 | Two new tasks submitted (`f1894d9a…`, `58ba63df…`) | ✅ |
| 9 | All three tasks appear in `agent list` | ✅ (plus 2 pre-existing leftovers from earlier runs) |
| 10 | `agent list --clear` confirms clearing | ❌ `Cleared 0 tracked agent task(s).` — finished tasks kept as "in-flight" |
| 11 | Final list shows "No tracked async tasks." | ❌ tasks still listed (message reachable only via `--clear --all`) |

**Bottom line:** submission, status (raw), and result retrieval work well; the **tracking/listing surface is disconnected from reality**. Every task that reached `"status":"done"` server-side (3/3) still displayed `queued` in `agent list` forever, so steps 3, 4, 6, 10 and 11 of the scenario fail. The root cause is one partially-migrated contract: the backend now answers `command_status` with the shared task envelope (`{taskId, status, statusTool, resultTool, cancelTool}` — `queued|running|done|failed|cancelled`), while every CLI-side consumer that reconciles lifecycle state (`agent list` refresh, `--clear`, `agent run --wait`) still looks for the old `processState` / `isDone` / `statusCode` / `finishTime` fields, finds none, and silently keeps its stale cache.

### Execution Context

**Key Commands:**

**Key decisions/workarounds:**
- Since `isDone`/`processState` never appear, polling used the envelope's `status` word as the completion signal (step 4 adapted).
- To exercise the clear step meaningfully, I waited for the two new tasks to reach server-side `done` (verified at 19:19:24) before running `--clear` — the strict expectation (all cleared) still failed.
- Demonstrated the only working cleanup path: `agent list --clear --all` → "Cleared 8 tracked agent task(s)." → final list prints "No tracked async tasks."
- Extra probe to bound the blast radius: `agent run "…" --wait --wait-timeout 90` while concurrently polling `agent status` — the task was `done` at **t+5 s**, yet the CLI printed "processing…" for 90 s and exited **1** with "timed out". Same failure for a 45 s probe. This is the documented wait workflow (agent.md §Pattern 2) being unusable.

**Evidence artifacts** (in `.test-sessions/20261015T0901431699046Z/`): `agent-lifecycle-evidence.md` (full raw log + source-level root cause), `agent-result-li-group.txt`, `agent-wait-exitcode.txt`, `agent-wait-timed.txt`.

---

## Issues Found (7 issues)

### Issue 1: agent status no longer returns the documented statusCode / processState / isDone fields

**Severity:** High
**Category:** Documentation

#### Reproduction

./b4w.ps1 agent run "Navigate to https://en.wikipedia.org/wiki/Li_group and describe what a Lie group is"
./b4w.ps1 agent status <task-id>

#### Expected Behavior

Per skills/browser4-cli/references/agent.md (section 3) and SKILL.md, the output is JSON containing an integer "statusCode" (e.g. 200/417/102), a "processState" ("done" when finished) and a boolean "isDone" — the documented reliable completion check. agent.md shows e.g. {"id":"…","status":"RUNNING","statusCode":null,"processState":"processing","message":"Navigating..."}.

#### Actual Behavior

Every poll (even after completion) returns only the shared envelope: {"taskId":"1a6da78e-…","status":"running"|"done","statusTool":"command_status","resultTool":"command_result","cancelTool":"command_cancel"}. No statusCode, processState, isDone, message or finishTime ever appear; the docs' example field "id" is now "taskId"; the vocabulary is queued/running/done/failed/cancelled instead of the documented RUNNING/COMPLETED/FAILED/EXPECTATION_FAILED. Four different vocabularies coexist (docs; this envelope; agent list's queued/processing/completed/failed; swarm's labels).

#### Root Cause Analysis

The backend was migrated to the shared async contract (commit 8f574d6881 'one async contract for long-running tools'): CommandToolExecutor.envelopeOf() serializes TaskEnvelopes.of(...), which emits only taskId/status/processed/error/statusTool/resultTool/cancelTool and drops the legacy CommandStatus fields (statusCode, processState, isDone, message, finishTime). The CLI's agent-status handler prints this payload verbatim (main.rs:14339) and the skill docs/help were never updated to the new contract.

#### Code Pointer

`browser4-rest/src/main/kotlin/ai/platon/pulsar/agent/tool/CommandToolExecutor.kt:envelopeOf(); skills/browser4-cli/references/agent.md (section 3 'Poll Status')`

#### AI Suggested Improvement

- Pick one canonical contract and make everything agree: either enrich the status envelope with processState/statusCode/isDone (mapped from CommandStatus at envelopeOf time), or rewrite agent.md + SKILL.md + `--help agent status` to document taskId/status/statusTool/resultTool/cancelTool and the queued|running|done|failed|cancelled vocabulary
- Update the agent.md example payload and the status table (currently RUNNING/COMPLETED/FAILED/EXPECTATION_FAILED, none of which the backend emits)
- Add an e2e assertion (Rust scenario + Kotlin contract test) that pins the documented status JSON shape so future contract migrations cannot silently invalidate the docs
- Consider keeping statusCode in the envelope: scenarios and users key on 'failed (417)' style codes that are now unobtainable

#### Human Review

- [ ] **ACCEPT**
- [ ] **ACCEPT with improvements**
- [ ] **DEFER**
- [ ] **WONTFIX**
- [ ] **REJECT**
- **Notes:**

---

### Issue 2: agent list never shows a terminal status for agent tasks — finished tasks display 'queued' forever

**Severity:** High
**Category:** Product

#### Reproduction

./b4w.ps1 agent run "<any task>"
# wait until ./b4w.ps1 agent status <id> reports "status":"done"
./b4w.ps1 agent list

#### Expected Behavior

The STATUS column shows a standard terminal label — `completed` or `failed (<code>)` — once the backend reports the task finished (scenario steps 6/7).

#### Actual Behavior

All three tasks that completed server-side (verified "status":"done" via agent status) still render STATUS=queued in every subsequent agent list call; FINISHED, DURATION and ELAPSED stay '-' for every row. A leftover task from 2026-10-13 still showed 'processing' two days later. The local store ~/.browser4/workspaces/Browser4-4.14-04e5248d/async-tasks.json contains entries with NO lastStatus field at all for tasks submitted today, proving the refresh never lands.

#### Root Cause Analysis

refreshed_agent_status() (main.rs:15488) only accepts a status payload if it contains processState, isDone, statusCode or finishTime; the backend's new shared envelope contains none of these (it carries `status`). The function therefore returns None for every poll, the refresh loop in handle_agent_list (main.rs:14527-14559) leaves the cached lastStatus empty, and format_async_task_list_opts (state.rs, ~line 1547) renders an empty last_status as the literal 'queued'. completed_at is likewise never set, which is why FINISHED/DURATION/ELAPSED remain '-'. The same dead branch pattern exists in extract_readable_agent_status (main.rs:14664, now test-only).

#### Code Pointer

`cli/browser4-cli/src/main.rs:refreshed_agent_status() and handle_agent_list() refresh loop; display fallback in cli/browser4-cli/src/state.rs:format_async_task_list_opts()`

#### AI Suggested Improvement

- Teach refreshed_agent_status() the shared envelope: map status queued->queued, running->processing, done->completed, failed->failed (use the envelope's error field for the reason), cancelled->cancelled; keep the legacy processState/isDone path as a fallback
- Add a unit test using the real envelope JSON ({taskId, status:done, statusTool:command_status,…}) asserting it maps to 'completed' — the existing tests only feed the legacy shape, which is why this regression slipped through
- Also read the envelope's `error` so failed tasks can display like 'failed (error)' rather than staying non-terminal
- Cross-check: agent list --clear and the --clear --all warning both consume last_status, so fixing this one function repairs issues 3 and 7 as well

#### Human Review

- [ ] **ACCEPT**
- [ ] **ACCEPT with improvements**
- [ ] **DEFER**
- [ ] **WONTFIX**
- [ ] **REJECT**
- **Notes:**

---

### Issue 3: agent list --clear cannot clear finished agent tasks ('Cleared 0')

**Severity:** High
**Category:** Product

#### Reproduction

./b4w.ps1 agent list --clear
./b4w.ps1 agent list

#### Expected Behavior

Tasks that finished (the scenario's step 10) are removed and the output confirms a non-zero count, e.g. 'Cleared N tracked agent task(s).'; the follow-up list shows 'No tracked async tasks.' (step 11).

#### Actual Behavior

'Cleared 0 tracked agent task(s).' followed by 'Kept 5 in-flight task(s) still running server-side; re-run with --all to remove them from tracking too.' — even though all three scenario tasks had already completed server-side and were verified 'done'. The tasks remain in the next agent list, so 'No tracked async tasks.' is never reached. Only 'agent list --clear --all' works: it cleared all 8 tasks and then the list printed 'No tracked async tasks.'

#### Root Cause Analysis

agent_clear_drops_task() (main.rs:14442) drops a tracked agent task only when its cached status is terminal ('completed' or starts with 'failed'). Because of issue 2, no cached status can ever become terminal — every fresh task keeps an empty last_status — so the default clear removes nothing. The clear branch also runs before the server refresh in handle_agent_list, so even a correct cache would first have to be refreshed by a previous agent list call.

#### Code Pointer

`cli/browser4-cli/src/main.rs:agent_clear_drops_task() / handle_agent_list() clear branch`

#### AI Suggested Improvement

- Fixing refreshed_agent_status (issue 2) makes the default --clear work; until then, consider refreshing terminal state inside the clear branch before deciding what to drop
- Treat a never-polled entry (empty last_status) as unknown rather than 'in-flight' when the server answers terminal for it
- Add a regression test: finished task + agent list --clear => task removed, message 'Cleared 1 tracked agent task(s).'

#### Human Review

- [ ] **ACCEPT**
- [ ] **ACCEPT with improvements**
- [ ] **DEFER**
- [ ] **WONTFIX**
- [ ] **REJECT**
- **Notes:**

---

### Issue 4: agent run --wait always times out even when the task completes in seconds (exit 1)

**Severity:** High
**Category:** Product

#### Reproduction

./b4w.ps1 agent run "reply with the single word ok" --wait --wait-timeout 90
# concurrently: ./b4w.ps1 agent status <task-id>  (every 4s)

#### Expected Behavior

Per agent.md Pattern 2, --wait blocks until the task reaches a terminal state, prints the final result and exits 0 — here at roughly t+5s when the task finished.

#### Actual Behavior

The server reported "status":"done" already at t+5s and for the entire remainder, but the CLI kept printing '[Ns] processing...' and at t+90s exited with 'Error: Agent task <id> timed out after 90s' and exit code 1. Reproduced twice (45s and 90s windows); both tasks were verified 'done' server-side after the timeouts. The documented wait workflow is effectively a fixed-length sleep whose result is always discarded.

#### Root Cause Analysis

The wait loop in handle_agent_run (main.rs:14078-14148) reads only parsed.get("processState") and treats the task as terminal exclusively on "done"/"completed". The current command_status envelope never carries processState (it carries status: queued|running|done|failed|cancelled), so the success branch is unreachable and the loop can only exit through the timeout branch (which exits non-zero and marks the local status 'processing').

#### Code Pointer

`cli/browser4-cli/src/main.rs:handle_agent_run() --wait loop (≈14094-14148)`

#### AI Suggested Improvement

- Treat envelope status in {done, failed, cancelled} as terminal in the wait loop; on failed, surface the envelope's error/statusCode instead of the generic timeout message
- Keep the legacy processState branch as a fallback for older backends and add a unit/e2e test that a task which reaches status=done mid-wait returns its result with exit 0
- Since --wait now always burns its full window, consider printing a warning when repeated polls show status=done but the loop cannot consume it

#### Human Review

- [ ] **ACCEPT**
- [ ] **ACCEPT with improvements**
- [ ] **DEFER**
- [ ] **WONTFIX**
- [ ] **REJECT**
- **Notes:**

---

### Issue 5: agent result empty-payload hint points at instructResults/--json that never appear; --json missing from help

**Severity:** Low
**Category:** Discoverability

#### Reproduction

./b4w.ps1 --help "agent status"   (shows no options at all)
./b4w.ps1 agent status <id> --json
# and the hint printed by agent result for an empty result: 'agent status <id> --json (check instructResults for extracted data)'

#### Expected Behavior

Help documents the flags the command actually accepts, and the CLI's own recovery hints reference fields that exist.

#### Actual Behavior

--json is accepted and changes the output envelope ({"status":"ok","command":"agent-status","output":{...}}) but is undocumented in agent status --help. The instructResults field the hint points at never appears in any status payload (envelopeOf only converts it to a numeric 'processed' count), so a user following the hint finds nothing.

#### Root Cause Analysis

The hint in handle_agent_result (main.rs ~14413) predates the TaskEnvelopes migration; CommandStatus.instructResults is internal to the backend and is no longer serialized. The agent status help entry declares no options while the command accepts --json via the generic option path.

#### Code Pointer

`cli/browser4-cli/src/main.rs:handle_agent_result() empty-result hint; help specs for 'agent status'`

#### AI Suggested Improvement

- Update the hint to reference real fields (result, processed, error) or re-expose instructResults in the envelope if it is genuinely useful
- Document --json (and its envelope shape) in `--help agent status` / `--help agent result`
- Add a help-coverage test that every accepted flag appears in the command's help output

#### Human Review

- [ ] **ACCEPT**
- [ ] **ACCEPT with improvements**
- [ ] **DEFER**
- [ ] **WONTFIX**
- [ ] **REJECT**
- **Notes:**

---

### Issue 6: Unknown task id reports status 'failed' with no reason; the CLI's friendly not-found message is dead code

**Severity:** Low
**Category:** UX

#### Reproduction

./b4w.ps1 agent status 00000000-0000-0000-0000-000000000000
./b4w.ps1 agent result 00000000-0000-0000-0000-000000000000

#### Expected Behavior

An actionable error such as 'No agent task found with id '…'. Run `agent list` to see tracked tasks.' (the message the CLI still contains), or at minimum a failed envelope with an error/reason field.

#### Actual Behavior

Both commands print {"taskId":"00000000-…","status":"failed","statusTool":"command_status",…} with exit 0 and no error or message explaining why. The typed-ID-mistake case (wrong ID, cleared task) is indistinguishable from a genuine task failure without a reason.

#### Root Cause Analysis

handle_agent_status/handle_agent_result (main.rs:14331, 14387) only detect an unknown id when the payload is null/absent. Since commit bc3268c176 the backend returns CommandStatus.notFound(id) serialized as a structured envelope (status=failed) whose message is blank, so the 'error' field is dropped (TaskEnvelopes.of omits blank errors) and the CLI's dedicated not-found branch can never fire.

#### Code Pointer

`cli/browser4-cli/src/main.rs:handle_agent_status()/handle_agent_result() null-payload guards; browser4-rest CommandStatus.notFound`

#### AI Suggested Improvement

- Populate a message for the notFound status (e.g. 'No task found with id …') so the envelope's error field carries it
- Or detect the backend's notFound marker in the CLI and print the existing friendly message
- Keep exit code 0 if the JSON status is the contract, but make the reason visible

#### Human Review

- [ ] **ACCEPT**
- [ ] **ACCEPT with improvements**
- [ ] **DEFER**
- [ ] **WONTFIX**
- [ ] **REJECT**
- **Notes:**

---

### Issue 7: agent list --clear --all warning claims completed tasks 'keep running server-side'

**Severity:** Low
**Category:** UX

#### Reproduction

./b4w.ps1 agent list --clear --all   (after tasks finished; verified via agent status = done)

#### Expected Behavior

The detached-task warning (added to protect in-flight work) should only name tasks that may actually still be running, or clearly qualify that their local state is unknown.

#### Actual Behavior

'Warning: 8 in-flight task(s) were removed from tracking but keep running server-side' — 7 of the 8 listed ids had already completed (server said 'done'); only the local cache was stale. A user may wait or re-poll tasks that finished long ago, or distrust the CLI's lifecycle reporting entirely.

#### Root Cause Analysis

The warning is derived from the same cached last_status that issue 2 prevents from ever becoming terminal, so every task is classified as in-flight regardless of its real state.

#### Code Pointer

`cli/browser4-cli/src/main.rs:handle_agent_list() --clear --all detached-task warning (≈14500-14511)`

#### AI Suggested Improvement

- Recompute statuses (or qualify the message as 'unverified locally — check with agent status <id>') before asserting tasks are still running
- Alternatively print the last known cached status next to each id so the user can judge

#### Human Review

- [ ] **ACCEPT**
- [ ] **ACCEPT with improvements**
- [ ] **DEFER**
- [ ] **WONTFIX**
- [ ] **REJECT**
- **Notes:**

---

## Overall Assessment

**Completion Status:** Partially Successful — the lifecycle runs end-to-end and result retrieval is solid (submit, poll via raw status, result, persistence across list calls all work; the Lie-group task genuinely completed and returned a correct summary). However, half the acceptance criteria fail: agent status lacks the documented statusCode/processState/isDone fields (steps 3-4), agent list never shows the terminal label (steps 6-7), agent list --clear clears nothing and the final list never shows 'No tracked async tasks.' (steps 10-11). All failures trace to a single unmigrated contract: CLI-side lifecycle consumers still read the pre-TaskEnvelopes status shape.

**Success Rate:** 60% — steps 1, 2, 5, 8, 9 fully passed; 7 partially (persistence held, label wrong); 3, 4, 6, 10, 11 failed on the specified verifications.

**Issues Found:** 7

**Major Blockers:** Status reconciliation between the backend's shared task envelope and the CLI's lifecycle consumers. Because refreshed_agent_status() ignores the envelope's status word, no agent task ever reaches a terminal cached state, which simultaneously breaks: (a) agent list STATUS/FINISHED/DURATION/ELAPSED columns, (b) agent list --clear, (c) agent run --wait (verified: task done at t+5s, CLI waited 90s and exited 1), and (d) the honesty of the --clear --all warning. The scenario's documented polling field isDone and failure label 'failed (417)' are unobtainable in this build.

**Most Confusing Aspects:** For a first-time user: (1) agent status returns neither the fields the documentation (and --help hints) promise nor the labels agent list uses — three vocabularies coexist (docs: RUNNING/COMPLETED; status envelope: queued/running/done/failed/cancelled; list: queued/processing/completed/failed); (2) agent list confidently shows 'queued' for tasks that finished minutes or days ago, with no hint the value is a placeholder; (3) --clear answers 'Cleared 0 … Kept N in-flight' about work that is verifiably finished; (4) --wait silently burns its whole timeout and exits 1 on a task that succeeded in 5 seconds.

**Most Valuable Improvements:** 1) Make one canonical task-status contract across backend, CLI and docs — ideally by having the status envelope carry (or the CLI map) a single terminal indicator consumed by agent status, agent list, --clear and --wait in the same way. 2) Fix refreshed_agent_status() to consume the shared envelope (queued/running/done/failed/cancelled) — one function repairs three of the seven issues. 3) Add e2e/unit tests pinned to the real envelope fixture so a future contract change cannot silently blank out lifecycle state again. 4) Update agent.md/SKILL.md and the CLI hints to the fields that actually exist.

**Usability Rating:** 5/10

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

#### Issue 1: agent status no longer returns the documented statusCode / processState / isDone fields

./b4w.ps1 agent run "Navigate to https://en.wikipedia.org/wiki/Li_group and describe what a Lie group is"
./b4w.ps1 agent status <task-id>

#### Issue 2: agent list never shows a terminal status for agent tasks — finished tasks display 'queued' forever

./b4w.ps1 agent run "<any task>"
# wait until ./b4w.ps1 agent status <id> reports "status":"done"
./b4w.ps1 agent list

#### Issue 3: agent list --clear cannot clear finished agent tasks ('Cleared 0')

./b4w.ps1 agent list --clear
./b4w.ps1 agent list

#### Issue 4: agent run --wait always times out even when the task completes in seconds (exit 1)

./b4w.ps1 agent run "reply with the single word ok" --wait --wait-timeout 90
# concurrently: ./b4w.ps1 agent status <task-id>  (every 4s)

#### Issue 5: agent result empty-payload hint points at instructResults/--json that never appear; --json missing from help

./b4w.ps1 --help "agent status"   (shows no options at all)
./b4w.ps1 agent status <id> --json
# and the hint printed by agent result for an empty result: 'agent status <id> --json (check instructResults for extracted data)'

#### Issue 6: Unknown task id reports status 'failed' with no reason; the CLI's friendly not-found message is dead code

./b4w.ps1 agent status 00000000-0000-0000-0000-000000000000
./b4w.ps1 agent result 00000000-0000-0000-0000-000000000000

#### Issue 7: agent list --clear --all warning claims completed tasks 'keep running server-side'

./b4w.ps1 agent list --clear --all   (after tasks finished; verified via agent status = done)

