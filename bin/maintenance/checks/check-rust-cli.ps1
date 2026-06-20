# ═══════════════════════════════════════════════════════════════════
# CROSS-PLATFORM: This script must run on Linux, macOS, and Windows.
# ═══════════════════════════════════════════════════════════════════

<#
.SYNOPSIS
B4 — Rust CLI check: runs cargo test and cargo clippy.

.DESCRIPTION
Runs cargo test (unit tests only, no e2e) and cargo clippy with
-D warnings to ensure the Rust CLI compiles cleanly and passes tests.

.PARAMETER ClippyArgs
Additional args for cargo clippy. Default: "-- -D warnings"

.PARAMETER ManifestPath
Path to Cargo.toml. Default: cli/browser4-cli/Cargo.toml

.OUTPUTS
Standard maintenance result object.
#>

param(
    [string]$ClippyArgs = "-- -D warnings",
    [string]$ManifestPath = "cli\browser4-cli\Cargo.toml"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Continue"

$ScriptDir = $PSScriptRoot
. (Join-Path $ScriptDir "..\common\MaintenanceUtil.ps1")

$result = New-MaintenanceResult -CheckId "B4" -Name "Rust CLI Check"
$repoRoot = Get-RepositoryRoot
$cliDir = Split-Path (Resolve-MaintenancePath $ManifestPath) -Parent

if (-not (Test-Path (Join-Path $cliDir "Cargo.toml"))) {
    Add-MaintenanceResult -Result $result -Item "cargo test" -Status "skipped" -Message "Cargo.toml not found at $cliDir"
    Set-MaintenanceResultSummary -Result $result
    $result
    return
}

# ── Cargo test ──
$testResult = Invoke-MaintenanceStep `
    -StepName "Cargo Test" `
    -WorkingDirectory $cliDir `
    -TimeoutSeconds 600 `
    -ScriptBlock {
        cargo test --lib 2>&1
        $LASTEXITCODE
    }

if ($testResult.ExitCode -eq 0) {
    Add-MaintenanceResult -Result $result -Item "cargo test" -Status "passed" -Message "All tests passed ($($testResult.DurationMs)ms)"
}
else {
    Add-MaintenanceResult -Result $result -Item "cargo test" -Status "failed" -Message "Exit code $($testResult.ExitCode)"
}

# ── Cargo clippy ──
$clippyResult = Invoke-MaintenanceStep `
    -StepName "Cargo Clippy" `
    -WorkingDirectory $cliDir `
    -TimeoutSeconds 300 `
    -ScriptBlock {
        cargo clippy --all-targets $ClippyArgs 2>&1
        $LASTEXITCODE
    }

if ($clippyResult.ExitCode -eq 0) {
    Add-MaintenanceResult -Result $result -Item "cargo clippy" -Status "passed" -Message "No warnings ($($clippyResult.DurationMs)ms)"
}
else {
    Add-MaintenanceResult -Result $result -Item "cargo clippy" -Status "failed" -Message "Clippy found issues"
}

Set-MaintenanceResultSummary -Result $result
$result
