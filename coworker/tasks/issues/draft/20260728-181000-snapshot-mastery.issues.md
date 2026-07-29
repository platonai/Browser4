# Issues: snapshot-mastery

> **Source:** `20260728-181000-snapshot-mastery.full.md` | **Date:** 20260728-181000 | **Mode:** dev

## Scenario Background

### Task

All 10 task steps were completed successfully:

1. **Navigation:** Successfully navigated to `https://en.wikipedia.org/wiki/Christopher_Alexander` via `goto`.
2. **Full-page snapshot (`-v 0`):** Captured viewport 0 showing the top of the Wikipedia page with full accessibility tree including navigation, sidebar, and article content (10 total viewports).
3. **Interactive-only snapshot (`-i`):** Captured an interactive-element-only view showing buttons, links, form controls, and their accessible names.
4. **Scoped snapshot (`--selector "#mw-content-text"`):** Attempted to scope to the main content area. The output **did not appear to filter correctly** — the navigation sidebar content was still present in the output.
5. **Depth-limited snapshot (`-d 3`):** Successfully captured a condensed tree at depth 3, showing only top-level structure.
6. **URLs-enabled snapshot (`-u`):** Successfully captured snapshot with `/url:` attributes visible on link elements.
7. **Click navigation:** Successfully clicked the "A Pattern Language" link (ref e940) and navigated to the related Wikipedia article.
8. **Auto-diff snapshot:** Captured a post-navigation snapshot with `--auto-diff`. Since the entire page changed (new URL), the diff showed the complete new tree — as documented, but without any visual markers to indicate changes vs unchanged content.
9. **Snapshot grep (7 variants):**
   - `-i "language"` — case-insensitive search ✓
   - `-C 3 "language"` — context lines ✓
   - `-v "generic"` — inverted matching ✓
   - `-c "link"` — count (80 matches) ✓
   - `-F "Pattern Language"` — fixed-string ✓
   - `-w "pattern"` — whole-word ✓
   - `--selector "h1"` — CSS scoped grep (results appeared unfiltered) ⚠
10. **Stdout snapshot:** Output format reviewed — YAML-like accessibility tree with refs, boxes, roles, names, and properties.

### Execution Context

**Key Commands:**

```
./b4w.ps1 help
./b4w.ps1 snapshot --help
./b4w.ps1 snapshot grep --help
./b4w.ps1 goto "https://en.wikipedia.org/wiki/Christopher_Alexander"
./b4w.ps1 snapshot -v 0 --stdout
./b4w.ps1 snapshot -i --stdout
./b4w.ps1 snapshot --selector "#mw-content-text" --stdout
./b4w.ps1 snapshot -d 3 --stdout
./b4w.ps1 snapshot -u --stdout
./b4w.ps1 snapshot -v 0-2 --stdout | grep "link.*architect\|..."
./b4w.ps1 click e940
./b4w.ps1 snapshot -v 0 --auto-diff --stdout
./b4w.ps1 snapshot grep -i "language"
./b4w.ps1 snapshot grep -C 3 "language"
./b4w.ps1 snapshot grep -v "generic"
./b4w.ps1 snapshot grep -c "link"
./b4w.ps1 snapshot grep -F "Pattern Language"
./b4w.ps1 snapshot grep -w "pattern"
./b4w.ps1 snapshot grep -i "language" --selector "h1"
./b4w.ps1 snapshot --stdout
```

**Key decisions:**
- Used `b4w.ps1` as instructed by the task, despite SKILL.md recommending `b4w.sh` for bash environments.
- Used `#mw-content-text` as the CSS selector for scoping based on Wikipedia's known DOM structure.
- Clicked "A Pattern Language" (e940) as the navigation target since it's a clearly related article from the Christopher Alexander page.

**Workarounds:**
- The short-flag warnings (`-v`, `-i`) were pervasive but non-blocking — every command with a short flag produced a warning banner.
- Viewport 2 of the original page appeared empty; had to search across viewports 0-2 to find article content links.

---

---

## Issues Found (8 issues)

### Issue 1: snapshot --selector does not filter output to scoped element

**Severity:** High
**Category:** Product

#### Reproduction

./b4w.ps1 snapshot --selector "#mw-content-text" --stdout — observe that the output includes the full page navigation sidebar, header, and other elements outside the scoped selector.

#### Expected Behavior

The snapshot output should contain only elements within (or ancestors of) the CSS selector #mw-content-text, i.e., the main article content area.

#### Actual Behavior

The output included the full Wikipedia sidebar navigation, sticky header, language menu, user tools, and page chrome — elements that are siblings or parents of #mw-content-text and not descendants of it.

#### Root Cause Analysis

