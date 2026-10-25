# Issues: backend-browser-footprint

> **Source:** `20261025-144304-backend-browser-footprint.issues.json` | **Date:** 20261025-144304 | **Mode:** dev

## Scenario Background

### Task

The **required** scenario checks (A–C, E) all passed on this build:

- **Clean baseline:** `BASE_B4 = 0` Browser4-managed Chrome roots; no listeners on `:8088`, `:18182`, or `:8282–:8290`.
- **Default backend start:** `open --headless https://example.com` started the dev backend on **:8282** (PID **243396**). Footprint was exactly `BASE_B4 + 1` (**1**), i.e. only the session's own headless Chrome (PID 227184). No MCP-over-HTTP endpoint was created: `:8088` had no listener, `Test-NetConnection 127.0.0.1 -Port 8088` returned **False**, and the backend log (`%TEMP%\browser4\browser4-cli\tmp\cli\browser4-server-jar-port8282-20261025T142728.420Z.log`) contained **neither** `Creating MCP HTTP server session` nor `MCP HTTP server listening`.
- **Session identity:** `page-info` reported `https://example.com/` — the counted browser is the session's browser.
- **Backend reuse:** after `list` and again after `page-info`, the port owner of `:8282` was still **243396**, no port in `8282–8290` was added, and `Browser4Roots` stayed **1**. The managed-process registry (`~/.browser4/workspaces/Browser4-4.14-feat-06d043d8/cli-managed-processes.json`) recorded `pid: 243396` for `:8282` — exactly the port owner.
- **Final cleanup:** `close-all` + `stop` left `Browser4Roots = 0` and no scenario-port listeners.

The **optional** opt-in check (D) was only partially successful: `:8088` did listen (PID 160260, log line `MCP HTTP server listening on http://0.0.0.0:8088/mcp` at 22:29:57), but the browser footprint grew by **1**, not the expected 2, because the opt-in backend's CLI session attached to the **first backend's** Chrome instead of launching its own; `stop` then left the MCP-created Chrome orphaned, requiring a manual `taskkill /T /F`.

### Execution Context

**Key Commands:**

1. `./b4w.ps1 help`, read `skills/browser4-cli/SKILL.md`.
2. Baseline: `./b4w.ps1 close-all` → `./b4w.ps1 stop`; confirmed footprint 0 / no listeners.
3. `./b4w.ps1 open --headless https://example.com` → server on `:8282`.
4. `./b4w.ps1 status` (Server URL `http://127.0.0.1:8282`), `page-info`, `list`; footprint + port checks after each; registry inspection.
5. MCP-off checks: `Get-NetTCPConnection -LocalPort 8088`, `Test-NetConnection`, grep of the newest backend log.
6. Opt-in test with `BROWSER4_CLI_STATE_DIR=$TEMP/b4-mcp-optin` and `BROWSER4_SERVER_OPTS='-Dmcp.http.enabled=true'`: `open --headless` → second backend on `:8283`, `:8088` listening; footprint/port/log/tab checks; `stop`; footprint check.
7. Root-cause isolation: controlled `open` + `stop` (no `close-all`) on an isolated default-off backend; then a two-backend test (main + isolated) to verify browser reuse, shared session UUID, and the effect of closing the isolated session.

Key decisions: used deltas (`BASE_B4 + n`) rather than absolute browser counts because a user Chrome and Edge were already running; counted only Chrome roots with `PULSAR_CHROME` and no `--type=`; treated logs as authoritative for MCP startup. Workarounds required: manual `taskkill /T /F` of orphaned Browser4 Chrome trees (PIDs 249556 and 251688) and of a lingering AOT-training JVM that held a temp state dir.

---

## Issues Found (4 issues)

### Issue 1: Dev-mode `stop` leaves the workspace's Browser4 Chrome running

**Severity:** High
**Category:** Reliability

#### Reproduction

cd D:/workspace/Browser4/Browser4-4.14-feat
./b4w.ps1 open --headless https://example.com
# Get-BrowserFootprint -> Browser4Roots = 1 (e.g. chrome PID 251688)
./b4w.ps1 stop
# Get-BrowserFootprint -> Browser4Roots = 1 and Get-Process -Id 251688 -> ALIVE
# Same with the MCP opt-in backend: chrome PID 249556 survived `stop` of java PID 160260.

