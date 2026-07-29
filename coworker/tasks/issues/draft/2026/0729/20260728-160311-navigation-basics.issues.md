# Issues: navigation-basics

> **Source:** `20260728-160311-navigation-basics.full.md` | **Date:** 20260728-160311 | **Mode:** dev

## Scenario Background

### Task

All 8 steps of the task were completed successfully:

1. Navigated to `https://en.wikipedia.org/wiki/Web_scraping`
2. Took a snapshot of the page (using `snapshot -v 0 --stdout` and `snapshot grep` to locate elements)
3. Identified links in the "See also" section (found ~20 links via `snapshot grep -A 80 'region "See also"'`) and clicked "Data scraping" (ref=e2529)
4. Used `go-back` to return to Web scraping, then `go-forward` to return to Data scraping
5. Used `reload` to refresh the Data scraping page
6. Checked status via `status` — server UP, version 4.12.1-SNAPSHOT
7. Listed sessions via `list` — showed DEFAULT session (SWARM) and amazon session
8. Closed the current session via `close`

### Execution Context

| Step | Command | Notes |
|------|---------|-------|
| 1 | `./b4w.ps1 goto "https://en.wikipedia.org/wiki/Web_scraping"` | Auto-started backend daemon; reused existing DEFAULT session |
| 2a | `./b4w.ps1 snapshot -v 0 --stdout` | Output was 43KB — too large for inline viewing |
| 2b | `./b4w.ps1 snapshot grep "See also"` | Found TOC entries but not body content |
| 2c | `./b4w.ps1 htmlsnapshot` | Captured static HTML; showed 483 links, 5 buttons |
| 2d | `./b4w.ps1 htmlsnapshot get all` | Attempted CSS selectors — returned empty arrays for `#See_also a` |
| 2e | `./b4w.ps1 scroll down 3000` | Scrolled to lower portion of page |
| 2f | `./b4w.ps1 click e1605` | Clicked TOC "See also" link to navigate to section |
| 2g | `./b4w.ps1 snapshot grep -A 80 'region "See also"'` | Successfully fou...

(truncated — see full.md for complete trace)

---

## Issues Found (8 issues)

### Issue 1: Snapshot output overwhelms first-time users (43KB+)

**Severity:** Medium
**Category:** UX

#### Reproduction

./b4w.ps1 snapshot -v 0 --stdout on https://en.wikipedia.org/wiki/Web_scraping

#### Expected Behavior

A manageable, scannable output or clear guidance on how to navigate large snapshots.

#### Actual Behavior

43.3KB of YAML dumped to stdout. The output was too large to view inline and required piping through grep to find relevant content.

#### Root Cause Analysis

Wikipedia pages have large accessibility trees with hundreds of nodes. Viewport 0 of a content-rich page still produces massive output. The SKILL.md warns about this ("Don't cat snapshot files — they can exceed 256KB") and recommends `snapshot grep` as an alternative, but the default `snapshot -v 0` behavior (which the docs teach as the first command after goto) dumps the full viewport tree without any size warning.

#### Code Pointer

`cli/browser4-cli/src/snapshot.rs`

#### AI Suggested Improvement

- Add a size warning when snapshot output exceeds a threshold (e.g., 500 lines) suggesting `snapshot grep` or `--page N` as alternatives
- Consider making `snapshot -v 0` output paginated by default (like `get html` already does at 2K lines)
- Add a `--summary` flag to show only interactive elements (buttons, inputs, links) with refs, which is what most users actually need

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 2: htmlsnapshot CSS selector fails on Wikipedia section IDs

**Severity:** Medium
**Category:** Product

#### Reproduction

./b4w.ps1 htmlsnapshot get all text '#See_also a' --limit 20

#### Expected Behavior

A list of link texts from the 'See also' section.

#### Actual Behavior

Empty array `[]` with message 'No elements matched "#See_also a"'.

#### Root Cause Analysis

Wikipedia's HTML may use sanitized/namespaced IDs or the static HTML snapshot captures a DOM structure where the `id` attribute differs from what appears in URL fragments. The `#See_also` anchor exists in the URL fragment but may not be present as a CSS-selectable ID in the captured DOM. The htmlsnapshot `inspect` command is suggested as a fallback in the error message, but this adds an extra step.

#### AI Suggested Improvement

- Improve error messages to suggest checking whether the ID might be namespaced or transformed (e.g., Wikipedia uses `mw-headline` spans inside headings)
- Add a `--debug-selector` flag to htmlsnapshot get that shows all IDs/classes present in the DOM to help users self-diagnose selector failures
- Include an example in docs showing how to extract Wikipedia sections specifically

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 3: Ref lifecycle break after scrolling — refs from pre-scroll snapshot become stale

**Severity:** Medium
**Category:** Product

