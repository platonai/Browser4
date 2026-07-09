# browser4-cli Evaluation Report — Tab Management

**Date:** 2026-07-09
**Task:** Tab management workflow (navigation, tab creation, tab switching, tab closing, snapshot verification)
**CLI Version:** 0.1.29
**Server Version:** v4.11.15
**Invocation Method:** `cargo run --manifest-path cli/browser4-cli/Cargo.toml --quiet --`

---

## A. Task Result

### Task: Multi-tab browser workflow

The task involved: navigating to 3 pages across 3 tabs, switching between tabs with snapshot verification, closing a tab, and verifying the remaining tabs.

**Final Status:** ⚠️ Partially Complete

The task could not be completed as specified due to critical tab management bugs. Navigation (`goto`) and snapshot capture worked reliably in isolation, but tab operations (`tab-new`, `tab-close`, `tab-select`) exhibited severe reliability issues including full tab loss and backend timeouts.

### Steps Completed vs Attempted:

| # | Step | Status | Notes |
|---|------|--------|-------|
| 1 | Goto Wikipedia Web scraping | ✅ Success | Page loaded correctly |
| 2 | List tabs (initial state) | ✅ Success | Showed 1 tab, expected |
| 3 | New tab → Hacker News | ✅ Success | Tab created at index 1 |
| 4 | New tab → Data mining | ⚠️ Partial | Created but index confusion (reported as tab 2, actually at index 1) |
| 5 | List all 3 tabs | ✅ Success | All 3 tabs visible with correct titles/URLs |
| 6 | Switch to tab 0 + snapshot | ✅ Success | Wikipedia Web scraping confirmed |
| 7 | Switch to tab 2 + snapshot | ✅ Success | Hacker News confirmed |
| 8 | Close Hacker News tab | ⚠️ Surface success | Returned `true`, but all tabs lost afterward |
| 9 | List tabs after close | ❌ Failed | Only `about:blank` remained; both Wikipedia tabs lost |
| 10 | Verify remaining tabs | ⚠️ Recovered | Had to re-navigate manually; both pages loaded directly via `goto` |

---

## B. Execution Trace

### Commands Used (in order):

```
1. cargo run --manifest-path cli/browser4-cli/Cargo.toml --quiet -- --help
2. cargo run --manifest-path cli/browser4-cli/Cargo.toml --quiet -- goto "https://en.wikipedia.org/wiki/Web_scraping"
3. cargo run --manifest-path cli/browser4-cli/Cargo.toml --quiet -- tab-list
4. cargo run --manifest-path cli/browser4-cli/Cargo.toml --quiet -- tab-new "https://news.ycombinator.com"
5. cargo run --manifest-path cli/browser4-cli/Cargo.toml --quiet -- tab-new "https://en.wikipedia.org/wiki/Data_mining"
6. cargo run --manifest-path cli/browser4-cli/Cargo.toml --quiet -- tab-list
7. cargo run --manifest-path cli/browser4-cli/Cargo.toml --quiet -- tab-select 0
8. cargo run --manifest-path cli/browser4-cli/Cargo.toml --quiet -- snapshot -v 0
9. cargo run --manifest-path cli/browser4-cli/Cargo.toml --quiet -- tab-select 2
10. cargo run --manifest-path cli/browser4-cli/Cargo.toml --quiet -- snapshot -v 0
11. cargo run --manifest-path cli/browser4-cli/Cargo.toml --quiet -- tab-close 2
12. cargo run --manifest-path cli/browser4-cli/Cargo.toml --quiet -- tab-list          # FAILED: only about:blank
13. cargo run --manifest-path cli/browser4-cli/Cargo.toml --quiet -- tab-select 0      # about:blank
14. cargo run --manifest-path cli/browser4-cli/Cargo.toml --quiet -- tab-select 1      # ERROR: out of range
15. cargo run --manifest-path cli/browser4-cli/Cargo.toml --quiet -- list              # session active
16. cargo run --manifest-path cli/browser4-cli/Cargo.toml --quiet -- goto "..."        # re-navigate (recovery)
17. cargo run --manifest-path cli/browser4-cli/Cargo.toml --quiet -- tab-new "..."     # re-create Data mining
18. cargo run --manifest-path cli/browser4-cli/Cargo.toml --quiet -- tab-list          # both tabs visible
19. cargo run --manifest-path cli/browser4-cli/Cargo.toml --quiet -- tab-select 0      # Data mining
20. cargo run --manifest-path cli/browser4-cli/Cargo.toml --quiet -- tab-select 1      # ERROR: out of range again
21. cargo run --manifest-path cli/browser4-cli/Cargo.toml --quiet -- tab-list          # HTTP timeout
22. cargo run --manifest-path cli/browser4-cli/Cargo.toml --quiet -- status            # server UP
23. cargo run --manifest-path cli/browser4-cli/Cargo.toml --quiet -- tab-list          # back to about:blank
24. cargo run --manifest-path cli/browser4-cli/Cargo.toml --quiet -- goto "..."        # recovery: Data mining ✅
25. cargo run --manifest-path cli/browser4-cli/Cargo.toml --quiet -- goto "..."        # recovery: Web scraping ✅
26. cargo run --manifest-path cli/browser4-cli/Cargo.toml --quiet -- snapshot -v 0 --stdout  # final verification
```