#### Expected Behavior

`stop` shuts the backend down and closes the browsers that backend launched, so Browser4Roots returns to the pre-open value (the scenario's step 11/12 cleanup expects exactly this).

#### Actual Behavior

The Java backend process is stopped, but the Chrome root it launched (and all renderer children) keeps running; the browser's parent PID is the dead backend. Only `close-all` before `stop` avoids the leak. Two orphaned Chrome trees (PIDs 249556, 251688) had to be killed manually with taskkill /T /F.

#### Root Cause Analysis

In development mode handle_stop() calls stop_workspace_servers_forcibly() (main.rs:27098), which calls shutdown_managed_server_processes_on_port(force=true, ...) (managed_processes.rs:976) and therefore force_stop(pid) = `Stop-Process -Force` / SIGKILL (managed_processes.rs:1420-1442). The JVM is terminated without running shutdown hooks, and stop_workspace_servers_forcibly explicitly 'deliberately skips the global browser sweep' (managed_processes.rs:960-962). The production path (stop_browser4_server_forcibly) does notify close-all and kill_all_browsers; dev mode has no equivalent for the stopped backend's own browsers. The browser is identifiable via the session's <contextDir>/launcher.pid (BrowserProcessReaper).

#### Code Pointer

`cli/browser4-cli/src/managed_processes.rs:stop_workspace_servers_forcibly() (line 963) and cli/browser4-cli/src/main.rs:handle_stop() (line 27084)`

#### AI Suggested Improvement

- In the dev stop path, close the sessions/browsers belonging to each managed port before force-killing the JVM (reuse notify_close_all_sessions_before_force_stop) or terminate the Chrome trees whose parent is the backend being stopped.
- Reuse BrowserProcessReaper.terminate(contextDir) / kill_all_browsers scoped to the workspace registry instead of skipping the browser sweep entirely.
- Add an integration test: open -> stop (without close-all) -> assert zero PULSAR_CHROME roots and exit code 0.
- Make the help text say `close-all` is required before `stop` if browsers must be closed (see issue 3).

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 2: Two backends on one machine share the DEFAULT browser and session UUID despite an isolated state dir

**Severity:** High
**Category:** Product

#### Reproduction

cd D:/workspace/Browser4/Browser4-4.14-feat
./b4w.ps1 open --headless https://example.com                    # backend A on :8282
BROWSER4_CLI_STATE_DIR=$(cygpath -w "$TEMP/b4-isolation") ./b4w.ps1 open --headless https://example.com   # backend B on :8283
# Get-BrowserFootprint -> Browser4Roots stays 1, not 2
# Backend B log: 'Found valid existing Chrome process on port: 20326' / 'Reusing existing Chrome process' / 'Found existing Chrome process on port: 20326 for userDataDir: ...\browser4-pereg\context\groups\default\PULSAR_CHROME\cx.001'
# Both backends report the same session UUID d7ab81a7-...; backend A's tab-list goes from 1 to 2 tabs and the extra tab survives backend B's close-all.

#### Expected Behavior

An isolated state directory yields an isolated backend: its own browser, its own session identity and no effect on the first workspace. In the MCP opt-in variant the footprint should grow by two (own session browser + MCP browser) as the scenario specifies.

#### Actual Behavior

Backend B attached to backend A's Chrome (A's PID 227260 owns the reused CDP port 20326; A's java process is its parent), added a tab to A's browser, and reused A's DEFAULT session UUID. The MCP opt-in run likewise increased Browser4Roots by only 1 (main browser + MCP browser) instead of the expected 2, because B's session browser was never launched. development-mode.md claims 'the default browser.profile.mode=DEFAULT profile is per workspace here' and 'Session browser profiles are never shared' — the observed behaviour contradicts both.

#### Root Cause Analysis

The DEFAULT-profile browser context directory is built on a per-user shared base, %TEMP%\browser4-<user>\context\groups\<group>\PULSAR_CHROME\cx.<n> (AppPaths.CONTEXT_BASE_DIR / AppPaths.getContextBaseDir; see PulsarSessionManager.kt:1766 and SessionProfileReaper.kt:144), which is not scoped by -Dapp.data.dir or BROWSER4_CLI_STATE_DIR. ChromeLauncher discovers the already-running Chrome bound to that userDataDir/CDP port and reuses it without checking which backend launched it. In addition, the display-name -> UUID registry is hardcoded to ~/.browser4/session-registry.json (CommandServiceConfig.kt:19), so both backends resolve DEFAULT to the same UUID.

#### Code Pointer

`browser4-rest/src/main/kotlin/ai/platon/pulsar/rest/session/PulsarSessionManager.kt (profile/context-dir resolution, ~line 1700-1766); browser4-rest/src/main/kotlin/ai/platon/pulsar/rest/config/CommandServiceConfig.kt:19; ChromeLauncher reuse lives in the ai.platon.pulsar.chrome dependency ('Found existing Chrome process ... for userDataDir')`

#### AI Suggested Improvement

- Scope the browser context base directory to the workspace/app-data root (or honor BROWSER4_CLI_STATE_DIR) so context/groups is per backend.
- Before reusing an existing Chrome, verify ownership (a backend/workspace marker in the context dir or the launcher.pid's parent) and refuse to attach to another backend's browser.
- Scope the session display-name registry to the workspace or include the workspace in the key.
- Update development-mode.md and add a regression test that starts two backends concurrently and asserts two distinct browsers, distinct session IDs and no tab leakage.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 3: `stop` help says 'Gracefully stop' but dev mode force-kills the backend

**Severity:** Low
**Category:** Documentation

#### Reproduction

./b4w.ps1 help   # lists: stop  Gracefully stop the Browser4 server
./b4w.ps1 open --headless https://example.com
./b4w.ps1 stop   # backend dies via Stop-Process -Force, browser left running

#### Expected Behavior

The documented behaviour ('gracefully') matches what happens: sessions are notified, browsers close, the server exits cleanly.

#### Actual Behavior

In development mode handle_stop() calls stop_workspace_servers_forcibly(), which uses force=true (Stop-Process -Force) and skips the browser sweep; no graceful close-all notification is sent, and browsers can be left orphaned. Only the production path performs the graceful notification.

#### Root Cause Analysis

The `stop` description was not updated when dev-mode stop was changed to the force-only workspace-scoped path (commit 3d6d983294 'isolate development-mode backends per checkout').

#### Code Pointer

`cli/browser4-cli/src/main.rs:handle_stop() (line 27084) and the `stop` help entry in the command reference`

#### AI Suggested Improvement

- Change the description to something like: 'Stop the Browser4 server (development mode force-stops this checkout's backend; run close-all first to close its browsers)'.
- Or make dev-mode stop perform the same graceful close-all notification before the forced kill.
- Document the close-all-before-stop requirement in the sessions/development-mode docs.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 4: Backend server log location is inconsistently documented and `doctor log` needs a running server

**Severity:** Low
**Category:** Discoverability

#### Reproduction

Read skills/browser4-cli/SKILL.md line 340 ('the backend writes its logs inside the runtime bundle') and skills/browser4-cli/references/development-mode.md line 23 ('logs' under <state dir>/app-data/). The actual server log is %TEMP%\browser4\browser4-cli\tmp\cli\browser4-server-jar-port8282-*.log; <state dir>/app-data/logs contains only agent/. Then run ./b4w.ps1 doctor log with the backend stopped.

#### Expected Behavior

One accurate documented location for the dev backend log, or `doctor log` lists the local launcher logs (and prints the directory) even when the backend is down.

#### Actual Behavior

Two docs disagree and neither names the directory used in this build; `doctor log` prints '(server not running or log listing unavailable: HTTP request failed ... /api/doctor/log-files)' and exits 0 with no path hint, so a first-time user cannot find the log after a failed start.

#### Root Cause Analysis

The launcher/Spring output is written by the CLI/daemon to %TEMP%\browser4\browser4-cli\tmp\cli\, while SKILL.md and development-mode.md describe the backend's own logging locations. `doctor log` is implemented purely as a backend HTTP call (GET /api/doctor/log-files) with no local fallback.

#### Code Pointer

`skills/browser4-cli/SKILL.md line 340; skills/browser4-cli/references/development-mode.md line 23; CLI `doctor log` handler in cli/browser4-cli/src/main.rs (fetches /api/doctor/log-files)`

#### AI Suggested Improvement

- Document the dev launcher log directory (%TEMP%\browser4\browser4-cli\tmp\cli\) and the production BROWSER4_SERVER_LOG_DIR default in both docs.
- Make `doctor log` fall back to listing local launcher logs when the backend is down, and print the directory it searched.
- Exit non-zero (or clearly mark the output as unavailable) when the log listing cannot be produced.

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

**Completion Status:** Successful for the required flow (A, B, C, E): default start used exactly one Browser4 port, no :8088 listener, no MCP HTTP log lines, one browser, stable backend pid/port across commands, and a correct registry pid. The optional opt-in check (D) was only partially successful: :8088 and the MCP log lines were correct, but the footprint grew by 1 instead of 2 and cleanup left an orphaned Chrome plus a leaked tab in the main browser.

**Success Rate:** 90% - every required check passed; the optional MCP opt-in footprint/cleanup check did not meet its stated expectations.

**Issues Found:** 4

**Major Blockers:** None blocked the required flow. The optional opt-in cleanup required manual taskkill /T /F of an orphaned Chrome, and the isolated second backend attached to the first backend's browser instead of launching its own.

**Most Confusing Aspects:** 1) `stop` is documented as graceful but in dev mode it force-kills the backend and leaves the browsers running. 2) BROWSER4_CLI_STATE_DIR looks like workspace isolation, but the shared %TEMP%\browser4-<user>\context browser group and ~/.browser4/session-registry.json are global, so two backends share one Chrome and one DEFAULT session UUID (and the docs promise the opposite). 3) Two different docs and `doctor log` disagree about where the backend log lives.

