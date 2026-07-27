#!/usr/bin/env pwsh
<#
.SYNOPSIS
Attach workflow: exercise the full open/attach/goto command lifecycle,
including multi-tab operations, and verify correctness.

.DESCRIPTION
Runs the exact sequence from the attach regression suite, verifying that
arbitrary interleaving of open, attach, goto, tab, and list commands works
correctly.  Covers:

  - Extension attach lifecycle (connect -> navigate -> disconnect -> reconnect)
  - Multi-tab operations through attached sessions (tab-new, tab-list,
    tab-select, tab-close) — including tab persistence across re-attach
  - Session list consistency (connection type, status, timestamps)
  - Fallthrough prevention (dead extension session must NOT silently switch
    to a Browser4-launched Chrome)
  - Cross-session tab isolation (regular vs extension tab scoping)
  - Mixed-session workflows (extension + regular sessions side by side)

.NOTES
Run from the repo root:
  pwsh ./browser4-tests/real-world-scenarios/tasks/workflow/attach.ps1

In production mode:
  pwsh ./browser4-tests/real-world-scenarios/tasks/workflow/attach.ps1 -Production

This test requires the Chrome extension to be installed and a Chrome browser
available for attach --extension.  If the extension is not available, the
agent should record the limitation and skip extension-specific steps.
#>

[CmdletBinding()]
param(
    [switch] $Silent,

    # Run in production mode (browser4-cli instead of ./b4w.ps1).
    [switch] $Production,

    # Maximum minutes to wait for the agent to complete.
    [int] $TimeoutMinutes = 0
)

$ErrorActionPreference = 'Stop'

# -- Set mode before loading common.ps1 ------------------------------------------
if ($Production -and -not $browser4cliMode -and -not $env:BROWSER4CLI_MODE) {
    $browser4cliMode = 'production'
}

# -- Resolve the scripts directory -----------------------------------------------
$ScenariosRoot = [System.IO.Path]::GetFullPath(
    (Join-Path $PSScriptRoot '..' '..')
)
$ScriptsDir = Join-Path $ScenariosRoot 'scripts'

if (-not (Test-Path -LiteralPath $ScriptsDir -PathType Container)) {
    Write-Host "ERROR: Cannot find scripts directory at: $ScriptsDir" -ForegroundColor Red
    Write-Host "  Expected: browser4-tests/real-world-scenarios/scripts/" -ForegroundColor DarkGray
    exit 1
}

# -- Dot-source the shared helpers -----------------------------------------------
. "$ScriptsDir/common.ps1"

# -- Pre-flight checks (catches CLI/backend issues in seconds) -------------------
$preflightOk = Test-WorkflowPreflight
if (-not $preflightOk) {
    Write-Host 'WARNING: Pre-flight checks failed. Agent may encounter errors.' -ForegroundColor Yellow
}
Write-WorkflowBanner -WorkflowName 'Attach + Multi-Tab Lifecycle' -StepCount 26 -EstimatedDuration '10–30 minutes'

# ===============================================================================
# Task-specific prompt (built from single-quoted fragments to avoid PowerShell
# escape-character collisions with Markdown backtick-quoted inline code).
# ===============================================================================

# Build the CLI reference string once so it is consistent with $generalPrompt.
$cliRef = $cliInvocation

# Single-quoted fragments avoid backtick-interpretation issues.
$taskBody = @'

## Progress Reporting (MANDATORY)

Report progress at EVERY step so the user can follow along in real time.
The test harness shows the last 10 lines of your output every 30–120 seconds.

**BEFORE each step** — print exactly (with angle brackets and step numbers):
  >>> STEP <N>/25: <brief description of what this step does>

**AFTER each step** — print exactly:
  <<< STEP <N>: PASS — <one-line summary of what was verified>
  or
  <<< STEP <N>: FAIL — <one-line summary of what went wrong>

**CRITICAL FAILURE** — if a Critical-severity issue makes remaining steps
pointless, print:
  !!! ABORT at step <N>: <reason> !!!
Then skip all remaining steps and go directly to Deliverables.

---

Execute the following attach/open/goto command sequence **in order**. After each
command, inspect its output and verify correctness before proceeding to the next
step.  Report any issues (unexpected output, missing data, confusing messages,
errors, wrong connection type, wrong session state) for each step individually.

## Pre-requisites

Before starting the sequence, ensure:

1. The Chrome browser is installed and the Browser4 Chrome Extension is set up.
2. Run `__CLI__ kill-all` to start from a clean slate.