### Workarounds Required:

1. **After `tab-close` destroyed all tabs:** Re-navigated to pages individually via `goto` instead of using tab navigation.
2. **After second tab loss:** Gave up on multi-tab workflow entirely; verified pages via sequential `goto` calls.
3. **After backend timeout:** Waited and retried; server recovered but tabs were gone.

### Important Decisions:

- Used `--quiet` flag to suppress cargo build output (as documented in development.md).
- Used `--stdout` for snapshot output when verifying content (avoids opening files).
- Did NOT use Playwright, Puppeteer, or any other browser automation tool.

---

## C. Issues Found

### Issue 1: Closing a tab destroys all other tabs (data loss)

**Severity:** Critical

**Category:** Reliability

**Reproduction:**
```
browser4-cli goto "https://en.wikipedia.org/wiki/Web_scraping"
browser4-cli tab-new "https://news.ycombinator.com"
browser4-cli tab-new "https://en.wikipedia.org/wiki/Data_mining"
browser4-cli tab-list            # shows 3 tabs
browser4-cli tab-close 2         # close HN tab
browser4-cli tab-list            # shows only about:blank, all tabs lost
```

**Expected:** Only the Hacker News tab (index 2) should be closed. The two Wikipedia tabs should remain intact with their loaded content.

**Actual:** After closing tab 2, `tab-list` showed only one tab: `about:blank`. Both Wikipedia tabs with their loaded content were irretrievably lost. The session remained active but all browser tabs were destroyed.

**Root Cause:** Likely a bug in the backend's tab management logic where closing a tab triggers an incorrect tab cleanup that removes all tabs rather than just the targeted one. Could be related to how the browser context handles CDP target destruction events — closing one target may trigger cleanup of the entire browsing context. Needs investigation in the `browser_tabs` / `browser.switchTab` / `browser.closeTab` MCP tool chain.

**Code Pointer:** Needs investigation in browser tab management code — likely in `WebDriver.kt` (tab close implementation) or the `BrowserTabToolExecutor.kt` that dispatches tab operations.

**AI Suggested Improvement:**
- Close only the specific tab by its CDP target ID, not by destroying the entire browser context
- Add integration tests that verify other tabs survive `tab-close`
- Add a guard that refuses to close the last tab if it would destroy all browsing state
- Log the actual CDP `Target.closeTarget` call and verify it targets only the specified tab

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 2: Tab switching intermittently loses other tabs

**Severity:** Critical

**Category:** Reliability

**Reproduction:**
```
browser4-cli tab-new "https://en.wikipedia.org/wiki/Data_mining"   # tab 1
browser4-cli tab-new "https://en.wikipedia.org/wiki/Web_scraping"  # tab 2
browser4-cli tab-list            # shows 2 tabs
browser4-cli tab-select 0        # select Data mining
browser4-cli tab-select 1        # ERROR: Tab index '1' out of range; found 1 tabs
```

