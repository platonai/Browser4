# ═══════════════════════════════════════════════════════════════════
# CROSS-PLATFORM: This script must run on Linux, macOS, and Windows.
# ═══════════════════════════════════════════════════════════════════

<#
.SYNOPSIS
H1 — Log directory size audit: monitors log file growth.

.DESCRIPTION
Checks the size of log directories against configured limits:
  - Total directory size ≤ MaxTotalMB (default 500)
  - Single file size ≤ MaxFileMB (default 50)

Flags oversized directories or files as warnings.

.PARAMETER LogDirs
Array of log directories to check. Default: "logs", "coworker/tasks/300logs",
"bin/maintenance/logs"

.PARAMETER MaxTotalMB
Maximum total directory size in MB. Default from thresholds.

.PARAMETER MaxFileMB
Maximum single file size in MB. Default from thresholds.

.OUTPUTS
Standard maintenance result object.
#>

param(
    [string[]]$LogDirs = @("logs", "coworker\tasks\300logs", "bin\maintenance\logs"),
    [int]$MaxTotalMB = 0,
    [int]$MaxFileMB = 0
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Continue"

$ScriptDir = $PSScriptRoot
. (Join-Path $ScriptDir "..\common\MaintenanceUtil.ps1")

$result = New-MaintenanceResult -CheckId "H1" -Name "Log Directory Size Audit"
$repoRoot = Get-RepositoryRoot

if ($MaxTotalMB -le 0) {
    $MaxTotalMB = Get-MaintenanceThreshold -Section "LogHealth" -Key "MaxTotalMB" -Default 500
}
if ($MaxFileMB -le 0) {
    $MaxFileMB = Get-MaintenanceThreshold -Section "LogHealth" -Key "MaxFileMB" -Default 50
}

$maxTotalBytes = $MaxTotalMB * 1MB
$maxFileBytes  = $MaxFileMB * 1MB
$grandTotalBytes = 0

foreach ($logDir in $LogDirs) {
    $fullPath = Resolve-MaintenancePath $logDir
    if (-not (Test-Path $fullPath)) {
        Add-MaintenanceResult -Result $result -Item $logDir -Status "skipped" -Message "Directory not found"
        continue
    }

    $totalSize = 0
    $largeFiles = @()

    $files = Get-ChildItem $fullPath -Recurse -File -ErrorAction SilentlyContinue
    foreach ($file in $files) {
        $totalSize += $file.Length
        if ($file.Length -gt $maxFileBytes) {
            $largeFiles += "$($file.Name) ($([math]::Round($file.Length / 1MB, 1)) MB)"
        }
    }

    $grandTotalBytes += $totalSize
    $totalMB = [math]::Round($totalSize / 1MB, 1)

    if ($totalSize -gt $maxTotalBytes) {
        Add-MaintenanceResult -Result $result -Item $logDir -Status "failed" -Message "${totalMB} MB exceeds limit of ${MaxTotalMB} MB"
    }
    else {
        Add-MaintenanceResult -Result $result -Item $logDir -Status "passed" -Message "${totalMB} MB"
    }

    foreach ($lf in $largeFiles) {
        Add-MaintenanceResult -Result $result -Item "$logDir / $lf" -Status "failed" -Message "Exceeds max file size ${MaxFileMB} MB"
    }
}

$grandTotalMB = [math]::Round($grandTotalBytes / 1MB, 1)
$result.Details = "Total across all log dirs: ${grandTotalMB} MB"

Set-MaintenanceResultSummary -Result $result
$result
