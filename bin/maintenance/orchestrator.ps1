# ===================================================================
# CROSS-PLATFORM: This script must run on Linux, macOS, and Windows.
# - Use $IsWindows / $IsLinux / $IsMacOS for platform detection.
# - Use "($IsWindows -or $env:OS -eq 'Windows_NT')" for PS 5.1 compat.
# ===================================================================

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

.PARAMETER Force
Bypass state-based scheduling: run every task regardless of when it last ran.
In CI mode, Force is implied automatically.

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
    [switch]$Force,
    [string]$ConfigPath,
    [ValidateSet("ci", "nightly", "dev")]
    [string]$Mode
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Continue"

# ── Resolve paths ──
$ScriptDir = $PSScriptRoot
. (Join-Path $ScriptDir "common\MaintenanceUtil.ps1")
. (Join-Path $ScriptDir "common\MaintenanceState.ps1")

# ── Determine mode ──
if ($Mode) {
    $env:MAINTENANCE_MODE = $Mode
}
$runMode = Test-IsMaintenanceMode
if ($runMode -eq "ci") {
    $Force = $true
    Write-MaintenanceLog -Level "INFO" -Component "Orchestrator" -Message "CI mode: Force-run enabled (state bypassed)"
}
Write-MaintenanceLog -Level "INFO" -Component "Orchestrator" -Message "Starting in mode: $runMode"

# ── Load config ──
if (-not $ConfigPath) {
    $ConfigPath = Join-Path $ScriptDir "config.psd1"
}
if (-not (Test-Path $ConfigPath)) {
    Write-MaintenanceLog -Level "ERROR" -Component "Orchestrator" -Message "Config file not found: $ConfigPath"
    exit 1
}
$config = Import-PowerShellDataFile -Path $ConfigPath
$Scheduler = $config.Scheduler
$Tasks = $config.Tasks | Where-Object { $_.Enabled -eq $true }

Write-MaintenanceLog -Level "INFO" -Component "Orchestrator" -Message "Loaded $($Tasks.Count) enabled tasks"

# ── Persistent state ──
$persistedState = Read-MaintenanceState
if ($null -eq $persistedState) {
    Initialize-MaintenanceState -PassThru | Out-Null
    $persistedState = Read-MaintenanceState
}
Write-MaintenanceLog -Level "INFO" -Component "Orchestrator" `
    -Message "State loaded: $(@($persistedState.Tasks.PSObject.Properties).Count) task(s) have run history"

# ── Runtime state tracking (hydrated from persistent state) ──
$taskStates = @{}
foreach ($task in $Tasks) {
    $lastRun = [DateTime]::MinValue
    $runCount = 0
    $lastResult = $null

    $stateTaskNames = @($persistedState.Tasks.PSObject.Properties | ForEach-Object { $_.Name })
    if ($stateTaskNames -contains $task.Name) {
        $entry = $persistedState.Tasks.($task.Name)
        if ($entry.lastRun) {
            try { $lastRun = [DateTime]::Parse($entry.lastRun) } catch { }
        }
        if ($entry.runCount) {
            $runCount = $entry.runCount
        }
        if ($entry.lastResult) {
            $lastResult = $entry.lastResult
        }
    }

    # In Force mode, always run immediately; otherwise respect last-run interval
    if ($Force) {
        $nextRun = [DateTime]::Now
    }
    elseif ($lastRun -ne [DateTime]::MinValue) {
        $nextRun = $lastRun.AddSeconds($task.IntervalSeconds)
        if ($nextRun -lt [DateTime]::Now) {
            $nextRun = [DateTime]::Now  # Interval elapsed — due now
        }
        else {
            Write-MaintenanceLog -Level "INFO" -Component "Orchestrator" `
                -Message "$($task.Name): last ran $lastRun, next run at $nextRun (skipping for now)"
        }
    }
    else {
        $nextRun = [DateTime]::Now  # Never run before — run immediately
    }

    $taskStates[$task.Name] = @{
        Name           = $task.Name
        LastRun        = $lastRun
        NextRun        = $nextRun
        IsRunning      = $false
        RunCount       = $runCount
        LastResult     = $lastResult
    }
}

$logDir = Get-MaintenanceLogDir
$repoRoot = Get-RepositoryRoot

# ===================================================================
# Task runner
# ===================================================================

