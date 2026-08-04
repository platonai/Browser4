# Issues: advanced-mouse-interaction

> **Source:** `20260804-173305-advanced-mouse-interaction.full.md` | **Date:** 20260804-173305 | **Mode:** dev

## Scenario Background

### Task

All 13 task steps completed successfully. The interactive-5.html test page was fully exercised: hover tooltips revealed, product card expanded, drag reorder confirmed, double-click activation and reset verified, CSS locator generated and validated with `get text`, and all three dialog types (alert, confirm, prompt) were triggered and handled with results verified in the interaction log. Final screenshots captured the complete page state.

**Key workaround required:** Every dialog-triggering click timed out and left a zombie `browser4-cli.exe` process that locked the binary, requiring manual `taskkill` between each dialog step. This turned a simple 3-dialog workflow into a 9-step process of click→kill→dialog-accept→repeat.

---

### Execution Context

| Step | Command | Outcome |
|------|---------|---------|
| Setup | `./b4w.ps1 help`, read SKILL.md | Learned commands and conventions |
| 1 | `./b4w.ps1 goto "http://localhost:18080/generated/interactive-5.html"` | Page loaded, server auto-started in 5.7s |
| 2 | `./b4w.ps1 snapshot -i --stdout` | Full interactive element tree discovered: tooltips (e22, e25), cards (e27, e31), drag list (e39-e42), dblclick zones (e45, e51), dialog buttons (e57-e60) |
| 3 | `./b4w.ps1 hover e22`, `./b4w.ps1 hover e25` | Hovered both tooltip terms; verified tooltip text embedded in AX accessible names |
| 4 | `./b4w.ps1 hover e27` | Hovered product card; verified expansion: box height grew from 73px→126px, detail text from 0px→53px |
| 5 | `./b4w.ps1 drag e39 e42` | Dragged "High Priority" to Backlog; verif...

(truncated — see full.md for complete trace)

---

## Issues Found (8 issues)

### Issue 1: Binary locked by zombie processes after dialog-triggering clicks time out

**Severity:** Critical
**Category:** Reliability

#### Reproduction

./b4w.ps1 click e57 (where e57 triggers alert/confirm/prompt). Command times out, leaves browser4-cli.exe running. Run any other ./b4w.ps1 command — it fails with 'error: failed to remove file ... browser4-cli.exe ... 拒绝访问。 (os error 5)' because b4w.ps1 detects source changes and tries to rebuild but can't overwrite the locked binary.

#### Expected Behavior

The background process from a timed-out command should be auto-terminated, or the CLI should not require a rebuild when sources haven't changed, or the locking process should release the binary immediately after timeout.

#### Actual Behavior

Every dialog-triggering click timed out, left a browser4-cli.exe zombie, and every subsequent command failed at the rebuild step because the zombie held a lock on target/debug/browser4-cli.exe. Required manual cmd //c "taskkill /F /IM browser4-cli.exe" between every single command.

#### Root Cause Analysis

When a click command triggers a native dialog (alert/confirm/prompt), the WebDriver call blocks waiting for the dialog to resolve. The Bash tool's timeout fires and moves the task to background, but the underlying browser4-cli.exe process continues running because it's still waiting for the CDP dialog-handling response. This running process holds a file handle on its own executable. Meanwhile, b4w.ps1 detects that 'Rust sources changed' (likely due to background task output files or temp files appearing in the project tree) and triggers a cargo rebuild, which fails because Windows won't let you delete a running executable.

#### Code Pointer

`cli/browser4-cli/src/main.rs — the click command handler should set a client-side timeout or implement a non-blocking dialog-aware execution path. The b4w.ps1 script's source-change detection is also a contributing factor.`

#### AI Suggested Improvement

- Auto-kill the browser4-cli.exe process when a command times out and is moved to background
- Add a server-side timeout for dialog-blocking WebDriver calls so the CLI process exits cleanly
- Fix the b4w.ps1 source-change detection to not trigger on temp/output files written by background tasks
- Or: implement dialog handling as a server-side option so click+dialog-accept is truly single-invocation

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Critical reliability bug — zombie processes lock the binary after dialog-triggering click timeouts, blocking all subsequent commands. Root cause is clear: CDP call blocks on the dialog, the Bash timeout fires but the process lives on. This alone makes the CLI unusable after any dialog interaction.

---

### Issue 2: --auto-dismiss-dialogs flag does not work reliably for native dialogs

**Severity:** High
**Category:** Reliability

#### Reproduction

./b4w.ps1 click --auto-dismiss-dialogs e58 (where e58 is a button that triggers confirm()). The command timed out after 30s and was moved to background.

