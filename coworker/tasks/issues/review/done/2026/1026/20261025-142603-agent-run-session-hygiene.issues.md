# Issues: agent-run-session-hygiene

> **Source:** `20261025-142603-agent-run-session-hygiene.issues.json` | **Date:** 20261025-142603 | **Mode:** dev

## Scenario Background

### Task

**Outcome: PASS — on this build, `agent run` executed in the CLI's current attached session; no session-binding regression reproduced.**

| Item | Value |
|---|---|
| Attached session | `00209ec9-fd20-41d0-b3cf-20ed23a8ff96` — `(default)`, Display `Attached`, `Connection: Extension (Microsoft Edge 155.0.0.0)` |
| Pre-task / restored URL | `about:blank` / `about:blank` |
| Task 1 (step B) | `7997ed48-b19a-4404-812a-4d90c89fc249` — done in 13.5 s, reported **Example Domain** |
| Task 2 (step E) | `26077a0e-c41f-4e66-81a7-21ccb4b12285` — done in 13.7 s; its `b4.run list` showed `00209ec9-…` with `Extension (Microsoft Edge 155.0.0.0)` |
| `command_run` / `Submitting agent task` | Both carry `session=00209ec9-…` (log lines 184/186 and 287/288) |
| Extension handshakes | 1 before the run, 1 after — no second relay connection |
| Sessions created | Only the pre-existing one (22:14:28); no new id in `list` after either task |
| Browser footprint | Baseline and post-run identical: `Browser4Roots=0`, `ChromeRoots=1`, `EdgeRoots=1`; port 9222 not listening |
| Post-run health | `page-info` = pre-task URL, `snapshot -i` works, `tab-list` shows the single relay tab; `agent result` corroborates the run |

Acceptance matrix: `command_run session=` ✅ · `Submitting agent task (session=)` ✅ · no new session ✅ · no second handshake ✅ · no new Browser4 browser root ✅ · task succeeded from the attached browser ✅ · pre-task page restored ✅ · session usable afterwards ✅ · agent's `b4.run list` reports the extension connection ✅ · no self-initiated `attach` ✅.

Note: the scenario's setup text says `status` has no session or `Connection` lines; on this build `status` **does** print a Session block (Name / Session ID / Status / Display / Connection / Next open), matching `attach.md`. I still read the connection from `list` as instructed.

### Execution Context

1. **Preparation** — verified `pwd` = repo root; ran `./b4w.ps1 help`; read `skills/browser4-cli/SKILL.md`, `AGENTS.md`, `references/agent.md`, `references/attach.md`. Confirmed JDK 25 via `JAVA_HOME` and a DeepSeek key in the backend config (`doctor`: "✓ LLM is configured").
2. **Environment baseline** — first `list` started the dev daemon/backend (log `browser4-server-jar-port8282-20261025T141409.463Z.log`, ready 22:14:24). The extension attach itself was already performed by the environment at 22:14:28 (log lines 154–162), so I issued no `attach`. Baseline: `page-info` = `about:blank`; footprint `Browser4Roots=0 / ChromeRoots=1 / EdgeRoots=1`; port 9222 empty; handshake count for the session = 1.
3. **Main task (no `-s`)** —
   `./b4w.ps1 agent run 'In the current session, run `goto htt...

(truncated — see 20261025-142603-agent-run-session-hygiene.issues.json for the complete execution trace)

---

## Issues Found (5 issues)

### Issue 1: Dev wrappers re-parse every CLI argument through Invoke-Expression, silently corrupting $ / backtick text and allowing command execution

**Severity:** High
**Category:** Reliability

#### Reproduction

From Git Bash or PowerShell in the repo root:
  ./b4w.ps1 eval '"A$B"' --json        -> {"result":"A"}            (expected "A$B")
  ./b4w.ps1 eval '"A`tB"' --json       -> {"result":"A\tB"}        (expected literal backtick-t)
  ./b4w.ps1 eval '"A$(1+1)B"' --json   -> {"result":"A2B"}          (the subexpression is EXECUTED)
  ./b4w.sh  eval '"A$(1+1)B"' --json   -> {"result":"A2B"}          (same defect)
Real-world manifestation: ./b4w.ps1 agent run 'First run `browser4-cli list` and report the FULL output...' --follow printed the header as 'First run rowser4-cli list'; GET http://127.0.0.1:8282/api/commands/<task-id>/events shows the stored task text as 'First run \browser4-cli list...' (U+0008 backspace), i.e. the pair `b was turned into a backspace before the CLI saw it.

#### Expected Behavior

