#!/usr/bin/env pwsh
$ErrorActionPreference = 'Stop'

# Fix 1: Force UTF-8 encoding for CLI output
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

# Fix 2a: Convert 2>&1 merged streams to plain strings so ErrorRecords don't break later parsing
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

for ($attempt = 1; $attempt -le $MaxPollAttempts; $attempt++) {
    # Fix 2a: Convert 2>&1 merged streams to plain strings
    $statusOutput = & $cli --json agent status $taskId 2>&1 | ForEach-Object { "$_" }
    $lastStatusText = ($statusOutput | Out-String).Trim()
    $lastStatusText

    # Fix 2b: Extract JSON object from mixed output before parsing
    $jsonMatch = [regex]::Match($lastStatusText, '\{.*\}', [System.Text.RegularExpressions.RegexOptions]::Singleline)
    if (-not $jsonMatch.Success) {
        Write-Host "Status poll ${attempt}/${MaxPollAttempts}: no JSON found in output, retrying..."
        sleep 5
        continue
    }

    try {
        $status = $jsonMatch.Value | ConvertFrom-Json
    } catch {
        Write-Host "Status poll ${attempt}/${MaxPollAttempts}: ConvertFrom-Json failed: $_"
        Write-Host "Raw JSON attempt: $($jsonMatch.Value)"
        sleep 5
        continue
    }

    $state = if ($status.processState) {
        [string]$status.processState
    } elseif ($status.status) {
        [string]$status.status
    } else {
        'unknown'
    }

    Write-Host "Status poll ${attempt}/${MaxPollAttempts}: $state"

    if ($state -in @('done', 'DONE', 'completed', 'COMPLETED', 'success', 'SUCCESS')) {
        $done = $true
        $success = $true
        break
    }

    if ($state -in @('failed', 'FAILED', 'error', 'ERROR', 'not_found', 'NOT_FOUND', 'cancelled', 'CANCELLED')) {
        $done = $true
        $success = $false
        break
    }

    sleep 5
}

# Fix 2a: Convert merged streams for final result output too
$agentResultOutput = & $cli agent result $taskId 2>&1 | ForEach-Object { "$_" }
Write-Host 'Final agent result:'
$agentResultOutput
