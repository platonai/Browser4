# Issues: agent-extraction

> **Source:** `20261015-111443-agent-extraction.issues.json` | **Date:** 20261015-111443 | **Mode:** dev

## Scenario Background

### Task

All nine task steps executed against the live Wikipedia page. Key outputs:

**Step 2 — `extract` with inline JSON schema** (13 s, exit 0):


**Step 3 — `extract` with a custom schema file** (standard JSON-Schema format with explicit types/`required`), result saved to `.test-sessions/20261015T0901431699046Z/extract-result-schema.json` (12 s):

Both documented schema formats (`{"fields":[…]}` and full JSON Schema) worked; the integer type and snake_case field names from the schema file were honored exactly.

**Step 4 — `summarize` (whole page, 13 s):** coherent multi-section summary covering history (Guido van Rossum, CWI, 1991 first release, BDFL → Steering Council 2019), philosophy (Zen of Python), syntax/typing, ecosystem (905k+ PyPI projects, CPython/PyPy/MicroPython), naming (Monty Python), and influence.

**Step 5 — `summarize --selector "#History"` (13 s):** correctly scoped — the summary covered only the History section (origins as ABC successor, Dec 1989 implementation, Python 0.9.0 in 1991, BDFL era to July 2018, Steering Council Jan 2019, naming, 2.0/3.0 dates). The `#History` id targets only the `<h2>` in the DOM, but the backend gathered the full section — this worked intuitively on the first try.

