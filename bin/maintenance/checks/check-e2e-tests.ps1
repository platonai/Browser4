# ═══════════════════════════════════════════════════════════════════
# CROSS-PLATFORM: This script must run on Linux, macOS, and Windows.
# ═══════════════════════════════════════════════════════════════════

<#
.SYNOPSIS
B2 — E2E tests: runs end-to-end browser tests.

.DESCRIPTION
Runs Maven E2E tests tagged E2E + Heavy, requiring browser and AI.
Requires full Docker stack. These are the most expensive tests.

.PARAMETER MavenProfiles
Maven profiles. Default: "all-main-modules,all-test-modules"

.PARAMETER TimeoutMinutes
Max test execution time. Default: 60 minutes.

.OUTPUTS
Standard maintenance result object.
#>

param(
    [string]$MavenProfiles = "all-main-modules,all-test-modules",
    [int]$TimeoutMinutes = 60
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Continue"

$ScriptDir = $PSScriptRoot
. (Join-Path $ScriptDir "..\common\MaintenanceUtil.ps1")

$result = New-MaintenanceResult -CheckId "B2" -Name "End-to-End Tests"
$repoRoot = Get-RepositoryRoot
$mvnCmd = if (Test-IsWindows) { ".\mvnw.cmd" } else { "./mvnw" }
$timeoutSecs = $TimeoutMinutes * 60

$testResult = Invoke-MaintenanceStep `
    -StepName "Maven E2E Tests" `
    -WorkingDirectory $repoRoot `
    -TimeoutSeconds $timeoutSecs `
    -ScriptBlock {
        & $using:mvnCmd test -P "$using:MavenProfiles" -DrunE2ETests=true `
            "-Dgroups=E2E" `
            -Dsurefire.failIfNoSpecifiedTests=false 2>&1
        $LASTEXITCODE
    }

if ($testResult.ExitCode -eq 0) {
    Add-MaintenanceResult -Result $result -Item "E2E Tests" -Status "passed" -Message "All passed ($($testResult.DurationMs)ms)"
}
else {
    Add-MaintenanceResult -Result $result -Item "E2E Tests" -Status "failed" -Message "Exit code $($testResult.ExitCode)"
}

Set-MaintenanceResultSummary -Result $result
$result
