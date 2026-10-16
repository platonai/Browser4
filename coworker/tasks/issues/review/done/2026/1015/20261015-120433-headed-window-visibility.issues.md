# Issues: headed-window-visibility

> **Source:** `20261015-120433-headed-window-visibility.issues.json` | **Date:** 20261015-120433 | **Mode:** dev

## Scenario Background

### Task

**Scenario: headed-window visibility boundary (`open --headed` / `open --headless`).**

The core behavior under test **works correctly** on this Windows machine:

| Step | Result |
|---|---|
| 1. `close-all` → `open --headed https://example.com` | ✅ exit 0 (13 s). Page reported: URL `https://example.com/`, title `Example Domain` |
| 3. Headed window visible + CDP serves the URL | ✅ PID 43512, `MainWindowHandle=527132`, title **`Example Domain - Google Chrome`**, `IsWindowVisible=True`, `IsMinimized=False`; CDP port **1958** → page target `url=https://example.com/`, `title=Example Domain` (never `about:blank`) |
| 4. Reconnect `open --headed` on an existing session | ✅ No window check runs; the existing headed window was simply navigated (`?r=2`, verified via its CDP port 28657). `open --headless` on that same headed session warned on **stderr** (`--headless ignored: reconnecting to existing session`) and did **not** run the window check |
| 5. Repeat `close` → `open --headed` | ✅ Same result: visible window (PID 44844, hwnd 7670566, `Example Domain - Google Chrome`), CDP port 28657 → requested URL. Stable across 3+ consecutive headed launches (incl. `--profile-mode default` and `--profile-mode sequential`) |
| 6. `open --headless https://example.com` | ✅ exit 0 (6 s), no warning; launched process PID 42076 carries `--headless`, `hwnd=0`; CDP port 46504 → `https://example.com/`; `list` shows `Display=Headless` |
| 7. Clean exit codes / no hangs | ✅ Every command exited 0 (opens 6–13 s); `close` always reported `Browser terminated` and the process was gone |

**What failed:** the *diagnostic* the scenario pins. On the two launches where an orphaned Browser4 browser of this checkout happened to be alive, the CLI printed `⚠ Could not confirm a visible window for this --headed session: every Browser4-managed browser detected for this workspace is running HEADLESS` — while the headed window was demonstrably visible with the correct CDP target. The detector is structurally blind to the browser the session just launched (its profile lives under `%TEMP%\browser4-pereg\context\groups\...`, which contains no workspace token), so it can only ever describe *other* processes: a false positive whenever such a process exists, and a silent no-op when none does (the very display-mode regression it was written to catch would go unreported).

### Execution Context

**Key Commands:**

- `help`, `--help open`, `--help close`, `--help close-all`, `--help list`, `config list`, `open --help --examples`
- `list` / `list --verbose`, `close-all`, `close`, `page-info`
- `open --headed https://example.com` ×3 (one after removing the orphan), `open --headed --profile-mode default|sequential`, `open --headless https://example.com`
- PowerShell probes (`chrome-state.ps1` in the scratch dir): enumerate `chrome.exe` with `PULSAR_CHROME` + `--remote-debugging-port`, reporting `--headless`, debug port, profile path, `MainWindowHandle`, title; `IsWindowVisible`/`IsIconic` via user32; `Get-NetTCPConnection` for the CDP port; `curl http://127.0.0.1:<port>/json` for page targets
- Root-cause reading: `cli/browser4-cli/src/daemon.rs:5721-5830` (`browser4_window_state`, `workspace_match_token`, `workspace_state_slug`), `cli/browser4-cli/src/main.rs:2707-2799` (`handle_open` diagnosis), `browser4-rest/.../PulsarSessionManager.kt:1366-1394` (profile mode / `contextDir`), backend launch reports and `skills/browser4-cli/references/browser-modes.md`

**Major steps / decisions:**
1. Learned the tool from `help` + `skills/browser4-cli/SKILL.md` before touching the browser.
2. Captured a baseline of all `PULSAR_CHROME` Chrome processes to be able to attribute detection results (this machine had many leftovers from earlier runs; the workspace token for this checkout is `Browser4-4.14-04e5248d`).
3. Ran the scenario; for each headed launch verified the window three ways (OS window handle/visibility, window title, CDP page target).
4. Isolated the root cause by differential testing: killed the orphaned same-workspace headless browser (PID 1248) → the warning disappeared entirely (silent no-op), proving the warning is triggered by *other* processes and never by the session's own browser. Confirmed by inspecting each launched browser's `--user-data-dir` (all temp context-group paths for `default`, `--profile-mode default`, and `--profile-mode sequential`) and the backend launch report (`headless=false`).
5. Synthesized the documented fallback state (a headed, windowless, workspace-marked Chrome via `--no-startup-window`) to verify the second warning's exact text — it fired as documented, yet again on a session whose own window was visible.
6. Workarounds: killed the orphaned browsers to test clean-state behavior; used PowerShell/CIM (not a browser-automation library) purely for OS-level observation, as the scenario prescribes.

