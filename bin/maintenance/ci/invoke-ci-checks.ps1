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
$ChecksDir = Join-Path $ScriptDir "..\checks"
$ReportersDir = Join-Path $ScriptDir "..\reporters"

$results = @()
$overallFailed = $false

function Invoke-Check {
    param(
        [string]$ScriptName,
        [string]$Label,
        [hashtable]$Arguments = @{}
    )

    $scriptPath = Join-Path $ChecksDir $ScriptName
    if (-not (Test-Path $scriptPath)) {
        Write-Host "  [SKIP] $Label - script not found: $scriptPath" -ForegroundColor Yellow
        return $null
    }

    Write-Host ""
    Write-Host "--- $Label ---" -ForegroundColor Cyan

    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    try {
        $argsList = @()
        foreach ($kv in $Arguments.GetEnumerator()) {
            $argsList += "-$($kv.Key)"
            if ($kv.Value -isnot [switch] -and $kv.Value) {
                $argsList += $kv.Value
            }
        }
        $checkResult = & $scriptPath @argsList
        $sw.Stop()
        $checkResult.DurationMs = $sw.ElapsedMilliseconds

        $icon = if ($checkResult.Status -eq "passed") { "✅" } elseif ($checkResult.Status -eq "skipped") { "!️" } else { "X" }
        Write-Host "$icon $Label - $($checkResult.Status) ($($checkResult.DurationMs)ms)" -ForegroundColor $(if ($checkResult.Status -eq "passed") { "Green" } else { "Red" })
        return $checkResult
    }
    catch {
        $sw.Stop()
        Write-Host "X $Label - ERROR: $($_.Exception.Message)" -ForegroundColor Red
        $errResult = [PSCustomObject]@{
            CheckId    = "??"
            Name       = $Label
            Status     = "error"
            DurationMs = $sw.ElapsedMilliseconds
            ExitCode   = 1
            Details    = $_.Exception.Message
            Results    = @()
            Artifacts  = @()
            Timestamp  = (Get-Date -Format "yyyy-MM-ddTHH:mm:ssK")
        }
        return $errResult
    }
}

Write-Host ""
Write-Host "==================================================" -ForegroundColor Cyan
Write-Host "|  CI Maintenance Checks                        |" -ForegroundColor Cyan
Write-Host "==================================================" -ForegroundColor Cyan

# ── A1: Compilation ──
$r = Invoke-Check -ScriptName "check-compilation.ps1" -Label "A1 Compilation"
if ($r) { $results += $r; if ($r.Status -eq "failed" -or $r.Status -eq "error") { $overallFailed = $true } }

# If compilation failed, skip dependent checks
if ($r -and $r.Status -ne "failed" -and $r.Status -ne "error") {
    # ── A2: Fast tests ──
    if (-not $SkipTests) {
        $r = Invoke-Check -ScriptName "check-fast-tests.ps1" -Label "A2 Fast Tests"
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
$r = Invoke-Check -ScriptName "check-rust-cli.ps1" -Label "B4 Rust CLI"
if ($r) { $results += $r; if ($r.Status -eq "failed" -or $r.Status -eq "error") { $overallFailed = $true } }

# ── C1: Doc links ──
$r = Invoke-Check -ScriptName "check-doc-links-internal.ps1" -Label "C1 Internal Doc Links"
if ($r) { $results += $r; if ($r.Status -eq "failed" -or $r.Status -eq "error") { $overallFailed = $true } }

# ── D1: SKILL frontmatter ──
$r = Invoke-Check -ScriptName "check-skill-frontmatter.ps1" -Label "D1 SKILL Frontmatter"
if ($r) { $results += $r; if ($r.Status -eq "failed" -or $r.Status -eq "error") { $overallFailed = $true } }

# ── E1: Version ──
$r = Invoke-Check -ScriptName "check-version-consistency.ps1" -Label "E1 Version Consistency"
if ($r) { $results += $r; if ($r.Status -eq "failed" -or $r.Status -eq "error") { $overallFailed = $true } }

# ── G2: PS1 syntax ──
$r = Invoke-Check -ScriptName "check-ps1-syntax.ps1" -Label "G2 PS1 Syntax"
if ($r) { $results += $r; if ($r.Status -eq "failed" -or $r.Status -eq "error") { $overallFailed = $true } }

# ── G1: Dockerfile ──
if (-not $SkipDocker) {
    $r = Invoke-Check -ScriptName "check-dockerfile.ps1" -Label "G1 Dockerfile" -Arguments @{ SkipBuild = $true }
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
