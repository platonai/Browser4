# Issues: iframes-and-popups

> **Source:** `20261013-164431-iframes-and-popups.issues.json` | **Date:** 20261013-164431 | **Mode:** dev

## Scenario Background

### Task

**Scenario:** Frame scoping and multi-window handling on `http://localhost:18080/b4/frame-switch.html` (MockSite was already running — verified HTTP 200; no `./bin/test.ps1 mock-site` needed).

| AC | Result | Evidence |
|---|---|---|
| AC1 — frame tree | ✅ | `frames` lists main + `payframe`, `otherframe`, `nestedframe`, and nested `innerframe` with full URLs; `"active":true` correctly marks main, then `payframe` after switching (output is raw JSON, not the tree shown in the docs) |
| AC2 — switch by selector | ✅ | `frame "#pay-frame"` → payment frame JSON with `"active":true` |
| AC3 — scoped interaction | ✅ | `fill "#card-number" "4111-1111-1111-1111"` + `click "#pay-submit"` reached the iframe; `#pay-state` = `submitted:4111-1111-1111-1111:`. The empty card holder is reported as just the trailing empty segment — **the CLI says nothing special about the empty field** |
| AC4 — scoping is real | ✅ | `is visible "#main-button"` → `false` while scoped; `true` after `frame main` |
| AC5 — nested frames | ✅ | `frame "#nested-frame"` → `frame "#inner-frame"` → `get text "#inner-heading"` = `Inner frame` |
| AC6 — loud failure | ✅ | `frame "#no-such-frame"` → exit 1, `Frame not found: #no-such-frame`; scope remains on main |
| AC7 — new window/target | ✅ | `window new …/frame-other.html` → `tab-list` shows it (URL exact), `tab-select 0` + `page-info` confirm identity. **CLI describes it as a "tab"** (`Created tab with GUID…`, `Switched to tab 0`, `(2/2 tabs total)`) — consistent with its own help ("equivalent to opening a new tab"), but `tab-management.md` calls it "the only command that creates a separate browser window" |
| AC8 — scope resets on navigation | ❌ **FAIL** | `goto` while frame-scoped → exit 1 `❌ Navigation failed … selected frame 'payframe' is not reachable` even though the navigation really happened; `reload` → exit 0 but leaves a **stale scope** that makes every subsequent element command fail (exit 1) until `frame main`/`frames` is run. Docs say "Navigation resets the scope" — it does not |

Other requested records: nested switching and target forms (CSS selector, frame name, URL fragment) all work; **no cross-frame hint** is given when a selector exists in another frame; element-command help (`fill`, `click`, `is visible`, `snapshot`) never mentions frame scoping.

### Execution Context

**Key Commands:**

`status`, `open --headless`, `wait` (selector), `frames`, `frame <sel|name|url-fragment|main>`, `fill`, `click`, `is visible`, `get text`, `eval`, `page-info`, `goto`, `reload`, `tab-list`, `tab-select`, `window new`, `help` (multiple), `list`, `close`, `doctor log` — plus source/log inspection.

Major steps: opened a fresh headless session → discovered the frame tree → switched into `pay-frame`, filled + submitted the card form, verified `#pay-state` through `contentDocument` → confirmed scoping via `is visible` → returned to main and clicked `#main-button` → nested frame switch → negative switch test → `window new` + tab verification → AC8 navigation tests (reproduced twice) → root-causing (subagent traced `wait_for_selector` through `BrowserTabExecutor` → external pulsar-browser `AbstractWebDriver.waitUntil`) → independent re-verification of the `wait`/`click` false positives on a fresh session with a JS click counter.

Workarounds required: none to complete the reads/interactions, but **verification had to avoid the CLI's own success messages** — `wait` and `click` report success unconditionally, so I verified effects with `eval` (`window.__frameSwitchState.mainButtonClicks`, `#pay-state` text) instead. Recovery from the stale frame scope needed `frame main`; a JS-dialog-free, deterministic fixture made this practical. The evaluation was also interrupted by a full backend death (session + tabs lost, backend restarted on a different dev port 8282 vs 8285) — recovered by re-opening a session.

---

---

## Issues Found (12 issues)

### Issue 1: `wait <selector>` always reports success and can never fail on timeout

**Severity:** Critical
**Category:** Reliability

#### Reproduction

