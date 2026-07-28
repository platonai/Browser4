# Issues: attach-remote-debug

> **Source:** `20260727-235856-attach-remote-debug.full.md` | **Date:** 20260727-235856 | **Mode:** dev

## Scenario Background

### Task

Successfully completed the attach workflow:

1. **Chrome started** with `--remote-debugging-port=9222` and confirmed via CDP `json/version` endpoint.
2. **Attached via CDP channel name** (`attach --cdp chrome`) — required closing a stale unnamed session first.
3. **Tab listing** worked, showing initially `about:blank`, then tracked multiple tabs across navigation and creation.
4. **Screenshots** captured successfully for `httpbin.org/get` (119KB, 2160×1292 PNG) and `example.com` (27KB).
5. **Snapshot** captured the accessibility tree with element refs for `httpbin.org/get`.
6. **State save** preserved cookies and localStorage (empty for the test sites, as expected) to JSON.
7. **Tab switching** (`tab-new`, `tab-select`) worked across two tabs with screenshots of each.
8. **`--endpoint` + `--cdp`** combined successfully: `attach --endpoint http://localhost:8182 --cdp chrome` connected through the local Browser4 server to Chrome's CDP.
9. **`--endpoint` alone** correctly switched the CLI to the remote server.
10. **Session closed** with `close`, which correctly disconnected without killing the Chrome browser.

---

### Execution Context

