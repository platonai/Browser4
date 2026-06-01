#!/usr/bin/env pwsh
$ErrorActionPreference = 'Stop'

$cli = if ($env:BROWSER4_CLI_BIN) {
    { & $env:BROWSER4_CLI_BIN $args }
} else {
    { cargo run --quiet -- $args }
}

& $cli open

$agentRunOutput = & $cli agent run "https://www.hua.com/flower/" 2>&1
$agentRunText = ($agentRunOutput | Out-String).Trim()
$agentRunText

$taskIdMatch = [regex]::Match($agentRunText, 'Task submitted:\s*(\S+)')
$taskId = $taskIdMatch.Groups[1].Value
Write-Host "Waiting for agent task $taskId to finish..."

$done = $false
$success = $false
$lastStatusText = ''

for ($attempt = 1; $attempt -le 60; $attempt++) {
	$statusOutput = & $cli agent status $taskId 2>&1
	$lastStatusText = ($statusOutput | Out-String).Trim()
	$lastStatusText

	$status = $lastStatusText | ConvertFrom-Json
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
