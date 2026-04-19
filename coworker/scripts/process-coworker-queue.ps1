#!/usr/bin/env pwsh

param(
    [int]$IntervalSeconds = 15,
    [switch]$Monitor,
    [switch]$Once
)

$ErrorActionPreference = 'Stop'

$configScriptPath = Join-Path $PSScriptRoot 'config.ps1'
. $configScriptPath

function Get-RepoRoot {
    $currentDirectory = $PSScriptRoot
    while ($currentDirectory) {
        if ((Test-Path (Join-Path $currentDirectory 'ROOT.md')) -or (Test-Path (Join-Path $currentDirectory '.git'))) {
            return (Resolve-Path $currentDirectory).Path
        }

        $parentDirectory = Split-Path -Parent $currentDirectory
        if ($parentDirectory -eq $currentDirectory) {
            break
        }
        $currentDirectory = $parentDirectory
    }

    throw 'Repo root not found.'
}

function Test-HasPendingCoworkerTasks {
    param(
        [Parameter(Mandatory = $true)]
        [string]$RepoRoot
    )

    $createdTasks = Get-ChildItem -Path (Join-Path $RepoRoot 'coworker\tasks\1created') -File -ErrorAction SilentlyContinue |
        Where-Object { Test-CoworkerPendingFile -Item $_ }
    $approvedTasks = Get-ChildItem -Path (Join-Path $RepoRoot 'coworker\tasks\5approved') -File -Recurse -ErrorAction SilentlyContinue |
        Where-Object { Test-CoworkerPendingFile -Item $_ }
    return [bool]($createdTasks -or $approvedTasks)
}

function Get-RunningCoworkerProcesses {
    param(
        [Parameter(Mandatory = $true)]
        [string]$ScriptName,
        [Parameter(Mandatory = $true)]
        [string]$WrapperName
    )

    $scriptNameEscaped   = [regex]::Escape($ScriptName)
    $wrapperNameEscaped  = [regex]::Escape($WrapperName)

    $candidates = @(Get-Process -Name 'pwsh', 'powershell' -ErrorAction SilentlyContinue |
        Where-Object { $_.Id -ne $PID })

    $result = @()
    foreach ($proc in $candidates) {
        $cmdLine = ''
        try {
            if ($IsWindows) {
                $cimProc = Get-CimInstance -ClassName Win32_Process -Filter "ProcessId=$($proc.Id)" -ErrorAction Stop
                $cmdLine = [string]$cimProc.CommandLine
            } elseif ($IsLinux) {
                $cmdLinePath = "/proc/$($proc.Id)/cmdline"
                if (Test-Path $cmdLinePath) {
                    $bytes = [System.IO.File]::ReadAllBytes($cmdLinePath)
                    $cmdLine = [System.Text.Encoding]::UTF8.GetString($bytes) -replace "`0", ' '
                }
            } elseif ($IsMacOS) {
                $cmdLine = (& ps -p $proc.Id -o args= 2>/dev/null) -join ' '
            }
        } catch {
            continue
        }

        if ($cmdLine -match $scriptNameEscaped -and $cmdLine -notmatch $wrapperNameEscaped) {
            $result += $proc
        }
    }
    return $result
}

function Invoke-TaskSourceMonitor {
    param(
        [Parameter(Mandatory = $true)]
        [string]$MonitorScriptPath
    )

    $timestamp = (Get-Date).ToUniversalTime().ToString('yyyy-MM-dd HH:mm:ss')
    Write-Host "$timestamp - Running task source monitor..."
    try {
        & $MonitorScriptPath -Once
    }
    catch {
        Write-Error "Failed to run task source monitor: $_"
    }
}

function Monitor-CoworkerProcess {
    param(
        [Parameter(Mandatory = $true)]
        [System.Diagnostics.Process]$Process,
        [Parameter(Mandatory = $true)]
        [string]$RepoRoot
    )

    $consecutiveLowActivity = 0
    $maxConsecutiveLowActivity = 18

    while (-not $Process.HasExited) {
        Start-Sleep -Seconds 10
        $Process.Refresh()

        $currentYear = (Get-Date).ToUniversalTime().ToString('yyyy')
        $currentMonth = (Get-Date).ToUniversalTime().ToString('MM')
        $currentDay = (Get-Date).ToUniversalTime().ToString('dd')
        $logsSubDir = Join-Path $RepoRoot "coworker\tasks\300logs\$currentYear\$currentMonth\$currentDay"

        if (-not (Test-Path $logsSubDir)) {
            continue
        }

        $latestLog = Get-ChildItem -Path $logsSubDir -Filter '*.copilot.log.stdout' |
                Sort-Object LastWriteTime -Descending |
                Select-Object -First 1
        if (-not $latestLog) {
            continue
        }

        try {
            $linesArray = @(Get-Content -Path $latestLog.FullName -Tail 500 -ErrorAction SilentlyContinue)
            $lineCount = $linesArray.Count
            if ($lineCount -le 10) {
                continue
            }

            $actionCount = 0
            foreach ($line in $linesArray) {
                if ($line -match '● ') {
                    $actionCount++
                }
            }

            $ratio = 0
            if ($lineCount -gt 0) {
                $ratio = $actionCount / $lineCount
            }

            if ($ratio -lt 0.05) {
                $consecutiveLowActivity++
                Write-Host "Warning: Low activity detected. Ratio: $ratio ($actionCount/$lineCount). Consecutive checks: $consecutiveLowActivity/$maxConsecutiveLowActivity" -ForegroundColor Yellow
            }
            else {
                $consecutiveLowActivity = 0
            }

            if ($consecutiveLowActivity -lt $maxConsecutiveLowActivity) {
                continue
            }

            Write-Host "Error: Coworker loop detected! Killing process $($Process.Id)..." -ForegroundColor Red
            Stop-Process -Id $Process.Id -Force
            Abort-CoworkerTaskFromLog -LogName $latestLog.Name -RepoRoot $RepoRoot
            return 1
        }
        catch {
            Write-Host "Error reading/processing log file: $_"
        }
    }

    return $Process.ExitCode
}

