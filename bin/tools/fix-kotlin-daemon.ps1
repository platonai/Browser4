#!/usr/bin/env pwsh

[CmdletBinding(SupportsShouldProcess)]
param(
    [string]$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path,
    [string]$JavaHome,
    [switch]$PersistJavaHome,
    [switch]$SkipMavenCheck,
    [switch]$RunInstallCheck
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Write-Step {
    param([string]$Message)
    Write-Host "[fix-kotlin-daemon] $Message"
}

function Get-KotlinDaemonProcesses {
    Get-CimInstance Win32_Process |
        Where-Object {
            $_.Name -eq "java.exe" -and
            $_.CommandLine -and
            $_.CommandLine -match "kotlin-daemon"
        }
}

function Remove-PathItems {
    param(
        [string]$Path,
        [switch]$Literal
    )

    $items = @()
    if ($Literal) {
        if (Test-Path -LiteralPath $Path) {
            $items = @(Get-ChildItem -LiteralPath $Path -Force -ErrorAction SilentlyContinue)
        }
    }
    else {
        $items = @(Get-ChildItem -Path $Path -Force -ErrorAction SilentlyContinue)
    }

    $removed = 0
    foreach ($item in $items) {
        if ($PSCmdlet.ShouldProcess($item.FullName, "Remove-Item -Force -Recurse")) {
            Remove-Item -LiteralPath $item.FullName -Force -Recurse -ErrorAction SilentlyContinue
            $removed++
        }
    }

    return $removed
}

Write-Step "Scanning for stale Kotlin daemon JVMs..."
$daemonProcs = @(Get-KotlinDaemonProcesses)
$killed = 0

foreach ($proc in $daemonProcs) {
    $target = "PID $($proc.ProcessId)"
    if ($PSCmdlet.ShouldProcess($target, "Stop-Process -Force")) {
        Stop-Process -Id $proc.ProcessId -Force -ErrorAction SilentlyContinue
        $killed++
    }
}
Write-Step "Stopped $killed Kotlin daemon process(es)."

Write-Step "Cleaning Kotlin daemon state and lock files..."
$removedDaemonDirItems = Remove-PathItems -Path (Join-Path $env:LOCALAPPDATA "kotlin\daemon") -Literal
$removedTempDaemonLogs = Remove-PathItems -Path (Join-Path $env:TEMP "kotlin-daemon.*")
$removedTempClientMarkers = Remove-PathItems -Path (Join-Path $env:TEMP "kotlin-compiler-client*-is-running")
$removedTempSessionMarkers = Remove-PathItems -Path (Join-Path $env:TEMP "kotlin-compilation-session*-is-running")

Write-Step "Removed $removedDaemonDirItems item(s) from LOCALAPPDATA kotlin/daemon."
Write-Step "Removed $removedTempDaemonLogs temp daemon log/lock file(s)."
Write-Step "Removed $removedTempClientMarkers temp client marker file(s)."
Write-Step "Removed $removedTempSessionMarkers temp session marker file(s)."

if ([string]::IsNullOrWhiteSpace($JavaHome)) {
    if (-not [string]::IsNullOrWhiteSpace($env:JAVA_HOME) -and (Test-Path (Join-Path $env:JAVA_HOME "bin\java.exe"))) {
        $JavaHome = $env:JAVA_HOME
    }
    else {
        $javaCmd = Get-Command java -ErrorAction SilentlyContinue
        if ($javaCmd) {
            $JavaHome = Split-Path (Split-Path $javaCmd.Source -Parent) -Parent
        }
    }
}

if (-not [string]::IsNullOrWhiteSpace($JavaHome) -and (Test-Path (Join-Path $JavaHome "bin\java.exe"))) {
    $env:JAVA_HOME = $JavaHome
    Write-Step "Set JAVA_HOME for this session to: $JavaHome"

    if ($PersistJavaHome) {
        if ($PSCmdlet.ShouldProcess("User Environment", "Persist JAVA_HOME with setx")) {
            setx JAVA_HOME "$JavaHome" | Out-Null
            Write-Step "Persisted JAVA_HOME for future shells."
        }
    }
}
else {
    Write-Warning "Could not resolve a valid JAVA_HOME. Maven checks may fail."
}

if (-not (Test-Path -LiteralPath $RepoRoot)) {
    throw "Repo root does not exist: $RepoRoot"
}

Push-Location $RepoRoot
try {
    if (-not $SkipMavenCheck) {
        Write-Step "Running Maven wrapper version check..."
        & .\mvnw.cmd -version
    }

    if ($RunInstallCheck) {
        Write-Step "Running install check for browser4-apps/browser4-agents (this can take time)..."
        & .\mvnw.cmd -pl browser4-apps/browser4-agents -am -DskipTests install -q
    }
}
finally {
    Pop-Location
}

Write-Step "Done. If the daemon issue persists, rerun with -RunInstallCheck and inspect output."

