#!/usr/bin/env pwsh
<#
.SYNOPSIS
    Agent free-command task lifecycle test.

.DESCRIPTION
    Submits a free-command agent task (goto → extract), polls status until
    completion, and retrieves the final result.

    All CLI invocations are logged.  Failures are reported with log paths.
    If `copilot` is on PATH, it is invoked to analyse failures.
#>

<#
.PARAMETER Locale
    Two-letter locale code for URL selection (e.g. 'en', 'zh').
    Auto-detected from system culture when omitted.
    Override via -Locale or $env:BROWSER4_TEST_LOCALE.
#>
param(
    [string]$Locale = ''
)

$ErrorActionPreference = 'Stop'

# -------------------------------------------------------------------
# Load shared test utilities
# -------------------------------------------------------------------
Import-Module "$PSScriptRoot\test-utils.psm1" -Force
Start-TestSession -Name 'agent-run-free-command'

# Force UTF-8 encoding for CLI output
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
[Console]::InputEncoding  = [System.Text.Encoding]::UTF8

Write-TestHeader -Name 'agent-run-free-command'

# ===================================================================
# Resolve locale-appropriate test URL
# ===================================================================
$TestUrl = Get-TestUrl -Purpose ecommerce -Locale $Locale
Write-Host "  Locale : $(Get-TestLocale -Locale $Locale)" -ForegroundColor DarkGray
Write-Host "  Test URL: $TestUrl" -ForegroundColor DarkGray
Write-Host ''

# -------------------------------------------------------------------
# 1. Open session
# -------------------------------------------------------------------
Write-Host "━━━ Opening session ━━━" -ForegroundColor Cyan
$output = Invoke-TrackedCli -Arguments @('open') -Label 'open session' -PassThruOnly
Write-Host ''

# -------------------------------------------------------------------
# 2. Submit agent task
# -------------------------------------------------------------------
Write-Host "━━━ Submitting agent task ━━━" -ForegroundColor Cyan
$taskDescription = "goto $TestUrl ; give me the titles and prices of the first 10 products"
$output = Invoke-TrackedCli -Arguments @('agent', 'run', $taskDescription) -Label 'agent run (free-command)' -PassThruOnly

$agentRunText = ($output | Out-String).Trim()
Write-Host "Agent run output: $agentRunText" -ForegroundColor DarkGray

$taskIdMatch = [regex]::Match($agentRunText, 'Task submitted:\s*(\S+)')
if (-not $taskIdMatch.Success) {
    Write-Host "❌ Could not parse Task ID from agent run output" -ForegroundColor Red
    $exitCode = Finish-TestSession -ExtraCopilotPrompt "Could not parse Task ID from agent run output. Output was: $agentRunText"
    exit $(if ($exitCode -eq 0) { 1 } else { $exitCode })
}
$taskId = $taskIdMatch.Groups[1].Value
Write-Host "Task ID: $taskId" -ForegroundColor Green
Write-Host ''

# -------------------------------------------------------------------
# 3. Poll for completion
# -------------------------------------------------------------------
Write-Host "━━━ Polling agent status (task: $taskId) ━━━" -ForegroundColor Cyan

$done = $false
$success = $false
$lastStatusText = ''
$MaxPollAttempts = 60

