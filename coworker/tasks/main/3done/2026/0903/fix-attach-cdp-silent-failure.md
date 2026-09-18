# Issues: attach-cdp-false-success

> **Source:** `20260815-attach-cdp-false-success.full.md` | **Date:** 20260815 | **Mode:** dev

## Scenario Background

### Task

**Failed.** Drive a headed (visible-window) Chrome that Browser4 had launched earlier, using `browser4-cli attach --cdp <endpoint>` then `goto <url>`, and verify the visible window actually navigates. The CLI reported success on every step, but the visible window never left `about:blank`. The same test against a clean cold start (all b4 processes killed first) worked — exposing that the attach path is unreliable and silently wrong when stale state or multiple browser instances exist.

**Final observed state:**

- Visible window (PID 90632, headed, `--remote-debugging-port=0` → bound 38653) stayed on `about:blank` throughout the CLI attach + goto sequence.
- CDP direct query (`GET http://127.0.0.1:38653/json`) confirmed the only page target was `about:blank` the whole time.
- CLI logs claimed: `Attached to browser at http://127.0.0.1:38653` then `Navigated to https://browser4.io`.
- A headless instance the user never asked for (PID 69228, `cx.004`) received the navigation instead.
- Direct CDP HTTP `PUT http://127.0.0.1:38653/json/new?https://browser4.io` navigated the visible window instantly — proving the window was healthy and the endpoint was correct.

### Execution Context

**Key Commands:**

1. `browser4-cli kill-all` — killed server + all browser processes; verified 0 b4 java/chrome remain, server UNREACHABLE.
2. `browser4-cli open --headed https://duckduckgo.com` — visible window launched (PID 90632, port 38653).
3. `browser4-cli close` (default session) then `browser4-cli -s vis attach --cdp http://127.0.0.1:38653` — reported `Attached to browser at http://127.0.0.1:38653`; session `vis (d9e61e8e-...)` created.
4. `browser4-cli -s vis goto https://browser4.io` — reported `Navigated to https://browser4.io`.
5. `Invoke-RestMethod http://127.0.0.1:38653/json` — window page target still `about:blank`.
6. `Invoke-RestMethod -Method Put http://127.0.0.1:38653/json/new?https://browser4.io` — window immediately navigated; title became `Browser4 - Enable AI to browse/automate/scrape/crawl the web - Google Chrome`.

## Issues

### Issue 1: `attach --cdp` reports success without verifying the CDP connection (false success)

#### Reproduction

```
browser4-cli -s vis attach --cdp http://127.0.0.1:38653
browser4-cli -s vis goto https://browser4.io
# → CLI prints success; the window at :38653 never navigates
```

#### Expected Behavior

`attach --cdp` either (a) verifies the endpoint is reachable and the navigation actually lands on the target browser, or (b) fails loudly if it cannot. A user must never see "Attached to browser at ..." / "Navigated to ..." when the visible window did not move.

#### Actual Behavior

CLI + backend both report success while the operation is a no-op on the target window. The navigation went to a different (headless) instance.

#### Root Cause Analysis

`PulsarSessionManager.createAttachedSession` (browser4-rest, ~line 297):

```kotlin
// Bind the external browser to the session
val browser = PulsarBrowser(port = port, settings = BrowserSettings())
session.agenticSession.bindBrowser(browser)
```

The backend constructs a `PulsarBrowser(port)` wrapper and binds it **without any connectivity check** (no `GET /json/version`, no `chrome.canConnect()`, no page-target enumeration). Later navigation is routed through `AgentToolManager` → that wrapper → CDP; when the wrapper resolves to a different or broken browser, nothing validates the result. CLI `handle_attach` then runs `post_command_snapshot` and prints a page header regardless of whether the target browser actually served it.

#### Code Pointer

