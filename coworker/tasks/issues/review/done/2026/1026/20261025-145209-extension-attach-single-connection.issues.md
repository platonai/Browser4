# Issues: extension-attach-single-connection

> **Source:** `20261025-145209-extension-attach-single-connection.issues.json` | **Date:** 20261025-145209 | **Mode:** dev

## Scenario Background

### Task

**Verdict: PASS — the `attach --extension` footprint is correct.** Edge was already running; the extension attach created exactly one session, opened no browser, exposed no CDP port, refused a second unnamed attach, and `disconnect` left Edge untouched.

| Check | Result | Evidence |
|---|---|---|
| Version / server | `browser4-cli 4.14.4`; `Server URL: http://127.0.0.1:8282` | `status` |
| Baseline sessions | none | `list` |
| Baseline footprint | `Browser4Roots=0`, `ChromeRoots=1`, `EdgeRoots=1` (Edge root PID 15744); no listeners on 9222/8088; `TNC_9222=False` | `footprint_measure.ps1` |
| `attach --extension msedge` | `Connected browser: Microsoft Edge 155.0.0.0`, `Session ready: ce6670dd-2896-4d06-b2bc-d33db38ab77b`, ~21 s total (14.7 s backend boot) | exit 0 |
| `list` after attach | exactly one row, `Display: Attached`, `Connection: Extension (Microsoft Edge 155.0.0.0)` | `list` |
| `tab-list` | only the session's own tab (`about:blank`, GUID `chrome:1439683073`), not the user's tab set | `tab-list` |
| Footprint after attach | identical: 0/1/1, same Edge PID 15744, no 9222/8088 listeners, `TNC_9222=False` | `C_footprint_after_attach.txt` |
| Repeat unnamed attach | exit **1**, `An unnamed session already exists: ce6670dd-…`, no `Session ready` | `D_attach2.log` |
| After refusal | same single session id; footprint unchanged 0/1/1, same PID | `D_list_after_attach2.txt`, `D_footprint_after_attach2.txt` |
| `disconnect` | `Disconnected from Browser4 Chrome Extension…`, exit 0; `list` empty; footprint still 0/1/1, same Edge PID; no 9222 | `E_*` files |
| Cleanup | `close` → no session remains; final `list` empty; final footprint 0/1/1, `TNC_9222=False` | `F_*` files |
| Leftovers | backend log shows the connect/automation tab (`1439683073`) detached and the relay closed; no connection remains. The dev backend itself remains running on 8282 (outside the scenario footprint). | `doctor log pulsar` |

No browser root process, no new session id and no listening `:9222` appeared at any point.

### Execution Context

Commands/steps used:

1. Read `./b4w.ps1 help`, `skills/browser4-cli/SKILL.md` (full), `references/attach.md`, `references/browser-modes.md`, and `AGENTS.md`; used `--help attach`, `--help close`, `--help disconnect`, `--help browsers`.
2. Recorded baseline with `./b4w.ps1 --version`, `status`, `list`, and the scenario's exact PowerShell helper (CIM process counts + `Get-NetTCPConnection` on 9222/8088 + `Test-NetConnection 127.0.0.1:9222`).
3. Ran `./b4w.ps1 attach --extension msedge`; captured the ordered output and timing.
4. Ran `list` and `tab-list`, then re-measured the footprint/ports.
5. Ran the second unnamed `attach --extension msedge`, captured exit code 1 and the message; re-ran `list` and footprint.
6. Ran `disconnect`, then `list`, footprint/ports; inspected the backend log (`...

(truncated — see 20261025-145209-extension-attach-single-connection.issues.json for the complete execution trace)

---

## Issues Found (6 issues)

### Issue 1: Normal extension disconnect logs an internal closeTab CancellationException and cannot confirm the tab closed

**Severity:** Medium
**Category:** Reliability

#### Reproduction

1) ./b4w.ps1 attach --extension msedge
2) ./b4w.ps1 disconnect
3) ./b4w.ps1 doctor log pulsar, then search for 'closeTab' near the disconnect time.

#### Expected Behavior

Disconnect closes the session tab cleanly. A debugger detach that is the consequence of the tab being removed should be treated as success, with no exception stack trace, and the tab outcome should be confirmed before the CLI reports success.

#### Actual Behavior

The CLI prints success and exits 0, but the backend log at 22:45:04 shows: WARN ai.platon.pulsar.chrome.PulsarBrowser - WebDriverException: closeTab ... Caused by: ChromeServiceException: closeTab timed out or failed: get ... Caused by: CancellationException at ExtensionChromeService.handleEvent, triggered by 'Debugger detached from tab 1439683073'. The detach (caused by the tab removal) cancels the pending chrome.tabs.remove request, and the exception is swallowed into a WARN while the session is reported as successfully deleted.

#### Root Cause Analysis

ExtensionChromeService cancels all pending requests when chrome.debugger.onDetach fires. closeTab's own chrome.tabs.remove causes that detach before its response arrives, so awaitAndCleanup's future is cancelled and reported as a close failure. The caller (PulsarBrowser.doClose) only logs the exception, and no post-close check verifies the tab is actually gone, so a genuine close failure would also be silent.

#### Code Pointer

`Dependency pulsar-browser-4.11.25.jar (not this checkout): ai.platon.pulsar.chrome.protocol.transport.ExtensionChromeService.closeTab (ExtensionChromeService.kt:127) and awaitAndCleanup (:310-313). Backend call site: browser4-rest/src/main/kotlin/ai/platon/pulsar/rest/session/PulsarSessionManager.kt:1953 (closeSession).`

#### AI Suggested Improvement

- Treat a CancellationException/detach that arrives while closeTab is awaiting chrome.tabs.remove as success when the target tab id is no longer present; log at DEBUG instead of WARN with a stack trace.
- If the tab is still present, surface a real failure to the CLI instead of exiting 0.
- Return the tab outcome (closed / still open / unknown) in the close/disconnect response so the CLI can report it.
- Add a regression test for the onDetach-vs-tabs.remove race on extension-session close.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 2: close is described as 'Close the browser'; disconnect text says 'Browser4 Chrome Extension' and that tabs remain, which is misleading for an Edge attach

**Severity:** Low
**Category:** Documentation

#### Reproduction

./b4w.ps1 --help close
./b4w.ps1 attach --extension msedge
./b4w.ps1 disconnect

#### Expected Behavior

Help and messages should describe detach semantics for attached sessions, name the actual browser family (Microsoft Edge), and distinguish the session-driven tab from the user's own tabs.

#### Actual Behavior

--help close prints only 'Close the browser' (the main help row is the same), which sounds like it will terminate Edge. After a successful Edge attach, disconnect prints 'Disconnected from Browser4 Chrome Extension. Your browser tabs and the extension remain active.' even though the session is 'Extension (Microsoft Edge 155.0.0.0)' and the backend attempted chrome.tabs.remove on the session tab (attach.md says the driven tab is removed). Only `disconnect --help` mentions detach semantics.

#### Root Cause Analysis

The close command description is a legacy generic string, and the disconnect success message is hardcoded and family-agnostic. The wording does not separate the user's tabs from the tab the session drove, and the documentation's close table and message use different mental models.

#### Code Pointer

`cli/browser4-cli/src/commands.rs:818 (close description); cli/browser4-cli/src/main.rs:4040 (disconnect message); skills/browser4-cli/references/attach.md:417 (documented close semantics).`

#### AI Suggested Improvement

- Align the close description with the disconnect alias, e.g. 'Close the current session (attached sessions detach; the external browser keeps running)'.
- Make the disconnect message name the actual browser/extension family and state the tab outcome from the backend report.
- Use 'Browser4 Extension' instead of 'Chrome Extension' when the connected family is Edge.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 3: No-session close/disconnect prints an error-styled 'Session required' block with irrelevant open/goto advice and a .exe hint, but exits 0

**Severity:** Low
**Category:** UX

#### Reproduction

After ./b4w.ps1 disconnect (or with no session at all), run:
./b4w.ps1 close
./b4w.ps1 disconnect

#### Expected Behavior

A neutral message such as 'No attached session to disconnect', exit 0, and optionally an attach --extension hint.

#### Actual Behavior

Prints '🔐 Session required', suggests `browser4-cli.exe open <url>`, `goto <url>` and `list`, and exits 0. The block looks like a failure, the suggestions do not apply to close/disconnect, and browser4-cli.exe is not the dev-wrapper invocation the user typed.

#### Root Cause Analysis

handle_close falls back to the generic no_active_session_message() guard when no session is stored, then returns Ok(()) so the process exits 0. cli_binary_name() uses argv[0], which under the wrapper is browser4-cli.exe, so the guidance bypasses ./b4w.ps1 without saying so.

#### Code Pointer

`cli/browser4-cli/src/main.rs:4010-4023 (handle_close); cli/browser4-cli/src/main.rs:697-724 (cli_binary_name / no_active_session_message).`

#### AI Suggested Improvement

- Give close/disconnect their own no-session message and skip the generic session-required guidance.
- When running under ./b4w.ps1 or ./b4w.sh, print the wrapper invocation (have the wrapper export it) instead of browser4-cli.exe.
- Document the intended exit code for a no-op close (0 is reasonable, but the output must not look like an error).

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 4: attach --extension prints no page/tab identity despite attach.md promising it, and after disconnect there is no CLI way to see what remained

**Severity:** Low
**Category:** Documentation

#### Reproduction

./b4w.ps1 attach --extension msedge
Compare output with skills/browser4-cli/references/attach.md ('After a successful attach, the CLI prints the target browser's real current page URL').
./b4w.ps1 tab-list
./b4w.ps1 disconnect
./b4w.ps1 tab-list

#### Expected Behavior

A successful attach should print the target page/tab identity (as documented), and the user should be able to determine which tab was created and whether disconnect closed or left it.

#### Actual Behavior

Success output ends at 'Connected browser: Microsoft Edge 155.0.0.0' and 'Session ready: <id>' with no URL or tab GUID. While attached, tab-list shows only the session's own about:blank tab; after disconnect the CLI has no session and cannot show the tab's fate. The scenario's 'record what remains' step therefore cannot be answered from the CLI.

#### Root Cause Analysis

The extension-attach success branch prints only the browser name and session id; the page-URL output exists only on the CDP path. The disconnect message is a fixed sentence rather than a report of the tab outcome.

#### Code Pointer

`cli/browser4-cli/src/main.rs:2829-2872 (extension attach success path); skills/browser4-cli/references/attach.md ('Endpoint verification').`

#### AI Suggested Improvement

- Print the bound tab's GUID and URL after 'Session ready' for extension attaches.
- Include the tab close outcome (closed / left open, with GUID) in disconnect output and --json.
- Document the extension attach's new-tab scope next to the attach help text.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 5: attach --extension is not surfaced in the top-level Quick Start or Common workflows

**Severity:** Low
**Category:** Discoverability

#### Reproduction

./b4w.ps1 help and search for 'attach'.

#### Expected Behavior

The 'use my own browser' workflow should be discoverable from the top-level help, e.g. under Common workflows.

#### Actual Behavior

attach appears only once in the entire top-level help, in the [Browsers] command table. The Quick Start box and Common workflows omit it, so a first-time user must already know the term 'attach' or browse the Browsers category. --help attach and references/attach.md are thorough once found.

#### Root Cause Analysis

The top-level help hero sections list only high-frequency navigation/extraction commands; the attach workflow is only in the category table and deep-dive docs.

#### Code Pointer

`cli/browser4-cli/src/commands.rs (command registry / help metadata) and the top-level help renderer that builds the Quick Start and Common workflows sections.`

#### AI Suggested Improvement

- Add a 'Use my own browser' line to Common workflows: attach --extension <channel>.
- Mention the Web Store extension prerequisite in that line.
- Consider listing attach in the Quick Start box since it is a headline feature.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 6: Repeat-attach refusal suggests --cdp chrome, a different transport from the failed --extension attach

**Severity:** Low
**Category:** Discoverability

#### Reproduction

With the unnamed Edge extension session active, run ./b4w.ps1 attach --extension msedge again.

#### Expected Behavior

The guidance should repeat the same transport, e.g. browser4-cli -s myname attach --extension msedge.

#### Actual Behavior

The message's example is 'browser4-cli -s myname attach --cdp chrome'; only a terse trailing 'Hint: browser4-cli -s <name> attach --extension' names the right transport. The suggested CDP path requires a debug port that extension users deliberately avoided.

#### Root Cause Analysis

check_unnamed_slot_free hardcodes a generic example and does not know which transport (extension vs cdp) and channel were attempted.

#### Code Pointer

`cli/browser4-cli/src/main.rs:796 (check_unnamed_slot_free).`

#### AI Suggested Improvement

- Build the example from the attempted transport and channel, e.g. `-s myname attach --extension msedge`.
- Keep one consistent hint instead of a generic example plus a second appended hint.

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

**Completion Status:** Successful — all six acceptance criteria for attach --extension footprint passed. Edge was driven in place with no new browser, no CDP port and no second session.

**Success Rate:** 100% — 13/13 scenario steps executed, all required assertions passed.

**Issues Found:** 6

**Most Confusing Aspects:** close vs disconnect wording ('Close the browser' vs detach), the no-session close printing an error-looking 'Session required' block, no page/tab identity in attach success output, no way to inspect leftover tabs after disconnect, and repeat-attach guidance switching to --cdp.

**Most Valuable Improvements:** Report the actual browser and tab outcome in close/disconnect; treat the onDetach-during-closeTab race as success and verify the tab is gone; add attach --extension to Common workflows; give close/disconnect a tailored no-session message that reflects the dev wrapper invocation.

**Usability Rating:** 8/10

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

#### Issue 1: Normal extension disconnect logs an internal closeTab CancellationException and cannot confirm the tab closed

1) ./b4w.ps1 attach --extension msedge
2) ./b4w.ps1 disconnect
3) ./b4w.ps1 doctor log pulsar, then search for 'closeTab' near the disconnect time.

#### Issue 2: close is described as 'Close the browser'; disconnect text says 'Browser4 Chrome Extension' and that tabs remain, which is misleading for an Edge attach

./b4w.ps1 --help close
./b4w.ps1 attach --extension msedge
./b4w.ps1 disconnect

#### Issue 3: No-session close/disconnect prints an error-styled 'Session required' block with irrelevant open/goto advice and a .exe hint, but exits 0

After ./b4w.ps1 disconnect (or with no session at all), run:
./b4w.ps1 close
./b4w.ps1 disconnect

#### Issue 4: attach --extension prints no page/tab identity despite attach.md promising it, and after disconnect there is no CLI way to see what remained

./b4w.ps1 attach --extension msedge
Compare output with skills/browser4-cli/references/attach.md ('After a successful attach, the CLI prints the target browser's real current page URL').
./b4w.ps1 tab-list
./b4w.ps1 disconnect
./b4w.ps1 tab-list

#### Issue 5: attach --extension is not surfaced in the top-level Quick Start or Common workflows

./b4w.ps1 help and search for 'attach'.

#### Issue 6: Repeat-attach refusal suggests --cdp chrome, a different transport from the failed --extension attach

With the unnamed Edge extension session active, run ./b4w.ps1 attach --extension msedge again.