## Command Sequence

### Step 0 — Smoke Test (quick session check)

>>> STEP 0/26: Smoke test — list

Before diving into the full lifecycle, verify the session subsystem responds:

    __CLI__ kill-all
    __CLI__ list

Verify:
- Both commands exit with code 0.
- `list` produces a table (even if empty — "No sessions found" is acceptable).
- NO crash, stack trace, or connection error.

If this step fails, session management is fundamentally broken — record a
**Critical** issue and skip remaining steps (go to Deliverables).

### Step 1 -- Extension attach

>>> STEP 1/25: Extension attach

Run:

    __CLI__ attach --extension

Verify:
- The command exits with code 0.
- The output contains "Extension session created:" followed by a session ID.
- The output contains "Relay endpoint: ws://127.0.0.1:8182/ws/extension/<session-id>".
- The output contains "Extension connected and healthy!" within a reasonable time
  (typically 1-5 s; up to 15 s is acceptable).
- The output contains "Session ready: <session-id>".

Note the session ID.  You will refer to it in subsequent steps.

### Step 2 -- List after attach

Run:

    __CLI__ list

Verify:
- The command exits with code 0.
- The session from Step 1 appears.
- The STATUS column shows "Active" (or equivalent).
- The CONNECTION column shows "Extension" (optionally with a browser channel
  hint like "Extension (chrome)").

### Step 3 -- Navigate via extension session

Run:

    __CLI__ goto https://example.com/1

Verify:
- The command exits with code 0.
- "Using existing session DEFAULT" appears (or "Session already open").
- The page URL is reported as `https://example.com/1`.
- The page title is "Example Domain".
- A snapshot is generated (indicated by a `[Snapshot](...)` line or equivalent).

### Step 4 -- List after navigation

Run:

    __CLI__ list

Verify:
- The command exits with code 0.
- The extension session still appears as "Active".
- The CONNECTION column still shows "Extension".
- The LAST ACCESS timestamp has updated (it should be more recent than in Step 2).

### Step 4b -- Create a second tab (extension session)

Run:

    __CLI__ tab-new https://httpbin.org/get

Verify:
- The command exits with code 0.
- A GUID is returned for the new tab.
- "Switched to tab" appears in the output (auto-selection to the new tab).
- The URL `https://httpbin.org/get` appears in the output.

### Step 4c -- List tabs after creating second tab

Run:

    __CLI__ tab-list

Verify:
- The command exits with code 0.
- Two tabs are listed (indices 0 and 1).
- Tab 0 URL: `https://example.com/1`, Tab 1 URL: `https://httpbin.org/get`.
- Both tabs have non-empty GUIDs (not just "-").
- Run `__CLI__ list` — the CONNECTION column still shows "Extension" (tab
  operations must NOT silently change the connection type).

### Step 4d -- Create a third tab

Run:

    __CLI__ tab-new https://httpbin.org/links/10

Verify:
- The command exits with code 0.
- A third tab is created with a new GUID.
- Run `__CLI__ tab-list` — exactly three tabs appear (indices 0, 1, 2).
- All GUIDs are distinct and non-empty.
- The extension session is still "Active" in `list`.

### Step 4e -- Switch tabs by index and verify content

Run:

    __CLI__ tab-select 0

Verify:
- The command exits with code 0.

Then run:

    __CLI__ goto https://example.com/1

Verify:
- The goto navigates to (or confirms "already at") `https://example.com/1`.

Then run:

    __CLI__ tab-select 1

Verify:
- The command exits with code 0.
- Run `__CLI__ snapshot -i` — the snapshot references `httpbin.org/get`,
  proving the tab switch actually changed the active page.

### Step 4f -- Switch tab by GUID

Run:

    __CLI__ tab-list --json

From the JSON output, extract the GUID of the httpbin.org/links/10 tab.
Then:

    __CLI__ tab-select --guid <the-guid>

Verify:
- The command exits with code 0.
- GUID-based selection works through an extension session.
- Run `__CLI__ snapshot -i` — the page is `httpbin.org/links/10`.

### Step 4g -- Close a tab by index (extension session)

Run:

    __CLI__ tab-close 1

Verify:
- The command exits with code 0.
- Run `__CLI__ tab-list` — two tabs remain (example.com/1 and
  httpbin.org/links/10).  The httpbin.org/get tab (index 1) is gone.
- Indices may be re-indexed (0, 1) or preserved with a gap.  Note which
  behavior occurs and whether it is consistent with the regular session
  behavior observed in other workflows.