./b4w.ps1 -s frames-eval wait "#definitely-no-such-el-xyz" --timeout 4000
# → ✓ Element found: #definitely-no-such-el-xyz   (exit 0)
./b4w.ps1 -s frames-eval wait "e999999" --timeout 3000
# → ✓ Element found: e999999   (exit 0)
# contrast: ./b4w.ps1 -s frames-eval is visible "#definitely-no-such-el-xyz" → false

#### Expected Behavior

A selector wait that never resolves should time out, exit non-zero and explain that the element never appeared (the tool spec explicitly promises "Fails (TIMEOUT) instead of returning silently when the selector never appears").

#### Actual Behavior

The command returns immediately with '✓ Element found: <selector>' and exit 0 for any selector, including ones that cannot exist. The scenario's own step 2 ("wait until #pay-submit resolves") is therefore a no-op that always 'passes'.

#### Root Cause Analysis

Backend chain: MCP `wait_for_selector` → tab.waitForSelector → PulsarWebDriver.waitForSelector → AbstractWebDriver.waitUntil (external artifact ai.platon.pulsar:pulsar-browser:4.11.24). waitUntil wraps its polling loop in `withTimeoutOrNull(timeout)` and discards the null, returning `timeout - elapsed` even when the predicate never became true — no TimeoutException. AbstractToolExecutor (and MCPToolController) only produce errors from thrown exceptions, so the call is reported OK. The CLI then formats any non-error wait_for_selector response as success: format_wait_result() prints "✓ Element found: {selector}" without inspecting the result value. The driver's own exists() check (DOM query) works correctly — it is simply never turned into a failure.

#### Code Pointer

`browser4-agentic/src/main/kotlin/ai/platon/pulsar/agentic/tools/builtin/BrowserTabToolExecutor.kt:1147 (waitForSelector branch) and cli/browser4-cli/src/main.rs:28903 (format_wait_result); in-repo place to override the swallowing wait: browser4-core/browser4-browser/src/main/kotlin/ai/platon/pulsar/chrome/Browser4WebDriver.kt`

#### AI Suggested Improvement

- In the BrowserTabToolExecutor waitForSelector branch (or a Browser4WebDriver override), poll `driver.exists(selector)` yourself and throw a TimeoutException when the deadline passes — MCPToolController already classifies TimeoutException as a TIMEOUT error.
- Guard the fix with an integration test: wait on a nonexistent selector must exit non-zero and must sit for the requested timeout.
- Change format_wait_result to stop printing an unconditional success claim: either rely on the backend error or re-check existence before printing '✓ Element found'.
- Keep the silent-withTimeoutOrNull behaviour out of the wait path entirely (e.g. add a strict variant used by waitForSelector).

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 2: `click` reports success for selectors that match nothing (including out-of-frame targets), silently doing nothing

**Severity:** High
**Category:** Reliability

#### Reproduction

./b4w.ps1 -s frames-eval2 click "#no-such-button"
# → ✓ Clicked #no-such-button   (exit 0, nothing exists)
./b4w.ps1 -s frames-eval2 frame "#pay-frame"
./b4w.ps1 -s frames-eval2 click "#main-button"
# → ✓ Clicked #main-button   (exit 0) but eval "window.__frameSwitchState.mainButtonClicks" stays 1
./b4w.ps1 -s frames-eval2 frame main; ./b4w.ps1 -s frames-eval2 click "#main-button" → counter becomes 2
# contrast: ./b4w.ps1 -s frames-eval2 fill "#no-such-input" x → exit 1, 'no element found for selector'

#### Expected Behavior

Clicking a selector that resolves to no element in the current frame scope should fail loudly (like fill does), or at minimum warn that nothing was clicked.

#### Actual Behavior

'✓ Clicked <selector>' with exit 0 and no effect; the backend log records `tool=browser_click … outcome=OK`. A scope mistake or typo is therefore invisible, and click-based verification is worthless.

#### Root Cause Analysis

The plain `driver.click(selector)` path (no count/modifier/button) has no existence validation and returns without throwing when the selector resolves to nothing; the in-repo Browser4WebDriver only overrides click(selector, count) and click(selector, modifier). BrowserTabToolExecutor's 'click' branch (line ~1545) forwards the call, AbstractToolExecutor only surfaces thrown exceptions, and the CLI prints its own success line. The upstream fill implementation does validate (hence the asymmetry).

#### Code Pointer

`browser4-agentic/src/main/kotlin/ai/platon/pulsar/agentic/tools/builtin/BrowserTabToolExecutor.kt:1545 ('click' branch); browser4-core/browser4-browser/src/main/kotlin/ai/platon/pulsar/chrome/Browser4WebDriver.kt:1606 (add a plain click(selector) override that asserts existence)`

