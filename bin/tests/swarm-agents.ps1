#!/usr/bin/env pwsh
<#
.SYNOPSIS
    Swarm create / submit / status lifecycle test.

.DESCRIPTION
    Creates a swarm session, submits a seed URL, polls status until completion,
    and verifies the lifecycle works end-to-end.

    All CLI invocations are logged.  Failures are reported with log paths.
    If `copilot` is on PATH, it is invoked to analyse failures.
#>

$ErrorActionPreference = 'Stop'

# -------------------------------------------------------------------
# Load shared test utilities
# -------------------------------------------------------------------
Import-Module "$PSScriptRoot\test-utils.psm1" -Force
Start-TestSession -Name 'swarm-agents'

Write-TestHeader -Name 'swarm-agents'

# -------------------------------------------------------------------
# 1. Open session + swarm create
# -------------------------------------------------------------------
Write-Host "━━━ Opening session ━━━" -ForegroundColor Cyan
$output = Invoke-TrackedCli -Arguments @('open') -Label 'open session' -PassThruOnly
Write-Host ''

Write-Host "━━━ Creating swarm session ━━━" -ForegroundColor Cyan
$output = Invoke-TrackedCli -Arguments @('swarm', 'create') -Label 'swarm create' -PassThruOnly
Write-Host ''

# -------------------------------------------------------------------
# 2. Submit seed URL
# -------------------------------------------------------------------
Write-Host "━━━ Submitting swarm task ━━━" -ForegroundColor Cyan
$output = Invoke-TrackedCli -Arguments @('swarm', 'submit', 'https://example.com') -Label 'swarm submit' -PassThruOnly
$outputText = ($output | Out-String).Trim()
$submittedLine = $outputText -split "`r?`n" | Where-Object { $_ -match 'Task ID:\s*\S+' } | Select-Object -First 1
if (-not $submittedLine) {
    $submittedLine = $outputText
}
Write-Host "Submitted swarm task: $submittedLine" -ForegroundColor DarkGray

$taskIdMatch = [regex]::Match($outputText, 'Task ID:\s*(\S+)')
if (-not $taskIdMatch.Success) {
    Write-Host "❌ Unable to parse Task ID from swarm submit output" -ForegroundColor Red
    Register-CliResult -Label 'swarm submit (parse Task ID)' -ExitCode 1 -ExpectedExitCode 0 `
        -OutputLines @($outputText) -Elapsed ([TimeSpan]::Zero)
    $code = Finish-TestSession -ExtraCopilotPrompt "Could not parse Task ID from swarm submit output. Output was: $outputText"
    exit $(if ($code -eq 0) { 1 } else { $code })
}

$taskId = $taskIdMatch.Groups[1].Value
Write-Host "Task ID: $taskId" -ForegroundColor Green
Write-Host ''

# -------------------------------------------------------------------
# 3. Poll for completion
# -------------------------------------------------------------------
Write-Host "━━━ Polling swarm status (task: $taskId) ━━━" -ForegroundColor Cyan

$done = $false
for ($i = 1; $i -le 20; $i++) {
    $status = Invoke-TrackedCli -Arguments @('swarm', 'status', $taskId) `
        -Label "swarm status poll ${i}/20" -PassThruOnly
    $statusText = ($status | Out-String).Trim()
    Write-Host "  Status poll ${i}: $statusText" -ForegroundColor DarkGray

    if ($statusText -match '"done"\s*:\s*true' -or $statusText -match '"isDone"\s*:\s*true' -or $statusText -match 'status:\s*done') {
        Write-Host "Task $taskId is done." -ForegroundColor Green
        $done = $true
        break
    }
    Start-Sleep -Seconds 3
}

if (-not $done) {
    Write-Host "⚠ Task $taskId did not complete within 20 polls" -ForegroundColor Yellow
    Register-CliResult -Label "swarm task $taskId (not done after 20 polls)" -ExitCode 1 -ExpectedExitCode 0 `
        -OutputLines @($statusText) -Elapsed ([TimeSpan]::Zero)
}

# -------------------------------------------------------------------
# 4. Cleanup
# -------------------------------------------------------------------
Write-Host "`n━━━ Cleanup ━━━" -ForegroundColor Cyan
Invoke-TrackedCli -Arguments @('close') -Label 'final close' -PassThruOnly

# -------------------------------------------------------------------
# 5. Report
# -------------------------------------------------------------------
if ($done) {
    Write-Host "✅ Swarm test completed successfully." -ForegroundColor Green
} else {
    Write-Host "⚠ Swarm test completed with warnings." -ForegroundColor Yellow
}
$code = Finish-TestSession -ExtraCopilotPrompt "Browser4 CLI swarm-agents test. Task ID: $taskId"
exit $code