**Expected:** After switching to tab 0, tab 1 should still exist. Switching should be a viewport change, not a destructive operation.

**Actual:** After switching to tab 0, tab 1 disappeared. `tab-select 1` error: "Tab index '1' out of range; found 1 tabs." This happened twice — once after `tab-close` (Issue 1) and again after re-creating tabs and switching.

**Root Cause:** The tab tracking state appears to lose tabs during `tab-select` operations. Could be a race condition between the CDP target activation and the internal tab list maintenance, or the tab list is being re-initialized incorrectly after a tab switch. The error message correctly reports the state but the state itself is wrong.

**Code Pointer:** Check `browser.switchTab()` implementation — the tab list may be rebuilt from CDP targets but losing entries during the rebuild.

**AI Suggested Improvement:**
- Audit the tab tracking data structure for thread-safety or stale-state issues
- Ensure `switchTab` is a read-only operation on the tab list (no mutation)
- Add defensive logging of tab count before/after each tab operation
- Create an E2E test that creates 3 tabs, switches between all of them, and verifies all 3 remain

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 3: Backend timeout after failed tab operations

**Severity:** High

**Category:** Reliability

**Reproduction:**
```
browser4-cli tab-list   # after tabs have been lost from tab-close/select
# Result: Error: HTTP request timed out [tool=browser_tabs, endpoint=http://localhost:8182/mcp/call-tool, timeout=30s]
```

**Expected:** The backend should gracefully handle queries about tab state, even when tabs have been unexpectedly destroyed. A timeout suggests the backend is hanging, not returning an error.

**Actual:** HTTP 30-second timeout. The backend became unresponsive after the tab management issues. The `status` command reported "UP" but `tab-list` timed out. After waiting, the backend recovered but all tabs were gone.

**Root Cause:** Likely the backend entered a bad state (deadlock, infinite loop, or hung coroutine) when trying to enumerate tabs after they'd been destroyed by the `tab-close` bug. The CDP connection to the browser may have been in an inconsistent state, causing the `browser_tabs` MCP tool to hang waiting for a response that never comes.

**Code Pointer:** The `browser_tabs` MCP tool implementation hangs — check for missing timeout on CDP calls, or a deadlock in the tab enumeration logic.

**AI Suggested Improvement:**
- Add a hard timeout (e.g., 5 seconds) on CDP `Target.getTargets` calls
- Return partial results rather than hanging if some CDP calls fail
- Detect and recover from hung CDP connections (circuit breaker pattern)
- Log warnings when tab enumeration takes >1 second to aid debugging

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 4: tab-new reports wrong index for inserted tab

**Severity:** Medium

**Category:** UX / Product

**Reproduction:**
```
browser4-cli goto "https://en.wikipedia.org/wiki/Web_scraping"    # tab 0
browser4-cli tab-new "https://news.ycombinator.com"                # tab 1 created
browser4-cli tab-new "https://en.wikipedia.org/wiki/Data_mining"   # reports "Switched to tab 2"
browser4-cli tab-list  # shows Data mining at index 1, HN at index 2
```

**Expected:** The "Switched to tab 2" message should reflect the actual index where the user will find the tab. Or the tab should be appended at the end (index 2) as the message implies.

**Actual:** `tab-new` reported "Switched to tab 2 (https://en.wikipedia.org/wiki/Data_mining)" but the tab was actually inserted at index 1. The index was off by one because the tab was inserted after the current tab (index 0) rather than appended at the end — matching standard browser behavior, but contradicting the CLI message.

**Root Cause:** The tab was inserted after the active tab (standard Chromium behavior for `chrome.tabs.create`), but the CLI message reports the pre-insertion total count as the new index, not accounting for the insertion position. These two interpretations of "new tab index" are inconsistent.

**AI Suggested Improvement:**
- Report the actual index after creation by querying the tab list post-insertion
- Document the insertion behavior: "new tabs are created after the current tab"
- Consider adding a `--append` flag to always create tabs at the end
- Add a `tab-new --background` flag to create tabs without switching to them

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 5: Snapshot after tab-new shows previous page content

