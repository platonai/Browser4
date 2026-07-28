# ===================================================================
# CROSS-PLATFORM: This script must run on Linux, macOS, and Windows.
# ===================================================================

<#
.SYNOPSIS
CI entry point: runs the fast, per-commit maintenance checks.

.DESCRIPTION
Invokes all level-1 (CI) checks in sequence. Designed to be called from
ci.yml as a single step. Each check runs in under 60 seconds, with the
full invocation completing in a few minutes (excluding A2 fast-tests).

Checks run: A1 (compilation), A2 (fast tests), B4 (Rust CLI),
C1 (doc links), D1 (SKILL frontmatter), E1 (version),
G2 (PS1 syntax), G1 (Dockerfile).

.PARAMETER ReportToConsole
If set, outputs results to console in addition to annotations.

.PARAMETER ReportToAnnotations
If set, outputs results as GitHub Actions annotations. Default: $true

.PARAMETER SkipTests
If set, skips A2 (fast tests).

.PARAMETER SkipDocker
If set, skips G1 (Dockerfile check).

.EXAMPLE
pwsh bin/maintenance/ci/invoke-ci-checks.ps1 -ReportToAnnotations
#>

param(
    [switch]$ReportToConsole,
    [switch]$ReportToAnnotations,
    [switch]$SkipTests,
    [switch]$SkipDocker
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Continue"

$ScriptDir = $PSScriptRoot
. (Join-Path $ScriptDir "..\common\MaintenanceUtil.ps1")
$ChecksDir = Join-Path $ScriptDir "..\checks"
$ReportersDir = Join-Path $ScriptDir "..\reporters"

$results = @()
$overallFailed = $false

Write-Host ""
Write-Host "==================================================" -ForegroundColor Cyan
Write-Host "|  CI Maintenance Checks                        |" -ForegroundColor Cyan
Write-Host "==================================================" -ForegroundColor Cyan

# ── A1: Compilation ──
$r = Invoke-MaintenanceCheck -ScriptPath (Join-Path $ChecksDir "check-compilation.ps1") -Label "A1 Compilation"
if ($r) { $results += $r; if ($r.Status -eq "failed" -or $r.Status -eq "error") { $overallFailed = $true } }

# If compilation failed, skip dependent checks
if ($r -and $r.Status -ne "failed" -and $r.Status -ne "error") {
    # ── A2: Fast tests ──
    if (-not $SkipTests) {
        $r = Invoke-MaintenanceCheck -ScriptPath (Join-Path $ChecksDir "check-fast-tests.ps1") -Label "A2 Fast Tests"
        if ($r) { $results += $r; if ($r.Status -eq "failed" -or $r.Status -eq "error") { $overallFailed = $true } }
    }
    else {
        Write-Host "  [SKIP] A2 Fast Tests (--SkipTests)" -ForegroundColor Yellow
    }
}
else {
    Write-Host "  [SKIP] A2 Fast Tests - compilation failed" -ForegroundColor Yellow
}

# ── B4: Rust CLI ──
$r = Invoke-MaintenanceCheck -ScriptPath (Join-Path $ChecksDir "check-rust-cli.ps1") -Label "B4 Rust CLI"
if ($r) { $results += $r; if ($r.Status -eq "failed" -or $r.Status -eq "error") { $overallFailed = $true } }

# ── C1: Doc links ──
$r = Invoke-MaintenanceCheck -ScriptPath (Join-Path $ChecksDir "check-doc-links-internal.ps1") -Label "C1 Internal Doc Links"
if ($r) { $results += $r; if ($r.Status -eq "failed" -or $r.Status -eq "error") { $overallFailed = $true } }

# ── D1: SKILL frontmatter ──
$r = Invoke-MaintenanceCheck -ScriptPath (Join-Path $ChecksDir "check-skill-frontmatter.ps1") -Label "D1 SKILL Frontmatter"
if ($r) { $results += $r; if ($r.Status -eq "failed" -or $r.Status -eq "error") { $overallFailed = $true } }

# ── E1: Version ──
$r = Invoke-MaintenanceCheck -ScriptPath (Join-Path $ChecksDir "check-version-consistency.ps1") -Label "E1 Version Consistency"
if ($r) { $results += $r; if ($r.Status -eq "failed" -or $r.Status -eq "error") { $overallFailed = $true } }

# ── G2: PS1 syntax ──
$r = Invoke-MaintenanceCheck -ScriptPath (Join-Path $ChecksDir "check-ps1-syntax.ps1") -Label "G2 PS1 Syntax"
if ($r) { $results += $r; if ($r.Status -eq "failed" -or $r.Status -eq "error") { $overallFailed = $true } }

# ── G1: Dockerfile ──
if (-not $SkipDocker) {
    $r = Invoke-MaintenanceCheck -ScriptPath (Join-Path $ChecksDir "check-dockerfile.ps1") -Label "G1 Dockerfile" -Arguments @{ SkipBuild = $true }
    if ($r) { $results += $r; if ($r.Status -eq "failed" -or $r.Status -eq "error") { $overallFailed = $true } }
}
else {
    Write-Host "  [SKIP] G1 Dockerfile (--SkipDocker)" -ForegroundColor Yellow
}

# ── Report ──
Write-Host ""
Write-Host "------------------------------------------------" -ForegroundColor Cyan

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

# ── Exit ──
if ($overallFailed) {
    Write-Host "X CI checks: FAILED" -ForegroundColor Red
    exit 1
}
else {
    Write-Host "✅ CI checks: ALL PASSED" -ForegroundColor Green
    exit 0
}