#### Expected Behavior

The --auto-dismiss-dialogs flag should auto-accept the dialog and the click command should complete within a few seconds.

#### Actual Behavior

Command timed out identically to a regular click, providing no benefit over the two-step approach.

#### Root Cause Analysis

The --auto-dismiss-dialogs flag may only handle auto-dismissal at the client layer but the underlying CDP call still blocks on the dialog. Or the flag may not be wired through correctly to the backend for confirm/prompt dialogs (which require a response value, not just dismissal). Investigation needed to determine whether this is a client-side or server-side issue.

#### AI Suggested Improvement

- Ensure --auto-dismiss-dialogs sets a short timeout on the CDP call and accepts the dialog server-side
- For confirm, default to accept=true; for prompt, default to empty string or expose a --dialog-input flag
- Add integration tests that verify --auto-dismiss-dialogs completes within 5 seconds for alert, confirm, and prompt

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] High-severity bug — the `--auto-dismiss-dialogs` flag is a key feature that would make Issue 1 and Issue 4 largely moot, but it's non-functional. The root cause (likely not wired through to CDP for confirm/prompt which need response values) needs investigation. Fixing this is the highest-leverage single change.

---

### Issue 3: snapshot grep and other commands hang when a native browser dialog is open

**Severity:** High
**Category:** Reliability

#### Reproduction

1. Click a button that triggers alert() — click hangs. 2. In another terminal, run ./b4w.ps1 snapshot grep "anything" — this also hangs indefinitely.

#### Expected Behavior

Snapshot commands should either (a) detect that a dialog is blocking the page and report it clearly, or (b) complete successfully on the pre-dialog page state.

#### Actual Behavior

snapshot grep timed out after 30s with no error message, just a background task notification. The user gets no feedback about why the command is stuck.

#### Root Cause Analysis

Native browser dialogs (alert/confirm/prompt) block the page's JavaScript main thread. Any CDP command that requires JS execution (like DOM.getDocument for accessibility tree) gets queued behind the dialog and never completes. The CLI has no detection or timeout mechanism for this state.

#### AI Suggested Improvement

- Add dialog-state detection before snapshot/eval commands: if a dialog is open, report 'Page is blocked by a native dialog — use dialog-accept or dialog-dismiss first'
- Set a reasonable timeout for snapshot-related CDP calls and surface a clear error when it fires
- Consider using Page.handleJavaScriptDialog to detect dialog state proactively

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Dialog-state detection is a critical missing feature — commands silently hang with no feedback when a dialog blocks the page. The suggested fix (proactive `Page.handleJavaScriptDialog` check before blocked CDP calls) is the right approach and would also help Issue 1 by enabling clean early-exit instead of timeout-zombie.

---

### Issue 4: Dialog handling workflow is poorly discoverable and cumbersome for new users

**Severity:** Medium
**Category:** UX

#### Reproduction

A first-time user wanting to click a button that triggers alert() must: 1. Read SKILL.md to learn dialogs require separate invocation 2. Try click → observe timeout 3. Realize they need a second terminal 4. Run dialog-accept 5. Discover the binary is now locked 6. Google how to kill processes on Windows 7. Kill the zombie process 8. Retry dialog-accept 9. Verify result.

#### Expected Behavior

The workflow should be either: (a) click --auto-dismiss-dialogs works in a single step, or (b) the click command clearly states 'Dialog detected — use dialog-accept or dialog-dismiss in a separate invocation' and exits cleanly instead of timing out.

#### Actual Behavior

Nine-step manual process with binary lockouts. The click timeout message gives no indication a dialog is the cause.

#### Root Cause Analysis

Multiple compounding issues: no dialog detection on the client side, no clean exit on dialog detection, binary locking from timeouts, and --auto-dismiss-dialogs not working. Each alone is minor; together they create a very poor experience.

#### AI Suggested Improvement

- Make click commands detect dialog-blocking and report 'Dialog detected: alert/confirm/prompt. Use dialog-accept or dialog-dismiss.' instead of timing out
- Fix --auto-dismiss-dialogs to actually work end-to-end
- Add an example to the Quick Start section showing the full dialog handling pattern
- Consider a click --dialog-accept and click --dialog-dismiss combined flag

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] This is the user-facing synthesis of Issues 1–3. Most of the 9-step ordeal is eliminated by fixing those three bugs. The remaining standalone UX value here is: (a) adding a dialog example to Quick Start, (b) a combined `click --dialog-accept` flag, and (c) a "Dialog detected" message in the click timeout path instead of a generic timeout. Worth implementing after 1–3 are resolved.