| # | Command | Purpose | Outcome |
|---|---------|---------|---------|
| 1 | `google-chrome --remote-debugging-port=9222 &` | Start Chrome with CDP | Chrome running on port 9222 |
| 2 | `./b4w.ps1 attach --cdp chrome` | Attach to Chrome via channel name | **Failed** — unnamed session exists |
| 3 | `./b4w.ps1 list` | List existing sessions | Found stale session |
| 4 | `./b4w.ps1 close` | Close stale session | Session closed, browser killed |
| 5 | `./b4w.ps1 attach --cdp chrome` | Retry attach | **Success** — attached to Chrome CDP |
| 6 | `./b4w.ps1 tab-list` | List browser tabs | 1 tab: `about:blank` |
| 7 | `./b4w.ps1 goto "https://httpbin.org/get"` | Navigate to test page | Page loaded |
| 8 | `./b4w.ps1 screenshot` | Screenshot of httpbin | 119KB PNG saved |
| 9 | `./b4w.ps1 -- snap...

(truncated — see full.md for complete trace)

---

## Issues Found (8 issues)

### Issue 1: b4w.ps1 cannot pass short flags on Linux/bash — requires b4w.sh workaround

**Severity:** Medium
**Category:** Reliability

#### Reproduction

./b4w.ps1 -- snapshot -v 0 --stdout  (produces PowerShell parameter ambiguity error); ./b4w.ps1 snapshot -v 0 --stdout  (parses as unknown command 'snapshot-0')

#### Expected Behavior

Flags like -v and -i should pass through to the CLI binary regardless of whether b4w.ps1 or b4w.sh is used.

#### Actual Behavior

PowerShell's parameter binder intercepts -v (matches -Verbose) and -i (matches -InformationAction) even on Linux. The -- separator doesn't work with b4w.ps1 either (throws 'parameter name is ambiguous'). Workaround: use ./b4w.sh instead, but it emits a warning recommending pwsh.

#### Root Cause Analysis

The b4w.ps1 PowerShell script doesn't properly shield short flags from PowerShell's parameter binder. Even on Linux with pwsh, the -v and -i flags match PowerShell common parameters. The SKILL.md documents this for Windows but the problem also affects Linux users who follow the task instructions to use $(./b4w.ps1).

#### AI Suggested Improvement

- Modify b4w.ps1 to pass all arguments after the script name through verbatim to cargo run without PowerShell parameter binding
- Or document prominently in SKILL.md that on Linux/macOS, prefer b4w.sh over b4w.ps1 for commands with short flags
- Or add a note in the help output's first-run experience about the wrapper behavior and which wrapper to use per platform

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [x] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT with improvements] PowerShell's parameter binder intercepting `-v`/`-i` on Linux pwsh is a real reliability bug that silently corrupts commands. The fix should be in `b4w.ps1` (pass args verbatim via `--%` or `@args` with stop-parsing). Cross-reference with Issue 2 — this is the root cause of misleading CLI errors when flags vanish.

---

### Issue 2: Snapshot flag parsing failure produces misleading error message

**Severity:** Medium
**Category:** Reliability

#### Reproduction

./b4w.ps1 snapshot -v 0 --stdout

#### Expected Behavior

A clear error like 'Unknown flag: -v' or successful snapshot with -v 0.

#### Actual Behavior

Error: 'Unknown command: snapshot-0. Did you mean: snapshot?' The error suggests snapshot-0 is being parsed as a subcommand name, confusing the user about CLI command structure.

#### Root Cause Analysis

PowerShell strips -v (matching -Verbose), leaving 'snapshot 0 --stdout'. The CLI parses '0' as a subcommand name appended to 'snapshot', forming 'snapshot-0'. The error message generator doesn't account for this flag-stripping case.

#### AI Suggested Improvement

- Improve the subcommand parser to detect when a positional argument looks like a flag value (numeric, short) and suggest checking flag syntax
- Add a specific error hint when an unknown subcommand looks like 'command-N' where N is numeric: 'If you meant to pass -v N, ensure flags are not being intercepted by your shell wrapper'

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] The `snapshot-0` → "Unknown command" error is genuinely misleading. While Issue 1 (PowerShell stripping `-v`) is the root cause, the CLI should never form a subcommand name from a bare numeric argument and suggest it as a command. A specific hint like "If you meant to pass a flag value, check your shell wrapper" would help self-diagnosis. No improvement suffix needed — just fix the error path.

---

### Issue 3: Backend version mismatch between locally-built CLI and installed backend

**Severity:** Medium
**Category:** Product

#### Reproduction

Run ./b4w.sh status after the daemon auto-starts.

#### Expected Behavior

The daemon should auto-start the locally-built backend JAR, matching CLI version 4.12.1.

#### Actual Behavior

CLI is 4.12.1 (from local source) but the running backend is v4.11.15 (installed bundle). Status output warns: 'Version mismatch: CLI is 4.12.1 but installed backend is v4.11.15. The CLI was built from local source while the backend runs from a pre-installed bundle.' The warning suggests manually running mvn spring-boot:run to use the locally-built backend.

#### Root Cause Analysis

The daemon auto-start logic prioritizes an already-installed backend bundle over building and running from local source. The locally-built JAR expected by the 'dev mode' instructions may not exist yet if mvn package wasn't run.

#### AI Suggested Improvement

- The daemon could detect it's running from a source checkout and prefer the locally-built JAR in browser4-rest/target/
- Or auto-build the backend as part of the daemon start when running from source
- Or update CLAUDE.md to clarify that 'mvn package -pl browser4-rest -am -DskipTests' is needed before the first dev-mode run

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [x] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT with improvements] Dev workflow friction when daemon starts a stale installed backend instead of the locally-built JAR. The improvement should be: in source-checkout context, check `browser4-rest/target/` first before falling back to the installed bundle, and update CLAUDE.md with the prerequisite `mvn package` step. This is a bite-sized change in the daemon startup resolution logic.

---

### Issue 4: attach --cdp fails with existing unnamed session but error is recoverable

**Severity:** Low
**Category:** UX

#### Reproduction

Have an existing unnamed (default) session active, then run: ./b4w.ps1 attach --cdp chrome

#### Expected Behavior

Either auto-close the stale session and attach, or prompt the user with an actionable choice.

#### Actual Behavior

Error: 'An unnamed session already exists: <uuid>. Use -s <name> to create a named session instead...' The user must manually close the old session, then re-attach. The error message is helpful but requires two commands.

#### Root Cause Analysis

Session management enforces one-unnamed-session constraint rigidly. The attach command doesn't offer to replace or reuse the existing session slot.

#### AI Suggested Improvement

- Add a --force flag to attach that auto-closes the existing unnamed session before attaching
- Or offer an interactive prompt: 'Existing session found. Replace it? [y/N]'
- Or treat attach as implicitly closing/replacing the unnamed session since the user explicitly wants to connect to a different browser

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [x] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT with improvements] The session collision UX forces a manual close-then-attach dance. A `--force` flag on `attach` to auto-close the conflicting unnamed session is the cleanest fix — it's explicit (no surprise replacement), backward-compatible, and consistent with how `--force` is used elsewhere in CLI tools.

---

### Issue 5: b4w.sh emits pwsh recommendation warning on every invocation on Linux

**Severity:** Low
**Category:** UX

#### Reproduction

Run any command via ./b4w.sh on Linux: e.g., ./b4w.sh tab-list

#### Expected Behavior

Clean command output without platform-irrelevant warnings.

#### Actual Behavior

Every command is prefixed with: 'It is strongly recommended to launch pwsh and run the .ps1 commands directly within the pwsh terminal.' This adds noise and is confusing on Linux where bash is the native shell.

#### Root Cause Analysis

b4w.sh unconditionally prints this warning regardless of platform. The warning is intended for Windows Git Bash / WSL users but triggers on native Linux as well.

#### Code Pointer

`b4w.sh: the warning print statement`

#### AI Suggested Improvement

- Detect the platform and suppress the pwsh recommendation on native Linux/macOS
- Or downgrade to a tip that prints only once per session (e.g., check an env var)
- Or print it only when PWSH/POWERSHELL is actually available on the system

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Printing a pwsh recommendation on native Linux is noise that undermines user trust in the tool's guidance. Simple fix: gate the warning on `uname` or `$OSTYPE` so it only fires on Windows/WSL/Git-Bash. Cross-reference with Issue 1 — both are wrapper-platform-awareness bugs; fix together for consistency.

---

### Issue 6: state-save saves to current working directory with no --output-dir flag

**Severity:** Low
**Category:** UX

#### Reproduction

./b4w.sh state-save my-backup.json

#### Expected Behavior

Option to specify an output directory, or the file saved to a configurable default location.

#### Actual Behavior

File is saved to CWD (repo root) with no way to redirect to a different directory. The user must manually move the file afterward.

#### Root Cause Analysis

state-save uses the provided filename as-is, resolved against CWD. There's no --output-dir or --dir flag.

#### AI Suggested Improvement

- Add --output-dir flag to state-save to specify target directory
- Or default to .browser4-cli/sessions/ directory for better file organization
- At minimum, document in the help output that the filename is relative to CWD

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [x] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT with improvements] `state-save` dropping files in CWD with no destination control is a minor but real UX gap. Add `--output-dir` flag. Defaulting to `.browser4-cli/sessions/` is tempting but would be a behavior change — safer to add the flag first and consider a default change separately. Cross-reference: same `--output-dir` pattern may apply to `crawl` and `htmlsnapshot` export paths for consistency.

---

### Issue 7: Snapshot output truncates long text content creating malformed escape sequences

**Severity:** Low
**Category:** UX

#### Reproduction

./b4w.sh snapshot -v 0 --stdout on a page with substantial text content (e.g., httpbin.org/get JSON response).

#### Expected Behavior

Cleanly truncated text with clear truncation markers, or full content without truncation.

#### Actual Behavior

Text is truncated with '(truncated from N chars)' appended inside the YAML value, but the JSON content includes nested escaped quotes that break readability: ' \\\"Chromium\\\";v=\\\"148\\\"'. The truncation in the middle of a JSON string creates visual noise.

#### Root Cause Analysis

The snapshot rendering truncates long text nodes with an inline marker but doesn't close the YAML string cleanly. When the truncated content contains escaped JSON, the output becomes hard to parse.

#### AI Suggested Improvement

- Truncate at a clean boundary (end of a YAML-safe segment) rather than mid-escape-sequence
- Consider collapsing long text nodes to a summary like '[JSON content: 936 chars]' instead of partial inline rendering
- Add a --no-truncate flag for users who need full content

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [x] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT with improvements] Truncating mid-escape-sequence produces malformed YAML that's worse than no truncation. The fix should truncate at clean boundaries (end of a YAML-safe segment) and consider a `--no-truncate` flag for full-content workflows. The improvement over plain ACCEPT is important here: a naive "truncate at N chars" fix won't help if the boundary still falls inside an escape sequence — needs content-aware truncation.

---

### Issue 8: tab-new output says 'Switched to tab 0' when creating first additional tab — confusing index

**Severity:** Low
**Category:** UX

#### Reproduction

Have 1 tab (about:blank at index 0), then run: ./b4w.sh tab-new https://example.com

#### Expected Behavior

Output like 'Created tab 1' or 'Switched to new tab (index 1)'.

#### Actual Behavior

'Switched to tab 0' — the new tab was inserted at position 0 (before the existing tab), which is Chrome's native behavior. But the user expects a NEW tab to have a NEW index, not to displace the existing tab.

#### Root Cause Analysis

Chrome inserts new tabs after the active tab. When the active tab is at index 0, the new tab goes to index 0 and the old tab shifts to index 1. tab-new output reflects the final state correctly but the user expects creation to produce a higher index.

#### AI Suggested Improvement

- Show both the created GUID and the resulting tab position: 'Created tab F122... at index 0 (shifted existing tabs)'
- Or include a tab-list automatically after tab-new so the user can see the full state
- Or add a note in the SKILL.md tab management section about Chrome's tab insertion behavior

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] "Switched to tab 0" when creating a brand-new tab is confusing. The fix is a one-line output change: include the created tab's GUID plus a note about insertion position (e.g., "Created tab F122... at index 0 (existing tab shifted to index 1)"). No architectural change needed — purely a messaging improvement.

---

## Overall Assessment

**Completion Status:** Successful — all 10 task steps completed. Attached to Chrome CDP, listed tabs, took screenshots, captured snapshots, saved state, switched between tabs, tested --endpoint flag combinations, and closed the session cleanly.

**Success Rate:** 100% — all task objectives were achieved, though two commands required workarounds (switching to b4w.sh for flag-bearing commands, manually closing a stale session before attach).

**Issues Found:** 8

**Major Blockers:** None that prevented task completion. The b4w.ps1 flag parsing issue required switching to b4w.sh for snapshot -v and similar commands, which was a minor friction point. The stale-session error for attach required an extra close step.

**Most Confusing Aspects:** 1. The b4w.ps1 vs b4w.sh dichotomy — being told to use .ps1 everywhere but then having it fail on flag-bearing commands, with b4w.sh emitting contradictory 'use pwsh' warnings. 2. The backend version mismatch — the dev-mode instructions say the daemon auto-starts the locally-built JAR, but it ran an older installed version instead. 3. The snapshot flag parsing error ('Unknown command: snapshot-0') was misleading and didn't point to the real issue.

**Most Valuable Improvements:** 1. Fix the b4w.ps1 flag passthrough so users don't need to switch wrappers for different commands. 2. Make the daemon auto-detect a source checkout and build/run the local backend JAR instead of the installed bundle. 3. Add a --force flag to attach that auto-closes/replaces the existing unnamed session.

**Usability Rating:** 6/10

---

## How to Reproduce

### Common Setup

1. Clone the repository and `cd` to the repo root.
2. The CLI is invoked via `./b4w.ps1` which auto-builds from source when needed.
3. The backend server starts automatically in dev mode.
4. All commands from repo root: `./b4w.ps1 <command>`

### Per-Issue Reproduction Steps

#### Issue 1: b4w.ps1 cannot pass short flags on Linux/bash — requires b4w.sh workaround

./b4w.ps1 -- snapshot -v 0 --stdout  (produces PowerShell parameter ambiguity error); ./b4w.ps1 snapshot -v 0 --stdout  (parses as unknown command 'snapshot-0')

#### Issue 2: Snapshot flag parsing failure produces misleading error message

./b4w.ps1 snapshot -v 0 --stdout

#### Issue 3: Backend version mismatch between locally-built CLI and installed backend

Run ./b4w.sh status after the daemon auto-starts.

#### Issue 4: attach --cdp fails with existing unnamed session but error is recoverable

Have an existing unnamed (default) session active, then run: ./b4w.ps1 attach --cdp chrome

#### Issue 5: b4w.sh emits pwsh recommendation warning on every invocation on Linux

Run any command via ./b4w.sh on Linux: e.g., ./b4w.sh tab-list

#### Issue 6: state-save saves to current working directory with no --output-dir flag

./b4w.sh state-save my-backup.json

#### Issue 7: Snapshot output truncates long text content creating malformed escape sequences

./b4w.sh snapshot -v 0 --stdout on a page with substantial text content (e.g., httpbin.org/get JSON response).

#### Issue 8: tab-new output says 'Switched to tab 0' when creating first additional tab — confusing index

Have 1 tab (about:blank at index 0), then run: ./b4w.sh tab-new https://example.com

