# Issues: tab-workflow

> **Source:** `20260727-234417-tab-workflow.full.md` | **Date:** 20260727-234417 | **Mode:** dev

## Scenario Background

### Task

The browser4-cli tab management subsystem was exercised across all three session types (Regular, Extension, and Mixed) with the following outcomes:

- **Tab creation** (`tab-new`): Works correctly with and without URLs, returns GUIDs, auto-switches to new tabs across all session types.
- **Tab listing** (`tab-list`): Produces consistent human-readable tables and JSON output; correctly scoped to the current session; no cross-session tab leakage observed.
- **Tab selection** (`tab-select`): Works by both index and GUID; correctly changes the active page context.
- **Tab closing** (`tab-close`): Works by index, by GUID, and without args (current tab); tabs are re-indexed compactly after close.
- **Session isolation**: Tab operations in one session never affect tabs in another session; CONNECTION type remains correct.
- **Error handling**: Invalid index produces a clear error; invalid GUID produces a **misleading success message** (see Issue 2).

**One critical gap**: There is no `session` command to switch the default session in multi-session environments, requiring `-s <session-id>` on every command targeting a non-default session.

### Execution Context

**Key Commands:**

**Major steps:** Smoke test → Part A (17 steps of regular session tab lifecycle) → Part B (8 steps of extension session tab lifecycle) → Part C (10 steps of mixed-session isolation testing)

**Workarounds required:**
1. PowerShell `-i` flag conflict: Used `--interactive` instead of `-i` for snapshot
2. No `session` command: Used `-s <session-id>` prefix for per-command session targeting
3. Chrome tab insert position differs from task assumptions: Adapted to actual tab order

