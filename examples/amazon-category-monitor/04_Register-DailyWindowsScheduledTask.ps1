#Requires -Version 7.0
#!/usr/bin/env pwsh

<#
.SYNOPSIS
Registers a Windows Scheduled Task for the daily Amazon category monitor.

.EXAMPLE
pwsh ./04_Register-DailyWindowsScheduledTask.ps1 -At '02:30'

.EXAMPLE
pwsh ./04_Register-DailyWindowsScheduledTask.ps1 -DataRoot "$HOME/.browser4/amazon-category-monitor" -TaskName 'Browser4 Amazon Category Monitor' -At '03:00'
#>

[CmdletBinding()]
param(
    [string]$DataRoot = "",
    [string]$TaskName = 'Browser4 Amazon Category Monitor',
    [ValidatePattern('^\d{2}:\d{2}$')][string]$At = '02:30'
)

if (-not $IsWindows) {
    $invokePath = Join-Path $PSScriptRoot '02_Run-DailyCategorySnapshot.ps1'
    Write-Host "This registrar is Windows-only. On Linux/macOS add a cron entry such as:"
    Write-Host "30 2 * * * cd '$PSScriptRoot' && pwsh '$invokePath' >> monitor.log 2>&1"
    exit 2
}

. (Join-Path $PSScriptRoot 'AmazonCategoryMonitor.Common.ps1')
if (-not $DataRoot) { $DataRoot = Get-DefaultMonitorDataRoot }
$DataRoot = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($DataRoot)
if (-not (Test-Path -LiteralPath (Join-Path $DataRoot 'config.json') -PathType Leaf)) {
    throw "Monitor is not initialized in $DataRoot. Run 01_Init-ManifestFromSeed.ps1 first."
}
$startAt = [DateTime]::ParseExact($At, 'HH:mm', [Globalization.CultureInfo]::InvariantCulture)
$invokePath = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '02_Run-DailyCategorySnapshot.ps1')).Path
$pwshPath = (Get-Process -Id $PID).Path
$actionArguments = "-NoProfile -ExecutionPolicy Bypass -File `"$invokePath`" -DataRoot `"$DataRoot`""
$action = New-ScheduledTaskAction -Execute $pwshPath -Argument $actionArguments -WorkingDirectory $PSScriptRoot
$trigger = New-ScheduledTaskTrigger -Daily -At $startAt
$settings = New-ScheduledTaskSettingsSet -StartWhenAvailable -MultipleInstances IgnoreNew `
    -RandomDelay (New-TimeSpan -Minutes 30) -ExecutionTimeLimit ([TimeSpan]::Zero)
$description = "Fetches the Amazon category manifest into $DataRoot once per day. Run logs and CSV snapshots are under daily/."

Register-ScheduledTask -TaskName $TaskName -Action $action -Trigger $trigger -Settings $settings `
    -Description $description -Force | Out-Null
Write-Host "Scheduled task registered: $TaskName"
Write-Host "  Start:    daily at $At, with up to 30 minutes random delay"
Write-Host "  Command:  $pwshPath $actionArguments"
Write-Host "  Inspect:  Get-ScheduledTask -TaskName '$TaskName'"
Write-Host "  Run now:  Start-ScheduledTask -TaskName '$TaskName'"
