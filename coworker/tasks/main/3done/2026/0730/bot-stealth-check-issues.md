# Issues: Bot stealth check

> **Source:** `20260730-164010-Bot stealth check.full.md` | **Date:** 20260730-164010 | **Mode:** dev

## Scenario Background

### Task

The bot stealth evaluation is complete. The report is at `target/bot-stealth-report.md`. Key findings:

- **Browser4 successfully evades the most critical check:** `navigator.webdriver` is `false` across all 5 services
- **2 of 5 services gave a clean PASS** (Sannysoft, BrowserScan)
- **3 of 5 services flagged Browser4** for non-automation issues: `hardwareConcurrency` leakage in Web Workers, WebGL shader inconsistency, datacenter IP, and timezone mismatch
- **Google search was blocked** — Google served a CAPTCHA, preventing Phase 1 of the task

### Execution Context

- 30+ browser4-cli commands executed across `goto`, `snapshot`, `fill`, `eval`, `htmlsnapshot`, `screenshot`, `click`, `wait`, `tab-list`, `tab-new`, `tab-select`
- 8 workarounds required (detailed in evaluation)
- 5 bot detection services tested, 5 screenshots captured

---

## Issues Found (11 issues)

### Issue 1: Google CAPTCHA blocks browser4-cli — bot detected at network level

**Severity:** Critical
**Category:** Reliability

#### Reproduction

./b4w.ps1 goto "https://www.google.com/search?q=online+bot+detection+test" — redirected to google.com/sorry CAPTCHA page.

#### Expected Behavior

Google search results page loads normally.

#### Actual Behavior

Redirected to https://www.google.com/sorry/index?... CAPTCHA challenge page blocking the search.

#### Root Cause Analysis

Google's bot detection combines IP reputation (datacenter IP: 133.18.123.58, KAGOYA JAPAN Inc.) with browser fingerprint signals. While navigator.webdriver is false, Google uses additional signals — likely WebGL fingerprinting, canvas fingerprinting, IP reputation, and hardwareConcurrency inconsistency between main thread and Web Workers.

#### AI Suggested Improvement

- Ensure navigator.hardwareConcurrency is consistently overridden across the main thread AND Web Workers
- Add support for configuring proxy/network settings to route through residential IPs
- Investigate what specific signals Google uses beyond navigator.webdriver to detect Browser4
- Consider implementing Google-specific stealth patches (e.g., overriding the Google-specific bot detection APIs)

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [x] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 2: fill --submit does not submit the form on Google (no navigation occurs)

**Severity:** High
**Category:** Product

#### Reproduction

./b4w.ps1 goto "https://www.google.com"; ./b4w.ps1 fill <searchbox-ref> "search query" --submit — fills text but URL stays at google.com, no navigation.

#### Expected Behavior

Text is filled and form is submitted (Enter pressed), navigating to search results.

#### Actual Behavior

Text fills successfully and command reports success, but the page URL remains https://www.google.com/ — no navigation occurs.

#### Root Cause Analysis

The --submit flag may only work for <form> elements with standard submit actions. Google's search page uses JavaScript-based form handling that doesn't respond to a traditional Enter/submit. Alternatively, --submit might send Enter to the wrong element or rely on form.submit() which JS-heavy pages intercept.

#### Code Pointer

`CLI: cli/browser4-cli/src/ — fill command handler; Backend: browser4-rest/ — fill tool executor`

#### AI Suggested Improvement

- After fill + Enter, verify the page URL changed; if not, explicitly call press Enter on the input element
- Document the --submit flag's behavior clearly: what exactly it does (press Enter vs form.submit())
- Add a --submit-selector option to target a specific submit button to click
- For Google specifically, fill + press Enter should work — document as a known pattern

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 3: fill command timed out after failed fill --submit

**Severity:** High
**Category:** Reliability

#### Reproduction

After a fill --submit that doesn't navigate (see Issue 2), run ./b4w.ps1 fill <ref> "text" — the command times out after 30s.

#### Expected Behavior

Second fill completes quickly by filling text into the field.

#### Actual Behavior

The fill command timed out (30s) and was moved to background. Page appears to be in an inconsistent state after the first incomplete submit.

