# ═══════════════════════════════════════════════════════════════════
# CROSS-PLATFORM: This script must run on Linux, macOS, and Windows.
# ═══════════════════════════════════════════════════════════════════

<#
.SYNOPSIS
A5 — Dependency vulnerability scan: checks for known CVEs.

.DESCRIPTION
Runs OWASP dependency-check against Maven dependencies and cargo audit
against Rust dependencies. Flags critical and high-severity CVEs.

.PARAMETER MavenProfiles
Maven profiles for the dependency check. Default: "all-main-modules"

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

$result = New-MaintenanceResult -CheckId "A5" -Name "Dependency Vulnerability Scan"
$repoRoot = Get-RepositoryRoot
$mvnCmd = if (Test-IsWindows) { ".\mvnw.cmd" } else { "./mvnw" }

$maxCrit = Get-MaintenanceThreshold -Section "Dependencies" -Key "MaxCriticalVulnerabilities" -Default 0
$maxHigh = Get-MaintenanceThreshold -Section "Dependencies" -Key "MaxHighVulnerabilities" -Default 2

# ── Maven OWASP dependency check ──
$owaspResult = Invoke-MaintenanceStep `
    -StepName "OWASP Dep Check" `
    -WorkingDirectory $repoRoot `
    -TimeoutSeconds 1800 `
    -ScriptBlock {
        & $mvnCmd org.owasp:dependency-check-maven:check -P "$MavenProfiles" -DfailBuildOnCVSS=0 -q 2>&1
        $LASTEXITCODE
    }

# OWASP plugin returns non-zero if CVEs found, which is expected
$owaspReport = Join-Path $repoRoot "target\dependency-check-report.html"
if (Test-Path $owaspReport) {
    $depCount = (Get-Content $owaspReport -Raw | Select-String -Pattern '<td>(\d+)</td>' -AllMatches).Matches.Count
    Add-MaintenanceResult -Result $result -Item "OWASP Maven" -Status $(if ($owaspResult.ExitCode -eq 0) { "passed" } else { "failed" }) -Message "Dependency check report generated"
}
else {
    Add-MaintenanceResult -Result $result -Item "OWASP Maven" -Status "skipped" -Message "OWASP plugin not configured or report not found"
}

# ── Cargo audit ──
$cargoAuditAvailable = $null -ne (Get-Command cargo-audit -ErrorAction SilentlyContinue)
if (-not $cargoAuditAvailable) {
    # Try to install
    try {
        cargo install cargo-audit 2>&1 | Out-Null
        $cargoAuditAvailable = $null -ne (Get-Command cargo-audit -ErrorAction SilentlyContinue)
    }
    catch { }
}

if ($cargoAuditAvailable) {
    $cliDir = Join-Path $repoRoot "cli\browser4-cli"
    if (Test-Path (Join-Path $cliDir "Cargo.toml")) {
        $cargoAuditResult = Invoke-MaintenanceStep `
            -StepName "Cargo Audit" `
            -WorkingDirectory $cliDir `
            -TimeoutSeconds 300 `
            -ScriptBlock {
                cargo audit 2>&1
                $LASTEXITCODE
            }

        if ($cargoAuditResult.ExitCode -eq 0) {
            Add-MaintenanceResult -Result $result -Item "cargo audit" -Status "passed" -Message "No vulnerabilities"
        }
        else {
            Add-MaintenanceResult -Result $result -Item "cargo audit" -Status "failed" -Message "Vulnerabilities found"
        }
    }
}
else {
    Add-MaintenanceResult -Result $result -Item "cargo audit" -Status "skipped" -Message "cargo-audit not available"
}

Set-MaintenanceResultSummary -Result $result
$result