#### AI Suggested Improvement

- Add an in-repo override `click(selector: String)` in Browser4WebDriver that resolves the element first (exists()/queryLocator) and throws the same styled 'no element found for selector …' error that fill produces.
- Mirror the same guard in dblclick/hover/check/uncheck/select if they share the silent path.
- Add a regression test asserting exit 1 for `click "#no-such-el"` and for a scoped click on a main-document element while a frame is active.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 3: Navigating while a frame is active: `goto` reports failure though the navigation succeeds, and the stale frame scope then breaks every element command (AC8 fails)

**Severity:** High
**Category:** Product

#### Reproduction

./b4w.ps1 -s frames-eval open --headless "http://localhost:18080/b4/frame-switch.html"
./b4w.ps1 -s frames-eval frame "#pay-frame"
./b4w.ps1 -s frames-eval goto "http://localhost:18080/b4/frame-switch.html"
# → exit 1: ❌ Navigation failed … The selected frame 'payframe' is not reachable …
# (the page DID navigate: frame ids change, document state resets)
./b4w.ps1 -s frames-eval is visible "#main-button"
# → exit 1: browser_is_visible failed: The selected frame 'payframe' is not reachable
# … keeps failing until `frame main` (or a `frames` listing) is run.
./b4w.ps1 -s frames-eval frame "#pay-frame" && ./b4w.ps1 -s frames-eval reload
# → reload exits 0, but the next element command fails with the same stale-frame error

#### Expected Behavior

Per the docs (references/frames.md: 'Navigation resets the scope — goto/open/reload/back/forward replace the whole frame tree'; `frame` help: 'The scope resets automatically on navigation'), navigation should succeed, clear the frame scope, and subsequent element commands should resolve against the main document (AC8).

#### Actual Behavior

`goto` exits 1 with 'Navigation failed' although the navigation completes; `reload` exits 0 but leaves the scope stale; in both cases element commands fail with 'The selected frame 'payframe' is not reachable' until the user explicitly runs `frame main` (or `frames`, which also appears to reconcile the tree). Reproduced twice (both a failed-goto run and a reload run) on separate sessions.

#### Root Cause Analysis

The driver's FrameManager keeps the active frame id in session state across navigation. A navigation replaces the frame tree (new frame ids) but the active-frame pointer is not cleared, so the post-navigation step of browser_navigate — and every subsequent frame-scoped element resolution — resolves the vanished frame and throws FrameScopeException, which DOMHandler deliberately re-throws. Commands that rebuild/list the tree (frames / frame main) reconcile the pointer, which is why they clear the condition. The scope reset the docs promise must be wired into the navigation path itself.

#### Code Pointer

`Driver-side FrameManager/DOMHandler (external pulsar-browser artifact, see references/frames.md 'Implementation notes'); in-repo navigation path: browser4-rest MCPToolController resolveMcpToolCall → browser_navigate, and browser4-core/browser4-browser/src/main/kotlin/ai/platon/pulsar/chrome/Browser4WebDriver.kt`

#### AI Suggested Improvement

- Clear the frame scope before/after any navigation at the driver level (activeFrameId = null on navigation start and on main-frame commit) instead of relying on the frame id staying resolvable.
- Make scope reconciliation lazy-safe: if the recorded active frame id no longer exists, resolve against the main document and emit a warning instead of throwing for every subsequent command.
- Add e2e coverage: goto/reload/go-back while frame-scoped must exit 0 and the next element command must resolve in the main document.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 4: Backend died mid-session without any log; page-dependent commands then misreport 'No active browser session' and do not auto-start the backend, while the session and tabs are silently lost

**Severity:** High
**Category:** Reliability

#### Reproduction

Run a session normally (e.g. the frames scenario). At some point:
./b4w.ps1 -s frames-eval eval "window.__frameSwitchState.mainButtonClicks"
# → exit 3: Error: No active browser session. Run 'browser4-cli.exe open <url>' or 'browser4-cli.exe goto <url>' first to load a page, then try 'eval' again.
./b4w.ps1 list
# → 'Browser4 backend is not started or unreachable at http://127.0.0.1:8285. Showing local persisted sessions only'
# The next command (page-info) auto-starts the backend on port 8282, then: '🔐 Session refresh needed — The saved session expired or is no longer usable.' All tabs/browser state gone.

