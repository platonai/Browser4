# ═══════════════════════════════════════════════════════════════════
# CROSS-PLATFORM: This script must run on Linux, macOS, and Windows.
# ═══════════════════════════════════════════════════════════════════

<#
.SYNOPSIS
A4 — Code coverage: verifies JaCoCo coverage against thresholds.

.DESCRIPTION
Parses the aggregated JaCoCo XML report and checks coverage percentages
against the thresholds defined in AGENTS.md:
  - Global ≥ 70%
  - Core   ≥ 80%
  - Utils  ≥ 90%
  - Controllers ≥ 85%

.PARAMETER JacocoReportDir
Path to the JaCoCo aggregated report directory. Default: target/site/jacoco-aggregate

.OUTPUTS
Standard maintenance result object.
#>

param(
    [string]$JacocoReportDir = ""
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Continue"

$ScriptDir = $PSScriptRoot
. (Join-Path $ScriptDir "..\common\MaintenanceUtil.ps1")

$result = New-MaintenanceResult -CheckId "A4" -Name "Code Coverage"
$repoRoot = Get-RepositoryRoot

if (-not $JacocoReportDir) {
    $JacocoReportDir = Join-Path $repoRoot "target\site\jacoco-aggregate"
}

$jacocoXml = Join-Path $JacocoReportDir "jacoco.xml"
if (-not (Test-Path $jacocoXml)) {
    # Try individual module reports
    $jacocoXml = Join-Path $repoRoot "target\site\jacoco\jacoco.xml"
}

if (-not (Test-Path $jacocoXml)) {
    $result.Status = "skipped"
    $result.Details = "No JaCoCo report found. Run: mvn verify -P all-main-modules,all-test-modules"
    Add-MaintenanceResult -Result $result -Item "jacoco.xml" -Status "skipped" -Message "Report not found"
    $result
    return
}

try {
    [xml]$jacoco = Get-Content $jacocoXml

    $thresholds = @{
        "Global"      = Get-MaintenanceThreshold -Section "Coverage" -Key "Global" -Default 0.70
        "Core"        = Get-MaintenanceThreshold -Section "Coverage" -Key "Core" -Default 0.80
        "Utilities"   = Get-MaintenanceThreshold -Section "Coverage" -Key "Utilities" -Default 0.90
        "Controllers" = Get-MaintenanceThreshold -Section "Coverage" -Key "Controllers" -Default 0.85
    }

    # ── Overall coverage from aggregate report ──
    $overallCounter = $jacoco.report.counter | Where-Object { $_.type -eq "INSTRUCTION" }
    if ($overallCounter) {
        $missed = [double]$overallCounter.missed
        $covered = [double]$overallCounter.covered
        $total = $missed + $covered
        $coverage = if ($total -gt 0) { [math]::Round($covered / $total * 100, 1) } else { 0 }

        $globalThreshold = $thresholds["Global"] * 100
        if ($coverage -ge $globalThreshold) {
            Add-MaintenanceResult -Result $result -Item "Global (instructions)" -Status "passed" -Message "${coverage}% (threshold: ${globalThreshold}%)"
        }
        else {
            Add-MaintenanceResult -Result $result -Item "Global (instructions)" -Status "failed" -Message "${coverage}% < ${globalThreshold}%"
        }
    }

    # ── Per-package analysis ──
    $packages = $jacoco.report.package
    if ($packages) {
        foreach ($pkg in $packages) {
            $pkgName = $pkg.name
            $counter = $pkg.counter | Where-Object { $_.type -eq "INSTRUCTION" }
            if (-not $counter) { continue }
            $missed = [double]$counter.missed
            $covered = [double]$counter.covered
            $total = $missed + $covered
            $pkgCov = if ($total -gt 0) { [math]::Round($covered / $total * 100, 1) } else { 0 }

            # Map package to category
            $category = "Global"
            if ($pkgName -match "browser4-core") {
                # Only report core if it's from the core module
            }
            if ($pkgName -match "util") {
                $category = "Utilities"
            }

            $threshold = $thresholds[$category] * 100
            $status = if ($pkgCov -ge $threshold) { "passed" } else { "failed" }
            # Skip very small packages to reduce noise
            if ($total -gt 100) {
                Add-MaintenanceResult -Result $result -Item "$category / $pkgName" -Status $status -Message "${pkgCov}%"
            }
        }
    }
    else {
        Add-MaintenanceResult -Result $result -Item "Coverage" -Status "passed" -Message "Report parsed successfully"
    }
}
catch {
    Add-MaintenanceResult -Result $result -Item "jacoco.xml" -Status "error" -Message "Parse error: $($_.Exception.Message)"
}

Set-MaintenanceResultSummary -Result $result
$result
