---

# Evaluation Report: Attach to Existing Browser via CDP

## A. Task Result

**Task**: Attach to a running Chrome browser via CDP, manage tabs, take screenshots, save state, and close.

**Outcome**: Partially successful. The core attach workflow works — connecting to Chrome, listing tabs, taking screenshots, capturing snapshots, and saving state all function. However, tab switching in the attached session has a critical reliability bug that breaks the session state.

## B. Execution Trace

| Step | Command | Result |
|------|---------|--------|
| 1 | Read help + SKILL.md + attach.md | Learned available commands and conventions |
| 2 | Started Chrome with `--remote-debugging-port=9222` | Chrome launched, CDP available on port 9222 |
| 3 | `attach --cdp chrome` | ✅ Attached to Chrome, session DEFAULT created |
| 4 | `attach --endpoint http://localhost:8182 --cdp chrome` | ✅ Combined flags work |
| 5 | `tab-list` | ✅ Listed 1 tab (Amazon search page) |
| 6 | `screenshot --filename attached-screenshot.png` | ✅ 1.26MB screenshot saved |
| 7 | `snapshot -v 0` | ✅ 611-node accessibility tree captured |
| 8 | `state-save attached-state.json` | ✅ 13KB state file with cookies saved |
| 9 | `tab-new "https://example.com"` | ✅ New tab created, reported as switched |
| 10 | `tab-select 0` + screenshot | ✅ Tab 0 (Amazon) screenshot saved |
| 11 | `tab-select 1` + screenshot | ❌ **Failed** — driver state corrupted |
| 12 | `close` | ✅ Disconnected cleanly, browser remains running |

**Workarounds**: None available for the tab-switching bug. The only path forward was to skip screenshot of tab 1 and document the failure.

## C. Issues Found

### Issue 1: Tab switching in attached sessions corrupts driver state

**Severity:** High

**Category:** Reliability

**Reproduction:**
```
cargo run -- attach --cdp chrome
cargo run -- tab-new "https://example.com"
cargo run -- tab-select 1
cargo run -- screenshot --filename test.png
```

**Expected:** Tab switches to example.com, screenshot captures that page.

**Actual:** `tab-select 1` reports "Switched to tab 1 (https://example.com)" but the subsequent `page` output still shows the previous tab's URL (Amazon). Screenshot times out after 30s with `HTTP request timed out`. Backend logs reveal:
```
⚠️ switchTab did not return a WebDriver; falling back to boundBrowser
⚠️ No driver is in front after switchTab
Oop, a bit slip-up executing action: [screenshot], retrying 1/2 time ... | No response | Page.captureScreenshot
```

**Root Cause:** The `AgentToolManager.switchTab` method does not properly re-establish the WebDriver frontend after switching tabs in an attached session. The "No driver is in front" warning indicates the browser context reference is lost after the tab switch, causing all subsequent CDP commands (`Page.captureScreenshot`) to get no response. The `PulsarSessionManager` also logs "Inconsistent driver/browser. Driver 11 state: INIT" consistently, suggesting a session initialization problem specific to attached (non-launched) browser sessions.

**Code Pointer:** `browser4-apps/browser4-bundle/.../AgentToolManager.kt:switchTab()` — the warning "switchTab did not return a WebDriver" originates here; the fix likely involves ensuring the returned driver is properly set as the active front driver after switching tabs in an attached session.

**AI Suggested Improvement:**
- After `switchTab`, validate that the returned driver is non-null and call `setFrontDriver()` if needed
- Add a retry mechanism in `switchTab` that re-queries the CDP browser context if the tab switch doesn't immediately produce a valid driver
- Surface a clear CLI error immediately when "No driver is in front" rather than letting subsequent commands silently timeout 30s later

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 2: `tab-select` reports incorrect page info after switch

**Severity:** Medium

**Category:** Reliability

**Reproduction:**
```
cargo run -- attach --cdp chrome
cargo run -- tab-new "https://example.com"
cargo run -- tab-select 1
```

**Expected:** After switching to tab 1, the page URL/title shown in output should reflect `https://example.com` / "Example Domain".

