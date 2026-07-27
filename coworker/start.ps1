#!/usr/bin/env pwsh

<#
.SYNOPSIS
    Starts the coworker scheduler. The GUI server is opt-in via -Gui.

.DESCRIPTION
    Runs the coworker scheduler in the foreground.
    Optionally starts the Node.js GUI server (coworker/gui/server.js) as a
    background process when -Gui is passed. Press Ctrl+C to stop both.

.PARAMETER Gui
    Start the GUI server alongside the scheduler.

.PARAMETER GuiPort
    Port for the GUI server. Default: 8090.

.PARAMETER GuiHost
    Host address for the GUI server. Default: 127.0.0.1.

.PARAMETER OpenBrowser
    Open the default browser to the GUI when the server starts.

.PARAMETER ConfigPath
    Path to the scheduler configuration file (passed through).

.PARAMETER Background
    Run the scheduler as a background process and exit. The script returns
    immediately; the scheduler keeps running in its own PowerShell process.

.PARAMETER Once
    Run the scheduler once and exit (passed through).
#>

[CmdletBinding()]
param(
    [switch]$Gui,
    [int]$GuiPort = 8090,
    [string]$GuiHost = '127.0.0.1',
    [switch]$OpenBrowser,
    [switch]$Background,
    [string]$ConfigPath,
    [switch]$Once
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$scriptDir = $PSScriptRoot

# ═══════════════════════════════════════════════════════════════════════════
# Load coworker shared config (sets up PATH, tool shims, utility functions)
# ═══════════════════════════════════════════════════════════════════════════
$configScriptPath = Join-Path $scriptDir 'scripts' 'config.ps1'
if (Test-Path -LiteralPath $configScriptPath) {
    . $configScriptPath
}

# ═══════════════════════════════════════════════════════════════════════════
# GUI server helpers
# ═══════════════════════════════════════════════════════════════════════════

$script:guiProcess = $null
$guiServerPath = Join-Path $scriptDir 'gui' 'server.js'
$tasksRoot = Join-Path $scriptDir 'tasks'

function Start-GuiServer {
    if (-not $Gui) {
        Write-Host '[coworker] GUI server not requested (use -Gui to start it).'
        return
    }

    if (-not (Test-Path -LiteralPath $guiServerPath)) {
        Write-Warning "[coworker] GUI server not found at: $guiServerPath"
        return
    }

    if (-not (Get-Command node -ErrorAction SilentlyContinue)) {
        Write-Warning '[coworker] Node.js not found on PATH. Install Node.js to use the GUI.'
        return
    }

    if (-not (Get-Command npm -ErrorAction SilentlyContinue)) {
        Write-Warning '[coworker] npm not found on PATH. Install Node.js to use the GUI.'
        return
    }

    $guiDir = Split-Path -Parent $guiServerPath
    Write-Host "[coworker] Installing GUI dependencies (npm install)..."
    $npmArgs = @('install', '--no-audit', '--no-fund', '--loglevel=error')
    if ($IsWindows) {
        $npmResult = Start-Process -FilePath 'cmd' `
            -ArgumentList (@('/c', 'npm') + $npmArgs) `
            -WorkingDirectory $guiDir `
            -NoNewWindow `
            -Wait `
            -PassThru
    } else {
        $npmResult = Start-Process -FilePath 'npm' `
            -ArgumentList $npmArgs `
            -WorkingDirectory $guiDir `
            -NoNewWindow `
            -Wait `
            -PassThru
    }
    if ($npmResult.ExitCode -ne 0) {
        Write-Warning "[coworker] npm install failed (exit code $($npmResult.ExitCode)). GUI may not work."
    }

    $guiArgs = @(
        $guiServerPath,
        '--port', $GuiPort,
        '--host', $GuiHost,
        '--tasks-root', $tasksRoot
    )
    if ($OpenBrowser) { $guiArgs += '--open-browser' }

    Write-Host "[coworker] GUI server → node $($guiArgs -join ' ')"

    $script:guiProcess = Start-Process -FilePath 'node' `
        -ArgumentList $guiArgs `
        -NoNewWindow `
        -PassThru

    # Ensure cleanup even if the terminal window is closed directly.
    # Capture PID for the event action (runs in a separate runspace).
    $guiPid = $script:guiProcess.Id
    Register-EngineEvent -SourceIdentifier PowerShell.Exiting -SupportEvent -Action {
        $proc = Get-Process -Id $guiPid -ErrorAction SilentlyContinue
        if ($proc -and -not $proc.HasExited) {
            $proc.Kill()
        }
    } | Out-Null

    Write-Host "[coworker] GUI server started (PID $guiPid) → http://${GuiHost}:${GuiPort}"
}

function Stop-GuiServer {
    if ($null -eq $script:guiProcess) { return }

    try {
        $script:guiProcess.Refresh()
        if (-not $script:guiProcess.HasExited) {
            Write-Host "[coworker] Stopping GUI server (PID $($script:guiProcess.Id))..."
            $script:guiProcess.Kill()
            $script:guiProcess.WaitForExit(5000) | Out-Null
        }
    } catch {
        # Process may have already exited
    }
}

# ═══════════════════════════════════════════════════════════════════════════
# Scheduler
# ═══════════════════════════════════════════════════════════════════════════

$schedulerPath = Join-Path $scriptDir 'scripts' 'coworker-scheduler.ps1'
$schedulerArgs = @{}
if ($ConfigPath) { $schedulerArgs['ConfigPath'] = $ConfigPath }
if ($Once)       { $schedulerArgs['Once'] = $true }

# ═══════════════════════════════════════════════════════════════════════════
# Main — start GUI, then run scheduler in foreground
# ═══════════════════════════════════════════════════════════════════════════

$bannerTitle = if ($Gui) { 'Coworker — Task Pipeline + GUI Manager' } else { 'Coworker — Task Pipeline' }
Write-Host '═══════════════════════════════════════════════════'
Write-Host "  $bannerTitle"
Write-Host "  Tasks root : $tasksRoot"
Write-Host '═══════════════════════════════════════════════════'

# ── Background mode: launch scheduler as separate process, exit immediately ──
if ($Background) {
    Start-GuiServer

    $pwshArgs = @('-NoProfile', '-File', $schedulerPath)
    if ($ConfigPath) { $pwshArgs += '-ConfigPath'; $pwshArgs += $ConfigPath }
    if ($Once)       { $pwshArgs += '-Once' }

    Write-Host "[coworker] Starting scheduler in background..."
    $schedulerProcess = Start-Process -FilePath 'pwsh' `
        -ArgumentList $pwshArgs `
        -NoNewWindow `
        -PassThru

    Write-Host "[coworker] Scheduler started (PID $($schedulerProcess.Id))."
    if ($Gui) {
        Write-Host "[coworker] GUI server → http://${GuiHost}:${GuiPort}"
    }
    Write-Host "[coworker] This terminal can be closed."
    Write-Host "[coworker] To stop: Stop-Process $($schedulerProcess.Id)"
    return
}

# ── Foreground mode: GUI (if requested), then scheduler in foreground ──
Start-GuiServer
Write-Host '[coworker] Starting scheduler (Ctrl+C to stop all)...'
Write-Host ''

try {
    . $schedulerPath @schedulerArgs
} finally {
    Stop-GuiServer
    Write-Host '[coworker] Shutdown complete.'
}