#### Expected Behavior

Either the backend keeps running for the session, or the CLI reports the true cause ('backend not running/unreachable'), auto-starts it like other commands do, and recovers or clearly explains the session loss.

#### Actual Behavior

The dead backend was detected as a closed local port; page-dependent commands short-circuit to a misleading 'No active browser session … open <url> first' message (exit 3) with no auto-start attempt, while page-info/open do auto-start the backend. After the restart the previous session is unrecoverable and its browser is gone. Backend evidence: bundle logs/pulsar.log — old process (pid 245648) last line 00:38:13.446 (a completed tool call), new process (pid 223968) starts 00:38:27.960; no shutdown/exception lines and no hs_err JVM dump, i.e. an abrupt external termination. The dev port also moved 8285 → 8282 between restarts, and a foreign session from another concurrent run ('form-fill-eval') shares this backend.

#### Root Cause Analysis

CLI-side: main.rs checks `is_page_dependent_command(command) && is_local && !is_local_port_open(base_url)` and returns the 'No active browser session' error BEFORE calling ensure_server_running, so recovery is skipped and the diagnosis blames the user. Backend-side: the process ended without a shutdown hook log or crash dump — most plausibly external termination in a shared dev backend (concurrent runs in the same checkout share one backend and one port pool); the CLI daemon's backend lifecycle/restart policy and cross-run interference need investigation (check for a stop/kill issued by a sibling run, OS-level kill, or daemon health-triggered restart).

#### Code Pointer

`cli/browser4-cli/src/main.rs:27381 (page-dependent command early return before ensure_server_running)`

#### AI Suggested Improvement

- Reorder the check: attempt ensure_server_running first, and only if it fails report 'Browser4 backend not running — start it with …' instead of 'No active browser session'.
- Distinguish 'backend down' from 'no session': the current message sends users to re-open a session they already have.
- Investigate and harden the dev backend lifecycle: log a reason on shutdown, detect abnormal termination, and consider a per-run backend or a documented warning that concurrent runs in one checkout share (and can kill) the same backend.
- After a backend restart, recover saved sessions when possible or state explicitly that the browser session was lost.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 5: No hint that a failing selector exists in another frame (silent 'not found' in the wrong scope)

**Severity:** Medium
**Category:** UX

#### Reproduction

On the fixture, from the main scope:
./b4w.ps1 -s frames-eval get text "#pay-heading"   # exists in payframe
# → null / 'No elements matched "#pay-heading".'  (no mention of frames)
While scoped to payframe:
./b4w.ps1 -s frames-eval get text "#main-heading"  # exists in main
# → null / same message
./b4w.ps1 -s frames-eval is visible "#card-number" # → false, silently

#### Expected Behavior

When a selector matches nothing in the current scope but exists in another (same-origin) frame, say so and point at the fix: e.g. 'not found in the current frame; a matching element exists in frame "payframe" — run frame "#pay-frame"'.

#### Actual Behavior

Identical 'No elements matched' output as for a genuinely absent element; nothing tells the user their scope is wrong. `is visible` returns a bare 'false'. Combined with the silent `click` no-op (Issue 2) and the silent `frame main`, scope mistakes are effectively undetectable.

#### Root Cause Analysis

Selector resolution queries only the active frame's document and reports a plain miss; no cross-frame search is attempted for the failure path (the frame tree is available to the driver, so a diagnostic lookup is feasible).

#### Code Pointer

`Frame-scoped resolution in the driver (DOMHandler.doResolveLocator / FrameManager, external pulsar-browser) surfaced through BrowserTabToolExecutor; error text assembled by the CLI's `get`/`is` formatting`

#### AI Suggested Improvement

- On a scoped miss, search the other same-origin frames once and, if a match is found, append a hint naming the frame and the exact `frame <target>` command.
- Apply the same hint to timeouts in `wait` once Issue 1 is fixed.
- Include the current scope (e.g. 'scope: payframe') in element-command output headers so the active document is always visible.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 6: Element-command help never mentions frame scoping

**Severity:** Medium
**Category:** Discoverability

#### Reproduction

./b4w.ps1 help fill | grep -i frame        # (no match)
./b4w.ps1 help click | grep -i frame       # (no match)
./b4w.ps1 help "is visible" | grep -i frame # (no match)
./b4w.ps1 help snapshot | grep -i frame    # (no match)

#### Expected Behavior

The commands whose selectors are frame-scoped should say so and cross-reference `frame`/`frames` (a 'See also: frames' line at minimum).

