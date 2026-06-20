# ═══════════════════════════════════════════════════════════════════
# CROSS-PLATFORM: This script must run on Linux, macOS, and Windows.
# ═══════════════════════════════════════════════════════════════════

<#
.SYNOPSIS
A2 — Fast tests: runs fast JUnit unit tests only.

.DESCRIPTION
Runs Maven tests filtered to Unit + Fast, excluding slow, heavy,
integration, E2E, and manual-only tests. Mirrors the default `mvn test`
semantics defined in docs/TESTING.md.

Uses surefire groups exclusion to match the semantics:
  Level = Unit AND Cost = Fast AND NOT ManualOnly

.PARAMETER MavenProfiles
Maven profiles. Default: "all-main-modules,all-test-modules"

.PARAMETER ExcludedGroups
JUnit tag groups to exclude. Default excludes Slow, Heavy, Integration,
E2E, ManualOnly, and Requires*.

.PARAMETER TimeoutMinutes
Max test execution time. Default from thresholds (MaxFastDurationSeconds/60).

.OUTPUTS
Standard maintenance result object.
#>

param(
    [string]$MavenProfiles = "all-main-modules,all-test-modules",
    [string]$ExcludedGroups = "",
    [int]$TimeoutMinutes = 0
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Continue"

$ScriptDir = $PSScriptRoot
. (Join-Path $ScriptDir "..\common\MaintenanceUtil.ps1")

$result = New-MaintenanceResult -CheckId "A2" -Name "Fast Unit Tests"

if ([string]::IsNullOrWhiteSpace($ExcludedGroups)) {
    $ExcludedGroups = "Slow,Heavy,Integration,E2E,SDK,ManualOnly,RequiresServer,RequiresBrowser,RequiresAI,RequiresDocker,SkippableLowerLevel,TestInfraCheck,OptionalTest"
}

if ($TimeoutMinutes -le 0) {
    $maxSecs = Get-MaintenanceThreshold -Section "TestHealth" -Key "MaxFastDurationSeconds" -Default 300
    $TimeoutMinutes = [math]::Ceiling($maxSecs / 60)
}

$repoRoot = Get-RepositoryRoot
$mvnCmd = if (Test-IsWindows) { ".\mvnw.cmd" } else { "./mvnw" }

$timeoutSecs = $TimeoutMinutes * 60

$testResult = Invoke-MaintenanceStep `
    -StepName "Maven Fast Tests" `
    -WorkingDirectory $repoRoot `
    -TimeoutSeconds $timeoutSecs `
    -ScriptBlock {
        $groups = "-Dgroups=""!$ExcludedGroups"""
        $cmd = "& 'mvnCmd' test -P 'MavenProfiles' $groups -Dsurefire.failIfNoSpecifiedTests=false"
        Invoke-Expression $cmd 2>&1
        $LASTEXITCODE
    }

if ($testResult.ExitCode -eq 0) {
    Add-MaintenanceResult -Result $result -Item "Fast Tests" -Status "passed" -Message "All tests passed ($($testResult.DurationMs)ms)"
}
else {
    Add-MaintenanceResult -Result $result -Item "Fast Tests" -Status "failed" -Message "Exit code $($testResult.ExitCode)"
}

Set-MaintenanceResultSummary -Result $result
$result
