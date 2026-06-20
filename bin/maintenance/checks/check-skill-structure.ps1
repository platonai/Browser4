# ═══════════════════════════════════════════════════════════════════
# CROSS-PLATFORM: This script must run on Linux, macOS, and Windows.
# ═══════════════════════════════════════════════════════════════════

<#
.SYNOPSIS
D2 — SKILL structure compliance: validates SKILL.md sections.

.DESCRIPTION
Checks that every SKILL.md file has the required sections per the
skill template defined in browser4-agentic/.../skills/README.md:
  - Description
  - Dependencies
  - Parameters (or Parameters table)
  - Return Value
  - Usage Examples
  - Error Handling

.PARAMETER SearchGlob
Glob for SKILL.md files. Default: "**/SKILL.md"

.PARAMETER RequiredSections
Array of required section headers. Default includes the 6 standard sections.

.OUTPUTS
Standard maintenance result object.
#>

param(
    [string]$SearchGlob = "**/SKILL.md",
    [string[]]$RequiredSections = @("## Description", "## Dependencies", "## Parameters", "## Return Value", "## Usage Examples", "## Error Handling"),
    [string[]]$ExcludePatterns = @("target", "node_modules")
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Continue"

$ScriptDir = $PSScriptRoot
. (Join-Path $ScriptDir "..\common\MaintenanceUtil.ps1")

$result = New-MaintenanceResult -CheckId "D2" -Name "SKILL Structure Compliance"
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
    $content = Get-Content $file.FullName -Raw -Encoding UTF8

    $missingSections = @()
    foreach ($section in $RequiredSections) {
        # Check for the section header (with ## prefix) or alternative wording
        if ($content -notmatch [regex]::Escape($section)) {
            $altPattern = $section -replace '^## ', '## '
            if ($content -notmatch $altPattern) {
                $missingSections += ($section -replace '^## ', '')
            }
        }
    }

    if ($missingSections.Count -eq 0) {
        Add-MaintenanceResult -Result $result -Item $relPath -Status "passed" -Message "All required sections present"
    }
    else {
        Add-MaintenanceResult -Result $result -Item $relPath -Status "failed" -Message "Missing sections: $($missingSections -join ', ')"
    }
}

Set-MaintenanceResultSummary -Result $result
$result
