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
    Agent page-visit task lifecycle test.

.DESCRIPTION
    Submits a page-visit agent task (visit a product page, summarize),
    polls status until completion, and retrieves the final result.

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
# 2. Submit agent task
# -------------------------------------------------------------------
Write-Host "━━━ Submitting agent task ━━━" -ForegroundColor Cyan
# Collapse to a single line to avoid multi-line argument splitting across
# platforms.  PowerShell here-strings pass newlines literally, and some
# CLI argument parsers (especially on Windows) split on embedded CR/LF.
$taskDescription = "Visit $TestUrl ; Summarize the product."
$output = Invoke-TrackedCli -Arguments @('agent', 'run', $taskDescription) -Label 'agent run (page-visit)' -PassThruOnly
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
    # Fall through — remaining steps will be skipped.
}
Write-Host ''

# -------------------------------------------------------------------
# 3. Poll for completion (only if we have a task ID)
# -------------------------------------------------------------------
$done = $false
$success = $false
$lastStatusText = ''
$MaxPollAttempts = 60

# Extract the first complete JSON object from CLI output.
# (Defined at script level so the parser doesn't get confused by nested braces.)
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

Write-Host "━━━ Polling agent status (task: $taskId) ━━━" -ForegroundColor Cyan

for ($attempt = 1; $attempt -le $MaxPollAttempts; $attempt++) {
    $statusOutput = Invoke-TrackedCli -Arguments @('--json', 'agent', 'status', $taskId) `
        -Label "agent status poll ${attempt}/${MaxPollAttempts}" -PassThruOnly
    $lastStatusText = ($statusOutput | Out-String).Trim()
    Write-Host "  Status poll ${attempt}: $lastStatusText" -ForegroundColor DarkGray

    $status = Get-JsonFromOutput $lastStatusText
    if (-not $status) {
        Write-Host "  Status poll ${attempt}/${MaxPollAttempts}: no valid JSON in output, retrying..." -ForegroundColor Yellow
        Start-Sleep -Seconds 3
        continue
    }

    if ($status.status -eq 'error') {
        $errMsg = if ($status.error.message) { $status.error.message } else { 'Unknown CLI error' }
        Write-Host "  Status poll ${attempt}/${MaxPollAttempts}: CLI error - ${errMsg}" -ForegroundColor Red
        $done = $true
        $success = $false
        Register-CliResult -Label "agent task $taskId (CLI error: $errMsg)" -ExitCode 1 -ExpectedExitCode 0 `
            -OutputLines @($lastStatusText) -Elapsed ([TimeSpan]::Zero)
        break
    }

    $raw = $status.output.raw
    if (-not $raw) {
        Write-Host "  Status poll ${attempt}/${MaxPollAttempts}: no output.raw in response, retrying..." -ForegroundColor Yellow
        Start-Sleep -Seconds 3
        continue
    }

    $processState = if ($raw.processState) { [string]$raw.processState } else { 'unknown' }
    $isDone = [bool]$raw.isDone
    $taskStatus = if ($raw.status) { [string]$raw.status } else { '' }
    $statusCode = if ($raw.statusCode) { [int]$raw.statusCode } else { 0 }

    Write-Host "  Status poll ${attempt}: processState=${processState}, isDone=${isDone}, status=${taskStatus}, statusCode=${statusCode}" -ForegroundColor DarkGray

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

} # end if ($taskId)

# -------------------------------------------------------------------
# 4. Retrieve final result (only if polling succeeded)
# -------------------------------------------------------------------
if ($taskId) {
    Write-Host "`n━━━ Retrieving agent result ━━━" -ForegroundColor Cyan
    $resultOutput = Invoke-TrackedCli -Arguments @('agent', 'result', $taskId) `
        -Label 'agent result' -PassThruOnly
    Write-Host "Final agent result:"
    $resultOutput | ForEach-Object { Write-Host $_ }
    Write-Host ''
}

# -------------------------------------------------------------------
# 5. Report
# -------------------------------------------------------------------
Invoke-TrackedCli -Arguments @('close') -Label 'final close' -PassThruOnly

if ($taskId -and $success) {
    Write-Host "✅ Agent task $taskId completed successfully." -ForegroundColor Green
    $code = Finish-TestSession
    exit $code
} elseif ($taskId) {
    Write-Host "❌ Agent task $taskId failed." -ForegroundColor Red
    $code = Finish-TestSession -ExtraCopilotPrompt "Browser4 CLI agent page-visit task failed. Task ID: $taskId"
    exit $(if ($code -eq 0) { 1 } else { $code })
} else {
    Write-Host "❌ Agent task failed — could not parse Task ID." -ForegroundColor Red
    $code = Finish-TestSession -ExtraCopilotPrompt "Browser4 CLI agent page-visit task failed. Could not parse Task ID from output: $agentRunText"
    exit $(if ($code -eq 0) { 1 } else { $code })
}