```json
{
  "issues": [
    {
      "title": "No session-switching command exists (session/switch/use)",
      "severity": "Critical",
      "category": "Discoverability",
      "reproduction": "In a multi-session environment (regular + extension), run `./b4w.ps1 session <session-id>`, `./b4w.ps1 switch <id>`, or `./b4w.ps1 use <id>`. All return 'Unknown command'.",
      "expected": "A command like `session <id>` should exist to change which session is marked DEFAULT, so tab commands and navigation auto-target the intended session without requiring `-s` on every command.",
      "actual": "No `session`, `switch`, `use`, or `default` command exists. The only way to target a non-default session is the `-s <session-id>` global flag on every individual command. The `--help session` category shows sessionStorage commands, not browser session management.",
      "rootCause": "The CLI and SKILL.md both lack a dedicated command for changing the default session. Multi-session management currently requires per-command `-s` flags, which is tedious and error-prone. The SKILL.md (line 106) documents `-s` for cross-session targeting but never explains how to change the persistent default.",
      "codePointer": "",
      "suggestion": "- Add a `session <name-or-id>` command that changes the DEFAULT marker to the specified session\n- Add a `session` subcommand (or `switch`/`use` alias) to the Browser sessions command group in help output\n- Document the command in SKILL.md under §2 Key Concepts > Sessions, with examples for multi-session workflows\n- Consider adding `--default` flag to `attach` and `open` to set the new session as default"
    },
    {
      "title": "tab-close --guid with nonexistent GUID reports misleading success",
      "severity": "High",
      "category": "Reliability",
      "reproduction": "./b4w.ps1 tab-close --guid \"nonexistent-guid-12345\"",
      "expected": "Error message: 'Tab with GUID nonexistent-guid-12345 not found'. Non-zero exit code. No false success claim.",
      "actual": "Output: 'Note: Tab was closed but the backend reported: ERROR: browser_tabs failed: Tab 'nonexistent-guid-12345' not found ... Closed tab with GUID: nonexistent-guid-12345'. Exit code 0. The CLI claims success ('Closed tab with GUID') while simultaneously noting the backend reported it wasn't found.",
      "rootCause": "The CLI layer appears to interpret the backend error response as a successful close, likely because the extension-session error-recovery path (documented in SKILL.md line 110 for 'chrome.tabs.remove callback firing after tab is gone') is incorrectly applied here. The CLI fails to distinguish between 'tab was closed but backend reported error' (valid for extension sessions) and 'tab was never found/closed' (invalid GUID).",
      "codePointer": "",
      "suggestion": "- Distinguish between 'backend error after successful close' and 'backend error because tab not found' — only apply the recovery note for the former\n- Return non-zero exit code when the backend reports tab-not-found for GUID-based operations\n- Remove the 'Closed tab with GUID: ...' success message when the backend reports the tab was not found\n- Add a dedicated error message: 'Error: No tab found with GUID <guid>' instead of the misleading success"
    },
    {
      "title": "PowerShell -i flag conflicts with snapshot interactive mode",
      "severity": "Medium",
      "category": "UX",
      "reproduction": "./b4w.ps1 snapshot -i or ./b4w.ps1 -- snapshot -i",
      "expected": "snapshot -i should launch interactive snapshot mode.",
      "actual": "PowerShell error: 'Parameter cannot be processed because the parameter name 'i' is ambiguous. Possible matches include: -InformationAction -InformationVariable.' The `--` separator also fails. Users must use the long form `--interactive`.",
      "rootCause": "The b4w.ps1 PowerShell wrapper script passes short flags through PowerShell's parameter binder, which intercepts `-i` as matching `-InformationAction`. The SKILL.md documents this (line 420): 'short flags like -i and -v may be intercepted by PowerShell's parameter binder'. However, it suggests using b4w.bat or b4w.sh instead — but the evaluation instructions require ./b4w.ps1.",
      "codePointer": "",
      "suggestion": "- Update b4w.ps1 to use `--%` (stop-parsing symbol) or `@args` splatting to pass all arguments through without PowerShell parameter binding\n- Or document `--interactive` as the preferred long form for PowerShell users in the snapshot help output\n- Add a tip after `snapshot` help that mentions the `-i` → `--interactive` substitution on PowerShell"
    },
    {
      "title": "Extension session tab-close shows confusing dual-message output",
      "severity": "Medium",
      "category": "UX",
      "reproduction": "In an extension session, run `./b4w.ps1 tab-close 1` or `./b4w.ps1 -s <ext-session> tab-close`.",
      "expected": "Clean output: 'Closed tab 1 (url — GUID: chrome:xxx)'. Possibly a note about extension behavior, but in a non-alarming format.",
      "actual": "'Note: Tab was closed but the backend reported: ERROR: browser_tabs failed: closeTab ... Closed tab 1 (url — GUID: xxx)'. The ERROR text looks like a failure even though the operation succeeded. The Java method signature in the help text is confusing.",
      "rootCause": "Per SKILL.md line 110, 'chrome.tabs.remove callback can fire an error after the tab is already gone'. The CLI correctly handles this (verifies removal and treats as success) but presents the backend error verbatim before the success message, creating a WARNING-then-OK pattern that reads like a failure.",
      "codePointer": "",
      "suggestion": "- Suppress the raw backend ERROR text when the CLI has verified the tab was actually removed\n- Replace with a single informational note: 'Tab closed (extension session — backend confirmation delayed)'\n- Or demote the ERROR to a debug-level message and show only 'Closed tab N (url — GUID: xxx)' on stdout\n- Remove the Java method signature from user-facing output (it belongs in --verbose/debug mode only)"
    },
    {
      "title": "Extension session GUID format changes from chrome: prefix to hex after session becomes stale",
      "severity": "Medium",
      "category": "Reliability",
      "reproduction": "1. Create extension session, observe GUIDs like chrome:217100829\n2. Close all tabs via extension session\n3. Create a new tab via extension session\n4. GUID is now hex (e.g. E931A329...) instead of chrome:-prefixed",
      "expected": "GUID format should be consistent for a given session type. Extension sessions should always show chrome:-prefixed GUIDs.",
      "actual": "After the extension session becomes 'Stale' (all tabs closed), new tabs show 32-char hex GUIDs instead of chrome:-prefixed GUIDs, even though the session's CONNECTION column still shows 'Extension'.",
      "rootCause": "When the extension session becomes stale, the tab management likely falls back to a different code path (possibly CDP-based) that generates hex GUIDs instead of using Chrome extension tab IDs. The session metadata still shows 'Extension' but the GUID format no longer reflects the extension origin.",
      "codePointer": "",
      "suggestion": "- Ensure GUID format is determined by session type (from session metadata), not by the current connection state\n- Cache the session connection type at creation time and use it for GUID formatting throughout the session lifecycle\n- If the session reverts to a non-extension transport, update the CONNECTION column to reflect this change"
    },
    {
      "title": "tab-list --json output format differs from documentation",
      "severity": "Low",
      "category": "Documentation",
      "reproduction": "Run `./b4w.ps1 tab-list --json` and compare with SKILL.md documentation.",
      "expected": "Documentation (SKILL.md line 104) says output includes a 'tabs' array with 'index', 'guid', 'url', 'title' plus a 'count' field.",
      "actual": "Output is wrapped in a JSON envelope: {\"command\":\"tab-list\",\"output\":{\"count\":1,\"tabs\":[...]},\"status\":\"ok\"}. The 'tabs' array and 'count' are nested inside 'output', not at the top level. Also, '--json' works both as a command-level flag (`tab-list --json`) and as a global flag (`--json tab-list`), but the documentation only mentions the global form.",
      "rootCause": "The documentation describes the inner payload structure but omits the outer envelope (command, output, status). The envelope is a general CLI convention applied to all --json output, but the SKILL.md example implies the tabs array is at the top level.",
      "codePointer": "",
      "suggestion": "- Update SKILL.md line 104 to show the full JSON envelope structure with a note that tabs are nested inside output\n- Document both `--json tab-list` and `tab-list --json` as valid invocation forms\n- Add a JSON output example showing the exact format"
    },
    {
      "title": "Error messages include raw Java method signatures in user-facing output",
      "severity": "Low",
      "category": "UX",
      "reproduction": "./b4w.ps1 tab-select 99 or ./b4w.ps1 tab-close --guid \"bogus\"",
      "expected": "Clean, human-readable error: 'Error: Tab index 99 is out of range. There are 3 tabs (indices 0-2).'",
      "actual": "'ERROR: browser_tabs failed: Tab index '99' out of range; found 3 tabs help: Switch to a specific browser tab by its zero-based index or GUID\\nbrowser.switchTab(Arg(name=index, type=Int, defaultValue=null), Arg(name=tabId, type=String, defaultValue=null))'",
      "rootCause": "The CLI is surfacing internal Java method signatures (browser.switchTab/browser.closeTab with Arg definitions) as part of the user-facing error output. These signatures are useful for developers but confusing for end users.",
      "codePointer": "",
      "suggestion": "- Strip Java method signatures from default output; show them only in --verbose/debug mode\n- Format error messages as: 'Error: <human-readable description>' followed by 'Suggestion: <actionable hint>'\n- Move the method signature to a debug/trace log level"
    },
    {
      "title": "Goto command navigation message inconsistent — says 'already at' but actually navigates",
      "severity": "Low",
      "category": "UX",
      "reproduction": "When switching to a tab that already has example.com loaded and running `goto https://example.com`.",
      "expected": "Clear indication: either 'Already at https://example.com' (no navigation) or 'Navigated to https://example.com' (navigation occurred).",
      "actual": "Sometimes produces 'Using existing session DEFAULT (current page: https://example.com/).' and shows a full snapshot output. The behavior is ambiguous — it's unclear whether a navigation actually occurred or the page was left as-is.",
      "rootCause": "The goto command always triggers a page load check but doesn't clearly distinguish between 'page was already at this URL, no navigation needed' and 'navigation occurred to this URL'. The snapshot output is always generated regardless.",
      "codePointer": "",
      "suggestion": "- Add explicit 'Already at <url> — no navigation needed' message when the current URL matches the target\n- Or add 'Navigated to <url>' vs 'Already at <url>' distinction in the output\n- Consider skipping the snapshot when no navigation occurred (add --force-snapshot flag for users who want it)"
    }
  ],
  "assessment": {
    "completionStatus": "Successful — All 31 steps completed across Part A (Regular), Part B (Extension), and Part C (Mixed). Full tab lifecycle verified: create, list, select, close, GUID operations, error handling, session isolation, cross-session independence. Two step-level failures recorded (A.16: misleading GUID error; C.5: no session-switching command) but both were worked around.",
    "successRate": "94% — 29 of 31 steps passed cleanly. Two steps produced failures: A.16 (misleading success for invalid GUID close) and C.5 (no session-switching command). Both had workarounds available.",
    "issuesFound": 8,
    "majorBlockers": "No session-switching command exists. Users in multi-session environments must prefix every command with `-s <session-id>` to target a non-default session. This is tedious and error-prone for workflows that alternate between sessions.",
    "mostConfusingAspects": "1. No `session` command to change the default session (had to discover this by trial and error). 2. Extension session tab-close outputs ERROR followed by success — hard to tell if the operation actually worked. 3. The `--help session` category shows sessionStorage commands, not browser session management, which is misleading. 4. Chrome tab insert positions are unpredictable (dependent on native Chrome behavior).",
    "mostValuableImprovements": "1. Add a `session <id>` command to switch the default session. 2. Fix the misleading success message for tab-close with invalid GUID. 3. Clean up extension session close output to not show raw ERROR text. 4. Add a 'Quick Start' or 'Common Workflows' section showing multi-session usage patterns.",
    "usabilityRating": 6
  }
}
```

---

## Issues Found (8 issues)

### Issue 1: No session-switching command exists (session/switch/use)

**Severity:** Critical
**Category:** Discoverability

#### Reproduction

In a multi-session environment (regular + extension), run `./b4w.ps1 session <session-id>`, `./b4w.ps1 switch <id>`, or `./b4w.ps1 use <id>`. All return 'Unknown command'.

#### Expected Behavior

A command like `session <id>` should exist to change which session is marked DEFAULT, so tab commands and navigation auto-target the intended session without requiring `-s` on every command.

#### Actual Behavior

No `session`, `switch`, `use`, or `default` command exists. The only way to target a non-default session is the `-s <session-id>` global flag on every individual command. The `--help session` category shows sessionStorage commands, not browser session management.

#### Root Cause Analysis

The CLI and SKILL.md both lack a dedicated command for changing the default session. Multi-session management currently requires per-command `-s` flags, which is tedious and error-prone. The SKILL.md (line 106) documents `-s` for cross-session targeting but never explains how to change the persistent default.

#### AI Suggested Improvement

- Add a `session <name-or-id>` command that changes the DEFAULT marker to the specified session
- Add a `session` subcommand (or `switch`/`use` alias) to the Browser sessions command group in help output
- Document the command in SKILL.md under §2 Key Concepts > Sessions, with examples for multi-session workflows
- Consider adding `--default` flag to `attach` and `open` to set the new session as default

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Genuine discoverability gap for multi-session workflows. Per-command `-s` flags don't scale — users need a persistent default switch. The suggested `session <id>` command with `--default` on attach/open is well-scoped. Severity is correctly rated Critical given the multi-session evaluation context.

---

### Issue 2: tab-close --guid with nonexistent GUID reports misleading success

**Severity:** High
**Category:** Reliability

#### Reproduction

./b4w.ps1 tab-close --guid "nonexistent-guid-12345"

#### Expected Behavior

Error message: 'Tab with GUID nonexistent-guid-12345 not found'. Non-zero exit code. No false success claim.

#### Actual Behavior

Output: 'Note: Tab was closed but the backend reported: ERROR: browser_tabs failed: Tab 'nonexistent-guid-12345' not found ... Closed tab with GUID: nonexistent-guid-12345'. Exit code 0. The CLI claims success ('Closed tab with GUID') while simultaneously noting the backend reported it wasn't found.

#### Root Cause Analysis

The CLI layer appears to interpret the backend error response as a successful close, likely because the extension-session error-recovery path (documented in SKILL.md line 110 for 'chrome.tabs.remove callback firing after tab is gone') is incorrectly applied here. The CLI fails to distinguish between 'tab was closed but backend reported error' (valid for extension sessions) and 'tab was never found/closed' (invalid GUID).

#### AI Suggested Improvement

- Distinguish between 'backend error after successful close' and 'backend error because tab not found' — only apply the recovery note for the former
- Return non-zero exit code when the backend reports tab-not-found for GUID-based operations
- Remove the 'Closed tab with GUID: ...' success message when the backend reports the tab was not found
- Add a dedicated error message: 'Error: No tab found with GUID <guid>' instead of the misleading success

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Clear reliability bug — exit code 0 with success message when the backend explicitly reported the tab was not found. The CLI's extension-session error-recovery path (SKILL.md line 110) is being applied too broadly; it should only fire when the tab was verifiably removed despite a callback race, not when the GUID was never valid. Cross-issue note: shares root cause with Issue 4 (same error-to-output pipeline), and both contribute to Issue 7's general pattern of raw backend errors leaking to users.

---

### Issue 3: PowerShell -i flag conflicts with snapshot interactive mode

**Severity:** Medium
**Category:** UX

#### Reproduction

./b4w.ps1 snapshot -i or ./b4w.ps1 -- snapshot -i

#### Expected Behavior

snapshot -i should launch interactive snapshot mode.

#### Actual Behavior

PowerShell error: 'Parameter cannot be processed because the parameter name 'i' is ambiguous. Possible matches include: -InformationAction -InformationVariable.' The `--` separator also fails. Users must use the long form `--interactive`.

#### Root Cause Analysis

The b4w.ps1 PowerShell wrapper script passes short flags through PowerShell's parameter binder, which intercepts `-i` as matching `-InformationAction`. The SKILL.md documents this (line 420): 'short flags like -i and -v may be intercepted by PowerShell's parameter binder'. However, it suggests using b4w.bat or b4w.sh instead — but the evaluation instructions require ./b4w.ps1.

#### AI Suggested Improvement

- Update b4w.ps1 to use `--%` (stop-parsing symbol) or `@args` splatting to pass all arguments through without PowerShell parameter binding
- Or document `--interactive` as the preferred long form for PowerShell users in the snapshot help output
- Add a tip after `snapshot` help that mentions the `-i` → `--interactive` substitution on PowerShell

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [x] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT with improvements] The PowerShell `-i` ambiguity is a real constraint of the wrapper, not a CLI bug. The suggestion to use `--%` is brittle (it blocks all PowerShell features, including variable expansion users may rely on). A better triage: (1) document `--interactive` as the canonical flag name in the snapshot help text and SKILL.md, (2) detect `-i` interception in b4w.ps1 and emit a specific hint ("Use --interactive instead of -i in PowerShell"), and (3) leave `--%` as an advanced-user escape hatch. WONTFIX the PowerShell binding itself — that's a platform limitation, not a defect.

---

### Issue 4: Extension session tab-close shows confusing dual-message output

**Severity:** Medium
**Category:** UX

#### Reproduction

In an extension session, run `./b4w.ps1 tab-close 1` or `./b4w.ps1 -s <ext-session> tab-close`.

#### Expected Behavior

Clean output: 'Closed tab 1 (url — GUID: chrome:xxx)'. Possibly a note about extension behavior, but in a non-alarming format.

#### Actual Behavior

'Note: Tab was closed but the backend reported: ERROR: browser_tabs failed: closeTab ... Closed tab 1 (url — GUID: xxx)'. The ERROR text looks like a failure even though the operation succeeded. The Java method signature in the help text is confusing.

#### Root Cause Analysis

Per SKILL.md line 110, 'chrome.tabs.remove callback can fire an error after the tab is already gone'. The CLI correctly handles this (verifies removal and treats as success) but presents the backend error verbatim before the success message, creating a WARNING-then-OK pattern that reads like a failure.

#### AI Suggested Improvement

- Suppress the raw backend ERROR text when the CLI has verified the tab was actually removed
- Replace with a single informational note: 'Tab closed (extension session — backend confirmation delayed)'
- Or demote the ERROR to a debug-level message and show only 'Closed tab N (url — GUID: xxx)' on stdout
- Remove the Java method signature from user-facing output (it belongs in --verbose/debug mode only)

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Valid UX issue — the raw ERROR text makes a successful operation look like a failure. This is the extension-session counterpart to Issue 2's GUID-not-found case; together they show the tab-close output pipeline needs two distinct error paths: "close succeeded but backend callback raced" (suppress ERROR, show info) vs. "close failed because tab wasn't found" (surface error, non-zero exit). Cross-reference Issue 7 as well — the Java method signature in the help text is part of the same output-cleanliness problem.

---

### Issue 5: Extension session GUID format changes from chrome: prefix to hex after session becomes stale

**Severity:** Medium
**Category:** Reliability

#### Reproduction

1. Create extension session, observe GUIDs like chrome:217100829
2. Close all tabs via extension session
3. Create a new tab via extension session
4. GUID is now hex (e.g. E931A329...) instead of chrome:-prefixed

#### Expected Behavior

GUID format should be consistent for a given session type. Extension sessions should always show chrome:-prefixed GUIDs.

#### Actual Behavior

After the extension session becomes 'Stale' (all tabs closed), new tabs show 32-char hex GUIDs instead of chrome:-prefixed GUIDs, even though the session's CONNECTION column still shows 'Extension'.

#### Root Cause Analysis

When the extension session becomes stale, the tab management likely falls back to a different code path (possibly CDP-based) that generates hex GUIDs instead of using Chrome extension tab IDs. The session metadata still shows 'Extension' but the GUID format no longer reflects the extension origin.

#### AI Suggested Improvement

- Ensure GUID format is determined by session type (from session metadata), not by the current connection state
- Cache the session connection type at creation time and use it for GUID formatting throughout the session lifecycle
- If the session reverts to a non-extension transport, update the CONNECTION column to reflect this change

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Legitimate reliability concern. GUID format should be stable per session type, not drift based on stale/active state. The root cause (fallback to CDP path generating hex GUIDs) means tab identity tracking across commands can break — a user that records a GUID from tab-list may find it unusable for tab-close moments later. The CONNECTION column not updating when the transport changes is a secondary metadata-integrity issue worth fixing alongside the GUID formatting.

---

### Issue 6: tab-list --json output format differs from documentation

**Severity:** Low
**Category:** Documentation

#### Reproduction

Run `./b4w.ps1 tab-list --json` and compare with SKILL.md documentation.

#### Expected Behavior

Documentation (SKILL.md line 104) says output includes a 'tabs' array with 'index', 'guid', 'url', 'title' plus a 'count' field.

#### Actual Behavior

Output is wrapped in a JSON envelope: {"command":"tab-list","output":{"count":1,"tabs":[...]},"status":"ok"}. The 'tabs' array and 'count' are nested inside 'output', not at the top level. Also, '--json' works both as a command-level flag (`tab-list --json`) and as a global flag (`--json tab-list`), but the documentation only mentions the global form.

#### Root Cause Analysis

The documentation describes the inner payload structure but omits the outer envelope (command, output, status). The envelope is a general CLI convention applied to all --json output, but the SKILL.md example implies the tabs array is at the top level.

#### AI Suggested Improvement

- Update SKILL.md line 104 to show the full JSON envelope structure with a note that tabs are nested inside output
- Document both `--json tab-list` and `tab-list --json` as valid invocation forms
- Add a JSON output example showing the exact format

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Straightforward documentation gap. The JSON envelope (`{command, output, status}`) is a general CLI convention and should be documented that way. Both invocation forms should be shown. No code change needed — SKILL.md update only.

---

### Issue 7: Error messages include raw Java method signatures in user-facing output

**Severity:** Low
**Category:** UX

#### Reproduction

./b4w.ps1 tab-select 99 or ./b4w.ps1 tab-close --guid "bogus"

#### Expected Behavior

Clean, human-readable error: 'Error: Tab index 99 is out of range. There are 3 tabs (indices 0-2).'

#### Actual Behavior

'ERROR: browser_tabs failed: Tab index '99' out of range; found 3 tabs help: Switch to a specific browser tab by its zero-based index or GUID\nbrowser.switchTab(Arg(name=index, type=Int, defaultValue=null), Arg(name=tabId, type=String, defaultValue=null))'

#### Root Cause Analysis

The CLI is surfacing internal Java method signatures (browser.switchTab/browser.closeTab with Arg definitions) as part of the user-facing error output. These signatures are useful for developers but confusing for end users.

#### AI Suggested Improvement

- Strip Java method signatures from default output; show them only in --verbose/debug mode
- Format error messages as: 'Error: <human-readable description>' followed by 'Suggestion: <actionable hint>'
- Move the method signature to a debug/trace log level

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] This is the umbrella issue for raw-backend-output-leaking, of which Issues 2 and 4 are specific instances. The Java method signatures (`Arg(name=index, type=Int, …)`) have zero value to end users and meaningful cost in confusion. Fix should be applied globally: strip signatures from stdout, gate them behind `--verbose`, and reformat all error output as `Error: <human-readable>` + optional `Suggestion: <actionable>`. Severity is correctly Low because it doesn't affect correctness, only clarity.

---

### Issue 8: Goto command navigation message inconsistent — says 'already at' but actually navigates

**Severity:** Low
**Category:** UX

#### Reproduction

When switching to a tab that already has example.com loaded and running `goto https://example.com`.

