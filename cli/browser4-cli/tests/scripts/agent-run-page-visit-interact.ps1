#!/usr/bin/env pwsh
$ErrorActionPreference = 'Stop'

# Force UTF-8 encoding for CLI output
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$PSDefaultParameterValues['*:Encoding'] = 'utf8'

$cli = if ($env:BROWSER4_CLI_BIN) {
    { & $env:BROWSER4_CLI_BIN $args }
} else {
    { cargo run --quiet -- $args }
}

& $cli open

$task = @"
Visit https://www.amazon.com/dp/B08PP5MSVB
Summarize the product.
Extract: product name, price, ratings.
Find all links containing /dp/.
After page load: click #title, then scroll to the middle.
"@

# Convert 2>&1 merged streams to plain strings so ErrorRecords don't break later parsing
$agentRunOutput = & $cli agent run "$task" 2>&1 | ForEach-Object { "$_" }
$agentRunText = ($agentRunOutput | Out-String).Trim()
$agentRunText

$taskIdMatch = [regex]::Match($agentRunText, 'Task submitted:\s*(\S+)')
$taskId = $taskIdMatch.Groups[1].Value
Write-Host "Waiting for agent task $taskId to finish..."

$done = $false
$success = $false
$lastStatusText = ''
$MaxPollAttempts = 60

# ---------------------------------------------------------------------------
# Parse a JSON line from the CLI output.
# CLI returns a JSON envelope: {"status":"ok"|"error", "command":"...", "output":{...}}
# The agent task state lives under output.raw:
#   output.raw.isDone       : bool   — true when the task has finished
#                                     Always serialized as "isDone" (never "done").
#                                     Jackson Kotlin module uses the Kotlin property name
#                                     directly, not the Java Bean convention of stripping "is".
#   output.raw.processState : str    — "in_progress" | "done" | "failed"
#   output.raw.status       : str    — "Processing" | "OK" | …
#   output.raw.statusCode   : int    — 102 (Processing), 200 (OK), etc.
#   output.raw.commandResult: object — present when done; contains pageSummary & fields
#   output.raw.instructResults: array — per-instruction results with statusCode
# ---------------------------------------------------------------------------

function Get-JsonFromOutput {
    param([string]$Text)
    # Extract the first complete JSON object (the CLI envelope)
    $jsonMatch = [regex]::Match($Text, '\{.*\}', [System.Text.RegularExpressions.RegexOptions]::Singleline)
    if (-not $jsonMatch.Success) { return $null }
    try {
        return $jsonMatch.Value | ConvertFrom-Json
    } catch {
        return $null
    }
}

for ($attempt = 1; $attempt -le $MaxPollAttempts; $attempt++) {
    # Convert 2>&1 merged streams to plain strings
    $statusOutput = & $cli --json agent status $taskId 2>&1 | ForEach-Object { "$_" }
    $lastStatusText = ($statusOutput | Out-String).Trim()
    $lastStatusText

    $status = Get-JsonFromOutput $lastStatusText
    if (-not $status) {
        Write-Host "Status poll ${attempt}/${MaxPollAttempts}: no valid JSON in output, retrying..."
        sleep 3
        continue
    }

    # --- Handle top-level CLI error envelope ---
    # {"status":"error","command":"agent-status","error":{"message":"...","code":"..."}}
    if ($status.status -eq 'error') {
        $errMsg = if ($status.error.message) { $status.error.message } else { 'Unknown CLI error' }
        $errCode = if ($status.error.code) { $status.error.code } else { 'UNKNOWN' }
        Write-Host "Status poll ${attempt}/${MaxPollAttempts}: CLI error [${errCode}] ${errMsg}"
        $done = $true
        $success = $false
        break
    }

    # --- Navigate to the agent task state: output.raw ---
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

    # --- Check terminal states ---
    # Success: isDone=true and processState="done"
    if ($isDone -and $processState -eq 'done') {
        $done = $true
        $success = $true
        break
    }

    # Failure: isDone=true but processState indicates an error
    if ($isDone -and $processState -in @('failed', 'error')) {
        $done = $true
        $success = $false
        Write-Host "Task $taskId ended with processState=${processState}"
        break
    }

    # Failure: statusCode indicates an error (4xx/5xx)
    if ($isDone -and $statusCode -ge 400) {
        $done = $true
        $success = $false
        Write-Host "Task $taskId ended with statusCode=${statusCode}, status=${taskStatus}"
        break
    }

    sleep 5
}

# --- Report outcome ---
if (-not $done) {
    Write-Host "WARNING: Timed out after ${MaxPollAttempts} polls. Last known state:"
    $lastStatusText
    exit 1
}

# --- Extract and display results from the final status poll ---
$finalStatus = Get-JsonFromOutput $lastStatusText
if ($finalStatus -and $finalStatus.output.raw) {
    $raw = $finalStatus.output.raw

    # Display finish time
    if ($raw.finishTime) {
        Write-Host "`nFinished at: $($raw.finishTime)"
    }

    # Display command results (pageSummary + fields)
    if ($raw.commandResult) {
        Write-Host "`n=== COMMAND RESULTS ==="
        if ($raw.commandResult.pageSummary) {
            Write-Host "--- Page Summary ---"
            Write-Host $raw.commandResult.pageSummary
        }
        if ($raw.commandResult.fields) {
            Write-Host "--- Extracted Fields ---"
            $raw.commandResult.fields | ConvertTo-Json -Depth 5
        }
    }

    # Display per-instruction results
    if ($raw.instructResults) {
        Write-Host "`n=== INSTRUCTION RESULTS ==="
        foreach ($ir in $raw.instructResults) {
            $icon = if ($ir.statusCode -eq 200) { '[OK]' } else { '[FAIL]' }
            Write-Host "${icon} ${$ir.name} (statusCode=$($ir.statusCode), resultType=$($ir.resultType))"
        }
    }

    # Display page metadata
    Write-Host "`n=== PAGE METADATA ==="
    Write-Host "pageStatusCode : $($raw.pageStatusCode)"
    Write-Host "pageContentBytes: $($raw.pageContentBytes)"
    Write-Host "event          : $($raw.event)"
}

# Also fetch the full agent result for completeness
$agentResultOutput = & $cli agent result $taskId 2>&1 | ForEach-Object { "$_" }
Write-Host "`n=== RAW AGENT RESULT ==="
$agentResultOutput

if ($success) {
    Write-Host "`nAgent task $taskId completed successfully."
    exit 0
} else {
    Write-Host "`nAgent task $taskId failed."
    exit 1
}