**Temporary artifacts** (all under `.test-sessions/20261015T0901431699046Z/`): `chrome-state.ps1`, `01-baseline.txt`, `02..23-*` outputs (stdout/stderr/exit codes), `06-cdp-json-headed1.json`, `20-after-profile-default.txt`, `22-final-state.txt`.

Final state: all sessions closed, no Browser4 browser of this checkout left running; the user's own Chrome and one pre-existing foreign process (PID 45644) untouched.

---

---

## Issues Found (6 issues)

### Issue 1: Headed-window check cannot see the session's own browser (silent no-op / false negative)

**Severity:** High
**Category:** Reliability

#### Reproduction

1) ./b4w.ps1 close-all (ensure no other Browser4 browser for this checkout is alive).
2) ./b4w.ps1 open --headed https://example.com
3) Observe: no warning of any kind is printed, although the CLI is documented to verify the window.
4) Inspect the launched browser: powershell -NoProfile -Command "Get-CimInstance Win32_Process -Filter \"Name='chrome.exe'\" | Where-Object { $_.CommandLine -match 'PULSAR_CHROME' -and $_.CommandLine -notmatch '--type=' } | ForEach-Object { ($_.CommandLine -split ' ') | Where-Object { $_ -match 'user-data-dir' } }" -> --user-data-dir=%TEMP%\browser4-pereg\context\groups\default\PULSAR_CHROME\cx.NNN\PULSAR_CHROME (no workspace token).
5) Compare with the filter in browser4_window_state(): the PowerShell probe requires the checkout slug (e.g. Browser4-4.14-04e5248d) in the command line.

#### Expected Behavior

After open --headed the CLI verifies that the freshly created session actually shows a visible window, and flags it when the headed session silently launched headless (the regression this check was written for). Documented in skills/browser4-cli/references/browser-modes.md: 'After open --headed, the CLI verifies that a visible window actually exists and warns when the session was launched headless anyway, or when the process is headed but no window is detected.'

#### Actual Behavior

For every session browser launched in this build the detector returns found_browser=false, because the browser's command line never contains the workspace slug; the caller (main.rs:2757-2764) breaks out of its retry loop immediately and prints nothing. Reproduced in all three clean headed launches (the two earlier runs were masked by a leftover process, which produced the false warning instead). Consequence: if open --headed regressed to launching a headless browser, the CLI would stay silent — the diagnostic cannot detect the bug it exists for. In non-dev builds (no workspace narrowing) the scan does see the browser, so this is a development-mode-specific failure that also invalidates the Windows e2e test.

#### Root Cause Analysis

browser4_window_state() narrows the process scan to this checkout by embedding workspace_match_token() (= workspace_state_slug of the checkout, 'Browser4-4.14-04e5248d') as a PowerShell `-match '<slug>'` on the chrome command line (daemon.rs:5786-5789). Session browsers are no longer launched with the workspace app-data profile: the backend assigns DEFAULT sessions profile mode DEFAULT / named sessions a contextDir and launches them under the shared temp root %TEMP%\browser4-pereg\context\groups\{default|named}\PULSAR_CHROME\cx.<n> (PulsarSessionManager.kt:1366-1394). That path carries no workspace identifier, so the narrowing can never match the session's own browser. Verified for `default`, `--profile-mode default` and `--profile-mode sequential` (all landed in cx.NNN dirs).

#### Code Pointer

`cli/browser4-cli/src/daemon.rs:browser4_window_state() (workspace filter, lines ~5767-5818); caller cli/browser4-cli/src/main.rs:handle_open diagnosis block ~2753-2799`

#### AI Suggested Improvement