- Run `__CLI__ list` — the session is still "Active" with CONNECTION
  "Extension".  Closing a tab must NOT damage the session.

### Step 4h -- Final tab switch before chrome:// test

Run:

    __CLI__ tab-select 0
    __CLI__ goto https://example.com/1

Verify:
- The active tab is back on `https://example.com/1`.
- The extension session is healthy (multi-tab operations did not degrade it).
- Run `__CLI__ tab-list` — the tab count and URLs are consistent.

### Step 5 -- Navigate to chrome://version (internal page)

>>> STEP 5/25: Navigate to chrome://version (tests debugger detach)

Run:

    __CLI__ goto chrome://version/

Verify:
- The command exits with code 0 (or fails with a clear error — see notes below).
- If successful: the page navigates to `chrome://version/`.
- If successful: the page title may be "Chrome", "版本", "Version", or
  **empty**.  chrome:// pages do not always expose a standard `document.title`
  via the accessibility tree; an empty title is expected and not a bug.

**Important — Extension debugger detach:** Chrome's `debugger` API does not
allow debugging `chrome://` internal pages.  When the extension-attached
session navigates to `chrome://version/`, Chrome auto-detaches the debugger
from the tab.  The Browser4 backend receives a `chrome.debugger.onDetach`
event, cancels all pending CDP requests, and marks the session as unhealthy.

This means:
- The `goto chrome://version/` command may report a navigation error after
  the page actually loads (the post-navigation CDP checks fail because the
  debugger is gone).  This is expected.
- The extension session is now **Stale**.  Any subsequent `goto` through this
  session will fail with: "Attached session ... is no longer healthy. Re-run
  `attach --extension` to reconnect."
- **This is not a regression.**  It is a Chrome platform limitation.

### Step 6 -- Verify no user-data-dir in snapshot

Run:

    __CLI__ snapshot grep "user-data-dir"

Verify:
- The command exits with code 0 (or fails because the session is stale — if
  so, note it and skip to Step 6b).
- **The output MUST be empty or contain no matches.**  If `user-data-dir`
  appears, the navigation went to a Browser4-launched Chrome instead of
  the extension-attached Chrome.  This is a **Critical** bug — the attached
  session silently fell through to a different browser.

If `user-data-dir` IS present, record a Critical issue immediately and note
the full snapshot line containing it.

### Step 6b -- Re-attach extension (recover from chrome:// detach)

Because the chrome:// navigation detached the debugger, the extension
session is now stale.  Re-attach to restore it:

Run:

    __CLI__ attach --extension

Verify:
- The command exits with code 0.
- A NEW session ID is created (different from the first attach).
- "Extension connected and healthy!" appears within 15 s.

Record the new session ID for the remaining steps.

### Step 7 -- Navigate via re-attached extension session

Run:

    __CLI__ goto https://example.com/2

Verify:
- The command exits with code 0.
- The page navigates to `https://example.com/2`.
- The DEFAULT session is the re-attached extension session from Step 6b.
- "Using existing session DEFAULT" (or the new session ID) appears.

### Step 8 -- List after second navigation

Run:

    __CLI__ list

Verify:
- The command exits with code 0.
- The re-attached extension session is "Active".
- CONNECTION shows "Extension".
- LAST ACCESS has updated since Step 2.
- The old extension session from Step 1 (if still listed) shows "Stale" or
  is absent.

### Step 8b -- List tabs after re-attach (tab discovery)

After re-attaching the extension, run:

    __CLI__ tab-list

Verify:
- The command exits with code 0.
- The re-attached session can enumerate the browser's tabs.
- The current active tab (example.com/2 from Step 7) appears.
- Tabs that existed before the chrome:// detach (example.com/1,
  httpbin.org/links/10 from Steps 4b–4h) may or may not still appear.
  The re-attach creates a new blank tab via `newTab=true`; old tabs are
  still open in the browser but may not be tracked by the new session.
  Both behaviors are acceptable — **record which behavior occurs** and
  whether it is adequately documented.  If old tabs are silently lost,
  note this as a UX issue.
- Run `__CLI__ list` — the CONNECTION column shows "Extension" (tab
  operations after re-attach must not change the session type).

### Step 8c -- Create tabs via re-attached session

Run:

    __CLI__ tab-new https://httpbin.org/get
    __CLI__ tab-new https://httpbin.org/links/10

Verify:
- Both commands exit with code 0.
- Run `__CLI__ tab-list` — all created tabs are present.
- Run `__CLI__ list` — CONNECTION is still "Extension".