Each argv element should reach browser4-cli byte-for-byte. Task text, JS, SQL and CSS containing $, `, $(...), quotes and spaces must be preserved exactly.

#### Actual Behavior

b4w.ps1 joins its arguments into a double-quoted string and re-evaluates it, so a second PowerShell parsing pass expands $variables, evaluates $(...) subexpressions, and interprets backtick escapes. The agent task text was silently altered (backtick+b became a backspace) while the CLI reported success; a task containing $(...) would execute arbitrary code in the user's shell context.

#### Root Cause Analysis

b4w.ps1 builds $SafeArgs by wrapping each argument in double quotes and escapes only internal double quotes ('"' + ($a -replace '"', '""') + '"'), then evaluates Invoke-Expression "& `"$Exe`" $SafeArgsStr". PowerShell parses the resulting string again, so $, ` and $(...) are interpreted as source. b4w.sh only disables MSYS path conversion and execs pwsh -File b4w.ps1 "$@", so it inherits the same behaviour. The installed browser4-cli binary is not affected.

#### Code Pointer

`b4w.ps1:1297-1307 (SafeArgs construction + Invoke-Expression); b4w.sh:79-81 (delegation); same pattern also at b4w.ps1:736, 756, 770`

#### AI Suggested Improvement

- Replace Invoke-Expression with call-operator splatting: `& $Exe @CliArgs` (and `& cargo run --manifest-path $Manifest -- @CliArgs`). The script already parses $args manually, so PowerShell will not bind dash flags to common parameters for a native command.
- If Windows native argument passing needs normalization, use `$PSNativeCommandArgumentPassing = 'Standard'` (pwsh 7.3+) or Start-Process -ArgumentList with explicit per-argument quoting; never re-evaluate a joined string.
- Add a regression test that round-trips an argument containing $, backtick, $(...), double quotes, spaces and a /-leading value through the wrapper and asserts byte identity (a harmless `eval`/`--help-json` call is enough).
- Audit the other Invoke-Expression call sites in b4w.ps1 for the same class of bug.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 2: agent run neither documents nor prints which session the task is bound to

**Severity:** Medium
**Category:** Discoverability

#### Reproduction

1) ./b4w.ps1 agent run --help  — the options and notes never say the task runs in the CLI's current session or that -s selects it.
2) ./b4w.ps1 agent run "..." --follow — output is only 'Task submitted: <task-id>'; no session id or connection is shown.
3) Verifying the binding required grepping the backend log for 'tool=command_run ... session=' and 'Submitting agent task ... (session=...)'.

#### Expected Behavior

The built-in help should state: 'Runs in the CLI's current session: -s <name> when given, otherwise the unnamed slot (including a browser attached with attach --extension/--cdp); never the backend DEFAULT session.' The submission line should echo the resolved session id and, when known, its connection (e.g. 'Task submitted: <id> (session=00209ec9-…, Extension (Microsoft Edge 155.0.0.0))').

#### Actual Behavior

The help text omits the binding entirely, and the CLI prints no session information for the task. Because the 2026-10-25 regression produced a successful task in a second browser while still reporting the attached session, this silence is exactly what makes the fault invisible; I had to consult the backend log and the process table to confirm the binding.

#### Root Cause Analysis

handle_agent_run computes resolved_session_id (resolve_agent_session_id) and sends it to the backend, but the human output only prints the task id (cli_println!("Task submitted: {}", task_id)). help.rs has no session-binding note for agent run, while the deeper reference skills/browser4-cli/references/agent.md does document it.

#### Code Pointer

`cli/browser4-cli/src/main.rs:handle_agent_run (Task submitted line ~15351, resolved_session_id ~15324); cli/browser4-cli/src/help.rs (agent run help block)`

#### AI Suggested Improvement

- Print the resolved session id (and connection label when available) on the 'Task submitted:' line, and add a sessionId field to the --json/--ndjson submission payload.
- Add a Notes line to `agent run --help`: the task runs in the CLI's current session (-s or the unnamed/attached slot), never the backend DEFAULT session; `open` creates a new session for attached browsers.
- Consider a stderr warning when an attached/extension session is targeted, so a future regression that launches a second browser is visible without inspecting logs.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 3: b4.run tool result repeats the first line of the child CLI output

**Severity:** Low
**Category:** UX

#### Reproduction

Inside an agent task that calls b4.run(list), inspect the tool.result event (GET /api/commands/<task-id>/events): its text begins 'b4.run [ok] Name | Session ID | URL | Status | Display | Created | Last Access | Connection | Next open Name | ... Next open ----------+...'. Likewise a b4.run(goto ...) result shows 'Using existing session DEFAULT (current page: about:blank).' twice. Running `./b4w.ps1 list` or `./b4w.ps1 tool call b4_run --json '{"args":"list"}'` directly prints it only once.

