#!/usr/bin/env pwsh
<#
.SYNOPSIS
Run ALL real-world scenarios from both use-case .txt files and task .md files
with resilience, resume capability, and comprehensive monitoring.

.DESCRIPTION
Orchestrates the complete execution of ~46 real-world browser automation
scenarios. Features:

  - Auto-discovers scenarios from both directories (use-cases + tasks)
  - State persistence for crash recovery and resume
  - Watchdog process for automatic restart on crash
  - Credit exhaustion detection (immediate abort on out-of-money)
  - Consecutive failure detection (abort after N consecutive failures)
  - Per-scenario token usage tracking
  - Live progress display with ETA
  - Final JSON + Markdown reports
  - Ctrl+C graceful shutdown with state save

.PARAMETER Production
Run in production mode (globally installed browser4-cli instead of cargo run).

.PARAMETER Resume
Resume from last saved state. Skips scenarios already marked as 'passed'.

.PARAMETER Force
Ignore existing state and re-run all scenarios from scratch.

.PARAMETER From
Start from a specific scenario ID (inclusive). Skips scenarios before this one.

.PARAMETER To
Run up to a specific scenario ID (inclusive). Skips scenarios after this one.

.PARAMETER NoWatchdog
Do not launch the watchdog process. Run orchestrator standalone.

.PARAMETER Silent
Suppress detailed output from individual scenario runners.

.PARAMETER SkipVersionCheck
Skip the browser4-cli version check.

.PARAMETER MaxConsecutiveFailures
Number of consecutive failures before aborting (default: 5).

.PARAMETER List
List all discovered scenarios and exit without running.

.EXAMPLE
# Run all scenarios in dev mode (with watchdog)
./run-all-scenarios.ps1

.EXAMPLE
# Production mode, resume from previous run
./run-all-scenarios.ps1 -Production -Resume

.EXAMPLE
# Run a subset
./run-all-scenarios.ps1 -From "01-ecommerce" -To "05-cloud-console"

.EXAMPLE
# Standalone (no watchdog)
./run-all-scenarios.ps1 -NoWatchdog

.EXAMPLE
# List all scenarios
./run-all-scenarios.ps1 -List

.NOTES
Exit codes:
  0 - All scenarios passed
  1 - Some scenarios failed
  2 - Critical bug detected (N consecutive failures)
  3 - Credit exhaustion detected
  4 - Watchdog exhausted restart limit
#>

[CmdletBinding()]
param(
    [switch] $Production,

    [switch] $Resume,

    [switch] $Force,

    [string] $From,

    [string] $To,

    [switch] $NoWatchdog,

    [switch] $Silent,

    [switch] $SkipVersionCheck,

    [int] $MaxConsecutiveFailures = 5,

    [switch] $List
)

$ErrorActionPreference = 'Stop'
$script:StartTime = Get-Date

# ═══════════════════════════════════════════════════════════════════════════════
# Dot-source helpers
# ═══════════════════════════════════════════════════════════════════════════════

$ScriptsDir = $PSScriptRoot

# common.ps1 checks $browser4cliMode at load time. It must be defined before
# dot-sourcing because orchestration-common.ps1 enables StrictMode.
$browser4cliMode = if ($Production) { 'production' } else { $null }

. "$ScriptsDir/orchestration-common.ps1"
. "$ScriptsDir/common.ps1"

# ═══════════════════════════════════════════════════════════════════════════════
# Path resolution
# ═══════════════════════════════════════════════════════════════════════════════

$script:RunnerMdPath  = Join-Path $ScriptsDir 'run-task.ps1'
$script:RunnerUcPath  = Join-Path $ScriptsDir 'run-use-case.ps1'
$script:WatchdogPath  = Join-Path $ScriptsDir 'watchdog.ps1'
$script:StateFilePath = Get-OrchestrationStatePath

# ═══════════════════════════════════════════════════════════════════════════════
# Scenario discovery
# ═══════════════════════════════════════════════════════════════════════════════

$script:AllScenarios = Get-AllScenarios

if ($script:AllScenarios.Count -eq 0) {
    Write-Host 'No scenarios discovered. Check that scenario directories exist.' -ForegroundColor Yellow
    exit 0
}

