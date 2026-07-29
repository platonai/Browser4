#!/usr/bin/env pwsh

<#
.SYNOPSIS
    Starts the Coworker task pipeline with subcommand control.

.DESCRIPTION
    Three subcommands:
      sched      — Run the coworker scheduler only.
      gui        — Start the Node.js GUI server (coworker/gui/server.js) only.
      both       — Start the scheduler and GUI together (default).

.EXAMPLE
    ./start.ps1               # both (default)
    ./start.ps1 both
    ./start.ps1 sched -Once
    ./start.ps1 sched -Background
    ./start.ps1 gui -Port 8091 -OpenBrowser

.PARAMETER Command
    Subcommand: sched | gui | both.

.PARAMETER Port
    Port for the GUI server. Default: 8090.  (gui / both only)

.PARAMETER Host
    Host address for the GUI server. Default: 127.0.0.1.  (gui / both only)

.PARAMETER OpenBrowser
    Open the default browser to the GUI when the server starts.

.PARAMETER ConfigPath
    Path to the scheduler configuration file.

.PARAMETER Background
    Run the scheduler as a background process and exit immediately.

.PARAMETER Once
    Run the scheduler once and exit.
#>

[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [ValidateSet('sched', 'gui', 'both')]
    [string]$Command,

    [int]$Port = 8090,
    [string]$Host = '127.0.0.1',
    [switch]$OpenBrowser,

    [string]$ConfigPath,
    [switch]$Background,
    [switch]$Once
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

if (-not $PSBoundParameters.ContainsKey('Command')) {
    Get-Help $PSCommandPath
    exit 1
}

$scriptDir = $PSScriptRoot

# ═══════════════════════════════════════════════════════════════════════════
# Load coworker shared config (PATH, tool shims, utility functions)
# ═══════════════════════════════════════════════════════════════════════════
$configScriptPath = Join-Path $scriptDir 'scripts' 'config.ps1'
if (Test-Path -LiteralPath $configScriptPath) {
    . $configScriptPath
}

# ═══════════════════════════════════════════════════════════════════════════
# Paths
# ═══════════════════════════════════════════════════════════════════════════

$guiServerPath  = Join-Path $scriptDir 'gui' 'server.js'
$schedulerPath  = Join-Path $scriptDir 'scripts' 'coworker-scheduler.ps1'
$tasksRoot      = Join-Path $scriptDir 'tasks'

$startGui       = $Command -in @('gui', 'both')
$startScheduler = $Command -in @('sched', 'both')

# ═══════════════════════════════════════════════════════════════════════════
# Shared helpers
# ═══════════════════════════════════════════════════════════════════════════

function Write-Banner {
    $labels = @{
        sched = 'Coworker — Task Pipeline'
        gui       = 'Coworker — GUI Manager'
        both      = 'Coworker — Task Pipeline + GUI Manager'
    }
    Write-Host '═══════════════════════════════════════════════════'
    Write-Host "  $($labels[$Command])"
    Write-Host "  Tasks root : $tasksRoot"
    Write-Host '═══════════════════════════════════════════════════'
}

function Test-NodeAvailable {
    if (-not (Get-Command node -ErrorAction SilentlyContinue)) {
        Write-Warning '[coworker] Node.js not found on PATH. Install Node.js to use the GUI.'
        return $false
    }
    if (-not (Get-Command npm -ErrorAction SilentlyContinue)) {
        Write-Warning '[coworker] npm not found on PATH. Install Node.js to use the GUI.'
        return $false
    }
    return $true
}

# ═══════════════════════════════════════════════════════════════════════════
# GUI server
# ═══════════════════════════════════════════════════════════════════════════

function Start-GuiServer {
    [CmdletBinding()]
    param(
        [switch]$ReturnProcess
    )

    if (-not $startGui) { return $null }

    if (-not (Test-Path -LiteralPath $guiServerPath)) {
        Write-Warning "[coworker] GUI server not found at: $guiServerPath"
        return $null
    }

    if (-not (Test-NodeAvailable)) { return $null }

    $guiDir = Split-Path -Parent $guiServerPath
    Write-Host '[coworker] Installing GUI dependencies (npm install)...'
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
        '--port', $Port,
        '--host', $Host,
        '--tasks-root', $tasksRoot
    )
    if ($OpenBrowser) { $guiArgs += '--open-browser' }

    Write-Host "[coworker] GUI server → node $($guiArgs -join ' ')"

    $proc = Start-Process -FilePath 'node' `
        -ArgumentList $guiArgs `
        -NoNewWindow `
        -PassThru

    # Ensure cleanup even if the terminal window is closed directly
    $guiPid = $proc.Id
    Register-EngineEvent -SourceIdentifier PowerShell.Exiting -SupportEvent -Action {
        $p = Get-Process -Id $guiPid -ErrorAction SilentlyContinue
        if ($p -and -not $p.HasExited) { $p.Kill() }
    } | Out-Null

    Write-Host "[coworker] GUI server started (PID $guiPid) → http://${Host}:${Port}"

    return $proc
}

function Stop-GuiServer {
    param([System.Diagnostics.Process]$Process)
    if ($null -eq $Process) { return }

    try {
        $Process.Refresh()
        if (-not $Process.HasExited) {
            Write-Host "[coworker] Stopping GUI server (PID $($Process.Id))..."
            $Process.Kill()
            $Process.WaitForExit(5000) | Out-Null
        }
    } catch {
        # Process may have already exited
    }
}

# ═══════════════════════════════════════════════════════════════════════════
# Scheduler
# ═══════════════════════════════════════════════════════════════════════════

function Invoke-Scheduler {
    [CmdletBinding()]
    param(
        [switch]$ReturnProcess
    )

    if (-not $startScheduler) { return $null }

    if (-not (Test-Path -LiteralPath $schedulerPath)) {
        Write-Error "[coworker] Scheduler not found at: $schedulerPath"
        exit 1
    }

    # Build a hashtable of arguments to splat
    $schedulerArgs = @{}
    if ($ConfigPath) { $schedulerArgs['ConfigPath'] = $ConfigPath }
    if ($Once)       { $schedulerArgs['Once'] = $true }

    Write-Host "[coworker] Starting scheduler..."

    if ($ReturnProcess -or $Background) {
        $pwshArgs = @('-NoProfile', '-File', $schedulerPath)
        if ($ConfigPath) { $pwshArgs += '-ConfigPath'; $pwshArgs += $ConfigPath }
        if ($Once)       { $pwshArgs += '-Once' }

        $proc = Start-Process -FilePath 'pwsh' `
            -ArgumentList $pwshArgs `
            -NoNewWindow `
            -PassThru
        Write-Host "[coworker] Scheduler started (PID $($proc.Id))."
        return $proc
    }

    # Foreground: dot-source the scheduler so Ctrl+C propagates
    . $schedulerPath @schedulerArgs
}

