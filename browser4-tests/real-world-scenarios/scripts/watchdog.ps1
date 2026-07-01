#!/usr/bin/env pwsh
<#
.SYNOPSIS
Watchdog process that monitors the orchestration runner and restarts it on crash.

.DESCRIPTION
Launched by run-all-scenarios.ps1 (or independently). Monitors the orchestrator
process by PID and heartbeat freshness. If the orchestrator crashes or hangs,
restarts it up to MaxRestarts times.

Exit codes are relayed from the orchestrator:
  0 - All passed
  1 - Some failed
  2 - Critical bug (consecutive failures)
  3 - Credit exhaustion

If the orchestrator exits with any of these clean codes, the watchdog does NOT
restart it. Only unexpected crashes/terminations trigger a restart.

.PARAMETER OrchestratorPid
PID of the orchestrator process to monitor.

.PARAMETER StateFilePath
Path to the orchestration state JSON file (for heartbeat checks).

.PARAMETER PollIntervalMs
How often to check the orchestrator, in milliseconds (default: 15000).

.PARAMETER StaleThresholdSec
How long without a heartbeat before the orchestrator is considered hung
(default: 90).

.PARAMETER MaxRestarts
Maximum number of times to restart the orchestrator after crashes
(default: 3).

.PARAMETER OrchestratorCommand
The pwsh command to re-launch the orchestrator. Defaults to re-running
run-all-scenarios.ps1 with the same arguments.

.EXAMPLE
# Launched automatically by run-all-scenarios.ps1:
./watchdog.ps1 -OrchestratorPid 12345 -StateFilePath "target/test-reports/state/orchestration-state.json"

.EXAMPLE
# Manual launch:
./watchdog.ps1 -OrchestratorPid 9999 -StateFilePath "target/test-reports/state/orchestration-state.json" -MaxRestarts 1
#>

[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [int] $OrchestratorPid,

    [Parameter(Mandatory = $true)]
    [string] $StateFilePath,

    [int] $PollIntervalMs = 15000,

    [int] $StaleThresholdSec = 90,

    [int] $MaxRestarts = 3,

    [string[]] $OrchestratorCommand = @()
)

$ErrorActionPreference = 'Stop'

# ── Write a timestamped log line ──────────────────────────────────────────────
function Write-WatchdogLog {
    param(
        [string] $Message,
        [string] $Level = 'INFO'
    )
    $ts = Get-Date -Format 'yyyy-MM-dd HH:mm:ss'
    $color = switch ($Level) {
        'ERROR' { 'Red' }
        'WARN'  { 'Yellow' }
        'INFO'  { 'Cyan' }
        default { 'White' }
    }
    Write-Host "[$ts] [WD:$Level] $Message" -ForegroundColor $color
}

# ── Validate inputs ───────────────────────────────────────────────────────────
if (-not (Test-Path $StateFilePath)) {
    Write-WatchdogLog "State file not found: $StateFilePath (will wait for orchestrator to create it)" -Level 'WARN'
}

# Find the orchestrator script path (same directory as this script)
$script:WatchdogDir = $PSScriptRoot
$script:OrchestratorScript = Join-Path $script:WatchdogDir 'run-all-scenarios.ps1'

# ═══════════════════════════════════════════════════════════════════════════════
# Main monitoring logic
# ═══════════════════════════════════════════════════════════════════════════════

$cleanExitCodes = @(0, 1, 2, 3)  # Exit codes that should NOT trigger restart
$restartCount = 0
$lastHeartbeatCheck = Get-Date

Write-WatchdogLog "Watchdog started. Monitoring PID: $OrchestratorPid" -Level 'INFO'
Write-WatchdogLog "  Poll interval: ${PollIntervalMs}ms, Stale threshold: ${StaleThresholdSec}s, Max restarts: $MaxRestarts" -Level 'INFO'
Write-Host ''

# Give the orchestrator a moment to start up before first check
Start-Sleep -Seconds 5

