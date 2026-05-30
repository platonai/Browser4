#!/usr/bin/env pwsh

param(
    [int]$IntervalSeconds = 15,
    [switch]$Once
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$configScriptPath = Join-Path $PSScriptRoot 'config.ps1'
. $configScriptPath

function Test-HasPendingCoworkerTasks {
    param(
        [Parameter(Mandatory = $true)]
        [string]$RepoRoot
    )

    $createdTasks = Get-ChildItem -Path (Join-Path $RepoRoot 'coworker\tasks\1created') -File -ErrorAction SilentlyContinue
    $approvedTasks = Get-ChildItem -Path (Join-Path $RepoRoot 'coworker\tasks\5approved') -File -Recurse -ErrorAction SilentlyContinue
    return [bool]($createdTasks -or $approvedTasks)
}

function Get-RunningCoworkerProcesses {
    param(
        [Parameter(Mandatory = $true)]
        [string]$ScriptName,
        [Parameter(Mandatory = $true)]
        [string]$WrapperName
    )

    return @(Get-CimInstance Win32_Process | Where-Object {
        $_.Name -match 'pwsh|powershell' -and
        $_.CommandLine -match [regex]::Escape($ScriptName) -and
        $_.CommandLine -notmatch [regex]::Escape($WrapperName) -and
        $_.ProcessId -ne $PID
    })
}

function Get-CurrentPowerShellExecutable {
    try {
        $currentProcess = Get-Process -Id $PID -ErrorAction Stop
        if (-not [string]::IsNullOrWhiteSpace($currentProcess.Path)) {
            return $currentProcess.Path
        }
    }
    catch {
    }

    if ($PSVersionTable.PSEdition -eq 'Desktop') {
        return (Join-Path $PSHOME 'powershell.exe')
    }

    return (Join-Path $PSHOME 'pwsh.exe')
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
        [string]$WorkingDirectory,
        [Parameter(Mandatory = $true)]
        [string]$PowerShellExecutable
    )

    if (-not (Test-HasPendingCoworkerTasks -RepoRoot $RepoRoot)) {
        Write-CoworkerLog -Component 'process-coworker-queue' -Level 'DEBUG' -Message 'No tasks found in 1created or 5approved.'
        return [pscustomobject]@{ ExitCode = 0; Action = 'Idle' }
    }

    $running = @(Get-RunningCoworkerProcesses -ScriptName $ScriptName -WrapperName $WrapperName)
    if ($running.Count -gt 0) {
        Write-CoworkerLog -Component 'process-coworker-queue' -Level 'DEBUG' -Message ("{0} is already running." -f $ScriptName)
        return [pscustomobject]@{ ExitCode = 0; Action = 'AlreadyRunning' }
    }

    $process = Start-Process -FilePath $PowerShellExecutable -ArgumentList @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $ScriptPath) -WorkingDirectory $WorkingDirectory -PassThru
    Write-CoworkerLog -Component 'process-coworker-queue' -Message ("Started {0} with PID {1}." -f $ScriptName, $process.Id)
    return [pscustomobject]@{ ExitCode = 0; Action = 'Started' }
}

function Wait-ForCoworkerQueueSignal {
    param(
        [Parameter(Mandatory = $true)]
        [int]$TimeoutSeconds
    )

    $eventRecord = Wait-Event -Timeout $TimeoutSeconds
    if ($null -ne $eventRecord) {
        Remove-Event -EventIdentifier $eventRecord.EventIdentifier -ErrorAction SilentlyContinue
    }

    while ($true) {
        $queuedEvent = Wait-Event -Timeout 0
        if ($null -eq $queuedEvent) {
            break
        }

        Remove-Event -EventIdentifier $queuedEvent.EventIdentifier -ErrorAction SilentlyContinue
    }
}

$repoRoot = Get-WorkspaceRoot
$workingDirectory = Get-SchedulerWorkingDirectory
$powerShellExecutable = Get-CurrentPowerShellExecutable
$scriptPath = Join-Path $PSScriptRoot 'coworker.ps1'
$scriptName = 'coworker.ps1'
$wrapperName = 'process-coworker-queue.ps1'
$watchPaths = @(
    (Join-Path $repoRoot 'coworker\tasks\1created')
    (Join-Path $repoRoot 'coworker\tasks\5approved')
)
$watchRegistrations = @()

Write-CoworkerLog -Component 'process-coworker-queue' -Message ("Monitoring {0}" -f $scriptName)
Write-CoworkerLog -Component 'process-coworker-queue' -Level 'DEBUG' -Message ("Script path: {0}" -f $scriptPath)

try {
    if (-not $Once) {
        foreach ($watchPath in $watchPaths) {
            $watchRegistrations += New-CoworkerFileWatcher -Path $watchPath -SourcePrefix 'process-coworker-queue'
        }
    }

    while ($true) {
        $result = Invoke-CoworkerPeriodicCheck `
            -RepoRoot $repoRoot `
            -ScriptPath $scriptPath `
            -ScriptName $scriptName `
            -WrapperName $wrapperName `
            -WorkingDirectory $workingDirectory `
            -PowerShellExecutable $powerShellExecutable

        if ($Once) {
            exit $result.ExitCode
        }

        Wait-ForCoworkerQueueSignal -TimeoutSeconds $IntervalSeconds
    }
}
catch {
    Write-CoworkerLog -Component 'process-coworker-queue' -Level 'ERROR' -Message $_.Exception.Message
    exit 1
}
finally {
    foreach ($registration in @($watchRegistrations)) {
        Remove-CoworkerFileWatcher -Registration $registration
    }
}
