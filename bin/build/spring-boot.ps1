#!/usr/bin/env pwsh

# ═══════════════════════════════════════════════════════════════════
# CROSS-PLATFORM: This script must run on Linux, macOS, and Windows.
# - Use $IsWindows / $IsLinux / $IsMacOS for platform detection.
# - Use "($IsWindows -or $env:OS -eq 'Windows_NT')" for PS 5.1 compat.
# - Windows-only env vars ($env:TEMP) need $env:TMPDIR fallback.
# - Guard "chcp" and other Windows-only commands behind platform checks.
# ═══════════════════════════════════════════════════════════════════

param(
    [Parameter(Position = 0)]
    [ValidateSet("start", "stop", "restart")]
    [string]$Command = "start",

    [Alias("b")]
    [switch]$Background,

    [ValidateRange(1, 65535)]
    [int]$Port = 8182
)

$repoRoot = (git rev-parse --show-toplevel 2>$null)
Set-Location $repoRoot

# Import common utility script
. (Join-Path $repoRoot "bin/common/Util.ps1")

Fix-Encoding-UTF8

$SERVER_HOME = Join-Path $repoRoot "browser4-apps/browser4-standalone"
$PID_FILE = Join-Path $repoRoot ".build/spring-boot.pid"
$LOG_FILE = Join-Path $repoRoot ".build/spring-boot.log"

# ── helpers ────────────────────────────────────────────────────────

function Write-BuildDir {
    $buildDir = Join-Path $repoRoot ".build"
    if (-not (Test-Path $buildDir)) {
        New-Item -ItemType Directory -Path $buildDir -Force | Out-Null
    }
}

function Get-RunningPid {
    if (-not (Test-Path $PID_FILE)) { return $null }
    $raw = (Get-Content $PID_FILE -Raw).Trim()
    if (-not $raw) { return $null }
    try {
        $proc = Get-Process -Id $raw -ErrorAction Stop
        return $raw
    } catch {
        return $null
    }
}

# ── start ──────────────────────────────────────────────────────────

function Start-Server {
    $existing = Get-RunningPid
    if ($existing) {
        Write-Host "Server is already running (PID: $existing)"
        return
    }
    # Clean up stale PID file
    if (Test-Path $PID_FILE) { Remove-Item $PID_FILE -Force }

    if ($Background) {
        Write-BuildDir
        # Truncate log
        "" | Out-File -FilePath $LOG_FILE -Encoding UTF8

        Write-Host "Starting Spring Boot in background (log: $LOG_FILE) …"

        if ($IsWindows) {
            # Merge stdout+stderr via cmd.exe redirect to avoid pwsh
            # limitation: RedirectStandardOutput and RedirectStandardError
            # cannot be the same file.
            $proc = Start-Process -FilePath "cmd.exe" `
                -ArgumentList @("/c", "cd /d `"$SERVER_HOME`" && ..\..\mvnw.cmd spring-boot:run -am 2>&1") `
                -NoNewWindow -PassThru `
                -RedirectStandardOutput $LOG_FILE
        } else {
            $proc = Start-Process -FilePath "sh" `
                -ArgumentList @("-c", "cd `"$SERVER_HOME`" && ../../mvnw spring-boot:run -am 2>&1") `
                -NoNewWindow -PassThru `
                -RedirectStandardOutput $LOG_FILE
        }

        $proc.Id | Out-File -FilePath $PID_FILE -Encoding ASCII -NoNewline
        Write-Host "Server launched (PID: $($proc.Id))"

        # ── wait until the HTTP port is responding ──────────────
        $healthUrl = "http://localhost:$Port"
        $maxWaitSec = 120
        $deadline = (Get-Date).AddSeconds($maxWaitSec)
        $intervalSec = 3
        $started = $false

        Write-Host "Waiting for server to be ready on $healthUrl (timeout: ${maxWaitSec}s) …"

        while ((Get-Date) -lt $deadline) {
            # Check the background process is still alive
            $alive = Get-Process -Id $proc.Id -ErrorAction SilentlyContinue
            if (-not $alive) {
                Write-Host "ERROR: Server process died during startup. Check log: $LOG_FILE"
                Remove-Item $PID_FILE -Force -ErrorAction SilentlyContinue
                Set-Location $repoRoot
                exit 1
            }

            try {
                $null = Invoke-WebRequest -Uri $healthUrl -TimeoutSec 3 -SkipHttpErrorCheck -ErrorAction Stop
                $started = $true
                break
            } catch {
                # Not ready yet — keep polling
            }

            Start-Sleep -Seconds $intervalSec
        }

        if ($started) {
            Write-Host "Server is ready."
        } else {
            Write-Host "WARNING: Server did not respond on $healthUrl within ${maxWaitSec}s."
            Write-Host "It may still be starting — check log: $LOG_FILE"
        }
    } else {
        Write-Host "Starting Spring Boot (foreground) …"
        Set-Location $SERVER_HOME

        if ($IsWindows) {
            & ..\..\mvnw.cmd spring-boot:run -am
        } else {
            & ../../mvnw spring-boot:run -am
        }
    }
}

# ── stop ───────────────────────────────────────────────────────────

function Stop-Server {
    $serverPid = Get-RunningPid
    if (-not $serverPid) {
        Write-Host "Server is not running (no active PID file)."
        if (Test-Path $PID_FILE) { Remove-Item $PID_FILE -Force }
        return
    }

    Write-Host "Stopping server (PID: $serverPid) …"
    Stop-Process -Id $serverPid -Force -ErrorAction SilentlyContinue

    # Wait up to 10 s for graceful exit
    $timeout = (Get-Date).AddSeconds(10)
    while ((Get-Date) -lt $timeout) {
        $alive = Get-Process -Id $serverPid -ErrorAction SilentlyContinue
        if (-not $alive) { break }
        Start-Sleep -Milliseconds 500
    }

    # Force-kill if still alive
    $alive = Get-Process -Id $serverPid -ErrorAction SilentlyContinue
    if ($alive) {
        Write-Host "Process did not exit gracefully — force-killing …"
        Stop-Process -Id $serverPid -Force -ErrorAction SilentlyContinue
        Start-Sleep -Seconds 1
    }

    Remove-Item $PID_FILE -Force -ErrorAction SilentlyContinue
    Write-Host "Server stopped."
}

# ── main ───────────────────────────────────────────────────────────

Write-Host "Working in: $repoRoot"

switch ($Command) {
    "start" {
        Start-Server
    }
    "stop" {
        Stop-Server
    }
    "restart" {
        Stop-Server
        # Brief pause to let the port free up
        Start-Sleep -Seconds 2
        Start-Server
    }
}

Set-Location $repoRoot