#### Expected Behavior

Clear indication: either 'Already at https://example.com' (no navigation) or 'Navigated to https://example.com' (navigation occurred).

#### Actual Behavior

Sometimes produces 'Using existing session DEFAULT (current page: https://example.com/).' and shows a full snapshot output. The behavior is ambiguous — it's unclear whether a navigation actually occurred or the page was left as-is.

#### Root Cause Analysis

The goto command always triggers a page load check but doesn't clearly distinguish between 'page was already at this URL, no navigation needed' and 'navigation occurred to this URL'. The snapshot output is always generated regardless.

#### AI Suggested Improvement

- Add explicit 'Already at <url> — no navigation needed' message when the current URL matches the target
- Or add 'Navigated to <url>' vs 'Already at <url>' distinction in the output
- Consider skipping the snapshot when no navigation occurred (add --force-snapshot flag for users who want it)

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [x] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT with improvements] The distinction between "already at URL" and "navigated to URL" is worth making, but the suggestion to skip snapshot on no-op navigation should default to OFF, not ON — users who run `goto` to verify page state expect the snapshot. Recommend: always show snapshot, but prefix with "Already at <url> — page unchanged" vs. "Navigated to <url>" so the user can interpret the snapshot context correctly. A `--quiet` or `--no-snapshot` flag for scripting use is a better addition than `--force-snapshot` (which inverts the default). Severity Low is correct.

