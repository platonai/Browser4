#!/usr/bin/env pwsh
<#
.SYNOPSIS
Tab workflow: exercise the full tab command lifecycle across all session types
and verify correctness.

.DESCRIPTION
Exercises the tab command lifecycle (tab-list, tab-new, tab-select, tab-close)
across every kind of Browser4 session:

  Part A — Regular Browser4 session (open / goto)
  Part B — Extension-attached session (attach --extension)
  Part C — Mixed sessions (both session types active simultaneously)

Uses an AI agent (Claude/Kimi) to check the result of each step and report
any issues found against browser4-cli usability and reliability.

.NOTES
Run from the repo root:
  pwsh ./browser4-tests/real-world-scenarios/tasks/workflow/tab.ps1

In production mode:
  pwsh ./browser4-tests/real-world-scenarios/tasks/workflow/tab.ps1 -Production

This test requires the Chrome extension to be installed for Parts B and C.
If the extension is not available, the agent should record the limitation
and skip extension-dependent steps.
#>

[CmdletBinding()]
param(
    [switch] $Silent,

    # Run in production mode (browser4-cli instead of ./b4w.ps1).
    [switch] $Production,

    # Maximum minutes to wait for the AI agent to complete.
    [int] $TimeoutMinutes = 0
)

$ErrorActionPreference = 'Stop'

# -- Set mode before loading common.ps1 ------------------------------------------
if ($Production -and -not $browser4cliMode -and -not $env:BROWSER4CLI_MODE) {
    $browser4cliMode = 'production'
}

# -- Resolve the scripts directory -----------------------------------------------
# $PSScriptRoot = .../real-world-scenarios/tasks/workflow
# Go up two levels to real-world-scenarios/, then into scripts/
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

Execute the following `tab` command sequence **in order** across all Browser4
session types. After each command, inspect its output and verify correctness
before proceeding to the next step.  Report any issues (unexpected output,
missing data, confusing messages, errors, wrong tab indices, wrong URLs,
missing GUIDs, wrong connection-type behavior) for each step individually.

## Session Types Under Test

Browser4 supports three ways to drive a browser, and tab commands must work
correctly in each:

| Session Type  | How To Create           | Browser Source              |
|---------------|--------------------------|-----------------------------|
| Regular       | `open`                   | Browser4-launched Chrome    |
| Extension     | `attach --extension`     | User's Chrome via Extension |
| Mixed         | Both active at once      | Both browsers side-by-side  |

The tab commands (`tab-list`, `tab-new`, `tab-select`, `tab-close`) must
operate on the **current default session's** tabs.  When both a regular and
an extension session exist, `tab-list` should show only the tabs belonging
to whichever session is currently DEFAULT.

## Pre-requisites

Before starting the sequence, ensure:

1. The Chrome browser is installed.
2. (For Parts B and C) The Browser4 Chrome Extension is set up.
3. Start each part from a clean slate: run `__CLI__ kill-all`.

---

# Part A — Regular Browser4 Session

These steps exercise the full tab lifecycle through a standard `open` session
(Browser4-launched Chrome).  This is the most common path and must be rock-solid.

## A.1 — Setup

Run:

    __CLI__ kill-all
    __CLI__ open
    __CLI__ goto https://example.com

Verify:
- All commands exit with code 0.
- The session opens and navigates to `https://example.com`.
- The page title is "Example Domain".

## A.2 — Initial tab-list (human-readable)

Run:

    __CLI__ tab-list

Verify:
- The command exits with code 0.
- The output is a table with columns: **Index**, **GUID**, **Title**, **URL**.
- At least one tab is listed.
- Tab 0 shows `https://example.com` in the URL column.
- Every tab has a non-empty GUID (not just "-").
- Record the GUID of tab 0 for later GUID-based operations.

## A.3 — tab-list --json

Run:

    __CLI__ tab-list --json

Verify:
- The command exits with code 0.
- The output is a valid JSON array.
- Each element has keys: `index` (integer), `guid` (string), `url` (string),
  `title` (string).