# ═══════════════════════════════════════════════════════════════════════════
# Main
# ═══════════════════════════════════════════════════════════════════════════

Write-Banner

# ── GUI-only mode ──────────────────────────────────────────────────────────
if ($Command -eq 'gui') {
    $guiProc = Start-GuiServer -ReturnProcess
    if ($null -eq $guiProc) {
        Write-Warning '[coworker] GUI server failed to start.'
        exit 1
    }
    Write-Host '[coworker] GUI server running. Press Ctrl+C to stop.'
    Write-Host "[coworker] Open → http://${Host}:${Port}"
    try {
        $guiProc.WaitForExit()
    } finally {
        Stop-GuiServer -Process $guiProc
        Write-Host '[coworker] Shutdown complete.'
    }
    return
}

# ── Scheduler-only mode ────────────────────────────────────────────────────
if ($Command -eq 'sched') {
    if ($Background) {
        $null = Invoke-Scheduler -ReturnProcess
        Write-Host '[coworker] This terminal can be closed.'
        Write-Host "[coworker] To stop: look for the pwsh process running coworker-scheduler.ps1"
        return
    }
    Write-Host '[coworker] Starting scheduler (Ctrl+C to stop all)...'
    Write-Host ''
    Invoke-Scheduler
    return
}

# ── Both mode (default) ────────────────────────────────────────────────────
$guiProc = Start-GuiServer -ReturnProcess

if ($Background) {
    $null = Invoke-Scheduler -ReturnProcess
    Write-Host '[coworker] This terminal can be closed.'
    Write-Host '[coworker] GUI server →' "http://${Host}:${Port}"
    if ($null -ne $guiProc) {
        Write-Host "[coworker] To stop GUI: Stop-Process $($guiProc.Id)"
    }
    return
}

# Foreground: scheduler runs in this terminal, GUI in background
Write-Host '[coworker] Starting scheduler (Ctrl+C to stop all)...'
Write-Host ''

try {
    Invoke-Scheduler
} finally {
    Stop-GuiServer -Process $guiProc
    Write-Host '[coworker] Shutdown complete.'
}