#### Root Cause Analysis

After fill --submit, Google's JS may still be processing the incomplete submit, leaving the element in an unfocusable or locked state. The fill command waits for the element to become interactable and hangs indefinitely.

#### AI Suggested Improvement

- Add a timeout parameter for fill operations with a sensible default (5-10s)
- Detect and recover from stuck page states after failed operations
- Provide a clear error message when an element is not interactable, rather than hanging

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 4: Session lost after tab operations — requires manual reconnection

**Severity:** High
**Category:** Reliability

#### Reproduction

Run tab-new, then tab-select, then any interaction command. Error: "No active session is currently stored for this CLI context."

#### Expected Behavior

Session persists across tab operations; commands work without reconnection.

#### Actual Behavior

After tab-new and tab-select, the session context was lost. Had to re-run goto to reconnect. Error message requires understanding of CLI session management.

#### Root Cause Analysis

Tab operations may not properly persist or update the CLI-side session tracking. When tab-switching occurs, the CLI's session reference may become stale or not be updated to track the new active tab.

#### Code Pointer

`CLI: cli/browser4-cli/src/ — session management module`

#### AI Suggested Improvement

- Ensure tab operations (tab-new, tab-select) update the CLI session state
- Auto-reconnect session transparently if session context is lost
- Provide a clearer diagnostic: "Session lost. Run goto <url> or open to reconnect."

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 5: tab-list shows stale/incorrect URLs after navigation

**Severity:** Medium
**Category:** Reliability

#### Reproduction

After tab-new to google.com and navigation, eval shows actual URL is localhost:18080 but tab-list still shows https://www.google.com.

#### Expected Behavior

tab-list displays the actual current URL of each tab as reported by the browser.

#### Actual Behavior

tab-list showed https://www.google.com for a tab that was actually displaying a mock ecommerce page at localhost:18080.

#### Root Cause Analysis

Tab URL tracking in the CLI may be based on the last navigation command rather than polling actual browser state. When a page redirects or browser internal navigation changes, tab-list may not reflect the actual URL.

#### Code Pointer

`CLI: tab-list command implementation`

#### AI Suggested Improvement

- tab-list should query the browser for actual current URLs, not rely on cached navigation history
- Add a --refresh flag to force polling actual browser state
- Display a warning indicator when a tab URL may be stale

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 6: htmlsnapshot export argument syntax unclear — produces error

**Severity:** Medium
**Category:** Discoverability

#### Reproduction

./b4w.ps1 htmlsnapshot export ".test-sessions/results.html" — error: "too many arguments: expected 0, received 1"

#### Expected Behavior

Command accepts a file path and exports snapshot there, OR help text clearly explains where export goes without arguments.

#### Actual Behavior

Help says "Export snapshot HTML to a local file" but rejects any path argument. Export destination is unknown. User must guess or read source code.

#### Root Cause Analysis

The command likely exports to a fixed location (snapshot directory) but the help text says 'to a local file' implying user control. The argument parser rejects any arguments.

#### Code Pointer

`CLI: htmlsnapshot export command definition`

#### AI Suggested Improvement

- Accept a file path argument: htmlsnapshot export [path]
- Or update help text to show the export path: "Export to <snapshot-dir>/export-<timestamp>.html"
- Show the export destination path in command output after successful export

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 7: Page context becomes stale after htmlsnapshot — eval returns empty

**Severity:** Medium
**Category:** Reliability

#### Reproduction

goto <url> → htmlsnapshot → eval "document.body.innerText" returns "Eval returned empty/null. The page context may be stale."

#### Expected Behavior

eval works after htmlsnapshot to extract live page data. The natural workflow is goto → htmlsnapshot → eval/extract.

#### Actual Behavior

After capturing HTML snapshot, eval on the live page returns empty/null. Required re-running goto before eval could work again.

#### Root Cause Analysis

htmlsnapshot appears to take a static snapshot and may disconnect from the live page context, or there's a session tracking issue where the live page reference is lost after snapshotting.

#### Code Pointer

`Backend: browser4-rest/ — htmlsnapshot controller; session tracking`

