#!/usr/bin/env pwsh

# ═══════════════════════════════════════════════════════════════════
# CROSS-PLATFORM: This script must run on Linux, macOS, and Windows.
# - Use $IsWindows / $IsLinux / $IsMacOS for platform detection.
# - Use "($IsWindows -or $env:OS -eq 'Windows_NT')" for PS 5.1 compat.
# - Windows-only env vars ($env:TEMP) need $env:TMPDIR fallback.
# - Guard "chcp" and other Windows-only commands behind platform checks.
# ═══════════════════════════════════════════════════════════════════

[CmdletBinding(SupportsShouldProcess)]
param(
    [switch]$ListOnly
)

. (Join-Path $PSScriptRoot 'browser4-process-common.ps1')

function Get-UniqueProcessesById {
    param($Processes)

    $map = @{}
    foreach ($proc in @($Processes)) {
        if ($proc -and $proc.ProcessId) {
            $map[[string]$proc.ProcessId] = $proc
        }
    }

    $map.Values | Sort-Object ProcessId
}

function Stop-ProcessWithFeedback {
    param(
        [Parameter(Mandatory)]$Process,
        [Parameter(Mandatory)][string]$Label
    )

    $processId = $Process.ProcessId
    $target = "$Label process ID $( $processId )"

    if ($PSCmdlet.ShouldProcess($target, 'Stop-Process -Force')) {
        try {
            Stop-Process -Id $processId -Force -ErrorAction Stop
            Write-Output "Killed $Label process with ID: $( $processId )"
            return $true
        }
        catch {
            Write-Output "Failed to kill $Label process with ID: $( $processId ). Error: $_"
            return $false
        }
    }

    Write-Output "Would kill $Label process with ID: $( $processId )"
    return $false
}

# Get java/javaw processes whose command line indicates Browser4.
$browser4Procs = @(Get-UniqueProcessesById -Processes (Get-Browser4JavaProcesses))
$chromeProcs = @(Get-UniqueProcessesById -Processes (Get-Browser4ChromeProcesses))

if ($browser4Procs.Count -eq 0 -and $chromeProcs.Count -eq 0) {
    Write-Output 'NO_BROWSER4_PROCESSES'
    return
}

if ($ListOnly) {
    foreach ($proc in $browser4Procs) {
        Write-Output "BROWSER4 PID=$($proc.ProcessId) CMD=$($proc.CommandLine)"
    }

    foreach ($proc in $chromeProcs) {
        Write-Output "CHROME PID=$($proc.ProcessId) CMD=$($proc.CommandLine)"
    }

    return
}

foreach ($proc in $browser4Procs) {
    Stop-ProcessWithFeedback -Process $proc -Label 'Browser4'
}

# Mirror kill_all_browsers: repeated sweeps to catch respawned/late Browser4 Chrome processes.
# Loop until no Browser4 Chrome processes remain or the 30-second timeout is reached.
$killTimeoutMs = 30000
$sweepDelayMs = 250
$waitAfterKillMs = 2000
$deadline = (Get-Date).AddMilliseconds($killTimeoutMs)

while ((Get-Date) -lt $deadline) {
    $sweepChromeProcs = @(Get-UniqueProcessesById -Processes (Get-Browser4ChromeProcesses))
    if ($sweepChromeProcs.Count -eq 0) {
        break
    }

    foreach ($chromeProc in $sweepChromeProcs) {
        Stop-ProcessWithFeedback -Process $chromeProc -Label 'Chrome' | Out-Null
    }

    # In WhatIf mode processes are never actually killed, so re-scanning would
    # find the same processes indefinitely; break after one informational pass.
    if ($WhatIfPreference) {
        break
    }

    if ((Get-Date) -ge $deadline) {
        break
    }

    Start-Sleep -Milliseconds $waitAfterKillMs

    Start-Sleep -Milliseconds $sweepDelayMs
}

$remainingChrome = @(Get-UniqueProcessesById -Processes (Get-Browser4ChromeProcesses))
foreach ($proc in $remainingChrome) {
    Write-Output "Remaining Chrome process with ID: $( $proc.ProcessId )"
}