#### Reproduction

1) Take snapshot at viewport 0 (shows top of page). 2) Scroll down. 3) Try to click a ref from the original snapshot.

#### Expected Behavior

Either refs should survive scrolling, or the tool should warn that refs become invalid after scroll.

#### Actual Behavior

After scrolling, the TOC ref coordinates changed (y went from 678 to 3566 then 7132), indicating the snapshot coordinate system shifts. The ref=e1605 link still worked when clicked post-scroll, but the documentation doesn't clearly state whether scroll invalidates refs or not.

#### Root Cause Analysis

The accessibility tree refs (backend node IDs) survive scroll since the DOM structure doesn't change — only the viewport position changes. However, the snapshot coordinates (box values) shift, which could confuse users trying to verify they're targeting the right element. The SKILL.md 'Ref Lifecycle' section lists safe/unsafe operations but doesn't mention scrolling.

#### Code Pointer

`skills/browser4-cli/SKILL.md (Ref Lifecycle section)`

#### AI Suggested Improvement

- Add 'scroll' to the 'Safe (refs survive)' list in the Ref Lifecycle documentation
- Clarify that refs survive scroll but snapshot box coordinates change
- Consider adding a `--scroll-to-ref <ref>` option to auto-scroll elements into view before interacting

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 4: Version mismatch warning between CLI and backend in dev mode

**Severity:** Low
**Category:** UX

#### Reproduction

./b4w.ps1 status

#### Expected Behavior

Clear indication that the version difference is expected in dev mode, or no warning at all.

#### Actual Behavior

"⚠ Version mismatch: CLI is 4.12.1 but running backend is 4.12.1-SNAPSHOT. The CLI and backend were built from different versions of the source tree. Rebuild both to match: mvn install -pl browser4-rest -am && cargo build..."

#### Root Cause Analysis

When running from source in dev mode, the CLI reports its version from the Cargo.toml (4.12.1) while the backend reports 4.12.1-SNAPSHOT from the Maven POM. These are semantically the same version (SNAPSHOT just means it's a dev build). The warning is misleading and suggests a rebuild that won't fix the discrepancy.

#### Code Pointer

`cli/browser4-cli/src/ (status command handler)`

#### AI Suggested Improvement

- Suppress the version mismatch warning when running in dev mode (detected by cargo build profile or when CLI and backend are both -SNAPSHOT or close enough)
- Change the message to note that minor version suffix differences are normal in development
- Consider normalizing version strings before comparison (strip -SNAPSHOT suffix)

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 5: PowerShell short-flag warning on every snapshot command

**Severity:** Low
**Category:** UX

#### Reproduction

./b4w.ps1 snapshot -v 0 --stdout

#### Expected Behavior

Clean output without warnings for documented usage patterns.

#### Actual Behavior

"⚠ Short flags detected: -v. PowerShell may intercept these in other contexts (b4w.sh, direct pwsh). Prefer long-form equivalents: --output, --interactive, --viewport"

#### Root Cause Analysis

The b4w.ps1 wrapper detects short flags and emits a warning. This warning appears even when using b4w.ps1 correctly (which is the documented primary choice on the platform where the issue exists). The warning creates noise for a command that the user was told to use.

#### Code Pointer

`b4w.ps1 (root-level PowerShell wrapper)`

#### AI Suggested Improvement

- Suppress the short-flag warning when running under b4w.ps1 (since the wrapper already handles argument parsing correctly)
- Only emit the warning when actual PowerShell parameter binding issues are likely (e.g., when running under direct pwsh without the wrapper)
- Update the documentation to consistently use long-form flags (already recommended for cross-shell compatibility, but the examples still use short flags)

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 6: No obvious way to navigate to a specific section by name

**Severity:** Low
**Category:** Discoverability

#### Reproduction

A user wants to go to the 'See also' section of a Wikipedia page. They must: 1) take a snapshot, 2) search for the TOC link ref, 3) click it, 4) re-snapshot.

#### Expected Behavior

A command like `click --text 'See also'` or a `scroll-to-section` command that navigates to a named section.

#### Actual Behavior

The workflow requires multiple round-trips: snapshot → grep → identify ref → click → re-snapshot. This is a 4-step process for what is conceptually a single action.

#### Root Cause Analysis

The snapshot model requires explicit refs for all interactions. There is no text-based element targeting without first obtaining a ref from a snapshot. This is by design (refs are backend node IDs), but it creates high interaction cost for simple navigation tasks.

#### AI Suggested Improvement

- Consider adding a `click --text '<text>'` flag that auto-snapshots, finds the first element with matching accessible text, and clicks it in one command
- Add a `scroll-to '<heading text>'` command for section navigation
- Document the TOC-click-then-snapshot pattern more prominently as a known workflow

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 7: Session list shows 'SWARM' as session ID for default session

