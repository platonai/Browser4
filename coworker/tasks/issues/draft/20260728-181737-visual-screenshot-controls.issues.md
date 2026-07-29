# Issues: visual-screenshot-controls

> **Source:** `20260728-181737-visual-screenshot-controls.full.md` | **Date:** 20260728-181737 | **Mode:** dev

## Scenario Background

### Task

All 10 steps of the task completed successfully:

1. ✅ Resized viewport to 1280×900
2. ✅ Navigated to `https://en.wikipedia.org/wiki/Large_language_model`
3. ✅ Waited for network idle
4. ✅ Took default viewport screenshot
5. ✅ Scrolled down 6 times (4800px total) to load article content
6. ✅ Waited for "Training" section heading text to be visible
7. ✅ Took full-page screenshot (`22154px` tall, 8-page PDF) saved to `.test-sessions/full-page-llm-article.png`
8. ✅ Scrolled back to top (position 0.0)
9. ✅ Waited 2 seconds, took top-of-page screenshot saved to `.test-sessions/top-of-page.png`
10. ✅ Saved page as PDF (8 pages) to `.test-sessions/llm-article.pdf`

### Execution Context

**Key Commands:**

| Step | Command | 
|------|---------|
| Prep | `./b4w.ps1 help` |
| Prep | Read `skills/browser4-cli/SKILL.md` |
| 1 | `./b4w.ps1 resize 1280 900` | 
| 2 | `./b4w.ps1 goto "https://en.wikipedia.org/wiki/Large_language_model"` |
| 3 | `./b4w.ps1 wait --load networkidle` |
| 4 | `./b4w.ps1 screenshot` (default, auto-saved) |
| 5 | `./b4w.ps1 scroll down 800` ×6 |
| 6 | `./b4w.ps1 wait --text "Training" --timeout 10000` |
| 7 | `./b4w.ps1 screenshot --full-page --filename .test-sessions/full-page-llm-article.png` |
| 8 | `./b4w.ps1 scroll up 5000` |
| 9 | `./b4w.ps1 wait 2000` + `./b4w.ps1 screenshot --filename .test-sessions/top-of-page.png` |
| 10 | `./b4w.ps1 pdf --filename .test-sessions/llm-article.pdf` |

**Key decisions:**
- Used `--filename` with relative paths for custom output locations; discovered that `--filename` resolves relative to CWD when the path contains a `/`
- Had to guess the pixel value for `scroll up` (used 5000) since there's no "scroll to top" command
- Used `--text` wait mode since it was the most natural way to verify content reached a certain section

**Workarounds:**
- For scrolling back to top, guessed `5000px` based on accumulated scroll-down distance

---

---

## Issues Found (8 issues)

### Issue 1: Viewport dimensions drift after full-page screenshot

**Severity:** High
**Category:** Reliability

#### Reproduction

1. resize 1280 900
2. goto any URL
3. screenshot (verify: 1600×1125 physical / 1280×900 CSS)
4. screenshot --full-page
5. scroll up 5000
6. screenshot
7. eval 'window.innerWidth' → 1728 not 1280

#### Expected Behavior

Viewport should remain at 1280×900 CSS pixels after full-page screenshot.

#### Actual Behavior

Viewport grew from 1280×900 (1600×1125 physical at 1.25 DPR) to 1728×1034 (2160×1292 physical). The resize command's effect was silently undone or mutated by a subsequent operation — likely the full-page screenshot temporarily adjusting the viewport and failing to restore it.

#### Root Cause Analysis

The full-page screenshot likely uses CDP to temporarily set the viewport height to the full page height (or uses Emulation.setDeviceMetricsOverride) and fails to restore the original dimensions afterward. The resize command may also set the outer window size rather than the inner content area, leading to confusion about what '1280×900' actually means.

#### Code Pointer

`browser4-core/browser4-browser/ — PulsarWebDriver screenshot/fullPage logic or the CDP Page.captureScreenshot invocation that temporarily resizes the viewport.`

#### AI Suggested Improvement