#### Expected Behavior

The model-visible result should contain the header/summary once and the body once, without duplicating the first content line.

#### Actual Behavior

The first non-blank line of every b4.run output appears twice (the agent itself reported 'the list table header printed twice ... cosmetic duplication').

#### Root Cause Analysis

ToolOutcome.render() appends the header, whose summary is buildSummary(ok, raw) = the first non-blank line of the raw output, and then appends body = the full raw output, which still starts with that same line. Both derive from evaluate.value, so the line is rendered twice whenever the output's first line is meaningful content.

#### Code Pointer

`browser4-agentic/src/main/kotlin/ai/platon/pulsar/agentic/model/ToolOutcome.kt:render() and buildSummary()`

#### AI Suggested Improvement

- When the header summary was derived from the first raw line, drop that line from the body before rendering (compare after whitespace compaction, since the summary is compacted).
- Alternatively, make the header carry only domain.method [ok|fail] and keep the summary as the first body line, so no content is repeated.
- Add a unit test with multi-line output whose first line is meaningful (a table header, a 'Using existing session...' line).

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 4: Backend logs use the same session= field for the browser session and the agent session

**Severity:** Low
**Category:** UX

#### Reproduction

After an agent run, search the backend log for the task id. It contains:
  tool=command_run session=00209ec9-fd20-41d0-b3cf-20ed23a8ff96 ...
  Submitting agent task 7997ed48-... (session=00209ec9-fd20-41d0-b3cf-20ed23a8ff96, ...)
  Agent task 7997ed48-... (session=1a1b7ecc-edc8-4991-98c2-9c78b4d8809b): engine overridden to CLI_TOOL_LOOP
`browser4-cli list` shows only 00209ec9; the second id is the AgenticSession UUID and is stable across both tasks, with no 'Created session' entry.

#### Expected Behavior

Logs should distinguish the two concepts, e.g. agentSession=<uuid> for the runner and browserSession=/session=<pulsar id> for the browser, or at least not reuse the bare field name session= for both.

#### Actual Behavior

The same `session=` key carries two different UUIDs for one task. The evaluation procedure for this exact regression treats 'a different session id' as the failure signal, so this line reads like a regression until the reader knows it is the agent's own session.

#### Root Cause Analysis

StatefulAgentRunner logs session.uuid where session is an AgenticSession, while ToolInvocationLogger/UserCommandExecutor log the Pulsar browser session id under the same session= key.

#### Code Pointer

`browser4-agent-tools/src/main/kotlin/ai/platon/pulsar/agentic/tools/advanced/agent/StatefulAgentRunner.kt:187 / 500 / 590-611 (logger calls with session.uuid)`

#### AI Suggested Improvement

- Rename the agent-session field in StatefulAgentRunner logs to agentSession= (and use session= or browserSession= for the bound Pulsar session id where available).
- Include the browser session id in the task start/done log lines so the binding is auditable from one line.
- Document which log lines are authoritative for session binding in references/agent.md.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 5: Docs claim b4w.sh quotes arguments safely, but it forwards into the same re-parsing path

**Severity:** Low
**Category:** Documentation

#### Reproduction

./b4w.sh eval '"A$(1+1)B"' --json returns {"result":"A2B"}, identical to ./b4w.ps1. CLAUDE.md's CLI Invocation section describes ./b4w.sh as a 'wrapper that quotes args safely for pwsh', and b4w.sh's header says it forwards args 'verbatim for pwsh'.

#### Expected Behavior

The docs should state precisely what is safe: b4w.sh only disables MSYS path conversion; argument values containing PowerShell metacharacters ($, backtick, $(...)) are still re-parsed and altered by b4w.ps1 until the wrapper is fixed.

#### Actual Behavior

A developer reading CLAUDE.md is led to believe b4w.sh protects argument text, but the same corruption occurs; there is no warning about $ or backticks anywhere in the dev-mode docs.

#### Root Cause Analysis

b4w.sh does forward each argument as its own argv element, but b4w.ps1 then rejoins the arguments into a double-quoted string executed with Invoke-Expression (Issue 1), so the 'safe quoting' claim does not hold for PowerShell metacharacters.

#### Code Pointer

`CLAUDE.md (CLI Invocation section, b4w.sh bullet); b4w.sh:40-51; skills/browser4-cli/SKILL.md §Invocation`

#### AI Suggested Improvement

