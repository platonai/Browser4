# ═══════════════════════════════════════════════════════════════════
# CROSS-PLATFORM: This script must run on Linux, macOS, and Windows.
# ═══════════════════════════════════════════════════════════════════

<#
.SYNOPSIS
H3 — Temp file cleanup: removes stale temp, lock, and log files.

.DESCRIPTION
Cleans up temporary files, lock files, and rotated log files older
than the configured age. Targets:
  - coworker/tasks/.locks/
  - coworker/tasks/300logs/ (older than retention)
  - Generic *.tmp, *.lock files

.PARAMETER MaxAgeDays
Maximum age before cleanup. Default from thresholds (7 days).

.PARAMETER Paths
Directories to clean. Default targets lock and log dirs.

.PARAMETER DryRun
If set, only lists what would be cleaned. Default: $true.

.PARAMETER Force
If set, actually performs cleanup.

.OUTPUTS
Standard maintenance result object.
#>

param(
    [int]$MaxAgeDays = 0,
    [string[]]$Paths = @("coworker\tasks\.locks", "coworker\tasks\300logs"),
    [switch]$Force,
    [switch]$DryRun
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Continue"

$ScriptDir = $PSScriptRoot
. (Join-Path $ScriptDir "..\common\MaintenanceUtil.ps1")

$result = New-MaintenanceResult -CheckId "H3" -Name "Temp File Cleanup"
$repoRoot = Get-RepositoryRoot

if ($MaxAgeDays -le 0) {
    $MaxAgeDays = Get-MaintenanceThreshold -Section "LogHealth" -Key "TempFileMaxAgeDays" -Default 7
}

$actuallyDelete = $Force -and -not $DryRun
$cutoff = (Get-Date).AddDays(-$MaxAgeDays)
$retentionDays = Get-MaintenanceThreshold -Section "LogHealth" -Key "RetentionDays" -Default 14
$retentionCutoff = (Get-Date).AddDays(-$retentionDays)
$totalCleaned = 0

foreach ($path in $Paths) {
    $fullPath = Resolve-MaintenancePath $path
    if (-not (Test-Path $fullPath)) {
        Add-MaintenanceResult -Result $result -Item $path -Status "skipped" -Message "Directory not found"
        continue
    }

    $files = Get-ChildItem $fullPath -Recurse -File -ErrorAction SilentlyContinue `
        | Where-Object { $_.LastWriteTime -lt $cutoff }

    foreach ($file in $files) {
        # Keep recent log files within retention period
        if ($path -match "300logs" -and $file.LastWriteTime -gt $retentionCutoff) {
            continue
        }

        $relPath = $file.FullName.Replace($repoRoot, "").TrimStart("\", "/")
        $sizeKB = [math]::Round($file.Length / 1KB, 1)
        $age = [math]::Round(((Get-Date) - $file.LastWriteTime).TotalDays, 1)

        if ($actuallyDelete) {
            try {
                Remove-Item $file.FullName -Force -ErrorAction Stop
                Add-MaintenanceResult -Result $result -Item $relPath -Status "passed" -Message "Removed (${sizeKB} KB, ${age}d old)"
                $totalCleaned++
            }
            catch {
                Add-MaintenanceResult -Result $result -Item $relPath -Status "error" -Message "Failed: $($_.Exception.Message)"
            }
        }
        else {
            Add-MaintenanceResult -Result $result -Item $relPath -Status "passed" -Message "[DRY-RUN] ${sizeKB} KB, ${age}d old"
        }
    }
}

$action = if ($actuallyDelete) { "Removed" } else { "Would remove (dry-run)" }
$result.Details = "$action $totalCleaned files"

if ($result.Results.Count -eq 0) {
    $result.Details = "No stale files found"
    Add-MaintenanceResult -Result $result -Item "Cleanup" -Status "passed" -Message "Nothing to clean"
}

if (-not $actuallyDelete) {
    Write-Host "DRY-RUN: Use -Force to actually delete." -ForegroundColor Yellow
}

Set-MaintenanceResultSummary -Result $result
$result