- The first element's `url` contains `example.com`.

## A.4 — Create a tab with a URL

Run:

    __CLI__ tab-new https://httpbin.org/links/10

Verify:
- The command exits with code 0.
- The output contains a GUID (the new tab's identifier).
- The output contains "Switched to tab" — auto-selection to the new tab.
- The URL `https://httpbin.org/links/10` appears in the output.
- Note the new tab's GUID.

## A.5 — List after creation

Run:

    __CLI__ tab-list

Verify:
- Two tabs are listed (indices 0 and 1).
- Tab 0 URL: `https://example.com`.
- Tab 1 URL: `https://httpbin.org/links/10`.
- Tab 1's GUID matches the GUID from Step A.4.

## A.6 — Create a tab without a URL

Run:

    __CLI__ tab-new

Verify:
- The command exits with code 0.
- A new tab was created even though no URL was provided.
- The output contains a GUID and "Switched to tab".
- Run `__CLI__ tab-list` — three tabs total; the new tab's URL is
  `about:blank` (or equivalent default page).

## A.7 — Switch tabs by index

Run:

    __CLI__ tab-select 0
    __CLI__ goto https://example.com

Verify:
- `tab-select 0` exits with code 0.
- The goto confirms we are on example.com (may say "already at" — acceptable).

Run:

    __CLI__ tab-select 1

Verify:
- `tab-select 1` exits with code 0.
- Run `__CLI__ snapshot -i` and verify the snapshot references
  `httpbin.org` (proving the switch actually changed the active tab).

## A.8 — Switch to tab 2 and navigate

Run:

    __CLI__ tab-select 2
    __CLI__ goto https://httpbin.org/get

Verify:
- Both commands exit with code 0.
- The page navigates to `https://httpbin.org/get`.
- Run `__CLI__ tab-list` — tab 2's URL is now `https://httpbin.org/get`
  (updated after navigation). Tab 0 and tab 1 URLs are unchanged.
- All GUIDs are stable (same as before switching).

## A.9 — Close tab by index

Run:

    __CLI__ tab-close 1

Verify:
- The command exits with code 0.
- Run `__CLI__ tab-list` — only two tabs remain.
- Tab 1 (httpbin/links/10) is gone.
- Indices may be re-indexed (0, 1) or preserved with gap (0, 2).  Either is
  acceptable — note which behavior occurs and whether it is consistent.

## A.10 — Close current tab (no args)

Run:

    __CLI__ tab-close

Verify:
- The command exits with code 0.
- Run `__CLI__ tab-list` — exactly one tab remains.

## A.11 — Close the last tab

Run:

    __CLI__ tab-close
    __CLI__ tab-list

Verify:
- Both commands exit with code 0.
- `tab-list` reports "No tabs found." (or empty JSON `[]` in `--json` mode).
- The command does NOT error — it gracefully reports zero tabs.

## A.12 — Create a tab after all were closed

Run:

    __CLI__ tab-new https://example.com

Verify:
- The command exits with code 0.
- A new tab is created successfully even though all previous tabs were closed.
- "Switched to tab" appears.
- Run `__CLI__ tab-list` — exactly one tab appears.

## A.13 — Close by GUID

Run:

    __CLI__ tab-new https://httpbin.org/get
    __CLI__ tab-list --json

From the JSON output, find the GUID of the httpbin.org/get tab. Then:

    __CLI__ tab-close --guid <the-guid>

Verify:
- `tab-close --guid` exits with code 0.
- Run `__CLI__ tab-list` — only the example.com tab remains.
## A.14 — Select by GUID

Run:

    __CLI__ tab-new https://httpbin.org/links/10
    __CLI__ tab-list --json

From the JSON output, find the GUID of the new httpbin tab. Then:

    __CLI__ tab-select --guid <the-guid>

Verify:
- `tab-select --guid` exits with code 0.
- Run `__CLI__ snapshot -i` — the page is `httpbin.org/links/10`,
  confirming GUID-based selection worked.

## A.15 — Error handling: invalid index

Run:

    __CLI__ tab-select 99

Verify:
- The command prints a clear error message (e.g., "Tab index '99' out of
  range" or "No tab at index 99").
- The command may exit non-zero — this is expected for invalid input.
- The CLI does NOT crash or hang.
- Run `__CLI__ tab-list` — the tab list is unchanged (no tabs lost).

## A.16 — Error handling: invalid GUID

Run:

    __CLI__ tab-close --guid "nonexistent-guid-12345"

Verify:
- The command prints a clear error message (e.g., "Tab not found").
- The tab list is unchanged.

## A.17 — Rapid operations consistency

Run the following in quick succession (no waits between commands):

    __CLI__ tab-new https://example.com
    __CLI__ tab-new https://httpbin.org/get
    __CLI__ tab-list

Verify:
- All commands exit with code 0.
- The tab list accurately shows all created tabs.
- No stale, duplicate, or missing entries.
- Indices are sequential (no gaps).

---

# Part B — Extension-Attached Session

These steps exercise the tab lifecycle through an extension-attached session.
The Chrome Extension drives the browser via `chrome.tabs` / `chrome.debugger`
APIs rather than direct CDP.  Tab commands must work identically regardless
of the underlying transport.

**If the Chrome extension is not installed, skip Part B and Part C.**  Record
this as a **Medium Limitation** issue — extension-dependent steps could not be
verified.

## B.1 — Setup

Run:

    __CLI__ kill-all
    __CLI__ attach --extension

Verify:
- The command exits with code 0.
- "Extension session created:" followed by a session ID appears.
- "Extension connected and healthy!" appears (within ~15 s).
- "Session ready: <session-id>" appears.
- Record the session ID.

## B.2 — Navigate to a page

Run:

    __CLI__ goto https://example.com

Verify:
- The command exits with code 0.
- The page navigates to `https://example.com`.
- The page title is "Example Domain".
- The session used is the extension session (DEFAULT).

## B.3 — tab-list via extension session

Run:

    __CLI__ tab-list

Verify:
- The command exits with code 0.
- At least one tab is listed.
- The output format is the same table (Index | GUID | Title | URL) as in
  the regular session — no format differences between session types.
- Tab 0 shows `https://example.com`.
- Each tab has a non-empty GUID.

## B.4 — Create tabs via extension session

Run:

    __CLI__ tab-new https://httpbin.org/links/10

Verify:
- The command exits with code 0.
- A GUID is returned and "Switched to tab" appears.
- Run `__CLI__ tab-list` — two tabs exist.
- **Critical:** Run `__CLI__ list` and verify the session's CONNECTION
  column still shows **"Extension"** (not "Browser4").  Creating tabs through
  an extension session must NOT silently change the connection type.

## B.5 — Switch tabs via extension session

Run:

    __CLI__ tab-select 0
    __CLI__ goto https://example.com

Verify:
- Both commands exit with code 0.
- The goto navigates (or confirms "already at") example.com.
- The extension session is still active (run `__CLI__ list` to verify
  CONNECTION is "Extension").

## B.6 — Close tabs via extension session

Run:

    __CLI__ tab-close 1
    __CLI__ tab-list

Verify:
- Both commands exit with code 0.
- Only one tab remains (tab 0, example.com).

Run:

    __CLI__ tab-close
    __CLI__ tab-list

Verify:
- `tab-close` (no args) exits with code 0.
- `tab-list` reports "No tabs found." — graceful empty state.

## B.7 — Re-create after all closed (extension)

Run:

    __CLI__ tab-new https://httpbin.org/get

Verify:
- The command exits with code 0.
- A new tab is created from an empty state via the extension session.
- Run `__CLI__ list` — CONNECTION is still "Extension".

## B.8 — GUID-based operations via extension

Run:

    __CLI__ tab-new https://httpbin.org/links/10
    __CLI__ tab-list --json

From the JSON output, extract the GUID of the httpbin/links/10 tab, then:

    __CLI__ tab-close --guid <the-guid>

Verify:
- `tab-close --guid` exits with code 0.
- Run `__CLI__ tab-list` — only the httpbin/get tab remains.

---

# Part C — Mixed Sessions (Regular + Extension)

These steps verify that tab commands correctly target the **current default
session** when multiple sessions of different types are active simultaneously.

## C.1 — Setup: both session types active

Run:

    __CLI__ kill-all
    __CLI__ attach --extension
    __CLI__ goto https://example.com

Verify the extension session is active (CONNECTION: Extension).

Now open a regular session alongside it:

    __CLI__ open
    __CLI__ goto https://httpbin.org/get

Verify:
- Both commands exit with code 0.
- A new regular session (CONNECTION: Browser4) is created.

## C.2 — List both sessions

Run:

    __CLI__ list

Verify:
- Two sessions appear.
- One has CONNECTION "Extension" (from `attach --extension`).
- One has CONNECTION "Browser4" (from `open`).
- The DEFAULT marker is on the most recently created session (the regular one).

## C.3 — Tab commands target the default (regular) session

Run:

    __CLI__ tab-list

Verify:
- Tabs shown belong to the **regular** (Browser4) session.
- The URL is `https://httpbin.org/get` (the regular session's page).
- The extension session's tabs (example.com) are NOT listed — tab commands
  are scoped to the DEFAULT session.

## C.4 — Create tabs in the regular session

Run:

    __CLI__ tab-new https://example.com
    __CLI__ tab-list

Verify:
- Two tabs now exist in the regular session.
- The regular session has: httpbin.org/get AND example.com.
- Run `__CLI__ list` — the extension session is still present and
  unaffected by the regular session's tab operations.

## C.5 — Switch default to the extension session

Use the `session` command (or `switch` / `use`) to change the default session
to the extension session.  First, note the extension session's ID from `list`,
then:

    __CLI__ session <extension-session-id>

Or, if `session` is not available, consult the help/skill documentation for
how to switch the default session.

Run:

    __CLI__ list

Verify:
- The DEFAULT marker has moved to the extension session.

## C.6 — Tab commands now target the extension session

Run:

    __CLI__ tab-list

Verify:
- The tab list now shows the **extension session's** tabs (example.com).
- The regular session's tabs (httpbin.org/get, example.com) are NOT listed.
- **This is the critical check:** tab commands must follow the DEFAULT session
  and NOT leak tabs from other sessions.

## C.7 — Create a tab in the extension session

Run:

    __CLI__ tab-new https://httpbin.org/links/10
    __CLI__ tab-list

Verify:
- The extension session now has two tabs (example.com + httpbin/links/10).
- Run `__CLI__ list` — the regular session is still present with its
  tabs intact.

## C.8 — Switch back to the regular session

Run:

    __CLI__ session <regular-session-id>

Verify:
- `list` shows DEFAULT back on the regular session.
- Run `__CLI__ tab-list` — the regular session's tabs reappear (the
  same tabs as before, unchanged).
- The extension session's new tab does NOT leak into this output.

## C.9 — Close tabs in each session independently

With the DEFAULT on the regular session:

    __CLI__ tab-close 1
    __CLI__ tab-list

Verify:
- One tab was closed in the **regular** session.
- Switch DEFAULT to the extension session and run `tab-list` — all extension
  tabs are still present (the regular session's close did not affect them).

Switch to the extension session and close a tab there:

    __CLI__ session <extension-session-id>
    __CLI__ tab-close
    __CLI__ tab-list

Verify:
- The extension session's tab count decreased.
- Switch back to the regular session — its tabs are unchanged.

## C.10 — Final cross-session consistency check

Run:

    __CLI__ kill-all
    __CLI__ open
    __CLI__ goto https://example.com
    __CLI__ tab-new https://httpbin.org/get
    __CLI__ tab-new https://httpbin.org/links/10
    __CLI__ tab-list --json

Verify:
- Exactly three tabs in the JSON array.
- All four keys present in every element.
- Indices: 0, 1, 2 — sequential, no gaps.
- All GUIDs: non-empty, distinct.
- The full lifecycle across all session types completes without errors.

---

## Success Criteria

### Correctness
1. Every `tab-list`, `tab-new`, `tab-select`, and `tab-close` command exits
   with code 0 under normal operation (across ALL session types).
2. `tab-new` always returns a GUID, auto-switches to the new tab, and works
   with or without a URL argument.
3. `tab-select` switches the active tab by both index and GUID.
4. `tab-close` closes tabs by index, by GUID, and by omitting args (current
   tab), across all session types.
5. After all tabs are closed, `tab-list` gracefully reports "No tabs found."
   (no error, no crash).
6. Creating a tab after all tabs were closed works correctly (no stuck state).

### Session-Type Independence
7. Tab commands target the **DEFAULT session's** tabs only — no cross-session
   tab leakage.
8. `tab-list` through an extension session shows extension tabs; through a
   regular session shows regular tabs; never a mix.
9. The CONNECTION type in `list` remains correct after tab operations:
   - Regular session stays "Browser4".
   - Extension session stays "Extension".
10. Closing a tab in one session does NOT affect tabs in another session.

### Output Quality
11. `tab-list` output format is identical across session types (table columns,
    JSON schema).
12. Human-readable and `--json` modes are consistent (same count, order, URLs,
    GUIDs).
13. GUIDs are stable — the same tab keeps the same GUID across multiple
    `tab-list` calls.
14. `tab-list` indices are sequential (0, 1, 2, ...) with no gaps.

### Error Handling
15. Invalid index (`tab-select 99`) produces a clear error without crashing.
16. Invalid GUID (`tab-close --guid "bogus"`) produces a clear error without
    corrupting the tab list.
17. `tab-close` when no tabs exist should either error clearly or succeed
    silently — NOT crash.

### Cross-Cutting
18. The full lifecycle works end-to-end: list → create → switch → navigate →
    close → empty → create after empty → GUID operations → error handling.
19. No tabs are lost, duplicated, or left in an inconsistent state.
20. No session type exhibits unique bugs — behavior is consistent regardless
    of whether the browser is launched by Browser4 or attached via Extension.

## Notes

- If the Chrome Extension is not installed, skip Parts B and C.  Record this
  as a **Medium Limitation** issue.
- Pay attention to index re-indexing after `tab-close`.  Observe whether
  indices are compacted (0, 1, 2 → 0, 1 after close) or left with gaps.
  Either is acceptable, but the behavior should be consistent.
- The `tab-select` and `tab-close` commands should clearly indicate which
  tab was selected or closed — a bare "OK" without context is a UX issue.
- Watch for unexpected side effects: does switching tabs reload pages?  Does
  closing tabs affect other tabs' state?  Does switching the default session
  change the active tab in either session?
- If the session-switching command (`session`, `switch`, `use`) is unavailable
  or poorly documented, record this as a **Discoverability** issue — users
  need to know how to change the default session for tab commands to work
  correctly in a multi-session environment.
- Record any confusing output, misleading messages, or inconsistent behavior
  as you encounter them.

'@

# Substitute the CLI invocation reference into the single-quoted prompt body.
$taskPrompt = $taskBody.Replace('__CLI__', $cliRef)

# -- Build the full prompt and invoke the agent ----------------------------------
$prompt = $generalPrompt + $taskPrompt

$invokeParams = @{
    Prompt       = $prompt
    ScenarioName = 'tab-workflow'
}
if ($Silent) {
    $invokeParams['Silent'] = $true
}
if ($TimeoutMinutes -gt 0) {
    $invokeParams['TimeoutSeconds'] = $TimeoutMinutes * 60
}

Invoke-Agent @invokeParams
