#!/usr/bin/env pwsh

cargo run --quiet -- open

$task = @"
Visit https://www.amazon.com/dp/B08PP5MSVB
Summarize the product.
Extract: product name, price, ratings.
Find all links containing /dp/.
After page load: click #title, then scroll to the middle.
"@

$agentRunOutput = cargo run --quiet -- agent run $task 2>&1
$agentRunText = ($agentRunOutput | Out-String).Trim()
$agentRunText

$taskIdMatch = [regex]::Match($agentRunText, 'Task submitted:\s*(\S+)')
$taskId = $taskIdMatch.Groups[1].Value
Write-Host "Waiting for agent task $taskId to finish..."

$done = $false
$success = $false
$lastStatusText = ''

for ($attempt = 1; $attempt -le 60; $attempt++) {
	$statusOutput = cargo run --quiet -- agent status $taskId 2>&1
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

$agentResultOutput = cargo run --quiet -- agent result $taskId 2>&1
Write-Host 'Final agent result:'
$agentResultOutput
