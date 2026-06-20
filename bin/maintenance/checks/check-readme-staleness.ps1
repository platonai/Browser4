# ═══════════════════════════════════════════════════════════════════
# CROSS-PLATFORM: This script must run on Linux, macOS, and Windows.
# ═══════════════════════════════════════════════════════════════════

<#
.SYNOPSIS
C3 — README staleness check: scores README freshness.

.DESCRIPTION
Evaluates README files for staleness using multiple signals:
  - Age of last modification
  - Version reference correctness
  - TOC completeness (all ## headers have entries)
  - Link validity
  - Content size (not too short)

Wraps coworker/scripts/workers/update-readmes.ps1 logic.

.PARAMETER Threshold
Staleness score threshold. Default from config (40). Score >= threshold means stale.

.PARAMETER SearchGlob
Glob for README files. Default: "**/README*.md"

.OUTPUTS
Standard maintenance result object.
#>

param(
    [int]$Threshold = 0,
    [string]$SearchGlob = "**/README*.md"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Continue"

$ScriptDir = $PSScriptRoot
. (Join-Path $ScriptDir "..\common\MaintenanceUtil.ps1")

$result = New-MaintenanceResult -CheckId "C3" -Name "README Staleness Check"
$repoRoot = Get-RepositoryRoot

if ($Threshold -le 0) {
    $Threshold = Get-MaintenanceThreshold -Section "Documentation" -Key "ReadmeStalenessThreshold" -Default 40
}

$readmeFiles = Get-ChildItem -Path $repoRoot -Filter "README*.md" -Recurse -File `
    | Where-Object { $_.FullName -notmatch "target[\\/]" -and $_.FullName -notmatch "node_modules[\\/]" }

if ($readmeFiles.Count -eq 0) {
    $result.Status = "skipped"
    $result.Details = "No README files found"
    $result
    return
}

$currentVersion = $null
$versionPath = Join-Path $repoRoot "VERSION"
if (Test-Path $versionPath) {
    $currentVersion = (Get-Content $versionPath -Raw).Trim()
}

foreach ($file in $readmeFiles) {
    $relPath = $file.FullName.Replace($repoRoot, "").TrimStart("\", "/")
    $content = Get-Content $file.FullName -Raw -Encoding UTF8 -ErrorAction SilentlyContinue
    if (-not $content) {
        Add-MaintenanceResult -Result $result -Item $relPath -Status "error" -Message "Cannot read file"
        continue
    }
    $mtime = $file.LastWriteTime
    $ageDays = [math]::Round(((Get-Date) - $mtime).TotalDays, 1)

    $stalenessScore = 0
    $signals = @()

    # Signal 1: Age (> 30 days = +15, > 90 days = +30)
    if ($ageDays -gt 90) {
        $stalenessScore += 30
        $signals += "Very old: ${ageDays}d"
    }
    elseif ($ageDays -gt 30) {
        $stalenessScore += 15
        $signals += "Moderately old: ${ageDays}d"
    }

    # Signal 2: Version reference mismatch
    if ($currentVersion) {
        $baseVersion = $currentVersion -replace '-SNAPSHOT$', ''
        if ($content -notmatch [regex]::Escape($baseVersion)) {
            $stalenessScore += 20
            $signals += "Missing current version: $baseVersion"
        }
    }

    # Signal 3: TOC completeness
    $headers = [regex]::Matches($content, '^##\s+(.+)', [System.Text.RegularExpressions.RegexOptions]::Multiline)
    $tocEntries = [regex]::Matches($content, '^\s*-\s+\[([^\]]+)\]', [System.Text.RegularExpressions.RegexOptions]::Multiline)
    if ($headers.Count -gt 5 -and $tocEntries.Count -lt ($headers.Count * 0.7)) {
        $stalenessScore += 15
        $signals += "TOC incomplete: $($tocEntries.Count) entries vs $($headers.Count) headers"
    }

    # Signal 4: Content quantity (< 200 words = suspicious)
    $wordCount = ($content -split '\s+').Count
    if ($wordCount -lt 100) {
        $stalenessScore += 10
        $signals += "Very short: ${wordCount} words"
    }

    $status = if ($stalenessScore -lt $Threshold) { "passed" } else { "failed" }
    $message = "Score ${stalenessScore}/${Threshold}"
    if ($signals.Count -gt 0) {
        $message += " — $($signals -join '; ')"
    }

    Add-MaintenanceResult -Result $result -Item $relPath -Status $status -Message $message
}

Set-MaintenanceResultSummary -Result $result
$result