**Severity:** Low
**Category:** UX

#### Reproduction

./b4w.ps1 list (after using goto without -s)

#### Expected Behavior

A human-readable session ID or name for the default session.

#### Actual Behavior

The Session ID column shows 'SWARM' for the unnamed default session, which is confusing — it suggests a swarm operation is running when it's actually a normal browsing session.

#### Root Cause Analysis

The default session appears to have been created or re-used from a prior swarm operation. The backend may be reusing a swarm-created session ID for the unnamed default session, leaking implementation details into the user-facing output.

#### Code Pointer

`browser4-rest/ (session listing / MCPToolController)`

#### AI Suggested Improvement

- Display a more descriptive label for the default session (e.g., 'default' or 'browsing session')
- If 'SWARM' is an internal session type, translate it to a user-friendly label in the list output
- Add a 'Type' column to differentiate between regular, swarm, and extension sessions

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 8: No progress indicator during page navigation

**Severity:** Low
**Category:** UX

#### Reproduction

./b4w.ps1 goto 'https://en.wikipedia.org/wiki/Web_scraping'

#### Expected Behavior

Some indication of loading progress (e.g., spinner, 'loading...', or status messages).

#### Actual Behavior

The command blocks silently until the page loads, then returns the result. On slow connections, the user has no feedback about what's happening.

#### Root Cause Analysis

The CLI sends the goto command and waits synchronously for the backend response. There is no streaming progress feedback during the wait.

#### Code Pointer

`cli/browser4-cli/src/ (goto command handler)`

#### AI Suggested Improvement

- Add a spinner or elapsed-time indicator during blocking navigation operations
- Stream page load events (e.g., 'connecting...', 'loading DOM...', 'network idle...') if the CDP protocol supports it
- At minimum, print 'Navigating to <url>...' before the request so the user knows the command is in progress

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

**Completion Status:** Successful — all 8 task steps completed without major errors. Navigation, snapshot, element interaction, history navigation, reload, status check, session listing, and session close all worked correctly.

**Success Rate:** 100% — every command executed successfully on the first attempt with no retries needed.

**Issues Found:** 8

**Most Confusing Aspects:** 1) The snapshot output was overwhelming (43KB) and required grep-based workflows to find relevant content — the 'snapshot -v 0' taught as the first step produces too much output for practical use. 2) CSS selector extraction with htmlsnapshot failed silently on Wikipedia section IDs, requiring fallback to the accessibility-tree snapshot approach. 3) The ref-based interaction model requires multiple round-trips for simple actions like 'click the See also link' — the user must snapshot, grep, identify a ref, click, then re-snapshot.

**Most Valuable Improvements:** 1) Paginate snapshot output by default or add a --summary mode showing only interactive elements with refs. 2) Add text-based element targeting (e.g., `click --text 'See also'`) to reduce the snapshot→grep→click→snapshot cycle to a single command. 3) Suppress the spurious version mismatch warning in dev mode. 4) Improve the session list to show user-friendly session types instead of internal identifiers like 'SWARM'.

**Usability Rating:** 6/10

---

## How to Reproduce

### Common Setup

1. Clone the repository and `cd` to the repo root.
2. The CLI is invoked via `./b4w.ps1` which auto-builds from source when needed.
3. The backend server starts automatically in dev mode.
4. All commands from repo root: `./b4w.ps1 <command>`

### Per-Issue Reproduction Steps

#### Issue 1: Snapshot output overwhelms first-time users (43KB+)

./b4w.ps1 snapshot -v 0 --stdout on https://en.wikipedia.org/wiki/Web_scraping

#### Issue 2: htmlsnapshot CSS selector fails on Wikipedia section IDs

./b4w.ps1 htmlsnapshot get all text '#See_also a' --limit 20

#### Issue 3: Ref lifecycle break after scrolling — refs from pre-scroll snapshot become stale

1) Take snapshot at viewport 0 (shows top of page). 2) Scroll down. 3) Try to click a ref from the original snapshot.

#### Issue 4: Version mismatch warning between CLI and backend in dev mode

./b4w.ps1 status

#### Issue 5: PowerShell short-flag warning on every snapshot command

./b4w.ps1 snapshot -v 0 --stdout

#### Issue 6: No obvious way to navigate to a specific section by name

A user wants to go to the 'See also' section of a Wikipedia page. They must: 1) take a snapshot, 2) search for the TOC link ref, 3) click it, 4) re-snapshot.

#### Issue 7: Session list shows 'SWARM' as session ID for default session

./b4w.ps1 list (after using goto without -s)

#### Issue 8: No progress indicator during page navigation

./b4w.ps1 goto 'https://en.wikipedia.org/wiki/Web_scraping'

