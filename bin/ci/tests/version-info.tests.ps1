#!/usr/bin/env pwsh

# ═══════════════════════════════════════════════════════════════════
# CROSS-PLATFORM: This script must run on Linux, macOS, and Windows.
# - Use $IsWindows / $IsLinux / $IsMacOS for platform detection.
# - Avoid Windows-only cmdlets; keep to core PowerShell.
# ═══════════════════════════════════════════════════════════════════

<#
.SYNOPSIS
    Unit tests for bin/ci/lib/VersionInfo.ps1 (Get-VersionInfo).

.DESCRIPTION
    Covers the VERSION shapes the CI trigger has to survive:

        4.14.0-rc.5        release-candidate line (the tag base keeps -rc.5)
        4.13.18-SNAPSHOT   development line (-SNAPSHOT is a build marker only)
        4.13.18            released version

    Regression guard: splitting `4.14.0-rc.5` on '.' yields the fragment `0-rc`,
    and casting that to [int] is how trigger-ci.ps1 used to fail with

        Cannot convert value "0-rc" to type "System.Int32"

    on every release-candidate branch.

    Run standalone:
        pwsh bin/ci/tests/version-info.tests.ps1

    Run via runner:
        pwsh browser4-tests/tests-production/run-tests.ps1 version-info
#>

[CmdletBinding()]
param()

$ErrorActionPreference = 'Continue'

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$VersionInfoPath = Join-Path $ScriptDir '..\VersionInfo.ps1'

if (-not (Test-Path $VersionInfoPath)) {
    Write-Host "FATAL: VersionInfo.ps1 not found at $VersionInfoPath" -ForegroundColor Red
    exit 1
}
. $VersionInfoPath

if (-not (Get-Command Get-VersionInfo -ErrorAction SilentlyContinue)) {
    Write-Host 'FATAL: Get-VersionInfo is not defined after dot-sourcing VersionInfo.ps1' -ForegroundColor Red
    exit 1
}

$script:Failures = 0
$script:Checks = 0

function Assert-Equal {
    param([string]$Label, $Actual, $Expected)
    $script:Checks++
    if ("$Actual" -eq "$Expected") {
        Write-Host "  [PASS] $Label" -ForegroundColor Green
    } else {
        $script:Failures++
        Write-Host "  [FAIL] $Label — expected '$Expected', got '$Actual'" -ForegroundColor Red
    }
}

function Assert-True {
    param([string]$Label, $Condition)
    $script:Checks++
    if ($Condition) {
        Write-Host "  [PASS] $Label" -ForegroundColor Green
    } else {
        $script:Failures++
        Write-Host "  [FAIL] $Label" -ForegroundColor Red
    }
}

Write-Host ''
Write-Host 'Get-VersionInfo unit tests' -ForegroundColor Cyan
Write-Host '──────────────────────────────────────────────────────────'

# ===================================================================
# 1. Release candidate (4.14.x) — the shape that used to crash
# ===================================================================
Write-Host "`n[1] release candidate '4.14.0-rc.5'" -ForegroundColor Yellow
$rc = Get-VersionInfo '4.14.0-rc.5'
Assert-Equal -Label 'rc: Full keeps the pre-release label'      -Actual $rc.Full         -Expected '4.14.0-rc.5'
Assert-Equal -Label 'rc: Core is the numeric core'              -Actual $rc.Core         -Expected '4.14.0'
Assert-Equal -Label 'rc: PreRelease is -rc.5'                   -Actual $rc.PreRelease   -Expected '-rc.5'
Assert-Equal -Label 'rc: MajorMinor is 4.14'                    -Actual $rc.MajorMinor   -Expected '4.14'
Assert-Equal -Label 'rc: Patch is the numeric core patch'       -Actual $rc.Patch        -Expected 0
Assert-True  -Label 'rc: IsPreRelease is true'                  -Condition ($rc.IsPreRelease -eq $true)
Assert-True  -Label 'rc: Patch is an [int], not the 0-rc fragment' -Condition ($rc.Patch -is [int])

