#!/usr/bin/env pwsh
$ErrorActionPreference = 'Stop'

# Force UTF-8 encoding for CLI output
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$PSDefaultParameterValues['*:Encoding'] = 'utf8'

$cli = if ($env:BROWSER4_CLI_BIN) {
    { & $env:BROWSER4_CLI_BIN $args }
} else {
    { browser4-cli $args }
}

& $cli open

$task = @"
Visit https://www.amazon.com/dp/B08PP5MSVB
Summarize the product.
"@
$agentRunOutput = & $cli agent run $task 2>&1 | ForEach-Object { "$_" }
$agentRunText = ($agentRunOutput | Out-String).Trim()
$agentRunText

$taskIdMatch = [regex]::Match($agentRunText, 'Task submitted:\s*(\S+)')
$taskId = $taskIdMatch.Groups[1].Value
Write-Host "Waiting for agent task $taskId to finish..."

$done = $false
$success = $false
$lastStatusText = ''
$MaxPollAttempts = 60

# Extract the first complete JSON object from CLI output.
# The --json flag returns a JSON envelope: {"status":"ok"|"error", "output":{"raw":{...}}}
# The agent task state lives under output.raw:
#   output.raw.isDone       : bool  — true when the task has finished
#   output.raw.processState : str   — "in_progress" | "done" | "failed"
#   output.raw.status       : str   — "Processing" | "OK" | …
#   output.raw.statusCode   : int   — 102 (Processing), 200 (OK), etc.
function Get-JsonFromOutput {
    param([string]$Text)
    $jsonMatch = [regex]::Match($Text, '\{.*\}', [System.Text.RegularExpressions.RegexOptions]::Singleline)
    if (-not $jsonMatch.Success) { return $null }
    try {
        return $jsonMatch.Value | ConvertFrom-Json
    } catch {
        return $null
    }
}

for ($attempt = 1; $attempt -le $MaxPollAttempts; $attempt++) {
    $statusOutput = & $cli --json agent status $taskId 2>&1 | ForEach-Object { "$_" }
    $lastStatusText = ($statusOutput | Out-String).Trim()
    $lastStatusText


    $status = Get-JsonFromOutput $lastStatusText
    if (-not $status) {
        Write-Host "Status poll ${attempt}/${MaxPollAttempts}: no valid JSON in output, retrying..."
        sleep 3
        continue
    }

    # Handle top-level CLI error envelope
    if ($status.status -eq 'error') {
        $errMsg = if ($status.error.message) { $status.error.message } else { 'Unknown CLI error' }
        Write-Host "Status poll ${attempt}/${MaxPollAttempts}: CLI error - ${errMsg}"
        $done = $true
        $success = $false
        break
    }

    # Navigate to the agent task state: output.raw
    $raw = $status.output.raw
    if (-not $raw) {
        Write-Host "Status poll ${attempt}/${MaxPollAttempts}: no output.raw in response, retrying..."
        sleep 3
        continue
    }

    $processState = if ($raw.processState) { [string]$raw.processState } else { 'unknown' }
    $isDone = [bool]$raw.isDone
    $taskStatus = if ($raw.status) { [string]$raw.status } else { '' }
    $statusCode = if ($raw.statusCode) { [int]$raw.statusCode } else { 0 }

    Write-Host "Status poll ${attempt}/${MaxPollAttempts}: processState=${processState}, isDone=${isDone}, status=${taskStatus}, statusCode=${statusCode}"

    # Check terminal states
    if ($isDone -and $processState -eq 'done') {
        $done = $true
        $success = $true
        break
    }

    if ($isDone -and $processState -in @('failed', 'error')) {
        $done = $true
        $success = $false
        break
    }

    if ($isDone -and $statusCode -ge 400) {
        $done = $true
        $success = $false
        break
    }

    sleep 5
}

if (-not $done) {
    Write-Host "WARNING: Timed out after ${MaxPollAttempts} polls."
    exit 1
}

$agentResultOutput = & $cli agent result $taskId 2>&1 | ForEach-Object { "$_" }
Write-Host 'Final agent result:'
$agentResultOutput

if ($success) {
    Write-Host "Agent task $taskId completed successfully."
    exit 0
} else {
    Write-Host "Agent task $taskId failed."
    exit 1
}