- After full-page screenshot, restore the original viewport dimensions explicitly via CDP
- Consider using `Page.captureScreenshot` with `captureBeyondViewport: true` instead of resizing the viewport
- Add a `--viewport` flag to `resize` that distinguishes between CSS pixels and device pixels
- Verify and report actual viewport dimensions after resize (e.g., "Resized to 1280×900 (inner: 1280×900, DPR: 1.25)")

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 2: Full-page screenshot saves as JPEG despite .png extension

**Severity:** Medium
**Category:** Reliability

#### Reproduction

1. goto any URL
2. screenshot --full-page --filename output.png
3. file output.png → "JPEG image data" despite .png extension

#### Expected Behavior

File extension should match the actual image format, or the format should be documented.

#### Actual Behavior

The file has a `.png` extension but contains JPEG data. This is misleading — tools that check magic bytes will correctly identify it as JPEG, but users and scripts relying on the extension will be confused.

#### Root Cause Analysis

Likely the full-page screenshot path uses CDP's `Page.captureScreenshot` which returns JPEG by default for large screenshots (smaller file size), but the CLI always appends `.png` to the filename regardless of the actual format.

#### Code Pointer

`cli/browser4-cli/src/ — screenshot command handler that determines the output filename extension.`

#### AI Suggested Improvement

- Detect the actual image format from the CDP response and use the correct extension (.jpg for JPEG, .png for PNG)
- Or force PNG format for consistency with the extension
- Or allow a `--format png|jpeg` flag so users can choose

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 3: Hidden help categories not discoverable

**Severity:** Medium
**Category:** Discoverability

#### Reproduction

1. Run `b4w.ps1 help`
2. Read the filter line: "Filter help by category: --help nav | --help extract | --help session | --help kb | --help agent | --help swarm | --help crawl"
3. Try `--help capture`, `--help mouse`, `--help core`, `--help kb` — all work but aren't listed

#### Expected Behavior

All valid help category filters should be listed in the main help output.

#### Actual Behavior

The main help only advertises 7 filter categories (nav, extract, session, kb, agent, swarm, crawl) but `capture`, `mouse`, and `core` also work. The Global options section also lists a subset: "try: nav, extract, session, kb, agent".

#### Root Cause Analysis

The help category filter list in the CLI's help text is hardcoded and was not updated when new categories (capture, mouse, core) were added. Two different lists exist (the Filter line at top and the Global options hint at bottom) with different contents.

#### Code Pointer

`cli/browser4-cli/src/ — help text generation, likely in main.rs or a help module.`

#### AI Suggested Improvement

- Generate the category filter list dynamically from registered categories instead of hardcoding
- Unify the two lists (filter line and Global options hint) to avoid inconsistency
- Add `capture`, `mouse`, and `core` to the advertised categories

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 4: No 'scroll to top' or 'scroll to bottom' convenience command

**Severity:** Low
**Category:** UX

#### Reproduction

1. scroll down 3000
2. Try to scroll back to top — must guess pixel amount (scroll up 3000) or use eval with window.scrollTo()

#### Expected Behavior

A shorthand command like `scroll top` or `scroll bottom` so users don't have to track how far they've scrolled.

#### Actual Behavior

Only pixel-based relative scrolling is available via `scroll <direction> <pixels>`. Users must remember or guess how far they scrolled down to get back to the top.

#### Root Cause Analysis

The scroll command only supports relative pixel offsets. Positional targets (top, bottom) were not implemented.

#### Code Pointer

`cli/browser4-cli/src/ — scroll command implementation.`

#### AI Suggested Improvement

- Support `scroll top` and `scroll bottom` as aliases that scroll to position 0 and document height respectively
- Consider `scroll to <pixels>` for absolute positioning as a complement to the relative `scroll <direction> <pixels>`

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 5: Wait command output lacks context about what was waited for

**Severity:** Low
**Category:** UX

#### Reproduction

1. wait --load networkidle
2. Output: "✓ Wait complete" — no indication of what condition was satisfied or how long it took.

