# ═══════════════════════════════════════════════════════════════════
# CROSS-PLATFORM: This script must run on Linux, macOS, and Windows.
# ═══════════════════════════════════════════════════════════════════

<#
.SYNOPSIS
F1 — Maven dependency convergence: checks for version conflicts.

.DESCRIPTION
Runs mvn dependency:tree -Dverbose and scans output for version conflict
markers (omitted for conflict, duplicate declarations).

.PARAMETER MavenProfiles
Maven profiles. Default: "all-main-modules,all-test-modules"

.OUTPUTS
Standard maintenance result object.
#>

param(
    [string]$MavenProfiles = "all-main-modules,all-test-modules"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Continue"

$ScriptDir = $PSScriptRoot
. (Join-Path $ScriptDir "..\common\MaintenanceUtil.ps1")

$result = New-MaintenanceResult -CheckId "F1" -Name "Maven Dependency Convergence"
$repoRoot = Get-RepositoryRoot
$mvnCmd = if (Test-IsWindows) { ".\mvnw.cmd" } else { "./mvnw" }

$treeResult = Invoke-MaintenanceStep `
    -StepName "Maven Dependency Tree" `
    -WorkingDirectory $repoRoot `
    -TimeoutSeconds 300 `
    -ScriptBlock {
        & $mvnCmd dependency:tree -Dverbose -P "$MavenProfiles" 2>&1
        $LASTEXITCODE
    }

$conflicts = ($treeResult.Stdout -split "`n" | Select-String -Pattern "omitted for conflict|convergence error|duplicate")
$conflictCount = $conflicts.Count

if ($conflictCount -eq 0) {
    Add-MaintenanceResult -Result $result -Item "Dependency tree" -Status "passed" -Message "No conflicts detected"
}
else {
    Add-MaintenanceResult -Result $result -Item "Dependency tree" -Status "failed" -Message "$conflictCount conflicts/duplicates detected"
    foreach ($c in ($conflicts | Select-Object -First 10)) {
        Add-MaintenanceResult -Result $result -Item $c.Line.Trim() -Status "failed" -Message "Conflict"
    }
    if ($conflictCount -gt 10) {
        Add-MaintenanceResult -Result $result -Item "...and $($conflictCount - 10) more" -Status "failed" -Message "Truncated"
    }
}

Set-MaintenanceResultSummary -Result $result
$result
