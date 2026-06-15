#!/usr/bin/env pwsh

# ═══════════════════════════════════════════════════════════════════
# CROSS-PLATFORM: This script must run on Linux, macOS, and Windows.
# - Use $IsWindows / $IsLinux / $IsMacOS for platform detection.
# - Use "($IsWindows -or $env:OS -eq 'Windows_NT')" for PS 5.1 compat.
# - Windows-only env vars ($env:TEMP) need $env:TMPDIR fallback.
# - Guard "chcp" and other Windows-only commands behind platform checks.
# ═══════════════════════════════════════════════════════════════════
<#
.SYNOPSIS
    Swarm create / submit / status lifecycle test.

.DESCRIPTION
    Creates a swarm session, submits a seed URL, polls status until completion,
    and verifies the lifecycle works end-to-end.

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

$ErrorActionPreference = 'Continue'

# -------------------------------------------------------------------
# Load shared test utilities
# -------------------------------------------------------------------
Import-Module "$PSScriptRoot\test-utils.psm1" -Force
Start-TestSession -Name 'swarm-agents'

Write-TestHeader -Name 'swarm-agents'

# ===================================================================
# Resolve locale-appropriate test URL
# ===================================================================
$SwarmUrl = Get-TestUrl -Purpose simple -Locale $Locale
Write-Host "  Locale : $(Get-TestLocale -Locale $Locale)" -ForegroundColor DarkGray
Write-Host "  Swarm URL: $SwarmUrl" -ForegroundColor DarkGray
Write-Host ''

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
$output = Invoke-TrackedCli -Arguments @('swarm', 'submit', $SwarmUrl) -Label 'swarm submit' -PassThruOnly
$outputText = ($output | Out-String).Trim()
$submittedLine = $outputText -split "`r?`n" | Where-Object { $_ -match 'Task ID:\s*\S+' } | Select-Object -First 1
if (-not $submittedLine) {
    $submittedLine = $outputText
}
Write-Host "Submitted swarm task: $submittedLine" -ForegroundColor DarkGray

$taskIdMatch = [regex]::Match($outputText, 'Task ID:\s*(\S+)')
$taskId = $null
if ($taskIdMatch.Success) {
    $taskId = $taskIdMatch.Groups[1].Value
    Write-Host "Task ID: $taskId" -ForegroundColor Green
} else {
    Write-Host "❌ Unable to parse Task ID from swarm submit output" -ForegroundColor Red
    Register-CliResult -Label 'swarm submit (parse Task ID)' -ExitCode 1 -ExpectedExitCode 0 `
        -OutputLines @($outputText) -Elapsed ([TimeSpan]::Zero)
    # Fall through — remaining steps will be skipped.
}
Write-Host ''

# -------------------------------------------------------------------
# 3. Poll for completion (only if we have a task ID)
# -------------------------------------------------------------------
$done = $false

if ($taskId) {
Write-Host "━━━ Polling swarm status (task: $taskId) ━━━" -ForegroundColor Cyan

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
}

# -------------------------------------------------------------------
# 4. Cleanup
# -------------------------------------------------------------------
Write-Host "`n━━━ Cleanup ━━━" -ForegroundColor Cyan
Invoke-TrackedCli -Arguments @('close') -Label 'final close' -PassThruOnly

# -------------------------------------------------------------------
# 5. Report
# -------------------------------------------------------------------
if ($taskId -and $done) {
    Write-Host "✅ Swarm test completed successfully." -ForegroundColor Green
} elseif ($taskId) {
    Write-Host "⚠ Swarm test completed with warnings." -ForegroundColor Yellow
} else {
    Write-Host "❌ Swarm test failed — could not parse Task ID." -ForegroundColor Red
}
$code = Finish-TestSession -ExtraCopilotPrompt "Browser4 CLI swarm-agents test. Task ID: $taskId"
exit $code