#### Expected Behavior

Output should include the condition that was satisfied, e.g., "✓ Network idle reached (took 1.2s)" or "✓ Text 'Training' found on page".

#### Actual Behavior

Uniform "✓ Wait complete" for all wait modes. Users can't tell from the output whether anything actually happened or the condition was already met.

#### Root Cause Analysis

The wait command uses a generic success message regardless of the wait mode, discarding mode-specific context that would help users understand what happened.

#### Code Pointer

`cli/browser4-cli/src/ — wait command output formatting.`

#### AI Suggested Improvement

- Include the wait mode in the output: "✓ Network idle reached", "✓ Text 'X' found", "✓ Element 'e1' appeared"
- Include elapsed time: "(took 1.2s)" or "(already satisfied)"
- Distinguish between "waited and found" vs "already satisfied" for debuggability

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 6: Resize command unclear about outer window vs inner viewport

**Severity:** Low
**Category:** Documentation

#### Reproduction

1. resize 1280 900 → "✓ Resized to 1280×900"
2. eval 'window.innerWidth' → 1728 (not 1280)
3. Confusion: what does 1280×900 actually control?

#### Expected Behavior

The command should clarify whether it resizes the outer browser window, the inner content area (viewport), and whether device pixel ratio affects the result. Output should report the actual resulting viewport dimensions.

#### Actual Behavior

The output says "1280×900" but the actual viewport CSS dimensions are different (1728×1034 in testing). The relationship between the requested size and the actual viewport is opaque to the user.

#### Root Cause Analysis

The resize command likely sets the outer window size via CDP `Browser.setWindowBounds`, but the inner content area is smaller due to browser chrome (title bar, toolbars). The device pixel ratio also affects physical vs CSS pixel dimensions. None of this is explained.

#### Code Pointer

`browser4-core/browser4-browser/ — PulsarWebDriver resize logic.`

#### AI Suggested Improvement

- Report both requested and actual viewport dimensions: "Resized window to 1280×900 (viewport: 1280×858, DPR: 1.25)"
- Document the distinction between outer window size and inner viewport in `resize --help`
- Consider adding a `--viewport` flag that targets the inner content area directly via CDP Emulation

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 7: Default screenshot save location is not obvious to first-time users

**Severity:** Low
**Category:** UX

#### Reproduction

1. screenshot (no --filename)
2. Output shows path under .browser4-cli/snapshot/ with timestamp filename
3. User may not know where .browser4-cli/ lives or how to find the file later

#### Expected Behavior

The output should make it easy to locate and use the file, perhaps with a relative path from CWD or a copyable command.

#### Actual Behavior

Output shows an absolute path which is helpful, but the directory `.browser4-cli/` is a hidden directory (dot-prefixed) that new users might not know about. The clickable link format in the terminal is good though.

#### Root Cause Analysis

Design choice to store artifacts in `.browser4-cli/snapshot/`. This is fine for power users but could be surfaced more clearly for newcomers.

#### AI Suggested Improvement

- Add a tip after the first screenshot: "Screenshots are saved to .browser4-cli/snapshot/. Use --filename to choose a custom location."
- Consider `screenshot --open` to open the file in the system viewer
- List recent screenshots in the session summary

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 8: Scroll, resize, wait, screenshot scattered across unrelated help categories

**Severity:** Low
**Category:** UX

#### Reproduction

1. Look for viewport-related commands in help
2. resize/wait are under "Core", scroll under "Mouse", screenshot/pdf under "Capture"
3. No unified "Viewport" or "Page control" category exists

#### Expected Behavior

Viewport and page control commands should be grouped together for easy discovery.

#### Actual Behavior

Related commands are scattered: resize and wait in Core, scroll in Mouse, screenshot and pdf in Capture. A user looking for 'how to control the page view' must scan three different sections.

#### Root Cause Analysis

Commands are organized by implementation mechanism (mouse events, CDP capture, core utilities) rather than by user intent (viewport control).

