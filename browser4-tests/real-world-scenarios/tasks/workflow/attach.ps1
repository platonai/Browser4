#!/usr/bin/env pwsh
<#
.SYNOPSIS
Attach workflow: exercise the full open/attach/goto command lifecycle and verify correctness.

.DESCRIPTION
Runs the exact sequence from the attach regression suite, verifying that
arbitrary interleaving of open, attach, goto, and list commands works
correctly.  Covers:

  - Extension attach lifecycle (connect -> navigate -> disconnect -> reconnect)
  - Session list consistency (connection type, status, timestamps)
  - Fallthrough prevention (dead extension session must NOT silently switch
    to a Browser4-launched Chrome)
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

# ===============================================================================
# Task-specific prompt (built from single-quoted fragments to avoid PowerShell
# escape-character collisions with Markdown backtick-quoted inline code).
# ===============================================================================

# Build the CLI reference string once so it is consistent with $generalPrompt.
$cliRef = $cliInvocation

# Single-quoted fragments avoid backtick-interpretation issues.
$taskBody = @'

Execute the following attach/open/goto command sequence **in order**. After each
command, inspect its output and verify correctness before proceeding to the next
step.  Report any issues (unexpected output, missing data, confusing messages,
errors, wrong connection type, wrong session state) for each step individually.

## Pre-requisites

Before starting the sequence, ensure:

1. The Chrome browser is installed and the Browser4 Chrome Extension is set up.
2. Run `__CLI__ kill-all` to start from a clean slate.

## Command Sequence

### Step 1 -- Extension attach

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

### Step 5 -- Navigate to chrome://version (internal page)

Run:

    __CLI__ goto chrome://version/

Verify:
- The command exits with code 0.
- The page title mentions "Chrome" or "版本" or "Version".
- A snapshot is generated.

### Step 6 -- Verify no user-data-dir in snapshot

Run:

    __CLI__ snapshot grep "user-data-dir"

Verify:
- The command exits with code 0.
- **The output MUST be empty or contain no matches.**  If `user-data-dir`
  appears, the navigation went to a Browser4-launched Chrome instead of
  the extension-attached Chrome.  This is a **Critical** bug -- the attached
  session silently fell through to a different browser.

If `user-data-dir` IS present, record a Critical issue immediately and note
the full snapshot line containing it.

### Step 7 -- Navigate via extension session again

Run:

    __CLI__ goto https://example.com/2

Verify:
- The command exits with code 0.
- The page navigates to `https://example.com/2`.
- The session is still the extension-attached session (same session ID).

### Step 8 -- List after second navigation

Run:

    __CLI__ list

Verify:
- The extension session is still "Active".
- CONNECTION is still "Extension".
- LAST ACCESS has updated.

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
8. Arbitrary interleaving of `open`, `attach --extension`, and `goto` works
   correctly regardless of order.

## Expected Behavior After the Fix

With the `resolveHealthySession` extension-guard fix applied (July 2026):

- When an extension WebSocket disconnects, the session is marked inactive
  (stopped) rather than silently recreated as a Browser4-CDP session.
- The CLI `get_or_create_navigation_session` detects the unhealthy attached
  session and returns a clear error: "Attached session ... is no longer healthy.
  Re-run `attach --extension` to reconnect."
- Re-attaching creates a new session with a new UUID.
- The `list` command correctly reflects the connection type from local state.

## Notes

- If the Chrome extension is not installed, skip Steps 1-8 and 12-14.  Record
  this as a **Medium** Limitation issue -- the extension-dependent steps could
  not be verified.
- If `snapshot grep` is not available, use `snapshot -v 0` and visually inspect
  the output for `user-data-dir`.
- Record any confusing output, misleading messages, discoverability issues,
  wrong connection types, or wrong session states as you encounter them.
- Pay special attention to the CONNECTION column in `list` -- it must accurately
  reflect the actual browser connection type.

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
