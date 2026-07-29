# Issues: session-management

> **Source:** `20260728-180533-session-management.full.md` | **Date:** 20260728-180533 | **Mode:** dev

## Scenario Background

### Task

All 8 steps completed successfully:

1. **Named session "research"** opened and navigated to Wikipedia (redirected from "Browser_automation" to "Headless_browser" — Wikipedia's normal redirect behavior, correctly reported by `goto`)
2. **Named session "news"** opened and navigated to Hacker News
3. **Snapshot in "news"** confirmed Hacker News content: header links (new, past, comments, ask, show, jobs, submit), story listings visible
4. **Snapshot in "research"** confirmed Wikipedia article: banner, navigation, "Main menu", article content about headless browsers
5. **Session listing** showed both named sessions ("research", "news") plus an auto-created unnamed default session
6. **"news" session closed** successfully
7. **Second listing** confirmed only "research" and default remained
8. **`close-all`** closed both remaining sessions; final listing confirmed empty

### Execution Context

**Key Commands:**

1. `./b4w.ps1 -s research goto "https://en.wikipedia.org/wiki/Browser_automation"`
2. `./b4w.ps1 -s news goto "https://news.ycombinator.com"`
3. `./b4w.ps1 -s news snapshot -v 0 --stdout`
4. `./b4w.ps1 -s research snapshot -v 0 --stdout`
5. `./b4w.ps1 list`
6. `./b4w.ps1 -s news close`
7. `./b4w.ps1 list`
8. `./b4w.ps1 close-all`
9. `./b4w.ps1 list` (verification)

**Decisions:**
- Used `snapshot -v 0 --stdout` to view content inline rather than opening YAML files, following the SKILL.md template
- Used `close` with `-s` flag to target a specific session — the pattern works but is not explicitly documented
- Used `close-all` for bulk cleanup

**Workarounds:**
- None strictly required. All commands worked as expected on first attempt.
- The `-v` flag produced a warning about PowerShell compatibility on every snapshot call, despite the SKILL.md claiming `b4w.ps1` handles short flags safely. This is a noise issue, not a functional blocker.

```json
{
  "issues": [
    {
      "title": "b4w.ps1 emits short-flag warnings for -v despite SKILL.md claiming safety",
      "severity": "Low",
      "category": "UX",
      "reproduction": "$(./b4w.ps1) -s news snapshot -v 0 --stdout",
      "expected": "No warning — SKILL.md states b4w.ps1 \"Uses manual $args parsing so common short flags (-o/-i/-v) are no longer intercepted by PowerShell's parameter binder.\"",
      "actual": "Every invocation emits: \"⚠ Short flags detected: -v. PowerShell may intercept these in other contexts (b4w.sh, direct pwsh). Prefer long-form equivalents: --output, --interactive, --viewport. Or use b4w.sh / b4w.bat (cmd.exe) for full compatibility.\"",
      "rootCause": "The CLI's argument parser unconditionally emits a warning when short flags like -v are detected, even when invoked through b4w.ps1 which handles them safely. The warning logic does not check whether the invocation context is already safe. This creates a contradiction with the SKILL.md documentation.",
      "codePointer": "cli/browser4-cli/src/ (argument parsing or warning emission logic)",
      "suggestion": "- Suppress the short-flag warning when the CLI detects it is running through b4w.ps1 (e.g., via an env var or argv[0] check)\n- Or remove the warning entirely for short flags that b4w.ps1 now handles safely, and only warn for truly problematic contexts (b4w.sh, direct pwsh)\n- Alternatively, provide a --no-warn-short-flags option to suppress these warnings"
    },
    {
      "title": "snapshot --stdout output is overwhelming for first-time users",
      "severity": "Medium",
      "category": "UX",
      "reproduction": "$(./b4w.ps1) -s news snapshot -v 0 --stdout",
      "expected": "A concise, scannable view of the page structure suitable for quick verification. The SKILL.md recommends --stdout for \"quick inline viewing.\"",
      "actual": "Hacker News produced 64KB of YAML output. Wikipedia produced 37KB. The output is too large to read in a terminal and was truncated by the tool harness. First-time users following the copy-paste template in SKILL.md would be overwhelmed.",
      "rootCause": "Even with -v 0 (single viewport), the AX tree for content-rich pages contains hundreds of lines. The SKILL.md template encourages --stdout without adequately warning about output volume. The snapshot output includes detailed box coordinates for every element, which adds significant volume.",
      "codePointer": "",
      "suggestion": "- Add a --summary or --brief mode to snapshot that prints only page title, URL, and top-level structure (headings, link count, form elements) without full AX tree\n- Update the SKILL.md copy-paste template to warn about expected output size and suggest snapshot grep for targeted verification\n- Consider limiting --stdout output to a configurable line count with a \"...truncated\" message, similar to how get html already paginates at 2K lines"
    },
    {
      "title": "Auto-created default session appears in list despite user never creating it",
      "severity": "Low",
      "category": "Discoverability",
      "reproduction": "Run $(./b4w.ps1) list after creating only named sessions with -s <name> goto.",
      "expected": "Only the two named sessions (research, news) appear, or the default session is hidden/minimized unless explicitly targeted.",
      "actual": "Three sessions appear: 'news', 'research', and '(default)'. The default session was auto-created by a prior (possibly unrelated) goto/open call. The footnote about '(default)' being \"safe to close or switch away from\" helps but is easily missed.",
      "rootCause": "The unnamed default session persists from prior commands or is auto-created on first use. session-default <name> can reassign it but doesn't remove it. New users may not understand where this session came from or whether they need to manage it.",
      "codePointer": "",
      "suggestion": "- Consider not auto-creating a default session until the user explicitly invokes goto or open without -s\n- Add a '--named-only' flag to list that filters out the default session\n- Highlight the default session footnote more prominently (or show it only when a default session actually exists)"
    },
    {
      "title": "No documented way to close a single named session",
      "severity": "Medium",
      "category": "Documentation",
      "reproduction": "Search the help output and SKILL.md for instructions on closing a single named session.",
      "expected": "Documentation showing how to close a specific session, e.g., a 'close' command with -s flag, or a dedicated subcommand like 'session-close <name>'.",
      "actual": "The help output lists 'close' as \"Close the browser\" with no mention of session targeting. SKILL.md's session section (§2) describes opening and listing but not closing individual sessions. The -s <name> close pattern works but is discoverable only by trial and error.",
      "rootCause": "The close command supports -s targeting but neither the help text nor the SKILL.md document this. The help text \"Close the browser\" implies closing everything, not a single session. The session management section in SKILL.md (§2) covers open, list, and session-default but omits close.",
      "codePointer": "cli/browser4-cli/src/ (help text generation); skills/browser4-cli/SKILL.md (session management section)",
      "suggestion": "- Update help text: 'close — Close the current browser session (use -s <name> to close a specific named session)'\n- Add a dedicated 'Closing sessions' subsection to SKILL.md §2 showing: browser4-cli -s <name> close, browser4-cli close (closes default), browser4-cli close-all\n- Consider adding a 'session-close <name>' alias for explicit session closing"
    },
    {
      "title": "close command says 'Browser terminated' when only one session is closed",
      "severity": "Low",
      "category": "UX",
      "reproduction": "$(./b4w.ps1) -s news close",
      "expected": "Session closed. Or: Session 'news' closed.",
      "actual": "Session closed. Browser terminated.",
      "rootCause": "The close command's output message is generic and assumes closing the browser entirely rather than a single session. When -s targets a named session, the message still says \"Browser terminated\" which is misleading — other sessions (e.g., 'research') remain active.",
      "codePointer": "cli/browser4-cli/src/ (close command output formatting)",
      "suggestion": "- When -s <name> is used, output: \"Session '<name>' closed.\" instead of \"Browser terminated.\"\n- When closing the default session without -s, output: \"Default session closed. Browser terminated.\"\n- Reserve \"Browser terminated\" only for cases where the last remaining session was closed"
    },
    {
      "title": "Snapshot output mixes AX tree with viewport metadata in a confusing format",
      "severity": "Low",
      "category": "UX",
      "reproduction": "$(./b4w.ps1) -s news snapshot -v 0 --stdout",
      "expected": "Clear separation between metadata (viewport info, page URL/title) and AX tree content.",
      "actual": "The output begins with YAML comments (# Viewport State, # - processingViewport, etc.) then dives directly into the AX tree. Page URL and title are shown separately in the command output (outside --stdout). The metadata-comment format (lines starting with #) is unusual for a YAML tool and may confuse users expecting structured data.",
      "rootCause": "Snapshot output uses YAML comments for viewport metadata and regular YAML for the AX tree. This hybrid format is not standard YAML and cannot be parsed by YAML tools without preprocessing. The page-level information (URL, title) appears in the command's human-readable output, not in the --stdout stream, fragmenting the information.",
      "codePointer": "cli/browser4-cli/src/snapshot.rs",
      "suggestion": "- Move viewport metadata into the YAML structure as top-level keys instead of comments, so the entire output is valid, parseable YAML\n- Include page URL and title in the --stdout output so users get a self-contained snapshot\n- Consider a --meta-only flag that prints just the metadata without the AX tree"
    }
  ],
  "assessment": {
    "completionStatus": "Successful — all 8 task steps completed without errors on first attempt",
    "successRate": "100% — every command worked as intended with no retries needed",
    "issuesFound": 6,
    "majorBlockers": "",
    "mostConfusingAspects": "1) The short-flag warning on b4w.ps1 contradicts the SKILL.md claim that short flags are safe. 2) The auto-created default session appearing in list alongside intentionally created named sessions is unexpected. 3) No documented way to close a single named session — the -s close pattern works but is discovered only by inference from other -s usage patterns.",
    "mostValuableImprovements": "1) Update close command docs and help text to document -s targeting for individual session closure. 2) Suppress the short-flag warning when running through b4w.ps1, resolving the SKILL.md contradiction. 3) Add a snapshot --summary mode for quick page verification without full AX tree dumps. 4) Make close output message session-aware (don't say 'Browser terminated' when only one session is closed).",
    "usabilityRating": 7
  }
}
```

---

## Issues Found (6 issues)

### Issue 1: snapshot --stdout output is overwhelming for first-time users

**Severity:** Medium
**Category:** UX

#### Reproduction

$(./b4w.ps1) -s news snapshot -v 0 --stdout

#### Expected Behavior

A concise, scannable view of the page structure suitable for quick verification. The SKILL.md recommends --stdout for "quick inline viewing."

#### Actual Behavior

Hacker News produced 64KB of YAML output. Wikipedia produced 37KB. The output is too large to read in a terminal and was truncated by the tool harness. First-time users following the copy-paste template in SKILL.md would be overwhelmed.

#### Root Cause Analysis

Even with -v 0 (single viewport), the AX tree for content-rich pages contains hundreds of lines. The SKILL.md template encourages --stdout without adequately warning about output volume. The snapshot output includes detailed box coordinates for every element, which adds significant volume.

#### AI Suggested Improvement

- Add a --summary or --brief mode to snapshot that prints only page title, URL, and top-level structure (headings, link count, form elements) without full AX tree
- Update the SKILL.md copy-paste template to warn about expected output size and suggest snapshot grep for targeted verification
- Consider limiting --stdout output to a configurable line count with a "...truncated" message, similar to how get html already paginates at 2K lines

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 2: No documented way to close a single named session

**Severity:** Medium
**Category:** Documentation

#### Reproduction

Search the help output and SKILL.md for instructions on closing a single named session.

#### Expected Behavior

Documentation showing how to close a specific session, e.g., a 'close' command with -s flag, or a dedicated subcommand like 'session-close <name>'.

#### Actual Behavior

The help output lists 'close' as "Close the browser" with no mention of session targeting. SKILL.md's session section (§2) describes opening and listing but not closing individual sessions. The -s <name> close pattern works but is discoverable only by trial and error.

#### Root Cause Analysis

The close command supports -s targeting but neither the help text nor the SKILL.md document this. The help text "Close the browser" implies closing everything, not a single session. The session management section in SKILL.md (§2) covers open, list, and session-default but omits close.

#### Code Pointer

`cli/browser4-cli/src/ (help text generation); skills/browser4-cli/SKILL.md (session management section)`

#### AI Suggested Improvement

- Update help text: 'close — Close the current browser session (use -s <name> to close a specific named session)'
- Add a dedicated 'Closing sessions' subsection to SKILL.md §2 showing: browser4-cli -s <name> close, browser4-cli close (closes default), browser4-cli close-all
- Consider adding a 'session-close <name>' alias for explicit session closing

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 3: b4w.ps1 emits short-flag warnings for -v despite SKILL.md claiming safety

**Severity:** Low
**Category:** UX

#### Reproduction

$(./b4w.ps1) -s news snapshot -v 0 --stdout

#### Expected Behavior

No warning — SKILL.md states b4w.ps1 "Uses manual $args parsing so common short flags (-o/-i/-v) are no longer intercepted by PowerShell's parameter binder."

#### Actual Behavior

Every invocation emits: "⚠ Short flags detected: -v. PowerShell may intercept these in other contexts (b4w.sh, direct pwsh). Prefer long-form equivalents: --output, --interactive, --viewport. Or use b4w.sh / b4w.bat (cmd.exe) for full compatibility."

#### Root Cause Analysis

The CLI's argument parser unconditionally emits a warning when short flags like -v are detected, even when invoked through b4w.ps1 which handles them safely. The warning logic does not check whether the invocation context is already safe. This creates a contradiction with the SKILL.md documentation.

#### Code Pointer

`cli/browser4-cli/src/ (argument parsing or warning emission logic)`

#### AI Suggested Improvement

- Suppress the short-flag warning when the CLI detects it is running through b4w.ps1 (e.g., via an env var or argv[0] check)
- Or remove the warning entirely for short flags that b4w.ps1 now handles safely, and only warn for truly problematic contexts (b4w.sh, direct pwsh)
- Alternatively, provide a --no-warn-short-flags option to suppress these warnings

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 4: Auto-created default session appears in list despite user never creating it

**Severity:** Low
**Category:** Discoverability

#### Reproduction

Run $(./b4w.ps1) list after creating only named sessions with -s <name> goto.

#### Expected Behavior

Only the two named sessions (research, news) appear, or the default session is hidden/minimized unless explicitly targeted.

#### Actual Behavior

Three sessions appear: 'news', 'research', and '(default)'. The default session was auto-created by a prior (possibly unrelated) goto/open call. The footnote about '(default)' being "safe to close or switch away from" helps but is easily missed.

#### Root Cause Analysis

The unnamed default session persists from prior commands or is auto-created on first use. session-default <name> can reassign it but doesn't remove it. New users may not understand where this session came from or whether they need to manage it.

#### AI Suggested Improvement

- Consider not auto-creating a default session until the user explicitly invokes goto or open without -s
- Add a '--named-only' flag to list that filters out the default session
- Highlight the default session footnote more prominently (or show it only when a default session actually exists)

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 5: close command says 'Browser terminated' when only one session is closed

**Severity:** Low
**Category:** UX

#### Reproduction

$(./b4w.ps1) -s news close

#### Expected Behavior

Session closed. Or: Session 'news' closed.

#### Actual Behavior

Session closed. Browser terminated.

#### Root Cause Analysis

The close command's output message is generic and assumes closing the browser entirely rather than a single session. When -s targets a named session, the message still says "Browser terminated" which is misleading — other sessions (e.g., 'research') remain active.

#### Code Pointer

`cli/browser4-cli/src/ (close command output formatting)`

#### AI Suggested Improvement

- When -s <name> is used, output: "Session '<name>' closed." instead of "Browser terminated."
- When closing the default session without -s, output: "Default session closed. Browser terminated."
- Reserve "Browser terminated" only for cases where the last remaining session was closed

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 6: Snapshot output mixes AX tree with viewport metadata in a confusing format

**Severity:** Low
**Category:** UX

#### Reproduction

$(./b4w.ps1) -s news snapshot -v 0 --stdout

#### Expected Behavior

Clear separation between metadata (viewport info, page URL/title) and AX tree content.

#### Actual Behavior

The output begins with YAML comments (# Viewport State, # - processingViewport, etc.) then dives directly into the AX tree. Page URL and title are shown separately in the command output (outside --stdout). The metadata-comment format (lines starting with #) is unusual for a YAML tool and may confuse users expecting structured data.

#### Root Cause Analysis

Snapshot output uses YAML comments for viewport metadata and regular YAML for the AX tree. This hybrid format is not standard YAML and cannot be parsed by YAML tools without preprocessing. The page-level information (URL, title) appears in the command's human-readable output, not in the --stdout stream, fragmenting the information.

#### Code Pointer

`cli/browser4-cli/src/snapshot.rs`

#### AI Suggested Improvement

- Move viewport metadata into the YAML structure as top-level keys instead of comments, so the entire output is valid, parseable YAML
- Include page URL and title in the --stdout output so users get a self-contained snapshot
- Consider a --meta-only flag that prints just the metadata without the AX tree

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

## Overall Assessment

**Completion Status:** Successful — all 8 task steps completed without errors on first attempt

**Success Rate:** 100% — every command worked as intended with no retries needed

**Issues Found:** 6

**Most Confusing Aspects:** 1) The short-flag warning on b4w.ps1 contradicts the SKILL.md claim that short flags are safe. 2) The auto-created default session appearing in list alongside intentionally created named sessions is unexpected. 3) No documented way to close a single named session — the -s close pattern works but is discovered only by inference from other -s usage patterns.