**Severity:** Medium

**Category:** Reliability / UX

**Reproduction:**
```
browser4-cli tab-new "https://en.wikipedia.org/wiki/Data_mining"
# Output shows: "Switched to tab 1"
# But snapshot shows: "Page URL: https://en.wikipedia.org/wiki/Web_scraping"
```

**Expected:** After creating a new tab and navigating to a URL, the snapshot should show the new page's content.

**Actual:** The snapshot captured after `tab-new` shows the *previous* page's content (the page from the tab we were on before creating the new tab). The new page eventually loads (confirmed by subsequent `tab-list`), but the initial snapshot doesn't reflect it.

**Root Cause:** `tab-new` switches to the new tab immediately but doesn't wait for the page to load before capturing the automatic snapshot. The snapshot captures the previous tab's content or a transitional state, giving the user misleading information about what page they're on.

**AI Suggested Improvement:**
- Wait for the new tab's page to reach at least `DOMContentLoaded` before capturing the automatic snapshot
- Or, skip the automatic snapshot on `tab-new` and let the user capture one explicitly
- Report the actual page URL from the snapshot, not the requested URL, to make the disconnect visible
- Add a `--no-wait` flag for users who want the old behavior

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add details in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 6: Mixed output format — raw JSON in human-readable mode

**Severity:** Low-Medium

**Category:** UX

**Reproduction:**
```
browser4-cli tab-select 0
# Output includes: {"type":"ai.platon.browser4.chrome.PulsarWebDriver","description":"Driver#34"}
# Followed by normal human-readable Page/Snapshot output
```

**Expected:** In default (human-readable) mode, output should be consistently formatted for human consumption. JSON objects should only appear with the `--json` flag.

**Actual:** `tab-select` outputs a raw JSON object (`{"type":"...","description":"..."}`) on stdout before the human-readable sections. This is jarring and inconsistent — most other commands don't do this.

**Root Cause:** A debug or internal logging statement is writing directly to stdout instead of using the configured output formatter. The MCP tool response from `browser.switchTab` likely includes a driver descriptor that's being printed raw.

**Code Pointer:** `browser.switchTab` MCP tool returns a JSON result that gets printed before the formatted output. Look at `main.rs` tab-select handler or the MCP response formatter.

**AI Suggested Improvement:**
- Suppress raw MCP response JSON in human-readable mode; only show formatted output
- Or convert the driver info into a consistent human-readable line (e.g., "Driver: #34")
- Add a test that verifies no raw JSON appears in default output mode for all commands

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 7: tab-list triggers an unnecessary snapshot

**Severity:** Low

**Category:** UX / Performance

**Reproduction:**
```
browser4-cli tab-list
# Output includes:
# ### Snapshot
# [Snapshot](/home/vincent/.../snapshot-....yml)
```

**Expected:** Listing tabs is a read-only operation. It should not capture a page snapshot. Users run `tab-list` to see tab state, not to capture page structure.

**Actual:** Every `tab-list` invocation captures and saves a full accessibility tree snapshot. This is wasteful (I/O, time, disk space) and surprising — the user didn't ask for a snapshot.

**Root Cause:** `tab-list` shares a code path with commands that need post-interaction snapshots. The automatic snapshot behavior is not scoped to only state-mutating commands.

**AI Suggested Improvement:**
- Add `tab-list` to `no_snapshot_commands()` so it doesn't trigger an automatic snapshot
- If snapshot info is needed for tab-list display, use lightweight CDP queries instead of full AX tree capture
- Document which commands trigger automatic snapshots and which don't

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add details in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add details in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 8: Duplicate "Page" blocks in output

**Severity:** Low

**Category:** UX

**Reproduction:**
```
browser4-cli tab-select 0
# ### Page
# - Page URL: https://en.wikipedia.org/wiki/Web_scraping
# - Page Title: Web scraping - Wikipedia
# ### Page                             <-- duplicate
# - Page URL: https://en.wikipedia.org/wiki/Web_scraping
# - Page Title: Web scraping - Wikipedia
```

**Expected:** Page information should appear once per command output.

