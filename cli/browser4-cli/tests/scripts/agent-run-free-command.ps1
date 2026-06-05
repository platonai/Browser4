#!/usr/bin/env pwsh
$ErrorActionPreference = 'Stop'

# Fix encoding: force UTF-8 so Chinese characters from the CLI are not garbled
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
[Console]::InputEncoding  = [System.Text.Encoding]::UTF8

$cli = if ($env:BROWSER4_CLI_BIN) {
    { & $env:BROWSER4_CLI_BIN $args }
} else {
    { cargo run --quiet -- $args }
}

& $cli open

$agentRunOutput = & $cli agent run "goto https://www.hua.com/flower/ ; give me the titles and prices of the first 10 products" 2>&1
Write-Host $agentRunOutput

$agentRunText = ($agentRunOutput | Out-String).Trim()

$taskIdMatch = [regex]::Match($agentRunText, 'Task submitted:\s*(\S+)')
$taskId = $taskIdMatch.Groups[1].Value
Write-Host "Waiting for agent task $taskId to finish..."

$done = $false
$success = $false
$lastStatusText = ''
$MaxPollAttempts = 60

for ($attempt = 1; $attempt -le $MaxPollAttempts; $attempt++) {
    $statusOutput = & $cli agent status $taskId 2>&1
    $lastStatusText = ($statusOutput | Out-String).Trim()

    # Extract the JSON object from the output (stderr may contain non-JSON log lines).
    # The JSON line is the one that starts with '{' — find it and parse only that.
    $jsonLine = ($lastStatusText -split "`n" | Where-Object { $_.Trim().StartsWith('{') } | Select-Object -Last 1)
    if (-not $jsonLine) {
        Write-Host "Status poll ${attempt}/${MaxPollAttempts}: no JSON found in output"
        Write-Host "Raw output: $lastStatusText"
        sleep 5
        continue
    }

    $status = $jsonLine | ConvertFrom-Json
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

$agentResultOutput = & $cli agent result $taskId 2>&1
Write-Host 'Final agent result:'
$agentResultOutput
