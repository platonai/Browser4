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
    ./start.ps1 sched -Background -PidFile .coworker/scheduler.pid
    ./start.ps1 gui -Port 8091 -OpenBrowser
    ./start.ps1 gui -Background -PidFile .coworker/scheduler.pid
    ./start.ps1 both -Background -PidFile .coworker/scheduler.pid

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
    Run the started process(es) as background processes and exit immediately
    (sched / gui / both).  Foreground modes keep the process in this terminal
    so Ctrl+C stops it cleanly.

.PARAMETER Once
    Run the scheduler once and exit.

.PARAMETER PidFile
    File that receives the PID(s) of the background process(es).  When both
    the scheduler and the GUI are running (both -Background), the file holds
    a small JSON object {"scheduler": <pid>, "gui": <pid>}; otherwise it
    holds a single PID.  Consumed by `b4w coworker stop` / `restart`.
#>

[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [ValidateSet('sched', 'gui', 'both')]
    [string]$Command,

    [int]$Port = 8090,
    [string]$HostAddress = '127.0.0.1',
    [switch]$OpenBrowser,

    [string]$ConfigPath,
    [switch]$Background,
    [switch]$Once,

    [string]$PidFile
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

function Write-CoworkerPidFile {
    <#
    .SYNOPSIS
        Record the PID(s) of background processes started by this script.
    .DESCRIPTION
        Writes the scheduler and/or GUI PID to the given file.  When both
        processes are running the file contains JSON so `b4w coworker stop`
        can stop both; a single process keeps the legacy plain-PID format.
    #>
    param(
        [string]$PidFile,
        [System.Diagnostics.Process]$SchedulerProcess,
        [System.Diagnostics.Process]$GuiProcess
    )

    if (-not $PidFile) { return }

    $pidDir = Split-Path -Parent $PidFile
    if ($pidDir -and -not (Test-Path -LiteralPath $pidDir)) {
        New-Item -ItemType Directory -Path $pidDir -Force | Out-Null
    }

    $schedulerId = if ($SchedulerProcess) { $SchedulerProcess.Id } else { $null }
    $guiId = if ($GuiProcess) { $GuiProcess.Id } else { $null }

    if ($schedulerId -and $guiId) {
        $payload = @{ scheduler = $schedulerId; gui = $guiId } | ConvertTo-Json -Compress
        [System.IO.File]::WriteAllText($PidFile, $payload, [System.Text.UTF8Encoding]::new($false))
    } elseif ($schedulerId) {
        [System.IO.File]::WriteAllText($PidFile, "$schedulerId", [System.Text.Encoding]::ASCII)
    } elseif ($guiId) {
        [System.IO.File]::WriteAllText($PidFile, "$guiId", [System.Text.Encoding]::ASCII)
    } else {
        return
    }

    Write-Host "[coworker] PID file written: $PidFile"
}

function Get-BackgroundLogPath {
    <#
    .SYNOPSIS
        Return a log file path for a detached background process.
    .DESCRIPTION
        Background processes must NOT inherit the caller's stdout/stderr
        handles — otherwise a caller that captures output (a pipe, a test
        harness, an agent) blocks until every background process exits.
        Their output is redirected to per-process log files instead.
    #>
    param([string]$Name)

    $logDir = Join-Path $HOME '.browser4-coworker\tasks\300logs'
    try {
        if (-not (Test-Path -LiteralPath $logDir)) {
            New-Item -ItemType Directory -Path $logDir -Force -ErrorAction Stop | Out-Null
        }
    } catch {
        $logDir = [System.IO.Path]::GetTempPath()
    }
    $ts = (Get-Date).ToString('yyyyMMdd-HHmmss')
    return Join-Path $logDir "$ts-$Name"
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
        '--host', $HostAddress,
        '--tasks-root', $tasksRoot
    )
    if ($OpenBrowser) { $guiArgs += '--open-browser' }

    Write-Host "[coworker] GUI server → node $($guiArgs -join ' ')"

    if ($Background) {
        # Detached run: redirect the server's output to a log file so the
        # caller's stdout/stderr pipes are released when start.ps1 exits.
        $guiLog = Get-BackgroundLogPath -Name 'gui-server'
        $proc = Start-Process -FilePath 'node' `
            -ArgumentList $guiArgs `
            -NoNewWindow `
            -RedirectStandardOutput "$guiLog.stdout.log" `
            -RedirectStandardError "$guiLog.stderr.log" `
            -PassThru
        Write-Host "[coworker] GUI server log → $guiLog.stdout.log"
    } else {
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
    }

    Write-Host "[coworker] GUI server started (PID $($proc.Id)) → http://${HostAddress}:${Port}"

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

        # Redirect output to a log file so a caller that captures stdout
        # (pipe / test harness / agent) does not block until the scheduler
        # exits.
        $schedLog = Get-BackgroundLogPath -Name 'coworker-scheduler'
        $proc = Start-Process -FilePath 'pwsh' `
            -ArgumentList $pwshArgs `
            -NoNewWindow `
            -RedirectStandardOutput "$schedLog.stdout.log" `
            -RedirectStandardError "$schedLog.stderr.log" `
            -PassThru
        Write-Host "[coworker] Scheduler started (PID $($proc.Id))."
        Write-Host "[coworker] Scheduler log → $schedLog.stdout.log"
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

    if ($Background) {
        # Detached GUI server: record the PID (when requested) and return so
        # the caller's terminal is free.  Use `b4w coworker stop` (or the PID
        # file) to stop it later.
        Write-CoworkerPidFile -PidFile $PidFile -GuiProcess $guiProc
        Write-Host '[coworker] GUI server running in the background. This terminal can be closed.'
        Write-Host "[coworker] Open → http://${HostAddress}:${Port}"
        if (-not $PidFile) {
            Write-Host "[coworker] To stop: Stop-Process $($guiProc.Id)"
        }
        return
    }

    Write-Host '[coworker] GUI server running. Press Ctrl+C to stop.'
    Write-Host "[coworker] Open → http://${HostAddress}:${Port}"
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
        $proc = Invoke-Scheduler -ReturnProcess
        Write-CoworkerPidFile -PidFile $PidFile -SchedulerProcess $proc
        Write-Host '[coworker] This terminal can be closed.'
        if (-not $PidFile) {
            Write-Host "[coworker] To stop: look for the pwsh process running coworker-scheduler.ps1"
        }
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
    $schedProc = Invoke-Scheduler -ReturnProcess
    Write-CoworkerPidFile -PidFile $PidFile -SchedulerProcess $schedProc -GuiProcess $guiProc
    Write-Host '[coworker] This terminal can be closed.'
    Write-Host '[coworker] GUI server →' "http://${HostAddress}:${Port}"
    if (-not $PidFile) {
        Write-Host '[coworker] To stop: run b4w coworker stop (or Stop-Process the PIDs above)'
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