**Actual:** Many commands (tab-select, tab-new, tab-list) show the "Page" section twice with identical content. This is noisy and confusing.

**Root Cause:** Two separate code paths are each printing the page information — likely once from the MCP tool response handler and again from the snapshot post-processing. These should be deduplicated.

**AI Suggested Improvement:**
- Identify and merge the two page-info printing paths
- Add a deduplication check: if the second Page block matches the first, suppress it
- If they differ (which would indicate a race), show both but label them clearly

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 9: Goto sometimes reconnects without navigating

**Severity:** Medium

**Category:** Reliability

**Reproduction:**
```
browser4-cli goto "https://en.wikipedia.org/wiki/Web_scraping"
# First attempt (after tab loss): "Reconnected to existing session on about:blank"
#   Page URL: about:blank   <-- didn't navigate!
# Second attempt: "Reconnected to existing session on about:blank"
#   Page URL: https://en.wikipedia.org/wiki/Web_scraping   <-- now it works
```

**Expected:** `goto <url>` should always navigate to the specified URL. If the session is reconnected, navigation should still occur.

**Actual:** After the session was in a broken state (about:blank after tab loss), the first `goto` only reconnected to the session without navigating. The URL parameter was effectively ignored. A second identical `goto` worked correctly.

**Root Cause:** Session reconnection logic may short-circuit navigation when the session already exists. If the current page matches or the session state is ambiguous, `goto` may decide no navigation is needed — but it should always navigate when given an explicit URL.

**AI Suggested Improvement:**
- `goto` with an explicit URL should always navigate, regardless of session state
- If reconnection is desired without navigation, users should use `open` instead
- Add a `--force` flag to ensure navigation even when reconnecting
- Document the difference between `goto` (always navigates) and `open` (reconnects if possible)

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add details in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add details in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 10: Tab management commands not mentioned in core skill documentation

**Severity:** Medium

**Category:** Discoverability

**Reproduction:**
1. Read `skills/browser4-cli/SKILL.md` — the main user-facing documentation.
2. Search for "tab" — only found in the warning about ref lifecycle ("tab switches").
3. The Command Map (Section 3) mentions `tab-*` only in the last row as "Visual capture & viewport control" with no elaboration.

**Expected:** Tab management (`tab-list`, `tab-new`, `tab-close`, `tab-select`) should have a dedicated row in the Command Map and at least a brief usage example in the Quick Patterns section.

**Actual:** Tab management commands are only discoverable via `--help`. The SKILL.md doesn't explain the tab lifecycle, index semantics, or how tabs interact with sessions. Users must discover tab commands through trial and error.

**AI Suggested Improvement:**
- Add a "Tab Management" row to the Command Map table (Section 3)
- Add a "Tab Management" quick pattern example showing multi-tab workflow
- Document tab index semantics: zero-based, new tabs inserted after current, indices shift when tabs close
- Add a note about the relationship between sessions and tabs (one session = one browser window with multiple tabs)

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 11: Empty page titles in output

**Severity:** Low

**Category:** Reliability / UX

**Reproduction:**
```
browser4-cli tab-new "https://news.ycombinator.com"
# Page Title:              <-- empty
# (later shows "Hacker News" after page finishes loading)
```

**Expected:** Page title should be shown once it's available, or a placeholder like "Loading..." should be shown. An empty string is ambiguous — is the page still loading, or does it genuinely have no title?

**Actual:** Hacker News initially showed an empty title. The title appeared in later commands after the page finished loading. An empty title field is indistinguishable from a page that genuinely has no `<title>` tag.

**AI Suggested Improvement:**
- Show "Loading..." or "—" instead of an empty string when title is not yet available
- Or, only show the title field when it has a value
- Consider adding a loading indicator in the Page section for pages that haven't fully loaded

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 12: No confirmation or visible feedback for destructive tab-close

**Severity:** Low

**Category:** UX

**Reproduction:**
```
browser4-cli tab-close 2
# Output: true
```

**Expected:** A human-readable confirmation like "Tab 2 closed (Hacker News)" or at minimum "Tab 2 closed." Returning just `true` is machine-oriented and provides no context about what was closed.

