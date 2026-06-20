# ═══════════════════════════════════════════════════════════════════
# CROSS-PLATFORM: This script must run on Linux, macOS, and Windows.
# ═══════════════════════════════════════════════════════════════════

<#
.SYNOPSIS
H2 — Build artifact cleanup: removes stale target/ directories.

.DESCRIPTION
Removes target/ directories and other build artifacts older than
the configured age. Safe by default — runs in dry-run mode unless
-Force is specified.

.PARAMETER MaxAgeDays
Maximum age of build artifacts before cleanup. Default from thresholds (3 days).

.PARAMETER Paths
Array of directory patterns to clean. Default: "**/target"

.PARAMETER DryRun
If set, only lists what would be cleaned. Default: $true.

.PARAMETER Force
If set, actually performs cleanup. Requires explicit opt-in.

.OUTPUTS
Standard maintenance result object.
#>

param(
    [int]$MaxAgeDays = 0,
    [string[]]$Paths = @("**/target"),
    [switch]$Force,
    [switch]$DryRun
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Continue"

$ScriptDir = $PSScriptRoot
. (Join-Path $ScriptDir "..\common\MaintenanceUtil.ps1")

$result = New-MaintenanceResult -CheckId "H2" -Name "Build Artifact Cleanup"
$repoRoot = Get-RepositoryRoot

if ($MaxAgeDays -le 0) {
    $MaxAgeDays = Get-MaintenanceThreshold -Section "LogHealth" -Key "BuildArtifactMaxAgeDays" -Default 3
}

$actuallyDelete = $Force -and -not $DryRun
$cutoff = (Get-Date).AddDays(-$MaxAgeDays)
$totalSizeBytes = 0
$cleanedCount = 0

foreach ($pattern in $Paths) {
    $dirs = Get-ChildItem $repoRoot -Recurse -Directory -Filter "target" -ErrorAction SilentlyContinue `
        | Where-Object { $_.FullName -match "target$" -and $_.LastWriteTime -lt $cutoff }

    # Exclude critical paths
    $dirs = $dirs | Where-Object {
        $_.FullName -notmatch "target[\\/]generated-sources[\\/]license"
    }

    foreach ($dir in $dirs) {
        $size = (Get-ChildItem $dir.FullName -Recurse -File -ErrorAction SilentlyContinue | Measure-Object -Property Length -Sum).Sum
        $totalSizeBytes += $size
        $sizeMB = [math]::Round($size / 1MB, 1)
        $relPath = $dir.FullName.Replace($repoRoot, "").TrimStart("\", "/")
        $age = [math]::Round(((Get-Date) - $dir.LastWriteTime).TotalDays, 1)

        if ($actuallyDelete) {
            try {
                Remove-Item $dir.FullName -Recurse -Force -ErrorAction Stop
                Add-MaintenanceResult -Result $result -Item $relPath -Status "passed" -Message "Removed (${sizeMB} MB, ${age}d old)"
                $cleanedCount++
            }
            catch {
                Add-MaintenanceResult -Result $result -Item $relPath -Status "error" -Message "Failed to remove: $($_.Exception.Message)"
            }
        }
        else {
            Add-MaintenanceResult -Result $result -Item $relPath -Status "passed" -Message "[DRY-RUN] Would remove ${sizeMB} MB (${age}d old)"
        }
    }
}

$totalMB = [math]::Round($totalSizeBytes / 1MB, 1)
$action = if ($actuallyDelete) { "Removed" } else { "Would remove (dry-run)" }
$result.Details = "$action $cleanedCount directories, ${totalMB} MB total"

if (-not $actuallyDelete) {
    Write-Host "DRY-RUN: Use -Force to actually delete. $($result.Details)" -ForegroundColor Yellow
}

Set-MaintenanceResultSummary -Result $result
$result
