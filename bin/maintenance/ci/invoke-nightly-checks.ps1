# ===================================================================
# CROSS-PLATFORM: This script must run on Linux, macOS, and Windows.
# ===================================================================

<#
.SYNOPSIS
Nightly entry point: runs the full maintenance check suite.

.DESCRIPTION
Invokes all level-1 (CI) checks plus level-2 (nightly) checks.
Designed to be called from nightly.yml as a single step.
Uses relaxed mode - collects all failures and reports at the end.

.PARAMETER ReportToAnnotations
Output GitHub Actions annotations. Default: $true

.PARAMETER ReportToConsole
Also output console report. Default: $true

.PARAMETER SkipHeavyTests
Skip B1 (integration tests) and B2 (E2E tests). Default: $false

.PARAMETER SkipDocker
Skip Docker-dependent checks (A3 Qodana, G1 Dockerfile).

.EXAMPLE
pwsh bin/maintenance/ci/invoke-nightly-checks.ps1 -ReportToAnnotations
#>

param(
    [switch]$ReportToAnnotations,
    [switch]$ReportToConsole,
    [switch]$SkipHeavyTests,
    [switch]$SkipDocker
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Continue"

$ScriptDir = $PSScriptRoot
. (Join-Path $ScriptDir "..\common\MaintenanceUtil.ps1")
$ChecksDir = Join-Path $ScriptDir "..\checks"
$ReportersDir = Join-Path $ScriptDir "..\reporters"

$results = @()
$failedCount = 0

Write-Host ""
Write-Host "========================================================================"
Write-Host "|  Nightly Maintenance Checks                                          |"
Write-Host "========================================================================"

# ── Level 1: CI checks (fast baseline) ──
$r = Invoke-MaintenanceCheck -ScriptPath (Join-Path $ChecksDir "check-compilation.ps1") -Label "A1 Compilation"
if ($r) { $results += $r; if ($r.Status -match 'failed|error') { $failedCount++ } }

if ($r -and $r.Status -notmatch 'failed|error') {
    $r = Invoke-MaintenanceCheck -ScriptPath (Join-Path $ChecksDir "check-fast-tests.ps1") -Label "A2 Fast Tests"
    if ($r) { $results += $r; if ($r.Status -match 'failed|error') { $failedCount++ } }
}

$r = Invoke-MaintenanceCheck -ScriptPath (Join-Path $ChecksDir "check-rust-cli.ps1") -Label "B4 Rust CLI"
if ($r) { $results += $r; if ($r.Status -match 'failed|error') { $failedCount++ } }

$r = Invoke-MaintenanceCheck -ScriptPath (Join-Path $ChecksDir "check-doc-links-internal.ps1") -Label "C1 Doc Links"
if ($r) { $results += $r; if ($r.Status -match 'failed|error') { $failedCount++ } }

$r = Invoke-MaintenanceCheck -ScriptPath (Join-Path $ChecksDir "check-skill-frontmatter.ps1") -Label "D1 SKILL Frontmatter"
if ($r) { $results += $r; if ($r.Status -match 'failed|error') { $failedCount++ } }

$r = Invoke-MaintenanceCheck -ScriptPath (Join-Path $ChecksDir "check-version-consistency.ps1") -Label "E1 Version"
if ($r) { $results += $r; if ($r.Status -match 'failed|error') { $failedCount++ } }

$r = Invoke-MaintenanceCheck -ScriptPath (Join-Path $ChecksDir "check-ps1-syntax.ps1") -Label "G2 PS1 Syntax"
if ($r) { $results += $r; if ($r.Status -match 'failed|error') { $failedCount++ } }

if (-not $SkipDocker) {
    $r = Invoke-MaintenanceCheck -ScriptPath (Join-Path $ChecksDir "check-dockerfile.ps1") -Label "G1 Dockerfile" -Arguments @{ SkipBuild = $true }
    if ($r) { $results += $r; if ($r.Status -match 'failed|error') { $failedCount++ } }
}

# ── Level 2: Nightly checks ──
$r = Invoke-MaintenanceCheck -ScriptPath (Join-Path $ChecksDir "check-coverage.ps1") -Label "A4 Coverage"
if ($r) { $results += $r; if ($r.Status -match 'failed|error') { $failedCount++ } }

$r = Invoke-MaintenanceCheck -ScriptPath (Join-Path $ChecksDir "check-test-tags.ps1") -Label "B3 Test Tags"
if ($r) { $results += $r; if ($r.Status -match 'failed|error') { $failedCount++ } }

$r = Invoke-MaintenanceCheck -ScriptPath (Join-Path $ChecksDir "check-skill-structure.ps1") -Label "D2 SKILL Structure"
if ($r) { $results += $r; if ($r.Status -match 'failed|error') { $failedCount++ } }

$r = Invoke-MaintenanceCheck -ScriptPath (Join-Path $ChecksDir "check-doc-links-external.ps1") -Label "C2 External Links"
if ($r) { $results += $r; if ($r.Status -match 'failed|error') { $failedCount++ } }

$r = Invoke-MaintenanceCheck -ScriptPath (Join-Path $ChecksDir "check-bilingual-readme.ps1") -Label "C4 Bilingual README"
if ($r) { $results += $r; if ($r.Status -match 'failed|error') { $failedCount++ } }

$r = Invoke-MaintenanceCheck -ScriptPath (Join-Path $ChecksDir "check-maven-deps.ps1") -Label "F1 Maven Deps"
if ($r) { $results += $r; if ($r.Status -match 'failed|error') { $failedCount++ } }

$r = Invoke-MaintenanceCheck -ScriptPath (Join-Path $ChecksDir "check-cargo-audit.ps1") -Label "F2 Cargo Audit"
if ($r) { $results += $r; if ($r.Status -match 'failed|error') { $failedCount++ } }

$r = Invoke-MaintenanceCheck -ScriptPath (Join-Path $ChecksDir "check-dependency-vulns.ps1") -Label "A5 Dep Vulns"
if ($r) { $results += $r; if ($r.Status -match 'failed|error') { $failedCount++ } }

$r = Invoke-MaintenanceCheck -ScriptPath (Join-Path $ChecksDir "check-log-sizes.ps1") -Label "H1 Log Sizes"
if ($r) { $results += $r; if ($r.Status -match 'failed|error') { $failedCount++ } }

if (-not $SkipDocker) {
    $r = Invoke-MaintenanceCheck -ScriptPath (Join-Path $ChecksDir "check-qodana.ps1") -Label "A3 Qodana"
    if ($r) { $results += $r; if ($r.Status -match 'failed|error') { $failedCount++ } }
}

if (-not $SkipHeavyTests) {
    $r = Invoke-MaintenanceCheck -ScriptPath (Join-Path $ChecksDir "check-integration-tests.ps1") -Label "B1 Integration Tests"
    if ($r) { $results += $r; if ($r.Status -match 'failed|error') { $failedCount++ } }

    $r = Invoke-MaintenanceCheck -ScriptPath (Join-Path $ChecksDir "check-e2e-tests.ps1") -Label "B2 E2E Tests"
    if ($r) { $results += $r; if ($r.Status -match 'failed|error') { $failedCount++ } }
}

# ── Report ──
Write-Host ""
Write-Host "------------------------------------------------------------" -ForegroundColor Cyan

if ($ReportToAnnotations) {
    $annotationsPath = Join-Path $ReportersDir "report-github-annotations.ps1"
    if (Test-Path $annotationsPath) {
        & $annotationsPath -Results $results
    }
}

if ($ReportToConsole) {
    $consolePath = Join-Path $ReportersDir "report-console.ps1"
    if (Test-Path $consolePath) {
        & $consolePath -Results $results
    }
}

# JSON report
$jsonPath = Join-Path $ReportersDir "report-json.ps1"
if (Test-Path $jsonPath) {
    & $jsonPath -Results $results
}

# Summary report
$summaryPath = Join-Path $ReportersDir "report-summary.ps1"
if (Test-Path $summaryPath) {
    & $summaryPath -Results $results
}

# ── Exit ──
if ($failedCount -gt 0) {
    Write-Host "X Nightly checks: ${failedCount} FAILURE(S)" -ForegroundColor Red
    exit 1
}
else {
    Write-Host "✅ Nightly checks: ALL PASSED" -ForegroundColor Green
    exit 0
}