### Step 8d -- Close a tab and switch (re-attached session)

Run:

    __CLI__ tab-close 1
    __CLI__ tab-select 0
    __CLI__ goto https://example.com/2

Verify:
- All commands exit with code 0.
- Tab 1 closed successfully.
- Switching to tab 0 and navigating to example.com/2 works.
- The extension session remains healthy.

### Step 9 -- Open a regular Browser4 session

Run:

    __CLI__ open

Verify:
- The command exits with code 0.
- "Session opened:" followed by a session ID is printed.
- This session ID is DIFFERENT from the extension session ID from Step 1.

### Step 10 -- Navigate via regular session

Run:

    __CLI__ goto https://example.com/3

Verify:
- The command exits with code 0.
- The navigation targets `https://example.com/3`.
- The DEFAULT session is used (the regular session from Step 9, NOT the
  extension session).

### Step 11 -- List with both sessions

Run:

    __CLI__ list

Verify:
- The command exits with code 0.
- At least TWO sessions appear:
  - One with CONNECTION "Extension" (the session from Step 1).
  - One with CONNECTION "Browser4" (the session from Step 9).
- Both sessions show "Active" status.
- The DEFAULT marker is on the most recently opened session.

### Step 11b -- Verify cross-session tab isolation

With the DEFAULT session still pointing to the **regular** Browser4 session
(from Step 11), create a tab in the regular session:

    __CLI__ tab-new https://httpbin.org/links/10
    __CLI__ tab-list

Verify:
- The new tab is created in the **regular** session.
- Run `__CLI__ list` — note the extension session's ID.

Now switch to the extension session using the `-s` flag:

    __CLI__ -s <extension-session-id> tab-list

Verify:
- The extension session's tab list does NOT include the httpbin.org/links/10
  tab created in the regular session.  **Tab isolation is critical:** tab
  operations in one session must NEVER leak into another session.
- Only the extension session's own tabs appear.