# Extract the first complete JSON object from CLI output.
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
    $statusOutput = Invoke-TrackedCli -Arguments @('--json', 'agent', 'status', $taskId) `
        -Label "agent status poll ${attempt}/${MaxPollAttempts}" -PassThruOnly
    $lastStatusText = ($statusOutput | Out-String).Trim()
    Write-Host "  Status poll ${attempt}: $lastStatusText" -ForegroundColor DarkGray

    $status = Get-JsonFromOutput $lastStatusText
    if (-not $status) {
        Write-Host "  Status poll ${attempt}/${MaxPollAttempts}: no valid JSON in output, retrying..." -ForegroundColor Yellow
        Start-Sleep -Seconds 5
        continue
    }

    # Handle top-level CLI error envelope
    if ($status.status -eq 'error') {
        $errMsg = if ($status.error.message) { $status.error.message } else { 'Unknown CLI error' }
        Write-Host "  Status poll ${attempt}/${MaxPollAttempts}: CLI error - ${errMsg}" -ForegroundColor Red
        $done = $true
        $success = $false
        # Register as explicit failure
        Register-CliResult -Label "agent task $taskId (CLI error: $errMsg)" -ExitCode 1 -ExpectedExitCode 0 `
            -OutputLines @($lastStatusText) -Elapsed ([TimeSpan]::Zero)
        break
    }

    # Navigate to the agent task state: output.raw
    $raw = $status.output.raw
    if (-not $raw) {
        Write-Host "  Status poll ${attempt}/${MaxPollAttempts}: no output.raw in response, retrying..." -ForegroundColor Yellow
        Start-Sleep -Seconds 5
        continue
    }

    $processState = if ($raw.processState) { [string]$raw.processState } else { 'unknown' }
    $isDone = [bool]$raw.isDone
    $taskStatus = if ($raw.status) { [string]$raw.status } else { '' }
    $statusCode = if ($raw.statusCode) { [int]$raw.statusCode } else { 0 }

    Write-Host "  Status poll ${attempt}: processState=${processState}, isDone=${isDone}, status=${taskStatus}, statusCode=${statusCode}" -ForegroundColor DarkGray

    # Check terminal states
    if ($isDone -and $processState -eq 'done') {
        $done = $true
        $success = $true
        break
    }

    if ($isDone -and $processState -in @('failed', 'error')) {
        $done = $true
        $success = $false
        Register-CliResult -Label "agent task $taskId (processState=$processState)" -ExitCode 1 -ExpectedExitCode 0 `
            -OutputLines @($lastStatusText) -Elapsed ([TimeSpan]::Zero)
        break
    }

    if ($isDone -and $statusCode -ge 400) {
        $done = $true
        $success = $false
        Register-CliResult -Label "agent task $taskId (statusCode=$statusCode, status=$taskStatus)" -ExitCode $statusCode `
            -ExpectedExitCode 0 -OutputLines @($lastStatusText) -Elapsed ([TimeSpan]::Zero)
        break
    }

    Start-Sleep -Seconds 5
}

if (-not $done) {
    Write-Host "❌ Timed out after ${MaxPollAttempts} polls." -ForegroundColor Red
    Register-CliResult -Label "agent task $taskId (timeout after $MaxPollAttempts polls)" -ExitCode 1 -ExpectedExitCode 0 `
        -OutputLines @($lastStatusText) -Elapsed ([TimeSpan]::Zero)
}

# -------------------------------------------------------------------
# 4. Retrieve final result
# -------------------------------------------------------------------
Write-Host "`n━━━ Retrieving agent result ━━━" -ForegroundColor Cyan
$resultOutput = Invoke-TrackedCli -Arguments @('--json', 'agent', 'result', $taskId) `
    -Label 'agent result' -PassThruOnly
Write-Host "Final agent result:"
$resultOutput | ForEach-Object { Write-Host $_ }
Write-Host ''

# -------------------------------------------------------------------
# 5. Report
# -------------------------------------------------------------------
if ($success) {
    Write-Host "✅ Agent task $taskId completed successfully." -ForegroundColor Green
    # Close session (best effort)
    Invoke-TrackedCli -Arguments @('close') -Label 'final close' -PassThruOnly
    $code = Finish-TestSession
    exit $code
} else {
    Write-Host "❌ Agent task $taskId failed." -ForegroundColor Red
    # Close session (best effort)
    Invoke-TrackedCli -Arguments @('close') -Label 'final close' -PassThruOnly
    $code = Finish-TestSession -ExtraCopilotPrompt "Browser4 CLI agent free-command task failed. Task ID: $taskId"
    exit $(if ($code -eq 0) { 1 } else { $code })
}
