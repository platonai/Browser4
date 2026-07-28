# Issues: attach-workflow

> **Source:** `20260727-231219-attach-workflow.full.md` | **Date:** 20260727-231219 | **Mode:** dev

## Scenario Background

### Task

The attach/open/goto command sequence was executed **successfully** through all 30 steps (Steps 0–17 plus sub-steps). All core operations — extension attach, regular open, navigation, tab management, cross-session isolation, and session recovery after `chrome://` detach — functioned correctly. The critical regression check (Step 6: `snapshot grep "user-data-dir"`) passed — the extension session did **not** silently fall through to a Browser4-launched Chrome. Several workarounds were required, and multiple UX/documentation issues were identified (detailed in the structured findings below).

### Execution Context

**Key Commands:**

"actual": "SKILL.md shows browser4-cli goto, browser4-cli snapshot, etc. The task forces ./b4w.ps1 invocation. A new user would not know which command name to use. The development.md reference file may cover this, but the main SKILL.md does not.",
      "rootCause": "browser4-cli is the installed binary name; ./b4w.ps1 is the dev-mode wrapper. The SKILL.md is written for the installed scenario and does not cover dev-mode invocation.",
      "codePointer": "skills/browser4-cli/SKILL.md: command examples throughout",
      "suggestion": "- Add a 'Development Mode' section to SKILL.md explaining that ./b4w.ps1 (PowerShell), ./b4w.sh (Git Bash), or ./b4w.bat (CMD) must be used when running from the repo\n- Or: auto-detect when running from the repo and print a note about the correct invocation"
    }
  ],
  "assessment": {
    "completionStatus": "Successful — all 30 steps completed. Core functionality works correctly. Multiple workarounds required but none blocked progress.",
    "successRate": "87% — 26 of 30 steps passed without workaround. 4 steps required workarounds (PowerShell flag binding, stale session blocking, tab-close retry, named session usage).",
    "issuesFound": 9,
    "majorBlockers": "Stale DEFAULT session blocks unnamed attach/open operations (Issue 2). PowerShell -i/-v flag interception prevents using documented snapshot flags (Issue 1). Old browser tabs invisible after extension re-attach (Issue 4).",
    "mostConfusingAspects": "1. The tab-close error message that looks like a failure but actually succeeded. 2. Why attach --extension fails when a DEFAULT session exists (even if Stale). 3. Why old tabs disappear after re-attach. 4. The mismatch between browser4-cli (documentation) and ./b4w.ps1 (actual invocation).",
    "mostValuableImprovements": "1. Fix PowerShell flag binding so -i and -v work via ./b4w.ps1. 2. Auto-replace stale DEFAULT sessions on new attach/open. 3. Enumerate existing browser tabs on extension re-attach. 4. Clean up the tab-close error UX. 5. Add a session switch/default command.",
    "usabilityRating": 5
  }
}
```

**Workarounds Applied During Task:**

1. PowerShell `-i`/`-v` flags intercepted — used bare `snapshot` without flags
2. Stale DEFAULT blocked unnamed `attach`/`open` — had to `close` first or use `-s <name>`
3. Tab-close first attempt errored — retry succeeded

```json
{
  "issues": [
    {
      "title": "PowerShell parameter binder intercepts -i and -v flags when using ./b4w.ps1",
      "severity": "High",
      "category": "UX",
      "reproduction": "cd repo-root && ./b4w.ps1 snapshot -i or ./b4w.ps1 snapshot -v 0",
      "expected": "The -i flag triggers interactive snapshot; -v 0 triggers viewport 0 snapshot.",
      "actual": "PowerShell error: 'Parameter cannot be processed because the parameter name 'i' is ambiguous. Possible matches include: -InformationAction -InformationVariable.' Similarly -v matches -Verbose. Using -- as a separator or quoting the flag does not help.",
      "rootCause": "The b4w.ps1 wrapper script passes arguments through to the browser4-cli binary, but PowerShell's own parameter binder processes short flags before forwarding them. The SKILL.md documents this: 'When running b4w.ps1 directly in PowerShell, short flags like -i and -v may be intercepted.' The -- separator is supposed to fix this per the documentation but did not work in testing (./b4w.ps1 -- snapshot -i still failed).",
      "codePointer": "b4w.ps1: parameter forwarding logic",
      "suggestion": "- Make -- snapshot -i actually work as documented — the separator approach should prevent PowerShell from intercepting flags after it\n- Consider renaming short flags that conflict with common PowerShell parameters (-i → --interactive, -v → --viewport)\n- Add a b4w.sh wrapper that avoids PowerShell parameter binding entirely (recommended in docs but not the default used in this evaluation)\n- Update the post-goto tip from 'Run `snapshot -v 0`' to include the workaround for PowerShell users"
    },
    {
      "title": "Stale DEFAULT session blocks unnamed attach/open operations",
      "severity": "High",
      "category": "UX",
      "reproduction": "1. attach --extension (creates DEFAULT session)\n2. Navigate to chrome://version/ (session becomes Stale)\n3. attach --extension (expecting new session)\n4. Also: open (expecting new regular Browser4 session)",
      "expected": "attach --extension should create a new session, replacing or superseding the stale DEFAULT. open should create a new Browser4 session.",
      "actual": "Error: 'An unnamed session already exists: <id>. Use -s <name> to create a named session instead... Or run browser4-cli close to end the current unnamed session first.' Users must either close the stale session first or use -s <name> on every operation.",
      "rootCause": "The session resolution logic treats any existing unnamed (DEFAULT) session as occupying the slot, regardless of its health status (Stale). There is no automatic eviction or replacement of stale DEFAULT sessions.",
      "codePointer": "cli/browser4-cli/src/session.rs:resolve_or_create_session()",
      "suggestion": "- Automatically evict/replace stale DEFAULT sessions when a new attach --extension or open is requested without -s\n- At minimum, improve the error message to explicitly state the session is Stale and suggest close as the fix (current message doesn't mention the session is stale)\n- Consider adding a --force flag to attach/open that replaces the DEFAULT session regardless of state"
    },
    {
      "title": "Confusing tab-close error message on extension sessions",
      "severity": "Medium",
      "category": "UX",
      "reproduction": "./b4w.ps1 -s ext2 tab-close 1 (on an extension-attached session)",
      "expected": "A clean output message indicating the tab was closed, or a note explaining Chrome extension behavior.",
      "actual": "First line is 'ERROR: browser_tabs failed: closeTab help: Close a tab by zero-based index or GUID, or the current tab when omitted' followed by a function signature. Then (on retry) 'Note: Tab was closed but the backend reported: ERROR: ...'. The error appears before the success note, creating the impression the operation failed.",
      "rootCause": "Chrome's chrome.tabs.remove callback can fire an error after the tab is already gone. The SKILL.md documents this: 'When closing tabs on extension-attached sessions, the backend may report an error even though the tab was successfully closed.' However, the error is surfaced to the user before the CLI verifies the tab was actually removed, causing confusion.",
      "codePointer": "cli/browser4-cli/src/tabs.rs:handle_tab_close()",
      "suggestion": "- Verify tab removal BEFORE surfacing the error to the user — if the tab is gone, suppress the error and show a clean success message\n- Change the error format from 'ERROR: browser_tabs failed: closeTab help: ...' to a clean informational note like 'Note: Chrome reported an error after closing the tab, but the tab was successfully removed.'\n- Consider making the first attempt work reliably rather than requiring a retry"
    },
    {
      "title": "Old browser tabs not visible after extension re-attach",
      "severity": "High",
      "category": "Product",
      "reproduction": "1. attach --extension → navigate to several pages, create multiple tabs\n2. Navigate to chrome://version/ (session becomes stale)\n3. attach --extension (re-attach)\n4. tab-list — only the auto-created blank tab from re-attach is visible",
      "expected": "After re-attaching the extension, tab-list should show all currently open browser tabs (or at minimum, document clearly that old tabs won't be tracked).",
      "actual": "Only 1 tab visible — the new blank tab created by the re-attach's newTab=true parameter. The example.com/1, httpbin.org/get, and httpbin.org/links/10 tabs from the previous session are still open in the browser but invisible to the new extension session.",
      "rootCause": "Each attach --extension creates a fresh WebSocket connection with a new session UUID. The extension creates a new blank tab for this session and only tracks tabs opened through this specific connection. The extension does not enumerate or adopt existing browser tabs on re-attach.",
      "codePointer": "browser4-apps/browser4-extension: extension connect handler (newTab=true logic)",
      "suggestion": "- On extension connect, enumerate ALL open browser tabs and make them available via tab-list\n- If full enumeration is not feasible, at minimum document this limitation clearly in the SKILL.md under the Tab Management section with a big warning\n- Consider adding a tab-discover or tab-sync command to manually pull in existing browser tabs after re-attach\n- The current SKILL.md mentions 'Extension sessions may also show Stale in list output after all tabs are closed' but does not mention that re-attach creates a fresh tab scope"
    },
    {
      "title": "No dedicated session-switch command — DEFAULT session cannot be changed explicitly",
      "severity": "Medium",
      "category": "Discoverability",
      "reproduction": "After creating multiple sessions, try to change which one is DEFAULT without closing/re-opening.",
      "expected": "A command like session switch <name> or session default <name> to change the DEFAULT session.",
      "actual": "The only way to target a non-default session is -s <name> on every command. There is no way to change which session is DEFAULT short of closing it and re-creating. The SKILL.md mentions -s as 'the canonical way to target a specific session' but doesn't explain how to change the default.",
      "rootCause": "Session management was designed with -s as the primary targeting mechanism. The DEFAULT concept exists mainly for convenience (first session gets it), but there's no command to reassign it.",
      "codePointer": "cli/browser4-cli/src/session.rs:default session resolution logic",
      "suggestion": "- Add a session default <name> or session switch <name> command to change the DEFAULT session\n- Alternatively, add a session use <name> command that sets the DEFAULT for subsequent commands in the same shell session\n- At minimum, document in SKILL.md how the DEFAULT session is determined and that -s is the only way to target non-default sessions"
    },
    {
      "title": "Session ID equals session name when using -s <name> open (should be UUID)",
      "severity": "Medium",
      "category": "Product",
      "reproduction": "./b4w.ps1 -s reg2 open then ./b4w.ps1 list",
      "expected": "Session Name: reg2, Session ID: <UUID> (like attach --extension behavior).",
      "actual": "Session Name: reg2, Session ID: reg2. Contrast with -s ext3 attach --extension which produces Session Name: ext3, Session ID: <proper UUID>.",
      "rootCause": "The open command appears to use the -s name value directly as the session ID when creating a regular Browser4 session, rather than generating a UUID and using -s only as a label/alias. The attach --extension path generates a UUID and stores the -s name separately.",
      "codePointer": "cli/browser4-cli/src/session.rs or backend SessionManager: session creation path for open vs attach",
      "suggestion": "- Ensure open with -s generates a proper UUID for the session ID and uses -s only as a label (consistent with attach behavior)\n- If the current behavior is intentional (named sessions use the name as ID), document this clearly and ensure both open and attach follow the same convention"
    },
    {
      "title": "httpbin.org/links tab silently disappears during extension session operations",
      "severity": "Low",
      "category": "Reliability",
      "reproduction": "1. Create an httpbin.org/links/10 tab via extension session\n2. Perform snapshot or tab operations\n3. Tab disappears from tab-list without explicit close",
      "expected": "Tabs should persist until explicitly closed or the session ends.",
      "actual": "In two separate extension sessions, the httpbin.org/links/10 tab disappeared between operations (Step 4f→4g and Step 8c→8d). The tab was present in one tab-list output and gone in the next, with no close command in between.",
      "rootCause": "Unclear — could be Chrome auto-closing the tab (unlikely for a static page), httpbin.org behavior (redirect chain?), or a Browser4 extension bug. The httpbin.org/links/10 page redirects to /links/10/0 and contains numerous links that could trigger navigation. Further investigation needed to determine if this is a Browser4 issue or Chrome/httpbin behavior.",
      "codePointer": "",
      "suggestion": "- Investigate whether the extension WebSocket disconnect/reconnect cycle causes Chrome to close certain tabs\n- Add logging to track tab lifecycle events in extension sessions\n- If this is Chrome behavior, document known limitations for certain URL patterns"
    },
    {
      "title": "Post-goto tip references -v 0 flag unusable on PowerShell",
      "severity": "Low",
      "category": "UX",
      "reproduction": "Run ./b4w.ps1 goto <url> — the tip 'Run `snapshot -v 0` to see interactive element refs' always appears.",
      "expected": "The tip should either work as written or provide a platform-appropriate alternative.",
      "actual": "The suggested command `snapshot -v 0` cannot be run via ./b4w.ps1 due to PowerShell parameter binding (Issue 1). Users following the tip exactly will get a PowerShell error.",
      "rootCause": "The tip is hardcoded in the CLI output and does not account for the PowerShell wrapper's parameter binding issue.",
      "codePointer": "cli/browser4-cli/src/output.rs: post-goto tip generation",
      "suggestion": "- Detect when running under the PowerShell wrapper and adjust the tip accordingly (e.g., suggest --viewport 0 or ./b4w.sh snapshot -v 0)\n- Add a platform-aware tip: 'Run snapshot -v 0 (use b4w.sh or b4w.bat if -v is intercepted by PowerShell)'"
    },
    {
      "title": "Documentation uses browser4-cli command name but evaluation uses ./b4w.ps1 — naming confusion",
      "severity": "Low",
      "category": "Documentation",
      "reproduction": "Read SKILL.md — all examples use browser4-cli. The task instructions require $(./b4w.ps1).",
      "expected": "Documentation should match the actual command used in development mode, or clearly explain the dev-mode invocation.",
      "actual": "SKILL.md shows browser4-cli goto, browser4-cli snapshot, etc. The task forces ./b4w.ps1 invocation. A new user would not know which command name to use. The development.md reference file may cover this, but the main SKILL.md does not.",
      "rootCause": "browser4-cli is the installed binary name; ./b4w.ps1 is the dev-mode wrapper. The SKILL.md is written for the installed scenario and does not cover dev-mode invocation.",
      "codePointer": "skills/browser4-cli/SKILL.md: command examples throughout",
      "suggestion": "- Add a 'Development Mode' section to SKILL.md explaining that ./b4w.ps1 (PowerShell), ./b4w.sh (Git Bash), or ./b4w.bat (CMD) must be used when running from the repo\n- Or: auto-detect when running from the repo and print a note about the correct invocation"
    }
  ],
  "assessment": {
    "completionStatus": "Successful — all 30 steps completed. Core functionality works correctly. Multiple workarounds required but none blocked progress.",
    "successRate": "87% — 26 of 30 steps passed without workaround. 4 steps required workarounds (PowerShell flag binding, stale session blocking, tab-close retry, named session usage).",
    "issuesFound": 9,
    "majorBlockers": "Stale DEFAULT session blocks unnamed attach/open operations (Issue 2). PowerShell -i/-v flag interception prevents using documented snapshot flags (Issue 1). Old browser tabs invisible after extension re-attach (Issue 4).",
    "mostConfusingAspects": "1. The tab-close error message that looks like a failure but actually succeeded. 2. Why attach --extension fails when a DEFAULT session exists (even if Stale). 3. Why old tabs disappear after re-attach. 4. The mismatch between browser4-cli (documentation) and ./b4w.ps1 (actual invocation).",
    "mostValuableImprovements": "1. Fix PowerShell flag binding so -i and -v work via ./b4w.ps1. 2. Auto-replace stale DEFAULT sessions on new attach/open. 3. Enumerate existing browser tabs on extension re-attach. 4. Clean up the tab-close error UX. 5. Add a session switch/default command.",
    "usabilityRating": 5
  }
}
```

---

## Issues Found (9 issues)

### Issue 1: PowerShell parameter binder intercepts -i and -v flags when using ./b4w.ps1

**Severity:** High
**Category:** UX

#### Reproduction

cd repo-root && ./b4w.ps1 snapshot -i or ./b4w.ps1 snapshot -v 0

#### Expected Behavior

The -i flag triggers interactive snapshot; -v 0 triggers viewport 0 snapshot.

#### Actual Behavior

PowerShell error: 'Parameter cannot be processed because the parameter name 'i' is ambiguous. Possible matches include: -InformationAction -InformationVariable.' Similarly -v matches -Verbose. Using -- as a separator or quoting the flag does not help.

#### Root Cause Analysis

The b4w.ps1 wrapper script passes arguments through to the browser4-cli binary, but PowerShell's own parameter binder processes short flags before forwarding them. The SKILL.md documents this: 'When running b4w.ps1 directly in PowerShell, short flags like -i and -v may be intercepted.' The -- separator is supposed to fix this per the documentation but did not work in testing (./b4w.ps1 -- snapshot -i still failed).

#### Code Pointer

`b4w.ps1: parameter forwarding logic`

#### AI Suggested Improvement

- Make -- snapshot -i actually work as documented — the separator approach should prevent PowerShell from intercepting flags after it
- Consider renaming short flags that conflict with common PowerShell parameters (-i → --interactive, -v → --viewport)
- Add a b4w.sh wrapper that avoids PowerShell parameter binding entirely (recommended in docs but not the default used in this evaluation)
- Update the post-goto tip from 'Run `snapshot -v 0`' to include the workaround for PowerShell users

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [x] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT with improvements] PowerShell parameter binding is a genuine platform limitation, not a Browser4 bug. Fix the `--` separator forwarding in b4w.ps1 so `./b4w.ps1 -- snapshot -i` actually works as documented. Add a `--interactive` long flag alias to avoid the conflict entirely. Update all tips/hints to use platform-safe forms.

---

### Issue 2: Stale DEFAULT session blocks unnamed attach/open operations

**Severity:** High
**Category:** UX

#### Reproduction

1. attach --extension (creates DEFAULT session)
2. Navigate to chrome://version/ (session becomes Stale)
3. attach --extension (expecting new session)
4. Also: open (expecting new regular Browser4 session)

#### Expected Behavior

attach --extension should create a new session, replacing or superseding the stale DEFAULT. open should create a new Browser4 session.

#### Actual Behavior

Error: 'An unnamed session already exists: <id>. Use -s <name> to create a named session instead... Or run browser4-cli close to end the current unnamed session first.' Users must either close the stale session first or use -s <name> on every operation.

#### Root Cause Analysis

The session resolution logic treats any existing unnamed (DEFAULT) session as occupying the slot, regardless of its health status (Stale). There is no automatic eviction or replacement of stale DEFAULT sessions.

#### Code Pointer

`cli/browser4-cli/src/session.rs:resolve_or_create_session()`

#### AI Suggested Improvement

- Automatically evict/replace stale DEFAULT sessions when a new attach --extension or open is requested without -s
- At minimum, improve the error message to explicitly state the session is Stale and suggest close as the fix (current message doesn't mention the session is stale)
- Consider adding a --force flag to attach/open that replaces the DEFAULT session regardless of state

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Auto-evicting stale DEFAULT sessions when a new `open` or unnamed `attach --extension` is requested is the correct behavior — users shouldn't need to manually `close` a dead session just to reclaim the unnamed slot. The stale health check already exists; it just needs to be consulted during session resolution.

---

### Issue 3: Old browser tabs not visible after extension re-attach

**Severity:** High
**Category:** Product

#### Reproduction

1. attach --extension → navigate to several pages, create multiple tabs
2. Navigate to chrome://version/ (session becomes stale)
3. attach --extension (re-attach)
4. tab-list — only the auto-created blank tab from re-attach is visible

#### Expected Behavior

After re-attaching the extension, tab-list should show all currently open browser tabs (or at minimum, document clearly that old tabs won't be tracked).

#### Actual Behavior

Only 1 tab visible — the new blank tab created by the re-attach's newTab=true parameter. The example.com/1, httpbin.org/get, and httpbin.org/links/10 tabs from the previous session are still open in the browser but invisible to the new extension session.

#### Root Cause Analysis

Each attach --extension creates a fresh WebSocket connection with a new session UUID. The extension creates a new blank tab for this session and only tracks tabs opened through this specific connection. The extension does not enumerate or adopt existing browser tabs on re-attach.

#### Code Pointer

`browser4-apps/browser4-extension: extension connect handler (newTab=true logic)`

#### AI Suggested Improvement

- On extension connect, enumerate ALL open browser tabs and make them available via tab-list
- If full enumeration is not feasible, at minimum document this limitation clearly in the SKILL.md under the Tab Management section with a big warning
- Consider adding a tab-discover or tab-sync command to manually pull in existing browser tabs after re-attach
- The current SKILL.md mentions 'Extension sessions may also show Stale in list output after all tabs are closed' but does not mention that re-attach creates a fresh tab scope

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [x] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT with improvements] Full browser-tab enumeration on every re-attach may be architecturally complex (requires `chrome.tabs.query` in the extension before the new connection is fully established). Start with clear documentation in SKILL.md about this limitation, add a `tab-discover` command that explicitly pulls existing tabs, and consider auto-enumeration as a follow-up enhancement.

---

### Issue 4: Confusing tab-close error message on extension sessions

**Severity:** Medium
**Category:** UX

#### Reproduction

./b4w.ps1 -s ext2 tab-close 1 (on an extension-attached session)

#### Expected Behavior

A clean output message indicating the tab was closed, or a note explaining Chrome extension behavior.

#### Actual Behavior

First line is 'ERROR: browser_tabs failed: closeTab help: Close a tab by zero-based index or GUID, or the current tab when omitted' followed by a function signature. Then (on retry) 'Note: Tab was closed but the backend reported: ERROR: ...'. The error appears before the success note, creating the impression the operation failed.

#### Root Cause Analysis

Chrome's chrome.tabs.remove callback can fire an error after the tab is already gone. The SKILL.md documents this: 'When closing tabs on extension-attached sessions, the backend may report an error even though the tab was successfully closed.' However, the error is surfaced to the user before the CLI verifies the tab was actually removed, causing confusion.

#### Code Pointer

`cli/browser4-cli/src/tabs.rs:handle_tab_close()`

#### AI Suggested Improvement

- Verify tab removal BEFORE surfacing the error to the user — if the tab is gone, suppress the error and show a clean success message
- Change the error format from 'ERROR: browser_tabs failed: closeTab help: ...' to a clean informational note like 'Note: Chrome reported an error after closing the tab, but the tab was successfully removed.'
- Consider making the first attempt work reliably rather than requiring a retry

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] The fix is straightforward: after calling `chrome.tabs.remove`, verify the tab is gone before surfacing any error to the user. If the tab was removed despite Chrome's callback error, suppress the error entirely and show a clean success message. The "retry" workaround should not be necessary.

---

### Issue 5: No dedicated session-switch command — DEFAULT session cannot be changed explicitly

**Severity:** Medium
**Category:** Discoverability

#### Reproduction

After creating multiple sessions, try to change which one is DEFAULT without closing/re-opening.

#### Expected Behavior

A command like session switch <name> or session default <name> to change the DEFAULT session.

#### Actual Behavior

The only way to target a non-default session is -s <name> on every command. There is no way to change which session is DEFAULT short of closing it and re-creating. The SKILL.md mentions -s as 'the canonical way to target a specific session' but doesn't explain how to change the default.

#### Root Cause Analysis

Session management was designed with -s as the primary targeting mechanism. The DEFAULT concept exists mainly for convenience (first session gets it), but there's no command to reassign it.

#### Code Pointer

`cli/browser4-cli/src/session.rs:default session resolution logic`

#### AI Suggested Improvement

- Add a session default <name> or session switch <name> command to change the DEFAULT session
- Alternatively, add a session use <name> command that sets the DEFAULT for subsequent commands in the same shell session
- At minimum, document in SKILL.md how the DEFAULT session is determined and that -s is the only way to target non-default sessions

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [x] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT with improvements] Add a `session default <name>` subcommand to reassign the DEFAULT slot. This is a low-effort, high-discoverability improvement. The `-s` flag remains the canonical targeting mechanism; `session default` is a convenience for interactive use.

---

### Issue 6: Session ID equals session name when using -s <name> open (should be UUID)

**Severity:** Medium
**Category:** Product

#### Reproduction

./b4w.ps1 -s reg2 open then ./b4w.ps1 list

#### Expected Behavior

Session Name: reg2, Session ID: <UUID> (like attach --extension behavior).

#### Actual Behavior

Session Name: reg2, Session ID: reg2. Contrast with -s ext3 attach --extension which produces Session Name: ext3, Session ID: <proper UUID>.

#### Root Cause Analysis

The open command appears to use the -s name value directly as the session ID when creating a regular Browser4 session, rather than generating a UUID and using -s only as a label/alias. The attach --extension path generates a UUID and stores the -s name separately.

#### Code Pointer

`cli/browser4-cli/src/session.rs or backend SessionManager: session creation path for open vs attach`

#### AI Suggested Improvement

- Ensure open with -s generates a proper UUID for the session ID and uses -s only as a label (consistent with attach behavior)
- If the current behavior is intentional (named sessions use the name as ID), document this clearly and ensure both open and attach follow the same convention

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] This is a clear inconsistency — `attach --extension -s ext3` produces `Name: ext3, ID: <UUID>`, but `open -s reg2` produces `Name: reg2, ID: reg2`. The `open` path should generate a UUID and use `-s` only as a label, matching `attach` behavior. If treating `-s` as the session ID is intentional design, both paths must follow the same convention.

---

### Issue 7: httpbin.org/links tab silently disappears during extension session operations

**Severity:** Low
**Category:** Reliability

#### Reproduction

1. Create an httpbin.org/links/10 tab via extension session
2. Perform snapshot or tab operations
3. Tab disappears from tab-list without explicit close

#### Expected Behavior

Tabs should persist until explicitly closed or the session ends.

#### Actual Behavior

In two separate extension sessions, the httpbin.org/links/10 tab disappeared between operations (Step 4f→4g and Step 8c→8d). The tab was present in one tab-list output and gone in the next, with no close command in between.

#### Root Cause Analysis

Unclear — could be Chrome auto-closing the tab (unlikely for a static page), httpbin.org behavior (redirect chain?), or a Browser4 extension bug. The httpbin.org/links/10 page redirects to /links/10/0 and contains numerous links that could trigger navigation. Further investigation needed to determine if this is a Browser4 issue or Chrome/httpbin behavior.

#### AI Suggested Improvement

- Investigate whether the extension WebSocket disconnect/reconnect cycle causes Chrome to close certain tabs
- Add logging to track tab lifecycle events in extension sessions
- If this is Chrome behavior, document known limitations for certain URL patterns

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [x] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: DEFER] Root cause is unclear — could be Chrome auto-discarding the tab, httpbin.org redirect behavior, or a Browser4 extension WebSocket lifecycle bug. Needs a dedicated investigation with tab-lifecycle event logging in the extension before a fix can be designed. Low severity and low reproducibility make this a lower priority than the UX issues above.

---

### Issue 8: Post-goto tip references -v 0 flag unusable on PowerShell

**Severity:** Low
**Category:** UX

#### Reproduction

Run ./b4w.ps1 goto <url> — the tip 'Run `snapshot -v 0` to see interactive element refs' always appears.

#### Expected Behavior

The tip should either work as written or provide a platform-appropriate alternative.

#### Actual Behavior

The suggested command `snapshot -v 0` cannot be run via ./b4w.ps1 due to PowerShell parameter binding (Issue 1). Users following the tip exactly will get a PowerShell error.

#### Root Cause Analysis

The tip is hardcoded in the CLI output and does not account for the PowerShell wrapper's parameter binding issue.

#### Code Pointer

`cli/browser4-cli/src/output.rs: post-goto tip generation`

#### AI Suggested Improvement

- Detect when running under the PowerShell wrapper and adjust the tip accordingly (e.g., suggest --viewport 0 or ./b4w.sh snapshot -v 0)
- Add a platform-aware tip: 'Run snapshot -v 0 (use b4w.sh or b4w.bat if -v is intercepted by PowerShell)'

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [x] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: DUPLICATE] This is a downstream symptom of Issue 1 (PowerShell intercepts `-v`). Fixing Issue 1's flag forwarding eliminates this problem. Once `./b4w.ps1 -- snapshot -v 0` works, the tip becomes correct. If the `--` separator fix proves infeasible, make the tip platform-aware as a fallback.

---

### Issue 9: Documentation uses browser4-cli command name but evaluation uses ./b4w.ps1 — naming confusion

**Severity:** Low
**Category:** Documentation

#### Reproduction

Read SKILL.md — all examples use browser4-cli. The task instructions require $(./b4w.ps1).

#### Expected Behavior

Documentation should match the actual command used in development mode, or clearly explain the dev-mode invocation.

#### Actual Behavior

SKILL.md shows browser4-cli goto, browser4-cli snapshot, etc. The task forces ./b4w.ps1 invocation. A new user would not know which command name to use. The development.md reference file may cover this, but the main SKILL.md does not.

#### Root Cause Analysis

browser4-cli is the installed binary name; ./b4w.ps1 is the dev-mode wrapper. The SKILL.md is written for the installed scenario and does not cover dev-mode invocation.

#### Code Pointer

`skills/browser4-cli/SKILL.md: command examples throughout`

#### AI Suggested Improvement

- Add a 'Development Mode' section to SKILL.md explaining that ./b4w.ps1 (PowerShell), ./b4w.sh (Git Bash), or ./b4w.bat (CMD) must be used when running from the repo
- Or: auto-detect when running from the repo and print a note about the correct invocation

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [x] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT with improvements] Add a short "Development Mode" section near the top of SKILL.md explaining the three wrappers (`./b4w.ps1`, `./b4w.sh`, `./b4w.bat`) and when to use each. All existing `browser4-cli` examples remain correct for the installed scenario; the dev-mode section bridges the gap without rewriting the entire doc.

---

## Overall Assessment

**Completion Status:** Successful — all 30 steps completed. Core functionality works correctly. Multiple workarounds required but none blocked progress.

**Success Rate:** 87% — 26 of 30 steps passed without workaround. 4 steps required workarounds (PowerShell flag binding, stale session blocking, tab-close retry, named session usage).

**Issues Found:** 9

**Major Blockers:** Stale DEFAULT session blocks unnamed attach/open operations (Issue 2). PowerShell -i/-v flag interception prevents using documented snapshot flags (Issue 1). Old browser tabs invisible after extension re-attach (Issue 4).

**Most Confusing Aspects:** 1. The tab-close error message that looks like a failure but actually succeeded. 2. Why attach --extension fails when a DEFAULT session exists (even if Stale). 3. Why old tabs disappear after re-attach. 4. The mismatch between browser4-cli (documentation) and ./b4w.ps1 (actual invocation).

**Most Valuable Improvements:** 1. Fix PowerShell flag binding so -i and -v work via ./b4w.ps1. 2. Auto-replace stale DEFAULT sessions on new attach/open. 3. Enumerate existing browser tabs on extension re-attach. 4. Clean up the tab-close error UX. 5. Add a session switch/default command.

**Usability Rating:** 5/10

---

## How to Reproduce

### Common Setup

1. Clone the repository and `cd` to the repo root.
2. The CLI is invoked via `./b4w.ps1` which auto-builds from source when needed.
3. The backend server starts automatically in dev mode.
4. All commands from repo root: `./b4w.ps1 <command>`

### Per-Issue Reproduction Steps

#### Issue 1: PowerShell parameter binder intercepts -i and -v flags when using ./b4w.ps1

cd repo-root && ./b4w.ps1 snapshot -i or ./b4w.ps1 snapshot -v 0

#### Issue 2: Stale DEFAULT session blocks unnamed attach/open operations

1. attach --extension (creates DEFAULT session)
2. Navigate to chrome://version/ (session becomes Stale)
3. attach --extension (expecting new session)
4. Also: open (expecting new regular Browser4 session)

#### Issue 3: Old browser tabs not visible after extension re-attach

1. attach --extension → navigate to several pages, create multiple tabs
2. Navigate to chrome://version/ (session becomes stale)
3. attach --extension (re-attach)
4. tab-list — only the auto-created blank tab from re-attach is visible

#### Issue 4: Confusing tab-close error message on extension sessions

./b4w.ps1 -s ext2 tab-close 1 (on an extension-attached session)

#### Issue 5: No dedicated session-switch command — DEFAULT session cannot be changed explicitly

After creating multiple sessions, try to change which one is DEFAULT without closing/re-opening.

#### Issue 6: Session ID equals session name when using -s <name> open (should be UUID)

./b4w.ps1 -s reg2 open then ./b4w.ps1 list

#### Issue 7: httpbin.org/links tab silently disappears during extension session operations

1. Create an httpbin.org/links/10 tab via extension session
2. Perform snapshot or tab operations
3. Tab disappears from tab-list without explicit close

#### Issue 8: Post-goto tip references -v 0 flag unusable on PowerShell

Run ./b4w.ps1 goto <url> — the tip 'Run `snapshot -v 0` to see interactive element refs' always appears.

#### Issue 9: Documentation uses browser4-cli command name but evaluation uses ./b4w.ps1 — naming confusion

Read SKILL.md — all examples use browser4-cli. The task instructions require $(./b4w.ps1).