- Verify the created session directly instead of guessing from a process scan: have the backend expose the session's browser (launch report / CDP endpoint) and check the window via CDP (Browser.getWindowForTarget -> windowState/bounds, or Target.getTargets + Browser.getWindowBounds) or the owning process's MainWindowHandle.
- If the process scan stays: match BOTH the workspace app-data root and the context-group temp root (browser4-pereg/context/groups), and treat matches as machine-wide with the documented caveat rather than workspace-scoped.
- Do not break the retry loop on found_browser=false; a freshly created session's browser may legitimately take a moment to appear — retry on 'not found' too and then report 'could not verify' explicitly instead of staying silent.
- Add a Windows e2e assertion that a fresh headed launch reports found_browser=true (and headed_browser=true), so a future filter change cannot silently hide the session browser again.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 2: False-positive headed-window warnings on a healthy, demonstrably visible headed session

**Severity:** High
**Category:** Reliability

#### Reproduction

Variant A (real-world, observed): with an orphaned headless Browser4 browser of the same checkout alive (headless-visible=false), run ./b4w.ps1 close-all then ./b4w.ps1 open --headed https://example.com — the CLI prints '⚠ Could not confirm a visible window for this --headed session: every Browser4-managed browser detected for this workspace is running HEADLESS.' while the launched window is visible (MainWindowHandle=527132, title 'Example Domain - Google Chrome', CDP port 1958 serving url=https://example.com/).
Variant B (synthetic): start a windowless headed chrome carrying the checkout slug and PULSAR_CHROME + --remote-debugging-port (e.g. chrome.exe --no-startup-window --user-data-dir=C:\Users\<u>\.browser4\workspaces\Browser4-4.14-04e5248d\probe-profile\PULSAR_CHROME) and run ./b4w.ps1 open --headed https://example.com — the CLI prints '⚠ A headed Browser4 browser process is running, but no visible window was detected.' although the session's own window was visible (hwnd=4198436, title 'Example Domain - Google Chrome', CDP target url=https://example.com/).

#### Expected Behavior

'The headed-window warning fires only when a headed browser process exists without a visible window, and never for headless sessions' (scenario acceptance). At minimum the warning must not describe the session's window when that window is visible.

#### Actual Behavior

Both warning variants fire on successfully launched headed sessions whenever an unrelated matching process exists. Rounds 1 and 2 printed the 'running HEADLESS' variant even though the headed window was visible and its CDP endpoint served the requested URL; the synthetic run printed the 'no visible window' variant on a session whose window was visible. The text asserts a problem with *this* session and tells the user to close/retry and consider 'a display-mode bug' — wasted effort chasing a non-existent bug, and it undermines trust in a correct feature.

#### Root Cause Analysis

Same root cause as the blind-spot issue: the detector's visible set never contains the session's own browser (temp context-group profile paths do not match the dev-mode workspace filter), so the diagnosis always describes other Browser4 processes — an orphan headless browser yields 'all detected are HEADLESS', a windowless headed process yields 'no visible window'. Because the workspace narrowing is deterministic, this mis-attribution is systematic in dev mode, not occasional skew.

#### Code Pointer

`cli/browser4-cli/src/main.rs:~2766-2798 (both warning branches); cli/browser4-cli/src/daemon.rs:browser4_window_state()`

#### AI Suggested Improvement

- Make the check session-scoped (see previous issue); warn only on a session-scoped negative result.
- Until then, reword so the message cannot be read as a claim about this session's window, e.g. 'Could not verify a window for this session; the only Browser4 browser processes detected on this machine are [headless]' and drop the 'it is a display-mode bug' sentence.
- Consider suppressing the warning when the same invocation can prove the session's window exists (e.g. backend launch info), rather than relying on the machine-wide guess.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 3: Orphaned Browser4 browser processes survive close-all/close (resource leak)

**Severity:** Medium
**Category:** Reliability

#### Reproduction

1) Note an existing Browser4-managed chrome process from an earlier run, e.g. PID 1248 (--headless, --user-data-dir=C:\Users\pereg\.browser4\workspaces\Browser4-4.14-04e5248d\app-data\browser\chrome\default\PULSAR_CHROME, started 19:01:01).
2) ./b4w.ps1 close-all -> 'Closed 3 session(s)'.
3) powershell -NoProfile -Command "Get-CimInstance Win32_Process -Filter \"Name='chrome.exe'\" | Where-Object { $_.CommandLine -match 'PULSAR_CHROME' } | ForEach-Object { $_.ProcessId }" -> PID 1248 still present and its CDP port still listening; ./b4w.ps1 list shows 'No active browser sessions.'
4) A second orphan (PID 45644, headless, temp context group cx.002, started 19:41:40) survived the entire session including multiple close/close-all.