---

### Issue 5: Rust sources changed rebuild triggers spuriously, slowing every command

**Severity:** Medium
**Category:** Reliability

#### Reproduction

Run several browser4-cli commands in sequence without editing any source files. After a background task is stopped or a command times out, subsequent commands show 'Rust sources changed, rebuilding browser4-cli...'.

#### Expected Behavior

Rebuild should only trigger when actual Rust source files have been modified.

#### Actual Behavior

Rebuild triggers after background tasks are stopped or after killing zombie processes, likely because temp files or task output files modify timestamps in the project tree that the change-detection logic monitors.

#### Root Cause Analysis

The b4w.ps1 script's source-change detection appears to monitor file modification timestamps in the project directory too broadly, picking up temp files, task output files, or other artifacts created during command execution. Each rebuild takes 2-12 seconds, compounding the dialog-handling delay.

#### Code Pointer

`b4w.ps1 — the source-change detection logic, likely a file timestamp comparison that scans too many directories.`

#### AI Suggested Improvement

- Scope source-change detection to only watch .rs files in cli/browser4-cli/src/
- Or use git diff to detect actual source changes instead of filesystem timestamps
- Exclude .browser4-cli/, temp/, and task output directories from change detection

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Spurious rebuilds compound every other issue — a 2–12 second rebuild after each timeout/zombie-kill turns a bad experience worse. The fix is straightforward: scope change detection to `.rs` files under `cli/browser4-cli/src/` or use `git diff` instead of filesystem timestamps. Exclude `.browser4-cli/`, `temp/`, and task output dirs.

---

### Issue 6: drag command has no visible confirmation message in output

**Severity:** Low
**Category:** UX

#### Reproduction

./b4w.ps1 drag e39 e42

#### Expected Behavior

Output should include something like '✓ Dragged e39 onto e42' or '✓ Drag completed', similar to how hover outputs '✓ Hovered e22' and dblclick outputs '✓ Double-clicked e45'.

#### Actual Behavior

Output jumps directly from the command invocation to '### Page' snapshot header with no intervening confirmation that the drag action executed.

#### Root Cause Analysis

The drag command handler in the CLI doesn't emit a success message before displaying the post-interaction snapshot. Compare with hover and dblclick which do.

#### Code Pointer

`cli/browser4-cli/src/ — the drag command handler, look for where hover/dblclick emit their '✓' messages and add equivalent for drag.`

#### AI Suggested Improvement

- Add '✓ Dragged <source-ref> → <target-ref>' confirmation message before the snapshot output
- Ensure all interaction commands (click, dblclick, hover, drag, fill, type, press) follow the same confirmation pattern

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Simple consistency fix. The `drag` command handler is missing the `✓` confirmation line that `hover`, `dblclick`, and other interaction commands emit. A one-line addition matching the existing pattern. The suggested message format `✓ Dragged <source-ref> → <target-ref>` is appropriate.

---

### Issue 7: get text output contains excessive leading whitespace

**Severity:** Low
**Category:** Product

#### Reproduction

./b4w.ps1 get text "#alertBtn"

#### Expected Behavior

Output should be the element's trimmed text content: '🔔 Show Alert'.

#### Actual Behavior

Output contains many leading spaces: '            🔔 Show Alert'.

#### Root Cause Analysis

The get-text handler likely returns the raw textContent of the DOM node, which includes whitespace from the HTML source indentation. No trimming is applied.

#### Code Pointer

`browser4-core — the get text WebDriver command implementation; textContent should be trimmed before returning.`

#### AI Suggested Improvement

- Trim whitespace from text content in the get text handler before returning
- Consider adding a --trim flag (defaulting to true) for explicit control

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Data quality issue — `textContent` returns HTML-source whitespace. A `.trim()` call in the get-text handler resolves it. The suggested `--trim` flag (defaulting to true) is reasonable but likely over-engineering; just trimming by default is sufficient since untrimmed whitespace-heavy output is never what the user wants.

---

### Issue 8: No warning when snapshot grep would search a stale snapshot after interactions

**Severity:** Low
**Category:** UX

#### Reproduction

Run several interaction commands, then snapshot grep without re-snapshotting. The grep searches the most recent automatic snapshot which may reflect an intermediate state rather than the current page state.

#### Expected Behavior

A clear indication of which snapshot is being searched, or a warning if the snapshot is from a different interaction than the most recent one.

#### Actual Behavior

snapshot grep runs silently on whatever the last automatic post-interaction snapshot was. The user must track mentally which snapshot corresponds to which state.

#### Root Cause Analysis