#### Actual Behavior

Scoping is documented only on `frame`/`frames` themselves (and in the skill reference frames.md). A user who reads `click --help` or `fill --help` has no way to learn that their selector resolves inside the last selected frame.

#### Root Cause Analysis

Frame scoping was added without updating the per-command help/notes strings of the element commands; the tip system has one frames tip (tips.rs) but tips are suppressed by default.

#### Code Pointer

`cli/browser4-cli/src/commands.rs (CommandDef options/notes for click/fill/type/get/is visible/snapshot) and cli/browser4-cli/src/help.rs`

#### AI Suggested Improvement

- Add a one-line note to every frame-scoped command's help: 'Selectors resolve in the frame selected via `frame` (default: main document).'
- Add the 'See also: frames.md' cross-reference already used by other commands.
- Consider showing the active scope in the help footer example section.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 7: False 'Possible blocked/challenge page' advisory fires on legitimate tiny fixture pages

**Severity:** Low
**Category:** Reliability

#### Reproduction

./b4w.ps1 -s frames-eval goto "http://localhost:18080/b4/frame-other.html"
# or: ./b4w.ps1 -s frames-eval page-info  (while that page is active)
# → ⚠ Possible blocked/challenge page — detected page text is implausibly empty (50 chars) after a successful navigation …

#### Expected Behavior

