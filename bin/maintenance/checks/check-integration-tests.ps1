# ═══════════════════════════════════════════════════════════════════
# CROSS-PLATFORM: This script must run on Linux, macOS, and Windows.
# ═══════════════════════════════════════════════════════════════════

<#
.SYNOPSIS
B1 — Integration tests: runs integration + slow tests.

.DESCRIPTION
Runs Maven integration tests tagged Integration + Slow, excluding
E2E, ManualOnly, and RequiresAI tests. Requires Docker for MongoDB
and dependent services.

.PARAMETER MavenProfiles
Maven profiles. Default: "all-main-modules,all-test-modules"

.PARAMETER TimeoutMinutes
Max test execution time. Default from thresholds.

.OUTPUTS
Standard maintenance result object.
#>

param(
    [string]$MavenProfiles = "all-main-modules,all-test-modules",
    [int]$TimeoutMinutes = 0
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Continue"

$ScriptDir = $PSScriptRoot
. (Join-Path $ScriptDir "..\common\MaintenanceUtil.ps1")

$result = New-MaintenanceResult -CheckId "B1" -Name "Integration Tests"
$repoRoot = Get-RepositoryRoot

if ($TimeoutMinutes -le 0) {
    $maxSecs = Get-MaintenanceThreshold -Section "TestHealth" -Key "MaxSlowDurationSeconds" -Default 1800
    $TimeoutMinutes = [math]::Ceiling($maxSecs / 60)
}

$mvnCmd = if (Test-IsWindows) { ".\mvnw.cmd" } else { "./mvnw" }
$timeoutSecs = $TimeoutMinutes * 60

$testResult = Invoke-MaintenanceStep `
    -StepName "Maven Integration Tests" `
    -WorkingDirectory $repoRoot `
    -TimeoutSeconds $timeoutSecs `
    -ScriptBlock {
        & $mvnCmd test -P "$MavenProfiles" -DrunITs=true `
            "-Dgroups=!E2E&!E2ETest&!ManualOnly&!RequiresAI&!RequiresBrowser&!TestInfraCheck&!OptionalTest&!SkippableLowerLevel" `
            -Dsurefire.failIfNoSpecifiedTests=false 2>&1
        $LASTEXITCODE
    }

if ($testResult.ExitCode -eq 0) {
    Add-MaintenanceResult -Result $result -Item "Integration Tests" -Status "passed" -Message "All passed ($($testResult.DurationMs)ms)"
}
else {
    Add-MaintenanceResult -Result $result -Item "Integration Tests" -Status "failed" -Message "Exit code $($testResult.ExitCode)"
}

Set-MaintenanceResultSummary -Result $result
$result