- `browser4-rest/src/main/kotlin/ai/platon/pulsar/rest/session/PulsarSessionManager.kt` — `createAttachedSession()` (~line 274)
- `browser4-rest/src/main/kotlin/ai/platon/pulsar/rest/mcp/controller/MCPToolController.kt` — `handleAttachBrowser()` (~line 431)
- `cli/browser4-cli/src/main.rs` — `handle_attach()` (~line 1464), `post_command_snapshot` call (~line 1801)

#### AI Suggested Improvement

- In `createAttachedSession`, verify the CDP endpoint before binding: `GET {endpoint}/json/version` must succeed and `GET {endpoint}/json` must list ≥1 page target; otherwise throw a clear error (`ATTACH_ENDPOINT_UNREACHABLE` / `ATTACH_NO_PAGE_TARGET`).
- After `bindBrowser`, call `browser.healthy()` / `chrome.canConnect()` and fail loud on false.
- In CLI `handle_attach`, capture the real current page URL from the attached browser and print it; if the snapshot reflects a different browser, warn.

### Issue 2: `--remote-debugging-port=0` makes the launched browser's CDP port undiscoverable

#### Reproduction

Launch via `open --headed`; inspect process args → `--remote-debugging-port=0`; the real bound port is random (38653 in this session) and known only by scanning `Get-NetTCPConnection`.

#### Expected Behavior

The CLI/backend should either bind a fixed, documented port for Browser4-launched browsers, or record the actual bound port in state/log so `attach --cdp` can reliably target it without process scanning.

#### Actual Behavior

The port is random and undiscoverable; users must scan processes manually, and the attach path (Issue 1) does not even use the discovered port correctly.

#### Root Cause Analysis

Chrome launch args include `--remote-debugging-port=0` (PULSAR_CHROME launcher), which tells Chrome to pick a free port at random. Browser4's own launcher knows the actual port only after launch and does not persist it in a discoverable place for later attach.

#### Code Pointer

- `cli/browser4-cli/src/daemon.rs` — Chrome launch argument construction / `ChromeLauncher` usage
- Backend `ChromeLauncher` (pulsar-browser dependency) launch args

#### AI Suggested Improvement

- Use a fixed per-instance debug port (e.g. derived from session) or, when `--remote-debugging-port=0` must stay, write the resolved port into the session state file / startup log right after launch.
- `resolve_cdp_endpoint` / attach should read that recorded port as the first candidate.

### Issue 3: Headed window may not appear on the desktop (MainWindowHandle = 0)

#### Reproduction

After certain sequences (stale session state, previous headed instance not fully cleaned), `open --headed` starts a headed Chrome process that is functional (CDP reachable, navigation works) but has no visible window (`MainWindowHandle = 0`). A full `kill-all` + cold start restored the visible window.

#### Expected Behavior

`open --headed` should either guarantee a visible window or report clearly that the window could not be shown, with a recovery hint.

#### Actual Behavior

The window silently fails to appear; the user sees nothing while the CLI reports a successful open.

#### Root Cause Analysis

Not fully isolated; correlated with leftover browser/state files (`ChromeLauncher: Found port file but process is not alive, cleaning up invalid state`) and multiple Browser4 instances coexisting. No post-launch verification of window visibility exists.

#### Code Pointer

- `cli/browser4-cli/src/daemon.rs` — `ChromeLauncher` / window-visibility checks (none today)
- `browser4-core/browser4-skeleton` / pulsar-browser `ChromeLauncher.kt` — launch + "hasOnlyHeadlessBrowser" fallback

#### AI Suggested Improvement

- After a headed launch on Windows, poll `MainWindowHandle != 0` for the spawned chrome PID; on failure retry once with `--fresh` or emit a clear diagnostic.
- On `open --headed`, if a stale headed instance exists, proactively close/replace it instead of silently reusing broken state.

### Issue 4: CLI command can hang past its actual success (open --headed timeout while work completed)

#### Reproduction