#### Expected Behavior

After close-all, no Browser4-managed browser process from this checkout remains (or the CLI reports and reaps strays).

#### Actual Behavior

Browser processes that no current session record points to are left running indefinitely (one for ~1 hour, still serving CDP), consuming memory and actively polluting the headed-window detection (see the false-positive issue).

#### Root Cause Analysis

close-all/close terminate only the browsers of sessions the backend can still resolve. Browsers whose session records are gone (stale session rows, or sessions created by a CLI/backend instance that exited) are unreachable from the session table, and nothing scans the workspace browser profile roots or the launcher.pid files that the launcher already writes (port/launcher.pid in each context dir) to reap them. Whether `stop`/`kill-all` reaps them was not tested.

#### Code Pointer

`cli/browser4-cli/src/main.rs (close-all handler); backend browser shutdown path browser4-rest/src/main/kotlin/ai/platon/pulsar/rest/session/PulsarSessionManager.kt`

#### AI Suggested Improvement

- On close-all/stop, reconcile the session table against on-disk browser state (workspace app-data browser roots + context-group dirs + launcher.pid) and terminate strays, reporting what was reaped.
- Surface orphaned browsers in doctor/status (e.g. '2 orphaned Browser4 browser processes').
- Ensure a crashed/interrupted CLI or backend cannot leave a browser behind (supervisor watchdog on the launcher.pid).

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 4: Window-verification diagnostic is undocumented in `open --help` and the reference overstates what it does

**Severity:** Low
**Category:** Documentation

#### Reproduction

1) ./b4w.ps1 --help open -> no mention of any post-launch window check.
2) Read skills/browser4-cli/references/browser-modes.md (section 'Headed-mode reliability and anti-bot notes'): 'After open --headed, the CLI verifies that a visible window actually exists and warns when the session was launched headless anyway...' and 'The visibility check is Windows-only and machine-wide.'
3) Contrast with the runtime warning, which says '...detected for this workspace...' (dev mode narrows it to the checkout), and with the observed behavior: the check cannot see the session's browser at all.

#### Expected Behavior

open --help documents the post-launch window diagnosis (what is checked, its limits, where the warning appears), and the reference describes the behavior that actually ships.

#### Actual Behavior

The feature is undiscoverable from the command's own help; the only mention overstates it ('verifies') and mislabels its scope ('machine-wide' vs the dev-mode workspace narrowing and the warning's own 'for this workspace' wording). A first-time user who sees the warning has no help text to consult and no way to judge whether it applies to their session.

#### Root Cause Analysis

Docs were written against the intended implementation; the dev-mode workspace filter added later changed the effective behavior, and neither the help text nor the reference was updated to match. The two warning strings and the doc also use three different scope descriptions.

#### Code Pointer

`cli/browser4-cli/src/main.rs (open help text); skills/browser4-cli/references/browser-modes.md:139-146`

#### AI Suggested Improvement

- Add a Notes bullet to `open --help` describing the check, its platform limit (Windows only) and where the warning is printed.
- Reword the reference to state precisely what is enumerated (chrome.exe with --remote-debugging-port + PULSAR_CHROME marker; dev mode: narrowed to the checkout's own profile paths) and to say it cannot be attributed to the session.
- Align the doc with whatever fix the detection issues receive (session-scoped verification would change this text again), and unify 'machine-wide' vs 'for this workspace'.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 5: Headed-window warning goes to stdout and vanishes under --quiet/--json

**Severity:** Low
**Category:** UX

#### Reproduction

./b4w.ps1 open --headed https://example.com > out.txt 2> err.txt — the ⚠ warning appears in out.txt (stdout), err.txt is empty, alongside the success payload. Compare ./b4w.ps1 open --headless <url> against an existing headed session: '--headless ignored: reconnecting...' is printed to stderr. In JSON mode the window warning is suppressible: the macro cli_println! (cli/browser4-cli/src/main.rs:265-276) emits nothing when quiet or json is active, so `--json open --headed` can never carry a window-verification result (contrast: challenge detection is exposed as challenge_detected).

#### Expected Behavior

Diagnostics that change how the result should be interpreted are visible to humans on stderr and to scripts as a structured field, consistently with other advisories.

#### Actual Behavior

Mixed into stdout in normal mode (script parsers see an unexpected ⚠ block), and silently dropped in --quiet/--json mode; the channel differs from the neighboring '--headless ignored' warning.

#### Root Cause Analysis

The diagnosis block uses cli_println! (stdout, quiet/json-aware) rather than the stderr/structured path used for other advisories.

#### Code Pointer

`cli/browser4-cli/src/main.rs:265-276 (macro) and 2773-2798 (warning call sites)`

#### AI Suggested Improvement

- Print the warning to stderr (and document that it is advisory, like the challenge-detection warning).
- Add a structured field to the --json envelope for open, e.g. window_verified: true|false|'unknown' plus a reason/source, so automation can act on it.
- Keep the human-readable text on stderr in normal mode so stdout stays parseable.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 6: Closed sessions leave their Chrome profile directories behind in %TEMP% (unbounded growth)

**Severity:** Low
**Category:** Product

#### Reproduction

1) Run several opens/closes (default profile mode). 2) Inspect %TEMP%\browser4-pereg\context\groups\default\PULSAR_CHROME: cx.001..cx.010 are all present after every session was closed; round 1's cx.004 remained after `close` at 19:57:37 and was still present (409 files) at 20:02; round 3's cx.007 (429 files) likewise. 3) du -sh of the directory: 828 MB for 10 rotations.