**Most Valuable Improvements:** Make dev-mode stop close the browsers it launched (or make close-all automatic); scope the browser context directory and session registry to the workspace/app-data; verify browser ownership before reusing a running Chrome; unify the log-path documentation and let `doctor log` work offline.

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

#### Issue 1: Dev-mode `stop` leaves the workspace's Browser4 Chrome running

cd D:/workspace/Browser4/Browser4-4.14-feat
./b4w.ps1 open --headless https://example.com
# Get-BrowserFootprint -> Browser4Roots = 1 (e.g. chrome PID 251688)
./b4w.ps1 stop
# Get-BrowserFootprint -> Browser4Roots = 1 and Get-Process -Id 251688 -> ALIVE
# Same with the MCP opt-in backend: chrome PID 249556 survived `stop` of java PID 160260.

#### Issue 2: Two backends on one machine share the DEFAULT browser and session UUID despite an isolated state dir

cd D:/workspace/Browser4/Browser4-4.14-feat
./b4w.ps1 open --headless https://example.com                    # backend A on :8282
BROWSER4_CLI_STATE_DIR=$(cygpath -w "$TEMP/b4-isolation") ./b4w.ps1 open --headless https://example.com   # backend B on :8283
# Get-BrowserFootprint -> Browser4Roots stays 1, not 2
# Backend B log: 'Found valid existing Chrome process on port: 20326' / 'Reusing existing Chrome process' / 'Found existing Chrome process on port: 20326 for userDataDir: ...\browser4-pereg\context\groups\default\PULSAR_CHROME\cx.001'
# Both backends report the same session UUID d7ab81a7-...; backend A's tab-list goes from 1 to 2 tabs and the extra tab survives backend B's close-all.

#### Issue 3: `stop` help says 'Gracefully stop' but dev mode force-kills the backend

./b4w.ps1 help   # lists: stop  Gracefully stop the Browser4 server
./b4w.ps1 open --headless https://example.com
./b4w.ps1 stop   # backend dies via Stop-Process -Force, browser left running

#### Issue 4: Backend server log location is inconsistently documented and `doctor log` needs a running server

Read skills/browser4-cli/SKILL.md line 340 ('the backend writes its logs inside the runtime bundle') and skills/browser4-cli/references/development-mode.md line 23 ('logs' under <state dir>/app-data/). The actual server log is %TEMP%\browser4\browser4-cli\tmp\cli\browser4-server-jar-port8282-*.log; <state dir>/app-data/logs contains only agent/. Then run ./b4w.ps1 doctor log with the backend stopped.