**Steps 6–8 — autonomous agent task:** task ID **`fe162b8d-a16d-4a7f-b0e7-6d6999cff05a`**, submitted 19:05:07, reached terminal state `done` by 19:06:13 (~60–70 s for a 2-page navigation + extraction). `agent result` returned only a one-sentence **summary** — the actual biographical data was *not* in the payload. The agent had written its deliverable to `…/.browser4/workspaces/Browser4-4.14-04e5248d/app-data/agent/2026/10/15/<uuid>/workspace/guido_van_rossum.md` (found by filesystem search; archived to the scratch dir). Its contents: born 31 Jan 1956 in The Hague (Dutch), programmer/author, M.Sc. mathematics & CS (U. Amsterdam, 1982), IMO bronze 1974, creator of Python and BDFL until 12 Jul 2018, Python Steering Council through 2019; awards (Dr. Dobb's 1999, FSF 2001, NLUUG 2003, ACM Distinguished Engineer 2006, USENIX STUG 2007, CHM Fellow 2018, Dijkstra Fellow 2019, C&C Prize 2023); employers CWI, Google, Dropbox, Microsoft; residence Belmont, California.

**Step 9 — comparison.** Synchronous `extract`/`summarize` (~13 s each, deterministic) operate on the page the session already shows and return verifiable structured JSON — ideal for single-page, known-target pulls (fields, a section, a page summary), especially with `--schema` for machine consumption. `agent run` is asynchronous and autonomous: it handled "navigate somewhere else, plan, extract, return" without any step-by-step instruction and ran server-side while the CLI was free — best for multi-step/multi-page/exploratory work or fire-and-forget submission. Its costs: ~5× the wall time here, nondeterministic, and **its deliverable is a prose summary — the extracted data must be retrieved from the backend's agent-workspace directory, not from `agent result`**, which makes it unreliable for tasks whose whole point is returning data. Practical rule: use `extract`/`summarize` when the page and fields are known; use `agent run` when navigation/exploration is needed or the task should run in the background, and expect to fetch artifacts from disk.

### Execution Context

Commands (all from repo root, `./b4w.sh` — the project's documented Git-Bash wrapper that quotes args safely for `pwsh`; see note below):
```
./b4w.sh help ; read skills/browser4-cli/SKILL.md + references/agent.md
./b4w.sh open --headless "https://en.wikipedia.org/wiki/Python_(programming_language)"   # daemon + backend auto-start
./b4w.sh page-info
./b4w.sh doctor                                                    # LLM: ✓ DEEPSEEK_API_KEY, model deepseek-v4-flash
./b4w.sh extract "…" --schema '{"fields":[…]}]' --stdout           # step 2
./b4w.sh extract "…" --schema "@.test-sessions/…/python-schema.json" --filename ".test-sessions/…/extract-result-schema.json"   # step 3
./b4w.sh summarize "…" --stdout                                    # step 4
./b4w.sh summarize "…" --selector "#Histo...

(truncated — see 20261015-111443-agent-extraction.issues.json for the complete execution trace)

---

## Issues Found (8 issues)

### Issue 1: agent result returns only a prose summary — the extracted data is not retrievable through the CLI

**Severity:** High
**Category:** Product

#### Reproduction

./b4w.sh agent run "Navigate to https://en.wikipedia.org/wiki/Guido_van_Rossum and extract key biographical details: birth date, nationality, occupation, education, known for, and awards. Return them as a structured list."
./b4w.sh agent status <task-id>   # -> status done
./b4w.sh agent result <task-id>   # -> only a summary
./b4w.sh tool call command_result --json '{"id":"<task-id>"}'   # identical payload

#### Expected Behavior

The task asked the agent to return the extracted biographical details; agent result should contain those details (or a structured pointer to retrievable artifacts).

#### Actual Behavior

Payload was {"taskId":"…","status":"done",…,"result":{"summary":"Navigated to the Wikipedia article … Details returned as a structured list and saved to guido_van_rossum.md."}}. The actual data existed only in C:\Users\pereg\.browser4\workspaces\Browser4-4.14-04e5248d\app-data\agent\2026\10\15\<uuid>\workspace\guido_van_rossum.md, discovered by filesystem search; no CLI command, help text, or doc names that location.

#### Root Cause Analysis

The task-result envelope carries only the agent's completion summary. The backend tracks richer completion data (ActionDescription exposes summary, keyFindings, filesChanged; RobustBrowserAgent.completeCliRun logs all three) and the agent writes deliverables into a per-task workspace under the backend app-data agent/<year>/<month>/<day>/<uuid>/workspace/, but none of that is surfaced to the caller. The generic MCP passthrough (tool call command_result) returns the same thin payload, so it is a backend/API contract gap, not just a CLI rendering omission.

#### Code Pointer

`browser4-agentic/src/main/kotlin/ai/platon/pulsar/agentic/agents/RobustBrowserAgent.kt (completeCliRun / task-completion result assembly, ~line 1577) and cli/browser4-cli/src/main.rs (agent result handler)`

#### AI Suggested Improvement

- Include keyFindings and filesChanged in the command_result payload, and return the agent's final textual answer when one exists
- Add a CLI command to list/read agent artifacts, e.g. `agent files <task-id>` / `agent artifact <task-id> <name>`, resolving the per-task workspace server-side
- At minimum, make `agent result` print the workspace path (or a download link) so the deliverable is reachable without filesystem archaeology
- Document the agent workspace layout in references/agent.md

#### Human Review

- [ ] **ACCEPT**
- [ ] **ACCEPT with improvements**
- [ ] **DEFER**
- [ ] **WONTFIX**
- [ ] **REJECT**
- **Notes:**

---

### Issue 2: Documented agent status payload and completion check do not match the CLI output

**Severity:** Medium
**Category:** Documentation

#### Reproduction

./b4w.sh agent status fe162b8d-a16d-4a7f-b0e7-6d6999cff05a
Compare with skills/browser4-cli/references/agent.md (status example + status table) and SKILL.md section 6 ('isDone: true is the reliable completion check').

#### Expected Behavior

Per docs: {"id":…,"status":"RUNNING"|"COMPLETED"|"FAILED"|"EXPECTATION_FAILED","statusCode":null,"processState":"processing","message":"Navigating..."} and completion detectable via isDone/processState.

#### Actual Behavior

Actual output: {"taskId":"…","status":"running"|"done","statusTool":"command_status","resultTool":"command_result","cancelTool":"command_cancel"} — field name taskId (not id), lowercase status values ('done' is not even in the documented vocabulary), and no processState, isDone, statusCode, or message field at all.

#### Root Cause Analysis

The CLI normalizes the backend task payload into an envelope naming the MCP tools to use, with its own lowercase status vocabulary, while the reference docs kept an older/raw payload shape. An agent or script following the docs (polling for isDone or an uppercase COMPLETED) would poll forever.

#### Code Pointer

`skills/browser4-cli/references/agent.md (status example and status table, lines ~102-113) and skills/browser4-cli/SKILL.md section 6 (agent lifecycle paragraph)`

#### AI Suggested Improvement

- Update the docs' payload example and status table to the actual lowercase status/taskId/statusTool shape, or make the CLI emit the backend's raw payload
- State the authoritative terminal-state check for this build (status == "done" seen in practice; verify failed/cancelled spellings) and drop or qualify the isDone guidance
- Add a `--json`/`--verbose` mode if the richer backend payload (processState/message) is available, so users can see progress detail

#### Human Review

- [ ] **ACCEPT**
- [ ] **ACCEPT with improvements**
- [ ] **DEFER**
- [ ] **WONTFIX**
- [ ] **REJECT**
- **Notes:**

---

### Issue 3: agent list shows a completed task as 'queued' indefinitely (local status never synced)

**Severity:** Medium
**Category:** Reliability

#### Reproduction

./b4w.sh agent run "…"            # note task id
./b4w.sh agent status <id>     # -> done
./b4w.sh agent list            # -> same task still listed as 'queued' (also after agent result)
cat ~/.browser4/workspaces/Browser4-4.14-04e5248d/async-tasks.json   # entry for the task has no lastStatus field

#### Expected Behavior

agent list should reflect the task's real state (queued/running/done), consistent with agent status.

#### Actual Behavior

agent list printed 'Status: 2 total, 1 processing, 1 queued' with our completed task as 'queued' (including after agent result); a task submitted 2026-10-13 is still shown as 'processing' two days later.

#### Root Cause Analysis

agent list renders from the local async-tasks.json; state.rs maps an empty lastStatus to the label 'queued' (state.rs ~1553). The only non-test writer of last_status is update_async_task_status, called solely from the agent run --wait timeout path (main.rs:14082). The normal submit/status/result path never writes progress or a terminal state, and agent list never refreshes from the backend, so the row stays 'queued' forever.

#### Code Pointer

`cli/browser4-cli/src/state.rs (update_async_task_status, async-task list rendering ~line 1553) and cli/browser4-cli/src/main.rs line ~14082`

#### AI Suggested Improvement

- Refresh last_status from the backend when rendering agent list (or on agent status/result), marking rows it cannot refresh
- Have agent status/result persist the observed status (including terminal 'done') into the local record
- Surface staleness explicitly (e.g. 'queued (unpolled)' vs a verified timestamp) instead of a label that contradicts agent status

#### Human Review

- [ ] **ACCEPT**
- [ ] **ACCEPT with improvements**
- [ ] **DEFER**
- [ ] **WONTFIX**
- [ ] **REJECT**
- **Notes:**

---

### Issue 4: Dev-mode backend URL resolves to port 1 and is then sticky forever

**Severity:** Medium
**Category:** Reliability

#### Reproduction

./b4w.sh open --headless "https://en.wikipedia.org/wiki/Python_(programming_language)"   # banner: 'Starting Browser4 dev server … on port 1'
./b4w.sh status      # 'Server URL: http://127.0.0.1:1'
curl -s http://127.0.0.1:1/status   # -> redirect:/status.html (Browser4 really listens on TCP 1)
# Contrast with a fresh state dir:
BROWSER4_CLI_STATE_DIR=<scratch>/state-fresh ./b4w.sh status   # -> 'Server URL: http://127.0.0.1:8282' (documented behaviour)

#### Expected Behavior

Development checkouts use 'the first free port from 8282 upward' (references/development-mode.md), and status should show the URL the CLI actually talks to.

#### Actual Behavior

The CLI started the backend on and recorded port 1 (startup banner, status output, cli-state.json baseUrl 'http://127.0.0.1:1', cli-managed-processes.json port 1). netstat confirms a real LISTENING socket on 0.0.0.0:1 owned by the backend JVM (Windows does not privilege-restrict low ports). Commands still work because everything consistently uses the same URL, but the displayed/recorded server URL is wrong per documentation. With a fresh state dir the allocator correctly returns 8282, so the bad value is inherited from previously persisted state.

#### Root Cause Analysis

decide_dev_base_url honours any recorded loopback URL whose port is not in the dev range 8282-8313 verbatim ('Anything else … honoured untouched'), so a value written once by an earlier session (state dir predates today: 2026-10-13) is never re-validated or re-allocated — there is no way back to 8282 without deleting state. The original writer of '1' is not identifiable from current state; note that ~/.browser4/cli-state.json (the global, non-workspace state file) was also rewritten with 'http://localhost:1' during this session, suggesting state-scope leakage worth investigating (workspace file holds 127.0.0.1:1, global file holds localhost:1).

#### Code Pointer

`cli/browser4-cli/src/daemon.rs (decide_dev_base_url / dev_base_url_for_workspace / resolve_base_url) and cli/browser4-cli/src/state.rs (read_state/write_state scoping)`

#### AI Suggested Improvement

- Treat a recorded loopback URL outside the dev range, or one no live server ever answered on, as stale and re-allocate instead of honouring it forever
- Show where the resolved URL came from in status (state file vs allocation vs --server vs config) so a stale value is diagnosable without source reading
- Add a recovery command (e.g. config reset server / doctor --fix) that clears a bad recorded backend URL
- Verify why the global ~/.browser4/cli-state.json is written during workspace-scoped runs

#### Human Review

- [ ] **ACCEPT**
- [ ] **ACCEPT with improvements**
- [ ] **DEFER**
- [ ] **WONTFIX**
- [ ] **REJECT**
- **Notes:**

---

### Issue 5: extract --filename help text contradicts the documented default output behaviour

**Severity:** Low
**Category:** Documentation

#### Reproduction

./b4w.sh --help extract

#### Expected Behavior

Option descriptions consistent with each other and with behaviour.

#### Actual Behavior

--filename is described as 'Save extracted content to a file instead of printing to stdout', while the Notes in the same help say output 'is saved to a timestamped file by default' and stdout is opt-in via --stdout. Observed behaviour matches the Notes (file + printed link only).

#### Root Cause Analysis

The per-option description was written for an earlier stdout-by-default design and not updated when file-by-default was introduced.

#### Code Pointer

`cli/browser4-cli/src/commands.rs or cli/browser4-cli/src/main.rs (extract/summarize clap option definitions)`

#### AI Suggested Improvement

- Reword to 'Write the extracted content to this path instead of the timestamped default file'
- Mention the default location (.browser4-cli/snapshot/) directly in the option help

#### Human Review

- [ ] **ACCEPT**
- [ ] **ACCEPT with improvements**
- [ ] **DEFER**
- [ ] **WONTFIX**
- [ ] **REJECT**
- **Notes:**

---

### Issue 6: Mixed path separators in the printed output-file links

**Severity:** Low
**Category:** UX

#### Reproduction

./b4w.sh extract "…" --filename ".test-sessions/20261015T0901431699046Z/extract-result-schema.json"

#### Expected Behavior

A single normalized path that can be copied and pasted.

#### Actual Behavior

[Extracted content](D:\workspace\Browser4\Browser4-4.14\.test-sessions/20261015T0901431699046Z/extract-result-schema.json) — backslashes from the cwd joined with forward slashes from the argument.

#### Root Cause Analysis

The printed path is assembled by joining the raw working directory with the user-supplied --filename string without normalizing separators.

#### Code Pointer

`cli/browser4-cli/src/main.rs (extract/summarize result-file link printing)`

#### AI Suggested Improvement

- Normalize the joined path (e.g. via PathBuf display) before printing
- Emit the path as a file:// URL or plain text when --stdout is used in scripts

#### Human Review

- [ ] **ACCEPT**
- [ ] **ACCEPT with improvements**
- [ ] **DEFER**
- [ ] **WONTFIX**
- [ ] **REJECT**
- **Notes:**

---

### Issue 7: Agent browsing happens in a context invisible to the invoking session and is undocumented

**Severity:** Low
**Category:** Documentation

#### Reproduction

./b4w.sh agent run "Navigate to https://en.wikipedia.org/wiki/Guido_van_Rossum …"
# wait for done
./b4w.sh tab-list ; ./b4w.sh page-info   # still only the pre-existing tab/page; no trace of the agent's navigation

#### Expected Behavior

Docs should explain where the agent browses and how (if at all) its browser activity can be observed or debugged.

#### Actual Behavior

The agent's Chrome activity neither appears in the invoking session's tab list nor is exposed by any documented command (agent subcommands are run/status/result/list/cancel only); agent.md says nothing about the isolation.

#### Root Cause Analysis

The autonomous agent runs in its own browser context/workspace on the backend; the CLI has no command to attach to or enumerate that context, and the docs do not state the isolation.

#### Code Pointer

`skills/browser4-cli/references/agent.md (How It Works section)`

#### AI Suggested Improvement

- Document explicitly that agent runs use an isolated browser context, and whether its tabs/screenshots are inspectable (e.g. via /pages.html or a session name)
- If the context is addressable, add an `agent tabs <task-id>`/`agent screenshot <task-id>` escape hatch for debugging
- Mention the per-task workspace directory (logs, artifacts) in the same section

#### Human Review

- [ ] **ACCEPT**
- [ ] **ACCEPT with improvements**
- [ ] **DEFER**
- [ ] **WONTFIX**
- [ ] **REJECT**
- **Notes:**

---

### Issue 8: Progress spinner keeps emitting animation frames when output is piped (non-TTY)

**Severity:** Low
**Category:** UX

#### Reproduction

./b4w.sh open --headless "<url>" | tail -40   (or capture output from an AI-agent harness)

#### Expected Behavior

Non-TTY output should degrade to a static start/finish line; SKILL.md positions the CLI for AI agents that capture stdout.

#### Actual Behavior

The server-startup spinner printed dozens of '⠋ Starting server... (Ns)…' frames with [K erase sequences into the pipe, burying the real messages; the navigation confirmation line itself appeared only as 'Session opened: …' with no explicit navigate confirmation.

#### Root Cause Analysis

The spinner writes frames unconditionally instead of checking whether stderr/stdout is a terminal (or isatty-style detection), so pipes/logs receive animation frames.

#### Code Pointer

`cli/browser4-cli/src/daemon.rs (server-startup progress rendering)`

#### AI Suggested Improvement

- Detect non-TTY output and print a single 'Starting server…' line plus the final 'Server ready in Ns' line
- Respect --quiet for startup progress as well
- Print an explicit navigation confirmation (URL + status) from open/goto even when a session was created first

#### Human Review

- [ ] **ACCEPT**
- [ ] **ACCEPT with improvements**
- [ ] **DEFER**
- [ ] **WONTFIX**
- [ ] **REJECT**
- **Notes:**

---

## Overall Assessment

**Completion Status:** Successful — all nine task steps executed against a live headless Chrome session: extract with inline and file-based JSON schemas, whole-page summarize, selector-scoped summarize, async agent task submission/polling/result retrieval, and the synchronous-vs-async comparison. The agent task completed correctly, but its extracted data was only obtainable by locating an undocumented backend workspace file on disk.

**Success Rate:** 90% — every command succeeded on the first functionally-correct invocation (one expected syntax error from using --headless with goto, per the documented open/goto split); the deduction is for the agent result being returned as a summary only, requiring filesystem archaeology, and for the misleading agent list status.

**Issues Found:** 8

**Major Blockers:** None blocking. The workspace pre-existed with a corrupted recorded backend URL (port 1), which made status/banner output misleading but did not break execution; the agent-result payload gap forced a manual filesystem search to obtain the task deliverable.

**Most Confusing Aspects:** For a first-time user: (1) agent list reporting 'queued' for a task that agent status reports as done, with no hint which to trust; (2) docs promising a status vocabulary (RUNNING/COMPLETED, processState, isDone) that the CLI never prints; (3) a server URL of http://127.0.0.1:1 in status and the startup banner while the docs promise 8282+; (4) agent result delivering a summary while the real answer sits in an unmentioned directory; (5) extract/summarize defaulting to a saved file with only a link printed — easy to miss in scripts.

**Most Valuable Improvements:** 1) Surface agent deliverables: include keyFindings/filesChanged in the result, add an artifact-listing command, or at least print the per-task workspace path. 2) Sync/refresh agent list status from the backend so it agrees with agent status. 3) Correct the agent status docs (or the payload) so the documented completion check actually works. 4) Validate recorded dev-mode backend URLs so a stale value can never stick forever, and show the URL's origin in status.

**Usability Rating:** 7/10

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

#### Issue 1: agent result returns only a prose summary — the extracted data is not retrievable through the CLI

./b4w.sh agent run "Navigate to https://en.wikipedia.org/wiki/Guido_van_Rossum and extract key biographical details: birth date, nationality, occupation, education, known for, and awards. Return them as a structured list."
./b4w.sh agent status <task-id>   # -> status done
./b4w.sh agent result <task-id>   # -> only a summary
./b4w.sh tool call command_result --json '{"id":"<task-id>"}'   # identical payload

#### Issue 2: Documented agent status payload and completion check do not match the CLI output

./b4w.sh agent status fe162b8d-a16d-4a7f-b0e7-6d6999cff05a
Compare with skills/browser4-cli/references/agent.md (status example + status table) and SKILL.md section 6 ('isDone: true is the reliable completion check').

#### Issue 3: agent list shows a completed task as 'queued' indefinitely (local status never synced)

./b4w.sh agent run "…"            # note task id
./b4w.sh agent status <id>     # -> done
./b4w.sh agent list            # -> same task still listed as 'queued' (also after agent result)
cat ~/.browser4/workspaces/Browser4-4.14-04e5248d/async-tasks.json   # entry for the task has no lastStatus field

#### Issue 4: Dev-mode backend URL resolves to port 1 and is then sticky forever

./b4w.sh open --headless "https://en.wikipedia.org/wiki/Python_(programming_language)"   # banner: 'Starting Browser4 dev server … on port 1'
./b4w.sh status      # 'Server URL: http://127.0.0.1:1'
curl -s http://127.0.0.1:1/status   # -> redirect:/status.html (Browser4 really listens on TCP 1)
# Contrast with a fresh state dir:
BROWSER4_CLI_STATE_DIR=<scratch>/state-fresh ./b4w.sh status   # -> 'Server URL: http://127.0.0.1:8282' (documented behaviour)

#### Issue 5: extract --filename help text contradicts the documented default output behaviour

./b4w.sh --help extract

#### Issue 6: Mixed path separators in the printed output-file links

./b4w.sh extract "…" --filename ".test-sessions/20261015T0901431699046Z/extract-result-schema.json"

#### Issue 7: Agent browsing happens in a context invisible to the invoking session and is undocumented

./b4w.sh agent run "Navigate to https://en.wikipedia.org/wiki/Guido_van_Rossum …"
# wait for done
./b4w.sh tab-list ; ./b4w.sh page-info   # still only the pre-existing tab/page; no trace of the agent's navigation

#### Issue 8: Progress spinner keeps emitting animation frames when output is piped (non-TTY)

./b4w.sh open --headless "<url>" | tail -40   (or capture output from an AI-agent harness)