Switch the DEFAULT back to the regular session for subsequent steps
(use the regular session's ID or name with `-s`).  If there is no
single-command way to change the DEFAULT session, record this as a
**Discoverability** or **UX** issue — users need to switch sessions
frequently in mixed-session workflows.

### Step 12 -- Attach extension again (re-attach)

Run:

    __CLI__ attach --extension

Verify:
- The command exits with code 0.
- A NEW session ID is created (different from the first attach).
- "Extension connected and healthy!" appears.
- The old extension session from Step 1 does NOT interfere.

### Step 13 -- List after re-attach

Run:

    __CLI__ list

Verify:
- The command exits with code 0.
- The NEW extension session appears with CONNECTION "Extension".
- If the old extension session is still listed, it should NOT be "Active"
  (it may show as "stopped" or be absent -- either is acceptable).

### Step 14 -- Navigate via re-attached extension session

Run:

    __CLI__ goto https://example.com/4

Verify:
- The command exits with code 0.
- The DEFAULT session is the re-attached extension session.
- The page navigates to `https://example.com/4`.

### Step 15 -- Open another regular session after extension

Run:

    __CLI__ open

Verify:
- The command exits with code 0.
- A NEW regular Browser4 session is created.
- This does NOT interfere with the extension session.

### Step 16 -- Final list

Run:

    __CLI__ list

Verify:
- The command exits with code 0.
- All sessions are listed with correct CONNECTION types.
- Extension sessions show "Extension" connection type.
- Regular sessions show "Browser4" connection type.

### Step 17 -- Navigate via regular session after extension

Run:

    __CLI__ goto https://example.com/5

Verify:
- The command exits with code 0.
- The DEFAULT session is the regular session from Step 15.
- The page navigates to `https://example.com/5`.
- The extension session is NOT affected.

## Success Criteria

1. Every command exits with code 0 (no unexpected failures).
2. Extension-attached sessions show "Extension" in the CONNECTION column.
3. Regular Browser4 sessions show "Browser4" in the CONNECTION column.
4. Navigation through an extension session reaches the correct URL and renders
   a valid page snapshot.
5. **Step 6 is the critical regression check:** `snapshot grep "user-data-dir"`
   on a chrome://version page must yield NO matches.  If it matches, the
   extension session silently fell through to a Browser4-launched Chrome --
   a **Critical** product defect.
6. Re-attaching via `attach --extension` creates a new session without
   interference from the old one.
7. Regular `open` commands create fresh Browser4 sessions without disturbing
   extension sessions.
8. Arbitrary interleaving of `open`, `attach --extension`, `goto`,
   `tab-new`, `tab-select`, and `tab-close` works correctly regardless
   of order.
9. **Multi-Tab (Extension):** `tab-new`, `tab-list`, `tab-select` (by
   index and GUID), and `tab-close` work correctly through
   extension-attached sessions, matching the behavior of regular sessions.
10. **Tab Persistence Across Re-attach:** After a stale-session re-attach
    via `attach --extension`, the new extension session can discover and
    manage browser tabs.  The behavior should be documented — if old tabs
    are not visible after re-attach, that should be clearly explained.
11. **Cross-Session Tab Isolation:** Tabs created in one session must not
    appear in another session's `tab-list`.  Regular session tabs stay
    scoped to the regular session; extension session tabs stay scoped to
    the extension session.
12. **Tab Operations Preserve Session Type:** Creating, switching, or
    closing tabs through an extension session must not silently change
    the CONNECTION type from "Extension" to "Browser4".

## Expected Behavior After the Fix

With the `resolveHealthySession` extension-guard fix applied (July 2026):

- When an extension WebSocket disconnects, the session is marked inactive
  (stopped) rather than silently recreated as a Browser4-CDP session.
- The CLI `get_or_create_navigation_session` detects the unhealthy attached
  session and returns a clear error: "Attached session ... is no longer healthy.
  Re-run `attach --extension` to reconnect."
- Re-attaching creates a new session with a new UUID.
- The `list` command correctly reflects the connection type from local state.

### chrome:// internal page behavior

Chrome's `debugger` API does not permit debugging `chrome://` privileged pages.
When the extension-attached session navigates to a chrome:// URL:

1. Chrome auto-detaches the debugger from the tab and fires
   `chrome.debugger.onDetach`.
2. The Browser4 backend cancels all pending CDP requests immediately (the
   2026-07-25 fix in `ExtensionChromeService.kt`), so the `goto` command fails
   in ~10-15 s instead of the previous 90-120 s timeout cascade.
3. The extension session becomes **Stale** — this is a Chrome platform
   limitation, not a Browser4 bug.
4. To continue, re-attach: `attach --extension`.  This creates a fresh
   extension session that can navigate to regular (non-chrome://) pages.

This is expected behavior, not a regression.  The workflow accounts for it
by inserting a re-attach step (Step 6b) after chrome:// navigation.

## Notes

- If the Chrome extension is not installed, skip Steps 1-8, 4b-4h, 8b-8d,
  and 11b.  Record this as a **Medium** Limitation issue -- the
  extension-dependent steps could not be verified.
- If `snapshot grep` is not available, use `snapshot -v 0` and visually
  inspect the output for `user-data-dir`.
- Record any confusing output, misleading messages, discoverability issues,
  wrong connection types, or wrong session states as you encounter them.
- Pay special attention to the CONNECTION column in `list` -- it must
  accurately reflect the actual browser connection type.
- **Multi-tab expectations:** Each `attach --extension` creates a new
  blank tab (via the `newTab=true` parameter in the extension connect
  URL).  Existing browser tabs opened by previous sessions should still
  be discoverable via `tab-list` in the new session.  If they are not,
  record this as a **High Product** issue -- users expect to see all
  their open tabs after reconnecting.
- **Tab index semantics after re-attach:** Pay attention to whether the
  tab indices change after re-attach.  The new blank tab created by
  re-attach may shift existing tab indices.  Consistency is key.
- **Session switching:** There is no dedicated `session` command in the
  CLI.  Sessions are switched by passing `-s <name>` on each command or
  by re-running `attach --extension` / `open`.  The `-s` global flag is
  the canonical way to target a specific session.  If discovering this
  requires reading the full SKILL.md, record it as a **Discoverability**
  issue -- users should be able to discover session switching from the
  `help` output of relevant commands.

'@

# Substitute the CLI invocation reference into the single-quoted prompt body.
$taskPrompt = $taskBody.Replace('__CLI__', $cliRef)

# -- Build the full prompt and invoke the agent ----------------------------------
$prompt = $generalPrompt + $taskPrompt

$invokeParams = @{
    Prompt       = $prompt
    ScenarioName = 'attach-workflow'
}
if ($Silent) {
    $invokeParams['Silent'] = $true
}
if ($TimeoutMinutes -gt 0) {
    $invokeParams['TimeoutSeconds'] = $TimeoutMinutes * 60
}

Invoke-Agent @invokeParams
