# ═══════════════════════════════════════════════════════════════════
# CROSS-PLATFORM: This script must run on Linux, macOS, and Windows.
# ═══════════════════════════════════════════════════════════════════

<#
.SYNOPSIS
F2 — Cargo audit: runs cargo audit for Rust dependency vulnerabilities.

.DESCRIPTION
Runs cargo audit against the browser4-cli Cargo.lock file to detect
known security vulnerabilities in Rust dependencies.

.PARAMETER ManifestPath
Path to Cargo.toml. Default: cli/browser4-cli/Cargo.toml

.OUTPUTS
Standard maintenance result object.
#>

param(
    [string]$ManifestPath = "cli\browser4-cli\Cargo.toml"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Continue"

$ScriptDir = $PSScriptRoot
. (Join-Path $ScriptDir "..\common\MaintenanceUtil.ps1")

$result = New-MaintenanceResult -CheckId "F2" -Name "Cargo Audit"
$repoRoot = Get-RepositoryRoot
$cliDir = Split-Path (Resolve-MaintenancePath $ManifestPath) -Parent

if (-not (Test-Path (Join-Path $cliDir "Cargo.toml"))) {
    $result.Status = "skipped"
    $result.Details = "Cargo.toml not found"
    Add-MaintenanceResult -Result $result -Item "Cargo.toml" -Status "skipped" -Message "Not found at $cliDir"
    $result
    return
}

# Ensure cargo-audit is installed
$auditAvailable = $null -ne (Get-Command cargo-audit -ErrorAction SilentlyContinue)
if (-not $auditAvailable) {
    try { cargo install cargo-audit --quiet 2>&1 | Out-Null }
    catch { }
    $auditAvailable = $null -ne (Get-Command cargo-audit -ErrorAction SilentlyContinue)
}

if (-not $auditAvailable) {
    Add-MaintenanceResult -Result $result -Item "cargo-audit" -Status "skipped" -Message "cargo-audit not available (install with: cargo install cargo-audit)"
    Set-MaintenanceResultSummary -Result $result
    $result
    return
}

$auditResult = Invoke-MaintenanceStep `
    -StepName "Cargo Audit" `
    -WorkingDirectory $cliDir `
    -TimeoutSeconds 300 `
    -ScriptBlock {
        cargo audit 2>&1
        $LASTEXITCODE
    }

if ($auditResult.ExitCode -eq 0) {
    Add-MaintenanceResult -Result $result -Item "cargo audit" -Status "passed" -Message "No vulnerabilities found"
}
else {
    Add-MaintenanceResult -Result $result -Item "cargo audit" -Status "failed" -Message "Vulnerabilities detected"
}

Set-MaintenanceResultSummary -Result $result
$result
