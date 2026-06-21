#!/usr/bin/env pwsh

<#
.SYNOPSIS
    Starts the coworker scheduler and the task-manager GUI server together.

.DESCRIPTION
    Launches the Node.js GUI server (coworker/gui/server.js) as a background
    process, then runs the coworker scheduler in the foreground.
    Press Ctrl+C to stop both.

.PARAMETER GuiPort
    Port for the GUI server. Default: 8090.

.PARAMETER GuiHost
    Host address for the GUI server. Default: 127.0.0.1.

.PARAMETER OpenBrowser
    Open the default browser to the GUI when the server starts.

.PARAMETER NoGui
    Skip the GUI server and start only the scheduler.

.PARAMETER ConfigPath
    Path to the scheduler configuration file (passed through).

.PARAMETER Once
    Run the scheduler once and exit (passed through).
#>

[CmdletBinding()]
param(
    [int]$GuiPort = 8090,
    [string]$GuiHost = '127.0.0.1',
    [switch]$OpenBrowser,
    [switch]$NoGui,
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
    if ($NoGui) {
        Write-Host '[coworker] GUI server skipped (--NoGui).'
        return
    }

    if (-not (Test-Path -LiteralPath $guiServerPath)) {
        Write-Warning "[coworker] GUI server not found at: $guiServerPath"
        return
    }

    if (-not (Get-Command node -ErrorAction SilentlyContinue)) {
        Write-Warning '[coworker] Node.js not found on PATH. Install Node.js or use --NoGui to skip the GUI.'
        return
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

Write-Host '═══════════════════════════════════════════════════'
Write-Host '  Coworker — Task Pipeline + GUI Manager'
Write-Host "  Tasks root : $tasksRoot"
Write-Host '═══════════════════════════════════════════════════'

Start-GuiServer
Write-Host '[coworker] Starting scheduler (Ctrl+C to stop all)...'
Write-Host ''

try {
    . $schedulerPath @schedulerArgs
} finally {
    Stop-GuiServer
    Write-Host '[coworker] Shutdown complete.'
}
