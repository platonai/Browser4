# ═══════════════════════════════════════════════════════════════════
# CROSS-PLATFORM: This script must run on Linux, macOS, and Windows.
# ═══════════════════════════════════════════════════════════════════

<#
.SYNOPSIS
E1 - Version consistency: verifies version alignment across key files.

.DESCRIPTION
Delegates to the unified version check script (bin/version.mjs check) which
verifies consistency across VERSION, pom.xml, Cargo.toml, and package.json.

.OUTPUTS
Standard maintenance result object.
#>

param()

Set-StrictMode -Version Latest
$ErrorActionPreference = "Continue"

$ScriptDir = $PSScriptRoot
. (Join-Path $ScriptDir "..\common\MaintenanceUtil.ps1")

$result = New-MaintenanceResult -CheckId "E1" -Name "Version Consistency"
$repoRoot = Get-RepositoryRoot

$versionScript = Join-Path $repoRoot "bin\version.mjs"
if (-not (Test-Path $versionScript)) {
    Add-MaintenanceResult -Result $result -Item "version.mjs" -Status "error" -Message "bin/version.mjs not found"
    Set-MaintenanceResultSummary -Result $result
    $result
    return
}

# Run the unified check and consume its exit code
$output = & node $versionScript check 2>&1
$exitCode = $LASTEXITCODE

if ($exitCode -eq 0) {
    Add-MaintenanceResult -Result $result -Item "version.mjs check" -Status "passed" -Message "All version checks passed"
} else {
    Add-MaintenanceResult -Result $result -Item "version.mjs check" -Status "failed" -Message "Version inconsistency detected. See output above."
}

# Capture VERSION for reporting
$versionPath = Join-Path $repoRoot "VERSION"
if (Test-Path $versionPath) {
    $versionFileVersion = (Get-Content $versionPath -Raw).Trim()
    Add-MaintenanceResult -Result $result -Item "VERSION" -Status "passed" -Message "VERSION file: $versionFileVersion"
}

Set-MaintenanceResultSummary -Result $result
$result
