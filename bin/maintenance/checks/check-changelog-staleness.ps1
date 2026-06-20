# ═══════════════════════════════════════════════════════════════════
# CROSS-PLATFORM: This script must run on Linux, macOS, and Windows.
# ═══════════════════════════════════════════════════════════════════

<#
.SYNOPSIS
E2 — Changelog staleness: detects unreleased changes not in changelog.

.DESCRIPTION
Checks if there are meaningful commits since the last release tag that
have not been documented in the changelog. Flags undocumented changes.

.PARAMETER LastReleaseTag
Git tag of the last release. If not specified, auto-detects.

.PARAMETER ChangelogPath
Path to changelog file. Auto-detects common names.

.OUTPUTS
Standard maintenance result object.
#>

param(
    [string]$LastReleaseTag = "",
    [string]$ChangelogPath = ""
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Continue"

$ScriptDir = $PSScriptRoot
. (Join-Path $ScriptDir "..\common\MaintenanceUtil.ps1")

$result = New-MaintenanceResult -CheckId "E2" -Name "Changelog Staleness"
$repoRoot = Get-RepositoryRoot

# ── Auto-detect changelog ──
if (-not $ChangelogPath) {
    $candidates = @("CHANGELOG.md", "CHANGES.md", "HISTORY.md", "RELEASE_NOTES.md")
    foreach ($c in $candidates) {
        $p = Join-Path $repoRoot $c
        if (Test-Path $p) {
            $ChangelogPath = $p
            break
        }
    }
}

if (-not $ChangelogPath -or -not (Test-Path $ChangelogPath)) {
    $result.Status = "skipped"
    $result.Details = "No changelog file found"
    Add-MaintenanceResult -Result $result -Item "Changelog" -Status "skipped" -Message "File not found"
    $result
    return
}

# ── Auto-detect last release tag ──
if (-not $LastReleaseTag) {
    try {
        $LastReleaseTag = & git describe --tags --abbrev=0 2>$null
        if ($LASTEXITCODE -ne 0) { $LastReleaseTag = "" }
    }
    catch { }
}

if (-not $LastReleaseTag) {
    $result.Status = "skipped"
    $result.Details = "No release tags found"
    Add-MaintenanceResult -Result $result -Item "Git tags" -Status "skipped" -Message "No tags found"
    $result
    return
}

# ── Get commits since last tag ──
$commitLog = & git log --oneline "${LastReleaseTag}..HEAD" 2>$null
$commitCount = ($commitLog | Where-Object { $_ -notmatch '^\s*$' }).Count

# ── Check if changelog mentions recent changes ──
$clContent = Get-Content $ChangelogPath -Raw -Encoding UTF8
$clMTime = (Get-Item $ChangelogPath).LastWriteTime
$lastTagDate = & git log -1 --format="%ai" $LastReleaseTag 2>$null
$lastTagDate = if ($lastTagDate) { [DateTime]::Parse($lastTagDate.Trim()) } else { [DateTime]::MinValue }

$clHasVersion = $clContent -match [regex]::Escape($LastReleaseTag.TrimStart('v'))

Add-MaintenanceResult -Result $result -Item "Last release" -Status "passed" -Message $LastReleaseTag
Add-MaintenanceResult -Result $result -Item "Commits since tag" -Status $(if ($commitCount -eq 0) { "passed" } else { "failed" }) -Message "$commitCount commits"

if ($clHasVersion) {
    Add-MaintenanceResult -Result $result -Item "Changelog has version" -Status "passed" -Message "Version $LastReleaseTag found"
}
else {
    Add-MaintenanceResult -Result $result -Item "Changelog has version" -Status "failed" -Message "Version $LastReleaseTag not found in changelog"
}

if ($clMTime -lt $lastTagDate -and $commitCount -gt 0) {
    Add-MaintenanceResult -Result $result -Item "Changelog age" -Status "failed" -Message "Changelog last modified $clMTime, but last release was $lastTagDate"
}
else {
    Add-MaintenanceResult -Result $result -Item "Changelog age" -Status "passed" -Message "Last modified: $clMTime"
}

Set-MaintenanceResultSummary -Result $result
$result
