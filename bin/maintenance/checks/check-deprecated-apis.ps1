# ═══════════════════════════════════════════════════════════════════
# CROSS-PLATFORM: This script must run on Linux, macOS, and Windows.
# ═══════════════════════════════════════════════════════════════════

<#
.SYNOPSIS
A6 — Deprecated API detection: scans for @Deprecated usage.

.DESCRIPTION
Grep-scans Kotlin, Java, and Rust source files for deprecated API
annotations and attributes. Reports files exceeding the threshold.

.PARAMETER IncludePatterns
File extensions to scan. Default: "*.kt", "*.java", "*.rs"

.PARAMETER MaxDeprecated
Maximum allowed deprecated usages before failing. Default from thresholds.

.OUTPUTS
Standard maintenance result object.
#>

param(
    [string[]]$IncludePatterns = @("*.kt", "*.java", "*.rs"),
    [int]$MaxDeprecated = 0
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Continue"

$ScriptDir = $PSScriptRoot
. (Join-Path $ScriptDir "..\common\MaintenanceUtil.ps1")

$result = New-MaintenanceResult -CheckId "A6" -Name "Deprecated API Detection"
$repoRoot = Get-RepositoryRoot

if ($MaxDeprecated -le 0) {
    $MaxDeprecated = Get-MaintenanceThreshold -Section "CodeQuality" -Key "MaxDeprecatedUsages" -Default 50
}

$rgAvailable = $null -ne (Get-Command rg -ErrorAction SilentlyContinue)
$totalDeprecated = 0

foreach ($pattern in $IncludePatterns) {
    $files = Get-ChildItem $repoRoot -Recurse -File -Filter "*.$($pattern.TrimStart('*.'))" -ErrorAction SilentlyContinue `
        | Where-Object { $_.FullName -notmatch "target[\\/]" }

    foreach ($file in $files) {
        $relPath = $file.FullName.Replace($repoRoot, "").TrimStart("\", "/")
        $content = Get-Content $file.FullName -Raw -Encoding UTF8 -ErrorAction SilentlyContinue
        if (-not $content) { continue }

        $count = 0

        # Kotlin/Java deprecation patterns
        if ($pattern -in @("*.kt", "*.java")) {
            # @Deprecated or @java.lang.Deprecated
            $depMatches = [regex]::Matches($content, '@Deprecated\b')
            $count = $depMatches.Count
            # Kotlin-specific: @Deprecated("..."), ReplaceWith(...)
            $depMatches2 = [regex]::Matches($content, '@Deprecated\(')
            $count += $depMatches2.Count
        }

        # Rust deprecation patterns
        if ($pattern -eq "*.rs") {
            $depMatches = [regex]::Matches($content, '#\[deprecated')
            $count = $depMatches.Count
            $depMatches2 = [regex]::Matches($content, '#\[allow\(deprecated\)')
            $count += $depMatches2.Count
        }

        if ($count -gt 0) {
            $totalDeprecated += $count
            $status = if ($count -le 2) { "passed" } else { "failed" }
            Add-MaintenanceResult -Result $result -Item $relPath -Status $status -Message "$count deprecated usages"
        }
    }
}

if ($totalDeprecated -le $MaxDeprecated) {
    Add-MaintenanceResult -Result $result -Item "Total" -Status "passed" -Message "$totalDeprecated (threshold: $MaxDeprecated)"
}
else {
    Add-MaintenanceResult -Result $result -Item "Total" -Status "failed" -Message "$totalDeprecated > $MaxDeprecated"
}

Set-MaintenanceResultSummary -Result $result
$result