function Invoke-MaintenanceTask {
    param($Task, $State)

    $State.IsRunning = $true
    $State.LastRun = [DateTime]::Now

    $scriptPath = Resolve-MaintenancePath $Task.ScriptPath
    if (-not (Test-Path $scriptPath)) {
        Write-MaintenanceLog -Level "ERROR" -Component $Task.Name -Message "Script not found: $scriptPath"
        $State.IsRunning = $false
        return New-MaintenanceResult -CheckId "UNKNOWN" -Name $Task.Name -Status "error" -Details "Script not found: $scriptPath"
    }

    $argsList = if ($Task.Arguments) { $Task.Arguments } else { @() }

    try {
        $timer = [System.Diagnostics.Stopwatch]::StartNew()

        # Run the check script in-process to capture its full result object
        $checkResult = & $scriptPath @argsList
        $timer.Stop()

        # Record actual wall-clock duration
        if ($checkResult -and ($checkResult.PSObject.Properties.Name -contains 'DurationMs')) {
            $checkResult.DurationMs = $timer.ElapsedMilliseconds
        }

        $State.RunCount++
        $State.LastResult = if ($checkResult.Status) { $checkResult.Status } else { "passed" }
        $State.IsRunning = $false

        if (-not $checkResult) {
            return New-MaintenanceResult `
                -CheckId ($Task.Name -replace '^check-','') `
                -Name ($Task.Description ?? $Task.Name) `
                -Status "failed" `
                -Details "Check script returned no result object"
        }
        return $checkResult
    }
    catch {
        $State.RunCount++
        $State.IsRunning = $false
        $State.LastResult = "error"
        Write-MaintenanceLog -Level "ERROR" -Component $Task.Name -Message "Failed: $($_.Exception.Message)"
        return New-MaintenanceResult `
            -CheckId ($Task.Name -replace '^check-','') `
            -Name ($Task.Description ?? $Task.Name) `
            -Status "error" `
            -Details $_.Exception.Message
    }
}

# ===================================================================
# Dependency check
# ===================================================================

function Test-TaskDependenciesSatisfied {
    param($Task)

    $hasDepends = $null -ne ($Task.PSObject.Properties | Where-Object { $_.Name -eq 'DependsOn' })
    if (-not $hasDepends -or -not $Task.DependsOn) { return $true }

    foreach ($dep in $Task.DependsOn) {
        if (-not $taskStates.ContainsKey($dep)) {
            Write-MaintenanceLog -Level "WARN" -Component $Task.Name -Message "Unknown dependency: $dep"
            continue
        }

        # Check session results first (current orchestrator run)
        if ($sessionTaskResults.ContainsKey($dep)) {
            $sessionResult = $sessionTaskResults[$dep]
            if ($sessionResult.Status -eq "failed" -or $sessionResult.Status -eq "error") {
                Write-MaintenanceLog -Level "DEBUG" -Component $Task.Name `
                    -Message "Dependency '$dep' failed in current session, blocking"
                return $false
            }
            # Dependency passed in this session — allow
            continue
        }

        # Dependency hasn't run in this session yet.
        # Check persistent state: if it has never run, block.
        $depState = $taskStates[$dep]
        if ($depState.RunCount -eq 0) {
            return $false
        }
        # If the dependency failed in a previous run, don't block —
        # the code may have been fixed since then. Warn and allow.
        if ($depState.LastResult -eq "failed" -or $depState.LastResult -eq "error") {
            Write-MaintenanceLog -Level "WARN" -Component $Task.Name `
                -Message "Dependency '$dep' failed in a previous run (not current session), allowing to proceed"
        }
    }
    return $true
}

# ===================================================================
# Main loop
# ===================================================================

$allResults = @()
$sessionTaskResults = @{}  # Tracks results from current orchestrator run for dependency resolution
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
        $sessionTaskResults[$task.Name] = $result  # Track for dependency resolution

        # Persist updated state so other team members / processes see this run
        Update-MaintenanceTaskState -TaskName $task.Name -Result $result -State $persistedState
        # Refresh the in-memory persisted state after write
        $persistedState = Read-MaintenanceState

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

# ===================================================================
# Report
# ===================================================================

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

    # Summary report
    $summaryReporterPath = Join-Path $ScriptDir "reporters\report-summary.ps1"
    if (Test-Path $summaryReporterPath) {
        & $summaryReporterPath -Results $allResults
    }
}

# ── Final exit code ──
$failures = $allResults | Where-Object { $_.Status -eq "failed" -or $_.Status -eq "error" }
if ($failures.Count -gt 0 -and $runMode -ne "dev") {
    exit 1
}
exit 0
