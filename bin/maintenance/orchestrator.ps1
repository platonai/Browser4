# ═══════════════════════════════════════════════════════════════════
# CROSS-PLATFORM: This script must run on Linux, macOS, and Windows.
# - Use $IsWindows / $IsLinux / $IsMacOS for platform detection.
# - Use "($IsWindows -or $env:OS -eq 'Windows_NT')" for PS 5.1 compat.
# ═══════════════════════════════════════════════════════════════════

<#
.SYNOPSIS
Master maintenance orchestrator. Runs maintenance checks on a configurable
schedule, modeled after coworker/scripts/coworker-scheduler.ps1.

.DESCRIPTION
Loads task definitions from config.psd1 and runs them on their configured
intervals. Supports three execution modes via $env:MAINTENANCE_MODE:
  - "ci"      → single pass, strict, exit 1 on any failure
  - "nightly" → single pass, relaxed, collect all results
  - "dev"     → continuous loop, warn only

.PARAMETER Once
Run a single scheduler pass and exit.

.PARAMETER ConfigPath
Path to a custom config.psd1 file. Defaults to config.psd1 in the same directory.

.PARAMETER Mode
Override the execution mode (ci|nightly|dev). Falls back to $env:MAINTENANCE_MODE.

.EXAMPLE
# One-shot run with default config
.\orchestrator.ps1 -Once

# Continuous monitoring (dev mode)
.\orchestrator.ps1

# CI mode single pass
$env:MAINTENANCE_MODE = "ci"
.\orchestrator.ps1 -Once
#>

