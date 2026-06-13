#!/usr/bin/env pwsh
<#
.SYNOPSIS
    Stress-test swarm create / submit / status / result lifecycle using a seed file.

.DESCRIPTION
    Creates a seeds.txt file with URLs to scrape, opens a swarm session, submits
    the seed file, polls for completion, and retrieves results.

    All CLI invocations are logged.  Failures are reported with log paths.
    If `copilot` is on PATH, it is invoked to analyse failures.

.PARAMETER TimeoutSeconds
    Maximum time to wait for the swarm task to complete.

.PARAMETER SeedFile
    Path to the seed file (created dynamically by this script).
#>
param(
    [int]$TimeoutSeconds = 120,
    [string]$SeedFile = 'seeds.txt',
    [string]$Locale = ''
)

$ErrorActionPreference = 'Stop'

# -------------------------------------------------------------------
# Load shared test utilities
# -------------------------------------------------------------------
Import-Module "$PSScriptRoot\test-utils.psm1" -Force
Start-TestSession -Name 'stress-swarm-agents'

Write-TestHeader -Name 'stress-swarm-agents'

# -------------------------------------------------------------------
# 1. Prepare seeds.txt with locale-appropriate URLs to scrape
# -------------------------------------------------------------------
$testUrls = Get-TestUrlSet -Locale $Locale -IncludeTimestamp | ForEach-Object { $_.url }
$urls = $testUrls
$urls | Set-Content -Path $SeedFile -Encoding UTF8

Write-Host "━━━ Seed file prepared ━━━" -ForegroundColor Cyan
Write-Host "Seeds file ($SeedFile): $($urls.Count) URLs (locale: $(Get-TestLocale -Locale $Locale))" -ForegroundColor Cyan
$urls | ForEach-Object { Write-Host "  $_" -ForegroundColor DarkGray }
Write-Host ''

# -------------------------------------------------------------------
# 2. Open session + swarm create
# -------------------------------------------------------------------
Write-Host "━━━ Opening session ━━━" -ForegroundColor Cyan
$output = Invoke-TrackedCli -Arguments @('open') -Label 'open session' -PassThruOnly
Write-Host ''

Write-Host "━━━ Creating swarm session ━━━" -ForegroundColor Cyan
$output = Invoke-TrackedCli -Arguments @('swarm', 'create') -Label 'swarm create' -PassThruOnly
Write-Host ''

# -------------------------------------------------------------------
# 3. Submit the seed file
# -------------------------------------------------------------------
Write-Host "━━━ Submitting seed file ━━━" -ForegroundColor Cyan
$output = Invoke-TrackedCli -Arguments @('swarm', 'submit', '--seed-file', $SeedFile) -Label 'swarm submit --seed-file' -PassThruOnly
$outputText = ($output | Out-String).Trim()
Write-Host $outputText

$taskIdMatch = [regex]::Match($outputText, 'Task ID:\s*(\S+)')
if (-not $taskIdMatch.Success) {
    Write-Host "❌ Unable to parse Task ID from swarm submit output" -ForegroundColor Red
    Register-CliResult -Label 'swarm submit (parse Task ID)' -ExitCode 1 -ExpectedExitCode 0 `
        -OutputLines @($outputText) -Elapsed ([TimeSpan]::Zero)
    Invoke-TrackedCli -Arguments @('close') -Label 'final close' -PassThruOnly
    $code = Finish-TestSession -ExtraCopilotPrompt "Could not parse Task ID from swarm submit output. Output was: $outputText"
    exit $(if ($code -eq 0) { 1 } else { $code })
}
$taskId = $taskIdMatch.Groups[1].Value
Write-Host "Task ID: $taskId" -ForegroundColor Green
Write-Host ''

# -------------------------------------------------------------------
# 4. Poll for completion
# -------------------------------------------------------------------
Write-Host "━━━ Polling swarm status (task: $taskId, timeout: ${TimeoutSeconds}s) ━━━" -ForegroundColor Cyan

$done = $false
$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
$lastStatus = ''

while ((Get-Date) -lt $deadline) {
    $status = Invoke-TrackedCli -Arguments @('swarm', 'status', $taskId) `
        -Label 'swarm status poll' -PassThruOnly
    $lastStatus = ($status | Out-String).Trim()

    if ($lastStatus -match '"done"\s*:\s*true' -or $lastStatus -match '"isDone"\s*:\s*true' -or $lastStatus -match 'status:\s*done') {
        Write-Host "Task $taskId completed." -ForegroundColor Green
        $done = $true
        break
    }

    Write-Host "  ... waiting" -ForegroundColor DarkGray
    Start-Sleep -Seconds 3
}

if (-not $done) {
    Write-Host "⚠ Task $taskId did not finish within ${TimeoutSeconds}s." -ForegroundColor Yellow
    Register-CliResult -Label "swarm task $taskId (timeout after ${TimeoutSeconds}s)" -ExitCode 1 -ExpectedExitCode 0 `
        -OutputLines @($lastStatus) -Elapsed ([TimeSpan]::FromSeconds($TimeoutSeconds))
}

# -------------------------------------------------------------------
# 5. Retrieve results
# -------------------------------------------------------------------
Write-Host "`n━━━ Retrieving results ━━━" -ForegroundColor Cyan
$result = Invoke-TrackedCli -Arguments @('swarm', 'result', $taskId) -Label 'swarm result' -PassThruOnly
$resultText = ($result | Out-String).Trim()
Write-Host $resultText
Write-Host ''

# -------------------------------------------------------------------
# 6. Cleanup
# -------------------------------------------------------------------
Write-Host "━━━ Cleanup ━━━" -ForegroundColor Cyan
Invoke-TrackedCli -Arguments @('close') -Label 'final close' -PassThruOnly
Write-Host ''

# -------------------------------------------------------------------
# 7. Report
# -------------------------------------------------------------------
if ($done) {
    Write-Host "✅ Stress-swarm test completed successfully." -ForegroundColor Green
} else {
    Write-Host "⚠ Stress-swarm test completed with timeout." -ForegroundColor Yellow
}
$code = Finish-TestSession -ExtraCopilotPrompt "Browser4 CLI stress-swarm-agents test. Task ID: $taskId. SeedFile: $SeedFile"
exit $code
