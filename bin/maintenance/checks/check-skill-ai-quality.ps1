# ═══════════════════════════════════════════════════════════════════
# CROSS-PLATFORM: This script must run on Linux, macOS, and Windows.
# ═══════════════════════════════════════════════════════════════════

<#
.SYNOPSIS
D3 — SKILL AI instruction quality: assesses readability for AI agents.

.DESCRIPTION
Evaluates SKILL.md files for AI-agent friendliness using static signals:
  - Ambiguity words (maybe, could, might, probably, sometimes)
  - Actionability (presence of "Use when..." pattern)
  - Parameter clarity (types, defaults, constraints documented)
  - Error coverage (failure cases documented)
  - Example quality (inputs AND outputs present)

Optionally uses AI analysis (-UseAI) for semantic quality scoring.

.PARAMETER SearchGlob
Glob for SKILL.md files. Default: "**/SKILL.md"

.PARAMETER UseAI
If set, invokes AI analysis for semantic quality scoring.

.OUTPUTS
Standard maintenance result object.
#>

param(
    [string]$SearchGlob = "**/SKILL.md",
    [switch]$UseAI
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Continue"

$ScriptDir = $PSScriptRoot
. (Join-Path $ScriptDir "..\common\MaintenanceUtil.ps1")

$result = New-MaintenanceResult -CheckId "D3" -Name "SKILL AI Instruction Quality"
$repoRoot = Get-RepositoryRoot

$maxAmbiguity = Get-MaintenanceThreshold -Section "AIQuality" -Key "MaxAmbiguityWords" -Default 3
$ambiguityWords = @("maybe", "could", "might", "probably", "sometimes", "possibly", "perhaps", "occasionally", "try to", "attempt to")

$skillFiles = Get-ChildItem -Path $repoRoot -Filter "SKILL.md" -Recurse -File `
    | Where-Object { $_.FullName -notmatch "target[\\/]" }

if ($skillFiles.Count -eq 0) {
    $result.Status = "skipped"
    $result.Details = "No SKILL.md files found"
    $result
    return
}

foreach ($file in $skillFiles) {
    $relPath = $file.FullName.Replace($repoRoot, "").TrimStart("\", "/")
    $content = Get-Content $file.FullName -Raw -Encoding UTF8
    $bodyContent = $content -replace '^---[\s\S]*?---', ''  # Strip frontmatter
    $lower = $bodyContent.ToLower()

    $score = 0
    $maxScore = 5
    $issues = @()

    # 1. Ambiguity check
    $ambiguityCount = 0
    foreach ($word in $ambiguityWords) {
        $matches = [regex]::Matches($lower, "\b$word\b")
        $ambiguityCount += $matches.Count
    }
    if ($ambiguityCount -le $maxAmbiguity) {
        $score++
    }
    else {
        $issues += "High ambiguity: $ambiguityCount ambiguous words (max $maxAmbiguity)"
    }

    # 2. Actionability (Use when... pattern)
    if ($lower -match 'use when') {
        $score++
    }
    else {
        $issues += "Missing 'Use when...' pattern in description"
    }

    # 3. Parameter clarity
    if ($bodyContent -match '\|.*Parameter.*\|.*Type.*\|.*Required.*\|' -or
        $bodyContent -match 'Parameters?\s*\n[-*]') {
        $score++
    }
    else {
        $issues += "Parameters not documented in clear table or list format"
    }

    # 4. Error coverage
    if ($lower -match 'error handling|error case|failure case|on failure|on error') {
        $score++
    }
    else {
        $issues += "Error handling not documented"
    }

    # 5. Example quality (has code block AND expected output)
    if ($bodyContent -match '```' -and ($lower -match 'return|result|output|expect|yields|produces')) {
        $score++
    }
    else {
        $issues += "Examples missing expected output or return value"
    }

    # ── If AI mode, perform semantic analysis ──
    if ($UseAI) {
        # Placeholder for AI invocation — would call Invoke-AiAnalysis
        # $aiResult = Invoke-AiAnalysis -Prompt "Rate this SKILL.md..."
        # For now, note it as skipped
    }

    $pct = [math]::Round($score / $maxScore * 100)
    $status = if ($pct -ge 60) { "passed" } else { "failed" }
    $message = "Score ${score}/${maxScore} (${pct}%)"
    if ($issues.Count -gt 0) {
        $message += " — issues: $($issues -join '; ')"
    }

    Add-MaintenanceResult -Result $result -Item $relPath -Status $status -Message $message
}

Set-MaintenanceResultSummary -Result $result
$result
