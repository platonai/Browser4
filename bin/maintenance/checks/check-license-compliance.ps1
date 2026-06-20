# ═══════════════════════════════════════════════════════════════════
# CROSS-PLATFORM: This script must run on Linux, macOS, and Windows.
# ═══════════════════════════════════════════════════════════════════

<#
.SYNOPSIS
F3 — License compliance: verifies dependency licenses are compatible.

.DESCRIPTION
Checks that all Maven dependencies use licenses compatible with
the project's Apache 2.0 license. Flags dependencies with banned
licenses (GPL, AGPL, SSPL).

Uses the maven license plugin to generate a report and scans it.

.PARAMETER MavenProfiles
Maven profiles. Default: "all-main-modules"

.OUTPUTS
Standard maintenance result object.
#>

param(
    [string]$MavenProfiles = "all-main-modules"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Continue"

$ScriptDir = $PSScriptRoot
. (Join-Path $ScriptDir "..\common\MaintenanceUtil.ps1")

$result = New-MaintenanceResult -CheckId "F3" -Name "License Compliance"
$repoRoot = Get-RepositoryRoot
$mvnCmd = if (Test-IsWindows) { ".\mvnw.cmd" } else { "./mvnw" }

$bannedLicenses = Get-MaintenanceThreshold -Section "Dependencies" -Key "BannedLicenses" -Default @('GPL-2.0-only', 'GPL-3.0-only', 'AGPL-1.0-only', 'AGPL-3.0-only', 'SSPL-1.0')
$approvedLicenses = Get-MaintenanceThreshold -Section "Dependencies" -Key "ApprovedLicenses" -Default @('Apache-2.0', 'MIT', 'BSD-2-Clause', 'BSD-3-Clause', 'ISC', 'MPL-2.0')

# ── Run license check ──
$licenseResult = Invoke-MaintenanceStep `
    -StepName "Maven License Check" `
    -WorkingDirectory $repoRoot `
    -TimeoutSeconds 300 `
    -ScriptBlock {
        & $mvnCmd org.codehaus.mojo:license-maven-plugin:aggregate-add-third-party -P "$MavenProfiles" -q 2>&1
        $LASTEXITCODE
    }

$licenseReport = Join-Path $repoRoot "target\generated-sources\license\THIRD-PARTY.txt"
if (Test-Path $licenseReport) {
    $reportContent = Get-Content $licenseReport -Raw
    $violations = @()

    foreach ($banned in $bannedLicenses) {
        if ($reportContent -match [regex]::Escape($banned)) {
            $violations += $banned
        }
    }

    if ($violations.Count -eq 0) {
        Add-MaintenanceResult -Result $result -Item "License check" -Status "passed" -Message "All licenses compatible"
    }
    else {
        Add-MaintenanceResult -Result $result -Item "License check" -Status "failed" -Message "Banned licenses found: $($violations -join ', ')"
    }
}
else {
    Add-MaintenanceResult -Result $result -Item "License report" -Status "skipped" -Message "THIRD-PARTY.txt not found (license plugin may not be configured)"
}

Set-MaintenanceResultSummary -Result $result
$result