# ===================================================================
# 2. Development version (-SNAPSHOT is dropped from the tag base)
# ===================================================================
Write-Host "`n[2] development version '4.13.18-SNAPSHOT'" -ForegroundColor Yellow
$snap = Get-VersionInfo '4.13.18-SNAPSHOT'
Assert-Equal -Label 'snapshot: Full drops -SNAPSHOT'            -Actual $snap.Full       -Expected '4.13.18'
Assert-Equal -Label 'snapshot: MajorMinor is 4.13'              -Actual $snap.MajorMinor -Expected '4.13'
Assert-Equal -Label 'snapshot: Patch is 18'                     -Actual $snap.Patch      -Expected 18
Assert-Equal -Label 'snapshot: PreRelease is empty'             -Actual $snap.PreRelease -Expected ''
Assert-True  -Label 'snapshot: IsPreRelease is false'           -Condition ($snap.IsPreRelease -eq $false)

# ===================================================================
# 3. Released version
# ===================================================================
Write-Host "`n[3] released version '4.13.18'" -ForegroundColor Yellow
$rel = Get-VersionInfo '4.13.18'
Assert-Equal -Label 'release: Full is unchanged'                -Actual $rel.Full       -Expected '4.13.18'
Assert-Equal -Label 'release: Patch is 18'                      -Actual $rel.Patch      -Expected 18

# ===================================================================
# 4. Older rc patch labels and whitespace tolerance
# ===================================================================
Write-Host "`n[4] other rc labels / whitespace" -ForegroundColor Yellow
$rc3 = Get-VersionInfo '4.14.0-rc.3'
Assert-Equal -Label 'rc.3: Full is 4.14.0-rc.3'                 -Actual $rc3.Full       -Expected '4.14.0-rc.3'
Assert-Equal -Label 'rc.3: Patch is 0'                          -Actual $rc3.Patch      -Expected 0

$padded = Get-VersionInfo "  4.14.0-rc.5`n"
Assert-Equal -Label 'padded: trailing newline/space trimmed'    -Actual $padded.Full    -Expected '4.14.0-rc.5'

$twoPart = Get-VersionInfo '4.14'
Assert-Equal -Label 'two-part: MajorMinor is 4.14'              -Actual $twoPart.MajorMinor -Expected '4.14'
Assert-Equal -Label 'two-part: Patch defaults to 0'             -Actual $twoPart.Patch      -Expected 0

# ===================================================================
# 5. Malformed input never invents a version
# ===================================================================
Write-Host "`n[5] malformed input" -ForegroundColor Yellow
$bad = Get-VersionInfo 'not-a-version'
Assert-Equal -Label 'malformed: MajorMinor is empty'            -Actual $bad.MajorMinor -Expected ''
Assert-Equal -Label 'malformed: Patch is 0'                     -Actual $bad.Patch      -Expected 0
Assert-True  -Label 'malformed: IsPreRelease is false'          -Condition ($bad.IsPreRelease -eq $false)

$empty = Get-VersionInfo ''
Assert-Equal -Label 'empty: Full is empty'                      -Actual $empty.Full       -Expected ''
Assert-Equal -Label 'empty: MajorMinor is empty'                -Actual $empty.MajorMinor -Expected ''

# ===================================================================
# 6. The tag base built from Full matches the ci.yml tag patterns
# ===================================================================
Write-Host "`n[6] ci.yml tag patterns" -ForegroundColor Yellow
$rcTag = "v$($rc.Full)-ci.1"
Assert-Equal -Label 'rc tag is v4.14.0-rc.5-ci.1'               -Actual $rcTag -Expected 'v4.14.0-rc.5-ci.1'
Assert-True  -Label 'rc tag matches vX.Y.Z-rc.N-ci.M'           -Condition ($rcTag -match '^v[0-9]+\.[0-9]+\.[0-9]+-rc\.[0-9]+-ci\.[0-9]+$')

$plainTag = "v$($snap.Full)-ci.7"
Assert-Equal -Label 'plain tag is v4.13.18-ci.7'                -Actual $plainTag -Expected 'v4.13.18-ci.7'
Assert-True  -Label 'plain tag matches vX.Y.Z-ci.N'             -Condition ($plainTag -match '^v[0-9]+\.[0-9]+\.[0-9]+-ci\.[0-9]+$')

# ===================================================================
# Summary
# ===================================================================
Write-Host ''
Write-Host '──────────────────────────────────────────────────────────'
if ($script:Failures -gt 0) {
    Write-Host "FAILED: $script:Failures of $script:Checks assertion(s) failed." -ForegroundColor Red
    exit 1
}
Write-Host "PASSED: $script:Checks assertion(s)." -ForegroundColor Green
exit 0