---

## Overall Assessment

**Completion Status:** Successful — All 31 steps completed across Part A (Regular), Part B (Extension), and Part C (Mixed). Full tab lifecycle verified: create, list, select, close, GUID operations, error handling, session isolation, cross-session independence. Two step-level failures recorded (A.16: misleading GUID error; C.5: no session-switching command) but both were worked around.

**Success Rate:** 94% — 29 of 31 steps passed cleanly. Two steps produced failures: A.16 (misleading success for invalid GUID close) and C.5 (no session-switching command). Both had workarounds available.

**Issues Found:** 8

**Major Blockers:** No session-switching command exists. Users in multi-session environments must prefix every command with `-s <session-id>` to target a non-default session. This is tedious and error-prone for workflows that alternate between sessions.

**Most Confusing Aspects:** 1. No `session` command to change the default session (had to discover this by trial and error). 2. Extension session tab-close outputs ERROR followed by success — hard to tell if the operation actually worked. 3. The `--help session` category shows sessionStorage commands, not browser session management, which is misleading. 4. Chrome tab insert positions are unpredictable (dependent on native Chrome behavior).

**Most Valuable Improvements:** 1. Add a `session <id>` command to switch the default session. 2. Fix the misleading success message for tab-close with invalid GUID. 3. Clean up extension session close output to not show raw ERROR text. 4. Add a 'Quick Start' or 'Common Workflows' section showing multi-session usage patterns.