while ($true) {
    $now = Get-Date

    # ── Check if orchestrator process is alive ────────────────────────────
    $processAlive = $false
    try {
        $proc = Get-Process -Id $OrchestratorPid -ErrorAction SilentlyContinue
        if ($proc -and -not $proc.HasExited) {
            $processAlive = $true
        }
    }
    catch {
        $processAlive = $false
    }

    # ── Check heartbeat from state file ───────────────────────────────────
    $heartbeatFresh = $false
    $stateObj = $null
    $globalAbort = $false

    if (Test-Path $StateFilePath) {
        try {
            $raw = Get-Content $StateFilePath -Raw -Encoding UTF8 -ErrorAction SilentlyContinue
            if ($raw) {
                $stateObj = $raw | ConvertFrom-Json -ErrorAction SilentlyContinue
                if ($stateObj -and $stateObj.orchestrator.heartbeat) {
                    try {
                        $hb = [DateTime]::Parse($stateObj.orchestrator.heartbeat)
                        $age = ($now - $hb).TotalSeconds
                        if ($age -le $StaleThresholdSec) {
                            $heartbeatFresh = $true
                        }
                        $lastHeartbeatCheck = $now
                    }
                    catch {
                        # Can't parse heartbeat -- stale
                    }
                }
                if ($stateObj -and $stateObj.orchestrator.globalAbort) {
                    $globalAbort = $true
                }
            }
        }
        catch {
            # State file may be mid-write -- skip this check
        }
    }

    # ── Global abort: don't restart, exit with orchestrator's exit code ───
    if ($globalAbort) {
        $exitCode = if ($stateObj.orchestrator.exitCode) { $stateObj.orchestrator.exitCode } else { 1 }
        Write-WatchdogLog "Global abort detected: $($stateObj.orchestrator.abortReason)" -Level 'WARN'
        Write-WatchdogLog "Exiting with code $exitCode." -Level 'INFO'

        # Kill orchestrator if still alive
        if ($processAlive) {
            try {
                Stop-Process -Id $OrchestratorPid -Force -ErrorAction SilentlyContinue
                Write-WatchdogLog "Killed orchestrator process (global abort)." -Level 'INFO'
            }
            catch { }
        }
        exit $exitCode
    }

    # ── Process is alive and heartbeat is fresh: all good ────────────────
    if ($processAlive -and $heartbeatFresh) {
        Start-Sleep -Milliseconds $PollIntervalMs
        continue
    }

    # ── Process is alive but heartbeat is stale: orchestrator hung ────────
    if ($processAlive -and -not $heartbeatFresh) {
        $hbAge = if ($stateObj -and $stateObj.orchestrator.heartbeat) {
            try { [math]::Round((($now - [DateTime]::Parse($stateObj.orchestrator.heartbeat)).TotalSeconds)) } catch { 'unknown' }
        }
        else { 'unknown' }
        Write-WatchdogLog "Heartbeat STALE (age: ${hbAge}s). Orchestrator appears hung. Killing..." -Level 'ERROR'
        try {
            Stop-Process -Id $OrchestratorPid -Force -ErrorAction SilentlyContinue
            Start-Sleep -Seconds 3
            $processAlive = $false
        }
        catch {
            Write-WatchdogLog "Could not kill hung orchestrator: $_" -Level 'ERROR'
        }
    }

    # ── Process is dead: check why ───────────────────────────────────────
    if (-not $processAlive) {
        # Try to read the exit code from state
        $orchestratorExitCode = $null
        if ($stateObj -and $stateObj.orchestrator.exitCode -ne $null) {
            $orchestratorExitCode = $stateObj.orchestrator.exitCode
        }

        # Check if this was a clean exit
        if ($orchestratorExitCode -in $cleanExitCodes) {
            Write-WatchdogLog "Orchestrator exited cleanly with code $orchestratorExitCode." -Level 'INFO'
            Write-WatchdogLog "Watchdog exiting (no restart needed)." -Level 'INFO'
            exit $orchestratorExitCode
        }

        # Crash or unexpected exit
        Write-WatchdogLog "Orchestrator CRASHED or exited unexpectedly (exit code: $orchestratorExitCode)." -Level 'ERROR'

        $restartCount++
        if ($restartCount -gt $MaxRestarts) {
            Write-WatchdogLog "Max restarts ($MaxRestarts) exceeded. Watchdog giving up." -Level 'ERROR'
            exit 4
        }

        Write-WatchdogLog "Restarting orchestrator (attempt $restartCount of $MaxRestarts)..." -Level 'WARN'
        Write-WatchdogLog "  Using -Resume to skip already-completed scenarios." -Level 'INFO'

        # ── Restart orchestrator ─────────────────────────────────────────
        if ($OrchestratorCommand.Count -gt 0) {
            $restartArgs = $OrchestratorCommand
        }
        else {
            # Default: restart with -Resume and -NoWatchdog (since WE are the watchdog)
            $restartArgs = @(
                '-NoProfile', '-ExecutionPolicy', 'Bypass',
                '-File', $script:OrchestratorScript,
                '-Resume', '-NoWatchdog'
            )
        }

        try {
            $proc = Start-Process -FilePath 'pwsh' -ArgumentList $restartArgs `
                -PassThru -NoNewWindow
            $OrchestratorPid = $proc.Id
            Write-WatchdogLog "Orchestrator restarted with PID: $OrchestratorPid" -Level 'INFO'

            # Update state with new PID
            if ($stateObj) {
                $stateObj.orchestrator.pid = $OrchestratorPid
                $stateObj.orchestrator.heartbeat = (Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssK')
                try {
                    $stateJson = $stateObj | ConvertTo-Json -Depth 10
                    $utf8NoBom = [System.Text.UTF8Encoding]::new($false)
                    [System.IO.File]::WriteAllText($StateFilePath, $stateJson, $utf8NoBom)
                }
                catch { }
            }
        }
        catch {
            Write-WatchdogLog "Failed to restart orchestrator: $_" -Level 'ERROR'
            exit 4
        }
    }

    # ── Sleep before next poll ───────────────────────────────────────────
    Start-Sleep -Milliseconds $PollIntervalMs
}