#### AI Suggested Improvement

- htmlsnapshot should not affect the live page/session reference
- If it must disconnect, document this clearly in help and SKILL.md
- Provide a --keep-context flag to preserve the live page reference
- Auto-reconnect the live page context when eval detects staleness

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [x] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
htmlsnapshot should not affect the live page/session reference

---

### Issue 8: SKILL.md examples use browser4-cli not ./b4w.ps1 — confusing for dev-mode users

**Severity:** Medium
**Category:** Documentation

#### Reproduction

Read skills/browser4-cli/SKILL.md — all examples use browser4-cli prefix. Must mentally translate every example to ./b4w.ps1.

#### Expected Behavior

Examples use the dev-mode invocation when working from source, or clearly indicate which invocation method to use.

#### Actual Behavior

Every example uses browser4-cli. The substitution table is on lines 19-24, easily missed when scrolling to later sections. Creates constant mental friction.

#### Root Cause Analysis

SKILL.md is written primarily for installed users. Dev-mode is mentioned once at the top.

#### AI Suggested Improvement

- Add a prominent banner at the top of every example section about dev-mode invocation
- Provide dual examples: "Installed: browser4-cli goto ..." and "Dev: ./b4w.ps1 goto ..."
- Include a --dev-mode flag to display dev-mode commands in help output

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 9: hardwareConcurrency leaks real value through Web Workers — detected by 3 of 5 services

**Severity:** Medium
**Category:** Product

#### Reproduction

Visit incolumitas.com or deviceandbrowserinfo.com with Browser4. Both detect hasInconsistentWorkerValues: true because hardwareConcurrency is 4 in main thread but 20 in Web Workers.

#### Expected Behavior

If hardwareConcurrency is overridden on the main thread, the same override applies in Web Workers.

#### Actual Behavior

Override only applies on the main thread. Web Workers expose the real hardwareConcurrency value (20), creating a detectable inconsistency.

#### Root Cause Analysis

Browser4 likely patches navigator.hardwareConcurrency via CDP's Page.addScriptToEvaluateOnNewDocument which only affects the main document context, not Web Worker contexts which have their own isolated JS environment.

#### Code Pointer

`browser4-core/browser4-browser/ — PulsarWebDriver stealth/evasion patches`

#### AI Suggested Improvement

- Extend navigator.hardwareConcurrency override to Web Worker contexts
- Alternatively, use a kernel-level or browser-level override that affects all contexts
- Add a test case specifically for Web Worker navigator consistency
- Document this as a known limitation if it cannot be fixed

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 10: No --full-page option for screenshot — cannot capture long result pages

**Severity:** Low
**Category:** Product

#### Reproduction

./b4w.ps1 screenshot on a long result page — only captures the visible viewport. Content below the fold is not included.

#### Expected Behavior

Option to capture full-page screenshot (entire scrollable content), or at minimum a --full-page flag.

#### Actual Behavior

Only viewport screenshots available. Long result pages (like incolumitas.com) cannot be fully captured in one image.

#### Root Cause Analysis

screenshot uses CDP's Page.captureScreenshot with default viewport clipping. Full-page screenshots require the captureBeyondViewport or fullPage parameter.

#### Code Pointer

`Backend: browser4-core/browser4-browser/ — PulsarWebDriver screenshot implementation`

#### AI Suggested Improvement

- Add --full-page flag to screenshot for full-page capture
- Add --clip option to capture specific regions
- Document the viewport-only limitation in help and SKILL.md

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [x] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 11: help output is comprehensive but overwhelming for first-time users

**Severity:** Low
**Category:** UX

#### Reproduction

Run ./b4w.ps1 help — 200+ lines with 50+ commands across 10+ categories, no progressive disclosure.

#### Expected Behavior

A quick-start section at the top with the 5 most common commands and examples. Detailed reference below.

#### Actual Behavior

All commands listed uniformly without visual hierarchy. 'Common workflows' section at top helps but assumes knowledge of the snapshot-ref loop.

#### Root Cause Analysis

Help format lists all commands uniformly without visual hierarchy or progressive disclosure.

#### AI Suggested Improvement