# ── List mode ─────────────────────────────────────────────────────────────────
if ($List) {
    Write-Host ''
    Write-OrchestratorBanner "Discovered Scenarios ($($script:AllScenarios.Count) total)"

    $typeHeaders = @{
        'use-case' = 'Use Cases (.txt)'
        'md-task'  = 'MD Tasks (.md)'
    }
    $lastType = ''
    $index = 0

    foreach ($s in $script:AllScenarios) {
        if ($s.type -ne $lastType) {
            if ($lastType -ne '') { Write-Host '' }
            Write-Host "--- $($typeHeaders[$s.type]) ---" -ForegroundColor Yellow
            $lastType = $s.type
            $index = 0
        }
        $index++
        $levelStr = if ($s.level) { "[$($s.level)]" } else { '' }
        $catStr   = if ($s.category) { "[$($s.category)]" } else { '' }
        $tags = ($levelStr, $catStr | Where-Object { $_ }) -join ' '
        Write-Host "  $($index.ToString().PadLeft(2)). $($s.id)  $tags" -ForegroundColor DarkGray
    }

    Write-Host ''
    Write-Host "Total: $($script:AllScenarios.Count) scenarios" -ForegroundColor Cyan
    Write-Host '  Use Cases:  ' -NoNewline
    Write-Host "$(($script:AllScenarios | Where-Object { $_.type -eq 'use-case' }).Count)" -ForegroundColor Green
    Write-Host '  MD Tasks:   ' -NoNewline
    Write-Host "$(($script:AllScenarios | Where-Object { $_.type -eq 'md-task' }).Count)" -ForegroundColor Green
    exit 0
}

# ═══════════════════════════════════════════════════════════════════════════════
# Filter by -From / -To
# ═══════════════════════════════════════════════════════════════════════════════

$script:SelectedScenarios = $script:AllScenarios

if ($From) {
    $fromFound = $false
    $script:SelectedScenarios = @($script:SelectedScenarios | Where-Object {
        if (-not $fromFound -and $_.id -eq $From) { $fromFound = $true }
        $fromFound
    })
    if (-not $fromFound) {
        Write-Host "WARNING: -From scenario '$From' not found." -ForegroundColor Yellow
    }
}

if ($To) {
    $script:SelectedScenarios = @($script:SelectedScenarios | Where-Object {
        if ($_.id -eq $To) {
            $script:_toFound = $true
            $true
            return
        }
        -not $script:_toFound
    })
}

if ($script:SelectedScenarios.Count -eq 0) {
    Write-Host 'No scenarios match the specified filters.' -ForegroundColor Yellow
    exit 0
}

# ═══════════════════════════════════════════════════════════════════════════════
# State initialization / resume
# ═══════════════════════════════════════════════════════════════════════════════

$mode = if ($Production) { 'production' } else { 'dev' }

if ($Force) {
    # Force fresh state
    if (Test-Path $script:StateFilePath) {
        Remove-Item $script:StateFilePath -Force
        Write-Host 'Removed existing state file (-Force).' -ForegroundColor Yellow
    }
    $script:State = Initialize-OrchestrationState -Scenarios $script:SelectedScenarios -Mode $mode -Force
}
elseif ($Resume -or (Test-Path $script:StateFilePath)) {
    $script:State = Read-OrchestrationState -StateFilePath $script:StateFilePath
    if ($script:State) {
        Write-Host "Resuming from existing state (updated: $($script:State.updatedAt))." -ForegroundColor Cyan
        Write-Host "  Previously: $($script:State.orchestrator.completedScenarios)/$($script:State.orchestrator.totalScenarios) completed, $($script:State.orchestrator.passed) passed, $($script:State.orchestrator.failed) failed" -ForegroundColor DarkGray

        # Handle scenarios that were 'running' when we crashed -- re-run them
        $stuckCount = 0
        foreach ($s in $script:State.scenarios) {
            if ($s.status -eq 'running') {
                $s.status = 'pending'
                $s.attempts = [Math]::Max(0, $s.attempts - 1)  # Don't count the crash as an attempt
                $stuckCount++
            }
        }
        if ($stuckCount -gt 0) {
            Write-Host "  $stuckCount scenario(s) were in 'running' state (likely crashed mid-run) -- reset to 'pending'." -ForegroundColor Yellow
        }
    }
    else {
        Write-Host 'State file corrupt or incompatible version. Creating fresh state.' -ForegroundColor Yellow
        $script:State = Initialize-OrchestrationState -Scenarios $script:SelectedScenarios -Mode $mode -Force
    }
}
else {
    $script:State = Initialize-OrchestrationState -Scenarios $script:SelectedScenarios -Mode $mode
}