**Actual:** The output shows `Page URL: https://www.amazon.com/...` (tab 0's URL), despite the command reporting "Switched to tab 1 (https://example.com)". The page metadata fetched after the switch is stale — it reads from the old (pre-switch) page context.

**Root Cause:** Related to Issue 1 — after the `switchTab` fails to put a driver in front, subsequent `page_url`/`page_title` calls fall back to whatever stale driver reference still exists. The page info is read from the old driver rather than the newly selected tab.

**Code Pointer:** Same as Issue 1 — the `page_url`/`page_title` tool calls after `tab-select` should use the newly switched tab's context.

**AI Suggested Improvement:**
- Fix the root cause from Issue 1
- Add a post-switch validation: after `tab-select`, issue a `page_url` call against the new tab and verify it matches the expected URL, retrying if not
- Warn the user if the page info after a tab switch doesn't match the tab list entry

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 3: Persistent "Inconsistent driver/browser" warnings in backend logs

**Severity:** Medium

**Category:** Reliability

**Reproduction:**
```
cargo run -- attach --cdp chrome
cargo run -- tab-list
# Check backend logs: cargo run -- doctor
```

**Expected:** Clean logs after successful attach, no warnings about driver/browser inconsistency.

**Actual:** Every MCP tool call after attach logs: `Inconsistent driver/browser. Driver 11 state: INIT browser {PULSAR_CHROME, ...} state: Active,Connected`. This appears for all operations — snapshot, tab-list, screenshot, page_url, etc.

**Root Cause:** The `PulsarSessionManager` tracks driver state and browser state separately. In an attached session, the driver is in `INIT` state (never went through the normal launch flow) while the browser is `Active,Connected` (because it was attached externally). The state comparison considers this "inconsistent" even though it's the expected state for attached sessions.

**Code Pointer:** `browser4-apps/browser4-bundle/.../PulsarSessionManager.kt` — the consistency check between driver state and browser state needs an exception for attached sessions where `INIT` driver with `Active,Connected` browser is normal.

**AI Suggested Improvement:**
- Add a session flag `isAttached` that suppresses the inconsistency warning for attached sessions
- Or introduce a new driver state `ATTACHED` distinct from `INIT` that pairs correctly with `Active,Connected` browser state

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 4: Screenshot timeout error message is unhelpful

**Severity:** Low

**Category:** UX

**Reproduction:**
```
cargo run -- attach --cdp chrome
cargo run -- tab-select 1
cargo run -- screenshot --filename test.png
```

**Expected:** If screenshot fails, the error message should indicate why (e.g., "Page not available", "Tab not active", "Driver state error").

**Actual:** `Error: HTTP request timed out [tool=browser_take_screenshot, endpoint=http://localhost:8182/mcp/call-tool, timeout=30s]` — a generic HTTP timeout that tells the user nothing about what went wrong. The user has to wait 30 seconds to learn it failed.

**Root Cause:** The CLI layer sends the MCP tool call and waits for a response. When the backend hangs (because the underlying CDP command never completes due to the broken driver state), the only signal is a timeout. The CLI has no awareness of backend-side errors or warnings.

**Code Pointer:** `cli/browser4-cli/src/` — the MCP client timeout handler; could be improved to poll for backend-side error state or include the last-known session status in the timeout message.

**AI Suggested Improvement:**
- Reduce the MCP call timeout for operations that should be fast (screenshots should not need 30s)
- After a timeout, automatically run a lightweight health check and report findings
- Include backend-side warnings from the last operation in the error message

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 5: `snapshot` auto-triggers after attach and tab operations with no way to disable

**Severity:** Low

**Category:** UX

**Reproduction:** Run any of `attach`, `tab-list`, `tab-new`, or `tab-select`.

**Expected:** The command performs only the requested operation. Optional snapshot is available via a flag.

**Actual:** Every one of these commands automatically triggers a full snapshot capture, adding latency and producing snapshot files the user may not want. For example, a simple `tab-list` takes multiple seconds because it also captures a snapshot.

**Root Cause:** The commands include an implicit post-operation snapshot for convenience, but this adds overhead and produces unwanted artifacts for users who only want to list tabs or switch tabs.

**Code Pointer:** The post-command hooks that trigger automatic snapshots in the CLI or backend command handlers.

**AI Suggested Improvement:**
- Add a `--no-snapshot` flag to suppress automatic snapshot capture
- Or make automatic snapshot opt-in (e.g., `--snapshot` flag) rather than opt-out
- At minimum, document this behavior clearly so users know to expect extra latency and snapshot files

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 6: `list` command shows "Reuse" but after attach, tab-select caused broken state

**Severity:** Medium

**Category:** Discoverability

**Reproduction:** After attaching, run `list` and observe "Next open: Reuse". Then attempt tab operations.

**Expected:** "Reuse" should mean the session is fully functional and all operations work as on a normally-launched session.

**Actual:** The session is listed as "Reuse" and appears healthy, but tab switching is broken. The session status display gives a false sense of health.

**Root Cause:** Session health check only verifies connectivity (browser is running, CDP is reachable), not functional integrity (can the driver actually execute commands across tabs?).

**AI Suggested Improvement:**
- Add a lightweight functional probe to session health checks (e.g., navigate a hidden tab, switch tabs, verify driver state post-switch)
- Surface a warning in `list` output when the session has warnings in the backend logs

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 7: `attach --endpoint` documentation is ambiguous about combined usage with `--cdp`

**Severity:** Low

**Category:** Documentation

**Reproduction:** Read the attach.md reference and `attach --help` output regarding `--endpoint`.

**Expected:** Clear documentation of what happens when `--endpoint` and `--cdp` are combined: does the attach happen at the remote server (browser4 attaches to CDP through the remote server), or locally (CLI switches to remote server, then the CDP attach is forwarded)?

**Actual:** Documentation says `--endpoint` "switches the CLI to the remote server" when used alone, and the example `attach --endpoint http://browser4-server:8182 --cdp chrome` appears in Patterns §3 but doesn't explain the execution model. The `--help` output also doesn't clarify the interaction.

**AI Suggested Improvement:**
- Add a sentence to attach.md explaining: "When combined with `--cdp`, the attach is routed through the remote Browser4 server, which connects to the browser on that machine. The browser must be running on the remote machine, not locally."
- Add this clarification to the `--help` output as well

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

## D. Overall Assessment

### Task Completion Status
**Partially completed (75%)**. Core attach, screenshot, snapshot, and state-save work. Tab switching is broken in attached sessions, preventing the multi-tab screenshot requirement from being fully met.

### Estimated Task Success Rate
- Attach by channel name: 100%
- Attach with `--endpoint`: 100%
- List tabs: 100%
- Screenshot (initial tab): 100%
- Snapshot: 100%
- State save: 100%
- Tab creation: 100%
- **Tab switching + screenshot: 0%** (blocked by bug)
- Session close: 100%

### Issues Found: 7 (1 High, 3 Medium, 3 Low)

### Major Blockers
1. **Tab switching corrupts driver state** (Issue 1) — the single biggest problem. Once you switch tabs in an attached session, the driver becomes unusable for subsequent operations.

### Most Confusing Aspects
- After `tab-select 1` says "Switched to tab 1", the page info still shows the old tab's URL. This is actively misleading.
- The automatic snapshot capture after every command adds unexpected latency and clutters the snapshot directory with files the user didn't ask for.

### Most Valuable Improvements
1. **Fix tab switching in attached sessions** — this is a critical bug that prevents the attach feature from being useful for multi-tab workflows.
2. **Surface backend warnings to the CLI** — the `doctor` logs clearly showed the problem but the CLI had no way to surface those warnings to the user.
3. **Add a `--no-snapshot` flag** — reduce latency for commands where the user doesn't need a snapshot.

### Overall Usability Rating: 6/10

**Strengths:**
- Help output is well-organized with clear command groupings
- Documentation (SKILL.md + references) is comprehensive with good examples
- Attach discovery works automatically (finds Chrome, probes ports)
- Core operations (screenshot, snapshot, state-save) work correctly when the session is healthy
- `close` behavior is correct (disconnects, leaves browser running)
- The `doctor` command is invaluable for diagnostics

**Weaknesses:**
- Tab switching in attached sessions is broken (critical bug)
- Unhelpful error messages (30s timeout with no root cause)
- Spammy backend warnings that should be suppressed for normal attached-session operation
- Automatic snapshot capture is surprising and adds latency
- Session health display is misleading (shows "Active/Reuse" even when broken)