**Most Valuable Improvements:** 1) Update close command docs and help text to document -s targeting for individual session closure. 2) Suppress the short-flag warning when running through b4w.ps1, resolving the SKILL.md contradiction. 3) Add a snapshot --summary mode for quick page verification without full AX tree dumps. 4) Make close output message session-aware (don't say 'Browser terminated' when only one session is closed).

**Usability Rating:** 7/10

---

## How to Reproduce

### Common Setup

1. Clone the repository and `cd` to the repo root.
2. The CLI is invoked via `./b4w.ps1` which auto-builds from source when needed.
3. The backend server starts automatically in dev mode.
4. All commands from repo root: `./b4w.ps1 <command>`

### Per-Issue Reproduction Steps

#### Issue 1: snapshot --stdout output is overwhelming for first-time users

$(./b4w.ps1) -s news snapshot -v 0 --stdout

#### Issue 2: No documented way to close a single named session

Search the help output and SKILL.md for instructions on closing a single named session.

#### Issue 3: b4w.ps1 emits short-flag warnings for -v despite SKILL.md claiming safety

$(./b4w.ps1) -s news snapshot -v 0 --stdout

#### Issue 4: Auto-created default session appears in list despite user never creating it

Run $(./b4w.ps1) list after creating only named sessions with -s <name> goto.

#### Issue 5: close command says 'Browser terminated' when only one session is closed

$(./b4w.ps1) -s news close

#### Issue 6: Snapshot output mixes AX tree with viewport metadata in a confusing format

$(./b4w.ps1) -s news snapshot -v 0 --stdout