if (-not $script:State) {
    Write-Host 'ERROR: Failed to initialize orchestration state.' -ForegroundColor Red
    exit 1
}

# ═══════════════════════════════════════════════════════════════════════════════
# Pre-flight checks
# ═══════════════════════════════════════════════════════════════════════════════

$scenarioAgentAvailable = $null -ne (Get-Command claude -ErrorAction SilentlyContinue) -or
    $null -ne (Get-Command kimi -ErrorAction SilentlyContinue)
if (-not $scenarioAgentAvailable) {
    Write-Host 'ERROR: no agent CLI found on PATH. Install Claude Code or Kimi Code to run scenarios.' -ForegroundColor Red
    exit 1
}

if (-not (Test-Path -LiteralPath $script:RunnerMdPath -PathType Leaf)) {
    Write-Host "ERROR: MD task runner not found: $script:RunnerMdPath" -ForegroundColor Red
    exit 1
}
if (-not (Test-Path -LiteralPath $script:RunnerUcPath -PathType Leaf)) {
    Write-Host "ERROR: Use-case runner not found: $script:RunnerUcPath" -ForegroundColor Red
    exit 1
}

if (-not $SkipVersionCheck) {
    $versionStatus = Assert-Browser4CliLatest -Silent:$Silent
    if ($versionStatus -ne 0) {
        Write-Host 'Run with -SkipVersionCheck to bypass this check.' -ForegroundColor DarkGray
        exit $versionStatus
    }
}

