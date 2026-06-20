# ═══════════════════════════════════════════════════════════════════
# CROSS-PLATFORM: This script must run on Linux, macOS, and Windows.
# ═══════════════════════════════════════════════════════════════════

<#
.SYNOPSIS
D1 — SKILL frontmatter validation: validates YAML frontmatter in all SKILL.md files.

.DESCRIPTION
Scans every SKILL.md file in the repository (excluding target/ and node_modules/),
parses YAML frontmatter between --- delimiters, and validates:
  1. Frontmatter delimiters must exist
  2. Required fields: name (non-empty), description (non-empty)
  3. Description must not exceed the max character limit (default 200)
  4. Recommended fields: tags

.PARAMETER SearchGlob
Glob pattern to find SKILL.md files. Default: "**/SKILL.md"

.PARAMETER ExcludePatterns
Patterns to exclude. Default: "target", "node_modules"

.PARAMETER MaxDescriptionChars
Maximum allowed description length. Default from thresholds.

.OUTPUTS
Standard maintenance result object.
#>

param(
    [string]$SearchGlob = "**/SKILL.md",
    [string[]]$ExcludePatterns = @("target", "node_modules"),
    [int]$MaxDescriptionChars = 0
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Continue"

$ScriptDir = $PSScriptRoot
. (Join-Path $ScriptDir "..\common\MaintenanceUtil.ps1")

$result = New-MaintenanceResult -CheckId "D1" -Name "SKILL Frontmatter Validation"

if ($MaxDescriptionChars -le 0) {
    $MaxDescriptionChars = Get-MaintenanceThreshold -Section "Documentation" -Key "SkillMaxDescriptionChars" -Default 200
}

$repoRoot = Get-RepositoryRoot
$skillFiles = Get-ChildItem -Path $repoRoot -Filter "SKILL.md" -Recurse -File `
    | Where-Object {
        $full = $_.FullName
        foreach ($pat in $ExcludePatterns) {
            if ($full -match $pat) { return $false }
        }
        return $true
    }

if ($skillFiles.Count -eq 0) {
    $result.Status = "skipped"
    $result.Details = "No SKILL.md files found"
    $result
    return
}

foreach ($file in $skillFiles) {
    $relPath = $file.FullName.Replace($repoRoot, "").TrimStart("\", "/")
    $content = Get-Content -Path $file.FullName -Raw -Encoding UTF8
    $lines = $content -split "`r?`n"

    # ── Check for YAML frontmatter delimiters ──
    if ($lines.Count -lt 3 -or $lines[0] -ne "---") {
        Add-MaintenanceResult -Result $result -Item $relPath -Status "failed" -Message "Missing YAML frontmatter (no opening ---)"
        continue
    }

    $endIdx = -1
    for ($i = 1; $i -lt $lines.Count; $i++) {
        if ($lines[$i] -eq "---") {
            $endIdx = $i
            break
        }
    }

    if ($endIdx -eq -1) {
        Add-MaintenanceResult -Result $result -Item $relPath -Status "failed" -Message "Missing closing --- in frontmatter"
        continue
    }

    # ── Parse frontmatter lines ──
    $fm = @{}
    for ($i = 1; $i -lt $endIdx; $i++) {
        $line = $lines[$i]
        if ($line -match '^(\w[\w-]*):\s*(.*)') {
            $key = $matches[1]
            $val = $matches[2].Trim()
            $fm[$key] = $val
        }
    }

    # ── Validate required fields ──
    $issues = @()

    if (-not $fm.ContainsKey("name") -or [string]::IsNullOrWhiteSpace($fm["name"])) {
        $issues += "Missing required field: name"
    }

    if (-not $fm.ContainsKey("description") -or [string]::IsNullOrWhiteSpace($fm["description"])) {
        $issues += "Missing required field: description"
    }
    elseif ($fm["description"].Length -gt $MaxDescriptionChars) {
        $issues += "Description too long: $($fm["description"].Length) chars (max $MaxDescriptionChars)"
    }

    # ── Check recommended fields ──
    if (-not $fm.ContainsKey("tags") -or [string]::IsNullOrWhiteSpace($fm["tags"])) {
        $issues += "Missing recommended field: tags"
    }

    if ($issues.Count -eq 0) {
        Add-MaintenanceResult -Result $result -Item $relPath -Status "passed" -Message "Valid frontmatter"
    }
    else {
        Add-MaintenanceResult -Result $result -Item $relPath -Status "failed" -Message ($issues -join "; ")
    }
}

Set-MaintenanceResultSummary -Result $result
$result