Automatic post-interaction snapshots are convenient but can create confusion when the user expects snapshot grep to reflect the current page state. There's no linkage between 'the last interaction' and 'the snapshot being searched'.

#### AI Suggested Improvement

- Show the snapshot timestamp/file being searched at the top of snapshot grep output
- Consider adding 'snapshot grep --latest' that always captures a fresh snapshot first

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Valid UX refinement. Automatic post-interaction snapshots are a convenience but create ambiguity about what `snapshot grep` is searching. Showing a timestamp or file reference in the grep output header is a low-cost, high-clarity fix. The `--latest` flag suggestion is a nice addition for the "just search current state" use case.

---

## Overall Assessment

**Completion Status:** Successful — all 13 task steps completed. Two screenshots captured showing final page state including the complete interaction log. However, significant workarounds were required for dialog handling: each dialog-triggering click required manual process killing before the next command could run.

**Success Rate:** 85% — task steps themselves all succeeded, but the dialog-handling workflow (steps 10-12) required 3x more commands than documented due to binary locking issues

**Issues Found:** 8

**Major Blockers:** The binary-locking issue (Critical severity) blocked all progress between dialog-handling steps, requiring manual taskkill every time. The --auto-dismiss-dialogs flag (High severity) is documented as the solution but does not work, leaving users with the painful two-step approach. Dialog-blocking also causes snapshot commands to hang silently (High severity).

**Most Confusing Aspects:** 1. Why does every command start a Rust rebuild when I haven't edited any code? 2. Why does the click command hang forever when a dialog appears, instead of telling me a dialog is blocking? 3. Why does the binary get locked and prevent all subsequent commands after a timeout? 4. The two-terminal dialog handling pattern is not obvious from the help output — you have to read the full SKILL.md.

**Most Valuable Improvements:** 1. Fix the binary locking issue (auto-kill zombie processes, fix spurious rebuild detection) 2. Make --auto-dismiss-dialogs actually work end-to-end 3. Detect dialog-blocking state and surface clear error messages instead of hanging 4. Add dialog-handling example to Quick Start / --help 5. Add confirmation messages to all interaction commands (drag is missing one)

**Usability Rating:** 5/10

---

## How to Reproduce

### Common Setup

1. Clone the repository and `cd` to the repo root.
2. The CLI is invoked via `./b4w.ps1` which auto-builds from source when needed.
3. The backend server starts automatically in dev mode.
4. All commands from repo root: `./b4w.ps1 <command>`

### Per-Issue Reproduction Steps

#### Issue 1: Binary locked by zombie processes after dialog-triggering clicks time out

./b4w.ps1 click e57 (where e57 triggers alert/confirm/prompt). Command times out, leaves browser4-cli.exe running. Run any other ./b4w.ps1 command — it fails with 'error: failed to remove file ... browser4-cli.exe ... 拒绝访问。 (os error 5)' because b4w.ps1 detects source changes and tries to rebuild but can't overwrite the locked binary.

#### Issue 2: --auto-dismiss-dialogs flag does not work reliably for native dialogs

./b4w.ps1 click --auto-dismiss-dialogs e58 (where e58 is a button that triggers confirm()). The command timed out after 30s and was moved to background.

#### Issue 3: snapshot grep and other commands hang when a native browser dialog is open

1. Click a button that triggers alert() — click hangs. 2. In another terminal, run ./b4w.ps1 snapshot grep "anything" — this also hangs indefinitely.

#### Issue 4: Dialog handling workflow is poorly discoverable and cumbersome for new users

A first-time user wanting to click a button that triggers alert() must: 1. Read SKILL.md to learn dialogs require separate invocation 2. Try click → observe timeout 3. Realize they need a second terminal 4. Run dialog-accept 5. Discover the binary is now locked 6. Google how to kill processes on Windows 7. Kill the zombie process 8. Retry dialog-accept 9. Verify result.

#### Issue 5: Rust sources changed rebuild triggers spuriously, slowing every command

Run several browser4-cli commands in sequence without editing any source files. After a background task is stopped or a command times out, subsequent commands show 'Rust sources changed, rebuilding browser4-cli...'.

#### Issue 6: drag command has no visible confirmation message in output

./b4w.ps1 drag e39 e42

#### Issue 7: get text output contains excessive leading whitespace

./b4w.ps1 get text "#alertBtn"

#### Issue 8: No warning when snapshot grep would search a stale snapshot after interactions

Run several interaction commands, then snapshot grep without re-snapshotting. The grep searches the most recent automatic snapshot which may reflect an intermediate state rather than the current page state.