- Add a 'Quick Start' section with 3-5 most common commands with full examples
- Group commands by frequency (Core > Common > Advanced/Rare)
- Add help <category> subcommand: ./b4w.ps1 help navigation
- Add help search <keyword> to find commands matching a keyword

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [x] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

## Overall Assessment

**Completion Status:** Partially Successful — core task (testing 5 services + report) completed, but Phase 1 (Google search) was blocked by Google CAPTCHA detection

**Success Rate:** 75% — 3 of 4 major phases completed; Phase 1 failed due to Google bot detection

**Issues Found:** 11

**Major Blockers:** Google CAPTCHA detection prevented the discovery workflow from working as designed. The fill --submit command did not trigger navigation on Google. Session was lost after tab operations, requiring manual reconnection.

**Most Confusing Aspects:** 1) The snapshot-ref cycle is powerful but takes time to learn. 2) Session management is implicit but fragile — lost after tab ops. 3) fill --submit behavior is unpredictable across websites. 4) htmlsnapshot vs snapshot distinction — two snapshot types for different purposes. 5) Cross-shell quoting complexity adds cognitive load.

**Most Valuable Improvements:** 1) Consistent hardwareConcurrency across Web Workers (fixes most common detection signal). 2) Reliable fill --submit behavior with clear documentation. 3) Session persistence across tab operations. 4) screenshot --full-page. 5) htmlsnapshot export <path> argument support.

**Usability Rating:** 6/10

---

## How to Reproduce

### Common Setup

1. Clone the repository and `cd` to the repo root.
2. The CLI is invoked via `./b4w.ps1` which auto-builds from source when needed.
3. The backend server starts automatically in dev mode.
4. All commands from repo root: `./b4w.ps1 <command>`

### Per-Issue Reproduction Steps

#### Issue 1: Google CAPTCHA blocks browser4-cli — bot detected at network level

./b4w.ps1 goto "https://www.google.com/search?q=online+bot+detection+test" — redirected to google.com/sorry CAPTCHA page.

#### Issue 2: fill --submit does not submit the form on Google (no navigation occurs)

./b4w.ps1 goto "https://www.google.com"; ./b4w.ps1 fill <searchbox-ref> "search query" --submit — fills text but URL stays at google.com, no navigation.

#### Issue 3: fill command timed out after failed fill --submit

After a fill --submit that doesn't navigate (see Issue 2), run ./b4w.ps1 fill <ref> "text" — the command times out after 30s.

#### Issue 4: Session lost after tab operations — requires manual reconnection

Run tab-new, then tab-select, then any interaction command. Error: "No active session is currently stored for this CLI context."

#### Issue 5: tab-list shows stale/incorrect URLs after navigation

After tab-new to google.com and navigation, eval shows actual URL is localhost:18080 but tab-list still shows https://www.google.com.

#### Issue 6: htmlsnapshot export argument syntax unclear — produces error

./b4w.ps1 htmlsnapshot export ".test-sessions/results.html" — error: "too many arguments: expected 0, received 1"

#### Issue 7: Page context becomes stale after htmlsnapshot — eval returns empty

goto <url> → htmlsnapshot → eval "document.body.innerText" returns "Eval returned empty/null. The page context may be stale."

#### Issue 8: SKILL.md examples use browser4-cli not ./b4w.ps1 — confusing for dev-mode users

Read skills/browser4-cli/SKILL.md — all examples use browser4-cli prefix. Must mentally translate every example to ./b4w.ps1.

#### Issue 9: hardwareConcurrency leaks real value through Web Workers — detected by 3 of 5 services

Visit incolumitas.com or deviceandbrowserinfo.com with Browser4. Both detect hasInconsistentWorkerValues: true because hardwareConcurrency is 4 in main thread but 20 in Web Workers.

#### Issue 10: No --full-page option for screenshot — cannot capture long result pages

./b4w.ps1 screenshot on a long result page — only captures the visible viewport. Content below the fold is not included.

#### Issue 11: help output is comprehensive but overwhelming for first-time users

Run ./b4w.ps1 help — 200+ lines with 50+ commands across 10+ categories, no progressive disclosure.