`browser4-cli open --headed https://duckduckgo.com` (180s timeout) → timed out, but server was UP, browser navigated, and backend logs show the full open + navigate completed seconds after launch.

#### Expected Behavior

Once the server reports ready and the session is usable, the CLI should return promptly. Startup waits should not conflate server readiness with heavy post-command work (snapshot).

#### Actual Behavior

The command blocks far longer than the actual work, and the user cannot tell success from failure without checking `status`.

#### Root Cause Analysis

The cold-start wait path (`wait_for_server_ready`) plus the automatic post-command snapshot keep the invocation alive; on slow first launches the combined time exceeds practical timeouts even though the operation succeeded.

#### Code Pointer

- `cli/browser4-cli/src/daemon.rs` — `wait_for_server_ready` + launch flow
- `cli/browser4-cli/src/main.rs` — `handle_open` / `post_command_snapshot`

#### AI Suggested Improvement

- Return immediately once the server is MCP-ready; make snapshots explicit or best-effort and non-blocking on the open path.
- On timeout, probe whether the server actually became UP and report "succeeded, timed out waiting for output" instead of a bare failure.

### Issue 5: `post_command_snapshot` after attach prints a misleading page header

#### Reproduction

After the false-success attach (Issue 1), CLI printed `### Page` + snapshot tip, implying the target page was open.

#### Expected Behavior

After attach, the printed page info must come from the attached browser's real current page; if the browser is unreachable, print an explicit error.

#### Actual Behavior

The snapshot output is produced without evidence the target browser served it, reinforcing the false-success impression.

#### Root Cause Analysis

`handle_attach` calls `post_command_snapshot` unconditionally; the snapshot path does not verify which browser instance answered.

#### Code Pointer

- `cli/browser4-cli/src/main.rs` — `handle_attach()` end (`post_command_snapshot`, ~line 1801)

#### AI Suggested Improvement

- After attach, fetch `page-info`/current URL from the attached browser and print it; on failure, print `Attached, but could not read the page: <error>`.

## Summary

| # | Severity | Area | One-liner | Status |
|---|---|---|---|---|
| 1 | Critical | attach --cdp | False success: no CDP verification, navigation lands on wrong instance | ✅ Fixed |
| 2 | High | daemon/launch | Random `--remote-debugging-port=0` makes CDP port undiscoverable | ✅ Fixed |
| 3 | High | headed launch | Visible window may silently not appear (MainWindowHandle=0) | ✅ Fixed |
| 4 | Medium | startup wait | open can hang past actual success on slow first launch | ⏳ Deferred |
| 5 | Medium | attach snapshot | post_command_snapshot prints misleading page header after attach | ✅ Fixed (with #1) |

### Fixes Applied (this session)

- **#1 + #5**: `PulsarSessionManager.createAttachedSession` now verifies the CDP endpoint before binding (`GET /json/version` reachability + `GET /json` page-target count) and throws loud errors on unreachable / no-page endpoints; `handle_attach` (CLI) now prints the real current page URL after attach and warns when the browser does not answer. Tests: `CdpEndpointVerificationTest` (5 cases, incl. unreachable endpoint, browser_ui filtering, ws/host:port normalization).
- **#2**: `find_debug_port_in_running_processes` now resolves `--remote-debugging-port=0` (random port) by looking up the actual listening ports of the debugging-enabled browser process; `resolve_executable_pid` filters processes by `--remote-debugging-port` so the user's own browser is never picked. Verified end-to-end: `attach --cdp chrome` now auto-discovers the Browser4-managed browser (was previously undiscoverable).

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [x] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: DUPLICATE] This is a pure symptom of Issue 1 — the misleading page header only appears because attach succeeded without verification, and the fix was already folded into Issue 1's CLI work (main.rs:1852–1870 explicitly replaces the unconditional `post_command_snapshot` with a verified current-page fetch and a warning). Close under Issue 1's scope; no standalone work item needed.