No blocked-page warning for a fully loaded, legitimate page (here the project's own test fixture with heading + button + state).

#### Actual Behavior

The advisory fires because the whole page has ~50 characters of visible text; a user is told to retry and inspect, and may waste steps or learn to ignore the warning.

#### Root Cause Analysis

The implausibly-empty-body heuristic thresholds on visible text length (and iframe presence) only; it lacks a lower-bound sanity check (e.g. tiny documents, localhost/dev hosts, or pages whose body text is short by design). SKILL.md claims widget/demo pages and content paths are exempt, but this fixture shows the exemption is incomplete.

#### Code Pointer

`Post-navigation advisory logic in cli/browser4-cli/src/main.rs (challenge/empty-body probe; see page_loaded_hint and the challenge-signature helpers)`

#### AI Suggested Improvement

- Exempt localhost/loopback and RFC1918 hosts from the empty-body advisory (bot challenges are not meaningful there).
- Raise the 'implausibly empty' threshold or require additional signals (no interactive nodes AND no navigation to a challenge URL) before warning.
- Ensure the warning text does not appear when a snapshot was just captured successfully with interactive elements.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 8: `frames` output is raw backend JSON while the reference doc shows a human-readable frame tree

**Severity:** Low
**Category:** Documentation

#### Reproduction

./b4w.ps1 -s frames-eval frames

#### Expected Behavior

Documented output (references/frames.md Quick Start): '# frame 0 (main) url=…' / '# frame 1 name=payframe url=…', i.e. an indented tree with names/URLs and an active marker.

#### Actual Behavior

A raw JSON array: [{"id":"…","url":"…","depth":0,"active":true,"label":"…","isMainFrame":true}, …] — no dedicated formatter exists in the CLI.

#### Root Cause Analysis

The CLI has no renderer for the frame-list tool result, so the backend JSON passes through verbatim, while the skill reference (frames.md) describes an idealized tree format that was never implemented (or was removed).

#### Code Pointer

`cli/browser4-cli/src/commands.rs:3074 (frames CommandDef — no output formatter); skills/browser4-cli/references/frames.md (Quick Start example)`

#### AI Suggested Improvement

- Either add a small human-readable renderer (indented tree with active marker) used by default and keep the JSON for --json, or update frames.md to show the actual JSON output.
- Keep the same fix applied to `frame <target>` (its success output is also raw JSON) and `frame main` (which prints nothing).

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 9: `frame main` succeeds silently — no confirmation the scope was cleared

**Severity:** Low
**Category:** UX

#### Reproduction

./b4w.ps1 -s frames-eval frame "#pay-frame"   # prints frame JSON
./b4w.ps1 -s frames-eval frame main               # prints nothing, exit 0

#### Expected Behavior

A short confirmation (e.g. '✓ Frame scope: main document') so the user knows the scope change took effect.

#### Actual Behavior

Empty output. Given that mismatched scope is a common and (per other findings) silent failure mode, the lack of confirmation is a real confidence gap.

#### Root Cause Analysis

The frame_main handler returns an empty/void result and the CLI has no success formatter for it, unlike frame_switch which returns the frame JSON.

#### Code Pointer

`cli/browser4-cli/src/commands.rs (frame command definitions); backend frame_main executor (BrowserTabToolExecutor)`

#### AI Suggested Improvement

- Print a one-line confirmation for `frame main` and for successful `frame` switches in a stable short form (name + url + active marker).
- Include the same line in the auto-snapshot header when a frame is active, so post-interaction output always states the scope.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 10: `window new` described as a tab in output and help while the tab-management reference calls it a separate window; popup handling is undocumented

**Severity:** Low
**Category:** Documentation

#### Reproduction

./b4w.ps1 -s frames-eval window new "http://localhost:18080/b4/frame-other.html"
# → 'Created tab with GUID: …' / 'Switched to tab 0 …'
./b4w.ps1 help "window new"
# → 'Create a new browser window (equivalent to opening a new tab)'
# references/tab-management.md:30 → 'window new is the only command that creates a separate browser window'

#### Expected Behavior

One consistent story: what `window new` creates versus `tab-new`, and how a page-opened popup (window.open / target=_blank) shows up and is selected.

#### Actual Behavior

Three descriptions disagree (output says tab; command help says window-equivalent-to-tab; reference doc says separate window), and no document explains popup windows at all — the evaluation question 'window new vs tab-new vs popup' is unanswerable from the docs.

#### Root Cause Analysis

Documentation drift: the headless backend models every target as a tab (tab-list/page-info show '(2/2 tabs total)'), but the CLI kept the Browser4 'window' command name and the reference retains pre-headless wording; popup targets were never documented.

#### Code Pointer

`skills/browser4-cli/references/tab-management.md:30, skills/browser4-cli/references/tabs.md, cli/browser4-cli/src/commands.rs (window new description)`

#### AI Suggested Improvement

- Align the wording: state that in managed/headless mode `window new` creates an additional tab/target in the same browser, and how (if at all) it differs from `tab-new` (auto-select vs manual `tab-select`).
- Document popups: whether they appear in tab-list, how to select them, and any auto-attach behavior.
- Have the command output print one consistent noun ('tab') and update tab-management.md to match.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 11: Recovery hints name MCP methods (`frameList()`, `frameMain()`) instead of CLI commands, and user errors are prefixed `[INTERNAL]`

**Severity:** Low
**Category:** UX

#### Reproduction

./b4w.ps1 -s frames-eval frame "#no-such-frame"
# → Error: [INTERNAL] frame_switch failed: Frame not found: #no-such-frame. List the page frames with `frameList()` and switch by …
# (stale-scope errors say: 'Switch back to the main frame (`frameMain()`)')

#### Expected Behavior

The error should name the CLI commands a user can actually run (`frames`, `frame main`) and avoid the `[INTERNAL]` classification for an expected user error.

#### Actual Behavior

Hints reference API names that are not CLI commands, and a plain 'frame not found' is labelled [INTERNAL] (misleading severity signal for humans and scripts).

#### Root Cause Analysis

The driver/MCP layer composes the message for an API audience (frameList()/frameMain() are the MCP tool names) and tags the failure as INTERNAL; the CLI passes the text through unchanged.

#### Code Pointer

`browser4-agentic BrowserTabToolExecutor (frame switch/main error text) and cli/browser4-cli frame command error rendering`

#### AI Suggested Improvement

- Translate MCP method names in user-facing errors to CLI commands (frameList() → `frames`, frameMain() → `frame main`).
- Use a non-INTERNAL error class for 'frame not found' (it is a user-input error) so exit-code/messaging tiers stay meaningful.
- Keep one canonical 'how to recover' sentence shared by frame-switch and stale-scope errors.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 12: `fill` error message dumps the full MCP tool help (Kotlin signature, examples) into stderr

**Severity:** Low
**Category:** UX

#### Reproduction

./b4w.ps1 -s frames-eval2 fill "#no-such-input" "x"
# → [INTERNAL] browser_type failed: fill: no element found for selector [#no-such-input]. …
#   followed by ~800 chars: 'tab.fill(selector: String, text: String) Arguments: … Examples: … driver.fill(…)'

#### Expected Behavior

A concise error naming the selector and the fix (re-snapshot / check scope); the API help should be optional (e.g. --verbose or a doc pointer).

#### Actual Behavior

The first line is good, but it is followed by the full tool documentation and a Kotlin example, burying the actionable part and inflating error output (backend logged resultChars=864 for a simple not-found).

#### Root Cause Analysis

The backend attaches the tool spec/help text to every tool exception; the CLI prints it verbatim on the error path.

#### Code Pointer

`browser4-agentic AbstractToolExecutor error wrapping (help = …) and cli/browser4-cli error rendering (http.rs error path)`

#### AI Suggested Improvement

- Print the first sentence of the backend error by default and gate the full help behind `--verbose`/`--json`.
- Alternatively cap the help excerpt (e.g. first line) in human output while keeping full text in JSON for agents.

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

**Completion Status:** Partially Successful — AC1–AC7 were all satisfied (frame tree listing with active marker, switch by selector, in-frame fill+click with correct #pay-state, real scoping via is visible, nested frame switching, loud failure on a missing frame, and window/target creation visible in tab-list with page-info confirmation). AC8 fails: navigating while a frame is active does not reset the scope — goto exits 1 ('Navigation failed') although the navigation really happened, and reload exits 0 but leaves a stale scope that makes every subsequent element command fail until `frame main` is run. Two evaluation-blocking reliability defects were also confirmed on fresh sessions: `wait <selector>` and `click` report success unconditionally.

**Success Rate:** 85% — 7 of 8 acceptance criteria pass end-to-end; the navigation/scope-reset criterion fails, and two verification primitives (wait, click) give false-success results that would invalidate naive automation built on them.

**Issues Found:** 12

**Major Blockers:** AC8 (stale frame scope after navigation; goto misreports failure) and the false-success primitives: `wait <selector>` never times out and `click` silently succeeds for nonexistent/out-of-scope selectors. Additionally the dev backend died mid-run with no shutdown log and no JVM crash dump, silently destroying the session and browser; the CLI then misreported it as 'No active browser session. Run open <url> first', which cost extra steps to diagnose.

**Most Confusing Aspects:** 1) Whether an interaction actually happened — the CLI's own success lines for wait/click cannot be trusted, so effects had to be re-verified via eval/DOM state. 2) Frame scope state: docs promise the scope resets on navigation, but after goto/reload the scope is stale and every element command errors until `frame main`; nothing in element-command help even mentions that selectors are frame-scoped. 3) Mixed vocabulary for the same concept (window vs tab) across help, output and reference docs, with popups undocumented. 4) Machine-oriented error text aimed at users (frameList()/frameMain(), [INTERNAL] prefix, full Kotlin API help appended to a simple 'not found').

**Most Valuable Improvements:** 1) Make failure states loud and true: wait must time out non-zero, click/fill must fail on unmatched selectors, and frame-scoped misses should hint that the element exists in another frame. 2) Make navigation clear the frame scope at the driver level and stop reporting a successful navigation as failed. 3) When the backend is down, say so and auto-start it instead of claiming 'no active browser session'; log a reason on backend shutdown. 4) Surface the active frame scope in command help and in element-command output so scoping is discoverable and always visible.

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