**Actual:** The output is just `true` — a bare boolean with no indication of which tab was closed. In context of the bugs where closing one tab destroys all tabs, this minimal feedback makes it harder to notice that something went wrong.

**AI Suggested Improvement:**
- Output "Closed tab 2: Hacker News" or similar human-readable confirmation
- Include the tab's title and URL in the confirmation for verification
- If the tab close affected other tabs (unexpectedly), surface a warning

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
⚠️ **Partially Complete** — The core task could not be completed as specified due to critical tab management bugs. Navigation and snapshot features worked reliably in isolation, but the multi-tab workflow was fundamentally broken.

### Estimated Task Success Rate
**~40%** — Individual operations (goto, snapshot, tab-list display) worked ~90% of the time, but the multi-tab orchestration (create multiple tabs → switch between them → close one → use the others) failed consistently. A first-time user would not be able to complete this task.

### Number of Issues Found
**12 issues**:
- 2 Critical (tab loss bugs)
- 1 High (backend timeout)
- 4 Medium (tab-new indexing, snapshot timing, goto inconsistency, missing docs)
- 5 Low-Medium/Low (mixed output, duplicate blocks, unnecessary snapshots, empty titles, poor feedback)

### Major Blockers
1. **Tab-close destroys all tabs** — This is the primary blocker. The entire multi-tab workflow becomes impossible after closing any tab.
2. **Tab-select loses other tabs** — Even without closing, switching between tabs can cause data loss.
3. **No recovery mechanism** — Once tabs are lost, there's no way to get them back. The user must re-navigate from scratch.

### Most Confusing Aspects
1. **Unpredictable tab indices** — `tab-new` reports one index but the tab appears at another, and indices shift in ways that aren't documented.
2. **Snapshot timing** — After `tab-new`, the snapshot shows the old page, creating momentary confusion about which tab is active.
3. **Silent data loss** — Tabs disappear without warning or error. The `tab-list` output just shows fewer tabs with no indication anything went wrong.
4. **Output inconsistency** — Some commands produce JSON, some produce formatted text, some produce both. The "Page" section appears twice.

### What Worked Well
1. **Help output** — `--help` is well-organized, comprehensive, and uses clear command categories.
2. **Navigation** — `goto` reliably loads pages and auto-manages sessions.
3. **Snapshots** — The accessibility tree snapshot is detailed, well-formatted, and provides useful element refs.
4. **Session management** — The auto-start daemon and backend setup worked seamlessly. No manual server configuration needed.
5. **Error messages for valid errors** — "Tab index '1' out of range; found 1 tabs" is clear and actionable.
6. **Tips** — The 💡 tips on stderr are genuinely helpful for discoverability.
7. **Development documentation** — `development.md` clearly explains how to run from source.

### Most Valuable Improvements
1. **Fix tab management reliability** (Issues 1, 2, 3) — These are showstoppers for any multi-tab workflow.
2. **Add tab documentation to SKILL.md** (Issue 10) — Users can't use features they can't find.
3. **Fix tab-new index reporting** (Issue 4) — Reduces confusion for first-time users.
4. **Wait for page load before snapshot on tab-new** (Issue 5) — Prevents misleading output.
5. **Clean up output formatting** (Issues 6, 7, 8) — Professional polish for CLI output.

### Overall Usability Rating: **5/10**

**Rationale:**
- The single-tab workflow (goto → snapshot → interact → extract) is well-designed and works reliably. The help system, tips, and auto-setup are excellent for a first-time user. **(8/10 for single-tab)**
- The multi-tab workflow is severely broken. Critical bugs make any non-trivial tab orchestration impossible. The feature exists but doesn't work. **(2/10 for multi-tab)**
- The documentation gap for tab management means even discovering the commands requires reading raw `--help` output rather than the curated skill guide. **(5/10 for discoverability)**

The core architecture and single-page workflow are strong, but the tab management subsystem needs significant reliability work before it can be considered production-ready for multi-tab use cases.

---

*Report generated as part of browser4-cli usability evaluation.*
*Session: 2026-07-09, CLI 0.1.29, Server v4.11.15*