# Update state with current PID (needed for watchdog)
$script:State.orchestrator.pid = $pid
$script:State.orchestrator.mode = $mode
$script:State.orchestrator.startedAt = (Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssK')
Write-OrchestrationState -State $script:State -StateFilePath $script:StateFilePath

# ═══════════════════════════════════════════════════════════════════════════════
# Ctrl+C graceful shutdown
# ═══════════════════════════════════════════════════════════════════════════════

$script:CtrlCPressed = $false
$null = [Console]::CancelKeyPress.Add({
    param($sender, $e)
    $e.Cancel = $true
    $script:CtrlCPressed = $true
    Write-Host "`n`nCtrl+C detected. Finishing current scenario then saving state..." -ForegroundColor Yellow
    Write-Host 'Press Ctrl+C again to force quit.' -ForegroundColor Yellow
})

# ═══════════════════════════════════════════════════════════════════════════════
# Launch watchdog
# ═══════════════════════════════════════════════════════════════════════════════

$script:WatchdogProcess = $null

if (-not $NoWatchdog) {
    if (Test-Path -LiteralPath $script:WatchdogPath -PathType Leaf) {
        $watchdogArgs = @(
            '-NoProfile', '-ExecutionPolicy', 'Bypass',
            '-File', $script:WatchdogPath,
            '-OrchestratorPid', $pid,
            '-StateFilePath', $script:StateFilePath,
            '-PollIntervalMs', 15000,
            '-StaleThresholdSec', 90,
            '-MaxRestarts', 3
        )

        try {
            $watchdogPsi = [System.Diagnostics.ProcessStartInfo]@{
                FileName               = 'pwsh'
                Arguments              = $watchdogArgs -join ' '
                UseShellExecute        = $false
                CreateNoWindow         = $true
                RedirectStandardOutput = $true
                RedirectStandardError  = $true
            }
            $script:WatchdogProcess = [System.Diagnostics.Process]::Start($watchdogPsi)
            Write-Host "Watchdog launched (PID: $($script:WatchdogProcess.Id))." -ForegroundColor DarkGray
        }
        catch {
            Write-Host "WARNING: Could not launch watchdog: $_" -ForegroundColor Yellow
            Write-Host '  Continuing without watchdog protection.' -ForegroundColor DarkGray
        }
    }
    else {
        Write-Host "WARNING: Watchdog script not found at $script:WatchdogPath" -ForegroundColor Yellow
        Write-Host '  Continuing without watchdog protection.' -ForegroundColor DarkGray
    }
}

# ═══════════════════════════════════════════════════════════════════════════════
# Ensure output directories exist
# ═══════════════════════════════════════════════════════════════════════════════

foreach ($dir in @($script:ReportsOutputDir, $script:ScenariosOutputDir, $script:StateDir)) {
    if (-not (Test-Path $dir)) {
        New-Item -ItemType Directory -Path $dir -Force | Out-Null
    }
}

# ═══════════════════════════════════════════════════════════════════════════════
# Main execution loop
# ═══════════════════════════════════════════════════════════════════════════════

$bannerTitle = "Orchestrated Scenario Run ($($script:SelectedScenarios.Count) scenarios)"
$bannerTitle += if ($mode -eq 'production') { " [PRODUCTION]" } else { " [DEV]" }
Write-OrchestratorBanner $bannerTitle

Write-ProgressStatus -State $script:State -StartTime $script:StartTime

$scenariosToRun = @($script:State.scenarios | Where-Object {
    $_.status -in @('pending', 'failed')  # Re-run previously failed scenarios too
})

# Filter to match SelectedScenarios (handles -From / -To)
$selectedIds = @($script:SelectedScenarios | ForEach-Object { $_.id })
$scenariosToRun = @($scenariosToRun | Where-Object { $_.id -in $selectedIds })

if ($scenariosToRun.Count -eq 0) {
    Write-Host ''
    Write-Host 'All scenarios already completed. Nothing to run.' -ForegroundColor Green
    Write-Host 'Use -Force to re-run all scenarios from scratch.' -ForegroundColor DarkGray
    goto CLEANUP
}

Write-Host ''
Write-Host "Running $($scenariosToRun.Count) scenario(s)..." -ForegroundColor Cyan
Write-Host ''

$abortRun = $false
$abortExitCode = 0

foreach ($scenario in $scenariosToRun) {
    # ── Check Ctrl+C ──────────────────────────────────────────────────────
    if ($script:CtrlCPressed) {
        Write-Host '`nRun interrupted by user. Saving state...' -ForegroundColor Yellow
        $abortRun = $true
        $abortExitCode = 0
        break
    }

    # ── Determine runner ──────────────────────────────────────────────────
    if ($scenario.type -eq 'md-task') {
        $runnerPath = $script:RunnerMdPath
    }
    else {
        $runnerPath = $script:RunnerUcPath
    }

    # Find the absolute source file path
    $sourceAbsPath = Join-Path $script:RepoRoot $scenario.sourceFile
    if (-not (Test-Path -LiteralPath $sourceAbsPath -PathType Leaf)) {
        Write-Host "  WARNING: Source file not found: $sourceAbsPath -- skipping." -ForegroundColor Yellow
        Update-ScenarioState -State $script:State -ScenarioId $scenario.id -Status 'skipped' -Fields @{
            errorSummary = "Source file not found: $sourceAbsPath"
        }
        continue
    }

    # ── Prepare scenario output paths ─────────────────────────────────────
    $timestamp = (Get-Date).ToUniversalTime().ToString('yyyyMMdd-HHmmss')
    $safeId = $scenario.id -replace '[\\/:*?"<>|]', '_'
    $rawOutputFile = Join-Path $script:ScenariosOutputDir "$timestamp-$safeId.raw.md"

    # ── Update state to 'running' ─────────────────────────────────────────
    $nowStr = (Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssK')
    Update-ScenarioState -State $script:State -ScenarioId $scenario.id -Status 'running' -Fields @{
        startedAt   = $nowStr
        attempts    = $scenario.attempts + 1
        rawOutputFile = $rawOutputFile
    }

    Write-ProgressStatus -State $script:State -StartTime $script:StartTime `
        -CurrentScenario $scenario -CurrentAction 'starting'

    # ── Run the scenario as a sub-process ─────────────────────────────────
    $scenarioStart = Get-Date

    $runnerArgs = @(
        '-NoProfile', '-ExecutionPolicy', 'Bypass',
        '-File', $runnerPath,
        '-TaskFile', $sourceAbsPath
    )
    if ($Silent) {
        $runnerArgs += '-Silent'
    }
    if ($SkipVersionCheck) {
        $runnerArgs += '-SkipVersionCheck'
    }

    if ($Production) {
        $env:BROWSER4CLI_MODE = 'production'
    }

    $exitCode = 0
    $capturedOutput = ''

    try {
        # Run the sub-process and capture output
        $tempFile = Join-Path $script:ReportsRoot "temp-$safeId-$pid.txt"
        $tempDir = Split-Path $tempFile -Parent
        if (-not (Test-Path $tempDir)) {
            New-Item -ItemType Directory -Path $tempDir -Force | Out-Null
        }

        # Use Start-NativeCommand-style capture from common.ps1
        $capturedOutput = & {
            $prevEncoding = [Console]::OutputEncoding
            [Console]::OutputEncoding = [System.Text.Encoding]::UTF8
            try {
                $output = & pwsh @runnerArgs 2>&1 | ForEach-Object {
                    $line = "$_"
                    if (-not $Silent) {
                        # Stream to console but dimmed (scenario output)
                        Write-Host "    $line" -ForegroundColor DarkGray
                    }
                    $line
                }
                $output -join "`n"
            }
            finally {
                [Console]::OutputEncoding = $prevEncoding
            }
        }
        # Check if $capturedOutput is actually an array from ForEach-Object
        if ($capturedOutput -is [array]) {
            $capturedOutput = $capturedOutput -join "`n"
        }
        $exitCode = $LASTEXITCODE
    }
    catch {
        $exitCode = 1
        $capturedOutput = "Exception: $_"
        Write-Host "    ERROR running scenario: $_" -ForegroundColor Red
    }

    $scenarioDuration = [math]::Round(((Get-Date) - $scenarioStart).TotalMilliseconds)

    # ── Save raw output ───────────────────────────────────────────────────
    if ($capturedOutput) {
        $outputDir = Split-Path $rawOutputFile -Parent
        if (-not (Test-Path $outputDir)) {
            New-Item -ItemType Directory -Path $outputDir -Force | Out-Null
        }
        $utf8NoBom = [System.Text.UTF8Encoding]::new($false)
        [System.IO.File]::WriteAllText($rawOutputFile, $capturedOutput, $utf8NoBom)
    }

    # ── Parse token usage ─────────────────────────────────────────────────
    $tokenUsage = ConvertFrom-TokenUsage -Output $capturedOutput
    if ($tokenUsage.byModel.Count -gt 0) {
        Merge-TokenUsage -State $script:State -TokenUsage $tokenUsage
    }

    # Calculate per-scenario token totals
    $scenarioTokens = @{
        byModel     = $tokenUsage.byModel
        totalInput  = ($tokenUsage.byModel.Values | Measure-Object -Property input  -Sum).Sum
        totalOutput = ($tokenUsage.byModel.Values | Measure-Object -Property output -Sum).Sum
        totalCached = ($tokenUsage.byModel.Values | Measure-Object -Property cached -Sum).Sum
    }

    # ── Check credit exhaustion ───────────────────────────────────────────
    $creditCheck = Test-CreditExhaustion -Output $capturedOutput -ExitCode $exitCode
    if ($creditCheck.detected) {
        Write-Host ''
        Write-Host ('=' * 72) -ForegroundColor Red
        Write-Host '  CREDIT EXHAUSTION DETECTED' -ForegroundColor Red
        Write-Host "  $($creditCheck.reason)" -ForegroundColor Red
        Write-Host ('=' * 72) -ForegroundColor Red
        Write-Host ''

        $script:State.orchestrator.globalAbort = $true
        $script:State.orchestrator.abortReason = $creditCheck.reason
        $script:State.orchestrator.exitCode = 3

        # Still record this scenario's failure
        $errorSummary = "Credit exhaustion: $($creditCheck.matchedPattern)"
        Update-ScenarioState -State $script:State -ScenarioId $scenario.id -Status 'failed' -Fields @{
            completedAt  = (Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssK')
            durationMs   = $scenarioDuration
            exitCode     = $exitCode
            errorSummary = $errorSummary
            tokens       = [PSCustomObject]$scenarioTokens
            rawOutputFile= $rawOutputFile
        }
        Write-ScenarioComplete -Scenario $scenario -ExitCode $exitCode -DurationMs $scenarioDuration -TokenUsage $tokenUsage

        Write-OrchestrationState -State $script:State -StateFilePath $script:StateFilePath
        $abortRun = $true
        $abortExitCode = 3
        break
    }

    # ── Update consecutive failure counter ────────────────────────────────
    if ($exitCode -ne 0) {
        $script:State.orchestrator.consecutiveFailures++
        $errorSummary = "Exit code: $exitCode"
        # Try to extract a meaningful error from output
        if ($capturedOutput -match '(?s)(?:Error|ERROR|error):\s*(.+?)(?:\n|$)') {
            $errorSummary = $Matches[1].Trim()
            if ($errorSummary.Length -gt 200) {
                $errorSummary = $errorSummary.Substring(0, 200) + '...'
            }
        }
    }
    else {
        $script:State.orchestrator.consecutiveFailures = 0
        $errorSummary = $null
    }

    # ── Check for consecutive failure abort ───────────────────────────────
    if ($script:State.orchestrator.consecutiveFailures -ge $MaxConsecutiveFailures) {
        Write-Host ''
        Write-Host ('=' * 72) -ForegroundColor Red
        Write-Host "  CRITICAL BUG: $MaxConsecutiveFailures consecutive failures" -ForegroundColor Red
        Write-Host '  Aborting run -- a systemic issue likely exists.' -ForegroundColor Red
        Write-Host ('=' * 72) -ForegroundColor Red
        Write-Host ''

        $script:State.orchestrator.globalAbort = $true
        $script:State.orchestrator.abortReason = "$MaxConsecutiveFailures consecutive failures (critical bug)"
        $script:State.orchestrator.exitCode = 2

        $status = if ($exitCode -eq 0) { 'passed' } else { 'failed' }
        Update-ScenarioState -State $script:State -ScenarioId $scenario.id -Status $status -Fields @{
            completedAt  = (Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssK')
            durationMs   = $scenarioDuration
            exitCode     = $exitCode
            errorSummary = $errorSummary
            tokens       = [PSCustomObject]$scenarioTokens
            rawOutputFile= $rawOutputFile
        }
        Write-ScenarioComplete -Scenario $scenario -ExitCode $exitCode -DurationMs $scenarioDuration -TokenUsage $tokenUsage

        Write-OrchestrationState -State $script:State -StateFilePath $script:StateFilePath
        $abortRun = $true
        $abortExitCode = 2
        break
    }

    # ── Update scenario state ─────────────────────────────────────────────
    $status = if ($exitCode -eq 0) { 'passed' } else { 'failed' }
    Update-ScenarioState -State $script:State -ScenarioId $scenario.id -Status $status -Fields @{
        completedAt  = (Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssK')
        durationMs   = $scenarioDuration
        exitCode     = $exitCode
        errorSummary = $errorSummary
        tokens       = [PSCustomObject]$scenarioTokens
        rawOutputFile= $rawOutputFile
    }

    Write-ScenarioComplete -Scenario $scenario -ExitCode $exitCode -DurationMs $scenarioDuration -TokenUsage $tokenUsage

    # ── Periodic heartbeat ────────────────────────────────────────────────
    Update-OrchestratorHeartbeat -State $script:State
}

# ═══════════════════════════════════════════════════════════════════════════════
# Final state update
# ═══════════════════════════════════════════════════════════════════════════════

CLEANUP:

if (-not $abortRun) {
    $script:State.orchestrator.exitCode = if ($script:State.orchestrator.failed -gt 0) { 1 } else { 0 }
}
Write-OrchestrationState -State $script:State -StateFilePath $script:StateFilePath

# ═══════════════════════════════════════════════════════════════════════════════
# Final report
# ═══════════════════════════════════════════════════════════════════════════════

$runTimestamp = (Get-Date).ToUniversalTime().ToString('yyyyMMdd-HHmmss')
$jsonReportPath = Join-Path $script:ReportsOutputDir "$runTimestamp-run-full.json"
$mdReportPath   = Join-Path $script:ReportsOutputDir "$runTimestamp-run-full.md"

Write-FinalReport -State $script:State -JsonPath $jsonReportPath -MarkdownPath $mdReportPath `
    -StartTime $script:StartTime

# ═══════════════════════════════════════════════════════════════════════════════
# Console summary
# ═══════════════════════════════════════════════════════════════════════════════

$totalDuration = (Get-Date) - $script:StartTime
$o = $script:State.orchestrator

Write-Host ''
Write-OrchestratorBanner 'Run Complete'

Write-Host ''
Write-Host "  Scenarios:  $($o.completedScenarios)/$($o.totalScenarios) completed" -ForegroundColor Cyan
Write-Host "  Passed:     $($o.passed)" -ForegroundColor Green
if ($o.failed -gt 0) {
    Write-Host "  Failed:     $($o.failed)" -ForegroundColor Red
}
else {
    Write-Host "  Failed:     0" -ForegroundColor Green
}
if ($o.skipped -gt 0) {
    Write-Host "  Skipped:    $($o.skipped)" -ForegroundColor DarkYellow
}
Write-Host "  Duration:   $(Format-Duration $totalDuration)" -ForegroundColor Cyan

if ($o.tokenTotals.grandTotalInput -gt 0) {
    Write-Host "  Tokens:     $(Format-TokenCount $o.tokenTotals.grandTotalInput) in / $(Format-TokenCount $o.tokenTotals.grandTotalOutput) out / $(Format-TokenCount $o.tokenTotals.grandTotalCached) cached" -ForegroundColor Cyan
}

if ($abortRun) {
    Write-Host ''
    Write-Host "  Run was ABORTED: $($o.abortReason)" -ForegroundColor Red
}

Write-Host ''
Write-Host "  Reports:" -ForegroundColor DarkGray
Write-Host "    JSON: $jsonReportPath" -ForegroundColor DarkGray
Write-Host "    MD:   $mdReportPath" -ForegroundColor DarkGray
Write-Host "    State: $script:StateFilePath" -ForegroundColor DarkGray

# ── Print failed scenarios ────────────────────────────────────────────────────
$failures = @($script:State.scenarios | Where-Object { $_.status -eq 'failed' })
if ($failures.Count -gt 0) {
    Write-Host ''
    Write-Host '  Failed Scenarios:' -ForegroundColor Red
    foreach ($f in $failures) {
        $dur = if ($f.durationMs -gt 0) { Format-Duration ([TimeSpan]::FromMilliseconds($f.durationMs)) } else { 'N/A' }
        $err = if ($f.errorSummary) { " -- $($f.errorSummary)" } else { '' }
        Write-Host "    [FAIL] $($f.id) ($dur)$err" -ForegroundColor Red
    }
}

Write-Host ''

# ── Determine final exit code ─────────────────────────────────────────────────
if ($abortExitCode -ne 0) {
    $finalExitCode = $abortExitCode
}
elseif ($o.failed -gt 0) {
    $finalExitCode = 1
}
else {
    $finalExitCode = 0
}

# ── Clean up watchdog ─────────────────────────────────────────────────────────
if ($script:WatchdogProcess) {
    try {
        if (-not $script:WatchdogProcess.HasExited) {
            # Give watchdog a moment to see our exit, then kill it
            Start-Sleep -Seconds 2
            $script:WatchdogProcess.Kill()
        }
    }
    catch {
        # Process already exited -- fine
    }
    Write-Host "Watchdog process terminated." -ForegroundColor DarkGray
}

exit $finalExitCode
