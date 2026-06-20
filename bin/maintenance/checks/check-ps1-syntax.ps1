# ═══════════════════════════════════════════════════════════════════
# CROSS-PLATFORM: This script must run on Linux, macOS, and Windows.
# ═══════════════════════════════════════════════════════════════════

<#
.SYNOPSIS
G2 — PS1 syntax validation: parses all .ps1 scripts for syntax errors.

.DESCRIPTION
Uses the PowerShell language parser to validate every .ps1 file in the
repository. This catches syntax errors early — before they cause runtime
failures. Mirrors the validation step already in ci.yml (lines 135-170).

.PARAMETER SearchGlob
Glob pattern to find .ps1 files. Default: "**/*.ps1"

.PARAMETER ExcludePatterns
Patterns to exclude. Default: "target", "node_modules", ".git"

.OUTPUTS
Standard maintenance result object.
#>

param(
    [string]$SearchGlob = "**/*.ps1",
    [string[]]$ExcludePatterns = @("target", "node_modules", ".git")
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Continue"

$ScriptDir = $PSScriptRoot
. (Join-Path $ScriptDir "..\common\MaintenanceUtil.ps1")

$result = New-MaintenanceResult -CheckId "G2" -Name "PS1 Syntax Validation"
$repoRoot = Get-RepositoryRoot

$psFiles = Get-ChildItem -Path $repoRoot -Filter "*.ps1" -Recurse -File `
    | Where-Object {
        $full = $_.FullName
        foreach ($pat in $ExcludePatterns) {
            if ($full -match $pat) { return $false }
        }
        return $true
    }

if ($psFiles.Count -eq 0) {
    $result.Status = "skipped"
    $result.Details = "No .ps1 files found"
    $result
    return
}

foreach ($file in $psFiles) {
    $relPath = $file.FullName.Replace($repoRoot, "").TrimStart("\", "/")
    $tokens = $null
    $parseErrors = $null
    $null = [System.Management.Automation.Language.Parser]::ParseFile(
        $file.FullName, [ref]$tokens, [ref]$parseErrors
    )

    if ($parseErrors.Count -eq 0) {
        Add-MaintenanceResult -Result $result -Item $relPath -Status "passed"
    }
    else {
        $errorMessages = $parseErrors | ForEach-Object { $_.Message }
        Add-MaintenanceResult -Result $result -Item $relPath -Status "failed" -Message ($errorMessages -join "; ")
    }
}

Set-MaintenanceResultSummary -Result $result

# ── Summary line ──
$passed  = ($result.Results | Where-Object { $_.Status -eq "passed" }).Count
$failed  = ($result.Results | Where-Object { $_.Status -eq "failed" }).Count
Write-Host "PS1 Syntax: $passed passed, $failed failed — $($result.Results.Count) total scripts"

$result
