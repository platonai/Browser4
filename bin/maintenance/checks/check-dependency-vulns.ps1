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
        & $mvnCmd org.owasp:dependency-check-maven:check -P "$MavenProfiles" -DfailBuildOnCVSS=0 2>&1
        $LASTEXITCODE
    }

# OWASP plugin returns non-zero if CVEs found, which is expected.
# Try JSON report first (structured), fall back to HTML.
$owaspJsonReport = Join-Path $repoRoot "target\dependency-check-report.json"
$owaspHtmlReport = Join-Path $repoRoot "target\dependency-check-report.html"
$owaspCveSummary = ""
$owaspStatus = "skipped"
$owaspMessage = ""

if (Test-Path $owaspJsonReport) {
    try {
        $json = Get-Content $owaspJsonReport -Raw | ConvertFrom-Json
        $vulns = $json.vulnerabilities ?? $json.dependencies.vulnerabilities
        if ($vulns) {
            $critical = ($vulns | Where-Object { $_.severity -match 'CRITICAL' }).Count
            $high     = ($vulns | Where-Object { $_.severity -match 'HIGH' }).Count
            $medium   = ($vulns | Where-Object { $_.severity -match 'MEDIUM' }).Count
            $low      = ($vulns | Where-Object { $_.severity -match 'LOW' }).Count
            $total    = $vulns.Count
            $owaspCveSummary = "$total total ($critical crit, $high high, $medium med, $low low)"
            $owaspStatus = if ($critical -gt $maxCrit -or $high -gt $maxHigh) { "failed" } else { "passed" }
            $owaspMessage = $owaspCveSummary
        }
        else {
            $owaspStatus = "passed"
            $owaspMessage = "No vulnerabilities found"
        }
    }
    catch {
        # JSON parse failed — fall back to exit-code-based status
        $owaspStatus = if ($owaspResult.ExitCode -eq 0) { "passed" } else { "failed" }
        $owaspMessage = "Report found but could not parse JSON"
    }
}
elseif (Test-Path $owaspHtmlReport) {
    $owaspStatus = if ($owaspResult.ExitCode -eq 0) { "passed" } else { "failed" }
    $owaspMessage = "Dependency check report generated (HTML only, no structured parse)"
}
else {
    $owaspStatus = "skipped"
    $owaspMessage = "OWASP plugin not configured or report not found"
}

Add-MaintenanceResult -Result $result -Item "OWASP Maven" -Status $owaspStatus -Message $owaspMessage

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
            # cargo audit outputs a summary line like "Crate: 5 vulnerabilities found (2 RUSTSEC-...)"
            $cargoSummary = ""
            if ($cargoAuditResult.Stdout) {
                $countMatch = [regex]::Match($cargoAuditResult.Stdout, '(\d+)\s+vulnerability|vulnerabilit(?:y|ies)\s+found.*?(\d+)')
                if ($countMatch.Success) {
                    $cargoSummary = " — $($countMatch.Value.Trim())"
                }
                else {
                    # Grab the last meaningful line for context
                    $lastLine = ($cargoAuditResult.Stdout -split "`n" | Where-Object { $_ -match '\S' } | Select-Object -Last 1)
                    $cargoSummary = " — $lastLine"
                }
            }
            Add-MaintenanceResult -Result $result -Item "cargo audit" -Status "failed" -Message "Vulnerabilities found$cargoSummary"
        }
    }
}
else {
    Add-MaintenanceResult -Result $result -Item "cargo audit" -Status "skipped" -Message "cargo-audit not available"
}

Set-MaintenanceResultSummary -Result $result
$result