param(
    [switch]$Once,
    [string]$ConfigPath,
    [ValidateSet("ci", "nightly", "dev")]
    [string]$Mode
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Continue"

# ── Resolve paths ──
$ScriptDir = $PSScriptRoot
. (Join-Path $ScriptDir "common\MaintenanceUtil.ps1")

# ── Determine mode ──
if ($Mode) {
    $env:MAINTENANCE_MODE = $Mode
}
$runMode = Test-IsMaintenanceMode
Write-MaintenanceLog -Level "INFO" -Component "Orchestrator" -Message "Starting in mode: $runMode"

# ── Load config ──
if (-not $ConfigPath) {
    $ConfigPath = Join-Path $ScriptDir "config.psd1"
}
if (-not (Test-Path $ConfigPath)) {
    Write-MaintenanceLog -Level "ERROR" -Component "Orchestrator" -Message "Config file not found: $ConfigPath"
    exit 1
}
$config = & $ConfigPath
$Scheduler = $config.Scheduler
$Tasks = $config.Tasks | Where-Object { $_.Enabled -eq $true }

Write-MaintenanceLog -Level "INFO" -Component "Orchestrator" -Message "Loaded $($Tasks.Count) enabled tasks"

# ── State tracking ──
$taskStates = @{}
foreach ($task in $Tasks) {
    $taskStates[$task.Name] = @{
        Name           = $task.Name
        LastRun        = [DateTime]::MinValue
        NextRun        = [DateTime]::Now   # Run immediately on first tick
        IsRunning      = $false
        RunCount       = 0
        LastResult     = $null
    }
}

$logDir = Get-MaintenanceLogDir
$repoRoot = Get-RepositoryRoot

# ═══════════════════════════════════════════════════════════════════
# Task runner
# ═══════════════════════════════════════════════════════════════════

function Invoke-MaintenanceTask {
    param($Task, $State)

    $State.IsRunning = $true
    $State.LastRun = [DateTime]::Now

    $scriptPath = Resolve-MaintenancePath $Task.ScriptPath
    if (-not (Test-Path $scriptPath)) {
        Write-MaintenanceLog -Level "ERROR" -Component $Task.Name -Message "Script not found: $scriptPath"
        $State.IsRunning = $false
        return New-MaintenanceResult -CheckId "??" -Name $Task.Name -Status "error" -Details "Script not found: $scriptPath"
    }

    $argsList = if ($Task.Arguments) { $Task.Arguments } else { @() }
    $argString = $argsList -join " "

    try {
        $proc = Start-Process -FilePath "pwsh" `
            -ArgumentList @("-NoProfile", "-File", $scriptPath) + $argsList `
            -WorkingDirectory $repoRoot `
            -NoNewWindow `
            -PassThru `
            -Wait

        $State.RunCount++
        $State.LastResult = if ($proc.ExitCode -eq 0) { "passed" } else { "failed" }
        $State.IsRunning = $false

        return New-MaintenanceResult `
            -CheckId ($Task.Name -replace '^check-','') `
            -Name ($Task.Description ?? $Task.Name) `
            -Status $(if ($proc.ExitCode -eq 0) { "passed" } else { "failed" }) `
            -ExitCode $proc.ExitCode
    }
    catch {
        Write-MaintenanceLog -Level "ERROR" -Component $Task.Name -Message "Failed to start: $($_.Exception.Message)"
        $State.IsRunning = $false
        return New-MaintenanceResult `
            -CheckId ($Task.Name -replace '^check-','') `
            -Name ($Task.Description ?? $Task.Name) `
            -Status "error" `
            -Details $_.Exception.Message
    }
}

# ═══════════════════════════════════════════════════════════════════
# Dependency check
# ═══════════════════════════════════════════════════════════════════

function Test-TaskDependenciesSatisfied {
    param($Task)

    if (-not $Task.DependsOn) { return $true }

    foreach ($dep in $Task.DependsOn) {
        if (-not $taskStates.ContainsKey($dep)) {
            Write-MaintenanceLog -Level "WARN" -Component $Task.Name -Message "Unknown dependency: $dep"
            continue
        }
        $depState = $taskStates[$dep]
        if ($depState.LastResult -eq "failed" -or $depState.LastResult -eq "error") {
            return $false
        }
        if ($depState.RunCount -eq 0) {
            return $false  # Dependency hasn't run yet
        }
    }
    return $true
}

# ═══════════════════════════════════════════════════════════════════
# Main loop
# ═══════════════════════════════════════════════════════════════════

$allResults = @()
$tickSeconds = $Scheduler.TickSeconds
if (-not $tickSeconds) { $tickSeconds = 10 }

do {
    $now = [DateTime]::Now

    foreach ($task in $Tasks) {
        $state = $taskStates[$task.Name]

        # Skip if already running or not yet due
        if ($state.IsRunning) { continue }
        if ($now -lt $state.NextRun) { continue }

        # Check dependencies
        if (-not (Test-TaskDependenciesSatisfied -Task $task)) {
            Write-MaintenanceLog -Level "DEBUG" -Component $task.Name -Message "Dependencies not satisfied, skipping"
            $state.NextRun = $now.AddSeconds($task.IntervalSeconds)
            continue
        }

        Write-MaintenanceLog -Level "INFO" -Component "Orchestrator" -Message "Running: $($task.Name)"

        $result = Invoke-MaintenanceTask -Task $task -State $state
        $allResults += $result

        # Schedule next run
        $state.NextRun = $now.AddSeconds($task.IntervalSeconds)

        # In CI mode, stop on first failure
        if ($runMode -eq "ci" -and ($result.Status -eq "failed" -or $result.Status -eq "error")) {
            Write-MaintenanceLog -Level "ERROR" -Component "Orchestrator" -Message "CI mode: stopping on failure"
            break
        }
    }

    if ($Once) {
        break
    }

    # Sleep until next tick
    Start-Sleep -Seconds $tickSeconds

} while ($true)

# ═══════════════════════════════════════════════════════════════════
# Report
# ═══════════════════════════════════════════════════════════════════

if ($allResults.Count -gt 0) {
    # Console report
    $reporterPath = Join-Path $ScriptDir "reporters\report-console.ps1"
    if (Test-Path $reporterPath) {
        & $reporterPath -Results $allResults
    }

    # JSON report
    $jsonReporterPath = Join-Path $ScriptDir "reporters\report-json.ps1"
    if (Test-Path $jsonReporterPath) {
        & $jsonReporterPath -Results $allResults
    }
}

# ── Final exit code ──
$failures = $allResults | Where-Object { $_.Status -eq "failed" -or $_.Status -eq "error" }
if ($failures.Count -gt 0 -and $runMode -ne "dev") {
    exit 1
}
exit 0