- After fixing Issue 1, update the wording to describe exactly what b4w.sh protects (MSYS path conversion only).
- Until then, add a warning to the dev-mode docs and wrapper help about $, backtick and $(...) in arguments, with a safe workaround (e.g. --file/--stdin where the command supports it).
- Keep the doc claim and the wrapper behaviour in one place so they cannot drift again.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

## Overall Assessment

**Completion Status:** Successful — all scenario steps executed. The primary acceptance passed: both agent tasks ran in the CLI's attached Edge session, reported Example Domain, restored about:blank, and created no new session, handshake, or Browser4 browser root.

**Success Rate:** 95% — every task step and acceptance assertion succeeded; the only functional defect encountered was the dev wrapper silently corrupting the second task's text (completion still unaffected).

**Issues Found:** 5

**Major Blockers:** None for the scenario itself. The High-severity wrapper defect is a real blocker for tasks whose text contains $, backticks, or $(...) — my second task string was silently altered (backtick+b became a backspace) through ./b4w.ps1 and ./b4w.sh, and a $(...) argument is executed.

**Most Confusing Aspects:** There is no CLI-visible confirmation of which session agent run bound; proving the binding needed backend-log archaeology. The backend log then shows two different session= ids for the same task (browser session vs agent session), and the scenario's note that status has no session/Connection lines is outdated — this build prints a full Session block.

**Most Valuable Improvements:** Print the resolved session id and connection on the 'Task submitted:' line and document the binding in `agent run --help`; fix the dev wrappers' Invoke-Expression argument re-parsing (silent corruption and command execution); distinguish agentSession= from browser session= in backend logs.

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

#### Issue 1: Dev wrappers re-parse every CLI argument through Invoke-Expression, silently corrupting $ / backtick text and allowing command execution

From Git Bash or PowerShell in the repo root:
  ./b4w.ps1 eval '"A$B"' --json        -> {"result":"A"}            (expected "A$B")
  ./b4w.ps1 eval '"A`tB"' --json       -> {"result":"A\tB"}        (expected literal backtick-t)
  ./b4w.ps1 eval '"A$(1+1)B"' --json   -> {"result":"A2B"}          (the subexpression is EXECUTED)
  ./b4w.sh  eval '"A$(1+1)B"' --json   -> {"result":"A2B"}          (same defect)
Real-world manifestation: ./b4w.ps1 agent run 'First run `browser4-cli list` and report the FULL output...' --follow printed the header as 'First run rowser4-cli list'; GET http://127.0.0.1:8282/api/commands/<task-id>/events shows the stored task text as 'First run \browser4-cli list...' (U+0008 backspace), i.e. the pair `b was turned into a backspace before the CLI saw it.

#### Issue 2: agent run neither documents nor prints which session the task is bound to

1) ./b4w.ps1 agent run --help  — the options and notes never say the task runs in the CLI's current session or that -s selects it.
2) ./b4w.ps1 agent run "..." --follow — output is only 'Task submitted: <task-id>'; no session id or connection is shown.
3) Verifying the binding required grepping the backend log for 'tool=command_run ... session=' and 'Submitting agent task ... (session=...)'.

#### Issue 3: b4.run tool result repeats the first line of the child CLI output

Inside an agent task that calls b4.run(list), inspect the tool.result event (GET /api/commands/<task-id>/events): its text begins 'b4.run [ok] Name | Session ID | URL | Status | Display | Created | Last Access | Connection | Next open Name | ... Next open ----------+...'. Likewise a b4.run(goto ...) result shows 'Using existing session DEFAULT (current page: about:blank).' twice. Running `./b4w.ps1 list` or `./b4w.ps1 tool call b4_run --json '{"args":"list"}'` directly prints it only once.

#### Issue 4: Backend logs use the same session= field for the browser session and the agent session

After an agent run, search the backend log for the task id. It contains:
  tool=command_run session=00209ec9-fd20-41d0-b3cf-20ed23a8ff96 ...
  Submitting agent task 7997ed48-... (session=00209ec9-fd20-41d0-b3cf-20ed23a8ff96, ...)
  Agent task 7997ed48-... (session=1a1b7ecc-edc8-4991-98c2-9c78b4d8809b): engine overridden to CLI_TOOL_LOOP
`browser4-cli list` shows only 00209ec9; the second id is the AgenticSession UUID and is stable across both tasks, with no 'Created session' entry.

#### Issue 5: Docs claim b4w.sh quotes arguments safely, but it forwards into the same re-parsing path

./b4w.sh eval '"A$(1+1)B"' --json returns {"result":"A2B"}, identical to ./b4w.ps1. CLAUDE.md's CLI Invocation section describes ./b4w.sh as a 'wrapper that quotes args safely for pwsh', and b4w.sh's header says it forwards args 'verbatim for pwsh'.