The --selector implementation appears to be including all root-to-leaf ancestor chains for matched elements (as documented: 'root-to-leaf ancestor elements outside the matched scope are included for tree-path context'). However, the navigation sidebar and page chrome are not ancestors of elements in #mw-content-text — they are in a completely separate DOM subtree. This suggests the selector scoping may not be working at all, or the ancestor inclusion logic is overly broad (including siblings and unrelated ancestors).

#### Code Pointer

`cli/browser4-cli/src/ — snapshot rendering logic; browser4-rest/ — snapshot selector scoping implementation`

#### AI Suggested Improvement

- Verify that --selector filtering actually prunes non-matching subtrees rather than just annotating them
- Consider adding a --strict flag that excludes ancestor elements outside the matched scope
- Add a test case with a known selector on a page with clearly separated DOM regions (sidebar vs main content)
- The documentation's 'root-to-leaf ancestor elements outside the matched scope are included for tree-path context' note should clarify WHICH ancestors (only direct ancestors of matched elements, not unrelated page regions)

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 2: snapshot grep --selector does not filter grep results by CSS selector scope

**Severity:** High
**Category:** Product

#### Reproduction

./b4w.ps1 snapshot grep -i "language" --selector "h1" — observe results include elements from nav sidebar, user menu, tool links, not just h1 headings.

#### Expected Behavior

Only lines from elements matching the CSS selector (h1 heading elements and their children) should appear in grep results.

#### Actual Behavior

Grep results included generic containers, list items, links from the sidebar navigation, user account menu, and page tools — none of which are inside an h1 element.

#### Root Cause Analysis

The --selector flag for snapshot grep appears to be non-functional or has the same overly-broad scoping issue as snapshot --selector. It may be ignoring the selector entirely and returning full-page results, or the selector matching logic is matching too broadly.

#### Code Pointer

`browser4-rest/ — snapshot grep implementation; cli/browser4-cli/ — grep rendering layer`

#### AI Suggested Improvement

- Add an integration test: grep with a selector that should match exactly one element (e.g., h1) and verify the result set is limited to that element's subtree
- Document the expected behavior of --selector clearly: does it filter by matching the element itself, or its subtree, or the element and its ancestors?

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 3: Short-flag warning banner fires on every command, even on Linux/bash where the warning is irrelevant

**Severity:** Medium
**Category:** UX

#### Reproduction

Run any snapshot command with short flags on Linux: ./b4w.ps1 snapshot -v 0, ./b4w.ps1 snapshot -i, ./b4w.ps1 snapshot grep -i "text". The warning appears every time.

#### Expected Behavior

The short-flag warning should only appear when the user's shell/environment actually risks PowerShell parameter binding (i.e., when running b4w.ps1 inside PowerShell). On Linux/bash, the warning is noise and should be suppressed.

#### Actual Behavior

Every command with -v, -i, or other short flags produces:
⚠  Short flags detected: -v
   PowerShell may intercept these in other contexts (b4w.sh, direct pwsh).
   Prefer long-form equivalents: --output, --interactive, --viewport
   Or use b4w.sh / b4w.bat (cmd.exe) for full compatibility.

#### Root Cause Analysis

The wrapper script b4w.ps1 unconditionally emits the warning when short flags are detected in arguments, without checking whether the execution environment is actually PowerShell (where parameter binding is a risk) or bash (where it is not).

#### Code Pointer

`b4w.ps1 — short flag detection and warning emission logic`

#### AI Suggested Improvement

- Check the execution environment ($PSVersionTable or similar) and suppress the warning when running from bash/sh
- Consider making the warning a one-time banner per session rather than per-command
- Add a SKIP_SHORT_FLAG_WARNING env var or --no-warn-short-flags global flag for power users

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 4: Auto-diff output lacks visual change indicators — indistinguishable from a normal snapshot

**Severity:** Medium
**Category:** UX

#### Reproduction

Navigate to page A, then navigate to page B, then run: ./b4w.ps1 snapshot -v 0 --auto-diff --stdout

#### Expected Behavior

The output should visually distinguish changed elements from unchanged ones — e.g., + for added, - for removed, ~ for modified, or color-coded output.

#### Actual Behavior

The output is a standard snapshot with no markers, prefixes, or visual indicators to show what changed. After a full page navigation (where everything changed), the output is indistinguishable from `snapshot -v 0 --stdout` without --auto-diff.

#### Root Cause Analysis

The auto-diff feature seems to compare element trees internally but presents the result as a flat output. There's no formatting layer that adds diff markers (+/-/~) or highlights structural changes. The current implementation may be computing the diff but rendering the 'after' state without annotation.

#### Code Pointer

`cli/browser4-cli/src/ — auto-diff rendering; browser4-rest/ — diff computation`

#### AI Suggested Improvement

