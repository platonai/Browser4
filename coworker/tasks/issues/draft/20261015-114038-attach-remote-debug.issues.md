# Issues: attach-remote-debug

> **Source:** `20261015-114038-attach-remote-debug.issues.json` | **Date:** 20261015-114038 | **Mode:** dev

## Scenario Background

### Task

**Outcome: successful — all 8 steps completed**, with two workarounds (details below).

Setup: port 9222 had no listener, so I started Chrome myself (Chrome 155.0.8059.79) with `--remote-debugging-port=9222 --user-data-dir=<run>/chrome-profile` and opened two tabs (example.com, wikipedia.org). The first `b4w.ps1` command connected to the already-built dev backend (4.14.1-SNAPSHOT at `http://127.0.0.1:8282`) without a rebuild.

| Step | Result |
|---|---|
| 1. `attach --cdp chrome` | ✅ Attached to Google Chrome 155.0.8059.79 at `http://localhost:9222`, current page example.com |
| 2. `attach --endpoint <url> --cdp chrome` | ⚠️ Rejected ("An unnamed session already exists") → works with `-s <name>`, which the error itself suggests |
| 3. List sessions/tabs | ✅ `list` showed 2 active attached sessions + 2 stale ones; `tab-list` showed both tabs |
| 4. Screenshot | ✅ Saved and visually verified (correct page captured from the real Chrome) |
| 5. Snapshot | ✅ YAML accessibility tree + 11-line preview with refs |
| 6. Save state | ✅ Cookies (5, incl. Wikipedia's WMF cookies — proof it works across tabs) + localStorage origins |
| 7. Multi-tab | ✅ Switched 0→1→0, screenshotted both tabs, verified with `page-info` |
| 8. Close | ✅ "Disconnected from attached browser … The browser is still running."; Chrome verified alive afterwards (HTTP 200, keep-alive `about:blank`) |

**Workarounds required:** (a) the `--endpoint` step needed `-s <name>`; (b) closing a second session attached to the same browser silently bricked the first session, and the documented remedy (re-running `attach --cdp chrome`) did **not** recover it — only `close` followed by a fresh `attach` did. Two documented items also failed outright: the `disconnect` alias does not exist, and Chrome's tab-list/error behaviour around a dead driver/tab is misdiagnosed as "browser crashed".

### Execution Context

**Key Commands:**

**Major steps / decisions:**
1. Read `help` + `skills/browser4-cli/SKILL.md` + `references/attach.md` before touching the browser.
2. Found port 9222 closed, located Chrome, launched it with a run-scoped profile and two tabs (a pre-existing Chrome instance wasn't available; the fallbacks `--cdp msedge` / direct URL weren't needed).
3. Attached via channel name; the CLI auto-replaced a stale unnamed-session record (left over from earlier activity) and reported the real browser identity — good behaviour.
4. Ran screenshot → viewed it → confirmed it captured the real Chrome (CJK-locale example.com page).
5. For `--endpoint`: first attempt bare (as documented) failed on the unnamed-slot check; retried with `-s endpoint-attach` as the error suggested — succeeded.
6. Closed the `endpoint-attach` session → its `close` closed the example.com tab that the **default** session was also driving; the default session then failed every command with "browser is no longer reachable". Used `curl :9222` and the backend log (`doctor log` → `logs/pulsar.log`) to prove Chrome was healthy and the driver was the dead component.
7. Tried the documented recovery (re-attach): it printed success but kept the dead driver. Recovered with `close` → `attach`. Also probed `disconnect` (documented alias) → unknown command.
8. Finished with `close`; verified Chrome survived with a keep-alive `about:blank`, and that a bad-port attach fails cleanly (exit 1, no stale record).

**Environment notes:** snapshot/screenshot/state files default to `./.browser4-cli/snapshot/` under the CWD — the CLI's documented default (gitignored, so no repo pollution), not a path I chose; my explicit outputs went to the run's scratch dir. The backend log also shows another process exercising attach on port 19999 during the session — this backend/workspace was being shared concurrently; my own findings were all reproduced against my own Chrome on 9222. Evidence file: `.test-sessions/20261015T0901431699046Z/attach-evaluation-evidence.md`.

---

## Issues Found (7 issues)

### Issue 1: Re-attach cannot revive an unhealthy CDP-attached session — the documented recovery path keeps the dead driver

**Severity:** High
**Category:** Reliability

#### Reproduction

1) Start Chrome with --remote-debugging-port=9222. 2) ./b4w.ps1 attach --cdp chrome  -> works. 3) ./b4w.ps1 -s other attach --cdp chrome. 4) ./b4w.ps1 -s other close  (this closes the shared tab; the first session's bound driver dies). 5) ./b4w.ps1 page-info -> '[CDP_ERROR] ... is no longer reachable ... Re-attach to reconnect'. 6) Run the recommended recovery: ./b4w.ps1 attach --cdp chrome -> prints 'Attached to Google Chrome 155 ... Session opened: <same id>' plus 'WARN Attached, but could not read the page: [CDP_ERROR] ... no longer reachable'; every subsequent command still fails. 7) ./b4w.ps1 close && ./b4w.ps1 attach --cdp chrome -> works again with a new session id.

#### Expected Behavior

attach (as recommended by the error message) recreates the lost driver/tab connection and the session becomes usable again, or the command fails loudly instead of reporting success.

#### Actual Behavior

Re-attach reports success ('Attached to Google Chrome ...', 'Session opened: <same id>') and even rebinds the browser ('Session ... is explicitly re-attaching from browser port 9222 to port 9222; rebinding now' -> browser state Active,Connected), but the session keeps its stale bound driver ('Inconsistent driver/browser. Driver 23 state: INIT', 'Bound driver 23 is unhealthy, state: INIT'), so every page command keeps failing permanently until the session is deleted with close.

#### Root Cause Analysis

Two layers. Backend: in the attach path (PulsarSessionManager, ~line 552-585) the same-port fast path keeps an existing driver only when driver.healthy().isOK; when unhealthy it logs 'rebinding now' and binds a NEW PulsarBrowser but never clears session.agenticSession.boundDriver, so the health gate in cdpAttachedSessionUnavailable() (line 301) still sees driver state INIT/WebDriver-not-open. CLI: the attach command also prints success and a mere warning when the post-attach page read fails. Verified in logs/pulsar.log at 19:35:10 immediately after the re-attach (driver 23 INIT vs browser Active,Connected).

#### Code Pointer

`browser4-rest/src/main/kotlin/ai/platon/pulsar/rest/session/PulsarSessionManager.kt:552-585 (attach/bind; clear or rebuild the stale boundDriver when rebinding), :301 cdpAttachedSessionUnavailable(); cli/browser4-cli/src/main.rs (attach command flow)`

#### AI Suggested Improvement

- On re-attach, if the existing bound driver is unhealthy/INIT, unbind it and create a fresh driver for a page target (or fully reset browser+driver) instead of only replacing the browser wrapper.
- Alternatively, make attach detect an unhealthy existing session and perform close+attach internally.
- Do not print plain success when the post-attach page read fails; either fail the command or make the warning explicit that the session is still unusable.
- Add a regression test: 'driven tab closed externally -> re-attach -> snapshot succeeds'.
- Until fixed, update the error text to recommend `close` followed by `attach` (the only working recovery).

#### Human Review

- [ ] **ACCEPT**
- [ ] **ACCEPT with improvements**
- [ ] **DEFER**
- [ ] **WONTFIX**
- [ ] **REJECT**
- **Notes:**

---

### Issue 2: Documented `disconnect` alias does not exist — CLI rejects it as an unknown command

**Severity:** Medium
**Category:** Product

#### Reproduction

Follow skills/browser4-cli/references/attach.md, section 'Close vs Disconnect' ('use close or its alias disconnect', 'browser4-cli close  # or: browser4-cli disconnect'), then run: ./b4w.ps1 disconnect

#### Expected Behavior

Same behaviour as close: 'Disconnected from attached browser at http://localhost:9222. The browser is still running.', exit 0.

#### Actual Behavior

stderr: `Error: Unknown command: 'disconnect'`; stdout receives the entire ~200-line command reference; exit code 2; no 'Did you mean close?' suggestion. The command reference itself never mentions disconnect.

#### Root Cause Analysis

Half-implemented alias. main.rs:28616 dispatches `"close" | "disconnect"`, and disconnect is also special-cased in no_snapshot_commands() (main.rs:429) and should_ensure_server_running() (main.rs:25970) — but commands.rs has no CommandDef named 'disconnect', so commands_map().get("disconnect") returns None first and main.rs:28311 raises Unknown command before dispatch is ever reached.

#### Code Pointer

`cli/browser4-cli/src/commands.rs:773 (register a hidden 'disconnect' CommandDef next to 'close'), cli/browser4-cli/src/main.rs:28311`

#### AI Suggested Improvement

- Register `disconnect` as a hidden alias of `close` in the command registry (or add a general alias mechanism so registry and dispatch cannot drift).
- Add a test asserting every command name the dispatcher accepts resolves via commands_map().
- If the alias was deliberately dropped, remove it from attach.md and list it in that file's error table instead.

#### Human Review

- [ ] **ACCEPT**
- [ ] **ACCEPT with improvements**
- [ ] **DEFER**
- [ ] **WONTFIX**
- [ ] **REJECT**
- **Notes:**

---

### Issue 3: Closing one attached session tears down a sibling session attached to the same browser, contradicting the documented session independence

**Severity:** Medium
**Category:** Reliability

#### Reproduction

./b4w.ps1 attach --cdp chrome ; ./b4w.ps1 -s other attach --cdp chrome ; ./b4w.ps1 -s other close ; ./b4w.ps1 tab-list  (on the default session) -> CDP_ERROR 'no longer reachable' despite Chrome running.

#### Expected Behavior

Per attach.md 'Session-slot semantics': 'attaching the same browser under two names creates two independent sessions rather than aliasing one' and 'Closing one name never tears down a different name's session.'

#### Actual Behavior

Both sessions share one backend browser object for the attach context ('Closing browser with 1 drivers/devtools ... context/external/cx.ext.attach.port.9222' -> 'Browser is closed successfully | #11 | Inactive,Disconnected'), the close also closes the tab the sibling was driving, and the surviving session's driver dies ('Bound driver 23 is unhealthy, state: INIT'); every subsequent command on it fails until it is closed and re-attached.

#### Root Cause Analysis

Attach sessions are keyed to a shared external context dir named after the CDP endpoint (cx.ext.attach.port.<port>), so N sessions share one PulsarBrowser (and its browser-level connections). deleteSession() (PulsarSessionManager.kt:1540) closes that shared browser for all of them and detach closes the tabs drivers were bound to, with no ref-counting between sessions.

#### Code Pointer

`browser4-rest/src/main/kotlin/ai/platon/pulsar/rest/session/PulsarSessionManager.kt:1540 deleteSession() (shared-browser teardown); the external context naming 'cx.ext.attach.port.<port>' (runtime-generated; search the external/context group)`

#### AI Suggested Improvement

- Reference-count the shared attach context and only close the underlying browser when the last session detaches.
- Or give each -s session its own driver set/tab bindings over the shared browser process, so closing one session only closes its own drivers.
- If shared teardown is intended, refuse the second attach (or document that closing any session tears down the whole shared context) instead of promising independence.

#### Human Review

- [ ] **ACCEPT**
- [ ] **ACCEPT with improvements**
- [ ] **DEFER**
- [ ] **WONTFIX**
- [ ] **REJECT**
- **Notes:**

---

### Issue 4: Misleading error: 'browser ... is no longer reachable — it was closed or crashed' when only the tab/driver connection died

**Severity:** Medium
**Category:** Reliability

#### Reproduction

Attach with --cdp chrome, then let the tab the session is driving be closed (another attached session closing it, the user clicking X, or a page crash), then run any command, e.g. ./b4w.ps1 page-info ; compare with `curl http://localhost:9222/json/version` -> HTTP 200 and the backend log.

#### Expected Behavior

An accurate diagnosis such as: 'the tab this session was driving was closed; the browser is still running — select another tab or re-attach', without implying the browser process died.

#### Actual Behavior

[CDP_ERROR] 'The CDP-attached browser (Google Chrome 155.0.8059.79) for session '3f75a91a-...' is no longer reachable — it was closed or crashed. ... Re-attach to reconnect ...' while Chrome is demonstrably alive; the backend log itself records `browser healthy=CheckState(code=0, message=Browser is healthy), driver healthy=CheckState(code=503, message=WebDriver is not open - the connection to the backend tab is lost)`. The prescribed remedy (re-attach) then does not even work (see first issue), so users chase a non-existent browser crash.

#### Root Cause Analysis

cdpAttachedSessionUnavailable() (PulsarSessionManager.kt:301-319) returns one 'browser unreachable' message for any non-OK health check, and checkHealthy() marks the session unhealthy on driver failure (503 WebDriver not open) even though it separately logs the browser as healthy. The CLI surfaces this as a browser-liveness error.

#### Code Pointer

`browser4-rest/src/main/kotlin/ai/platon/pulsar/rest/session/PulsarSessionManager.kt:301-319 cdpAttachedSessionUnavailable(), :320+ checkHealthy()`

#### AI Suggested Improvement

- Split the two conditions (browser unreachable vs driver/tab connection lost) into distinct messages and exit semantics.
- Include the driver health state in --json output so scripts can react.
- Have the message suggest a recovery that actually works for this state (close + attach; or selecting another tab once driver re-binding is fixed).

#### Human Review

- [ ] **ACCEPT**
- [ ] **ACCEPT with improvements**
- [ ] **DEFER**
- [ ] **WONTFIX**
- [ ] **REJECT**
- **Notes:**

---

### Issue 5: `close` on a CDP-attached session silently closes the user's own tabs and accumulates keep-alive about:blank tabs

**Severity:** Medium
**Category:** UX

#### Reproduction

Start Chrome with two user tabs; ./b4w.ps1 attach --cdp chrome; ./b4w.ps1 tab-select 1 and screenshot; ./b4w.ps1 tab-select 0; ./b4w.ps1 close -> the tabs the session visited are closed (in my run both of my tabs disappeared across two session closes; Chrome survived with about:blank keep-alives, and repeated attach/close cycles left two about:blank tabs).

#### Expected Behavior

At minimum a runtime warning naming how many/which tabs will be closed before detaching; ideally a --keep-tabs option (extension-attached sessions already leave user tabs open), or leave tabs open by default for CDP attach.

#### Actual Behavior

Tabs are closed silently with no prompt or warning; the only disclosure is in attach.md ('Keep the page you were working on: close on an attached session closes the tab the session was driving'). Unsaved user state in those tabs is lost.

#### Root Cause Analysis

By design, close/detach closes 'the tab(s) the session held drivers for'; the CLI prints only the generic 'Disconnected ... The browser is still running.' and offers no opt-out; preserveAttachedBrowserWithBlankTab() adds a keep-alive about:blank on each close even when one already exists.

#### Code Pointer

`browser4-rest/src/main/kotlin/ai/platon/pulsar/rest/session/PulsarSessionManager.kt:1540 deleteSession(), :1617 preserveAttachedBrowserWithBlankTab(); cli/browser4-cli/src/main.rs (close output)`

#### AI Suggested Improvement

- Print the count and URLs of tabs that will be closed before detaching, or require --confirm for closes that would close more than the driven tab.
- Add a --keep-tabs flag for CDP-attached sessions.
- Skip creating a new keep-alive about:blank when one is already present.

#### Human Review

- [ ] **ACCEPT**
- [ ] **ACCEPT with improvements**
- [ ] **DEFER**
- [ ] **WONTFIX**
- [ ] **REJECT**
- **Notes:**

---

### Issue 6: `attach --endpoint <url> --cdp chrome` (documented pattern) is rejected while an unnamed session is active; the docs warn about this only for --extension

**Severity:** Low
**Category:** Documentation

#### Reproduction

With any active default (unnamed) session: ./b4w.ps1 attach --endpoint http://127.0.0.1:8282 --cdp chrome   (pattern 3 in references/attach.md, verbatim)

#### Expected Behavior

Works as documented; attach.md also calls a same-endpoint re-attach 'an idempotent re-attach to the same port keeps the existing driver', suggesting a no-op success.

#### Actual Behavior

'An unnamed session already exists: <id> ... Use -s <name> to create a named session instead ... Or run browser4-cli close ...' — the command is refused; it succeeds with -s <name>. The failure mode is only documented under 'Attach via Browser4 Extension'.

#### Root Cause Analysis

The unnamed-session occupancy check applies to --cdp attach too, but the documentation covers the restriction solely in the --extension section; alternatively, attach to the same endpoint should be treated as an idempotent re-attach for the unnamed slot.

#### Code Pointer

`cli/browser4-cli/src/main.rs (attach pre-flight 'An unnamed session already exists'); skills/browser4-cli/references/attach.md sections 4-5`

#### AI Suggested Improvement

- Extend the 'unnamed session slot is occupied' note to the --cdp section of attach.md (and to the Flags table).
- Ideally make attach to the same endpoint idempotent for the unnamed slot instead of failing.
- The error message itself is good and actionable; keep it, but align it with the docs.

#### Human Review

- [ ] **ACCEPT**
- [ ] **ACCEPT with improvements**
- [ ] **DEFER**
- [ ] **WONTFIX**
- [ ] **REJECT**
- **Notes:**

---

### Issue 7: Artifact paths are printed as raw markdown links with mixed path separators

**Severity:** Low
**Category:** UX

#### Reproduction

./b4w.ps1 screenshot -o .test-sessions/x.png -> '[Screenshot](D:\workspace\...\.test-sessions/x.png)'; ./b4w.ps1 snapshot -> '[Snapshot](D:\workspace\...\.browser4-cli\snapshot\snapshot-....yml)'

#### Expected Behavior

A plain path (or a real terminal hyperlink) with consistent separators, so it can be copied/pasted into the next command directly.

#### Actual Behavior

Markdown link syntax is emitted to a plain terminal, and the printed path mixes backslashes and forward slashes (D:\...\Browser4-4.14\.test-sessions/20261015T0901431699046Z/attach-wikipedia.png).

#### Root Cause Analysis

The CLI renders file results as markdown links by joining the canonical absolute prefix with the user-supplied relative path form, without normalizing separators or adapting to a terminal.

#### Code Pointer

`cli/browser4-cli/src/main.rs (screenshot/snapshot file-path rendering)`

#### AI Suggested Improvement

- Print plain absolute paths by default (keep markdown only behind an explicit format flag).
- Normalize path separators to the platform style before printing.

#### Human Review

- [ ] **ACCEPT**
- [ ] **ACCEPT with improvements**
- [ ] **DEFER**
- [ ] **WONTFIX**
- [ ] **REJECT**
- **Notes:**

---

## Overall Assessment

**Completion Status:** Successful — all 8 scenario steps completed (attach via channel, attach via --endpoint+--cdp, session/tab listing, screenshot, snapshot, state save, multi-tab switching with per-tab screenshots, session close). Two steps needed workarounds: --endpoint attach required -s <name>, and the session broken by a sibling session's close required close + fresh attach because the documented re-attach recovery did not work.

**Success Rate:** 90% — every step eventually succeeded, but two required undocumented workarounds and one documented command (disconnect) was unusable.

**Issues Found:** 7

**Major Blockers:** The documented recovery for a broken CDP-attached session (re-running attach --cdp) does not restore the session: it reports success while keeping the dead bound driver, and all commands keep failing. While in that state, the error misreports the still-running browser as 'closed or crashed'. A second attach session on the same browser also silently closed the first session's tab, bricking it. The documented `disconnect` alias does not exist.

**Most Confusing Aspects:** 1) After closing my second session, the first claimed the whole browser was 'closed or crashed' — I verified with curl that Chrome was alive and initially suspected my own setup. 2) The re-attach 'succeeded' with only a warning yet changed nothing, so the recommended fix made things look better while the session stayed dead. 3) The docs promise `browser4-cli disconnect` but the build rejects it as an unknown command. 4) Multi-session attach is documented as independent, yet closing one session destroyed the other's tab and driver. 5) `close` silently closed the user tabs I had opened (documented only in a doc paragraph, not at runtime).