#### Issue 1: `wait <selector>` always reports success and can never fail on timeout

./b4w.ps1 -s frames-eval wait "#definitely-no-such-el-xyz" --timeout 4000
# → ✓ Element found: #definitely-no-such-el-xyz   (exit 0)
./b4w.ps1 -s frames-eval wait "e999999" --timeout 3000
# → ✓ Element found: e999999   (exit 0)
# contrast: ./b4w.ps1 -s frames-eval is visible "#definitely-no-such-el-xyz" → false

#### Issue 2: `click` reports success for selectors that match nothing (including out-of-frame targets), silently doing nothing

./b4w.ps1 -s frames-eval2 click "#no-such-button"
# → ✓ Clicked #no-such-button   (exit 0, nothing exists)
./b4w.ps1 -s frames-eval2 frame "#pay-frame"
./b4w.ps1 -s frames-eval2 click "#main-button"
# → ✓ Clicked #main-button   (exit 0) but eval "window.__frameSwitchState.mainButtonClicks" stays 1
./b4w.ps1 -s frames-eval2 frame main; ./b4w.ps1 -s frames-eval2 click "#main-button" → counter becomes 2
# contrast: ./b4w.ps1 -s frames-eval2 fill "#no-such-input" x → exit 1, 'no element found for selector'

#### Issue 3: Navigating while a frame is active: `goto` reports failure though the navigation succeeds, and the stale frame scope then breaks every element command (AC8 fails)