- Add + (added), - (removed), ~ (modified) prefixes to diff output lines
- Support --color/--no-color for terminal color highlighting of diff hunks
- Consider a --diff-format=unified option for traditional unified diff output
- For post-navigation diffs where everything is new, show a summary line like 'Full page change: all NNN elements are new' instead of dumping the entire tree

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 5: Navigation auto-diff always shows full tree — no way to see structural-only changes

**Severity:** Low
**Category:** UX

#### Reproduction

Navigate to any page, then click a link to navigate to a new page, then run: ./b4w.ps1 snapshot --auto-diff --stdout

#### Expected Behavior

Ideally, the diff would show a structural summary of what changed (e.g., 'page title changed from X to Y', 'main content replaced') rather than dumping the entire new tree.

#### Actual Behavior

After navigation, --auto-diff shows the entire accessibility tree of the new page with no indication of what's different from the old page. The documentation acknowledges this ('after page navigation, all elements appear as changed') but offers no workaround.

#### Root Cause Analysis

After a full page navigation, every backend node ID changes (new DOM), so the diff algorithm correctly identifies all elements as new. However, the tool could provide a higher-level summary comparing page structure (e.g., title, headings, number of links) rather than treating every node as a raw diff entry.

#### Code Pointer

`browser4-rest/ — auto-diff logic`

#### AI Suggested Improvement

- Add a page-level diff summary before the full diff: title change, URL change, heading count change, viewport count change
- Consider a --diff-stats flag that shows only aggregate change statistics without the full tree
- Document that --auto-diff is most useful within the same page (e.g., after clicking a button that modifies the DOM) rather than across navigations

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 6: -i flag overloaded between snapshot (interactive) and snapshot grep (case-insensitive)

**Severity:** Low
**Category:** Discoverability

#### Reproduction

Run ./b4w.ps1 snapshot -i (works: interactive mode). Then run ./b4w.ps1 snapshot grep -i "text" (works: case-insensitive). The same flag letter has completely different meanings in the parent command vs its subcommand.

#### Expected Behavior

Ideally, flag names should be consistent across the command hierarchy, or the ambiguity should be clearly documented in the help output.

#### Actual Behavior

-i means 'interactive' for snapshot but 'case-insensitive' for snapshot grep. This follows grep convention but creates a learning curve for users who might expect -i to mean the same thing everywhere.

#### Root Cause Analysis

Historical: snapshot -i predates snapshot grep -i. The grep subcommand follows Unix grep conventions. There's no easy fix without breaking backward compatibility, but better documentation could help.

#### AI Suggested Improvement

- Add a note in `snapshot --help` and `snapshot grep --help` flag descriptions cross-referencing the different meanings
- Consider adding long-form aliases (--interactive and --ignore-case) as the primary documented options, with short flags as convenience shortcuts
- Add a tip to the tool output when a user runs snapshot grep immediately after snapshot -i

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 7: SKILL.md recommends b4w.sh for bash but task requires b4w.ps1 — documentation contradiction

**Severity:** Low
**Category:** Documentation

#### Reproduction

Read skills/browser4-cli/SKILL.md: 'b4w.sh — Git Bash / Linux / macOS: individually quotes each argument... Recommended when running from bash environments.' But the setup instructions say to use $(./b4w.ps1).

#### Expected Behavior

The documentation should be internally consistent about which wrapper to use on which platform.

#### Actual Behavior

SKILL.md says b4w.sh is recommended for bash environments, but the task setup instructions specify b4w.ps1. Using b4w.ps1 on Linux produces short-flag warnings that b4w.sh would avoid.

#### Root Cause Analysis

The task setup instructions were written to ensure the local source tree is used, but b4w.sh would also use the local source tree. The preference for b4w.ps1 may come from a Windows-centric development history.

#### Code Pointer

`skills/browser4-cli/SKILL.md — shell selection guide section; task setup instructions`

#### AI Suggested Improvement

- Align the task instructions with SKILL.md: recommend b4w.sh for Linux/bash, b4w.ps1 for PowerShell
- Or update SKILL.md to clarify that b4w.ps1 is safe on Linux too and the short-flag warning can be ignored
- Add a note in SKILL.md about the short-flag warning behavior on different platforms

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 8: Empty viewport output for viewport 2 on Wikipedia page

**Severity:** Low
**Category:** Reliability

#### Reproduction

./b4w.ps1 goto "https://en.wikipedia.org/wiki/Christopher_Alexander" then ./b4w.ps1 snapshot -v 2 --stdout

#### Expected Behavior

Viewport 2 should show page content from approximately 2068-3102px down the page (the third screen-height chunk).

#### Actual Behavior