**Usability Rating:** 6/10

---

## How to Reproduce

### Common Setup

1. Clone the repository and `cd` to the repo root.
2. The CLI is invoked via `./b4w.ps1` which auto-builds from source when needed.
3. The backend server starts automatically in dev mode.
4. All commands from repo root: `./b4w.ps1 <command>`

### Per-Issue Reproduction Steps

#### Issue 1: No session-switching command exists (session/switch/use)

In a multi-session environment (regular + extension), run `./b4w.ps1 session <session-id>`, `./b4w.ps1 switch <id>`, or `./b4w.ps1 use <id>`. All return 'Unknown command'.

#### Issue 2: tab-close --guid with nonexistent GUID reports misleading success

./b4w.ps1 tab-close --guid "nonexistent-guid-12345"

#### Issue 3: PowerShell -i flag conflicts with snapshot interactive mode

./b4w.ps1 snapshot -i or ./b4w.ps1 -- snapshot -i

#### Issue 4: Extension session tab-close shows confusing dual-message output

In an extension session, run `./b4w.ps1 tab-close 1` or `./b4w.ps1 -s <ext-session> tab-close`.

#### Issue 5: Extension session GUID format changes from chrome: prefix to hex after session becomes stale

1. Create extension session, observe GUIDs like chrome:217100829
2. Close all tabs via extension session
3. Create a new tab via extension session
4. GUID is now hex (e.g. E931A329...) instead of chrome:-prefixed

#### Issue 6: tab-list --json output format differs from documentation

Run `./b4w.ps1 tab-list --json` and compare with SKILL.md documentation.

#### Issue 7: Error messages include raw Java method signatures in user-facing output

./b4w.ps1 tab-select 99 or ./b4w.ps1 tab-close --guid "bogus"

#### Issue 8: Goto command navigation message inconsistent — says 'already at' but actually navigates

When switching to a tab that already has example.com loaded and running `goto https://example.com`.