**Most Valuable Improvements:** 1) Make attach rebuild an unhealthy bound driver so re-attach truly recovers the session (and stop printing success when the page read failed). 2) Distinguish 'browser unreachable' from 'driver/tab connection lost' in error messages and --json. 3) Reference-count the shared attach context (or per-session drivers) so closing one session cannot kill another's tab/driver. 4) Register `disconnect` as an alias of `close`, or remove it from the docs; add a registry-vs-dispatch consistency test. 5) Warn before closing user tabs and document the unnamed-slot rule for --cdp attach.

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

#### Issue 1: Re-attach cannot revive an unhealthy CDP-attached session — the documented recovery path keeps the dead driver

1) Start Chrome with --remote-debugging-port=9222. 2) ./b4w.ps1 attach --cdp chrome  -> works. 3) ./b4w.ps1 -s other attach --cdp chrome. 4) ./b4w.ps1 -s other close  (this closes the shared tab; the first session's bound driver dies). 5) ./b4w.ps1 page-info -> '[CDP_ERROR] ... is no longer reachable ... Re-attach to reconnect'. 6) Run the recommended recovery: ./b4w.ps1 attach --cdp chrome -> prints 'Attached to Google Chrome 155 ... Session opened: <same id>' plus 'WARN Attached, but could not read the page: [CDP_ERROR] ... no longer reachable'; every subsequent command still fails. 7) ./b4w.ps1 close && ./b4w.ps1 attach --cdp chrome -> works again with a new session id.

#### Issue 2: Documented `disconnect` alias does not exist — CLI rejects it as an unknown command

Follow skills/browser4-cli/references/attach.md, section 'Close vs Disconnect' ('use close or its alias disconnect', 'browser4-cli close  # or: browser4-cli disconnect'), then run: ./b4w.ps1 disconnect

#### Issue 3: Closing one attached session tears down a sibling session attached to the same browser, contradicting the documented session independence

./b4w.ps1 attach --cdp chrome ; ./b4w.ps1 -s other attach --cdp chrome ; ./b4w.ps1 -s other close ; ./b4w.ps1 tab-list  (on the default session) -> CDP_ERROR 'no longer reachable' despite Chrome running.

#### Issue 4: Misleading error: 'browser ... is no longer reachable — it was closed or crashed' when only the tab/driver connection died

Attach with --cdp chrome, then let the tab the session is driving be closed (another attached session closing it, the user clicking X, or a page crash), then run any command, e.g. ./b4w.ps1 page-info ; compare with `curl http://localhost:9222/json/version` -> HTTP 200 and the backend log.

#### Issue 5: `close` on a CDP-attached session silently closes the user's own tabs and accumulates keep-alive about:blank tabs

Start Chrome with two user tabs; ./b4w.ps1 attach --cdp chrome; ./b4w.ps1 tab-select 1 and screenshot; ./b4w.ps1 tab-select 0; ./b4w.ps1 close -> the tabs the session visited are closed (in my run both of my tabs disappeared across two session closes; Chrome survived with about:blank keep-alives, and repeated attach/close cycles left two about:blank tabs).

#### Issue 6: `attach --endpoint <url> --cdp chrome` (documented pattern) is rejected while an unnamed session is active; the docs warn about this only for --extension

With any active default (unnamed) session: ./b4w.ps1 attach --endpoint http://127.0.0.1:8282 --cdp chrome   (pattern 3 in references/attach.md, verbatim)

#### Issue 7: Artifact paths are printed as raw markdown links with mixed path separators

./b4w.ps1 screenshot -o .test-sessions/x.png -> '[Screenshot](D:\workspace\...\.test-sessions/x.png)'; ./b4w.ps1 snapshot -> '[Snapshot](D:\workspace\...\.browser4-cli\snapshot\snapshot-....yml)'