Viewport 2 produced empty output (only YAML comment headers, no tree nodes). Viewport 0 worked correctly. Viewports 0-2 combined worked, suggesting the content exists but isn't being captured by viewport 2's Y-range filter.

#### Root Cause Analysis

The viewport slicing filters the accessibility tree by Y-coordinate bounding boxes. It's possible that at Wikipedia's responsive breakpoints, the content in viewport 2's Y-range consists of elements that have bounding boxes outside the viewport's Y-range (e.g., tall elements spanning multiple viewports). The filter may be too strict, excluding elements whose computed bounding box starts before or ends after the viewport's Y-range.

#### Code Pointer

`browser4-rest/ — viewport Y-range filtering logic`

#### AI Suggested Improvement

- Consider inclusive filtering: include elements whose bounding box overlaps the viewport range, not just elements fully contained within it
- When a viewport produces zero elements, emit a clear message like 'No elements found in this viewport range. Try a wider range (e.g., -v 1-3).'
- Add a --viewport-overlap flag to control overlap tolerance

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

## Overall Assessment

**Completion Status:** Successful — all 10 task steps completed. The core workflow (goto → snapshot → click → auto-diff → grep) worked end-to-end. Two significant product issues were found (--selector filtering for both snapshot and snapshot grep does not appear to work as documented), but neither blocked task completion.

**Success Rate:** 80% — 8 out of 10 steps worked as expected; steps 4 (scoped snapshot) and 9g (selector-scoped grep) produced results but did not filter correctly.

**Issues Found:** 8

**Major Blockers:** None. All task steps were achievable. The --selector scoping issues are significant but had workarounds (grepping the full output).

**Most Confusing Aspects:** 1. The short-flag warning banner appearing on every command despite being on Linux/bash where PowerShell parameter binding is irrelevant. 2. The --auto-diff output being indistinguishable from a regular snapshot — no visual cues to indicate what changed. 3. The --selector flag for both snapshot and snapshot grep appearing to have no effect on output.

**Most Valuable Improvements:** 1. Fix --selector filtering for both snapshot and snapshot grep so CSS selectors actually scope the output. 2. Add visual diff markers (+/-/~) to auto-diff output so users can see what changed at a glance. 3. Suppress the short-flag warning when running on Linux/bash (or make it once-per-session). 4. Improve empty-viewport messaging so users know to adjust their viewport range.

**Usability Rating:** 6/10

---

## How to Reproduce

### Common Setup

1. Clone the repository and `cd` to the repo root.
2. The CLI is invoked via `./b4w.ps1` which auto-builds from source when needed.
3. The backend server starts automatically in dev mode.
4. All commands from repo root: `./b4w.ps1 <command>`

### Per-Issue Reproduction Steps

#### Issue 1: snapshot --selector does not filter output to scoped element

./b4w.ps1 snapshot --selector "#mw-content-text" --stdout — observe that the output includes the full page navigation sidebar, header, and other elements outside the scoped selector.

#### Issue 2: snapshot grep --selector does not filter grep results by CSS selector scope

./b4w.ps1 snapshot grep -i "language" --selector "h1" — observe results include elements from nav sidebar, user menu, tool links, not just h1 headings.

#### Issue 3: Short-flag warning banner fires on every command, even on Linux/bash where the warning is irrelevant

Run any snapshot command with short flags on Linux: ./b4w.ps1 snapshot -v 0, ./b4w.ps1 snapshot -i, ./b4w.ps1 snapshot grep -i "text". The warning appears every time.

#### Issue 4: Auto-diff output lacks visual change indicators — indistinguishable from a normal snapshot

Navigate to page A, then navigate to page B, then run: ./b4w.ps1 snapshot -v 0 --auto-diff --stdout

#### Issue 5: Navigation auto-diff always shows full tree — no way to see structural-only changes

Navigate to any page, then click a link to navigate to a new page, then run: ./b4w.ps1 snapshot --auto-diff --stdout

#### Issue 6: -i flag overloaded between snapshot (interactive) and snapshot grep (case-insensitive)

Run ./b4w.ps1 snapshot -i (works: interactive mode). Then run ./b4w.ps1 snapshot grep -i "text" (works: case-insensitive). The same flag letter has completely different meanings in the parent command vs its subcommand.

#### Issue 7: SKILL.md recommends b4w.sh for bash but task requires b4w.ps1 — documentation contradiction

Read skills/browser4-cli/SKILL.md: 'b4w.sh — Git Bash / Linux / macOS: individually quotes each argument... Recommended when running from bash environments.' But the setup instructions say to use $(./b4w.ps1).

#### Issue 8: Empty viewport output for viewport 2 on Wikipedia page

./b4w.ps1 goto "https://en.wikipedia.org/wiki/Christopher_Alexander" then ./b4w.ps1 snapshot -v 2 --stdout