function Abort-CoworkerTaskFromLog {
    param(
        [Parameter(Mandatory = $true)]
        [string]$LogName,
        [Parameter(Mandatory = $true)]
        [string]$RepoRoot
    )

    if ($LogName -notmatch '^\d{6}-(.*)\.copilot\.log\.stdout$') {
        Write-Host "Could not determine task name from log file: $LogName"
        return
    }

    $taskBaseName = $Matches[1]
    Write-Host "Aborting task: $taskBaseName"

    $workingDir = Join-Path $RepoRoot 'coworker\tasks\2working'
    $abortedDir = Join-Path $RepoRoot 'coworker\tasks\3_5aborted'
    if (-not (Test-Path $abortedDir)) {
        New-Item -ItemType Directory -Path $abortedDir | Out-Null
    }

    $taskFiles = Get-ChildItem -Path $workingDir -Filter "$taskBaseName*" -ErrorAction SilentlyContinue
    foreach ($file in $taskFiles) {
        $destination = Join-Path $abortedDir $file.Name
        Move-Item -Path $file.FullName -Destination $destination -Force
        Write-Host "Moved task file to: $destination" -ForegroundColor Red
    }
}

function Invoke-CoworkerPeriodicCheck {
    param(
        [Parameter(Mandatory = $true)]
        [string]$RepoRoot,
        [Parameter(Mandatory = $true)]
        [string]$ScriptPath,
        [Parameter(Mandatory = $true)]
        [string]$ScriptName,
        [Parameter(Mandatory = $true)]
        [string]$WrapperName,
        [Parameter(Mandatory = $true)]
        [string]$MonitorScriptPath,
        [Parameter(Mandatory = $true)]
        [bool]$EnableMonitor
    )

    if ($EnableMonitor) {
        Invoke-TaskSourceMonitor -MonitorScriptPath $MonitorScriptPath
    }

    $timestamp = (Get-Date).ToUniversalTime().ToString('yyyy-MM-dd HH:mm:ss')
    if (-not (Test-HasPendingCoworkerTasks -RepoRoot $RepoRoot)) {
        Write-Host "$timestamp - No tasks found in 1created or 5approved. Skipping check."
        return 0
    }

    $running = Get-RunningCoworkerProcesses -ScriptName $ScriptName -WrapperName $WrapperName
    if ($running.Count -gt 0) {
        Write-Host "$timestamp - $ScriptName is already running."
        return 0
    }

    Write-Host "$timestamp - $ScriptName is NOT running. Starting it..."
    try {
        $process = Start-Process -FilePath 'pwsh' -ArgumentList @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $ScriptPath) -WorkingDirectory $RepoRoot -PassThru
        Write-Host "Started $ScriptName with PID: $($process.Id)"
        $exitCode = Monitor-CoworkerProcess -Process $process -RepoRoot $RepoRoot
        Write-Host "Finished $ScriptName."
        return $exitCode
    }
    catch {
        Write-Error "Failed to start ${ScriptName}: $_"
        return 1
    }
}

$repoRoot = Get-RepoRoot
Set-Location $repoRoot

$scriptPath = Join-Path $repoRoot 'coworker\scripts\coworker.ps1'
$scriptName = 'coworker.ps1'
$wrapperName = 'process-coworker-queue.ps1'
$monitorScriptPath = Join-Path $repoRoot 'coworker\scripts\process-task-source.ps1'

Write-Host "Monitoring $scriptName..."
Write-Host "Script path: $scriptPath"
if ($Monitor) {
    Write-Host "Task source monitoring enabled using: $monitorScriptPath"
}

while ($true) {
    $exitCode = Invoke-CoworkerPeriodicCheck `
        -RepoRoot $repoRoot `
        -ScriptPath $scriptPath `
        -ScriptName $scriptName `
        -WrapperName $wrapperName `
        -MonitorScriptPath $monitorScriptPath `
        -EnableMonitor $Monitor.IsPresent

    if ($Once) {
        exit $exitCode
    }

    Start-Sleep -Seconds $IntervalSeconds
}