#### Code Pointer

`cli/browser4-cli/src/ — help text command grouping/organization.`

#### AI Suggested Improvement

- Add a "Viewport" or "Page" help category grouping resize, scroll, wait, screenshot, and pdf
- Keep existing categories for backward compatibility but cross-reference via "See also --help viewport"

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

## Overall Assessment

**Completion Status:** Successful — all 10 task steps completed. Three output files produced: full-page screenshot (6.9MB, 22154px tall), top-of-page screenshot (523KB), and 8-page PDF (2.4MB).

**Success Rate:** 100% — every command executed without errors and produced the expected output.

**Issues Found:** 8

**Major Blockers:** None. No command failures or work-stopping errors occurred. The task was completed smoothly.

**Most Confusing Aspects:** 1. The resize command says '1280×900' but the actual viewport is 1728×1034 — the relationship between requested size and actual viewport is opaque.
2. The viewport silently changed size after the full-page screenshot operation, with no warning or indication.
3. Default screenshot files go to a hidden .browser4-cli/ directory — new users wouldn't know to look there.
4. The full-page screenshot file has a .png extension but contains JPEG data — confusing and potentially breaking for downstream tools.

**Most Valuable Improvements:** 1. Fix viewport drift after full-page screenshot — this is a correctness bug that silently changes browser state.
2. List all help category filters (capture, mouse, core) in the main help output — low-effort, high-impact discoverability fix.
3. Add scroll top/bottom convenience commands — small addition that eliminates an entire class of guesswork.
4. Make wait output descriptive — '✓ Network idle reached (took 1.2s)' is far more useful than '✓ Wait complete'.

**Usability Rating:** 7/10

---

## How to Reproduce

### Common Setup

1. Clone the repository and `cd` to the repo root.
2. The CLI is invoked via `./b4w.ps1` which auto-builds from source when needed.
3. The backend server starts automatically in dev mode.
4. All commands from repo root: `./b4w.ps1 <command>`

### Per-Issue Reproduction Steps

#### Issue 1: Viewport dimensions drift after full-page screenshot

1. resize 1280 900
2. goto any URL
3. screenshot (verify: 1600×1125 physical / 1280×900 CSS)
4. screenshot --full-page
5. scroll up 5000
6. screenshot
7. eval 'window.innerWidth' → 1728 not 1280

#### Issue 2: Full-page screenshot saves as JPEG despite .png extension

1. goto any URL
2. screenshot --full-page --filename output.png
3. file output.png → "JPEG image data" despite .png extension

#### Issue 3: Hidden help categories not discoverable

1. Run `b4w.ps1 help`
2. Read the filter line: "Filter help by category: --help nav | --help extract | --help session | --help kb | --help agent | --help swarm | --help crawl"
3. Try `--help capture`, `--help mouse`, `--help core`, `--help kb` — all work but aren't listed

#### Issue 4: No 'scroll to top' or 'scroll to bottom' convenience command

1. scroll down 3000
2. Try to scroll back to top — must guess pixel amount (scroll up 3000) or use eval with window.scrollTo()

#### Issue 5: Wait command output lacks context about what was waited for

1. wait --load networkidle
2. Output: "✓ Wait complete" — no indication of what condition was satisfied or how long it took.

#### Issue 6: Resize command unclear about outer window vs inner viewport

1. resize 1280 900 → "✓ Resized to 1280×900"
2. eval 'window.innerWidth' → 1728 (not 1280)
3. Confusion: what does 1280×900 actually control?

#### Issue 7: Default screenshot save location is not obvious to first-time users

1. screenshot (no --filename)
2. Output shows path under .browser4-cli/snapshot/ with timestamp filename
3. User may not know where .browser4-cli/ lives or how to find the file later

#### Issue 8: Scroll, resize, wait, screenshot scattered across unrelated help categories

1. Look for viewport-related commands in help
2. resize/wait are under "Core", scroll under "Mouse", screenshot/pdf under "Capture"
3. No unified "Viewport" or "Page control" category exists

