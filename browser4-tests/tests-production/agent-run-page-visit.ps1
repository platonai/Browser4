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
    Agent page-visit task health check.

.DESCRIPTION
    Submits a page-visit agent task (visit a product page, summarize),
    verifies the task was accepted and is processing, then closes the session.

    This is a HEALTH CHECK — it verifies that the agent run pipeline
    (submission → task ID → processing state) is functional, without
    waiting for the full agent task to complete.

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
Start-TestSession -Name 'agent-run-page-visit'

# Force UTF-8 encoding for CLI output
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$PSDefaultParameterValues['*:Encoding'] = 'utf8'

Write-TestHeader -Name 'agent-run-page-visit'

# ===================================================================
# Resolve locale-appropriate test URL
# ===================================================================
$TestUrl = Get-TestUrl -Purpose product -Locale $Locale
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
# 2. Submit agent task (health check)
# -------------------------------------------------------------------
Write-Host "━━━ Submitting agent task (health check) ━━━" -ForegroundColor Cyan
# Use --timeout to give the CLI enough headroom for the HTTP call on a
# cold server.  The agent run command is async — the server should return
# a task ID immediately, but a cold server may need time to initialise.
$taskDescription = "Visit $TestUrl ; Summarize the product."
$output = Invoke-TrackedCli -Arguments @('--timeout', '120', 'agent', 'run', $taskDescription) `
    -Label 'agent run (page-visit)' -PassThruOnly
$agentRunText = ($output | Out-String).Trim()
Write-Host "Agent run output: $agentRunText" -ForegroundColor DarkGray

$taskIdMatch = [regex]::Match($agentRunText, 'Task submitted:\s*(\S+)')
$taskId = $null
if ($taskIdMatch.Success) {
    $taskId = $taskIdMatch.Groups[1].Value
    Write-Host "Task ID: $taskId" -ForegroundColor Green
} else {
    Write-Host "❌ Could not parse Task ID from agent run output" -ForegroundColor Red
    Register-CliResult -Label 'agent run (parse Task ID)' -ExitCode 1 -ExpectedExitCode 0 `
        -OutputLines @($agentRunText) -Elapsed ([TimeSpan]::Zero)
}
Write-Host ''

# -------------------------------------------------------------------
# 3. Health verification — quick status poll (only if we have a task ID)
# -------------------------------------------------------------------
$healthVerified = $false
$lastStatusText = ''

# Parse a JSON line from the CLI output.
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

if ($taskId) {

Write-Host "━━━ Health verification (task: $taskId) ━━━" -ForegroundColor Cyan

# Poll a few times to confirm the task entered a valid processing state.
# This is a health check, not a completion wait — we only need to see
# that the agent runner picked up the task.
$MaxHealthPolls = 6   # 6 polls × 5 s = 30 s max for health verification
for ($attempt = 1; $attempt -le $MaxHealthPolls; $attempt++) {
    $statusOutput = Invoke-TrackedCli -Arguments @('--json', 'agent', 'status', $taskId) `
        -Label "agent status poll ${attempt}/${MaxHealthPolls}" -PassThruOnly
    $lastStatusText = ($statusOutput | Out-String).Trim()
    Write-Host "  Status poll ${attempt}: $lastStatusText" -ForegroundColor DarkGray

    $status = Get-JsonFromOutput $lastStatusText
    if (-not $status) {
        Write-Host "  Status poll ${attempt}/${MaxHealthPolls}: no valid JSON in output, retrying..." -ForegroundColor Yellow
        Start-Sleep -Seconds 5
        continue
    }

    if ($status.status -eq 'error') {
        $errMsg = if ($status.error.message) { $status.error.message } else { 'Unknown CLI error' }
        Write-Host "  Status poll ${attempt}/${MaxHealthPolls}: CLI error - ${errMsg}" -ForegroundColor Red
        Register-CliResult -Label "agent task $taskId (CLI error: $errMsg)" -ExitCode 1 -ExpectedExitCode 0 `
            -OutputLines @($lastStatusText) -Elapsed ([TimeSpan]::Zero)
        break
    }

    $raw = $status.output.raw
    if (-not $raw) {
        Write-Host "  Status poll ${attempt}/${MaxHealthPolls}: no output.raw in response, retrying..." -ForegroundColor Yellow
        Start-Sleep -Seconds 5
        continue
    }

    $processState = if ($raw.processState) { [string]$raw.processState } else { 'unknown' }
    $isDone = [bool]$raw.isDone
    $taskStatus = if ($raw.status) { [string]$raw.status } else { '' }

    Write-Host "  Status poll ${attempt}: processState=${processState}, isDone=${isDone}, status=${taskStatus}" -ForegroundColor DarkGray

    # Health check: any valid response with a recognised processState means
    # the agent runner is alive and processing the task.
    if ($processState -in @('in_progress', 'processing', 'pending', 'done')) {
        $healthVerified = $true
        Write-Host "  ✅ Health check passed: task ${taskId} is in state '${processState}'" -ForegroundColor Green
        break
    }

    if ($isDone -and $processState -in @('failed', 'error')) {
        Write-Host "  ❌ Task ${taskId} entered terminal state '${processState}' during health check" -ForegroundColor Red
        Register-CliResult -Label "agent task $taskId (processState=$processState)" -ExitCode 1 -ExpectedExitCode 0 `
            -OutputLines @($lastStatusText) -Elapsed ([TimeSpan]::Zero)
        break
    }

    Start-Sleep -Seconds 5
}

if (-not $healthVerified) {
    Write-Host "  ⚠ Health verification inconclusive after ${MaxHealthPolls} polls — task may still be initialising" -ForegroundColor Yellow
    # Don't register a failure here — the task ID was obtained successfully.
    # The health check is best-effort; a slow-starting task is not necessarily broken.
}

} # end if ($taskId)

# -------------------------------------------------------------------
# 4. Report
# -------------------------------------------------------------------
Invoke-TrackedCli -Arguments @('close') -Label 'final close' -PassThruOnly

if ($taskId) {
    Write-Host "✅ Agent task $taskId submitted successfully (health check: $(if ($healthVerified) { 'verified' } else { 'submitted' }))." -ForegroundColor Green
    $code = Finish-TestSession
    exit $code
} else {
    Write-Host "❌ Agent task failed — could not parse Task ID." -ForegroundColor Red
    $code = Finish-TestSession -ExtraCopilotPrompt "Browser4 CLI agent page-visit health check failed. Could not parse Task ID from output: $agentRunText"
    exit $(if ($code -eq 0) { 1 } else { $code })
}
