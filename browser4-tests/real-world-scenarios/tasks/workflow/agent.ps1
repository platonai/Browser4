#!/usr/bin/env pwsh
<#
.SYNOPSIS
Agent workflow: exercise the full agent command lifecycle and verify correctness.

.DESCRIPTION
Runs the exact sequence:
  agent list → agent run → agent list → agent status → agent list →
  agent result → agent list

Uses an AI agent (Claude/Kimi) to check the result of each step and report
any issues found against browser4-cli usability and reliability.

The task asks the agent to compute the 100th prime number (541). If an LLM
API key is configured, the agent MUST find "541" in the result output.

.NOTES
Run from the repo root:
  pwsh ./browser4-tests/real-world-scenarios/tasks/workflow/agent.ps1

In production mode:
  pwsh ./browser4-tests/real-world-scenarios/tasks/workflow/agent.ps1 -Production
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
# Task-specific prompt
# ===============================================================================

$taskPrompt = @"

Execute the following `agent` command sequence **in order**. After each command,
inspect its output and verify correctness before proceeding to the next step.
Report any issues (unexpected output, missing data, confusing messages, errors)
for each step individually.

## Command Sequence

### Step 1 — Initial `agent list`

Run:

    $cliInvocation agent list

Verify:
- The command exits with code 0.
- If no prior tasks exist, the output says "No tracked async tasks."
- If prior tasks exist, they are listed with standard lifecycle labels
  (`queued`, `processing`, `completed`, `failed (<code>)`) — never `"done"`.

### Step 2 — Submit an agent task

Run:

    $cliInvocation agent run "给出第100个素数"

Verify:
- The command exits with code 0.
- The output contains "Task submitted:" followed by a task ID.
- The output contains a hint like "Use 'browser4-cli agent status <id>' to check progress".
- Note the task ID — you will use it in all subsequent steps.

### Step 3 — `agent list` after submission

Run:

    $cliInvocation agent list

Verify:
- The task ID from Step 2 appears in the list.
- The STATUS column shows a standard lifecycle label (`queued`, `processing`,
  `completed`, or `failed (<code>)`).
- The label is NOT the deprecated `"done"` string.

### Step 4 — `agent status`

Run (replace `<task-id>` with the actual task ID from Step 2):

    $cliInvocation agent status <task-id>

Verify:
- The command exits with code 0.
- The output is valid JSON.
- `"statusCode"` appears as an integer (e.g. `200`, `102`, `417`), not a string.
- `"processState"` or `"status"` is present.
- `"id"` matches the task ID from Step 2.

### Step 5 — `agent list` after status

Run:

    $cliInvocation agent list

Verify:
- The task ID from Step 2 still appears in the list.
- The STATUS column is consistent with what `agent status` reported.
- Tasks are NOT unexpectedly removed between `agent list` calls (terminal
  tasks should persist, not auto-prune).

### Step 6 — `agent result` (THE CRITICAL CHECK)

**Wait up to 120 seconds** for the task to complete. If the task is still
running after `agent status` reports `"isDone": false`, poll every 3-5 seconds
until `"isDone": true` before running `agent result`.

Run (replace `<task-id>` with the actual task ID):

    $cliInvocation agent result <task-id>

Verify:
- The command exits with code 0.
- The output is NOT the literal string `null` and is NOT empty.
- If an LLM API key is configured and the task completed successfully:
  - **The output MUST contain "541"** (the 100th prime number).
  - The output should contain a `"summary"` field or a text description
    mentioning prime numbers.
- If no LLM API key is configured (task failed):
  - The output should NOT be `null` — it should contain a status message
    or failure reason.
  - The command should still exit with code 0.

### Step 7 — Final `agent list`

Run:

    $cliInvocation agent list

Verify:
- The task ID from Step 2 still appears.
- The STATUS column reflects the final state: `completed` (if the task
  succeeded) or `failed (<code>)` (if no LLM key or other error).
- The label is NEVER `"done"`.
- No tasks were unexpectedly lost across the full lifecycle.

## Success Criteria

1. Every command exits with code 0 (no unexpected failures).
2. `agent list` correctly tracks the task through its entire lifecycle:
   `queued` → `processing` → `completed` or `failed (<code>)`.
3. `agent status` returns valid JSON with integer `statusCode`.
4. **`agent result` returns non-null content.** If an LLM is configured,
   the result **must contain the number 541** (the 100th prime).
5. No deprecated `"done"` status label appears anywhere.

## Notes

- If the LLM API key is not configured, the agent task will fail with a
  status like `failed (417)`. This is expected behavior — verify that the
  failure is handled gracefully (non-null result, clear status, exit 0 on
  `agent result`).
- Record any confusing output, misleading messages, or discoverability
  issues as you encounter them.
"@

# -- Build the full prompt and invoke the agent ----------------------------------
$prompt = $generalPrompt + $taskPrompt

$invokeParams = @{
    Prompt       = $prompt
    ScenarioName = 'agent-workflow'
}
if ($Silent) {
    $invokeParams['Silent'] = $true
}
if ($TimeoutMinutes -gt 0) {
    $invokeParams['TimeoutSeconds'] = $TimeoutMinutes * 60
}

Invoke-Agent @invokeParams