#### Expected Behavior

Profiles of closed sessions are removed (they are created under TEMP for temporary/default profile mode), or a documented retention/cleanup policy explains the growth.

#### Actual Behavior

Every session launch allocates a new cx.NNN directory that is not reclaimed on close; ten rotations from a handful of runs already occupy ~828 MB in the user's temp directory. (Cleanup at backend shutdown / `stop` was not tested.)

#### Root Cause Analysis

The launcher creates per-session context dirs under the shared temp root and the session-close path does not delete them; nothing observed ages them out. Verification of the stop/shutdown path is needed to determine whether this is a permanent leak or only a delayed cleanup.

#### Code Pointer

`browser4-core (BrowserFiles / BrowserProfileGenerator context-dir lifecycle) and the close path in browser4-rest/src/main/kotlin/ai/platon/pulsar/rest/session/PulsarSessionManager.kt`

#### AI Suggested Improvement

- Delete the session's context dir on close when it lives under the temp context-group root; keep dirs for persistent-profile sessions.
- Age out stale cx.* dirs at backend startup (nothing referencing them in a launcher.pid / session record), logging reclaimed space.
- Document the profile retention policy in browser-modes.md.

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

**Completion Status:** Successful with documented deviations - all scenario steps ran and the primary behavior is correct: open --headed navigates a genuinely visible window (verified via OS window handle/visibility, window title 'Example Domain - Google Chrome', and its CDP page target url=https://example.com/), open --headless creates no window and launches with --headless, reconnect never runs the check, and every command exits 0 with no hangs. Two acceptance criteria fail: the headed-window warning fires on healthy sessions (false positive) and the check cannot observe the session's own browser (silent no-op / false negative).

**Success Rate:** 85%

**Issues Found:** 6

**Major Blockers:** None blocked task execution. The misleading warning was a major interference: it appeared on successful headed opens and claims the session may have no window / may be a display-mode bug, while the window was verifiably visible. Both false-positive warning variants were reproduced, and the clean-state case proved the check never sees the session's browser.

**Most Confusing Aspects:** A ⚠ warning contradicting an obviously visible browser window on a successful command, including advice to close/retry and a 'display-mode bug' accusation; no way to learn the session's display mode from open's own output (only `list` shows a Display column); the warning is printed on stdout among the success payload while a neighbouring warning (display mode ignored on reconnect) goes to stderr; documentation states the CLI 'verifies that a visible window actually exists', which does not match what the check can actually observe.

**Most Valuable Improvements:** Make the window verification session-scoped and trustworthy (use the backend's launch info / CDP window state instead of guessing from a machine-wide process scan; the scan's dev-mode workspace filter cannot see session browsers launched with temp context-group profiles). Reap orphaned Browser4 browser processes on close-all/stop and surface them in doctor. Align open --help and browser-modes.md with the real behavior, print the warning to stderr, and expose it as a structured field in --json. Clean up temp context profiles of closed sessions.

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

#### Issue 1: Headed-window check cannot see the session's own browser (silent no-op / false negative)

1) ./b4w.ps1 close-all (ensure no other Browser4 browser for this checkout is alive).
2) ./b4w.ps1 open --headed https://example.com
3) Observe: no warning of any kind is printed, although the CLI is documented to verify the window.
4) Inspect the launched browser: powershell -NoProfile -Command "Get-CimInstance Win32_Process -Filter \"Name='chrome.exe'\" | Where-Object { $_.CommandLine -match 'PULSAR_CHROME' -and $_.CommandLine -notmatch '--type=' } | ForEach-Object { ($_.CommandLine -split ' ') | Where-Object { $_ -match 'user-data-dir' } }" -> --user-data-dir=%TEMP%\browser4-pereg\context\groups\default\PULSAR_CHROME\cx.NNN\PULSAR_CHROME (no workspace token).
5) Compare with the filter in browser4_window_state(): the PowerShell probe requires the checkout slug (e.g. Browser4-4.14-04e5248d) in the command line.