./b4w.ps1 -s frames-eval open --headless "http://localhost:18080/b4/frame-switch.html"
./b4w.ps1 -s frames-eval frame "#pay-frame"
./b4w.ps1 -s frames-eval goto "http://localhost:18080/b4/frame-switch.html"
# → exit 1: ❌ Navigation failed … The selected frame 'payframe' is not reachable …
# (the page DID navigate: frame ids change, document state resets)
./b4w.ps1 -s frames-eval is visible "#main-button"
# → exit 1: browser_is_visible failed: The selected frame 'payframe' is not reachable
# … keeps failing until `frame main` (or a `frames` listing) is run.
./b4w.ps1 -s frames-eval frame "#pay-frame" && ./b4w.ps1 -s frames-eval reload
# → reload exits 0, but the next element command fails with the same stale-frame error

#### Issue 4: Backend died mid-session without any log; page-dependent commands then misreport 'No active browser session' and do not auto-start the backend, while the session and tabs are silently lost

Run a session normally (e.g. the frames scenario). At some point:
./b4w.ps1 -s frames-eval eval "window.__frameSwitchState.mainButtonClicks"
# → exit 3: Error: No active browser session. Run 'browser4-cli.exe open <url>' or 'browser4-cli.exe goto <url>' first to load a page, then try 'eval' again.
./b4w.ps1 list
# → 'Browser4 backend is not started or unreachable at http://127.0.0.1:8285. Showing local persisted sessions only'
# The next command (page-info) auto-starts the backend on port 8282, then: '🔐 Session refresh needed — The saved session expired or is no longer usable.' All tabs/browser state gone.

#### Issue 5: No hint that a failing selector exists in another frame (silent 'not found' in the wrong scope)

On the fixture, from the main scope:
./b4w.ps1 -s frames-eval get text "#pay-heading"   # exists in payframe
# → null / 'No elements matched "#pay-heading".'  (no mention of frames)
While scoped to payframe:
./b4w.ps1 -s frames-eval get text "#main-heading"  # exists in main
# → null / same message
./b4w.ps1 -s frames-eval is visible "#card-number" # → false, silently

#### Issue 6: Element-command help never mentions frame scoping

./b4w.ps1 help fill | grep -i frame        # (no match)
./b4w.ps1 help click | grep -i frame       # (no match)
./b4w.ps1 help "is visible" | grep -i frame # (no match)
./b4w.ps1 help snapshot | grep -i frame    # (no match)

#### Issue 7: False 'Possible blocked/challenge page' advisory fires on legitimate tiny fixture pages

./b4w.ps1 -s frames-eval goto "http://localhost:18080/b4/frame-other.html"
# or: ./b4w.ps1 -s frames-eval page-info  (while that page is active)
# → ⚠ Possible blocked/challenge page — detected page text is implausibly empty (50 chars) after a successful navigation …

#### Issue 8: `frames` output is raw backend JSON while the reference doc shows a human-readable frame tree

./b4w.ps1 -s frames-eval frames

#### Issue 9: `frame main` succeeds silently — no confirmation the scope was cleared

./b4w.ps1 -s frames-eval frame "#pay-frame"   # prints frame JSON
./b4w.ps1 -s frames-eval frame main               # prints nothing, exit 0

#### Issue 10: `window new` described as a tab in output and help while the tab-management reference calls it a separate window; popup handling is undocumented

./b4w.ps1 -s frames-eval window new "http://localhost:18080/b4/frame-other.html"
# → 'Created tab with GUID: …' / 'Switched to tab 0 …'
./b4w.ps1 help "window new"
# → 'Create a new browser window (equivalent to opening a new tab)'
# references/tab-management.md:30 → 'window new is the only command that creates a separate browser window'

#### Issue 11: Recovery hints name MCP methods (`frameList()`, `frameMain()`) instead of CLI commands, and user errors are prefixed `[INTERNAL]`

./b4w.ps1 -s frames-eval frame "#no-such-frame"
# → Error: [INTERNAL] frame_switch failed: Frame not found: #no-such-frame. List the page frames with `frameList()` and switch by …
# (stale-scope errors say: 'Switch back to the main frame (`frameMain()`)')

#### Issue 12: `fill` error message dumps the full MCP tool help (Kotlin signature, examples) into stderr

./b4w.ps1 -s frames-eval2 fill "#no-such-input" "x"
# → [INTERNAL] browser_type failed: fill: no element found for selector [#no-such-input]. …
#   followed by ~800 chars: 'tab.fill(selector: String, text: String) Arguments: … Examples: … driver.fill(…)'

