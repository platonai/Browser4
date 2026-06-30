# ═══════════════════════════════════════════════════════════════════
# CROSS-PLATFORM: This script must run on Linux, macOS, and Windows.
# - Use $IsWindows / $IsLinux / $IsMacOS for platform detection.
# - Use "($IsWindows -or $env:OS -eq 'Windows_NT')" for PS 5.1 compat.
# ═══════════════════════════════════════════════════════════════════

<#
.SYNOPSIS
A1 — Compilation check: compiles all Maven + Cargo modules without tests.

.DESCRIPTION
Runs Maven compile (all-main-modules profile) and Cargo check on the CLI.
Verifies the project compiles cleanly before any other checks run.

.PARAMETER MavenProfiles
Maven profiles to activate. Default: "all-main-modules"

.PARAMETER SkipCargo
If set, skips the Cargo/Rust check entirely.

.OUTPUTS
Standard maintenance result object.
#>

param(
    [string]$MavenProfiles = "all-main-modules",
    [switch]$SkipCargo
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Continue"

$ScriptDir = $PSScriptRoot
. (Join-Path $ScriptDir "..\common\MaintenanceUtil.ps1")

$result = New-MaintenanceResult -CheckId "A1" -Name "Compilation Check"
$repoRoot = Get-RepositoryRoot

# ── Maven compilation ──
$mvnCmd = if (Test-IsWindows) { ".\mvnw.cmd" } else { "./mvnw" }

$mavenResult = Invoke-MaintenanceStep `
    -StepName "Maven Compile" `
    -WorkingDirectory $repoRoot `
    -TimeoutSeconds 900 `
    -ScriptBlock {
        & $mvnCmd compile -pl !browser4-tests -P "$MavenProfiles" -DskipTests -q 2>&1
        $LASTEXITCODE
    }

if ($mavenResult.ExitCode -eq 0) {
    Add-MaintenanceResult -Result $result -Item "Maven (all-main-modules)" -Status "passed" -Message "Compiled in $($mavenResult.DurationMs)ms"
}
else {
    Add-MaintenanceResult -Result $result -Item "Maven (all-main-modules)" -Status "failed" -Message "Exit code $($mavenResult.ExitCode)"
}

# ── Cargo compilation ──
if (-not $SkipCargo) {
    $cliDir = Join-Path $repoRoot "cli\browser4-cli"
    if (Test-Path (Join-Path $cliDir "Cargo.toml")) {
        $cargoResult = Invoke-MaintenanceStep `
            -StepName "Cargo Check" `
            -WorkingDirectory $cliDir `
            -TimeoutSeconds 300 `
            -ScriptBlock {
                cargo check 2>&1
                $LASTEXITCODE
            }

        if ($cargoResult.ExitCode -eq 0) {
            Add-MaintenanceResult -Result $result -Item "Cargo (browser4-cli)" -Status "passed" -Message "Checked in $($cargoResult.DurationMs)ms"
        }
        else {
            Add-MaintenanceResult -Result $result -Item "Cargo (browser4-cli)" -Status "failed" -Message "Exit code $($cargoResult.ExitCode)"
        }
    }
    else {
        Add-MaintenanceResult -Result $result -Item "Cargo (browser4-cli)" -Status "skipped" -Message "Cargo.toml not found"
    }
}

Set-MaintenanceResultSummary -Result $result
$result
