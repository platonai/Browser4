#!/usr/bin/env pwsh

param(
	[int]$PollIntervalSeconds = 5,
	[int]$MaxPollAttempts = 60
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Format-DisplayArgument {
	param(
		[AllowNull()]
		[string]$Argument
	)

	if ($null -eq $Argument) {
		return "''"
	}

	if ($Argument -match '[\s"''`;,&|<>()\[\]{}]') {
		return "'" + $Argument.Replace("'", "''") + "'"
	}

	return $Argument
}

function Invoke-Browser4CliCommand {
	param(
		[Parameter(Mandatory = $true)]
		[string[]]$Arguments
	)

	$cargoArguments = @('run', '--quiet', '--') + $Arguments
	$displayCommand = @('cargo') + @($cargoArguments | ForEach-Object { Format-DisplayArgument $_ })
	Write-Host ">>> $($displayCommand -join ' ')" -ForegroundColor Cyan

	$output = & cargo @cargoArguments 2>&1
	$exitCode = $LASTEXITCODE
	$text = (@($output) | ForEach-Object { $_.ToString() }) -join [Environment]::NewLine

	if (-not [string]::IsNullOrWhiteSpace($text)) {
		Write-Host $text
	}

	if ($exitCode -ne 0) {
		throw "Command failed with exit code ${exitCode}: cargo $($cargoArguments -join ' ')"
	}

	return $text
}

function Get-AgentTaskIdFromOutput {
	param(
		[Parameter(Mandatory = $true)]
		[string]$Output
	)

	$match = [regex]::Match($Output, '(?m)^\s*Task submitted:\s*(?<TaskId>\S+)\s*$')
	if (-not $match.Success) {
		throw "Could not extract the agent task id from output:`n$Output"
	}

	return $match.Groups['TaskId'].Value
}

function Get-AgentTaskState {
	param(
		[Parameter(Mandatory = $true)]
		[string]$StatusOutput
	)

	$raw = $StatusOutput.Trim()
	$state = 'unknown'
	$displayState = $null
	$message = $null
	$statusCode = $null

	try {
		$parsed = $raw | ConvertFrom-Json -ErrorAction Stop

		if ($parsed.PSObject.Properties.Name -contains 'processState' -and $parsed.processState) {
			$displayState = [string]$parsed.processState
			$state = $displayState.ToLowerInvariant()
		}

		if ($parsed.PSObject.Properties.Name -contains 'status' -and $parsed.status) {
			if (-not $displayState) {
				$displayState = [string]$parsed.status
			}

			if ($state -eq 'unknown') {
				switch ($displayState.ToUpperInvariant()) {
					'CREATED' { $state = 'created' }
					'RUNNING' { $state = 'in_progress' }
					'IN_PROGRESS' { $state = 'in_progress' }
					'DONE' { $state = 'done' }
					'COMPLETED' { $state = 'done' }
					'SUCCESS' { $state = 'done' }
					'FAILED' { $state = 'failed' }
					'ERROR' { $state = 'error' }
					'NOT_FOUND' { $state = 'not_found' }
					default { $state = $displayState.ToLowerInvariant() }
				}
			}
		}

		if ($parsed.PSObject.Properties.Name -contains 'message' -and $parsed.message) {
			$message = [string]$parsed.message
		}

		if ($parsed.PSObject.Properties.Name -contains 'statusCode' -and $null -ne $parsed.statusCode) {
			$statusCode = [int]$parsed.statusCode
		}
	}
	catch {
		return [pscustomobject]@{
			Raw = $raw
			State = $state
			DisplayState = $displayState
			Message = $message
			StatusCode = $statusCode
			IsDone = $false
			IsSuccess = $false
		}
	}

	$terminalStates = @('done', 'completed', 'success', 'failed', 'error', 'not_found', 'cancelled')
	$failureStates = @('failed', 'error', 'not_found', 'cancelled')
	$isDone = $terminalStates -contains $state
	$isSuccess = $isDone -and ($failureStates -notcontains $state) -and ($null -eq $statusCode -or $statusCode -lt 400)

	return [pscustomobject]@{
		Raw = $raw
		State = $state
		DisplayState = $displayState
		Message = $message
		StatusCode = $statusCode
		IsDone = $isDone
		IsSuccess = $isSuccess
	}
}

Invoke-Browser4CliCommand @('open', 'https://www.amazon.com/')
Start-Sleep -Seconds 5
Invoke-Browser4CliCommand @('list')

$agentRunOutput = Invoke-Browser4CliCommand @(
	'agent-run',
	'goto https://www.hua.com/flower/ ; give me the titles and prices of the first 10 products'
)

$taskId = Get-AgentTaskIdFromOutput -Output $agentRunOutput
Write-Host "Waiting for agent task $taskId to finish..." -ForegroundColor Yellow

$lastStatus = $null
for ($attempt = 1; $attempt -le $MaxPollAttempts; $attempt++) {
	$statusOutput = Invoke-Browser4CliCommand @('agent-status', $taskId)
	$lastStatus = Get-AgentTaskState -StatusOutput $statusOutput

	$statusLabel = if ($lastStatus.DisplayState) { $lastStatus.DisplayState } else { $lastStatus.State }
	Write-Host "Status poll ${attempt}/${MaxPollAttempts}: $statusLabel"

	if ($lastStatus.IsDone) {
		break
	}

	Start-Sleep -Seconds $PollIntervalSeconds
}

if ($null -eq $lastStatus -or -not $lastStatus.IsDone) {
	throw "Timed out waiting for agent task $taskId after $MaxPollAttempts polls."
}

if (-not $lastStatus.IsSuccess) {
	$failureMessage = if ($lastStatus.Message) { $lastStatus.Message } else { $lastStatus.Raw }
	throw "Agent task $taskId finished unsuccessfully: $failureMessage"
}

$agentResultOutput = Invoke-Browser4CliCommand @('agent-result', $taskId)
Write-Host "Final agent result:" -ForegroundColor Green
Write-Host $agentResultOutput