#### Issue 2: False-positive headed-window warnings on a healthy, demonstrably visible headed session

Variant A (real-world, observed): with an orphaned headless Browser4 browser of the same checkout alive (headless-visible=false), run ./b4w.ps1 close-all then ./b4w.ps1 open --headed https://example.com — the CLI prints '⚠ Could not confirm a visible window for this --headed session: every Browser4-managed browser detected for this workspace is running HEADLESS.' while the launched window is visible (MainWindowHandle=527132, title 'Example Domain - Google Chrome', CDP port 1958 serving url=https://example.com/).
Variant B (synthetic): start a windowless headed chrome carrying the checkout slug and PULSAR_CHROME + --remote-debugging-port (e.g. chrome.exe --no-startup-window --user-data-dir=C:\Users\<u>\.browser4\workspaces\Browser4-4.14-04e5248d\probe-profile\PULSAR_CHROME) and run ./b4w.ps1 open --headed https://example.com — the CLI prints '⚠ A headed Browser4 browser process is running, but no visible window was detected.' although the session's own window was visible (hwnd=4198436, title 'Example Domain - Google Chrome', CDP target url=https://example.com/).

#### Issue 3: Orphaned Browser4 browser processes survive close-all/close (resource leak)

1) Note an existing Browser4-managed chrome process from an earlier run, e.g. PID 1248 (--headless, --user-data-dir=C:\Users\pereg\.browser4\workspaces\Browser4-4.14-04e5248d\app-data\browser\chrome\default\PULSAR_CHROME, started 19:01:01).
2) ./b4w.ps1 close-all -> 'Closed 3 session(s)'.
3) powershell -NoProfile -Command "Get-CimInstance Win32_Process -Filter \"Name='chrome.exe'\" | Where-Object { $_.CommandLine -match 'PULSAR_CHROME' } | ForEach-Object { $_.ProcessId }" -> PID 1248 still present and its CDP port still listening; ./b4w.ps1 list shows 'No active browser sessions.'
4) A second orphan (PID 45644, headless, temp context group cx.002, started 19:41:40) survived the entire session including multiple close/close-all.

#### Issue 4: Window-verification diagnostic is undocumented in `open --help` and the reference overstates what it does

1) ./b4w.ps1 --help open -> no mention of any post-launch window check.
2) Read skills/browser4-cli/references/browser-modes.md (section 'Headed-mode reliability and anti-bot notes'): 'After open --headed, the CLI verifies that a visible window actually exists and warns when the session was launched headless anyway...' and 'The visibility check is Windows-only and machine-wide.'
3) Contrast with the runtime warning, which says '...detected for this workspace...' (dev mode narrows it to the checkout), and with the observed behavior: the check cannot see the session's browser at all.

#### Issue 5: Headed-window warning goes to stdout and vanishes under --quiet/--json

./b4w.ps1 open --headed https://example.com > out.txt 2> err.txt — the ⚠ warning appears in out.txt (stdout), err.txt is empty, alongside the success payload. Compare ./b4w.ps1 open --headless <url> against an existing headed session: '--headless ignored: reconnecting...' is printed to stderr. In JSON mode the window warning is suppressible: the macro cli_println! (cli/browser4-cli/src/main.rs:265-276) emits nothing when quiet or json is active, so `--json open --headed` can never carry a window-verification result (contrast: challenge detection is exposed as challenge_detected).

#### Issue 6: Closed sessions leave their Chrome profile directories behind in %TEMP% (unbounded growth)

1) Run several opens/closes (default profile mode). 2) Inspect %TEMP%\browser4-pereg\context\groups\default\PULSAR_CHROME: cx.001..cx.010 are all present after every session was closed; round 1's cx.004 remained after `close` at 19:57:37 and was still present (409 files) at 20:02; round 3's cx.007 (429 files) likewise. 3) du -sh of the directory: 828 MB for 10 rotations.

