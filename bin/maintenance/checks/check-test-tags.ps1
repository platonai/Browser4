# ═══════════════════════════════════════════════════════════════════
# CROSS-PLATFORM: This script must run on Linux, macOS, and Windows.
# ═══════════════════════════════════════════════════════════════════

<#
.SYNOPSIS
B3 — Test tag audit: verifies JUnit 5 test tag compliance.

.DESCRIPTION
Scans all test source files for @Tag annotations and verifies compliance
with the taxonomy defined in docs/TESTING.md:
  1. Every test class must declare a Level tag (Unit|Integration|E2E|SDK)
  2. Every test class must declare a Cost tag (Fast|Slow|Heavy)
  3. Legacy tag names (IntegrationTest, E2ETest, HeavyTest) are banned

.PARAMETER Strict
If set, exits with code 1 on violations. Default: warn only.

.PARAMETER SearchGlob
Glob for test source files. Default: "**/src/test/**/*.{kt,java}"

.OUTPUTS
Standard maintenance result object.
#>

param(
    [switch]$Strict,
    [string]$SearchGlob = "**/src/test/**/*.{kt,java}"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Continue"

$ScriptDir = $PSScriptRoot
. (Join-Path $ScriptDir "..\common\MaintenanceUtil.ps1")

$result = New-MaintenanceResult -CheckId "B3" -Name "Test Tag Audit"
$repoRoot = Get-RepositoryRoot

# Known valid tags per taxonomy
$validLevels  = @("Unit", "Integration", "E2E", "SDK")
$validCosts   = @("Fast", "Slow", "Heavy")
$bannedTags   = @("IntegrationTest", "E2ETest", "HeavyTest", "SlowTest",
                   "FastTest", "UnitTest", "SkippableLowerLevelTest")

$testFiles = Get-ChildItem -Path $repoRoot -Recurse -File -ErrorAction SilentlyContinue `
    | Where-Object {
        $_.Extension -in @(".kt", ".java") -and
        $_.FullName -match "src[\\/]test" -and
        $_.FullName -notmatch "target[\\/]"
    }

$checkedFiles = 0
$violationFiles = 0

foreach ($file in $testFiles) {
    $relPath = $file.FullName.Replace($repoRoot, "").TrimStart("\", "/")
    $content = Get-Content $file.FullName -Raw -Encoding UTF8 -ErrorAction SilentlyContinue
    if (-not $content) { continue }

    # Check if it's a test class (has @Test, @ParameterizedTest, or similar)
    if ($content -notmatch '@(Test|ParameterizedTest|RepeatedTest|TestFactory|TestTemplate)') {
        continue
    }
    $checkedFiles++

    # Extract all @Tag annotations
    $tags = @()
    $tagMatches = [regex]::Matches($content, '@Tag\("([^"]+)"\)')
    foreach ($m in $tagMatches) {
        $tag = $m.Groups[1].Value
        $tags += $tag
    }

    # Also check from @Tags container
    $tagsMatches = [regex]::Matches($content, '@Tags\(\{(.+?)\}\)', [System.Text.RegularExpressions.RegexOptions]::Singleline)
    foreach ($m in $tagsMatches) {
        $inner = $m.Groups[1].Value
        $innerMatches = [regex]::Matches($inner, '@Tag\("([^"]+)"\)')
        foreach ($im in $innerMatches) {
            $tags += $im.Groups[1].Value
        }
    }

    $issues = @()

    # Check for banned legacy tags
    foreach ($tag in $tags) {
        if ($tag -in $bannedTags) {
            $issues += "Banned legacy tag: '$tag'"
        }
    }

    # Check Level tag present
    $hasLevel = $tags | Where-Object { $_ -in $validLevels }
    if (-not $hasLevel) {
        $issues += "Missing Level tag (one of: $($validLevels -join ', '))"
    }

    # Check Cost tag present
    $hasCost = $tags | Where-Object { $_ -in $validCosts }
    if (-not $hasCost) {
        $issues += "Missing Cost tag (one of: $($validCosts -join ', '))"
    }

    if ($issues.Count -eq 0) {
        Add-MaintenanceResult -Result $result -Item $relPath -Status "passed" -Message "Tags: $($tags -join ', ')"
    }
    else {
        $violationFiles++
        foreach ($issue in $issues) {
            Add-MaintenanceResult -Result $result -Item $relPath -Status "failed" -Message $issue
        }
    }
}

$result.Details = "$checkedFiles test classes checked, $violationFiles with violations"
$result.ExitCode = if ($Strict -and $violationFiles -gt 0) { 1 } else { 0 }

Set-MaintenanceResultSummary -Result $result
$result
